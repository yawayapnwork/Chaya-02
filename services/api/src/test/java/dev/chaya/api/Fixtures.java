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

    /** A FINALIZED version -- the only kind RescanService accepts as a re-scan's source. */
    UUID finalizedScanVersion(UUID org, UUID venue, UUID scan, UUID floor, int number) {
        return jdbc.sql("""
                INSERT INTO scan_version (organization_id, venue_id, scan_id, floor_id, version_number, status, provenance, finalized_at)
                VALUES (:o, :v, :s, :f, :n, 'FINALIZED', '{"bootstrap": true}'::jsonb, now()) RETURNING id""")
            .param("o", org).param("v", venue).param("s", scan).param("f", floor).param("n", number)
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
