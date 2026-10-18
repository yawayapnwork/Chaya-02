-- Multi-room stitching (docs/multi-room.md): rooms of a floor captured and reconstructed one by one, then registered into
-- one coordinate frame and stitched into the floor's reconstruction.
--
--   * a ROOM is a `space` of the floor (V2; kind ROOM, CORRIDOR, HALL or OTHER);
--   * a room capture is an ordinary capture_session that names its space. Its run reconstructs that room only and is a
--     ROOM VERSION: a scan_version of the floor naming the space, finalized with its own cloud, viewer asset and canonical
--     frame like any version, and NEVER published as what the floor shows (it is one room, not the floor);
--   * a stitch is a pipeline run of its own (PipelineDefinition.STITCH_STAGES) on a capture_session with no media
--     (`stitch`), whose DRAFT scan_version records exactly which finalized room versions it combines
--     (scan_version_stitch_source). It is published like a full reconstruction once every stage succeeded.

-- 1. The two stitch stages.
ALTER TABLE processing_job DROP CONSTRAINT processing_job_stage_check;
ALTER TABLE processing_job ADD CONSTRAINT processing_job_stage_check CHECK (stage IN (
    'INPUT_VALIDATION', 'FFMPEG_PREPROCESS', 'FRAME_QUALITY_FILTER', 'PRIVACY_PREPROCESS',
    'POSE_ESTIMATION', 'SPLAT_RECONSTRUCTION', 'SEMANTIC_SEGMENTATION', 'GEOMETRIC_CLEANUP',
    'REGION_ALIGNMENT', 'REGION_SPLICE', 'ROOM_REGISTRATION', 'ROOM_STITCH',
    'PLANE_FITTING', 'ARTIFACT_GENERATION', 'NAVIGATION_BAKING', 'SEMANTIC_INDEXING',
    'MEDIA_FILTER', 'SPLAT_TRAINING', 'GEOMETRY_CLEANUP', 'SEGMENTATION', 'OBJECT_DETECTION', 'NAVMESH_GENERATION'));

-- 2. Rooms: the kinds the API accepts. Existing rows keep whatever they have (NOT VALID); nothing wrote them before.
ALTER TABLE space ADD CONSTRAINT space_kind_check CHECK (kind IN ('ROOM', 'CORRIDOR', 'HALL', 'OTHER')) NOT VALID;
CREATE UNIQUE INDEX space_floor_name_idx ON space (floor_id, lower(name)) WHERE deleted_at IS NULL;

-- 3. A capture of one room, or a stitch. Neither is a re-scan, a stitch has no room, and both are fixed at creation.
ALTER TABLE capture_session
    ADD COLUMN space_id uuid,
    ADD COLUMN stitch boolean NOT NULL DEFAULT false,
    ADD CONSTRAINT capture_session_space_fkey FOREIGN KEY (space_id, floor_id) REFERENCES space (id, floor_id) ON DELETE RESTRICT,
    ADD CONSTRAINT capture_session_room_check CHECK (space_id IS NULL OR (floor_id IS NOT NULL AND parent_scan_version_id IS NULL)),
    ADD CONSTRAINT capture_session_stitch_check CHECK (NOT stitch OR (floor_id IS NOT NULL AND space_id IS NULL
                                                                     AND parent_scan_version_id IS NULL));
CREATE INDEX capture_session_space_idx ON capture_session (space_id) WHERE space_id IS NOT NULL;

CREATE FUNCTION capture_session_room_guard() RETURNS trigger AS $$
BEGIN
    IF NEW.space_id IS DISTINCT FROM OLD.space_id OR NEW.stitch <> OLD.stitch THEN
        RAISE EXCEPTION 'capture_session % room and stitch are fixed at creation', OLD.id USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER capture_session_room_guard BEFORE UPDATE ON capture_session
    FOR EACH ROW WHEN (NOT chaya_erasure_active()) EXECUTE FUNCTION capture_session_room_guard();

-- 4. Room versions, and what a stitch measured. registration_report / stitch_report / room_connectivity are written while
--    the version is DRAFT (whatever the outcome, like alignment_report) and are immutable with it once FINALIZED.
ALTER TABLE scan_version
    ADD COLUMN space_id uuid,
    ADD COLUMN registration_report jsonb CHECK (registration_report IS NULL OR jsonb_typeof(registration_report) = 'object'),
    ADD COLUMN stitch_report jsonb CHECK (stitch_report IS NULL OR jsonb_typeof(stitch_report) = 'object'),
    ADD COLUMN room_connectivity jsonb CHECK (room_connectivity IS NULL OR jsonb_typeof(room_connectivity) = 'object'),
    ADD CONSTRAINT scan_version_space_fkey FOREIGN KEY (space_id, floor_id) REFERENCES space (id, floor_id) ON DELETE RESTRICT,
    ADD CONSTRAINT scan_version_room_check CHECK (space_id IS NULL OR (parent_version_id IS NULL AND region_geometry IS NULL));
CREATE INDEX scan_version_space_idx ON scan_version (space_id, version_number DESC) WHERE space_id IS NOT NULL;

