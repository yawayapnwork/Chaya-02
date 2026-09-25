-- Versioned coordinate frames (docs/coordinate-frames.md).
--
-- A reconstruction (COLMAP/GLOMAP output and everything computed from it) lives in its own frame with arbitrary
-- scale, rotation and origin. The Chaya canonical venue frame is metres, right-handed, +Z up (opposite gravity).
-- A coordinate_frame row is one calibration of one reconstruction frame:
--
--     X_canonical = scale * R(rotation) * X_reconstruction + translation
--
-- recorded with where each part came from (measured distances, surveyed control points, the reconstructed floor
-- plane, operator floor points), how well it fits, who calibrated it and when. Rows are immutable; recalibrating
-- adds a new version and supersedes the previous one. A frame is CANONICAL only when it is both METRIC (scale known)
-- and ALIGNED (gravity known); only then does it have a rotation and translation. A METRIC, NOT_ALIGNED frame
-- carries its scale alone (what an incremental re-scan region needs before registration).

CREATE TABLE coordinate_frame (
    id                          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id             uuid NOT NULL,
    venue_id                    uuid NOT NULL,
    floor_id                    uuid,
    -- The pipeline run whose POSE_ESTIMATION defines the reconstruction frame, and that POSES artifact.
    source_run_id               uuid NOT NULL,
    source_artifact_id          uuid REFERENCES processing_artifact (id) ON DELETE RESTRICT,
    version                     integer NOT NULL CHECK (version > 0),
    status                      text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'SUPERSEDED')),
    metric_status               text NOT NULL CHECK (metric_status IN ('NOT_CALIBRATED', 'METRIC')),
    gravity_status              text NOT NULL CHECK (gravity_status IN ('NOT_ALIGNED', 'ALIGNED')),
    horizontal_datum            text NOT NULL CHECK (horizontal_datum IN ('NONE', 'FLOOR_LOCAL', 'VENUE_CONTROL_POINTS')),
    scale                       double precision CHECK (scale IS NULL OR (scale > 0 AND scale < 'Infinity'::float8)),
    rotation_w                  double precision,
    rotation_x                  double precision,
    rotation_y                  double precision,
    rotation_z                  double precision,
    translation_x               double precision,
    translation_y               double precision,
    translation_z               double precision,
    scale_source                text NOT NULL CHECK (scale_source IN ('NONE', 'MEASURED_DISTANCES', 'CONTROL_POINTS')),
    -- Largest relative disagreement between the scale implied by each measured reference and the fitted scale.
    scale_relative_spread       double precision CHECK (scale_relative_spread IS NULL OR scale_relative_spread >= 0),
    gravity_source              text NOT NULL
                                CHECK (gravity_source IN ('NONE', 'RECONSTRUCTED_FLOOR_PLANE', 'OPERATOR_FLOOR_POINTS', 'CONTROL_POINTS')),
    gravity_up_reconstruction   double precision[] CHECK (gravity_up_reconstruction IS NULL OR array_length(gravity_up_reconstruction, 1) = 3),
    -- For a control-point frame: angle between its implied up and the reconstructed floor plane's, when both exist.
    gravity_disagreement_deg    double precision CHECK (gravity_disagreement_deg IS NULL OR gravity_disagreement_deg >= 0),
    control_point_rms_m         double precision CHECK (control_point_rms_m IS NULL OR control_point_rms_m >= 0),
    method                      text NOT NULL CHECK (length(method) > 0),
    inputs                      jsonb NOT NULL CHECK (jsonb_typeof(inputs) = 'object'),
    residuals                   jsonb NOT NULL CHECK (jsonb_typeof(residuals) = 'object'),
    calibrated_by               text NOT NULL,
    calibrated_at               timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (venue_id, organization_id) REFERENCES venue (id, organization_id) ON DELETE RESTRICT,
    FOREIGN KEY (floor_id, venue_id) REFERENCES floor (id, venue_id) ON DELETE RESTRICT,
    FOREIGN KEY (source_run_id, venue_id) REFERENCES pipeline_run (id, venue_id) ON DELETE RESTRICT,
    UNIQUE (source_run_id, version),
    UNIQUE (id, venue_id),
    CHECK ((metric_status = 'METRIC') = (scale IS NOT NULL)),
    CHECK ((metric_status = 'METRIC') = (scale_source <> 'NONE')),
    CHECK ((gravity_status = 'ALIGNED') = (gravity_source <> 'NONE')),
    -- Rotation and translation exist exactly for a canonical frame, and then the horizontal datum is known.
    CHECK ((metric_status = 'METRIC' AND gravity_status = 'ALIGNED') = (rotation_w IS NOT NULL)),
    CHECK ((rotation_w IS NULL) = (rotation_x IS NULL) AND (rotation_w IS NULL) = (rotation_y IS NULL)
           AND (rotation_w IS NULL) = (rotation_z IS NULL) AND (rotation_w IS NULL) = (translation_x IS NULL)
           AND (rotation_w IS NULL) = (translation_y IS NULL) AND (rotation_w IS NULL) = (translation_z IS NULL)),
    CHECK ((horizontal_datum <> 'NONE') = (rotation_w IS NOT NULL)),
    CHECK (rotation_w IS NULL OR abs(rotation_w * rotation_w + rotation_x * rotation_x + rotation_y * rotation_y
                                      + rotation_z * rotation_z - 1) < 1e-9)
);

CREATE UNIQUE INDEX coordinate_frame_one_active_idx ON coordinate_frame (source_run_id) WHERE status = 'ACTIVE';
CREATE INDEX coordinate_frame_floor_idx ON coordinate_frame (floor_id, calibrated_at DESC);

