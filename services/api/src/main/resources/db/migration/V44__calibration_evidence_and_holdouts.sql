-- Measured calibration evidence (docs/coordinate-frames.md §3, docs/capture-calibration.md).
--
-- 1. A coordinate frame records where its reconstruction points came from:
--      TRIANGULATED_OBSERVATIONS  each measured point was located by triangulating the pixels the operator marked in at
--                                 least two registered views, through the run's own SfM poses (CalibrationTriangulation);
--      OPERATOR_SUPPLIED          the reconstruction coordinates were typed in or picked by hand.
--    Only a TRIANGULATED_OBSERVATIONS frame is eligible for metric routing and physical AR guidance
--    (chaya.frames.accept-operator-supplied-points, false by default). Every frame calibrated before this migration had
--    hand-entered points, so existing rows are OPERATOR_SUPPLIED: that is what they are.
-- 2. A frame records its validation against independent HOLDOUT measurements, which never enter the fit:
--      HOLDOUT_VALIDATED  every holdout was evaluated and within tolerance (its errors are in residuals.holdout);
--      NOT_VALIDATED      there was no holdout, so the frame's accuracy is unknown beyond its own fit residuals.
--    A calibration whose holdouts fail is refused, not stored.
-- 3. A measurement has a role: CALIBRATION (used in the fit) or HOLDOUT (only to validate it).

ALTER TABLE coordinate_frame
    ADD COLUMN point_source text NOT NULL DEFAULT 'OPERATOR_SUPPLIED'
        CHECK (point_source IN ('TRIANGULATED_OBSERVATIONS', 'OPERATOR_SUPPLIED')),
    ADD COLUMN validation_status text
        CHECK (validation_status IS NULL OR validation_status IN ('HOLDOUT_VALIDATED', 'NOT_VALIDATED')),
    ADD COLUMN holdout_max_relative_error double precision
        CHECK (holdout_max_relative_error IS NULL OR (holdout_max_relative_error >= 0 AND holdout_max_relative_error < 'Infinity'::float8)),
    ADD COLUMN holdout_max_error_m double precision
        CHECK (holdout_max_error_m IS NULL OR (holdout_max_error_m >= 0 AND holdout_max_error_m < 'Infinity'::float8));

-- A metric frame always says whether it was validated; an uncalibrated one has nothing to validate. Frames are write-once
-- (coordinate_frame_guard), so the guard is suspended for exactly this backfill of the new column, inside this migration's
-- transaction: no existing value changes, and no frame before this migration had a holdout, so NOT_VALIDATED is true.
ALTER TABLE coordinate_frame DISABLE TRIGGER coordinate_frame_guard;
UPDATE coordinate_frame SET validation_status = 'NOT_VALIDATED' WHERE metric_status = 'METRIC';
ALTER TABLE coordinate_frame ENABLE TRIGGER coordinate_frame_guard;
ALTER TABLE coordinate_frame
    ADD CONSTRAINT coordinate_frame_validation_recorded
        CHECK ((metric_status = 'METRIC') = (validation_status IS NOT NULL)),
    ADD CONSTRAINT coordinate_frame_holdout_errors_only_when_validated
        CHECK (validation_status = 'HOLDOUT_VALIDATED' OR (holdout_max_relative_error IS NULL AND holdout_max_error_m IS NULL));

ALTER TABLE capture_measurement
    ADD COLUMN role text NOT NULL DEFAULT 'CALIBRATION' CHECK (role IN ('CALIBRATION', 'HOLDOUT'));
