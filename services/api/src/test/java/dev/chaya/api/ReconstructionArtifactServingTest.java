package dev.chaya.api;

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/** Viewer artifacts are streamed from the API's origin, so what they are served as must not be up to the worker. */
class ReconstructionArtifactServingTest extends PipelineTestSupport {

    @Test
    void viewerArtifactsAreServedAsTheirKindsTypeWhateverTheWorkerLabelledThem() throws Exception {
        Started s = startRun();
        runThrough("PLANE_FITTING");
        JsonNode generation = claimExpecting("ARTIFACT_GENERATION");
        Map<String, Object> ksplat = artifact(generation, "scene.ksplat", "KSPLAT", false, false, "<script>alert(1)</script>");
        ksplat.put("contentType", "text/html");
        Map<String, Object> manifest = artifact(generation, "manifest.json", "ARTIFACT_MANIFEST", false, false, "{}");
        manifest.put("contentType", "image/svg+xml");
        send(generation, report("SUCCEEDED", List.of(ksplat, manifest), null, null), svc).andExpect(status().isOk());

        String base = "/api/v1/venues/" + s.c().venue() + "/reconstructions/" + s.run();
        get(base, s.c().operator()).andExpect(status().isOk())
            .andExpect(jsonPath("$.artifacts[?(@.kind == 'KSPLAT')].contentType", contains("application/octet-stream")))
            .andExpect(jsonPath("$.artifacts[?(@.kind == 'ARTIFACT_MANIFEST')].contentType", contains("application/json")));

        MvcResult started = get(base + "/artifacts/KSPLAT", s.c().operator()).andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(started)).andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "application/octet-stream"))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andExpect(content().string("<script>alert(1)</script>"));
    }
}
