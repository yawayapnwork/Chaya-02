package dev.chaya.api.security;

import java.util.Optional;

/**
 * Roles the API understands. ADMIN, OPERATOR, VENUE_MANAGER, VIEWER and SERVICE are Keycloak realm
 * roles; PUBLIC_VIEWER is never taken from a token, it is assigned by the backend to anonymous
 * viewer-link sessions.
 */
public enum Role {
    ADMIN("admin"),
    OPERATOR("operator"),
    VENUE_MANAGER("venue-manager"),
    VIEWER("viewer"),
    SERVICE("service"),
    PUBLIC_VIEWER(null);

    private final String keycloakName;

    Role(String keycloakName) {
        this.keycloakName = keycloakName;
    }

    public String authority() {
        return "ROLE_" + name();
    }

    public static Optional<Role> fromKeycloak(String name) {
        for (Role r : values()) {
            if (r.keycloakName != null && r.keycloakName.equals(name)) {
                return Optional.of(r);
            }
        }
        return Optional.empty();
    }
}
