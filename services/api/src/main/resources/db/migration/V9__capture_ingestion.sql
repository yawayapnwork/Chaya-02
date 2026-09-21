-- Capture ingestion: capture session lifecycle, raw media records and resumable upload parts.
--
-- The database holds metadata and object references only. Raw bytes live in MinIO under
-- server-generated keys; no client-supplied name ever becomes part of an object key.

-- 1. capture_session: replace the placeholder states with the real lifecycle. ------------------
DO $$
DECLARE c record;
BEGIN
    FOR c IN SELECT conname FROM pg_constraint
              WHERE conrelid = 'capture_session'::regclass AND contype = 'c'
                AND pg_get_constraintdef(oid) LIKE '%IN_PROGRESS%' LOOP
        EXECUTE format('ALTER TABLE capture_session DROP CONSTRAINT %I', c.conname);
    END LOOP;
END $$;

ALTER TABLE capture_session ALTER COLUMN status SET DEFAULT 'CREATED';
UPDATE capture_session SET status = 'CREATED' WHERE status = 'IN_PROGRESS';
UPDATE capture_session SET status = 'FAILED' WHERE status = 'ABANDONED';

ALTER TABLE capture_session
    ADD CONSTRAINT capture_session_status_check CHECK (status IN (
        'CREATED', 'UPLOADING', 'UPLOADED', 'VALIDATING', 'READY_FOR_PROCESSING',
        'PROCESSING', 'COMPLETED', 'FAILED')),
    ADD COLUMN device           jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(device) = 'object'),
    ADD COLUMN duration_seconds numeric(10, 2) CHECK (duration_seconds IS NULL OR duration_seconds >= 0),
    -- Capture quality assessment is a later pipeline feature; until it exists the state is honest.
    ADD COLUMN quality_state    text NOT NULL DEFAULT 'NOT_ASSESSED'
                                CHECK (quality_state IN ('NOT_ASSESSED', 'PENDING', 'PASSED', 'FAILED')),
    ADD COLUMN failure_code     text,
    ADD COLUMN failure_message  text,
    ADD CONSTRAINT capture_session_failure_check CHECK (status <> 'FAILED' OR failure_code IS NOT NULL),
    ADD CONSTRAINT capture_session_time_check CHECK (ended_at IS NULL OR ended_at >= started_at);

-- Lifecycle (enforced here, mirrored by CaptureStatus in Java):
--   CREATED -> UPLOADING -> UPLOADED -> VALIDATING -> READY_FOR_PROCESSING -> PROCESSING -> COMPLETED
--   any non-terminal state -> FAILED.  COMPLETED and FAILED are terminal.
CREATE FUNCTION capture_session_guard() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'capture_session % cannot be deleted', OLD.id USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF NEW.id <> OLD.id OR NEW.organization_id <> OLD.organization_id OR NEW.venue_id <> OLD.venue_id
       OR NEW.floor_id IS DISTINCT FROM OLD.floor_id OR NEW.operator_id <> OLD.operator_id THEN
        RAISE EXCEPTION 'capture_session % identity columns are immutable', OLD.id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF NEW.status = OLD.status THEN
        RETURN NEW;
    END IF;
    IF NOT ((OLD.status = 'CREATED'             AND NEW.status IN ('UPLOADING', 'FAILED'))
         OR (OLD.status = 'UPLOADING'           AND NEW.status IN ('UPLOADED', 'FAILED'))
         OR (OLD.status = 'UPLOADED'            AND NEW.status IN ('VALIDATING', 'FAILED'))
         OR (OLD.status = 'VALIDATING'          AND NEW.status IN ('READY_FOR_PROCESSING', 'FAILED'))
         OR (OLD.status = 'READY_FOR_PROCESSING' AND NEW.status IN ('PROCESSING', 'FAILED'))
         OR (OLD.status = 'PROCESSING'          AND NEW.status IN ('COMPLETED', 'FAILED'))) THEN
        RAISE EXCEPTION 'invalid capture_session transition % -> %', OLD.status, NEW.status
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER capture_session_guard BEFORE UPDATE OR DELETE ON capture_session
    FOR EACH ROW EXECUTE FUNCTION capture_session_guard();

-- A capture session yields at most one scan.
CREATE UNIQUE INDEX scan_one_per_capture_idx ON scan (capture_session_id);

