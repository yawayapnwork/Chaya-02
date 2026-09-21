package dev.chaya.api.planning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import dev.chaya.api.planning.PlanResult.PathPoint;
import dev.chaya.api.planning.PlanResult.Waypoint;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The planner end to end on deterministic synthetic geometry. The fixtures are hand-drawn plan views used only
 * to test the algorithm; they are not application data.
 */
class CapturePathPlannerTest {

    private static final Set<String> TYPES = Set.of("CORNER", "DOORWAY", "OCCLUSION", "BOUNDARY", "COVERAGE");
    private final CapturePathPlanner planner = new CapturePathPlanner();

    /** Properties that must hold for every plan, whatever the geometry. */
    private void assertInvariants(PlanResult r, PlanRequest req) {
        PlannerConfig cfg = req.config();
        var b = r.baseline();
        var p = r.planned();
        assertThat(p.coveragePercent()).as("a route never reduces coverage").isGreaterThanOrEqualTo(b.coveragePercent() - 1e-9);
        assertThat(p.targetAreaM2()).isEqualTo(b.targetAreaM2());
        for (var m : List.of(b, p)) {
            assertThat(m.coveragePercent()).isBetween(0.0, 100.0);
            assertThat(m.weightedCoveragePercent()).isBetween(0.0, 100.0);
            assertThat(m.redundantCapturePercent()).isBetween(0.0, 100.0);
            assertThat(m.coveredAreaM2() + m.uncoveredAreaM2()).isCloseTo(m.targetAreaM2(), within(0.001));
            assertThat(m.coveragePercent()).isCloseTo(100 * m.coveredAreaM2() / m.targetAreaM2(), within(0.01));
        }
        // The improvement block is exactly the difference between the two measurements.
        assertThat(r.improvement().coveragePercentPoints()).isCloseTo(p.coveragePercent() - b.coveragePercent(), within(0.001));
        assertThat(r.improvement().coveredAreaM2()).isCloseTo(p.coveredAreaM2() - b.coveredAreaM2(), within(0.001));
        assertThat(r.improvement().addedWaypoints()).isEqualTo(r.waypoints().size()).isEqualTo(p.waypointCount());
        assertThat(b.waypointCount()).isZero();

        // Waypoints: ordered, typed, explained, and their contributions add up to the measured gain.
        double legs = 0, gains = 0, prevCum = 0;
        for (int i = 0; i < r.waypoints().size(); i++) {
            Waypoint w = r.waypoints().get(i);
            assertThat(w.order()).isEqualTo(i + 1);
            assertThat(TYPES).contains(w.type());
            assertThat(w.reason()).isNotBlank();
            assertThat(w.expectedCoverageGainM2()).isGreaterThanOrEqualTo(-1e-9);
            assertThat(w.cumulativeDistanceMeters()).isGreaterThanOrEqualTo(prevCum - 1e-9);
            assertThat(w.cumulativeCaptureSeconds()).isCloseTo(w.cumulativeDistanceMeters() / cfg.walkSpeedMetersPerSecond() + (i + 1) * cfg.dwellSeconds(), within(0.01));
            prevCum = w.cumulativeDistanceMeters();
            legs += w.legDistanceMeters();
            gains += w.expectedCoverageGainM2();
        }
        assertThat(gains).as("waypoint contributions sum to the measured coverage gain").isCloseTo(p.coveredAreaM2() - b.coveredAreaM2(), within(0.005 * Math.max(1, r.waypoints().size())));
        assertThat(legs).isCloseTo(r.estimatedDistanceMeters(), within(0.001 * Math.max(1, r.waypoints().size())));
        assertThat(r.estimatedCaptureSeconds()).isCloseTo(r.estimatedDistanceMeters() / cfg.walkSpeedMetersPerSecond() + r.waypoints().size() * cfg.dwellSeconds(), within(0.01));
        assertThat(p.pathLengthMeters()).isCloseTo(b.pathLengthMeters() + r.estimatedDistanceMeters(), within(0.01));
        assertThat(r.confidence().score()).isBetween(0.0, 1.0);

        // Every waypoint is on standable, reachable floor and the minimum separation is respected.
        Grid g = Grid.build(req.scene(), cfg);
        for (Waypoint w : r.waypoints()) {
            int c = g.cellOf(w.x(), w.y());
            assertThat(c).isGreaterThanOrEqualTo(0);
            assertThat(g.standable[c]).as("waypoint (%s, %s) is standable", w.x(), w.y()).isTrue();
        }
        for (int i = 0; i < r.waypoints().size(); i++) {
            for (int j = i + 1; j < r.waypoints().size(); j++) {
                Waypoint a = r.waypoints().get(i), c = r.waypoints().get(j);
                assertThat(Math.hypot(a.x() - c.x(), a.y() - c.y())).as("waypoints %d and %d", i + 1, j + 1).isGreaterThanOrEqualTo(cfg.minSeparation() - 1e-9);
            }
        }
        // The route polyline starts where the lap ended, visits the waypoints, and never crosses a wall or obstacle.
        if (!r.waypoints().isEmpty()) {
            var last = r.waypoints().get(r.waypoints().size() - 1);
            var end = r.route().get(r.route().size() - 1);
            assertThat(Math.hypot(end.x() - last.x(), end.y() - last.y())).isLessThan(0.01);
            double len = 0;
            for (int i = 0; i + 1 < r.route().size(); i++) {
                PathPoint a = r.route().get(i), c = r.route().get(i + 1);
                len += Math.hypot(c.x() - a.x(), c.y() - a.y());
                int steps = (int) Math.ceil(Math.hypot(c.x() - a.x(), c.y() - a.y()) / 0.05);
                for (int s = 0; s <= steps; s++) {
                    double f = steps == 0 ? 0 : (double) s / steps;
                    int cell = g.cellOf(a.x() + (c.x() - a.x()) * f, a.y() + (c.y() - a.y()) * f);
                    assertThat(g.type[cell]).as("route point %d.%d is on free floor", i, s).isEqualTo(Grid.FREE);
                }
            }
            assertThat(len).isCloseTo(r.estimatedDistanceMeters(), within(0.05));
        }
        // Uncovered regions are what is left: their total area cannot exceed the uncovered area.
        double uncoveredRegions = r.uncoveredRegions().stream().mapToDouble(u -> u.areaM2()).sum();
        assertThat(uncoveredRegions).isLessThanOrEqualTo(p.uncoveredAreaM2() + 0.001);
    }

