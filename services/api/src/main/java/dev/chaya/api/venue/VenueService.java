package dev.chaya.api.venue;

import dev.chaya.api.audit.AuditService;
import dev.chaya.api.security.Actor;
import dev.chaya.api.security.Role;
import dev.chaya.api.security.TenantGuard;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VenueService {

    public record Venue(UUID id, UUID organizationId, String slug, String name, String timezone) {}

    private static final String COLUMNS = "id, organization_id, slug, name, timezone";

    private final JdbcClient jdbc;
    private final TenantGuard guard;
    private final AuditService audit;

    public VenueService(JdbcClient jdbc, TenantGuard guard, AuditService audit) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.audit = audit;
    }

    private static Venue map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new Venue(rs.getObject("id", UUID.class), rs.getObject("organization_id", UUID.class),
            rs.getString("slug"), rs.getString("name"), rs.getString("timezone"));
    }

    /** Venues the actor may see: all of the organization for admins, otherwise the listed venues. */
    @Transactional(readOnly = true)
    public List<Venue> list(Actor actor) {
        if (actor.roles().contains(Role.ADMIN)) {
            return jdbc.sql("SELECT " + COLUMNS + " FROM venue WHERE organization_id = :o AND deleted_at IS NULL ORDER BY name")
                .param("o", actor.organizationId()).query(VenueService::map).list();
        }
        if (actor.venueIds().isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT " + COLUMNS + " FROM venue WHERE organization_id = :o AND deleted_at IS NULL AND id IN (:ids) ORDER BY name")
            .param("o", actor.organizationId()).param("ids", actor.venueIds()).query(VenueService::map).list();
    }

    @Transactional(readOnly = true)
    public Venue get(Actor actor, UUID venueId) {
        guard.requireVenue(actor, venueId);
        return jdbc.sql("SELECT " + COLUMNS + " FROM venue WHERE id = :v AND organization_id = :o")
            .param("v", venueId).param("o", actor.organizationId()).query(VenueService::map).single();
    }

    @Transactional
    public Venue create(Actor actor, String slug, String name, String timezone) {
        Venue v = jdbc.sql("INSERT INTO venue (organization_id, slug, name, timezone) VALUES (:o, :s, :n, :t) RETURNING " + COLUMNS)
            .param("o", actor.organizationId()).param("s", slug).param("n", name)
            .param("t", timezone == null ? "UTC" : timezone).query(VenueService::map).single();
        audit.success(actor, v.id(), "venue.create", "venue", v.id(), Map.of("slug", slug));
        return v;
    }

    @Transactional
    public Venue update(Actor actor, UUID venueId, String name, String timezone) {
        guard.requireVenue(actor, venueId);
        Venue v = jdbc.sql("UPDATE venue SET name = coalesce(:n, name), timezone = coalesce(:t, timezone) "
                + "WHERE id = :v AND organization_id = :o RETURNING " + COLUMNS)
            .param("n", name).param("t", timezone).param("v", venueId).param("o", actor.organizationId())
            .query(VenueService::map).single();
        audit.success(actor, venueId, "venue.update", "venue", venueId,
            Map.of("nameChanged", name != null, "timezoneChanged", timezone != null));
        return v;
    }
}
