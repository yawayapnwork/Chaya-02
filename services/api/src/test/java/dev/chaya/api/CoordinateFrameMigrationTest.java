package dev.chaya.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * V17 against a database that already holds data from before coordinate frames existed: finished runs get their
 * reconstruction frame backfilled (the terminal-run guard must allow exactly that), and anchors that claimed to be
 * CALIBRATED without any known frame become STALE. Skipped when Docker is unavailable.
 */
@Testcontainers(disabledWithoutDocker = true)
class CoordinateFrameMigrationTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
        DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    @Test
    void v17BackfillsFinishedRunsAndMarksFramelessCalibratedAnchorsStale() {
        Flyway.configure().dataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()).target(MigrationVersion.fromVersion("16"))
            .load().migrate();
        JdbcTemplate db = new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
        UUID org = db.queryForObject("INSERT INTO organization (slug, name) VALUES ('mig-org', 'Org') RETURNING id", UUID.class);
        UUID venue = db.queryForObject("INSERT INTO venue (organization_id, slug, name) VALUES (?, 'mig-venue', 'Venue') RETURNING id",
            UUID.class, org);
        UUID floor = db.queryForObject("INSERT INTO floor (organization_id, venue_id, level, name) VALUES (?, ?, 0, 'F') RETURNING id",
            UUID.class, org, venue);
        UUID session = db.queryForObject("INSERT INTO capture_session (organization_id, venue_id, floor_id, operator_id) "
            + "VALUES (?, ?, ?, 'op') RETURNING id", UUID.class, org, venue, floor);
        UUID scan = db.queryForObject("INSERT INTO scan (organization_id, venue_id, capture_session_id) VALUES (?, ?, ?) RETURNING id",
            UUID.class, org, venue, session);
        UUID run = db.queryForObject("""
            INSERT INTO pipeline_run (organization_id, venue_id, scan_id, capture_session_id, status, quality, stages,
                time_budget_seconds, deadline_at, finished_at, requested_by)
            VALUES (?, ?, ?, ?, 'SUCCEEDED', 'FINAL', '{}', 3600, now(), now(), 'm') RETURNING id""", UUID.class, org, venue, scan, session);
        UUID anchor = db.queryForObject("""
            INSERT INTO ar_anchor (organization_id, venue_id, floor_id, marker_type, marker_identifier, physical_x, physical_y,
                physical_z, digital_x, digital_y, digital_z, calibration_status, last_calibrated_at)
            VALUES (?, ?, ?, 'QR_CODE', 'm1', 0, 0, 0, 1, 2, 3, 'CALIBRATED', now()) RETURNING id""", UUID.class, org, venue, floor);

        Flyway.configure().dataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()).load().migrate();

        assertThat(db.queryForObject("SELECT reconstruction_frame_run_id FROM pipeline_run WHERE id = ?", UUID.class, run)).isEqualTo(run);
        assertThat(db.queryForObject("SELECT calibration_status FROM ar_anchor WHERE id = ?", String.class, anchor)).isEqualTo("STALE");
        assertThatThrownBy(() -> db.update("UPDATE pipeline_run SET reconstruction_frame_run_id = NULL WHERE id = ?", run))
            .hasMessageContaining("immutable");
        assertThatThrownBy(() -> db.update("UPDATE pipeline_run SET requested_by = 'x' WHERE id = ?", run))
            .hasMessageContaining("terminal");
    }
}
