package dev.chaya.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.chaya.api.health.JwksIdentityProviderProbe;
import dev.chaya.api.security.DependencyFailureFilter;
import dev.chaya.api.security.JwtValidation;
import dev.chaya.api.security.SecurityProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;

/**
 * Keycloak unavailable: the production decoder (JWKS by URL) pointed at an address where nothing listens. A validly
 * signed token cannot be verified, which must surface as a 503 naming the cause -- not as a 401 telling the user their
 * token is bad, and not as a generic 500. No Spring context and no Docker needed.
 */
class IdentityProviderUnavailableTest {

    private static final String DEAD_ISSUER = "http://127.0.0.1:1/realms/chaya";

    private JwtAuthenticationProvider providerAgainstDeadKeycloak() {
        SecurityProperties props = new SecurityProperties(DEAD_ISSUER, null, TestJwt.AUDIENCE, null, null);
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(props.jwksUri()).build();
        decoder.setJwtValidator(JwtValidation.validator(DEAD_ISSUER, TestJwt.AUDIENCE));
        return new JwtAuthenticationProvider(decoder);
    }

    @Test
    void aValidTokenThatCannotBeVerifiedIsAnAuthenticationServiceFailureNotABadToken() {
        String token = TestJwt.user(java.util.UUID.randomUUID(), "viewer").token();
        assertThatThrownBy(() -> providerAgainstDeadKeycloak().authenticate(new BearerTokenAuthenticationToken(token)))
            .isInstanceOf(AuthenticationServiceException.class);
    }

    @Test
    void theApiAnswers503AuthenticationUnavailable() throws Exception {
        String token = TestJwt.user(java.util.UUID.randomUUID(), "viewer").token();
        JwtAuthenticationProvider provider = providerAgainstDeadKeycloak();
        MockHttpServletResponse res = new MockHttpServletResponse();
        new DependencyFailureFilter().doFilter(new MockHttpServletRequest("GET", "/api/v1/venues"), res,
            (req, resp) -> provider.authenticate(new BearerTokenAuthenticationToken(token)));
        assertThat(res.getStatus()).isEqualTo(503);
        assertThat(res.getContentAsString()).contains("AUTHENTICATION_UNAVAILABLE");
        assertThat(res.getHeader("Retry-After")).isNotNull();
    }

    @Test
    void healthReportsTheIdentityProviderAsUnreachable() {
        assertThat(new JwksIdentityProviderProbe(new SecurityProperties(DEAD_ISSUER, null, "chaya-api", null, null)).reachable())
            .isFalse();
    }
}
