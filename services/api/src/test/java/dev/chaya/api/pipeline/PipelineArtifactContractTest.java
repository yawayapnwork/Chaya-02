package dev.chaya.api.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.chaya.api.processing.JobStage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The control plane's half of the stage artifact contract (packages/contracts/pipeline/stage-artifacts.json). The worker's
 * half, what each stage really reads and writes, is services/reconstruction tests/unit/test_stage_artifact_contract.py.
 * A mismatch here means a run created by the API could be refused, or let through, on outputs the worker does not
 * publish.
 */
class PipelineArtifactContractTest {

    static final Path CONTRACT = Path.of("../../packages/contracts/pipeline/stage-artifacts.json");

    private static JsonNode contract() throws Exception {
        return new ObjectMapper().readTree(CONTRACT.toFile());
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }

    @Test
    void thePlansAreTheContractsPlans() throws Exception {
        JsonNode plans = contract().get("plans");
        assertThat(PipelineDefinition.STAGES.stream().map(Enum::name).toList()).isEqualTo(strings(plans.get("full")));
        assertThat(PipelineDefinition.INCREMENTAL_STAGES.stream().map(Enum::name).toList()).isEqualTo(strings(plans.get("incremental")));
        assertThat(PipelineDefinition.plan(false)).doesNotContain(JobStage.PRIVACY_PREPROCESS);
        assertThat(strings(plans.get("optionalStages"))).containsExactlyInAnyOrder("PRIVACY_PREPROCESS", "NAVIGATION_BAKING");
    }

    @Test
    void everyStagesRequiredOutputsAreTheContracts() throws Exception {
        JsonNode stages = contract().get("stages");
        Set<String> planned = new LinkedHashSet<>();
        PipelineDefinition.STAGES.forEach(s -> planned.add(s.name()));
        PipelineDefinition.INCREMENTAL_STAGES.forEach(s -> planned.add(s.name()));
        for (String stage : planned) {
            assertThat(stages.has(stage)).as(stage + " has a contract entry").isTrue();
            assertThat(PipelineDefinition.REQUIRED_OUTPUTS.get(JobStage.valueOf(stage))).as(stage)
                .isEqualTo(strings(stages.get(stage).get("requiredOutputs")));
        }
        assertThat(PipelineDefinition.REQUIRED_OUTPUTS.keySet().stream().map(Enum::name)).containsExactlyInAnyOrderElementsOf(planned);
    }

    @Test
    void missingOutputsAreNamedInContractOrder() {
        assertThat(PipelineDefinition.missingOutputs(JobStage.NAVIGATION_BAKING, Set.of("NAVMESH")))
            .containsExactly("NAVMESH_MANIFEST", "NAVIGATION_GRAPH");
        assertThat(PipelineDefinition.missingOutputs(JobStage.INPUT_VALIDATION, Set.of())).isEmpty();
    }
}
