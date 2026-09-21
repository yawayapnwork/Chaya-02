package dev.chaya.api.security;

import dev.chaya.api.audit.AuditLogWriter.ActorType;
import java.util.Set;
import java.util.UUID;

/**
 * The authenticated caller, as established by the backend from a validated JWT or a public viewer
 * token. Services receive this instead of trusting anything from the request body.
 *
 * @param organizationId null only for SERVICE actors, which are not tenant-bound
 * @param venueIds       venues the actor is restricted to; ignored for ADMIN, who is org-wide
 */
public record Actor(Kind kind, String subject, UUID organizationId, Set<UUID> venueIds, Set<Role> roles) {

    public enum Kind { USER, SERVICE, PUBLIC_VIEWER }

    public boolean mayAccessVenue(UUID venueId) {
        if (organizationId == null) {
            return false;
        }
        return roles.contains(Role.ADMIN) || venueIds.contains(venueId);
    }

    public ActorType auditType() {
        return switch (kind) {
            case USER -> ActorType.USER;
            case SERVICE -> ActorType.SERVICE;
            case PUBLIC_VIEWER -> ActorType.PUBLIC_VIEWER;
        };
    }
}
