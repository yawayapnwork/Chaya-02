package dev.chaya.api;

import dev.chaya.api.security.JwtValidation;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Replaces only the key source (JWKS URL -> a fixed test public key). Signature verification and the
 * issuer/expiry/audience rules are the production ones from {@link JwtValidation}.
 */
@TestConfiguration
class TestJwtConfig {

    @Bean
    @Primary
    JwtDecoder testJwtDecoder() throws Exception {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(TestJwt.KEY.toRSAPublicKey()).build();
        decoder.setJwtValidator(JwtValidation.validator(TestJwt.ISSUER, TestJwt.AUDIENCE));
        return decoder;
    }
}
