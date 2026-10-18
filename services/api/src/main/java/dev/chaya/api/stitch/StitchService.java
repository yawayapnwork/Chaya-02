package dev.chaya.api.stitch;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.chaya.api.audit.AuditService;
import dev.chaya.api.frame.CoordinateFrameService;
import dev.chaya.api.frame.FrameDtos.FrameView;
import dev.chaya.api.pipeline.PipelineDefinition;
import dev.chaya.api.pipeline.PipelineService;
import dev.chaya.api.rescan.ScanVersionService;
import dev.chaya.api.security.Actor;
import dev.chaya.api.security.TenantGuard;
import dev.chaya.api.stitch.StitchDtos.CreateRoom;
import dev.chaya.api.stitch.StitchDtos.RoomVersion;
import dev.chaya.api.stitch.StitchDtos.RoomView;
import dev.chaya.api.stitch.StitchDtos.StitchPair;
import dev.chaya.api.stitch.StitchDtos.StitchRequest;
import dev.chaya.api.stitch.StitchDtos.StitchRoom;
import dev.chaya.api.stitch.StitchDtos.StitchRoomRequest;
import dev.chaya.api.stitch.StitchDtos.StitchView;
import dev.chaya.api.web.ApiException;
import dev.chaya.api.web.NotFoundException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rooms of a floor and multi-room stitching (docs/multi-room.md).
 *
 * <p>A room is a `space` of the floor. Each is captured on its own (a capture naming its room: CaptureService#create) and
 * reconstructed with the room plan; its run's version is a ROOM VERSION, finalized once the room is calibrated and never
 * published as the floor's. A stitch combines FINALIZED room versions: it is a pipeline run of its own
 * (PipelineDefinition.STITCH_STAGES) on a capture with no media, whose DRAFT floor version records exactly what it combines
 * (scan_version_stitch_source: each room version, its pinned cloud and labels, and the calibration it is registered with).
 * Everything after that -- registration, stitching, the floor's planes, viewer asset and navigation, the version's
 * publication -- happens in {@link PipelineService}, where the worker's reports land. The stitch is followed, retried and
 * cancelled through its capture's ordinary processing endpoints.
 */
@Service
public class StitchService {

    static final Set<String> ROOM_KINDS = Set.of("ROOM", "CORRIDOR", "HALL", "OTHER");

    private final JdbcClient jdbc;
    private final TenantGuard guard;
    private final AuditService audit;
    private final PipelineService pipeline;
    private final ScanVersionService versions;
    private final CoordinateFrameService frames;
    private final StitchProperties props;
    private final ObjectMapper mapper;

    public StitchService(JdbcClient jdbc, TenantGuard guard, AuditService audit, PipelineService pipeline, ScanVersionService versions,
                         CoordinateFrameService frames, StitchProperties props, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.audit = audit;
        this.pipeline = pipeline;
        this.versions = versions;
        this.frames = frames;
        this.props = props;
        this.mapper = mapper;
    }

    // ---- rooms ----------------------------------------------------------------------------------------------------------

    @Transactional
    public RoomView createRoom(Actor actor, UUID venueId, UUID floorId, CreateRoom body) {
        guard.requireVenue(actor, venueId);
        requireFloor(venueId, floorId);
        String kind = body.kind() == null ? "ROOM" : body.kind().trim().toUpperCase(java.util.Locale.ROOT);
        if (!ROOM_KINDS.contains(kind)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ROOM_KIND_INVALID", "kind must be one of " + new java.util.TreeSet<>(ROOM_KINDS));
        }
        UUID id;
        try {
            id = jdbc.sql("""
                    INSERT INTO space (organization_id, venue_id, floor_id, name, kind) VALUES (:o, :v, :f, :n, :k) RETURNING id
                    """).param("o", actor.organizationId()).param("v", venueId).param("f", floorId).param("n", body.name().trim())
                .param("k", kind).query(UUID.class).single();
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "ROOM_NAME_TAKEN", "the floor already has a room named " + body.name().trim());
        }
        audit.success(actor, venueId, "room.create", "space", id, Map.of("floorId", floorId.toString(), "kind", kind));
        return rooms(actor, venueId, floorId).stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow();
    }

    @Transactional(readOnly = true)
    public List<RoomView> rooms(Actor actor, UUID venueId, UUID floorId) {
        guard.requireVenue(actor, venueId);
        requireFloor(venueId, floorId);
        record Row(UUID id, String name, String kind, Instant createdAt) {}
        List<Row> rows = jdbc.sql("""
                SELECT id, name, kind, created_at FROM space
                 WHERE floor_id = :f AND venue_id = :v AND organization_id = :o AND deleted_at IS NULL ORDER BY lower(name)
                """).param("f", floorId).param("v", venueId).param("o", actor.organizationId())
            .query((rs, i) -> new Row(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getTimestamp(4).toInstant())).list();
        List<RoomView> out = new ArrayList<>();
        for (Row r : rows) {
            record Capture(UUID id, String status) {}
            Capture latest = jdbc.sql("SELECT id, status FROM capture_session WHERE space_id = :s ORDER BY created_at DESC LIMIT 1")
                .param("s", r.id()).query((rs, i) -> new Capture(rs.getObject(1, UUID.class), rs.getString(2))).optional().orElse(null);
            out.add(new RoomView(r.id(), floorId, r.name(), r.kind(), r.createdAt(), roomVersion(r.id(), false), roomVersion(r.id(), true),
                latest == null ? null : latest.id(), latest == null ? null : latest.status()));
        }
        return out;
    }

    private RoomVersion roomVersion(UUID roomId, boolean finalizedOnly) {
        return jdbc.sql("""
                SELECT v.id, v.version_number, v.status, v.pipeline_run_id, r.status AS run_status, v.coordinate_frame_id,
                       v.finalized_at, v.created_at, coalesce(r.reconstruction_frame_run_id, r.id) AS frame_run
                  FROM scan_version v LEFT JOIN pipeline_run r ON r.id = v.pipeline_run_id
                 WHERE v.space_id = :s AND (v.status = 'FINALIZED' OR NOT :finalizedOnly)
                 ORDER BY v.version_number DESC LIMIT 1
                """).param("s", roomId).param("finalizedOnly", finalizedOnly)
            .query((rs, i) -> {
                boolean awaiting = "DRAFT".equals(rs.getString("status")) && "SUCCEEDED".equals(rs.getString("run_status"))
                    && frames.activeForRun(rs.getObject("frame_run", UUID.class)).filter(FrameView::canonical).isEmpty();
                return new RoomVersion(rs.getObject("id", UUID.class), rs.getInt("version_number"), rs.getString("status"),
                    rs.getObject("pipeline_run_id", UUID.class), rs.getString("run_status"), rs.getObject("coordinate_frame_id", UUID.class),
                    instant(rs.getTimestamp("finalized_at")), rs.getTimestamp("created_at").toInstant(), awaiting);
            }).optional().orElse(null);
    }

    /** Finalizes the room's latest successful run once its reconstruction is calibrated (its run ends DRAFT when it
     * succeeds uncalibrated). Never publishes it: a room version is never the floor's. */
    @Transactional
    public RoomView finalizeRoom(Actor actor, UUID venueId, UUID floorId, UUID roomId) {
        guard.requireVenue(actor, venueId);
        requireRoom(venueId, floorId, roomId);
        record Latest(UUID runId, UUID versionId, String status) {}
        Latest latest = jdbc.sql("""
                SELECT r.id, v.id, v.status FROM scan_version v JOIN pipeline_run r ON r.id = v.pipeline_run_id
                 WHERE v.space_id = :s AND r.status = 'SUCCEEDED' ORDER BY v.version_number DESC LIMIT 1
                """).param("s", roomId)
            .query((rs, i) -> new Latest(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3))).optional()
            .orElseThrow(() -> new NotFoundException("this room has no successfully reconstructed capture to finalize"));
        if (!"FINALIZED".equals(latest.status())) {
            pipeline.finalizeRoomRun(actor, latest.runId());
        }
        return rooms(actor, venueId, floorId).stream().filter(r -> r.id().equals(roomId)).findFirst().orElseThrow();
    }

    // ---- stitches -------------------------------------------------------------------------------------------------------

    private record Source(UUID roomId, String name, UUID versionId, int versionNumber, UUID runId, UUID cloud, UUID labels, FrameView frame) {}

    /**
     * Starts a stitch of the given rooms. Each must be a room of this floor with a FINALIZED room version (the given one,
     * or its latest) whose reconstruction has a canonical calibration (NOT_CALIBRATED otherwise, naming the room): rooms
     * are registered rigidly in metres with gravity known. Creates the stitch's capture (no media), scan and DRAFT floor
     * version with its sources, and starts the stitch plan. Nothing is published until every stage has succeeded.
     */
    @Transactional
    public StitchView initiate(Actor actor, UUID venueId, UUID floorId, StitchRequest request) {
        guard.requireVenue(actor, venueId);
        requireFloor(venueId, floorId);
        List<StitchRoomRequest> asked = request.rooms();
        if (asked.size() < 2 || asked.size() > props.maxRooms()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "STITCH_ROOMS_INVALID", "a stitch combines 2 to " + props.maxRooms()
                + " rooms (" + asked.size() + " given)");
        }
        if (new HashSet<>(asked.stream().map(StitchRoomRequest::roomId).toList()).size() != asked.size()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "STITCH_ROOMS_INVALID", "a room may appear only once in a stitch");
        }
        UUID anchorRoom = request.anchorRoomId() == null ? asked.get(0).roomId() : request.anchorRoomId();
        if (asked.stream().noneMatch(r -> r.roomId().equals(anchorRoom))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "STITCH_ROOMS_INVALID", "the anchor room must be one of the stitched rooms");
        }
        List<Source> sources = new ArrayList<>();
        List<String> uncalibrated = new ArrayList<>();
        for (StitchRoomRequest r : asked) {
            String name = requireRoom(venueId, floorId, r.roomId());
            Source s = source(r, name);
            if (s.frame() == null) {
                uncalibrated.add(name);
            }
            sources.add(s);
        }
        if (!uncalibrated.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, CoordinateFrameService.NOT_CALIBRATED, "rooms are registered in metres with a "
                + "known up direction; these rooms' reconstructions have no canonical calibration: " + String.join(", ", uncalibrated));
        }

        UUID captureId = jdbc.sql("""
                INSERT INTO capture_session (organization_id, venue_id, floor_id, operator_id, status, stitch)
                VALUES (:o, :v, :f, :op, 'PROCESSING', true) RETURNING id
                """).param("o", actor.organizationId()).param("v", venueId).param("f", floorId).param("op", actor.subject())
            .query(UUID.class).single();
        UUID scanId = jdbc.sql("INSERT INTO scan (organization_id, venue_id, capture_session_id) VALUES (:o, :v, :c) RETURNING id")
            .param("o", actor.organizationId()).param("v", venueId).param("c", captureId).query(UUID.class).single();
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("stitch", true);
        config.put("rooms", sources.stream().map(s -> s.roomId().toString()).toList());
        config.put("anchorRoomId", anchorRoom.toString());
        config.put("allowUnconfirmedRooms", Boolean.TRUE.equals(request.allowUnconfirmedRooms()));
        if (request.timeBudgetSeconds() != null) {
            config.put("timeBudgetSeconds", request.timeBudgetSeconds());
        }
        UUID versionId = versions.createFullRunDraft(actor, venueId, scanId, floorId, config);
        int position = 0;
        for (Source s : sources) {
            jdbc.sql("""
                    INSERT INTO scan_version_stitch_source (stitch_version_id, position, room_version_id, space_id, cloud_artifact_id,
                        labels_artifact_id, coordinate_frame_id, anchor)
                    VALUES (:sv, :p, :rv, :space, :cloud, :labels, :frame, :anchor)
                    """).param("sv", versionId).param("p", position++).param("rv", s.versionId()).param("space", s.roomId())
                .param("cloud", s.cloud()).param("labels", s.labels()).param("frame", s.frame().id()).param("anchor", s.roomId().equals(anchorRoom))
                .update();
        }
        UUID runId = pipeline.start(actor, venueId, captureId, scanId, versionId, PipelineDefinition.STITCH_STAGES, true,
            request.timeBudgetSeconds());
        audit.success(actor, venueId, "stitch.initiate", "scan_version", versionId, Map.of("floorId", floorId.toString(),
            "runId", runId.toString(), "rooms", sources.size(), "anchorRoomId", anchorRoom.toString(),
            "allowUnconfirmedRooms", Boolean.TRUE.equals(request.allowUnconfirmedRooms()),
            "roomVersions", sources.stream().map(s -> s.versionId().toString()).toList()));
        return view(actor, venueId, floorId, versionId);
    }

    /** The room version a stitch uses for a room: the one asked for, or the room's latest FINALIZED one; its pinned cloud,
     * that cloud's labels, and the room reconstruction's ACTIVE canonical calibration (frame null when it has none). */
    private Source source(StitchRoomRequest r, String name) {
        record Version(UUID id, int number, String status, UUID space, UUID runId, UUID frameRun) {}
        // the version asked for (whatever its status, to say why it cannot be used), or the room's latest FINALIZED one
        String which = r.scanVersionId() != null ? "v.id = :v" : "v.status = 'FINALIZED'";
        Version v = jdbc.sql("""
                SELECT v.id, v.version_number, v.status, v.space_id, v.pipeline_run_id, coalesce(p.reconstruction_frame_run_id, p.id)
                  FROM scan_version v JOIN pipeline_run p ON p.id = v.pipeline_run_id
                 WHERE v.space_id = :s AND %s
                 ORDER BY v.version_number DESC LIMIT 1
                """.formatted(which)).param("s", r.roomId()).param("v", r.scanVersionId())
            .query((rs, i) -> new Version(rs.getObject(1, UUID.class), rs.getInt(2), rs.getString(3), rs.getObject(4, UUID.class),
                rs.getObject(5, UUID.class), rs.getObject(6, UUID.class))).optional()
            .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "ROOM_NOT_RECONSTRUCTED", "room " + name + " has no "
                + (r.scanVersionId() == null ? "finalized room version: capture it, process it and calibrate it first" : "version "
                + r.scanVersionId())));
        if (!"FINALIZED".equals(v.status())) {
            throw new ApiException(HttpStatus.CONFLICT, ScanVersionService.VERSION_NOT_FINALIZED, "room " + name + "'s version "
                + v.id() + " is " + v.status() + "; only a FINALIZED room version can be stitched");
        }
        record Pinned(UUID cloud, UUID labels) {}
        Pinned pinned = jdbc.sql("""
                SELECT c.id, l.id FROM scan_version_artifact pin
                  JOIN processing_artifact c ON c.id = pin.artifact_id
                  JOIN processing_artifact l ON l.stage_run_id = c.stage_run_id AND l.kind = 'SEMANTIC_LABELS_CLEAN' AND NOT l.contains_pii
                 WHERE pin.scan_version_id = :v AND pin.kind = 'SPLAT_CLEAN'
                 ORDER BY l.created_at DESC LIMIT 1
                """).param("v", v.id()).query((rs, i) -> new Pinned(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)))
            .optional().orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "VERSION_INCOMPLETE", "room " + name + "'s version "
                + v.id() + " pins no cleaned cloud with labels; it cannot be stitched"));
        FrameView frame = frames.activeForRun(v.frameRun()).filter(FrameView::canonical).orElse(null);
        return new Source(r.roomId(), name, v.id(), v.number(), v.runId(), pinned.cloud(), pinned.labels(), frame);
    }

    @Transactional(readOnly = true)
    public List<StitchView> list(Actor actor, UUID venueId, UUID floorId) {
        guard.requireVenue(actor, venueId);
        requireFloor(venueId, floorId);
        List<UUID> ids = jdbc.sql("""
                SELECT v.id FROM scan_version v
                 WHERE v.floor_id = :f AND v.venue_id = :venue AND v.organization_id = :o
                   AND EXISTS (SELECT 1 FROM scan_version_stitch_source s WHERE s.stitch_version_id = v.id)
                 ORDER BY v.version_number DESC LIMIT 50
                """).param("f", floorId).param("venue", venueId).param("o", actor.organizationId()).query(UUID.class).list();
        return ids.stream().map(id -> view(actor, venueId, floorId, id)).toList();
    }

    @Transactional(readOnly = true)
    public StitchView get(Actor actor, UUID venueId, UUID floorId, UUID stitchId) {
        guard.requireVenue(actor, venueId);
        requireFloor(venueId, floorId);
        return view(actor, venueId, floorId, stitchId);
    }

    private StitchView view(Actor actor, UUID venueId, UUID floorId, UUID versionId) {
        record Row(int number, String status, boolean current, UUID scanId, UUID captureId, Map<String, Object> registration,
                   Map<String, Object> stitch, Map<String, Object> connectivity, Instant createdAt, Instant finalizedAt, String createdBy,
                   boolean allowUnconfirmed) {}
        Row row = jdbc.sql("""
                SELECT v.version_number, v.status, v.id = f.current_scan_version_id IS TRUE AS current, v.scan_id, sc.capture_session_id,
                       v.registration_report, v.stitch_report, v.room_connectivity, v.created_at, v.finalized_at, v.created_by,
                       coalesce((v.processing_config ->> 'allowUnconfirmedRooms')::boolean, false)
                  FROM scan_version v JOIN floor f ON f.id = v.floor_id JOIN scan sc ON sc.id = v.scan_id
                 WHERE v.id = :id AND v.floor_id = :f AND v.venue_id = :venue AND v.organization_id = :o
                   AND EXISTS (SELECT 1 FROM scan_version_stitch_source s WHERE s.stitch_version_id = v.id)
                """).param("id", versionId).param("f", floorId).param("venue", venueId).param("o", actor.organizationId())
            .query((rs, i) -> new Row(rs.getInt(1), rs.getString(2), rs.getBoolean(3), rs.getObject(4, UUID.class), rs.getObject(5, UUID.class),
                readJson(rs.getString(6)), readJson(rs.getString(7)), readJson(rs.getString(8)), rs.getTimestamp(9).toInstant(),
                instant(rs.getTimestamp(10)), rs.getString(11), rs.getBoolean(12)))
            .optional().orElseThrow(() -> new NotFoundException("stitch not found"));
        Map<String, Object> registration = row.registration();
        Set<String> registered = registration == null ? Set.of() : new HashSet<>(strings(registration.get("registered")));
        Map<?, ?> unregistered = registration != null && registration.get("unregistered") instanceof Map<?, ?> u ? u : Map.of();
        Set<String> unconfirmed = registration == null ? Set.of() : new HashSet<>(strings(registration.get("unconfirmed")));
        List<StitchRoom> rooms = jdbc.sql("""
                SELECT s.space_id, sp.name, s.room_version_id, rv.version_number, s.coordinate_frame_id, s.anchor
                  FROM scan_version_stitch_source s JOIN space sp ON sp.id = s.space_id JOIN scan_version rv ON rv.id = s.room_version_id
                 WHERE s.stitch_version_id = :v ORDER BY s.position
                """).param("v", versionId)
            .query((rs, i) -> {
                String space = rs.getString(1);
                Boolean reg = registration == null ? null : registered.contains(space) && !unregistered.containsKey(space);
                Object why = unregistered.get(space);
                return new StitchRoom(rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class), rs.getInt(4),
                    rs.getObject(5, UUID.class), rs.getBoolean(6), reg, why == null ? null : String.valueOf(why),
                    Boolean.TRUE.equals(reg) ? !unconfirmed.contains(space) : null);
            }).list();
        UUID anchor = rooms.stream().filter(StitchRoom::anchor).map(StitchRoom::roomId).findFirst().orElse(null);
        return new StitchView(versionId, floorId, row.number(), row.status(), row.current(), row.captureId(), row.scanId(),
            pipeline.runView(row.scanId()).orElse(null), anchor, row.allowUnconfirmed(), rooms, pairs(registration), registration,
            row.stitch(), row.connectivity(),
            row.createdAt(), row.finalizedAt(), row.createdBy());
    }

    /** The pairs ROOM_REGISTRATION tried, from its report, with what loop closure made of each accepted one. */
    private static List<StitchPair> pairs(Map<String, Object> registration) {
        if (registration == null || !(registration.get("pairs") instanceof List<?> pairs)) {
            return List.of();
        }
        Map<String, String> edgeStatus = new HashMap<>();
        Object graph = registration.get("graph");
        Object edges = graph instanceof Map<?, ?> g ? g.get("edges") : registration.get("edges");
        if (edges instanceof List<?> l) {
            for (Object e : l) {
                if (e instanceof Map<?, ?> m) {
                    edgeStatus.put(String.valueOf(m.get("edge")), String.valueOf(m.get("status")));
                }
            }
        }
        List<StitchPair> out = new ArrayList<>();
        for (Object o : pairs) {
            if (!(o instanceof Map<?, ?> p)) {
                continue;
            }
            String source = String.valueOf(p.get("source"));
            String target = String.valueOf(p.get("target"));
            out.add(new StitchPair(uuid(source), uuid(target), String.valueOf(p.get("status")), String.valueOf(p.get("outcome")),
                strings(p.get("failed_gates")), edgeStatus.get(source + "->" + target)));
        }
        return out;
    }

    // ---- helpers --------------------------------------------------------------------------------------------------------

    private void requireFloor(UUID venueId, UUID floorId) {
        if (jdbc.sql("SELECT count(*) FROM floor WHERE id = :f AND venue_id = :v AND deleted_at IS NULL")
                .param("f", floorId).param("v", venueId).query(Integer.class).single() == 0) {
            throw new NotFoundException("floor not found");
        }
    }

    /** The room's name; 404 unless it is a live room of this floor. */
    private String requireRoom(UUID venueId, UUID floorId, UUID roomId) {
        return jdbc.sql("SELECT name FROM space WHERE id = :s AND floor_id = :f AND venue_id = :v AND deleted_at IS NULL")
            .param("s", roomId).param("f", floorId).param("v", venueId).query(String.class).optional()
            .orElseThrow(() -> new NotFoundException("room " + roomId + " not found on this floor"));
    }

    private static UUID uuid(String s) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static List<String> strings(Object o) {
        return o instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of();
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJson(String jsonb) {
        if (jsonb == null) {
            return null;
        }
        try {
            return mapper.readValue(jsonb, Map.class);
        } catch (JsonProcessingException e) {
            return Map.of("unreadable", true);
        }
    }
}
