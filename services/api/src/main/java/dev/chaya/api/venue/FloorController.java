package dev.chaya.api.venue;

import dev.chaya.api.audit.AuditService;
import dev.chaya.api.security.Actor;
import dev.chaya.api.security.ActorAuthentication;
import dev.chaya.api.security.TenantGuard;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Floors of a venue: what a capture session is recorded against. */
@RestController
@RequestMapping("/api/v1/venues/{venueId}/floors")
public class FloorController {

    public record Floor(UUID id, int level, String name) {}

    public record CreateFloor(int level, @NotBlank String name) {}

    private final JdbcClient jdbc;
    private final TenantGuard guard;
    private final AuditService audit;

    public FloorController(JdbcClient jdbc, TenantGuard guard, AuditService audit) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.audit = audit;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','VENUE_MANAGER','OPERATOR','VIEWER','PUBLIC_VIEWER')")
    @Transactional(readOnly = true)
    public List<Floor> list(@PathVariable UUID venueId) {
        Actor actor = ActorAuthentication.currentActor();
        guard.requireVenue(actor, venueId);
        return jdbc.sql("SELECT id, level, name FROM floor WHERE venue_id = :v AND organization_id = :o AND deleted_at IS NULL ORDER BY level")
            .param("v", venueId).param("o", actor.organizationId())
            .query((rs, i) -> new Floor(rs.getObject("id", UUID.class), rs.getInt("level"), rs.getString("name"))).list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','VENUE_MANAGER')")
    @Transactional
    public Floor create(@PathVariable UUID venueId, @Valid @RequestBody CreateFloor body) {
        Actor actor = ActorAuthentication.currentActor();
        guard.requireVenue(actor, venueId);
        Floor f = jdbc.sql("INSERT INTO floor (organization_id, venue_id, level, name) VALUES (:o, :v, :l, :n) RETURNING id, level, name")
            .param("o", actor.organizationId()).param("v", venueId).param("l", body.level()).param("n", body.name())
            .query((rs, i) -> new Floor(rs.getObject("id", UUID.class), rs.getInt("level"), rs.getString("name"))).single();
        audit.success(actor, venueId, "floor.create", "floor", f.id(), Map.of("level", f.level()));
        return f;
    }
}
