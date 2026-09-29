package dev.chaya.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import dev.chaya.api.navigation.FloorConnectionService;
import dev.chaya.api.navigation.FloorConnectionService.ConnectionData;
import dev.chaya.api.navigation.NavigationDtos.BlockedRegion;
import dev.chaya.api.navigation.NavigationDtos.RouteRequest;
import dev.chaya.api.navigation.NavigationDtos.RouteResponse;
import dev.chaya.api.navigation.NavigationDtos.Waypoint;
import dev.chaya.api.navigation.RouteService;
import dev.chaya.api.security.Actor;
import dev.chaya.api.security.Role;
import dev.chaya.api.web.ApiException;
import dev.chaya.api.web.NotFoundException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
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

    /** The caller of a route request. Publishes the venue's draft graphs first: graphs are built as DRAFT and only then
     * activated, exactly as PipelineService#ingestNavigationGraph does -- the database refuses to add nodes or edges to
     * an ACTIVE graph (navigation_graph_content_guard, V6). */
    private Actor actorFor(UUID org, UUID venue) {
        jdbc.sql("UPDATE navigation_graph SET status = 'ACTIVE' WHERE venue_id = :v AND status = 'DRAFT'").param("v", venue).update();
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
                    coordinate_frame_id, created_by)
                VALUES (:o, :v, :p, 1, :label, :cat, '{}', :x, :y, :z, (SELECT current_coordinate_frame_id FROM floor WHERE id = :f), 'test')
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
        fx.calibratedFloor(t.org(), t.venue(), t.floor(), "FLOOR_LOCAL"); // a new reconstruction becomes current
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
        UUID newFrame = fx.calibratedFloor(t.org(), t.venue(), t.floor(), "FLOOR_LOCAL");
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

        BlockedRegion obstacle = new BlockedRegion(t.floor(), 2.0, -1.0, 3.0, 1.0); // covers the middle node
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
            List.of(new BlockedRegion(t.floor(), 4.5, -0.5, 5.5, 0.5))));
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
        BlockedRegion box = new BlockedRegion(t.floor(), 1.8, -0.2, 2.2, 0.2);

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
            List.of(new BlockedRegion(other, -1, -1, 6, 1))));
        assertThat(r.distanceMeters()).isCloseTo(5.0, within(1e-9));
    }

    @Test
    void anInvalidBlockedRegionIsRejected() {
        var t = calibratedTree();
        corridor(t, t.floor(), "STANDARD", new double[]{0, 0, 0}, new double[]{5, 0, 0});
        UUID destination = insertPoi(t.org(), t.venue(), t.floor(), "B", null, 5, 0, 0);
        assertCode(() -> routeService.route(actorFor(t.org(), t.venue()), request(t.venue(), t.floor(), ORIGIN, destination, null,
            List.of(new BlockedRegion(t.floor(), 3, 0, 1, 1)))), "INVALID_BLOCKED_REGION");
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

        register(actor, f, "RAMP", 12.0, 4.0, 1.5);
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
        fx.calibratedFloor(f.t().org(), f.t().venue(), f.floor1(), "FLOOR_LOCAL");
        jdbc.sql("UPDATE navigation_graph SET status = 'RETIRED' WHERE floor_id = :f").param("f", f.floor1()).update();
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
}
