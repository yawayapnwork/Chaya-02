package dev.chaya.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** No rate card configured (the default): attempts are recorded with their measurements, nothing is priced, and the
 * dashboard says why instead of showing 0. */
class ResourceCostWithoutRatesTest extends PipelineTestSupport {

    @Test
    void withoutRatesTimeIsRecordedAndCostIsUnavailableWithTheReason() throws Exception {
        Started s = startRun();
        JsonNode order = claimExpecting("INPUT_VALIDATION");
        Map<String, Object> r = report("SUCCEEDED", outputsFor(order), null, null);
        Instant t0 = Instant.now().minusSeconds(20);
        r.put("startedAt", t0.toString());
        r.put("finishedAt", t0.plusSeconds(10).toString());
        r.put("resources", Map.of("schema", 1, "resourceClass", "gpu-a10g", "cpu", Map.of("processSeconds", 3.0, "childrenMeasured", false)));
        send(order, r, svc).andExpect(status().isOk());

        Map<String, Object> u = jdbc.sql("SELECT * FROM job_attempt_usage WHERE job_id = :j").param("j", UUID.fromString(order.get("id").asText()))
            .query().singleRow();
        assertThat((Double) u.get("wall_seconds")).isEqualTo(10.0);
        assertThat((Double) u.get("cpu_seconds")).isEqualTo(3.0);
        assertThat(u.get("estimated_cost")).isNull();
        assertThat(u.get("cost_rate_set_id")).isNull();
        assertThat((String) u.get("cost_unavailable_reason")).startsWith("NO_RATES_CONFIGURED");

        String ops = "/api/v1/venues/" + s.c().venue() + "/ops/";
        get(ops + "resources", s.c().operator()).andExpect(status().isOk())
            .andExpect(jsonPath("$.rateCard").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.estimate.amounts").isEmpty())
            .andExpect(jsonPath("$.estimate.unpricedReasons.NO_RATES_CONFIGURED").value(1))
            .andExpect(jsonPath("$.storage.estimatedPerMonth").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.storage.unavailableReason").value(org.hamcrest.Matchers.startsWith("NO_RATES_CONFIGURED")))
            .andExpect(jsonPath("$.window.wallSeconds").value(10.0));
        get(ops + "storage", s.c().operator()).andExpect(status().isOk())
            .andExpect(jsonPath("$.cost.available").value(false))
            .andExpect(jsonPath("$.cost.kind").value("ESTIMATED"))
            .andExpect(jsonPath("$.cost.reason").value(org.hamcrest.Matchers.containsString("NO_RATES_CONFIGURED")));
        // the viewer role may not read processing resources
        get(ops + "resources", TestJwt.user(s.c().org(), "viewer").venues(s.c().venue()).token()).andExpect(status().isForbidden());
    }
}
