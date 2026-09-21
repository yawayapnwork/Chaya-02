package dev.chaya.api.health;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.chaya.api.config.SecurityConfig;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
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

    @Test
    void healthIsUpWhenDatabaseIsUp() throws Exception {
        when(healthService.check()).thenReturn(new HealthService.HealthReport("UP", "UP"));
        mvc.perform(get("/api/v1/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void healthIs503WhenDatabaseIsDown() throws Exception {
        when(healthService.check()).thenReturn(new HealthService.HealthReport("DOWN", "DOWN"));
        mvc.perform(get("/api/v1/health"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.database").value("DOWN"));
    }

    @Test
    void versionReportsBuildAndApiVersion() throws Exception {
        mvc.perform(get("/api/v1/version"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.version").value("1.2.3"))
            .andExpect(jsonPath("$.apiVersion").value("v1"));
    }

    @Test
    void unlistedEndpointsAreDenied() throws Exception {
        mvc.perform(get("/api/v1/venues")).andExpect(status().isForbidden());
    }
}
