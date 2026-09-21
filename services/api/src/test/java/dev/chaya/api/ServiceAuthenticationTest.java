package dev.chaya.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.chaya.api.processing.JobStage;
import dev.chaya.api.processing.ProcessingJobRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Worker (service-to-service) authentication contract. Uses one stage so claims never collide with other tests. */
class ServiceAuthenticationTest extends ApiTest {

    private static final String CLAIM = "/api/v1/internal/jobs/claim";
    private static final String CLAIM_BODY = "{\"stage\":\"NAVMESH_GENERATION\"}";

    @Autowired ProcessingJobRepository jobs;

    @BeforeEach
    void drainStage() {
        jdbc.sql("UPDATE processing_job SET status = 'CANCELLED', finished_at = now() WHERE status = 'QUEUED' AND stage = 'NAVMESH_GENERATION'")
            .update();
    }

    private UUID queuedJob(Fixtures.Tree t) {
        return jobs.enqueue(t.org(), t.venue(), t.scan(), null, JobStage.NAVMESH_GENERATION);
    }

    @Test
    void workerEndpointsRejectAnonymousCallers() throws Exception {
        post(CLAIM, null, CLAIM_BODY).andExpect(status().isUnauthorized());
        post("/api/v1/internal/jobs/" + UUID.randomUUID() + "/complete", null, null).andExpect(status().isUnauthorized());
        post("/api/v1/internal/jobs/" + UUID.randomUUID() + "/fail", null, "{}").andExpect(status().isUnauthorized());
    }

    @Test
    void humanUsersCannotUseWorkerEndpointsEvenAsAdmin() throws Exception {
        UUID org = fx.organization();
        post(CLAIM, TestJwt.user(org, "admin").token(), CLAIM_BODY).andExpect(status().isForbidden());
    }

    @Test
    void serviceTokenClaimsCompletesAndFailsJobsWithAudit() throws Exception {
        var t = fx.tree();
        UUID job = queuedJob(t);
        String svc = TestJwt.service().token();

        post(CLAIM, svc, CLAIM_BODY).andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(job.toString()))
            .andExpect(jsonPath("$.organizationId").value(t.org().toString()));
        // Nothing else is queued: no content, and the running job is not handed out twice.
        post(CLAIM, svc, CLAIM_BODY).andExpect(status().isNoContent());

        post("/api/v1/internal/jobs/" + job + "/complete", svc, null).andExpect(status().isOk());
        // Completing twice is a conflict.
        post("/api/v1/internal/jobs/" + job + "/complete", svc, null).andExpect(status().isConflict());

        assertThat(auditCount(t.org(), "job.claim")).isEqualTo(1);
        assertThat(auditCount(t.org(), "job.complete")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT DISTINCT actor_type FROM audit_log WHERE organization_id = :o AND action = 'job.claim'")
            .param("o", t.org()).query(String.class).single()).isEqualTo("SERVICE");
    }

    @Test
    void failedJobCanBeRetriedByAnOperatorOfThatVenue() throws Exception {
        var t = fx.tree();
        UUID job = queuedJob(t);
        String svc = TestJwt.service().token();
        post(CLAIM, svc, CLAIM_BODY).andExpect(status().isOk());
        post("/api/v1/internal/jobs/" + job + "/fail", svc, "{\"errorCode\":\"NAV_FAILED\",\"errorMessage\":\"no walkable surface\"}")
            .andExpect(status().isOk());
        post("/api/v1/internal/jobs/" + job + "/fail", svc, "{\"errorCode\":\"X\"}").andExpect(status().isBadRequest());

        String op = TestJwt.user(t.org(), "operator").venues(t.venue()).token();
        post("/api/v1/venues/" + t.venue() + "/jobs/" + job + "/retry", op, null).andExpect(status().isOk());
        assertThat(auditCount(t.org(), "job.fail")).isEqualTo(1);
        assertThat(auditCount(t.org(), "job.retry")).isEqualTo(1);
    }

    @Test
    void serviceTokenHasNoAccessToTenantEndpoints() throws Exception {
        UUID org = fx.organization();
        UUID venue = fx.venue(org);
        String svc = TestJwt.service().token();
        get("/api/v1/venues", svc).andExpect(status().isForbidden());
        get("/api/v1/venues/" + venue, svc).andExpect(status().isForbidden());
        post("/api/v1/venues/" + venue + "/scans", svc, null).andExpect(status().isForbidden());
        get("/api/v1/audit-log", svc).andExpect(status().isForbidden());
    }

    @Test
    void tokenMixingServiceAndUserRolesIsRejected() throws Exception {
        UUID org = fx.organization();
        String mixed = TestJwt.user(org, "admin").role("service").token();
        post(CLAIM, mixed, CLAIM_BODY).andExpect(status().isUnauthorized());
    }

    @Test
    void serviceTokenFromAnUntrustedIssuerIsRejected() throws Exception {
        post(CLAIM, TestJwt.service().signedWith(TestJwt.OTHER_KEY).token(), CLAIM_BODY).andExpect(status().isUnauthorized());
        post(CLAIM, TestJwt.service().issuer("https://evil.example").token(), CLAIM_BODY).andExpect(status().isUnauthorized());
    }
}
