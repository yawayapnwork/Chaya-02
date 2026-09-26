package dev.chaya.api.navigation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public final class NavigationDtos {

    private NavigationDtos() {}

    /** An axis-aligned region in one floor's reconstruction frame that the AR client observed as blocked
     * (a person, a cart, a closed door -- whatever it actually detected). Never invented server-side; see
     * RouteService's module docstring. */
    public record BlockedRegion(@NotNull UUID floorId, double minX, double minY, double maxX, double maxY) {}

    /** accessibility: "STANDARD" (default) or "STEP_FREE". start: [x, y, z] in floorId's reconstruction
     * frame. */
    public record RouteRequest(@NotNull UUID venueId, @NotNull UUID floorId,
                               @NotNull @Size(min = 3, max = 3) List<Double> start, @NotNull UUID destinationPoiId,
                               String accessibility, @Valid List<BlockedRegion> blockedRegions) {}

    public record Waypoint(double x, double y, double z, UUID floorId, String kind) {}

    public record FloorTransition(UUID fromFloorId, UUID toFloorId, String connectorType, UUID poiId) {}

    /** What one floor's leg was routed on: the navigation graph, and the navmesh it was derived from (source
     * RECAST_NAVMESH, with that navmesh's SHA-256 and the recastnavigation version that built it). SYNTHETIC only ever
     * appears when chaya.navigation.accept-synthetic-graphs is set (tests). */
    public record RoutingSource(UUID floorId, UUID graphId, String source, String navmeshSha256, String recastnavigationVersion) {}

    public record RouteResponse(List<Waypoint> waypoints, double distanceMeters, double estimatedDurationSeconds,
                                List<FloorTransition> floorTransitions, String accessibilityProfile,
                                List<String> accessibilityConstraintsApplied, List<RoutingSource> routingSources) {}
}
