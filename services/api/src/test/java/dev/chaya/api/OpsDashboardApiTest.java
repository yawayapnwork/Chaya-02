package dev.chaya.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The operations dashboard read model against real PostgreSQL: the section/role matrix (docs/security.md), tenant
 * isolation, and that every figure is the stored data -- counts, failures, search analytics, freshness -- with
 * nothing filled in when the data does not exist (no cost, no latency, no freshness).
 */
class OpsDashboardApiTest extends ApiTest {

    private static final String[] SECTIONS = {"overview", "coverage", "jobs", "failures", "storage", "search-analytics", "rescans", "audit"};

    /** Expected HTTP status per role and section; mirrors OpsSection. */
    private static final Map<String, Map<String, Integer>> MATRIX = Map.of(
        "admin", Map.of("overview", 200, "coverage", 200, "jobs", 200, "failures", 200, "storage", 200, "search-analytics", 200, "rescans", 200, "audit", 200),
        "venue-manager", Map.of("overview", 200, "coverage", 200, "jobs", 200, "failures", 200, "storage", 200, "search-analytics", 200, "rescans", 200, "audit", 200),
        "operator", Map.of("overview", 200, "coverage", 200, "jobs", 200, "failures", 200, "storage", 200, "search-analytics", 403, "rescans", 200, "audit", 403),
        "viewer", Map.of("overview", 200, "coverage", 403, "jobs", 403, "failures", 403, "storage", 403, "search-analytics", 403, "rescans", 403, "audit", 403));

    private String token(UUID org, UUID venue, String role) {
        return TestJwt.user(org, role).venues(venue).token();
    }

    private String url(UUID venue, String section) {
        return "/api/v1/venues/" + venue + "/ops/" + section;
    }

    @Test
    void sectionAccessFollowsTheRoleMatrix() throws Exception {
        UUID org = fx.organization();
        UUID venue = fx.venue(org);
        for (var role : MATRIX.entrySet()) {
            String t = token(org, venue, role.getKey());
            for (String section : SECTIONS) {
                int expected = role.getValue().get(section);
                get(url(venue, section), t).andExpect(status().is(expected));
            }
        }
        get(url(venue, "access"), token(org, venue, "viewer")).andExpect(status().isOk())
            .andExpect(jsonPath("$.sections.OVERVIEW").value(true))
            .andExpect(jsonPath("$.sections.JOBS").value(false))
            .andExpect(jsonPath("$.mayControlProcessing").value(false));
        get(url(venue, "access"), token(org, venue, "operator")).andExpect(status().isOk())
            .andExpect(jsonPath("$.sections.AUDIT").value(false))
            .andExpect(jsonPath("$.mayControlProcessing").value(true));
    }

    @Test
    void otherVenuesAndNonStaffAreRefused() throws Exception {
        UUID org = fx.organization();
        UUID mine = fx.venue(org);
        UUID other = fx.venue(org);
        UUID foreignOrg = fx.organization();
        UUID foreign = fx.venue(foreignOrg);

        get(url(other, "overview"), token(org, mine, "venue-manager")).andExpect(status().isNotFound());
        get(url(foreign, "overview"), TestJwt.user(org, "admin").token()).andExpect(status().isNotFound());
        get(url(mine, "overview"), null).andExpect(status().isUnauthorized());
        get(url(mine, "overview"), TestJwt.service().token()).andExpect(status().isForbidden());
    }

    @Test
    void jobCountsAndRetryAvailabilityComeFromStoredJobs() throws Exception {
        Fixtures.Tree t = fx.tree();
        insertJob(t, "INPUT_VALIDATION", "QUEUED", 0, null);
        insertJob(t, "FFMPEG_PREPROCESS", "RUNNING", 0, null);
        insertJob(t, "POSE_ESTIMATION", "SUCCEEDED", 0, null);
        insertJob(t, "PLANE_FITTING", "CANCELLED", 0, null);
        UUID retryable = insertJob(t, "SPLAT_RECONSTRUCTION", "FAILED", 0, "DEPENDENCY_UNAVAILABLE");
        UUID exhausted = insertJob(t, "GEOMETRIC_CLEANUP", "FAILED", 3, "WORKER_LOST");
        String manager = token(t.org(), t.venue(), "venue-manager");

        get(url(t.venue(), "jobs"), manager).andExpect(status().isOk())
            .andExpect(jsonPath("$.counts.QUEUED").value(1))
            .andExpect(jsonPath("$.counts.RUNNING").value(1))
            .andExpect(jsonPath("$.counts.SUCCEEDED").value(1))
            .andExpect(jsonPath("$.counts.FAILED").value(2))
            .andExpect(jsonPath("$.counts.CANCELLED").value(1))
            .andExpect(jsonPath("$.jobs.length()").value(6));

        get(url(t.venue(), "jobs") + "?status=FAILED", manager).andExpect(status().isOk())
            .andExpect(jsonPath("$.jobs.length()").value(2))
            .andExpect(jsonPath("$.jobs[?(@.id == '" + retryable + "')].retry.available").value(hasItem(true)))
            .andExpect(jsonPath("$.jobs[?(@.id == '" + retryable + "')].retry.via").value(hasItem("JOB")))
            .andExpect(jsonPath("$.jobs[?(@.id == '" + retryable + "')].errorCode").value(hasItem("DEPENDENCY_UNAVAILABLE")))
            .andExpect(jsonPath("$.jobs[?(@.id == '" + exhausted + "')].retry.available").value(hasItem(false)));

        get(url(t.venue(), "jobs") + "?status=DONE", manager).andExpect(status().isBadRequest());
    }

