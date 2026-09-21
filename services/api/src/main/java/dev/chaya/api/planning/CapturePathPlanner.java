package dev.chaya.api.planning;

import dev.chaya.api.planning.CandidateGenerator.Candidate;
import dev.chaya.api.planning.CoverageState.Gain;
import dev.chaya.api.planning.PlanResult.Confidence;
import dev.chaya.api.planning.PlanResult.CoverageMetrics;
import dev.chaya.api.planning.PlanResult.Diagnostics;
import dev.chaya.api.planning.PlanResult.Improvement;
import dev.chaya.api.planning.PlanResult.PathPoint;
import dev.chaya.api.planning.PlanResult.UncoveredRegion;
import dev.chaya.api.planning.PlanResult.Waypoint;
import dev.chaya.api.planning.Scene.Doorway;
import dev.chaya.api.planning.TrajectoryNormalizer.Pose;
import dev.chaya.api.planning.geometry.Geometry;
import dev.chaya.api.planning.geometry.Point;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * Chaya 02 capture-path planner. Deterministic: no randomness, no external services, no learned components.
 * The algorithm and its mathematics are written up in docs/route-planning.md.
 *
 * <pre>
 *  1 normalise the recon-lap trajectory          TrajectoryNormalizer
 *  2 estimate the walkable region                Grid
 *  3 identify candidate viewpoints               CandidateGenerator
 *  4 estimate what each viewpoint observes       Visibility
 *  5 score viewpoints by marginal coverage gain  CoverageState.gain
 *  6 penalise redundant viewpoints               redundancy factor, minimum separation
 *  7 prioritise high-information viewpoints      cell importance weights (corners, doorways, boundaries)
 *  8 order the route                             sequential greedy + 2-opt on walking distance
 *  9 measure the result                          replay of lap + route through CoverageState
 * </pre>
 */
public final class CapturePathPlanner {

    /** A pose to evaluate: where to stand and which way to face. */
    public record Viewpoint(double x, double y, double yawRadians) {}

    /** Result of evaluating an arbitrary route with the same coverage metric the planner uses. */
    public record Evaluation(CoverageMetrics metrics, double routeLengthMeters, int reachableWaypoints, int skippedWaypoints) {}

    private static final class Context {
        PlannerConfig cfg;
        Grid grid;
        Visibility vis;
        TrajectoryNormalizer.Result trajectory;
        boolean[] reachable;
        CoverageState baseline;
        BitSet shadow = new BitSet();
        int startCell;
        Point startPoint;
        List<String> warnings = new ArrayList<>();
    }

    private static final class Chosen {
        final Candidate cand;
        final double yaw;
        final Visibility.Obs obs;
        final Gain gain;

        Chosen(Candidate cand, double yaw, Visibility.Obs obs, Gain gain) {
            this.cand = cand;
            this.yaw = yaw;
            this.obs = obs;
            this.gain = gain;
        }
    }

