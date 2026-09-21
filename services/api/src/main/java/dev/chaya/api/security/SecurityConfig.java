package dev.chaya.api.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Two ways in: a Keycloak-issued JWT (Authorization: Bearer) or a public-viewer token
 * (X-Chaya-Viewer-Token). Everything not listed as public requires authentication, and
 * per-endpoint role rules live on the controllers (@PreAuthorize) next to the code they protect.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, PublicViewerAuthenticator viewerAuthenticator)
        throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable) // stateless token API, no cookie sessions
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a
                .requestMatchers("/api/v1/health", "/api/v1/version", "/actuator/health/**").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/public/viewer-token").permitAll()
                .requestMatchers("/api/v1/internal/**").hasRole(Role.SERVICE.name())
                .anyRequest().authenticated())
            .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(new JwtActorConverter())))
            .addFilterBefore(new PublicViewerTokenFilter(viewerAuthenticator), BearerTokenAuthenticationFilter.class);
        return http.build();
    }
}
