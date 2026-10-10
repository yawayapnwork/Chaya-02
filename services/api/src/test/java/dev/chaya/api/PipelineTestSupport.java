package dev.chaya.api;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.chaya.api.pipeline.PipelineDefinition;
import dev.chaya.api.storage.ObjectStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
        return artifact(order, name, kind, pii, partial, content.getBytes(StandardCharsets.UTF_8));
    }

    /** chaya_worker.stages.base.run_provenance for a work order: the run and version a version-scoped artifact names. */
    protected Map<String, Object> sourceOf(JsonNode order) {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("run_id", order.path("runId").asText(null));
        JsonNode version = order.path("scanVersionId");
        source.put("scan_version_id", version.isNull() || version.isMissingNode() ? null : version.asText());
        return source;
    }

    private static final Path NAVMESH_FIXTURE = Path.of("../../packages/contracts/fixtures/navmesh");

    /** The committed production-encoder output (packages/contracts/fixtures/ksplat/scene.ksplat, 3 splats): a KSPLAT is
     * only published if it meets the viewer contract (KsplatValidator), so tests publish real ones. */
    static final Path KSPLAT_FIXTURE = Path.of("../../packages/contracts/fixtures/ksplat/scene.ksplat");

    /** The fixture with its scene-centre x (stored, never used to read splats) set from {@code variant}: a valid .ksplat
     * whose bytes and hash differ per variant, so tests can tell models apart. */
    protected static byte[] ksplat(String variant) {
        try {
            byte[] b = Files.readAllBytes(KSPLAT_FIXTURE);
            java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN).putFloat(24, variant.hashCode());
            return b;
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** NAVIGATION_BAKING's outputs in the worker's real shape: the committed real Recast navmesh fixture
     * (packages/contracts/fixtures/navmesh), its manifest, and its graph in `frameId`, naming the order's version. */
    @SuppressWarnings("unchecked")
    protected List<Map<String, Object>> navigationOutputs(JsonNode order, Object frameId) throws IOException {
        byte[] navmesh = Files.readAllBytes(NAVMESH_FIXTURE.resolve("navmesh.bin"));
        String manifest = Files.readString(NAVMESH_FIXTURE.resolve("navmesh-manifest.json"), StandardCharsets.UTF_8);
        Map<String, Object> graph = mapper.readValue(Files.readString(NAVMESH_FIXTURE.resolve("navigation-graph.json"),
            StandardCharsets.UTF_8).replace("FIXTURE_FRAME_ID", String.valueOf(frameId)), Map.class);
        graph.put("source", sourceOf(order));
        return List.of(artifact(order, "navmesh.bin", "NAVMESH", false, false, navmesh),
            artifact(order, "navmesh-manifest.json", "NAVMESH_MANIFEST", false, false, manifest),
            artifact(order, "navigation-graph.json", "NAVIGATION_GRAPH", false, false, mapper.writeValueAsString(graph)));
    }

    /** As above, for binary outputs (a Detour navmesh tile, say). */
    protected Map<String, Object> artifact(JsonNode order, String name, String kind, boolean pii, boolean partial, byte[] data) {
        String key = order.get("outputPrefix").asText() + (pii ? "pii/" : "") + name;
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

    /** Reports on a claimed job as the worker that claimed it (with the work order's lease token). */
    protected org.springframework.test.web.servlet.ResultActions send(JsonNode order, Map<String, Object> report, String token) throws Exception {
        return sendWithLease(order, report, token, order.path("leaseToken").asText(null));
    }

    protected org.springframework.test.web.servlet.ResultActions sendWithLease(JsonNode order, Map<String, Object> report, String token,
                                                                              String leaseToken) throws Exception {
        return postWithLease("/api/v1/internal/jobs/" + order.get("id").asText() + "/report", token, mapper.writeValueAsString(report),
            leaseToken);
    }

    protected org.springframework.test.web.servlet.ResultActions heartbeat(JsonNode order, String leaseToken) throws Exception {
        return postWithLease("/api/v1/internal/jobs/" + order.get("id").asText() + "/heartbeat", svc, "{\"workerId\":\"test-worker\"}",
            leaseToken);
    }

    /**
     * What the real worker publishes for each stage (chaya_worker.stages; packages/contracts/pipeline/stage-artifacts.json),
     * as small real objects with the real kinds: pre-privacy frames are flagged as PII. FIXTURE CONTENT: the bytes are not
     * frames, models or clouds; only the kinds, the PII rules, the frame and version references and the documents the
     * control plane itself reads (DETECTED_OBJECTS, NAVIGATION_GRAPH, the .ksplat) have the real worker's shape.
     *
     * <p>SEMANTIC_INDEXING and NAVIGATION_BAKING work in canonical metres: like the real worker, they need the work order's
     * coordinate frame. Without one, {@link #succeed} reports what the real worker reports, a FAILED NOT_CALIBRATED; a test
     * that drives a run through them calibrates first ({@link #calibrateRun}).
     */
    protected List<Map<String, Object>> outputsFor(JsonNode order) {
        String stage = order.get("stage").asText();
        String frameId = frameOf(order);
        try {
            return switch (stage) {
                case "INPUT_VALIDATION" -> List.of(artifact(order, "input-report.json", "INPUT_REPORT", false, false, "{\"ok\":true}"));
                case "FFMPEG_PREPROCESS" -> List.of(artifact(order, "frames.tar", "FRAME_ARCHIVE", true, false, "frame-archive-bytes"),
                    artifact(order, "frames-manifest.json", "FRAME_MANIFEST", false, false, "{\"frames\":1}"));
                case "FRAME_QUALITY_FILTER" -> List.of(artifact(order, "frames-selected.tar", "FRAME_ARCHIVE_SELECTED", true, false,
                        "selected-frame-bytes"),
                    artifact(order, "frame-quality-report.json", "FRAME_QUALITY_REPORT", false, false, "{\"kept\":1}"));
                case "PRIVACY_PREPROCESS" -> List.of(artifact(order, "frames-anon.tar", "FRAME_ARCHIVE_ANON", false, false, "blurred-frame-bytes"),
                    artifact(order, "privacy-masks.tar", "PRIVACY_MASKS", false, false, "mask-bytes"),
                    artifact(order, "privacy-report.json", "PRIVACY_REPORT", false, false, "{}"));
                case "POSE_ESTIMATION" -> List.of(artifact(order, "sparse-model.tar", "SPARSE_MODEL", false, false, "sparse-model-bytes"),
                    artifact(order, "poses.json", "POSES", false, false, "{}"));
                case "SPLAT_RECONSTRUCTION" -> List.of(artifact(order, "splat.ply", "SPLAT", false, false, "splat-bytes"));
                case "SEMANTIC_SEGMENTATION" -> List.of(artifact(order, "semantic-labels.json", "SEMANTIC_LABELS", false, false,
                    "{\"labels\":[]}"));
                // The kinds chaya_worker.stages.geometric_cleanup / region_splice / artifact_generation publish: a ScanVersion
                // pins its cloud and viewer asset, and cannot be finalized without them.
                case "GEOMETRIC_CLEANUP" -> List.of(artifact(order, "splat-clean.ply", "SPLAT_CLEAN", false, false, "clean-cloud-bytes"),
                    artifact(order, "semantic-labels-clean.json", "SEMANTIC_LABELS_CLEAN", false, false, "{\"labels\":[]}"));
                case "REGION_ALIGNMENT" -> List.of(artifact(order, "splat-aligned.ply", "SPLAT_ALIGNED", false, false, "aligned-cloud-bytes"));
                case "REGION_SPLICE" -> List.of(artifact(order, "splat-merged.ply", "SPLAT_MERGED", false, false, "merged-cloud-bytes"),
                    artifact(order, "splice-report.json", "SPLICE_REPORT", false, false, "{}"));
                case "PLANE_FITTING" -> List.of(artifact(order, "planes.json", "PLANE_MODEL", false, false, "{\"planes\":[]}"),
                    artifact(order, "gravity-estimate.json", "GRAVITY_ESTIMATE", false, false,
                        "{\"status\":\"NOT_ESTIMATED\",\"reason\":\"fixture\"}"));
                case "ARTIFACT_GENERATION" -> List.of(artifact(order, "scene.ksplat", "KSPLAT", false, false, ksplat("run")),
                    artifact(order, "manifest.json", "ARTIFACT_MANIFEST", false, false, "{}"));
                case "SEMANTIC_INDEXING" -> List.of(detections(order, frameId, List.of()));
                case "NAVIGATION_BAKING" -> navigationOutputs(order, frameId);
                default -> throw new AssertionError("no fixture outputs for stage " + stage);
            };
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** The canonical frame the work order carries, or null. */
    protected static String frameOf(JsonNode order) {
        JsonNode f = order.path("coordinateFrame");
        return f.isMissingNode() || f.isNull() ? null : f.get("id").asText();
    }

    static final Set<String> METRIC_STAGES = Set.of("SEMANTIC_INDEXING", "NAVIGATION_BAKING");

    /** DETECTED_OBJECTS in the worker's shape (chaya_worker.stages.semantic_indexing), in `frameId`, naming the order's run and
     * version. Each object is (label, x, y). */
    protected Map<String, Object> detections(JsonNode order, String frameId, List<Object[]> objects) throws IOException {
        List<Double> embedding = new ArrayList<>();
        for (int i = 0; i < 512; i++) {
            embedding.add(Math.sin(i * 0.017));
        }
        List<Map<String, Object>> objs = new ArrayList<>();
        for (Object[] o : objects) {
            objs.add(Map.of("label", o[0], "confidence", 0.9, "position", List.of(o[1], o[2], 0.05), "embedding", embedding,
                "localization", Map.of("status", "MULTI_VIEW", "uncertainty_m", 0.1)));
        }
        Map<String, Object> doc = Map.of(
            "coordinate_frame", Map.of("id", String.valueOf(frameId), "units", "m", "up_axis", "+Z"),
            "embedding_model", "open_clip:ViT-B-32:openai",
            "source", sourceOf(order),
            "objects", objs);
        return artifact(order, "detected-objects.json", "DETECTED_OBJECTS", false, false, mapper.writeValueAsString(doc));
    }

    /** POST .../processing/retry for the order's run, as its operator: re-queues the stage the run failed at. */
    protected void retryRun(JsonNode order) throws Exception {
        UUID org = UUID.fromString(order.get("organizationId").asText());
        UUID venue = UUID.fromString(order.get("venueId").asText());
        UUID capture = jdbc.sql("SELECT capture_session_id FROM pipeline_run WHERE id = :r")
            .param("r", UUID.fromString(order.get("runId").asText())).query(UUID.class).single();
        post("/api/v1/venues/" + venue + "/captures/" + capture + "/processing/retry", TestJwt.user(org, "operator").venues(venue).token(),
            null).andExpect(status().isAccepted());
    }

    /** Calibrates the order's run with the synthetic control points (an operator's act, between stages), as a run must be
     * before its metric stages. Returns the frame id. */
    protected String calibrateRun(JsonNode order) throws Exception {
        UUID org = UUID.fromString(order.get("organizationId").asText());
        UUID venue = UUID.fromString(order.get("venueId").asText());
        String operator = TestJwt.user(org, "operator").venues(venue).token();
        String json = post("/api/v1/venues/" + venue + "/reconstructions/" + order.get("runId").asText() + "/coordinate-frames", operator,
            controlPointCalibration(0)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(json).get("id").asText();
    }

    /** Reports what the real worker would: the stage's outputs, or -- for a metric stage given no coordinate frame -- a FAILED
     * NOT_CALIBRATED (chaya_worker.frames.require_canonical). */
    protected void succeed(JsonNode order) throws Exception {
        if (METRIC_STAGES.contains(order.get("stage").asText()) && frameOf(order) == null) {
            send(order, report("FAILED", List.of(), "NOT_CALIBRATED", "the reconstruction has no canonical coordinate frame"), svc)
                .andExpect(status().isOk());
            return;
        }
        send(order, report("SUCCEEDED", outputsFor(order), null, null), svc).andExpect(status().isOk());
    }

    /** Claims and succeeds stages until (and including) the given one. */
    protected List<JsonNode> runThrough(String lastStage) throws Exception {
        List<JsonNode> orders = new ArrayList<>();
        for (var stage : PipelineDefinition.STAGES) {
            JsonNode order = claimExpecting(stage.name());
            if (METRIC_STAGES.contains(stage.name()) && frameOf(order) == null) {
                // The operator calibrates once POSE_ESTIMATION has a frame; a metric stage claimed before that would fail
                // NOT_CALIBRATED, so this helper calibrates and re-claims it after the retry the operator would start.
                send(order, report("FAILED", List.of(), "NOT_CALIBRATED", "the reconstruction has no canonical coordinate frame"), svc)
                    .andExpect(status().isOk());
                calibrateRun(order);
                retryRun(order);
                order = claimExpecting(stage.name());
            }
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

    // ---- coordinate frames ------------------------------------------------------------------------

    /** The shared synthetic MATHEMATICAL calibration fixture (not venue data): packages/contracts/fixtures. */
    protected JsonNode calibrationFixture() throws IOException {
        return mapper.readTree(Path.of("../../packages/contracts/fixtures/synthetic-calibration.json").toFile());
    }

    /** A calibration request with the fixture's control points, their venue coordinates shifted by dx metres in x. */
    protected String controlPointCalibration(double dx) throws IOException {
        List<Map<String, Object>> cps = new ArrayList<>();
        for (JsonNode cp : calibrationFixture().get("controlPoints")) {
            List<Double> venue = List.of(cp.get("venue").get(0).asDouble() + dx, cp.get("venue").get(1).asDouble(), cp.get("venue").get(2).asDouble());
            cps.add(Map.of("label", cp.get("label").asText(), "reconstruction", mapper.convertValue(cp.get("reconstruction"), List.class),
                "venue", venue));
        }
        return mapper.writeValueAsString(Map.of("controlPoints", cps));
    }

    protected org.springframework.test.web.servlet.ResultActions calibrate(Started s, String body) throws Exception {
        return post("/api/v1/venues/" + s.c().venue() + "/reconstructions/" + s.run() + "/coordinate-frames", s.c().operator(), body);
    }

    protected JsonNode calibrateOk(Started s, String body) throws Exception {
        return mapper.readTree(calibrate(s, body).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }
}
