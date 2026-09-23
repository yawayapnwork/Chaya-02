package dev.chaya.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.chaya.api.rescan.RescanDtos.RegionGeometry;
import dev.chaya.api.rescan.RescanDtos.RescanInitiated;
import dev.chaya.api.rescan.RescanDtos.RescanRequest;
import dev.chaya.api.rescan.RescanDtos.ScanVersionView;
import dev.chaya.api.rescan.RescanService;
import dev.chaya.api.security.Actor;
import dev.chaya.api.security.Role;
import dev.chaya.api.web.ApiException;
import dev.chaya.api.web.NotFoundException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Validation and versioning rules of RescanService, exercised directly against real Postgres (steps 1-2
 * and the version-selection/bootstrap side of docs/rescan.md). The alignment-gate and splice flow, which
 * needs a full worker-report cycle, is exercised in RescanControlPlaneTest. Skipped, not failed, without
 * Docker (AbstractIntegrationTest).
 */
class RescanServiceTest extends AbstractIntegrationTest {

    @Autowired
    private RescanService rescan;

    private Actor actorFor(UUID org, UUID venue) {
        return new Actor(Actor.Kind.USER, "test-operator", org, Set.of(venue), Set.of(Role.OPERATOR));
    }

    private static final RegionGeometry SQUARE = new RegionGeometry(List.of(
        List.of(0.0, 0.0), List.of(0.0, 3.0), List.of(3.0, 3.0), List.of(3.0, 0.0))); // 9 m^2

    @Test
    void cannotRescanAgainstAnUnfinalizedVersion() {
        var t = fx.tree(); // fx.tree()'s version is DRAFT
        Actor actor = actorFor(t.org(), t.venue());
        assertThatThrownBy(() -> rescan.initiate(actor, t.venue(), t.floor(), new RescanRequest(t.version(), SQUARE, null)))
            .isInstanceOf(ApiException.class).hasMessageContaining("FINALIZED");
    }

    @Test
    void cannotRescanAVersionFromAnotherFloor() {
        var t = fx.tree();
        Actor actor = actorFor(t.org(), t.venue());
        UUID otherFloor = fx.floor(t.org(), t.venue(), 1);
        UUID otherScan = fx.scan(t.org(), t.venue(), fx.captureSession(t.org(), t.venue()));
        UUID finalizedElsewhere = fx.finalizedScanVersion(t.org(), t.venue(), otherScan, otherFloor, 1);
        assertThatThrownBy(() -> rescan.initiate(actor, t.venue(), t.floor(), new RescanRequest(finalizedElsewhere, SQUARE, null)))
            .isInstanceOf(ApiException.class).hasMessageContaining("different floor");
    }

    @Test
    void rescanOfAnUnknownVersionIs404() {
        var t = fx.tree();
        Actor actor = actorFor(t.org(), t.venue());
        assertThatThrownBy(() -> rescan.initiate(actor, t.venue(), t.floor(), new RescanRequest(UUID.randomUUID(), SQUARE, null)))
            .isInstanceOf(NotFoundException.class);
    }

    @Test
    void regionMustBeWithinConfiguredAreaBounds() {
        var t = fx.tree();
        UUID parent = fx.finalizedScanVersion(t.org(), t.venue(), t.scan(), t.floor(), 2);
        Actor actor = actorFor(t.org(), t.venue());

        RegionGeometry tiny = new RegionGeometry(List.of(
            List.of(0.0, 0.0), List.of(0.0, 0.01), List.of(0.01, 0.01), List.of(0.01, 0.0))); // 0.0001 m^2
        assertThatThrownBy(() -> rescan.initiate(actor, t.venue(), t.floor(), new RescanRequest(parent, tiny, null)))
            .isInstanceOf(ApiException.class).hasMessageContaining("REGION_TOO_SMALL");

        RegionGeometry huge = new RegionGeometry(List.of(
            List.of(0.0, 0.0), List.of(0.0, 1000.0), List.of(1000.0, 1000.0), List.of(1000.0, 0.0))); // 1,000,000 m^2
        assertThatThrownBy(() -> rescan.initiate(actor, t.venue(), t.floor(), new RescanRequest(parent, huge, null)))
            .isInstanceOf(ApiException.class).hasMessageContaining("REGION_TOO_LARGE");
    }

    @Test
    void initiateCreatesARescanScopedCaptureSessionAndAuditsWhoAndWhichRegion() {
        var t = fx.tree();
        UUID parent = fx.finalizedScanVersion(t.org(), t.venue(), t.scan(), t.floor(), 2);
        Actor actor = actorFor(t.org(), t.venue());

        RescanInitiated result = rescan.initiate(actor, t.venue(), t.floor(), new RescanRequest(parent, SQUARE, null));
        assertThat(result.parentVersionId()).isEqualTo(parent);
        assertThat(result.scanVersionId()).as("not created until processing starts").isNull();
        assertThat(result.regionAreaSquareMeters()).isCloseTo(9.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(result.navigationRebuildRequired()).as("no navigation graph exists yet for this floor").isFalse();

        var captured = jdbc.sql("SELECT parent_scan_version_id, region_geometry FROM capture_session WHERE id = :c")
            .param("c", result.captureId()).query().listOfRows().get(0);
        assertThat(captured.get("parent_scan_version_id")).isEqualTo(parent);
        assertThat(captured.get("region_geometry")).isNotNull();

        assertThat(jdbc.sql("SELECT count(*) FROM audit_log WHERE action = 'rescan.initiate' AND resource_id = :c")
            .param("c", result.captureId()).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void listVersionsAndBootstrapFinalizeCurrent() {
        var t = fx.tree();
        Actor actor = actorFor(t.org(), t.venue());
        assertThat(rescan.listVersions(actor, t.venue(), t.floor())).isEmpty();

        // No successful full-venue run exists yet for this floor.
        assertThatThrownBy(() -> rescan.finalizeCurrent(actor, t.venue(), t.floor())).isInstanceOf(NotFoundException.class);

        UUID session2 = jdbc.sql("INSERT INTO capture_session (organization_id, venue_id, floor_id, operator_id) "
                + "VALUES (:o, :v, :f, 'operator-sub') RETURNING id")
            .param("o", t.org()).param("v", t.venue()).param("f", t.floor()).query(UUID.class).single();
        UUID scan2 = fx.scan(t.org(), t.venue(), session2);
        UUID runId = jdbc.sql("""
                INSERT INTO pipeline_run (organization_id, venue_id, scan_id, capture_session_id, status, quality, stages,
                    time_budget_seconds, deadline_at, finished_at, requested_by)
                VALUES (:o, :v, :s, :cs, 'SUCCEEDED', 'FINAL', '{}', 3600, now(), now(), 'tester')
                RETURNING id
                """)
            .param("o", t.org()).param("v", t.venue()).param("s", scan2).param("cs", session2).query(UUID.class).single();

        ScanVersionView bootstrapped = rescan.finalizeCurrent(actor, t.venue(), t.floor());
        assertThat(bootstrapped.versionNumber()).isEqualTo(1);
        assertThat(bootstrapped.status()).isEqualTo("FINALIZED");
        assertThat(bootstrapped.parentVersionId()).isNull();

        // Idempotent: finalizing again returns the same version, not a duplicate.
        ScanVersionView again = rescan.finalizeCurrent(actor, t.venue(), t.floor());
        assertThat(again.id()).isEqualTo(bootstrapped.id());
        assertThat(rescan.listVersions(actor, t.venue(), t.floor())).hasSize(1);
        assertThat(runId).isNotNull();
    }
}
