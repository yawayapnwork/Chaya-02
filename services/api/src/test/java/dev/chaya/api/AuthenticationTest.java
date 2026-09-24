package dev.chaya.api;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuthenticationTest extends ApiTest {

    private TestJwt admin(UUID org) {
        return TestJwt.user(org, "admin");
    }

    @Test
    void validJwtIsAccepted() throws Exception {
        UUID org = fx.organization();
        UUID venue = fx.venue(org);
        get("/api/v1/venues", admin(org).token())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value(venue.toString()));
    }

    @Test
    void missingTokenIs401() throws Exception {
        get("/api/v1/venues", null).andExpect(status().isUnauthorized());
    }

    @Test
    void garbageTokenIs401() throws Exception {
        get("/api/v1/venues", "not-a-jwt").andExpect(status().isUnauthorized());
    }

    @Test
    void tokenSignedByAnUntrustedKeyIs401() throws Exception {
        UUID org = fx.organization();
        get("/api/v1/venues", admin(org).signedWith(TestJwt.OTHER_KEY).token()).andExpect(status().isUnauthorized());
    }

    @Test
    void expiredTokenIs401() throws Exception {
        UUID org = fx.organization();
        get("/api/v1/venues", admin(org).lifetime(Duration.ofMinutes(-10)).token()).andExpect(status().isUnauthorized());
    }

    @Test
    void wrongIssuerIs401() throws Exception {
        UUID org = fx.organization();
        get("/api/v1/venues", admin(org).issuer("https://evil.example/realms/chaya").token())
            .andExpect(status().isUnauthorized());
    }

    @Test
    void wrongAudienceIs401() throws Exception {
        UUID org = fx.organization();
        get("/api/v1/venues", admin(org).audience("some-other-api").token()).andExpect(status().isUnauthorized());
    }

    @Test
    void unsignedTokenIs401() throws Exception {
        UUID org = fx.organization();
        get("/api/v1/venues", admin(org).unsignedToken()).andExpect(status().isUnauthorized());
    }

    @Test
    void validTokenWithoutOrgClaimIs401() throws Exception {
        get("/api/v1/venues", TestJwt.user(null, "admin").token()).andExpect(status().isUnauthorized());
    }

    @Test
    void healthAndVersionArePublic() throws Exception {
        get("/api/v1/health", null).andExpect(status().isOk());
        get("/api/v1/version", null).andExpect(status().isOk());
    }

    @Test
    void containerProbesArePublicAndReadinessReflectsRealDependencies() throws Exception {
        get("/actuator/health/liveness", null).andExpect(status().isOk());
        // Postgres and MinIO are real containers here (the identity provider probe is the test stub): ready.
        get("/actuator/health/readiness", null).andExpect(status().isOk());
        // Details are never public.
        get("/actuator/health/readiness", null).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
            .jsonPath("$.components").doesNotExist());
    }

    @Test
    void unknownPathsRequireAuthenticationToo() throws Exception {
        get("/api/v1/does-not-exist", null).andExpect(status().isUnauthorized());
    }
}
