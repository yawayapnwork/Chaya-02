package dev.chaya.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import dev.chaya.api.navigation.NavigationDtos.BlockedRegion;
import dev.chaya.api.navigation.NavigationDtos.RouteRequest;
import dev.chaya.api.navigation.NavigationDtos.RouteResponse;
import dev.chaya.api.navigation.RouteService;
import dev.chaya.api.security.Actor;
import dev.chaya.api.security.Role;
import dev.chaya.api.web.ApiException;
import dev.chaya.api.web.NotFoundException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * Real Dijkstra routing over navigation_graph/navigation_node/navigation_edge, exercised with hand-built
 * fixture graphs (the shape dev.chaya.api.pipeline.PipelineService#ingestNavigationGraph would have
 * written from a real NAVIGATION_BAKING artifact). Skipped, not failed, without Docker.
 *
 * <p>These graphs are SYNTHETIC (V18: not derived from a Recast navmesh), which production refuses with
 * NAVMESH_NOT_READY; this class alone opts in with chaya.navigation.accept-synthetic-graphs to unit-test the routing
 * rules. The navmesh-only gate, and routing on a graph ingested from real Recast output, are tested in
 * PipelineControlPlaneTest with the production default.
 */
@TestPropertySource(properties = "chaya.navigation.accept-synthetic-graphs=true")
class RouteServiceTest extends AbstractIntegrationTest {

    @Autowired
    private RouteService routeService;

    /** The caller of a route request. Publishes the venue's draft graphs first: graphs are built as DRAFT and only then
     * activated, exactly as PipelineService#ingestNavigationGraph does -- the database refuses to add nodes or edges to
     * an ACTIVE graph (navigation_graph_content_guard, V6). */
    private Actor actorFor(UUID org, UUID venue) {
        jdbc.sql("UPDATE navigation_graph SET status = 'ACTIVE' WHERE venue_id = :v AND status = 'DRAFT'").param("v", venue).update();
        return new Actor(Actor.Kind.USER, "test-user", org, Set.of(venue), Set.of(Role.ADMIN));
    }

    /** A tree whose floor has a canonical frame registered to the venue datum (an identity fixture frame). */
    private Fixtures.Tree calibratedTree() {
        var t = fx.tree();
        fx.calibratedFloor(t.org(), t.venue(), t.floor(), "VENUE_CONTROL_POINTS");
        return t;
    }

