package dev.chaya.api.planning;

import dev.chaya.api.planning.CapturePathPlanner.Viewpoint;
import dev.chaya.api.planning.PlanResult.CoverageMetrics;
import dev.chaya.api.planning.geometry.Point;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Compares route quality on the synthetic fixtures, using the SAME coverage metric for every strategy:
 *
 * <ul>
 *   <li><b>recon lap only</b>: the baseline;</li>
 *   <li><b>planner</b>: Chaya's capture-path planner;</li>
 *   <li><b>boundary loop</b>: waypoints spaced evenly around the walls, each facing its nearest wall, same number of waypoints;</li>
 *   <li><b>lawnmower</b>: a serpentine sweep of parallel rows, same number of waypoints;</li>
 *   <li><b>lawnmower (same distance)</b>: the same sweep cut off at the planner's route length.</li>
 * </ul>
 *
 * Run: scripts/benchmark-planner.sh [--csv out.csv]. The output describes the algorithm on hand-drawn synthetic
 * geometry. It says nothing about real captures and must not be quoted as a product performance claim.
 */
public final class PlannerBenchmark {

    private record Fixture(String name, PlanRequest request) {}

    /**
     * @param lapSeconds         duration of the recon lap, from the trajectory's own timestamps
     * @param routeSeconds       walking + dwell time of the secondary route: length / walkSpeed + waypoints * dwell (the planner's
     *                           own formula, applied to every strategy); 0 for the lap alone
     * @param redundantWaypoints waypoints whose marginal covered area, added in route order, is below {@link #REDUNDANT_GAIN_M2};
     *                           -1 where there are no waypoints to judge
     */
    private record Row(String fixture, String strategy, int waypoints, double routeMeters, CoverageMetrics m, double baselineCoverage, Double millis,
                       double lapSeconds, double routeSeconds, int redundantWaypoints) {}

    /** A waypoint adding less covered area than four 0.25 m grid cells is counted as redundant. */
    static final double REDUNDANT_GAIN_M2 = 0.25;