    public PlanResult plan(PlanRequest request) {
        Context ctx = prepare(request);
        PlannerConfig cfg = ctx.cfg;
        Grid g = ctx.grid;

        CandidateGenerator.Output gen = new CandidateGenerator(g, cfg, ctx.reachable, ctx.vis).generate(request.candidates());
        List<Candidate> cands = gen.candidates();
        Visibility.Obs[] omni = new Visibility.Obs[cands.size()];
        Visibility.Obs[][] facing = new Visibility.Obs[cands.size()][cfg.headingCount()];
        boolean[] observable = new boolean[g.n];
        for (int k = 0; k < cands.size(); k++) {
            omni[k] = ctx.vis.around(cands.get(k).x(), cands.get(k).y(), null);
            for (int c : omni[k].cell) {
                observable[c] = true;
            }
        }

        // ---- steps 5-7: sequential greedy selection on marginal weighted gain --------------------------------
        CoverageState work = ctx.baseline.copy();
        List<Chosen> chosen = new ArrayList<>();
        boolean[] used = new boolean[cands.size()];
        int current = ctx.startCell;
        Point currentPoint = ctx.startPoint;
        PathFinder.Field field = PathFinder.from(g, current);
        double beta = cfg.travelWeight() * cfg.targetDistance();
        String stop = cands.isEmpty() ? "NO_CANDIDATES" : "NO_CANDIDATE_WITH_GAIN";
        int evaluated = 0;
        while (true) {
            CoverageState.Raw now = work.raw();
            if (now.targetAreaM2() > 0 && now.coveredAreaM2() / now.targetAreaM2() >= cfg.stopCoverage()) {
                stop = "TARGET_COVERAGE_REACHED";
                break;
            }
            if (chosen.size() >= cfg.maxWaypoints()) {
                stop = "MAX_WAYPOINTS";
                break;
            }
            int bestK = -1, bestH = -1;
            double bestScore = 0, bestGain = 0, bestDist = 0;
            Gain bestG = null;
            for (int k = 0; k < cands.size(); k++) {
                Candidate cand = cands.get(k);
                double dist = field.dist()[cand.cell()];
                if (used[k] || dist == PathFinder.UNREACHABLE || tooClose(cand, chosen, cfg.minSeparation())) {
                    continue;
                }
                for (int h = 0; h < cfg.headingCount(); h++) {
                    if (facing[k][h] == null) {
                        facing[k][h] = ctx.vis.facing(omni[k], heading(h, cfg.headingCount()));
                    }
                    Gain gain = work.gain(facing[k][h], ctx.shadow);
                    evaluated++;
                    double score = gain.weighted() * (1 - cfg.redundancyPenalty() * gain.redundancy()) / Math.max(1e-6, beta + dist);
                    if (gain.weighted() > 0 && better(score, gain.weighted(), dist, bestScore, bestGain, bestDist, bestK)) {
                        bestScore = score;
                        bestGain = gain.weighted();
                        bestDist = dist;
                        bestK = k;
                        bestH = h;
                        bestG = gain;
                    }
                }
            }
            if (bestK < 0 || bestGain < cfg.minMarginalGain()) {
                break;
            }
            Chosen ch = new Chosen(cands.get(bestK), heading(bestH, cfg.headingCount()), facing[bestK][bestH], bestG);
            chosen.add(ch);
            used[bestK] = true;
            // The selection state follows the route as it would be walked: the leg to the waypoint is captured too.
            Point to = new Point(ch.cand.x(), ch.cand.y());
            addLegPoses(ctx, work, PathFinder.path(g, field, current, ch.cand.cell(), currentPoint, to));
            work.add(ch.obs);
            current = ch.cand.cell();
            currentPoint = to;
            field = PathFinder.from(g, current);
        }

        CoverageState.Raw built = work.raw();
        double constructionCoverage = built.targetAreaM2() == 0 ? 0 : 100 * built.coveredAreaM2() / built.targetAreaM2();
        double constructionRoute = 0;
        int chainCell = ctx.startCell;
        Point chainPoint = ctx.startPoint;
        for (Chosen ch : chosen) {
            Point to = new Point(ch.cand.x(), ch.cand.y());
            constructionRoute += PathFinder.length(PathFinder.path(g, PathFinder.from(g, chainCell), chainCell, ch.cand.cell(), chainPoint, to));
            chainCell = ch.cand.cell();
            chainPoint = to;
        }

        // ---- step 8: order the route ---------------------------------------------------------------------------
        List<Chosen> ordered = twoOpt(g, ctx.startCell, chosen);

        // ---- step 9: replay lap + route in order and measure ----------------------------------------------------
        CoverageState.Raw before = ctx.baseline.raw();
        CoverageState state = ctx.baseline.copy();
        List<Waypoint> waypoints = new ArrayList<>();
        List<PathPoint> routePoints = new ArrayList<>();
        routePoints.add(new PathPoint(ctx.startPoint.x(), ctx.startPoint.y()));
        Point prev = ctx.startPoint;
        int prevCell = ctx.startCell;
        double cumulative = 0;
        int legPoses = 0;
        CoverageState.Raw last = before;
        for (int idx = 0; idx < ordered.size(); idx++) {
            Chosen ch = ordered.get(idx);
            PathFinder.Field from = PathFinder.from(g, prevCell);
            Point to = new Point(ch.cand.x(), ch.cand.y());
            List<Point> leg = PathFinder.path(g, from, prevCell, ch.cand.cell(), prev, to);
            legPoses += addLegPoses(ctx, state, leg);
            for (int p = 1; p < leg.size(); p++) {
                routePoints.add(new PathPoint(leg.get(p).x(), leg.get(p).y()));
            }
            state.add(ch.obs);
            double legLen = PathFinder.length(leg);
            cumulative += legLen;
            CoverageState.Raw after = state.raw();
            double gainArea = after.coveredAreaM2() - last.coveredAreaM2();
            String[] typeAndReason = classify(ctx, ch, gainArea);
            waypoints.add(new Waypoint(idx + 1, round(ch.cand.x()), round(ch.cand.y()), round(Math.toDegrees(ch.yaw)),
                typeAndReason[0], typeAndReason[1], round(gainArea),
                round(after.targetAreaM2() == 0 ? 0 : 100 * gainArea / after.targetAreaM2()), round(legLen), round(cumulative),
                round(cumulative / cfg.walkSpeedMetersPerSecond() + (idx + 1) * cfg.dwellSeconds())));
            last = after;
            prev = to;
            prevCell = ch.cand.cell();
        }
        CoverageState.Raw finalRaw = state.raw();
        int lapPoses = ctx.trajectory.poses().size();
        CoverageMetrics baseline = metrics(before, lapPoses, ctx.trajectory.lengthMeters(), 0);
        CoverageMetrics planned = metrics(finalRaw, lapPoses + legPoses + ordered.size(), ctx.trajectory.lengthMeters() + cumulative, ordered.size());
        double captureSeconds = cumulative / cfg.walkSpeedMetersPerSecond() + ordered.size() * cfg.dwellSeconds();

        List<UncoveredRegion> uncovered = uncoveredRegions(g, state, observable, ctx);
        Confidence confidence = confidence(ctx, planned);
        Diagnostics diagnostics = new Diagnostics(g.nx, g.ny, g.res, round(g.standableArea()), round(reachableArea(ctx)),
            gen.generated(), evaluated, stop, round(constructionCoverage), round(constructionRoute), ctx.trajectory.inputSamples(), lapPoses, round(ctx.trajectory.lengthMeters()),
            ctx.trajectory.yawSource(), List.copyOf(ctx.warnings));
        return new PlanResult(List.copyOf(waypoints), List.copyOf(routePoints), round(cumulative), round(captureSeconds), confidence,
            baseline, planned,
            new Improvement(round(planned.coveragePercent() - baseline.coveragePercent()), round(planned.coveredAreaM2() - baseline.coveredAreaM2()),
                round(baseline.uncoveredAreaM2() - planned.uncoveredAreaM2()),
                round(planned.redundantCapturePercent() - baseline.redundantCapturePercent()), round(cumulative), ordered.size()),
            uncovered, gen.rejected(), diagnostics);
    }

