package dev.chaya.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Reconstruction worker unavailable: a claimable job has been waiting for two hours and nobody took it. Health must say
 * so (processing STALLED, overall DEGRADED) instead of reporting UP. Uses the legacy SEGMENTATION stage, which no other
 * test claims, and cancels the job afterwards.
 */
class WorkerUnavailableHealthTest extends ApiTest {

    @Test
    void anUnclaimedOldJobIsReportedAsAStalledQueue() throws Exception {
        Fixtures.Tree t = fx.tree();
        UUID job = jdbc.sql("""
                INSERT INTO processing_job (organization_id, venue_id, scan_id, stage, queued_at)
                VALUES (:o, :v, :s, 'SEGMENTATION', now() - interval '2 hours') RETURNING id
                """)
            .param("o", t.org()).param("v", t.venue()).param("s", t.scan()).query(UUID.class).single();
        try {
            String body = get("/api/v1/health", null).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            JsonNode processing = new ObjectMapper().readTree(body).get("processing");
            assertThat(processing.get("queuedJobs").asLong()).isGreaterThanOrEqualTo(1);
            assertThat(processing.get("oldestQueuedAgeSeconds").asLong()).isGreaterThanOrEqualTo(7000);
            // The shared test database may hold RUNNING jobs leased by other tests; the rule is "stalled only when
            // nothing is running", so assert exactly that.
            if (processing.get("runningJobs").asLong() == 0) {
                assertThat(processing.get("status").asText()).isEqualTo("STALLED");
                assertThat(new ObjectMapper().readTree(body).get("status").asText()).isEqualTo("DEGRADED");
            } else {
                assertThat(processing.get("status").asText()).isEqualTo("ACTIVE");
            }
        } finally {
            jdbc.sql("UPDATE processing_job SET status = 'CANCELLED', finished_at = now() WHERE id = :j").param("j", job).update();
        }
    }
}
