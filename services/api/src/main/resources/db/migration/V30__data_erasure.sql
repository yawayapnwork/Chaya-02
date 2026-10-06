-- Data erasure (review E-1; docs/privacy-erasure.md) and complete PII staging cleanup (review S-7).
--
-- 1. The guards that make history immutable (artifacts, stage runs, jobs, runs, versions, frames, POI versions, anchor
--    poses, capture sessions and media, PII purge records) also made it impossible to honour a deletion request: a
--    capture of an indoor space contains people, and nothing could remove it, its frames or anything derived from it.
--    An erasure is the one sanctioned exception. dev.chaya.api.erasure.ErasureService sets the transaction-local flag
--    chaya.erasure = on around its own deletes, and only there; the guards' UPDATE/DELETE triggers do not fire while it is
--    set. INSERT guards always fire, and the audit log stays append-only without exception (an erasure is recorded in it).
--    The flag is SET LOCAL: it ends with the transaction, so no other work on the connection can inherit it.
--
-- 2. erasure_request is the durable record of an erasure: who asked for what, when the rows went, and whether every
--    object is gone yet. Rows are deleted in one transaction; objects cannot be part of it, so erasure_object lists every
--    key and prefix to delete, and ErasureObjectPurger (on the request, then every minute) deletes them until none
--    remains. Nothing in these tables is content: ids, server-generated object keys and counts only.
--
-- 3. pii_staging_sweep: S-7 purged only the PII objects a worker REGISTERED. A stage that failed, crashed, lost its lease
--    or had its report refused may have uploaded unanonymised frames under its pii/ prefix that no row names. Once a run
--    no longer needs its staging, the sweep now lists the run's prefixes and deletes every */pii/* object there, and
--    records that it did.

CREATE FUNCTION chaya_erasure_active() RETURNS boolean AS $$
    SELECT coalesce(current_setting('chaya.erasure', true), '') = 'on';
$$ LANGUAGE sql STABLE;

-- ---- 1. guards yield to an erasure (UPDATE/DELETE only) ----------------------------------------------------------------
-- Each guard keeps its function; only the trigger is re-created, split where it also guarded INSERT.

DROP TRIGGER capture_session_guard ON capture_session;
CREATE TRIGGER capture_session_guard BEFORE UPDATE OR DELETE ON capture_session
    FOR EACH ROW WHEN (NOT chaya_erasure_active()) EXECUTE FUNCTION capture_session_guard();

DROP TRIGGER capture_media_guard ON capture_media;
CREATE TRIGGER capture_media_guard_insert BEFORE INSERT ON capture_media
    FOR EACH ROW EXECUTE FUNCTION capture_media_guard();
CREATE TRIGGER capture_media_guard BEFORE UPDATE OR DELETE ON capture_media
    FOR EACH ROW WHEN (NOT chaya_erasure_active()) EXECUTE FUNCTION capture_media_guard();

DROP TRIGGER processing_job_guard ON processing_job;
CREATE TRIGGER processing_job_guard BEFORE UPDATE OR DELETE ON processing_job
    FOR EACH ROW WHEN (NOT chaya_erasure_active()) EXECUTE FUNCTION processing_job_guard();

DROP TRIGGER processing_artifact_guard ON processing_artifact;
CREATE TRIGGER processing_artifact_guard_insert BEFORE INSERT ON processing_artifact
    FOR EACH ROW EXECUTE FUNCTION processing_artifact_guard();
CREATE TRIGGER processing_artifact_guard BEFORE UPDATE OR DELETE ON processing_artifact
    FOR EACH ROW WHEN (NOT chaya_erasure_active()) EXECUTE FUNCTION processing_artifact_guard();

DROP TRIGGER pipeline_run_guard ON pipeline_run;
CREATE TRIGGER pipeline_run_guard BEFORE UPDATE OR DELETE ON pipeline_run
    FOR EACH ROW WHEN (NOT chaya_erasure_active()) EXECUTE FUNCTION pipeline_run_guard();

