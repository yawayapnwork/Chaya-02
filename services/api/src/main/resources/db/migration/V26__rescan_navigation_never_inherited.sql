-- A re-scan's navigation is never inherited (docs/rescan.md, "Downstream rebuilds").
--
-- Before: a re-scan whose region held no node of the floor's ACTIVE routing graph skipped NAVIGATION_BAKING and pinned
-- its parent's NAVMESH / NAVMESH_MANIFEST / NAVIGATION_GRAPH. That test cannot tell whether the region changed
-- navigation: a region inside one large navmesh polygon holds none of the graph's nodes (centroids), and a region where
-- furniture was removed has no walkable polygon yet. The new version then routed on a navmesh baked for geometry it no
-- longer has. The kinds were also inherited one by one, so a version could pin its own NAVMESH next to its parent's
-- NAVIGATION_GRAPH.
--
-- Now (dev.chaya.api.rescan.RescanService#parentHasNavigation, ScanVersionService#finalizeVersion), and here:
--   1. NAVMESH, NAVMESH_MANIFEST and NAVIGATION_GRAPH can only be pinned as the version's own (from its own run);
--   2. a version is finalized with all three of its own, or none;
--   3. a re-scan whose parent pinned a NAVMESH is finalized only with its own (re-baked from its merged scene).
-- Versions finalized before this migration keep whatever they pinned (pins are write-once); only new pins and new
-- finalizations are checked.

CREATE OR REPLACE FUNCTION scan_version_artifact_guard() RETURNS trigger AS $$
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

CREATE OR REPLACE FUNCTION scan_version_guard() RETURNS trigger AS $$
DECLARE
    v_frame_run uuid;
    v_run_version uuid;
    v_own_navigation integer;
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
        SELECT count(DISTINCT kind) INTO v_own_navigation FROM scan_version_artifact
         WHERE scan_version_id = NEW.id AND owner_version_id = NEW.id AND kind IN ('NAVMESH', 'NAVMESH_MANIFEST', 'NAVIGATION_GRAPH');
        IF v_own_navigation NOT IN (0, 3) THEN
            RAISE EXCEPTION 'scan_version % pins % of NAVMESH, NAVMESH_MANIFEST and NAVIGATION_GRAPH; navigation is all three or none',
                NEW.id, v_own_navigation USING ERRCODE = 'integrity_constraint_violation';
        END IF;
        IF v_own_navigation = 0 AND EXISTS (SELECT 1 FROM scan_version_artifact
                                             WHERE scan_version_id = NEW.parent_version_id AND kind = 'NAVMESH') THEN
            RAISE EXCEPTION 'scan_version % cannot be finalized without its own navmesh: its parent has one (never inherited)',
                NEW.id USING ERRCODE = 'integrity_constraint_violation';
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
