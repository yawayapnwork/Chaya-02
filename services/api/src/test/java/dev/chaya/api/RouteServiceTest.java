package dev.chaya.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import dev.chaya.api.navigation.FloorConnectionService;
import dev.chaya.api.navigation.FloorConnectionService.ConnectionData;
import dev.chaya.api.navigation.NavigationDtos.BlockedRegion;
import dev.chaya.api.navigation.NavigationDtos.MultiStopRequest;
import dev.chaya.api.navigation.NavigationDtos.MultiStopResponse;
import dev.chaya.api.navigation.NavigationDtos.StopResult;
import dev.chaya.api.navigation.NavigationDtos.RouteRequest;
import dev.chaya.api.navigation.NavigationDtos.RouteResponse;
import dev.chaya.api.navigation.NavigationDtos.Waypoint;
import dev.chaya.api.navigation.RouteService;
import dev.chaya.api.security.Actor;
import dev.chaya.api.security.Role;
import dev.chaya.api.web.ApiException;
import dev.chaya.api.web.NotFoundException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.assertj.core.groups.Tuple;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * Routing rules of RouteService, exercised with hand-built graphs (the shape
 * dev.chaya.api.pipeline.PipelineService#ingestNavigationGraph writes from a real NAVIGATION_BAKING artifact). Skipped,
 * not failed, without Docker.
 *
 * <p>These graphs, frames and connections are SYNTHETIC algorithmic fixtures. They prove the routing rules -- fail-closed
 * accessibility, registered floor connections, obstacle blocking, metric distance -- and nothing about navigation in a
 * real venue. Production refuses SYNTHETIC graphs with NAVMESH_NOT_READY; this class alone opts in with
 * chaya.navigation.accept-synthetic-graphs. Routing on a graph ingested from real Recast output is tested in
 * PipelineControlPlaneTest with the production default.
 */
@TestPropertySource(properties = "chaya.navigation.accept-synthetic-graphs=true")
class RouteServiceTest extends AbstractIntegrationTest {

    private static final double WALK = 1.3;        // chaya.navigation.standard-walking-speed-mps
    private static final double WALK_ACCESSIBLE = 1.0;
    private static final double STAIRS_SPEED = 0.5;
    private static final double ELEVATOR_SECONDS = 45;

    @Autowired
    private RouteService routeService;

    @Autowired
    private FloorConnectionService connections;

    /** The caller of a route request. Publishes the venue's draft graphs first: graphs are built as DRAFT and only become
     * ACTIVE as part of the floor's published scan version, exactly as ScanVersionService#promote does -- the database
     * refuses to add nodes or edges to an ACTIVE graph (V6), and any ACTIVE graph that is not the floor's current version's
     * (V28). */
    private Actor actorFor(UUID org, UUID venue) {
        for (UUID floor : jdbc.sql("SELECT id FROM floor WHERE venue_id = :v").param("v", venue).query(UUID.class).list()) {
            fx.publishDrafts(org, venue, floor);
        }
        return new Actor(Actor.Kind.USER, "test-user", org, Set.of(venue), Set.of(Role.ADMIN));
    }

    /** A tree whose floor has a canonical (metric, gravity-aligned) identity fixture frame. */
    private Fixtures.Tree calibratedTree() {
        var t = fx.tree();
        fx.calibratedFloor(t.org(), t.venue(), t.floor(), "FLOOR_LOCAL");
        return t;
    }

    private UUID calibratedFloor(Fixtures.Tree t, int level) {
        UUID floor = fx.floor(t.org(), t.venue(), level);
        fx.calibratedFloor(t.org(), t.venue(), floor, "FLOOR_LOCAL");
        return floor;
    }

    /** Graphs and POIs are written in the floor's current coordinate frame, as ingestion and PoiService do. */
    private UUID insertGraph(UUID org, UUID venue, UUID floor, String profile) {
        return jdbc.sql("INSERT INTO navigation_graph (organization_id, venue_id, floor_id, profile, status, coordinate_frame_id, source) "
                + "VALUES (:o, :v, :f, :p, 'DRAFT', (SELECT current_coordinate_frame_id FROM floor WHERE id = :f), 'SYNTHETIC') RETURNING id")
            .param("o", org).param("v", venue).param("f", floor).param("p", profile).query(UUID.class).single();
    }

    private UUID insertNode(UUID org, UUID venue, UUID graph, UUID floor, double x, double y, double z) {
        return jdbc.sql("INSERT INTO navigation_node (organization_id, venue_id, graph_id, floor_id, kind, x, y, z) "
                + "VALUES (:o, :v, :g, :f, 'WAYPOINT', :x, :y, :z) RETURNING id")
            .param("o", org).param("v", venue).param("g", graph).param("f", floor).param("x", x).param("y", y).param("z", z)
            .query(UUID.class).single();
    }

    /** length_m is stored because the column requires it; routing never reads it (see metricDistance... below). */
    private void insertEdge(UUID org, UUID venue, UUID graph, UUID from, UUID to, boolean stepFree, Double clearanceM, Double slopeDeg) {
        jdbc.sql("INSERT INTO navigation_edge (organization_id, venue_id, graph_id, from_node_id, to_node_id, length_m, "
                + "step_free, bidirectional, min_clearance_m, max_slope_deg) VALUES (:o, :v, :g, :from, :to, 1.0, :sf, true, :clear, :slope)")
            .param("o", org).param("v", venue).param("g", graph).param("from", from).param("to", to).param("sf", stepFree)
            .param("clear", clearanceM).param("slope", slopeDeg).update();
    }

    /** A graph of nodes joined in sequence by measured, level, 1.2 m wide edges. */
    private UUID corridor(Fixtures.Tree t, UUID floor, String profile, double[]... points) {
        UUID graph = insertGraph(t.org(), t.venue(), floor, profile);
        UUID previous = null;
        for (double[] p : points) {
            UUID node = insertNode(t.org(), t.venue(), graph, floor, p[0], p[1], p[2]);
            if (previous != null) {
                insertEdge(t.org(), t.venue(), graph, previous, node, true, 1.2, 0.0);
            }
            previous = node;
        }
        return graph;
    }

    private UUID insertPoi(UUID org, UUID venue, UUID floor, String label, String category, double x, double y, double z) {
        UUID poiId = jdbc.sql("INSERT INTO poi (organization_id, venue_id, floor_id) VALUES (:o, :v, :f) RETURNING id")
            .param("o", org).param("v", venue).param("f", floor).query(UUID.class).single();
        jdbc.sql("""
                INSERT INTO poi_version (organization_id, venue_id, poi_id, version_number, label, category, tags, x, y, z,
                    coordinate_frame_id, scan_version_id, created_by)
                VALUES (:o, :v, :p, 1, :label, :cat, '{}', :x, :y, :z, (SELECT current_coordinate_frame_id FROM floor WHERE id = :f),
                    (SELECT current_scan_version_id FROM floor WHERE id = :f), 'test')
                """)
            .param("o", org).param("v", venue).param("p", poiId).param("label", label).param("cat", category).param("f", floor)
            .param("x", x).param("y", y).param("z", z).update();
        return poiId;
    }

    private static RouteRequest request(UUID venue, UUID floor, double[] start, UUID destination, String accessibility,
                                        List<BlockedRegion> blocked) {
        return new RouteRequest(venue, floor, List.of(start[0], start[1], start[2]), destination, accessibility, blocked);
    }

    private static void assertCode(ThrowingCallable call, String code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(code));
    }

    private static final double[] ORIGIN = {0, 0, 0};

    /** When the obstacles of a test were observed: just now, so none is stale. */
    private static final java.time.Instant NOW = java.time.Instant.now().plusSeconds(1);

    // ---- a route, with metric distance and duration ------------------------------------------------------

    @Test
    void aRouteExistsAcrossTwoConnectedWaypointsToTheDestinationPoi() {
        var t = calibratedTree();
        UUID graph = corridor(t, t.floor(), "STANDARD", new double[]{0, 0, 0}, new double[]{5, 0, 0});
        UUID destination = insertPoi(t.org(), t.venue(), t.floor(), "Reception", "desk", 5, 0, 0);

        RouteResponse response = routeService.route(actorFor(t.org(), t.venue()),
            request(t.venue(), t.floor(), ORIGIN, destination, null, null));

        assertThat(response.accessibilityProfile()).isEqualTo("STANDARD");
        assertThat(response.waypoints().get(0).kind()).isEqualTo("START");
        assertThat(response.waypoints().get(response.waypoints().size() - 1).kind()).isEqualTo("DESTINATION");
        assertThat(response.distanceMeters()).isCloseTo(5.0, within(1e-9));
        assertThat(response.floorTransitions()).isEmpty();
        assertThat(response.routingSources()).singleElement().satisfies(src -> {
            assertThat(src.graphId()).isEqualTo(graph);
            assertThat(src.source()).as("a hand-built graph is reported as what it is").isEqualTo("SYNTHETIC");
            assertThat(src.navmeshSha256()).isNull();
        });
    }

    @Test
    void metricDistanceAndDurationComeFromCanonicalGeometryNotTheStoredEdgeLength() {
        var t = calibratedTree();
        // A 4 m run with a 3 m rise along canonical +Z: 5 m of 3-D path. The stored length_m (1.0) is wrong on purpose.
        corridor(t, t.floor(), "STANDARD", new double[]{0, 0, 0}, new double[]{4, 0, 3});
        UUID destination = insertPoi(t.org(), t.venue(), t.floor(), "Mezzanine", null, 4, 0, 3);

        RouteResponse r = routeService.route(actorFor(t.org(), t.venue()), request(t.venue(), t.floor(), ORIGIN, destination, null, null));

        assertThat(r.distanceMeters()).isCloseTo(5.0, within(1e-9));
        assertThat(r.estimatedDurationSeconds()).isCloseTo(5.0 / WALK, within(1e-9));
        double polyline = 0;
        for (int i = 1; i < r.waypoints().size(); i++) {
            Waypoint a = r.waypoints().get(i - 1);
            Waypoint b = r.waypoints().get(i);
            polyline += Math.sqrt(Math.pow(a.x() - b.x(), 2) + Math.pow(a.y() - b.y(), 2) + Math.pow(a.z() - b.z(), 2));
        }
        assertThat(r.distanceMeters()).as("the reported distance is the length of the reported polyline").isCloseTo(polyline, within(1e-9));
    }

    // ---- failure states -------------------------------------------------------------------------------

    @Test
    void navmeshNotReadyWhenNoActiveGraphExistsForTheFloor() {
        var t = calibratedTree();
        UUID destination = insertPoi(t.org(), t.venue(), t.floor(), "Somewhere", null, 5, 0, 0);
        assertCode(() -> routeService.route(actorFor(t.org(), t.venue()), request(t.venue(), t.floor(), ORIGIN, destination, null, null)),
            "NAVMESH_NOT_READY");
    }

    @Test
    void navmeshNotReadyWhenTheGraphWasBakedInAnOlderFrame() {
        var t = calibratedTree();
        corridor(t, t.floor(), "STANDARD", new double[]{0, 0, 0}, new double[]{5, 0, 0});
        Actor actor = actorFor(t.org(), t.venue()); // activates the graph in the first frame
        fx.recalibrateCurrentReconstruction(t.org(), t.venue(), t.floor()); // the floor's frame changes
        UUID destination = insertPoi(t.org(), t.venue(), t.floor(), "Reception", null, 5, 0, 0);
        assertCode(() -> routeService.route(actor, request(t.venue(), t.floor(), ORIGIN, destination, null, null)), "NAVMESH_NOT_READY");
    }

    @Test
    void metricCalibrationRequiredWithoutACanonicalFrame() {
        var t = fx.tree();
        UUID destination = insertPoi(t.org(), t.venue(), t.floor(), "Somewhere", null, 5, 0, 0);
        assertCode(() -> routeService.route(actorFor(t.org(), t.venue()), request(t.venue(), t.floor(), ORIGIN, destination, null, null)),
            "METRIC_CALIBRATION_REQUIRED");
    }

    @Test
    void metricCalibrationRequiredForADestinationNotPlacedInTheCurrentFrame() {
        var t = calibratedTree();
        UUID graph = insertGraph(t.org(), t.venue(), t.floor(), "STANDARD");
        insertNode(t.org(), t.venue(), graph, t.floor(), 0, 0, 0);
        UUID destination = insertPoi(t.org(), t.venue(), t.floor(), "Old reception", null, 5, 0, 0);
        UUID newFrame = fx.recalibrateCurrentReconstruction(t.org(), t.venue(), t.floor());
        jdbc.sql("UPDATE navigation_graph SET coordinate_frame_id = :c WHERE id = :g").param("c", newFrame).param("g", graph).update();
        assertCode(() -> routeService.route(actorFor(t.org(), t.venue()), request(t.venue(), t.floor(), ORIGIN, destination, null, null)),
            "METRIC_CALIBRATION_REQUIRED");
    }

    @Test
    void noRouteWhenTheGraphIsDisconnected() {
        var t = calibratedTree();
        UUID graph = insertGraph(t.org(), t.venue(), t.floor(), "STANDARD");
        insertNode(t.org(), t.venue(), graph, t.floor(), 0, 0, 0);
        insertNode(t.org(), t.venue(), graph, t.floor(), 5, 0, 0);
        UUID destination = insertPoi(t.org(), t.venue(), t.floor(), "Far corner", null, 5, 0, 0);
        assertCode(() -> routeService.route(actorFor(t.org(), t.venue()), request(t.venue(), t.floor(), ORIGIN, destination, null, null)),
            "NO_ROUTE");
    }

    // ---- dynamic obstacles ------------------------------------------------------------------------------

    @Test
    void aReportedObstacleCoveringTheOnlyPathMakesTheRouteUnavailable() {
        var t = calibratedTree();
        corridor(t, t.floor(), "STANDARD", new double[]{0, 0, 0}, new double[]{2.5, 0, 0}, new double[]{5, 0, 0});
        UUID destination = insertPoi(t.org(), t.venue(), t.floor(), "Reception", null, 5, 0, 0);
        Actor actor = actorFor(t.org(), t.venue());
        assertThat(routeService.route(actor, request(t.venue(), t.floor(), ORIGIN, destination, null, null)).waypoints()).isNotEmpty();

        BlockedRegion obstacle = new BlockedRegion(t.floor(), 2.0, -1.0, 3.0, 1.0, NOW); // covers the middle node
        assertCode(() -> routeService.route(actor, request(t.venue(), t.floor(), ORIGIN, destination, null, List.of(obstacle))), "NO_ROUTE");
    }

    @Test
    void aDynamicObstacleOnTheShortPathReroutesAroundItAndTheDistanceReflectsTheDetour() {
        var t = calibratedTree();
        // A 10 m straight corridor and a parallel corridor 4 m away joined at both ends.
        UUID g = insertGraph(t.org(), t.venue(), t.floor(), "STANDARD");
        UUID a = insertNode(t.org(), t.venue(), g, t.floor(), 0, 0, 0);
        UUID m = insertNode(t.org(), t.venue(), g, t.floor(), 5, 0, 0);
        UUID b = insertNode(t.org(), t.venue(), g, t.floor(), 10, 0, 0);
        UUID a2 = insertNode(t.org(), t.venue(), g, t.floor(), 0, 4, 0);
        UUID b2 = insertNode(t.org(), t.venue(), g, t.floor(), 10, 4, 0);
        insertEdge(t.org(), t.venue(), g, a, m, true, 1.2, 0.0);
        insertEdge(t.org(), t.venue(), g, m, b, true, 1.2, 0.0);
        insertEdge(t.org(), t.venue(), g, a, a2, true, 1.2, 0.0);
        insertEdge(t.org(), t.venue(), g, a2, b2, true, 1.2, 0.0);
        insertEdge(t.org(), t.venue(), g, b2, b, true, 1.2, 0.0);
        UUID destination = insertPoi(t.org(), t.venue(), t.floor(), "End", null, 10, 0, 0);
        Actor actor = actorFor(t.org(), t.venue());

        assertThat(routeService.route(actor, request(t.venue(), t.floor(), ORIGIN, destination, null, null)).distanceMeters())
            .isCloseTo(10.0, within(1e-9));
        RouteResponse around = routeService.route(actor, request(t.venue(), t.floor(), ORIGIN, destination, null,
            List.of(new BlockedRegion(t.floor(), 4.5, -0.5, 5.5, 0.5, NOW))));
        assertThat(around.distanceMeters()).isCloseTo(18.0, within(1e-9));
        assertThat(around.waypoints()).noneSatisfy(w -> assertThat(w.x()).isBetween(4.5, 5.5));
        assertThat(around.accessibilityConstraintsApplied()).anySatisfy(c -> assertThat(c).contains("obstacle"));
    }

    @Test
    void anObstacleBetweenTwoNodesBlocksTheEdgeThatCrossesItEvenWithBothEndpointsOutside() {
        var t = calibratedTree();
        UUID g = insertGraph(t.org(), t.venue(), t.floor(), "STANDARD");
        UUID a = insertNode(t.org(), t.venue(), g, t.floor(), 0, 0, 0);
        UUID b = insertNode(t.org(), t.venue(), g, t.floor(), 4, 0, 0);
        UUID detour = insertNode(t.org(), t.venue(), g, t.floor(), 2, 3, 0);
        insertEdge(t.org(), t.venue(), g, a, b, true, 1.2, 0.0);
        insertEdge(t.org(), t.venue(), g, a, detour, true, 1.2, 0.0);
        insertEdge(t.org(), t.venue(), g, detour, b, true, 1.2, 0.0);
        UUID destination = insertPoi(t.org(), t.venue(), t.floor(), "B", null, 4, 0, 0);
        Actor actor = actorFor(t.org(), t.venue());
        // a small box on the a-b segment; no node is inside it
        BlockedRegion box = new BlockedRegion(t.floor(), 1.8, -0.2, 2.2, 0.2, NOW);

        RouteResponse r = routeService.route(actor, request(t.venue(), t.floor(), ORIGIN, destination, null, List.of(box)));
        assertThat(r.distanceMeters()).isCloseTo(2 * Math.hypot(2, 3), within(1e-9));
        assertThat(r.waypoints()).anySatisfy(w -> assertThat(w.y()).isEqualTo(3.0));
    }

    @Test
    void anObstacleOnAnotherFloorDoesNotBlockThisOne() {
        var t = calibratedTree();
        UUID other = calibratedFloor(t, 1);
        corridor(t, t.floor(), "STANDARD", new double[]{0, 0, 0}, new double[]{5, 0, 0});
        UUID destination = insertPoi(t.org(), t.venue(), t.floor(), "B", null, 5, 0, 0);
        RouteResponse r = routeService.route(actorFor(t.org(), t.venue()), request(t.venue(), t.floor(), ORIGIN, destination, null,
            List.of(new BlockedRegion(other, -1, -1, 6, 1, NOW))));
        assertThat(r.distanceMeters()).isCloseTo(5.0, within(1e-9));
    }

    @Test
    void anInvalidBlockedRegionIsRejected() {
        var t = calibratedTree();
        corridor(t, t.floor(), "STANDARD", new double[]{0, 0, 0}, new double[]{5, 0, 0});
        UUID destination = insertPoi(t.org(), t.venue(), t.floor(), "B", null, 5, 0, 0);
        assertCode(() -> routeService.route(actorFor(t.org(), t.venue()), request(t.venue(), t.floor(), ORIGIN, destination, null,
            List.of(new BlockedRegion(t.floor(), 3, 0, 1, 1, NOW)))), "INVALID_BLOCKED_REGION");
    }

    // ---- accessibility: measured constraints only ----------------------------------------------------------

    private UUID singleEdge(Fixtures.Tree t, String profile, boolean stepFree, Double clearance, Double slope) {
        UUID g = insertGraph(t.org(), t.venue(), t.floor(), profile);
        UUID a = insertNode(t.org(), t.venue(), g, t.floor(), 0, 0, 0);
        UUID b = insertNode(t.org(), t.venue(), g, t.floor(), 5, 0, 0);
        insertEdge(t.org(), t.venue(), g, a, b, stepFree, clearance, slope);
        return g;
    }

    @Test
    void unknownClearanceIsNeverAccessible() {
        var t = calibratedTree();
        singleEdge(t, "STANDARD", true, null, 0.0);
        singleEdge(t, "STEP_FREE", true, null, 0.0);
        UUID destination = insertPoi(t.org(), t.venue(), t.floor(), "Beyond", null, 5, 0, 0);
        Actor actor = actorFor(t.org(), t.venue());

        assertThat(routeService.route(actor, request(t.venue(), t.floor(), ORIGIN, destination, "STANDARD", null)).distanceMeters())
            .as("STANDARD makes no clearance claim").isCloseTo(5.0, within(1e-9));
        assertThatThrownBy(() -> routeService.route(actor, request(t.venue(), t.floor(), ORIGIN, destination, "STEP_FREE", null)))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.code()).isEqualTo("NO_ACCESSIBLE_ROUTE");
                assertThat(e.getMessage()).contains("no measured clearance");
            });
    }

    @Test
    void insufficientClearanceIsNotAccessible() {
        var t = calibratedTree();
        singleEdge(t, "STEP_FREE", true, 0.3, 0.0);
        UUID destination = insertPoi(t.org(), t.venue(), t.floor(), "Narrow gap beyond", null, 5, 0, 0);
        assertThatThrownBy(() -> routeService.route(actorFor(t.org(), t.venue()),
                request(t.venue(), t.floor(), ORIGIN, destination, "STEP_FREE", null)))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.code()).isEqualTo("NO_ACCESSIBLE_ROUTE");
                assertThat(e.getMessage()).contains("narrower than the accessible clearance");
            });
    }

    @Test
    void aSlopeSteeperThanTheAccessibleLimitIsNotAccessible() {
        var t = calibratedTree();
        singleEdge(t, "STEP_FREE", true, 1.2, 8.0);
        UUID destination = insertPoi(t.org(), t.venue(), t.floor(), "Up the slope", null, 5, 0, 0);
        assertThatThrownBy(() -> routeService.route(actorFor(t.org(), t.venue()),
                request(t.venue(), t.floor(), ORIGIN, destination, "STEP_FREE", null)))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.code()).isEqualTo("NO_ACCESSIBLE_ROUTE");
                assertThat(e.getMessage()).contains("steeper than the accessible slope");
            });
    }

    @Test
    void anUnmeasuredSlopeIsNotAccessible() {
        var t = calibratedTree();
        singleEdge(t, "STEP_FREE", true, 1.2, null);
        UUID destination = insertPoi(t.org(), t.venue(), t.floor(), "Beyond", null, 5, 0, 0);
        assertThatThrownBy(() -> routeService.route(actorFor(t.org(), t.venue()),
                request(t.venue(), t.floor(), ORIGIN, destination, "STEP_FREE", null)))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.code()).isEqualTo("NO_ACCESSIBLE_ROUTE");
                assertThat(e.getMessage()).contains("no measured slope");
            });
    }

    @Test
    void aGentleMeasuredSlopeWithMeasuredClearanceIsAccessibleAtTheAccessiblePace() {
        var t = calibratedTree();
        singleEdge(t, "STEP_FREE", true, 1.2, 3.0);
        UUID destination = insertPoi(t.org(), t.venue(), t.floor(), "Beyond", null, 5, 0, 0);
        RouteResponse r = routeService.route(actorFor(t.org(), t.venue()), request(t.venue(), t.floor(), ORIGIN, destination, "STEP_FREE", null));
        assertThat(r.distanceMeters()).isCloseTo(5.0, within(1e-9));
        assertThat(r.estimatedDurationSeconds()).isCloseTo(5.0 / WALK_ACCESSIBLE, within(1e-9));
        assertThat(r.accessibilityConstraintsApplied()).anySatisfy(c -> assertThat(c).contains("no measured slope are excluded"))
            .anySatisfy(c -> assertThat(c).contains("no measured clearance are excluded"));
    }

    @Test
    void aStepOnTheOnlyPathIsNotAccessibleButIsWalkable() {
        var t = calibratedTree();
        singleEdge(t, "STANDARD", false, 1.2, 30.0); // the baked edge crosses a step
        UUID stepFree = insertGraph(t.org(), t.venue(), t.floor(), "STEP_FREE");
        insertNode(t.org(), t.venue(), stepFree, t.floor(), 0, 0, 0);
        insertNode(t.org(), t.venue(), stepFree, t.floor(), 5, 0, 0); // the STEP_FREE bake left the step out
        UUID destination = insertPoi(t.org(), t.venue(), t.floor(), "Upper landing", null, 5, 0, 0);
        Actor actor = actorFor(t.org(), t.venue());

        assertThat(routeService.route(actor, request(t.venue(), t.floor(), ORIGIN, destination, "STANDARD", null)).waypoints()).isNotEmpty();
        assertCode(() -> routeService.route(actor, request(t.venue(), t.floor(), ORIGIN, destination, "STEP_FREE", null)),
            "NO_ACCESSIBLE_ROUTE");
    }

    // ---- multi-floor: registered connections only ------------------------------------------------------

    /** Two floors, each with its OWN frame (FLOOR_LOCAL: unrelated origins and headings). Floor 0: a corridor from the
     * origin to a landing at (10, 0). Floor 1: a corridor from a landing at (-40, 73) -- deliberately nowhere near
     * (10, 0), since the two frames share nothing -- to an office 5 m away. */
    private record TwoFloors(Fixtures.Tree t, UUID floor1, UUID landing0, UUID landing1, UUID office) {}

    private TwoFloors twoFloors(String... profiles) {
        var t = calibratedTree();
        UUID floor1 = calibratedFloor(t, 1);
        for (String profile : profiles) {
            corridor(t, t.floor(), profile, new double[]{0, 0, 0}, new double[]{10, 0, 0});
            corridor(t, floor1, profile, new double[]{-40, 73, 0}, new double[]{-40, 78, 0});
        }
        UUID landing0 = insertPoi(t.org(), t.venue(), t.floor(), "Core A (ground)", "elevator", 10, 0, 0);
        UUID landing1 = insertPoi(t.org(), t.venue(), floor1, "Core A (level 1)", "elevator", -40, 73, 0);
        UUID office = insertPoi(t.org(), t.venue(), floor1, "Level 1 office", null, -40, 78, 0);
        return new TwoFloors(t, floor1, landing0, landing1, office);
    }

    private UUID register(Actor actor, TwoFloors f, String type, Double length, Double slope, Double clearance) {
        return connections.create(actor, f.t().venue(),
            new ConnectionData(type, f.t().floor(), f.landing0(), f.floor1(), f.landing1(), true, length, slope, clearance)).id();
    }

    @Test
    void aMultiFloorRouteCrossesARegisteredElevatorBetweenFloorsWithUnrelatedFrames() {
        TwoFloors f = twoFloors("STANDARD");
        Actor actor = actorFor(f.t().org(), f.t().venue());
        UUID elevator = register(actor, f, "ELEVATOR", null, null, 1.1);

        RouteResponse r = routeService.route(actor, request(f.t().venue(), f.t().floor(), ORIGIN, f.office(), null, null));

        assertThat(r.floorTransitions()).singleElement().satisfies(tr -> {
            assertThat(tr.connectorType()).isEqualTo("ELEVATOR");
            assertThat(tr.connectionId()).isEqualTo(elevator);
            assertThat(tr.fromFloorId()).isEqualTo(f.t().floor());
            assertThat(tr.toFloorId()).isEqualTo(f.floor1());
            assertThat(tr.poiId()).isEqualTo(f.landing0());
            assertThat(tr.toPoiId()).isEqualTo(f.landing1());
            assertThat(tr.distanceMeters()).as("an elevator is ridden, not walked").isZero();
        });
        assertThat(r.distanceMeters()).isCloseTo(10.0 + 5.0, within(1e-9));
        assertThat(r.estimatedDurationSeconds()).isCloseTo(15.0 / WALK + ELEVATOR_SECONDS, within(1e-9));
        assertThat(r.waypoints()).filteredOn(w -> w.floorId().equals(f.floor1()))
            .as("floor 1 waypoints are in floor 1's own frame").allSatisfy(w -> assertThat(w.x()).isEqualTo(-40.0));
        assertThat(r.waypoints()).filteredOn(w -> w.kind().equals("TRANSITION")).singleElement()
            .satisfies(w -> assertThat(w.floorId()).isEqualTo(f.t().floor()));
        assertThat(r.routingSources()).hasSize(2);
    }

    @Test
    void floorsWithoutARegisteredConnectionAreNeverJoinedByNearbyConnectorPois() {
        TwoFloors f = twoFloors("STANDARD");
        // Same-category POIs at identical coordinates on both floors: the old proximity match would have linked them.
        insertPoi(f.t().org(), f.t().venue(), f.t().floor(), "Stairs", "stairs", 5, 0, 0);
        insertPoi(f.t().org(), f.t().venue(), f.floor1(), "Stairs", "stairs", 5, 0, 0);
        assertCode(() -> routeService.route(actorFor(f.t().org(), f.t().venue()),
            request(f.t().venue(), f.t().floor(), ORIGIN, f.office(), null, null)), "FLOOR_CONNECTION_UNAVAILABLE");
    }

    @Test
    void anOutOfServiceConnectionIsNotUsed() {
        TwoFloors f = twoFloors("STANDARD");
        Actor actor = actorFor(f.t().org(), f.t().venue());
        UUID elevator = register(actor, f, "ELEVATOR", null, null, 1.1);
        connections.setStatus(actor, f.t().venue(), elevator, "OUT_OF_SERVICE");
        assertThatThrownBy(() -> routeService.route(actor, request(f.t().venue(), f.t().floor(), ORIGIN, f.office(), null, null)))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.code()).isEqualTo("FLOOR_CONNECTION_UNAVAILABLE");
                assertThat(e.getMessage()).contains("out of service");
            });
    }

    @Test
    void stairsAreWalkedAtStairPaceInStandardAndNeverUsedStepFree() {
        TwoFloors f = twoFloors("STANDARD", "STEP_FREE");
        Actor actor = actorFor(f.t().org(), f.t().venue());
        register(actor, f, "STAIRS", 6.0, 33.0, 1.5);

        RouteResponse standard = routeService.route(actor, request(f.t().venue(), f.t().floor(), ORIGIN, f.office(), "STANDARD", null));
        assertThat(standard.floorTransitions()).singleElement().satisfies(tr -> {
            assertThat(tr.connectorType()).isEqualTo("STAIRS");
            assertThat(tr.distanceMeters()).isEqualTo(6.0);
            assertThat(tr.durationSeconds()).isCloseTo(6.0 / STAIRS_SPEED, within(1e-9));
        });
        assertThat(standard.distanceMeters()).isCloseTo(10.0 + 6.0 + 5.0, within(1e-9));
        assertThat(standard.estimatedDurationSeconds()).isCloseTo(15.0 / WALK + 6.0 / STAIRS_SPEED, within(1e-9));

        assertThatThrownBy(() -> routeService.route(actor, request(f.t().venue(), f.t().floor(), ORIGIN, f.office(), "STEP_FREE", null)))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.code()).isEqualTo("FLOOR_CONNECTION_UNAVAILABLE");
                assertThat(e.getMessage()).contains("stairs are never step-free");
            });
    }

    @Test
    void stepFreeCrossesByAnElevatorOnlyWhenItsClearWidthIsRegisteredAndSufficient() {
        TwoFloors f = twoFloors("STANDARD", "STEP_FREE");
        Actor actor = actorFor(f.t().org(), f.t().venue());
        register(actor, f, "STAIRS", 6.0, 33.0, 1.5);
        UUID unmeasured = register(actor, f, "ELEVATOR", null, null, null);

        assertCode(() -> routeService.route(actor, request(f.t().venue(), f.t().floor(), ORIGIN, f.office(), "STEP_FREE", null)),
            "FLOOR_CONNECTION_UNAVAILABLE");
        assertThat(routeService.route(actor, request(f.t().venue(), f.t().floor(), ORIGIN, f.office(), "STANDARD", null)).floorTransitions())
            .as("STANDARD may use an elevator of unknown width").isNotEmpty();

        connections.delete(actor, f.t().venue(), unmeasured);
        register(actor, f, "ELEVATOR", null, null, 0.8); // below the 0.9 m accessible clear width
        assertCode(() -> routeService.route(actor, request(f.t().venue(), f.t().floor(), ORIGIN, f.office(), "STEP_FREE", null)),
            "FLOOR_CONNECTION_UNAVAILABLE");

        UUID wide = register(actor, f, "ELEVATOR", null, null, 1.1);
        assertThatThrownBy(() -> routeService.route(actor, request(f.t().venue(), f.t().floor(), ORIGIN, f.office(), "STEP_FREE", null)))
            .as("a registered width is a claim: unverified, the elevator is not used step-free")
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.code()).isEqualTo("FLOOR_CONNECTION_UNAVAILABLE");
                assertThat(e.getMessage()).contains("has not been verified on site");
            });
        connections.setAccessibilityVerified(actor, f.t().venue(), wide, true);
        RouteResponse r = routeService.route(actor, request(f.t().venue(), f.t().floor(), ORIGIN, f.office(), "STEP_FREE", null));
        assertThat(r.floorTransitions()).singleElement().satisfies(tr -> assertThat(tr.connectionId()).isEqualTo(wide));
        assertThat(r.estimatedDurationSeconds()).isCloseTo(15.0 / WALK_ACCESSIBLE + ELEVATOR_SECONDS, within(1e-9));
    }

    @Test
    void stepFreeUsesARampOnlyWithARegisteredSlopeWithinTheLimit() {
        TwoFloors f = twoFloors("STEP_FREE");
        Actor actor = actorFor(f.t().org(), f.t().venue());
        UUID steep = register(actor, f, "RAMP", 12.0, 7.0, 1.5);
        assertCode(() -> routeService.route(actor, request(f.t().venue(), f.t().floor(), ORIGIN, f.office(), "STEP_FREE", null)),
            "FLOOR_CONNECTION_UNAVAILABLE");
        connections.delete(actor, f.t().venue(), steep);
        UUID unknownSlope = register(actor, f, "RAMP", 12.0, null, 1.5);
        assertCode(() -> routeService.route(actor, request(f.t().venue(), f.t().floor(), ORIGIN, f.office(), "STEP_FREE", null)),
            "FLOOR_CONNECTION_UNAVAILABLE");
        connections.delete(actor, f.t().venue(), unknownSlope);

        UUID gentle = register(actor, f, "RAMP", 12.0, 4.0, 1.5);
        assertCode(() -> routeService.route(actor, request(f.t().venue(), f.t().floor(), ORIGIN, f.office(), "STEP_FREE", null)),
            "FLOOR_CONNECTION_UNAVAILABLE");
        connections.setAccessibilityVerified(actor, f.t().venue(), gentle, true);
        RouteResponse r = routeService.route(actor, request(f.t().venue(), f.t().floor(), ORIGIN, f.office(), "STEP_FREE", null));
        assertThat(r.floorTransitions()).singleElement().satisfies(tr -> assertThat(tr.connectorType()).isEqualTo("RAMP"));
        assertThat(r.distanceMeters()).isCloseTo(10.0 + 12.0 + 5.0, within(1e-9));
        assertThat(r.estimatedDurationSeconds()).isCloseTo(27.0 / WALK_ACCESSIBLE, within(1e-9));
    }

    @Test
    void aRouteCanChainConnectionsThroughAnIntermediateFloor() {
        TwoFloors f = twoFloors("STANDARD");
        UUID floor2 = calibratedFloor(f.t(), 2);
        corridor(f.t(), floor2, "STANDARD", new double[]{7, 7, 0}, new double[]{7, 10, 0});
        UUID landing2 = insertPoi(f.t().org(), f.t().venue(), floor2, "Core A (level 2)", "elevator", 7, 7, 0);
        UUID top = insertPoi(f.t().org(), f.t().venue(), floor2, "Roof garden", null, 7, 10, 0);
        Actor actor = actorFor(f.t().org(), f.t().venue());
        register(actor, f, "ELEVATOR", null, null, 1.1);
        connections.create(actor, f.t().venue(), new ConnectionData("ELEVATOR", f.floor1(), f.landing1(), floor2, landing2, true, null, null, 1.1));

        RouteResponse r = routeService.route(actor, request(f.t().venue(), f.t().floor(), ORIGIN, top, null, null));
        assertThat(r.floorTransitions()).extracting(tr -> tr.toFloorId()).containsExactly(f.floor1(), floor2);
        assertThat(r.distanceMeters()).as("no walking between two rides from the same landing").isCloseTo(10.0 + 3.0, within(1e-9));
        assertThat(r.estimatedDurationSeconds()).isCloseTo(13.0 / WALK + 2 * ELEVATOR_SECONDS, within(1e-9));
    }

    @Test
    void aConnectionWhoseLandingIsNotInItsFloorsCurrentFrameIsUnusable() {
        TwoFloors f = twoFloors("STANDARD");
        Actor actor = actorFor(f.t().org(), f.t().venue());
        register(actor, f, "ELEVATOR", null, null, 1.1);
        // Floor 1 is re-reconstructed and re-baked, and a destination is placed in the new frame -- but nobody re-placed
        // the landing POI, whose coordinates are still in the old frame.
        fx.publishNewReconstruction(f.t().org(), f.t().venue(), f.floor1()); // its old graphs are retired with its old version
        corridor(f.t(), f.floor1(), "STANDARD", new double[]{-40, 73, 0}, new double[]{-40, 78, 0});
        UUID office = insertPoi(f.t().org(), f.t().venue(), f.floor1(), "Re-placed office", null, -40, 78, 0);
        Actor again = actorFor(f.t().org(), f.t().venue());

        assertThatThrownBy(() -> routeService.route(again, request(f.t().venue(), f.t().floor(), ORIGIN, office, null, null)))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.code()).isEqualTo("FLOOR_CONNECTION_UNAVAILABLE");
                assertThat(e.getMessage()).contains("not placed in floor " + f.floor1() + "'s current metric frame");
            });
    }

    // ---- connection registration --------------------------------------------------------------------------

    @Test
    void connectionsMustCarryTheMeasurementsTheirTypeNeedsAndLandOnTheirFloors() {
        TwoFloors f = twoFloors("STANDARD");
        Actor actor = actorFor(f.t().org(), f.t().venue());
        UUID venue = f.t().venue();
        assertCode(() -> connections.create(actor, venue,
            new ConnectionData("STAIRS", f.t().floor(), f.landing0(), f.floor1(), f.landing1(), true, null, null, null)),
            "INVALID_FLOOR_CONNECTION");
        assertCode(() -> connections.create(actor, venue,
            new ConnectionData("ELEVATOR", f.t().floor(), f.landing0(), f.floor1(), f.landing1(), true, 4.0, null, null)),
            "INVALID_FLOOR_CONNECTION");
        assertCode(() -> connections.create(actor, venue,
            new ConnectionData("ELEVATOR", f.t().floor(), f.landing1(), f.floor1(), f.landing0(), true, null, null, null)),
            "INVALID_FLOOR_CONNECTION"); // landings swapped: neither POI is on the floor it is named for
        assertCode(() -> connections.create(actor, venue,
            new ConnectionData("ESCALATOR", f.t().floor(), f.landing0(), f.floor1(), f.landing1(), true, 4.0, null, null)),
            "INVALID_FLOOR_CONNECTION");
        assertThat(connections.list(actor, venue)).isEmpty();
    }

    // ---- access ------------------------------------------------------------------------------------

    @Test
    void aDestinationPoiInAnotherVenueIsRejectedAsNotFound() {
        var t1 = fx.tree();
        var t2 = fx.tree();
        insertGraph(t1.org(), t1.venue(), t1.floor(), "STANDARD");
        UUID otherVenuesPoi = insertPoi(t2.org(), t2.venue(), t2.floor(), "Not yours", null, 5, 0, 0);
        assertThatThrownBy(() -> routeService.route(actorFor(t1.org(), t1.venue()),
            request(t1.venue(), t1.floor(), ORIGIN, otherVenuesPoi, null, null))).isInstanceOf(NotFoundException.class);
    }

    @Test
    void anActorWithoutAccessToTheVenueIsRejectedBeforeAnythingElse() {
        var t = calibratedTree();
        UUID destination = insertPoi(t.org(), t.venue(), t.floor(), "Somewhere", null, 5, 0, 0);
        Actor outsider = new Actor(Actor.Kind.USER, "outsider", fx.organization(), Set.of(), Set.of(Role.OPERATOR));
        assertThatThrownBy(() -> routeService.route(outsider, request(t.venue(), t.floor(), ORIGIN, destination, null, null)))
            .isInstanceOf(NotFoundException.class);
    }

    // ---- reported obstacles: freshness and frame ---------------------------------------------------------

    @Test
    void aStaleOrFutureObstacleObservationIsRejectedAndReportedNotRoutedAround() {
        var t = calibratedTree();
        corridor(t, t.floor(), "STANDARD", new double[]{0, 0, 0}, new double[]{2.5, 0, 0}, new double[]{5, 0, 0});
        UUID destination = insertPoi(t.org(), t.venue(), t.floor(), "Reception", null, 5, 0, 0);
        Actor actor = actorFor(t.org(), t.venue());

        BlockedRegion stale = new BlockedRegion(t.floor(), 2.0, -1.0, 3.0, 1.0, Instant.now().minusSeconds(3600));
        BlockedRegion future = new BlockedRegion(t.floor(), 2.0, -1.0, 3.0, 1.0, Instant.now().plusSeconds(3600));
        RouteResponse r = routeService.route(actor, request(t.venue(), t.floor(), ORIGIN, destination, null, List.of(stale, future)));
        assertThat(r.distanceMeters()).as("neither observation blocks the corridor").isCloseTo(5.0, within(1e-9));
        assertThat(r.rejectedObstacles()).extracting(o -> o.index() + ":" + o.reason())
            .containsExactly("0:STALE_OBSERVATION", "1:FUTURE_OBSERVATION");
        assertThat(r.accessibilityConstraintsApplied()).anySatisfy(c -> assertThat(c).contains("2 reported obstacle region(s) rejected"));

        // A fresh observation of the same region is used, alongside the rejected one.
        BlockedRegion fresh = new BlockedRegion(t.floor(), 2.0, -1.0, 3.0, 1.0, NOW);
        assertCode(() -> routeService.route(actor, request(t.venue(), t.floor(), ORIGIN, destination, null, List.of(stale, fresh))),
            "NO_ROUTE");
        // Without a time an observation cannot be judged current at all.
        assertCode(() -> routeService.route(actor, request(t.venue(), t.floor(), ORIGIN, destination, null,
            List.of(new BlockedRegion(t.floor(), 2.0, -1.0, 3.0, 1.0, null)))), "INVALID_BLOCKED_REGION");
    }

    @Test
    void anObstacleObservedInAnotherCoordinateFrameIsRejected() {
        var t = calibratedTree();
        corridor(t, t.floor(), "STANDARD", new double[]{0, 0, 0}, new double[]{2.5, 0, 0}, new double[]{5, 0, 0});
        UUID destination = insertPoi(t.org(), t.venue(), t.floor(), "Reception", null, 5, 0, 0);
        Actor actor = actorFor(t.org(), t.venue());
        UUID frame = jdbc.sql("SELECT current_coordinate_frame_id FROM floor WHERE id = :f").param("f", t.floor()).query(UUID.class).single();

        BlockedRegion elsewhere = new BlockedRegion(t.floor(), 2.0, -1.0, 3.0, 1.0, NOW, UUID.randomUUID());
        RouteResponse r = routeService.route(actor, request(t.venue(), t.floor(), ORIGIN, destination, null, List.of(elsewhere)));
        assertThat(r.rejectedObstacles()).singleElement().satisfies(o -> assertThat(o.reason()).isEqualTo("FRAME_MISMATCH"));
        BlockedRegion here = new BlockedRegion(t.floor(), 2.0, -1.0, 3.0, 1.0, NOW, frame);
        assertCode(() -> routeService.route(actor, request(t.venue(), t.floor(), ORIGIN, destination, null, List.of(here))), "NO_ROUTE");
    }

    // ---- endpoints outside the navmesh -------------------------------------------------------------------

    @Test
    void anEndpointOutsideTheNavigableAreaOrFarFromEveryPolygonIsRefused() {
        var t = calibratedTree();
        // An L: (0,0) -> (30,0) -> (30,30). (5, 25) is inside its footprint but 25 m from every polygon.
        corridor(t, t.floor(), "STANDARD", new double[]{0, 0, 0}, new double[]{30, 0, 0}, new double[]{30, 30, 0});
        UUID corner = insertPoi(t.org(), t.venue(), t.floor(), "Corner", null, 30, 30, 0);
        UUID outside = insertPoi(t.org(), t.venue(), t.floor(), "Car park", null, 60, 0, 0);
        Actor actor = actorFor(t.org(), t.venue());

        assertCode(() -> routeService.route(actor, request(t.venue(), t.floor(), new double[]{-5, 0, 0}, corner, null, null)),
            "START_OUT_OF_BOUNDS");
        assertCode(() -> routeService.route(actor, request(t.venue(), t.floor(), new double[]{10, 0, 6}, corner, null, null)),
            "START_OUT_OF_BOUNDS"); // 6 m above every walkable surface of the floor
        assertCode(() -> routeService.route(actor, request(t.venue(), t.floor(), new double[]{5, 25, 0}, corner, null, null)),
            "START_OFF_NAVMESH");
        assertCode(() -> routeService.route(actor, request(t.venue(), t.floor(), ORIGIN, outside, null, null)),
            "DESTINATION_OUT_OF_BOUNDS");
        // Within the margin, at camera height above the floor: a valid start.
        assertThat(routeService.route(actor, request(t.venue(), t.floor(), new double[]{-0.5, 0.3, 1.5}, corner, null, null))
            .waypoints()).isNotEmpty();
    }

    // ---- multi-stop routes -----------------------------------------------------------------------------

    private static MultiStopRequest multiStop(UUID venue, UUID floor, double[] start, List<UUID> stops, boolean optimize,
                                              String accessibility, List<BlockedRegion> blocked) {
        return new MultiStopRequest(venue, floor, List.of(start[0], start[1], start[2]), stops, optimize, accessibility, blocked, null);
    }

    @Test
    void aMultiStopRouteKeepsTheRequestedOrderUnlessOptimisationIsRequested() {
        var t = calibratedTree();
        corridor(t, t.floor(), "STANDARD", new double[]{0, 0, 0}, new double[]{2.5, 0, 0}, new double[]{5, 0, 0},
            new double[]{7.5, 0, 0}, new double[]{10, 0, 0});
        UUID far = insertPoi(t.org(), t.venue(), t.floor(), "Far", null, 10, 0, 0);
        UUID near = insertPoi(t.org(), t.venue(), t.floor(), "Near", null, 2.5, 0, 0);
        UUID middle = insertPoi(t.org(), t.venue(), t.floor(), "Middle", null, 5, 0, 0);
        Actor actor = actorFor(t.org(), t.venue());

        MultiStopResponse asGiven = routeService.routeMultiStop(actor, multiStop(t.venue(), t.floor(), ORIGIN, List.of(far, near, middle), false, null, null));
        assertThat(asGiven.visitOrder()).containsExactly(far, near, middle);
        assertThat(asGiven.orderOptimized()).isFalse();
        assertThat(asGiven.complete()).isTrue();
        assertThat(asGiven.distanceMeters()).isCloseTo(10 + 7.5 + 2.5, within(1e-9));
        assertThat(asGiven.segments()).extracting(seg -> seg.fromPoiId() + ">" + seg.toPoiId())
            .containsExactly("null>" + far, far + ">" + near, near + ">" + middle);
        // One continuous polyline: START, then a STOP at each intermediate destination, then the DESTINATION.
        assertThat(asGiven.waypoints().get(0).kind()).isEqualTo("START");
        assertThat(asGiven.waypoints().get(asGiven.waypoints().size() - 1).kind()).isEqualTo("DESTINATION");
        assertThat(asGiven.waypoints()).filteredOn(w -> w.kind().equals("STOP")).extracting(Waypoint::x).containsExactly(10.0, 2.5);
        assertThat(RouteServiceTestSupport.polyline(asGiven.waypoints())).isCloseTo(asGiven.distanceMeters(), within(1e-9));

        MultiStopResponse optimised = routeService.routeMultiStop(actor, multiStop(t.venue(), t.floor(), ORIGIN, List.of(far, near, middle), true, null, null));
        assertThat(optimised.visitOrder()).containsExactly(near, middle, far);
        assertThat(optimised.orderOptimized()).isTrue();
        assertThat(optimised.distanceMeters()).isCloseTo(10.0, within(1e-9));
        assertThat(optimised.estimatedDurationSeconds()).isCloseTo(10.0 / WALK, within(1e-9));
        assertThat(optimised.stops()).extracting(StopResult::requestedIndex, StopResult::visitIndex)
            .containsExactly(Tuple.tuple(0, 2), Tuple.tuple(1, 0), Tuple.tuple(2, 1));
    }

    @Test
    void anUnreachableIntermediateStopIsReportedAndTheRouteContinuesFromTheLastStopReached() {
        var t = calibratedTree();
        UUID g = corridor(t, t.floor(), "STANDARD", new double[]{0, 0, 0}, new double[]{5, 0, 0}, new double[]{10, 0, 0});
        insertNode(t.org(), t.venue(), g, t.floor(), 5, 8, 0); // an island: no edge reaches it
        UUID first = insertPoi(t.org(), t.venue(), t.floor(), "First", null, 5, 0, 0);
        UUID island = insertPoi(t.org(), t.venue(), t.floor(), "Island", null, 5, 8, 0);
        UUID last = insertPoi(t.org(), t.venue(), t.floor(), "Last", null, 10, 0, 0);
        var other = calibratedTree();
        UUID otherVenuesPoi = insertPoi(other.org(), other.venue(), other.floor(), "Elsewhere", null, 1, 0, 0);
        Actor actor = actorFor(t.org(), t.venue());

        MultiStopResponse r = routeService.routeMultiStop(actor,
            multiStop(t.venue(), t.floor(), ORIGIN, List.of(first, island, otherVenuesPoi, last), false, null, null));
        assertThat(r.complete()).isFalse();
        assertThat(r.visitOrder()).containsExactly(first, last);
        assertThat(r.stops()).extracting(StopResult::status, StopResult::failureCode).containsExactly(
            Tuple.tuple("REACHED", null), Tuple.tuple("UNREACHABLE", "NO_ROUTE"),
            Tuple.tuple("UNREACHABLE", "POI_NOT_FOUND"), Tuple.tuple("REACHED", null));
        assertThat(r.segments()).extracting(seg -> seg.fromPoiId() + ">" + seg.toPoiId())
            .containsExactly("null>" + first, first + ">" + last);
        assertThat(r.distanceMeters()).isCloseTo(10.0, within(1e-9));

        assertCode(() -> routeService.routeMultiStop(actor, multiStop(t.venue(), t.floor(), ORIGIN, List.of(island), false, null, null)),
            "NO_REACHABLE_STOP");
        assertCode(() -> routeService.routeMultiStop(actor, multiStop(t.venue(), t.floor(), ORIGIN, List.of(first, first), false, null, null)),
            "INVALID_STOPS");
        List<UUID> tooMany = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            tooMany.add(UUID.randomUUID());
        }
        assertCode(() -> routeService.routeMultiStop(actor, multiStop(t.venue(), t.floor(), ORIGIN, tooMany, true, null, null)),
            "INVALID_STOPS");
        assertCode(() -> routeService.routeMultiStop(actor, multiStop(t.venue(), t.floor(), new double[]{50, 0, 0}, List.of(first), false,
            null, null)), "START_OUT_OF_BOUNDS");
    }

    @Test
    void aStepFreeMultiStopRouteReportsTheStopOnlyAStepReachesAsInaccessible() {
        var t = calibratedTree();
        // STANDARD: 0 -> 5 -> 10, a step between 5 and 10. STEP_FREE: the step's edge is not baked.
        UUID std = corridor(t, t.floor(), "STANDARD", new double[]{0, 0, 0}, new double[]{5, 0, 0});
        UUID stdTop = insertNode(t.org(), t.venue(), std, t.floor(), 10, 0, 0.17);
        UUID stdMid = jdbc.sql("SELECT id FROM navigation_node WHERE graph_id = :g AND x = 5").param("g", std).query(UUID.class).single();
        insertEdge(t.org(), t.venue(), std, stdMid, stdTop, false, 1.2, 30.0);
        UUID sf = corridor(t, t.floor(), "STEP_FREE", new double[]{0, 0, 0}, new double[]{5, 0, 0});
        insertNode(t.org(), t.venue(), sf, t.floor(), 10, 0, 0.17);
        UUID lower = insertPoi(t.org(), t.venue(), t.floor(), "Lower", null, 5, 0, 0);
        UUID upper = insertPoi(t.org(), t.venue(), t.floor(), "Upper", null, 10, 0, 0.17);
        Actor actor = actorFor(t.org(), t.venue());

        assertThat(routeService.routeMultiStop(actor, multiStop(t.venue(), t.floor(), ORIGIN, List.of(upper, lower), false, "STANDARD", null))
            .complete()).isTrue();
        MultiStopResponse stepFree = routeService.routeMultiStop(actor,
            multiStop(t.venue(), t.floor(), ORIGIN, List.of(upper, lower), false, "STEP_FREE", null));
        assertThat(stepFree.visitOrder()).containsExactly(lower);
        assertThat(stepFree.stops().get(0).failureCode()).isEqualTo("NO_ACCESSIBLE_ROUTE");
        assertThat(stepFree.accessibilityProfile()).isEqualTo("STEP_FREE");
    }

    @Test
    void aMultiStopRouteCrossesFloorsAndBackOverAVerifiedElevator() {
        TwoFloors f = twoFloors("STANDARD", "STEP_FREE");
        Actor actor = actorFor(f.t().org(), f.t().venue());
        UUID lift = register(actor, f, "ELEVATOR", null, null, 1.1);
        // On a graph node: a point between the corridor's two nodes would be equidistant from both (a snapping tie).
        UUID ground = insertPoi(f.t().org(), f.t().venue(), f.t().floor(), "Ground cafe", null, 0, 0, 0);

        MultiStopRequest upAndBack = multiStop(f.t().venue(), f.t().floor(), ORIGIN, List.of(f.office(), ground), false, "STEP_FREE", null);
        MultiStopResponse unverified = routeService.routeMultiStop(actor, upAndBack);
        assertThat(unverified.visitOrder()).as("the unverified lift is not used step-free").containsExactly(ground);
        assertThat(unverified.stops().get(0).failureCode()).isEqualTo("FLOOR_CONNECTION_UNAVAILABLE");

        connections.setAccessibilityVerified(actor, f.t().venue(), lift, true);
        MultiStopResponse r = routeService.routeMultiStop(actor, upAndBack);
        assertThat(r.complete()).isTrue();
        assertThat(r.segments()).allSatisfy(seg -> assertThat(seg.route().floorTransitions()).singleElement()
            .satisfies(tr -> assertThat(tr.connectorType()).isEqualTo("ELEVATOR")));
        assertThat(r.waypoints()).filteredOn(w -> w.kind().equals("STOP")).singleElement()
            .satisfies(w -> assertThat(w.floorId()).isEqualTo(f.floor1()));
        assertThat(r.estimatedDurationSeconds()).isCloseTo((15.0 + 15.0) / WALK_ACCESSIBLE + 2 * ELEVATOR_SECONDS, within(1e-9));
    }

    @Test
    void verifyingAccessibilityNeedsRegisteredMeasurementsAndIsNeverPossibleForStairs() {
        TwoFloors f = twoFloors("STANDARD");
        Actor actor = actorFor(f.t().org(), f.t().venue());
        UUID stairs = register(actor, f, "STAIRS", 6.0, 33.0, 1.5);
        UUID unmeasured = register(actor, f, "ELEVATOR", null, null, null);
        UUID ramp = register(actor, f, "RAMP", 12.0, null, 1.5);
        assertCode(() -> connections.setAccessibilityVerified(actor, f.t().venue(), stairs, true), "INVALID_FLOOR_CONNECTION");
        assertCode(() -> connections.setAccessibilityVerified(actor, f.t().venue(), unmeasured, true), "INVALID_FLOOR_CONNECTION");
        assertCode(() -> connections.setAccessibilityVerified(actor, f.t().venue(), ramp, true), "INVALID_FLOOR_CONNECTION");

        UUID lift = register(actor, f, "ELEVATOR", null, null, 1.1);
        var verified = connections.setAccessibilityVerified(actor, f.t().venue(), lift, true);
        assertThat(verified.accessibilityVerifiedAt()).isNotNull();
        assertThat(verified.accessibilityVerifiedBy()).isEqualTo("test-user");
        var withdrawn = connections.setAccessibilityVerified(actor, f.t().venue(), lift, false);
        assertThat(withdrawn.accessibilityVerifiedAt()).isNull();
        assertThat(withdrawn.accessibilityVerifiedBy()).isNull();
    }

    // ---- graph cache, republished versions and concurrent requests -----------------------------------------

    @Test
    void aCachedGraphIsNeverServedAfterTheFloorPublishesANewOne() {
        var t = calibratedTree();
        UUID first = corridor(t, t.floor(), "STANDARD", new double[]{0, 0, 0}, new double[]{5, 0, 0});
        UUID destination = insertPoi(t.org(), t.venue(), t.floor(), "Desk", null, 5, 0, 0);
        Actor actor = actorFor(t.org(), t.venue());
        RouteRequest req = request(t.venue(), t.floor(), ORIGIN, destination, null, null);

        assertThat(routeService.route(actor, req).routingSources().get(0).graphId()).isEqualTo(first);
        long hits = routeService.graphCacheStats().hits();
        long misses = routeService.graphCacheStats().misses();
        assertThat(routeService.route(actor, req).routingSources().get(0).graphId()).isEqualTo(first);
        assertThat(routeService.graphCacheStats().hits()).as("the second request reads no nodes or edges").isGreaterThan(hits);
        assertThat(routeService.graphCacheStats().misses()).isEqualTo(misses);

        // A new bake is published (the old graph is retired): routing switches at once, with the new geometry.
        UUID second = corridor(t, t.floor(), "STANDARD", new double[]{0, 0, 0}, new double[]{0, 4, 0}, new double[]{5, 4, 0},
            new double[]{5, 0, 0});
        fx.publishDrafts(t.org(), t.venue(), t.floor());
        RouteResponse after = routeService.route(actor, req);
        assertThat(after.routingSources().get(0).graphId()).isEqualTo(second);
        assertThat(after.distanceMeters()).isCloseTo(13.0, within(1e-9));
    }

    @Test
    void concurrentRouteRequestsAllGetTheSameCorrectAnswer() throws Exception {
        var t = calibratedTree();
        corridor(t, t.floor(), "STANDARD", new double[]{0, 0, 0}, new double[]{2.5, 0, 0}, new double[]{5, 0, 0},
            new double[]{5, 5, 0});
        UUID a = insertPoi(t.org(), t.venue(), t.floor(), "A", null, 5, 0, 0);
        UUID b = insertPoi(t.org(), t.venue(), t.floor(), "B", null, 5, 5, 0);
        Actor actor = actorFor(t.org(), t.venue());
        List<Callable<Double>> calls = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            UUID dest = i % 2 == 0 ? a : b;
            // Every third request reports an obstacle off the corridor: per-request exclusions never leak into the cache.
            List<BlockedRegion> blocked = i % 3 == 0 ? List.of(new BlockedRegion(t.floor(), 0.5, 3, 1.5, 4, NOW)) : null;
            calls.add(() -> routeService.route(actor, request(t.venue(), t.floor(), ORIGIN, dest, null, blocked)).distanceMeters());
        }
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Double>> results = pool.invokeAll(calls);
            for (int i = 0; i < results.size(); i++) {
                assertThat(results.get(i).get()).isCloseTo(i % 2 == 0 ? 5.0 : 10.0, within(1e-9));
            }
        } finally {
            pool.shutdownNow();
        }
    }

    // ---- the floor-transition graph and transitions that change --------------------------------------------

    @Test
    void theFloorTransitionGraphShowsWhichFloorsAreConnectedForEachProfile() {
        TwoFloors f = twoFloors("STANDARD", "STEP_FREE");
        UUID floor2 = calibratedFloor(f.t(), 2);
        corridor(f.t(), floor2, "STANDARD", new double[]{7, 7, 0}, new double[]{7, 10, 0});
        corridor(f.t(), floor2, "STEP_FREE", new double[]{7, 7, 0}, new double[]{7, 10, 0});
        UUID roof = insertPoi(f.t().org(), f.t().venue(), floor2, "Roof garden", null, 7, 10, 0);
        Actor actor = actorFor(f.t().org(), f.t().venue());
        register(actor, f, "STAIRS", 6.0, 33.0, 1.2); // stairs only, between floors 0 and 1

        var standard = routeService.floorTransitionGraph(actor, f.t().venue(), "STANDARD");
        assertThat(standard.components()).containsExactlyInAnyOrder(List.of(f.t().floor(), f.floor1()), List.of(floor2));
        assertThat(standard.edges()).hasSize(2).allSatisfy(e -> assertThat(e.connectorType()).isEqualTo("STAIRS"));
        var stepFree = routeService.floorTransitionGraph(actor, f.t().venue(), "STEP_FREE");
        assertThat(stepFree.components()).hasSize(3);
        assertThat(stepFree.unusableConnections()).anySatisfy(u -> assertThat(u).contains("stairs are never step-free"));

        // Floor 2 is disconnected: no route reaches it, and the failure says so instead of guessing a way.
        assertCode(() -> routeService.route(actor, request(f.t().venue(), f.t().floor(), ORIGIN, roof, null, null)),
            "FLOOR_CONNECTION_UNAVAILABLE");
        // A verified elevator joins floors 0 and 1 step-free.
        connections.setAccessibilityVerified(actor, f.t().venue(), register(actor, f, "ELEVATOR", null, null, 1.1), true);
        assertThat(routeService.floorTransitionGraph(actor, f.t().venue(), "STEP_FREE").components())
            .containsExactlyInAnyOrder(List.of(f.t().floor(), f.floor1()), List.of(floor2));
    }

    @Test
    void aTransitionThatBecomesUnavailableOrChangesIsRoutedAroundOnTheNextRequest() {
        TwoFloors f = twoFloors("STANDARD", "STEP_FREE");
        Actor actor = actorFor(f.t().org(), f.t().venue());
        UUID stairs = register(actor, f, "STAIRS", 6.0, 33.0, 1.2);
        UUID lift = register(actor, f, "ELEVATOR", null, null, 1.1);
        connections.setAccessibilityVerified(actor, f.t().venue(), lift, true);
        RouteRequest standard = request(f.t().venue(), f.t().floor(), ORIGIN, f.office(), "STANDARD", null);
        RouteRequest stepFree = request(f.t().venue(), f.t().floor(), ORIGIN, f.office(), "STEP_FREE", null);

        assertThat(routeService.route(actor, standard).floorTransitions()).singleElement()
            .satisfies(t -> assertThat(t.connectionId()).as("stairs: 12 s against a 45 s lift").isEqualTo(stairs));
        assertThat(routeService.route(actor, stepFree).floorTransitions()).singleElement()
            .satisfies(t -> assertThat(t.connectionId()).isEqualTo(lift));

        // The stairs close: the standard route recalculates over the lift.
        connections.setStatus(actor, f.t().venue(), stairs, "OUT_OF_SERVICE");
        assertThat(routeService.route(actor, standard).floorTransitions()).singleElement()
            .satisfies(t -> assertThat(t.connectionId()).isEqualTo(lift));

        // The lift's registration is corrected to a narrower door: its verification is withdrawn, so no step-free route
        // remains until someone verifies it again; the standard route still uses it.
        connections.update(actor, f.t().venue(), lift, new ConnectionData("ELEVATOR", f.t().floor(), f.landing0(), f.floor1(),
            f.landing1(), true, null, null, 1.0));
        assertCode(() -> routeService.route(actor, stepFree), "FLOOR_CONNECTION_UNAVAILABLE");
        assertThat(routeService.route(actor, standard).floorTransitions()).singleElement()
            .satisfies(t -> assertThat(t.connectionId()).isEqualTo(lift));
        connections.setAccessibilityVerified(actor, f.t().venue(), lift, true);
        assertThat(routeService.route(actor, stepFree).floorTransitions()).hasSize(1);

        // The lift goes out of service as well: the floors are now disconnected for every profile.
        connections.setStatus(actor, f.t().venue(), lift, "OUT_OF_SERVICE");
        assertCode(() -> routeService.route(actor, standard), "FLOOR_CONNECTION_UNAVAILABLE");
        connections.setStatus(actor, f.t().venue(), stairs, "IN_SERVICE");
        assertThat(routeService.route(actor, standard).floorTransitions()).singleElement()
            .satisfies(t -> assertThat(t.connectionId()).isEqualTo(stairs));
    }
}
