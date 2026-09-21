package dev.chaya.api.poi;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.chaya.api.audit.AuditService;
import dev.chaya.api.security.Actor;
import dev.chaya.api.security.TenantGuard;
import dev.chaya.api.web.NotFoundException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** POIs are versioned: every modification appends an immutable poi_version. */
@Service
public class PoiService {

    public record PoiData(UUID floorId, UUID spaceId, String label, String category, String description,
                          List<String> tags, double x, double y, double z) {}

    public record Poi(UUID id, UUID floorId, UUID spaceId, int version, String label, String category,
                      String description, List<String> tags, double x, double y, double z) {}

    private static final String LATEST = """
            SELECT p.id, p.floor_id, p.space_id, v.version_number, v.label, v.category, v.description,
                   v.tags, v.x, v.y, v.z
              FROM poi p
              JOIN poi_version v ON v.poi_id = p.id
             WHERE p.venue_id = :v AND p.organization_id = :o AND p.deleted_at IS NULL
               AND v.version_number = (SELECT max(version_number) FROM poi_version WHERE poi_id = p.id)
            """;

    private final JdbcClient jdbc;
    private final TenantGuard guard;
    private final AuditService audit;
    private final ObjectMapper mapper;

    public PoiService(JdbcClient jdbc, TenantGuard guard, AuditService audit, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.audit = audit;
        this.mapper = mapper;
    }

    private static Poi map(ResultSet rs, int i) throws SQLException {
        String[] tags = (String[]) rs.getArray("tags").getArray();
        return new Poi(rs.getObject("id", UUID.class), rs.getObject("floor_id", UUID.class),
            rs.getObject("space_id", UUID.class), rs.getInt("version_number"), rs.getString("label"),
            rs.getString("category"), rs.getString("description"), List.of(tags),
            rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("z"));
    }

    @Transactional(readOnly = true)
    public List<Poi> list(Actor actor, UUID venueId) {
        guard.requireVenue(actor, venueId);
        return jdbc.sql(LATEST + " ORDER BY v.label").param("v", venueId).param("o", actor.organizationId())
            .query(PoiService::map).list();
    }

    @Transactional(readOnly = true)
    public Poi get(Actor actor, UUID venueId, UUID poiId) {
        guard.requireVenue(actor, venueId);
        return jdbc.sql(LATEST + " AND p.id = :p").param("v", venueId).param("o", actor.organizationId())
            .param("p", poiId).query(PoiService::map).optional().orElseThrow(() -> new NotFoundException("poi not found"));
    }

    @Transactional
    public Poi create(Actor actor, UUID venueId, PoiData d) {
        guard.requireVenue(actor, venueId);
        requirePlacement(venueId, d);
        UUID poi = jdbc.sql("INSERT INTO poi (organization_id, venue_id, floor_id, space_id) VALUES (:o, :v, :f, :s) RETURNING id")
            .param("o", actor.organizationId()).param("v", venueId).param("f", d.floorId()).param("s", d.spaceId())
            .query(UUID.class).single();
        insertVersion(actor, venueId, poi, 1, d);
        audit.success(actor, venueId, "poi.create", "poi", poi, Map.of("version", 1));
        return get(actor, venueId, poi);
    }

    /** Appends a new version; the previous one stays as history. */
    @Transactional
    public Poi update(Actor actor, UUID venueId, UUID poiId, PoiData d) {
        guard.requireVenue(actor, venueId);
        requirePlacement(venueId, d);
        Integer current = jdbc.sql("SELECT max(v.version_number) FROM poi p JOIN poi_version v ON v.poi_id = p.id "
                + "WHERE p.id = :p AND p.venue_id = :v AND p.organization_id = :o AND p.deleted_at IS NULL")
            .param("p", poiId).param("v", venueId).param("o", actor.organizationId()).query(Integer.class).single();
        if (current == null) {
            throw new NotFoundException("poi not found");
        }
        jdbc.sql("UPDATE poi SET floor_id = :f, space_id = :s WHERE id = :p AND venue_id = :v")
            .param("f", d.floorId()).param("s", d.spaceId()).param("p", poiId).param("v", venueId).update();
        insertVersion(actor, venueId, poiId, current + 1, d);
        audit.success(actor, venueId, "poi.update", "poi", poiId, Map.of("version", current + 1));
        return get(actor, venueId, poiId);
    }

    @Transactional
    public void delete(Actor actor, UUID venueId, UUID poiId) {
        guard.requireVenue(actor, venueId);
        int rows = jdbc.sql("UPDATE poi SET deleted_at = now() WHERE id = :p AND venue_id = :v AND organization_id = :o AND deleted_at IS NULL")
            .param("p", poiId).param("v", venueId).param("o", actor.organizationId()).update();
        if (rows == 0) {
            throw new NotFoundException("poi not found");
        }
        audit.success(actor, venueId, "poi.delete", "poi", poiId, Map.of());
    }

    private void requirePlacement(UUID venueId, PoiData d) {
        if (d.floorId() != null && jdbc.sql("SELECT count(*) FROM floor WHERE id = :f AND venue_id = :v AND deleted_at IS NULL")
                .param("f", d.floorId()).param("v", venueId).query(Integer.class).single() == 0) {
            throw new NotFoundException("floor not found");
        }
        if (d.spaceId() != null && (d.floorId() == null || jdbc.sql(
                "SELECT count(*) FROM space WHERE id = :s AND floor_id = :f AND venue_id = :v AND deleted_at IS NULL")
                .param("s", d.spaceId()).param("f", d.floorId()).param("v", venueId).query(Integer.class).single() == 0)) {
            throw new NotFoundException("space not found on that floor");
        }
    }

    private void insertVersion(Actor actor, UUID venueId, UUID poi, int number, PoiData d) {
        jdbc.sql("INSERT INTO poi_version (organization_id, venue_id, poi_id, version_number, label, category, description, "
                + "tags, x, y, z, created_by) VALUES (:o, :v, :p, :n, :label, :cat, :desc, "
                + "ARRAY(SELECT jsonb_array_elements_text(CAST(:tags AS jsonb))), :x, :y, :z, :by)")
            .param("o", actor.organizationId()).param("v", venueId).param("p", poi).param("n", number)
            .param("label", d.label()).param("cat", d.category()).param("desc", d.description())
            .param("tags", toJson(d.tags() == null ? List.of() : d.tags()))
            .param("x", d.x()).param("y", d.y()).param("z", d.z()).param("by", actor.subject())
            .update();
    }

    private String toJson(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("not serializable", e);
        }
    }
}
