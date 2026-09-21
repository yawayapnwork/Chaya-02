package dev.chaya.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.chaya.api.security.Actor;
import dev.chaya.api.security.ActorAuthentication;
import dev.chaya.api.security.JwtActorConverter;
import dev.chaya.api.security.JwtDecoderConfig;
import dev.chaya.api.security.Role;
import dev.chaya.api.security.SecurityProperties;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

/**
 * Runs the PRODUCTION decoder (real JWKS fetch, real Keycloak-issued token) against a live Keycloak
 * that has imported infra/keycloak/chaya-realm.json. Skipped unless KEYCLOAK_LIVE_ISSUER is set:
 *
 *   KEYCLOAK_LIVE_ISSUER=http://localhost:8081/realms/chaya KEYCLOAK_LIVE_WORKER_SECRET=... mvn test -Dtest=KeycloakLiveTest
 */
@EnabledIfEnvironmentVariable(named = "KEYCLOAK_LIVE_ISSUER", matches = ".+")
class KeycloakLiveTest {

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String serviceToken(String issuer, String secret) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(issuer + "/protocol/openid-connect/token"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(
                "grant_type=client_credentials&client_id=chaya-worker&client_secret=" + enc(secret)))
            .build();
        HttpResponse<String> res = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
        return res.body().replaceAll(".*\"access_token\":\"([^\"]+)\".*", "$1");
    }

    private static JwtDecoder decoder(String issuer, String audience) {
        return new JwtDecoderConfig().jwtDecoder(new SecurityProperties(issuer, null, audience, Duration.ofMinutes(10), Duration.ofDays(30)));
    }

    @Test
    void realServiceAccountTokenPassesProductionValidationAndMapsToAServiceActor() throws Exception {
        String issuer = System.getenv("KEYCLOAK_LIVE_ISSUER");
        String token = serviceToken(issuer, System.getenv("KEYCLOAK_LIVE_WORKER_SECRET"));

        Jwt jwt = decoder(issuer, "chaya-api").decode(token);
        Actor actor = ((ActorAuthentication) new JwtActorConverter().convert(jwt)).getPrincipal();

        assertThat(actor.kind()).isEqualTo(Actor.Kind.SERVICE);
        assertThat(actor.roles()).containsExactly(Role.SERVICE);
        assertThat(actor.organizationId()).isNull();
    }

    @Test
    void sameRealTokenIsRejectedForTheWrongAudienceOrIssuer() throws Exception {
        String issuer = System.getenv("KEYCLOAK_LIVE_ISSUER");
        String token = serviceToken(issuer, System.getenv("KEYCLOAK_LIVE_WORKER_SECRET"));

        assertThatThrownBy(() -> decoder(issuer, "another-api").decode(token)).isInstanceOf(JwtException.class);
        // Expected issuer differs from the token iss, so validation must fail (JWKS still resolves).
        JwtDecoder wrongIssuer = new JwtDecoderConfig().jwtDecoder(new SecurityProperties(
            "http://localhost:9/realms/other", issuer + "/protocol/openid-connect/certs", "chaya-api",
            Duration.ofMinutes(10), Duration.ofDays(30)));
        assertThatThrownBy(() -> wrongIssuer.decode(token)).isInstanceOf(JwtException.class);
    }
}
