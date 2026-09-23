-- Fiducial anchors for AR relocalization (ARCHITECTURE.md #5, #9; docs/ar.md). A floor can carry several
-- anchors -- multi-anchor relocalization interpolates between whichever ones a client currently sees --
-- so this is a one-to-many table per floor, not a single anchor column on `floor`.
--
-- Each anchor records two poses in the same six-DOF shape (position + quaternion):
--   * digital_*  : where the marker sits in the reconstruction's venue frame (the source of truth; set
--                  when the marker is placed/measured against the reconstruction).
--   * physical_* : the pose the marker declares about itself in real space (e.g. an ArUco marker's own
--                  size/orientation convention), used together with a live device detection of that same
--                  marker to solve the device_frame -> venue_frame transform at runtime.
-- Neither pose is ever fabricated by a client: digital_* comes from venue setup/calibration, physical_*
-- from the marker's physical specification, and the transform itself is computed client-side from a real
-- detection (see dev.chaya.api.ar.CoordinateTransform, which both AR clients' own math mirrors).

CREATE TABLE ar_anchor (
    id                     uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id        uuid NOT NULL,
    venue_id               uuid NOT NULL,
    floor_id               uuid NOT NULL,
    marker_type            text NOT NULL
                           CHECK (marker_type IN ('QR_CODE', 'ARUCO_MARKER', 'IMAGE_TARGET', 'APRILTAG')),
    marker_identifier      text NOT NULL,
    physical_x             double precision NOT NULL,
    physical_y             double precision NOT NULL,
    physical_z             double precision NOT NULL,
    physical_qx            double precision NOT NULL DEFAULT 0,
    physical_qy            double precision NOT NULL DEFAULT 0,
    physical_qz            double precision NOT NULL DEFAULT 0,
    physical_qw            double precision NOT NULL DEFAULT 1,
    digital_x              double precision NOT NULL,
    digital_y              double precision NOT NULL,
    digital_z              double precision NOT NULL,
    digital_qx             double precision NOT NULL DEFAULT 0,
    digital_qy             double precision NOT NULL DEFAULT 0,
    digital_qz             double precision NOT NULL DEFAULT 0,
    digital_qw             double precision NOT NULL DEFAULT 1,
    -- CALIBRATED is only set by an explicit calibration action (see AnchorService#calibrate); creating or
    -- editing an anchor's poses always resets it to UNCALIBRATED so a stale calibration is never implied.
    calibration_status     text NOT NULL DEFAULT 'UNCALIBRATED'
                           CHECK (calibration_status IN ('UNCALIBRATED', 'CALIBRATED', 'STALE')),
    last_calibrated_at     timestamptz,
    created_at             timestamptz NOT NULL DEFAULT now(),
    updated_at             timestamptz NOT NULL DEFAULT now(),
    deleted_at             timestamptz,
    FOREIGN KEY (venue_id, organization_id) REFERENCES venue (id, organization_id) ON DELETE RESTRICT,
    FOREIGN KEY (floor_id, venue_id) REFERENCES floor (id, venue_id) ON DELETE RESTRICT,
    CHECK ((calibration_status = 'CALIBRATED') = (last_calibrated_at IS NOT NULL))
);

-- One live marker identifier per floor; a re-registered marker must reuse or replace the existing row,
-- never silently duplicate it (soft-deleted rows are excluded so a marker can be re-added after removal).
CREATE UNIQUE INDEX ar_anchor_floor_marker_idx
    ON ar_anchor (floor_id, marker_type, marker_identifier) WHERE deleted_at IS NULL;
CREATE INDEX ar_anchor_venue_idx ON ar_anchor (venue_id) WHERE deleted_at IS NULL;
CREATE INDEX ar_anchor_floor_idx ON ar_anchor (floor_id) WHERE deleted_at IS NULL;

CREATE TRIGGER ar_anchor_updated BEFORE UPDATE ON ar_anchor
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
