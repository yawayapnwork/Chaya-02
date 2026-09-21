package dev.chaya.api.security;

import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

/**
 * Turns an already signature-verified Keycloak JWT into an {@link Actor}.
 * Claims: realm_access.roles (role names), org_id (uuid), venue_id (uuid or list of uuids).
 * A token that verifies but does not carry the claims this API needs is rejected as invalid.
 */
public class JwtActorConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Set<Role> roles = EnumSet.noneOf(Role.class);
        for (String name : realmRoles(jwt)) {
            Role.fromKeycloak(name).ifPresent(roles::add);
        }

        if (roles.contains(Role.SERVICE)) {
            if (roles.size() > 1) {
                throw invalid("a service token must not carry user roles");
            }
            return new ActorAuthentication(new Actor(Actor.Kind.SERVICE, jwt.getSubject(), null, Set.of(), roles));
        }

        UUID orgId = uuidClaim(jwt.getClaim("org_id"), "org_id");
        if (orgId == null) {
            throw invalid("missing org_id claim");
        }
        Set<UUID> venueIds = new HashSet<>();
        Object venueClaim = jwt.getClaim("venue_id");
        if (venueClaim instanceof Collection<?> values) {
            for (Object v : values) {
                venueIds.add(uuidClaim(v, "venue_id"));
            }
        } else if (venueClaim != null) {
            venueIds.add(uuidClaim(venueClaim, "venue_id"));
        }
        return new ActorAuthentication(new Actor(Actor.Kind.USER, jwt.getSubject(), orgId, Set.copyOf(venueIds), roles));
    }

    private static List<String> realmRoles(Jwt jwt) {
        Object access = jwt.getClaim("realm_access");
        if (access instanceof Map<?, ?> m && m.get("roles") instanceof Collection<?> roles) {
            return roles.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    private static UUID uuidClaim(Object value, String name) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(String.valueOf(value));
        } catch (IllegalArgumentException e) {
            throw invalid("claim " + name + " is not a UUID");
        }
    }

    private static InvalidBearerTokenException invalid(String message) {
        return new InvalidBearerTokenException(message);
    }
}
