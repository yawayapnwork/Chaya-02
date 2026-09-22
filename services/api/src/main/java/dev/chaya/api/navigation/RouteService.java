package dev.chaya.api.navigation;

import dev.chaya.api.navigation.NavigationDtos.BlockedRegion;
import dev.chaya.api.navigation.NavigationDtos.FloorTransition;
import dev.chaya.api.navigation.NavigationDtos.RouteRequest;
import dev.chaya.api.navigation.NavigationDtos.RouteResponse;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Real pedestrian routing over the navigation graph NAVIGATION_BAKING produces (see
 * dev.chaya.api.pipeline.PipelineService#ingestNavigationGraph and chaya_worker.navmesh). Pathfinding is
 * plain Dijkstra over navigation_node/navigation_edge -- Recast/Detour already did the hard geometric work
 * offline; this only needs a weighted-graph shortest path, which is what those tables exist for.
 *
 * <p>Accessibility (`accessibility = "STEP_FREE"`) is not the same query re-labelled: it selects the
 * STEP_FREE-profile graph (baked with stairs and steep slopes already excluded by real per-polygon slope
 * geometry, see chaya_worker.navmesh.build_routing_graphs), additionally rejects any edge whose measured
 * clearance is below {@link NavigationProperties#minAccessibleClearanceM()}, and -- for a multi-floor
 * route -- only crosses floors via an elevator-categorised POI pair, never stairs.
 *
 * <p>Multi-floor: navigation_edge can never span two graphs (its FKs are scoped to one graph_id), so a
 * floor transition is its own step, not a graph edge -- exactly the "floor-local navigation + transition +
 * next-floor navigation" shape the routing API documents. A transition is found by matching a
 * stairs/elevator-categorised POI on one floor to the nearest same-category POI on an adjacent (stairs) or
 * any (elevator) floor within {@link NavigationProperties#transitionMatchRadiusMeters()} -- real POI
 * positions, never a fabricated link table.
 *
 * <p>Dynamic obstacles: {@code blockedRegions} in the request excludes graph nodes that fall inside them
 * for THIS query only (never written to the database) -- the architecture a runtime AR-reported obstacle
 * needs: the client observes something real and reports its region; this service never invents one.
 */
@Service
public class RouteService {

    private static final Set<String> PROFILES = Set.of("STANDARD", "STEP_FREE");

    private record NodeRow(UUID id, double x, double y, double z) {}

    private record EdgeRow(UUID from, UUID to, double lengthM, boolean bidirectional, Double minClearanceM) {}

    private record GraphEdge(UUID to, double weight) {}

    private record GraphData(Map<UUID, double[]> positions, Map<UUID, List<GraphEdge>> adjacency) {}

    private record PathResult(List<UUID> nodeIds, double distanceMeters) {}

    private record RouteLeg(List<double[]> waypoints, double distanceMeters, UUID floorId) {}

    private record PoiRow(UUID id, UUID floorId, double x, double y, double z) {}

    private record FloorRow(UUID id, int level) {}

    private record FloorHop(UUID toFloor, String connectorType, UUID poiId, double[] entryPoint, double[] exitPoint) {}

    private final JdbcClient jdbc;
    private final TenantGuard guard;
    private final NavigationProperties props;

    public RouteService(JdbcClient jdbc, TenantGuard guard, NavigationProperties props) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.props = props;
    }

    @Transactional(readOnly = true)
    public RouteResponse route(Actor actor, RouteRequest request) {
        guard.requireVenue(actor, request.venueId());
        String profile = normalizeProfile(request.accessibility());
        requireFloor(request.venueId(), request.floorId());
        PoiRow destination = loadPoi(actor.organizationId(), request.venueId(), request.destinationPoiId());

        double[] start = {request.start().get(0), request.start().get(1), request.start().get(2)};
        List<BlockedRegion> blocked = request.blockedRegions() == null ? List.of() : request.blockedRegions();

        List<String> constraints = new ArrayList<>();
        if (profile.equals("STEP_FREE")) {
            constraints.add("excludes stairs and slopes steeper than the baked accessible-ramp threshold");
            constraints.add("excludes passages narrower than " + props.minAccessibleClearanceM() + " m of measured clearance");
            constraints.add("crosses floors only via an elevator, never stairs");
        }
        if (!blocked.isEmpty()) {
            constraints.add(blocked.size() + " reported obstacle region(s) excluded from the route");
        }

        List<RouteLeg> legs = new ArrayList<>();
        List<FloorTransition> transitions = new ArrayList<>();

        if (request.floorId().equals(destination.floorId())) {
            RouteLeg leg = routeWithinFloor(request.venueId(), request.floorId(), start,
                new double[]{destination.x(), destination.y(), destination.z()}, profile, blocked);
            requireRoute(leg, "no route could be found between the start point and the destination on this floor");
            legs.add(leg);
        } else {
            List<FloorHop> hops = findFloorPath(actor.organizationId(), request.venueId(), request.floorId(), destination.floorId(), profile);
            if (hops == null) {
                throw new ApiException(HttpStatus.NOT_FOUND, "ROUTE_UNAVAILABLE",
                    "no floor transition connects the start floor to the destination floor"
                        + (profile.equals("STEP_FREE") ? " accessibly (no matching elevator was found)" : ""));
            }
            double[] cursor = start;
            UUID currentFloor = request.floorId();
            for (FloorHop hop : hops) {
                RouteLeg leg = routeWithinFloor(request.venueId(), currentFloor, cursor, hop.entryPoint(), profile, blocked);
                requireRoute(leg, "no route could be found to the " + hop.connectorType().toLowerCase(Locale.ROOT) + " on floor " + currentFloor);
                legs.add(leg);
                transitions.add(new FloorTransition(currentFloor, hop.toFloor(), hop.connectorType(), hop.poiId()));
                cursor = hop.exitPoint();
                currentFloor = hop.toFloor();
            }
            RouteLeg finalLeg = routeWithinFloor(request.venueId(), currentFloor, cursor,
                new double[]{destination.x(), destination.y(), destination.z()}, profile, blocked);
            requireRoute(finalLeg, "no route could be found from the last floor transition to the destination");
            legs.add(finalLeg);
        }

        return assembleResponse(legs, transitions, profile, constraints);
    }

    private static void requireRoute(RouteLeg leg, String message) {
        if (leg == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ROUTE_UNAVAILABLE", message);
        }
    }

    private static String normalizeProfile(String accessibility) {
        String p = accessibility == null || accessibility.isBlank() ? "STANDARD" : accessibility.strip().toUpperCase(Locale.ROOT);
        if (!PROFILES.contains(p)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ACCESSIBILITY", "accessibility must be one of " + PROFILES);
        }
        return p;
    }

    private void requireFloor(UUID venueId, UUID floorId) {
        Integer count = jdbc.sql("SELECT count(*) FROM floor WHERE id = :f AND venue_id = :v AND deleted_at IS NULL")
            .param("f", floorId).param("v", venueId).query(Integer.class).single();
        if (count == 0) {
            throw new NotFoundException("floor not found");
        }
    }

    private PoiRow loadPoi(UUID orgId, UUID venueId, UUID poiId) {
        return jdbc.sql("""
                SELECT p.id, p.floor_id, v.x, v.y, v.z
                  FROM poi p
                  JOIN poi_version v ON v.poi_id = p.id
                 WHERE p.id = :poi AND p.venue_id = :venue AND p.organization_id = :org AND p.deleted_at IS NULL
                   AND v.version_number = (SELECT max(version_number) FROM poi_version WHERE poi_id = p.id)
                """)
            .param("poi", poiId).param("venue", venueId).param("org", orgId)
            .query((rs, i) -> new PoiRow(rs.getObject("id", UUID.class), rs.getObject("floor_id", UUID.class),
                rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("z")))
            .optional().orElseThrow(() -> new NotFoundException("destination POI not found"));
    }

    // ---- single-floor routing -----------------------------------------------------------------------

    private RouteLeg routeWithinFloor(UUID venueId, UUID floorId, double[] from, double[] to, String profile,
                                      List<BlockedRegion> blocked) {
        GraphData graph = loadGraph(venueId, floorId, profile, blocked);
        if (graph == null || graph.positions().isEmpty()) {
            return null;
        }
        UUID startNode = nearestNode(graph, from);
        UUID endNode = nearestNode(graph, to);
        if (startNode == null || endNode == null) {
            return null;
        }
        if (distance(graph.positions().get(startNode), from) > props.nodeSnapMaxDistanceMeters()
                || distance(graph.positions().get(endNode), to) > props.nodeSnapMaxDistanceMeters()) {
            return null;
        }
        PathResult path = dijkstra(graph, startNode, endNode);
        if (path == null) {
            return null;
        }
        List<double[]> waypoints = new ArrayList<>();
        waypoints.add(from);
        for (UUID id : path.nodeIds()) {
            waypoints.add(graph.positions().get(id));
        }
        waypoints.add(to);
        double total = distance(from, graph.positions().get(path.nodeIds().get(0))) + path.distanceMeters()
            + distance(graph.positions().get(path.nodeIds().get(path.nodeIds().size() - 1)), to);
        return new RouteLeg(waypoints, total, floorId);
    }

    private GraphData loadGraph(UUID venueId, UUID floorId, String profile, List<BlockedRegion> blocked) {
        UUID graphId = jdbc.sql("SELECT id FROM navigation_graph WHERE venue_id = :v AND floor_id = :f AND profile = :p AND status = 'ACTIVE'")
            .param("v", venueId).param("f", floorId).param("p", profile).query(UUID.class).optional().orElse(null);
        if (graphId == null) {
            return null;
        }
        List<NodeRow> nodeRows = jdbc.sql("SELECT id, x, y, z FROM navigation_node WHERE graph_id = :g").param("g", graphId)
            .query((rs, i) -> new NodeRow(rs.getObject("id", UUID.class), rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("z"))).list();

        Map<UUID, double[]> positions = new HashMap<>();
        for (NodeRow n : nodeRows) {
            positions.put(n.id(), new double[]{n.x(), n.y(), n.z()});
        }
        Set<UUID> excluded = new HashSet<>();
        for (NodeRow n : nodeRows) {
            for (BlockedRegion r : blocked) {
                if (r.floorId().equals(floorId) && n.x() >= r.minX() && n.x() <= r.maxX() && n.y() >= r.minY() && n.y() <= r.maxY()) {
                    excluded.add(n.id());
                    break;
                }
            }
        }
        double minClearance = profile.equals("STEP_FREE") ? props.minAccessibleClearanceM() : 0.0;
        List<EdgeRow> edgeRows = jdbc.sql("SELECT from_node_id, to_node_id, length_m, bidirectional, min_clearance_m "
                + "FROM navigation_edge WHERE graph_id = :g").param("g", graphId)
            .query((rs, i) -> new EdgeRow(rs.getObject("from_node_id", UUID.class), rs.getObject("to_node_id", UUID.class),
                rs.getDouble("length_m"), rs.getBoolean("bidirectional"),
                rs.getObject("min_clearance_m") == null ? null : ((Number) rs.getObject("min_clearance_m")).doubleValue()))
            .list();

        Map<UUID, List<GraphEdge>> adjacency = new HashMap<>();
        for (EdgeRow e : edgeRows) {
            if (excluded.contains(e.from()) || excluded.contains(e.to())) {
                continue;
            }
            if (e.minClearanceM() != null && e.minClearanceM() < minClearance) {
                continue;
            }
            adjacency.computeIfAbsent(e.from(), k -> new ArrayList<>()).add(new GraphEdge(e.to(), e.lengthM()));
            if (e.bidirectional()) {
                adjacency.computeIfAbsent(e.to(), k -> new ArrayList<>()).add(new GraphEdge(e.from(), e.lengthM()));
            }
        }
        positions.keySet().removeAll(excluded);
        return new GraphData(positions, adjacency);
    }

    private static UUID nearestNode(GraphData graph, double[] point) {
        UUID best = null;
        double bestDist = Double.MAX_VALUE;
        for (Map.Entry<UUID, double[]> e : graph.positions().entrySet()) {
            double d = distance(e.getValue(), point);
            if (d < bestDist) {
                bestDist = d;
                best = e.getKey();
            }
        }
        return best;
    }

    private static PathResult dijkstra(GraphData graph, UUID startNode, UUID endNode) {
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
            for (GraphEdge edge : graph.adjacency().getOrDefault(u, List.of())) {
                if (visited.contains(edge.to())) {
                    continue;
                }
                double candidate = dist.get(u) + edge.weight();
                if (candidate < dist.getOrDefault(edge.to(), Double.MAX_VALUE)) {
                    dist.put(edge.to(), candidate);
                    prev.put(edge.to(), u);
                    queue.add(edge.to());
                }
            }
        }
        if (!dist.containsKey(endNode)) {
            return null;
        }
        List<UUID> path = new ArrayList<>();
        UUID cur = endNode;
        while (cur != null) {
            path.add(cur);
            cur = prev.get(cur);
        }
        Collections.reverse(path);
        return new PathResult(path, dist.get(endNode));
    }

    private static double distance(double[] a, double[] b) {
        return Math.sqrt(Math.pow(a[0] - b[0], 2) + Math.pow(a[1] - b[1], 2) + Math.pow(a[2] - b[2], 2));
    }

    // ---- multi-floor transitions ----------------------------------------------------------------------

    private List<FloorHop> findFloorPath(UUID orgId, UUID venueId, UUID startFloor, UUID destFloor, String profile) {
        List<FloorRow> floors = jdbc.sql("SELECT id, level FROM floor WHERE venue_id = :v AND deleted_at IS NULL")
            .param("v", venueId).query((rs, i) -> new FloorRow(rs.getObject("id", UUID.class), rs.getInt("level"))).list();
        Map<UUID, Integer> levelOf = new HashMap<>();
        for (FloorRow f : floors) {
            levelOf.put(f.id(), f.level());
        }
        if (!levelOf.containsKey(startFloor) || !levelOf.containsKey(destFloor)) {
            return null;
        }

        record Visit(UUID floor, List<FloorHop> path) {}
        Deque<Visit> queue = new ArrayDeque<>();
        queue.add(new Visit(startFloor, List.of()));
        Set<UUID> visited = new HashSet<>();
        visited.add(startFloor);

        while (!queue.isEmpty()) {
            Visit current = queue.poll();
            if (current.floor().equals(destFloor)) {
                return current.path();
            }
            for (FloorRow candidate : floors) {
                if (visited.contains(candidate.id()) || !hasActiveGraph(venueId, candidate.id(), profile)) {
                    continue;
                }
                FloorHop hop = tryTransition(orgId, venueId, current.floor(), candidate.id(), levelOf, profile);
                if (hop == null) {
                    continue;
                }
                visited.add(candidate.id());
                List<FloorHop> extended = new ArrayList<>(current.path());
                extended.add(hop);
                queue.add(new Visit(candidate.id(), extended));
            }
        }
        return null;
    }

    private boolean hasActiveGraph(UUID venueId, UUID floorId, String profile) {
        Integer count = jdbc.sql("SELECT count(*) FROM navigation_graph WHERE venue_id = :v AND floor_id = :f AND profile = :p AND status = 'ACTIVE'")
            .param("v", venueId).param("f", floorId).param("p", profile).query(Integer.class).single();
        return count > 0;
    }

    /** Finds the closest matching stairs (adjacent floor levels only) or elevator (any floor levels) POI
     * pair between two floors. Stairs are never offered for the STEP_FREE profile. Real POI positions are
     * the only source of truth here -- see the class docstring. */
    private FloorHop tryTransition(UUID orgId, UUID venueId, UUID floorA, UUID floorB, Map<UUID, Integer> levelOf, String profile) {
        int levelDiff = Math.abs(levelOf.get(floorA) - levelOf.get(floorB));
        if (!profile.equals("STEP_FREE") && levelDiff == 1) {
            FloorHop stairs = bestMatch(loadPoisByCategory(orgId, venueId, floorA, "stairs"),
                loadPoisByCategory(orgId, venueId, floorB, "stairs"), floorB, "STAIRS");
            if (stairs != null) {
                return stairs;
            }
        }
        return bestMatch(loadPoisByCategory(orgId, venueId, floorA, "elevator"),
            loadPoisByCategory(orgId, venueId, floorB, "elevator"), floorB, "ELEVATOR");
    }

    private FloorHop bestMatch(List<PoiRow> a, List<PoiRow> b, UUID toFloor, String connectorType) {
        FloorHop best = null;
        double bestDist = props.transitionMatchRadiusMeters();
        for (PoiRow pa : a) {
            for (PoiRow pb : b) {
                double d = Math.hypot(pa.x() - pb.x(), pa.y() - pb.y());
                if (d <= bestDist) {
                    bestDist = d;
                    best = new FloorHop(toFloor, connectorType, pa.id(), new double[]{pa.x(), pa.y(), pa.z()},
                        new double[]{pb.x(), pb.y(), pb.z()});
                }
            }
        }
        return best;
    }

    private List<PoiRow> loadPoisByCategory(UUID orgId, UUID venueId, UUID floorId, String category) {
        return jdbc.sql("""
                SELECT p.id, p.floor_id, v.x, v.y, v.z
                  FROM poi p
                  JOIN poi_version v ON v.poi_id = p.id
                 WHERE p.venue_id = :venue AND p.organization_id = :org AND p.floor_id = :floor AND p.deleted_at IS NULL
                   AND lower(v.category) = :category
                   AND v.version_number = (SELECT max(version_number) FROM poi_version WHERE poi_id = p.id)
                """)
            .param("venue", venueId).param("org", orgId).param("floor", floorId).param("category", category)
            .query((rs, i) -> new PoiRow(rs.getObject("id", UUID.class), rs.getObject("floor_id", UUID.class),
                rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("z")))
            .list();
    }

    // ---- response assembly ----------------------------------------------------------------------------

    private RouteResponse assembleResponse(List<RouteLeg> legs, List<FloorTransition> transitions, String profile,
                                           List<String> constraints) {
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
        double distanceMeters = legs.stream().mapToDouble(RouteLeg::distanceMeters).sum();
        double walkingSpeed = profile.equals("STEP_FREE") ? props.accessibleWalkingSpeedMps() : props.standardWalkingSpeedMps();
        double transitionSeconds = transitions.stream()
            .mapToDouble(t -> Objects.equals(t.connectorType(), "STAIRS") ? props.stairsTransitionSeconds() : props.elevatorTransitionSeconds())
            .sum();
        double durationSeconds = distanceMeters / walkingSpeed + transitionSeconds;
        return new RouteResponse(waypoints, distanceMeters, durationSeconds, transitions, profile, constraints);
    }
}
