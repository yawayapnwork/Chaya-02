-- Capture-time calibration evidence (docs/capture-calibration.md).
--
-- A COLMAP/GLOMAP reconstruction has an arbitrary similarity gauge (docs/coordinate-frames.md). Metric scale comes only
-- from physical measurements the operator makes on site. Until now those measurements existed only as numbers typed into
-- a calibration request after reconstruction, with nothing tying them to the capture, the floor or the images they were
-- taken in. This migration records them where they are made, with the capture:
--
-- 1. capture_measurement: one measured distance (two physical points and a measured length) or one surveyed control
--    point (one physical point with venue coordinates in a named survey datum). The operator's value and unit are kept
--    verbatim next to the metres they normalise to. The floor is the capture's floor (a trigger enforces it).
--    Rows are immutable; the only change is ACTIVE -> WITHDRAWN, with who, when and why.
-- 2. capture_measurement_observation: where each physical point is seen, as a pixel in an accepted image or video frame
--    OF THE SAME CAPTURE (composite foreign key). Immutable.
-- 3. capture_media.pixel_width/pixel_height: the decoded size of an accepted image, read by the server from the file's
--    own header; NULL when the server cannot read it (HEIC, video). Observations in images are bounds-checked against it.
-- 4. capture_calibration_attempt: every attempt to turn a capture's evidence into a coordinate frame, accepted or refused,
--    append-only. coordinate_frame_measurement links an accepted frame to the measurements it was computed from.
--
-- Nothing here is a calibration: a coordinate_frame row (V17) remains the only place a metric frame exists.

ALTER TABLE capture_media
    ADD COLUMN pixel_width  integer CHECK (pixel_width IS NULL OR pixel_width > 0),
    ADD COLUMN pixel_height integer CHECK (pixel_height IS NULL OR pixel_height > 0),
    ADD CONSTRAINT capture_media_pixel_size_pair CHECK ((pixel_width IS NULL) = (pixel_height IS NULL));

-- 1. measurements ----------------------------------------------------------------------------------------------------
CREATE TABLE capture_measurement (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id    uuid NOT NULL,
    venue_id           uuid NOT NULL,
    capture_session_id uuid NOT NULL,
    floor_id           uuid NOT NULL,
    kind               text NOT NULL CHECK (kind IN ('DISTANCE', 'CONTROL_POINT')),
    label              text NOT NULL CHECK (length(btrim(label)) BETWEEN 1 AND 120),
    -- How it was measured, as the operator states it. Recorded, not verified.
    method             text NOT NULL CHECK (method IN ('TAPE', 'LASER_DISTANCE_METER', 'TOTAL_STATION', 'SURVEY_PLAN', 'OTHER')),
    unit               text NOT NULL CHECK (unit IN ('m', 'cm', 'mm', 'ft', 'in')),
    -- DISTANCE: the value as entered, in `unit`, and in metres.
    measured_value     double precision,
    measured_metres    double precision,
    -- CONTROL_POINT: the survey datum the coordinates are in, as entered (in `unit`), and in metres. +Z is up.
    datum              text CHECK (datum IS NULL OR length(btrim(datum)) BETWEEN 1 AND 120),
    venue_x            double precision,
    venue_y            double precision,
    venue_z            double precision,
    venue_x_m          double precision,
    venue_y_m          double precision,
    venue_z_m          double precision,
    uncertainty_metres double precision CHECK (uncertainty_metres IS NULL OR uncertainty_metres > 0),
    status             text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'WITHDRAWN')),
    withdrawn_reason   text,
    withdrawn_by       text,
    withdrawn_at       timestamptz,
    created_by         text NOT NULL,
    created_at         timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (venue_id, organization_id) REFERENCES venue (id, organization_id) ON DELETE RESTRICT,
    FOREIGN KEY (capture_session_id, venue_id) REFERENCES capture_session (id, venue_id) ON DELETE RESTRICT,
    FOREIGN KEY (floor_id, venue_id) REFERENCES floor (id, venue_id) ON DELETE RESTRICT,
    CHECK (kind <> 'DISTANCE' OR (coalesce(measured_value > 0 AND measured_metres > 0, false) AND datum IS NULL AND venue_x IS NULL
                                  AND venue_y IS NULL AND venue_z IS NULL AND venue_x_m IS NULL AND venue_y_m IS NULL
                                  AND venue_z_m IS NULL)),
    CHECK (kind <> 'CONTROL_POINT' OR (measured_value IS NULL AND measured_metres IS NULL AND datum IS NOT NULL
                                       AND venue_x IS NOT NULL AND venue_y IS NOT NULL AND venue_z IS NOT NULL
                                       AND venue_x_m IS NOT NULL AND venue_y_m IS NOT NULL AND venue_z_m IS NOT NULL)),
    CHECK ((status = 'WITHDRAWN') = (withdrawn_at IS NOT NULL AND withdrawn_by IS NOT NULL AND withdrawn_reason IS NOT NULL)),
    UNIQUE (id, capture_session_id)
);

CREATE INDEX capture_measurement_capture_idx ON capture_measurement (capture_session_id, created_at);

CREATE FUNCTION capture_measurement_guard() RETURNS trigger AS $$
DECLARE
    v_floor  uuid;
    v_status text;
