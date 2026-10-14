package dev.chaya.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import dev.chaya.api.navigation.FloorConnectionService;
import dev.chaya.api.navigation.FloorConnectionService.ConnectionData;
import dev.chaya.api.navigation.NavigationDtos.BlockedRegion;
import dev.chaya.api.navigation.NavigationDtos.MultiStopRequest;
import dev.chaya.api.navigation.NavigationDtos.MultiStopResponse;
import dev.chaya.api.navigation.NavigationDtos.RouteRequest;
import dev.chaya.api.navigation.NavigationDtos.RouteResponse;
import dev.chaya.api.navigation.NavigationDtos.Waypoint;
import dev.chaya.api.navigation.NavigationProperties;
import dev.chaya.api.navigation.RouteService;
import dev.chaya.api.security.Actor;
import dev.chaya.api.security.Role;
import dev.chaya.api.web.ApiException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Navigation on REAL Recast/Detour output: every test here ingests the committed output of the chaya-navmesh tool
 * (recastnavigation 1.6.0; packages/contracts/fixtures/navmesh) through the pipeline's own NAVIGATION_BAKING ingestion,
 * with production routing settings (no synthetic graphs), and routes on the ingested graph.
 *
 * <p>What this is and is not: the navmesh is real library output, but the geometry it was baked from is the SYNTHETIC
 * venue of services/reconstruction/tests/navmesh/venue_scene.py, not a reconstruction of a real building. These tests
 * validate routing on real navmesh data structures; they are not real-venue validation (docs/navigation.md).
 *
 * <p>The venue: a 12 x 7 m floor; a wall at x 3.9-4.1 with a 1.1 m doorway (y 2.0-3.1) and a 1.6 m doorway (y 5.0-6.6);
 * a sofa at x 1-2 / y 3.6-4.8; a platform 0.17 m up at x >= 9.5, reached over a step (y < 3.0) or up a 1:14 ramp
 * (y > 3.3), the two lanes split by a railing.
 */
class RealNavmeshNavigationTest extends PipelineTestSupport {

    @Autowired RouteService routes;
    @Autowired FloorConnectionService connections;
    @Autowired dev.chaya.api.security.TenantGuard guard;
    @Autowired dev.chaya.api.frame.CoordinateFrameService frames;
    @Autowired dev.chaya.api.rescan.ScanVersionService versions;

    private static final Instant NOW = Instant.now().plusSeconds(1);

    /** A floor whose published navigation is the real Recast fixture, ingested by a full pipeline run. */
    private record Baked(Ctx c, UUID floor, UUID frame, Actor viewer, Actor manager) {}

    private Baked bake(Ctx c) throws Exception {
        startRun(c, null);
        runThrough("NAVIGATION_BAKING");
        UUID frame = jdbc.sql("SELECT current_coordinate_frame_id FROM floor WHERE id = :f").param("f", c.floor()).query(UUID.class).single();
        Integer recast = jdbc.sql("SELECT count(*) FROM navigation_graph WHERE floor_id = :f AND status = 'ACTIVE' "
                + "AND source = 'RECAST_NAVMESH' AND coordinate_frame_id = :fr")
            .param("f", c.floor()).param("fr", frame).query(Integer.class).single();
        assertThat(recast).as("both profiles of the real navmesh are published on the floor").isEqualTo(2);
        Actor viewer = new Actor(Actor.Kind.USER, "viewer", c.org(), Set.of(c.venue()), Set.of(Role.VIEWER));
        Actor manager = new Actor(Actor.Kind.USER, "manager", c.org(), Set.of(c.venue()), Set.of(Role.VENUE_MANAGER));
        return new Baked(c, c.floor(), frame, viewer, manager);
    }

    private Baked bake() throws Exception {
        return bake(ctx());
    }

    private UUID poi(Baked b, String label, double x, double y, double z) {
        UUID poi = jdbc.sql("INSERT INTO poi (organization_id, venue_id, floor_id) VALUES (:o, :v, :f) RETURNING id")
            .param("o", b.c().org()).param("v", b.c().venue()).param("f", b.floor()).query(UUID.class).single();
        jdbc.sql("""
                INSERT INTO poi_version (organization_id, venue_id, poi_id, version_number, label, category, tags, x, y, z,
                    coordinate_frame_id, scan_version_id, created_by)
                VALUES (:o, :v, :p, 1, :label, null, '{}', :x, :y, :z, (SELECT current_coordinate_frame_id FROM floor WHERE id = :f),
                    (SELECT current_scan_version_id FROM floor WHERE id = :f), 'test')
                """)
            .param("o", b.c().org()).param("v", b.c().venue()).param("p", poi).param("label", label).param("f", b.floor())
            .param("x", x).param("y", y).param("z", z).update();
        return poi;
    }

