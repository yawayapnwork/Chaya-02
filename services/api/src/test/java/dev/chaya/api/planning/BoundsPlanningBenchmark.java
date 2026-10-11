package dev.chaya.api.planning;

import static dev.chaya.api.planning.PlannerFixtures.lap;
import static dev.chaya.api.planning.PlannerFixtures.rect;

import dev.chaya.api.planning.CapturePathPlanner.Viewpoint;
import dev.chaya.api.planning.PlanResult.Waypoint;
import dev.chaya.api.planning.RoomBoundsEstimator.Estimate;
import dev.chaya.api.planning.RoomBoundsEstimator.SurfaceObservation;
import dev.chaya.api.planning.geometry.Point;
import dev.chaya.api.planning.geometry.Polygon;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Automatic capture planning end to end on hand-drawn ground truth: reconnaissance walk -> estimated bounds -> plan, with
 * every route finally measured against the TRUE scene (not the estimate), next to the same planner given the true bounds
 * (an oracle). Observations come from SyntheticRangeSensor, a deterministic ray caster with no noise: these numbers bound
 * what the algorithm does with perfect hits; they are not field accuracy. Also times the live HUD tick (assess) against a
 * full plan.
 *
 * <p>Run: scripts/benchmark-planner.sh --bounds [--json out.json]
 */
public final class BoundsPlanningBenchmark {

    private record Fixture(String name, Scene truth, List<TrajectorySample> walk, Supplier<List<SurfaceObservation>> observations) {}

    private record Row(String fixture, String status, double confidence, double boundaryObserved, double iou, double precision, double recall,
                       double estimateMs, int waypoints, int invalidWaypoints, double routeMeters, double predicted, double trueBaseline,
                       double trueCoverage, double oracleCoverage, int unobservedWaypoints, String note) {}

