package dev.chaya.api.venue;

import dev.chaya.api.audit.AuditService;
import dev.chaya.api.frame.CoordinateFrameService;
import dev.chaya.api.frame.FrameDtos.FrameView;
import dev.chaya.api.security.Actor;
import dev.chaya.api.security.TenantGuard;
import dev.chaya.api.venue.FloorGeometry.FloorFacts;
import dev.chaya.api.venue.FloorGeometry.Finding;
import dev.chaya.api.venue.FloorGeometry.Severity;
import dev.chaya.api.web.ApiException;
import dev.chaya.api.web.NotFoundException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Floors of a venue (docs/navigation.md, "Floors"): what captures, scan versions, POIs, navigation graphs and floor
 * connections are recorded against.
 *
 * <p>Each floor is reconstructed and calibrated on its own, so Chaya does not measure how floors relate. What it holds:
 * <ul>
 *   <li>the floor's level (its order in the building) and name;</li>
 *   <li>optionally its elevation above the venue's elevation reference, recorded by staff from a survey or a measurement
 *       on site, with who recorded it and how. Unknown stays unknown: nothing is estimated;</li>
 *   <li>the registered floor connections between floors (FloorConnectionService).</li>
 * </ul>
 * Recorded values are checked against each other when written (levels and elevations agree in order; a stair or ramp is
 * long enough for the rise it climbs), and {@link #consistency} checks them against geometry measured in a shared
 * surveyed datum where two floors have one. Contradictions are refused at entry (400), not silently stored.
 */
@Service
public class FloorService {

    public static final Set<String> ELEVATION_SOURCES = Set.of("SURVEYED", "MEASURED_ON_SITE");

    /** elevationM: metres above the venue's elevation reference; source SURVEYED or MEASURED_ON_SITE. */
    public record Elevation(double elevationM, String source, String reference, String recordedBy, Instant recordedAt) {}

    /** currentScanVersionId: what the floor publishes now (null: nothing). elevation: null when not recorded. */
    public record Floor(UUID id, int level, String name, UUID currentScanVersionId, Elevation elevation) {}

    public record FloorStatus(UUID floorId, int level, String name, Elevation elevation, String horizontalDatum,
                              boolean canonicalFrame) {}

    /** connectionId null: a finding about the floors themselves. */
    public record VenueFinding(UUID connectionId, String code, String severity, String detail) {}

    /** alignment: CONSISTENT (compared in a shared surveyed datum, no contradiction), INCONSISTENT, or NOT_COMPARABLE (no
     * shared datum: the floors are related only by their registered connections). */
    public record FloorPair(UUID fromFloorId, UUID toFloorId, UUID connectionId, String alignment, String detail) {}

    public record ConsistencyReport(List<FloorStatus> floors, List<FloorPair> pairs, List<VenueFinding> findings, boolean consistent) {}

    private static final String SELECT = """
            SELECT id, level, name, current_scan_version_id, elevation_m, elevation_source, elevation_reference,
                   elevation_recorded_by, elevation_recorded_at
              FROM floor WHERE venue_id = :v AND organization_id = :o AND deleted_at IS NULL""";

    private final JdbcClient jdbc;
    private final TenantGuard guard;
    private final AuditService audit;
    private final CoordinateFrameService frames;

    public FloorService(JdbcClient jdbc, TenantGuard guard, AuditService audit, CoordinateFrameService frames) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.audit = audit;
        this.frames = frames;
    }

    private static Floor map(ResultSet rs, int i) throws SQLException {
        Object e = rs.getObject("elevation_m");
        Elevation elevation = e == null ? null : new Elevation(((Number) e).doubleValue(), rs.getString("elevation_source"),
            rs.getString("elevation_reference"), rs.getString("elevation_recorded_by"),
            rs.getTimestamp("elevation_recorded_at").toInstant());
        return new Floor(rs.getObject("id", UUID.class), rs.getInt("level"), rs.getString("name"),
            rs.getObject("current_scan_version_id", UUID.class), elevation);
    }

    @Transactional(readOnly = true)
    public List<Floor> list(Actor actor, UUID venueId) {
        guard.requireVenue(actor, venueId);
        return floors(actor, venueId);
    }

    private List<Floor> floors(Actor actor, UUID venueId) {
        return jdbc.sql(SELECT + " ORDER BY level").param("v", venueId).param("o", actor.organizationId()).query(FloorService::map).list();
    }

    @Transactional(readOnly = true)
    public Floor get(Actor actor, UUID venueId, UUID floorId) {
        guard.requireVenue(actor, venueId);
        return find(actor, venueId, floorId);
    }

    private Floor find(Actor actor, UUID venueId, UUID floorId) {
        return jdbc.sql(SELECT + " AND id = :f").param("v", venueId).param("o", actor.organizationId()).param("f", floorId)
            .query(FloorService::map).optional().orElseThrow(() -> new NotFoundException("floor not found"));
    }

    @Transactional
    public Floor create(Actor actor, UUID venueId, int level, String name) {
        guard.requireVenue(actor, venueId);
        UUID id;
        try {
            id = jdbc.sql("INSERT INTO floor (organization_id, venue_id, level, name) VALUES (:o, :v, :l, :n) RETURNING id")
                .param("o", actor.organizationId()).param("v", venueId).param("l", level).param("n", requireName(name))
                .query(UUID.class).single();
        } catch (DuplicateKeyException e) {
            throw levelTaken(level);
        }
        audit.success(actor, venueId, "floor.create", "floor", id, Map.of("level", level));
        return find(actor, venueId, id);
    }

    /** Renames the floor or moves it to another level. A new level must keep the recorded elevations in order. */
    @Transactional
    public Floor update(Actor actor, UUID venueId, UUID floorId, int level, String name) {
        guard.requireVenue(actor, venueId);
        Floor current = find(actor, venueId, floorId);
        List<FloorFacts> facts = new ArrayList<>();
        for (Floor f : floors(actor, venueId)) {
            facts.add(new FloorFacts(f.id().toString(), f.id().equals(floorId) ? level : f.level(),
                f.elevation() == null ? null : f.elevation().elevationM()));
        }
        refuseIfInconsistent(FloorGeometry.elevationOrder(facts), "FLOOR_ELEVATION_INCONSISTENT");
        try {
            jdbc.sql("UPDATE floor SET level = :l, name = :n WHERE id = :f").param("l", level).param("n", requireName(name))
                .param("f", floorId).update();
        } catch (DuplicateKeyException e) {
            throw levelTaken(level);
        }
        audit.success(actor, venueId, "floor.update", "floor", floorId, Map.of("level", level, "previousLevel", current.level()));
        return find(actor, venueId, floorId);
    }

    /**
     * Records the floor's elevation above the venue's elevation reference: a value somebody measured or took from a
     * survey, never derived here. Refused when it contradicts what is already recorded: the order of levels, or a stair or
     * ramp registered between this floor and another too short (or a ramp too gentle) for the rise.
     */
    @Transactional
    public Floor setElevation(Actor actor, UUID venueId, UUID floorId, Double elevationM, String source, String reference) {
        guard.requireVenue(actor, venueId);
        find(actor, venueId, floorId);
        String src = source == null ? "" : source.strip().toUpperCase(Locale.ROOT);
        if (!ELEVATION_SOURCES.contains(src)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ELEVATION",
                "source must be one of " + ELEVATION_SOURCES + ": an elevation is recorded from a survey or a measurement on site");
        }
        if (elevationM == null || !Double.isFinite(elevationM) || Math.abs(elevationM) >= 1000) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ELEVATION", "elevationM must be a finite height in metres");
        }
        List<FloorFacts> facts = new ArrayList<>();
        Map<UUID, Double> elevations = new HashMap<>();
        for (Floor f : floors(actor, venueId)) {
            Double e = f.id().equals(floorId) ? elevationM : f.elevation() == null ? null : f.elevation().elevationM();
            facts.add(new FloorFacts(f.id().toString(), f.level(), e));
            elevations.put(f.id(), e);
        }
        List<Finding> findings = new ArrayList<>(FloorGeometry.elevationOrder(facts));
        for (ConnectionRow c : connections(actor, venueId)) {
            if (c.fromFloor().equals(floorId) || c.toFloor().equals(floorId)) {
                findings.addAll(FloorGeometry.connector(c.type(), c.lengthM(), c.maxSlopeDeg(), elevations.get(c.fromFloor()),
                    elevations.get(c.toFloor())));
            }
        }
        refuseIfInconsistent(findings, "FLOOR_ELEVATION_INCONSISTENT");
        jdbc.sql("UPDATE floor SET elevation_m = :e, elevation_source = :s, elevation_reference = :r, elevation_recorded_by = :by, "
                + "elevation_recorded_at = now() WHERE id = :f")
            .param("e", elevationM).param("s", src).param("r", reference == null || reference.isBlank() ? null : reference.strip())
            .param("by", actor.subject()).param("f", floorId).update();
        audit.success(actor, venueId, "floor.elevation", "floor", floorId, Map.of("elevationM", elevationM, "source", src));
        return find(actor, venueId, floorId);
    }

    /** Withdraws a recorded elevation (it was wrong, or its source is no longer trusted): the floor's elevation is unknown. */
    @Transactional
    public Floor clearElevation(Actor actor, UUID venueId, UUID floorId) {
        guard.requireVenue(actor, venueId);
        find(actor, venueId, floorId);
        jdbc.sql("UPDATE floor SET elevation_m = NULL, elevation_source = NULL, elevation_reference = NULL, elevation_recorded_by = NULL, "
                + "elevation_recorded_at = NULL WHERE id = :f").param("f", floorId).update();
        audit.success(actor, venueId, "floor.elevation_cleared", "floor", floorId, Map.of());
        return find(actor, venueId, floorId);
    }

    /**
     * Retires a floor that holds nothing anyone relies on. A floor that publishes a scan version, is a landing of a floor
     * connection, or has captures still in progress is refused (409 FLOOR_IN_USE): erase or move those first. The floor
     * row stays (soft delete) so history that names it still resolves; its level becomes free.
     */
    @Transactional
    public void delete(Actor actor, UUID venueId, UUID floorId) {
        guard.requireVenue(actor, venueId);
        Floor f = find(actor, venueId, floorId);
        List<String> uses = new ArrayList<>();
        if (f.currentScanVersionId() != null) {
            uses.add("it publishes scan version " + f.currentScanVersionId());
        }
        int connections = jdbc.sql("SELECT count(*) FROM floor_connection WHERE deleted_at IS NULL AND (from_floor_id = :f OR to_floor_id = :f)")
            .param("f", floorId).query(Integer.class).single();
        if (connections > 0) {
            uses.add(connections + " floor connection(s) land on it");
        }
        int open = jdbc.sql("SELECT count(*) FROM capture_session WHERE floor_id = :f AND status NOT IN ('COMPLETED', 'FAILED')")
            .param("f", floorId).query(Integer.class).single();
        if (open > 0) {
            uses.add(open + " capture(s) of it are still in progress");
        }
        if (!uses.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "FLOOR_IN_USE", "floor " + floorId + " cannot be deleted: " + String.join("; ", uses));
        }
        jdbc.sql("UPDATE floor SET deleted_at = now() WHERE id = :f").param("f", floorId).update();
        audit.success(actor, venueId, "floor.delete", "floor", floorId, Map.of("level", f.level()));
    }

    // ---- consistency ----------------------------------------------------------------------------------------------

    private record ConnectionRow(UUID id, String type, UUID fromFloor, UUID fromPoi, UUID toFloor, UUID toPoi, Double lengthM,
                                 Double maxSlopeDeg) {}

    private List<ConnectionRow> connections(Actor actor, UUID venueId) {
        return jdbc.sql("""
                SELECT id, connector_type, from_floor_id, from_poi_id, to_floor_id, to_poi_id, length_m, max_slope_deg
                  FROM floor_connection WHERE venue_id = :v AND organization_id = :o AND deleted_at IS NULL ORDER BY created_at, id""")
            .param("v", venueId).param("o", actor.organizationId())
            .query((rs, i) -> new ConnectionRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class),
                rs.getObject(4, UUID.class), rs.getObject(5, UUID.class), rs.getObject(6, UUID.class), nullable(rs.getObject(7)),
                nullable(rs.getObject(8))))
            .list();
    }

    private static Double nullable(Object o) {
        return o == null ? null : ((Number) o).doubleValue();
    }

    /** A landing POI's position in its floor's current frame, or null when it is not placed in it. */
    private double[] landing(UUID venueId, UUID poiId, FrameView frame) {
        return jdbc.sql("SELECT v.x, v.y, v.z, v.coordinate_frame_id FROM poi p JOIN poi_version v ON v.poi_id = p.id WHERE p.id = :p "
                + "AND p.venue_id = :v AND p.deleted_at IS NULL AND v.version_number = (SELECT max(version_number) FROM poi_version WHERE poi_id = p.id)")
            .param("p", poiId).param("v", venueId)
            .query((rs, i) -> frame.id().equals(rs.getObject(4, UUID.class)) ? new double[]{rs.getDouble(1), rs.getDouble(2), rs.getDouble(3)} : null)
            .optional().orElse(null);
    }

    /**
     * Every recorded relation between the venue's floors, checked: elevation order, each walked connector against the
     * recorded elevations, and -- where both floors are calibrated to surveyed control points (horizontal datum
     * VENUE_CONTROL_POINTS) -- each connector's two landings against each other and the elevations. Floors calibrated
     * FLOOR_LOCAL have unrelated frames and are NOT_COMPARABLE: nothing about their relative position is known or claimed.
     * The VENUE_CONTROL_POINTS check assumes both floors were calibrated against the same survey (Chaya cannot tell two
     * surveys apart); a contradiction it finds may mean they were not.
     */
    @Transactional(readOnly = true)
    public ConsistencyReport consistency(Actor actor, UUID venueId) {
        guard.requireVenue(actor, venueId);
        List<Floor> floors = floors(actor, venueId);
        Map<UUID, Floor> byId = new LinkedHashMap<>();
        floors.forEach(f -> byId.put(f.id(), f));
        Map<UUID, Optional<FrameView>> frameOf = new HashMap<>();
        List<FloorStatus> statuses = new ArrayList<>();
        for (Floor f : floors) {
            Optional<FrameView> frame = frames.currentForFloor(venueId, f.id());
            frameOf.put(f.id(), frame);
            statuses.add(new FloorStatus(f.id(), f.level(), f.name(), f.elevation(), frame.map(FrameView::horizontalDatum).orElse(null),
                frame.map(FrameView::canonical).orElse(false)));
        }
        List<VenueFinding> findings = new ArrayList<>();
        List<FloorFacts> facts = floors.stream().map(f -> new FloorFacts(f.id().toString(), f.level(),
            f.elevation() == null ? null : f.elevation().elevationM())).toList();
        FloorGeometry.elevationOrder(facts).forEach(x -> findings.add(new VenueFinding(null, x.code(), x.severity().name(), x.detail())));

        List<FloorPair> pairs = new ArrayList<>();
        for (ConnectionRow c : connections(actor, venueId)) {
            Floor from = byId.get(c.fromFloor());
            Floor to = byId.get(c.toFloor());
            if (from == null || to == null) {
                findings.add(new VenueFinding(c.id(), "CONNECTION_FLOOR_DELETED", Severity.ERROR.name(), "a landing floor was deleted"));
                continue;
            }
            Double ef = from.elevation() == null ? null : from.elevation().elevationM();
            Double et = to.elevation() == null ? null : to.elevation().elevationM();
            List<Finding> cf = new ArrayList<>(FloorGeometry.connector(c.type(), c.lengthM(), c.maxSlopeDeg(), ef, et));
            Optional<FrameView> ff = frameOf.get(c.fromFloor());
            Optional<FrameView> ft = frameOf.get(c.toFloor());
            String alignment;
            String detail;
            if (shared(ff) && shared(ft)) {
                double[] a = landing(venueId, c.fromPoi(), ff.get());
                double[] b = landing(venueId, c.toPoi(), ft.get());
                if (a == null || b == null) {
                    alignment = "NOT_COMPARABLE";
                    detail = "a landing POI is not placed in its floor's current frame";
                } else {
                    List<Finding> landings = FloorGeometry.landingsInSharedDatum(c.type(), c.lengthM(), a, b, ef, et);
                    cf.addAll(landings);
                    alignment = FloorGeometry.hasErrors(landings) ? "INCONSISTENT" : "CONSISTENT";
                    detail = landings.get(0).detail();
                }
            } else {
                alignment = "NOT_COMPARABLE";
                detail = "the floors are not both calibrated to surveyed venue control points, so their frames are unrelated; "
                    + "they are joined only by this registered connection";
            }
            cf.forEach(x -> findings.add(new VenueFinding(c.id(), x.code(), x.severity().name(), x.detail())));
            pairs.add(new FloorPair(c.fromFloor(), c.toFloor(), c.id(), alignment, detail));
        }
        boolean consistent = findings.stream().noneMatch(f -> f.severity().equals(Severity.ERROR.name()));
        return new ConsistencyReport(statuses, pairs, findings, consistent);
    }

    private static boolean shared(Optional<FrameView> frame) {
        return frame.isPresent() && frame.get().canonical() && "VENUE_CONTROL_POINTS".equals(frame.get().horizontalDatum());
    }

    // ---- helpers ----------------------------------------------------------------------------------------------------

    private static String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_FLOOR", "name must not be blank");
        }
        return name.strip();
    }

    private static ApiException levelTaken(int level) {
        return new ApiException(HttpStatus.CONFLICT, "FLOOR_LEVEL_TAKEN", "another floor of this venue is level " + level);
    }

    private static void refuseIfInconsistent(List<Finding> findings, String code) {
        if (FloorGeometry.hasErrors(findings)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, code, FloorGeometry.describe(findings));
        }
    }
}
