package dev.chaya.api.hud;

import dev.chaya.api.planning.CapturePathPlanner;
import dev.chaya.api.planning.PlanResult;
import dev.chaya.api.planning.Reconnaissance;
import dev.chaya.api.planning.RoomBoundsEstimator;
import dev.chaya.api.planning.api.RoutePlanDtos.PointDto;
import dev.chaya.api.planning.api.RoutePlanDtos.SceneDto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Wire format for the live capture HUD and scan planning. Coordinates are metres in the same plan view as route-plan
 * (x east, y north) and, for tracked sources, in the tracking frame named by {@code frameId}; angles are degrees.
 * Nothing here is a raw camera frame: quality signals are computed in the browser and only the numbers are sent.
 */
public final class CaptureHudDtos {

    private CaptureHudDtos() {}

    /**
     * Establishes (or replaces) the room outline the HUD measures coverage against, as the operator measured it.
     *
     * @param frameId the tracking frame the outline is placed in, when the operator aligned it to one; otherwise the
     *                server names a new plan frame, and positions must then be posted in that frame (manual marks, or
     *                tracked positions the client aligned to it)
     */
    public record SetSceneRequest(@NotNull @Valid SceneDto scene, Map<String, Double> config, String frameId) {}

    /**
     * One reported device position. {@code capturedAtMs} is the client's wall-clock time (epoch millis); it decides
     * staleness and ordering.
     *
     * @param source         webxr, arkit (platform world tracking), operator-trajectory (imported) or manual (a spot the
     *                       operator marked on the plan). Missing means manual. Video is not a position source.
     * @param frameId        the tracking frame of x/y; required for webxr and arkit (one per AR session)
     * @param frameAlignment NATIVE (the tracker's own frame) or OPERATOR_ALIGNED (transformed into the frame by an
     *                       alignment the operator made, see docs/capture-hud.md)
     */
    public record PoseSampleDto(long capturedAtMs, double x, double y, Double yawDegrees, String source, String frameId,
                                String frameAlignment) {}

    public record PoseBatchRequest(@NotEmpty List<@Valid PoseSampleDto> samples) {}

    /**
     * One surface point the device's tracker reported (a hit test against a detected plane), and where the camera was.
     *
     * @param kind          WALL (vertical surface) or FLOOR
     * @param normalDegrees plan-view direction the surface faces, when known
     */
    public record SurfaceSampleDto(long capturedAtMs, double originX, double originY, double hitX, double hitY, String kind,
                                   Double normalDegrees, String source, String frameId) {}

    public record SurfaceBatchRequest(@NotEmpty List<@Valid SurfaceSampleDto> samples) {}

    /**
     * A tracking state change or session problem observed by the client.
     *
     * @param state  SESSION_STARTED, TRACKING, LIMITED, LOST, INTERRUPTED, RESUMED, SESSION_ENDED, PERMISSION_DENIED,
     *               UNSUPPORTED, ORIENTATION_CHANGED or REALIGNED
     * @param source webxr, arkit, manual, camera or operator-trajectory
     */
    public record TrackingEventDto(long capturedAtMs, String state, String source, String frameId, String detail) {}

    public record TrackingEventBatchRequest(@NotEmpty List<@Valid TrackingEventDto> events) {}

    /**
     * One summarised frame-quality sample. All of blur/motion/exposure/feature signals and their warnings are
     * computed client-side (see apps/web/lib/capture-quality.ts); the server only aggregates them.
     */
    public record QualitySampleDto(long capturedAtMs, double blurScore, double brightnessMean,
                                   double shadowClipFraction, double highlightClipFraction, Double motionScore,
                                   boolean duplicateFrame, int featureCount, Double spacingMeters,
                                   List<String> warnings) {}

    public record QualityBatchRequest(@NotEmpty List<@Valid QualitySampleDto> samples) {}

    /** @param frameId the tracking frame to estimate in; omitted: the frame of the most recent tracked position */
    public record EstimateBoundsRequest(String frameId) {}

