package dev.chaya.api.organization;

import dev.chaya.api.security.Actor;
import dev.chaya.api.security.ActorAuthentication;
import dev.chaya.api.security.Role;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Organization management endpoints. Every authenticated user belongs to an organization.
 */
@RestController
@RequestMapping("/api/v1/organizations")
public class OrganizationController {

    public record OrganizationView(UUID id, String slug, String name, Instant createdAt) {}

    public record CreateOrganization(
        @NotBlank @Pattern(regexp = "^[a-z0-9][a-z0-9-]{1,62}$") String slug,
        @NotBlank String name
    ) {}

    private final JdbcClient jdbc;

    public OrganizationController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Operation(summary = "Get the current caller's organization")
    @GetMapping("/current")
    @PreAuthorize("hasAnyRole('ADMIN','VENUE_MANAGER','OPERATOR','VIEWER')")
    public OrganizationView current() {
        Actor actor = ActorAuthentication.currentActor();
        return jdbc.sql("SELECT id, slug, name, created_at FROM organization WHERE id = :id AND deleted_at IS NULL")
            .param("id", actor.organizationId())
            .query((rs, i) -> new OrganizationView(
                rs.getObject("id", UUID.class),
                rs.getString("slug"),
                rs.getString("name"),
                rs.getTimestamp("created_at").toInstant()
            ))
            .single();
    }

    @Operation(summary = "List organizations visible to the caller")
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','VENUE_MANAGER','OPERATOR','VIEWER')")
    public List<OrganizationView> list() {
        Actor actor = ActorAuthentication.currentActor();
        boolean isAdmin = actor.roles().contains(Role.ADMIN);
        if (isAdmin) {
            return jdbc.sql("SELECT id, slug, name, created_at FROM organization WHERE deleted_at IS NULL ORDER BY name")
                .query((rs, i) -> new OrganizationView(
                    rs.getObject("id", UUID.class),
                    rs.getString("slug"),
                    rs.getString("name"),
                    rs.getTimestamp("created_at").toInstant()
                ))
                .list();
        } else {
            return jdbc.sql("SELECT id, slug, name, created_at FROM organization WHERE id = :id AND deleted_at IS NULL ORDER BY name")
                .param("id", actor.organizationId())
                .query((rs, i) -> new OrganizationView(
                    rs.getObject("id", UUID.class),
                    rs.getString("slug"),
                    rs.getString("name"),
                    rs.getTimestamp("created_at").toInstant()
                ))
                .list();
        }
    }

    @Operation(summary = "Create an organization (admin)")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN')")
    @Transactional
    public OrganizationView create(@Valid @RequestBody CreateOrganization body) {
        return jdbc.sql("INSERT INTO organization (slug, name) VALUES (:s, :n) RETURNING id, slug, name, created_at")
            .param("s", body.slug())
            .param("n", body.name())
            .query((rs, i) -> new OrganizationView(
                rs.getObject("id", UUID.class),
                rs.getString("slug"),
                rs.getString("name"),
                rs.getTimestamp("created_at").toInstant()
            ))
            .single();
    }
}