    private static RouteRequest request(Baked b, double[] start, UUID destination, String profile, List<BlockedRegion> blocked) {
        return new RouteRequest(b.c().venue(), b.floor(), List.of(start[0], start[1], start[2]), destination, profile, blocked);
    }

    private static void assertCode(ThrowingCallable call, String code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(code));
    }

    /** No sampled point of the route is inside the wall (outside its doorways) or the sofa. */
    private static void assertClearOfWallAndSofa(List<Waypoint> waypoints) {
        assertThat(RouteServiceTestSupport.samples(waypoints)).allSatisfy(p -> {
            boolean inWall = p[0] > 3.9 && p[0] < 4.1 && !(p[1] > 2.0 && p[1] < 3.1) && !(p[1] > 5.0 && p[1] < 6.6);
            boolean inSofa = p[0] > 1.0 && p[0] < 2.0 && p[1] > 3.6 && p[1] < 4.8;
            assertThat(inWall || inSofa).as("the route crosses blocked geometry at (%.2f, %.2f)", p[0], p[1]).isFalse();
        });
    }

    /** Which doorway of the wall (x = 4) the route crosses. */
    private static String doorwayCrossed(List<Waypoint> waypoints) {
        for (int i = 1; i < waypoints.size(); i++) {
            Waypoint a = waypoints.get(i - 1);
            Waypoint b = waypoints.get(i);
            if ((a.x() - 4.0) * (b.x() - 4.0) <= 0 && a.x() != b.x()) {
                double y = a.y() + (b.y() - a.y()) * (4.0 - a.x()) / (b.x() - a.x());
                return y > 2.0 && y < 3.1 ? "NARROW" : y > 5.0 && y < 6.6 ? "WIDE" : "WALL at y=" + y;
            }
        }
        return "NONE";
    }

    // ---- same floor, multiple destinations ------------------------------------------------------------------

    @Test
    void aMultiStopRouteOnTheRealNavmeshVisitsEveryStopWithoutCrossingWallsAndOptimisingNeverCostsTime() throws Exception {
        Baked b = bake();
        UUID east = poi(b, "East room", 6.0, 2.5, 0.0);
        UUID platform = poi(b, "Platform", 11.0, 1.5, 0.17);
        UUID westNorth = poi(b, "West room, north", 1.0, 6.0, 0.0);
        List<UUID> stops = List.of(platform, westNorth, east);
        List<Double> start = List.of(1.0, 1.0, 0.0);

        MultiStopResponse given = routes.routeMultiStop(b.viewer(),
            new MultiStopRequest(b.c().venue(), b.floor(), start, stops, false, null, null, null));
        MultiStopResponse best = routes.routeMultiStop(b.viewer(),
            new MultiStopRequest(b.c().venue(), b.floor(), start, stops, true, null, null, null));

        for (MultiStopResponse r : List.of(given, best)) {
            assertThat(r.complete()).isTrue();
            assertThat(r.segments()).hasSize(3).allSatisfy(seg -> {
                assertThat(seg.route().routingSources()).allSatisfy(src -> {
                    assertThat(src.source()).isEqualTo("RECAST_NAVMESH");
                    assertThat(src.recastnavigationVersion()).isEqualTo("1.6.0");
                    assertThat(src.coordinateFrameId()).isEqualTo(b.frame());
                });
                assertClearOfWallAndSofa(seg.route().waypoints());
            });
            assertClearOfWallAndSofa(r.waypoints());
            assertThat(RouteServiceTestSupport.polyline(r.waypoints())).isCloseTo(r.distanceMeters(), within(1e-6));
            // Each STOP is exactly where its POI is: the next segment starts there.
            assertThat(r.waypoints()).filteredOn(w -> w.kind().equals("STOP")).hasSize(2);
        }
        assertThat(given.visitOrder()).containsExactly(platform, westNorth, east);
        assertThat(best.visitOrder()).containsExactlyInAnyOrderElementsOf(stops);
        assertThat(best.estimatedDurationSeconds()).isLessThanOrEqualTo(given.estimatedDurationSeconds() + 1e-9);
        assertThat(best.visitOrder()).as("crossing the wall twice is never optimal here").isNotEqualTo(given.visitOrder());
    }

    // ---- reported obstacles on the real navmesh --------------------------------------------------------------

    @Test
    void anObstacleInTheNarrowDoorwayReroutesThroughTheWideOneAndAStaleObservationDoesNot() throws Exception {
        Baked b = bake();
        UUID east = poi(b, "East room", 6.0, 2.5, 0.0);
        double[] west = {1.0, 2.5, 0.0};

        RouteResponse direct = routes.route(b.viewer(), request(b, west, east, null, null));
        assertThat(doorwayCrossed(direct.waypoints())).isEqualTo("NARROW");

        BlockedRegion cart = new BlockedRegion(b.floor(), 3.8, 2.2, 4.2, 2.9, NOW, b.frame());
        RouteResponse around = routes.route(b.viewer(), request(b, west, east, null, List.of(cart)));
        assertThat(doorwayCrossed(around.waypoints())).isEqualTo("WIDE");
        assertThat(around.distanceMeters()).isGreaterThan(direct.distanceMeters() + 2.0);
        assertThat(around.rejectedObstacles()).isEmpty();
        assertClearOfWallAndSofa(around.waypoints());
        assertThat(RouteServiceTestSupport.samples(around.waypoints())).noneSatisfy(p ->
            assertThat(p[0] > 3.8 && p[0] < 4.2 && p[1] > 2.2 && p[1] < 2.9).isTrue());

        BlockedRegion staleCart = new BlockedRegion(b.floor(), 3.8, 2.2, 4.2, 2.9, Instant.now().minusSeconds(600), b.frame());
        RouteResponse stale = routes.route(b.viewer(), request(b, west, east, null, List.of(staleCart)));
        assertThat(doorwayCrossed(stale.waypoints())).isEqualTo("NARROW");
        assertThat(stale.rejectedObstacles()).singleElement().satisfies(o -> assertThat(o.reason()).isEqualTo("STALE_OBSERVATION"));

        // Both doorways blocked: no route, and the failure says obstacles cut it.
        BlockedRegion wideCart = new BlockedRegion(b.floor(), 3.8, 5.0, 4.2, 6.6, NOW);
        assertThatThrownBy(() -> routes.route(b.viewer(), request(b, west, east, null, List.of(cart, wideCart))))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.code()).isEqualTo("NO_ROUTE");
                assertThat(e.getMessage()).contains("crossing reported obstacles");
            });
    }

    @Test
    void aPartlyObstructedDoorwayStaysUsableOnlyIfAWheelchairStillFitsThroughWhatIsLeft() throws Exception {
        Baked b = bake();
        UUID east = poi(b, "East room", 6.0, 2.5, 0.0);
        double[] west = {1.0, 2.5, 0.0};
        assertThat(doorwayCrossed(routes.route(b.viewer(), request(b, west, east, "STEP_FREE", null)).waypoints())).isEqualTo("NARROW");

        // A bin against the south jamb of the 1.1 m doorway leaves ~0.85 m: a walker passes, a wheelchair (0.9 m) does not.
        BlockedRegion bin = new BlockedRegion(b.floor(), 3.9, 2.0, 4.1, 2.25, NOW);
        assertThat(doorwayCrossed(routes.route(b.viewer(), request(b, west, east, "STANDARD", List.of(bin))).waypoints()))
            .isEqualTo("NARROW");
        RouteResponse stepFree = routes.route(b.viewer(), request(b, west, east, "STEP_FREE", List.of(bin)));
        assertThat(doorwayCrossed(stepFree.waypoints())).isEqualTo("WIDE");
        assertClearOfWallAndSofa(stepFree.waypoints());
    }

    // ---- endpoints ------------------------------------------------------------------------------------------

    @Test
    void endpointsOutsideTheRealNavmeshAreRefusedAndACameraHeightStartIsAccepted() throws Exception {
        Baked b = bake();
        UUID east = poi(b, "East room", 6.0, 2.5, 0.0);
        UUID offSite = poi(b, "Off site", -6.0, 3.0, 0.0);

        assertCode(() -> routes.route(b.viewer(), request(b, new double[]{20, 3, 0}, east, null, null)), "START_OUT_OF_BOUNDS");
        assertCode(() -> routes.route(b.viewer(), request(b, new double[]{6, 3, 4.0}, east, null, null)), "START_OUT_OF_BOUNDS");
        assertCode(() -> routes.route(b.viewer(), request(b, new double[]{1, 2.5, 0}, offSite, null, null)), "DESTINATION_OUT_OF_BOUNDS");
        RouteResponse fromDevice = routes.route(b.viewer(), request(b, new double[]{1, 2.5, 1.5}, east, null, null));
        assertThat(doorwayCrossed(fromDevice.waypoints())).isEqualTo("NARROW");
    }

    // ---- concurrency and the graph cache ----------------------------------------------------------------------

    @Test
    void concurrentRequestsOnTheRealNavmeshMatchTheSequentialAnswersExactly() throws Exception {
        Baked b = bake();
        UUID east = poi(b, "East room", 6.0, 2.5, 0.0);
        UUID platform = poi(b, "Platform", 11.0, 1.5, 0.17);
        BlockedRegion cart = new BlockedRegion(b.floor(), 3.8, 2.2, 4.2, 2.9, NOW);
        List<RouteRequest> requests = new ArrayList<>();
        for (int i = 0; i < 32; i++) {
            requests.add(request(b, new double[]{1.0, 1.0 + (i % 4), 0.0}, i % 2 == 0 ? east : platform,
                i % 3 == 0 ? "STEP_FREE" : "STANDARD", i % 5 == 0 ? List.of(cart) : null));
        }
        List<List<Waypoint>> sequential = new ArrayList<>();
        for (RouteRequest r : requests) {
            sequential.add(routes.route(b.viewer(), r).waypoints());
        }
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<List<Waypoint>>> calls = new ArrayList<>();
            for (RouteRequest r : requests) {
                calls.add(() -> routes.route(b.viewer(), r).waypoints());
            }
            List<Future<List<Waypoint>>> results = pool.invokeAll(calls);
            for (int i = 0; i < results.size(); i++) {
                assertThat(results.get(i).get()).as("request %d", i).isEqualTo(sequential.get(i));
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(routes.graphCacheStats().hits()).as("the real graphs are read from memory after the first request").isPositive();
    }

    // ---- two floors, each its own real navmesh, joined by registered connections ------------------------------

    @Test
    void aMultiFloorRouteOverTwoRealNavmeshesUsesStairsStandardAndOnlyAVerifiedLiftStepFree() throws Exception {
        Baked ground = bake();
        UUID floor1 = fx.floor(ground.c().org(), ground.c().venue(), 1);
        Baked upper = bake(new Ctx(ground.c().org(), ground.c().venue(), floor1, ground.c().operator()));
        assertThat(upper.frame()).as("each floor is calibrated in its own frame").isNotEqualTo(ground.frame());

        UUID stairsFoot = poi(ground, "Stairs (ground)", 7.0, 6.0, 0.0);
        UUID stairsHead = poi(upper, "Stairs (level 1)", 7.0, 6.0, 0.0);
        UUID liftGround = poi(ground, "Lift (ground)", 7.0, 1.0, 0.0);
        UUID liftUpper = poi(upper, "Lift (level 1)", 7.0, 1.0, 0.0);
        UUID office = poi(upper, "Level 1 office", 1.0, 1.0, 0.0);
        connections.create(ground.manager(), ground.c().venue(),
            new ConnectionData("STAIRS", ground.floor(), stairsFoot, floor1, stairsHead, true, 7.0, 32.0, 1.2));
        UUID lift = connections.create(ground.manager(), ground.c().venue(),
            new ConnectionData("ELEVATOR", ground.floor(), liftGround, floor1, liftUpper, true, null, null, 1.1)).id();

        double[] start = {1.0, 6.0, 0.0};
        RouteResponse standard = routes.route(ground.viewer(), request(ground, start, office, "STANDARD", null));
        assertThat(standard.floorTransitions()).singleElement().satisfies(t -> assertThat(t.connectorType()).isEqualTo("STAIRS"));
        assertThat(standard.routingSources()).extracting(s -> s.floorId() + "@" + s.coordinateFrameId())
            .containsExactly(ground.floor() + "@" + ground.frame(), floor1 + "@" + upper.frame());
        assertThat(standard.waypoints()).filteredOn(w -> w.kind().equals("TRANSITION")).singleElement()
            .satisfies(w -> assertThat(w.floorId()).isEqualTo(ground.floor()));
        assertClearOfWallAndSofa(standard.waypoints());

        assertCode(() -> routes.route(ground.viewer(), request(ground, start, office, "STEP_FREE", null)), "FLOOR_CONNECTION_UNAVAILABLE");
        connections.setAccessibilityVerified(ground.manager(), ground.c().venue(), lift, true);
        RouteResponse stepFree = routes.route(ground.viewer(), request(ground, start, office, "STEP_FREE", null));
        assertThat(stepFree.floorTransitions()).singleElement().satisfies(t -> assertThat(t.connectorType()).isEqualTo("ELEVATOR"));
        assertClearOfWallAndSofa(stepFree.waypoints());

        connections.setStatus(ground.manager(), ground.c().venue(), lift, "OUT_OF_SERVICE");
        assertCode(() -> routes.route(ground.viewer(), request(ground, start, office, "STEP_FREE", null)), "FLOOR_CONNECTION_UNAVAILABLE");
        assertThat(routes.route(ground.viewer(), request(ground, start, office, "STANDARD", null)).floorTransitions())
            .singleElement().satisfies(t -> assertThat(t.connectorType()).isEqualTo("STAIRS"));
    }

    // ---- a stale scan version ------------------------------------------------------------------------------

    @Test
    void afterTheFloorIsReconstructedAgainOldPositionsAreRefusedAndTheOldVersionStillRoutesOnItsOwnNavmesh() throws Exception {
        Baked first = bake();
        UUID oldVersion = jdbc.sql("SELECT current_scan_version_id FROM floor WHERE id = :f").param("f", first.floor())
            .query(UUID.class).single();
        UUID oldDesk = poi(first, "Desk (old frame)", 6.0, 2.5, 0.0);
        double[] west = {1.0, 2.5, 0.0};
        RouteResponse before = routes.route(first.viewer(), request(first, west, oldDesk, null, null));
        UUID oldGraph = before.routingSources().get(0).graphId();

        Baked second = bake(first.c());
        assertThat(second.frame()).as("the new reconstruction has its own calibration").isNotEqualTo(first.frame());
        UUID newVersion = jdbc.sql("SELECT current_scan_version_id FROM floor WHERE id = :f").param("f", first.floor())
            .query(UUID.class).single();
        assertThat(newVersion).isNotEqualTo(oldVersion);

        // A destination placed in the superseded frame has no position in what the floor publishes now.
        assertThatThrownBy(() -> routes.route(first.viewer(), request(first, west, oldDesk, null, null)))
            .isInstanceOfSatisfying(ApiException.class, e ->
                assertThat(e.code()).isIn("METRIC_CALIBRATION_REQUIRED", "VERSION_MISMATCH"));
        // The current state routes on the new navmesh graph; nothing cached from the old one is used for it.
        UUID newDesk = poi(second, "Desk", 6.0, 2.5, 0.0);
        RouteResponse now = routes.route(second.viewer(), request(second, west, newDesk, null, null));
        assertThat(now.routingSources()).singleElement().satisfies(src -> {
            assertThat(src.graphId()).isNotEqualTo(oldGraph);
            assertThat(src.scanVersionId()).isEqualTo(newVersion);
            assertThat(src.coordinateFrameId()).isEqualTo(second.frame());
        });
        // A viewer still showing the old version routes on that version's own navmesh, in its own frame.
        RouteResponse pinned = routes.route(first.viewer(), new RouteRequest(first.c().venue(), first.floor(), List.of(1.0, 2.5, 0.0),
            oldDesk, null, null, oldVersion));
        assertThat(pinned.routingSources()).singleElement().satisfies(src -> {
            assertThat(src.graphId()).isEqualTo(oldGraph);
            assertThat(src.scanVersionId()).isEqualTo(oldVersion);
            assertThat(src.coordinateFrameId()).isEqualTo(first.frame());
        });
        assertThat(pinned.waypoints()).isEqualTo(before.waypoints());
    }

    @Test
    void theRequiredClearWidthIsAConfiguredConstraintOnTheRealNavmesh() throws Exception {
        Baked b = bake();
        UUID east = poi(b, "East room", 6.0, 2.5, 0.0);
        RouteRequest across = request(b, new double[]{1.0, 2.5, 0.0}, east, "STEP_FREE", null);
        RouteService wide = new RouteService(jdbc, guard, NavigationProperties.withMinAccessibleClearance(1.3), frames, versions);
        assertThat(doorwayCrossed(wide.route(b.viewer(), across).waypoints())).isEqualTo("WIDE");
        RouteService wider = new RouteService(jdbc, guard, NavigationProperties.withMinAccessibleClearance(1.8), frames, versions);
        assertCode(() -> wider.route(b.viewer(), across), "NO_ACCESSIBLE_ROUTE");
    }
}
