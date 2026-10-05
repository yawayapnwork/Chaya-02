package dev.chaya.api;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Test-only row builders. Every call creates uniquely named rows so tests never collide. */
final class Fixtures {

    private final JdbcClient jdbc;

    Fixtures(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static String slug() {
        return "t-" + UUID.randomUUID().toString().substring(0, 12);
    }

    UUID organization() {
        return jdbc.sql("INSERT INTO organization (slug, name) VALUES (:s, 'Test Org') RETURNING id")
            .param("s", slug()).query(UUID.class).single();
    }

    UUID venue(UUID org) {
        return jdbc.sql("INSERT INTO venue (organization_id, slug, name) VALUES (:o, :s, 'Test Venue') RETURNING id")
            .param("o", org).param("s", slug()).query(UUID.class).single();
    }

    UUID floor(UUID org, UUID venue, int level) {
        return jdbc.sql("INSERT INTO floor (organization_id, venue_id, level, name) VALUES (:o, :v, :l, 'Floor') RETURNING id")
            .param("o", org).param("v", venue).param("l", level).query(UUID.class).single();
    }

    UUID space(UUID org, UUID venue, UUID floor) {
        return jdbc.sql("INSERT INTO space (organization_id, venue_id, floor_id, name) VALUES (:o, :v, :f, 'Room') RETURNING id")
            .param("o", org).param("v", venue).param("f", floor).query(UUID.class).single();
    }

    UUID captureSession(UUID org, UUID venue) {
        return jdbc.sql("INSERT INTO capture_session (organization_id, venue_id, operator_id) VALUES (:o, :v, 'operator-sub') RETURNING id")
            .param("o", org).param("v", venue).query(UUID.class).single();
    }

    UUID scan(UUID org, UUID venue, UUID session) {
        return jdbc.sql("INSERT INTO scan (organization_id, venue_id, capture_session_id) VALUES (:o, :v, :c) RETURNING id")
            .param("o", org).param("v", venue).param("c", session).query(UUID.class).single();
    }

    UUID draftScanVersion(UUID org, UUID venue, UUID scan, UUID floor, int number) {
        return jdbc.sql("""
                INSERT INTO scan_version (organization_id, venue_id, scan_id, floor_id, version_number)
                VALUES (:o, :v, :s, :f, :n) RETURNING id""")
            .param("o", org).param("v", venue).param("s", scan).param("f", floor).param("n", number)
            .query(UUID.class).single();
    }

    /**
     * A FINALIZED version of `scan`, made the only way the schema allows (V23): a SUCCEEDED run of the scan with a published
     * cloud (SPLAT_CLEAN) and viewer asset (KSPLAT), an ACTIVE canonical frame of that run (an identity similarity, a
     * mathematical fixture -- see identityFrame -- made the floor's current frame), a DRAFT version of the run pinning both
     * artifacts, then DRAFT -> FINALIZED with that frame. The artifact rows name objects that are never read.
     */
    UUID finalizedScanVersion(UUID org, UUID venue, UUID scan, UUID floor, int number) {
        return finalizedScanVersion(org, venue, scan, floor, number, false);
    }

    /** As above; `withNavmesh` also pins the run's NAVMESH, NAVMESH_MANIFEST and NAVIGATION_GRAPH (all three, V26). */
    UUID finalizedScanVersion(UUID org, UUID venue, UUID scan, UUID floor, int number, boolean withNavmesh) {
        UUID session = jdbc.sql("SELECT capture_session_id FROM scan WHERE id = :s").param("s", scan).query(UUID.class).single();
        UUID frame = calibratedRunForScan(org, venue, scan, session, floor, "FLOOR_LOCAL");
        UUID run = jdbc.sql("SELECT id FROM pipeline_run WHERE scan_id = :s").param("s", scan).query(UUID.class).single();
        UUID cloud = publishedArtifact(org, venue, scan, run, "GEOMETRIC_CLEANUP", "SPLAT_CLEAN");
        UUID ksplat = publishedArtifact(org, venue, scan, run, "ARTIFACT_GENERATION", "KSPLAT");
        UUID version = jdbc.sql("""
                INSERT INTO scan_version (organization_id, venue_id, scan_id, floor_id, version_number, pipeline_run_id)
                VALUES (:o, :v, :s, :f, :n, :r) RETURNING id""")
            .param("o", org).param("v", venue).param("s", scan).param("f", floor).param("n", number).param("r", run)
            .query(UUID.class).single();
        var pins = new java.util.HashMap<>(java.util.Map.of(cloud, "SPLAT_CLEAN", ksplat, "KSPLAT"));
        if (withNavmesh) {
            for (String kind : java.util.List.of("NAVMESH", "NAVMESH_MANIFEST", "NAVIGATION_GRAPH")) {
                pins.put(publishedArtifact(org, venue, scan, run, "NAVIGATION_BAKING", kind), kind);
            }
        }
        for (var pin : pins.entrySet()) {
            jdbc.sql("INSERT INTO scan_version_artifact (scan_version_id, artifact_id, kind, owner_version_id) VALUES (:v, :a, :k, :v)")
                .param("v", version).param("a", pin.getKey()).param("k", pin.getValue()).update();
        }
        jdbc.sql("""
                UPDATE scan_version SET status = 'FINALIZED', finalized_at = now(), coordinate_frame_id = :frame,
                       provenance = CAST(:p AS jsonb) WHERE id = :id""")
            .param("frame", frame).param("p", "{\"bootstrap\": true, \"runId\": \"" + run + "\"}").param("id", version).update();
        return version;
    }

    /** A processing_artifact row of `kind` published by a SUCCEEDED `stage` of `run` (its object is never read). */
    UUID publishedArtifact(UUID org, UUID venue, UUID scan, UUID run, String stage, String kind) {
        UUID job = jdbc.sql("""
                INSERT INTO processing_job (organization_id, venue_id, scan_id, run_id, stage, status, started_at, finished_at)
                VALUES (:o, :v, :s, :r, :st, 'SUCCEEDED', now() - interval '1 minute', now()) RETURNING id""")
            .param("o", org).param("v", venue).param("s", scan).param("r", run).param("st", stage).query(UUID.class).single();
        UUID stageRun = jdbc.sql("""
                INSERT INTO pipeline_stage_run (id, organization_id, venue_id, run_id, job_id, stage, attempt, status, command,
                    started_at, finished_at)
                VALUES (gen_random_uuid(), :o, :v, :r, :j, :st, 1, 'SUCCEEDED', '{}'::jsonb, now() - interval '1 minute', now())
                RETURNING id""")
            .param("o", org).param("v", venue).param("r", run).param("j", job).param("st", stage).query(UUID.class).single();
        return jdbc.sql("""
                INSERT INTO processing_artifact (organization_id, venue_id, scan_id, job_id, stage, bucket, object_key,
                    checksum_sha256, content_type, size_bytes, kind, stage_run_id, contains_pii, partial)
                VALUES (:o, :v, :s, :j, :st, 'chaya-derived-test', :key, :sha, 'application/octet-stream', 1, :k, :sr, false, false)
                RETURNING id""")
            .param("o", org).param("v", venue).param("s", scan).param("j", job).param("st", stage)
            .param("key", "org/" + org + "/venue/" + venue + "/fixture/" + run + "/" + stage + "/" + kind).param("sha", "0".repeat(64)).param("k", kind).param("sr", stageRun)
            .query(UUID.class).single();
    }

    /**
     * A SUCCEEDED pipeline run for `scan` and an ACTIVE canonical coordinate frame for it -- an identity similarity with
     * the given horizontal datum, a mathematical test fixture, not a calibration of anything -- made the floor's current
     * frame. Returns the frame id.
     */
    UUID calibratedRunForScan(UUID org, UUID venue, UUID scan, UUID session, UUID floor, String datum) {
        UUID run = jdbc.sql("""
                INSERT INTO pipeline_run (organization_id, venue_id, scan_id, capture_session_id, status, quality, stages,
                    time_budget_seconds, deadline_at, finished_at, requested_by)
                VALUES (:o, :v, :s, :cs, 'SUCCEEDED', 'FINAL', '{}', 3600, now(), now(), 'fixture') RETURNING id""")
            .param("o", org).param("v", venue).param("s", scan).param("cs", session).query(UUID.class).single();
        jdbc.sql("UPDATE pipeline_run SET reconstruction_frame_run_id = id WHERE id = :r").param("r", run).update();
        return identityFrame(org, venue, floor, run, datum);
    }

    /** A new capture session, scan and calibrated run on `floor` (see calibratedRunForScan). Returns the frame id. */
    UUID calibratedFloor(UUID org, UUID venue, UUID floor, String datum) {
        UUID session = jdbc.sql("INSERT INTO capture_session (organization_id, venue_id, floor_id, operator_id) "
                + "VALUES (:o, :v, :f, 'operator-sub') RETURNING id")
            .param("o", org).param("v", venue).param("f", floor).query(UUID.class).single();
        return calibratedRunForScan(org, venue, scan(org, venue, session), session, floor, datum);
    }

    UUID identityFrame(UUID org, UUID venue, UUID floor, UUID run, String datum) {
        return identityFrame(org, venue, floor, run, datum, true);
    }

    /** As above; makeCurrent false leaves the floor's current frame alone (a floor that publishes a version only takes a
     * new reconstruction's frame by promotion, V28). */
    UUID identityFrame(UUID org, UUID venue, UUID floor, UUID run, String datum, boolean makeCurrent) {
        jdbc.sql("UPDATE coordinate_frame SET status = 'SUPERSEDED' WHERE source_run_id = :r AND status = 'ACTIVE'")
            .param("r", run).update();
        UUID frame = jdbc.sql("""
                INSERT INTO coordinate_frame (organization_id, venue_id, floor_id, source_run_id, version, metric_status,
                    gravity_status, horizontal_datum, scale, rotation_w, rotation_x, rotation_y, rotation_z,
                    translation_x, translation_y, translation_z, scale_source, gravity_source, method, inputs, residuals, calibrated_by)
                VALUES (:o, :v, :f, :r, (SELECT coalesce(max(version), 0) + 1 FROM coordinate_frame WHERE source_run_id = :r),
                    'METRIC', 'ALIGNED', :d, 1, 1, 0, 0, 0, 0, 0, 0,
                    'CONTROL_POINTS', 'CONTROL_POINTS', 'TEST_FIXTURE', '{"fixture": true}', '{}', 'fixture') RETURNING id""")
            .param("o", org).param("v", venue).param("f", floor).param("r", run).param("d", datum).query(UUID.class).single();
        if (floor != null && makeCurrent) {
            // As CoordinateFrameService#calibrate: a floor that publishes a version of another reconstruction keeps its frame
            // until this reconstruction's version is promoted (V28).
            jdbc.sql("""
                    UPDATE floor f SET current_coordinate_frame_id = :c
                     WHERE f.id = :f AND (f.current_scan_version_id IS NULL OR :r = (
                         SELECT cf.source_run_id FROM scan_version v JOIN coordinate_frame cf ON cf.id = v.coordinate_frame_id
                          WHERE v.id = f.current_scan_version_id))""")
                .param("c", frame).param("f", floor).param("r", run).update();
        }
        return frame;
    }

    /** A new capture, scan and SUCCEEDED run on a floor that already publishes a version, with an identity frame that is
     * not (yet) the floor's, then published as the floor's current version (see publish). Returns the version. */
    UUID publishNewReconstruction(UUID org, UUID venue, UUID floor) {
        UUID session = jdbc.sql("INSERT INTO capture_session (organization_id, venue_id, floor_id, operator_id) "
                + "VALUES (:o, :v, :f, 'operator-sub') RETURNING id")
            .param("o", org).param("v", venue).param("f", floor).query(UUID.class).single();
        UUID scan = scan(org, venue, session);
        UUID run = jdbc.sql("""
                INSERT INTO pipeline_run (organization_id, venue_id, scan_id, capture_session_id, status, quality, stages,
                    time_budget_seconds, deadline_at, finished_at, requested_by)
                VALUES (:o, :v, :s, :cs, 'SUCCEEDED', 'FINAL', '{}', 3600, now(), now(), 'fixture') RETURNING id""")
            .param("o", org).param("v", venue).param("s", scan).param("cs", session).query(UUID.class).single();
        jdbc.sql("UPDATE pipeline_run SET reconstruction_frame_run_id = id WHERE id = :r").param("r", run).update();
        UUID frame = identityFrame(org, venue, floor, run, "FLOOR_LOCAL", false);
        return publish(org, venue, floor, run, scan, frame);
    }

    /**
     * Publishes the reconstruction of the floor's current frame as the floor's current scan version (V28), the way
     * ScanVersionService#promote does, in one transaction: a FINALIZED version of that run (its own KSPLAT and SPLAT_CLEAN
     * pinned, the floor's frame recorded); the floor's DRAFT graphs and its unversioned POIs bound to it; those graphs
     * ACTIVE and every other ACTIVE graph of the floor retired; and floor.current_scan_version_id set. Returns the version.
     */
    UUID publishCurrentReconstruction(UUID org, UUID venue, UUID floor) {
        record Current(UUID frame, UUID run, UUID scan) {}
        Current c = jdbc.sql("""
                SELECT cf.id, r.id, r.scan_id FROM floor f JOIN coordinate_frame cf ON cf.id = f.current_coordinate_frame_id
                  JOIN pipeline_run r ON r.id = cf.source_run_id WHERE f.id = :f""")
            .param("f", floor).query((rs, i) -> new Current(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getObject(3, UUID.class))).single();
        return publish(org, venue, floor, c.run(), c.scan(), c.frame());
    }

    /** Publishes `run` (its frame `frame`) as the floor's current version; see publishCurrentReconstruction. */
    UUID publish(UUID org, UUID venue, UUID floor, UUID run, UUID scan, UUID frame) {
        record Current(UUID frame, UUID run, UUID scan) {}
        Current c = new Current(frame, run, scan);
        UUID cloud = publishedArtifact(org, venue, c.scan(), c.run(), "GEOMETRIC_CLEANUP", "SPLAT_CLEAN");
        UUID ksplat = publishedArtifact(org, venue, c.scan(), c.run(), "ARTIFACT_GENERATION", "KSPLAT");
        UUID version = jdbc.sql("""
                INSERT INTO scan_version (organization_id, venue_id, scan_id, floor_id, version_number, pipeline_run_id)
                VALUES (:o, :v, :s, :f, (SELECT coalesce(max(version_number), 0) + 1 FROM scan_version WHERE floor_id = :f), :r)
                RETURNING id""")
            .param("o", org).param("v", venue).param("s", c.scan()).param("f", floor).param("r", c.run()).query(UUID.class).single();
        for (var pin : java.util.Map.of(cloud, "SPLAT_CLEAN", ksplat, "KSPLAT").entrySet()) {
            jdbc.sql("INSERT INTO scan_version_artifact (scan_version_id, artifact_id, kind, owner_version_id) VALUES (:v, :a, :k, :v)")
                .param("v", version).param("a", pin.getKey()).param("k", pin.getValue()).update();
        }
        jdbc.sql("UPDATE scan_version SET status = 'FINALIZED', finalized_at = now(), coordinate_frame_id = :frame, "
                + "provenance = CAST(:p AS jsonb) WHERE id = :id")
            .param("frame", c.frame()).param("p", "{\"fixture\": true, \"runId\": \"" + c.run() + "\"}").param("id", version).update();
        // One statement, one transaction: the V28 checks run at its commit and see the finished promotion.
        jdbc.sql("""
                DO $$
                BEGIN
                    UPDATE navigation_graph SET status = 'RETIRED' WHERE floor_id = '%2$s' AND status = 'ACTIVE';
                    UPDATE navigation_graph SET scan_version_id = '%1$s' WHERE floor_id = '%2$s' AND status = 'DRAFT'
                       AND scan_version_id IS NULL;
                    UPDATE navigation_graph SET status = 'ACTIVE' WHERE floor_id = '%2$s' AND status = 'DRAFT'
                       AND scan_version_id = '%1$s';
                    UPDATE poi_version SET scan_version_id = '%1$s'
                     WHERE scan_version_id IS NULL AND poi_id IN (SELECT id FROM poi WHERE floor_id = '%2$s')
                       AND (coordinate_frame_id IS NULL
                            OR coordinate_frame_id IN (SELECT id FROM coordinate_frame WHERE source_run_id = '%4$s'));
                    UPDATE floor SET current_scan_version_id = '%1$s', current_coordinate_frame_id = '%3$s' WHERE id = '%2$s';
                END $$""".formatted(version, floor, c.frame(), c.run())).update();
        return version;
    }

    /**
     * What a promotion does for the floor's DRAFT graphs and unversioned POIs, for hand-built test graphs: a floor with a
     * calibrated frame and no published version publishes its current reconstruction (publishCurrentReconstruction); a
     * floor that already publishes one gets its DRAFT graphs and its unversioned POIs in a frame of that reconstruction
     * bound to it, those graphs ACTIVE in place of the profile's previous ones. A floor without a frame is left alone.
     */
    void publishDrafts(UUID org, UUID venue, UUID floor) {
        record State(UUID frame, UUID current) {}
        State s = jdbc.sql("SELECT current_coordinate_frame_id, current_scan_version_id FROM floor WHERE id = :f").param("f", floor)
            .query((rs, i) -> new State(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class))).single();
        if (s.frame() == null) {
            return;
        }
        if (s.current() == null) {
            publishCurrentReconstruction(org, venue, floor);
            return;
        }
        jdbc.sql("""
                DO $$
                BEGIN
                    UPDATE navigation_graph g SET status = 'RETIRED' WHERE g.floor_id = '%2$s' AND g.status = 'ACTIVE'
                       AND EXISTS (SELECT 1 FROM navigation_graph d WHERE d.floor_id = g.floor_id AND d.profile = g.profile
                                      AND d.status = 'DRAFT' AND d.scan_version_id IS NULL);
                    UPDATE navigation_graph SET scan_version_id = '%1$s' WHERE floor_id = '%2$s' AND status = 'DRAFT'
                       AND scan_version_id IS NULL;
                    UPDATE navigation_graph SET status = 'ACTIVE' WHERE floor_id = '%2$s' AND status = 'DRAFT'
                       AND scan_version_id = '%1$s';
                    UPDATE poi_version SET scan_version_id = '%1$s'
                     WHERE scan_version_id IS NULL AND poi_id IN (SELECT id FROM poi WHERE floor_id = '%2$s')
                       AND (coordinate_frame_id IS NULL OR coordinate_frame_id IN (
                            SELECT id FROM coordinate_frame WHERE source_run_id = (
                                SELECT cf.source_run_id FROM scan_version v JOIN coordinate_frame cf ON cf.id = v.coordinate_frame_id
                                 WHERE v.id = '%1$s')));
                END $$""".formatted(s.current(), floor)).update();
    }

    /** A new calibration of the reconstruction the floor's current frame belongs to, made the floor's frame (nothing is
     * re-projected). Returns the new frame. */
    UUID recalibrateCurrentReconstruction(UUID org, UUID venue, UUID floor) {
        UUID run = jdbc.sql("SELECT cf.source_run_id FROM floor f JOIN coordinate_frame cf ON cf.id = f.current_coordinate_frame_id "
                + "WHERE f.id = :f").param("f", floor).query(UUID.class).single();
        return identityFrame(org, venue, floor, run, "FLOOR_LOCAL");
    }

    /** A venue with a floor, capture session, scan and one draft version. */
    Tree tree() {
        UUID org = organization();
        UUID venue = venue(org);
        UUID floor = floor(org, venue, 0);
        UUID session = captureSession(org, venue);
        UUID scan = scan(org, venue, session);
        UUID version = draftScanVersion(org, venue, scan, floor, 1);
        return new Tree(org, venue, floor, session, scan, version);
    }

    record Tree(UUID org, UUID venue, UUID floor, UUID session, UUID scan, UUID version) {}
}
