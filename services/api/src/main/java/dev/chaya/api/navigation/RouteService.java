package dev.chaya.api.navigation;

import dev.chaya.api.frame.CoordinateFrameService;
import dev.chaya.api.frame.FrameDtos.FrameView;
import dev.chaya.api.navigation.FloorConnectionService.Connection;
import dev.chaya.api.navigation.NavigationDtos.BlockedRegion;
import dev.chaya.api.navigation.NavigationDtos.FloorTransition;
import dev.chaya.api.navigation.NavigationDtos.RouteRequest;
import dev.chaya.api.navigation.NavigationDtos.RouteResponse;
import dev.chaya.api.navigation.NavigationDtos.RoutingSource;
import dev.chaya.api.navigation.NavigationDtos.Waypoint;
import dev.chaya.api.security.Actor;
import dev.chaya.api.security.TenantGuard;
import dev.chaya.api.web.ApiException;
import dev.chaya.api.web.NotFoundException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Real pedestrian routing over the navigation graph NAVIGATION_BAKING produces (see
 * dev.chaya.api.pipeline.PipelineService#ingestNavigationGraph and chaya_worker.navmesh). That graph is the polygon
 * graph of a Detour navmesh the real Recast/Detour library built from the reconstruction (nodes are navmesh polygon
 * centroids, edges are Detour links); pathfinding here is Dijkstra over it -- the same polygon-corridor search Detour's
 * findPath does, without its string-pulling, so waypoints are polygon centroids rather than a smoothed path.
 *
 * <p>Failure states, each a distinct code (docs/navigation.md):
 * <ul>
 *   <li>METRIC_CALIBRATION_REQUIRED (409): the start or destination floor has no current metric, gravity-aligned
 *       (canonical) frame, or the destination POI is not placed in it. Nothing on such a floor is in metres.</li>
 *   <li>NAVMESH_NOT_READY (409): the start or destination floor has no ACTIVE graph for the profile that was baked from a
 *       Recast navmesh (source RECAST_NAVMESH, V18) in the floor's current frame. There is no fallback graph.</li>
 *   <li>FLOOR_CONNECTION_UNAVAILABLE (404): the destination is on another floor and no chain of usable registered floor
 *       connections (FloorConnectionService) joins the two floors for this profile.</li>
 *   <li>NO_ACCESSIBLE_ROUTE (404): STEP_FREE was requested and no path satisfies its measured constraints.</li>
 *   <li>NO_ROUTE (404): STANDARD was requested and the walkable graph does not connect start and destination (including
 *       when reported obstacles cut the only path).</li>
 * </ul>
 *
 * <p>Accessibility (STEP_FREE) fails closed: it routes on the STEP_FREE-profile graph and uses an edge only when its
 * slope (against canonical +Z) and clearance were both measured and pass
 * {@link NavigationProperties#maxAccessibleSlopeDeg()} and {@link NavigationProperties#minAccessibleClearanceM()}. An
 * unmeasured value excludes the edge -- an accessibility guarantee is never made about geometry nobody measured.
 *
 * <p>Multi-floor: floors are reconstructed and calibrated independently, so this service never compares a coordinate on
 * one floor with a coordinate on another. A route crosses floors only through a registered floor_connection between a
 * landing POI on each floor (each in its own floor's current frame). The search is a Dijkstra over points -- start,
 * connection landings, destination -- where moving between two points on one floor is a real floor-local route and
 * moving along a connection costs its measured length or its elevator time; it minimises estimated duration.
 *
 * <p>Dynamic obstacles: {@code blockedRegions} in the request exclude, for THIS query only (never written to the
 * database), every graph node inside them and every route segment -- graph edge, or the segment from the start or to the
 * destination -- whose horizontal footprint crosses them. The client observes something real and reports its region;
 * this service never invents one.
 *
 * <p>Distance and duration come from canonical geometry: an edge's weight is the 3-D distance between its two nodes'
 * canonical positions (never the stored length_m), a leg's distance is the length of its waypoint polyline, and a
 * connection adds its registered walked length. Speeds and the elevator time are assumed constants
 * (NavigationProperties).
 */
@Service
public class RouteService {

    private static final Set<String> PROFILES = Set.of("STANDARD", "STEP_FREE");
    private static final String STEP_FREE = "STEP_FREE";

    private record EdgeRow(UUID from, UUID to, boolean bidirectional, boolean stepFree, Double minClearanceM, Double maxSlopeDeg) {}

    private record ActiveGraph(UUID id, UUID frameId, String source, String navmeshSha256, String recastVersion) {}

    private record PoiRow(UUID id, UUID floorId, double x, double y, double z, UUID frameId) {
        double[] position() {
            return new double[]{x, y, z};
        }
    }

    private record Box(double minX, double minY, double maxX, double maxY) {
        boolean contains(double[] p) {
            return p[0] >= minX && p[0] <= maxX && p[1] >= minY && p[1] <= maxY;
        }
    }

    /** Why edges and nodes were left out of a floor's graph for this query -- reported in the failure message. */
    private static final class Exclusions {
        int unknownClearance;
        int narrow;
        int unknownSlope;
        int steep;
        int notStepFree;
        int blockedNodes;
        int blockedEdges;

        String describe() {
            List<String> parts = new ArrayList<>();
            add(parts, unknownClearance, "edge(s) with no measured clearance");
            add(parts, narrow, "edge(s) narrower than the accessible clearance");
            add(parts, unknownSlope, "edge(s) with no measured slope");
            add(parts, steep, "edge(s) steeper than the accessible slope");
            add(parts, notStepFree, "edge(s) not baked as step-free");
            add(parts, blockedNodes, "node(s) inside reported obstacles");
            add(parts, blockedEdges, "edge(s) crossing reported obstacles");
            return parts.isEmpty() ? "" : " (excluded: " + String.join(", ", parts) + ")";
        }

        private static void add(List<String> parts, int n, String what) {
            if (n > 0) {
                parts.add(n + " " + what);
            }
        }
    }

    private record FloorGraph(UUID floorId, Map<UUID, double[]> positions, Map<UUID, List<UUID>> adjacency,
                              List<Box> boxes, RoutingSource source, Exclusions exclusions) {}

    private record RouteLeg(List<double[]> waypoints, double distanceMeters, UUID floorId, RoutingSource source) {}

    /** One direction of a registered floor connection. */
    private record Hop(Connection connection, UUID fromFloor, UUID fromPoi, UUID toFloor, UUID toPoi) {}

    /** A point the multi-floor search visits: the start, a connection landing, or the destination. */
    private record Point(String key, UUID floorId, double[] position) {}

    private record Step(String previousKey, RouteLeg leg, Hop hop) {}

    private final JdbcClient jdbc;
    private final TenantGuard guard;
    private final NavigationProperties props;
    private final CoordinateFrameService frames;

    public RouteService(JdbcClient jdbc, TenantGuard guard, NavigationProperties props, CoordinateFrameService frames) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.props = props;
        this.frames = frames;
    }

    @Transactional(readOnly = true)
    public RouteResponse route(Actor actor, RouteRequest request) {
        guard.requireVenue(actor, request.venueId());
        String profile = normalizeProfile(request.accessibility());
        requireFloor(request.venueId(), request.floorId());
        PoiRow destination = loadPoi(actor.organizationId(), request.venueId(), request.destinationPoiId())
            .orElseThrow(() -> new NotFoundException("destination POI not found"));
        if (destination.floorId() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "METRIC_CALIBRATION_REQUIRED",
                "the destination POI is not placed on any floor, so it has no metric position to route to");
        }
        requireMetricFrame(request.venueId(), request.floorId());
        FrameView destinationFrame = requireMetricFrame(request.venueId(), destination.floorId());
        if (!destinationFrame.id().equals(destination.frameId())) {
            throw new ApiException(HttpStatus.CONFLICT, "METRIC_CALIBRATION_REQUIRED", "the destination POI's coordinates are "
                + (destination.frameId() == null ? "not bound to any calibrated coordinate frame"
                    : "in coordinate frame " + destination.frameId() + ", not its floor's current frame " + destinationFrame.id())
                + "; re-place it in the current metric frame before routing to it");
        }
        List<BlockedRegion> blocked = request.blockedRegions() == null ? List.of() : request.blockedRegions();
        validateBlocked(blocked);
        double[] start = {request.start().get(0), request.start().get(1), request.start().get(2)};
        if (!Double.isFinite(start[0]) || !Double.isFinite(start[1]) || !Double.isFinite(start[2])) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_START", "start must be three finite canonical coordinates");
        }

        Map<UUID, FloorGraph> graphs = new HashMap<>();
        graphs.put(request.floorId(), loadFloorGraph(request.venueId(), request.floorId(), profile, blocked, true));
        if (!destination.floorId().equals(request.floorId())) {
            graphs.put(destination.floorId(), loadFloorGraph(request.venueId(), destination.floorId(), profile, blocked, true));
        }

        List<String> connectionProblems = new ArrayList<>();
        List<Hop> hops = usableHops(actor.organizationId(), request.venueId(), profile, connectionProblems);
        if (!destination.floorId().equals(request.floorId()) && !floorsConnected(hops, request.floorId(), destination.floorId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "FLOOR_CONNECTION_UNAVAILABLE",
                "no usable registered floor connection joins floor " + request.floorId() + " to floor " + destination.floorId()
                    + (profile.equals(STEP_FREE) ? " step-free (only elevators and ramps with registered, passing clear width"
                        + " and slope qualify)" : "")
                    + (connectionProblems.isEmpty() ? "" : "; unusable connections: " + String.join("; ", connectionProblems)));
        }

        Point startPoint = new Point("START", request.floorId(), start);
        Point destPoint = new Point("DESTINATION", destination.floorId(), destination.position());
        Map<String, Step> steps = search(request.venueId(), profile, blocked, graphs, hops, startPoint, destPoint);
        if (steps == null) {
            Set<String> excluded = new LinkedHashSet<>();
            for (FloorGraph g : graphs.values()) {
                String d = g.exclusions().describe();
                if (!d.isEmpty()) {
                    excluded.add("floor " + g.floorId() + d);
                }
            }
            String detail = (destination.floorId().equals(request.floorId())
                    ? "no walkable path connects the start point to the destination on this floor"
                    : "no walkable path connects the start point, the usable floor connections and the destination")
                + (excluded.isEmpty() ? "" : "; " + String.join("; ", excluded));
            if (profile.equals(STEP_FREE)) {
                throw new ApiException(HttpStatus.NOT_FOUND, "NO_ACCESSIBLE_ROUTE", "no step-free route with measured clearance of at "
                    + "least " + props.minAccessibleClearanceM() + " m and measured slope of at most "
                    + round(props.maxAccessibleSlopeDeg()) + " degrees exists: " + detail);
            }
            throw new ApiException(HttpStatus.NOT_FOUND, "NO_ROUTE", detail);
        }

        List<RouteLeg> legs = new ArrayList<>();
        List<Hop> used = new ArrayList<>();
        String key = destPoint.key();
        while (!key.equals(startPoint.key())) {
            Step step = steps.get(key);
            if (step.hop() != null) {
                used.add(step.hop());
            }
            legs.add(step.leg());
            key = step.previousKey();
        }
        Collections.reverse(legs);
        Collections.reverse(used);
        return assembleResponse(legs, used, profile, constraints(profile, blocked));
    }

    private List<String> constraints(String profile, List<BlockedRegion> blocked) {
        List<String> constraints = new ArrayList<>();
        if (profile.equals(STEP_FREE)) {
            constraints.add("uses only edges of the STEP_FREE graph baked from the navmesh");
            constraints.add("uses only edges whose measured slope against canonical +Z is at most "
                + round(props.maxAccessibleSlopeDeg()) + " degrees; edges with no measured slope are excluded");
            constraints.add("uses only edges whose measured clearance (Detour portal width, already eroded by the agent radius) "
                + "is at least " + props.minAccessibleClearanceM() + " m; edges with no measured clearance are excluded");
            constraints.add("crosses floors only by registered elevators or ramps with a registered clear width of at least "
                + props.minAccessibleClearanceM() + " m (and, for a ramp, a registered slope within the limit); never stairs");
        }
        if (!blocked.isEmpty()) {
            constraints.add(blocked.size() + " reported obstacle region(s): graph nodes inside them and route segments "
                + "crossing them are excluded");
        }
        return constraints;
    }

    private static String round(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    private static String normalizeProfile(String accessibility) {
        String p = accessibility == null || accessibility.isBlank() ? "STANDARD" : accessibility.strip().toUpperCase(Locale.ROOT);
        if (!PROFILES.contains(p)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ACCESSIBILITY", "accessibility must be one of " + PROFILES);
        }
        return p;
    }

    private static void validateBlocked(List<BlockedRegion> blocked) {
        for (BlockedRegion r : blocked) {
            boolean finite = Double.isFinite(r.minX()) && Double.isFinite(r.minY()) && Double.isFinite(r.maxX()) && Double.isFinite(r.maxY());
            if (!finite || r.minX() > r.maxX() || r.minY() > r.maxY()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_BLOCKED_REGION",
                    "a blocked region needs finite canonical bounds with min <= max");
            }
        }
    }

    private void requireFloor(UUID venueId, UUID floorId) {
        Integer count = jdbc.sql("SELECT count(*) FROM floor WHERE id = :f AND venue_id = :v AND deleted_at IS NULL")
            .param("f", floorId).param("v", venueId).query(Integer.class).single();
        if (count == 0) {
            throw new NotFoundException("floor not found");
        }
    }

    /** The floor's current frame, which must be canonical: metric scale and gravity-aligned +Z. */
    private Optional<FrameView> metricFrame(UUID venueId, UUID floorId) {
        return frames.currentForFloor(venueId, floorId).filter(FrameView::canonical);
    }

    private FrameView requireMetricFrame(UUID venueId, UUID floorId) {
        return metricFrame(venueId, floorId).orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "METRIC_CALIBRATION_REQUIRED",
            "floor " + floorId + " has no calibrated metric, gravity-aligned coordinate frame, so its navigation data cannot be "
                + "in metres; calibrate its reconstruction first"));
    }

    private Optional<PoiRow> loadPoi(UUID orgId, UUID venueId, UUID poiId) {
        return jdbc.sql("""
                SELECT p.id, p.floor_id, v.x, v.y, v.z, v.coordinate_frame_id
                  FROM poi p
                  JOIN poi_version v ON v.poi_id = p.id
                 WHERE p.id = :poi AND p.venue_id = :venue AND p.organization_id = :org AND p.deleted_at IS NULL
                   AND v.version_number = (SELECT max(version_number) FROM poi_version WHERE poi_id = p.id)
                """)
            .param("poi", poiId).param("venue", venueId).param("org", orgId)
            .query((rs, i) -> new PoiRow(rs.getObject("id", UUID.class), rs.getObject("floor_id", UUID.class),
                rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("z"), rs.getObject("coordinate_frame_id", UUID.class)))
            .optional();
    }

    // ---- floor graphs ---------------------------------------------------------------------------------

    private ActiveGraph activeGraph(UUID venueId, UUID floorId, String profile) {
        return jdbc.sql("SELECT id, coordinate_frame_id, source, navmesh_sha256, recastnavigation_version FROM navigation_graph "
                + "WHERE venue_id = :v AND floor_id = :f AND profile = :p AND status = 'ACTIVE'")
            .param("v", venueId).param("f", floorId).param("p", profile)
            .query((rs, i) -> new ActiveGraph(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                rs.getString(4), rs.getString(5))).optional().orElse(null);
    }

    /** Why the floor cannot be routed on (no metric frame, no navmesh-backed graph, or one baked in an older frame), or
     * null when it can. */
    private String notReadyReason(UUID venueId, UUID floorId, String profile) {
        Optional<FrameView> frame = metricFrame(venueId, floorId);
        if (frame.isEmpty()) {
            return "floor " + floorId + " has no calibrated metric frame";
        }
        ActiveGraph graph = activeGraph(venueId, floorId, profile);
        if (graph == null || !("RECAST_NAVMESH".equals(graph.source()) || props.acceptSyntheticGraphs())) {
            return "floor " + floorId + " has no " + profile + " navigation graph baked from a Recast navmesh"
                + (graph == null ? "" : " (its ACTIVE graph " + graph.id() + " is " + graph.source() + ", which is never routed on)");
        }
        if (!frame.get().id().equals(graph.frameId())) {
            return "the " + profile + " navigation graph of floor " + floorId + " was baked in coordinate frame " + graph.frameId()
                + ", not the floor's current frame " + frame.get().id() + ", and must be baked again";
        }
        return null;
    }

    /** Loads the floor's routable graph with every exclusion for this query applied. `required` floors (start and
     * destination) fail the request with NAVMESH_NOT_READY; any other floor is only ever loaded after notReadyReason
     * cleared it. */
    private FloorGraph loadFloorGraph(UUID venueId, UUID floorId, String profile, List<BlockedRegion> blocked, boolean required) {
        String problem = notReadyReason(venueId, floorId, profile);
        if (problem != null) {
            if (metricFrame(venueId, floorId).isEmpty()) {
                requireMetricFrame(venueId, floorId);
            }
            throw new ApiException(HttpStatus.CONFLICT, "NAVMESH_NOT_READY", problem
                + "; run NAVIGATION_BAKING on a calibrated reconstruction of this floor" + (required ? "" : " (intermediate floor)"));
        }
        ActiveGraph active = activeGraph(venueId, floorId, profile);
        List<Box> boxes = blocked.stream().filter(r -> r.floorId().equals(floorId))
            .map(r -> new Box(r.minX(), r.minY(), r.maxX(), r.maxY())).toList();
        Exclusions exclusions = new Exclusions();

        Map<UUID, double[]> positions = new HashMap<>();
        jdbc.sql("SELECT id, x, y, z FROM navigation_node WHERE graph_id = :g").param("g", active.id())
            .query((rs, i) -> Map.entry(rs.getObject("id", UUID.class), new double[]{rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("z")}))
            .list().forEach(e -> positions.put(e.getKey(), e.getValue()));
        positions.values().removeIf(p -> {
            boolean inside = boxes.stream().anyMatch(b -> b.contains(p));
            if (inside) {
                exclusions.blockedNodes++;
            }
            return inside;
        });

        boolean stepFree = profile.equals(STEP_FREE);
        List<EdgeRow> edgeRows = jdbc.sql("SELECT from_node_id, to_node_id, bidirectional, step_free, min_clearance_m, max_slope_deg "
                + "FROM navigation_edge WHERE graph_id = :g").param("g", active.id())
            .query((rs, i) -> new EdgeRow(rs.getObject("from_node_id", UUID.class), rs.getObject("to_node_id", UUID.class),
                rs.getBoolean("bidirectional"), rs.getBoolean("step_free"), nullableDouble(rs.getObject("min_clearance_m")),
                nullableDouble(rs.getObject("max_slope_deg"))))
            .list();
        Map<UUID, List<UUID>> adjacency = new HashMap<>();
        for (EdgeRow e : edgeRows) {
            double[] a = positions.get(e.from());
            double[] b = positions.get(e.to());
            if (a == null || b == null) {
                continue; // an endpoint is inside a reported obstacle (counted above)
            }
            if (stepFree && !accessible(e, exclusions)) {
                continue;
            }
            if (crossesAny(a, b, boxes)) {
                exclusions.blockedEdges++;
                continue;
            }
            adjacency.computeIfAbsent(e.from(), k -> new ArrayList<>()).add(e.to());
            if (e.bidirectional()) {
                adjacency.computeIfAbsent(e.to(), k -> new ArrayList<>()).add(e.from());
            }
        }
        RoutingSource source = new RoutingSource(floorId, active.id(), active.source(), active.navmeshSha256(), active.recastVersion());
        return new FloorGraph(floorId, positions, adjacency, boxes, source, exclusions);
    }

    /** STEP_FREE fails closed: every constraint needs a measured value, and the value must pass. */
    private boolean accessible(EdgeRow e, Exclusions x) {
        if (!e.stepFree()) {
            x.notStepFree++;
            return false;
        }
        if (e.minClearanceM() == null) {
            x.unknownClearance++;
            return false;
        }
        if (e.minClearanceM() < props.minAccessibleClearanceM()) {
            x.narrow++;
            return false;
        }
        if (e.maxSlopeDeg() == null) {
            x.unknownSlope++;
            return false;
        }
        if (e.maxSlopeDeg() > props.maxAccessibleSlopeDeg()) {
            x.steep++;
            return false;
        }
        return true;
    }

    private static Double nullableDouble(Object v) {
        return v == null ? null : ((Number) v).doubleValue();
    }

    // ---- single-floor legs ----------------------------------------------------------------------------

    /** A route between two canonical points on one floor, or null. The start and end snap to the nearest graph node
     * within nodeSnapMaxDistanceMeters whose connecting segment crosses no reported obstacle (see snap). */
    private RouteLeg routeWithinFloor(FloorGraph graph, double[] from, double[] to) {
        if (crossesAny(from, from, graph.boxes()) || crossesAny(to, to, graph.boxes())) {
            return null; // the point itself is inside a reported obstacle
        }
        if (distance(from, to) == 0) {
            // Already there: e.g. arriving at an elevator landing that is also the landing of the next connection.
            return new RouteLeg(List.of(from, to), 0.0, graph.floorId(), graph.source());
        }
        UUID startNode = snap(graph, from);
        UUID endNode = snap(graph, to);
        if (startNode == null || endNode == null) {
            return null;
        }
        Map<UUID, Double> dist = new HashMap<>();
        Map<UUID, UUID> prev = new HashMap<>();
        Set<UUID> visited = new HashSet<>();
        PriorityQueue<UUID> queue = new PriorityQueue<>(Comparator.comparingDouble(id -> dist.getOrDefault(id, Double.MAX_VALUE)));
        dist.put(startNode, 0.0);
        queue.add(startNode);
        while (!queue.isEmpty()) {
            UUID u = queue.poll();
            if (!visited.add(u)) {
                continue;
            }
            if (u.equals(endNode)) {
                break;
            }
            for (UUID v : graph.adjacency().getOrDefault(u, List.of())) {
                if (visited.contains(v)) {
                    continue;
                }
                // Edge weight: the 3-D distance between the two nodes' canonical positions, never the stored length_m.
                double candidate = dist.get(u) + distance(graph.positions().get(u), graph.positions().get(v));
                if (candidate < dist.getOrDefault(v, Double.MAX_VALUE)) {
                    dist.put(v, candidate);
                    prev.put(v, u);
                    queue.add(v);
                }
            }
        }
        if (!dist.containsKey(endNode)) {
            return null;
        }
        List<double[]> waypoints = new ArrayList<>();
        for (UUID cur = endNode; cur != null; cur = prev.get(cur)) {
            waypoints.add(graph.positions().get(cur));
        }
        waypoints.add(from);
        Collections.reverse(waypoints);
        waypoints.add(to);
        return new RouteLeg(waypoints, polylineLength(waypoints), graph.floorId(), graph.source());
    }

    /** The nearest graph node within nodeSnapMaxDistanceMeters whose straight segment to the point crosses no reported
     * obstacle, or null. */
    private UUID snap(FloorGraph graph, double[] point) {
        UUID best = null;
        double bestDist = props.nodeSnapMaxDistanceMeters();
        for (Map.Entry<UUID, double[]> e : graph.positions().entrySet()) {
            double d = distance(e.getValue(), point);
            if (d <= bestDist && !crossesAny(point, e.getValue(), graph.boxes())) {
                bestDist = d;
                best = e.getKey();
            }
        }
        return best;
    }

    static double polylineLength(List<double[]> points) {
        double total = 0;
        for (int i = 1; i < points.size(); i++) {
            total += distance(points.get(i - 1), points.get(i));
        }
        return total;
    }

    private static double distance(double[] a, double[] b) {
        return Math.sqrt(Math.pow(a[0] - b[0], 2) + Math.pow(a[1] - b[1], 2) + Math.pow(a[2] - b[2], 2));
    }

    private static boolean crossesAny(double[] a, double[] b, List<Box> boxes) {
        for (Box box : boxes) {
            if (segmentIntersectsBox(a, b, box)) {
                return true;
            }
        }
        return false;
    }

    /** Whether the horizontal footprint of segment a-b touches the box (Liang-Barsky clipping). A degenerate segment
     * (a == b) is a point-in-box test. */
    static boolean segmentIntersectsBox(double[] a, double[] b, double minX, double minY, double maxX, double maxY) {
        double dx = b[0] - a[0];
        double dy = b[1] - a[1];
        double[] p = {-dx, dx, -dy, dy};
        double[] q = {a[0] - minX, maxX - a[0], a[1] - minY, maxY - a[1]};
        double t0 = 0;
        double t1 = 1;
        for (int i = 0; i < 4; i++) {
            if (p[i] == 0) {
                if (q[i] < 0) {
                    return false;
                }
            } else {
                double t = q[i] / p[i];
                if (p[i] < 0) {
                    if (t > t1) {
                        return false;
                    }
                    t0 = Math.max(t0, t);
                } else {
                    if (t < t0) {
                        return false;
                    }
                    t1 = Math.min(t1, t);
                }
            }
        }
        return true;
    }

    private static boolean segmentIntersectsBox(double[] a, double[] b, Box box) {
        return segmentIntersectsBox(a, b, box.minX(), box.minY(), box.maxX(), box.maxY());
    }

    // ---- floor connections and the multi-floor search ---------------------------------------------------

    /** Every direction of every registered connection this profile may use, with each landing verified on its own floor
     * in that floor's current metric frame and both floors routable. Why any connection was left out goes to
     * `problems`. Never compares coordinates across floors. */
    private List<Hop> usableHops(UUID orgId, UUID venueId, String profile, List<String> problems) {
        List<Connection> connections = jdbc.sql("""
                SELECT id, connector_type, from_floor_id, from_poi_id, to_floor_id, to_poi_id, bidirectional, length_m,
                       max_slope_deg, min_clearance_m, status
                  FROM floor_connection
                 WHERE venue_id = :v AND organization_id = :o AND deleted_at IS NULL
                 ORDER BY created_at, id
                """).param("v", venueId).param("o", orgId).query(FloorConnectionService::map).list();
        Map<UUID, String> floorProblems = new HashMap<>();
        List<Hop> hops = new ArrayList<>();
        for (Connection c : connections) {
            String problem = connectionProblem(c, profile);
            if (problem == null) {
                problem = landingProblem(orgId, venueId, c.fromFloorId(), c.fromPoiId());
            }
            if (problem == null) {
                problem = landingProblem(orgId, venueId, c.toFloorId(), c.toPoiId());
            }
            for (UUID floor : List.of(c.fromFloorId(), c.toFloorId())) {
                if (problem == null) {
                    problem = floorProblems.computeIfAbsent(floor, f -> Optional.ofNullable(notReadyReason(venueId, f, profile)).orElse(""));
                    problem = problem.isEmpty() ? null : problem;
                }
            }
            if (problem != null) {
                problems.add(c.connectorType().toLowerCase(Locale.ROOT) + " connection " + c.id() + ": " + problem);
                continue;
            }
            hops.add(new Hop(c, c.fromFloorId(), c.fromPoiId(), c.toFloorId(), c.toPoiId()));
            if (c.bidirectional()) {
                hops.add(new Hop(c, c.toFloorId(), c.toPoiId(), c.fromFloorId(), c.fromPoiId()));
            }
        }
        return hops;
    }

    private String connectionProblem(Connection c, String profile) {
        if (!"IN_SERVICE".equals(c.status())) {
            return "out of service";
        }
        if (!profile.equals(STEP_FREE)) {
            return null;
        }
        if (c.connectorType().equals("STAIRS")) {
            return "stairs are never step-free";
        }
        if (c.minClearanceM() == null) {
            return "no clear width registered, so it cannot be claimed accessible";
        }
        if (c.minClearanceM() < props.minAccessibleClearanceM()) {
            return "registered clear width " + c.minClearanceM() + " m is below " + props.minAccessibleClearanceM() + " m";
        }
        if (c.connectorType().equals("RAMP")) {
            if (c.maxSlopeDeg() == null) {
                return "no ramp slope registered, so it cannot be claimed accessible";
            }
            if (c.maxSlopeDeg() > props.maxAccessibleSlopeDeg()) {
                return "registered ramp slope " + c.maxSlopeDeg() + " degrees exceeds " + round(props.maxAccessibleSlopeDeg());
            }
        }
        return null;
    }

    private String landingProblem(UUID orgId, UUID venueId, UUID floorId, UUID poiId) {
        Optional<PoiRow> poi = loadPoi(orgId, venueId, poiId);
        if (poi.isEmpty()) {
            return "landing POI " + poiId + " no longer exists";
        }
        if (!floorId.equals(poi.get().floorId())) {
            return "landing POI " + poiId + " is no longer on floor " + floorId;
        }
        Optional<FrameView> frame = metricFrame(venueId, floorId);
        if (frame.isEmpty() || !frame.get().id().equals(poi.get().frameId())) {
            return "landing POI " + poiId + " is not placed in floor " + floorId + "'s current metric frame";
        }
        return null;
    }

    private static boolean floorsConnected(List<Hop> hops, UUID from, UUID to) {
        Set<UUID> seen = new HashSet<>(Set.of(from));
        Deque<UUID> queue = new ArrayDeque<>(List.of(from));
        while (!queue.isEmpty()) {
            UUID f = queue.poll();
            if (f.equals(to)) {
                return true;
            }
            for (Hop h : hops) {
                if (h.fromFloor().equals(f) && seen.add(h.toFloor())) {
                    queue.add(h.toFloor());
                }
            }
        }
        return false;
    }

    private double walkingSpeed(String profile) {
        return profile.equals(STEP_FREE) ? props.accessibleWalkingSpeedMps() : props.standardWalkingSpeedMps();
    }

    private double hopDistance(Hop hop) {
        return hop.connection().lengthM() == null ? 0.0 : hop.connection().lengthM();
    }

    private double hopSeconds(Hop hop, String profile) {
        return switch (hop.connection().connectorType()) {
            case "ELEVATOR" -> props.elevatorTransitionSeconds();
            case "STAIRS" -> hopDistance(hop) / props.stairsSpeedMps();
            default -> hopDistance(hop) / walkingSpeed(profile); // RAMP: walked at the profile's pace
        };
    }

    /** Dijkstra over points (start, connection landings, destination) minimising estimated seconds. Moving between two
     * points on one floor is a real floor-local route (computed lazily, once); crossing a connection costs its measured
     * length or its elevator time. Returns the predecessor steps, or null when the destination is unreachable. */
    private Map<String, Step> search(UUID venueId, String profile, List<BlockedRegion> blocked, Map<UUID, FloorGraph> graphs,
                                     List<Hop> hops, Point start, Point dest) {
        Map<String, Double> seconds = new HashMap<>();
        Map<String, Point> points = new HashMap<>();
        Map<String, Step> steps = new HashMap<>();
        Set<String> settled = new HashSet<>();
        PriorityQueue<String> queue = new PriorityQueue<>(Comparator.comparingDouble(k -> seconds.getOrDefault(k, Double.MAX_VALUE)));
        seconds.put(start.key(), 0.0);
        points.put(start.key(), start);
        queue.add(start.key());
        Map<UUID, Optional<double[]>> landings = new HashMap<>();

        while (!queue.isEmpty()) {
            String key = queue.poll();
            if (!settled.add(key)) {
                continue;
            }
            if (key.equals(dest.key())) {
                return steps;
            }
            Point here = points.get(key);
            FloorGraph graph = graphs.computeIfAbsent(here.floorId(), f -> loadFloorGraph(venueId, f, profile, blocked, false));
            double t = seconds.get(key);

            if (here.floorId().equals(dest.floorId())) {
                RouteLeg leg = routeWithinFloor(graph, here.position(), dest.position());
                if (leg != null) {
                    relax(dest, t + leg.distanceMeters() / walkingSpeed(profile), new Step(key, leg, null), seconds, points, steps, queue);
                }
            }
            for (Hop hop : hops) {
                if (!hop.fromFloor().equals(here.floorId())) {
                    continue;
                }
                double[] departure = landings.computeIfAbsent(hop.fromPoi(), p -> landingPosition(venueId, p)).orElse(null);
                double[] arrival = landings.computeIfAbsent(hop.toPoi(), p -> landingPosition(venueId, p)).orElse(null);
                if (departure == null || arrival == null) {
                    continue;
                }
                String arrivalKey = "LANDING:" + hop.toPoi();
                if (settled.contains(arrivalKey)) {
                    continue;
                }
                RouteLeg leg = routeWithinFloor(graph, here.position(), departure);
                if (leg == null) {
                    continue;
                }
                double cost = t + leg.distanceMeters() / walkingSpeed(profile) + hopSeconds(hop, profile);
                relax(new Point(arrivalKey, hop.toFloor(), arrival), cost, new Step(key, leg, hop), seconds, points, steps, queue);
            }
        }
        return null;
    }

    private static void relax(Point to, double cost, Step step, Map<String, Double> seconds, Map<String, Point> points,
                              Map<String, Step> steps, PriorityQueue<String> queue) {
        if (cost < seconds.getOrDefault(to.key(), Double.MAX_VALUE)) {
            seconds.put(to.key(), cost);
            points.put(to.key(), to);
            steps.put(to.key(), step);
            queue.add(to.key());
        }
    }

    private Optional<double[]> landingPosition(UUID venueId, UUID poiId) {
        return jdbc.sql("SELECT v.x, v.y, v.z FROM poi p JOIN poi_version v ON v.poi_id = p.id WHERE p.id = :p AND p.venue_id = :v "
                + "AND v.version_number = (SELECT max(version_number) FROM poi_version WHERE poi_id = p.id)")
            .param("p", poiId).param("v", venueId)
            .query((rs, i) -> new double[]{rs.getDouble(1), rs.getDouble(2), rs.getDouble(3)}).optional();
    }

    // ---- response assembly ----------------------------------------------------------------------------

    private RouteResponse assembleResponse(List<RouteLeg> legs, List<Hop> hops, String profile, List<String> constraints) {
        List<Waypoint> waypoints = new ArrayList<>();
        for (int legIndex = 0; legIndex < legs.size(); legIndex++) {
            RouteLeg leg = legs.get(legIndex);
            for (int i = 0; i < leg.waypoints().size(); i++) {
                double[] p = leg.waypoints().get(i);
                String kind;
                if (legIndex == 0 && i == 0) {
                    kind = "START";
                } else if (legIndex == legs.size() - 1 && i == leg.waypoints().size() - 1) {
                    kind = "DESTINATION";
                } else if (i == leg.waypoints().size() - 1 && legIndex < legs.size() - 1) {
                    kind = "TRANSITION";
                } else {
                    kind = "WAYPOINT";
                }
                waypoints.add(new Waypoint(p[0], p[1], p[2], leg.floorId(), kind));
            }
        }
        double walked = legs.stream().mapToDouble(RouteLeg::distanceMeters).sum();
        List<FloorTransition> transitions = new ArrayList<>();
        double connectorDistance = 0;
        double connectorSeconds = 0;
        for (Hop hop : hops) {
            double d = hopDistance(hop);
            double s = hopSeconds(hop, profile);
            connectorDistance += d;
            connectorSeconds += s;
            transitions.add(new FloorTransition(hop.fromFloor(), hop.toFloor(), hop.connection().connectorType(), hop.fromPoi(),
                hop.connection().id(), hop.toPoi(), d, s));
        }
        double durationSeconds = walked / walkingSpeed(profile) + connectorSeconds;
        return new RouteResponse(waypoints, walked + connectorDistance, durationSeconds, transitions, profile, constraints,
            legs.stream().map(RouteLeg::source).distinct().toList());
    }
}
