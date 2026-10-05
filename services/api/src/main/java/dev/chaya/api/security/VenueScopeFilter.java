package dev.chaya.api.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Makes roles venue-aware (review S-8). For a request under /api/v1/venues/{venueId}/ by a user who is a member of that
 * venue, the authenticated actor is replaced with {@link Actor#atVenue}: exactly the roles held at that venue, and access
 * to that venue only. Everything downstream -- {@code @PreAuthorize} role rules, TenantGuard, the services' own role
 * checks (OpsSection, the privacy-off rule, ...) -- then sees the venue's roles, so a user who manages venue A and views
 * venue B is a VIEWER for every request about B.
 *
 * <p>Organization admins, non-members (refused as 404 by TenantGuard, unchanged), service accounts and public-link
 * sessions (already bound to one venue) pass through untouched. Runs after authentication, before authorization.
 */
public class VenueScopeFilter extends OncePerRequestFilter {

    private static final Pattern VENUE_PATH = Pattern.compile("^/api/v1/venues/([0-9a-fA-F-]{36})(?:/.*)?$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        SecurityContext context = SecurityContextHolder.getContext();
        if (context.getAuthentication() instanceof ActorAuthentication auth) {
            Optional<UUID> venue = venueOf(pathOf(request));
            Actor actor = auth.getPrincipal();
            if (venue.isPresent()) {
                Actor scoped = actor.atVenue(venue.get());
                if (scoped != actor) {
                    SecurityContext narrowed = SecurityContextHolder.getContextHolderStrategy().createEmptyContext();
                    ActorAuthentication replacement = new ActorAuthentication(scoped);
                    replacement.setDetails(auth.getDetails());
                    narrowed.setAuthentication(replacement);
                    SecurityContextHolder.setContext(narrowed);
                    try {
                        chain.doFilter(request, response);
                    } finally {
                        SecurityContextHolder.setContext(context);
                    }
                    return;
                }
            }
        }
        chain.doFilter(request, response);
    }

    private static String pathOf(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        return context != null && !context.isEmpty() && uri.startsWith(context) ? uri.substring(context.length()) : uri;
    }

    /** The venue a path is about, if it is a venue-scoped path with a well-formed id. */
    static Optional<UUID> venueOf(String path) {
        Matcher m = VENUE_PATH.matcher(path);
        if (!m.matches()) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(m.group(1)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
