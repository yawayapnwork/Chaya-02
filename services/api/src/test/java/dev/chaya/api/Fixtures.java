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
        for (var pin : java.util.Map.of(cloud, "SPLAT_CLEAN", ksplat, "KSPLAT").entrySet()) {
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
            .param("key", "fixture/" + run + "/" + stage + "/" + kind).param("sha", "0".repeat(64)).param("k", kind).param("sr", stageRun)
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
        if (floor != null) {
            jdbc.sql("UPDATE floor SET current_coordinate_frame_id = :c WHERE id = :f").param("c", frame).param("f", floor).update();
        }
        return frame;
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
