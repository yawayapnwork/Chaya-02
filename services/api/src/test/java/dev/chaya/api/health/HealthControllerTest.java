package dev.chaya.api.health;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.chaya.api.security.PublicViewerAuthenticator;
import dev.chaya.api.security.SecurityConfig;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest({HealthController.class, VersionController.class})
@Import({SecurityConfig.class, HealthControllerTest.Build.class})
class HealthControllerTest {

    @TestConfiguration
    static class Build {
        @Bean
        BuildProperties buildProperties() {
            Properties p = new Properties();
            p.setProperty("name", "chaya-api");
            p.setProperty("version", "1.2.3");
            return new BuildProperties(p);
        }
    }

    @Autowired MockMvc mvc;
    @MockitoBean HealthService healthService;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean PublicViewerAuthenticator viewerAuthenticator;

    private static HealthService.HealthReport report(String db, String storage, String idp, String scanner, String processing) {
        return HealthService.assemble(db, storage, idp, scanner,
            new HealthService.Processing(processing, 0L, 0L, null), java.time.Instant.now());
    }

    @Test
    void healthIsUpWhenEverythingIsUp() throws Exception {
        when(healthService.check()).thenReturn(report("UP", "UP", "UP", "UP", "IDLE"));
        mvc.perform(get("/api/v1/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"))
            .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
    }

    @Test
    void healthIs503WhenStorageIsDown() throws Exception {
        when(healthService.check()).thenReturn(report("UP", "DOWN", "UP", "UP", "IDLE"));
        mvc.perform(get("/api/v1/health"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.status").value("DOWN"))
            .andExpect(jsonPath("$.storage").value("DOWN"));
    }

    @Test
    void healthIs503WhenTheDatabaseOrIdentityProviderIsDown() throws Exception {
        when(healthService.check()).thenReturn(report("DOWN", "UP", "UP", "UP", "UNKNOWN"));
        mvc.perform(get("/api/v1/health")).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.database").value("DOWN"));
        when(healthService.check()).thenReturn(report("UP", "UP", "DOWN", "UP", "IDLE"));
        mvc.perform(get("/api/v1/health")).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.identityProvider").value("DOWN"));
    }

    @Test
    void aStalledQueueOrMissingScannerIsDegradedAndSaysWhy() throws Exception {
        when(healthService.check()).thenReturn(report("UP", "UP", "UP", "UP", "STALLED"));
        mvc.perform(get("/api/v1/health")).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("DEGRADED")).andExpect(jsonPath("$.processing.status").value("STALLED"));
        when(healthService.check()).thenReturn(report("UP", "UP", "UP", "DISABLED", "IDLE"));
        mvc.perform(get("/api/v1/health")).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("DEGRADED")).andExpect(jsonPath("$.malwareScanner").value("DISABLED"));
    }

    @Test
    void versionReportsBuildAndApiVersion() throws Exception {
        mvc.perform(get("/api/v1/version"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.version").value("1.2.3"))
            .andExpect(jsonPath("$.apiVersion").value("v1"));
    }

    @Test
    void everythingElseRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/v1/venues")).andExpect(status().isUnauthorized());
    }
}
