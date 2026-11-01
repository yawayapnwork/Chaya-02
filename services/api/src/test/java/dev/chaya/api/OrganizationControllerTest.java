package dev.chaya.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrganizationControllerTest extends ApiTest {

    @Test
    void current_returnsCallersOrganization() throws Exception {
        UUID org = fx.organization();
        String token = TestJwt.user(org, "operator").token();

        get("/api/v1/organizations/current", token)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(org.toString()))
            .andExpect(jsonPath("$.name").value("Test Org"));
    }

    @Test
    void list_asAdmin_returnsOrganizations() throws Exception {
        UUID org1 = fx.organization();
        UUID org2 = fx.organization();
        String adminToken = TestJwt.user(org1, "admin").token();

        get("/api/v1/organizations", adminToken)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(2)));
    }

    @Test
    void list_asNonAdmin_returnsOnlyOwnOrganization() throws Exception {
        UUID org1 = fx.organization();
        UUID org2 = fx.organization();
        String opToken = TestJwt.user(org1, "operator").token();

        get("/api/v1/organizations", opToken)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].id").value(org1.toString()));
    }

    @Test
    void create_asAdmin_succeeds() throws Exception {
        UUID org = fx.organization();
        String adminToken = TestJwt.user(org, "admin").token();
        String slug = "org-" + UUID.randomUUID().toString().substring(0, 8);

        post("/api/v1/organizations", adminToken, """
            {"slug": "%s", "name": "New Test Org"}
            """.formatted(slug))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.slug").value(slug))
            .andExpect(jsonPath("$.name").value("New Test Org"));
    }

    @Test
    void create_asOperator_isForbidden() throws Exception {
        UUID org = fx.organization();
        String opToken = TestJwt.user(org, "operator").token();
        String slug = "org-" + UUID.randomUUID().toString().substring(0, 8);

        post("/api/v1/organizations", opToken, """
            {"slug": "%s", "name": "New Test Org"}
            """.formatted(slug))
            .andExpect(status().isForbidden());
    }

    @Test
    void unauthenticated_isUnauthorized() throws Exception {
        get("/api/v1/organizations/current", null)
            .andExpect(status().isUnauthorized());
    }
}
