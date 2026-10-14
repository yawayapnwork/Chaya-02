package dev.chaya.api.navigation;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Tunables for RouteService. None of them is measured by Chaya; each is an assumed planning constant, documented as
 * such in docs/navigation.md:
 * <ul>
 *   <li>standardWalkingSpeedMps ~1.3 m/s, the typical adult walking speed used in transport planning;
 *       accessibleWalkingSpeedMps ~1.0 m/s, the slower pace assumed for accessible/assisted travel.</li>
 *   <li>stairsSpeedMps ~0.5 m/s along a stair flight's walked length (pedestrian-planning figures for ascent).</li>
 *   <li>elevatorTransitionSeconds ~45 s per elevator ride (waiting plus travel); an elevator is never walked.</li>
 *   <li>minAccessibleClearanceM 0.9 m approximates the ADA minimum clear width (32 in ~= 0.81 m) with a margin.</li>
 *   <li>maxAccessibleSlopeDeg ~4.76 degrees is the ADA 1:12 ramp limit.</li>
 * </ul>
 *
 * <p>Request handling, not planning constants:
 * <ul>
 *   <li>obstacleMaxAgeSeconds (120): a reported obstacle observed longer ago than this is rejected, not routed around --
 *       the client saw something that may have moved since. obstacleMaxFutureSkewSeconds (10): an observation stamped
 *       further in the future than this is rejected too (a wrong device clock).</li>
 *   <li>endpointBoundsMarginM (1.0): how far outside the horizontal footprint of a floor's navmesh graph (its polygon
 *       centroids and portals) a start or destination may lie before it is refused as out of bounds.</li>
 *   <li>agentRadiusM (0.35): the walking body's radius, the same as the worker's navmesh_agent_radius_m. Where a
 *       reported obstacle reaches a navmesh portal, the edge stays open only if the opening left there is at least twice
 *       this wide (STEP_FREE: at least the accessible clear width); see RouteService#freePortal.</li>
 *   <li>maxStops (10): the most destinations one multi-stop request may name.</li>
 *   <li>graphCacheMaxGraphs (64): how many navigation graphs' nodes and edges are kept in memory. A graph's content is
 *       immutable once it leaves DRAFT (V6), so a cached copy keyed by its id can never be stale.</li>
 * </ul>
 *
 * <p>acceptSyntheticGraphs (default false, and never set in application.yml) lets RouteService route on a
 * navigation_graph that was not derived from a Recast navmesh (source SYNTHETIC, V18) -- hand-built test fixtures.
 * Production refuses those with NAVMESH_NOT_READY; see RouteService. */
@ConfigurationProperties("chaya.navigation")
public record NavigationProperties(double standardWalkingSpeedMps, double accessibleWalkingSpeedMps, double stairsSpeedMps,
                                   double minAccessibleClearanceM, double maxAccessibleSlopeDeg,
                                   double elevatorTransitionSeconds, double nodeSnapMaxDistanceMeters,
                                   boolean acceptSyntheticGraphs, double obstacleMaxAgeSeconds, double obstacleMaxFutureSkewSeconds,
                                   double endpointBoundsMarginM, int maxStops, int graphCacheMaxGraphs, double agentRadiusM) {

    public NavigationProperties {
        if (standardWalkingSpeedMps <= 0) standardWalkingSpeedMps = 1.3;
        if (accessibleWalkingSpeedMps <= 0) accessibleWalkingSpeedMps = 1.0;
        if (stairsSpeedMps <= 0) stairsSpeedMps = 0.5;
        if (minAccessibleClearanceM <= 0) minAccessibleClearanceM = 0.9;
        if (maxAccessibleSlopeDeg <= 0) maxAccessibleSlopeDeg = Math.toDegrees(Math.atan(1.0 / 12.0));
        if (elevatorTransitionSeconds <= 0) elevatorTransitionSeconds = 45;
        if (nodeSnapMaxDistanceMeters <= 0) nodeSnapMaxDistanceMeters = 10.0;
        if (obstacleMaxAgeSeconds <= 0) obstacleMaxAgeSeconds = 120;
        if (obstacleMaxFutureSkewSeconds <= 0) obstacleMaxFutureSkewSeconds = 10;
        if (endpointBoundsMarginM <= 0) endpointBoundsMarginM = 1.0;
        if (maxStops <= 0) maxStops = 10;
        if (graphCacheMaxGraphs <= 0) graphCacheMaxGraphs = 64;
        if (agentRadiusM <= 0) agentRadiusM = 0.35;
    }

    /** Defaults for everything but the accessible clear width (tests that vary only that). */
    public static NavigationProperties withMinAccessibleClearance(double minAccessibleClearanceM) {
        return new NavigationProperties(0, 0, 0, minAccessibleClearanceM, 0, 0, 0, false, 0, 0, 0, 0, 0, 0);
    }
}
