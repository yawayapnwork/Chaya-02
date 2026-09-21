package dev.chaya.api.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param issuer            expected `iss` of access tokens (the Keycloak realm URL)
 * @param jwksUri           optional override; defaults to the realm's certs endpoint
 * @param audience          required `aud` value; Keycloak adds it through an audience mapper
 * @param viewerTokenTtl    lifetime of a public-viewer access token
 * @param publicLinkMaxTtl  longest lifetime a public link may be created with
 */
@ConfigurationProperties("chaya.security")
public record SecurityProperties(
    String issuer, String jwksUri, String audience, Duration viewerTokenTtl, Duration publicLinkMaxTtl) {

    public SecurityProperties {
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalStateException("chaya.security.issuer (KEYCLOAK_ISSUER_URI) must be set");
        }
        if (audience == null || audience.isBlank()) {
            throw new IllegalStateException("chaya.security.audience (KEYCLOAK_AUDIENCE) must be set");
        }
        if (jwksUri == null || jwksUri.isBlank()) {
            jwksUri = issuer.replaceAll("/+$", "") + "/protocol/openid-connect/certs";
        }
    }
}
