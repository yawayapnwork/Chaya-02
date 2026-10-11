package dev.chaya.api.planning;

import dev.chaya.api.planning.geometry.Geometry;
import dev.chaya.api.planning.geometry.Point;
import dev.chaya.api.planning.geometry.Polygon;
import dev.chaya.api.planning.geometry.Segment;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Estimates a room's observable bounds, in plan view, from the spatial evidence of a short reconnaissance walk.
 * Deterministic; no randomness and no learned components. docs/route-planning.md section 12 has the derivation.
 *
 * <p>Evidence it accepts, and nothing else:
 * <ul>
 *   <li>the walked trajectory (tracked positions): the floor within {@code walkedRadius} of it is free;</li>
 *   <li>surface observations: a ray from the camera to a point on a real surface the device's tracker reported (for
 *       WebXR, a hit test against a detected plane). A WALL hit clears the cells along the ray and marks the hit cell
 *       occupied; a FLOOR hit marks its own cell free;</li>
 *   <li>optionally, an outline the operator measured. It is then taken as the bounds and only checked against the
 *       evidence; it is never changed.</li>
 * </ul>
 *
 * <p>Method: a plan-view occupancy grid (free / occupied votes per cell); the free component the walk stands in, with
 * gaps of up to two cells closed; holes enclosed by it become obstacles (they contain an occupied cell) or unobserved
 * interior (they do not); the outer boundary is traced on cell edges and simplified (Douglas-Peucker). Every boundary
 * edge whose outside cell is (next to) an occupied cell was observed; the rest is <b>frontier</b>: where observation
 * stopped, not a wall. The estimate reports how much of the boundary was observed and never presents frontier as a wall.
 */
public final class RoomBoundsEstimator {

    /**
     * One surface point reported by the device, with the camera position it was observed from. Plan-view metres.
     *
     * @param kind          WALL (a vertical surface) or FLOOR
     * @param normalDegrees plan-view direction the surface faces (its normal), when the device reported one
     */
    public record SurfaceObservation(double originX, double originY, double hitX, double hitY, String kind, Double normalDegrees) {}

    /**
     * @param resolution               occupancy cell size, metres
     * @param walkedRadius             floor within this distance of a walked position is free (body footprint)
     * @param fanMaxHitGap             two consecutive wall hits on the same surface (normals within fanMaxNormalDegrees) at
     *                                 most this far apart ...
     * @param fanMaxOriginShift        ... observed from origins at most this far apart clear the area between their rays, and
     *                                 the surface between them counts as observed. Without normals the gap is fanNoNormalHitGap
     *                                 and only the area between the rays is cleared
     * @param maxRayLength             surface hits farther than this from the camera are discarded as unreliable
     * @param minWallObservations      fewer accepted wall hits than this lower the confidence (and block ESTIMATED)
     * @param minObservedBoundaryShare share of the boundary that must be observed walls for ESTIMATED
     * @param minConfidence            heuristic confidence needed for ESTIMATED
     * @param maxOpeningMeters         an unobserved boundary stretch at least this long is an opening the room may continue
     *                                 through (a doorway, the end of a partition): the estimate is then LOW_CONFIDENCE
     * @param minAreaM2                a free region smaller than this is NOT_ESTIMABLE
     * @param maxCells                 largest occupancy grid accepted
     */
    public record Params(double resolution, double walkedRadius, double fanMaxHitGap, double fanMaxOriginShift, double fanMaxNormalDegrees,
                         double fanNoNormalHitGap, double maxRayLength, int minWallObservations, double minObservedBoundaryShare,
                         double minConfidence, double maxOpeningMeters, double minAreaM2, int maxCells) {

        public static Params defaults() {
            return new Params(0.2, 0.3, 0.75, 0.5, 25, 0.3, 8.0, 12, 0.85, 0.75, 0.8, 1.0, 250_000);
        }
    }

    /** An unbroken stretch of boundary where the walk observed no surface: polyline in boundary order. */
    public record Frontier(List<Point> polyline, double lengthMeters, double midX, double midY, double outwardDegrees) {}

    public record Issue(String code, String message) {}

    public record Evidence(int walkSamples, int walkSegments, double walkedMeters, int wallObservations, int floorObservations,
                           int rejectedObservations, int fanFills) {}

    /**
     * @param boundaryObserved share of the boundary length backed by an observed surface
     * @param interiorObserved 1 - unobserved interior area / area
     * @param density          min(1, wall observations / minWallObservations)
     */
    public record ConfidenceParts(double boundaryObserved, double interiorObserved, double density) {}

    /**
     * @param status  ESTIMATED (bounds supported by observations), LOW_CONFIDENCE (a polygon exists but part of its
     *                boundary is frontier or evidence is sparse: a lower bound of the room, not its walls), MEASURED (the
     *                operator's outline, checked against the evidence) or NOT_ESTIMABLE (no polygon is returned)
     * @param basis   SENSOR_EVIDENCE or OPERATOR_MEASUREMENT
     * @param area    the bounds polygon, or null when NOT_ESTIMABLE
     * @param confidence heuristic in [0, 1] = boundaryObserved x interiorObserved x density; not a calibrated probability
     */
    public record Estimate(String status, String basis, Polygon area, List<Polygon> obstacles, List<Polygon> unobservedInterior,
                           List<Frontier> frontier, double confidence, ConfidenceParts confidenceParts, double areaM2,
                           double observedBoundaryMeters, double frontierMeters, Evidence evidence, List<String> assumptions,
                           List<Issue> issues) {}

    private static final byte UNKNOWN = 0;
    private static final byte FREE = 1;
    private static final byte OCC = 2;

    private final Params p;

    public RoomBoundsEstimator(Params params) {
        this.p = params;
    }

    public RoomBoundsEstimator() {
        this(Params.defaults());
    }

    // ---------------------------------------------------------------------------------------------------------------

