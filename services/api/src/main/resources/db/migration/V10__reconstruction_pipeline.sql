-- Reconstruction pipeline: durable run and per-stage records, worker leases, artifact metadata.
--
-- Spring Boot is the control plane: it owns this state. The Python worker only claims a stage job,
-- executes it, and reports one structured stage record. Nothing here is written by a simulator.

-- 1. More stages. Legacy names stay valid so existing rows and clients keep working.
ALTER TABLE processing_job DROP CONSTRAINT processing_job_stage_check;
ALTER TABLE processing_job ADD CONSTRAINT processing_job_stage_check CHECK (stage IN (
    -- reconstruction pipeline (in execution order)
    'INPUT_VALIDATION', 'FFMPEG_PREPROCESS', 'FRAME_QUALITY_FILTER', 'PRIVACY_PREPROCESS',
    'POSE_ESTIMATION', 'SPLAT_RECONSTRUCTION', 'SEMANTIC_SEGMENTATION', 'GEOMETRIC_CLEANUP',
    'PLANE_FITTING', 'ARTIFACT_GENERATION', 'NAVIGATION_BAKING', 'SEMANTIC_INDEXING',
    -- legacy
    'MEDIA_FILTER', 'SPLAT_TRAINING', 'GEOMETRY_CLEANUP', 'SEGMENTATION', 'OBJECT_DETECTION', 'NAVMESH_GENERATION'));

-- 2. pipeline_run: one per scan.
CREATE TABLE pipeline_run (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     uuid NOT NULL,
    venue_id            uuid NOT NULL,
    scan_id             uuid NOT NULL,
    capture_session_id  uuid NOT NULL,
    -- RUNNING -> SUCCEEDED | PARTIAL | FAILED | CANCELLED ; FAILED -> RUNNING (retry). Others are terminal.
    status              text NOT NULL DEFAULT 'RUNNING'
                        CHECK (status IN ('RUNNING', 'SUCCEEDED', 'PARTIAL', 'FAILED', 'CANCELLED')),
    -- FINAL only for a fully completed run; PARTIAL only for an explicitly time-boxed result that
    -- already contains a reconstruction. NULL for every other state.
    quality             text CHECK (quality IN ('FINAL', 'PARTIAL')),
    stages              text[] NOT NULL,                 -- ordered plan for this run
    privacy_enabled     boolean NOT NULL DEFAULT true,
    time_budget_seconds integer NOT NULL CHECK (time_budget_seconds > 0),
    started_at          timestamptz NOT NULL DEFAULT now(),
    deadline_at         timestamptz NOT NULL,
    finished_at         timestamptz,
    failure_stage       text,
    failure_code        text,
    failure_message     text,
    requested_by        text NOT NULL,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (scan_id, venue_id) REFERENCES scan (id, venue_id) ON DELETE RESTRICT,
    FOREIGN KEY (capture_session_id, venue_id) REFERENCES capture_session (id, venue_id) ON DELETE RESTRICT,
    FOREIGN KEY (venue_id, organization_id) REFERENCES venue (id, organization_id) ON DELETE RESTRICT,
    CHECK (status <> 'SUCCEEDED' OR quality IS NOT DISTINCT FROM 'FINAL'),
    CHECK (status <> 'PARTIAL' OR quality IS NOT DISTINCT FROM 'PARTIAL'),
    CHECK (status IN ('SUCCEEDED', 'PARTIAL') OR quality IS NULL),
    CHECK ((status = 'RUNNING') = (finished_at IS NULL)),
    CHECK (status NOT IN ('FAILED', 'PARTIAL') OR failure_code IS NOT NULL),
    UNIQUE (scan_id),
    UNIQUE (id, venue_id)
);