    public static void main(String[] args) throws IOException {
        Path json = null;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--json") && i + 1 < args.length) {
                json = Path.of(args[++i]);
            }
        }
        List<Fixture> fixtures = new ArrayList<>();
        Scene rect = room(rect(0, 0, 8, 6));
        List<TrajectorySample> rectWalk = lap(1.0, 2, 2, 6, 2, 6, 4, 2, 4, 2, 2.5);
        fixtures.add(new Fixture("rectangular 8x6", rect, rectWalk, () -> new SyntheticRangeSensor(rect, 8).sweep(rectWalk, 7, 6, 40, null)));
        List<TrajectorySample> southWalk = lap(1.0, 2, 1.5, 6, 1.5, 6, 2.5, 2, 2.5, 2, 2);
        fixtures.add(new Fixture("rectangular 8x6, north wall never faced", rect, southWalk,
            () -> new SyntheticRangeSensor(rect, 8).sweep(southWalk, 7, 6, 40, new double[] {180, 360})));
        fixtures.add(new Fixture("rectangular 8x6, 6 observations only", rect, rectWalk,
            () -> new SyntheticRangeSensor(rect, 8).sweep(rectWalk, 1, 0, 90, null).subList(0, 6)));
        Polygon l = new Polygon(List.of(new Point(0, 0), new Point(9, 0), new Point(9, 4), new Point(4, 4), new Point(4, 8), new Point(0, 8)));
        Scene lRoom = room(l);
        List<TrajectorySample> lWalk = lap(1.0, 2, 6.5, 2, 2, 7.5, 2, 2, 2, 2, 5);
        fixtures.add(new Fixture("L-shaped 9x8", lRoom, lWalk, () -> new SyntheticRangeSensor(lRoom, 8).sweep(lWalk, 7, 6, 40, null)));
        Polygon slanted = new Polygon(List.of(new Point(0, 0), new Point(7, 0), new Point(9, 3), new Point(7, 6), new Point(0, 6)));
        Scene slantRoom = room(slanted);
        List<TrajectorySample> sWalk = lap(1.0, 2, 2, 6, 2, 6.5, 4, 2, 4, 2, 2.5);
        fixtures.add(new Fixture("irregular, slanted walls", slantRoom, sWalk, () -> new SyntheticRangeSensor(slantRoom, 8).sweep(sWalk, 7, 6, 40, null)));
        Scene pillar = room(rect(0, 0, 10, 6), rect(4.5, 2.5, 5.5, 3.5));
        List<TrajectorySample> pWalk = lap(1.0, 1.5, 1.5, 3.5, 1.5, 3.5, 4.5, 1.5, 4.5, 1.5, 2);
        fixtures.add(new Fixture("10x6 + pillar, walk on one side", pillar, pWalk, () -> new SyntheticRangeSensor(pillar, 8).sweep(pWalk, 7, 6, 40, null)));
        Scene partition = room(rect(0, 0, 10, 6), rect(4.5, 1.0, 5.5, 5.0));
        fixtures.add(new Fixture("10x6 + partition, walk on one side", partition, pWalk,
            () -> new SyntheticRangeSensor(partition, 8).sweep(pWalk, 7, 6, 40, null)));
        PlanRequest office = PlannerBenchmark.office();
        fixtures.add(new Fixture("office 16x10 + annex, 3 obstacles", office.scene(), office.trajectory(),
            () -> new SyntheticRangeSensor(office.scene(), 8).sweep(office.trajectory(), 7, 6, 40, null)));

        CapturePathPlanner planner = new CapturePathPlanner();
        RoomBoundsEstimator estimator = new RoomBoundsEstimator();
        List<Row> rows = new ArrayList<>();
        for (Fixture f : fixtures) {
            List<SurfaceObservation> obs = f.observations().get();
            Estimate e = null;
            long[] times = new long[7];
            for (int i = 0; i < 3; i++) {
                e = estimator.estimate(f.walk(), obs, null);
            }
            for (int i = 0; i < times.length; i++) {
                long t0 = System.nanoTime();
                e = estimator.estimate(f.walk(), obs, null);
                times[i] = System.nanoTime() - t0;
            }
            Arrays.sort(times);
            double ms = times[times.length / 2] / 1e6;
            PlanRequest onTruth = PlannerFixtures.request(f.truth(), f.walk());
            double trueBaseline = planner.baseline(onTruth).coveragePercent();
            PlanResult oracle = planner.plan(onTruth);
            double oracleCoverage = oracle.planned().coveragePercent();
            Polygon trueOutline = f.truth().areas().size() == 1 ? f.truth().areas().get(0) : null;
            if (e.area() == null) {
                rows.add(new Row(f.name(), e.status(), e.confidence(), e.confidenceParts().boundaryObserved(), 0, 0, 0, ms, 0, 0, 0, 0,
                    trueBaseline, trueBaseline, oracleCoverage, 0, e.issues().stream().map(RoomBoundsEstimator.Issue::code).toList().toString()));
                continue;
            }
            double iou = trueOutline == null ? Double.NaN : SyntheticRangeSensor.iou(e.area(), e.obstacles(), trueOutline, 0.05);
            double precision = trueOutline == null ? Double.NaN : SyntheticRangeSensor.containedShare(e.area(), trueOutline, 0.05);
            double recall = trueOutline == null ? Double.NaN : SyntheticRangeSensor.containedShare(trueOutline, e.area(), 0.05);
            PlanResult plan = planner.plan(new PlanRequest(EstimatedScene.scene(e), f.walk(), List.of(), List.of(), PlannerConfig.defaults(),
                EstimatedScene.priorities(e)));
            Grid truthGrid = Grid.build(f.truth(), PlannerConfig.defaults());
            int invalid = 0, unobserved = 0;
            for (Waypoint w : plan.waypoints()) {
                int c = truthGrid.cellOf(w.x(), w.y());
                if (c < 0 || !truthGrid.standable[c]) invalid++;
                if (w.type().equals("UNOBSERVED")) unobserved++;
            }
            var onTrue = planner.evaluate(onTruth, plan.waypoints().stream().map(w -> new Viewpoint(w.x(), w.y(), Math.toRadians(w.yawDegrees()))).toList());
            rows.add(new Row(f.name(), e.status(), e.confidence(), e.confidenceParts().boundaryObserved(), iou, precision, recall, ms,
                plan.waypoints().size(), invalid, plan.estimatedDistanceMeters(), plan.planned().coveragePercent(), trueBaseline,
                onTrue.metrics().coveragePercent(), oracleCoverage, unobserved, ""));
        }

        // Iterating as the HUD does: walk the plan looking around, re-estimate, re-plan.
        List<String> iterations = new ArrayList<>();
        for (String name : List.of("10x6 + partition, walk on one side", "rectangular 8x6, north wall never faced")) {
            Fixture f = fixtures.stream().filter(x -> x.name().equals(name)).findFirst().orElseThrow();
            SyntheticRangeSensor sensor = new SyntheticRangeSensor(f.truth(), 8);
            List<TrajectorySample> all = new ArrayList<>(f.walk());
            List<SurfaceObservation> obs = new ArrayList<>(f.observations().get());
            Polygon truth = f.truth().areas().get(0);
            Estimate e = estimator.estimate(all, obs, null);
            StringBuilder line = new StringBuilder(String.format(Locale.ROOT, "%-42s round 0: %s IoU %.2f", name, e.status(),
                SyntheticRangeSensor.iou(e.area(), e.obstacles(), truth, 0.05)));
            for (int round = 1; round <= 4 && !e.status().equals("ESTIMATED"); round++) {
                PlanResult plan = planner.plan(new PlanRequest(EstimatedScene.scene(e), all, List.of(), List.of(), PlannerConfig.defaults(),
                    EstimatedScene.priorities(e)));
                List<TrajectorySample> walked = follow(plan, all.get(all.size() - 1).t());
                all.addAll(walked);
                obs.addAll(sensor.sweep(walked, 7, 6, 40, null));
                e = estimator.estimate(all, obs, null);
                var cov = planner.baseline(PlannerFixtures.request(f.truth(), all)).coveragePercent();
                line.append(String.format(Locale.ROOT, " | round %d: +%d wp, %s IoU %.2f, true coverage walked %.1f%%", round,
                    plan.waypoints().size(), e.status(), SyntheticRangeSensor.iou(e.area(), e.obstacles(), truth, 0.05), cov));
            }
            iterations.add(line.toString());
        }

        // Live HUD tick: assess() on a long trajectory, against a full plan() on the same input.
        List<TrajectorySample> longWalk = new ArrayList<>();
        double t = 0;
        for (int loop = 0; loop < 10; loop++) {
            for (TrajectorySample s : lap(0.5, 1.5, 1.5, 6.5, 1.5, 6.5, 4.5, 1.5, 4.5, 1.5, 1.6)) {
                longWalk.add(new TrajectorySample(t + s.t(), s.x(), s.y(), null));
            }
            t = longWalk.get(longWalk.size() - 1).t() + 0.5;
        }
        PlanRequest longReq = PlannerFixtures.request(rect, longWalk);
        double assessMs = median(() -> planner.assess(longReq));
        double planMs = median(() -> planner.plan(longReq));

        print(rows, iterations, longWalk.size(), assessMs, planMs);
        if (json != null) {
            writeJson(json, rows, iterations, longWalk.size(), assessMs, planMs);
            System.out.println("JSON written to " + json);
        }
    }

    private static Scene room(Polygon outline, Polygon... obstacles) {
        return new Scene(List.of(outline), List.of(), List.of(obstacles), List.of(), List.of());
    }

    private static double median(Runnable r) {
        for (int i = 0; i < 3; i++) r.run();
        long[] times = new long[7];
        for (int i = 0; i < times.length; i++) {
            long t0 = System.nanoTime();
            r.run();
            times[i] = System.nanoTime() - t0;
        }
        Arrays.sort(times);
        return times[times.length / 2] / 1e6;
    }

    /** Walks the plan's route; at each waypoint one sample faces the waypoint heading. */
    static List<TrajectorySample> follow(PlanResult plan, double t0) {
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
                out.add(new TrajectorySample(t, a.x() + (b.x() - a.x()) * f, a.y() + (b.y() - a.y()) * f, heading));
            }
            while (next < plan.waypoints().size()
                && Math.hypot(plan.waypoints().get(next).x() - b.x(), plan.waypoints().get(next).y() - b.y()) <= 1e-3) {
                Waypoint w = plan.waypoints().get(next++);
                t += 1;
                out.add(new TrajectorySample(t, w.x() + 0.01, w.y(), Math.toRadians(w.yawDegrees())));
            }
        }
        return out;
    }

    private static void print(List<Row> rows, List<String> iterations, int longPoses, double assessMs, double planMs) {
        System.out.println("Bounds estimation and planning on estimated bounds (synthetic ground truth, noise-free hits)\n");
        System.out.printf(Locale.ROOT, "%-40s %-15s %5s %5s %5s %5s %5s %6s | %3s %3s %3s %6s %6s %6s %6s %6s%n", "fixture", "bounds", "conf",
            "bObs", "IoU", "prec", "rec", "ms", "wp", "bad", "unO", "route", "pred%", "base%", "true%", "orcl%");
        for (Row r : rows) {
            System.out.printf(Locale.ROOT, "%-40s %-15s %5.2f %5.2f %5.2f %5.2f %5.2f %6.1f | %3d %3d %3d %6.1f %6.1f %6.1f %6.1f %6.1f %s%n",
                r.fixture(), r.status(), r.confidence(), r.boundaryObserved(), r.iou(), r.precision(), r.recall(), r.estimateMs(), r.waypoints(),
                r.invalidWaypoints(), r.unobservedWaypoints(), r.routeMeters(), r.predicted(), r.trueBaseline(), r.trueCoverage(),
                r.oracleCoverage(), r.note());
        }
        System.out.println("\nbObs = share of the estimated boundary backed by observed surfaces; prec = share of the estimate inside the true room;");
        System.out.println("rec = share of the true room inside the estimate; bad = waypoints not standable in the true room; unO = UNOBSERVED waypoints;");
        System.out.println("pred% = coverage the plan predicts on its estimate; true% = the same route measured on the true room; orcl% = the planner");
        System.out.println("given the true room. base% = the recon walk alone, on the true room.\n");
        iterations.forEach(System.out::println);
        System.out.printf(Locale.ROOT, "%nLive HUD tick on a %d-sample trajectory (8x6 room): assess() %.1f ms, full plan() %.1f ms (median of 7).%n",
            longPoses, assessMs, planMs);
    }

    private static void writeJson(Path out, List<Row> rows, List<String> iterations, int longPoses, double assessMs, double planMs) throws IOException {
        StringBuilder sb = new StringBuilder("{\n  \"java\": \"").append(System.getProperty("java.version")).append("\",\n  \"cores\": ")
            .append(Runtime.getRuntime().availableProcessors()).append(",\n  \"rows\": [\n");
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            sb.append(String.format(Locale.ROOT, "    {\"fixture\": \"%s\", \"bounds\": \"%s\", \"confidence\": %.4f, \"boundaryObserved\": %.4f, "
                    + "\"iou\": %s, \"precision\": %s, \"recall\": %s, \"estimateMs\": %.2f, \"waypoints\": %d, \"invalidWaypoints\": %d, "
                    + "\"unobservedWaypoints\": %d, \"routeMeters\": %.2f, \"predictedPercent\": %.2f, \"trueBaselinePercent\": %.2f, "
                    + "\"trueCoveragePercent\": %.2f, \"oracleCoveragePercent\": %.2f}%s%n",
                r.fixture(), r.status(), r.confidence(), r.boundaryObserved(), num(r.iou()), num(r.precision()), num(r.recall()), r.estimateMs(),
                r.waypoints(), r.invalidWaypoints(), r.unobservedWaypoints(), r.routeMeters(), r.predicted(), r.trueBaseline(), r.trueCoverage(),
                r.oracleCoverage(), i + 1 < rows.size() ? "," : ""));
        }
        sb.append("  ],\n  \"iterations\": [");
        for (int i = 0; i < iterations.size(); i++) {
            sb.append(i == 0 ? "\n" : ",\n").append("    \"").append(iterations.get(i).replace("\"", "'")).append("\"");
        }
        sb.append(String.format(Locale.ROOT, "\n  ],\n  \"liveTick\": {\"poses\": %d, \"assessMs\": %.2f, \"planMs\": %.2f}\n}\n", longPoses, assessMs, planMs));
        Files.writeString(out, sb.toString());
    }

    private static String num(double v) {
        return Double.isNaN(v) ? "null" : String.format(Locale.ROOT, "%.4f", v);
    }
}
