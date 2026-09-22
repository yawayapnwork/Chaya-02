package dev.chaya.api.hud;

import dev.chaya.api.planning.api.RoutePlanDtos.SceneDto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Wire format for the live capture HUD. Coordinates are metres in the same plan view as route-plan
 * (x east, y north); angles are degrees. Nothing here is a raw camera frame: quality signals are computed in
 * the browser from the live frame and only the resulting numbers are ever sent.
 */
public final class CaptureHudDtos {

    private CaptureHudDtos() {}

    /** Establishes (or replaces) the room outline the HUD measures coverage against. */
    public record SetSceneRequest(@NotNull @Valid SceneDto scene, Map<String, Double> config) {}

    /**
     * One reported device pose. {@code capturedAtMs} is the client's wall-clock time (epoch millis); it is what
     * decides staleness. {@code source} names where the position came from, for example "manual" (the operator
     * marking their spot on the floor plan) so a future on-device SLAM-lite source can be told apart later.
     */
    public record PoseSampleDto(long capturedAtMs, double x, double y, Double yawDegrees, String source) {}

    public record PoseBatchRequest(@NotEmpty List<@Valid PoseSampleDto> samples) {}

    /**
     * One summarised frame-quality sample. All of blur/motion/exposure/feature signals and their warnings are
     * computed client-side (see apps/web/lib/capture-quality.ts); the server only aggregates them.
     */
    public record QualitySampleDto(long capturedAtMs, double blurScore, double brightnessMean,
                                   double shadowClipFraction, double highlightClipFraction, Double motionScore,
                                   boolean duplicateFrame, int featureCount, Double spacingMeters,
                                   List<String> warnings) {}

    public record QualityBatchRequest(@NotEmpty List<@Valid QualitySampleDto> samples) {}

    // ---- status --------------------------------------------------------------------------------

    public record PositionView(double x, double y, Double headingDegrees, long asOfMs) {}

    public record PathPointView(double x, double y) {}

    /** @param reason NOT_OBSERVABLE_FROM_EVALUATED_VIEWPOINTS or BELOW_COVERAGE_TARGET, as in PlanResult */
    public record UncoveredZoneView(double centroidX, double centroidY, double areaM2, String reason) {}

    public record PlannedWaypointView(int order, double x, double y, double yawDegrees, String type, String reason,
                                      double expectedCoverageGainM2) {}

    public record QualitySummaryView(int sampleCount, double avgBlurScore, double avgBrightnessMean,
                                     double duplicateFrameRate, double avgFeatureCount, Double avgSpacingMeters,
                                     long lastSampleAtMs) {}

    public record ReshootRecommendationView(String reason, Double x, Double y) {}

    public record HudStatus(
        boolean trackingAvailable, String trackingUnavailableReason, PositionView currentPosition,
        List<PathPointView> pathSoFar,
        boolean sceneSet, boolean coverageAvailable, String coverageUnavailableReason,
        Double coveragePercent, Double weightedCoveragePercent, Double uncoveredAreaM2,
        List<UncoveredZoneView> uncoveredZones, List<PlannedWaypointView> plannedPath,
        QualitySummaryView quality,
        List<String> warnings, List<ReshootRecommendationView> reshootRecommendations,
        Instant computedAt) {}
}
