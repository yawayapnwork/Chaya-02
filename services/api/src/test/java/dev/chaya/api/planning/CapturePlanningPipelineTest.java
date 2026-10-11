package dev.chaya.api.planning;

import static dev.chaya.api.planning.PlannerFixtures.lap;
import static dev.chaya.api.planning.PlannerFixtures.rect;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import dev.chaya.api.planning.CapturePathPlanner.Assessment;
import dev.chaya.api.planning.CapturePathPlanner.Progress;
import dev.chaya.api.planning.CapturePathPlanner.Viewpoint;
import dev.chaya.api.planning.PlanResult.Waypoint;
import dev.chaya.api.planning.RoomBoundsEstimator.Estimate;
import dev.chaya.api.planning.geometry.Geometry;
import dev.chaya.api.planning.geometry.Point;
import dev.chaya.api.planning.geometry.Polygon;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Reconnaissance walk -> estimated bounds -> planned scan path -> progress, on hand-drawn ground truth. Observations
 * come from SyntheticRangeSensor (test only). Every route is finally measured against the TRUE scene, not the
 * estimate, so an estimate that invents floor or misses walls shows up as lost coverage.
 */
class CapturePlanningPipelineTest {

    private final RoomBoundsEstimator estimator = new RoomBoundsEstimator();
    private final CapturePathPlanner planner = new CapturePathPlanner();

    private static Scene room(Polygon outline, Polygon... obstacles) {
        return new Scene(List.of(outline), List.of(), List.of(obstacles), List.of(), List.of());
    }

    private PlanRequest planRequest(Estimate e, List<TrajectorySample> walk) {
        return new PlanRequest(EstimatedScene.scene(e), walk, List.of(), List.of(), PlannerConfig.defaults(), EstimatedScene.priorities(e));
    }

    /** Walks the plan as an operator would: along the route polyline, then facing each waypoint's heading at it. */
    private static List<TrajectorySample> follow(PlanResult plan, double t0, int segment) {
        List<TrajectorySample> out = new ArrayList<>();
        double t = t0;
        int next = 0;
        for (int i = 0; i + 1 < plan.route().size(); i++) {
            var a = plan.route().get(i);
            var b = plan.route().get(i + 1);
            double len = Math.hypot(b.x() - a.x(), b.y() - a.y());
            double heading = Math.atan2(b.y() - a.y(), b.x() - a.x());
            int steps = Math.max(1, (int) Math.ceil(len / 0.25));
            for (int k = 1; k <= steps; k++) {
                t += 0.5;
                double f = (double) k / steps;
                out.add(new TrajectorySample(t, a.x() + (b.x() - a.x()) * f, a.y() + (b.y() - a.y()) * f, heading, segment));
            }
            while (next < plan.waypoints().size()) {
                Waypoint w = plan.waypoints().get(next);
                if (Math.hypot(w.x() - b.x(), w.y() - b.y()) > 1e-3) {
                    break;
                }
                t += 1;
                out.add(new TrajectorySample(t, w.x() + 0.01, w.y(), Math.toRadians(w.yawDegrees()), segment));
                next++;
            }
        }
        return out;
    }

