package dev.chaya.api.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Applies RateLimiter budgets after authentication (so the caller is known) and answers 429 with Retry-After when a
 * budget is spent. Not a Spring bean on purpose: as a bean Boot would also register it as a servlet filter that runs
 * before authentication. SecurityConfig adds it to the security chain.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimiter limiter;

    public RateLimitFilter(RateLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        RateLimitProperties p = limiter.properties();
        if (!p.enabled() || "OPTIONS".equals(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }
        long now = System.currentTimeMillis();
        String path = request.getRequestURI();
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Actor actor = auth instanceof ActorAuthentication a ? a.getPrincipal() : null;

        RateLimiter.Decision d;
        if (actor != null && actor.kind() == Actor.Kind.SERVICE) {
            chain.doFilter(request, response);
            return;
        } else if (actor == null) {
            String client = request.getRemoteAddr();
            d = "POST".equals(request.getMethod()) && path.equals("/api/v1/public/viewer-token")
                ? limiter.tryAcquire("link:" + client, p.publicLinkExchangePerMinute(), now)
                : limiter.tryAcquire("anon:" + client, p.anonymousPerMinute(), now);
        } else {
            String who = actor.kind() + ":" + actor.subject();
            d = limiter.tryAcquire("actor:" + who, p.authenticatedPerMinute(), now);
            if (d.allowed() && path.startsWith("/api/v1/venues/") && path.endsWith("/search")) {
                d = limiter.tryAcquire("search:" + who, p.searchPerMinute(), now);
            }
        }
        if (d.allowed()) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(429);
        response.setHeader("Retry-After", Long.toString(d.retryAfterSeconds()));
        response.setContentType("application/problem+json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Too Many Requests\",\"status\":429,"
            + "\"detail\":\"Request budget exhausted; retry after " + d.retryAfterSeconds() + " s.\",\"code\":\"RATE_LIMITED\"}");
    }
}