CREATE FUNCTION scan_version_room_guard() RETURNS trigger AS $$
BEGIN
    IF NEW.space_id IS DISTINCT FROM OLD.space_id THEN
        RAISE EXCEPTION 'scan_version % room is fixed at creation', OLD.id USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER scan_version_room_guard BEFORE UPDATE ON scan_version
    FOR EACH ROW WHEN (NOT chaya_erasure_active()) EXECUTE FUNCTION scan_version_room_guard();

-- 5. A room version is never what its floor publishes: it is one room, and the floor's navigation, POIs and anchors are
--    the floor's.
CREATE FUNCTION floor_room_version_guard() RETURNS trigger AS $$
BEGIN
    IF NEW.current_scan_version_id IS NOT NULL
       AND EXISTS (SELECT 1 FROM scan_version WHERE id = NEW.current_scan_version_id AND space_id IS NOT NULL) THEN
        RAISE EXCEPTION 'scan_version % is a room version and cannot be published as floor %', NEW.current_scan_version_id, NEW.id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER floor_room_version_guard BEFORE UPDATE OF current_scan_version_id ON floor
    FOR EACH ROW EXECUTE FUNCTION floor_room_version_guard();

-- 6. What a stitch combines: write-once rows, one per room, written with the DRAFT stitch version. Each names a FINALIZED
--    room version of that room on the stitch's floor, the exact cloud and labels artifacts handed to the worker, and the
--    calibration the room was registered with. Exactly one row is the anchor, whose reconstruction frame the stitched
--    floor is written in.
CREATE TABLE scan_version_stitch_source (
    stitch_version_id   uuid NOT NULL REFERENCES scan_version (id) ON DELETE RESTRICT,
    position            integer NOT NULL CHECK (position >= 0),
    room_version_id     uuid NOT NULL REFERENCES scan_version (id) ON DELETE RESTRICT,
    space_id            uuid NOT NULL REFERENCES space (id) ON DELETE RESTRICT,
    cloud_artifact_id   uuid NOT NULL REFERENCES processing_artifact (id) ON DELETE RESTRICT,
    labels_artifact_id  uuid NOT NULL REFERENCES processing_artifact (id) ON DELETE RESTRICT,
    coordinate_frame_id uuid NOT NULL REFERENCES coordinate_frame (id) ON DELETE RESTRICT,
    anchor              boolean NOT NULL,
    created_at          timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (stitch_version_id, position),
    UNIQUE (stitch_version_id, space_id)
);
CREATE UNIQUE INDEX scan_version_stitch_source_anchor_idx ON scan_version_stitch_source (stitch_version_id) WHERE anchor;
CREATE INDEX scan_version_stitch_source_room_idx ON scan_version_stitch_source (room_version_id);

CREATE FUNCTION scan_version_stitch_source_guard() RETURNS trigger AS $$
DECLARE
    v_stitch scan_version%ROWTYPE;
    v_room scan_version%ROWTYPE;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'scan_version_stitch_source is write-once (% rejected)', TG_OP USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    SELECT * INTO v_stitch FROM scan_version WHERE id = NEW.stitch_version_id;
    SELECT * INTO v_room FROM scan_version WHERE id = NEW.room_version_id;
    IF v_stitch.status <> 'DRAFT' OR v_stitch.space_id IS NOT NULL OR v_stitch.parent_version_id IS NOT NULL THEN
        RAISE EXCEPTION 'scan_version % is not a DRAFT floor version a stitch can be recorded on', NEW.stitch_version_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF v_room.status <> 'FINALIZED' OR v_room.space_id IS DISTINCT FROM NEW.space_id OR v_room.floor_id <> v_stitch.floor_id THEN
        RAISE EXCEPTION 'scan_version % is not a FINALIZED room version of space % on the stitch''s floor', NEW.room_version_id,
            NEW.space_id USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM scan_version_artifact WHERE scan_version_id = NEW.room_version_id AND artifact_id = NEW.cloud_artifact_id
                                                         AND kind = 'SPLAT_CLEAN') THEN
        RAISE EXCEPTION 'artifact % is not the cloud room version % pins', NEW.cloud_artifact_id, NEW.room_version_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM processing_artifact l JOIN processing_artifact c ON c.id = NEW.cloud_artifact_id
                    WHERE l.id = NEW.labels_artifact_id AND l.kind = 'SEMANTIC_LABELS_CLEAN' AND l.stage_run_id = c.stage_run_id) THEN
        RAISE EXCEPTION 'artifact % is not the labels of cloud %', NEW.labels_artifact_id, NEW.cloud_artifact_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM coordinate_frame cf JOIN pipeline_run r ON r.id = v_room.pipeline_run_id
                    WHERE cf.id = NEW.coordinate_frame_id AND cf.source_run_id = coalesce(r.reconstruction_frame_run_id, r.id)) THEN
        RAISE EXCEPTION 'coordinate frame % is not a calibration of room version %''s reconstruction', NEW.coordinate_frame_id,
            NEW.room_version_id USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER scan_version_stitch_source_guard BEFORE INSERT OR UPDATE OR DELETE ON scan_version_stitch_source
    FOR EACH ROW WHEN (NOT chaya_erasure_active()) EXECUTE FUNCTION scan_version_stitch_source_guard();
