package dev.chaya.api.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;

/**
 * Two ways in: a Keycloak-issued JWT (Authorization: Bearer) or a public-viewer token
 * (X-Chaya-Viewer-Token). Everything not listed as public requires authentication, and
 * per-endpoint role rules live on the controllers (@PreAuthorize) next to the code they protect.
 *
 * <p>CSRF protection is off because no cookie or session authenticates anything: credentials travel only in
 * headers a cross-site page cannot make a browser attach. Re-enable it if cookie authentication is ever added.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    static final String METRICS_USER = "prometheus";

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, PublicViewerAuthenticator viewerAuthenticator,
                                            ObjectProvider<RateLimiter> rateLimiter,
                                            @Value("${chaya.metrics.scrape-password:}") String metricsPassword)
        throws Exception {
        http
            .cors(org.springframework.security.config.Customizer.withDefaults())
            .csrf(AbstractHttpConfigurer::disable) // stateless token API, no cookie sessions
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a
                .requestMatchers("/api/v1/health", "/api/v1/version", "/actuator/health/**").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/public/viewer-token").permitAll()
                .requestMatchers("/actuator/prometheus").access((auth, ctx) ->
                    new AuthorizationDecision(metricsCredentialsMatch(ctx.getRequest().getHeader("Authorization"), metricsPassword)))
                .requestMatchers("/api/v1/internal/**").hasRole(Role.SERVICE.name())
                .anyRequest().authenticated())
            .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(new JwtActorConverter())))
            .addFilterBefore(new DependencyFailureFilter(), SecurityContextHolderFilter.class)
            .addFilterBefore(new PublicViewerTokenFilter(viewerAuthenticator), BearerTokenAuthenticationFilter.class);
        rateLimiter.ifAvailable(l -> http.addFilterAfter(new RateLimitFilter(l), BearerTokenAuthenticationFilter.class));
        return http.build();
    }

    /**
     * Prometheus scrapes with HTTP Basic credentials (user "prometheus", password from METRICS_SCRAPE_PASSWORD).
     * With no password configured the metrics endpoint is closed to everyone.
     */
    static boolean metricsCredentialsMatch(String authorizationHeader, String expectedPassword) {
        if (expectedPassword == null || expectedPassword.isBlank() || authorizationHeader == null
            || !authorizationHeader.regionMatches(true, 0, "Basic ", 0, 6)) {
            return false;
        }
        byte[] presented;
        try {
            presented = Base64.getDecoder().decode(authorizationHeader.substring(6).trim());
        } catch (IllegalArgumentException e) {
            return false;
        }
        byte[] expected = (METRICS_USER + ":" + expectedPassword).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(presented, expected);
    }
}