    @Test
    void failuresKeepTheStageRecordAndOfferRetryThroughTheRun() throws Exception {
        Fixtures.Tree t = fx.tree();
        UUID run = jdbc.sql("""
                INSERT INTO pipeline_run (organization_id, venue_id, scan_id, capture_session_id, status, stages,
                    time_budget_seconds, deadline_at, finished_at, failure_stage, failure_code, failure_message, requested_by)
                VALUES (:o, :v, :s, :c, 'FAILED', '{POSE_ESTIMATION}', 3600, now(), now(), 'POSE_ESTIMATION',
                    'DEPENDENCY_UNAVAILABLE', 'colmap missing', 'tester') RETURNING id
                """)
            .param("o", t.org()).param("v", t.venue()).param("s", t.scan()).param("c", t.session()).query(UUID.class).single();
        UUID job = jdbc.sql("""
                INSERT INTO processing_job (organization_id, venue_id, scan_id, run_id, stage, status, started_at, finished_at,
                    error_code, error_message)
                VALUES (:o, :v, :s, :r, 'POSE_ESTIMATION', 'FAILED', now() - interval '2 minutes', now(),
                    'DEPENDENCY_UNAVAILABLE', 'colmap missing') RETURNING id
                """)
            .param("o", t.org()).param("v", t.venue()).param("s", t.scan()).param("r", run).query(UUID.class).single();
        jdbc.sql("""
                INSERT INTO pipeline_stage_run (organization_id, venue_id, run_id, job_id, stage, attempt, status, command,
                    started_at, finished_at, exit_status, error_code, error_message, worker_id)
                VALUES (:o, :v, :r, :j, 'POSE_ESTIMATION', 1, 'FAILED', '{}'::jsonb, now() - interval '2 minutes', now(),
                    127, 'DEPENDENCY_UNAVAILABLE', 'colmap missing', 'worker-1')
                """)
            .param("o", t.org()).param("v", t.venue()).param("r", run).param("j", job).update();

        get(url(t.venue(), "failures"), token(t.org(), t.venue(), "operator")).andExpect(status().isOk())
            .andExpect(jsonPath("$.failedRuns[0].runId").value(run.toString()))
            .andExpect(jsonPath("$.failedRuns[0].retry.available").value(true))
            .andExpect(jsonPath("$.failedRuns[0].retry.captureId").value(t.session().toString()))
            .andExpect(jsonPath("$.failures[0].stage").value("POSE_ESTIMATION"))
            .andExpect(jsonPath("$.failures[0].errorCode").value("DEPENDENCY_UNAVAILABLE"))
            .andExpect(jsonPath("$.failures[0].exitStatus").value(127))
            .andExpect(jsonPath("$.failures[0].failedAt").exists())
            .andExpect(jsonPath("$.failures[0].retry.via").value("PIPELINE_RUN"))
            .andExpect(jsonPath("$.countsByCode[0].count").value(1));
    }

    @Test
    void searchAnalyticsAreAggregatesOfLoggedQueries() throws Exception {
        UUID org = fx.organization();
        UUID venue = fx.venue(org);
        String manager = token(org, venue, "venue-manager");

        get(url(venue, "search-analytics"), manager).andExpect(status().isOk())
            .andExpect(jsonPath("$.queryCount").value(0))
            .andExpect(jsonPath("$.avgLatencyMs").value(nullValue()))
            .andExpect(jsonPath("$.topTerms.length()").value(0));

        logQuery(org, venue, "sofa", 3, 10);
        logQuery(org, venue, "sofa", 2, 30);
        logQuery(org, venue, "fire exit", 0, 20);
        get(url(venue, "search-analytics"), manager).andExpect(status().isOk())
            .andExpect(jsonPath("$.queryCount").value(3))
            .andExpect(jsonPath("$.zeroResultCount").value(1))
            .andExpect(jsonPath("$.avgLatencyMs").value(20.0))
            .andExpect(jsonPath("$.topTerms[0].term").value("sofa"))
            .andExpect(jsonPath("$.topTerms[0].count").value(2))
            .andExpect(jsonPath("$.zeroResultTerms[0].term").value("fire exit"));
    }

