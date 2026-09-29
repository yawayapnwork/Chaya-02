package dev.chaya.api.navigation;

import dev.chaya.api.audit.AuditService;
import dev.chaya.api.security.Actor;
import dev.chaya.api.security.TenantGuard;
import dev.chaya.api.web.ApiException;
import dev.chaya.api.web.NotFoundException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registered floor connections (V22 floor_connection): the only way RouteService crosses floors.
 *
 * <p>Each floor is reconstructed and calibrated on its own, so no coordinate on one floor is ever compared with a
 * coordinate on another. A connection instead names a landing POI on each floor -- each placed in its own floor's frame
 * -- and what venue staff measured about the connector itself:
 * <ul>
 *   <li>STAIRS and RAMP are walked, so their walked length ({@code lengthM}, metres) is required: it is part of the
 *       route's distance. An ELEVATOR is ridden, never walked, and has no length.</li>
 *   <li>{@code minClearanceM} (the narrowest clear width: a ramp's width, an elevator's door) and, for a ramp,
 *       {@code maxSlopeDeg} are optional here, but STEP_FREE routing uses a connection only when they are registered
 *       and pass. Stairs are never step-free.</li>
 * </ul>
 * A multi-stop elevator is registered as one connection per pair of floors it actually serves.
 */
@Service
public class FloorConnectionService {

    static final Set<String> TYPES = Set.of("STAIRS", "RAMP", "ELEVATOR");
    static final Set<String> STATUSES = Set.of("IN_SERVICE", "OUT_OF_SERVICE");

    public record ConnectionData(String connectorType, UUID fromFloorId, UUID fromPoiId, UUID toFloorId, UUID toPoiId,
                                 Boolean bidirectional, Double lengthM, Double maxSlopeDeg, Double minClearanceM) {}

    public record Connection(UUID id, String connectorType, UUID fromFloorId, UUID fromPoiId, UUID toFloorId, UUID toPoiId,
                             boolean bidirectional, Double lengthM, Double maxSlopeDeg, Double minClearanceM, String status) {}

    private static final String SELECT = """
            SELECT id, connector_type, from_floor_id, from_poi_id, to_floor_id, to_poi_id, bidirectional, length_m,
                   max_slope_deg, min_clearance_m, status
              FROM floor_connection
             WHERE venue_id = :v AND organization_id = :o AND deleted_at IS NULL
            """;

    private final JdbcClient jdbc;
    private final TenantGuard guard;
    private final AuditService audit;