    public Estimate estimate(List<TrajectorySample> walk, List<SurfaceObservation> surfaces, Polygon measuredOutline) {
        List<Issue> issues = new ArrayList<>();
        List<String> assumptions = new ArrayList<>();
        List<TrajectorySample> w = new ArrayList<>();
        for (TrajectorySample s : walk) {
            if (Double.isFinite(s.t()) && Double.isFinite(s.x()) && Double.isFinite(s.y())) {
                w.add(s);
            }
        }
        w.sort(Comparator.comparingDouble(TrajectorySample::t));
        List<SurfaceObservation> walls = new ArrayList<>();
        List<SurfaceObservation> floors = new ArrayList<>();
        int rejected = 0;
        for (SurfaceObservation o : surfaces) {
            double len = Math.hypot(o.hitX() - o.originX(), o.hitY() - o.originY());
            boolean finite = Double.isFinite(o.originX()) && Double.isFinite(o.originY()) && Double.isFinite(o.hitX()) && Double.isFinite(o.hitY());
            if (!finite || len > p.maxRayLength() || ("WALL".equals(o.kind()) && len < 0.1)) {
                rejected++;
            } else if ("WALL".equals(o.kind())) {
                walls.add(o);
            } else if ("FLOOR".equals(o.kind())) {
                floors.add(o);
            } else {
                rejected++;
            }
        }
        if (rejected > 0) {
            issues.add(new Issue("OBSERVATIONS_REJECTED", rejected + " surface observation(s) were discarded: non-finite, of an unknown "
                + "kind, or farther than " + fmt(p.maxRayLength()) + " m from the camera"));
        }
        int segments = (int) w.stream().mapToInt(TrajectorySample::segment).distinct().count();
        double walked = walkedLength(w);
        Evidence ev0 = new Evidence(w.size(), segments, round(walked), walls.size(), floors.size(), rejected, 0);

        if (measuredOutline != null) {
            return checkMeasured(measuredOutline, w, walls, ev0, issues);
        }
        if (w.isEmpty()) {
            issues.add(new Issue("NO_WALK", "no tracked position was reported, so nothing is known about where the floor is"));
            return notEstimable(ev0, assumptions, issues);
        }
        if (walls.isEmpty()) {
            issues.add(new Issue("NO_SURFACE_OBSERVATIONS", "no wall surface was observed. The walked path only proves where floor is, "
                + "not where the walls are, so the room bounds cannot be estimated. Point the camera at each wall during the walk, or "
                + "enter a measured outline"));
            return notEstimable(ev0, assumptions, issues);
        }

        // ---- occupancy grid ---------------------------------------------------------------------------------------
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (TrajectorySample s : w) {
            minX = Math.min(minX, s.x()); maxX = Math.max(maxX, s.x());
            minY = Math.min(minY, s.y()); maxY = Math.max(maxY, s.y());
        }
        for (SurfaceObservation o : concat(walls, floors)) {
            minX = Math.min(minX, Math.min(o.originX(), o.hitX())); maxX = Math.max(maxX, Math.max(o.originX(), o.hitX()));
            minY = Math.min(minY, Math.min(o.originY(), o.hitY())); maxY = Math.max(maxY, Math.max(o.originY(), o.hitY()));
        }
        double res = p.resolution();
        double margin = Math.max(p.walkedRadius(), res) + 3 * res;
        double ox = minX - margin, oy = minY - margin;
        long nxL = (long) Math.ceil((maxX - minX + 2 * margin) / res);
        long nyL = (long) Math.ceil((maxY - minY + 2 * margin) / res);
        if (nxL * nyL > p.maxCells()) {
            issues.add(new Issue("EVIDENCE_TOO_LARGE", "the evidence spans " + fmt(maxX - minX) + " x " + fmt(maxY - minY)
                + " m, more than one room-sized estimate can cover"));
            return notEstimable(ev0, assumptions, issues);
        }
        Occ g = new Occ(ox, oy, res, (int) nxL, (int) nyL);

        // Walked floor: positions and the straight line between consecutive samples of the same segment.
        for (int i = 0; i < w.size(); i++) {
            TrajectorySample s = w.get(i);
            g.disc(s.x(), s.y(), p.walkedRadius());
            if (i + 1 < w.size() && w.get(i + 1).segment() == s.segment()) {
                TrajectorySample t = w.get(i + 1);
                double d = Math.hypot(t.x() - s.x(), t.y() - s.y());
                int steps = (int) Math.ceil(d / (res / 2));
                for (int k = 1; k < steps; k++) {
                    double f = (double) k / steps;
                    g.disc(s.x() + (t.x() - s.x()) * f, s.y() + (t.y() - s.y()) * f, p.walkedRadius());
                }
            }
        }
        assumptions.add("Floor within " + fmt(p.walkedRadius()) + " m of every walked position is free.");
        for (SurfaceObservation o : walls) {
            g.ray(o.originX(), o.originY(), o.hitX(), o.hitY());
        }
        assumptions.add("Plan view only: the line from the camera to an observed wall point is free at camera height; low furniture "
            + "under that line is not detected.");
        for (SurfaceObservation o : floors) {
            g.vote(g.cellOf(o.hitX(), o.hitY()), FREE);
        }
        int fans = 0, joined = 0;
        for (int i = 0; i + 1 < walls.size(); i++) {
            SurfaceObservation a = walls.get(i), b = walls.get(i + 1);
            if (Math.hypot(a.originX() - b.originX(), a.originY() - b.originY()) > p.fanMaxOriginShift()) {
                continue;
            }
            double gap = Math.hypot(a.hitX() - b.hitX(), a.hitY() - b.hitY());
            boolean sameSurface = a.normalDegrees() != null && b.normalDegrees() != null
                && Geometry.angleDiff(Math.toRadians(a.normalDegrees()), Math.toRadians(b.normalDegrees())) <= Math.toRadians(p.fanMaxNormalDegrees());
            if (sameSurface ? gap <= p.fanMaxHitGap() : gap <= p.fanNoNormalHitGap()) {
                g.triangle(a.originX(), a.originY(), a.hitX(), a.hitY(), b.hitX(), b.hitY());
                g.triangle(a.originX(), a.originY(), b.hitX(), b.hitY(), b.originX(), b.originY());
                fans++;
                if (sameSurface) {
                    g.surface(a.hitX(), a.hitY(), b.hitX(), b.hitY());
                    joined++;
                }
            }
        }
        if (fans > 0) {
            assumptions.add("The area between two consecutive wall observations taken from within " + fmt(p.fanMaxOriginShift()) + " m of "
                + "each other is free when the hits are on the same surface (normals within " + fmt(p.fanMaxNormalDegrees()) + " degrees) and at "
                + "most " + fmt(p.fanMaxHitGap()) + " m apart, or at most " + fmt(p.fanNoNormalHitGap()) + " m apart without normals ("
                + fans + " pairs). Between same-surface pairs the surface is taken as continuous (" + joined + " pairs).");
        }
        Evidence ev = new Evidence(w.size(), segments, round(walked), walls.size(), floors.size(), rejected, fans);
        byte[] state = g.resolve();

        // ---- the free region the walk stands in ------------------------------------------------------------------
        boolean[] free = new boolean[g.n];
        for (int c = 0; c < g.n; c++) {
            free[c] = state[c] == FREE;
        }
        List<Integer> seeds = new ArrayList<>();
        for (TrajectorySample s : w) {
            int c = g.cellOf(s.x(), s.y());
            if (c >= 0 && free[c]) {
                seeds.add(c);
            }
        }
        if (seeds.isEmpty()) {
            issues.add(new Issue("WALK_NOT_ON_FREE_FLOOR", "every walked position was marked occupied by surface observations; the evidence "
                + "is contradictory (wrong tracking frame, or hits reported in another coordinate frame)"));
            return notEstimable(ev, assumptions, issues);
        }
        boolean[] region = g.component(free, seeds);
        region = g.close(region, state);
        region = g.component(region, seeds);
        assumptions.add("Gaps of up to two cells (" + fmt(2 * res) + " m) inside observed free space are closed.");

        // ---- holes: obstacles (contain an occupied cell) or unobserved interior --------------------------------------
        boolean[] outside = g.outsideOf(region);
        List<Polygon> obstacles = new ArrayList<>();
        List<Polygon> unobserved = new ArrayList<>();
        double unobservedArea = 0;
        boolean[] filled = region.clone();
        boolean[] none = new boolean[g.n];
        for (List<Integer> hole : g.components8(outside, region)) {
            for (int c : hole) {
                filled[c] = true;
            }
            // Observed faces of the hole (occupied cells) block sight and walking. Unknown cells walled in by those faces
            // alone (touching no observed floor) are the obstacle's solid interior; the rest of the hole was never seen.
            boolean[] occMask = new boolean[g.n];
            boolean[] unkMask = new boolean[g.n];
            int occCount = 0, unkCount = 0;
            for (int c : hole) {
                if (state[c] == OCC) {
                    occMask[c] = true;
                    occCount++;
                } else {
                    unkMask[c] = true;
                    unkCount++;
                }
            }
            if (occCount == 0 && unkCount <= 2) {
                continue; // a pinhole between rays, not a feature
            }
            List<List<Integer>> unknownParts = g.components8(g.complement(unkMask), none);
            List<List<Integer>> pockets = new ArrayList<>();
            for (List<Integer> part : unknownParts) {
                if (occCount > 0 && !g.touches4(part, region)) {
                    part.forEach(c -> occMask[c] = true);
                } else if (part.size() > 2) {
                    pockets.add(part);
                }
            }
            for (List<Integer> part : g.components8(g.complement(occMask), none)) {
                boolean[] m = new boolean[g.n];
                part.forEach(c -> m[c] = true);
                Polygon poly = g.outline(m, 0.5 * res);
                if (poly != null) {
                    obstacles.add(poly);
                }
            }
            for (List<Integer> part : pockets) {
                boolean[] m = new boolean[g.n];
                part.forEach(c -> m[c] = true);
                Polygon poly = g.outline(m, 0.5 * res);
                if (poly != null) {
                    unobserved.add(poly);
                    unobservedArea += part.size() * g.cellArea;
                }
            }
        }
        if (!unobserved.isEmpty()) {
            assumptions.add("Unobserved space enclosed by observed floor (behind an obstacle, or never looked at) is inside the room: it "
                + "must be scanned, but it is not standable and does not block sight until it is observed.");
            issues.add(new Issue("UNOBSERVED_INTERIOR", unobserved.size() + " pocket(s) of floor inside the room (" + fmt(unobservedArea)
                + " m2) were never observed"));
        }

        int regionCells = 0;
        for (boolean b : filled) {
            if (b) regionCells++;
        }
        double area = regionCells * g.cellArea;
        if (area < p.minAreaM2()) {
            issues.add(new Issue("REGION_TOO_SMALL", "the observed free region is " + fmt(area) + " m2, below " + fmt(p.minAreaM2()) + " m2"));
            return notEstimable(ev, assumptions, issues);
        }
        Polygon outline = g.outline(filled, 0.75 * res);
        if (outline == null) {
            issues.add(new Issue("BOUNDARY_NOT_TRACEABLE", "the observed free region has no traceable boundary"));
            return notEstimable(ev, assumptions, issues);
        }

        // ---- boundary: observed wall vs frontier -----------------------------------------------------------------------
        Boundary b = g.classifyBoundary(filled, state);
        double total = b.observedMeters + b.frontierMeters;
        double boundaryObserved = total == 0 ? 0 : b.observedMeters / total;
        double interiorObserved = 1 - unobservedArea / area;
        double density = Math.min(1, (double) walls.size() / p.minWallObservations());
        double confidence = boundaryObserved * interiorObserved * density;
        List<Frontier> frontier = new ArrayList<>();
        for (List<Point> stretch : b.frontier) {
            double len = 0;
            for (int i = 0; i + 1 < stretch.size(); i++) {
                len += stretch.get(i).distanceTo(stretch.get(i + 1));
            }
            if (len < 2 * res - 1e-9) {
                continue; // a single unobserved cell edge between observed ones: noise, not a missing wall
            }
            frontier.add(frontierOf(simplifyOpen(stretch, 0.75 * res), len));
        }
        frontier.sort(Comparator.comparingDouble(Frontier::lengthMeters).reversed()
            .thenComparingDouble(Frontier::midY).thenComparingDouble(Frontier::midX));
        if (!frontier.isEmpty()) {
            double fm = frontier.stream().mapToDouble(Frontier::lengthMeters).sum();
            issues.add(new Issue("UNOBSERVED_BOUNDARY", fmt(fm) + " m of the boundary (" + pct(1 - boundaryObserved) + ") was not observed: "
                + "it is where observation stopped, not a wall. The room may extend beyond it. Largest gap: " + fmt(frontier.get(0).lengthMeters())
                + " m around (" + fmt(frontier.get(0).midX()) + ", " + fmt(frontier.get(0).midY()) + ")"));
        }
        if (walls.size() < p.minWallObservations()) {
            issues.add(new Issue("SPARSE_SURFACE_OBSERVATIONS", walls.size() + " wall observation(s); at least " + p.minWallObservations()
                + " are needed for a reliable estimate"));
        }
        if (segments > 1) {
            issues.add(new Issue("TRACKING_INTERRUPTED", "the walk has " + segments + " tracked segments; positions are not joined across a "
                + "tracking loss"));
        }
        double largestOpening = frontier.isEmpty() ? 0 : frontier.get(0).lengthMeters();
        if (largestOpening >= p.maxOpeningMeters()) {
            issues.add(new Issue("OPENING_TO_UNOBSERVED_SPACE", "an unobserved stretch of " + fmt(largestOpening) + " m around ("
                + fmt(frontier.get(0).midX()) + ", " + fmt(frontier.get(0).midY()) + ") is wide enough to be an opening (a doorway, the end "
                + "of a partition); the room may continue beyond it"));
        }
        boolean reliable = confidence >= p.minConfidence() && boundaryObserved >= p.minObservedBoundaryShare()
            && walls.size() >= p.minWallObservations() && largestOpening < p.maxOpeningMeters();
        String status = reliable ? "ESTIMATED" : "LOW_CONFIDENCE";
        if (!reliable) {
            issues.add(new Issue("LOW_CONFIDENCE", "these bounds are a lower bound of the room, not its walls: confidence " + fmt2(confidence)
                + " (boundary observed " + pct(boundaryObserved) + ", interior observed " + pct(interiorObserved) + ", observation density "
                + pct(density) + ")"));
        }
        assumptions.add("Surface positions are taken as reported by the device tracker; tracker drift is not modelled.");
        return new Estimate(status, "SENSOR_EVIDENCE", outline, List.copyOf(obstacles), List.copyOf(unobserved), List.copyOf(frontier),
            round(confidence), new ConfidenceParts(round(boundaryObserved), round(interiorObserved), round(density)), round(outline.area()),
            round(b.observedMeters), round(b.frontierMeters), ev, List.copyOf(assumptions), List.copyOf(issues));
    }