    /**
     * Measures an arbitrary ordered route (lap + the given viewpoints, walked in order, shortest walking path between them)
     * with exactly the metric the planner uses. Used to compare planner output against other strategies.
     */
    public Evaluation evaluate(PlanRequest request, List<Viewpoint> route) {
        Context ctx = prepare(request);
        CoverageState state = ctx.baseline.copy();
        Point prev = ctx.startPoint;
        int prevCell = ctx.startCell;
        double length = 0;
        int legPoses = 0, ok = 0, skipped = 0;
        for (Viewpoint v : route) {
            int cell = ctx.grid.snapToStandable(v.x(), v.y(), 2);
            PathFinder.Field from = PathFinder.from(ctx.grid, prevCell);
            if (cell < 0 || from.dist()[cell] == PathFinder.UNREACHABLE) {
                skipped++;
                continue;
            }
            Point to = new Point(ctx.grid.centerX(cell), ctx.grid.centerY(cell));
            List<Point> leg = PathFinder.path(ctx.grid, from, prevCell, cell, prev, to);
            legPoses += addLegPoses(ctx, state, leg);
            length += PathFinder.length(leg);
            state.add(ctx.vis.facing(ctx.vis.around(to.x(), to.y(), null), v.yawRadians()));
            prev = to;
            prevCell = cell;
            ok++;
        }
        CoverageMetrics m = metrics(state.raw(), ctx.trajectory.poses().size() + legPoses + ok, ctx.trajectory.lengthMeters() + length, ok);
        return new Evaluation(m, length, ok, skipped);
    }

    /** The recon lap alone, measured with the same metric (the baseline). */
    public CoverageMetrics baseline(PlanRequest request) {
        Context ctx = prepare(request);
        return metrics(ctx.baseline.raw(), ctx.trajectory.poses().size(), ctx.trajectory.lengthMeters(), 0);
    }

    // ---------------------------------------------------------------------------------------------------------------

