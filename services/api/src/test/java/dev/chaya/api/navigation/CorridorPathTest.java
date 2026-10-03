package dev.chaya.api.navigation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The string-pulled path through a polygon corridor (review N-3), on hand-made convex polygons: unit inputs, not navmesh
 * output (the real Recast output is routed on in PipelineControlPlaneTest).
 */
class CorridorPathTest {

    /** An L-shaped corridor, 1 m wide, of three convex polygons: east along y in [0, 1], a corner square, north along x in
     * [3, 4]. Centroids and the portals between consecutive polygons. */
    private static final double[][][] L_POLYGONS = {
        {{0, 0, 0}, {3, 0, 0}, {3, 1, 0}, {0, 1, 0}},
        {{3, 0, 0}, {4, 0, 0}, {4, 1, 0}, {3, 1, 0}},
        {{3, 1, 0}, {4, 1, 0}, {4, 4, 0}, {3, 4, 0}},
    };
    private static final List<double[]> L_CENTRES = List.of(new double[]{1.5, 0.5, 0}, new double[]{3.5, 0.5, 0},
        new double[]{3.5, 2.5, 0});
    private static final List<double[][]> L_PORTALS = List.of(
        new double[][]{{3, 0, 0}, {3, 1, 0}},
        new double[][]{{3, 1, 0}, {4, 1, 0}});

    private static boolean inPolygon(double[] p, double[][] poly) {
        boolean inside = false;
        for (int i = 0, j = poly.length - 1; i < poly.length; j = i++) {
            if ((poly[i][1] > p[1]) != (poly[j][1] > p[1])
                && p[0] < (poly[j][0] - poly[i][0]) * (p[1] - poly[i][1]) / (poly[j][1] - poly[i][1]) + poly[i][0]) {
                inside = !inside;
            }
        }
        // the boundary counts as inside: a string-pulled path touches the corners it turns at
        for (int i = 0, j = poly.length - 1; i < poly.length; j = i++) {
            double cross = (poly[i][0] - poly[j][0]) * (p[1] - poly[j][1]) - (poly[i][1] - poly[j][1]) * (p[0] - poly[j][0]);
            boolean between = Math.min(poly[i][0], poly[j][0]) - 1e-9 <= p[0] && p[0] <= Math.max(poly[i][0], poly[j][0]) + 1e-9
                && Math.min(poly[i][1], poly[j][1]) - 1e-9 <= p[1] && p[1] <= Math.max(poly[i][1], poly[j][1]) + 1e-9;
            if (Math.abs(cross) < 1e-9 && between) {
                return true;
            }
        }
        return inside;
    }

    private static List<double[]> samples(List<double[]> path) {
        List<double[]> out = new ArrayList<>();
        for (int i = 1; i < path.size(); i++) {
            double[] a = path.get(i - 1);
            double[] b = path.get(i);
            for (int k = 0; k <= 100; k++) {
                double t = k / 100.0;
                out.add(new double[]{a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t});
            }
        }
        return out;
    }

    @Test
    void everySegmentOfAnLShapedCorridorRouteLiesInsideTheNavmeshPolygons() {
        double[] start = {0.5, 0.5, 0};
        double[] end = {3.5, 3.5, 0};
        List<double[]> path = CorridorPath.pull(start, end, CorridorPath.orient(L_CENTRES, L_PORTALS));
        assertThat(samples(path)).allSatisfy(p -> assertThat(
            inPolygon(p, L_POLYGONS[0]) || inPolygon(p, L_POLYGONS[1]) || inPolygon(p, L_POLYGONS[2]))
            .as("(%.3f, %.3f) is inside the corridor", p[0], p[1]).isTrue());
        // it turns exactly at the inner corner (3, 1): the shortest way round
        assertThat(path).hasSize(3);
        assertThat(path.get(1)[0]).isCloseTo(3.0, within(1e-9));
        assertThat(path.get(1)[1]).isCloseTo(1.0, within(1e-9));

        // and it is shorter than the polygon-centroid polyline it replaces
        List<double[]> centroids = new ArrayList<>(List.of(start));
        centroids.addAll(L_CENTRES);
        centroids.add(end);
        assertThat(RouteService.polylineLength(path)).isLessThan(RouteService.polylineLength(centroids));
    }