    // ---- operator measurement ------------------------------------------------------------------------------------------

    private static final double MEASURED_TOLERANCE = 0.3;

    private Estimate checkMeasured(Polygon outline, List<TrajectorySample> w, List<SurfaceObservation> walls, Evidence ev, List<Issue> issues) {
        List<String> assumptions = new ArrayList<>();
        assumptions.add("The bounds are the operator's measured outline, used as given; the evidence only checks it.");
        if (!outline.isValid()) {
            issues.add(new Issue("INVALID_OUTLINE", "the measured outline is not a valid polygon"));
            return notEstimable(ev, assumptions, issues);
        }
        List<Segment> edges = new ArrayList<>();
        List<Point> v = outline.vertices();
        for (int i = 0; i < v.size(); i++) {
            edges.add(new Segment(v.get(i), v.get((i + 1) % v.size())));
        }
        int walkedOutside = 0;
        for (TrajectorySample s : w) {
            if (!outline.contains(s.x(), s.y()) && distanceTo(edges, s.x(), s.y()) > MEASURED_TOLERANCE) {
                walkedOutside++;
            }
        }
        if (!w.isEmpty() && walkedOutside > 0.05 * w.size()) {
            issues.add(new Issue("OUTLINE_CONTRADICTED_BY_WALK", walkedOutside + " of " + w.size() + " walked positions lie more than "
                + fmt(MEASURED_TOLERANCE) + " m outside the measured outline: the outline, or its placement in the tracking frame, is wrong"));
        }
        int outsideHits = 0, consistent = 0;
        int[] support = new int[edges.size()];
        for (SurfaceObservation o : walls) {
            double best = Double.MAX_VALUE;
            int bestEdge = -1;
            for (int i = 0; i < edges.size(); i++) {
                double d = Geometry.distancePointSegment(o.hitX(), o.hitY(), edges.get(i));
                if (d < best) {
                    best = d;
                    bestEdge = i;
                }
            }
            boolean inside = outline.contains(o.hitX(), o.hitY());
            if (best <= MEASURED_TOLERANCE) {
                support[bestEdge]++;
                consistent++;
            } else if (inside) {
                consistent++; // furniture or a partition inside the room
            } else {
                outsideHits++;
            }
        }
        if (outsideHits > 0) {
            issues.add(new Issue("SURFACES_OUTSIDE_OUTLINE", outsideHits + " observed wall point(s) lie more than " + fmt(MEASURED_TOLERANCE)
                + " m outside the measured outline"));
        }
        List<Frontier> unconfirmed = new ArrayList<>();
        double observedM = 0, unconfirmedM = 0;
        for (int i = 0; i < edges.size(); i++) {
            Segment e = edges.get(i);
            if (support[i] > 0) {
                observedM += e.length();
            } else {
                unconfirmedM += e.length();
                if (!walls.isEmpty()) {
                    unconfirmed.add(frontierOf(List.of(e.a(), e.b()), e.length()));
                }
            }
        }
        double confidence;
        ConfidenceParts parts;
        if (walls.isEmpty()) {
            issues.add(new Issue("OUTLINE_UNVERIFIED", "no wall surface was observed, so the measured outline is not checked against any "
                + "sensor evidence"));
            confidence = 0;
            parts = new ConfidenceParts(0, 1, 0);
        } else {
            double boundary = observedM / (observedM + unconfirmedM);
            double consistency = (double) consistent / walls.size();
            double density = Math.min(1, (double) walls.size() / p.minWallObservations());
            confidence = boundary * consistency * density;
            parts = new ConfidenceParts(round(boundary), round(consistency), round(density));
            if (!unconfirmed.isEmpty()) {
                issues.add(new Issue("OUTLINE_EDGES_UNCONFIRMED", unconfirmed.size() + " edge(s) of the measured outline (" + fmt(unconfirmedM)
                    + " m) have no observed surface near them"));
            }
        }
        if (!w.isEmpty() && walkedOutside > 0.05 * w.size()) {
            confidence = 0;
        }
        return new Estimate("MEASURED", "OPERATOR_MEASUREMENT", outline, List.of(), List.of(), List.copyOf(unconfirmed), round(confidence),
            parts, round(outline.area()), round(observedM), round(unconfirmedM), ev, List.copyOf(assumptions), List.copyOf(issues));
    }

