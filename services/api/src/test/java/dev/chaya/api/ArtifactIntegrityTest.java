package dev.chaya.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import dev.chaya.api.storage.ObjectStore;
import dev.chaya.api.storage.TenantKeys;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Registered artifacts are sealed, SHA-256-verified copies that no worker can replace, every read of them is verified
 * again, and only the worker holding a job's lease can report on it (review S-2; docs/security.md, "Object storage" and
 * "Service-to-service authentication"). The storage accounts themselves are tested in StoragePolicyTest.
 */
class ArtifactIntegrityTest extends PipelineTestSupport {

    @Autowired dev.chaya.api.pipeline.PipelineService pipeline;

    private void put(String key, byte[] data) {
        String upload = derived.beginMultipart(key, "application/octet-stream");
        String etag = derived.uploadPart(key, upload, 1, data);
        derived.completeMultipart(key, upload, List.of(new ObjectStore.PartEtag(1, etag)));
    }

    private record Row(String key, String workerKey, boolean sealed, String sha) {}

    private List<Row> artifactsOf(UUID runId) {
        return jdbc.sql("""
                SELECT a.object_key, a.worker_object_key, a.sealed, a.checksum_sha256 FROM processing_artifact a
                  JOIN pipeline_stage_run sr ON sr.id = a.stage_run_id WHERE sr.run_id = :r""")
            .param("r", runId).query((rs, i) -> new Row(rs.getString(1), rs.getString(2), rs.getBoolean(3), rs.getString(4))).list();
    }

    @Test
    void everyRegisteredArtifactIsASealedCopyUnderItsTenantAndTheWorkersOriginalIsGone() throws Exception {
        Started s = startRun();
        runThrough("ARTIFACT_GENERATION");
        List<Row> rows = artifactsOf(s.run());
        assertThat(rows).isNotEmpty();
        String sealedPrefix = TenantKeys.sealedPrefix(s.c().org(), s.c().venue());
        for (Row r : rows) {
            assertThat(r.sealed()).isTrue();
            assertThat(r.key()).startsWith(sealedPrefix);
            assertThat(r.workerKey()).startsWith(TenantKeys.prefix(s.c().org(), s.c().venue()));
            assertThat(r.key()).endsWith(r.workerKey().substring(r.workerKey().lastIndexOf('/'))); // the file name is kept
            assertThat(existsInDerived(r.workerKey())).as("worker original %s", r.workerKey()).isFalse();
            boolean purgedPii = r.workerKey().contains("/pii/");
            if (!purgedPii) {
                assertThat(derived.sha256(r.key())).isEqualTo(r.sha());
            }
        }
    }

    @Test
    void laterStagesAreHandedOnlySealedInputsOfTheirOwnTenant() throws Exception {
        Started s = startRun();
        runThrough("POSE_ESTIMATION");
        JsonNode order = claimExpecting("SPLAT_RECONSTRUCTION");
        assertThat(order.get("inputs")).isNotEmpty();
        for (JsonNode in : order.get("inputs")) {
            String key = in.get("key").asText();
            assertThat(TenantKeys.belongsTo(key, s.c().org(), s.c().venue())).as(key).isTrue();
            if (!in.get("kind").asText().startsWith("RAW_")) {
                assertThat(key).startsWith(TenantKeys.sealedPrefix(s.c().org(), s.c().venue()));
            }
        }
    }

    @Test
    void anObjectReplacedBeforeItsReportIsRefusedAndNothingIsRegistered() throws Exception {
        startRun();
        JsonNode order = claimExpecting("INPUT_VALIDATION");
        Map<String, Object> a = artifact(order, "validation.json", "INPUT_REPORT", false, false, "{\"ok\":true}");
        // Same size, different bytes: a size check alone would accept it.
        put((String) a.get("key"), "{\"ok\":tru3}".getBytes(StandardCharsets.UTF_8));
        send(order, report("SUCCEEDED", List.of(a), null, null), svc).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("ARTIFACT_CHECKSUM_MISMATCH"));

