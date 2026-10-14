package dev.chaya.api.navigation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class NavigationDtos {

    private NavigationDtos() {}

    /** A region the AR client observed as blocked (a person, a cart, a closed door -- whatever it actually detected), as
     * an axis-aligned horizontal footprint in canonical metres on one floor, in that floor's current frame. It blocks the
     * full height of the floor: every graph node inside it and every route segment crossing it is excluded. Never
     * invented server-side; see RouteService.
     *
     * <p>observedAt (required): when the device observed it. An observation older than
     * chaya.navigation.obstacle-max-age-seconds, or stamped in the future beyond the allowed clock skew, is rejected
     * (reported in the response's rejectedObstacles, not routed around). coordinateFrameId (optional): the frame the
     * device was localized in; an observation in another frame than the one the floor is routed in is rejected too. */
    public record BlockedRegion(@NotNull UUID floorId, double minX, double minY, double maxX, double maxY, Instant observedAt,
                                UUID coordinateFrameId) {

        public BlockedRegion(UUID floorId, double minX, double minY, double maxX, double maxY, Instant observedAt) {
            this(floorId, minX, minY, maxX, maxY, observedAt, null);
        }
    }

    /** A reported obstacle that was not used: index is its position in the request's blockedRegions; reason is
     * STALE_OBSERVATION, FUTURE_OBSERVATION or FRAME_MISMATCH. */
    public record RejectedObstacle(int index, UUID floorId, String reason, String detail) {}

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

    /** kind: START, WAYPOINT, TRANSITION (the end of a floor's leg at a floor connection), STOP (an intermediate
     * destination of a multi-stop route) or DESTINATION. */
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
     * never combines graphs or POIs of two versions of one floor. coordinateFrameId: the canonical frame the graph (and so
     * the leg's waypoints) is in; an AR client draws the leg only with a transform solved in that same frame. */
    public record RoutingSource(UUID floorId, UUID graphId, String source, String navmeshSha256, String recastnavigationVersion,
                                String pathMethod, UUID scanVersionId, UUID coordinateFrameId) {}

    /** distanceMeters: the 3-D length of the waypoint polyline of every floor leg, in canonical metres, plus the
     * registered walked length of every stairs/ramp connection. estimatedDurationSeconds: each walked length divided by
     * the (assumed) speed for it, plus a fixed (assumed) time per elevator ride. */
    /** rejectedObstacles: reported obstacles that were not routed around, and why (BlockedRegion). */
    public record RouteResponse(List<Waypoint> waypoints, double distanceMeters, double estimatedDurationSeconds,
                                List<FloorTransition> floorTransitions, String accessibilityProfile,
                                List<String> accessibilityConstraintsApplied, List<RoutingSource> routingSources,
                                List<RejectedObstacle> rejectedObstacles) {}

    /** A route from start through several destinations (RouteService#routeMultiStop). stopPoiIds are visited in the order
     * given unless optimizeOrder is true, in which case the order that minimises the estimated duration is chosen. The
     * other fields mean what they mean in RouteRequest. */
    public record MultiStopRequest(@NotNull UUID venueId, @NotNull UUID floorId,
                                   @NotNull @Size(min = 3, max = 3) List<Double> start, @NotNull @Size(min = 1) List<UUID> stopPoiIds,
                                   boolean optimizeOrder, String accessibility, @Valid List<BlockedRegion> blockedRegions,
                                   UUID scanVersionId) {}

    /** One leg of a multi-stop route: from the start (fromPoiId null) or a reached stop, to the next reached stop. */
    public record RouteSegment(int index, UUID fromPoiId, UUID toPoiId, RouteResponse route) {}

    /** What became of one requested stop: REACHED (visitIndex is its position in the route) or UNREACHABLE (with the code
     * a single route to it would have failed with, and why). requestedIndex is its position in the request. */
    public record StopResult(UUID poiId, int requestedIndex, Integer visitIndex, String status, String failureCode,
                             String failureMessage) {}

    /** visitOrder: the reached stops in route order. waypoints: every segment's waypoints joined into one continuous route
     * (an intermediate stop is a STOP waypoint). distanceMeters, estimatedDurationSeconds: sums over the segments.
     * complete: every requested stop was reached. */
    public record MultiStopResponse(List<UUID> visitOrder, boolean orderOptimized, boolean complete, List<StopResult> stops,
                                    List<RouteSegment> segments, List<Waypoint> waypoints, double distanceMeters,
                                    double estimatedDurationSeconds, String accessibilityProfile,
                                    List<String> accessibilityConstraintsApplied, List<RoutingSource> routingSources,
                                    List<RejectedObstacle> rejectedObstacles) {}
}