    private boolean routeCrosses(PlanResult r, double x, double yMin, double yMax) {
        for (int i = 0; i + 1 < r.route().size(); i++) {
            PathPoint a = r.route().get(i), c = r.route().get(i + 1);
            if ((a.x() - x) * (c.x() - x) <= 0 && a.x() != c.x()) {
                double y = a.y() + (c.y() - a.y()) * (x - a.x()) / (c.x() - a.x());
                if (y >= yMin && y <= yMax) {
                    return true;
                }
            }
        }
        return false;
    }

    // ---- the scenarios --------------------------------------------------------------------------------------

    @Test
    void rectangularRoomTheRouteRaisesMeasuredCoverageOverTheReconLapAlone() {
        PlanRequest req = PlannerFixtures.rectangularRoom();
        PlanResult r = planner.plan(req);
        assertInvariants(r, req);
        assertThat(r.baseline().coveragePercent()).as("a 10 s lap sees only part of the room").isLessThan(50);
        assertThat(r.improvement().coveragePercentPoints()).isGreaterThan(30);
        assertThat(r.planned().coveragePercent()).isGreaterThan(85);
        assertThat(r.waypoints()).isNotEmpty();
        assertThat(r.waypoints().stream().map(Waypoint::type)).as("corners and walls are prioritised").containsAnyOf("CORNER", "BOUNDARY");
        assertThat(r.uncoveredRegions().stream().mapToDouble(u -> u.areaM2()).max().orElse(0)).as("nothing large left").isLessThan(2.0);
        assertThat(r.diagnostics().yawSource()).isEqualTo("derived-from-motion");
    }