    public static void main(String[] args) throws IOException {
        Path csv = null;
        Path json = null;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--csv") && i + 1 < args.length) {
                csv = Path.of(args[++i]);
            } else if (args[i].equals("--json") && i + 1 < args.length) {
                json = Path.of(args[++i]);
            }
        }
        List<Fixture> fixtures = List.of(
            new Fixture("rectangular room 8x6", PlannerFixtures.rectangularRoom()),
            new Fixture("room + doorway + corridor", PlannerFixtures.roomWithDoorway()),
            new Fixture("room with partition (occlusion)", PlannerFixtures.roomWithOcclusion()),
            new Fixture("three rooms, two doorways", PlannerFixtures.multipleRooms()),
            new Fixture("room + obstacle + sealed neighbour", PlannerFixtures.roomWithSealedNeighbour()),
            new Fixture("office 16x10 + annex, 3 obstacles", office()),
            new Fixture("same office, waypoint cap raised to 40", withCap(office(), 40)));

        CapturePathPlanner planner = new CapturePathPlanner();
        List<Row> rows = new ArrayList<>();
        for (Fixture f : fixtures) {
            PlanRequest req = f.request();
            CoverageMetrics base = planner.baseline(req);
            double lap = lapSeconds(req);
            rows.add(new Row(f.name(), "recon lap only", 0, 0, base, base.coveragePercent(), null, lap, 0, -1));

            PlanResult plan = null;
            for (int i = 0; i < 3; i++) { // warm-up, so JIT compilation is not timed
                plan = planner.plan(req);
            }
            long[] times = new long[7];
            for (int i = 0; i < times.length; i++) {
                long t0 = System.nanoTime();
                plan = planner.plan(req);
                times[i] = System.nanoTime() - t0;
            }
            Arrays.sort(times);
            double ms = times[times.length / 2] / 1e6;
            int n = plan.waypoints().size();
            List<Viewpoint> planned = plan.waypoints().stream().map(w -> new Viewpoint(w.x(), w.y(), Math.toRadians(w.yawDegrees()))).toList();
            rows.add(new Row(f.name(), "planner", n, plan.estimatedDistanceMeters(), plan.planned(), base.coveragePercent(), ms,
                lap, plan.estimatedCaptureSeconds(), redundant(planner, req, planned)));

            Grid g = Grid.build(req.scene(), req.config());
            boolean[] reach = reachable(g, req);
            List<Viewpoint> loopRoute = boundaryLoop(g, reach, req.config(), n);
            var loop = planner.evaluate(req, loopRoute);
            rows.add(new Row(f.name(), "boundary loop (" + n + " wp)", loop.reachableWaypoints(), loop.routeLengthMeters(), loop.metrics(), base.coveragePercent(), null,
                lap, routeSeconds(req, loop), redundant(planner, req, loopRoute)));
            List<Viewpoint> mow = lawnmower(g, reach, req.config());
            List<Viewpoint> mowN = mow.subList(0, Math.min(n, mow.size()));
            var sweep = planner.evaluate(req, mowN);
            rows.add(new Row(f.name(), "lawnmower (" + n + " wp)", sweep.reachableWaypoints(), sweep.routeLengthMeters(), sweep.metrics(), base.coveragePercent(), null,
                lap, routeSeconds(req, sweep), redundant(planner, req, mowN)));
            CapturePathPlanner.Evaluation matched = null;
            int matchedK = 0;
            for (int k = 1; k <= mow.size(); k++) {
                var e = planner.evaluate(req, mow.subList(0, k));
                if (e.routeLengthMeters() > plan.estimatedDistanceMeters() && matched != null) {
                    break;
                }
                matched = e;
                matchedK = k;
            }
            if (matched != null) {
                rows.add(new Row(f.name(), "lawnmower (same distance)", matched.reachableWaypoints(), matched.routeLengthMeters(), matched.metrics(), base.coveragePercent(), null,
                    lap, routeSeconds(req, matched), redundant(planner, req, mow.subList(0, matchedK))));
            }
        }
        print(rows);
        if (csv != null) {
            writeCsv(csv, rows);
            System.out.println("\nCSV written to " + csv);
        }
        if (json != null) {
            writeJson(json, rows);
            System.out.println("JSON written to " + json);
        }
    }

    private static double lapSeconds(PlanRequest req) {
        var t = req.trajectory();
        return t.isEmpty() ? 0 : t.get(t.size() - 1).t() - t.get(0).t();
    }

    private static double routeSeconds(PlanRequest req, CapturePathPlanner.Evaluation e) {
        return e.routeLengthMeters() / req.config().walkSpeedMetersPerSecond() + e.reachableWaypoints() * req.config().dwellSeconds();
    }

    /** Replays the route one waypoint at a time with the same coverage metric and counts waypoints that add almost nothing. */
    static int redundant(CapturePathPlanner planner, PlanRequest req, List<Viewpoint> route) {
        double covered = planner.evaluate(req, List.of()).metrics().coveredAreaM2();
        int reachable = 0, redundant = 0;
        for (int k = 1; k <= route.size(); k++) {
            var e = planner.evaluate(req, route.subList(0, k));
            if (e.reachableWaypoints() > reachable) {
                if (e.metrics().coveredAreaM2() - covered < REDUNDANT_GAIN_M2) {
                    redundant++;
                }
                reachable = e.reachableWaypoints();
            }
            covered = e.metrics().coveredAreaM2();
        }
        return redundant;
    }

    // ---- comparison strategies (deterministic, no randomness) ------------------------------------------------------

    private static boolean[] reachable(Grid g, PlanRequest req) {
        List<Integer> seeds = new ArrayList<>();
        for (var p : TrajectoryNormalizer.normalize(req.trajectory(), req.config()).poses()) {
            seeds.add(g.snapToStandable(p.x(), p.y(), 3));
        }
        return g.reachableFrom(seeds);
    }

    /** N waypoints evenly spread (by angle) along a ring one target distance from the walls, each facing its nearest wall. */
    static List<Viewpoint> boundaryLoop(Grid g, boolean[] reach, PlannerConfig cfg, int n) {
        List<Integer> ring = new ArrayList<>();
        double cx = 0, cy = 0;
        // The ring is the set of floor cells about one target distance from the nearest wall or obstacle; where a scene has
        // none, widen the band step by step (down to 0.3 D and up to 1.7 D) until there are enough cells to place n waypoints.
        for (double band = g.res; band <= 1.4 * cfg.targetDistance() && ring.size() < Math.max(n, 3); band += g.res) {
            ring.clear();
            cx = 0;
            cy = 0;
            for (int c = 0; c < g.n; c++) {
                if (g.standable[c] && reach[c] && Math.abs(g.clearance[c] - cfg.targetDistance()) <= band) {
                    ring.add(c);
                    cx += g.centerX(c);
                    cy += g.centerY(c);
                }
            }
        }
        if (ring.isEmpty() || n == 0) {
            return List.of();
        }
        final double mx = cx / ring.size(), my = cy / ring.size();
        ring.sort(Comparator.comparingDouble((Integer c) -> Math.atan2(g.centerY(c) - my, g.centerX(c) - mx)).thenComparingInt(c -> c));
        List<Viewpoint> out = new ArrayList<>();
        for (int k = 0; k < n; k++) {
            int c = ring.get((int) ((long) k * ring.size() / n));
            out.add(new Viewpoint(g.centerX(c), g.centerY(c), towardNearestWall(g, c, cfg)));
        }
        return out;
    }

    private static double towardNearestWall(Grid g, int cell, PlannerConfig cfg) {
        double best = Double.MAX_VALUE, yaw = 0;
        for (int k = 0; k < 16; k++) {
            double a = 2 * Math.PI * k / 16;
            for (double d = g.res; d <= 4 * cfg.targetDistance(); d += g.res / 2) {
                int c = g.cellOf(g.centerX(cell) + d * Math.cos(a), g.centerY(cell) + d * Math.sin(a));
                if (c < 0 || g.type[c] != Grid.FREE) {
                    if (d < best - 1e-9) {
                        best = d;
                        yaw = a;
                    }
                    break;
                }
            }
        }
        return yaw;
    }

    /** Parallel rows one target distance apart, walked back and forth, a waypoint every 1.5 m, facing the direction of travel. */
    static List<Viewpoint> lawnmower(Grid g, boolean[] reach, PlannerConfig cfg) {
        double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (int c = 0; c < g.n; c++) {
            if (g.standable[c] && reach[c]) {
                minY = Math.min(minY, g.centerY(c));
                maxY = Math.max(maxY, g.centerY(c));
            }
        }
        List<Viewpoint> out = new ArrayList<>();
        boolean eastward = true;
        for (double y = minY + cfg.targetDistance() / 2; y <= maxY + 1e-9; y += cfg.targetDistance()) {
            List<Integer> row = new ArrayList<>();
            for (int c = 0; c < g.n; c++) {
                if (g.standable[c] && reach[c] && Math.abs(g.centerY(c) - y) <= g.res / 2) {
                    row.add(c);
                }
            }
            row.sort(Comparator.comparingDouble((Integer c) -> g.centerX(c)).thenComparingInt(c -> c));
            if (!eastward) {
                java.util.Collections.reverse(row);
            }
            double lastX = Double.NaN;
            for (int c : row) {
                double x = g.centerX(c);
                if (Double.isNaN(lastX) || Math.abs(x - lastX) >= 1.5) {
                    out.add(new Viewpoint(x, g.centerY(c), eastward ? 0 : Math.PI));
                    lastX = x;
                }
            }
            eastward = !eastward;
        }
        return out;
    }

    private static PlanRequest withCap(PlanRequest r, int maxWaypoints) {
        return new PlanRequest(r.scene(), r.trajectory(), r.observedRegions(), r.candidates(), r.config().toBuilder().maxWaypoints(maxWaypoints).build());
    }

    // ---- a larger synthetic scene, benchmark only -----------------------------------------------------------------

    private static PlanRequest office() {
        Scene scene = new Scene(
            List.of(PlannerFixtures.rect(0, 0, 16, 10), PlannerFixtures.rect(16, 3, 22, 7)),
            List.of(PlannerFixtures.wall(16, 0, 16, 4.5), PlannerFixtures.wall(16, 5.5, 16, 10)),
            List.of(PlannerFixtures.rect(5, 3, 7, 5), PlannerFixtures.rect(10, 6, 12, 8), PlannerFixtures.rect(3, 7, 4, 8)),
            List.of(PlannerFixtures.rect(13, 1, 15, 2.5)),
            List.of(new Scene.Doorway(16, 5.0, 1.0, 0.0)));
        return PlannerFixtures.request(scene, PlannerFixtures.lap(1.0, 2, 2, 9, 2, 9, 5, 8.5, 5.5, 8.5, 9));
    }

    // ---- output ---------------------------------------------------------------------------------------------------

    private static void print(List<Row> rows) {
        System.out.println("SYNTHETIC FIXTURES ONLY: hand-drawn geometry to compare route strategies with one coverage metric.");
        System.out.println("These numbers say nothing about real captures. Java " + System.getProperty("java.version") + ", "
            + Runtime.getRuntime().availableProcessors() + " cores, planner time = median of 7 runs after 3 warm-ups.\n");
        System.out.println("| fixture | strategy | waypoints | route (m) | coverage % | +pts vs lap | uncovered m2 | redundant % | planner ms |");
        System.out.println("|---|---|--:|--:|--:|--:|--:|--:|--:|");
        for (Row r : rows) {
            System.out.println(String.format(Locale.ROOT, "| %s | %s | %d | %.1f | %.1f | %+.1f | %.1f | %.1f | %s |", r.fixture(), r.strategy(),
                r.waypoints(), r.routeMeters(), r.m().coveragePercent(), r.m().coveragePercent() - r.baselineCoverage(),
                r.m().uncoveredAreaM2(), r.m().redundantCapturePercent(), r.millis() == null ? "" : String.format(Locale.ROOT, "%.0f", r.millis())));
        }
    }

    private static void writeJson(Path path, List<Row> rows) throws IOException {
        List<java.util.Map<String, Object>> out = new ArrayList<>();
        for (Row r : rows) {
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("fixture", r.fixture());
            m.put("strategy", r.strategy());
            m.put("waypoints", r.waypoints());
            m.put("route_m", r.routeMeters());
            m.put("coverage_pct", r.m().coveragePercent());
            m.put("weighted_coverage_pct", r.m().weightedCoveragePercent());
            m.put("covered_m2", r.m().coveredAreaM2());
            m.put("uncovered_m2", r.m().uncoveredAreaM2());
            m.put("target_m2", r.m().targetAreaM2());
            m.put("redundant_capture_pct", r.m().redundantCapturePercent());
            m.put("redundant_waypoints", r.redundantWaypoints());
            m.put("lap_s", r.lapSeconds());
            m.put("route_s", r.routeSeconds());
            m.put("total_capture_s", r.lapSeconds() + r.routeSeconds());
            m.put("planner_ms", r.millis());
            out.add(m);
        }
        PlannerConfig d = PlannerConfig.defaults(); // every fixture uses the defaults, except the one waypoint cap
        java.util.Map<String, Object> doc = new java.util.LinkedHashMap<>();
        doc.put("config", java.util.Map.of("walk_speed_mps", d.walkSpeedMetersPerSecond(), "dwell_s", d.dwellSeconds(),
            "target_distance_m", d.targetDistance(), "redundant_gain_m2", REDUNDANT_GAIN_M2));
        doc.put("java", System.getProperty("java.version"));
        doc.put("cores", Runtime.getRuntime().availableProcessors());
        doc.put("rows", out);
        Files.writeString(path, new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(doc));
    }

    private static void writeCsv(Path path, List<Row> rows) throws IOException {
        StringBuilder sb = new StringBuilder("fixture,strategy,waypoints,route_m,coverage_pct,uncovered_m2,redundant_pct,planner_ms\n");
        for (Row r : rows) {
            sb.append(String.format(Locale.ROOT, "\"%s\",\"%s\",%d,%.3f,%.3f,%.3f,%.3f,%s%n", r.fixture(), r.strategy(), r.waypoints(), r.routeMeters(),
                r.m().coveragePercent(), r.m().uncoveredAreaM2(), r.m().redundantCapturePercent(), r.millis() == null ? "" : String.format(Locale.ROOT, "%.1f", r.millis())));
        }
        Files.writeString(path, sb.toString());
    }
}