BEGIN
    IF TG_OP = 'INSERT' THEN
        SELECT floor_id, status INTO v_floor, v_status FROM capture_session WHERE id = NEW.capture_session_id;
        IF v_floor IS DISTINCT FROM NEW.floor_id THEN
            RAISE EXCEPTION 'capture_measurement floor % is not the floor of capture %', NEW.floor_id, NEW.capture_session_id
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
        IF v_status = 'FAILED' THEN
            RAISE EXCEPTION 'capture_session % has FAILED and takes no calibration evidence', NEW.capture_session_id
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'capture_measurement % cannot be deleted; withdraw it', OLD.id USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF OLD.status <> 'ACTIVE' OR NEW.status <> 'WITHDRAWN'
       OR (to_jsonb(NEW) - ARRAY['status', 'withdrawn_reason', 'withdrawn_by', 'withdrawn_at'])
          IS DISTINCT FROM (to_jsonb(OLD) - ARRAY['status', 'withdrawn_reason', 'withdrawn_by', 'withdrawn_at']) THEN
        RAISE EXCEPTION 'capture_measurement % is immutable; the only change is ACTIVE -> WITHDRAWN', OLD.id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER capture_measurement_guard_insert BEFORE INSERT ON capture_measurement
    FOR EACH ROW EXECUTE FUNCTION capture_measurement_guard();
CREATE TRIGGER capture_measurement_guard BEFORE UPDATE OR DELETE ON capture_measurement
    FOR EACH ROW WHEN (NOT chaya_erasure_active()) EXECUTE FUNCTION capture_measurement_guard();

-- 2. observations ----------------------------------------------------------------------------------------------------
CREATE TABLE capture_measurement_observation (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id    uuid NOT NULL,
    venue_id           uuid NOT NULL,
    capture_session_id uuid NOT NULL,
    measurement_id     uuid NOT NULL,
    -- A and B: the two ends of a distance. P: a control point.
    point              text NOT NULL CHECK (point IN ('A', 'B', 'P')),
    media_id           uuid NOT NULL,
    -- Seconds from the start of a video; NULL for an image.
    frame_time_seconds double precision CHECK (frame_time_seconds IS NULL OR frame_time_seconds >= 0),
    -- Pixel coordinates; (0, 0) is the top-left corner of the top-left pixel.
    pixel_u            double precision NOT NULL CHECK (pixel_u >= 0),
    pixel_v            double precision NOT NULL CHECK (pixel_v >= 0),
    created_at         timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (venue_id, organization_id) REFERENCES venue (id, organization_id) ON DELETE RESTRICT,
    FOREIGN KEY (measurement_id, capture_session_id) REFERENCES capture_measurement (id, capture_session_id) ON DELETE RESTRICT,
    FOREIGN KEY (media_id, capture_session_id) REFERENCES capture_media (id, capture_session_id) ON DELETE RESTRICT
);

CREATE UNIQUE INDEX capture_measurement_observation_unique_view
    ON capture_measurement_observation (measurement_id, point, media_id, coalesce(frame_time_seconds, -1));
CREATE INDEX capture_measurement_observation_media_idx ON capture_measurement_observation (media_id);

CREATE FUNCTION capture_measurement_observation_guard() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'capture_measurement_observation % is immutable', OLD.id USING ERRCODE = 'integrity_constraint_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER capture_measurement_observation_guard BEFORE UPDATE OR DELETE ON capture_measurement_observation
    FOR EACH ROW WHEN (NOT chaya_erasure_active()) EXECUTE FUNCTION capture_measurement_observation_guard();

-- 3. calibration attempts --------------------------------------------------------------------------------------------
CREATE TABLE capture_calibration_attempt (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     uuid NOT NULL,
    venue_id            uuid NOT NULL,
    capture_session_id  uuid NOT NULL,
    run_id              uuid NOT NULL REFERENCES pipeline_run (id) ON DELETE RESTRICT,
    outcome             text NOT NULL CHECK (outcome IN ('ACCEPTED', 'REJECTED')),
    coordinate_frame_id uuid REFERENCES coordinate_frame (id) ON DELETE RESTRICT,
    error_code          text,
    error_message       text,
    measurement_ids     uuid[] NOT NULL,
    created_by          text NOT NULL,
    created_at          timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (venue_id, organization_id) REFERENCES venue (id, organization_id) ON DELETE RESTRICT,
    FOREIGN KEY (capture_session_id, venue_id) REFERENCES capture_session (id, venue_id) ON DELETE RESTRICT,
    CHECK ((outcome = 'ACCEPTED') = (coordinate_frame_id IS NOT NULL)),
    CHECK ((outcome = 'REJECTED') = (error_code IS NOT NULL))
);

CREATE INDEX capture_calibration_attempt_capture_idx ON capture_calibration_attempt (capture_session_id, created_at DESC);

CREATE TABLE coordinate_frame_measurement (
    coordinate_frame_id uuid NOT NULL REFERENCES coordinate_frame (id) ON DELETE RESTRICT,
    measurement_id      uuid NOT NULL REFERENCES capture_measurement (id) ON DELETE RESTRICT,
    PRIMARY KEY (coordinate_frame_id, measurement_id)
);

CREATE INDEX coordinate_frame_measurement_measurement_idx ON coordinate_frame_measurement (measurement_id);

CREATE FUNCTION capture_calibration_append_only() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '% is append-only (% rejected)', TG_TABLE_NAME, TG_OP USING ERRCODE = 'integrity_constraint_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER capture_calibration_attempt_guard BEFORE UPDATE OR DELETE ON capture_calibration_attempt
    FOR EACH ROW WHEN (NOT chaya_erasure_active()) EXECUTE FUNCTION capture_calibration_append_only();
CREATE TRIGGER coordinate_frame_measurement_guard BEFORE UPDATE OR DELETE ON coordinate_frame_measurement
    FOR EACH ROW WHEN (NOT chaya_erasure_active()) EXECUTE FUNCTION capture_calibration_append_only();
