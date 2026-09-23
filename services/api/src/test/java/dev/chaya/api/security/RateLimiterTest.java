package dev.chaya.api.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

class RateLimiterTest {

    private static RateLimiter limiter(int linkPerMinute) {
        return new RateLimiter(new RateLimitProperties(true, linkPerMinute, 5, 5, 2));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void fixedWindowAllowsTheBudgetThenRefusesUntilTheNextMinute() {
        RateLimiter l = limiter(3);
        long t = 60_000L * 1000 + 15_000; // 15 s into a minute
        for (int i = 0; i < 3; i++) {
            assertThat(l.tryAcquire("k", 3, t).allowed()).isTrue();
        }
        RateLimiter.Decision refused = l.tryAcquire("k", 3, t);
        assertThat(refused.allowed()).isFalse();
        assertThat(refused.retryAfterSeconds()).isEqualTo(45);
        assertThat(l.tryAcquire("other", 3, t).allowed()).as("budgets are per key").isTrue();
        assertThat(l.tryAcquire("k", 3, t + 45_000).allowed()).as("new window").isTrue();
    }

    @Test
    void oldWindowsAreDroppedSoKeysCannotGrowMemoryForever() {
        RateLimiter l = limiter(3);
        for (int i = 0; i < 1000; i++) {
            l.tryAcquire("k" + i, 3, 0);
        }
        l.tryAcquire("late", 3, 120_000);
        assertThat(l.trackedKeys()).isEqualTo(1);
    }

    private static MockHttpServletResponse call(RateLimitFilter f, String method, String path) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest(method, path);
        req.setRemoteAddr("203.0.113.7");
        MockHttpServletResponse res = new MockHttpServletResponse();
        f.doFilter(req, res, new MockFilterChain());
        return res;
    }

    @Test
    void publicLinkExchangeIsLimitedPerAddressWith429AndRetryAfter() throws Exception {
        RateLimitFilter f = new RateLimitFilter(limiter(2));
        assertThat(call(f, "POST", "/api/v1/public/viewer-token").getStatus()).isEqualTo(200);
        assertThat(call(f, "POST", "/api/v1/public/viewer-token").getStatus()).isEqualTo(200);
        MockHttpServletResponse refused = call(f, "POST", "/api/v1/public/viewer-token");
        assertThat(refused.getStatus()).isEqualTo(429);
        assertThat(refused.getHeader("Retry-After")).isNotBlank();
        assertThat(refused.getContentAsString()).contains("RATE_LIMITED");
    }

    @Test
    void searchHasItsOwnSmallerBudgetAndWorkersAreExempt() throws Exception {
        RateLimitFilter f = new RateLimitFilter(limiter(10));
        SecurityContextHolder.getContext().setAuthentication(new ActorAuthentication(
            new Actor(Actor.Kind.USER, "user-1", UUID.randomUUID(), Set.of(), Set.of(Role.VIEWER))));
        String search = "/api/v1/venues/" + UUID.randomUUID() + "/search";
        assertThat(call(f, "GET", search).getStatus()).isEqualTo(200);
        assertThat(call(f, "GET", search).getStatus()).isEqualTo(200);
        assertThat(call(f, "GET", search).getStatus()).isEqualTo(429);

        SecurityContextHolder.getContext().setAuthentication(new ActorAuthentication(
            new Actor(Actor.Kind.SERVICE, "worker", null, Set.of(), Set.of(Role.SERVICE))));
        for (int i = 0; i < 20; i++) {
            assertThat(call(f, "POST", "/api/v1/internal/jobs/claim").getStatus()).isEqualTo(200);
        }
    }

    @Test
    void disabledMeansNoLimit() throws Exception {
        RateLimitFilter f = new RateLimitFilter(new RateLimiter(new RateLimitProperties(false, 1, 1, 1, 1)));
        for (int i = 0; i < 5; i++) {
            assertThat(call(f, "POST", "/api/v1/public/viewer-token").getStatus()).isEqualTo(200);
        }
    }

    @Test
    void metricsEndpointNeedsTheConfiguredScrapeCredential() {
        String good = "Basic " + Base64.getEncoder().encodeToString("prometheus:s3cret".getBytes(StandardCharsets.UTF_8));
        String bad = "Basic " + Base64.getEncoder().encodeToString("prometheus:guess".getBytes(StandardCharsets.UTF_8));
        assertThat(SecurityConfig.metricsCredentialsMatch(good, "s3cret")).isTrue();
        assertThat(SecurityConfig.metricsCredentialsMatch(bad, "s3cret")).isFalse();
        assertThat(SecurityConfig.metricsCredentialsMatch(good, "")).as("no password configured: closed").isFalse();
        assertThat(SecurityConfig.metricsCredentialsMatch("Bearer abc", "s3cret")).isFalse();
        assertThat(SecurityConfig.metricsCredentialsMatch("Basic !!!", "s3cret")).isFalse();
        assertThat(SecurityConfig.metricsCredentialsMatch(null, "s3cret")).isFalse();
    }
}
