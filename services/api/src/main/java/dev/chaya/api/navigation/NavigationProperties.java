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
 * <p>acceptSyntheticGraphs (default false, and never set in application.yml) lets RouteService route on a
 * navigation_graph that was not derived from a Recast navmesh (source SYNTHETIC, V18) -- hand-built test fixtures.
 * Production refuses those with NAVMESH_NOT_READY; see RouteService. */
@ConfigurationProperties("chaya.navigation")
public record NavigationProperties(double standardWalkingSpeedMps, double accessibleWalkingSpeedMps, double stairsSpeedMps,
                                   double minAccessibleClearanceM, double maxAccessibleSlopeDeg,
                                   double elevatorTransitionSeconds, double nodeSnapMaxDistanceMeters,
                                   boolean acceptSyntheticGraphs) {

    public NavigationProperties {
        if (standardWalkingSpeedMps <= 0) standardWalkingSpeedMps = 1.3;
        if (accessibleWalkingSpeedMps <= 0) accessibleWalkingSpeedMps = 1.0;
        if (stairsSpeedMps <= 0) stairsSpeedMps = 0.5;
        if (minAccessibleClearanceM <= 0) minAccessibleClearanceM = 0.9;
        if (maxAccessibleSlopeDeg <= 0) maxAccessibleSlopeDeg = Math.toDegrees(Math.atan(1.0 / 12.0));
        if (elevatorTransitionSeconds <= 0) elevatorTransitionSeconds = 45;
        if (nodeSnapMaxDistanceMeters <= 0) nodeSnapMaxDistanceMeters = 10.0;
    }
}
