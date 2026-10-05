package dev.chaya.api.security;

import dev.chaya.api.audit.AuditLogWriter.ActorType;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The authenticated caller, as established by the backend from a validated JWT or a public viewer
 * token. Services receive this instead of trusting anything from the request body.
 *
 * <p>Roles are venue-aware (review S-8; docs/security.md, "Role model"):
 * <ul>
 *   <li>{@code roles}: the token's realm roles. {@code ADMIN} is organization-wide. Any other realm role applies at every
 *       venue in {@code venueIds} (the {@code venue_id} claim) -- the original model, kept for existing users.</li>
 *   <li>{@code venueRoles}: per-venue grants (the {@code venue_roles} claim, "venueId:role"). At a venue listed here the
 *       actor has exactly these roles, whatever its realm roles say.</li>
 * </ul>
 * {@link #atVenue} narrows an actor to one venue's roles; VenueScopeFilter applies it to every request under
 * /api/v1/venues/{venueId}/, before method security and the services see the actor.
 *
 * @param organizationId null only for SERVICE actors, which are not tenant-bound
 * @param venueIds       venues the realm roles apply to; ignored for ADMIN, who is org-wide
 * @param venueRoles     venue -> the roles granted at that venue (never ADMIN or SERVICE)
 */
public record Actor(Kind kind, String subject, UUID organizationId, Set<UUID> venueIds, Set<Role> roles,
                    Map<UUID, Set<Role>> venueRoles) {

    public enum Kind { USER, SERVICE, PUBLIC_VIEWER }

    public Actor {
        venueIds = Set.copyOf(venueIds);
        roles = roles.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(roles));
        venueRoles = Map.copyOf(venueRoles);
        for (Set<Role> granted : venueRoles.values()) {
            if (granted.isEmpty() || granted.contains(Role.ADMIN) || granted.contains(Role.SERVICE) || granted.contains(Role.PUBLIC_VIEWER)) {
                throw new IllegalArgumentException("a venue grant holds venue-manager, operator or viewer, never " + granted);
            }
        }
    }

    /** Without per-venue grants: realm roles at every venue in venueIds. */
    public Actor(Kind kind, String subject, UUID organizationId, Set<UUID> venueIds, Set<Role> roles) {
        this(kind, subject, organizationId, venueIds, roles, Map.of());
    }

    public boolean isOrganizationAdmin() {
        return organizationId != null && roles.contains(Role.ADMIN);
    }

    public boolean mayAccessVenue(UUID venueId) {
        if (organizationId == null) {
            return false;
        }
        return roles.contains(Role.ADMIN) || venueIds.contains(venueId) || venueRoles.containsKey(venueId);
    }

    /** Whether the actor holds a non-admin membership of this venue (a grant, or venue_id with realm roles). */
    public boolean isMemberOf(UUID venueId) {
        return organizationId != null && (venueRoles.containsKey(venueId) || venueIds.contains(venueId));
    }

    /** The roles this actor holds at one venue: everything for an organization admin, the venue's own grant if there is
     * one, otherwise the realm roles if the venue is in venue_id, otherwise none. */
    public Set<Role> rolesAt(UUID venueId) {
        if (isOrganizationAdmin()) {
            return roles;
        }
        Set<Role> granted = venueRoles.get(venueId);
        if (granted != null) {
            return granted;
        }
        return venueIds.contains(venueId) ? roles : Set.of();
    }

    /** Every role the actor holds anywhere: what a request that names no venue in its path is first checked against. */
    public Set<Role> allRoles() {
        Set<Role> all = new HashSet<>(roles);
        venueRoles.values().forEach(all::addAll);
        return Set.copyOf(all);
    }

    /**
     * This actor as seen by a request about one venue: only that venue's roles, and only that venue. An organization
     * admin, a non-user, and an actor that is not a member of the venue are returned unchanged (a non-member is then
     * refused, as 404, by TenantGuard).
     */
    public Actor atVenue(UUID venueId) {
        if (kind != Kind.USER || isOrganizationAdmin() || !isMemberOf(venueId)) {
            return this;
        }
        return new Actor(kind, subject, organizationId, Set.of(venueId), rolesAt(venueId), Map.of());
    }

    public ActorType auditType() {
        return switch (kind) {
            case USER -> ActorType.USER;
            case SERVICE -> ActorType.SERVICE;
            case PUBLIC_VIEWER -> ActorType.PUBLIC_VIEWER;
        };
    }
}