    private UUID calibratedFloor(Fixtures.Tree t, int level, String datum) {
        UUID floor = fx.floor(t.org(), t.venue(), level);
        fx.calibratedFloor(t.org(), t.venue(), floor, datum);
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

    private void insertEdge(UUID org, UUID venue, UUID graph, UUID from, UUID to, double lengthM, Double minClearanceM) {
        jdbc.sql("INSERT INTO navigation_edge (organization_id, venue_id, graph_id, from_node_id, to_node_id, length_m, "
                + "step_free, bidirectional, min_clearance_m) VALUES (:o, :v, :g, :from, :to, :len, true, true, :clear)")
            .param("o", org).param("v", venue).param("g", graph).param("from", from).param("to", to).param("len", lengthM)
            .param("clear", minClearanceM).update();
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

    private RouteRequest request(UUID venue, UUID floor, double[] start, UUID destination, String accessibility, List<BlockedRegion> blocked) {
        return new RouteRequest(venue, floor, List.of(start[0], start[1], start[2]), destination, accessibility, blocked);
    }

    // ---- route exists ---------------------------------------------------------------------------------

    @Test
    void aRouteExistsAcrossTwoConnectedWaypointsToTheDestinationPoi() {
        var t = calibratedTree();
        UUID graph = insertGraph(t.org(), t.venue(), t.floor(), "STANDARD");
        UUID a = insertNode(t.org(), t.venue(), graph, t.floor(), 0, 0, 0);
        UUID b = insertNode(t.org(), t.venue(), graph, t.floor(), 5, 0, 0);
        insertEdge(t.org(), t.venue(), graph, a, b, 5.0, 1.2);
        UUID destination = insertPoi(t.org(), t.venue(), t.floor(), "Reception", "desk", 5, 0, 0);

        RouteResponse response = routeService.route(actorFor(t.org(), t.venue()),
            request(t.venue(), t.floor(), new double[]{0, 0, 0}, destination, null, null));

        assertThat(response.accessibilityProfile()).isEqualTo("STANDARD");
        assertThat(response.waypoints()).hasSizeGreaterThanOrEqualTo(2);
        assertThat(response.waypoints().get(0).kind()).isEqualTo("START");
        assertThat(response.waypoints().get(response.waypoints().size() - 1).kind()).isEqualTo("DESTINATION");
        assertThat(response.distanceMeters()).isCloseTo(5.0, within(0.01));
        assertThat(response.estimatedDurationSeconds()).isGreaterThan(0);
        assertThat(response.floorTransitions()).isEmpty();
        assertThat(response.routingSources()).singleElement().satisfies(src -> {
            assertThat(src.graphId()).isEqualTo(graph);
            assertThat(src.source()).as("a hand-built graph is reported as what it is").isEqualTo("SYNTHETIC");
            assertThat(src.navmeshSha256()).isNull();
        });
    }

    // ---- route unavailable ------------------------------------------------------------------------------

    @Test
    void navmeshNotReadyWhenNoActiveGraphExistsForTheFloor() {
        var t = calibratedTree();
        UUID destination = insertPoi(t.org(), t.venue(), t.floor(), "Somewhere", null, 5, 0, 0);

        assertThatThrownBy(() -> routeService.route(actorFor(t.org(), t.venue()),
            request(t.venue(), t.floor(), new double[]{0, 0, 0}, destination, null, null)))
            .isInstanceOf(ApiException.class)
            .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("NAVMESH_NOT_READY"));
    }

    @Test
    void routeUnavailableWhenTheGraphIsDisconnected() {
        var t = calibratedTree();
        UUID graph = insertGraph(t.org(), t.venue(), t.floor(), "STANDARD");
        insertNode(t.org(), t.venue(), graph, t.floor(), 0, 0, 0);
        insertNode(t.org(), t.venue(), graph, t.floor(), 5, 0, 0);
        // no edge between them
        UUID destination = insertPoi(t.org(), t.venue(), t.floor(), "Far corner", null, 5, 0, 0);

        assertThatThrownBy(() -> routeService.route(actorFor(t.org(), t.venue()),
            request(t.venue(), t.floor(), new double[]{0, 0, 0}, destination, null, null)))
            .isInstanceOf(ApiException.class)
            .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("ROUTE_UNAVAILABLE"));
    }

    // ---- obstacle blocks route ------------------------------------------------------------------------

