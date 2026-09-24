package dev.chaya.api;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.chaya.api.pipeline.PipelineDefinition;
import dev.chaya.api.storage.ObjectStore;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

/** Drives the pipeline API as a worker would, with real objects written to the real derived bucket. */
abstract class PipelineTestSupport extends CaptureTestSupport {

    static final String CLAIM = "/api/v1/internal/jobs/claim";

    @Autowired ObjectMapper mapper;
    @Autowired @Qualifier("derived") ObjectStore derived;

    protected final String svc = TestJwt.service().token();

    /** The shared queue is global; each test starts with no leftover queued pipeline jobs from other tests. */
    @BeforeEach
    void drainQueue() {
        jdbc.sql("UPDATE processing_job SET status = 'CANCELLED', finished_at = now() WHERE status = 'QUEUED' AND run_id IS NOT NULL").update();
    }

    record Started(Ctx c, UUID capture, UUID scan, UUID run) {}

    /** Every stage a real worker claims (chaya_worker.stages.STAGE_ORDER): the full-venue plan plus the incremental
     * re-scan's REGION_ALIGNMENT and REGION_SPLICE. */
    protected static String allStages() {
        var stages = new java.util.LinkedHashSet<>(PipelineDefinition.STAGES);
        stages.addAll(PipelineDefinition.INCREMENTAL_STAGES);
        return "[" + String.join(",", stages.stream().map(s -> "\"" + s + "\"").toList()) + "]";
    }

    protected Started startRun(String startBody) throws Exception {
        var c = ctx();
        UUID capture = newCapture(c);
        var u = upload(c, capture, "VIDEO", "walk.mp4", "video/mp4", mp4(4096));
        awaitSettled(c, capture, u.mediaId());
        post(capUrl(c, capture) + "/complete-upload", c.operator(), null).andExpect(status().isOk());
        String json = post(capUrl(c, capture) + "/processing", c.operator(), startBody).andExpect(status().isAccepted())
            .andReturn().getResponse().getContentAsString();
        JsonNode n = mapper.readTree(json);
        return new Started(c, capture, UUID.fromString(n.get("scanId").asText()), UUID.fromString(n.get("run").get("id").asText()));
    }

    protected Started startRun() throws Exception {
        return startRun(null);
    }

    /** Claims the next pipeline job; null when nothing is queued. */
    protected JsonNode claim() throws Exception {
        var res = post(CLAIM, svc, "{\"stages\":" + allStages() + ",\"workerId\":\"test-worker\"}").andReturn().getResponse();
        if (res.getStatus() == 204) {
            return null;
        }
        if (res.getStatus() != 200) {
            throw new AssertionError("claim failed: " + res.getStatus() + " " + res.getContentAsString());
        }
        return mapper.readTree(res.getContentAsString());
    }

    protected JsonNode claimExpecting(String stage) throws Exception {
        JsonNode order = claim();
        if (order == null || !order.get("stage").asText().equals(stage)) {
            throw new AssertionError("expected to claim " + stage + " but got " + (order == null ? "nothing" : order.get("stage").asText()));
        }
        return order;
    }

    /** Writes a real object into the derived bucket and returns the artifact report entry for it. */
    protected Map<String, Object> artifact(JsonNode order, String name, String kind, boolean pii, boolean partial, String content) {
        String key = order.get("outputPrefix").asText() + (pii ? "pii/" : "") + name;
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        String uploadId = derived.beginMultipart(key, "application/octet-stream");
        String etag = derived.uploadPart(key, uploadId, 1, data);
        derived.completeMultipart(key, uploadId, List.of(new ObjectStore.PartEtag(1, etag)));
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("kind", kind);
        a.put("key", key);
        a.put("sha256", sha256(data));
        a.put("contentType", contentTypeOf(name)); // as the real worker labels its outputs (chaya_worker.stages)
        a.put("sizeBytes", data.length);
        a.put("containsPii", pii);
        a.put("partial", partial);
        return a;
    }

    static String contentTypeOf(String name) {
        if (name.endsWith(".json")) return "application/json";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".jpg")) return "image/jpeg";
        if (name.endsWith(".tar")) return "application/x-tar";
        if (name.endsWith(".log") || name.endsWith(".txt")) return "text/plain";
        return "application/octet-stream";
    }

    protected Map<String, Object> report(String status, List<Map<String, Object>> artifacts, String errorCode, String message) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("status", status);
        r.put("startedAt", Instant.now().minusSeconds(2).toString());
        r.put("finishedAt", Instant.now().toString());
        r.put("command", Map.of("argv", List.of("test-stage"), "config", Map.of("fixture", true)));
        r.put("inputArtifactIds", List.of());
        r.put("exitStatus", status.equals("SUCCEEDED") ? 0 : 1);
        r.put("artifacts", artifacts);
        r.put("errorCode", errorCode);
        r.put("errorMessage", message);
        return r;
    }

    protected org.springframework.test.web.servlet.ResultActions send(JsonNode order, Map<String, Object> report, String token) throws Exception {
        return post("/api/v1/internal/jobs/" + order.get("id").asText() + "/report", token, mapper.writeValueAsString(report));
    }

    /** The realistic output of each stage (small real objects). Pre-privacy frames are flagged as PII. */
    protected List<Map<String, Object>> outputsFor(JsonNode order) {
        String stage = order.get("stage").asText();
        return switch (stage) {
            case "INPUT_VALIDATION" -> List.of(artifact(order, "validation.json", "INPUT_REPORT", false, false, "{\"ok\":true}"));
            case "FFMPEG_PREPROCESS" -> List.of(artifact(order, "frame-000001.png", "FRAME", true, false, "frame-bytes"));
            case "FRAME_QUALITY_FILTER" -> List.of(artifact(order, "selection.json", "FRAME_SELECTION", true, false, "{\"kept\":1}"));
            case "PRIVACY_PREPROCESS" -> List.of(artifact(order, "frame-000001.png", "FRAME_ANON", false, false, "blurred-frame-bytes"));
            case "POSE_ESTIMATION" -> List.of(artifact(order, "poses.json", "POSES", false, false, "{}"));
            case "SPLAT_RECONSTRUCTION" -> List.of(artifact(order, "scene.ksplat", "SPLAT", false, false, "splat-bytes"));
            default -> List.of(artifact(order, stage.toLowerCase() + ".json", stage, false, false, "{}"));
        };
    }

    protected void succeed(JsonNode order) throws Exception {
        send(order, report("SUCCEEDED", outputsFor(order), null, null), svc).andExpect(status().isOk());
    }

    /** Claims and succeeds stages until (and including) the given one. */
    protected List<JsonNode> runThrough(String lastStage) throws Exception {
        List<JsonNode> orders = new ArrayList<>();
        for (var stage : PipelineDefinition.STAGES) {
            JsonNode order = claimExpecting(stage.name());
            orders.add(order);
            succeed(order);
            if (stage.name().equals(lastStage)) {
                break;
            }
        }
        return orders;
    }

    protected JsonNode processing(Started s) throws Exception {
        String json = get(capUrl(s.c(), s.capture()) + "/processing", s.c().operator()).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        return mapper.readTree(json);
    }

    protected String captureStatus(Started s) throws Exception {
        return mapper.readTree(get(capUrl(s.c(), s.capture()), s.c().operator()).andReturn().getResponse().getContentAsString()).get("status").asText();
    }

    protected boolean existsInDerived(String key) {
        try {
            derived.size(key);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