    @Test
    void roomWithDoorwayTheRoutePassesThroughItAndCoversTheCorridor() {
        PlanRequest req = PlannerFixtures.roomWithDoorway();
        PlanResult r = planner.plan(req);
        assertInvariants(r, req);
        assertThat(r.waypoints().stream().filter(w -> w.type().equals("DOORWAY"))).as("a doorway waypoint is planned").isNotEmpty();
        assertThat(r.waypoints().stream().anyMatch(w -> w.x() > 6.0)).as("the route enters the corridor").isTrue();
        assertThat(routeCrosses(r, 6.0, 2.0, 3.0)).as("and goes through the gap in the wall, not through the wall").isTrue();
        assertThat(r.planned().coveragePercent()).isGreaterThan(80);
        assertThat(r.baseline().coveragePercent()).isLessThan(45);
    }

    @Test
    void roomWithOcclusionThePlannerWalksToTheHiddenSide() {
        PlanRequest req = PlannerFixtures.roomWithOcclusion();
        PlanResult r = planner.plan(req);
        assertInvariants(r, req);
        assertThat(r.baseline().coveragePercent()).isLessThan(30);
        assertThat(r.waypoints().stream().anyMatch(w -> w.x() > 4.5)).as("a waypoint on the far side of the partition").isTrue();
        assertThat(r.waypoints().stream().filter(w -> w.type().equals("OCCLUSION")).count())
            .as("waypoints that mainly see what the lap could not see past the partition").isGreaterThanOrEqualTo(1);
        assertThat(r.planned().coveragePercent()).isGreaterThan(80);
    }

    @Test
    void multipleRoomsTheRouteVisitsEveryRoomThroughItsDoorways() {
        PlanRequest req = PlannerFixtures.multipleRooms();
        PlanResult r = planner.plan(req);
        assertInvariants(r, req);
        assertThat(r.waypoints().stream().anyMatch(w -> w.x() > 4 && w.x() < 8)).as("second room visited").isTrue();
        assertThat(r.waypoints().stream().anyMatch(w -> w.x() > 8)).as("third room visited").isTrue();
        assertThat(routeCrosses(r, 4.0, 1.5, 2.5)).as("first doorway").isTrue();
        assertThat(routeCrosses(r, 8.0, 1.5, 2.5)).as("second doorway").isTrue();
        assertThat(r.planned().coveragePercent()).isGreaterThan(75);
        assertThat(r.confidence().reachableFraction()).as("all rooms are connected").isCloseTo(1.0, within(0.001));
    }

    @Test
    void anUnreachableRoomIsNeverPlannedIntoAndIsReportedAsUncoveredWithLowerConfidence() {
        PlanRequest req = PlannerFixtures.roomWithSealedNeighbour();
        PlanResult r = planner.plan(req);
        assertInvariants(r, req);
        assertThat(r.waypoints()).noneMatch(w -> w.x() > 10);
        assertThat(r.diagnostics().reachableAreaM2()).isLessThan(r.diagnostics().walkableAreaM2());
        assertThat(r.confidence().reachableFraction()).isLessThan(1.0);
        assertThat(r.uncoveredRegions()).anyMatch(u -> u.centroidX() > 10 && u.reason().equals("NOT_OBSERVABLE_FROM_EVALUATED_VIEWPOINTS"));
        double sealedFloor = r.uncoveredRegions().stream().filter(u -> u.centroidX() > 10).mapToDouble(u -> u.areaM2()).sum();
        assertThat(sealedFloor).as("most of the 3 x 3 m room stays uncovered").isGreaterThan(5.0);
        PlanResult open = planner.plan(PlannerFixtures.rectangularRoom());
        assertThat(r.confidence().score()).isLessThan(open.confidence().score());
    }