    // ---- helpers -------------------------------------------------------------------------------------------------------

    private static Estimate notEstimable(Evidence ev, List<String> assumptions, List<Issue> issues) {
        return new Estimate("NOT_ESTIMABLE", "SENSOR_EVIDENCE", null, List.of(), List.of(), List.of(), 0, new ConfidenceParts(0, 0, 0), 0,
            0, 0, ev, List.copyOf(assumptions), List.copyOf(issues));
    }

    private static double walkedLength(List<TrajectorySample> w) {
        double len = 0;
        for (int i = 0; i + 1 < w.size(); i++) {
            if (w.get(i).segment() == w.get(i + 1).segment()) {
                len += Math.hypot(w.get(i + 1).x() - w.get(i).x(), w.get(i + 1).y() - w.get(i).y());
            }
        }
        return len;
    }

    private static double distanceTo(List<Segment> edges, double x, double y) {
        double best = Double.MAX_VALUE;
        for (Segment e : edges) {
            best = Math.min(best, Geometry.distancePointSegment(x, y, e));
        }
        return best;
    }

    private static Frontier frontierOf(List<Point> polyline, double length) {
        // Midpoint by arc length; outward = the boundary's right-hand normal (the region is on the left), length-weighted.
        double half = length / 2, acc = 0, mx = polyline.get(0).x(), my = polyline.get(0).y(), nx = 0, ny = 0;
        boolean midSet = false;
        for (int i = 0; i + 1 < polyline.size(); i++) {
            Point a = polyline.get(i), b = polyline.get(i + 1);
            double seg = a.distanceTo(b);
            nx += b.y() - a.y();
            ny += -(b.x() - a.x());
            if (!midSet && acc + seg >= half && seg > 0) {
                double f = (half - acc) / seg;
                mx = a.x() + (b.x() - a.x()) * f;
                my = a.y() + (b.y() - a.y()) * f;
                midSet = true;
            }
            acc += seg;
        }
        List<Point> rounded = polyline.stream().map(q -> new Point(round(q.x()), round(q.y()))).toList();
        return new Frontier(rounded, round(length), round(mx), round(my), round(Math.toDegrees(Math.atan2(ny, nx))));
    }