    public FloorConnectionService(JdbcClient jdbc, TenantGuard guard, AuditService audit) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.audit = audit;
    }

    static Connection map(ResultSet rs, int i) throws SQLException {
        return new Connection(rs.getObject("id", UUID.class), rs.getString("connector_type"),
            rs.getObject("from_floor_id", UUID.class), rs.getObject("from_poi_id", UUID.class),
            rs.getObject("to_floor_id", UUID.class), rs.getObject("to_poi_id", UUID.class), rs.getBoolean("bidirectional"),
            nullableDouble(rs, "length_m"), nullableDouble(rs, "max_slope_deg"), nullableDouble(rs, "min_clearance_m"),
            rs.getString("status"));
    }

    private static Double nullableDouble(ResultSet rs, String column) throws SQLException {
        Object v = rs.getObject(column);
        return v == null ? null : ((Number) v).doubleValue();
    }

    @Transactional(readOnly = true)
    public List<Connection> list(Actor actor, UUID venueId) {
        guard.requireVenue(actor, venueId);
        return jdbc.sql(SELECT + " ORDER BY created_at, id").param("v", venueId).param("o", actor.organizationId())
            .query(FloorConnectionService::map).list();
    }

    @Transactional(readOnly = true)
    public Connection get(Actor actor, UUID venueId, UUID connectionId) {
        guard.requireVenue(actor, venueId);
        return jdbc.sql(SELECT + " AND id = :id").param("v", venueId).param("o", actor.organizationId()).param("id", connectionId)
            .query(FloorConnectionService::map).optional().orElseThrow(() -> new NotFoundException("floor connection not found"));
    }

    @Transactional
    public Connection create(Actor actor, UUID venueId, ConnectionData d) {
        guard.requireVenue(actor, venueId);
        String type = validate(actor, venueId, d);
        UUID id = jdbc.sql("""
                INSERT INTO floor_connection (organization_id, venue_id, connector_type, from_floor_id, from_poi_id, to_floor_id,
                    to_poi_id, bidirectional, length_m, max_slope_deg, min_clearance_m, created_by)
                VALUES (:o, :v, :t, :ff, :fp, :tf, :tp, :bi, :len, :slope, :clear, :by) RETURNING id""")
            .param("o", actor.organizationId()).param("v", venueId).param("t", type).param("ff", d.fromFloorId())
            .param("fp", d.fromPoiId()).param("tf", d.toFloorId()).param("tp", d.toPoiId())
            .param("bi", d.bidirectional() == null || d.bidirectional()).param("len", d.lengthM())
            .param("slope", d.maxSlopeDeg()).param("clear", d.minClearanceM()).param("by", actor.subject())
            .query(UUID.class).single();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("connectorType", type);
        meta.put("fromFloorId", d.fromFloorId().toString());
        meta.put("toFloorId", d.toFloorId().toString());
        audit.success(actor, venueId, "floor_connection.create", "floor_connection", id, meta);
        return get(actor, venueId, id);
    }

    /** IN_SERVICE or OUT_OF_SERVICE (an elevator under maintenance, a closed stairwell). Routing skips the latter. */
    @Transactional
    public Connection setStatus(Actor actor, UUID venueId, UUID connectionId, String status) {
        guard.requireVenue(actor, venueId);
        String s = status == null ? "" : status.strip().toUpperCase(Locale.ROOT);
        if (!STATUSES.contains(s)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_FLOOR_CONNECTION", "status must be one of " + STATUSES);
        }
        int rows = jdbc.sql("UPDATE floor_connection SET status = :s WHERE id = :id AND venue_id = :v AND organization_id = :o "
                + "AND deleted_at IS NULL")
            .param("s", s).param("id", connectionId).param("v", venueId).param("o", actor.organizationId()).update();
        if (rows == 0) {
            throw new NotFoundException("floor connection not found");
        }
        audit.success(actor, venueId, "floor_connection.status", "floor_connection", connectionId, Map.of("status", s));
        return get(actor, venueId, connectionId);
    }

    @Transactional
    public void delete(Actor actor, UUID venueId, UUID connectionId) {
        guard.requireVenue(actor, venueId);
        int rows = jdbc.sql("UPDATE floor_connection SET deleted_at = now() WHERE id = :id AND venue_id = :v "
                + "AND organization_id = :o AND deleted_at IS NULL")
            .param("id", connectionId).param("v", venueId).param("o", actor.organizationId()).update();
        if (rows == 0) {
            throw new NotFoundException("floor connection not found");
        }
        audit.success(actor, venueId, "floor_connection.delete", "floor_connection", connectionId, Map.of());
    }

    private String validate(Actor actor, UUID venueId, ConnectionData d) {
        String type = d.connectorType() == null ? "" : d.connectorType().strip().toUpperCase(Locale.ROOT);
        if (!TYPES.contains(type)) {
            throw invalid("connectorType must be one of " + TYPES);
        }
        if (d.fromFloorId() == null || d.toFloorId() == null || d.fromPoiId() == null || d.toPoiId() == null) {
            throw invalid("fromFloorId, fromPoiId, toFloorId and toPoiId are required");
        }
        if (d.fromFloorId().equals(d.toFloorId())) {
            throw invalid("a floor connection joins two different floors");
        }
        if (type.equals("ELEVATOR") && d.lengthM() != null) {
            throw invalid("an elevator is ridden, not walked: it has no lengthM");
        }
        if (!type.equals("ELEVATOR") && (d.lengthM() == null || !Double.isFinite(d.lengthM()) || d.lengthM() <= 0)) {
            throw invalid(type.toLowerCase(Locale.ROOT) + " are walked: their measured walked length lengthM (metres, > 0) is required");
        }
        if (d.maxSlopeDeg() != null && (!Double.isFinite(d.maxSlopeDeg()) || d.maxSlopeDeg() < 0 || d.maxSlopeDeg() > 90)) {
            throw invalid("maxSlopeDeg must be between 0 and 90 degrees");
        }
        if (d.minClearanceM() != null && (!Double.isFinite(d.minClearanceM()) || d.minClearanceM() <= 0)) {
            throw invalid("minClearanceM must be a positive width in metres");
        }
        requireLanding(actor, venueId, d.fromFloorId(), d.fromPoiId(), "fromPoiId");
        requireLanding(actor, venueId, d.toFloorId(), d.toPoiId(), "toPoiId");
        return type;
    }

    private void requireLanding(Actor actor, UUID venueId, UUID floorId, UUID poiId, String field) {
        Integer floors = jdbc.sql("SELECT count(*) FROM floor WHERE id = :f AND venue_id = :v AND deleted_at IS NULL")
            .param("f", floorId).param("v", venueId).query(Integer.class).single();
        if (floors == 0) {
            throw new NotFoundException("floor not found");
        }
        Integer pois = jdbc.sql("SELECT count(*) FROM poi WHERE id = :p AND venue_id = :v AND organization_id = :o "
                + "AND floor_id = :f AND deleted_at IS NULL")
            .param("p", poiId).param("v", venueId).param("o", actor.organizationId()).param("f", floorId).query(Integer.class).single();
        if (pois == 0) {
            throw invalid(field + " must be a POI on floor " + floorId);
        }
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_FLOOR_CONNECTION", message);
    }
}
