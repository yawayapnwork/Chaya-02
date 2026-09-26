package dev.chaya.api.navigation;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Tunables for RouteService. Speeds are real, commonly used pedestrian-planning constants, not
 * arbitrary: ~1.3 m/s is the typical adult walking speed used in transport planning; ~1.0 m/s reflects
 * the slower pace assumed for accessible/assisted travel. minAccessibleClearanceM (0.9 m) approximates
 * the ADA minimum clear width (32 in ~= 0.81 m) with a safety margin.
 *
 * <p>acceptSyntheticGraphs (default false, and never set in application.yml) lets RouteService route on a
 * navigation_graph that was not derived from a Recast navmesh (source SYNTHETIC, V18) -- hand-built test fixtures.
 * Production refuses those with NAVMESH_NOT_READY; see RouteService. */
@ConfigurationProperties("chaya.navigation")
public record NavigationProperties(double standardWalkingSpeedMps, double accessibleWalkingSpeedMps,
                                   double minAccessibleClearanceM, double stairsTransitionSeconds,
                                   double elevatorTransitionSeconds, double transitionMatchRadiusMeters,
                                   double nodeSnapMaxDistanceMeters, boolean acceptSyntheticGraphs) {

    public NavigationProperties {
        if (standardWalkingSpeedMps <= 0) standardWalkingSpeedMps = 1.3;
        if (accessibleWalkingSpeedMps <= 0) accessibleWalkingSpeedMps = 1.0;
        if (minAccessibleClearanceM <= 0) minAccessibleClearanceM = 0.9;
        if (stairsTransitionSeconds <= 0) stairsTransitionSeconds = 20;
        if (elevatorTransitionSeconds <= 0) elevatorTransitionSeconds = 45;
        if (transitionMatchRadiusMeters <= 0) transitionMatchRadiusMeters = 5.0;
        if (nodeSnapMaxDistanceMeters <= 0) nodeSnapMaxDistanceMeters = 10.0;
    }
}