CREATE FUNCTION pipeline_run_guard() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'pipeline_run % cannot be deleted', OLD.id USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF NEW.id <> OLD.id OR NEW.organization_id <> OLD.organization_id OR NEW.venue_id <> OLD.venue_id
       OR NEW.scan_id <> OLD.scan_id OR NEW.stages <> OLD.stages OR NEW.privacy_enabled <> OLD.privacy_enabled THEN
        RAISE EXCEPTION 'pipeline_run % identity and plan are immutable', OLD.id USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF NEW.status <> OLD.status AND NOT (
           (OLD.status = 'RUNNING' AND NEW.status IN ('SUCCEEDED', 'PARTIAL', 'FAILED', 'CANCELLED'))
        OR (OLD.status = 'FAILED'  AND NEW.status = 'RUNNING')) THEN
        RAISE EXCEPTION 'invalid pipeline_run transition % -> %', OLD.status, NEW.status
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF NEW.status = OLD.status AND OLD.status IN ('SUCCEEDED', 'PARTIAL', 'CANCELLED') THEN
        RAISE EXCEPTION 'pipeline_run % is terminal (%)', OLD.id, OLD.status USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER pipeline_run_guard BEFORE UPDATE OR DELETE ON pipeline_run
    FOR EACH ROW EXECUTE FUNCTION pipeline_run_guard();
CREATE TRIGGER pipeline_run_updated BEFORE UPDATE ON pipeline_run
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE INDEX pipeline_run_active_idx ON pipeline_run (deadline_at) WHERE status = 'RUNNING';
CREATE INDEX pipeline_run_venue_idx ON pipeline_run (venue_id, created_at DESC);

-- 3. Jobs belong to a run and carry a worker lease.
ALTER TABLE processing_job
    ADD COLUMN run_id           uuid REFERENCES pipeline_run (id) ON DELETE RESTRICT,
    ADD COLUMN worker_id        text,
    ADD COLUMN lease_expires_at timestamptz;
CREATE INDEX processing_job_run_idx ON processing_job (run_id, created_at);
CREATE INDEX processing_job_lease_idx ON processing_job (lease_expires_at) WHERE status = 'RUNNING';

-- 4. pipeline_stage_run: the immutable record of one execution attempt of one stage.
CREATE TABLE pipeline_stage_run (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     uuid NOT NULL,
    venue_id            uuid NOT NULL,
    run_id              uuid NOT NULL REFERENCES pipeline_run (id) ON DELETE RESTRICT,
    job_id              uuid NOT NULL REFERENCES processing_job (id) ON DELETE RESTRICT,
    stage               text NOT NULL,
    attempt             integer NOT NULL CHECK (attempt >= 1),
    status              text NOT NULL CHECK (status IN ('SUCCEEDED', 'FAILED')),
    command             jsonb NOT NULL CHECK (jsonb_typeof(command) = 'object'),   -- argv and configuration actually used
    input_artifact_ids  uuid[] NOT NULL DEFAULT '{}',
    started_at          timestamptz NOT NULL,
    finished_at         timestamptz NOT NULL,
    exit_status         integer,                                                   -- of the external process, when one ran
    stdout_artifact_id  uuid,
    stderr_artifact_id  uuid,
    output_sha256       char(64) CHECK (output_sha256 ~ '^[0-9a-f]{64}$'),        -- checksum over the output artifact checksums
    error_code          text,
    error_message       text,
    error_details       jsonb CHECK (error_details IS NULL OR jsonb_typeof(error_details) = 'object'),
    worker_id           text,
    created_at          timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (venue_id, organization_id) REFERENCES venue (id, organization_id) ON DELETE RESTRICT,
    CHECK (finished_at >= started_at),
    CHECK (status <> 'FAILED' OR (error_code IS NOT NULL AND error_message IS NOT NULL)),
    CHECK (status <> 'SUCCEEDED' OR (error_code IS NULL AND error_message IS NULL)),
    UNIQUE (job_id, attempt)
);

CREATE FUNCTION immutable_record() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '% is write-once (% rejected)', TG_TABLE_NAME, TG_OP USING ERRCODE = 'integrity_constraint_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER pipeline_stage_run_immutable BEFORE UPDATE OR DELETE ON pipeline_stage_run
    FOR EACH ROW EXECUTE FUNCTION immutable_record();
CREATE INDEX pipeline_stage_run_run_idx ON pipeline_stage_run (run_id, created_at);

-- 5. Artifact metadata for pipeline outputs (the table stays write-once).
ALTER TABLE processing_artifact
    ADD COLUMN kind          text,
    ADD COLUMN stage_run_id  uuid REFERENCES pipeline_stage_run (id) ON DELETE RESTRICT,
    ADD COLUMN contains_pii  boolean NOT NULL DEFAULT false,
    ADD COLUMN partial       boolean NOT NULL DEFAULT false;
CREATE INDEX processing_artifact_stage_run_idx ON processing_artifact (stage_run_id);
