package dev.chaya.api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Security foundation. Authentication (Keycloak JWT resource server) is not implemented yet:
 * only the operational endpoints below are reachable and everything else is denied,
 * so no business endpoint can be added by accident without an explicit rule.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable) // stateless token API, no cookie sessions
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a
                .requestMatchers("/api/v1/health", "/api/v1/version", "/actuator/health/**").permitAll()
                .anyRequest().denyAll());
        return http.build();
    }
}