DROP TRIGGER pipeline_stage_run_immutable ON pipeline_stage_run;
CREATE TRIGGER pipeline_stage_run_immutable BEFORE UPDATE OR DELETE ON pipeline_stage_run
    FOR EACH ROW WHEN (NOT chaya_erasure_active()) EXECUTE FUNCTION immutable_record();

DROP TRIGGER pii_staging_purge_immutable ON pii_staging_purge;
CREATE TRIGGER pii_staging_purge_immutable BEFORE UPDATE OR DELETE ON pii_staging_purge
    FOR EACH ROW WHEN (NOT chaya_erasure_active()) EXECUTE FUNCTION immutable_record();

DROP TRIGGER scan_version_guard ON scan_version;
CREATE TRIGGER scan_version_guard_insert BEFORE INSERT ON scan_version
    FOR EACH ROW EXECUTE FUNCTION scan_version_guard();
CREATE TRIGGER scan_version_guard BEFORE UPDATE OR DELETE ON scan_version
    FOR EACH ROW WHEN (NOT chaya_erasure_active()) EXECUTE FUNCTION scan_version_guard();

DROP TRIGGER scan_version_artifact_guard ON scan_version_artifact;
CREATE TRIGGER scan_version_artifact_guard_insert BEFORE INSERT ON scan_version_artifact
    FOR EACH ROW EXECUTE FUNCTION scan_version_artifact_guard();
CREATE TRIGGER scan_version_artifact_guard BEFORE UPDATE OR DELETE ON scan_version_artifact
    FOR EACH ROW WHEN (NOT chaya_erasure_active()) EXECUTE FUNCTION scan_version_artifact_guard();

DROP TRIGGER coordinate_frame_guard ON coordinate_frame;
CREATE TRIGGER coordinate_frame_guard BEFORE UPDATE OR DELETE ON coordinate_frame
    FOR EACH ROW WHEN (NOT chaya_erasure_active()) EXECUTE FUNCTION coordinate_frame_guard();

DROP TRIGGER poi_version_guard ON poi_version;
CREATE TRIGGER poi_version_guard BEFORE UPDATE OR DELETE ON poi_version
    FOR EACH ROW WHEN (NOT chaya_erasure_active()) EXECUTE FUNCTION poi_version_guard();

DROP TRIGGER navigation_node_guard ON navigation_node;
CREATE TRIGGER navigation_node_guard_insert BEFORE INSERT ON navigation_node
    FOR EACH ROW EXECUTE FUNCTION navigation_graph_content_guard();
CREATE TRIGGER navigation_node_guard BEFORE UPDATE OR DELETE ON navigation_node
    FOR EACH ROW WHEN (NOT chaya_erasure_active()) EXECUTE FUNCTION navigation_graph_content_guard();

DROP TRIGGER navigation_edge_guard ON navigation_edge;
CREATE TRIGGER navigation_edge_guard_insert BEFORE INSERT ON navigation_edge
    FOR EACH ROW EXECUTE FUNCTION navigation_graph_content_guard();
CREATE TRIGGER navigation_edge_guard BEFORE UPDATE OR DELETE ON navigation_edge
    FOR EACH ROW WHEN (NOT chaya_erasure_active()) EXECUTE FUNCTION navigation_graph_content_guard();

DROP TRIGGER ar_anchor_pose_guard ON ar_anchor_pose;
CREATE TRIGGER ar_anchor_pose_guard_insert BEFORE INSERT ON ar_anchor_pose
    FOR EACH ROW EXECUTE FUNCTION ar_anchor_pose_guard();
CREATE TRIGGER ar_anchor_pose_guard BEFORE UPDATE OR DELETE ON ar_anchor_pose
    FOR EACH ROW WHEN (NOT chaya_erasure_active()) EXECUTE FUNCTION ar_anchor_pose_guard();

