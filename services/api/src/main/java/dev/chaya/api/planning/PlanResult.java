package dev.chaya.api.planning;

import java.util.List;

/** Everything the planner returns. All areas are m2, distances m, times s, angles degrees, percentages 0..100. */
public record PlanResult(
    List<Waypoint> waypoints,
    List<PathPoint> route,
    double estimatedDistanceMeters,
    double estimatedCaptureSeconds,
    Confidence confidence,
    CoverageMetrics baseline,
    CoverageMetrics planned,
    Improvement improvement,
    List<UncoveredRegion> uncoveredRegions,
    List<RejectedCandidate> rejectedCandidates,
    Diagnostics diagnostics) {

    /**
     * @param type                  CORNER, DOORWAY, OCCLUSION, BOUNDARY or COVERAGE, derived from what the waypoint measurably adds
     * @param expectedCoverageGainM2 covered area this waypoint (and the leg that leads to it) adds, in route order
     */
    public record Waypoint(int order, double x, double y, double yawDegrees, String type, String reason,
                           double expectedCoverageGainM2, double expectedCoverageGainPercentPoints,
                           double legDistanceMeters, double cumulativeDistanceMeters, double cumulativeCaptureSeconds) {}

    public record PathPoint(double x, double y) {}

    /** A heuristic score from 0 to 1, not a calibrated probability. See docs/route-planning.md. */
    public record Confidence(double score, double trajectoryEvidence, double reachableFraction, double plannedCoverage, String note) {}

    /**
     * @param coveragePercent          share of the target area that is adequately covered (the headline metric)
     * @param weightedCoveragePercent  the same, weighted by cell importance and counting partial coverage
     * @param redundantCapturePercent  share of all observation quality beyond what coverage needed
     * @param pathLengthMeters         total walked path considered (recon lap plus, for the plan, the secondary route)
     */
    public record CoverageMetrics(double coveragePercent, double weightedCoveragePercent, double coveredAreaM2, double uncoveredAreaM2,
                                  double targetAreaM2, double redundantCapturePercent, int poseCount, double pathLengthMeters,
                                  int waypointCount) {}

    public record Improvement(double coveragePercentPoints, double coveredAreaM2, double uncoveredAreaReductionM2,
                              double redundantCapturePercentPoints, double addedRouteMeters, int addedWaypoints) {}

    /** @param reason NOT_OBSERVABLE_FROM_EVALUATED_VIEWPOINTS (no evaluated reachable viewpoint sees it) or BELOW_COVERAGE_TARGET */
    public record UncoveredRegion(double areaM2, double centroidX, double centroidY, double minX, double minY, double maxX, double maxY,
                                  String reason) {}

    public record RejectedCandidate(double x, double y, String label, String reason) {}

    public record Diagnostics(int gridColumns, int gridRows, double resolution, double walkableAreaM2, double reachableAreaM2,
                              int candidatesGenerated, int candidatesEvaluated, String stopReason, double constructionCoveragePercent,
                              double constructionRouteMeters, int trajectoryInputSamples,
                              int trajectoryUsedPoses, double trajectoryLengthMeters, String yawSource, List<String> warnings) {}
}
