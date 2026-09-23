package dev.chaya.api.ops;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.chaya.api.hud.CaptureHudDtos.HudStatus;
import dev.chaya.api.hud.CaptureHudService;
import dev.chaya.api.ops.JobActions.Action;
import dev.chaya.api.ops.JobActions.JobState;
import dev.chaya.api.ops.OpsDtos.Access;
import dev.chaya.api.ops.OpsDtos.ArtifactKind;
import dev.chaya.api.ops.OpsDtos.Audit;
import dev.chaya.api.ops.OpsDtos.AuditEntry;
import dev.chaya.api.ops.OpsDtos.CodeCount;
import dev.chaya.api.ops.OpsDtos.Cost;
import dev.chaya.api.ops.OpsDtos.Coverage;
import dev.chaya.api.ops.OpsDtos.CurrentVersion;
import dev.chaya.api.ops.OpsDtos.FailedRun;
import dev.chaya.api.ops.OpsDtos.Failure;
import dev.chaya.api.ops.OpsDtos.Failures;
import dev.chaya.api.ops.OpsDtos.FloorCoverage;
import dev.chaya.api.ops.OpsDtos.FloorStatus;
import dev.chaya.api.ops.OpsDtos.Freshness;
import dev.chaya.api.ops.OpsDtos.Job;
import dev.chaya.api.ops.OpsDtos.Jobs;
import dev.chaya.api.ops.OpsDtos.LatestReconstruction;
import dev.chaya.api.ops.OpsDtos.MeasuredCoverage;
import dev.chaya.api.ops.OpsDtos.MediaBucket;
import dev.chaya.api.ops.OpsDtos.Overview;
import dev.chaya.api.ops.OpsDtos.PoiStats;
import dev.chaya.api.ops.OpsDtos.Rescan;
import dev.chaya.api.ops.OpsDtos.Rescans;
import dev.chaya.api.ops.OpsDtos.SearchAnalytics;
import dev.chaya.api.ops.OpsDtos.StageTime;
import dev.chaya.api.ops.OpsDtos.Storage;
import dev.chaya.api.ops.OpsDtos.TermCount;
import dev.chaya.api.ops.OpsDtos.UncoveredZone;
import dev.chaya.api.processing.JobStatus;
import dev.chaya.api.rescan.PolygonGeometry;
import dev.chaya.api.security.Actor;
import dev.chaya.api.security.Role;
import dev.chaya.api.security.TenantGuard;
import dev.chaya.api.storage.StorageProperties;
import dev.chaya.api.venue.VenueService;
import dev.chaya.api.venue.VenueService.Venue;
import dev.chaya.api.web.ApiException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read model of the operations dashboard (docs/operations-dashboard.md). Every method checks the section's role rule
 * (OpsSection) first, then the venue (TenantGuard), and every query filters by venue and organization. All figures
 * come from stored rows; nothing here estimates, scores or fills in a missing value.
 */
@Service
public class OpsDashboardService {

    static final int MAX_DAYS = 365;
    static final int DEFAULT_DAYS = 30;
    static final int MAX_JOBS = 200;
    static final int MAX_AUDIT = 500;
    private static final int FAILURE_LIMIT = 100;
    private static final int TERM_LIMIT = 20;

    static final String COST_UNAVAILABLE_REASON = "Processing cost is not tracked: no pricing, billing or GPU-hour rate is "
        + "recorded for workers or storage. Measured worker stage time is shown instead; it is not a cost.";

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final TenantGuard guard;
    private final VenueService venues;
    private final CaptureHudService hud;
    private final StorageProperties storage;
    private final ObjectMapper mapper;

    public OpsDashboardService(JdbcClient jdbc, TenantGuard guard, VenueService venues, CaptureHudService hud,
                               StorageProperties storage, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.venues = venues;
        this.hud = hud;
        this.storage = storage;
        this.mapper = mapper;
    }

    // =========================================================================================
    // Access
    // =========================================================================================

    @Transactional(readOnly = true)
    public Access access(Actor actor, UUID venueId) {
        guard.requireVenue(actor, venueId);
        Map<OpsSection, Boolean> sections = new EnumMap<>(OpsSection.class);
        for (OpsSection s : OpsSection.values()) {
            sections.put(s, s.permits(actor));
        }
        return new Access(venueId, actor.roles().stream().map(Role::name).sorted().toList(), sections,
            OpsSection.mayControlProcessing(actor));
    }