        Map<String, Object> wrongHash = artifact(order, "validation-2.json", "INPUT_REPORT", false, false, "{\"ok\":true}");
        wrongHash.put("sha256", "f".repeat(64));
        send(order, report("SUCCEEDED", List.of(wrongHash), null, null), svc).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("ARTIFACT_CHECKSUM_MISMATCH"));

        UUID job = UUID.fromString(order.get("id").asText());
        assertThat(jdbc.sql("SELECT count(*) FROM processing_artifact WHERE job_id = :j").param("j", job).query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT status FROM processing_job WHERE id = :j").param("j", job).query(String.class).single()).isEqualTo("RUNNING");
        // the worker's object is left for it to fix; a correct report is still accepted
        send(order, report("SUCCEEDED", List.of(artifact(order, "validation.json", "INPUT_REPORT", false, false, "{\"ok\":true}")), null, null), svc)
            .andExpect(status().isOk());
    }

    @Test
    void aSealedArtifactReplacedInStorageIsNeverServedAndTheAttemptIsAudited() throws Exception {
        Started s = startRun();
        runThrough("ARTIFACT_GENERATION");
        String key = jdbc.sql("""
                SELECT a.object_key FROM processing_artifact a JOIN pipeline_stage_run sr ON sr.id = a.stage_run_id
                 WHERE sr.run_id = :r AND a.kind = 'KSPLAT'""").param("r", s.run()).query(String.class).single();
        byte[] genuine = derived.open(key).readAllBytes();
        byte[] forged = new byte[genuine.length];
        java.util.Arrays.fill(forged, (byte) 'x');
        put(key, forged); // someone with write access to sealed/ (only the API account has it) replaced the object

        String url = "/api/v1/venues/" + s.c().venue() + "/reconstructions/" + s.run() + "/artifacts/KSPLAT";
        MvcResult started = get(url, s.c().operator()).andExpect(request().asyncStarted()).andReturn();
        Object outcome = started.getAsyncResult(30_000);
        assertThat(outcome).isInstanceOf(dev.chaya.api.storage.VerifyingInputStream.IntegrityException.class);
        assertThat(auditCount(s.c().org(), "artifact.integrity_violation", "FAILURE")).isEqualTo(1);

        put(key, genuine); // restored: served again
        MvcResult again = get(url, s.c().operator()).andExpect(request().asyncStarted()).andReturn();
        assertThat(again.getAsyncResult(30_000)).isNull();
        assertThat(again.getResponse().getContentAsByteArray()).isEqualTo(genuine);
    }

    // ---- leases: a worker can act only on the job it claimed ----------------------------------------------------------

    @Test
    void aWorkerCannotReportOnOrKeepAliveAnotherTenantsJob() throws Exception {
        Started first = startRun();
        Started second = startRun();
        assertThat(first.c().org()).isNotEqualTo(second.c().org());
        JsonNode mine = claimExpecting("INPUT_VALIDATION");
        JsonNode theirs = claimExpecting("INPUT_VALIDATION");
        assertThat(mine.get("organizationId").asText()).isNotEqualTo(theirs.get("organizationId").asText());
        String myLease = mine.get("leaseToken").asText();

        // with my lease, about their job: refused, and nothing changes
        Map<String, Object> theirOutput = artifact(theirs, "validation.json", "INPUT_REPORT", false, false, "{\"ok\":true}");
        sendWithLease(theirs, report("SUCCEEDED", List.of(theirOutput), null, null), svc, myLease).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("LEASE_MISMATCH"));
        sendWithLease(theirs, report("FAILED", List.of(), "SABOTAGE", "x"), svc, null).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("LEASE_MISMATCH"));
        heartbeat(theirs, myLease).andExpect(status().isOk()).andExpect(jsonPath("$.keepGoing").value(false));
        UUID theirJob = UUID.fromString(theirs.get("id").asText());
        assertThat(jdbc.sql("SELECT status FROM processing_job WHERE id = :j").param("j", theirJob).query(String.class).single())
            .isEqualTo("RUNNING");
        assertThat(jdbc.sql("SELECT count(*) FROM processing_artifact WHERE job_id = :j").param("j", theirJob).query(Integer.class).single())
            .isZero();

        // each worker with its own lease
        send(theirs, report("SUCCEEDED", List.of(theirOutput), null, null), svc).andExpect(status().isOk());
        succeed(mine);
    }

    @Test
    void aWorkerWhoseLeaseExpiredCannotReportAfterTheJobWasHandedToAnother() throws Exception {
        Started s = startRun();
        JsonNode stale = claimExpecting("INPUT_VALIDATION");
        jdbc.sql("UPDATE processing_job SET lease_expires_at = now() - interval '1 minute' WHERE id = :j")
            .param("j", UUID.fromString(stale.get("id").asText())).update();
        pipeline.enforceLeasesAndDeadlines();
        post(capUrl(s.c(), s.capture()) + "/processing/retry", s.c().operator(), null).andExpect(status().isAccepted());
        JsonNode fresh = claimExpecting("INPUT_VALIDATION");
        assertThat(fresh.get("id").asText()).isEqualTo(stale.get("id").asText());
        assertThat(fresh.get("leaseToken").asText()).isNotEqualTo(stale.get("leaseToken").asText());

        send(stale, report("SUCCEEDED", List.of(), null, null), svc).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("LEASE_MISMATCH"));
        heartbeat(stale, stale.get("leaseToken").asText()).andExpect(jsonPath("$.keepGoing").value(false));
        succeed(fresh);
    }
}