CREATE FUNCTION coordinate_frame_guard() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'coordinate_frame % cannot be deleted; supersede it with a new calibration', OLD.id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF (to_jsonb(OLD) - 'status') IS DISTINCT FROM (to_jsonb(NEW) - 'status')
       OR NOT (OLD.status = NEW.status OR (OLD.status = 'ACTIVE' AND NEW.status = 'SUPERSEDED')) THEN
        RAISE EXCEPTION 'coordinate_frame % is immutable (only ACTIVE -> SUPERSEDED is allowed)', OLD.id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER coordinate_frame_guard BEFORE UPDATE OR DELETE ON coordinate_frame
    FOR EACH ROW EXECUTE FUNCTION coordinate_frame_guard();

-- Which run's reconstruction frame a run's geometry ends up in: itself for a full reconstruction; for an
-- incremental re-scan, the frame its merged output is spliced into (the parent's, recursively).
ALTER TABLE pipeline_run ADD COLUMN reconstruction_frame_run_id uuid;
ALTER TABLE pipeline_run
    ADD FOREIGN KEY (reconstruction_frame_run_id, venue_id) REFERENCES pipeline_run (id, venue_id) ON DELETE RESTRICT;

-- The run guard (V10) freezes terminal runs. reconstruction_frame_run_id is a fact about the run's geometry, known at
-- start and needed for runs that finished before this column existed: it may be set exactly once, from NULL, on any run
-- (and nothing else may change in that same update); once set it is immutable like the run's identity and plan.
CREATE OR REPLACE FUNCTION pipeline_run_guard() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'pipeline_run % cannot be deleted', OLD.id USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF OLD.reconstruction_frame_run_id IS NULL AND NEW.reconstruction_frame_run_id IS NOT NULL
       AND (to_jsonb(NEW) - 'reconstruction_frame_run_id' - 'updated_at') = (to_jsonb(OLD) - 'reconstruction_frame_run_id' - 'updated_at') THEN
        RETURN NEW;
    END IF;
    IF NEW.id <> OLD.id OR NEW.organization_id <> OLD.organization_id OR NEW.venue_id <> OLD.venue_id
       OR NEW.scan_id <> OLD.scan_id OR NEW.stages <> OLD.stages OR NEW.privacy_enabled <> OLD.privacy_enabled
       OR NEW.reconstruction_frame_run_id IS DISTINCT FROM OLD.reconstruction_frame_run_id THEN
        RAISE EXCEPTION 'pipeline_run % identity, plan and reconstruction frame are immutable', OLD.id
            USING ERRCODE = 'integrity_constraint_violation';
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
UPDATE pipeline_run SET reconstruction_frame_run_id = id WHERE scan_version_id IS NULL;
UPDATE pipeline_run r SET reconstruction_frame_run_id = coalesce(p.reconstruction_frame_run_id, p.id)
  FROM scan_version v
  JOIN scan_version parent ON parent.id = v.parent_version_id
  JOIN pipeline_run p ON p.scan_id = parent.scan_id
 WHERE r.scan_version_id = v.id AND r.reconstruction_frame_run_id IS NULL;

-- The floor's current canonical frame: what POI, anchor and navigation coordinates on that floor must be in.
ALTER TABLE floor ADD COLUMN current_coordinate_frame_id uuid;
ALTER TABLE floor
    ADD FOREIGN KEY (current_coordinate_frame_id, venue_id) REFERENCES coordinate_frame (id, venue_id) ON DELETE RESTRICT;

-- Spatial data records the frame its coordinates are in. NULL = not bound to any calibrated frame.
ALTER TABLE navigation_graph ADD COLUMN coordinate_frame_id uuid;
ALTER TABLE navigation_graph
    ADD FOREIGN KEY (coordinate_frame_id, venue_id) REFERENCES coordinate_frame (id, venue_id) ON DELETE RESTRICT;
ALTER TABLE poi_version ADD COLUMN coordinate_frame_id uuid;
ALTER TABLE poi_version
    ADD FOREIGN KEY (coordinate_frame_id, venue_id) REFERENCES coordinate_frame (id, venue_id) ON DELETE RESTRICT;
ALTER TABLE ar_anchor ADD COLUMN coordinate_frame_id uuid;
ALTER TABLE ar_anchor
    ADD FOREIGN KEY (coordinate_frame_id, venue_id) REFERENCES coordinate_frame (id, venue_id) ON DELETE RESTRICT;

-- Anchors calibrated before frames existed were never tied to a known frame: they are STALE, not CALIBRATED.
-- STALE keeps last_calibrated_at (when it was last verified); only UNCALIBRATED has none.
ALTER TABLE ar_anchor DROP CONSTRAINT IF EXISTS ar_anchor_check;
UPDATE ar_anchor SET calibration_status = 'STALE' WHERE calibration_status = 'CALIBRATED';
ALTER TABLE ar_anchor
    ADD CONSTRAINT ar_anchor_calibrated_has_timestamp CHECK (calibration_status <> 'CALIBRATED' OR last_calibrated_at IS NOT NULL),
    ADD CONSTRAINT ar_anchor_uncalibrated_has_no_timestamp CHECK (calibration_status <> 'UNCALIBRATED' OR last_calibrated_at IS NULL),
    ADD CONSTRAINT ar_anchor_calibrated_has_frame CHECK (calibration_status <> 'CALIBRATED' OR coordinate_frame_id IS NOT NULL);
