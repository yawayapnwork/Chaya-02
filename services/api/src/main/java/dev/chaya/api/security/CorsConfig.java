package dev.chaya.api.security;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/** Lets the configured web origins call the API from the browser. Credentials are bearer tokens, not cookies. */
@Configuration
public class CorsConfig {

    @ConfigurationProperties("chaya.cors")
    public record CorsProperties(List<String> allowedOrigins) {}

    @Bean
    CorsConfigurationSource corsConfigurationSource(CorsProperties props) {
        for (String origin : props.allowedOrigins()) {
            // Explicit origins only: a wildcard would let any site drive a signed-in user's browser against the API.
            if (origin.contains("*")) {
                throw new IllegalStateException("CHAYA_ALLOWED_ORIGINS must list explicit origins; '" + origin + "' is a wildcard");
            }
        }
        CorsConfiguration c = new CorsConfiguration();
        c.setAllowedOrigins(props.allowedOrigins());
        c.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        c.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Part-Sha256", "X-Chaya-Viewer-Token"));
        c.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", c);
        return source;
    }
}