    private static List<TrajectorySample> concat(List<TrajectorySample> a, List<TrajectorySample> b) {
        List<TrajectorySample> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    @Test
    void estimatedBoundsPlanAPathThatRaisesTrueCoverageAndNeverLeavesTheTrueRoom() {
        Scene truth = room(rect(0, 0, 8, 6));
        List<TrajectorySample> walk = lap(1.0, 2, 2, 6, 2, 6, 4, 2, 4, 2, 2.5);
        Estimate e = estimator.estimate(walk, new SyntheticRangeSensor(truth, 8).sweep(walk, 7, 6, 40, null), null);
        assertThat(e.status()).isEqualTo("ESTIMATED");

        PlanResult plan = planner.plan(planRequest(e, walk));
        assertThat(plan.waypoints()).isNotEmpty();
        Grid trueGrid = Grid.build(truth, PlannerConfig.defaults());
        for (Waypoint w : plan.waypoints()) {
            int c = trueGrid.cellOf(w.x(), w.y());
            assertThat(c >= 0 && trueGrid.standable[c]).as("waypoint %d (%s, %s) is standable in the true room", w.order(), w.x(), w.y()).isTrue();
        }
        // Measured against the true room, with the same metric.
        PlanRequest onTruth = PlannerFixtures.request(truth, walk);
        var baseline = planner.baseline(onTruth);
        var route = planner.evaluate(onTruth, plan.waypoints().stream().map(w -> new Viewpoint(w.x(), w.y(), Math.toRadians(w.yawDegrees()))).toList());
        assertThat(route.skippedWaypoints()).isZero();
        assertThat(route.metrics().coveragePercent()).isGreaterThan(baseline.coveragePercent() + 20);
        assertThat(route.metrics().coveragePercent()).as("close to what the planner predicted on its estimate")
            .isCloseTo(plan.planned().coveragePercent(), within(12.0));
    }

    @Test
    void anUnobservedWallIsPrioritisedTheRouteLooksAtIt() {
        Scene truth = room(rect(0, 0, 8, 6));
        List<TrajectorySample> walk = lap(1.0, 2, 1.5, 6, 1.5, 6, 2.5, 2, 2.5, 2, 2);
        Estimate e = estimator.estimate(walk, new SyntheticRangeSensor(truth, 8).sweep(walk, 7, 6, 40, new double[] {180, 360}), null);
        assertThat(e.frontier()).isNotEmpty();

        PlanResult plan = planner.plan(planRequest(e, walk));
        List<Waypoint> unobserved = plan.waypoints().stream().filter(w -> w.type().equals("UNOBSERVED")).toList();
        assertThat(unobserved).as("waypoints typed UNOBSERVED").isNotEmpty();
        assertThat(unobserved.stream().anyMatch(w -> Geometry.angleDiff(Math.toRadians(w.yawDegrees()), Math.PI / 2) <= Math.toRadians(60)))
            .as("at least one faces the unobserved north boundary").isTrue();
        // The bounds are a lower bound, so every waypoint stays inside what was observed (and hence inside the true room).
        for (Waypoint w : plan.waypoints()) {
            assertThat(e.area().contains(w.x(), w.y())).isTrue();
            assertThat(truth.areas().get(0).contains(w.x(), w.y())).isTrue();
        }
        // The same plan without the frontier priority puts less weight there: priorities change the route.
        PlanResult unprioritised = planner.plan(new PlanRequest(EstimatedScene.scene(e), walk, List.of(), List.of(), PlannerConfig.defaults()));
        assertThat(unprioritised.waypoints().stream().noneMatch(w -> w.type().equals("UNOBSERVED"))).isTrue();
    }

    @Test
    void afterWalkingTheRouteAndLookingAtTheFrontierTheReestimatedRoomGrows() {
        Scene truth = room(rect(0, 0, 8, 6));
        SyntheticRangeSensor sensor = new SyntheticRangeSensor(truth, 8);
        List<TrajectorySample> walk = lap(1.0, 2, 1.5, 6, 1.5, 6, 2.5, 2, 2.5, 2, 2);
        Estimate first = estimator.estimate(walk, sensor.sweep(walk, 7, 6, 40, new double[] {180, 360}), null);
        PlanResult plan = planner.plan(planRequest(first, walk));

        List<TrajectorySample> walked = follow(plan, walk.get(walk.size() - 1).t(), 0);
        List<TrajectorySample> all = concat(walk, walked);
        var obs = new ArrayList<>(sensor.sweep(walk, 7, 6, 40, new double[] {180, 360}));
        obs.addAll(sensor.sweep(walked, 7, 6, 40, null));
        Estimate second = estimator.estimate(all, obs, null);
        assertThat(second.areaM2()).isGreaterThan(first.areaM2() + 5);
        assertThat(second.confidence()).isGreaterThan(first.confidence());
        assertThat(SyntheticRangeSensor.iou(second.area(), second.obstacles(), truth.areas().get(0), 0.05)).isGreaterThan(0.85);
    }

    @Test
    void anOccludedAreaIsReachedIterativelyLookPastTheOccluderThenPlanBehindIt() {
        Polygon outline = rect(0, 0, 10, 6);
        Scene truth = room(outline, rect(4.5, 1.0, 5.5, 5.0));
        SyntheticRangeSensor sensor = new SyntheticRangeSensor(truth, 9);
        List<TrajectorySample> walk = lap(1.0, 1.5, 1.5, 3.5, 1.5, 3.5, 4.5, 1.5, 4.5, 1.5, 2);
        Estimate first = estimator.estimate(walk, sensor.sweep(walk, 7, 6, 40, null), null);
        assertThat(first.area()).isNotNull();
        assertThat(first.area().contains(8, 3)).as("the hidden side is not assumed").isFalse();

        // The first plan cannot stand behind the partition (it is not known floor) but looks past its ends.
        PlanResult plan = planner.plan(planRequest(first, walk));
        assertThat(plan.waypoints()).allMatch(w -> first.area().contains(w.x(), w.y()));
        assertThat(plan.waypoints().stream().anyMatch(w -> w.type().equals("UNOBSERVED"))).isTrue();

        // What the HUD does as evidence arrives: walk the plan (looking around at each step), re-estimate, re-plan.
        List<TrajectorySample> all = new ArrayList<>(walk);
        List<RoomBoundsEstimator.SurfaceObservation> obs = new ArrayList<>(sensor.sweep(walk, 7, 6, 40, null));
        Estimate current = first;
        PlanResult currentPlan = plan;
        int rounds = 0;
        while (!current.area().contains(7.5, 3) && rounds < 4) {
            List<TrajectorySample> walked = follow(currentPlan, all.get(all.size() - 1).t(), 0);
            all.addAll(walked);
            obs.addAll(sensor.sweep(walked));
            Estimate next = estimator.estimate(all, obs, null);
            assertThat(next.areaM2()).as("observation only ever grows the known floor").isGreaterThanOrEqualTo(current.areaM2() - 0.5);
            current = next;
            currentPlan = planner.plan(planRequest(current, all));
            rounds++;
        }
        assertThat(current.area().contains(7.5, 3)).as("observed past the partition within 4 rounds").isTrue();
        assertThat(currentPlan.waypoints().stream().anyMatch(w -> w.x() > 5.5) || current.status().equals("ESTIMATED"))
            .as("then the plan reaches behind the partition, or nothing there is left to cover").isTrue();
        for (Waypoint w : currentPlan.waypoints()) {
            assertThat(truth.areas().get(0).contains(w.x(), w.y()) && !truth.obstacles().get(0).contains(w.x(), w.y())).isTrue();
        }
    }

    @Test
    void impossibleWaypointsAreRejectedOrReportedNeverPlanned() {
        // A supplied viewpoint outside the estimated bounds, one in an obstacle and one in a sealed area are refused with a reason.
        Scene scene = new Scene(List.of(rect(0, 0, 10, 6), rect(10, 0, 13, 3)), List.of(PlannerFixtures.wall(10, 0, 10, 3)),
            List.of(rect(4, 2, 6, 4)), List.of(), List.of());
        List<TrajectorySample> walk = lap(1.0, 1.5, 1.5, 8.5, 1.5, 8.5, 4.5);
        PlanRequest req = new PlanRequest(scene, walk, List.of(), List.of(
            new CandidateViewpoint(20, 20, "outside"), new CandidateViewpoint(5, 3, "in obstacle"), new CandidateViewpoint(11.5, 1.5, "sealed")),
            PlannerConfig.defaults());
        PlanResult plan = planner.plan(req);
        assertThat(plan.rejectedCandidates()).extracting(PlanResult.RejectedCandidate::reason)
            .containsExactly("OUTSIDE_BOUNDS", "IN_OBSTACLE", "UNREACHABLE");
        for (Waypoint w : plan.waypoints()) {
            assertThat(w.x()).isLessThan(10);
        }
        // A persisted plan whose waypoint area is later found to be an obstacle: progress reports it UNREACHABLE.
        Scene before = new Scene(List.of(rect(0, 0, 8, 6)), List.of(), List.of(), List.of(), List.of());
        List<TrajectorySample> lap = lap(1.0, 1.5, 1.5, 6.5, 1.5, 6.5, 4.5, 4.5, 4.5);
        PlanResult p1 = planner.plan(PlannerFixtures.request(before, lap));
        Waypoint target = p1.waypoints().get(0);
        Scene after = new Scene(List.of(rect(0, 0, 8, 6)), List.of(),
            List.of(rect(target.x() - 0.6, target.y() - 0.6, target.x() + 0.6, target.y() + 0.6)), List.of(), List.of());
        Progress pr = planner.progress(PlannerFixtures.request(after, lap), p1.waypoints(), List.of(), 0.5);
        assertThat(pr.waypoints().get(0).status()).isEqualTo("UNREACHABLE");
        assertThat(pr.nextOrder()).isNotEqualTo(1);
    }

    @Test
    void progressMarksVisitedSkippedAndPendingAndCompletesTheRoute() {
        PlanRequest req = PlannerFixtures.rectangularRoom();
        PlanResult plan = planner.plan(req);
        assertThat(plan.waypoints().size()).isGreaterThan(3);
        double t0 = req.trajectory().get(req.trajectory().size() - 1).t();

        Progress none = planner.progress(req, plan.waypoints(), List.of(), 0.5);
        assertThat(none.completion()).isEqualTo("NOT_STARTED");
        assertThat(none.nextOrder()).isEqualTo(1);
        assertThat(none.nextWalkMeters()).isCloseTo(plan.waypoints().get(0).legDistanceMeters(), within(0.3));
        assertThat(none.nextLeg()).isNotEmpty();

        // Visit waypoint 1 facing its heading, then go straight to waypoint 3.
        Waypoint w1 = plan.waypoints().get(0), w3 = plan.waypoints().get(2);
        List<TrajectorySample> since = List.of(
            new TrajectorySample(t0 + 5, w1.x(), w1.y(), Math.toRadians(w1.yawDegrees())),
            new TrajectorySample(t0 + 20, w3.x() + 0.2, w3.y(), Math.toRadians(w3.yawDegrees())));
        Progress mid = planner.progress(req.withTrajectory(concat(req.trajectory(), since)), plan.waypoints(), since, 0.5);
        assertThat(mid.waypoints().get(0).status()).isEqualTo("VISITED");
        assertThat(mid.waypoints().get(1).status()).isEqualTo("SKIPPED");
        assertThat(mid.waypoints().get(2).status()).isEqualTo("VISITED");
        assertThat(mid.waypoints().get(3).status()).isEqualTo("PENDING");
        assertThat(mid.nextOrder()).as("the skipped waypoint comes first").isEqualTo(2);
        assertThat(mid.completion()).isEqualTo("IN_PROGRESS");
        assertThat(mid.headingVerified()).isTrue();

        // Standing at a waypoint facing the wrong way is not a visit.
        List<TrajectorySample> wrongWay = List.of(new TrajectorySample(t0 + 5, w1.x(), w1.y(), Math.toRadians(w1.yawDegrees()) + Math.PI));
        Progress wrong = planner.progress(req.withTrajectory(concat(req.trajectory(), wrongWay)), plan.waypoints(), wrongWay, 0.5);
        assertThat(wrong.waypoints().get(0).status()).isEqualTo("PENDING");

        List<TrajectorySample> walked = follow(plan, t0, 0);
        Progress done = planner.progress(req.withTrajectory(concat(req.trajectory(), walked)), plan.waypoints(), walked, 0.5);
        assertThat(done.completion()).isEqualTo("ROUTE_COMPLETE");
        assertThat(done.visited()).isEqualTo(plan.waypoints().size());
        assertThat(done.nextOrder()).isNull();
    }

    @Test
    void liveAssessmentReportsCoverageGapsAndTheyCloseAsTheRouteIsWalked() {
        PlanRequest req = PlannerFixtures.rectangularRoom();
        Assessment before = planner.assess(req);
        assertThat(before.observed().coveragePercent()).isEqualTo(planner.baseline(req).coveragePercent());
        assertThat(before.uncoveredRegions()).isNotEmpty();
        assertThat(before.uncoveredRegions()).extracting(PlanResult.UncoveredRegion::reason)
            .containsAnyOf("NOT_OBSERVED", "INSUFFICIENTLY_OBSERVED");
        double gapBefore = before.uncoveredRegions().stream().mapToDouble(PlanResult.UncoveredRegion::areaM2).sum();

        PlanResult plan = planner.plan(req);
        List<TrajectorySample> walked = follow(plan, req.trajectory().get(req.trajectory().size() - 1).t(), 0);
        Assessment after = planner.assess(req.withTrajectory(concat(req.trajectory(), walked)));
        double gapAfter = after.uncoveredRegions().stream().mapToDouble(PlanResult.UncoveredRegion::areaM2).sum();
        assertThat(after.observed().coveragePercent()).isGreaterThan(before.observed().coveragePercent() + 30);
        assertThat(gapAfter).isLessThan(gapBefore / 2);
    }

    @Test
    void nothingIsCreditedAcrossATrackingLoss() {
        Scene scene = new Scene(List.of(rect(0, 0, 12, 4)), List.of(), List.of(), List.of(), List.of());
        List<TrajectorySample> a = lap(1.0, 1, 2, 3, 2);
        List<TrajectorySample> b = lap(1.0, 9, 2, 11, 2);
        List<TrajectorySample> joined = new ArrayList<>(a);
        List<TrajectorySample> split = new ArrayList<>(a);
        double t0 = a.get(a.size() - 1).t() + 6.5; // 6 m in 6.5 s: plausible walking, so only the segment break separates them
        for (TrajectorySample s : b) {
            joined.add(new TrajectorySample(t0 + s.t(), s.x(), s.y(), null, 0));
            split.add(new TrajectorySample(t0 + s.t(), s.x(), s.y(), null, 1));
        }
        Assessment j = planner.assess(PlannerFixtures.request(scene, joined));
        Assessment s = planner.assess(PlannerFixtures.request(scene, split));
        assertThat(j.trajectorySegments()).isEqualTo(1);
        assertThat(s.trajectorySegments()).isEqualTo(2);
        assertThat(s.trajectoryLengthMeters()).isCloseTo(4.0, within(0.01));
        assertThat(j.trajectoryLengthMeters()).isCloseTo(10.0, within(0.01));
        assertThat(s.observed().coveredAreaM2()).as("the unwalked middle is not credited").isLessThan(j.observed().coveredAreaM2());
    }

    @Test
    void invalidPriorityRegionsAreRefused() {
        PlanRequest base = PlannerFixtures.rectangularRoom();
        PlanRequest bad = new PlanRequest(base.scene(), base.trajectory(), List.of(), List.of(), base.config(),
            List.of(PriorityRegion.around(List.of(new Point(1, 1), new Point(2, 2)), 0, 2, "UNOBSERVED_BOUNDARY")));
        assertThatThrownBy(() -> planner.plan(bad)).isInstanceOf(PlanningException.class).hasMessageContaining("priorityRegions");
        PlanRequest heavy = new PlanRequest(base.scene(), base.trajectory(), List.of(), List.of(), base.config(),
            List.of(PriorityRegion.inside(rect(1, 1, 2, 2), 50, "UNOBSERVED_INTERIOR")));
        assertThatThrownBy(() -> planner.plan(heavy)).isInstanceOf(PlanningException.class);
    }
}
