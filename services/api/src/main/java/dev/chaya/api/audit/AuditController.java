package dev.chaya.api.audit;

import dev.chaya.api.security.ActorAuthentication;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read access to the caller's own organization audit trail. Admin only. */
@RestController
@RequestMapping("/api/v1/audit-log")
public class AuditController {

    public record Entry(UUID id, UUID venueId, String actorId, String actorType, String action,
                        String resourceType, UUID resourceId, String outcome, Instant occurredAt) {}

    private final JdbcClient jdbc;

    public AuditController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public List<Entry> list(@RequestParam(defaultValue = "100") int limit) {
        UUID org = ActorAuthentication.currentActor().organizationId();
        return jdbc.sql("SELECT id, venue_id, actor_id, actor_type, action, resource_type, resource_id, outcome, occurred_at "
                + "FROM audit_log WHERE organization_id = :o ORDER BY occurred_at DESC LIMIT :l")
            .param("o", org).param("l", Math.min(Math.max(limit, 1), 500))
            .query((rs, i) -> new Entry(rs.getObject("id", UUID.class), rs.getObject("venue_id", UUID.class),
                rs.getString("actor_id"), rs.getString("actor_type"), rs.getString("action"),
                rs.getString("resource_type"), rs.getObject("resource_id", UUID.class), rs.getString("outcome"),
                rs.getTimestamp("occurred_at").toInstant()))
            .list();
    }
}
