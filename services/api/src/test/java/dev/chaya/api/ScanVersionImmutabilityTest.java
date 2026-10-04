package dev.chaya.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/** The database's own guarantees about scan_version (V3, V19, V23), independent of any service code. */
class ScanVersionImmutabilityTest extends AbstractIntegrationTest {

    /** A second scan (own capture session) of the tree's venue. */
    private UUID otherScan(Fixtures.Tree t) {
        return fx.scan(t.org(), t.venue(), fx.captureSession(t.org(), t.venue()));
    }

    @Test
    void draftVersionCanBeEditedAndDeleted() {
        var t = fx.tree();
        jdbc.sql("UPDATE scan_version SET provenance = CAST('{\"k\":1}' AS jsonb) WHERE id = :id")
            .param("id", t.version()).update();
        assertThat(jdbc.sql("DELETE FROM scan_version WHERE id = :id").param("id", t.version()).update()).isEqualTo(1);
    }

    @Test
    void aVersionIsCreatedDraftNeverInsertedFinalized() {
        var t = fx.tree();
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO scan_version (organization_id, venue_id, scan_id, floor_id, version_number, status, provenance, finalized_at)
                VALUES (:o, :v, :s, :f, 2, 'FINALIZED', '{"pipeline":"test-fixture"}'::jsonb, now())""")
            .param("o", t.org()).param("v", t.venue()).param("s", otherScan(t)).param("f", t.floor()).update())
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("created DRAFT");
    }

    @Test
    void finalizedVersionRejectsUpdatesAndDeletes() {
        var t = fx.tree();
        UUID version = fx.finalizedScanVersion(t.org(), t.venue(), otherScan(t), t.floor(), 2);

        assertThatThrownBy(() -> jdbc.sql("UPDATE scan_version SET provenance = CAST('{\"x\":2}' AS jsonb) WHERE id = :id")
            .param("id", version).update())
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("finalized and immutable");
        assertThatThrownBy(() -> jdbc.sql("UPDATE scan_version SET status = 'DRAFT', finalized_at = NULL WHERE id = :id")
            .param("id", version).update())
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("UPDATE scan_version SET coordinate_frame_id = NULL WHERE id = :id")
            .param("id", version).update())
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM scan_version WHERE id = :id").param("id", version).update())
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aFinalizedVersionsArtifactsAreFixed() {
        var t = fx.tree();
        UUID scan = otherScan(t);
        UUID version = fx.finalizedScanVersion(t.org(), t.venue(), scan, t.floor(), 2);
        UUID run = jdbc.sql("SELECT pipeline_run_id FROM scan_version WHERE id = :v").param("v", version).query(UUID.class).single();
        UUID later = fx.publishedArtifact(t.org(), t.venue(), scan, run, "PLANE_FITTING", "PLANE_MODEL");

        assertThatThrownBy(() -> jdbc.sql("INSERT INTO scan_version_artifact (scan_version_id, artifact_id, kind, owner_version_id) "
                + "VALUES (:v, :a, 'PLANE_MODEL', :v)").param("v", version).param("a", later).update())
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("its artifacts are fixed");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM scan_version_artifact WHERE scan_version_id = :v").param("v", version).update())
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("write-once");
        assertThatThrownBy(() -> jdbc.sql("UPDATE scan_version_artifact SET kind = 'X' WHERE scan_version_id = :v").param("v", version).update())
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("write-once");
    }

    @Test
    void aVersionOnlyPinsItsOwnRunsArtifactsOrItsParentsPins() {
        var t = fx.tree();
        UUID parent = fx.finalizedScanVersion(t.org(), t.venue(), otherScan(t), t.floor(), 2);
        UUID strangerScan = otherScan(t);
        UUID stranger = fx.finalizedScanVersion(t.org(), t.venue(), strangerScan, t.floor(), 3);
        UUID strangerRun = jdbc.sql("SELECT pipeline_run_id FROM scan_version WHERE id = :v").param("v", stranger).query(UUID.class).single();
        UUID strangerPlanes = fx.publishedArtifact(t.org(), t.venue(), strangerScan, strangerRun, "PLANE_FITTING", "PLANE_MODEL");
        UUID parentKsplat = jdbc.sql("SELECT artifact_id FROM scan_version_artifact WHERE scan_version_id = :v AND kind = 'KSPLAT'")
            .param("v", parent).query(UUID.class).single();
        UUID strangerKsplat = jdbc.sql("SELECT artifact_id FROM scan_version_artifact WHERE scan_version_id = :v AND kind = 'KSPLAT'")
            .param("v", stranger).query(UUID.class).single();

        UUID child = jdbc.sql("""
                INSERT INTO scan_version (organization_id, venue_id, scan_id, floor_id, version_number, parent_version_id)
                VALUES (:o, :v, :s, :f, 4, :p) RETURNING id""")
            .param("o", t.org()).param("v", t.venue()).param("s", otherScan(t)).param("f", t.floor()).param("p", parent)
            .query(UUID.class).single();
        String pin = "INSERT INTO scan_version_artifact (scan_version_id, artifact_id, kind, owner_version_id) VALUES (:v, :a, :k, :owner)";
        // Another run's artifact, claimed as the child's own: refused.
        assertThatThrownBy(() -> jdbc.sql(pin).param("v", child).param("a", strangerPlanes).param("k", "PLANE_MODEL").param("owner", child).update())
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("belongs to run");
        // Another version's pin, claimed as inherited: refused -- only the parent's pins can be inherited.
        assertThatThrownBy(() -> jdbc.sql(pin).param("v", child).param("a", strangerKsplat).param("k", "KSPLAT").param("owner", stranger).update())
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("parent");
        // A kind that is not the artifact's: refused.
        assertThatThrownBy(() -> jdbc.sql(pin).param("v", child).param("a", parentKsplat).param("k", "NAVMESH").param("owner", parent).update())
            .isInstanceOf(DataIntegrityViolationException.class);
        // The parent's own pin, inherited with its owner: allowed.
        jdbc.sql(pin).param("v", child).param("a", parentKsplat).param("k", "KSPLAT").param("owner", parent).update();
    }

    @Test
    void navigationIsNeverInheritedAndIsPinnedAllOrNothing() {
        var t = fx.tree();
        UUID parent = fx.finalizedScanVersion(t.org(), t.venue(), otherScan(t), t.floor(), 2, true);
        UUID parentNavmesh = jdbc.sql("SELECT artifact_id FROM scan_version_artifact WHERE scan_version_id = :v AND kind = 'NAVMESH'")
            .param("v", parent).query(UUID.class).single();
        UUID scan = otherScan(t);
        UUID session = jdbc.sql("SELECT capture_session_id FROM scan WHERE id = :s").param("s", scan).query(UUID.class).single();
        UUID frame = fx.calibratedRunForScan(t.org(), t.venue(), scan, session, t.floor(), "FLOOR_LOCAL");
        UUID run = jdbc.sql("SELECT id FROM pipeline_run WHERE scan_id = :s").param("s", scan).query(UUID.class).single();
        UUID child = jdbc.sql("""
                INSERT INTO scan_version (organization_id, venue_id, scan_id, floor_id, version_number, parent_version_id, pipeline_run_id)
                VALUES (:o, :v, :s, :f, 3, :p, :r) RETURNING id""")
            .param("o", t.org()).param("v", t.venue()).param("s", scan).param("f", t.floor()).param("p", parent).param("r", run)
            .query(UUID.class).single();
        String pin = "INSERT INTO scan_version_artifact (scan_version_id, artifact_id, kind, owner_version_id) VALUES (:v, :a, :k, :owner)";
        String finalize = "UPDATE scan_version SET status = 'FINALIZED', finalized_at = now(), coordinate_frame_id = :frame, "
            + "provenance = CAST('{\"pipeline\":\"test-fixture\"}' AS jsonb) WHERE id = :id";

        // The parent's own navmesh pin, claimed as inherited: refused, though inheriting any other parent pin is allowed.
        assertThatThrownBy(() -> jdbc.sql(pin).param("v", child).param("a", parentNavmesh).param("k", "NAVMESH").param("owner", parent).update())
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("cannot inherit");
        for (String kind : List.of("KSPLAT", "SPLAT_CLEAN")) {
            jdbc.sql(pin).param("v", child).param("a", fx.publishedArtifact(t.org(), t.venue(), scan, run, "ARTIFACT_GENERATION", kind)).param("k", kind)
                .param("owner", child).update();
        }
        // No navigation of its own while the parent has a navmesh: not finalized.
        assertThatThrownBy(() -> jdbc.sql(finalize).param("frame", frame).param("id", child).update())
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("without its own navmesh");
        // Its own navmesh without the graph baked with it: not finalized either.
        for (String kind : List.of("NAVMESH", "NAVMESH_MANIFEST")) {
            jdbc.sql(pin).param("v", child).param("a", fx.publishedArtifact(t.org(), t.venue(), scan, run, "NAVIGATION_BAKING", kind))
                .param("k", kind).param("owner", child).update();
        }
        assertThatThrownBy(() -> jdbc.sql(finalize).param("frame", frame).param("id", child).update())
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("all three or none");
        jdbc.sql(pin).param("v", child).param("a", fx.publishedArtifact(t.org(), t.venue(), scan, run, "NAVIGATION_BAKING", "NAVIGATION_GRAPH"))
            .param("k", "NAVIGATION_GRAPH").param("owner", child).update();
        assertThat(jdbc.sql(finalize).param("frame", frame).param("id", child).update()).isEqualTo(1);
    }

    @Test
    void finalizationNeedsTheRunItsFrameAndItsOwnViewerAssetAndCloud() {
        var t = fx.tree();
        String finalize = "UPDATE scan_version SET status = 'FINALIZED', finalized_at = now(), coordinate_frame_id = :frame, "
            + "provenance = CAST('{\"pipeline\":\"test-fixture\"}' AS jsonb) WHERE id = :id";
        // No run, no frame.
        assertThatThrownBy(() -> jdbc.sql("UPDATE scan_version SET status = 'FINALIZED', finalized_at = now(), "
                + "provenance = CAST('{\"pipeline\":\"test-fixture\"}' AS jsonb) WHERE id = :id").param("id", t.version()).update())
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("without its run and coordinate frame");
        // No provenance.
        assertThatThrownBy(() -> jdbc.sql("UPDATE scan_version SET status = 'FINALIZED', finalized_at = now() WHERE id = :id")
            .param("id", t.version()).update())
            .isInstanceOf(DataIntegrityViolationException.class);

        UUID frame = fx.calibratedRunForScan(t.org(), t.venue(), t.scan(), t.session(), t.floor(), "FLOOR_LOCAL");
        UUID run = jdbc.sql("SELECT id FROM pipeline_run WHERE scan_id = :s").param("s", t.scan()).query(UUID.class).single();
        jdbc.sql("UPDATE scan_version SET pipeline_run_id = :r WHERE id = :v").param("r", run).param("v", t.version()).update();
        // A frame of another reconstruction.
        UUID foreignFrame = fx.calibratedFloor(t.org(), t.venue(), fx.floor(t.org(), t.venue(), 5), "FLOOR_LOCAL");
        assertThatThrownBy(() -> jdbc.sql(finalize).param("frame", foreignFrame).param("id", t.version()).update())
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("is not a frame of run");
        // Its own frame, but nothing pinned.
        assertThatThrownBy(() -> jdbc.sql(finalize).param("frame", frame).param("id", t.version()).update())
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("viewer asset (KSPLAT) and cloud");
        // Its run is fixed once set.
        UUID otherRun = jdbc.sql("SELECT pipeline_run_id FROM scan_version WHERE id = :v")
            .param("v", fx.finalizedScanVersion(t.org(), t.venue(), otherScan(t), t.floor(), 2)).query(UUID.class).single();
        assertThatThrownBy(() -> jdbc.sql("UPDATE scan_version SET pipeline_run_id = :r WHERE id = :v")
            .param("r", otherRun).param("v", t.version()).update())
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("immutable");

        String pin = "INSERT INTO scan_version_artifact (scan_version_id, artifact_id, kind, owner_version_id) VALUES (:v, :a, :k, :v)";
        jdbc.sql(pin).param("v", t.version()).param("a", fx.publishedArtifact(t.org(), t.venue(), t.scan(), run, "ARTIFACT_GENERATION", "KSPLAT"))
            .param("k", "KSPLAT").update();
        jdbc.sql(pin).param("v", t.version()).param("a", fx.publishedArtifact(t.org(), t.venue(), t.scan(), run, "GEOMETRIC_CLEANUP", "SPLAT_CLEAN"))
            .param("k", "SPLAT_CLEAN").update();
        assertThat(jdbc.sql(finalize).param("frame", frame).param("id", t.version()).update()).isEqualTo(1);
    }

    @Test
    void newVersionMayReferenceAFinalizedParent() {
        var t = fx.tree();
        UUID parent = fx.finalizedScanVersion(t.org(), t.venue(), otherScan(t), t.floor(), 2);
        UUID child = jdbc.sql("""
                INSERT INTO scan_version (organization_id, venue_id, scan_id, floor_id, version_number, parent_version_id)
                VALUES (:o, :v, :s, :f, 3, :p) RETURNING id""")
            .param("o", t.org()).param("v", t.venue()).param("s", otherScan(t)).param("f", t.floor()).param("p", parent)
            .query(UUID.class).single();
        assertThat(child).isNotNull();
        // The lineage of a version is fixed.
        assertThatThrownBy(() -> jdbc.sql("UPDATE scan_version SET parent_version_id = NULL WHERE id = :v").param("v", child).update())
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.sql("UPDATE scan_version SET version_number = 9 WHERE id = :v").param("v", child).update())
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("immutable");
    }

    @Test
    void versionNumbersAreUniquePerScanAndPerFloor() {
        var t = fx.tree();
        assertThatThrownBy(() -> fx.draftScanVersion(t.org(), t.venue(), t.scan(), t.floor(), 1))
            .isInstanceOf(DataIntegrityViolationException.class);
        // Another scan of the same floor cannot take version 1 again (review V-4).
        assertThatThrownBy(() -> fx.draftScanVersion(t.org(), t.venue(), otherScan(t), t.floor(), 1))
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("scan_version_floor_number_idx");
        // Another floor has its own numbering.
        assertThat(fx.draftScanVersion(t.org(), t.venue(), otherScan(t), fx.floor(t.org(), t.venue(), 3), 1)).isNotNull();
    }

    @Test
    void artifactsAreWriteOnceAndCannotJoinAFinalizedVersion() {
        var t = fx.tree();
        UUID job = jdbc.sql("INSERT INTO processing_job (organization_id, venue_id, scan_id, scan_version_id, stage) VALUES (:o,:v,:s,:sv,'SPLAT_TRAINING') RETURNING id")
            .param("o", t.org()).param("v", t.venue()).param("s", t.scan()).param("sv", t.version())
            .query(UUID.class).single();
        String sql = """
                INSERT INTO processing_artifact (organization_id, venue_id, scan_id, scan_version_id, job_id, stage,
                                                 bucket, object_key, checksum_sha256, content_type, size_bytes)
                VALUES (:o, :v, :s, :sv, :j, 'SPLAT_TRAINING', 'chaya-derived', :key, :sum, 'application/octet-stream', 10)
                RETURNING id""";
        String sum = "a".repeat(64);
        UUID artifact = jdbc.sql(sql).param("o", t.org()).param("v", t.venue()).param("s", t.scan())
            .param("sv", t.version()).param("j", job).param("key", "k/" + t.version()).param("sum", sum)
            .query(UUID.class).single();

        // Same object key again: refused rather than silently replaced.
        assertThatThrownBy(() -> jdbc.sql(sql).param("o", t.org()).param("v", t.venue()).param("s", t.scan())
            .param("sv", t.version()).param("j", job).param("key", "k/" + t.version()).param("sum", sum)
            .query(UUID.class).single()).isInstanceOf(DataIntegrityViolationException.class);
        // Overwrite / delete of an existing record: refused.
        assertThatThrownBy(() -> jdbc.sql("UPDATE processing_artifact SET checksum_sha256 = :c WHERE id = :id")
            .param("c", "b".repeat(64)).param("id", artifact).update())
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("write-once");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM processing_artifact WHERE id = :id").param("id", artifact).update())
            .isInstanceOf(DataIntegrityViolationException.class);
        // Bad checksum format: refused.
        assertThatThrownBy(() -> jdbc.sql(sql).param("o", t.org()).param("v", t.venue()).param("s", t.scan())
            .param("sv", t.version()).param("j", job).param("key", "k2/" + t.version()).param("sum", "xyz")
            .query(UUID.class).single()).isInstanceOf(DataIntegrityViolationException.class);

        // Nothing can be added to a finalized version.
        UUID scan = otherScan(t);
        UUID finalized = fx.finalizedScanVersion(t.org(), t.venue(), scan, t.floor(), 2);
        UUID finalizedJob = jdbc.sql("INSERT INTO processing_job (organization_id, venue_id, scan_id, scan_version_id, stage) VALUES (:o,:v,:s,:sv,'SPLAT_TRAINING') RETURNING id")
            .param("o", t.org()).param("v", t.venue()).param("s", scan).param("sv", finalized).query(UUID.class).single();
        assertThatThrownBy(() -> jdbc.sql(sql).param("o", t.org()).param("v", t.venue()).param("s", scan)
            .param("sv", finalized).param("j", finalizedJob).param("key", "k3/" + finalized).param("sum", sum)
            .query(UUID.class).single())
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("finalized");
    }
}
