package dev.chaya.api.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates requests carrying an opaque public-viewer token in X-Chaya-Viewer-Token.
 * An invalid, expired or revoked token is a hard 401; it never falls through to anonymous access.
 */
public class PublicViewerTokenFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Chaya-Viewer-Token";

    private final PublicViewerAuthenticator authenticator;

    public PublicViewerTokenFilter(PublicViewerAuthenticator authenticator) {
        this.authenticator = authenticator;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        String token = request.getHeader(HEADER);
        if (token == null) {
            chain.doFilter(request, response);
            return;
        }
        if (request.getHeader("Authorization") != null) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "send either a bearer token or a viewer token, not both");
            return;
        }
        Optional<Actor> actor = authenticator.authenticate(token);
        if (actor.isEmpty()) {
            SecurityContextHolder.clearContext();
            response.setHeader("WWW-Authenticate", "ChayaViewerToken");
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "invalid or expired viewer token");
            return;
        }
        SecurityContextHolder.getContext().setAuthentication(new ActorAuthentication(actor.get()));
        chain.doFilter(request, response);
    }
}
