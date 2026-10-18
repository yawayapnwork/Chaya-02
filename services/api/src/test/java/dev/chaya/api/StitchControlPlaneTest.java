package dev.chaya.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import dev.chaya.api.pipeline.PipelineDefinition;
import dev.chaya.api.processing.JobStage;
import dev.chaya.api.rescan.ScanVersionService;
import dev.chaya.api.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;

/**
 * Multi-room stitching end to end through the real HTTP worker protocol (docs/multi-room.md): rooms are created, each is
 * captured and reconstructed with the room plan and calibrated, and a stitch of their FINALIZED room versions is driven
 * through its stages exactly as a worker would. It proves what the worker's own tests cannot:
 * <ul>
 *   <li>a room version is finalized with its own cloud, viewer asset and frame, and is never published as the floor's (not
 *       by a run, not by promote, not by the database, not as a re-scan's parent), nor moves the floor's frame;</li>
 *   <li>a stitch's work orders carry exactly its rooms: their recorded calibrations and their pinned clouds and labels;</li>
 *   <li>a successful stitch is published like a full reconstruction, in its anchor room's frame, with what each stage
 *       measured recorded on its version and the room versions it combined untouched;</li>
 *   <li>a rejected registration ends the run, cannot be retried and publishes nothing; a "successful" registration that
 *       left a room out, and a stitch report that does not prove its inputs, are refused by the control plane;</li>
 *   <li>a stitch whose anchor was recalibrated while it ran is not published;</li>
 *   <li>erasing a room's capture erases the stitches built from it, and only with cascade.</li>
 * </ul>
 * The worker's reports are fixtures in the worker's real report shape (chaya_worker.stages.room_registration, room_stitch);
 * the algorithms are tested in the worker's own suite. Skipped, not failed, without Docker.
 */
class StitchControlPlaneTest extends PipelineTestSupport {

    @Autowired ScanVersionService versions;
    @Autowired org.springframework.transaction.support.TransactionTemplate tx;

    record Room(UUID id, String name, UUID capture, UUID run, UUID version, UUID frame) {}

    private String floorUrl(Ctx c) {
        return "/api/v1/venues/" + c.venue() + "/floors/" + c.floor();
    }

    private String manager(Ctx c) {
        return TestJwt.user(c.org(), "venue-manager").venues(c.venue()).token();
    }

    private JsonNode json(org.springframework.test.web.servlet.ResultActions r) throws Exception {
        return mapper.readTree(r.andReturn().getResponse().getContentAsString());
    }

    private UUID createRoom(Ctx c, String name) throws Exception {
        return UUID.fromString(json(post(floorUrl(c) + "/rooms", c.operator(), "{\"name\":\"" + name + "\",\"kind\":\"ROOM\"}")
            .andExpect(status().isCreated())).get("id").asText());
    }

    /** A capture of the room, uploaded and started: the room plan. */
    private Started startRoomCapture(Ctx c, UUID roomId) throws Exception {
        String body = "{\"floorId\":\"" + c.floor() + "\",\"roomId\":\"" + roomId + "\"}";
        UUID capture = UUID.fromString(json(post("/api/v1/venues/" + c.venue() + "/captures", c.operator(), body)
            .andExpect(status().isCreated())).get("id").asText());
        var u = upload(c, capture, "VIDEO", "walk.mp4", "video/mp4", mp4(4096));
        awaitSettled(c, capture, u.mediaId());
        post(capUrl(c, capture) + "/complete-upload", c.operator(), null).andExpect(status().isOk());
        JsonNode n = json(post(capUrl(c, capture) + "/processing", c.operator(), null).andExpect(status().isAccepted()));
        return new Started(c, capture, UUID.fromString(n.get("scanId").asText()), UUID.fromString(n.get("run").get("id").asText()));
    }

