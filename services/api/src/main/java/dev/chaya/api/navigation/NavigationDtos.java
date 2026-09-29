package dev.chaya.api.navigation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public final class NavigationDtos {

    private NavigationDtos() {}

    /** A region the AR client observed as blocked (a person, a cart, a closed door -- whatever it actually detected), as
     * an axis-aligned horizontal footprint in canonical metres on one floor, in that floor's current frame. It blocks the
     * full height of the floor: every graph node inside it and every route segment crossing it is excluded. Never
     * invented server-side; see RouteService. */
    public record BlockedRegion(@NotNull UUID floorId, double minX, double minY, double maxX, double maxY) {}

    /** accessibility: "STANDARD" (default) or "STEP_FREE". start: [x, y, z] in canonical venue metres (+Z up) on
     * floorId, in the floor's current coordinate frame (RouteService; docs/coordinate-frames.md). */
    public record RouteRequest(@NotNull UUID venueId, @NotNull UUID floorId,
                               @NotNull @Size(min = 3, max = 3) List<Double> start, @NotNull UUID destinationPoiId,
                               String accessibility, @Valid List<BlockedRegion> blockedRegions) {}

    public record Waypoint(double x, double y, double z, UUID floorId, String kind) {}

    /** One crossing of a registered floor connection (FloorConnectionService). poiId is the landing the route leaves
     * from on fromFloorId, toPoiId the landing it arrives at on toFloorId. distanceMeters is the registered walked
     * length (0 for an elevator); durationSeconds is what the crossing adds to the route's estimate. */
    public record FloorTransition(UUID fromFloorId, UUID toFloorId, String connectorType, UUID poiId, UUID connectionId,
                                  UUID toPoiId, double distanceMeters, double durationSeconds) {}

    /** What one floor's leg was routed on: the navigation graph, and the navmesh it was derived from (source
     * RECAST_NAVMESH, with that navmesh's SHA-256 and the recastnavigation version that built it). SYNTHETIC only ever
     * appears when chaya.navigation.accept-synthetic-graphs is set (tests). */
    public record RoutingSource(UUID floorId, UUID graphId, String source, String navmeshSha256, String recastnavigationVersion) {}

    /** distanceMeters: the 3-D length of the waypoint polyline of every floor leg, in canonical metres, plus the
     * registered walked length of every stairs/ramp connection. estimatedDurationSeconds: each walked length divided by
     * the (assumed) speed for it, plus a fixed (assumed) time per elevator ride. */
    public record RouteResponse(List<Waypoint> waypoints, double distanceMeters, double estimatedDurationSeconds,
                                List<FloorTransition> floorTransitions, String accessibilityProfile,
                                List<String> accessibilityConstraintsApplied, List<RoutingSource> routingSources) {}
}