-- 2. capture_media: one row per uploaded raw file. -------------------------------------------
CREATE TABLE capture_media (
    id                    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id       uuid NOT NULL,
    venue_id              uuid NOT NULL,
    capture_session_id    uuid NOT NULL,
    kind                  text NOT NULL CHECK (kind IN ('VIDEO', 'IMAGE', 'METADATA')),
    -- PENDING: parts still arriving. VALIDATING: assembled, checks running. ACCEPTED / REJECTED are
    -- terminal. QUARANTINED: could not be validated (e.g. malware scanner unreachable); it is not
    -- usable and may be re-validated.
    status                text NOT NULL DEFAULT 'PENDING'
                          CHECK (status IN ('PENDING', 'VALIDATING', 'ACCEPTED', 'REJECTED', 'QUARANTINED')),
    original_filename     text NOT NULL CHECK (length(original_filename) BETWEEN 1 AND 255),
    claimed_content_type  text NOT NULL,
    detected_content_type text,
    declared_size_bytes   bigint NOT NULL CHECK (declared_size_bytes > 0),
    declared_sha256       char(64) NOT NULL CHECK (declared_sha256 ~ '^[0-9a-f]{64}$'),
    verified_sha256       char(64) CHECK (verified_sha256 ~ '^[0-9a-f]{64}$'),
    bucket                text NOT NULL,
    object_key            text NOT NULL,
    upload_id             text,
    part_size_bytes       integer NOT NULL CHECK (part_size_bytes > 0),
    total_parts           integer NOT NULL CHECK (total_parts BETWEEN 1 AND 10000),
    assembled             boolean NOT NULL DEFAULT false,
    scan_result           text,
    rejection_code        text,
    rejection_message     text,
    validated_at          timestamptz,
    created_at            timestamptz NOT NULL DEFAULT now(),
    updated_at            timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (capture_session_id, venue_id) REFERENCES capture_session (id, venue_id) ON DELETE RESTRICT,
    FOREIGN KEY (venue_id, organization_id) REFERENCES venue (id, organization_id) ON DELETE RESTRICT,
    CHECK (status <> 'ACCEPTED' OR (verified_sha256 IS NOT NULL AND detected_content_type IS NOT NULL AND scan_result = 'CLEAN')),
    CHECK (status NOT IN ('REJECTED', 'QUARANTINED') OR rejection_code IS NOT NULL),
    UNIQUE (bucket, object_key),
    UNIQUE (id, capture_session_id)
);

CREATE TABLE capture_media_part (
    media_id    uuid NOT NULL REFERENCES capture_media (id) ON DELETE RESTRICT,
    part_number integer NOT NULL CHECK (part_number BETWEEN 1 AND 10000),
    etag        text NOT NULL,
    size_bytes  integer NOT NULL CHECK (size_bytes > 0),
    sha256      char(64) NOT NULL,
    uploaded_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (media_id, part_number)
);

CREATE FUNCTION capture_media_guard() RETURNS trigger AS $$
DECLARE
    v_status text;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'capture_media % cannot be deleted', OLD.id USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF TG_OP = 'INSERT' THEN
        SELECT status INTO v_status FROM capture_session WHERE id = NEW.capture_session_id;
        IF v_status NOT IN ('CREATED', 'UPLOADING') THEN
            RAISE EXCEPTION 'capture_session % is % and no longer accepts media', NEW.capture_session_id, v_status
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.object_key <> OLD.object_key OR NEW.bucket <> OLD.bucket OR NEW.capture_session_id <> OLD.capture_session_id
       OR NEW.declared_sha256 <> OLD.declared_sha256 OR NEW.declared_size_bytes <> OLD.declared_size_bytes THEN
        RAISE EXCEPTION 'capture_media % identity columns are immutable', OLD.id USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF OLD.status IN ('ACCEPTED', 'REJECTED') THEN
        RAISE EXCEPTION 'capture_media % is terminal (%)', OLD.id, OLD.status USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF NEW.status <> OLD.status AND NOT (
           (OLD.status = 'PENDING'     AND NEW.status IN ('VALIDATING', 'REJECTED'))
        OR (OLD.status = 'VALIDATING'  AND NEW.status IN ('ACCEPTED', 'REJECTED', 'QUARANTINED'))
        OR (OLD.status = 'QUARANTINED' AND NEW.status IN ('VALIDATING', 'REJECTED'))) THEN
        RAISE EXCEPTION 'invalid capture_media transition % -> %', OLD.status, NEW.status
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER capture_media_guard BEFORE INSERT OR UPDATE OR DELETE ON capture_media
    FOR EACH ROW EXECUTE FUNCTION capture_media_guard();
CREATE TRIGGER capture_media_updated BEFORE UPDATE ON capture_media
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Access patterns: list a capture media, find media still being validated (restart recovery).
CREATE INDEX capture_media_session_idx ON capture_media (capture_session_id, created_at);
CREATE INDEX capture_media_validating_idx ON capture_media (created_at) WHERE status = 'VALIDATING';