    @Test
    void aReportedObstacleRegionExcludesTheOnlyPathAndTheRouteBecomesUnavailable() {
        var t = calibratedTree();
        UUID graph = insertGraph(t.org(), t.venue(), t.floor(), "STANDARD");
        UUID a = insertNode(t.org(), t.venue(), graph, t.floor(), 0, 0, 0);
        UUID mid = insertNode(t.org(), t.venue(), graph, t.floor(), 2.5, 0, 0);
        UUID b = insertNode(t.org(), t.venue(), graph, t.floor(), 5, 0, 0);
        insertEdge(t.org(), t.venue(), graph, a, mid, 2.5, 1.2);
        insertEdge(t.org(), t.venue(), graph, mid, b, 2.5, 1.2);
        UUID destination = insertPoi(t.org(), t.venue(), t.floor(), "Reception", null, 5, 0, 0);

        // sanity: the route exists before any obstacle is reported
        RouteResponse before = routeService.route(actorFor(t.org(), t.venue()),
            request(t.venue(), t.floor(), new double[]{0, 0, 0}, destination, null, null));
        assertThat(before.waypoints()).isNotEmpty();

        BlockedRegion obstacle = new BlockedRegion(t.floor(), 2.0, -1.0, 3.0, 1.0); // covers the "mid" node
        assertThatThrownBy(() -> routeService.route(actorFor(t.org(), t.venue()),
            request(t.venue(), t.floor(), new double[]{0, 0, 0}, destination, null, List.of(obstacle))))
            .isInstanceOf(ApiException.class)
            .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("ROUTE_UNAVAILABLE"));
    }

    // ---- accessible route avoids stairs -----------------------------------------------------------------

    @Test
    void theAccessibleProfileNeverUsesAStairsOnlyPathTheStandardProfileWouldUse() {
        var t = calibratedTree();
        UUID standard = insertGraph(t.org(), t.venue(), t.floor(), "STANDARD");
        UUID a1 = insertNode(t.org(), t.venue(), standard, t.floor(), 0, 0, 0);
        UUID b1 = insertNode(t.org(), t.venue(), standard, t.floor(), 5, 0, 0);
        insertEdge(t.org(), t.venue(), standard, a1, b1, 5.0, 1.2); // a real stairs edge, only in STANDARD

        UUID stepFree = insertGraph(t.org(), t.venue(), t.floor(), "STEP_FREE");
        insertNode(t.org(), t.venue(), stepFree, t.floor(), 0, 0, 0);
        insertNode(t.org(), t.venue(), stepFree, t.floor(), 5, 0, 0);
        // no edge in the STEP_FREE graph: the baked navmesh never connected these across the stairs

        UUID destination = insertPoi(t.org(), t.venue(), t.floor(), "Upper landing", null, 5, 0, 0);

        RouteResponse standardRoute = routeService.route(actorFor(t.org(), t.venue()),
            request(t.venue(), t.floor(), new double[]{0, 0, 0}, destination, "STANDARD", null));
        assertThat(standardRoute.waypoints()).isNotEmpty();

        assertThatThrownBy(() -> routeService.route(actorFor(t.org(), t.venue()),
            request(t.venue(), t.floor(), new double[]{0, 0, 0}, destination, "STEP_FREE", null)))
            .isInstanceOf(ApiException.class)
            .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("ROUTE_UNAVAILABLE"));
    }

    @Test
    void theAccessibleProfileRejectsAnEdgeNarrowerThanTheConfiguredMinimumClearance() {
        var t = calibratedTree();
        UUID graph = insertGraph(t.org(), t.venue(), t.floor(), "STEP_FREE");
        UUID a = insertNode(t.org(), t.venue(), graph, t.floor(), 0, 0, 0);
        UUID b = insertNode(t.org(), t.venue(), graph, t.floor(), 5, 0, 0);
        insertEdge(t.org(), t.venue(), graph, a, b, 5.0, 0.3); // far narrower than the ~0.9 m default minimum
        UUID destination = insertPoi(t.org(), t.venue(), t.floor(), "Narrow gap beyond", null, 5, 0, 0);

        assertThatThrownBy(() -> routeService.route(actorFor(t.org(), t.venue()),
            request(t.venue(), t.floor(), new double[]{0, 0, 0}, destination, "STEP_FREE", null)))
            .isInstanceOf(ApiException.class)
            .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("ROUTE_UNAVAILABLE"));
    }

    // ---- multi-floor route --------------------------------------------------------------------------

    @Test
    void aMultiFloorRouteCrossesViaAMatchingElevatorPoiPair() {
        var t = calibratedTree();
        UUID floor2 = calibratedFloor(t, 1, "VENUE_CONTROL_POINTS");

        UUID g1 = insertGraph(t.org(), t.venue(), t.floor(), "STANDARD");
        UUID start1 = insertNode(t.org(), t.venue(), g1, t.floor(), 0, 0, 0);
        UUID elevatorNode1 = insertNode(t.org(), t.venue(), g1, t.floor(), 10, 0, 0);
        insertEdge(t.org(), t.venue(), g1, start1, elevatorNode1, 10.0, 1.2);

        UUID g2 = insertGraph(t.org(), t.venue(), floor2, "STANDARD");
        UUID elevatorNode2 = insertNode(t.org(), t.venue(), g2, floor2, 10, 0, 0);
        UUID destNode2 = insertNode(t.org(), t.venue(), g2, floor2, 15, 0, 0);
        insertEdge(t.org(), t.venue(), g2, elevatorNode2, destNode2, 5.0, 1.2);

        // Same (x, y) footprint on both floors -- a real elevator shaft occupies the same horizontal spot.
        insertPoi(t.org(), t.venue(), t.floor(), "Elevator", "elevator", 10, 0, 0);
        insertPoi(t.org(), t.venue(), floor2, "Elevator", "elevator", 10, 0, 0);
        UUID destination = insertPoi(t.org(), t.venue(), floor2, "Floor 2 office", null, 15, 0, 0);

        RouteResponse response = routeService.route(actorFor(t.org(), t.venue()),
            request(t.venue(), t.floor(), new double[]{0, 0, 0}, destination, null, null));

        assertThat(response.floorTransitions()).hasSize(1);
        assertThat(response.floorTransitions().get(0).connectorType()).isEqualTo("ELEVATOR");
        assertThat(response.floorTransitions().get(0).fromFloorId()).isEqualTo(t.floor());
        assertThat(response.floorTransitions().get(0).toFloorId()).isEqualTo(floor2);
        assertThat(response.distanceMeters()).isCloseTo(15.0, within(0.5));
        assertThat(response.waypoints().stream().anyMatch(w -> w.kind().equals("TRANSITION"))).isTrue();
        assertThat(response.estimatedDurationSeconds())
            .isGreaterThan(response.distanceMeters() / 1.3); // includes the elevator transition time, not just walking
    }

    @Test
    void multiFloorRouteUnavailableWithNoMatchingConnectorPoi() {
        var t = calibratedTree();
        UUID floor2 = calibratedFloor(t, 1, "VENUE_CONTROL_POINTS");
        insertGraph(t.org(), t.venue(), t.floor(), "STANDARD");
        insertGraph(t.org(), t.venue(), floor2, "STANDARD");
        UUID destination = insertPoi(t.org(), t.venue(), floor2, "Unreachable", null, 15, 0, 0);

        assertThatThrownBy(() -> routeService.route(actorFor(t.org(), t.venue()),
            request(t.venue(), t.floor(), new double[]{0, 0, 0}, destination, null, null)))
            .isInstanceOf(ApiException.class)
            .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("ROUTE_UNAVAILABLE"));
    }

    // ---- cross-venue access rejected -----------------------------------------------------------------

    @Test
    void aDestinationPoiInAnotherVenueIsRejectedAsNotFound() {
        var t1 = fx.tree();
        var t2 = fx.tree();
        insertGraph(t1.org(), t1.venue(), t1.floor(), "STANDARD");
        UUID otherVenuesPoi = insertPoi(t2.org(), t2.venue(), t2.floor(), "Not yours", null, 5, 0, 0);

        assertThatThrownBy(() -> routeService.route(actorFor(t1.org(), t1.venue()),
            request(t1.venue(), t1.floor(), new double[]{0, 0, 0}, otherVenuesPoi, null, null)))
            .isInstanceOf(NotFoundException.class);
    }

    @Test
    void anActorWithoutAccessToTheVenueIsRejectedBeforeAnythingElse() {
        var t = calibratedTree();
        UUID destination = insertPoi(t.org(), t.venue(), t.floor(), "Somewhere", null, 5, 0, 0);
        Actor outsider = new Actor(Actor.Kind.USER, "outsider", fx.organization(), Set.of(), Set.of(Role.OPERATOR));

        assertThatThrownBy(() -> routeService.route(outsider,
            request(t.venue(), t.floor(), new double[]{0, 0, 0}, destination, null, null)))
            .isInstanceOf(NotFoundException.class);
    }

    // ---- coordinate frames -----------------------------------------------------------------------------

    @Test
    void aFloorWithoutACalibratedFrameCannotBeRoutedOn() {
        var t = fx.tree();
        UUID destination = insertPoi(t.org(), t.venue(), t.floor(), "Somewhere", null, 5, 0, 0);
        assertThatThrownBy(() -> routeService.route(actorFor(t.org(), t.venue()),
            request(t.venue(), t.floor(), new double[]{0, 0, 0}, destination, null, null)))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("NAVIGATION_NOT_CALIBRATED"));
    }

    @Test
    void aGraphBakedInAnOlderFrameIsRefusedNotRoutedInTheWrongPlace() {
        var t = calibratedTree();
        UUID graph = insertGraph(t.org(), t.venue(), t.floor(), "STANDARD");
        UUID a = insertNode(t.org(), t.venue(), graph, t.floor(), 0, 0, 0);
        UUID b = insertNode(t.org(), t.venue(), graph, t.floor(), 5, 0, 0);
        insertEdge(t.org(), t.venue(), graph, a, b, 5.0, 1.2);
        Actor actor = actorFor(t.org(), t.venue()); // activates the graph in the first frame
        fx.calibratedFloor(t.org(), t.venue(), t.floor(), "VENUE_CONTROL_POINTS"); // a new reconstruction becomes current
        UUID destination = insertPoi(t.org(), t.venue(), t.floor(), "Reception", null, 5, 0, 0);

        assertThatThrownBy(() -> routeService.route(actor, request(t.venue(), t.floor(), new double[]{0, 0, 0}, destination, null, null)))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("NAVIGATION_FRAME_STALE"));
    }

    @Test
    void aDestinationPoiNotInTheCurrentFrameIsRefused() {
        var t = calibratedTree();
        UUID graph = insertGraph(t.org(), t.venue(), t.floor(), "STANDARD");
        insertNode(t.org(), t.venue(), graph, t.floor(), 0, 0, 0);
        UUID destination = insertPoi(t.org(), t.venue(), t.floor(), "Old reception", null, 5, 0, 0);
        UUID newFrame = fx.calibratedFloor(t.org(), t.venue(), t.floor(), "VENUE_CONTROL_POINTS");
        jdbc.sql("UPDATE navigation_graph SET coordinate_frame_id = :c WHERE id = :g").param("c", newFrame).param("g", graph).update();

        assertThatThrownBy(() -> routeService.route(actorFor(t.org(), t.venue()),
            request(t.venue(), t.floor(), new double[]{0, 0, 0}, destination, null, null)))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("POI_NOT_CALIBRATED"));
    }

    @Test
    void aMultiFloorRouteBetweenFloorLocalFramesIsRefusedBecauseTheirOriginsAreUnrelated() {
        var t = fx.tree();
        fx.calibratedFloor(t.org(), t.venue(), t.floor(), "FLOOR_LOCAL");
        UUID floor2 = calibratedFloor(t, 1, "FLOOR_LOCAL");
        insertGraph(t.org(), t.venue(), t.floor(), "STANDARD");
        insertGraph(t.org(), t.venue(), floor2, "STANDARD");
        insertPoi(t.org(), t.venue(), t.floor(), "Elevator", "elevator", 10, 0, 0);
        insertPoi(t.org(), t.venue(), floor2, "Elevator", "elevator", 10, 0, 0);
        UUID destination = insertPoi(t.org(), t.venue(), floor2, "Floor 2 office", null, 15, 0, 0);

        assertThatThrownBy(() -> routeService.route(actorFor(t.org(), t.venue()),
            request(t.venue(), t.floor(), new double[]{0, 0, 0}, destination, null, null)))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("FLOORS_NOT_REGISTERED"));
    }
}
