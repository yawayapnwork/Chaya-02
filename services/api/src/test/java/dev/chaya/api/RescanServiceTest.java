package dev.chaya.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.chaya.api.rescan.RescanDtos;
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
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("REGION_TOO_SMALL"));

        RegionGeometry huge = new RegionGeometry(List.of(
            List.of(0.0, 0.0), List.of(0.0, 1000.0), List.of(1000.0, 1000.0), List.of(1000.0, 0.0))); // 1,000,000 m^2
        assertThatThrownBy(() -> rescan.initiate(actor, t.venue(), t.floor(), new RescanRequest(parent, huge, null)))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("REGION_TOO_LARGE"));
    }

    @Test
    void cannotRescanAVersionWhoseReconstructionHasNoCalibratedFrame() {
        var t = fx.tree();
        UUID parent = fx.finalizedScanVersion(t.org(), t.venue(), t.scan(), t.floor(), 2);
        Actor actor = actorFor(t.org(), t.venue());
        // Its reconstruction was recalibrated after the version finalized, and the new calibration has a scale but no
        // gravity: metric, not canonical. The version keeps the frame it recorded, but the region (canonical metres) can no
        // longer be located in the reconstruction the worker would receive.
        UUID run = jdbc.sql("SELECT pipeline_run_id FROM scan_version WHERE id = :v").param("v", parent).query(UUID.class).single();
        jdbc.sql("UPDATE coordinate_frame SET status = 'SUPERSEDED' WHERE source_run_id = :r").param("r", run).update();
        jdbc.sql("""
                INSERT INTO coordinate_frame (organization_id, venue_id, floor_id, source_run_id, version, metric_status,
                    gravity_status, horizontal_datum, scale, scale_source, gravity_source, method, inputs, residuals, calibrated_by)
                VALUES (:o, :v, :f, :r, 2, 'METRIC', 'NOT_ALIGNED', 'NONE', 1, 'MEASURED_DISTANCES', 'NONE', 'TEST_FIXTURE',
                    '{"fixture": true}', '{}', 'fixture')""")
            .param("o", t.org()).param("v", t.venue()).param("f", t.floor()).param("r", run).update();
        assertThatThrownBy(() -> rescan.initiate(actor, t.venue(), t.floor(), new RescanRequest(parent, SQUARE, null)))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("NOT_CALIBRATED"));
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

    /** A SUCCEEDED full-venue run on `floor` (its own capture session and scan). */
    private UUID fullRun(Fixtures.Tree t, UUID floor) {
        UUID session = jdbc.sql("INSERT INTO capture_session (organization_id, venue_id, floor_id, operator_id) "
                + "VALUES (:o, :v, :f, 'operator-sub') RETURNING id")
            .param("o", t.org()).param("v", t.venue()).param("f", floor).query(UUID.class).single();
        UUID scan = fx.scan(t.org(), t.venue(), session);
        UUID run = jdbc.sql("""
                INSERT INTO pipeline_run (organization_id, venue_id, scan_id, capture_session_id, status, quality, stages,
                    time_budget_seconds, deadline_at, finished_at, requested_by)
                VALUES (:o, :v, :s, :cs, 'SUCCEEDED', 'FINAL', '{}', 3600, now(), clock_timestamp(), 'tester')
                RETURNING id
                """)
            .param("o", t.org()).param("v", t.venue()).param("s", scan).param("cs", session).query(UUID.class).single();
        jdbc.sql("UPDATE pipeline_run SET reconstruction_frame_run_id = id WHERE id = :r").param("r", run).update();
        return run;
    }

    private UUID scanOf(UUID run) {
        return jdbc.sql("SELECT scan_id FROM pipeline_run WHERE id = :r").param("r", run).query(UUID.class).single();
    }

    @Test
    void finalizeCurrentPinsTheRunsOwnArtifactsAndFrameAndNumbersPerFloor() {
        var tree = fx.tree();
        // A floor with no versions at all (fx.tree()'s own floor already carries a DRAFT version).
        UUID floor = fx.floor(tree.org(), tree.venue(), 1);
        var t = new Fixtures.Tree(tree.org(), tree.venue(), floor, tree.session(), tree.scan(), null);
        Actor actor = actorFor(t.org(), t.venue());
        assertThat(rescan.listVersions(actor, t.venue(), floor)).isEmpty();

        // No successful full-venue run exists yet for this floor.
        assertThatThrownBy(() -> rescan.finalizeCurrent(actor, t.venue(), floor)).isInstanceOf(NotFoundException.class);

        // A run that succeeded but was never calibrated is not a version: its POIs and navigation have no frame.
        UUID run1 = fullRun(t, floor);
        assertThatThrownBy(() -> rescan.finalizeCurrent(actor, t.venue(), floor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("NOT_CALIBRATED"));
        // Calibrated, but it published no viewer asset or cloud: nothing a version could be made of.
        UUID frame1 = fx.identityFrame(t.org(), t.venue(), floor, run1, "FLOOR_LOCAL");
        assertThatThrownBy(() -> rescan.finalizeCurrent(actor, t.venue(), floor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("VERSION_INCOMPLETE"));
        assertThat(rescan.listVersions(actor, t.venue(), floor)).as("a refused bootstrap leaves nothing behind").isEmpty();

        UUID cloud1 = fx.publishedArtifact(t.org(), t.venue(), scanOf(run1), run1, "GEOMETRIC_CLEANUP", "SPLAT_CLEAN");
        UUID ksplat1 = fx.publishedArtifact(t.org(), t.venue(), scanOf(run1), run1, "ARTIFACT_GENERATION", "KSPLAT");
        ScanVersionView v1 = rescan.finalizeCurrent(actor, t.venue(), floor);
        assertThat(v1.versionNumber()).isEqualTo(1);
        assertThat(v1.status()).isEqualTo("FINALIZED");
        assertThat(v1.parentVersionId()).isNull();
        assertThat(v1.pipelineRunId()).isEqualTo(run1);
        assertThat(v1.coordinateFrameId()).isEqualTo(frame1);
        assertThat(v1.artifacts()).extracting(RescanDtos.PinnedArtifact::artifactId).containsExactlyInAnyOrder(cloud1, ksplat1);
        assertThat(v1.artifacts()).allSatisfy(a -> assertThat(a.ownerVersionId()).isEqualTo(v1.id()));

        // Idempotent: finalizing the same run again returns the same version, not a duplicate.
        assertThat(rescan.finalizeCurrent(actor, t.venue(), floor).id()).isEqualTo(v1.id());
        assertThat(rescan.listVersions(actor, t.venue(), floor)).hasSize(1);

        // A later full reconstruction of the floor is the floor's next number (it used to be "1" again), with no parent:
        // its geometry is not derived from version 1.
        UUID run2 = fullRun(t, floor);
        UUID frame2 = fx.identityFrame(t.org(), t.venue(), floor, run2, "FLOOR_LOCAL");
        fx.publishedArtifact(t.org(), t.venue(), scanOf(run2), run2, "GEOMETRIC_CLEANUP", "SPLAT_CLEAN");
        fx.publishedArtifact(t.org(), t.venue(), scanOf(run2), run2, "ARTIFACT_GENERATION", "KSPLAT");
        ScanVersionView v2 = rescan.finalizeCurrent(actor, t.venue(), floor);
        assertThat(v2.versionNumber()).isEqualTo(2);
        assertThat(v2.parentVersionId()).isNull();
        assertThat(v2.pipelineRunId()).isEqualTo(run2);
        assertThat(v2.coordinateFrameId()).isEqualTo(frame2);
        assertThat(v2.artifacts()).extracting(RescanDtos.PinnedArtifact::artifactId).doesNotContain(cloud1, ksplat1);
        assertThat(rescan.listVersions(actor, t.venue(), floor)).extracting(ScanVersionView::versionNumber).containsExactly(2, 1);
    }
}
