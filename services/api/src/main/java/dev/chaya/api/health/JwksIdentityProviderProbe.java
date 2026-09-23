package dev.chaya.api.health;

import dev.chaya.api.security.SecurityProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.stereotype.Component;

/** Fetches the configured JWKS document (the same URL the JWT decoder uses) with a short timeout. */
@Component
public class JwksIdentityProviderProbe implements IdentityProviderProbe {

    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    private final URI jwksUri;
    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(TIMEOUT)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();

    public JwksIdentityProviderProbe(SecurityProperties props) {
        this.jwksUri = URI.create(props.jwksUri());
    }

    @Override
    public boolean reachable() {
        try {
            HttpResponse<String> res = http.send(HttpRequest.newBuilder(jwksUri).timeout(TIMEOUT).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            return res.statusCode() == 200 && res.body().contains("\"keys\"");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            return false;
        }
    }
}
