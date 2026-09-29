package dev.chaya.api.rescan;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.chaya.api.audit.AuditService;
import dev.chaya.api.frame.CoordinateFrameService;
import dev.chaya.api.frame.FrameDtos.FrameView;
import dev.chaya.api.pipeline.PipelineDefinition;
import dev.chaya.api.pipeline.PipelineService;
import dev.chaya.api.processing.JobStage;
import dev.chaya.api.rescan.RescanDtos.RegionGeometry;
import dev.chaya.api.rescan.RescanDtos.RescanInitiated;
import dev.chaya.api.rescan.RescanDtos.RescanRequest;
import dev.chaya.api.security.Actor;
import dev.chaya.api.security.TenantGuard;
import dev.chaya.api.web.ApiException;
import dev.chaya.api.web.NotFoundException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Incremental re-scan orchestration (docs/rescan.md). Owns the two steps the product flow adds on top of
 * an ordinary capture: (1) selecting an existing version and the changed region, which becomes a
 * region-scoped {@code capture_session} the operator uploads media to exactly like any other capture; and
 * (2), once that capture is ready, creating the DRAFT {@code scan_version} and starting the incremental
 * pipeline plan. Everything after that (alignment gating, splicing, conditional navigation/search updates,
 * finalization) happens inside {@link PipelineService}, which is where the worker's reports actually land.
 *
 * <p>The selected region is a polygon in canonical venue metres (docs/coordinate-frames.md), so the parent version's
 * reconstruction must have a canonical coordinate frame: without one the polygon has no defined meaning, and the
 * re-scan is refused (NOT_CALIBRATED) before any capture is created.
 */
@Service
public class RescanService {

    private record ParentVersion(UUID id, UUID floorId, int versionNumber, String status) {}

    private final JdbcClient jdbc;
    private final TenantGuard guard;
    private final AuditService audit;
    private final PipelineService pipeline;
    private final RescanProperties props;
    private final ObjectMapper mapper;
    private final CoordinateFrameService frames;
    private final ScanVersionService versions;