    private Context prepare(PlanRequest request) {
        Context ctx = new Context();
        ctx.cfg = request.config();
        ctx.grid = Grid.build(request.scene(), ctx.cfg);
        ctx.warnings.addAll(ctx.grid.warnings);
        ctx.vis = new Visibility(ctx.grid, ctx.cfg);
        ctx.trajectory = TrajectoryNormalizer.normalize(request.trajectory(), ctx.cfg);
        if (ctx.trajectory.poses().isEmpty()) {
            throw new PlanningException("NO_USABLE_TRAJECTORY", "the reconnaissance trajectory contains no usable samples ("
                + ctx.trajectory.droppedNonFinite() + " non-finite, " + ctx.trajectory.droppedOutOfOrder() + " out of order)");
        }
        List<Integer> seeds = new ArrayList<>();
        for (Pose p : ctx.trajectory.poses()) {
            int s = ctx.grid.snapToStandable(p.x(), p.y(), 3);
            if (s >= 0) {
                seeds.add(s);
            }
        }
        if (seeds.isEmpty()) {
            throw new PlanningException("TRAJECTORY_OUTSIDE_SCENE", "no trajectory sample lies on (or within 0.75 m of) walkable floor of the given scene");
        }
        ctx.reachable = ctx.grid.reachableFrom(seeds);
        ctx.baseline = new CoverageState(ctx.grid, ctx.cfg);
        for (ObservedRegion r : request.observedRegions()) {
            if (!(r.quality() >= 0 && r.quality() <= 1) || !r.polygon().isValid()) {
                throw new PlanningException("INVALID_OBSERVED_REGION", "observedRegions need a valid polygon and a quality between 0 and 1");
            }
            for (int c = 0; c < ctx.grid.n; c++) {
                if (ctx.grid.target[c] && r.polygon().contains(ctx.grid.centerX(c), ctx.grid.centerY(c))) {
                    ctx.baseline.credit(c, r.quality());
                }
            }
        }
        int offFloor = 0;
        for (Pose p : ctx.trajectory.poses()) {
            int c = ctx.grid.cellOf(p.x(), p.y());
            if (c < 0 || ctx.grid.type[c] != Grid.FREE) {
                offFloor++;
            }
            ctx.baseline.add(ctx.vis.facing(ctx.vis.around(p.x(), p.y(), ctx.shadow), p.yaw()));
        }
        if (offFloor > 0) {
            ctx.warnings.add(offFloor + " of " + ctx.trajectory.poses().size() + " trajectory poses are not on free floor of the given scene");
        }
        if (ctx.trajectory.droppedSpeedSpikes() + ctx.trajectory.droppedNonFinite() + ctx.trajectory.droppedOutOfOrder() > 0) {
            ctx.warnings.add("trajectory samples dropped: " + ctx.trajectory.droppedNonFinite() + " non-finite, "
                + ctx.trajectory.droppedOutOfOrder() + " out of order, " + ctx.trajectory.droppedSpeedSpikes() + " tracking glitches");
        }
        Pose end = ctx.trajectory.poses().get(ctx.trajectory.poses().size() - 1);
        ctx.startCell = ctx.grid.snapToStandable(end.x(), end.y(), 3);
        if (ctx.startCell < 0) {
            ctx.startCell = seeds.get(seeds.size() - 1);
        }
        ctx.startPoint = ctx.startCell == ctx.grid.cellOf(end.x(), end.y()) ? new Point(end.x(), end.y())
            : new Point(ctx.grid.centerX(ctx.startCell), ctx.grid.centerY(ctx.startCell));
        return ctx;
    }

    private static double heading(int h, int count) {
        return -Math.PI + 2 * Math.PI * h / count;
    }

    private static boolean tooClose(Candidate c, List<Chosen> chosen, double minSeparation) {
        for (Chosen ch : chosen) {
            if (Math.hypot(ch.cand.x() - c.x(), ch.cand.y() - c.y()) < Math.max(minSeparation, 1e-9)) {
                return true;
            }
        }
        return false;
    }

    /** Deterministic ordering: higher score, then higher raw gain, then shorter walk; the caller's loop order breaks remaining ties. */
    private static boolean better(double score, double gain, double dist, double bestScore, double bestGain, double bestDist, int bestK) {
        if (bestK < 0) {
            return true;
        }
        if (Math.abs(score - bestScore) > 1e-12) {
            return score > bestScore;
        }
        if (Math.abs(gain - bestGain) > 1e-12) {
            return gain > bestGain;
        }
        return dist < bestDist - 1e-12;
    }