    @Test
    void theCentroidPolylineCanLeaveTheCorridorTheStringPulledPathCannot() {
        // A corridor that jogs: two long thin polygons offset sideways, sharing a short portal at one end. The segment
        // between their centroids passes outside both; the string-pulled path goes through the portal.
        double[][] a = {{0, 0, 0}, {4, 0, 0}, {4, 1, 0}, {0, 1, 0}};
        double[][] b = {{3, 1, 0}, {4, 1, 0}, {4, 5, 0}, {3, 5, 0}};
        List<double[]> centres = List.of(new double[]{2, 0.5, 0}, new double[]{3.5, 3, 0});
        List<double[][]> portals = List.<double[][]>of(new double[][]{{3, 1, 0}, {4, 1, 0}});
        double[] start = {0.2, 0.2, 0};
        double[] end = {3.2, 4.8, 0};
        List<double[]> centroidPath = List.of(start, centres.get(0), centres.get(1), end);
        assertThat(samples(centroidPath)).anySatisfy(p -> assertThat(inPolygon(p, a) || inPolygon(p, b)).isFalse());
        List<double[]> pulled = CorridorPath.pull(start, end, CorridorPath.orient(centres, portals));
        assertThat(samples(pulled)).allSatisfy(p -> assertThat(inPolygon(p, a) || inPolygon(p, b)).isTrue());
    }

    @Test
    void aStraightCorridorIsOneSegmentAndHeightsComeFromThePortalCorners() {
        List<double[]> centres = List.of(new double[]{0.5, 0.5, 0}, new double[]{1.5, 0.5, 0.1}, new double[]{2.5, 0.5, 0.2});
        List<double[][]> portals = List.of(new double[][]{{1, 0, 0.05}, {1, 1, 0.05}}, new double[][]{{2, 0, 0.15}, {2, 1, 0.15}});
        List<double[]> path = CorridorPath.pull(new double[]{0.2, 0.5, 0}, new double[]{2.8, 0.5, 0.2}, CorridorPath.orient(centres, portals));
        assertThat(path).hasSize(2);
        // a corner inherits its portal endpoint's height (a ramp or a step stays where it is)
        List<double[]> bent = CorridorPath.pull(new double[]{0.2, 0.9, 0}, new double[]{2.8, 0.1, 0.2},
            // the straight line (0.2, 0.9) -> (2.8, 0.1) passes x = 1 at y = 0.65, below this first portal: it must bend
            CorridorPath.orient(centres, List.of(new double[][]{{1, 0.7, 0.05}, {1, 1, 0.05}}, new double[][]{{2, 0, 0.15}, {2, 0.3, 0.15}})));
        assertThat(bent).hasSizeGreaterThan(2);
        assertThat(bent.get(1)).containsExactly(1.0, 0.7, 0.05);
    }

    @Test
    void portalsAreOrientedLeftAndRightOfTheTravelDirection() {
        List<CorridorPath.Portal> east = CorridorPath.orient(List.of(new double[]{0, 0, 0}, new double[]{2, 0, 0}),
            List.<double[][]>of(new double[][]{{1, -1, 0}, {1, 1, 0}}));
        assertThat(east.get(0).left()[1]).as("heading +x, +y is on the left").isEqualTo(1.0);
        List<CorridorPath.Portal> west = CorridorPath.orient(List.of(new double[]{2, 0, 0}, new double[]{0, 0, 0}),
            List.<double[][]>of(new double[][]{{1, -1, 0}, {1, 1, 0}}));
        assertThat(west.get(0).left()[1]).isEqualTo(-1.0);
    }

    @Test
    void midpointsStayInsideTheCorridorToo() {
        List<double[]> mids = CorridorPath.midpoints(new double[]{0.5, 0.5, 0}, new double[]{3.5, 3.5, 0},
            CorridorPath.orient(L_CENTRES, L_PORTALS));
        assertThat(samples(mids)).allSatisfy(p -> assertThat(
            inPolygon(p, L_POLYGONS[0]) || inPolygon(p, L_POLYGONS[1]) || inPolygon(p, L_POLYGONS[2])).isTrue());
    }
}
