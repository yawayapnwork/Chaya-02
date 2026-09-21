package dev.chaya.api.planning;

/**
 * Every tunable of the capture-path planner. All values are deterministic inputs: the same scene, trajectory
 * and configuration always produce the same route. See docs/route-planning.md for what each one means.
 *
 * @param resolution              grid cell size, metres
 * @param agentRadius             clearance the operator needs from any wall or obstacle, metres
 * @param targetDistance          D: the ideal camera-to-surface distance, metres
 * @param fovDegrees              horizontal camera field of view
 * @param minSeparation           two waypoints are never closer than this, metres
 * @param headingCount            candidate viewing directions tried at each viewpoint
 * @param maxWaypoints            hard cap on route length in waypoints
 * @param stopCoverage            stop adding waypoints once this fraction (0..1) of the target area is covered
 * @param minMarginalGain         stop when the best remaining waypoint adds less than this weighted area, m2
 * @param requiredObservationMass A0: observation quality a surface cell needs to count as covered
 * @param minTriangulationDegrees gamma0: widest angle between two views of a cell needed to count as covered
 * @param minObservationQuality   observations with lower quality than this are ignored
 * @param redundancyPenalty       rho: how strongly re-observing already covered cells is penalised (0..1)
 * @param travelWeight            beta factor: trade-off between gain and walking distance (in target distances)
 * @param walkSpeedMetersPerSecond speed while capturing on the move
 * @param dwellSeconds            time spent at each waypoint
 * @param poseSpacing             spacing of resampled trajectory and route poses, metres
 * @param maxSpeedMetersPerSecond trajectory jumps faster than this are treated as tracking glitches
 * @param cornerWeight            importance multiplier for cells near corners
 * @param doorwayWeight           importance multiplier for cells near doorways
 * @param boundaryWeight          importance multiplier for wall and obstacle surfaces and cells beside them
 * @param maxCandidates           upper bound on candidate viewpoints evaluated
 * @param maxGridCells            largest grid the planner accepts
 */
