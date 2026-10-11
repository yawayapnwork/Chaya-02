package dev.chaya.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Reconstruction worker unavailable: a claimable job has been waiting for two hours and nobody took it. Health must say
 * so instead of reporting UP. Uses the legacy SEGMENTATION stage, which no test worker offers, so no live worker serves
 * it: processing is NO_WORKER (the stage is named in unservedStages) and the report DEGRADED. Cancels the job afterwards.
 */
class WorkerUnavailableHealthTest extends ApiTest {

    @Test
    void anUnclaimableOldJobIsReportedWithTheStageNobodyServes() throws Exception {
        Fixtures.Tree t = fx.tree();
        UUID job = jdbc.sql("""
                INSERT INTO processing_job (organization_id, venue_id, scan_id, stage, queued_at)
                VALUES (:o, :v, :s, 'SEGMENTATION', now() - interval '2 hours') RETURNING id
                """)
            .param("o", t.org()).param("v", t.venue()).param("s", t.scan()).query(UUID.class).single();
        try {
            String body = get("/api/v1/health", null).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            JsonNode report = new ObjectMapper().readTree(body);
            JsonNode processing = report.get("processing");
            assertThat(processing.get("queuedJobs").asLong()).isGreaterThanOrEqualTo(1);
            assertThat(processing.get("oldestQueuedAgeSeconds").asLong()).isGreaterThanOrEqualTo(7000);
            List<String> unserved = new ArrayList<>();
            processing.get("unservedStages").forEach(n -> unserved.add(n.asText()));
            assertThat(unserved).contains("SEGMENTATION");
            assertThat(processing.get("status").asText()).isEqualTo("NO_WORKER");
            assertThat(report.get("status").asText()).isEqualTo("DEGRADED");
            // Public endpoint: counts and stage names only, never worker ids.
            assertThat(body).doesNotContain("test-worker");
        } finally {
            jdbc.sql("UPDATE processing_job SET status = 'CANCELLED', finished_at = now() WHERE id = :j").param("j", job).update();
        }
    }
}
