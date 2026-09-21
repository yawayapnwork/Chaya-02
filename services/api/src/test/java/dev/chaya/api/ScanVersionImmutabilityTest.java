package dev.chaya.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class ScanVersionImmutabilityTest extends AbstractIntegrationTest {

    private void finalizeVersion(UUID id) {
        jdbc.sql("""
                UPDATE scan_version SET status = 'FINALIZED', finalized_at = now(),
                       provenance = CAST('{"pipeline":"test-fixture"}' AS jsonb)
                 WHERE id = :id""").param("id", id).update();
    }

    @Test
    void draftVersionCanBeEditedAndDeleted() {
        var t = fx.tree();
        jdbc.sql("UPDATE scan_version SET provenance = CAST('{\"k\":1}' AS jsonb) WHERE id = :id")
            .param("id", t.version()).update();
        assertThat(jdbc.sql("DELETE FROM scan_version WHERE id = :id").param("id", t.version()).update()).isEqualTo(1);
    }

    @Test
    void finalizedVersionRejectsUpdatesAndDeletes() {
        var t = fx.tree();
        finalizeVersion(t.version());

        assertThatThrownBy(() -> jdbc.sql("UPDATE scan_version SET provenance = CAST('{\"x\":2}' AS jsonb) WHERE id = :id")
            .param("id", t.version()).update())
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("finalized and immutable");
        assertThatThrownBy(() -> jdbc.sql("UPDATE scan_version SET status = 'DRAFT', finalized_at = NULL WHERE id = :id")
            .param("id", t.version()).update())
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM scan_version WHERE id = :id").param("id", t.version()).update())
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void cannotFinalizeWithoutProvenance() {
        var t = fx.tree();
        assertThatThrownBy(() -> jdbc.sql("UPDATE scan_version SET status = 'FINALIZED', finalized_at = now() WHERE id = :id")
            .param("id", t.version()).update())
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void newVersionMayReferenceAFinalizedParent() {
        var t = fx.tree();
        finalizeVersion(t.version());
        UUID child = jdbc.sql("""
                INSERT INTO scan_version (organization_id, venue_id, scan_id, floor_id, version_number, parent_version_id)
                VALUES (:o, :v, :s, :f, 2, :p) RETURNING id""")
            .param("o", t.org()).param("v", t.venue()).param("s", t.scan()).param("f", t.floor()).param("p", t.version())
            .query(UUID.class).single();
        assertThat(child).isNotNull();
    }

    @Test
    void versionNumbersAreUniquePerScan() {
        var t = fx.tree();
        assertThatThrownBy(() -> fx.draftScanVersion(t.org(), t.venue(), t.scan(), t.floor(), 1))
            .isInstanceOf(DataIntegrityViolationException.class);
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

        finalizeVersion(t.version());
        assertThatThrownBy(() -> jdbc.sql(sql).param("o", t.org()).param("v", t.venue()).param("s", t.scan())
            .param("sv", t.version()).param("j", job).param("key", "k3/" + t.version()).param("sum", sum)
            .query(UUID.class).single())
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("finalized");
    }
}
