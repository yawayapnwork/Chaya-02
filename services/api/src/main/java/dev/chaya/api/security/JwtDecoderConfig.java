package dev.chaya.api.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

@Configuration
public class JwtDecoderConfig {

    /**
     * Verifies RS256 signatures against Keycloak's JWKS (fetched lazily and cached, keys are picked
     * by `kid`), then checks issuer, expiry and audience. Unsigned (alg=none) tokens are rejected
     * because the decoder only accepts the configured signature algorithm.
     */
    @Bean
    public JwtDecoder jwtDecoder(SecurityProperties props) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(props.jwksUri()).build();
        decoder.setJwtValidator(JwtValidation.validator(props.issuer(), props.audience()));
        return decoder;
    }
}
