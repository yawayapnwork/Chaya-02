package dev.chaya.api.security;

import java.util.stream.Collectors;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/** Authentication whose principal is the resolved {@link Actor}. */
public class ActorAuthentication extends AbstractAuthenticationToken {

    private final Actor actor;

    public ActorAuthentication(Actor actor) {
        super(actor.roles().stream().map(r -> new SimpleGrantedAuthority(r.authority())).collect(Collectors.toSet()));
        this.actor = actor;
        setAuthenticated(true);
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
