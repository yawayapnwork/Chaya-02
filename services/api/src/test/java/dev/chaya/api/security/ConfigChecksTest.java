package dev.chaya.api.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.chaya.api.storage.StorageProperties;
import dev.chaya.api.web.ConfigChecks;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Malformed URL settings are refused at startup, naming the variable and never echoing the value (DEPLOYMENT.md §2). */
class ConfigChecksTest {

    @Test
    void anEndpointMustBeAnAbsoluteHttpUrlWithAHost() {
        assertThat(ConfigChecks.httpUrl("S3_ENDPOINT", "http://minio:9000").getHost()).isEqualTo("minio");
        assertThat(ConfigChecks.httpUrl("S3_ENDPOINT", " HTTPS://s3.example.org ").getHost()).isEqualTo("s3.example.org");
        for (String bad : List.of("minio:9000", "localhost", "ftp://minio:9000", "http://", "http://exa mple.org", "/realms/chaya")) {
            assertThatThrownBy(() -> ConfigChecks.httpUrl("S3_ENDPOINT", bad))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("S3_ENDPOINT")
                .satisfies(e -> { if (bad.length() > 7) assertThat(e.getMessage()).doesNotContain(bad.strip()); });
        }
        assertThatThrownBy(() -> ConfigChecks.httpUrl("S3_ENDPOINT", " ")).hasMessage("S3_ENDPOINT must be set");
    }

    @Test
    void anOriginIsSchemeHostAndPortOnly() {
        assertThat(ConfigChecks.origin("CHAYA_ALLOWED_ORIGINS", "https://App.Example.org/")).isEqualTo("https://app.example.org");
        assertThat(ConfigChecks.origin("CHAYA_ALLOWED_ORIGINS", "http://localhost:3000")).isEqualTo("http://localhost:3000");
        for (String bad : List.of("https://app.example.org/capture", "https://app.example.org?x=1", "https://user@app.example.org",
                "app.example.org")) {
            assertThatThrownBy(() -> ConfigChecks.origin("CHAYA_ALLOWED_ORIGINS", bad)).hasMessageContaining("CHAYA_ALLOWED_ORIGINS");
        }
    }

    @Test
    void theConfigurationRecordsRefuseMalformedUrlsWhenTheApplicationStarts() {
        assertThatThrownBy(() -> new StorageProperties("minio:9000", "us-east-1", "k", "s", "raw", "derived"))
            .hasMessageContaining("S3_ENDPOINT");
        assertThatThrownBy(() -> new SecurityProperties("keycloak/realms/chaya", null, "chaya-api", null, null))
            .hasMessageContaining("KEYCLOAK_ISSUER_URI");
        assertThatThrownBy(() -> new SecurityProperties("https://auth.example.org/realms/chaya", "certs", "chaya-api", null, null))
            .hasMessageContaining("KEYCLOAK_JWKS_URI");
        assertThat(new SecurityProperties("https://auth.example.org/realms/chaya/", null, "chaya-api", null, null).jwksUri())
            .isEqualTo("https://auth.example.org/realms/chaya/protocol/openid-connect/certs");
        CorsConfig cors = new CorsConfig();
        assertThatThrownBy(() -> cors.corsConfigurationSource(new CorsConfig.CorsProperties(List.of("https://app.example.org/capture"))))
            .hasMessageContaining("CHAYA_ALLOWED_ORIGINS");
    }
}