    @Test
    void storageSumsRecordedArtifactsAndNeverInventsCost() throws Exception {
        Fixtures.Tree t = fx.tree();
        UUID job = insertJob(t, "ARTIFACT_GENERATION", "SUCCEEDED", 0, null);
        jdbc.sql("""
                INSERT INTO processing_artifact (organization_id, venue_id, scan_id, job_id, stage, bucket, object_key,
                    checksum_sha256, content_type, size_bytes, kind)
                VALUES (:o, :v, :s, :j, 'ARTIFACT_GENERATION', 'chaya-derived-test', :k, :sha, 'application/octet-stream', 1234, 'KSPLAT')
                """)
            .param("o", t.org()).param("v", t.venue()).param("s", t.scan()).param("j", job)
            .param("k", "ops-test/" + UUID.randomUUID()).param("sha", "a".repeat(64)).update();

        get(url(t.venue(), "storage"), token(t.org(), t.venue(), "operator")).andExpect(status().isOk())
            .andExpect(jsonPath("$.artifactCount").value(1))
            .andExpect(jsonPath("$.artifactBytes").value(1234))
            .andExpect(jsonPath("$.artifacts[0].kind").value("KSPLAT"))
            .andExpect(jsonPath("$.cost.available").value(false))
            .andExpect(jsonPath("$.cost.amount").value(nullValue()))
            .andExpect(jsonPath("$.derivedBucket").value("chaya-derived-test"));
    }

    @Test
    void freshnessIsNullUntilSomethingWasReconstructed() throws Exception {
        Fixtures.Tree t = fx.tree(); // one floor, one draft version, no reconstruction
        get(url(t.venue(), "overview"), token(t.org(), t.venue(), "viewer")).andExpect(status().isOk())
            .andExpect(jsonPath("$.floorCount").value(1))
            .andExpect(jsonPath("$.floors[0].currentVersion").value(nullValue()))
            .andExpect(jsonPath("$.floors[0].draftVersions").value(1))
            .andExpect(jsonPath("$.floors[0].latestReconstruction").value(nullValue()))
            .andExpect(jsonPath("$.floors[0].freshness.lastReconstructedAt").value(nullValue()))
            .andExpect(jsonPath("$.floors[0].freshness.lastReconstructedAgeSeconds").value(nullValue()))
            .andExpect(jsonPath("$.asOf").exists());
    }

    @Test
    void auditIsVenueScopedAndAdminsAlsoSeeRefusedAccess() throws Exception {
        UUID org = fx.organization();
        UUID venue = fx.venue(org);
        UUID other = fx.venue(org);
        String manager = token(org, venue, "venue-manager");
        call(org.springframework.http.HttpMethod.PATCH, "/api/v1/venues/" + venue, manager, "{\"name\":\"Renamed\"}")
            .andExpect(status().isOk());
        // An operator of another venue probing this one: refused, audited without venue_id.
        get("/api/v1/venues/" + venue, token(org, other, "operator")).andExpect(status().isNotFound());

        get(url(venue, "audit"), manager).andExpect(status().isOk())
            .andExpect(jsonPath("$.entries[*].action").value(hasItem("venue.update")))
            .andExpect(jsonPath("$.entries[?(@.outcome == 'DENIED')]").isEmpty());
        get(url(venue, "audit"), TestJwt.user(org, "admin").token()).andExpect(status().isOk())
            .andExpect(jsonPath("$.entries[?(@.outcome == 'DENIED')].action").value(hasItem("venue.access")));
        assertThat(auditCount(org, "venue.access", "DENIED")).isEqualTo(1);
    }

    private UUID insertJob(Fixtures.Tree t, String stage, String status, int retryCount, String errorCode) {
        boolean started = !status.equals("QUEUED");
        boolean finished = !status.equals("QUEUED") && !status.equals("RUNNING");
        return jdbc.sql("""
                INSERT INTO processing_job (organization_id, venue_id, scan_id, stage, status, retry_count, started_at,
                    finished_at, error_code, error_message)
                VALUES (:o, :v, :s, :stage, :status, :rc, CASE WHEN :started THEN now() - interval '1 minute' END,
                    CASE WHEN :finished THEN now() END, :code, CASE WHEN CAST(:code AS text) IS NOT NULL THEN 'failed in test' END)
                RETURNING id
                """)
            .param("o", t.org()).param("v", t.venue()).param("s", t.scan()).param("stage", stage).param("status", status)
            .param("rc", retryCount).param("started", started).param("finished", finished).param("code", errorCode)
            .query(UUID.class).single();
    }

    private void logQuery(UUID org, UUID venue, String q, int results, int latencyMs) {
        jdbc.sql("INSERT INTO search_query (organization_id, venue_id, actor_id, query_normalized, result_count, latency_ms) "
                + "VALUES (:o, :v, 'tester', :q, :rc, :lat)")
            .param("o", org).param("v", venue).param("q", q).param("rc", results).param("lat", latencyMs).update();
    }
}