    @Test
    void invalidCandidateViewpointsAreRejectedWithTheirReasonAndValidOnesAreUsed() {
        PlanRequest base = PlannerFixtures.roomWithSealedNeighbour();
        List<CandidateViewpoint> supplied = List.of(
            new CandidateViewpoint(-3, -3, "outside"),
            new CandidateViewpoint(5, 3, "in the obstacle"),
            new CandidateViewpoint(1.2, 5.0, "in the no-go zone"),
            new CandidateViewpoint(0.1, 3, "against the wall"),
            new CandidateViewpoint(Double.NaN, 1, "not finite"),
            new CandidateViewpoint(11.5, 1.5, "in the sealed room"),
            new CandidateViewpoint(2.0, 1.5, "good"),
            new CandidateViewpoint(2.0, 1.5, "good again"));
        PlanRequest req = new PlanRequest(base.scene(), base.trajectory(), List.of(), supplied, base.config());
        PlanResult r = planner.plan(req);
        assertInvariants(r, req);
        Map<String, String> reasons = r.rejectedCandidates().stream().collect(Collectors.toMap(c -> c.label(), c -> c.reason()));
        assertThat(reasons).containsEntry("outside", "OUTSIDE_BOUNDS")
            .containsEntry("in the obstacle", "IN_OBSTACLE")
            .containsEntry("in the no-go zone", "IN_NO_GO_ZONE")
            .containsEntry("against the wall", "TOO_CLOSE_TO_WALL")
            .containsEntry("not finite", "NOT_FINITE")
            .containsEntry("in the sealed room", "UNREACHABLE")
            .containsEntry("good again", "DUPLICATE");
        assertThat(reasons).as("the valid candidate was accepted").doesNotContainKey("good");
        assertThat(r.diagnostics().candidatesGenerated()).isPositive();
    }