    public RescanService(JdbcClient jdbc, TenantGuard guard, AuditService audit, PipelineService pipeline,
                         RescanProperties props, ObjectMapper mapper, CoordinateFrameService frames, ScanVersionService versions) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.audit = audit;
        this.pipeline = pipeline;
        this.props = props;
        this.mapper = mapper;
        this.frames = frames;
        this.versions = versions;
    }

    /** Steps 1-2: select an existing (FINALIZED) version and the changed region. Creates a region-scoped
     * capture_session the operator then uploads media to exactly like any other capture (steps 3-4 are the
     * ordinary capture + processing-start flow; see {@link #startIncrementalProcessing}). */
    @Transactional
    public RescanInitiated initiate(Actor actor, UUID venueId, UUID floorId, RescanRequest request) {
        guard.requireVenue(actor, venueId);
        requireFloor(venueId, floorId);
        ParentVersion parent = requireFinalizedVersion(actor, venueId, request.parentVersionId());
        if (!parent.floorId().equals(floorId)) {
            throw new ApiException(HttpStatus.CONFLICT, "VERSION_WRONG_FLOOR",
                "the selected version belongs to a different floor than " + floorId);
        }
        FrameView parentFrame = requireCanonicalParentFrame(parent.id());
        double area = requireValidRegion(request.region());
        boolean navigationRebuildRequired = navigationIntersectsRegion(venueId, floorId, request.region().points());

        Map<String, Object> device = request.device() == null ? Map.of() : request.device();
        UUID captureId = jdbc.sql("""
                INSERT INTO capture_session (organization_id, venue_id, floor_id, operator_id, device,
                    parent_scan_version_id, region_geometry)
                VALUES (:o, :v, :f, :op, CAST(:d AS jsonb), :parent, CAST(:region AS jsonb)) RETURNING id
                """)
            .param("o", actor.organizationId()).param("v", venueId).param("f", floorId).param("op", actor.subject())
            .param("d", toJson(device)).param("parent", parent.id()).param("region", toJson(Map.of("points", request.region().points())))
            .query(UUID.class).single();

        audit.success(actor, venueId, "rescan.initiate", "capture_session", captureId, Map.of(
            "floorId", floorId.toString(), "sourceVersionId", parent.id().toString(),
            "regionAreaSquareMeters", area, "navigationRebuildRequired", navigationRebuildRequired,
            "parentCoordinateFrameId", parentFrame.id().toString()));

        return new RescanInitiated(captureId, null, parent.id(), navigationRebuildRequired, area);
    }

    /**
     * Steps 5-9, kicked off once the region's capture is ready: creates the DRAFT ScanVersion (recording
     * its parent, region and processing configuration up front -- docs/rescan.md "VERSIONING") and starts
     * the incremental pipeline plan. Called by {@code CaptureService#startProcessing} for a capture whose
     * {@code parent_scan_version_id} is set, inside the same transaction that moves the capture to
     * PROCESSING and creates its {@code scan} row.
     */
    public UUID startIncrementalProcessing(Actor actor, UUID venueId, UUID captureId, UUID scanId, UUID floorId,
                                           UUID parentVersionId, Map<String, Object> regionGeometry,
                                           Boolean privacyEnabled, Integer timeBudgetSeconds) {
        ParentVersion parent = requireFinalizedVersion(actor, venueId, parentVersionId);
        @SuppressWarnings("unchecked")
        List<List<Number>> points = (List<List<Number>>) regionGeometry.get("points");
        boolean navigationRebuildRequired = navigationIntersectsRegion(venueId, floorId, points);

        LinkedHashMap<String, Object> processingConfig = new LinkedHashMap<>();
        processingConfig.put("navigationRebuildRequired", navigationRebuildRequired);
        processingConfig.put("privacyEnabled", privacyEnabled == null || privacyEnabled);
        if (timeBudgetSeconds != null) {
            processingConfig.put("timeBudgetSeconds", timeBudgetSeconds);
        }

        // The floor's next number, not parent + 1: two re-scans of the same parent are two versions (review V-4).
        UUID scanVersionId = jdbc.sql("""
                INSERT INTO scan_version (organization_id, venue_id, scan_id, floor_id, version_number,
                    parent_version_id, status, region_geometry, processing_config, created_by)
                VALUES (:o, :v, :s, :f, :n, :parent, 'DRAFT', CAST(:region AS jsonb), CAST(:config AS jsonb), :by) RETURNING id
                """)
            .param("o", actor.organizationId()).param("v", venueId).param("s", scanId).param("f", floorId)
            .param("n", versions.nextVersionNumber(floorId)).param("parent", parent.id())
            .param("region", toJson(regionGeometry)).param("config", toJson(processingConfig)).param("by", actor.subject())
            .query(UUID.class).single();

        List<JobStage> plan = PipelineDefinition.incrementalPlan(privacyEnabled == null || privacyEnabled, navigationRebuildRequired);
        UUID runId = pipeline.start(actor, venueId, captureId, scanId, scanVersionId, plan, privacyEnabled, timeBudgetSeconds);
        jdbc.sql("UPDATE scan_version SET pipeline_run_id = :r WHERE id = :v").param("r", runId).param("v", scanVersionId).update();

        audit.success(actor, venueId, "rescan.processing_started", "scan_version", scanVersionId, Map.of(
            "sourceVersionId", parent.id().toString(), "runId", runId.toString(),
            "navigationRebuildRequired", navigationRebuildRequired));
        return runId;
    }

    /** The canonical frame of the reconstruction the parent version's geometry is in (its run's reconstruction frame). */
    private FrameView requireCanonicalParentFrame(UUID parentVersionId) {
        UUID frameRun = jdbc.sql("""
                SELECT coalesce(r.reconstruction_frame_run_id, r.id) FROM pipeline_run r
                  JOIN scan_version v ON v.pipeline_run_id = r.id WHERE v.id = :v
                """).param("v", parentVersionId).query(UUID.class).optional().orElse(null);
        return frames.activeForRun(frameRun).filter(FrameView::canonical)
            .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, CoordinateFrameService.NOT_CALIBRATED,
                "the selected version's reconstruction has no calibrated coordinate frame, so a region in venue metres cannot "
                    + "be located in it; calibrate that reconstruction first"));
    }

    // ---- reading versions / bootstrapping the first one --------------------------------------------

    @Transactional(readOnly = true)
    public List<RescanDtos.ScanVersionView> listVersions(Actor actor, UUID venueId, UUID floorId) {
        guard.requireVenue(actor, venueId);
        requireFloor(venueId, floorId);
        return jdbc.sql("""
                SELECT id, floor_id, scan_id, version_number, parent_version_id, status, region_geometry,
                       alignment_method, alignment_confidence, alignment_residual_m, changed_artifact_kinds,
                       processing_config, finalized_at, created_at, alignment_report, splice_report, created_by, rejected_at,
                       pipeline_run_id, coordinate_frame_id
                  FROM scan_version WHERE venue_id = :v AND floor_id = :f ORDER BY version_number DESC
                """)
            .param("v", venueId).param("f", floorId).query(this::mapVersion).list();
    }

    private RescanDtos.ScanVersionView mapVersion(ResultSet rs, int i) throws SQLException {
        String[] kinds = (String[]) rs.getArray("changed_artifact_kinds").getArray();
        Double confidence = rs.getObject("alignment_confidence") == null ? null : rs.getDouble("alignment_confidence");
        Double residual = rs.getObject("alignment_residual_m") == null ? null : rs.getDouble("alignment_residual_m");
        Timestamp finalizedAt = rs.getTimestamp("finalized_at");
        Timestamp rejectedAt = rs.getTimestamp("rejected_at");
        UUID id = rs.getObject("id", UUID.class);
        return new RescanDtos.ScanVersionView(rs.getObject("id", UUID.class), rs.getObject("floor_id", UUID.class),
            rs.getObject("scan_id", UUID.class), rs.getInt("version_number"), rs.getObject("parent_version_id", UUID.class),
            rs.getString("status"), readJson(rs.getString("region_geometry")), rs.getString("alignment_method"), confidence,
            residual, List.of(kinds), readJson(rs.getString("processing_config")),
            finalizedAt == null ? null : finalizedAt.toInstant(), rs.getTimestamp("created_at").toInstant(),
            readJson(rs.getString("alignment_report")), readJson(rs.getString("splice_report")), rs.getString("created_by"),
            rejectedAt == null ? null : rejectedAt.toInstant(), rs.getObject("pipeline_run_id", UUID.class),
            rs.getObject("coordinate_frame_id", UUID.class),
            versions.pins(id).stream().map(p -> new RescanDtos.PinnedArtifact(p.artifactId(), p.kind(), p.ownerVersionId())).toList());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJson(String jsonb) {
        if (jsonb == null) {
            return null;
        }
        try {
            return mapper.readValue(jsonb, Map.class);
        } catch (JsonProcessingException e) {
            return Map.of("unreadable", true);
        }
    }

    /**
     * Formalizes the floor's latest successful full-venue reconstruction as a FINALIZED version, so there is something to
     * select as the source of a re-scan. It does not fabricate one: the version is that run, pinned to the run's own
     * published artifacts (viewer asset, cloud, planes, navmesh, detections) and to the ACTIVE canonical frame of its
     * reconstruction (ScanVersionService#finalizeVersion) -- an uncalibrated reconstruction, or one with no viewer asset,
     * is refused. The version takes the floor's next number and has no parent: a new full reconstruction is not derived
     * from an earlier version's geometry. The run's navigation graphs baked in that frame, its detected POIs, and the MANUAL
     * POIs and anchors already placed in that reconstruction's frames are bound to the new version. Idempotent per run.
     */
    @Transactional
    public RescanDtos.ScanVersionView finalizeCurrent(Actor actor, UUID venueId, UUID floorId) {
        guard.requireVenue(actor, venueId);
        requireFloor(venueId, floorId);
        record LatestRun(UUID scanId, UUID runId) {}
        LatestRun latest = jdbc.sql("""
                SELECT r.scan_id, r.id AS run_id FROM pipeline_run r JOIN capture_session c ON c.id = r.capture_session_id
                 WHERE c.venue_id = :v AND c.floor_id = :f AND r.status = 'SUCCEEDED' AND r.scan_version_id IS NULL
                 ORDER BY r.finished_at DESC LIMIT 1
                """)
            .param("v", venueId).param("f", floorId)
            .query((rs, i) -> new LatestRun(rs.getObject("scan_id", UUID.class), rs.getObject("run_id", UUID.class)))
            .optional().orElseThrow(() -> new NotFoundException(
                "no successful full-venue reconstruction exists yet for this floor to finalize as a version"));

        int number = versions.nextVersionNumber(floorId);
        UUID existing = jdbc.sql("SELECT id FROM scan_version WHERE pipeline_run_id = :r").param("r", latest.runId())
            .query(UUID.class).optional().orElse(null);
        UUID versionId;
        if (existing != null) {
            versionId = existing;
        } else {
            versionId = jdbc.sql("""
                    INSERT INTO scan_version (organization_id, venue_id, scan_id, floor_id, version_number, status,
                        pipeline_run_id, created_by)
                    VALUES (:o, :v, :s, :f, :n, 'DRAFT', :r, :by) RETURNING id
                    """)
                .param("o", actor.organizationId()).param("v", venueId).param("s", latest.scanId()).param("f", floorId)
                .param("n", number).param("r", latest.runId()).param("by", actor.subject())
                .query(UUID.class).single();
            UUID frameId = versions.finalizeVersion(versionId, Map.of("bootstrap", true, "runId", latest.runId().toString()));
            int graphs = jdbc.sql("""
                    UPDATE navigation_graph SET scan_version_id = :sv
                     WHERE pipeline_run_id = :r AND scan_version_id IS NULL AND coordinate_frame_id = :frame
                    """).param("sv", versionId).param("r", latest.runId()).param("frame", frameId).update();
            // MANUAL POIs and anchors carry the frame they were placed in; a frame of this reconstruction ties them to it.
            int pois = jdbc.sql("""
                    UPDATE poi_version SET scan_version_id = :sv
                     WHERE scan_version_id IS NULL AND venue_id = :v
                       AND (pipeline_run_id = :r
                            OR (source = 'MANUAL' AND coordinate_frame_id IN (SELECT id FROM coordinate_frame WHERE source_run_id = :r)))
                    """).param("sv", versionId).param("v", venueId).param("r", latest.runId()).update();
            int anchors = jdbc.sql("""
                    UPDATE ar_anchor SET scan_version_id = :sv
                     WHERE scan_version_id IS NULL AND floor_id = :f AND deleted_at IS NULL
                       AND coordinate_frame_id IN (SELECT id FROM coordinate_frame WHERE source_run_id = :r)
                    """).param("sv", versionId).param("f", floorId).param("r", latest.runId()).update();
            audit.success(actor, venueId, "rescan.version_bootstrapped", "scan_version", versionId, Map.of(
                "floorId", floorId.toString(), "runId", latest.runId().toString(), "versionNumber", number,
                "coordinateFrameId", frameId.toString(), "navigationGraphsBound", graphs, "poiVersionsBound", pois,
                "anchorsBound", anchors));
        }
        return listVersions(actor, venueId, floorId).stream().filter(v -> v.id().equals(versionId)).findFirst().orElseThrow();
    }

    // ---- validation -------------------------------------------------------------------------------

    private void requireFloor(UUID venueId, UUID floorId) {
        Integer count = jdbc.sql("SELECT count(*) FROM floor WHERE id = :f AND venue_id = :v AND deleted_at IS NULL")
            .param("f", floorId).param("v", venueId).query(Integer.class).single();
        if (count == 0) {
            throw new NotFoundException("floor not found");
        }
    }

    private ParentVersion requireFinalizedVersion(Actor actor, UUID venueId, UUID versionId) {
        ParentVersion version = jdbc.sql("SELECT id, floor_id, version_number, status FROM scan_version WHERE id = :id AND venue_id = :v")
            .param("id", versionId).param("v", venueId)
            .query((rs, i) -> new ParentVersion(rs.getObject("id", UUID.class), rs.getObject("floor_id", UUID.class),
                rs.getInt("version_number"), rs.getString("status")))
            .optional().orElseThrow(() -> new NotFoundException("scan version not found"));
        if (!"FINALIZED".equals(version.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "VERSION_NOT_FINALIZED",
                "only a FINALIZED version can be selected as the source of a re-scan (this one is " + version.status() + ")");
        }
        return version;
    }

    private double requireValidRegion(RegionGeometry region) {
        double area = PolygonGeometry.area(region.points());
        if (area < props.minRegionAreaSquareMeters()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "REGION_TOO_SMALL",
                "the selected region is " + area + " m^2, below the minimum of " + props.minRegionAreaSquareMeters() + " m^2");
        }
        if (area > props.maxRegionAreaSquareMeters()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "REGION_TOO_LARGE",
                "the selected region is " + area + " m^2, above the maximum of " + props.maxRegionAreaSquareMeters()
                    + " m^2 for an incremental re-scan; reconstruct the venue instead");
        }
        return area;
    }

    /** "Only affected regions require navigation updates where technically possible" (docs/rescan.md
     * "NAVIGATION"): true only when the floor's current STANDARD routing graph actually has geometry inside
     * the selected region. No graph at all, or a graph entirely outside the region, means nothing there
     * needs to change, so NAVIGATION_BAKING is left out of the plan entirely (see PipelineDefinition). */
    private boolean navigationIntersectsRegion(UUID venueId, UUID floorId, List<? extends List<? extends Number>> polygon) {
        List<double[]> nodes = jdbc.sql("""
                SELECT n.x, n.y FROM navigation_node n JOIN navigation_graph g ON g.id = n.graph_id
                 WHERE g.venue_id = :v AND g.floor_id = :f AND g.profile = 'STANDARD' AND g.status = 'ACTIVE'
                """)
            .param("v", venueId).param("f", floorId)
            .query((rs, i) -> new double[]{rs.getDouble("x"), rs.getDouble("y")}).list();
        for (double[] node : nodes) {
            if (PolygonGeometry.pointInPolygon(node[0], node[1], polygon)) {
                return true;
            }
        }
        return false;
    }

    private String toJson(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_JSON", "value is not serializable");
        }
    }
}