    /** @param config optional planner overrides by name, as for route-plan */
    public record CreatePlanRequest(Map<String, Double> config) {}

    // ---- bounds --------------------------------------------------------------------------------------------------

    public record FrontierView(List<PointDto> polyline, double lengthMeters, double midX, double midY, double outwardDegrees) {}

    /**
     * A room-bounds estimate (RoomBoundsEstimator), as stored with the scene and with every plan.
     *
     * @param status ESTIMATED, LOW_CONFIDENCE (a lower bound: part of the boundary was not observed), MEASURED or NOT_ESTIMABLE
     */
    public record BoundsView(String status, String basis, String frameId, List<PointDto> area, List<List<PointDto>> obstacles,
                             List<List<PointDto>> unobservedInterior, List<FrontierView> frontier, double confidence,
                             RoomBoundsEstimator.ConfidenceParts confidenceParts, double areaM2, double observedBoundaryMeters,
                             double frontierMeters, RoomBoundsEstimator.Evidence evidence, List<String> assumptions,
                             List<RoomBoundsEstimator.Issue> issues) {}

    /** @param stored whether the estimate replaced the capture's room outline (false when NOT_ESTIMABLE) */
    public record EstimateBoundsResponse(boolean stored, BoundsView bounds, Reconnaissance.Assessment reconnaissance) {}

    // ---- plans ---------------------------------------------------------------------------------------------------

    /**
     * Which stored evidence a plan was computed from.
     *
     * @param poseSamplesBySource used positions per source; @param maxPoseSampleId and the other ids are high-water marks
     */
    public record PlanEvidence(Map<String, Integer> poseSamplesBySource, int surfaceSamples, int trackingEvents, int qualitySamples,
                               long maxPoseSampleId, long maxSurfaceSampleId, long maxTrackingEventId, long maxQualitySampleId,
                               Long firstCapturedAtMs, Long lastCapturedAtMs, String frameId, int excludedOtherFramePositions) {}

    /**
     * A persisted scan plan.
     *
     * @param predicted      the planner's result: its waypoints, route and {@code planned} coverage are PREDICTED by the
     *                       coverage model, not measured
     * @param observedAtPlan coverage of the positions recorded before the plan, by the same model (OBSERVED)
     */
    public record PlanView(int version, Instant createdAt, String createdBy, Instant supersededAt, String sceneSource, String frameId,
                           SceneDto scene, BoundsView bounds, Reconnaissance.Assessment reconnaissance, PlanEvidence evidence,
                           PlanResult predicted, PlanResult.CoverageMetrics observedAtPlan, long plannedAfterMs) {}

    /** The current plan and how far the operator has got along it. */
    public record PlanWithProgress(PlanView plan, CapturePathPlanner.Progress progress) {}

    /**
     * @param coverageKind always OBSERVED: the coverage model applied to positions actually recorded
     */
    public record ObservedCoverage(String coverageKind, String basis, PlanResult.CoverageMetrics metrics, int trajectorySegments,
                                   double trackedMeters, double interpolatedMeters, double importedMeters) {}

    public record PlanSummary(int version, Instant createdAt, String createdBy, Instant supersededAt, String sceneSource,
                              String coverageKind, double predictedCoveragePercent, double observedAtPlanCoveragePercent,
                              int waypoints, double routeMeters) {}

    /** For audit and processing tools: every plan, what evidence exists, and observed coverage kept apart from predicted. */
    public record EvidenceReport(Map<String, Integer> poseSamplesBySource, Map<String, Integer> surfaceSamplesByKind,
                                 Map<String, Integer> trackingEventsByState, int qualitySamples, List<String> frames,
                                 String sceneSource, String sceneFrameId, BoundsView bounds, Reconnaissance.Assessment reconnaissance,
                                 ObservedCoverage observedNow, List<PlanSummary> plans, CapturePathPlanner.Progress currentPlanProgress,
                                 Map<String, String> definitions) {}

