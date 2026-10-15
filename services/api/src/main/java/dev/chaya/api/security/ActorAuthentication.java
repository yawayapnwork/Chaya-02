package dev.chaya.api.security;

import java.time.Instant;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/** Authentication whose principal is the resolved {@link Actor}. */
public class ActorAuthentication extends AbstractAuthenticationToken {

    private final Actor actor;
    /** When the credential this authentication came from expires (a JWT's exp), or null when unknown. A long-lived
     * response (a Server-Sent Events stream) ends then, so it never outlives the sign-in it was authorized with. */
    private final Instant expiresAt;

    public ActorAuthentication(Actor actor) {
        this(actor, null);
    }

    public ActorAuthentication(Actor actor, Instant expiresAt) {
        // allRoles: a venue-scoped request carries an actor already narrowed to that venue (VenueScopeFilter), whose
        // roles are exactly the venue's; otherwise every role the actor holds anywhere, the first, coarse check.
        super(actor.allRoles().stream().map(r -> new SimpleGrantedAuthority(r.authority())).collect(Collectors.toSet()));
        this.actor = actor;
        this.expiresAt = expiresAt;
        setAuthenticated(true);
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    /** When the current request's credential expires, if known. */
    public static Optional<Instant> currentExpiry() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth instanceof ActorAuthentication a ? Optional.ofNullable(a.expiresAt) : Optional.empty();
    }

    @Override
    public Object getCredentials() {
        return "";
    }

    @Override
    public Actor getPrincipal() {
        return actor;
    }

    /** The actor of the current request; only valid behind the security filter chain. */
    public static Actor currentActor() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof ActorAuthentication a) {
            return a.getPrincipal();
        }
        throw new IllegalStateException("no authenticated actor in the security context");
    }
}