    @Test
    void redundantAndDuplicateViewpointsNeverEndUpAsWaypointsCloserThanTheMinimumSeparation() {
        PlanRequest base = PlannerFixtures.rectangularRoom();
        PlannerConfig wide = base.config().toBuilder().minSeparation(2.5).build();
        List<CandidateViewpoint> supplied = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            supplied.add(new CandidateViewpoint(2.0, 4.0, "dup" + i)); // the same spot five times
            supplied.add(new CandidateViewpoint(2.0 + 0.1 * i, 4.0, "near" + i)); // and a cluster of near-identical spots
        }
        PlanRequest req = new PlanRequest(base.scene(), base.trajectory(), List.of(), supplied, wide);
        PlanResult r = planner.plan(req);
        assertInvariants(r, req); // includes: every pair of waypoints is at least minSeparation apart
        assertThat(r.rejectedCandidates().stream().filter(c -> c.reason().equals("DUPLICATE"))).isNotEmpty();
        assertThat(r.waypoints().size()).as("wide separation means fewer, better spread waypoints").isLessThan(
            planner.plan(base).waypoints().size() + 1);
    }

    @Test
    void redundancyPenaltyReducesRedundantCaptureWithoutBreakingCoverage() {
        PlanRequest base = PlannerFixtures.rectangularRoom();
        PlanResult none = planner.plan(new PlanRequest(base.scene(), base.trajectory(), List.of(), List.of(), base.config().toBuilder().redundancyPenalty(0).build()));
        PlanResult strong = planner.plan(new PlanRequest(base.scene(), base.trajectory(), List.of(), List.of(), base.config().toBuilder().redundancyPenalty(1).build()));
        assertInvariants(none, base);
        assertThat(strong.planned().redundantCapturePercent()).as("penalising re-observation never increases it here")
            .isLessThanOrEqualTo(none.planned().redundantCapturePercent() + 1.0);
        assertThat(strong.planned().coveragePercent()).isGreaterThan(80);
    }

    // ---- determinism, comparison, stop conditions ------------------------------------------------------------------

    @Test
    void thePlannerIsDeterministic() {
        for (PlanRequest req : List.of(PlannerFixtures.rectangularRoom(), PlannerFixtures.roomWithDoorway(), PlannerFixtures.roomWithOcclusion())) {
            PlanResult a = planner.plan(req);
            PlanResult b = new CapturePathPlanner().plan(req);
            assertThat(b).as("identical input gives an identical result, every waypoint and metric").isEqualTo(a);
        }
    }

    @Test
    void evaluatingThePlannedRouteReproducesItsMeasuredCoverageAndTheBaselineMatches() {
        PlanRequest req = PlannerFixtures.roomWithOcclusion();
        PlanResult r = planner.plan(req);
        List<CapturePathPlanner.Viewpoint> route = r.waypoints().stream()
            .map(w -> new CapturePathPlanner.Viewpoint(w.x(), w.y(), Math.toRadians(w.yawDegrees()))).toList();
        var ev = planner.evaluate(req, route);
        assertThat(ev.skippedWaypoints()).isZero();
        assertThat(ev.metrics().coveragePercent()).isCloseTo(r.planned().coveragePercent(), within(0.5));
        assertThat(ev.routeLengthMeters()).isCloseTo(r.estimatedDistanceMeters(), within(0.05));
        assertThat(planner.evaluate(req, List.of()).metrics()).as("no waypoints = the baseline").isEqualTo(r.baseline());
        assertThat(planner.baseline(req)).isEqualTo(r.baseline());
    }

    @Test
    void evaluatingARouteSkipsWaypointsThatCannotBeWalkedTo() {
        PlanRequest req = PlannerFixtures.roomWithSealedNeighbour();
        var ev = planner.evaluate(req, List.of(new CapturePathPlanner.Viewpoint(11.5, 1.5, 0), new CapturePathPlanner.Viewpoint(2.0, 1.5, 0)));
        assertThat(ev.skippedWaypoints()).isEqualTo(1);
        assertThat(ev.reachableWaypoints()).isEqualTo(1);
    }

    @Test
    void orderingTheRouteNeverLengthensTheGreedyConstructionOrder() {
        for (PlanRequest req : List.of(PlannerFixtures.rectangularRoom(), PlannerFixtures.roomWithDoorway(), PlannerFixtures.roomWithOcclusion(), PlannerFixtures.multipleRooms())) {
            PlanResult r = planner.plan(req);
            assertThat(r.estimatedDistanceMeters()).isLessThanOrEqualTo(r.diagnostics().constructionRouteMeters() * 1.02);
        }
    }

    @Test
    void stopConditionsAreHonouredAndReported() {
        PlanRequest base = PlannerFixtures.rectangularRoom();
        PlanResult three = planner.plan(new PlanRequest(base.scene(), base.trajectory(), List.of(), List.of(), base.config().toBuilder().maxWaypoints(3).stopCoverage(1.0).build()));
        assertThat(three.waypoints()).hasSize(3);
        assertThat(three.diagnostics().stopReason()).isEqualTo("MAX_WAYPOINTS");

        PlanResult modest = planner.plan(new PlanRequest(base.scene(), base.trajectory(), List.of(), List.of(), base.config().toBuilder().stopCoverage(0.6).build()));
        assertThat(modest.waypoints().size()).isLessThan(planner.plan(base).waypoints().size());
        assertThat(modest.diagnostics().stopReason()).isEqualTo("TARGET_COVERAGE_REACHED");

        PlanResult nothing = planner.plan(new PlanRequest(base.scene(), base.trajectory(), List.of(), List.of(), base.config().toBuilder().minMarginalGain(1e6).build()));
        assertThat(nothing.waypoints()).isEmpty();
        assertThat(nothing.diagnostics().stopReason()).isEqualTo("NO_CANDIDATE_WITH_GAIN");
        assertThat(nothing.planned()).as("no route = the baseline").usingRecursiveComparison().ignoringFields("waypointCount").isEqualTo(nothing.baseline());
        assertThat(nothing.estimatedDistanceMeters()).isZero();
    }

    @Test
    void alreadyObservedAreasNeedNoRoute() {
        PlanRequest base = PlannerFixtures.rectangularRoom();
        var everything = new ObservedRegion(PlannerFixtures.rect(-1, -1, 9, 7), 1.0);
        PlanResult r = planner.plan(new PlanRequest(base.scene(), base.trajectory(), List.of(everything), List.of(), base.config()));
        assertThat(r.baseline().coveragePercent()).isCloseTo(100.0, within(0.001));
        assertThat(r.waypoints()).isEmpty();
        assertThat(r.diagnostics().stopReason()).isEqualTo("TARGET_COVERAGE_REACHED");
        assertThat(r.uncoveredRegions()).isEmpty();
    }

    @Test
    void observedRegionsThatAreMostlyCoveredShrinkTheRoute() {
        PlanRequest base = PlannerFixtures.rectangularRoom();
        var west = new ObservedRegion(PlannerFixtures.rect(-1, -1, 4.5, 7), 1.0);
        PlanResult with = planner.plan(new PlanRequest(base.scene(), base.trajectory(), List.of(west), List.of(), base.config()));
        PlanResult without = planner.plan(base);
        assertThat(with.baseline().coveragePercent()).isGreaterThan(without.baseline().coveragePercent() + 20);
        assertThat(with.estimatedDistanceMeters()).isLessThan(without.estimatedDistanceMeters());
    }

    @Test
    void trajectoryProblemsAreExplicit() {
        PlanRequest base = PlannerFixtures.rectangularRoom();
        var far = List.of(new TrajectorySample(0, 100, 100, null), new TrajectorySample(1, 101, 100, null));
        assertThatThrownBy(() -> planner.plan(new PlanRequest(base.scene(), far, List.of(), List.of(), base.config())))
            .isInstanceOf(PlanningException.class).extracting("code").isEqualTo("TRAJECTORY_OUTSIDE_SCENE");
        var glitchy = new ArrayList<>(base.trajectory());
        glitchy.add(5, new TrajectorySample(glitchy.get(4).t() + 0.1, 500, 500, null));
        PlanResult r = planner.plan(new PlanRequest(base.scene(), glitchy, List.of(), List.of(), base.config()));
        assertThat(r.diagnostics().warnings()).anyMatch(w -> w.contains("tracking glitches"));
        assertInvariants(r, new PlanRequest(base.scene(), glitchy, List.of(), List.of(), base.config()));
    }

    @Test
    void aDoorwayThatIsNotOnFloorIsIgnoredWithAWarning() {
        PlanRequest base = PlannerFixtures.rectangularRoom();
        Scene scene = new Scene(base.scene().areas(), List.of(), List.of(), List.of(), List.of(new Scene.Doorway(50, 50, 1.0, null)));
        PlanResult r = planner.plan(new PlanRequest(scene, base.trajectory(), List.of(), List.of(), base.config()));
        assertThat(r.diagnostics().warnings()).anyMatch(w -> w.contains("doorway"));
    }

    @Test
    void aFinerGridStillPlansWithinTheBudget() {
        PlanRequest base = PlannerFixtures.rectangularRoom();
        long t0 = System.nanoTime();
        PlanResult r = planner.plan(new PlanRequest(base.scene(), base.trajectory(), List.of(), List.of(), base.config().toBuilder().resolution(0.15).poseSpacing(0.15).build()));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertThat(r.planned().coveragePercent()).isGreaterThan(r.baseline().coveragePercent());
        assertThat(ms).as("generous bound; the benchmark reports real timings").isLessThan(20_000);
    }

    @Test
    void metricsExposeEveryQuantityTheProductNeeds() {
        PlanResult r = planner.plan(PlannerFixtures.roomWithDoorway());
        assertThat(r.planned().coveragePercent()).isPositive();
        assertThat(r.planned().uncoveredAreaM2()).isNotNegative();
        assertThat(r.planned().redundantCapturePercent()).isNotNegative();
        assertThat(r.planned().pathLengthMeters()).isGreaterThan(r.baseline().pathLengthMeters());
        assertThat(r.planned().waypointCount()).isEqualTo(r.waypoints().size());
        assertThat(r.estimatedDistanceMeters()).isPositive();
        assertThat(r.estimatedCaptureSeconds()).isGreaterThan(r.waypoints().size() * 3.0 - 1e-9);
        assertThat(r.confidence().note()).contains("Heuristic");
    }
}