    // ---- status --------------------------------------------------------------------------------------------------

    /**
     * @param kind TRACKED (platform world tracking), MANUAL (marked by the operator), IMPORTED (operator trajectory)
     */
    public record PositionView(double x, double y, Double headingDegrees, long asOfMs, String source, String kind) {}

    /**
     * A recorded position. Consecutive points with the same segment are joined; {@code kind} says how the stretch that
     * ends at this point is known (TRACKED, IMPORTED, or INTERPOLATED between manual marks; START at a segment's start).
     */
    public record PathPointView(double x, double y, int segment, String kind) {}

    /** @param reason NOT_OBSERVED or INSUFFICIENTLY_OBSERVED (live), as in CapturePathPlanner.Assessment */
    public record UncoveredZoneView(double centroidX, double centroidY, double areaM2, String reason) {}

    /** @param status VISITED, SKIPPED, PENDING, UNREACHABLE for a persisted plan; SUGGESTED for a live suggestion */
    public record PlannedWaypointView(int order, double x, double y, double yawDegrees, String type, String reason,
                                      double expectedCoverageGainM2, String status) {}

    public record NextWaypointView(int order, double x, double y, double yawDegrees, String type, String reason, Double walkMeters,
                                   List<PathPointView> leg) {}

    public record QualitySummaryView(int sampleCount, double avgBlurScore, double avgBrightnessMean,
                                     double duplicateFrameRate, double avgFeatureCount, Double avgSpacingMeters,
                                     long lastSampleAtMs) {}

    public record ReshootRecommendationView(String reason, Double x, Double y) {}

    /** @param kind PREDICTED for a plan's expectation */
    public record PlanStatusView(int version, Instant createdAt, String coverageKind, double predictedCoveragePercent,
                                 double observedAtPlanCoveragePercent, int waypoints, int visited, int unreachable) {}

    /**
     * @param trackingState      NOT_STARTED, TRACKING, STALE, LIMITED, LOST, INTERRUPTED, SESSION_ENDED, FRAME_MISMATCH,
     *                           PERMISSION_DENIED, UNSUPPORTED, MANUAL (positions are marks) or IMPORTED_ONLY
     * @param coverageBasis      LIVE_TRACKED (fresh tracked positions), TRACKED_NOT_LIVE, MANUAL_MARKS, IMPORTED_TRAJECTORY,
     *                           MIXED or NONE: what the coverage figure is computed from
     * @param coverageLive       true only when coverage is computed from tracked positions and tracking is live right now
     * @param plannedPathKind    PERSISTED_PLAN, LIVE_SUGGESTION or NONE
     * @param completion         NO_PLAN, NOT_STARTED, IN_PROGRESS, ROUTE_COMPLETE or COVERAGE_TARGET_REACHED
     * @param qualityFlags       warnings shared by most of the recent frames (sustained, not one bad frame)
     */
    public record HudStatus(
        boolean trackingAvailable, String trackingUnavailableReason, PositionView currentPosition,
        List<PathPointView> pathSoFar,
        boolean sceneSet, boolean coverageAvailable, String coverageUnavailableReason,
        Double coveragePercent, Double weightedCoveragePercent, Double uncoveredAreaM2,
        List<UncoveredZoneView> uncoveredZones, List<PlannedWaypointView> plannedPath,
        QualitySummaryView quality,
        List<String> warnings, List<ReshootRecommendationView> reshootRecommendations,
        Instant computedAt,
        String trackingState, String sceneSource, String sceneFrameId, SceneDto scene, BoundsView bounds,
        Reconnaissance.Assessment reconnaissance, String coverageBasis, boolean coverageLive, String coverageBasisDetail,
        String plannedPathKind, PlanStatusView plan, NextWaypointView nextWaypoint, String completion,
        int excludedOtherFramePositions, List<String> qualityFlags, Long lastTrackingEventAtMs, String lastTrackingEvent) {}
}