    /** A room captured, reconstructed with the room plan (calibrated after POSE_ESTIMATION unless `calibrate` is false) and,
     * when calibrated, finalized. */
    private Room room(Ctx c, String name, boolean calibrate) throws Exception {
        UUID roomId = createRoom(c, name);
        Started s = startRoomCapture(c, roomId);
        assertThat(jdbc.sql("SELECT array_to_string(stages, ',') FROM pipeline_run WHERE id = :r").param("r", s.run()).query(String.class)
            .single()).as("a room capture runs the room plan")
            .isEqualTo(String.join(",", PipelineDefinition.ROOM_STAGES.stream().map(Enum::name).toList()));
        UUID frame = null;
        for (JobStage stage : PipelineDefinition.ROOM_STAGES) {
            JsonNode order = claimExpecting(stage.name());
            succeed(order);
            if (stage == JobStage.POSE_ESTIMATION && calibrate) {
                frame = UUID.fromString(calibrateRun(order));
            }
        }
        UUID version = jdbc.sql("SELECT scan_version_id FROM pipeline_run WHERE id = :r").param("r", s.run()).query(UUID.class).single();
        return new Room(roomId, name, s.capture(), s.run(), version, frame);
    }

    private String versionStatus(UUID version) {
        return jdbc.sql("SELECT status FROM scan_version WHERE id = :v").param("v", version).query(String.class).single();
    }

    private UUID current(Ctx c) {
        return jdbc.sql("SELECT current_scan_version_id FROM floor WHERE id = :f").param("f", c.floor()).query(UUID.class).optional().orElse(null);
    }

    private JsonNode startStitch(Ctx c, Room anchor, Room... others) throws Exception {
        List<String> rooms = new ArrayList<>(List.of("{\"roomId\":\"" + anchor.id() + "\"}"));
        for (Room r : others) {
            rooms.add("{\"roomId\":\"" + r.id() + "\"}");
        }
        String body = "{\"rooms\":[" + String.join(",", rooms) + "],\"anchorRoomId\":\"" + anchor.id()
            + "\",\"allowUnconfirmedRooms\":true}";
        return json(post(floorUrl(c) + "/stitches", c.operator(), body).andExpect(status().isCreated()));
    }

    private Map<String, Object> reportWith(String status, List<Map<String, Object>> artifacts, String code, String message,
                                           Map<String, Object> config, Map<String, Object> errorDetails) {
        Map<String, Object> r = report(status, artifacts, code, message);
        r.put("command", Map.of("argv", List.of("test-stage"), "config", config));
        r.put("errorDetails", errorDetails);
        return r;
    }

    private static List<String> spaces(JsonNode order) {
        List<String> out = new ArrayList<>();
        order.get("rooms").forEach(r -> out.add(r.get("spaceId").asText()));
        return out;
    }

    /** ROOM_REGISTRATION's summary (config.registration) in the worker's shape, every room of the order placed. */
    private Map<String, Object> registration(JsonNode order) {
        List<String> rooms = spaces(order);
        List<Map<String, Object>> pairs = new ArrayList<>();
        for (int i = 0; i < rooms.size(); i++) {
            for (int j = i + 1; j < rooms.size(); j++) {
                pairs.add(Map.of("source", rooms.get(j), "target", rooms.get(i), "status", "ACCEPTED", "outcome", "REGISTERED",
                    "failed_gates", List.of()));
            }
        }
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("method", "ROOM_FEATURE_GRAPH");
        doc.put("status", "ACCEPTED");
        doc.put("anchor", rooms.get(0));
        doc.put("registered", rooms);
        doc.put("unregistered", Map.of());
        doc.put("unconfirmed", rooms.subList(1, rooms.size()));  // two rooms: one registration, no loop
        doc.put("allow_unconfirmed_rooms", true);
        doc.put("pairs", pairs);
        doc.put("loop_closures", List.of());
        return doc;
    }

    private void succeedRegistration(JsonNode order) throws Exception {
        send(order, reportWith("SUCCEEDED", List.of(artifact(order, "room-registration-report.json", "ROOM_REGISTRATION_REPORT", false,
            false, mapper.writeValueAsString(registration(order)))), null, null, Map.of("registration", registration(order)), null), svc)
            .andExpect(status().isOk());
    }

