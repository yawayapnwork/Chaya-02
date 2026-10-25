package dev.chaya.api.navigation;

import dev.chaya.api.frame.CoordinateFrameService;
import dev.chaya.api.frame.FrameDtos.FrameView;
import dev.chaya.api.navigation.FloorConnectionService.Connection;
import dev.chaya.api.navigation.NavigationDtos.BlockedRegion;
import dev.chaya.api.navigation.NavigationDtos.FloorTransition;
import dev.chaya.api.navigation.NavigationDtos.MultiStopRequest;
import dev.chaya.api.navigation.NavigationDtos.MultiStopResponse;
import dev.chaya.api.navigation.NavigationDtos.RejectedObstacle;
import dev.chaya.api.navigation.NavigationDtos.RouteRequest;
import dev.chaya.api.navigation.NavigationDtos.RouteResponse;
import dev.chaya.api.navigation.NavigationDtos.RouteSegment;
import dev.chaya.api.navigation.NavigationDtos.RoutingSource;
import dev.chaya.api.navigation.NavigationDtos.StopResult;
import dev.chaya.api.navigation.NavigationDtos.Waypoint;
import dev.chaya.api.rescan.ScanVersionService;
import dev.chaya.api.security.Actor;
import dev.chaya.api.security.TenantGuard;
import dev.chaya.api.web.ApiException;
import dev.chaya.api.web.NotFoundException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Real pedestrian routing over the navigation graph NAVIGATION_BAKING produces (see
 * dev.chaya.api.pipeline.PipelineService#ingestNavigationGraph and chaya_worker.navmesh). That graph is the polygon
 * graph of a Detour navmesh the real Recast/Detour library built from the reconstruction (nodes are navmesh polygon
 * centroids, edges are Detour links with their portals); pathfinding here is Dijkstra over it -- the same polygon-corridor
 * search Detour's findPath does -- and the waypoints are that corridor string-pulled through its portals (CorridorPath, the
 * funnel algorithm of Detour's findStraightPath), so the reported path stays inside the navmesh (review N-3). A
 * RECAST_NAVMESH graph baked before portals were recorded is not routable (NAVMESH_NOT_READY: bake again).
 *
 * <p>Failure states, each a distinct code (docs/navigation.md):
 * <ul>
 *   <li>METRIC_CALIBRATION_REQUIRED (409): the start or destination floor has no current metric, gravity-aligned
 *       (canonical) frame, or the destination POI is not placed in it. Nothing on such a floor is in metres.</li>
 *   <li>NAVMESH_NOT_READY (409): the start or destination floor has no ACTIVE graph for the profile that was baked from a
 *       Recast navmesh (source RECAST_NAVMESH, V18) in the floor's current frame. There is no fallback graph.</li>
 *   <li>START_OUT_OF_BOUNDS / DESTINATION_OUT_OF_BOUNDS (422): the point lies outside the footprint of its floor's navmesh
 *       graph (plus NavigationProperties#endpointBoundsMarginM), or far above or below every walkable surface of it.</li>
 *   <li>START_OFF_NAVMESH / DESTINATION_OFF_NAVMESH (422): within the footprint, but no navmesh polygon is within
 *       NavigationProperties#nodeSnapMaxDistanceMeters of it (a point inside a wall block, or in an unscanned hole).</li>
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
 * unmeasured value excludes the edge -- an accessibility guarantee is never made about geometry nobody measured. Between
 * floors it uses only elevators and ramps whose registered clear width (and slope) pass and whose accessibility venue
 * staff verified on site (V37); never stairs.
 *
 * <p>Multi-floor: floors are reconstructed and calibrated independently, so this service never compares a coordinate on
 * one floor with a coordinate on another. A route crosses floors only through a registered floor_connection between a
 * landing POI on each floor (each in its own floor's current frame). The search is a Dijkstra over points -- start,
 * connection landings, destination -- where moving between two points on one floor is a real floor-local route and
 * moving along a connection costs its measured length or its elevator time; it minimises estimated duration.
 *
 * <p>Multi-stop ({@link #routeMultiStop}): one route from the start through several destination POIs. Each segment is a
 * route exactly as above, between the previous reached stop and the next; all segments share one request's graphs,
 * obstacles and connections. The requested order is kept unless optimisation is asked for, which picks the visiting
 * order with the least estimated duration (exact, Held-Karp, over at most {@link #MAX_OPTIMISED_STOPS} stops). A stop
 * that cannot be reached is reported with the failure code a single route to it gets, and the route continues from the
 * last stop it did reach.
 *
 * <p>Dynamic obstacles: {@code blockedRegions} in the request exclude, for THIS query only (never written to the
 * database, and never a reason to rebuild a navmesh), every graph node inside them and every route segment -- graph edge,
 * or the segment from the start or to the destination -- whose horizontal footprint crosses them. The client observes
 * something real and reports its region with the time it saw it. An edge is also closed when the obstacles leave no
 * opening at its Detour portal as wide as the walking body (the accessible clear width for STEP_FREE), and the route is
 * string-pulled through the part they leave free. An observation older than
 * NavigationProperties#obstacleMaxAgeSeconds (or from the future), or in another frame than the floor is routed in, is
 * rejected and reported, not routed around. This service never invents an obstacle.
 *
 * <p>Current version: without a scanVersionId a route is planned on what each floor publishes now, its current scan
 * version (floor.current_scan_version_id, V28) -- that version's ACTIVE graph, and a destination and landing POIs that
 * belong to that version or an ancestor of it. A floor that publishes no version has nothing to route on
 * (NAVMESH_NOT_READY), and a destination placed against another version is refused (VERSION_MISMATCH): a route never
 * mixes two versions of one floor. Each routingSource names its version.
 *
 * <p>Scan versions: a request naming a FINALIZED scanVersionId routes on that version exactly -- the navigation graph of
 * the navmesh the version pinned (its own, or the one it inherited when its re-scan did not re-bake navigation), in the
 * coordinate frame the version recorded, to the destination POI as it is in that version -- never on the floor's ACTIVE
 * graph, which may belong to a newer version. Such a route stays on the version's floor.
 *
 * <p>Graph cache: a graph's nodes and edges are immutable once it leaves DRAFT (V6 navigation_graph_content_guard), so
 * they are cached by graph id. Which graph a floor routes on is still decided from the database on every request, so a
 * newly published version, a retired graph or a new frame takes effect immediately; obstacles and accessibility rules
 * are applied to a per-request copy and never cached.
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

    /** Held-Karp is O(2^n n^2): exact and instant for this many stops. NavigationProperties#maxStops is capped to it. */
    public static final int MAX_OPTIMISED_STOPS = 12;

    /** How far below the lowest walkable surface of a floor's graph a start or destination may be. */
    private static final double BELOW_FLOOR_TOLERANCE_M = 1.0;
    /** How far above the highest walkable surface: an AR client sends its camera position, held at up to eye height. */
    private static final double ABOVE_FLOOR_TOLERANCE_M = 2.5;

    private record EdgeRow(UUID from, UUID to, boolean bidirectional, boolean stepFree, Double minClearanceM, Double maxSlopeDeg,
                           double[][] portal) {}

    private record ActiveGraph(UUID id, UUID frameId, String source, String navmeshSha256, String recastVersion, UUID scanVersionId,
                               String status) {}

    /** A graph's nodes and edges as stored, before any per-request exclusion. Shared between requests through the graph
     * cache: never modified (positions is unmodifiable, and no code writes into a position array). bounds: min x, y, z then
     * max x, y, z of every node and portal endpoint; null for a graph without nodes. */
    private record StoredGraph(Map<UUID, double[]> positions, List<EdgeRow> edges, double[] bounds) {}

    /** current: whether this POI version belongs to what its floor publishes (poi_version_is_current, V28). */
    private record PoiRow(UUID id, UUID floorId, double x, double y, double z, UUID frameId, UUID scanVersionId, boolean current) {
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
                              Map<List<UUID>, double[][]> portals, List<Box> boxes, RoutingSource source, Exclusions exclusions,
                              StoredGraph stored) {}

    private record RouteLeg(List<double[]> waypoints, double distanceMeters, UUID floorId, RoutingSource source) {}

    /** One direction of a registered floor connection. */
    private record Hop(Connection connection, UUID fromFloor, UUID fromPoi, UUID toFloor, UUID toPoi) {}

    /** A point the multi-floor search visits: the start, a connection landing, or the destination. */
    private record Point(String key, UUID floorId, double[] position) {}

    private record Step(String previousKey, RouteLeg leg, Hop hop) {}

    /** A reported obstacle accepted for this request, with its index in the request. */
    private record Observation(int index, BlockedRegion region) {}

    /**
     * Everything one request routes with: its profile, its accepted obstacles, and the floor graphs and floor connections,
     * each loaded at most once and shared by every segment of a multi-stop request. scope is the requested scan version
     * (null: route on what each floor publishes now).
     */
    private final class Query {
        final Actor actor;
        final UUID venueId;
        final String profile;
        final ScanVersionService.Scope scope;
        final FrameView versionFrame;
        final Map<UUID, FloorGraph> graphs = new HashMap<>();
        final Map<Integer, RejectedObstacle> rejected = new TreeMap<>();
        final List<String> connectionProblems = new ArrayList<>();
        List<Observation> observations = List.of();
        List<Hop> hops;

        Query(Actor actor, UUID venueId, String profile, ScanVersionService.Scope scope, FrameView versionFrame) {
            this.actor = actor;
            this.venueId = venueId;
            this.profile = profile;
            this.scope = scope;
            this.versionFrame = versionFrame;
        }

        /** The floor's routable graph with this request's exclusions applied (NAVMESH_NOT_READY when there is none). */
        FloorGraph graph(UUID floorId, boolean required) {
            FloorGraph g = graphs.get(floorId);
            if (g == null) {
                g = scope == null ? loadFloorGraph(this, floorId, required) : loadVersionGraph(this);
                graphs.put(floorId, g);
            }
            return g;
        }

        List<Hop> hops() {
            if (hops == null) {
                hops = scope == null ? usableHops(actor.organizationId(), venueId, profile, connectionProblems) : List.of();
            }
            return hops;
        }

        List<RejectedObstacle> rejectedObstacles() {
            return List.copyOf(rejected.values());
        }
    }

    private final JdbcClient jdbc;
    private final TenantGuard guard;
    private final NavigationProperties props;
    private final CoordinateFrameService frames;
    private final ScanVersionService versions;

    /** Graph id -> stored graph, least recently used first. Guarded by itself. */
    private final LinkedHashMap<UUID, StoredGraph> graphCache;
    private final AtomicLong cacheHits = new AtomicLong();
    private final AtomicLong cacheMisses = new AtomicLong();

    public RouteService(JdbcClient jdbc, TenantGuard guard, NavigationProperties props, CoordinateFrameService frames,
                        ScanVersionService versions) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.props = props;
        this.frames = frames;
        this.versions = versions;
        int capacity = props.graphCacheMaxGraphs();
        this.graphCache = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<UUID, StoredGraph> eldest) {
                return size() > capacity;
            }
        };
    }

    /** One floor in the floor-transition graph. routable: the floor has a navigation graph this profile can route on (if
     * not, problem says why). */
    public record FloorNode(UUID floorId, int level, String name, boolean routable, String problem) {}

    /** A usable direction of a registered connection for the profile. */
    public record FloorEdge(UUID connectionId, String connectorType, UUID fromFloorId, UUID toFloorId) {}

    /** The venue's floors as routing sees them for one profile: which connections it may use, which it may not and why,
     * and which floors are connected (components: floors mutually reachable through usable connections). */
    public record FloorTransitionGraph(String accessibilityProfile, List<FloorNode> floors, List<FloorEdge> edges,
                                       List<String> unusableConnections, List<List<UUID>> components) {}

    /** The floor-transition graph RouteService routes over (the same usableHops rules), for the venue's live floors. */
    @Transactional(readOnly = true)
    public FloorTransitionGraph floorTransitionGraph(Actor actor, UUID venueId, String accessibility) {
        guard.requireVenue(actor, venueId);
        String profile = normalizeProfile(accessibility);
        List<String> problems = new ArrayList<>();
        List<Hop> hops = usableHops(actor.organizationId(), venueId, profile, problems);
        record F(UUID id, int level, String name) {}
        List<F> floors = jdbc.sql("SELECT id, level, name FROM floor WHERE venue_id = :v AND organization_id = :o AND deleted_at IS NULL "
                + "ORDER BY level").param("v", venueId).param("o", actor.organizationId())
            .query((rs, i) -> new F(rs.getObject(1, UUID.class), rs.getInt(2), rs.getString(3))).list();
        List<FloorNode> nodes = new ArrayList<>();
        for (F f : floors) {
            String problem = notReadyReason(venueId, f.id(), profile);
            nodes.add(new FloorNode(f.id(), f.level(), f.name(), problem == null, problem));
        }
        List<FloorEdge> edges = hops.stream().map(h -> new FloorEdge(h.connection().id(), h.connection().connectorType(), h.fromFloor(),
            h.toFloor())).toList();
        // Components of mutual reachability (a one-way connection alone does not join two floors).
        List<List<UUID>> components = new ArrayList<>();
        Set<UUID> placed = new HashSet<>();
        for (F f : floors) {
            if (placed.contains(f.id())) {
                continue;
            }
            List<UUID> component = new ArrayList<>();
            for (F g : floors) {
                if (!placed.contains(g.id()) && (g.id().equals(f.id())
                    || (floorsConnected(hops, f.id(), g.id()) && floorsConnected(hops, g.id(), f.id())))) {
                    component.add(g.id());
                    placed.add(g.id());
                }
            }
            components.add(component);
        }
        return new FloorTransitionGraph(profile, nodes, edges, problems, components);
    }

    /** How the graph cache has been used since start: lookups served from memory, lookups that read the database. */
    public record GraphCacheStats(long hits, long misses, int size) {}

    public GraphCacheStats graphCacheStats() {
        synchronized (graphCache) {
            return new GraphCacheStats(cacheHits.get(), cacheMisses.get(), graphCache.size());
        }
    }

    @Transactional(readOnly = true)
    public RouteResponse route(Actor actor, RouteRequest request) {
        guard.requireVenue(actor, request.venueId());
        String profile = normalizeProfile(request.accessibility());
        requireFloor(request.venueId(), request.floorId());
        Query q = query(actor, request.venueId(), request.floorId(), profile, request.scanVersionId());
        PoiRow destination = resolveDestination(q, request.destinationPoiId(), request.floorId());
        acceptObstacles(q, request.blockedRegions());
        double[] start = parseStart(request.start());
        return routeLeg(q, request.floorId(), start, destination);
    }

    /** The request's routing context: on what each floor publishes now, or (scanVersionId) on that FINALIZED version of
     * the start floor. See the class comment, "Scan versions". */
    private Query query(Actor actor, UUID venueId, UUID floorId, String profile, UUID scanVersionId) {
        if (scanVersionId == null) {
            return new Query(actor, venueId, profile, null, null);
        }
        ScanVersionService.Scope scope = versions.requireFinalized(actor, venueId, scanVersionId);
        if (!scope.floorId().equals(floorId)) {
            throw new ApiException(HttpStatus.CONFLICT, "VERSION_WRONG_FLOOR",
                "scan version " + scope.id() + " is a version of floor " + scope.floorId() + ", not " + floorId);
        }
        FrameView frame = frames.load(scope.coordinateFrameId()).filter(FrameView::canonical)
            .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "METRIC_CALIBRATION_REQUIRED",
                "scan version " + scope.id() + " has no canonical coordinate frame"));
        frames.requireGuidanceEvidence(frame, "routing in scan version " + scope.id());
        return new Query(actor, venueId, profile, scope, frame);
    }

    /** The destination POI as the query routes to it, after every check that it has a metric position in the frame and
     * version the query routes in. startFloorId: the floor the route to it starts on, which must be calibrated too. */
    private PoiRow resolveDestination(Query q, UUID poiId, UUID startFloorId) {
        if (q.scope != null) {
            return resolveVersionDestination(q, poiId);
        }
        PoiRow destination = loadPoi(q.actor.organizationId(), q.venueId, poiId)
            .orElseThrow(() -> new NotFoundException("destination POI not found"));
        if (destination.floorId() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "METRIC_CALIBRATION_REQUIRED",
                "the destination POI is not placed on any floor, so it has no metric position to route to");
        }
        if (!destination.current()) {
            throw new ApiException(HttpStatus.CONFLICT, ScanVersionService.VERSION_MISMATCH, "the destination POI "
                + (destination.scanVersionId() == null ? "was never placed against a published scan version"
                    : "was placed against scan version " + destination.scanVersionId())
                + ", which is not (an ancestor of) the version its floor publishes now ("
                + versions.currentOf(destination.floorId()).map(UUID::toString).orElse("none")
                + "); route on that version with scanVersionId, or re-place the POI");
        }
        requireMetricFrame(q.venueId, startFloorId);
        FrameView destinationFrame = requireMetricFrame(q.venueId, destination.floorId());
        if (!destinationFrame.id().equals(destination.frameId())) {
            throw new ApiException(HttpStatus.CONFLICT, "METRIC_CALIBRATION_REQUIRED", "the destination POI's coordinates are "
                + (destination.frameId() == null ? "not bound to any calibrated coordinate frame"
                    : "in coordinate frame " + destination.frameId() + ", not its floor's current frame " + destinationFrame.id())
                + "; re-place it in the current metric frame before routing to it");
        }
        return destination;
    }

    private PoiRow resolveVersionDestination(Query q, UUID poiId) {
        ScanVersionService.Scope scope = q.scope;
        PoiRow destination = jdbc.sql("""
                SELECT p.id, p.floor_id, v.x, v.y, v.z, v.coordinate_frame_id, v.scan_version_id
                  FROM poi p JOIN poi_version v ON v.id = scan_version_poi_version(p.id, :sv)
                 WHERE p.id = :poi AND p.venue_id = :venue AND p.organization_id = :org
                """)
            .param("sv", scope.id()).param("poi", poiId).param("venue", q.venueId).param("org", q.actor.organizationId())
            .query((rs, i) -> new PoiRow(rs.getObject("id", UUID.class), rs.getObject("floor_id", UUID.class),
                rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("z"), rs.getObject("coordinate_frame_id", UUID.class),
                rs.getObject("scan_version_id", UUID.class), true))
            .optional().orElseThrow(() -> new NotFoundException("destination POI is not part of scan version " + scope.id()));
        if (!scope.floorId().equals(destination.floorId())) {
            throw new ApiException(HttpStatus.CONFLICT, "VERSION_WRONG_FLOOR",
                "a route on a scan version stays on that version's floor; the destination POI is on floor " + destination.floorId());
        }
        if (!q.versionFrame.id().equals(destination.frameId())) {
            throw new ApiException(HttpStatus.CONFLICT, "METRIC_CALIBRATION_REQUIRED", "in scan version " + scope.id()
                + " the destination POI is not placed in the version's coordinate frame " + q.versionFrame.id());
        }
        return destination;
    }

    /** The scan version's graph: the navigation graph of the navmesh the version pinned, in the version's frame. */
    private FloorGraph loadVersionGraph(Query q) {
        ScanVersionService.Scope scope = q.scope;
        String profile = q.profile;
        ActiveGraph graph = jdbc.sql("""
                SELECT g.id, g.coordinate_frame_id, g.source, g.navmesh_sha256, g.recastnavigation_version, pin.scan_version_id,
                       g.status
                  FROM navigation_graph g
                  JOIN scan_version_artifact pin ON pin.artifact_id = g.navmesh_artifact_id
                 WHERE pin.scan_version_id = :sv AND pin.kind = 'NAVMESH' AND g.floor_id = :f AND g.profile = :p
                   AND g.source = 'RECAST_NAVMESH'
                 ORDER BY g.created_at DESC LIMIT 1
                """)
            .param("sv", scope.id()).param("f", scope.floorId()).param("p", profile)
            .query((rs, i) -> new ActiveGraph(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getObject(6, UUID.class), rs.getString(7))).optional()
            .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "NAVMESH_NOT_READY", "scan version " + scope.id()
                + " has no " + profile + " navigation graph baked from a Recast navmesh"));
        if (!q.versionFrame.id().equals(graph.frameId())) {
            throw new ApiException(HttpStatus.CONFLICT, "NAVMESH_NOT_READY", "the " + profile + " navigation graph of scan version "
                + scope.id() + " was baked in coordinate frame " + graph.frameId() + ", not the version's frame " + q.versionFrame.id());
        }
        if (missingPortals(graph.id())) {
            throw new ApiException(HttpStatus.CONFLICT, "NAVMESH_NOT_READY", "the " + profile + " navigation graph of scan version "
                + scope.id() + " was baked before navmesh portals were recorded, so no route along the navmesh can be drawn on it");
        }
        return buildFloorGraph(q, graph, scope.floorId());
    }

    /** One route on the query, from `start` on startFloor to the destination POI. */
    private RouteResponse routeLeg(Query q, UUID startFloor, double[] start, PoiRow destination) {
        if (q.scope != null && !q.scope.floorId().equals(startFloor)) {
            throw new ApiException(HttpStatus.CONFLICT, "VERSION_WRONG_FLOOR",
                "a route on a scan version stays on that version's floor; the start is on floor " + startFloor);
        }
        requireOnNavmesh(q.graph(startFloor, true), start, "START");
        requireOnNavmesh(q.graph(destination.floorId(), true), destination.position(), "DESTINATION");
        List<Hop> hops = q.hops();
        if (!destination.floorId().equals(startFloor) && !floorsConnected(hops, startFloor, destination.floorId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "FLOOR_CONNECTION_UNAVAILABLE",
                "no usable registered floor connection joins floor " + startFloor + " to floor " + destination.floorId()
                    + (q.profile.equals(STEP_FREE) ? " step-free (only elevators and ramps with registered, passing and verified"
                        + " clear width and slope qualify)" : "")
                    + (q.connectionProblems.isEmpty() ? "" : "; unusable connections: " + String.join("; ", q.connectionProblems)));
        }
        return solve(q, hops, startFloor, start, destination);
    }

    private static double[] parseStart(List<Double> start) {
        double[] p = {start.get(0) == null ? Double.NaN : start.get(0), start.get(1) == null ? Double.NaN : start.get(1),
            start.get(2) == null ? Double.NaN : start.get(2)};
        if (!Double.isFinite(p[0]) || !Double.isFinite(p[1]) || !Double.isFinite(p[2])) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_START", "start must be three finite canonical coordinates");
        }
        return p;
    }

    /**
     * Refuses a start or destination that cannot be on this floor's navmesh: outside the horizontal footprint of the
     * graph's polygons and portals (plus endpointBoundsMarginM), far above or below every walkable surface, or with no
     * polygon of the graph within nodeSnapMaxDistanceMeters at all. Obstacles are not considered here: a point that only
     * reported obstacles cut off is a NO_ROUTE, not a bad endpoint.
     */
    private void requireOnNavmesh(FloorGraph graph, double[] p, String which) {
        double[] b = graph.stored().bounds();
        if (b == null) {
            return; // a graph without nodes: nothing can snap, and the search reports NO_ROUTE
        }
        double m = props.endpointBoundsMarginM();
        String what = which.equals("START") ? "the start" : "the destination";
        if (p[0] < b[0] - m || p[0] > b[3] + m || p[1] < b[1] - m || p[1] > b[4] + m
            || p[2] < b[2] - BELOW_FLOOR_TOLERANCE_M || p[2] > b[5] + ABOVE_FLOOR_TOLERANCE_M) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, which + "_OUT_OF_BOUNDS", String.format(Locale.ROOT,
                "%s (%.2f, %.2f, %.2f) is outside the navigable area of floor %s (x %.2f..%.2f, y %.2f..%.2f, walkable height "
                    + "%.2f..%.2f m, canonical metres in the floor's routing frame)", what, p[0], p[1], p[2], graph.floorId(),
                b[0], b[3], b[1], b[4], b[2], b[5]));
        }
        double nearest = Double.MAX_VALUE;
        for (double[] node : graph.stored().positions().values()) {
            nearest = Math.min(nearest, distance(node, p));
        }
        if (nearest > props.nodeSnapMaxDistanceMeters()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, which + "_OFF_NAVMESH", String.format(Locale.ROOT,
                "%s (%.2f, %.2f, %.2f) is %.2f m from the nearest walkable navmesh polygon of floor %s (at most %.2f m is snapped)",
                what, p[0], p[1], p[2], nearest, graph.floorId(), props.nodeSnapMaxDistanceMeters()));
        }
    }

    // ---- multi-stop routing ---------------------------------------------------------------------------------

    /** A segment's result: a route, or the failure a single route between the same points gets. */
    private record Outcome(RouteResponse route, String code, String message) {
        static Outcome failed(String code, String message) {
            return new Outcome(null, code, message);
        }

        boolean ok() {
            return route != null;
        }

        double seconds() {
            return ok() ? route.estimatedDurationSeconds() : Double.POSITIVE_INFINITY;
        }
    }

    /** See the class comment, "Multi-stop". */
    @Transactional(readOnly = true)
    public MultiStopResponse routeMultiStop(Actor actor, MultiStopRequest request) {
        guard.requireVenue(actor, request.venueId());
        String profile = normalizeProfile(request.accessibility());
        requireFloor(request.venueId(), request.floorId());
        List<UUID> stops = validateStops(request.stopPoiIds());
        Query q = query(actor, request.venueId(), request.floorId(), profile, request.scanVersionId());
        acceptObstacles(q, request.blockedRegions());
        double[] start = parseStart(request.start());
        if (q.scope == null) {
            requireMetricFrame(q.venueId, request.floorId());
        }
        // Problems with the start fail the whole request; problems with a stop only make that stop unreachable.
        requireOnNavmesh(q.graph(request.floorId(), true), start, "START");

        Map<UUID, PoiRow> resolved = new LinkedHashMap<>();
        Map<UUID, Outcome> failures = new HashMap<>();
        for (UUID stop : stops) {
            try {
                resolved.put(stop, resolveDestination(q, stop, request.floorId()));
            } catch (ApiException e) {
                failures.put(stop, Outcome.failed(e.code(), e.getMessage()));
            } catch (NotFoundException e) {
                failures.put(stop, Outcome.failed("POI_NOT_FOUND", e.getMessage()));
            }
        }

        Map<String, Outcome> memo = new HashMap<>();
        Point startPoint = new Point("START", request.floorId(), start);
        List<UUID> order = new ArrayList<>();
        if (request.optimizeOrder()) {
            List<UUID> candidates = new ArrayList<>();
            for (UUID stop : resolved.keySet()) {
                Outcome o = segment(q, memo, startPoint, resolved.get(stop));
                if (o.ok()) {
                    candidates.add(stop);
                } else {
                    failures.put(stop, o);
                }
            }
            List<UUID> best = cheapestOrder(q, memo, startPoint, candidates, resolved);
            if (best != null) {
                order.addAll(best);
            } else {
                // Only possible with one-way edges: no order visits every stop. Fall back to the requested order, skipping.
                order.addAll(visitInOrder(q, memo, startPoint, candidates, resolved, failures));
            }
        } else {
            order.addAll(visitInOrder(q, memo, startPoint, new ArrayList<>(resolved.keySet()), resolved, failures));
        }

        if (order.isEmpty()) {
            List<String> reasons = new ArrayList<>();
            for (UUID stop : stops) {
                Outcome f = failures.get(stop);
                reasons.add(stop + ": " + (f == null ? "not reached" : f.code() + " (" + f.message() + ")"));
            }
            throw new ApiException(HttpStatus.NOT_FOUND, "NO_REACHABLE_STOP",
                "none of the " + stops.size() + " stop(s) can be reached from the start: " + String.join("; ", reasons));
        }
        return assembleMultiStop(q, memo, startPoint, stops, order, resolved, failures, request.optimizeOrder());
    }

    private List<UUID> validateStops(List<UUID> stops) {
        if (stops == null || stops.isEmpty() || stops.stream().anyMatch(java.util.Objects::isNull)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_STOPS", "stopPoiIds must name at least one POI");
        }
        int max = Math.min(props.maxStops(), MAX_OPTIMISED_STOPS);
        if (stops.size() > max) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_STOPS", "at most " + max + " stops may be routed at once");
        }
        if (new HashSet<>(stops).size() != stops.size()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_STOPS", "each stop may be named only once");
        }
        return stops;
    }

    private static Point pointOf(PoiRow poi) {
        return new Point("POI:" + poi.id(), poi.floorId(), poi.position());
    }

    /** The route from `from` to the stop, computed once per pair within the request. */
    private Outcome segment(Query q, Map<String, Outcome> memo, Point from, PoiRow to) {
        String key = from.key() + ">" + to.id();
        Outcome cached = memo.get(key);
        if (cached != null) {
            return cached;
        }
        Outcome o;
        try {
            o = new Outcome(routeLeg(q, from.floorId(), from.position(), to), null, null);
        } catch (ApiException e) {
            o = Outcome.failed(e.code(), e.getMessage());
        } catch (NotFoundException e) {
            o = Outcome.failed("POI_NOT_FOUND", e.getMessage());
        }
        memo.put(key, o);
        return o;
    }

    /** Visits `stops` in the given order; a stop that cannot be reached from the last reached point is recorded in
     * `failures` and skipped. Returns the stops reached, in order. */
    private List<UUID> visitInOrder(Query q, Map<String, Outcome> memo, Point start, List<UUID> stops, Map<UUID, PoiRow> resolved,
                                    Map<UUID, Outcome> failures) {
        List<UUID> reached = new ArrayList<>();
        Point here = start;
        for (UUID stop : stops) {
            PoiRow poi = resolved.get(stop);
            Outcome o = segment(q, memo, here, poi);
            if (o.ok()) {
                reached.add(stop);
                here = pointOf(poi);
            } else {
                failures.put(stop, o);
            }
        }
        return reached;
    }

    /** The order of `stops` (each reachable from the start) with the least total estimated duration, starting at `start`
     * and ending at whichever stop is last: Held-Karp dynamic programming over subsets, exact. Ties are broken by a fixed
     * iteration order, so the same request always gets the same order. Null when no order visits them all. */
    private List<UUID> cheapestOrder(Query q, Map<String, Outcome> memo, Point start, List<UUID> stops, Map<UUID, PoiRow> resolved) {
        int n = stops.size();
        if (n == 0) {
            return List.of();
        }
        double[] fromStart = new double[n];
        double[][] cost = new double[n][n];
        for (int i = 0; i < n; i++) {
            fromStart[i] = segment(q, memo, start, resolved.get(stops.get(i))).seconds();
            for (int j = 0; j < n; j++) {
                cost[i][j] = i == j ? 0 : segment(q, memo, pointOf(resolved.get(stops.get(i))), resolved.get(stops.get(j))).seconds();
            }
        }
        int full = (1 << n) - 1;
        double[][] best = new double[1 << n][n];
        int[][] previous = new int[1 << n][n];
        for (double[] row : best) {
            Arrays.fill(row, Double.POSITIVE_INFINITY);
        }
        for (int i = 0; i < n; i++) {
            best[1 << i][i] = fromStart[i];
            previous[1 << i][i] = -1;
        }
        for (int set = 1; set <= full; set++) {
            for (int last = 0; last < n; last++) {
                if ((set & (1 << last)) == 0 || best[set][last] == Double.POSITIVE_INFINITY) {
                    continue;
                }
                for (int next = 0; next < n; next++) {
                    if ((set & (1 << next)) != 0) {
                        continue;
                    }
                    int grown = set | (1 << next);
                    double c = best[set][last] + cost[last][next];
                    if (c < best[grown][next]) {
                        best[grown][next] = c;
                        previous[grown][next] = last;
                    }
                }
            }
        }
        int last = -1;
        for (int i = 0; i < n; i++) {
            if (best[full][i] < (last < 0 ? Double.POSITIVE_INFINITY : best[full][last])) {
                last = i;
            }
        }
        if (last < 0) {
            return null;
        }
        List<UUID> order = new ArrayList<>();
        for (int set = full, i = last; i >= 0; ) {
            order.add(stops.get(i));
            int p = previous[set][i];
            set &= ~(1 << i);
            i = p;
        }
        Collections.reverse(order);
        return order;
    }

    private MultiStopResponse assembleMultiStop(Query q, Map<String, Outcome> memo, Point start, List<UUID> requested,
                                                List<UUID> order, Map<UUID, PoiRow> resolved, Map<UUID, Outcome> failures,
                                                boolean optimized) {
        List<RouteSegment> segments = new ArrayList<>();
        List<Waypoint> waypoints = new ArrayList<>();
        Set<RoutingSource> sources = new LinkedHashSet<>();
        double distance = 0;
        double seconds = 0;
        Point here = start;
        UUID fromPoi = null;
        for (int s = 0; s < order.size(); s++) {
            PoiRow poi = resolved.get(order.get(s));
            RouteResponse route = segment(q, memo, here, poi).route();
            segments.add(new RouteSegment(s, fromPoi, poi.id(), route));
            List<Waypoint> w = route.waypoints();
            boolean last = s == order.size() - 1;
            // A segment starts where the previous one ended (the previous stop's position): that point is listed once.
            for (int i = s == 0 ? 0 : 1; i < w.size(); i++) {
                Waypoint p = w.get(i);
                boolean end = i == w.size() - 1;
                waypoints.add(end && !last ? new Waypoint(p.x(), p.y(), p.z(), p.floorId(), "STOP") : p);
            }
            distance += route.distanceMeters();
            seconds += route.estimatedDurationSeconds();
            sources.addAll(route.routingSources());
            here = pointOf(poi);
            fromPoi = poi.id();
        }
        List<StopResult> stopResults = new ArrayList<>();
        for (int i = 0; i < requested.size(); i++) {
            UUID stop = requested.get(i);
            int visit = order.indexOf(stop);
            Outcome f = failures.get(stop);
            stopResults.add(visit >= 0 ? new StopResult(stop, i, visit, "REACHED", null, null)
                : new StopResult(stop, i, null, "UNREACHABLE", f == null ? "NOT_REACHED" : f.code(), f == null ? null : f.message()));
        }
        return new MultiStopResponse(List.copyOf(order), optimized, order.size() == requested.size(), stopResults, segments,
            waypoints, distance, seconds, q.profile, constraints(q), List.copyOf(sources), q.rejectedObstacles());
    }

    // ---- solving and request validation -------------------------------------------------------------------

    private RouteResponse solve(Query q, List<Hop> hops, UUID startFloor, double[] start, PoiRow destination) {
        Point startPoint = new Point("START", startFloor, start);
        Point destPoint = new Point("DESTINATION", destination.floorId(), destination.position());
        Map<String, Step> steps = search(q, hops, startPoint, destPoint);
        if (steps == null) {
            Set<String> excluded = new LinkedHashSet<>();
            for (FloorGraph g : q.graphs.values()) {
                String d = g.exclusions().describe();
                if (!d.isEmpty()) {
                    excluded.add("floor " + g.floorId() + d);
                }
            }
            String detail = (destination.floorId().equals(startFloor)
                    ? "no walkable path connects the start point to the destination on this floor"
                    : "no walkable path connects the start point, the usable floor connections and the destination")
                + (excluded.isEmpty() ? "" : "; " + String.join("; ", excluded));
            if (q.profile.equals(STEP_FREE)) {
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
        return assembleResponse(legs, used, q.profile, constraints(q), q.rejectedObstacles());
    }

    private List<String> constraints(Query q) {
        List<String> constraints = new ArrayList<>();
        if (q.profile.equals(STEP_FREE)) {
            constraints.add("uses only edges of the STEP_FREE graph baked from the navmesh");
            constraints.add("uses only edges whose measured slope against canonical +Z is at most "
                + round(props.maxAccessibleSlopeDeg()) + " degrees; edges with no measured slope are excluded");
            constraints.add("uses only edges whose measured clear width (the corridor at the Detour portal, measured on the "
                + "reconstruction's obstacle geometry) is at least " + props.minAccessibleClearanceM() + " m; edges with no measured "
                + "clearance are excluded");
            constraints.add("crosses floors only by registered elevators or ramps with a registered clear width of at least "
                + props.minAccessibleClearanceM() + " m (and, for a ramp, a registered slope within the limit) whose accessibility "
                + "was verified on site; never stairs");
        }
        int used = q.observations.size() - (int) q.rejected.values().stream()
            .filter(r -> r.reason().equals("FRAME_MISMATCH")).count();
        if (used > 0) {
            constraints.add(used + " reported obstacle region(s): graph nodes inside them and route segments "
                + "crossing them are excluded");
        }
        if (!q.rejected.isEmpty()) {
            constraints.add(q.rejected.size() + " reported obstacle region(s) rejected and not routed around (see rejectedObstacles)");
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

    /** Validates every reported obstacle (400 for a malformed one), then keeps those observed recently enough. A stale
     * observation, or one stamped in the future beyond the allowed clock skew, is rejected and reported, not used. */
    private void acceptObstacles(Query q, List<BlockedRegion> blocked) {
        List<BlockedRegion> regions = blocked == null ? List.of() : blocked;
        Instant now = Instant.now();
        Duration maxAge = Duration.ofMillis(Math.round(props.obstacleMaxAgeSeconds() * 1000));
        Duration skew = Duration.ofMillis(Math.round(props.obstacleMaxFutureSkewSeconds() * 1000));
        List<Observation> accepted = new ArrayList<>();
        for (int i = 0; i < regions.size(); i++) {
            BlockedRegion r = regions.get(i);
            boolean finite = Double.isFinite(r.minX()) && Double.isFinite(r.minY()) && Double.isFinite(r.maxX()) && Double.isFinite(r.maxY());
            if (!finite || r.minX() > r.maxX() || r.minY() > r.maxY()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_BLOCKED_REGION",
                    "a blocked region needs finite canonical bounds with min <= max");
            }
            if (r.observedAt() == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_BLOCKED_REGION",
                    "a blocked region needs observedAt: an observation without a time cannot be judged current");
            }
            if (r.observedAt().isBefore(now.minus(maxAge))) {
                q.rejected.put(i, new RejectedObstacle(i, r.floorId(), "STALE_OBSERVATION", "observed at " + r.observedAt()
                    + ", more than " + props.obstacleMaxAgeSeconds() + " s ago; observe it again to route around it"));
            } else if (r.observedAt().isAfter(now.plus(skew))) {
                q.rejected.put(i, new RejectedObstacle(i, r.floorId(), "FUTURE_OBSERVATION", "observed at " + r.observedAt()
                    + ", later than the server's clock allows (" + props.obstacleMaxFutureSkewSeconds() + " s skew)"));
            } else {
                accepted.add(new Observation(i, r));
            }
        }
        q.observations = accepted;
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
        FrameView frame = metricFrame(venueId, floorId).orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "METRIC_CALIBRATION_REQUIRED",
            "floor " + floorId + " has no calibrated metric, gravity-aligned coordinate frame, so its navigation data cannot be "
                + "in metres; calibrate its reconstruction first"));
        // Metres are only advertised from a frame calibrated on measured observations (docs/coordinate-frames.md §3).
        return frames.requireGuidanceEvidence(frame, "routing on floor " + floorId);
    }

    private Optional<PoiRow> loadPoi(UUID orgId, UUID venueId, UUID poiId) {
        return jdbc.sql("""
                SELECT p.id, p.floor_id, v.x, v.y, v.z, v.coordinate_frame_id, v.scan_version_id,
                       poi_version_is_current(p.floor_id, v.scan_version_id, v.source) AS current
                  FROM poi p
                  JOIN poi_version v ON v.poi_id = p.id
                 WHERE p.id = :poi AND p.venue_id = :venue AND p.organization_id = :org AND p.deleted_at IS NULL
                   AND v.version_number = (SELECT max(version_number) FROM poi_version WHERE poi_id = p.id)
                """)
            .param("poi", poiId).param("venue", venueId).param("org", orgId)
            .query((rs, i) -> new PoiRow(rs.getObject("id", UUID.class), rs.getObject("floor_id", UUID.class),
                rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("z"), rs.getObject("coordinate_frame_id", UUID.class),
                rs.getObject("scan_version_id", UUID.class), rs.getBoolean("current")))
            .optional();
    }

    // ---- floor graphs ---------------------------------------------------------------------------------

    private ActiveGraph activeGraph(UUID venueId, UUID floorId, String profile) {
        return jdbc.sql("SELECT id, coordinate_frame_id, source, navmesh_sha256, recastnavigation_version, scan_version_id, status "
                + "FROM navigation_graph WHERE venue_id = :v AND floor_id = :f AND profile = :p AND status = 'ACTIVE'")
            .param("v", venueId).param("f", floorId).param("p", profile)
            .query((rs, i) -> new ActiveGraph(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getObject(6, UUID.class), rs.getString(7))).optional().orElse(null);
    }

    /** Why the floor cannot be routed on (no metric frame, no navmesh-backed graph, or one baked in an older frame), or
     * null when it can. */
    private String notReadyReason(UUID venueId, UUID floorId, String profile) {
        Optional<FrameView> frame = metricFrame(venueId, floorId);
        if (frame.isEmpty()) {
            return "floor " + floorId + " has no calibrated metric frame";
        }
        if (!frames.eligibleForGuidance(frame.get())) {
            return "floor " + floorId + " coordinate frame was calibrated from hand-entered points ("
                + frame.get().pointSource() + "), not measured observations, so metric routing cannot be advertised";
        }
        Optional<UUID> current = versions.currentOf(floorId);
        if (current.isEmpty()) {
            return "floor " + floorId + " has not published a scan version, so nothing derived from a scan is current there";
        }
        ActiveGraph graph = activeGraph(venueId, floorId, profile);
        if (graph != null && !current.get().equals(graph.scanVersionId())) {
            // V28 refuses this state at commit; never route on it if it is seen anyway.
            return "the ACTIVE " + profile + " navigation graph of floor " + floorId + " belongs to scan version "
                + graph.scanVersionId() + ", not the version the floor publishes (" + current.get() + ")";
        }
        if (graph == null || !("RECAST_NAVMESH".equals(graph.source()) || props.acceptSyntheticGraphs())) {
            return "floor " + floorId + " has no " + profile + " navigation graph baked from a Recast navmesh"
                + (graph == null ? "" : " (its ACTIVE graph " + graph.id() + " is " + graph.source() + ", which is never routed on)");
        }
        if (!frame.get().id().equals(graph.frameId())) {
            return "the " + profile + " navigation graph of floor " + floorId + " was baked in coordinate frame " + graph.frameId()
                + ", not the floor's current frame " + frame.get().id() + ", and must be baked again";
        }
        if ("RECAST_NAVMESH".equals(graph.source()) && missingPortals(graph.id())) {
            return "the " + profile + " navigation graph of floor " + floorId + " was baked before navmesh portals were recorded, so "
                + "no route along the navmesh can be drawn on it, and must be baked again";
        }
        return null;
    }

    /** Whether any edge of the graph lacks its Detour portal (V25). */
    private boolean missingPortals(UUID graphId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM navigation_edge WHERE graph_id = :g AND portal_ax IS NULL)")
            .param("g", graphId).query(Boolean.class).single();
    }

    /** Loads the floor's routable graph with every exclusion for this query applied. `required` floors (start and
     * destination) fail the request with NAVMESH_NOT_READY; any other floor is only ever loaded after notReadyReason
     * cleared it. */
    private FloorGraph loadFloorGraph(Query q, UUID floorId, boolean required) {
        String problem = notReadyReason(q.venueId, floorId, q.profile);
        if (problem != null) {
            Optional<FrameView> mf = metricFrame(q.venueId, floorId);
            if (mf.isEmpty() || !frames.eligibleForGuidance(mf.get())) {
                requireMetricFrame(q.venueId, floorId);
            }
            throw new ApiException(HttpStatus.CONFLICT, "NAVMESH_NOT_READY", problem
                + "; run NAVIGATION_BAKING on a calibrated reconstruction of this floor" + (required ? "" : " (intermediate floor)"));
        }
        requireMetricFrame(q.venueId, floorId);
        return buildFloorGraph(q, activeGraph(q.venueId, floorId, q.profile), floorId);
    }

    /** The graph's stored nodes and edges: from the cache when it has left DRAFT (and so can never change), else read. */
    private StoredGraph storedGraph(ActiveGraph active) {
        boolean immutable = !"DRAFT".equals(active.status());
        if (immutable) {
            synchronized (graphCache) {
                StoredGraph cached = graphCache.get(active.id());
                if (cached != null) {
                    cacheHits.incrementAndGet();
                    return cached;
                }
            }
        }
        cacheMisses.incrementAndGet();
        Map<UUID, double[]> positions = new HashMap<>();
        jdbc.sql("SELECT id, x, y, z FROM navigation_node WHERE graph_id = :g").param("g", active.id())
            .query((rs, i) -> Map.entry(rs.getObject("id", UUID.class), new double[]{rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("z")}))
            .list().forEach(e -> positions.put(e.getKey(), e.getValue()));
        List<EdgeRow> edges = jdbc.sql("SELECT from_node_id, to_node_id, bidirectional, step_free, min_clearance_m, max_slope_deg, "
                + "portal_ax, portal_ay, portal_az, portal_bx, portal_by, portal_bz FROM navigation_edge WHERE graph_id = :g "
                + "ORDER BY from_node_id, to_node_id")
            .param("g", active.id())
            .query((rs, i) -> new EdgeRow(rs.getObject("from_node_id", UUID.class), rs.getObject("to_node_id", UUID.class),
                rs.getBoolean("bidirectional"), rs.getBoolean("step_free"), nullableDouble(rs.getObject("min_clearance_m")),
                nullableDouble(rs.getObject("max_slope_deg")),
                rs.getObject("portal_ax") == null ? null : new double[][]{
                    {rs.getDouble("portal_ax"), rs.getDouble("portal_ay"), rs.getDouble("portal_az")},
                    {rs.getDouble("portal_bx"), rs.getDouble("portal_by"), rs.getDouble("portal_bz")}}))
            .list();
        double[] bounds = null;
        List<double[]> extent = new ArrayList<>(positions.values());
        edges.stream().filter(e -> e.portal() != null).forEach(e -> extent.addAll(List.of(e.portal())));
        for (double[] p : extent) {
            if (bounds == null) {
                bounds = new double[]{p[0], p[1], p[2], p[0], p[1], p[2]};
            }
            for (int k = 0; k < 3; k++) {
                bounds[k] = Math.min(bounds[k], p[k]);
                bounds[k + 3] = Math.max(bounds[k + 3], p[k]);
            }
        }
        StoredGraph stored = new StoredGraph(Collections.unmodifiableMap(positions), List.copyOf(edges), bounds);
        if (immutable) {
            synchronized (graphCache) {
                graphCache.put(active.id(), stored);
            }
        }
        return stored;
    }

    /** The routable graph of `active` with every exclusion for this query applied. A reported obstacle on this floor that
     * names another coordinate frame than the graph's is rejected (FRAME_MISMATCH), not applied. */
    private FloorGraph buildFloorGraph(Query q, ActiveGraph active, UUID floorId) {
        List<Box> boxes = new ArrayList<>();
        for (Observation o : q.observations) {
            BlockedRegion r = o.region();
            if (!r.floorId().equals(floorId)) {
                continue;
            }
            if (r.coordinateFrameId() != null && !r.coordinateFrameId().equals(active.frameId())) {
                q.rejected.put(o.index(), new RejectedObstacle(o.index(), floorId, "FRAME_MISMATCH", "observed in coordinate frame "
                    + r.coordinateFrameId() + ", but floor " + floorId + " is routed in " + active.frameId()));
                continue;
            }
            boxes.add(new Box(r.minX(), r.minY(), r.maxX(), r.maxY()));
        }
        Exclusions exclusions = new Exclusions();
        StoredGraph stored = storedGraph(active);

        Map<UUID, double[]> positions = new HashMap<>(stored.positions());
        positions.values().removeIf(p -> {
            boolean inside = boxes.stream().anyMatch(b -> b.contains(p));
            if (inside) {
                exclusions.blockedNodes++;
            }
            return inside;
        });

        boolean stepFree = q.profile.equals(STEP_FREE);
        double halfWidth = stepFree ? Math.max(props.agentRadiusM(), props.minAccessibleClearanceM() / 2) : props.agentRadiusM();
        Map<UUID, List<UUID>> adjacency = new HashMap<>();
        Map<List<UUID>, double[][]> portals = new HashMap<>();
        for (EdgeRow e : stored.edges()) {
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
            // The edge is walked through its portal: if reported obstacles leave no opening there a body fits through, the
            // edge is closed; otherwise the route is pulled through the part they leave free.
            double[][] portal = e.portal() == null ? null : freePortal(e.portal(), boxes, halfWidth, e.minClearanceM());
            if (e.portal() != null && portal == null) {
                exclusions.blockedEdges++;
                continue;
            }
            adjacency.computeIfAbsent(e.from(), k -> new ArrayList<>()).add(e.to());
            if (e.bidirectional()) {
                adjacency.computeIfAbsent(e.to(), k -> new ArrayList<>()).add(e.from());
            }
            if (portal != null) {
                portals.put(List.of(e.from(), e.to()), portal);
                portals.put(List.of(e.to(), e.from()), portal);
            }
        }
        RoutingSource source = new RoutingSource(floorId, active.id(), active.source(), active.navmeshSha256(), active.recastVersion(),
            null, active.scanVersionId(), active.frameId());
        return new FloorGraph(floorId, positions, adjacency, portals, boxes, source, exclusions, stored);
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
        List<UUID> corridor = new ArrayList<>();
        for (UUID cur = endNode; cur != null; cur = prev.get(cur)) {
            corridor.add(cur);
        }
        Collections.reverse(corridor);
        List<double[]> centroids = new ArrayList<>();
        centroids.add(from);
        corridor.forEach(id -> centroids.add(graph.positions().get(id)));
        centroids.add(to);

        // The reported path: the corridor string-pulled through its portals; if that crosses a reported obstacle, the
        // portal midpoints; failing that the centroids, whose segments were already checked against every obstacle.
        List<double[]> waypoints = centroids;
        String method = "POLYGON_CENTROIDS";
        List<double[][]> portals = new ArrayList<>();
        for (int i = 1; i < corridor.size(); i++) {
            portals.add(graph.portals().get(List.of(corridor.get(i - 1), corridor.get(i))));
        }
        if (!portals.contains(null)) {
            List<CorridorPath.Portal> oriented = CorridorPath.orient(corridor.stream().map(graph.positions()::get).toList(), portals);
            List<double[]> pulled = CorridorPath.pull(from, to, oriented);
            List<double[]> mids = CorridorPath.midpoints(from, to, oriented);
            if (!polylineCrossesAny(pulled, graph.boxes())) {
                waypoints = pulled;
                method = "STRING_PULLED";
            } else if (!polylineCrossesAny(mids, graph.boxes())) {
                waypoints = mids;
                method = "PORTAL_MIDPOINTS";
            }
        }
        RoutingSource s = graph.source();
        RoutingSource source = new RoutingSource(s.floorId(), s.graphId(), s.source(), s.navmeshSha256(), s.recastnavigationVersion(), method,
            s.scanVersionId(), s.coordinateFrameId());
        return new RouteLeg(waypoints, polylineLength(waypoints), graph.floorId(), source);
    }

    private static boolean polylineCrossesAny(List<double[]> points, List<Box> boxes) {
        for (int i = 1; i < points.size(); i++) {
            if (crossesAny(points.get(i - 1), points.get(i), boxes)) {
                return true;
            }
        }
        return false;
    }

    /** The nearest graph node within nodeSnapMaxDistanceMeters whose straight segment to the point crosses no reported
     * obstacle, or null. Ties are broken by node id, so the result does not depend on map iteration order. */
    private UUID snap(FloorGraph graph, double[] point) {
        UUID best = null;
        double bestDist = props.nodeSnapMaxDistanceMeters();
        for (Map.Entry<UUID, double[]> e : graph.positions().entrySet()) {
            double d = distance(e.getValue(), point);
            boolean closer = d < bestDist || (d == bestDist && (best == null || e.getKey().compareTo(best) < 0));
            if (closer && !crossesAny(point, e.getValue(), graph.boxes())) {
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

    /**
     * What is left of a Detour portal for a body of half-width `halfWidth` once reported obstacles are taken out, or null
     * when the body no longer fits through. With no obstacle on the opening the portal is returned unchanged.
     *
     * <p>A portal's endpoints are where Recast lets the centre of a body of agentRadiusM be (it eroded the walkable area by
     * that radius, rounded up to whole voxels), so the physical opening at the portal is the portal extended by the radius
     * at both ends -- or, when wider, the edge's measured clear width centred on the portal (Recast's rounding can make a
     * 1.1 m doorway's portal only 0.2 m long). Obstacles cut that opening into free pieces; the edge stays open when the
     * widest piece is at least the body's width (2 x halfWidth: 0.7 m walking, the accessible clear width step-free), and
     * the route then crosses where a body's centre fits in that piece, kept on the portal. Like the measured clearance this
     * is accurate to about one voxel (0.1 m).
     */
    private double[][] freePortal(double[][] portal, List<Box> boxes, double halfWidth, Double clearanceM) {
        if (boxes.isEmpty()) {
            return portal;
        }
        double[] a = portal[0];
        double[] b = portal[1];
        double length = Math.hypot(b[0] - a[0], b[1] - a[1]);
        double r = props.agentRadiusM();
        if (length == 0) {
            return boxes.stream().anyMatch(x -> grownContains(x, halfWidth, a)) ? null : portal;
        }
        double ux = (b[0] - a[0]) / length;
        double uy = (b[1] - a[1]) / length;
        double half = clearanceM == null ? 0 : clearanceM / 2;
        double from = Math.min(-r, length / 2 - half);
        double to = Math.max(length + r, length / 2 + half);
        double[] p = {a[0] + ux * from, a[1] + uy * from, a[2]};
        double[] q = {a[0] + ux * to, a[1] + uy * to, b[2]};
        double span = to - from;
        List<double[]> covered = new ArrayList<>();
        for (Box box : boxes) {
            double[] t = clip(p, q, box.minX(), box.minY(), box.maxX(), box.maxY());
            if (t != null) {
                covered.add(new double[]{from + t[0] * span, from + t[1] * span});
            }
        }
        if (covered.isEmpty()) {
            return portal; // no obstacle reaches this opening: the edge's measured clearance already describes it
        }
        covered.sort(Comparator.comparingDouble(t -> t[0]));
        double bestFrom = 0;
        double bestTo = -1;
        double cursor = from;
        for (double[] t : covered) {
            if (t[0] - cursor > bestTo - bestFrom) {
                bestFrom = cursor;
                bestTo = t[0];
            }
            cursor = Math.max(cursor, t[1]);
        }
        if (to - cursor > bestTo - bestFrom) {
            bestFrom = cursor;
            bestTo = to;
        }
        if (bestTo - bestFrom < 2 * halfWidth - 1e-9) {
            return null;
        }
        double s0 = Math.max(0, bestFrom + halfWidth);
        double s1 = Math.min(length, bestTo - halfWidth);
        if (s0 > s1) {
            s0 = s1 = Math.max(0, Math.min(length, (bestFrom + bestTo) / 2)); // the body fits, centred just off the portal
        }
        return new double[][]{lerp(a, b, s0 / length), lerp(a, b, s1 / length)};
    }

    private static boolean grownContains(Box box, double grow, double[] p) {
        return p[0] >= box.minX() - grow && p[0] <= box.maxX() + grow && p[1] >= box.minY() - grow && p[1] <= box.maxY() + grow;
    }

    private static double[] lerp(double[] a, double[] b, double t) {
        return new double[]{a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t};
    }

    /** The parameter interval [t0, t1] of segment a-b whose horizontal footprint lies in the box (Liang-Barsky), or null. */
    static double[] clip(double[] a, double[] b, double minX, double minY, double maxX, double maxY) {
        double dx = b[0] - a[0];
        double dy = b[1] - a[1];
        double[] p = {-dx, dx, -dy, dy};
        double[] q = {a[0] - minX, maxX - a[0], a[1] - minY, maxY - a[1]};
        double t0 = 0;
        double t1 = 1;
        for (int i = 0; i < 4; i++) {
            if (p[i] == 0) {
                if (q[i] < 0) {
                    return null;
                }
            } else {
                double t = q[i] / p[i];
                if (p[i] < 0) {
                    t0 = Math.max(t0, t);
                } else {
                    t1 = Math.min(t1, t);
                }
            }
        }
        return t0 <= t1 ? new double[]{t0, t1} : null;
    }

    // ---- floor connections and the multi-floor search ---------------------------------------------------

    /** Every direction of every registered connection this profile may use, with each landing verified on its own floor
     * in that floor's current metric frame and both floors routable. Why any connection was left out goes to
     * `problems`. Never compares coordinates across floors. */
    private List<Hop> usableHops(UUID orgId, UUID venueId, String profile, List<String> problems) {
        List<Connection> connections = jdbc.sql("""
                SELECT id, connector_type, from_floor_id, from_poi_id, to_floor_id, to_poi_id, bidirectional, length_m,
                       max_slope_deg, min_clearance_m, status, accessibility_verified_at, accessibility_verified_by
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
        if (c.accessibilityVerifiedAt() == null) {
            return "its accessibility has not been verified on site, so it cannot be claimed accessible";
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
        if (!poi.get().current()) {
            return "landing POI " + poiId + " is not part of the scan version floor " + floorId + " publishes";
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
    private Map<String, Step> search(Query q, List<Hop> hops, Point start, Point dest) {
        String profile = q.profile;
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
            FloorGraph graph = q.graph(here.floorId(), false);
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
                double[] departure = landings.computeIfAbsent(hop.fromPoi(), p -> landingPosition(q.venueId, p)).orElse(null);
                double[] arrival = landings.computeIfAbsent(hop.toPoi(), p -> landingPosition(q.venueId, p)).orElse(null);
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

    private RouteResponse assembleResponse(List<RouteLeg> legs, List<Hop> hops, String profile, List<String> constraints,
                                           List<RejectedObstacle> rejected) {
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
            legs.stream().map(RouteLeg::source).distinct().toList(), rejected);
    }
}
