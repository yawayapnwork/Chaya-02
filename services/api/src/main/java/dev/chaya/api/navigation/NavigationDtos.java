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
     * floorId, in the floor's current coordinate frame (RouteService; docs/coordinate-frames.md).
     *
     * <p>scanVersionId (optional): route on that FINALIZED scan version of floorId instead of the floor's current state --
     * on the navigation graph of the navmesh the version pinned, to the destination POI as it is in that version, with
     * start in the version's coordinate frame. What a viewer showing that version must use. */
    public record RouteRequest(@NotNull UUID venueId, @NotNull UUID floorId,
                               @NotNull @Size(min = 3, max = 3) List<Double> start, @NotNull UUID destinationPoiId,
                               String accessibility, @Valid List<BlockedRegion> blockedRegions, UUID scanVersionId) {

        public RouteRequest(UUID venueId, UUID floorId, List<Double> start, UUID destinationPoiId, String accessibility,
                            List<BlockedRegion> blockedRegions) {
            this(venueId, floorId, start, destinationPoiId, accessibility, blockedRegions, null);
        }
    }

    public record Waypoint(double x, double y, double z, UUID floorId, String kind) {}

    /** One crossing of a registered floor connection (FloorConnectionService). poiId is the landing the route leaves
     * from on fromFloorId, toPoiId the landing it arrives at on toFloorId. distanceMeters is the registered walked
     * length (0 for an elevator); durationSeconds is what the crossing adds to the route's estimate. */
    public record FloorTransition(UUID fromFloorId, UUID toFloorId, String connectorType, UUID poiId, UUID connectionId,
                                  UUID toPoiId, double distanceMeters, double durationSeconds) {}

    /** What one floor's leg was routed on: the navigation graph, and the navmesh it was derived from (source
     * RECAST_NAVMESH, with that navmesh's SHA-256 and the recastnavigation version that built it). SYNTHETIC only ever
     * appears when chaya.navigation.accept-synthetic-graphs is set (tests). */
    /** pathMethod: how the leg's waypoints were made -- STRING_PULLED (the funnel through the Detour portals of the polygon
     * corridor), PORTAL_MIDPOINTS (when the string-pulled path crosses a reported obstacle), or POLYGON_CENTROIDS (graphs
     * without portals, which only SYNTHETIC test graphs are, or when both of the above cross a reported obstacle). */
    /** scanVersionId: the scan version the graph belongs to -- the floor's current version (or the requested one); a route
     * never combines graphs or POIs of two versions of one floor. */
    public record RoutingSource(UUID floorId, UUID graphId, String source, String navmeshSha256, String recastnavigationVersion,
                                String pathMethod, UUID scanVersionId) {}

    /** distanceMeters: the 3-D length of the waypoint polyline of every floor leg, in canonical metres, plus the
     * registered walked length of every stairs/ramp connection. estimatedDurationSeconds: each walked length divided by
     * the (assumed) speed for it, plus a fixed (assumed) time per elevator ride. */
    public record RouteResponse(List<Waypoint> waypoints, double distanceMeters, double estimatedDurationSeconds,
                                List<FloorTransition> floorTransitions, String accessibilityProfile,
                                List<String> accessibilityConstraintsApplied, List<RoutingSource> routingSources) {}
}