    /** ROOM_STITCH's outputs in the worker's shape: the STITCH_REPORT names the order's ROOM_CLOUD / ROOM_LABELS inputs (or,
     * with `otherCloud`, a cloud the stitch did not record) and the SPLAT_MERGED and SEMANTIC_LABELS_MERGED it publishes. */
    private List<Map<String, Object>> stitchOutputs(JsonNode order, boolean otherCloud) throws Exception {
        Map<String, JsonNode> inputs = new LinkedHashMap<>();
        order.get("inputs").forEach(in -> inputs.put(in.get("artifactId").asText(), in));
        String merged = "stitched-cloud:" + order.get("id").asText();
        String labels = "{\"labels\":[\"floor\",\"wall\"]}";
        List<Map<String, Object>> rooms = new ArrayList<>();
        List<Map<String, Object>> blocks = new ArrayList<>();
        int start = 0;
        for (JsonNode r : order.get("rooms")) {
            JsonNode cloud = inputs.get(r.get("cloudArtifactId").asText());
            JsonNode lab = inputs.get(r.get("labelsArtifactId").asText());
            rooms.add(Map.of("space_id", r.get("spaceId").asText(),
                "cloud", Map.of("artifact_id", otherCloud ? UUID.randomUUID().toString() : cloud.get("artifactId").asText(),
                    "sha256", cloud.get("sha256").asText()),
                "labels", Map.of("artifact_id", lab.get("artifactId").asText(), "sha256", lab.get("sha256").asText())));
            blocks.add(Map.of("piece", r.get("spaceId").asText(), "start", start, "count", 10));
            start += 10;
        }
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("source", sourceOf(order));
        doc.put("inputs", Map.of("rooms", rooms));
        doc.put("output", Map.of("artifact", "splat-merged.ply", "sha256", sha256(merged.getBytes(StandardCharsets.UTF_8))));
        doc.put("labels", Map.of("stitched", true, "sha256", sha256(labels.getBytes(StandardCharsets.UTF_8))));
        doc.put("blocks", blocks);
        doc.put("duplicate_fraction_after", 0.002);
        return List.of(artifact(order, "splat-merged.ply", "SPLAT_MERGED", false, false, merged),
            artifact(order, "semantic-labels-merged.json", "SEMANTIC_LABELS_MERGED", false, false, labels),
            artifact(order, "stitch-report.json", "STITCH_REPORT", false, false, mapper.writeValueAsString(doc)),
            artifact(order, "stitch-index.npz", "STITCH_INDEX", false, false, "index-bytes"));
    }

    private static final Map<String, Object> CONNECTED = Map.of("connected", true, "unreachable_rooms", List.of(),
        "step_free_connected", true);

    /** Drives a started stitch through all its stages as a successful worker would. */
    private void driveStitch() throws Exception {
        succeedRegistration(claimExpecting("ROOM_REGISTRATION"));
        JsonNode stitch = claimExpecting("ROOM_STITCH");
        send(stitch, reportWith("SUCCEEDED", stitchOutputs(stitch, false), null, null, Map.of("stitch", Map.of("duplicate_fraction_after", 0.002)),
            null), svc).andExpect(status().isOk());
        succeed(claimExpecting("PLANE_FITTING"));
        succeed(claimExpecting("ARTIFACT_GENERATION"));
        JsonNode nav = claimExpecting("NAVIGATION_BAKING");
        send(nav, reportWith("SUCCEEDED", navigationOutputs(nav, frameOf(nav)), null, null, Map.of("rooms", CONNECTED), null), svc)
            .andExpect(status().isOk());
    }

    // ---- rooms ----------------------------------------------------------------------------------------------------------

