-- Asynchronous processing jobs and the artifacts they produce.
--
-- Job state machine (enforced by processing_job_guard):
--   QUEUED  -> RUNNING | CANCELLED
--   RUNNING -> SUCCEEDED | FAILED | CANCELLED
--   FAILED  -> QUEUED   (retry; retry_count must increase by exactly 1 and stay <= max_retries)
--   SUCCEEDED and CANCELLED are terminal.
-- Jobs are never deleted; they are the processing history.

CREATE TABLE processing_job (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id  uuid NOT NULL,
    venue_id         uuid NOT NULL,
    scan_id          uuid NOT NULL,
    scan_version_id  uuid,
    stage            text NOT NULL CHECK (stage IN (
        'MEDIA_FILTER', 'POSE_ESTIMATION', 'SPLAT_TRAINING', 'GEOMETRY_CLEANUP',
        'SEGMENTATION', 'OBJECT_DETECTION', 'NAVMESH_GENERATION')),
    status           text NOT NULL DEFAULT 'QUEUED'
                     CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    retry_count      integer NOT NULL DEFAULT 0 CHECK (retry_count >= 0),
    max_retries      integer NOT NULL DEFAULT 3 CHECK (max_retries >= 0),
    queued_at        timestamptz NOT NULL DEFAULT now(),
    started_at       timestamptz,
    finished_at      timestamptz,
    error_code       text,
    error_message    text,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (scan_id, venue_id) REFERENCES scan (id, venue_id) ON DELETE RESTRICT,
    FOREIGN KEY (venue_id, organization_id) REFERENCES venue (id, organization_id) ON DELETE RESTRICT,
    FOREIGN KEY (scan_version_id, scan_id) REFERENCES scan_version (id, scan_id) ON DELETE RESTRICT,
    CHECK (retry_count <= max_retries),
    -- Status / timestamp / error consistency.
    CHECK (status <> 'QUEUED'    OR (started_at IS NULL AND finished_at IS NULL)),
    CHECK (status <> 'RUNNING'   OR (started_at IS NOT NULL AND finished_at IS NULL)),
    CHECK (status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELLED') OR finished_at IS NOT NULL),
    CHECK (status <> 'FAILED'    OR (error_code IS NOT NULL AND error_message IS NOT NULL)),
    CHECK (status <> 'SUCCEEDED' OR (error_code IS NULL AND error_message IS NULL)),
    UNIQUE (id, venue_id)
);

CREATE FUNCTION processing_job_guard() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'processing_job % cannot be deleted', OLD.id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF NEW.id <> OLD.id OR NEW.organization_id <> OLD.organization_id OR NEW.venue_id <> OLD.venue_id
       OR NEW.scan_id <> OLD.scan_id OR NEW.stage <> OLD.stage
       OR NEW.scan_version_id IS DISTINCT FROM OLD.scan_version_id THEN
        RAISE EXCEPTION 'processing_job % identity columns are immutable', OLD.id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF OLD.status IN ('SUCCEEDED', 'CANCELLED') THEN
        RAISE EXCEPTION 'processing_job % is terminal (%)', OLD.id, OLD.status
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF NEW.status = OLD.status THEN
        IF OLD.status = 'FAILED' THEN
            RAISE EXCEPTION 'processing_job % is FAILED; only a retry (FAILED -> QUEUED) is allowed', OLD.id
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
        RETURN NEW; -- metadata update while QUEUED or RUNNING
    END IF;

    IF NOT ((OLD.status = 'QUEUED'  AND NEW.status IN ('RUNNING', 'CANCELLED'))
         OR (OLD.status = 'RUNNING' AND NEW.status IN ('SUCCEEDED', 'FAILED', 'CANCELLED'))
         OR (OLD.status = 'FAILED'  AND NEW.status = 'QUEUED')) THEN
        RAISE EXCEPTION 'invalid processing_job transition % -> %', OLD.status, NEW.status
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF OLD.status = 'FAILED' THEN -- retry
        IF NEW.retry_count <> OLD.retry_count + 1 THEN
            RAISE EXCEPTION 'retry must increment retry_count by 1 (was %, got %)', OLD.retry_count, NEW.retry_count
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
    ELSIF NEW.retry_count <> OLD.retry_count THEN
        RAISE EXCEPTION 'retry_count may only change on retry'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER processing_job_guard BEFORE UPDATE OR DELETE ON processing_job
    FOR EACH ROW EXECUTE FUNCTION processing_job_guard();
CREATE TRIGGER processing_job_updated BEFORE UPDATE ON processing_job
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Workers claim the oldest queued job of their stage.
CREATE INDEX processing_job_queue_idx ON processing_job (stage, queued_at) WHERE status = 'QUEUED';
-- Never two active jobs for the same stage of the same scan.
CREATE UNIQUE INDEX processing_job_one_active_idx ON processing_job (scan_id, stage)
    WHERE status IN ('QUEUED', 'RUNNING');
CREATE INDEX processing_job_scan_idx ON processing_job (scan_id, created_at DESC);

-- Artifacts are write-once records of objects stored in MinIO.
CREATE TABLE processing_artifact (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL,
    venue_id        uuid NOT NULL,
    scan_id         uuid NOT NULL,
    scan_version_id uuid,
    job_id          uuid NOT NULL,
    stage           text NOT NULL,
    bucket          text NOT NULL CHECK (length(bucket) > 0),
    object_key      text NOT NULL CHECK (length(object_key) > 0),
    checksum_sha256 text NOT NULL CHECK (checksum_sha256 ~ '^[0-9a-f]{64}$'),
    content_type    text NOT NULL CHECK (length(content_type) > 0),
    size_bytes      bigint NOT NULL CHECK (size_bytes >= 0),
    created_at      timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (job_id, venue_id) REFERENCES processing_job (id, venue_id) ON DELETE RESTRICT,
    FOREIGN KEY (scan_id, venue_id) REFERENCES scan (id, venue_id) ON DELETE RESTRICT,
    FOREIGN KEY (venue_id, organization_id) REFERENCES venue (id, organization_id) ON DELETE RESTRICT,
    FOREIGN KEY (scan_version_id, scan_id) REFERENCES scan_version (id, scan_id) ON DELETE RESTRICT,
    -- One row per stored object: a key can never be registered twice or silently re-pointed.
    UNIQUE (bucket, object_key)
);

CREATE FUNCTION processing_artifact_guard() RETURNS trigger AS $$
DECLARE
    v_status text;
BEGIN
    IF TG_OP IN ('UPDATE', 'DELETE') THEN
        RAISE EXCEPTION 'processing_artifact is write-once (% rejected); write a new object key instead', TG_OP
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF NEW.scan_version_id IS NOT NULL THEN
        SELECT status INTO v_status FROM scan_version WHERE id = NEW.scan_version_id;
        IF v_status = 'FINALIZED' THEN
            RAISE EXCEPTION 'scan_version % is finalized; artifacts cannot be added', NEW.scan_version_id
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER processing_artifact_guard BEFORE INSERT OR UPDATE OR DELETE ON processing_artifact
    FOR EACH ROW EXECUTE FUNCTION processing_artifact_guard();

CREATE INDEX processing_artifact_version_idx ON processing_artifact (scan_version_id, stage);
CREATE INDEX processing_artifact_job_idx ON processing_artifact (job_id);