    private void require(Actor actor, OpsSection section, UUID venueId) {
        if (!section.permits(actor)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "OPS_SECTION_FORBIDDEN",
                "Your role does not permit reading the " + section.name().toLowerCase().replace('_', ' ') + " section.");
        }
        guard.requireVenue(actor, venueId);
    }

    private Instant dbNow() {
        return jdbc.sql("SELECT now()").query(Timestamp.class).single().toInstant();
    }

    // =========================================================================================
    // 1-3. Venue overview, current reconstruction version, scan freshness
    // =========================================================================================

    @Transactional(readOnly = true)
    public Overview overview(Actor actor, UUID venueId) {
        require(actor, OpsSection.OVERVIEW, venueId);
        Instant asOf = dbNow();
        UUID org = actor.organizationId();
        Venue venue = venues.get(actor, venueId);
        Instant venueCreatedAt = jdbc.sql("SELECT created_at FROM venue WHERE id = :v AND organization_id = :o")
            .param("v", venueId).param("o", org).query(Timestamp.class).single().toInstant();

        int captureCount = count("SELECT count(*) FROM capture_session WHERE venue_id = :v AND organization_id = :o", venueId, org);
        int poiCount = count("SELECT count(*) FROM poi WHERE venue_id = :v AND organization_id = :o AND deleted_at IS NULL", venueId, org);

        List<FloorStatus> floors = jdbc.sql("""
                SELECT f.id, f.level, f.name,
                       sv.id AS sv_id, sv.version_number, sv.parent_version_id, sv.region_geometry IS NOT NULL AS sv_incremental,
                       sv.finalized_at AS sv_finalized_at, sv.alignment_confidence, sv.alignment_residual_m, sv.changed_artifact_kinds,
                       (SELECT count(*) FROM scan_version d
                         WHERE d.floor_id = f.id AND d.venue_id = f.venue_id AND d.status = 'DRAFT') AS draft_versions,
                       rc.run_id, rc.generated_at, rc.run_status, rc.run_quality, rc.has_viewer_asset,
                       full_cap.captured_at AS full_captured_at,
                       region_cap.captured_at AS region_captured_at,
                       (SELECT max(c.started_at) FROM capture_session c
                         WHERE c.floor_id = f.id AND c.venue_id = f.venue_id) AS last_capture_started_at
                  FROM floor f
                  LEFT JOIN LATERAL (
                        SELECT id, version_number, parent_version_id, region_geometry, finalized_at, alignment_confidence,
                               alignment_residual_m, changed_artifact_kinds
                          FROM scan_version
                         WHERE floor_id = f.id AND venue_id = f.venue_id AND status = 'FINALIZED'
                         ORDER BY finalized_at DESC, version_number DESC
                         LIMIT 1) sv ON true
                  LEFT JOIN LATERAL (
                        SELECT r.id AS run_id, sr.finished_at AS generated_at, r.status AS run_status, r.quality AS run_quality,
                               EXISTS (SELECT 1 FROM processing_artifact a JOIN pipeline_stage_run s2 ON s2.id = a.stage_run_id
                                        WHERE s2.run_id = r.id AND s2.status = 'SUCCEEDED' AND a.kind = 'KSPLAT'
                                          AND a.contains_pii = false) AS has_viewer_asset
                          FROM pipeline_stage_run sr
                          JOIN pipeline_run r ON r.id = sr.run_id
                          JOIN capture_session cs ON cs.id = r.capture_session_id
                         WHERE sr.stage = 'ARTIFACT_GENERATION' AND sr.status = 'SUCCEEDED'
                           AND cs.floor_id = f.id AND r.venue_id = f.venue_id
                         ORDER BY sr.finished_at DESC
                         LIMIT 1) rc ON true
                  LEFT JOIN LATERAL (
                        SELECT max(COALESCE(cs.ended_at, cs.started_at)) AS captured_at
                          FROM pipeline_stage_run sr
                          JOIN pipeline_run r ON r.id = sr.run_id
                          JOIN capture_session cs ON cs.id = r.capture_session_id
                         WHERE sr.stage = 'ARTIFACT_GENERATION' AND sr.status = 'SUCCEEDED'
                           AND cs.floor_id = f.id AND r.venue_id = f.venue_id
                           AND cs.parent_scan_version_id IS NULL) full_cap ON true
                  LEFT JOIN LATERAL (
                        SELECT max(COALESCE(cs.ended_at, cs.started_at)) AS captured_at
                          FROM scan_version v
                          JOIN scan s ON s.id = v.scan_id
                          JOIN capture_session cs ON cs.id = s.capture_session_id
                         WHERE v.floor_id = f.id AND v.venue_id = f.venue_id AND v.status = 'FINALIZED'
                           AND cs.parent_scan_version_id IS NOT NULL) region_cap ON true
                 WHERE f.venue_id = :v AND f.organization_id = :o AND f.deleted_at IS NULL
                 ORDER BY f.level
                """)
            .param("v", venueId).param("o", org)
            .query((rs, i) -> floorStatus(rs, asOf)).list();

        return new Overview(venueId, venue.name(), venue.slug(), venue.timezone(), venueCreatedAt, floors.size(), captureCount,
            poiCount, jobCounts(venueId, org), floors, asOf);
    }

    private FloorStatus floorStatus(ResultSet rs, Instant asOf) throws SQLException {
        CurrentVersion version = null;
        if (rs.getObject("sv_id") != null) {
            version = new CurrentVersion(rs.getObject("sv_id", UUID.class), rs.getInt("version_number"),
                rs.getObject("parent_version_id", UUID.class), rs.getBoolean("sv_incremental"), ts(rs, "sv_finalized_at"),
                dbl(rs, "alignment_confidence"), dbl(rs, "alignment_residual_m"), strings(rs, "changed_artifact_kinds"));
        }
        LatestReconstruction reconstruction = null;
        if (rs.getObject("run_id") != null) {
            reconstruction = new LatestReconstruction(rs.getObject("run_id", UUID.class), ts(rs, "generated_at"),
                rs.getString("run_status"), rs.getString("run_quality"), rs.getBoolean("has_viewer_asset"));
        }
        Instant fullCapture = ts(rs, "full_captured_at");
        Instant reconstructed = reconstruction == null ? null : reconstruction.generatedAt();
        Instant regionCapture = ts(rs, "region_captured_at");
        Freshness freshness = new Freshness(fullCapture, age(fullCapture, asOf), reconstructed, age(reconstructed, asOf),
            regionCapture, age(regionCapture, asOf), ts(rs, "last_capture_started_at"));
        return new FloorStatus(rs.getObject("id", UUID.class), rs.getInt("level"), rs.getString("name"), version,
            rs.getInt("draft_versions"), reconstruction, freshness);
    }

    /** Seconds from {@code t} to {@code asOf}. Not clamped: a capture time reported by a device clock ahead of the
     * server shows as a negative age rather than being silently corrected. */
    static Long age(Instant t, Instant asOf) {
        return t == null ? null : Duration.between(t, asOf).getSeconds();
    }

    private Map<String, Long> jobCounts(UUID venueId, UUID org) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (JobStatus s : JobStatus.values()) {
            counts.put(s.name(), 0L);
        }
        jdbc.sql("SELECT status, count(*) AS n FROM processing_job WHERE venue_id = :v AND organization_id = :o GROUP BY status")
            .param("v", venueId).param("o", org)
            .query((rs, i) -> Map.entry(rs.getString("status"), rs.getLong("n"))).list()
            .forEach(e -> counts.put(e.getKey(), e.getValue()));
        return counts;
    }

    // =========================================================================================
    // 4. Coverage gaps
    // =========================================================================================

    @Transactional(readOnly = true)
    public Coverage coverage(Actor actor, UUID venueId) {
        require(actor, OpsSection.COVERAGE, venueId);
        Instant asOf = dbNow();
        UUID org = actor.organizationId();

        record FloorRow(UUID id, int level, String name, boolean hasReconstruction, boolean hasFinalizedVersion) {}
        List<FloorRow> floors = jdbc.sql("""
                SELECT f.id, f.level, f.name,
                       EXISTS (SELECT 1 FROM pipeline_stage_run sr
                                 JOIN pipeline_run r ON r.id = sr.run_id
                                 JOIN capture_session cs ON cs.id = r.capture_session_id
                                WHERE sr.stage = 'ARTIFACT_GENERATION' AND sr.status = 'SUCCEEDED'
                                  AND cs.floor_id = f.id AND r.venue_id = f.venue_id) AS has_reconstruction,
                       EXISTS (SELECT 1 FROM scan_version v
                                WHERE v.floor_id = f.id AND v.venue_id = f.venue_id AND v.status = 'FINALIZED') AS has_finalized
                  FROM floor f
                 WHERE f.venue_id = :v AND f.organization_id = :o AND f.deleted_at IS NULL
                 ORDER BY f.level
                """)
            .param("v", venueId).param("o", org)
            .query((rs, i) -> new FloorRow(rs.getObject("id", UUID.class), rs.getInt("level"), rs.getString("name"),
                rs.getBoolean("has_reconstruction"), rs.getBoolean("has_finalized")))
            .list();

        // The newest capture of each floor that has a HUD room outline: the only captures coverage can be measured for.
        record MeasuredCapture(UUID captureId, Instant startedAt, String status) {}
        Map<UUID, MeasuredCapture> latestMeasured = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT DISTINCT ON (c.floor_id) c.floor_id, c.id, c.started_at, c.status
                  FROM capture_session c
                  JOIN capture_hud_scene h ON h.capture_session_id = c.id
                 WHERE c.venue_id = :v AND c.organization_id = :o AND c.floor_id IS NOT NULL
                 ORDER BY c.floor_id, c.started_at DESC
                """)
            .param("v", venueId).param("o", org)
            .query((rs, i) -> Map.entry(rs.getObject("floor_id", UUID.class),
                new MeasuredCapture(rs.getObject("id", UUID.class), ts(rs, "started_at"), rs.getString("status"))))
            .list().forEach(e -> latestMeasured.put(e.getKey(), e.getValue()));

        List<FloorCoverage> out = new ArrayList<>();
        for (FloorRow f : floors) {
            MeasuredCapture mc = latestMeasured.get(f.id());
            MeasuredCoverage measured = null;
            if (mc != null) {
                HudStatus s = hud.status(actor, venueId, mc.captureId()); // reruns the capture planner on stored samples
                List<UncoveredZone> zones = s.uncoveredZones().stream()
                    .map(z -> new UncoveredZone(z.centroidX(), z.centroidY(), z.areaM2(), z.reason())).toList();
                measured = new MeasuredCoverage(mc.captureId(), mc.startedAt(), mc.status(), s.coverageAvailable(),
                    s.coverageUnavailableReason(), s.coveragePercent(), s.weightedCoveragePercent(), s.uncoveredAreaM2(), zones);
            }
            out.add(new FloorCoverage(f.id(), f.level(), f.name(), f.hasReconstruction(), f.hasFinalizedVersion(), measured,
                gaps(f.hasReconstruction(), f.hasFinalizedVersion(), measured)));
        }
        return new Coverage(out, asOf);
    }

    static List<String> gaps(boolean hasReconstruction, boolean hasFinalizedVersion, MeasuredCoverage measured) {
        List<String> gaps = new ArrayList<>();
        if (!hasReconstruction) gaps.add("NO_RECONSTRUCTION");
        if (!hasFinalizedVersion) gaps.add("NO_FINALIZED_VERSION");
        if (measured == null) {
            gaps.add("NO_MEASURED_COVERAGE");
        } else if (!measured.coverageAvailable()) {
            gaps.add("COVERAGE_UNAVAILABLE");
        } else if (!measured.uncoveredZones().isEmpty()) {
            gaps.add("UNCOVERED_ZONES");
        }
        return gaps;
    }

    // =========================================================================================
    // 5. Processing jobs
    // =========================================================================================

    @Transactional(readOnly = true)
    public Jobs jobs(Actor actor, UUID venueId, String status, int limit) {
        require(actor, OpsSection.JOBS, venueId);
        if (status != null) {
            try {
                JobStatus.valueOf(status);
            } catch (IllegalArgumentException e) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_JOB_STATUS", "unknown job status: " + status);
            }
        }
        int l = Math.min(Math.max(limit, 1), MAX_JOBS);
        Instant asOf = dbNow();
        UUID org = actor.organizationId();
        List<Job> list = jdbc.sql("""
                SELECT j.id, j.stage, j.status, j.scan_id, s.capture_session_id, j.run_id, r.status AS run_status,
                       r.failure_stage, j.retry_count, j.max_retries, j.queued_at, j.started_at, j.finished_at, j.worker_id,
                       j.lease_expires_at, j.error_code, j.error_message,
                       COALESCE(j.id = (SELECT j2.id FROM processing_job j2 WHERE j2.run_id = j.run_id AND j2.stage = j.stage
                                         ORDER BY j2.created_at DESC LIMIT 1), false) AS latest_of_stage
                  FROM processing_job j
                  JOIN scan s ON s.id = j.scan_id
                  LEFT JOIN pipeline_run r ON r.id = j.run_id
                 WHERE j.venue_id = :v AND j.organization_id = :o
                   AND (CAST(:status AS text) IS NULL OR j.status = CAST(:status AS text))
                 ORDER BY COALESCE(j.finished_at, j.started_at, j.queued_at) DESC, j.created_at DESC
                 LIMIT :l
                """)
            .param("v", venueId).param("o", org).param("status", status).param("l", l)
            .query((rs, i) -> {
                JobState state = jobState(rs, "status", "capture_session_id");
                return new Job(rs.getObject("id", UUID.class), rs.getString("stage"), rs.getString("status"),
                    rs.getObject("scan_id", UUID.class), rs.getObject("capture_session_id", UUID.class),
                    rs.getObject("run_id", UUID.class), rs.getString("run_status"), rs.getInt("retry_count"),
                    rs.getInt("max_retries"), ts(rs, "queued_at"), ts(rs, "started_at"), ts(rs, "finished_at"),
                    rs.getString("worker_id"), ts(rs, "lease_expires_at"), rs.getString("error_code"),
                    rs.getString("error_message"), JobActions.retry(state), JobActions.cancel(state));
            })
            .list();
        return new Jobs(jobCounts(venueId, org), list, l, asOf);
    }

    private static JobState jobState(ResultSet rs, String statusColumn, String captureColumn) throws SQLException {
        return new JobState(rs.getString(statusColumn), rs.getString("stage"), rs.getInt("retry_count"), rs.getInt("max_retries"),
            rs.getObject("run_id", UUID.class), rs.getString("run_status"), rs.getString("failure_stage"),
            rs.getBoolean("latest_of_stage"), rs.getObject(captureColumn, UUID.class));
    }

    // =========================================================================================
    // 6. Processing failures
    // =========================================================================================

    @Transactional(readOnly = true)
    public Failures failures(Actor actor, UUID venueId, int days) {
        require(actor, OpsSection.FAILURES, venueId);
        int d = clampDays(days);
        Instant asOf = dbNow();
        UUID org = actor.organizationId();

        List<FailedRun> failedRuns = jdbc.sql("""
                SELECT r.id, r.capture_session_id, r.failure_stage, r.failure_code, r.failure_message, r.finished_at,
                       j.status AS job_status, j.retry_count, j.max_retries
                  FROM pipeline_run r
                  LEFT JOIN LATERAL (SELECT status, retry_count, max_retries FROM processing_job
                                      WHERE run_id = r.id AND stage = r.failure_stage
                                      ORDER BY created_at DESC LIMIT 1) j ON true
                 WHERE r.venue_id = :v AND r.organization_id = :o AND r.status = 'FAILED'
                 ORDER BY r.finished_at DESC
                 LIMIT 50
                """)
            .param("v", venueId).param("o", org)
            .query((rs, i) -> {
                Integer retryCount = rs.getObject("retry_count") == null ? null : rs.getInt("retry_count");
                Integer maxRetries = rs.getObject("max_retries") == null ? null : rs.getInt("max_retries");
                return new FailedRun(rs.getObject("id", UUID.class), rs.getObject("capture_session_id", UUID.class),
                    rs.getString("failure_stage"), rs.getString("failure_code"), rs.getString("failure_message"),
                    ts(rs, "finished_at"), JobActions.runRetry(rs.getString("failure_stage"), rs.getString("job_status"),
                        retryCount, maxRetries, rs.getObject("capture_session_id", UUID.class)));
            })
            .list();

        List<Failure> failures = new ArrayList<>(jdbc.sql("""
                SELECT sr.job_id, sr.run_id, r.capture_session_id, sr.stage, sr.attempt, sr.error_code, sr.error_message,
                       CAST(sr.error_details AS text) AS error_details, sr.exit_status, sr.worker_id, sr.finished_at,
                       j.status AS job_status, j.retry_count, j.max_retries, r.status AS run_status, r.failure_stage,
                       COALESCE(j.id = (SELECT j2.id FROM processing_job j2 WHERE j2.run_id = j.run_id AND j2.stage = j.stage
                                         ORDER BY j2.created_at DESC LIMIT 1), false) AS latest_of_stage
                  FROM pipeline_stage_run sr
                  JOIN pipeline_run r ON r.id = sr.run_id
                  JOIN processing_job j ON j.id = sr.job_id
                 WHERE sr.venue_id = :v AND sr.organization_id = :o AND sr.status = 'FAILED'
                   AND sr.finished_at >= now() - make_interval(days => :d)
                 ORDER BY sr.finished_at DESC
                 LIMIT :l
                """)
            .param("v", venueId).param("o", org).param("d", d).param("l", FAILURE_LIMIT)
            .query((rs, i) -> new Failure("STAGE_RUN", rs.getObject("job_id", UUID.class), rs.getObject("run_id", UUID.class),
                rs.getObject("capture_session_id", UUID.class), rs.getString("stage"), rs.getInt("attempt"),
                rs.getString("error_code"), rs.getString("error_message"), json(rs.getString("error_details")),
                rs.getObject("exit_status") == null ? null : rs.getInt("exit_status"), rs.getString("worker_id"),
                ts(rs, "finished_at"), rs.getString("job_status"),
                JobActions.retry(jobState(rs, "job_status", "capture_session_id"))))
            .list());

        // Legacy jobs outside any pipeline run keep their error on the job row itself.
        failures.addAll(jdbc.sql("""
                SELECT j.id, j.stage, j.status, j.retry_count, j.max_retries, j.error_code, j.error_message, j.worker_id,
                       j.finished_at, s.capture_session_id, j.run_id, NULL AS run_status, NULL AS failure_stage,
                       false AS latest_of_stage
                  FROM processing_job j
                  JOIN scan s ON s.id = j.scan_id
                 WHERE j.venue_id = :v AND j.organization_id = :o AND j.run_id IS NULL AND j.status = 'FAILED'
                   AND j.finished_at >= now() - make_interval(days => :d)
                 ORDER BY j.finished_at DESC
                 LIMIT :l
                """)
            .param("v", venueId).param("o", org).param("d", d).param("l", FAILURE_LIMIT)
            .query((rs, i) -> new Failure("LEGACY_JOB", rs.getObject("id", UUID.class), null,
                rs.getObject("capture_session_id", UUID.class), rs.getString("stage"), rs.getInt("retry_count") + 1,
                rs.getString("error_code"), rs.getString("error_message"), null, null, rs.getString("worker_id"),
                ts(rs, "finished_at"), rs.getString("status"), JobActions.retry(jobState(rs, "status", "capture_session_id"))))
            .list());
        failures.sort(Comparator.comparing(Failure::failedAt, Comparator.nullsLast(Comparator.reverseOrder())));
        List<Failure> limited = failures.size() > FAILURE_LIMIT ? failures.subList(0, FAILURE_LIMIT) : failures;

        List<CodeCount> counts = jdbc.sql("""
                SELECT stage, error_code, count(*) AS n FROM (
                    SELECT stage, error_code FROM pipeline_stage_run
                     WHERE venue_id = :v AND organization_id = :o AND status = 'FAILED'
                       AND finished_at >= now() - make_interval(days => :d)
                    UNION ALL
                    SELECT stage, error_code FROM processing_job
                     WHERE venue_id = :v AND organization_id = :o AND run_id IS NULL AND status = 'FAILED'
                       AND finished_at >= now() - make_interval(days => :d)) f
                 GROUP BY stage, error_code
                 ORDER BY n DESC, stage, error_code
                """)
            .param("v", venueId).param("o", org).param("d", d)
            .query((rs, i) -> new CodeCount(rs.getString("stage"), rs.getString("error_code"), rs.getLong("n"))).list();

        return new Failures(d, failedRuns, new ArrayList<>(limited), counts, asOf);
    }

    // =========================================================================================
    // 7. Storage, artifacts, processing time and cost
    // =========================================================================================

    @Transactional(readOnly = true)
    public Storage storage(Actor actor, UUID venueId) {
        require(actor, OpsSection.STORAGE, venueId);
        Instant asOf = dbNow();
        UUID org = actor.organizationId();

        List<MediaBucket> media = jdbc.sql("""
                SELECT status, count(*) AS n, COALESCE(sum(declared_size_bytes), 0) AS bytes
                  FROM capture_media WHERE venue_id = :v AND organization_id = :o
                 GROUP BY status ORDER BY status
                """)
            .param("v", venueId).param("o", org)
            .query((rs, i) -> new MediaBucket(rs.getString("status"), rs.getLong("n"), rs.getLong("bytes"))).list();

        List<ArtifactKind> kinds = jdbc.sql("""
                SELECT COALESCE(kind, 'UNCLASSIFIED') AS kind, count(*) AS n, COALESCE(sum(size_bytes), 0) AS bytes,
                       count(*) FILTER (WHERE partial) AS partial_n
                  FROM processing_artifact
                 WHERE venue_id = :v AND organization_id = :o AND contains_pii = false
                 GROUP BY 1 ORDER BY bytes DESC, kind
                """)
            .param("v", venueId).param("o", org)
            .query((rs, i) -> new ArtifactKind(rs.getString("kind"), rs.getLong("n"), rs.getLong("bytes"), rs.getLong("partial_n")))
            .list();

        record Totals(long count, long bytes, long piiCount, long piiBytes, Instant last) {}
        Totals totals = jdbc.sql("""
                SELECT count(*) FILTER (WHERE NOT contains_pii) AS n,
                       COALESCE(sum(size_bytes) FILTER (WHERE NOT contains_pii), 0) AS bytes,
                       count(*) FILTER (WHERE contains_pii) AS pii_n,
                       COALESCE(sum(size_bytes) FILTER (WHERE contains_pii), 0) AS pii_bytes,
                       max(created_at) AS last_at
                  FROM processing_artifact WHERE venue_id = :v AND organization_id = :o
                """)
            .param("v", venueId).param("o", org)
            .query((rs, i) -> new Totals(rs.getLong("n"), rs.getLong("bytes"), rs.getLong("pii_n"), rs.getLong("pii_bytes"),
                ts(rs, "last_at")))
            .single();

        long purgeIncomplete = jdbc.sql("""
                SELECT count(*) FROM audit_log
                 WHERE venue_id = :v AND organization_id = :o AND action = 'pipeline.pii_purge_incomplete'
                """)
            .param("v", venueId).param("o", org).query(Long.class).single();

        List<StageTime> stageTime = jdbc.sql("""
                SELECT stage, count(*) AS n, COALESCE(sum(EXTRACT(EPOCH FROM finished_at - started_at)), 0) AS total
                  FROM pipeline_stage_run WHERE venue_id = :v AND organization_id = :o
                 GROUP BY stage ORDER BY total DESC, stage
                """)
            .param("v", venueId).param("o", org)
            .query((rs, i) -> {
                long n = rs.getLong("n");
                double total = rs.getDouble("total");
                return new StageTime(rs.getString("stage"), n, total, n == 0 ? 0 : total / n);
            })
            .list();

        return new Storage(storage.bucket(), storage.derivedBucket(), media, kinds, totals.count(), totals.bytes(),
            totals.piiCount(), totals.piiBytes(), purgeIncomplete, totals.last(), stageTime, costUnavailable(), asOf);
    }

    /** No cost source exists anywhere in the system (no pricing, billing or rate table), so cost is always reported
     * as unavailable. When one is added, this is the single place that changes. */
    static Cost costUnavailable() {
        return new Cost(false, null, null, COST_UNAVAILABLE_REASON);
    }

    // =========================================================================================
    // 8. POI and search analytics
    // =========================================================================================

    @Transactional(readOnly = true)
    public SearchAnalytics searchAnalytics(Actor actor, UUID venueId, int days) {
        require(actor, OpsSection.SEARCH_ANALYTICS, venueId);
        int d = clampDays(days);
        Instant asOf = dbNow();
        UUID org = actor.organizationId();

        record Summary(long total, long zero, Double avgLatency, Double p95Latency, long searchers, Instant first, Instant last) {}
        Summary summary = jdbc.sql("""
                SELECT count(*) AS total, count(*) FILTER (WHERE result_count = 0) AS zero,
                       avg(latency_ms) AS avg_latency,
                       percentile_cont(0.95) WITHIN GROUP (ORDER BY latency_ms) AS p95_latency,
                       count(DISTINCT actor_id) AS searchers, min(created_at) AS first_at, max(created_at) AS last_at
                  FROM search_query
                 WHERE venue_id = :v AND organization_id = :o AND created_at >= now() - make_interval(days => :d)
                """)
            .param("v", venueId).param("o", org).param("d", d)
            .query((rs, i) -> new Summary(rs.getLong("total"), rs.getLong("zero"), dbl(rs, "avg_latency"), dbl(rs, "p95_latency"),
                rs.getLong("searchers"), ts(rs, "first_at"), ts(rs, "last_at")))
            .single();

        List<TermCount> top = terms("", venueId, org, d);
        List<TermCount> zero = terms("AND result_count = 0", venueId, org, d);

        PoiStats pois = jdbc.sql("""
                SELECT count(*) AS total,
                       count(*) FILTER (WHERE v.source = 'MANUAL') AS manual,
                       count(*) FILTER (WHERE v.source = 'AUTO_DETECTED') AS auto,
                       count(*) FILTER (WHERE v.no_embedding) AS awaiting
                  FROM poi p
                  JOIN LATERAL (SELECT source, embedding IS NULL AS no_embedding FROM poi_version
                                 WHERE poi_id = p.id ORDER BY version_number DESC LIMIT 1) v ON true
                 WHERE p.venue_id = :v AND p.organization_id = :o AND p.deleted_at IS NULL
                """)
            .param("v", venueId).param("o", org)
            .query((rs, i) -> new PoiStats(rs.getLong("total"), rs.getLong("manual"), rs.getLong("auto"), rs.getLong("awaiting")))
            .single();

        return new SearchAnalytics(d, summary.total(), summary.zero(), summary.avgLatency(), summary.p95Latency(),
            summary.searchers(), summary.first(), summary.last(), top, zero, pois, asOf);
    }

    private List<TermCount> terms(String extraWhere, UUID venueId, UUID org, int days) {
        return jdbc.sql("SELECT query_normalized, count(*) AS n, avg(result_count) AS avg_results, max(created_at) AS last_at "
                + "FROM search_query WHERE venue_id = :v AND organization_id = :o "
                + "AND created_at >= now() - make_interval(days => :d) " + extraWhere + " "
                + "GROUP BY query_normalized ORDER BY n DESC, last_at DESC LIMIT :l")
            .param("v", venueId).param("o", org).param("d", days).param("l", TERM_LIMIT)
            .query((rs, i) -> new TermCount(rs.getString("query_normalized"), rs.getLong("n"), dbl(rs, "avg_results"),
                ts(rs, "last_at")))
            .list();
    }

    // =========================================================================================
    // 9. Re-scan history
    // =========================================================================================

    @Transactional(readOnly = true)
    public Rescans rescans(Actor actor, UUID venueId) {
        require(actor, OpsSection.RESCANS, venueId);
        Instant asOf = dbNow();
        List<Rescan> list = jdbc.sql("""
                SELECT c.id AS capture_id, c.floor_id, f.name AS floor_name, c.operator_id, c.created_at, c.status AS capture_status,
                       c.parent_scan_version_id, pv.version_number AS parent_version_number,
                       CAST(c.region_geometry AS text) AS region_geometry,
                       r.id AS run_id, r.status AS run_status, r.failure_stage, r.failure_code, r.finished_at AS run_finished_at,
                       sv.id AS sv_id, sv.version_number AS sv_number, sv.status AS sv_status, sv.alignment_confidence,
                       sv.alignment_residual_m, sv.changed_artifact_kinds, sv.finalized_at
                  FROM capture_session c
                  JOIN scan_version pv ON pv.id = c.parent_scan_version_id
                  LEFT JOIN floor f ON f.id = c.floor_id
                  LEFT JOIN scan s ON s.capture_session_id = c.id
                  LEFT JOIN pipeline_run r ON r.scan_id = s.id
                  LEFT JOIN scan_version sv ON sv.id = r.scan_version_id
                 WHERE c.venue_id = :v AND c.organization_id = :o AND c.parent_scan_version_id IS NOT NULL
                 ORDER BY c.created_at DESC
                 LIMIT 100
                """)
            .param("v", venueId).param("o", actor.organizationId())
            .query((rs, i) -> new Rescan(rs.getObject("capture_id", UUID.class), rs.getObject("floor_id", UUID.class),
                rs.getString("floor_name"), rs.getString("operator_id"), ts(rs, "created_at"), rs.getString("capture_status"),
                rs.getObject("parent_scan_version_id", UUID.class), rs.getInt("parent_version_number"),
                regionArea(rs.getString("region_geometry")), rs.getObject("run_id", UUID.class), rs.getString("run_status"),
                rs.getString("failure_stage"), rs.getString("failure_code"), ts(rs, "run_finished_at"),
                rs.getObject("sv_id", UUID.class), rs.getObject("sv_number") == null ? null : rs.getInt("sv_number"),
                rs.getString("sv_status"), dbl(rs, "alignment_confidence"), dbl(rs, "alignment_residual_m"),
                strings(rs, "changed_artifact_kinds"),
                ts(rs, "finalized_at")))
            .list();
        return new Rescans(list, asOf);
    }

    /** Area of the stored region polygon (same computation RescanService validated it with), or null if unreadable. */
    private Double regionArea(String regionJson) {
        Map<String, Object> region = json(regionJson);
        if (region == null || !(region.get("points") instanceof List<?> raw)) {
            return null;
        }
        try {
            @SuppressWarnings("unchecked")
            List<List<Number>> points = (List<List<Number>>) raw;
            return PolygonGeometry.area(points);
        } catch (RuntimeException e) {
            return null;
        }
    }

    // =========================================================================================
    // 10. Audit activity
    // =========================================================================================

    /**
     * The venue's audit trail. Administrators additionally see refused attempts to reach this venue: those rows carry
     * no venue_id (the attempted id may belong to another tenant, see AuditService#denied), only metadata.
     */
    @Transactional(readOnly = true)
    public Audit audit(Actor actor, UUID venueId, int limit) {
        require(actor, OpsSection.AUDIT, venueId);
        int l = Math.min(Math.max(limit, 1), MAX_AUDIT);
        Instant asOf = dbNow();
        boolean admin = actor.roles().contains(Role.ADMIN);
        List<AuditEntry> entries = jdbc.sql("""
                SELECT * FROM (
                    (SELECT id, actor_id, actor_type, action, resource_type, resource_id, outcome, occurred_at
                       FROM audit_log WHERE organization_id = :o AND venue_id = :v
                      ORDER BY occurred_at DESC LIMIT :l)
                    UNION ALL
                    (SELECT id, actor_id, actor_type, action, resource_type, resource_id, outcome, occurred_at
                       FROM audit_log
                      WHERE :admin AND organization_id = :o AND venue_id IS NULL AND outcome = 'DENIED'
                        AND metadata->>'attemptedResourceId' = :vs
                      ORDER BY occurred_at DESC LIMIT :l)) a
                 ORDER BY occurred_at DESC
                 LIMIT :l
                """)
            .param("o", actor.organizationId()).param("v", venueId).param("vs", venueId.toString()).param("admin", admin)
            .param("l", l)
            .query((rs, i) -> new AuditEntry(rs.getObject("id", UUID.class), rs.getString("actor_id"), rs.getString("actor_type"),
                rs.getString("action"), rs.getString("resource_type"), rs.getObject("resource_id", UUID.class),
                rs.getString("outcome"), ts(rs, "occurred_at")))
            .list();
        return new Audit(entries, l, asOf);
    }

    // =========================================================================================
    // helpers
    // =========================================================================================

    static int clampDays(int days) {
        return Math.min(Math.max(days, 1), MAX_DAYS);
    }

    private int count(String sql, UUID venueId, UUID org) {
        return jdbc.sql(sql).param("v", venueId).param("o", org).query(Integer.class).single();
    }

    private static Instant ts(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }

    private static Double dbl(ResultSet rs, String column) throws SQLException {
        Object o = rs.getObject(column);
        return o == null ? null : ((Number) o).doubleValue();
    }

    private static List<String> strings(ResultSet rs, String column) throws SQLException {
        java.sql.Array a = rs.getArray(column);
        if (a == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object o : (Object[]) a.getArray()) {
            out.add(String.valueOf(o));
        }
        return out;
    }

    private Map<String, Object> json(String text) {
        if (text == null) {
            return null;
        }
        try {
            return mapper.readValue(text, MAP);
        } catch (Exception e) {
            return Map.of("unreadable", true);
        }
    }
}
