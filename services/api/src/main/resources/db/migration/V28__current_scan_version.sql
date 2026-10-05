-- One coherent current scan version per floor (review N-4, V-1, V-2; docs/rescan.md, "Publication").
--
-- Before this migration "current" was a guess: the viewer showed the newest listed run, versioned or not; a full run's
-- navigation graphs went ACTIVE when NAVIGATION_BAKING reported, before the run had finished or become a version; its
-- detected POIs went live at SEMANTIC_INDEXING; calibrating any new reconstruction moved the floor's frame under the
-- POIs and anchors of the version still being shown; and an anchor's pose was overwritten in place by a recalibration
-- while its version tag kept naming the old frame.
--
-- From here on:
--   1. floor.current_scan_version_id names the one FINALIZED version a floor publishes. It only ever changes by promotion
--      (dev.chaya.api.rescan.ScanVersionService#promote), and is never cleared once set.
--   2. At commit, a floor's ACTIVE navigation graphs are exactly its current version's (none without a current version),
--      and its current coordinate frame is a calibration of that version's reconstruction. These are deferred checks, so
--      a promotion flips the pointer, the graphs and the frame in one transaction or not at all.
--   3. An AR anchor's pose is versioned: ar_anchor_pose rows are write-once (a pose, its scan version and its coordinate
--      frame, a frame of that version's reconstruction); ar_anchor is the head that names the current one.
--   4. Version-scoped artifacts name the version they were produced for: a detected POI always names one; a navigation
--      graph produced by a versioned run names that run's version; a pinned artifact produced for a version is pinned as
--      that version's own, never as another's.

-- ---- 1. the pointer ---------------------------------------------------------------------------------------------------
CREATE UNIQUE INDEX scan_version_id_floor_idx ON scan_version (id, floor_id);
ALTER TABLE floor ADD COLUMN current_scan_version_id uuid;
ALTER TABLE floor ADD CONSTRAINT floor_current_scan_version_fkey
    FOREIGN KEY (current_scan_version_id, id) REFERENCES scan_version (id, floor_id) ON DELETE RESTRICT;

-- Backfill: the version the floor was already treated as current by floor_current_scan_version (V23).
UPDATE floor f SET current_scan_version_id = floor_current_scan_version(f.id) WHERE f.deleted_at IS NULL;

-- A floor's ACTIVE graphs that are not its current version's were published without a version (a full run's graphs
-- went ACTIVE at ingestion) or belong to another version. They are retired, not deleted: promoting that run's version
-- (POST .../scan-versions/finalize-current) activates them again.
UPDATE navigation_graph g SET status = 'RETIRED'
  FROM floor f
 WHERE g.floor_id = f.id AND g.status = 'ACTIVE' AND g.scan_version_id IS DISTINCT FROM f.current_scan_version_id;
UPDATE navigation_graph SET status = 'RETIRED' WHERE status = 'ACTIVE' AND floor_id IS NULL;

-- New POIs and anchors are placed against the published version, nothing else.
CREATE OR REPLACE FUNCTION floor_current_scan_version(p_floor uuid) RETURNS uuid AS $$
    SELECT current_scan_version_id FROM floor WHERE id = p_floor;
$$ LANGUAGE sql STABLE;

CREATE FUNCTION floor_current_scan_version_guard() RETURNS trigger AS $$
DECLARE
    v_status text;
    v_frame uuid;
BEGIN
    IF NEW.current_scan_version_id IS NOT DISTINCT FROM OLD.current_scan_version_id THEN
        RETURN NEW;
    END IF;
    IF NEW.current_scan_version_id IS NULL THEN
        RAISE EXCEPTION 'floor % publishes scan_version %; a published floor is never unpublished', NEW.id,
            OLD.current_scan_version_id USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    SELECT status, coordinate_frame_id INTO v_status, v_frame FROM scan_version WHERE id = NEW.current_scan_version_id;
    IF v_status IS DISTINCT FROM 'FINALIZED' OR v_frame IS NULL THEN
        RAISE EXCEPTION 'scan_version % is %; only a FINALIZED version with a recorded coordinate frame can be current',
            NEW.current_scan_version_id, coalesce(v_status, 'missing') USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER floor_current_scan_version_guard BEFORE UPDATE OF current_scan_version_id ON floor
    FOR EACH ROW EXECUTE FUNCTION floor_current_scan_version_guard();

-- ---- 2. what a floor publishes is one version, checked at commit ------------------------------------------------------
-- Rows are re-read rather than taken from NEW: a deferred trigger sees the row as it was when the event was queued, and
-- the transaction may have changed it again since.
CREATE FUNCTION floor_publication_check(p_floor uuid) RETURNS void AS $$
DECLARE
    v_current uuid;
    v_floor_frame uuid;
    v_stray uuid;
    v_floor_run uuid;
    v_version_run uuid;
BEGIN
    SELECT current_scan_version_id, current_coordinate_frame_id INTO v_current, v_floor_frame FROM floor WHERE id = p_floor;
    SELECT id INTO v_stray FROM navigation_graph
     WHERE floor_id = p_floor AND status = 'ACTIVE' AND (v_current IS NULL OR scan_version_id IS DISTINCT FROM v_current) LIMIT 1;
    IF v_stray IS NOT NULL THEN
        RAISE EXCEPTION 'navigation_graph % is ACTIVE on floor % but is not its current scan_version %''s', v_stray, p_floor,
            coalesce(v_current::text, '(none)') USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF v_current IS NOT NULL THEN
        SELECT cf.source_run_id INTO v_floor_run FROM coordinate_frame cf WHERE cf.id = v_floor_frame;
        SELECT cf.source_run_id INTO v_version_run
          FROM scan_version v JOIN coordinate_frame cf ON cf.id = v.coordinate_frame_id WHERE v.id = v_current;
        IF v_floor_run IS NULL OR v_floor_run IS DISTINCT FROM v_version_run THEN
            RAISE EXCEPTION 'floor %''s current coordinate frame % is not a calibration of its current scan_version %''s reconstruction',
                p_floor, coalesce(v_floor_frame::text, '(none)'), v_current USING ERRCODE = 'integrity_constraint_violation';
        END IF;
    END IF;
END;
$$ LANGUAGE plpgsql;

CREATE FUNCTION floor_publication_trigger() RETURNS trigger AS $$
BEGIN
    IF TG_TABLE_NAME = 'floor' THEN
        PERFORM floor_publication_check(NEW.id);
    ELSIF NEW.floor_id IS NOT NULL THEN
        PERFORM floor_publication_check(NEW.floor_id);
    ELSIF NEW.status = 'ACTIVE' THEN
        RAISE EXCEPTION 'navigation_graph % has no floor and cannot be ACTIVE', NEW.id USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;
CREATE CONSTRAINT TRIGGER floor_publication_floor AFTER INSERT OR UPDATE OF current_scan_version_id, current_coordinate_frame_id
    ON floor DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION floor_publication_trigger();
CREATE CONSTRAINT TRIGGER floor_publication_graph AFTER INSERT OR UPDATE OF status, scan_version_id, floor_id
    ON navigation_graph DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION floor_publication_trigger();

-- ---- 4. version-scoped artifacts name their version -------------------------------------------------------------------
-- A graph produced by a versioned run belongs to that run's version; a graph's version never changes once set (V23).
CREATE OR REPLACE FUNCTION navigation_graph_version_guard() RETURNS trigger AS $$
DECLARE
    v_run_version uuid;
BEGIN
    IF TG_OP = 'UPDATE' AND OLD.scan_version_id IS NOT NULL AND NEW.scan_version_id IS DISTINCT FROM OLD.scan_version_id THEN
        RAISE EXCEPTION 'navigation_graph % belongs to scan_version %; that cannot change', OLD.id, OLD.scan_version_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF NEW.pipeline_run_id IS NOT NULL THEN
        SELECT scan_version_id INTO v_run_version FROM pipeline_run WHERE id = NEW.pipeline_run_id;
        IF v_run_version IS NOT NULL AND NEW.scan_version_id IS DISTINCT FROM v_run_version THEN
            RAISE EXCEPTION 'navigation_graph % comes from run %, which produces scan_version %, but names %', NEW.id,
                NEW.pipeline_run_id, v_run_version, coalesce(NEW.scan_version_id::text, 'no version')
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
DROP TRIGGER navigation_graph_version_guard ON navigation_graph;
CREATE TRIGGER navigation_graph_version_guard BEFORE INSERT OR UPDATE ON navigation_graph
    FOR EACH ROW EXECUTE FUNCTION navigation_graph_version_guard();

-- A detected object is scan-derived: it names the version whose run detected it. NOT VALID keeps rows written before
-- this migration (a full run's detections had no version) as they are; readers never treat those as current.
ALTER TABLE poi_version ADD CONSTRAINT poi_version_detected_has_version
    CHECK (source <> 'AUTO_DETECTED' OR scan_version_id IS NOT NULL) NOT VALID;

-- An artifact produced for a version (processing_artifact.scan_version_id) is pinned as that version's own and by no
-- other version as its own. Otherwise the V26 rules stand.
CREATE OR REPLACE FUNCTION scan_version_artifact_guard() RETURNS trigger AS $$
DECLARE
    v_version scan_version%ROWTYPE;
    v_artifact_run uuid;
    v_artifact_kind text;
    v_artifact_version uuid;
    v_pii boolean;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'scan_version_artifact is write-once (% rejected)', TG_OP
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    SELECT * INTO v_version FROM scan_version WHERE id = NEW.scan_version_id;
    IF v_version.status <> 'DRAFT' THEN
        RAISE EXCEPTION 'scan_version % is %; its artifacts are fixed', NEW.scan_version_id, v_version.status
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    SELECT sr.run_id, a.kind, a.contains_pii, a.scan_version_id INTO v_artifact_run, v_artifact_kind, v_pii, v_artifact_version
      FROM processing_artifact a JOIN pipeline_stage_run sr ON sr.id = a.stage_run_id AND sr.status = 'SUCCEEDED'
     WHERE a.id = NEW.artifact_id;
    IF v_artifact_run IS NULL OR v_artifact_kind IS DISTINCT FROM NEW.kind OR v_pii THEN
        RAISE EXCEPTION 'artifact % is not a published, non-PII % of a succeeded stage', NEW.artifact_id, NEW.kind
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF v_artifact_version IS NOT NULL AND v_artifact_version <> NEW.owner_version_id THEN
        RAISE EXCEPTION 'artifact % was produced for scan_version %, not %', NEW.artifact_id, v_artifact_version,
            NEW.owner_version_id USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF NEW.owner_version_id = NEW.scan_version_id THEN
        IF v_version.pipeline_run_id IS DISTINCT FROM v_artifact_run THEN
            RAISE EXCEPTION 'artifact % belongs to run %, not to scan_version %''s run %', NEW.artifact_id, v_artifact_run,
                NEW.scan_version_id, v_version.pipeline_run_id USING ERRCODE = 'integrity_constraint_violation';
        END IF;
    ELSIF NEW.kind IN ('NAVMESH', 'NAVMESH_MANIFEST', 'NAVIGATION_GRAPH') THEN
        RAISE EXCEPTION 'scan_version % cannot inherit its parent''s %: navigation is re-baked from the version''s own scene',
            NEW.scan_version_id, NEW.kind USING ERRCODE = 'integrity_constraint_violation';
    ELSIF NOT EXISTS (SELECT 1 FROM scan_version_artifact p
                       WHERE p.scan_version_id = v_version.parent_version_id AND p.artifact_id = NEW.artifact_id
                         AND p.owner_version_id = NEW.owner_version_id) THEN
        RAISE EXCEPTION 'artifact % can only be inherited from scan_version %''s parent', NEW.artifact_id, NEW.scan_version_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- ---- 3. versioned anchor poses ----------------------------------------------------------------------------------------
CREATE TABLE ar_anchor_pose (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     uuid NOT NULL,
    venue_id            uuid NOT NULL,
    anchor_id           uuid NOT NULL REFERENCES ar_anchor (id) ON DELETE RESTRICT,
    revision            integer NOT NULL CHECK (revision > 0),
    -- The version the pose was entered against, and the frame it is in: a calibration of that version's reconstruction.
    scan_version_id     uuid NOT NULL,
    coordinate_frame_id uuid NOT NULL,
    physical_x double precision NOT NULL, physical_y double precision NOT NULL, physical_z double precision NOT NULL,
    physical_qx double precision NOT NULL, physical_qy double precision NOT NULL, physical_qz double precision NOT NULL,
    physical_qw double precision NOT NULL,
    digital_x double precision NOT NULL, digital_y double precision NOT NULL, digital_z double precision NOT NULL,
    digital_qx double precision NOT NULL, digital_qy double precision NOT NULL, digital_qz double precision NOT NULL,
    digital_qw double precision NOT NULL,
    -- ENTERED by staff; REPROJECTED when the same reconstruction was recalibrated (the frame moved, the anchor did not);
    -- BACKFILL for an anchor that existed before this table.
    source              text NOT NULL CHECK (source IN ('ENTERED', 'REPROJECTED', 'BACKFILL')),
    -- When an operator physically verified this pose (AnchorService#calibrate). Set at most once.
    calibrated_at       timestamptz,
    created_by          text NOT NULL,
    created_at          timestamptz NOT NULL DEFAULT now(),
    UNIQUE (anchor_id, revision),
    FOREIGN KEY (venue_id, organization_id) REFERENCES venue (id, organization_id) ON DELETE RESTRICT,
    FOREIGN KEY (scan_version_id, venue_id) REFERENCES scan_version (id, venue_id) ON DELETE RESTRICT,
    FOREIGN KEY (coordinate_frame_id, venue_id) REFERENCES coordinate_frame (id, venue_id) ON DELETE RESTRICT
);
CREATE INDEX ar_anchor_pose_version_idx ON ar_anchor_pose (scan_version_id);

CREATE FUNCTION ar_anchor_pose_guard() RETURNS trigger AS $$
DECLARE
    v_frame_run uuid;
    v_version_run uuid;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'ar_anchor_pose % is history and cannot be deleted', OLD.id USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF TG_OP = 'UPDATE' THEN
        IF OLD.calibrated_at IS NULL AND NEW.calibrated_at IS NOT NULL
           AND (to_jsonb(NEW) - 'calibrated_at') = (to_jsonb(OLD) - 'calibrated_at') THEN
            RETURN NEW;
        END IF;
        RAISE EXCEPTION 'ar_anchor_pose % is immutable (only its first calibration may be recorded)', OLD.id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    SELECT source_run_id INTO v_frame_run FROM coordinate_frame WHERE id = NEW.coordinate_frame_id;
    SELECT coalesce(r.reconstruction_frame_run_id, r.id) INTO v_version_run
      FROM scan_version v JOIN pipeline_run r ON r.id = v.pipeline_run_id WHERE v.id = NEW.scan_version_id;
    IF v_version_run IS NULL OR v_frame_run IS DISTINCT FROM v_version_run THEN
        RAISE EXCEPTION 'coordinate frame % is not a frame of scan_version %''s reconstruction', NEW.coordinate_frame_id,
            NEW.scan_version_id USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER ar_anchor_pose_guard BEFORE INSERT OR UPDATE OR DELETE ON ar_anchor_pose
    FOR EACH ROW EXECUTE FUNCTION ar_anchor_pose_guard();

ALTER TABLE ar_anchor ADD COLUMN current_pose_id uuid;

-- Anchors already tagged with a version get their pose as revision 1. An anchor without a version stays as it is: it
-- cannot be used for relocalization (ANCHOR_UNVERSIONED) until its pose is entered again against a published version.
INSERT INTO ar_anchor_pose (organization_id, venue_id, anchor_id, revision, scan_version_id, coordinate_frame_id,
    physical_x, physical_y, physical_z, physical_qx, physical_qy, physical_qz, physical_qw,
    digital_x, digital_y, digital_z, digital_qx, digital_qy, digital_qz, digital_qw, source, calibrated_at, created_by)
SELECT a.organization_id, a.venue_id, a.id, 1, a.scan_version_id, a.coordinate_frame_id,
       a.physical_x, a.physical_y, a.physical_z, a.physical_qx, a.physical_qy, a.physical_qz, a.physical_qw,
       a.digital_x, a.digital_y, a.digital_z, a.digital_qx, a.digital_qy, a.digital_qz, a.digital_qw, 'BACKFILL',
       CASE WHEN a.calibration_status = 'CALIBRATED' THEN a.last_calibrated_at END, 'migration:V28'
  FROM ar_anchor a
  JOIN scan_version v ON v.id = a.scan_version_id
  JOIN pipeline_run r ON r.id = v.pipeline_run_id
  JOIN coordinate_frame cf ON cf.id = a.coordinate_frame_id AND cf.source_run_id = coalesce(r.reconstruction_frame_run_id, r.id);
UPDATE ar_anchor a SET current_pose_id = p.id FROM ar_anchor_pose p WHERE p.anchor_id = a.id;
-- A tag whose frame is not of the version's reconstruction (a recalibration moved the pose, review V-1) is not a version
-- claim anyone can check: dropped, like an anchor that never had one.
UPDATE ar_anchor SET scan_version_id = NULL WHERE scan_version_id IS NOT NULL AND current_pose_id IS NULL;

ALTER TABLE ar_anchor ADD CONSTRAINT ar_anchor_current_pose_fkey
    FOREIGN KEY (current_pose_id) REFERENCES ar_anchor_pose (id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED;

-- A live anchor that has a version (every anchor created from now on) names its current pose, and its head columns are
-- exactly that pose. Checked at commit, so the anchor and its first pose can be written in either order.
CREATE FUNCTION ar_anchor_pose_consistency() RETURNS trigger AS $$
DECLARE
    a ar_anchor%ROWTYPE;
    p ar_anchor_pose%ROWTYPE;
BEGIN
    SELECT * INTO a FROM ar_anchor WHERE id = NEW.id;
    IF a.id IS NULL OR a.deleted_at IS NOT NULL THEN
        RETURN NULL;
    END IF;
    IF TG_OP = 'UPDATE' AND a.scan_version_id IS NULL AND a.current_pose_id IS NULL THEN
        RETURN NULL; -- an unversioned anchor from before V28, untouched
    END IF;
    SELECT * INTO p FROM ar_anchor_pose WHERE id = a.current_pose_id;
    IF p.id IS NULL OR p.anchor_id <> a.id OR p.scan_version_id IS DISTINCT FROM a.scan_version_id
       OR p.coordinate_frame_id IS DISTINCT FROM a.coordinate_frame_id
       OR (p.digital_x, p.digital_y, p.digital_z, p.digital_qx, p.digital_qy, p.digital_qz, p.digital_qw)
          IS DISTINCT FROM (a.digital_x, a.digital_y, a.digital_z, a.digital_qx, a.digital_qy, a.digital_qz, a.digital_qw)
       OR (p.physical_x, p.physical_y, p.physical_z, p.physical_qx, p.physical_qy, p.physical_qz, p.physical_qw)
          IS DISTINCT FROM (a.physical_x, a.physical_y, a.physical_z, a.physical_qx, a.physical_qy, a.physical_qz, a.physical_qw)
       OR (a.calibration_status = 'CALIBRATED' AND p.calibrated_at IS NULL) THEN
        RAISE EXCEPTION 'ar_anchor % does not match its current versioned pose %', a.id, coalesce(a.current_pose_id::text, '(none)')
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;
CREATE CONSTRAINT TRIGGER ar_anchor_pose_consistency AFTER INSERT OR UPDATE ON ar_anchor
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION ar_anchor_pose_consistency();

-- The pose of anchor p_anchor as of version p_version: the newest pose entered against the version or an ancestor,
-- preferring one in the version's own coordinate frame (so a later recalibration never moves an anchor in an older
-- version). NULL when the anchor has no pose in that lineage.
CREATE FUNCTION scan_version_anchor_pose(p_anchor uuid, p_version uuid) RETURNS uuid AS $$
    SELECT p.id
      FROM ar_anchor_pose p
     WHERE p.anchor_id = p_anchor
       AND p.scan_version_id IN (SELECT id FROM scan_version_lineage(p_version))
     ORDER BY (p.coordinate_frame_id IS NOT DISTINCT FROM (SELECT coordinate_frame_id FROM scan_version WHERE id = p_version)) DESC,
              p.revision DESC
     LIMIT 1;
$$ LANGUAGE sql STABLE;

-- ---- reading "current" ------------------------------------------------------------------------------------------------
-- Whether a POI version belongs to what its floor publishes now: placed against the floor's current version or one of
-- its ancestors. A floorless POI, or a staff-placed one on a floor that has never published a version, carries no scan
-- geometry to disagree with; anything detected from a scan never counts without its version.
CREATE FUNCTION poi_version_is_current(p_floor uuid, p_scan_version uuid, p_source text) RETURNS boolean AS $$
    SELECT CASE
        WHEN p_floor IS NULL THEN p_source <> 'AUTO_DETECTED'
        ELSE coalesce((SELECT CASE WHEN f.current_scan_version_id IS NULL THEN p_source <> 'AUTO_DETECTED' AND p_scan_version IS NULL
                                   ELSE p_scan_version IN (SELECT id FROM scan_version_lineage(f.current_scan_version_id)) END
                         FROM floor f WHERE f.id = p_floor), false)
    END;
$$ LANGUAGE sql STABLE;