    /** Reorders the chosen waypoints (start fixed) by 2-opt on geodesic walking distance until no reversal shortens the route. */
    private List<Chosen> twoOpt(Grid g, int startCell, List<Chosen> chosen) {
        int m = chosen.size();
        if (m < 2) {
            return chosen;
        }
        int[] cells = new int[m + 1];
        cells[0] = startCell;
        for (int i = 0; i < m; i++) {
            cells[i + 1] = chosen.get(i).cand.cell();
        }
        double[][] d = new double[m + 1][m + 1];
        for (int i = 0; i <= m; i++) {
            PathFinder.Field f = PathFinder.from(g, cells[i]);
            for (int j = 0; j <= m; j++) {
                d[i][j] = f.dist()[cells[j]];
            }
        }
        int[] order = new int[m];
        for (int i = 0; i < m; i++) {
            order[i] = i + 1;
        }
        boolean improved = true;
        int guard = 0;
        while (improved && guard++ < 1000) {
            improved = false;
            for (int i = 0; i < m - 1 && !improved; i++) {
                for (int j = i + 1; j < m && !improved; j++) {
                    int before = i == 0 ? 0 : order[i - 1];
                    double oldCost = d[before][order[i]] + (j < m - 1 ? d[order[j]][order[j + 1]] : 0);
                    double newCost = d[before][order[j]] + (j < m - 1 ? d[order[i]][order[j + 1]] : 0);
                    if (newCost < oldCost - 1e-9) {
                        for (int a = i, b = j; a < b; a++, b--) {
                            int t = order[a];
                            order[a] = order[b];
                            order[b] = t;
                        }
                        improved = true;
                    }
                }
            }
        }
        List<Chosen> out = new ArrayList<>();
        for (int idx : order) {
            out.add(chosen.get(idx - 1));
        }
        return out;
    }

    private int addLegPoses(Context ctx, CoverageState state, List<Point> leg) {
        int added = 0;
        double spacing = ctx.cfg.poseSpacing();
        double carry = 0;
        for (int i = 0; i + 1 < leg.size(); i++) {
            Point a = leg.get(i), b = leg.get(i + 1);
            double seg = a.distanceTo(b);
            if (seg == 0) {
                continue;
            }
            double heading = Math.atan2(b.y() - a.y(), b.x() - a.x());
            double pos = spacing - carry;
            while (pos < seg - 1e-9) {
                double f = pos / seg;
                state.add(ctx.vis.facing(ctx.vis.around(a.x() + (b.x() - a.x()) * f, a.y() + (b.y() - a.y()) * f, null), heading));
                added++;
                pos += spacing;
            }
            carry = seg - (pos - spacing);
        }
        return added;
    }

    private static CoverageMetrics metrics(CoverageState.Raw r, int poses, double pathLength, int waypoints) {
        double target = r.targetAreaM2();
        return new CoverageMetrics(round(target == 0 ? 0 : 100 * r.coveredAreaM2() / target), round(100 * r.weightedFraction()),
            round(r.coveredAreaM2()), round(target - r.coveredAreaM2()), round(target), round(100 * r.redundantFraction()), poses,
            round(pathLength), waypoints);
    }

    /** What the waypoint measurably adds decides its type (see docs/route-planning.md, "Waypoint type"). */
    private String[] classify(Context ctx, Chosen ch, double gainArea) {
        Gain gn = ch.gain;
        double total = Math.max(gn.weighted(), 1e-12);
        double doorwayShare = gn.doorway() / total, cornerShare = gn.corner() / total, shadowShare = gn.shadow() / total,
            boundaryShare = gn.boundary() / total;
        Doorway nearDoor = null;
        for (Doorway d : ctx.grid.doorways) {
            if (Math.hypot(d.x() - ch.cand.x(), d.y() - ch.cand.y()) <= d.width() / 2 + 1.0) {
                nearDoor = d;
                break;
            }
        }
        String type;
        String why;
        if (nearDoor != null || doorwayShare >= 0.25) {
            type = "DOORWAY";
            why = nearDoor != null
                ? "Doorway (" + fmt(nearDoor.width()) + " m wide) at (" + fmt(nearDoor.x()) + ", " + fmt(nearDoor.y()) + "): opens the view through it"
                : "Covers the area around a doorway";
        } else if (cornerShare >= 0.25) {
            type = "CORNER";
            why = "Corner coverage: " + pct(cornerShare) + " of the gain is at corners";
        } else if (shadowShare >= 0.30) {
            type = "OCCLUSION";
            why = "Sees " + pct(shadowShare) + " of its gain in areas the recon lap could not see past obstacles or partitions";
        } else if (boundaryShare >= 0.5) {
            type = "BOUNDARY";
            why = "Covers wall and boundary surfaces (" + pct(boundaryShare) + " of the gain)";
        } else {
            type = "COVERAGE";
            why = "Fills an uncovered area of floor";
        }
        return new String[] {type, why + "; adds " + fmt(gainArea) + " m2 of fully covered area (" + pct(gn.newlyObservedShare()) + " of the cells it helps were not seen by anything before)."};
    }