    private static <T> List<T> concat(List<T> a, List<T> b) {
        List<T> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    /** Douglas-Peucker on an open polyline. */
    static List<Point> simplifyOpen(List<Point> pts, double tol) {
        if (pts.size() <= 2) {
            return List.copyOf(pts);
        }
        boolean[] keep = new boolean[pts.size()];
        keep[0] = true;
        keep[pts.size() - 1] = true;
        dp(pts, 0, pts.size() - 1, tol, keep);
        List<Point> out = new ArrayList<>();
        for (int i = 0; i < pts.size(); i++) {
            if (keep[i]) {
                out.add(pts.get(i));
            }
        }
        return out;
    }

    private static void dp(List<Point> pts, int a, int b, double tol, boolean[] keep) {
        if (b <= a + 1) {
            return;
        }
        Segment s = new Segment(pts.get(a), pts.get(b));
        double best = -1;
        int idx = -1;
        for (int i = a + 1; i < b; i++) {
            double d = Geometry.distancePointSegment(pts.get(i).x(), pts.get(i).y(), s);
            if (d > best) {
                best = d;
                idx = i;
            }
        }
        if (best > tol) {
            keep[idx] = true;
            dp(pts, a, idx, tol, keep);
            dp(pts, idx, b, tol, keep);
        }
    }

    /** Douglas-Peucker on a closed ring: split at the vertex farthest from the first, simplify both halves. */
    static List<Point> simplifyClosed(List<Point> ring, double tol) {
        if (ring.size() <= 4) {
            return List.copyOf(ring);
        }
        int far = 0;
        double best = -1;
        for (int i = 1; i < ring.size(); i++) {
            double d = ring.get(0).distanceTo(ring.get(i));
            if (d > best) {
                best = d;
                far = i;
            }
        }
        List<Point> first = simplifyOpen(ring.subList(0, far + 1), tol);
        List<Point> loop = new ArrayList<>(ring.subList(far, ring.size()));
        loop.add(ring.get(0));
        List<Point> second = simplifyOpen(loop, tol);
        List<Point> out = new ArrayList<>(first.subList(0, first.size() - 1));
        out.addAll(second.subList(0, second.size() - 1));
        return out;
    }

    private static double round(double v) {
        return Math.round(v * 10_000.0) / 10_000.0;
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    private static String fmt2(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    private static String pct(double f) {
        return String.format(Locale.ROOT, "%.0f%%", 100 * f);
    }

    private record Boundary(double observedMeters, double frontierMeters, List<List<Point>> frontier) {}

    /** The occupancy grid. Cell (i, j) covers [ox + i r, ox + (i+1) r) x [oy + j r, oy + (j+1) r). */
    private static final class Occ {
        final double ox, oy, res, cellArea;
        final int nx, ny, n;
        final int[] free;
        final int[] occ;

        Occ(double ox, double oy, double res, int nx, int ny) {
            this.ox = ox;
            this.oy = oy;
            this.res = res;
            this.cellArea = res * res;
            this.nx = nx;
            this.ny = ny;
            this.n = nx * ny;
            this.free = new int[n];
            this.occ = new int[n];
        }

        int cellOf(double x, double y) {
            int i = (int) Math.floor((x - ox) / res), j = (int) Math.floor((y - oy) / res);
            return i < 0 || j < 0 || i >= nx || j >= ny ? -1 : j * nx + i;
        }

        double cx(int i) {
            return ox + (i + 0.5) * res;
        }

        double cy(int j) {
            return oy + (j + 0.5) * res;
        }

        void vote(int c, byte kind) {
            if (c >= 0) {
                if (kind == FREE) free[c]++;
                else occ[c]++;
            }
        }

        void disc(double x, double y, double r) {
            int span = (int) Math.ceil(r / res) + 1;
            int ci = (int) Math.floor((x - ox) / res), cj = (int) Math.floor((y - oy) / res);
            for (int j = cj - span; j <= cj + span; j++) {
                for (int i = ci - span; i <= ci + span; i++) {
                    if (i >= 0 && j >= 0 && i < nx && j < ny && Math.hypot(cx(i) - x, cy(j) - y) <= r) {
                        free[j * nx + i]++;
                    }
                }
            }
        }

        /** Bresenham from the origin cell to the hit cell: cells before the hit are free, the hit cell is occupied. */
        void ray(double x0, double y0, double x1, double y1) {
            int a = cellOf(x0, y0), b = cellOf(x1, y1);
            if (a < 0 || b < 0) {
                return;
            }
            int i0 = a % nx, j0 = a / nx, i1 = b % nx, j1 = b / nx;
            int dx = Math.abs(i1 - i0), dy = Math.abs(j1 - j0), sx = i0 < i1 ? 1 : -1, sy = j0 < j1 ? 1 : -1, err = dx - dy;
            int i = i0, j = j0;
            while (!(i == i1 && j == j1)) {
                free[j * nx + i]++;
                int e2 = 2 * err;
                if (e2 > -dy) {
                    err -= dy;
                    i += sx;
                }
                if (e2 < dx) {
                    err += dx;
                    j += sy;
                }
            }
            occ[b]++;
        }

        /** The surface between two hits on it: occupied votes along the segment. */
        void surface(double x0, double y0, double x1, double y1) {
            int steps = Math.max(1, (int) Math.ceil(Math.hypot(x1 - x0, y1 - y0) / (res / 2)));
            int last = -2;
            for (int k = 1; k < steps; k++) {
                double f = (double) k / steps;
                int c = cellOf(x0 + (x1 - x0) * f, y0 + (y1 - y0) * f);
                if (c >= 0 && c != last) {
                    occ[c]++;
                    last = c;
                }
            }
        }

        /** Whether any cell of the set has a 4-neighbour in the mask. */
        boolean touches4(List<Integer> cells, boolean[] mask) {
            for (int c : cells) {
                int i = c % nx, j = c / nx;
                if ((i > 0 && mask[c - 1]) || (i < nx - 1 && mask[c + 1]) || (j > 0 && mask[c - nx]) || (j < ny - 1 && mask[c + nx])) {
                    return true;
                }
            }
            return false;
        }

        boolean[] complement(boolean[] mask) {
            boolean[] out = new boolean[n];
            for (int c = 0; c < n; c++) {
                out[c] = !mask[c];
            }
            return out;
        }

        void triangle(double ax, double ay, double bx, double by, double qx, double qy) {
            int i0 = Math.max(0, (int) Math.floor((Math.min(ax, Math.min(bx, qx)) - ox) / res));
            int i1 = Math.min(nx - 1, (int) Math.floor((Math.max(ax, Math.max(bx, qx)) - ox) / res));
            int j0 = Math.max(0, (int) Math.floor((Math.min(ay, Math.min(by, qy)) - oy) / res));
            int j1 = Math.min(ny - 1, (int) Math.floor((Math.max(ay, Math.max(by, qy)) - oy) / res));
            for (int j = j0; j <= j1; j++) {
                for (int i = i0; i <= i1; i++) {
                    double x = cx(i), y = cy(j);
                    double d1 = (bx - ax) * (y - ay) - (by - ay) * (x - ax);
                    double d2 = (qx - bx) * (y - by) - (qy - by) * (x - bx);
                    double d3 = (ax - qx) * (y - qy) - (ay - qy) * (x - qx);
                    boolean neg = d1 < 0 || d2 < 0 || d3 < 0, pos = d1 > 0 || d2 > 0 || d3 > 0;
                    if (!(neg && pos)) {
                        free[j * nx + i]++;
                    }
                }
            }
        }

        /** A cell is occupied when it was hit at least once and at least half as often as it was seen through. */
        byte[] resolve() {
            byte[] s = new byte[n];
            for (int c = 0; c < n; c++) {
                s[c] = occ[c] > 0 && 2 * occ[c] >= free[c] ? OCC : free[c] > 0 ? FREE : UNKNOWN;
            }
            return s;
        }

        boolean[] component(boolean[] mask, List<Integer> seeds) {
            boolean[] seen = new boolean[n];
            int[] q = new int[n];
            int head = 0, tail = 0;
            for (int s : seeds) {
                if (mask[s] && !seen[s]) {
                    seen[s] = true;
                    q[tail++] = s;
                }
            }
            while (head < tail) {
                int c = q[head++];
                int i = c % nx, j = c / nx;
                int[][] nb = {{i + 1, j}, {i - 1, j}, {i, j + 1}, {i, j - 1}};
                for (int[] p : nb) {
                    if (p[0] >= 0 && p[1] >= 0 && p[0] < nx && p[1] < ny) {
                        int k = p[1] * nx + p[0];
                        if (mask[k] && !seen[k]) {
                            seen[k] = true;
                            q[tail++] = k;
                        }
                    }
                }
            }
            return seen;
        }

        /** Morphological closing with a 5x5 square (gaps of up to two cells), never onto an occupied cell. */
        boolean[] close(boolean[] mask, byte[] state) {
            boolean[] dil = new boolean[n];
            for (int c = 0; c < n; c++) {
                if (!mask[c]) {
                    continue;
                }
                int i = c % nx, j = c / nx;
                for (int dj = -2; dj <= 2; dj++) {
                    for (int di = -2; di <= 2; di++) {
                        int a = i + di, b = j + dj;
                        if (a >= 0 && b >= 0 && a < nx && b < ny) {
                            dil[b * nx + a] = true;
                        }
                    }
                }
            }
            boolean[] out = new boolean[n];
            for (int c = 0; c < n; c++) {
                if (mask[c]) {
                    out[c] = true;
                    continue;
                }
                if (!dil[c] || state[c] == OCC) {
                    continue;
                }
                int i = c % nx, j = c / nx;
                boolean all = true;
                for (int dj = -2; dj <= 2 && all; dj++) {
                    for (int di = -2; di <= 2 && all; di++) {
                        int a = i + di, b = j + dj;
                        all = a >= 0 && b >= 0 && a < nx && b < ny && dil[b * nx + a];
                    }
                }
                out[c] = all;
            }
            return out;
        }

        /** Cells not in the region that are 8-connected to the grid border without crossing the region. */
        boolean[] outsideOf(boolean[] region) {
            boolean[] seen = new boolean[n];
            int[] q = new int[n];
            int head = 0, tail = 0;
            for (int c = 0; c < n; c++) {
                int i = c % nx, j = c / nx;
                if ((i == 0 || j == 0 || i == nx - 1 || j == ny - 1) && !region[c]) {
                    seen[c] = true;
                    q[tail++] = c;
                }
            }
            while (head < tail) {
                int c = q[head++];
                int i = c % nx, j = c / nx;
                for (int k = 0; k < 8; k++) {
                    int a = i + Grid.DI[k], b = j + Grid.DJ[k];
                    if (a >= 0 && b >= 0 && a < nx && b < ny) {
                        int m = b * nx + a;
                        if (!region[m] && !seen[m]) {
                            seen[m] = true;
                            q[tail++] = m;
                        }
                    }
                }
            }
            return seen;
        }

        /** 8-connected components of cells that are neither in the region nor outside it (the holes), in index order. */
        List<List<Integer>> components8(boolean[] outside, boolean[] region) {
            boolean[] seen = new boolean[n];
            List<List<Integer>> out = new ArrayList<>();
            for (int s = 0; s < n; s++) {
                if (region[s] || outside[s] || seen[s]) {
                    continue;
                }
                List<Integer> comp = new ArrayList<>();
                List<Integer> stack = new ArrayList<>();
                stack.add(s);
                seen[s] = true;
                while (!stack.isEmpty()) {
                    int c = stack.remove(stack.size() - 1);
                    comp.add(c);
                    int i = c % nx, j = c / nx;
                    for (int k = 0; k < 8; k++) {
                        int a = i + Grid.DI[k], b = j + Grid.DJ[k];
                        if (a >= 0 && b >= 0 && a < nx && b < ny) {
                            int m = b * nx + a;
                            if (!region[m] && !outside[m] && !seen[m]) {
                                seen[m] = true;
                                stack.add(m);
                            }
                        }
                    }
                }
                comp.sort(Integer::compare);
                out.add(comp);
            }
            return out;
        }

        // ---- boundary tracing on the cell-corner lattice. Vertex (i, j) is the corner at (ox + i r, oy + j r). --------

        private long key(int i, int j) {
            return (long) j * (nx + 1) + i;
        }

        /** Directed boundary edges with the mask on the left (counter-clockwise outer boundary). */
        private Map<Long, List<int[]>> edges(boolean[] mask) {
            Map<Long, List<int[]>> out = new HashMap<>();
            for (int c = 0; c < n; c++) {
                if (!mask[c]) {
                    continue;
                }
                int i = c % nx, j = c / nx;
                if (j == 0 || !mask[c - nx]) add(out, i, j, i + 1, j, j == 0 ? -1 : c - nx);
                if (i == nx - 1 || !mask[c + 1]) add(out, i + 1, j, i + 1, j + 1, i == nx - 1 ? -1 : c + 1);
                if (j == ny - 1 || !mask[c + nx]) add(out, i + 1, j + 1, i, j + 1, j == ny - 1 ? -1 : c + nx);
                if (i == 0 || !mask[c - 1]) add(out, i, j + 1, i, j, i == 0 ? -1 : c - 1);
            }
            return out;
        }

        /** Edge record: {i0, j0, i1, j1, outside cell or -1}. */
        private void add(Map<Long, List<int[]>> m, int i0, int j0, int i1, int j1, int outsideCell) {
            m.computeIfAbsent(key(i0, j0), k -> new ArrayList<>()).add(new int[] {i0, j0, i1, j1, outsideCell});
        }

        /** Every closed boundary loop of the mask, as lists of edges. At a pinch vertex the left-most turn is taken. */
        private List<List<int[]>> loops(boolean[] mask) {
            Map<Long, List<int[]>> all = edges(mask);
            List<Long> starts = new ArrayList<>(all.keySet());
            starts.sort(Long::compare);
            Map<int[], Boolean> used = new java.util.IdentityHashMap<>();
            List<List<int[]>> loops = new ArrayList<>();
            for (long sk : starts) {
                for (int[] first : all.get(sk)) {
                    if (used.containsKey(first)) {
                        continue;
                    }
                    List<int[]> loop = new ArrayList<>();
                    int[] e = first;
                    int guard = 0;
                    while (e != null && !used.containsKey(e) && guard++ < 4 * n + 8) {
                        used.put(e, true);
                        loop.add(e);
                        List<int[]> nexts = all.get(key(e[2], e[3]));
                        int[] pick = null;
                        int bestTurn = Integer.MIN_VALUE;
                        if (nexts != null) {
                            int dx = e[2] - e[0], dy = e[3] - e[1];
                            for (int[] cand : nexts) {
                                if (used.containsKey(cand)) {
                                    continue;
                                }
                                int ex = cand[2] - cand[0], ey = cand[3] - cand[1];
                                int cross = dx * ey - dy * ex; // > 0: left turn
                                int turn = cross > 0 ? 2 : cross == 0 ? 1 : 0;
                                if (turn > bestTurn) {
                                    bestTurn = turn;
                                    pick = cand;
                                }
                            }
                        }
                        e = pick;
                    }
                    loops.add(loop);
                }
            }
            return loops;
        }

        private static double signedArea(List<Point> ring) {
            double s = 0;
            for (int i = 0; i < ring.size(); i++) {
                Point a = ring.get(i), b = ring.get((i + 1) % ring.size());
                s += a.x() * b.y() - b.x() * a.y();
            }
            return s / 2;
        }

        private List<Point> ringOf(List<int[]> loop) {
            List<Point> ring = new ArrayList<>();
            for (int k = 0; k < loop.size(); k++) {
                int[] e = loop.get(k), prev = loop.get((k + loop.size() - 1) % loop.size());
                boolean collinear = (prev[2] - prev[0]) * (e[3] - e[1]) - (prev[3] - prev[1]) * (e[2] - e[0]) == 0;
                if (!collinear) {
                    ring.add(new Point(ox + e[0] * res, oy + e[1] * res));
                }
            }
            return ring;
        }

        /** The outer boundary of the mask as a simplified polygon (the largest counter-clockwise loop), or null. */
        Polygon outline(boolean[] mask, double tol) {
            List<Point> best = null;
            double bestArea = 0;
            for (List<int[]> loop : loops(mask)) {
                List<Point> ring = ringOf(loop);
                double a = signedArea(ring);
                if (ring.size() >= 3 && a > bestArea) {
                    bestArea = a;
                    best = ring;
                }
            }
            if (best == null) {
                return null;
            }
            List<Point> simple = simplifyClosed(best, tol);
            List<Point> rounded = simple.stream().map(q -> new Point(round(q.x()), round(q.y()))).toList();
            Polygon poly = new Polygon(rounded);
            if (!poly.isValid()) {
                poly = new Polygon(best);
            }
            return poly.isValid() ? poly : null;
        }

        /** Classifies every edge of the mask's outer boundary as observed wall or frontier, and chains the frontier. */
        Boundary classifyBoundary(boolean[] mask, byte[] state) {
            List<int[]> outer = null;
            double bestArea = 0;
            for (List<int[]> loop : loops(mask)) {
                double a = signedArea(loop.stream().map(e -> new Point(e[0], e[1])).toList());
                if (a > bestArea) {
                    bestArea = a;
                    outer = loop;
                }
            }
            if (outer == null) {
                return new Boundary(0, 0, List.of());
            }
            boolean[] observed = new boolean[outer.size()];
            for (int k = 0; k < outer.size(); k++) {
                observed[k] = nearOccupied(outer.get(k)[4], state);
            }
            double obsM = 0, frM = 0;
            for (boolean b : observed) {
                if (b) obsM += res;
                else frM += res;
            }
            // Chain frontier edges; start right after an observed edge so a stretch is never split by the loop's start.
            List<List<Point>> stretches = new ArrayList<>();
            int start = -1;
            for (int k = 0; k < outer.size(); k++) {
                if (observed[k]) {
                    start = (k + 1) % outer.size();
                    break;
                }
            }
            if (start < 0) {
                List<Point> all = new ArrayList<>();
                for (int[] e : outer) {
                    all.add(new Point(ox + e[0] * res, oy + e[1] * res));
                }
                all.add(all.get(0));
                stretches.add(all);
            } else {
                List<Point> cur = null;
                for (int s = 0; s < outer.size(); s++) {
                    int k = (start + s) % outer.size();
                    int[] e = outer.get(k);
                    if (!observed[k]) {
                        if (cur == null) {
                            cur = new ArrayList<>();
                            cur.add(new Point(ox + e[0] * res, oy + e[1] * res));
                        }
                        cur.add(new Point(ox + e[2] * res, oy + e[3] * res));
                    } else if (cur != null) {
                        stretches.add(cur);
                        cur = null;
                    }
                }
                if (cur != null) {
                    stretches.add(cur);
                }
            }
            return new Boundary(obsM, frM, stretches);
        }

        /** The outside cell of a boundary edge is an observed surface if it, or a cell touching it, is occupied. */
        private boolean nearOccupied(int cell, byte[] state) {
            if (cell < 0) {
                return false;
            }
            int i = cell % nx, j = cell / nx;
            for (int dj = -1; dj <= 1; dj++) {
                for (int di = -1; di <= 1; di++) {
                    int a = i + di, b = j + dj;
                    if (a >= 0 && b >= 0 && a < nx && b < ny && state[b * nx + a] == OCC) {
                        return true;
                    }
                }
            }
            return false;
        }
    }
}
