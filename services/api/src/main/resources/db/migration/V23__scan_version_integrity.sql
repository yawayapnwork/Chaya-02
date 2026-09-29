-- ScanVersion integrity (docs/rescan.md, "VERSIONING").
--
-- Before this migration a finalized scan_version named its reconstruction only through provenance->>'runId' (a JSON
-- string nothing checked), recorded no coordinate frame, and owned no artifacts: readers found "its" .ksplat, cloud,
-- navigation graph and POIs by looking at whatever was current, so a viewer showing version N could draw version N+1's
-- POIs and route on N+1's graph. Version numbers were parent + 1 with no per-floor uniqueness (review V-4), and a
-- bootstrap after an earlier version always wrote 1 again.
--
-- From here on:
--   1. A version records the run it IS (pipeline_run_id) and, once FINALIZED, the exact coordinate frame its canonical
--      data is in (coordinate_frame_id, a frame of that run's reconstruction frame).
--   2. scan_version_artifact pins the exact processing_artifact rows that make up the version (viewer asset, cloud,
--      plane model, navmesh, navigation graph, detections). A re-scan that did not re-bake navigation inherits its
--      parent's navmesh pins, and inherits the parent's detections next to its own region-only ones; owner_version_id
--      says which version's run produced each artifact.
--   3. A version can only be created DRAFT and only become FINALIZED with a run, a frame of that run's reconstruction and
--      its own pinned viewer asset (KSPLAT) and cloud. After that nothing about it changes (V3/V19 guard, kept).
--   4. version_number is unique per floor and assigned from the floor's sequence, not parent + 1.
--   5. POI versions, navigation graphs and AR anchors name the version they belong to; a POI superseded by a re-scan
--      records which version superseded it, so the version it came from still shows it.

-- ---- 1. what a version is -------------------------------------------------------------------------------------------
ALTER TABLE scan_version
    ADD COLUMN pipeline_run_id     uuid,
    ADD COLUMN coordinate_frame_id uuid;
ALTER TABLE scan_version
    ADD CONSTRAINT scan_version_pipeline_run_fkey
        FOREIGN KEY (pipeline_run_id, venue_id) REFERENCES pipeline_run (id, venue_id) ON DELETE RESTRICT,
    ADD CONSTRAINT scan_version_coordinate_frame_fkey
        FOREIGN KEY (coordinate_frame_id, venue_id) REFERENCES coordinate_frame (id, venue_id) ON DELETE RESTRICT;
CREATE UNIQUE INDEX scan_version_pipeline_run_idx ON scan_version (pipeline_run_id) WHERE pipeline_run_id IS NOT NULL;

-- ---- 2. the artifacts a version is made of --------------------------------------------------------------------------
CREATE TABLE scan_version_artifact (
    scan_version_id  uuid NOT NULL REFERENCES scan_version (id) ON DELETE RESTRICT,
    artifact_id      uuid NOT NULL REFERENCES processing_artifact (id) ON DELETE RESTRICT,
    kind             text NOT NULL,
    -- The version whose own run produced the artifact: this version, or an ancestor it was inherited from.
    owner_version_id uuid NOT NULL REFERENCES scan_version (id) ON DELETE RESTRICT,
    created_at       timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (scan_version_id, artifact_id)
);
-- One artifact per kind, except detections: a re-scan's own region-only detections sit next to the ones it inherits.
CREATE UNIQUE INDEX scan_version_artifact_one_per_kind_idx ON scan_version_artifact (scan_version_id, kind)
    WHERE kind <> 'DETECTED_OBJECTS';
CREATE INDEX scan_version_artifact_artifact_idx ON scan_version_artifact (artifact_id);

CREATE FUNCTION scan_version_artifact_guard() RETURNS trigger AS $$
DECLARE
    v_version scan_version%ROWTYPE;
    v_artifact_run uuid;
    v_artifact_kind text;
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
    SELECT sr.run_id, a.kind, a.contains_pii INTO v_artifact_run, v_artifact_kind, v_pii
      FROM processing_artifact a JOIN pipeline_stage_run sr ON sr.id = a.stage_run_id AND sr.status = 'SUCCEEDED'
     WHERE a.id = NEW.artifact_id;
    IF v_artifact_run IS NULL OR v_artifact_kind IS DISTINCT FROM NEW.kind OR v_pii THEN
        RAISE EXCEPTION 'artifact % is not a published, non-PII % of a succeeded stage', NEW.artifact_id, NEW.kind
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF NEW.owner_version_id = NEW.scan_version_id THEN
        IF v_version.pipeline_run_id IS DISTINCT FROM v_artifact_run THEN
            RAISE EXCEPTION 'artifact % belongs to run %, not to scan_version %''s run %', NEW.artifact_id, v_artifact_run,
                NEW.scan_version_id, v_version.pipeline_run_id USING ERRCODE = 'integrity_constraint_violation';
        END IF;
    ELSIF NOT EXISTS (SELECT 1 FROM scan_version_artifact p
                       WHERE p.scan_version_id = v_version.parent_version_id AND p.artifact_id = NEW.artifact_id
                         AND p.owner_version_id = NEW.owner_version_id) THEN
        RAISE EXCEPTION 'artifact % can only be inherited from scan_version %''s parent', NEW.artifact_id, NEW.scan_version_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- ---- 5. version tags on spatial data ---------------------------------------------------------------------------------
-- poi_version.scan_version_id (V5) and navigation_graph.scan_version_id (V6) already exist and were never written.
ALTER TABLE poi ADD COLUMN superseded_by_scan_version_id uuid;
ALTER TABLE poi
    ADD CONSTRAINT poi_superseded_by_fkey
        FOREIGN KEY (superseded_by_scan_version_id, venue_id) REFERENCES scan_version (id, venue_id) ON DELETE RESTRICT,
    ADD CONSTRAINT poi_superseded_is_deleted CHECK (superseded_by_scan_version_id IS NULL OR deleted_at IS NOT NULL);
ALTER TABLE ar_anchor ADD COLUMN scan_version_id uuid;
ALTER TABLE ar_anchor
    ADD CONSTRAINT ar_anchor_scan_version_fkey
        FOREIGN KEY (scan_version_id, venue_id) REFERENCES scan_version (id, venue_id) ON DELETE RESTRICT;
CREATE INDEX poi_version_scan_version_idx ON poi_version (scan_version_id) WHERE scan_version_id IS NOT NULL;
CREATE INDEX navigation_graph_scan_version_idx ON navigation_graph (scan_version_id) WHERE scan_version_id IS NOT NULL;

-- A poi_version may be bound to its version exactly once (the bootstrap of a reconstruction that had no version yet),
-- with nothing else changing in that update; otherwise the V5 rule stands (only a missing text embedding may be filled).
CREATE OR REPLACE FUNCTION poi_version_guard() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'poi_version % cannot be deleted; soft-delete the poi instead', OLD.id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF OLD.scan_version_id IS NULL AND NEW.scan_version_id IS NOT NULL
       AND (to_jsonb(NEW) - 'scan_version_id') = (to_jsonb(OLD) - 'scan_version_id') THEN
        RETURN NEW;
    END IF;
    IF (to_jsonb(OLD) - 'embedding' - 'embedding_model') IS DISTINCT FROM
       (to_jsonb(NEW) - 'embedding' - 'embedding_model')
       OR OLD.embedding IS NOT NULL THEN
        RAISE EXCEPTION 'poi_version % is immutable (only a missing embedding may be filled in)', OLD.id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE FUNCTION navigation_graph_version_guard() RETURNS trigger AS $$
BEGIN
    IF OLD.scan_version_id IS NOT NULL AND NEW.scan_version_id IS DISTINCT FROM OLD.scan_version_id THEN
        RAISE EXCEPTION 'navigation_graph % belongs to scan_version %; that cannot change', OLD.id, OLD.scan_version_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER navigation_graph_version_guard BEFORE UPDATE ON navigation_graph
    FOR EACH ROW EXECUTE FUNCTION navigation_graph_version_guard();

-- ---- 3. creation and finalization rules ------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION scan_version_guard() RETURNS trigger AS $$
DECLARE
    v_frame_run uuid;
    v_run_version uuid;
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'DRAFT' THEN
            RAISE EXCEPTION 'a scan_version is created DRAFT and finalized with its reconstruction, never inserted %', NEW.status
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.status IN ('FINALIZED', 'ALIGNMENT_REJECTED') THEN
        RAISE EXCEPTION 'scan_version % is % and immutable', OLD.id,
            CASE OLD.status WHEN 'FINALIZED' THEN 'finalized' ELSE 'alignment-rejected' END
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    IF NEW.organization_id <> OLD.organization_id OR NEW.venue_id <> OLD.venue_id OR NEW.scan_id <> OLD.scan_id
       OR NEW.floor_id <> OLD.floor_id OR NEW.version_number <> OLD.version_number
       OR NEW.parent_version_id IS DISTINCT FROM OLD.parent_version_id
       OR (OLD.pipeline_run_id IS NOT NULL AND NEW.pipeline_run_id IS DISTINCT FROM OLD.pipeline_run_id) THEN
        RAISE EXCEPTION 'scan_version % identity, number, lineage and run are immutable', OLD.id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF NEW.status = 'FINALIZED' THEN
        IF NEW.pipeline_run_id IS NULL OR NEW.coordinate_frame_id IS NULL THEN
            RAISE EXCEPTION 'scan_version % cannot be finalized without its run and coordinate frame', NEW.id
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
        SELECT coalesce(reconstruction_frame_run_id, id), scan_version_id INTO v_frame_run, v_run_version
          FROM pipeline_run WHERE id = NEW.pipeline_run_id;
        IF v_run_version IS NOT NULL AND v_run_version <> NEW.id THEN
            RAISE EXCEPTION 'run % produces scan_version %, not %', NEW.pipeline_run_id, v_run_version, NEW.id
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
        IF NOT EXISTS (SELECT 1 FROM coordinate_frame WHERE id = NEW.coordinate_frame_id AND source_run_id = v_frame_run) THEN
            RAISE EXCEPTION 'coordinate frame % is not a frame of run %''s reconstruction', NEW.coordinate_frame_id, NEW.pipeline_run_id
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
        IF NOT EXISTS (SELECT 1 FROM scan_version_artifact WHERE scan_version_id = NEW.id AND owner_version_id = NEW.id AND kind = 'KSPLAT')
           OR NOT EXISTS (SELECT 1 FROM scan_version_artifact WHERE scan_version_id = NEW.id AND owner_version_id = NEW.id
                                                              AND kind IN ('SPLAT_MERGED', 'SPLAT_CLEAN')) THEN
            RAISE EXCEPTION 'scan_version % cannot be finalized without its own pinned viewer asset (KSPLAT) and cloud', NEW.id
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER scan_version_guard ON scan_version;
CREATE TRIGGER scan_version_guard BEFORE INSERT OR UPDATE OR DELETE ON scan_version
    FOR EACH ROW EXECUTE FUNCTION scan_version_guard();

-- ---- backfill of rows written before this migration -----------------------------------------------------------------
-- A one-off data migration of rows the guards would otherwise protect (the V21 precedent): each value written here is
-- read from facts the database already recorded, never invented. Nothing else about a row changes.
ALTER TABLE scan_version DISABLE TRIGGER scan_version_guard;

-- The run: a re-scan's own run, else the run a bootstrapped version's provenance names (when it really is a run of that
-- venue).
UPDATE scan_version v SET pipeline_run_id = r.id
  FROM pipeline_run r WHERE r.scan_version_id = v.id AND v.pipeline_run_id IS NULL;
UPDATE scan_version v SET pipeline_run_id = r.id
  FROM pipeline_run r
 WHERE v.pipeline_run_id IS NULL
   AND (v.provenance ->> 'runId') ~ '^[0-9a-f-]{36}$' AND r.id = CAST(v.provenance ->> 'runId' AS uuid) AND r.venue_id = v.venue_id
   AND NOT EXISTS (SELECT 1 FROM scan_version o WHERE o.pipeline_run_id = r.id);

-- The frame: the calibration of that run's reconstruction frame that was in force when the version finalized.
UPDATE scan_version v SET coordinate_frame_id = (
        SELECT cf.id FROM pipeline_run r JOIN coordinate_frame cf ON cf.source_run_id = coalesce(r.reconstruction_frame_run_id, r.id)
         WHERE r.id = v.pipeline_run_id AND cf.calibrated_at <= v.finalized_at
         ORDER BY cf.version DESC LIMIT 1)
 WHERE v.status = 'FINALIZED' AND v.pipeline_run_id IS NOT NULL AND v.coordinate_frame_id IS NULL;

-- Per-floor numbering: floors whose numbers collide are renumbered in creation order; the old number is kept in
-- provenance ("renumberedFrom"). Floors without a collision keep their numbers.
WITH collided AS (
    SELECT DISTINCT floor_id FROM scan_version GROUP BY floor_id, version_number HAVING count(*) > 1),
renumbered AS (
    SELECT id, version_number AS old_number,
           row_number() OVER (PARTITION BY floor_id ORDER BY created_at, id) AS new_number
      FROM scan_version WHERE floor_id IN (SELECT floor_id FROM collided))
UPDATE scan_version v
   SET version_number = r.new_number,
       provenance = v.provenance || jsonb_build_object('renumberedFrom', r.old_number)
  FROM renumbered r
 WHERE v.id = r.id AND r.new_number <> r.old_number;

ALTER TABLE scan_version ENABLE TRIGGER scan_version_guard;

CREATE UNIQUE INDEX scan_version_floor_number_idx ON scan_version (floor_id, version_number);

-- Pins for finalized versions: their own run's published artifacts. (Inheritance is not reconstructed for old rows.)
INSERT INTO scan_version_artifact (scan_version_id, artifact_id, kind, owner_version_id)
SELECT DISTINCT ON (v.id, a.kind) v.id, a.id, a.kind, v.id
  FROM scan_version v
  JOIN pipeline_run r ON r.id = v.pipeline_run_id
  JOIN pipeline_stage_run sr ON sr.run_id = r.id AND sr.status = 'SUCCEEDED'
  JOIN processing_artifact a ON a.stage_run_id = sr.id AND NOT a.contains_pii
 WHERE v.status = 'FINALIZED'
   AND (a.kind IN ('KSPLAT', 'ARTIFACT_MANIFEST', 'PLANE_MODEL', 'NAVMESH', 'NAVMESH_MANIFEST', 'NAVIGATION_GRAPH', 'DETECTED_OBJECTS')
        OR a.kind = CASE WHEN 'REGION_SPLICE' = ANY (r.stages) THEN 'SPLAT_MERGED' ELSE 'SPLAT_CLEAN' END)
 ORDER BY v.id, a.kind, sr.finished_at DESC, a.created_at DESC;

CREATE TRIGGER scan_version_artifact_guard BEFORE INSERT OR UPDATE OR DELETE ON scan_version_artifact
    FOR EACH ROW EXECUTE FUNCTION scan_version_artifact_guard();

-- Graphs and detected POIs a version's run produced belong to it.
UPDATE navigation_graph g SET scan_version_id = v.id
  FROM scan_version v WHERE g.scan_version_id IS NULL AND g.pipeline_run_id = v.pipeline_run_id AND v.status = 'FINALIZED';
UPDATE poi_version pv SET scan_version_id = v.id
  FROM scan_version v WHERE pv.scan_version_id IS NULL AND pv.pipeline_run_id = v.pipeline_run_id AND v.status = 'FINALIZED';

-- A finalized version names its run and frame. NOT VALID: a version finalized before this migration whose frame was
-- never recorded anywhere stays as it is rather than being given one after the fact.
ALTER TABLE scan_version ADD CONSTRAINT scan_version_finalized_has_run_and_frame
    CHECK (status <> 'FINALIZED' OR (pipeline_run_id IS NOT NULL AND coordinate_frame_id IS NOT NULL)) NOT VALID;

-- ---- reading a version -----------------------------------------------------------------------------------------------
-- The version and its ancestors, nearest first.
CREATE FUNCTION scan_version_lineage(p_version uuid) RETURNS TABLE (id uuid, depth integer) AS $$
    WITH RECURSIVE l (id, parent, depth) AS (
        SELECT v.id, v.parent_version_id, 0 FROM scan_version v WHERE v.id = p_version
        UNION ALL
        SELECT v.id, v.parent_version_id, l.depth + 1 FROM scan_version v JOIN l ON v.id = l.parent)
    SELECT l.id, l.depth FROM l;
$$ LANGUAGE sql STABLE;

-- Which poi_version represents POI p_poi in version p_version, or NULL when the POI is not part of that version.
-- A POI is part of a version when one of its versions was placed against the version or an ancestor, and it was not
-- deleted -- except when the deletion was a re-scan superseding it, and that re-scan is not in this lineage. Among the
-- candidates, the one in the version's own coordinate frame wins, then the newest.
CREATE FUNCTION scan_version_poi_version(p_poi uuid, p_version uuid) RETURNS uuid AS $$
    SELECT pv.id
      FROM poi p JOIN poi_version pv ON pv.poi_id = p.id
     WHERE p.id = p_poi
       AND pv.scan_version_id IN (SELECT id FROM scan_version_lineage(p_version))
       AND (p.deleted_at IS NULL
            OR (p.superseded_by_scan_version_id IS NOT NULL
                AND p.superseded_by_scan_version_id NOT IN (SELECT id FROM scan_version_lineage(p_version))))
     ORDER BY (pv.coordinate_frame_id IS NOT DISTINCT FROM (SELECT coordinate_frame_id FROM scan_version WHERE id = p_version)) DESC,
              pv.version_number DESC
     LIMIT 1;
$$ LANGUAGE sql STABLE;

-- The version new POIs and anchors on a floor are placed against: the newest FINALIZED version of the floor whose frame
-- is a calibration of the same reconstruction as the floor's current frame. NULL when there is none.
CREATE FUNCTION floor_current_scan_version(p_floor uuid) RETURNS uuid AS $$
    SELECT v.id
      FROM floor f
      JOIN coordinate_frame cur ON cur.id = f.current_coordinate_frame_id
      JOIN scan_version v ON v.floor_id = f.id AND v.status = 'FINALIZED'
      JOIN coordinate_frame vf ON vf.id = v.coordinate_frame_id
     WHERE f.id = p_floor AND vf.source_run_id = cur.source_run_id
     ORDER BY v.version_number DESC
     LIMIT 1;
$$ LANGUAGE sql STABLE;
