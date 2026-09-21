package dev.chaya.api.security;

import dev.chaya.api.audit.AuditService;
import dev.chaya.api.web.NotFoundException;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The single place that decides whether an actor may touch a venue. Every service method that
 * takes a venue id goes through here, and every query it then runs is additionally filtered by
 * venue and organization. Refusals are audited and reported as 404 so venues cannot be enumerated.
 */
@Component
public class TenantGuard {

    private final JdbcClient jdbc;
    private final AuditService audit;

    public TenantGuard(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public void requireVenue(Actor actor, UUID venueId) {
        boolean allowed = actor.mayAccessVenue(venueId)
            && jdbc.sql("SELECT count(*) FROM venue WHERE id = :v AND organization_id = :o AND deleted_at IS NULL")
                .param("v", venueId).param("o", actor.organizationId())
                .query(Integer.class).single() == 1;
        if (!allowed) {
            audit.denied(actor, "venue.access", "venue", venueId);
            throw new NotFoundException("venue not found");
        }
    }
}