    private List<UncoveredRegion> uncoveredRegions(Grid g, CoverageState state, boolean[] observable, Context ctx) {
        boolean[] seen = new boolean[g.n];
        List<UncoveredRegion> out = new ArrayList<>();
        int[] queue = new int[g.n];
        for (int start = 0; start < g.n; start++) {
            if (!g.target[start] || state.covered(start) || seen[start]) {
                continue;
            }
            int head = 0, tail = 0;
            queue[tail++] = start;
            seen[start] = true;
            double sx = 0, sy = 0, minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
            int cells = 0, reachableCells = 0;
            while (head < tail) {
                int c = queue[head++];
                cells++;
                double x = g.centerX(c), y = g.centerY(c);
                sx += x;
                sy += y;
                minX = Math.min(minX, x - g.res / 2);
                minY = Math.min(minY, y - g.res / 2);
                maxX = Math.max(maxX, x + g.res / 2);
                maxY = Math.max(maxY, y + g.res / 2);
                if (observable[c] || state.observationMass(c) > 0) {
                    reachableCells++;
                }
                int ci = c % g.nx, cj = c / g.nx;
                for (int k = 0; k < 8; k++) {
                    int ni = ci + Grid.DI[k], nj = cj + Grid.DJ[k];
                    if (ni < 0 || nj < 0 || ni >= g.nx || nj >= g.ny) {
                        continue;
                    }
                    int nc = nj * g.nx + ni;
                    if (g.target[nc] && !state.covered(nc) && !seen[nc]) {
                        seen[nc] = true;
                        queue[tail++] = nc;
                    }
                }
            }
            if (cells * g.cellArea >= 2 * g.cellArea) {
                boolean anyObservable = reachableCells * 2 >= cells;
                out.add(new UncoveredRegion(round(cells * g.cellArea), round(sx / cells), round(sy / cells), round(minX), round(minY),
                    round(maxX), round(maxY), anyObservable ? "BELOW_COVERAGE_TARGET" : "NOT_OBSERVABLE_FROM_EVALUATED_VIEWPOINTS"));
            }
        }
        out.sort((a, b) -> a.areaM2() != b.areaM2() ? Double.compare(b.areaM2(), a.areaM2())
            : a.centroidY() != b.centroidY() ? Double.compare(a.centroidY(), b.centroidY()) : Double.compare(a.centroidX(), b.centroidX()));
        return List.copyOf(out);
    }

    private double reachableArea(Context ctx) {
        int c = 0;
        for (int i = 0; i < ctx.grid.n; i++) {
            if (ctx.reachable[i]) {
                c++;
            }
        }
        return c * ctx.grid.cellArea;
    }

    private Confidence confidence(Context ctx, CoverageMetrics planned) {
        int onFloor = 0;
        for (Pose p : ctx.trajectory.poses()) {
            int c = ctx.grid.cellOf(p.x(), p.y());
            if (c >= 0 && ctx.grid.type[c] == Grid.FREE) {
                onFloor++;
            }
        }
        double evidence = (double) onFloor / ctx.trajectory.poses().size()
            * Math.min(1, ctx.trajectory.lengthMeters() / (2 * ctx.cfg.targetDistance()));
        double standable = ctx.grid.standableArea();
        double reachableFraction = standable == 0 ? 0 : reachableArea(ctx) / standable;
        double covered = planned.coveragePercent() / 100;
        double score = evidence * (0.5 + 0.5 * reachableFraction) * (0.5 + 0.5 * covered);
        return new Confidence(round(score), round(evidence), round(reachableFraction), round(covered),
            "Heuristic 0-1 score = trajectory evidence x (0.5 + 0.5 x reachable share of the floor) x (0.5 + 0.5 x planned coverage). Not a calibrated probability.");
    }

    private static double round(double v) {
        return Math.round(v * 10_000.0) / 10_000.0;
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.1f", v);
    }

    private static String pct(double fraction) {
        return String.format(java.util.Locale.ROOT, "%.0f%%", 100 * fraction);
    }
}