    @Test
    void aRoomCaptureIsFinalizedAsARoomVersionAndNeverPublishedAsTheFloor() throws Exception {
        Ctx c = ctx();
        Room kitchen = room(c, "Kitchen", true);
        assertThat(versionStatus(kitchen.version())).isEqualTo("FINALIZED");
        assertThat(jdbc.sql("SELECT space_id FROM scan_version WHERE id = :v").param("v", kitchen.version()).query(UUID.class).single())
            .isEqualTo(kitchen.id());
        assertThat(current(c)).as("one room is never what the floor publishes").isNull();
        assertThat(jdbc.sql("SELECT current_coordinate_frame_id FROM floor WHERE id = :f").param("f", c.floor()).query(UUID.class)
            .optional().orElse(null)).as("and its calibration does not move the floor's frame").isNull();
        assertThat(jdbc.sql("SELECT kind FROM scan_version_artifact WHERE scan_version_id = :v ORDER BY kind").param("v", kitchen.version())
            .query(String.class).list()).contains("SPLAT_CLEAN", "KSPLAT").doesNotContain("NAVMESH", "DETECTED_OBJECTS");

        JsonNode rooms = json(get(floorUrl(c) + "/rooms", c.operator()).andExpect(status().isOk()));
        assertThat(rooms.get(0).get("latestFinalizedVersion").get("id").asText()).isEqualTo(kitchen.version().toString());
        assertThat(rooms.get(0).get("latestCaptureId").asText()).isEqualTo(kitchen.capture().toString());
        JsonNode capture = json(get(capUrl(c, kitchen.capture()), c.operator()));
        assertThat(capture.get("purpose").asText()).isEqualTo("ROOM");
        assertThat(capture.get("roomId").asText()).isEqualTo(kitchen.id().toString());

        // Not by promote, not by the database, not as a re-scan's parent, not by finalize-current.
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> versions.promote(kitchen.version(), Map.of())))
            .isInstanceOf(ApiException.class).hasMessageContaining("one room");
        assertThatThrownBy(() -> jdbc.sql("UPDATE floor SET current_scan_version_id = :v WHERE id = :f").param("v", kitchen.version())
            .param("f", c.floor()).update()).hasMessageContaining("room version");
        post(floorUrl(c) + "/rescan", c.operator(), "{\"parentVersionId\":\"" + kitchen.version() + "\",\"region\":{\"points\":"
            + "[[0,0],[0,3],[3,3],[3,0]]}}").andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value(ScanVersionService.ROOM_VERSION_NOT_PUBLISHABLE));
        post(floorUrl(c) + "/scan-versions/finalize-current", c.operator(), null).andExpect(status().isNotFound());

        JsonNode listing = json(get(floorUrl(c) + "/reconstructions", c.operator()).andExpect(status().isOk()));
        assertThat(listing.get(0).get("kind").asText()).isEqualTo("ROOM");
        assertThat(listing.get(0).get("roomName").asText()).isEqualTo("Kitchen");
    }

    @Test
    void aRoomCalibratedOnlyAfterItsRunIsFinalizedOnRequest() throws Exception {
        Ctx c = ctx();
        Room hall = room(c, "Hall", false);
        assertThat(versionStatus(hall.version())).isEqualTo("DRAFT");
        JsonNode rooms = json(get(floorUrl(c) + "/rooms", c.operator()));
        assertThat(rooms.get(0).get("latestVersion").get("awaitingCalibration").asBoolean()).isTrue();
        post(floorUrl(c) + "/rooms/" + hall.id() + "/versions/finalize-current", c.operator(), null).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("NOT_CALIBRATED"));

        post("/api/v1/venues/" + c.venue() + "/reconstructions/" + hall.run() + "/coordinate-frames", c.operator(),
            controlPointCalibration(0)).andExpect(status().isCreated());
        JsonNode finalized = json(post(floorUrl(c) + "/rooms/" + hall.id() + "/versions/finalize-current", c.operator(), null)
            .andExpect(status().isOk()));
        assertThat(finalized.get("latestFinalizedVersion").get("id").asText()).isEqualTo(hall.version().toString());
        assertThat(current(c)).isNull();
    }

    @Test
    void roomsAreNamedOncePerFloorAndOfAKnownKind() throws Exception {
        Ctx c = ctx();
        createRoom(c, "Lobby");
        post(floorUrl(c) + "/rooms", c.operator(), "{\"name\":\"lobby\"}").andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("ROOM_NAME_TAKEN"));
        post(floorUrl(c) + "/rooms", c.operator(), "{\"name\":\"Attic\",\"kind\":\"BALLROOM\"}").andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("ROOM_KIND_INVALID"));
        post("/api/v1/venues/" + c.venue() + "/captures", c.operator(), "{\"roomId\":\"" + UUID.randomUUID() + "\"}")
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("ROOM_REQUIRES_FLOOR"));
        post("/api/v1/venues/" + c.venue() + "/captures", c.operator(), "{\"floorId\":\"" + c.floor() + "\",\"roomId\":\""
            + UUID.randomUUID() + "\"}").andExpect(status().isNotFound());
    }

    // ---- stitching ------------------------------------------------------------------------------------------------------

    @Test
    void aStitchOfTwoRoomsIsPublishedInItsAnchorsFrameWithEverythingItMeasured() throws Exception {
        Ctx c = ctx();
        Room a = room(c, "Room A", true);
        Room b = room(c, "Room B", true);
        JsonNode started = startStitch(c, a, b);
        UUID stitch = UUID.fromString(started.get("id").asText());
        assertThat(started.get("status").asText()).isEqualTo("DRAFT");
        assertThat(started.get("anchorRoomId").asText()).isEqualTo(a.id().toString());
        assertThat(started.get("rooms").get(0).get("registered").isNull()).as("not yet registered").isTrue();

        JsonNode reg = claimExpecting("ROOM_REGISTRATION");
        assertThat(reg.get("coordinateFrame").get("id").asText()).as("the run works in the anchor's frame").isEqualTo(a.frame().toString());
        assertThat(spaces(reg)).containsExactly(a.id().toString(), b.id().toString());
        Map<String, String> kinds = new LinkedHashMap<>();
        reg.get("inputs").forEach(in -> kinds.put(in.get("artifactId").asText(), in.get("kind").asText()));
        for (JsonNode r : reg.get("rooms")) {
            assertThat(kinds.get(r.get("cloudArtifactId").asText())).isEqualTo("ROOM_CLOUD");
            assertThat(kinds.get(r.get("labelsArtifactId").asText())).isEqualTo("ROOM_LABELS");
            UUID pinned = jdbc.sql("SELECT artifact_id FROM scan_version_artifact WHERE scan_version_id = :v AND kind = 'SPLAT_CLEAN'")
                .param("v", UUID.fromString(r.get("scanVersionId").asText())).query(UUID.class).single();
            assertThat(r.get("cloudArtifactId").asText()).as("the room version's own pinned cloud").isEqualTo(pinned.toString());
            assertThat(r.get("coordinateFrame").get("metricStatus").asText()).isEqualTo("METRIC");
        }
        assertThat(reg.get("rooms").get(0).get("anchor").asBoolean()).isTrue();
        assertThat(kinds.values()).containsOnly("ROOM_CLOUD", "ROOM_LABELS");
        assertThat(reg.get("stitchOptions").get("allowUnconfirmedRooms").asBoolean()).as("as the stitch was started").isTrue();
        succeedRegistration(reg);

        JsonNode stitchOrder = claimExpecting("ROOM_STITCH");
        assertThat(stitchOrder.get("rooms")).hasSize(2);
        assertThat(kinds(stitchOrder)).contains("ROOM_REGISTRATION_REPORT", "ROOM_CLOUD", "ROOM_LABELS");
        send(stitchOrder, reportWith("SUCCEEDED", stitchOutputs(stitchOrder, false), null, null,
            Map.of("stitch", Map.of("duplicate_fraction_after", 0.002)), null), svc).andExpect(status().isOk());
        JsonNode planes = claimExpecting("PLANE_FITTING");
        assertThat(kinds(planes)).contains("SPLAT_MERGED", "SEMANTIC_LABELS_MERGED").doesNotContain("ROOM_CLOUD");
        assertThat(planes.get("rooms").isNull()).isTrue();
        succeed(planes);
        succeed(claimExpecting("ARTIFACT_GENERATION"));
        JsonNode nav = claimExpecting("NAVIGATION_BAKING");
        assertThat(kinds(nav)).contains("STITCH_REPORT", "SPLAT_MERGED");
        send(nav, reportWith("SUCCEEDED", navigationOutputs(nav, frameOf(nav)), null, null, Map.of("rooms", CONNECTED), null), svc)
            .andExpect(status().isOk());

        assertThat(versionStatus(stitch)).isEqualTo("FINALIZED");
        assertThat(current(c)).as("published as the floor").isEqualTo(stitch);
        assertThat(jdbc.sql("SELECT coordinate_frame_id FROM scan_version WHERE id = :v").param("v", stitch).query(UUID.class).single())
            .as("in its anchor room's frame").isEqualTo(a.frame());
        assertThat(jdbc.sql("SELECT kind FROM scan_version_artifact WHERE scan_version_id = :v AND owner_version_id = :v ORDER BY kind")
            .param("v", stitch).query(String.class).list())
            .contains("SPLAT_MERGED", "KSPLAT", "NAVMESH", "NAVMESH_MANIFEST", "NAVIGATION_GRAPH").doesNotContain("SPLAT_CLEAN");
        assertThat(jdbc.sql("SELECT count(*) FROM navigation_graph WHERE scan_version_id = :v AND status = 'ACTIVE'").param("v", stitch)
            .query(Integer.class).single()).isPositive();
        for (Room r : List.of(a, b)) {
            assertThat(versionStatus(r.version())).as("the room versions are untouched").isEqualTo("FINALIZED");
        }

        JsonNode view = json(get(floorUrl(c) + "/stitches/" + stitch, c.operator()).andExpect(status().isOk()));
        assertThat(view.get("current").asBoolean()).isTrue();
        assertThat(view.get("run").get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(view.get("rooms").findValues("registered").stream().allMatch(JsonNode::asBoolean)).isTrue();
        assertThat(view.get("allowUnconfirmedRooms").asBoolean()).isTrue();
        assertThat(view.get("rooms").get(0).get("loopConfirmed").asBoolean()).as("the anchor").isTrue();
        assertThat(view.get("rooms").get(1).get("loopConfirmed").asBoolean()).as("two rooms: one registration, no loop").isFalse();
        assertThat(view.get("pairs").get(0).get("outcome").asText()).isEqualTo("REGISTERED");
        assertThat(view.get("registration").get("status").asText()).isEqualTo("ACCEPTED");
        assertThat(view.get("stitch").get("duplicate_fraction_after").asDouble()).isEqualTo(0.002);
        assertThat(view.get("connectivity").get("connected").asBoolean()).isTrue();
        assertThat(json(get(floorUrl(c) + "/stitches", c.operator())).get(0).get("id").asText()).isEqualTo(stitch.toString());

        JsonNode listing = json(get(floorUrl(c) + "/reconstructions", c.operator()));
        List<String> listed = new ArrayList<>();
        listing.forEach(v -> listed.add(v.get("kind").asText()));
        assertThat(listed).containsExactly("STITCHED", "ROOM", "ROOM");
        assertThat(json(get(capUrl(c, UUID.fromString(view.get("captureId").asText())), c.operator())).get("purpose").asText())
            .isEqualTo("STITCH");
        assertThat(auditCount(c.org(), "stitch.initiate")).isOne();
        assertThat(auditCount(c.org(), "stitch.outcome")).isOne();
    }

    private static List<String> kinds(JsonNode order) {
        List<String> out = new ArrayList<>();
        order.get("inputs").forEach(in -> out.add(in.get("kind").asText()));
        return out;
    }

    @Test
    void aStitchRefusesRoomsNoLoopConfirmsUnlessItIsStartedAcceptingThem() throws Exception {
        Ctx c = ctx();
        Room a = room(c, "Room A", true);
        Room b = room(c, "Room B", true);
        String body = "{\"rooms\":[{\"roomId\":\"" + a.id() + "\"},{\"roomId\":\"" + b.id() + "\"}]}";
        JsonNode started = json(post(floorUrl(c) + "/stitches", c.operator(), body).andExpect(status().isCreated()));
        assertThat(started.get("allowUnconfirmedRooms").asBoolean()).as("off unless asked for").isFalse();
        assertThat(started.get("anchorRoomId").asText()).as("default: the first room").isEqualTo(a.id().toString());
        JsonNode reg = claimExpecting("ROOM_REGISTRATION");
        assertThat(reg.get("stitchOptions").get("allowUnconfirmedRooms").asBoolean()).isFalse();
    }

    @Test
    void aRejectedRegistrationEndsTheStitchPublishesNothingAndCannotBeRetried() throws Exception {
        Ctx c = ctx();
        Room a = room(c, "Room A", true);
        Room b = room(c, "Room B", true);
        UUID stitch = UUID.fromString(startStitch(c, a, b).get("id").asText());
        JsonNode reg = claimExpecting("ROOM_REGISTRATION");
        Map<String, Object> details = new LinkedHashMap<>(registration(reg));
        details.put("status", "REJECTED");
        details.put("registered", List.of(a.id().toString()));
        details.put("unregistered", Map.of(b.id().toString(), "no accepted registration links it to the fixed rooms"));
        details.put("pairs", List.of(Map.of("source", b.id().toString(), "target", a.id().toString(), "status", "REJECTED",
            "outcome", "WEAK_OVERLAP", "failed_gates", List.of("constraint_ratio"))));
        send(reg, reportWith("FAILED", List.of(), "REGISTRATION_REJECTED", "1 of 2 rooms could not be registered", Map.of(), details), svc)
            .andExpect(status().isOk());

        assertThat(versionStatus(stitch)).isEqualTo("DRAFT");
        assertThat(current(c)).isNull();
        JsonNode view = json(get(floorUrl(c) + "/stitches/" + stitch, c.operator()));
        assertThat(view.get("run").get("status").asText()).isEqualTo("FAILED");
        assertThat(view.get("run").get("failureCode").asText()).isEqualTo("REGISTRATION_REJECTED");
        assertThat(view.get("run").get("retryable").asBoolean()).isFalse();
        JsonNode roomB = view.get("rooms").get(1);
        assertThat(roomB.get("registered").asBoolean()).isFalse();
        assertThat(roomB.get("reason").asText()).contains("no accepted registration");
        assertThat(view.get("pairs").get(0).get("failedGates").get(0).asText()).isEqualTo("constraint_ratio");
        post(capUrl(c, UUID.fromString(view.get("captureId").asText())) + "/processing/retry", c.operator(), null)
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RUN_NOT_RETRYABLE"));
        assertThat(claim()).as("nothing more is queued").isNull();
    }

    @Test
    void aRegistrationThatLeftARoomOutIsRefusedWhateverTheWorkerReports() throws Exception {
        Ctx c = ctx();
        Room a = room(c, "Room A", true);
        Room b = room(c, "Room B", true);
        UUID stitch = UUID.fromString(startStitch(c, a, b).get("id").asText());
        JsonNode reg = claimExpecting("ROOM_REGISTRATION");
        Map<String, Object> partial = new LinkedHashMap<>(registration(reg));
        partial.put("registered", List.of(a.id().toString()));
        send(reg, reportWith("SUCCEEDED", List.of(artifact(reg, "room-registration-report.json", "ROOM_REGISTRATION_REPORT", false, false,
            mapper.writeValueAsString(partial))), null, null, Map.of("registration", partial), null), svc).andExpect(status().isOk());
        JsonNode view = json(get(floorUrl(c) + "/stitches/" + stitch, c.operator()));
        assertThat(view.get("run").get("failureCode").asText()).isEqualTo("REGISTRATION_REJECTED");
        assertThat(view.get("run").get("failureMessage").asText()).contains("did not place every room");
        assertThat(current(c)).isNull();
    }

    @Test
    void aStitchReportThatDoesNotProveItsInputsIsRefusedAndRetryable() throws Exception {
        Ctx c = ctx();
        Room a = room(c, "Room A", true);
        Room b = room(c, "Room B", true);
        UUID stitch = UUID.fromString(startStitch(c, a, b).get("id").asText());
        succeedRegistration(claimExpecting("ROOM_REGISTRATION"));
        JsonNode order = claimExpecting("ROOM_STITCH");
        send(order, reportWith("SUCCEEDED", stitchOutputs(order, true), null, null, Map.of("stitch", Map.of()), null), svc)
            .andExpect(status().isOk());
        JsonNode view = json(get(floorUrl(c) + "/stitches/" + stitch, c.operator()));
        assertThat(view.get("run").get("failureCode").asText()).isEqualTo("STITCH_LINEAGE_INVALID");
        assertThat(view.get("run").get("retryable").asBoolean()).as("a broken report, not a rejection").isTrue();
        assertThat(jdbc.sql("SELECT count(*) FROM processing_artifact a JOIN pipeline_stage_run sr ON sr.id = a.stage_run_id "
                + "WHERE sr.run_id = :r AND sr.status = 'SUCCEEDED' AND a.kind = 'SPLAT_MERGED'")
            .param("r", UUID.fromString(view.get("run").get("id").asText())).query(Integer.class).single()).isZero();
    }

    @Test
    void aStitchNeedsTwoCalibratedFinalizedRoomsOfItsFloor() throws Exception {
        Ctx c = ctx();
        Room a = room(c, "Room A", true);
        Room uncalibrated = room(c, "Room B", false);
        UUID empty = createRoom(c, "Room C");
        String one = "{\"rooms\":[{\"roomId\":\"" + a.id() + "\"}]}";
        post(floorUrl(c) + "/stitches", c.operator(), one).andExpect(status().isBadRequest());
        String twice = "{\"rooms\":[{\"roomId\":\"" + a.id() + "\"},{\"roomId\":\"" + a.id() + "\"}]}";
        post(floorUrl(c) + "/stitches", c.operator(), twice).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("STITCH_ROOMS_INVALID"));
        String notReconstructed = "{\"rooms\":[{\"roomId\":\"" + a.id() + "\"},{\"roomId\":\"" + empty + "\"}]}";
        post(floorUrl(c) + "/stitches", c.operator(), notReconstructed).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("ROOM_NOT_RECONSTRUCTED"));
        String draft = "{\"rooms\":[{\"roomId\":\"" + a.id() + "\"},{\"roomId\":\"" + uncalibrated.id() + "\",\"scanVersionId\":\""
            + uncalibrated.version() + "\"}]}";
        post(floorUrl(c) + "/stitches", c.operator(), draft).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("VERSION_NOT_FINALIZED"));
        Ctx other = ctx();
        String foreign = "{\"rooms\":[{\"roomId\":\"" + a.id() + "\"},{\"roomId\":\"" + createRoom(other, "Elsewhere") + "\"}]}";
        post(floorUrl(c) + "/stitches", c.operator(), foreign).andExpect(status().isNotFound());
        assertThat(jdbc.sql("SELECT count(*) FROM scan_version_stitch_source s JOIN scan_version v ON v.id = s.stitch_version_id "
            + "WHERE v.floor_id = :f").param("f", c.floor()).query(Integer.class).single()).as("nothing was started").isZero();
    }

    @Test
    void aStitchWhoseAnchorWasRecalibratedWhileItRanIsNotPublished() throws Exception {
        Ctx c = ctx();
        Room a = room(c, "Room A", true);
        Room b = room(c, "Room B", true);
        UUID stitch = UUID.fromString(startStitch(c, a, b).get("id").asText());
        succeedRegistration(claimExpecting("ROOM_REGISTRATION"));
        JsonNode order = claimExpecting("ROOM_STITCH");
        send(order, reportWith("SUCCEEDED", stitchOutputs(order, false), null, null, Map.of("stitch", Map.of()), null), svc)
            .andExpect(status().isOk());
        post("/api/v1/venues/" + c.venue() + "/reconstructions/" + a.run() + "/coordinate-frames", c.operator(),
            controlPointCalibration(0.5)).andExpect(status().isCreated());
        // PLANE_FITTING onwards run in the anchor's new frame; the published version would pin it
        succeed(claimExpecting("PLANE_FITTING"));
        succeed(claimExpecting("ARTIFACT_GENERATION"));
        JsonNode nav = claimExpecting("NAVIGATION_BAKING");
        send(nav, reportWith("SUCCEEDED", navigationOutputs(nav, frameOf(nav)), null, null, Map.of("rooms", CONNECTED), null), svc)
            .andExpect(status().isOk());
        JsonNode view = json(get(floorUrl(c) + "/stitches/" + stitch, c.operator()));
        assertThat(view.get("run").get("failureCode").asText()).isEqualTo("STITCH_FRAME_CHANGED");
        assertThat(view.get("run").get("retryable").asBoolean()).isFalse();
        assertThat(versionStatus(stitch)).isEqualTo("DRAFT");
        assertThat(current(c)).isNull();
    }

    @Test
    void erasingARoomsCaptureErasesTheStitchesBuiltFromItButOnlyWithCascade() throws Exception {
        Ctx c = ctx();
        Room a = room(c, "Room A", true);
        Room b = room(c, "Room B", true);
        UUID stitch = UUID.fromString(startStitch(c, a, b).get("id").asText());
        driveStitch();
        assertThat(current(c)).isEqualTo(stitch);

        call(HttpMethod.DELETE, capUrl(c, b.capture()), manager(c), null).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("ERASURE_HAS_DEPENDENTS"));
        assertThat(versionStatus(stitch)).as("refused: nothing erased").isEqualTo("FINALIZED");

        call(HttpMethod.DELETE, capUrl(c, b.capture()) + "?cascade=true", manager(c), null).andExpect(status().isOk());
        assertThat(jdbc.sql("SELECT count(*) FROM scan_version WHERE id IN (:ids)").param("ids", List.of(stitch, b.version()))
            .query(Integer.class).single()).isZero();
        assertThat(versionStatus(a.version())).as("the other room is not derived from the erased one").isEqualTo("FINALIZED");
        assertThat(current(c)).as("the floor publishes nothing that was erased").isNull();
    }
}