-- A floor whose published version is erased is unpublished (or falls back to a surviving ancestor, ErasureService).
DROP TRIGGER floor_current_scan_version_guard ON floor;
CREATE TRIGGER floor_current_scan_version_guard BEFORE UPDATE OF current_scan_version_id ON floor
    FOR EACH ROW WHEN (NOT chaya_erasure_active()) EXECUTE FUNCTION floor_current_scan_version_guard();

-- ---- 2. the erasure record -------------------------------------------------------------------------------------------
CREATE TABLE erasure_request (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id  uuid NOT NULL,
    -- The venue row is never hard-deleted (the audit log references it); an erased venue is a scrubbed tombstone.
    venue_id         uuid NOT NULL,
    target_type      text NOT NULL CHECK (target_type IN ('VENUE', 'CAPTURE', 'SCAN_VERSION')),
    target_id        uuid NOT NULL,
    cascade          boolean NOT NULL,
    requested_by     text NOT NULL,
    -- OBJECTS_PENDING: every row is gone; some objects may not be yet. COMPLETED: every listed key and prefix was
    -- deleted and found empty after settle_after.
    status           text NOT NULL CHECK (status IN ('OBJECTS_PENDING', 'COMPLETED')),
    requested_at     timestamptz NOT NULL DEFAULT now(),
    rows_erased_at   timestamptz NOT NULL DEFAULT now(),
    -- A worker that held a lease when the rows went may still upload until its lease ends; prefixes are only declared
    -- empty after this.
    settle_after     timestamptz NOT NULL,
    completed_at     timestamptz,
    object_attempts  integer NOT NULL DEFAULT 0,
    last_error       text,
    -- What was erased, as counts per store (never content).
    summary          jsonb NOT NULL CHECK (jsonb_typeof(summary) = 'object'),
    FOREIGN KEY (venue_id, organization_id) REFERENCES venue (id, organization_id) ON DELETE RESTRICT,
    CHECK ((status = 'COMPLETED') = (completed_at IS NOT NULL)),
    UNIQUE (organization_id, target_type, target_id)
);
CREATE INDEX erasure_request_pending_idx ON erasure_request (requested_at) WHERE status = 'OBJECTS_PENDING';
CREATE INDEX erasure_request_venue_idx ON erasure_request (venue_id, requested_at DESC);

-- Every entity an erasure removed (the target and everything cascaded): a repeated request for any of them is answered
-- with the erasure that already removed it, instead of 404.
CREATE TABLE erasure_item (
    request_id  uuid NOT NULL REFERENCES erasure_request (id) ON DELETE RESTRICT,
    item_type   text NOT NULL CHECK (item_type IN ('VENUE', 'CAPTURE', 'SCAN', 'SCAN_VERSION', 'PIPELINE_RUN')),
    item_id     uuid NOT NULL,
    PRIMARY KEY (item_type, item_id)
);
CREATE INDEX erasure_item_request_idx ON erasure_item (request_id);

CREATE TABLE erasure_object (
    request_id  uuid NOT NULL REFERENCES erasure_request (id) ON DELETE RESTRICT,
    bucket      text NOT NULL,
    object_key  text NOT NULL,
    is_prefix   boolean NOT NULL,
    deleted_at  timestamptz,
    PRIMARY KEY (request_id, bucket, object_key)
);
CREATE INDEX erasure_object_pending_idx ON erasure_object (request_id) WHERE deleted_at IS NULL;

-- ---- 3. unregistered PII staging -------------------------------------------------------------------------------------
CREATE TABLE pii_staging_sweep (
    run_id          uuid PRIMARY KEY REFERENCES pipeline_run (id) ON DELETE RESTRICT,
    swept_at        timestamptz NOT NULL DEFAULT now(),
    objects_deleted integer NOT NULL CHECK (objects_deleted >= 0)
);
