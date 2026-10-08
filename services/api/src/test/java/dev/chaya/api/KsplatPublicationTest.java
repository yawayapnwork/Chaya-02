package dev.chaya.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import dev.chaya.api.pipeline.KsplatValidator;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * A KSPLAT is published only if its sealed bytes meet the viewer contract (packages/contracts/viewer/ksplat-contract.json),
 * and what its headers say is stored with it and handed to the viewer next to its SHA-256 and size.
 */
class KsplatPublicationTest extends PipelineTestSupport {

    private JsonNode generationOrder() throws Exception {
        startRun();
        runThrough("PLANE_FITTING");
        return claimExpecting("ARTIFACT_GENERATION");
    }

    private Map<String, Object> manifest(JsonNode order) {
        return artifact(order, "manifest.json", "ARTIFACT_MANIFEST", false, false, "{}");
    }

    private int registered(JsonNode order) {
        return jdbc.sql("SELECT count(*) FROM processing_artifact WHERE job_id = :j")
            .param("j", UUID.fromString(order.get("id").asText())).query(Integer.class).single();
    }

    private void refused(JsonNode order, byte[] bytes, String code, String reason) throws Exception {
        Map<String, Object> ksplat = artifact(order, "scene.ksplat", "KSPLAT", false, false, bytes);
        send(order, report("SUCCEEDED", List.of(ksplat, manifest(order)), null, null), svc).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value(code))
            .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString(reason)));
        assertThat(registered(order)).as("nothing of a refused report is registered").isZero();
    }

    @Test
    void corruptTruncatedOrForeignBytesAreNeverPublishedAsAViewerAsset() throws Exception {
        JsonNode order = generationOrder();
        byte[] good = ksplat("good");
        refused(order, Arrays.copyOf(good, good.length - 1), "KSPLAT_INVALID", "TRUNCATED");
        refused(order, Arrays.copyOf(good, good.length + 10), "KSPLAT_INVALID", "TRAILING_BYTES");
        refused(order, "<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8), "KSPLAT_INVALID", "TRUNCATED");
        byte[] v02 = good.clone();
        v02[1] = 2; // a version the library would accept, but not the layout this contract proves
        refused(order, v02, "KSPLAT_INVALID", "UNSUPPORTED_VERSION");
        byte[] level1 = good.clone();
        level1[20] = 1;
        refused(order, level1, "KSPLAT_INVALID", "UNSUPPORTED_COMPRESSION");

        // the job is still the worker's: a correct report is accepted
        send(order, report("SUCCEEDED", List.of(artifact(order, "scene.ksplat", "KSPLAT", false, false, good), manifest(order)),
            null, null), svc).andExpect(status().isOk());
    }

    @Test
    void anOversizedKsplatIsRefusedBeforeItIsCopied() throws Exception {
        JsonNode order = generationOrder();
        Map<String, Object> ksplat = artifact(order, "scene.ksplat", "KSPLAT", false, false, ksplat("big"));
        ksplat.put("sizeBytes", KsplatValidator.MAX_BYTES + 1); // reported; never uploaded
        send(order, report("SUCCEEDED", List.of(ksplat, manifest(order)), null, null), svc).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("ARTIFACT_TOO_LARGE"));
        assertThat(registered(order)).isZero();
    }

    @Test
    void theValidatedHeadersAreStoredAndServedWithTheChecksumAndSize() throws Exception {
        Started s = startRun();
        runThrough("ARTIFACT_GENERATION");
        String format = jdbc.sql("""
                SELECT a.format_metadata::text FROM processing_artifact a JOIN pipeline_stage_run sr ON sr.id = a.stage_run_id
                 WHERE sr.run_id = :r AND a.kind = 'KSPLAT'""").param("r", s.run()).query(String.class).single();
        assertThat(mapper.readTree(format)).isEqualTo(mapper.readTree("""
            {"format":"ksplat","contract":"@mkkellogg/gaussian-splats-3d@0.4.7","version":"0.1","compressionLevel":0,
             "sphericalHarmonicsDegree":0,"sectionCount":1,"splatCount":3}"""));

        byte[] bytes = ksplat("run");
        String base = "/api/v1/venues/" + s.c().venue() + "/reconstructions/" + s.run();
        get(base, s.c().operator()).andExpect(status().isOk())
            .andExpect(jsonPath("$.artifacts[?(@.kind == 'KSPLAT')].sizeBytes").value(bytes.length))
            .andExpect(jsonPath("$.artifacts[?(@.kind == 'KSPLAT')].sha256").value(sha256(bytes)))
            .andExpect(jsonPath("$.artifacts[?(@.kind == 'KSPLAT')].format.splatCount").value(3))
            .andExpect(jsonPath("$.artifacts[?(@.kind == 'KSPLAT')].format.version").value("0.1"))
            .andExpect(jsonPath("$.artifacts[?(@.kind == 'ARTIFACT_MANIFEST')].format").value(org.hamcrest.Matchers.contains((Object) null)));
    }

    @Test
    void theDatabaseRefusesAKsplatRowWithoutValidatedHeaders() {
        Fixtures.Tree t = fx.tree();
        UUID job = jdbc.sql("""
                INSERT INTO processing_job (organization_id, venue_id, scan_id, stage, status, started_at, finished_at)
                VALUES (:o, :v, :s, 'ARTIFACT_GENERATION', 'SUCCEEDED', now(), now()) RETURNING id""") // never claimable
            .param("o", t.org()).param("v", t.venue()).param("s", t.scan()).query(UUID.class).single();
        String sql = """
            INSERT INTO processing_artifact (organization_id, venue_id, scan_id, job_id, stage, bucket, object_key, checksum_sha256,
                                             content_type, size_bytes, kind, format_metadata)
            VALUES (:o, :v, :s, :j, 'ARTIFACT_GENERATION', 'b', :k, :sha, 'application/octet-stream', 5164, :kind, CAST(:fmt AS jsonb))""";
        String key = "org/" + t.org() + "/venue/" + t.venue() + "/x/";
        assertThatThrownBy(() -> jdbc.sql(sql).param("o", t.org()).param("v", t.venue()).param("s", t.scan()).param("j", job)
                .param("k", key + "a").param("sha", "a".repeat(64)).param("kind", "KSPLAT").param("fmt", null).update())
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("processing_artifact_ksplat_validated");
        assertThat(jdbc.sql(sql).param("o", t.org()).param("v", t.venue()).param("s", t.scan()).param("j", job)
            .param("k", key + "b").param("sha", "a".repeat(64)).param("kind", "NAVMESH").param("fmt", null).update()).isEqualTo(1);
    }
}
