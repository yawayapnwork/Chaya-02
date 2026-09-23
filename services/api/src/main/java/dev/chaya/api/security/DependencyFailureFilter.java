package dev.chaya.api.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * First filter of the security chain. Turns "a dependency is down" into an explicit 503 problem response instead of a
 * generic 500 (or, worse, a 401 that tells the user their valid token is bad):
 *
 * <ul>
 *   <li>{@link AuthenticationServiceException}: the JWT could not be checked at all, typically because Keycloak's JWKS
 *       endpoint is unreachable (Spring Security raises this for a decoder failure that is not a bad token, and
 *       rethrows it rather than answering 401).</li>
 *   <li>{@link DataAccessResourceFailureException} (e.g. no database connection), when it escapes before a controller
 *       advice can handle it -- for example from the public-viewer token filter, which reads the database.</li>
 * </ul>
 * Anything else is rethrown unchanged.
 */
public class DependencyFailureFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(DependencyFailureFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } catch (ServletException | RuntimeException e) {
            String code = classify(e);
            if (code == null || response.isCommitted()) {
                throw e;
            }
            log.error("{} on {} {}: {}", code, request.getMethod(), request.getRequestURI(), rootMessage(e));
            write(response, code);
        }
    }

    /** The problem code for a dependency failure anywhere in the cause chain, or null if it is something else. */
    static String classify(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof AuthenticationServiceException) {
                return "AUTHENTICATION_UNAVAILABLE";
            }
            if (t instanceof DataAccessResourceFailureException) {
                return "DATABASE_UNAVAILABLE";
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return null;
    }

    private static void write(HttpServletResponse response, String code) throws IOException {
        String detail = code.equals("AUTHENTICATION_UNAVAILABLE")
            ? "The identity provider cannot be reached, so the access token could not be verified. Retry later."
            : "The database is unavailable. The request was not completed; retry later.";
        response.resetBuffer();
        response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        response.setHeader("Retry-After", "30");
        response.setContentType("application/problem+json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Service Unavailable\",\"status\":503,\"detail\":\""
            + detail + "\",\"code\":\"" + code + "\"}");
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.getClass().getSimpleName() + ": " + t.getMessage();
    }
}