public record PlannerConfig(
    double resolution,
    double agentRadius,
    double targetDistance,
    double fovDegrees,
    double minSeparation,
    int headingCount,
    int maxWaypoints,
    double stopCoverage,
    double minMarginalGain,
    double requiredObservationMass,
    double minTriangulationDegrees,
    double minObservationQuality,
    double redundancyPenalty,
    double travelWeight,
    double walkSpeedMetersPerSecond,
    double dwellSeconds,
    double poseSpacing,
    double maxSpeedMetersPerSecond,
    double cornerWeight,
    double doorwayWeight,
    double boundaryWeight,
    int maxCandidates,
    int maxGridCells) {

    public PlannerConfig {
        require(resolution >= 0.05 && resolution <= 2, "resolution", "must be between 0.05 and 2 metres");
        require(agentRadius >= 0 && agentRadius <= 2, "agentRadius", "must be between 0 and 2 metres");
        require(targetDistance >= 0.3 && targetDistance <= 20, "targetDistance", "must be between 0.3 and 20 metres");
        require(fovDegrees >= 20 && fovDegrees <= 180, "fovDegrees", "must be between 20 and 180 degrees");
        require(minSeparation >= 0 && minSeparation <= 50, "minSeparation", "must be between 0 and 50 metres");
        require(headingCount >= 1 && headingCount <= 72, "headingCount", "must be between 1 and 72");
        require(maxWaypoints >= 0 && maxWaypoints <= 200, "maxWaypoints", "must be between 0 and 200");
        require(stopCoverage > 0 && stopCoverage <= 1, "stopCoverage", "must be in (0, 1]");
        require(minMarginalGain >= 0, "minMarginalGain", "must not be negative");
        require(requiredObservationMass > 0 && requiredObservationMass <= 10, "requiredObservationMass", "must be in (0, 10]");
        require(minTriangulationDegrees >= 0 && minTriangulationDegrees <= 90, "minTriangulationDegrees", "must be between 0 and 90");
        require(minObservationQuality >= 0 && minObservationQuality < 1, "minObservationQuality", "must be in [0, 1)");
        require(redundancyPenalty >= 0 && redundancyPenalty <= 1, "redundancyPenalty", "must be in [0, 1]");
        require(travelWeight >= 0, "travelWeight", "must not be negative");
        require(walkSpeedMetersPerSecond > 0, "walkSpeedMetersPerSecond", "must be positive");
        require(dwellSeconds >= 0, "dwellSeconds", "must not be negative");
        require(poseSpacing >= resolution / 2, "poseSpacing", "must be at least half the grid resolution");
        require(maxSpeedMetersPerSecond > 0, "maxSpeedMetersPerSecond", "must be positive");
        require(cornerWeight >= 1 && doorwayWeight >= 1 && boundaryWeight >= 1, "weights", "importance multipliers must be at least 1");
        require(maxCandidates >= 1 && maxCandidates <= 5000, "maxCandidates", "must be between 1 and 5000");
        require(maxGridCells >= 100, "maxGridCells", "must be at least 100");
    }

    private static void require(boolean ok, String field, String message) {
        if (!ok) {
            throw new PlanningException("INVALID_CONFIG", "config." + field + " " + message);
        }
    }

    public static PlannerConfig defaults() {
        return new PlannerConfig(0.25, 0.30, 2.0, 70, 1.0, 8, 20, 0.95, 0.05, 1.5, 10, 0.2, 0.5, 1.0, 0.5, 3.0, 0.25, 3.0,
            2.0, 2.0, 1.3, 600, 250_000);
    }

    public double fovRadians() {
        return Math.toRadians(fovDegrees);
    }

    public double minTriangulationRadians() {
        return Math.toRadians(minTriangulationDegrees);
    }

    /** Shortest camera-to-surface distance that still meets the minimum observation quality. */
    public double minRange() {
        return targetDistance * minObservationQuality;
    }

    /** Longest camera-to-surface distance that still meets the minimum observation quality. */
    public double maxRange() {
        return targetDistance * (2 - minObservationQuality);
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    /** Mutable copy for overriding a few fields: `PlannerConfig.defaults().toBuilder().targetDistance(1.5).build()`. */
    public static final class Builder {
        private double resolution, agentRadius, targetDistance, fovDegrees, minSeparation, stopCoverage, minMarginalGain;
        private double requiredObservationMass, minTriangulationDegrees, minObservationQuality, redundancyPenalty, travelWeight;
        private double walkSpeed, dwellSeconds, poseSpacing, maxSpeed, cornerWeight, doorwayWeight, boundaryWeight;
        private int headingCount, maxWaypoints, maxCandidates, maxGridCells;

        private Builder(PlannerConfig c) {
            resolution = c.resolution; agentRadius = c.agentRadius; targetDistance = c.targetDistance; fovDegrees = c.fovDegrees;
            minSeparation = c.minSeparation; headingCount = c.headingCount; maxWaypoints = c.maxWaypoints;
            stopCoverage = c.stopCoverage; minMarginalGain = c.minMarginalGain; requiredObservationMass = c.requiredObservationMass;
            minTriangulationDegrees = c.minTriangulationDegrees; minObservationQuality = c.minObservationQuality;
            redundancyPenalty = c.redundancyPenalty; travelWeight = c.travelWeight; walkSpeed = c.walkSpeedMetersPerSecond;
            dwellSeconds = c.dwellSeconds; poseSpacing = c.poseSpacing; maxSpeed = c.maxSpeedMetersPerSecond;
            cornerWeight = c.cornerWeight; doorwayWeight = c.doorwayWeight; boundaryWeight = c.boundaryWeight;
            maxCandidates = c.maxCandidates; maxGridCells = c.maxGridCells;
        }

        public Builder resolution(double v) { resolution = v; return this; }
        public Builder agentRadius(double v) { agentRadius = v; return this; }
        public Builder targetDistance(double v) { targetDistance = v; return this; }
        public Builder fovDegrees(double v) { fovDegrees = v; return this; }
        public Builder minSeparation(double v) { minSeparation = v; return this; }
        public Builder headingCount(int v) { headingCount = v; return this; }
        public Builder maxWaypoints(int v) { maxWaypoints = v; return this; }
        public Builder stopCoverage(double v) { stopCoverage = v; return this; }
        public Builder minMarginalGain(double v) { minMarginalGain = v; return this; }
        public Builder requiredObservationMass(double v) { requiredObservationMass = v; return this; }
        public Builder minTriangulationDegrees(double v) { minTriangulationDegrees = v; return this; }
        public Builder minObservationQuality(double v) { minObservationQuality = v; return this; }
        public Builder redundancyPenalty(double v) { redundancyPenalty = v; return this; }
        public Builder travelWeight(double v) { travelWeight = v; return this; }
        public Builder walkSpeedMetersPerSecond(double v) { walkSpeed = v; return this; }
        public Builder dwellSeconds(double v) { dwellSeconds = v; return this; }
        public Builder poseSpacing(double v) { poseSpacing = v; return this; }
        public Builder maxSpeedMetersPerSecond(double v) { maxSpeed = v; return this; }
        public Builder cornerWeight(double v) { cornerWeight = v; return this; }
        public Builder doorwayWeight(double v) { doorwayWeight = v; return this; }
        public Builder boundaryWeight(double v) { boundaryWeight = v; return this; }
        public Builder maxCandidates(int v) { maxCandidates = v; return this; }
        public Builder maxGridCells(int v) { maxGridCells = v; return this; }

        public PlannerConfig build() {
            return new PlannerConfig(resolution, agentRadius, targetDistance, fovDegrees, minSeparation, headingCount, maxWaypoints,
                stopCoverage, minMarginalGain, requiredObservationMass, minTriangulationDegrees, minObservationQuality,
                redundancyPenalty, travelWeight, walkSpeed, dwellSeconds, poseSpacing, maxSpeed, cornerWeight, doorwayWeight,
                boundaryWeight, maxCandidates, maxGridCells);
        }
    }
}
