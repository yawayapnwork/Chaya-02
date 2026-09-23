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
