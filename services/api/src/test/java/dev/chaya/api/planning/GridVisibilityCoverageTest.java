package dev.chaya.api.planning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import dev.chaya.api.planning.geometry.Point;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Steps 2 and 4 and the coverage model, on the synthetic fixtures. */
class GridVisibilityCoverageTest {

    private final PlannerConfig cfg = PlannerConfig.defaults();

    private Grid grid(PlanRequest r) {
        return Grid.build(r.scene(), r.config());
    }

    private List<Integer> seedsOf(Grid g, PlanRequest r) {
        List<Integer> seeds = new ArrayList<>();
        for (var p : TrajectoryNormalizer.normalize(r.trajectory(), r.config()).poses()) {
            seeds.add(g.snapToStandable(p.x(), p.y(), 3));
        }
        return seeds;
    }

    // ---- grid and walkable region ------------------------------------------------------------------------

    @Test
    void aRectangularRoomRasterisesToItsFloorAreaPlusBoundarySurfaces() {
        Grid g = grid(PlannerFixtures.rectangularRoom());
        int free = 0, targets = 0;
        for (int c = 0; c < g.n; c++) {
            if (g.type[c] == Grid.FREE) free++;
            if (g.target[c]) targets++;
        }
        assertThat(free * g.cellArea).isCloseTo(48.0, within(1.0));
        assertThat(targets).as("floor plus the wall faces touching it").isGreaterThan(free);
        int c = g.cellOf(4, 3);
        assertThat(g.standable[c]).isTrue();
        assertThat(g.standable[g.cellOf(0.1, 3)]).as("closer to the wall than the agent radius").isFalse();
        assertThat(g.standable[g.cellOf(1.0, 3)]).isTrue();
        assertThat((double) g.clearance[g.cellOf(4, 3)]).isCloseTo(3.0, within(0.3));
    }

    @Test
    void obstaclesAndNoGoZonesAreNotStandableAndObstaclesBlock() {
        Grid g = grid(PlannerFixtures.roomWithSealedNeighbour());
        assertThat(g.type[g.cellOf(5, 3)]).isEqualTo(Grid.OBSTACLE);
        assertThat(g.standable[g.cellOf(5, 3)]).isFalse();
        assertThat(g.type[g.cellOf(1.2, 5.0)]).as("no-go zone is floor").isEqualTo(Grid.FREE);
        assertThat(g.standable[g.cellOf(1.2, 5.0)]).as("but nobody may stand there").isFalse();
        assertThat(g.target[g.cellOf(1.2, 5.0)]).as("and it still has to be reconstructed").isTrue();
    }

    @Test
    void aDoorwayGapMakesTheNextRoomReachableAndASolidWallDoesNot() {
        PlanRequest withDoor = PlannerFixtures.roomWithDoorway();
        Grid g = grid(withDoor);
        boolean[] reach = g.reachableFrom(seedsOf(g, withDoor));
        assertThat(reach[g.cellOf(2, 2)]).isTrue();
        assertThat(reach[g.cellOf(8, 2.5)]).as("corridor beyond the doorway").isTrue();

        Scene closed = new Scene(withDoor.scene().areas(), List.of(PlannerFixtures.wall(6, 0, 6, 5)), List.of(), List.of(), List.of());
        PlanRequest solid = PlannerFixtures.request(closed, withDoor.trajectory());
        Grid g2 = grid(solid);
        boolean[] reach2 = g2.reachableFrom(seedsOf(g2, solid));
        assertThat(reach2[g2.cellOf(2, 2)]).isTrue();
        assertThat(reach2[g2.cellOf(8, 2.5)]).as("no way through").isFalse();
    }

    @Test
    void sceneErrorsAreExplicit() {
        assertThatThrownBy(() -> Grid.build(new Scene(List.of(), List.of(), List.of(), List.of(), List.of()), cfg))
            .isInstanceOf(PlanningException.class).extracting("code").isEqualTo("NO_AREAS");
        Scene huge = new Scene(List.of(PlannerFixtures.rect(0, 0, 500, 500)), List.of(), List.of(), List.of(), List.of());
        assertThatThrownBy(() -> Grid.build(huge, cfg)).isInstanceOf(PlanningException.class).extracting("code").isEqualTo("SCENE_TOO_LARGE");
        Scene degenerate = new Scene(List.of(new dev.chaya.api.planning.geometry.Polygon(List.of(new Point(0, 0), new Point(1, 1), new Point(2, 2)))),
            List.of(), List.of(), List.of(), List.of());
        assertThatThrownBy(() -> Grid.build(degenerate, cfg)).extracting("code").isEqualTo("INVALID_GEOMETRY");
    }

    @Test
    void cornersDoorwaysAndBoundariesCarryHigherImportance() {
        PlanRequest r = PlannerFixtures.roomWithDoorway();
        Grid g = grid(r);
        assertThat(g.importance[g.cellOf(2.5, 2.5)]).as("plain floor").isEqualTo(1f);
        assertThat(g.importance[g.cellOf(0.3, 0.3)]).as("room corner").isEqualTo((float) cfg.cornerWeight());
        assertThat(g.importance[g.cellOf(5.6, 2.5)]).as("in front of the doorway").isEqualTo((float) cfg.doorwayWeight());
        assertThat(g.importance[g.cellOf(0.3, 2.5)]).as("floor beside a wall").isEqualTo((float) cfg.boundaryWeight());
    }

    // ---- visibility (step 4) -----------------------------------------------------------------------------

    @Test
    void visibilityRespectsRangeWeightAndFieldOfView() {
        PlanRequest r = PlannerFixtures.rectangularRoom();
        Grid g = grid(r);
        Visibility vis = new Visibility(g, cfg);
        Visibility.Obs all = vis.around(4, 3, null);
        double best = 0;
        for (int k = 0; k < all.n; k++) {
            double d = Math.hypot(g.centerX(all.cell[k]) - 4, g.centerY(all.cell[k]) - 3);
            assertThat(d).isBetween(cfg.minRange() - 1e-6, cfg.maxRange() + 1e-6);
            assertThat(all.w[k]).isBetween((float) cfg.minObservationQuality() - 1e-6f, 1.0f);
            if (Math.abs(d - cfg.targetDistance()) < 0.1) {
                best = Math.max(best, all.w[k]);
            }
        }
        assertThat(best).as("quality peaks at the target distance").isGreaterThan(0.95);
        Visibility.Obs east = vis.facing(all, 0);
        assertThat(east.n).isLessThan(all.n);
        for (int k = 0; k < east.n; k++) {
            double bearing = Math.atan2(g.centerY(east.cell[k]) - 3, g.centerX(east.cell[k]) - 4);
            assertThat(Math.abs(bearing)).isLessThanOrEqualTo(cfg.fovRadians() / 2 + 1e-6);
        }
    }

    @Test
    void anObstacleBlocksSightAndRecordsTheHiddenCellsAsShadow() {
        PlanRequest r = PlannerFixtures.roomWithOcclusion();
        Grid g = grid(r);
        Visibility vis = new Visibility(g, cfg);
        BitSet shadow = new BitSet();
        Visibility.Obs obs = vis.around(2.5, 3.0, shadow);
        int behind = g.cellOf(5.5, 3.0); // 3 m away, straight through the partition
        assertThat(behind).isGreaterThanOrEqualTo(0);
        assertThat(g.target[behind]).isTrue();
        assertThat(java.util.Arrays.stream(obs.cell, 0, obs.n).anyMatch(c -> c == behind)).as("not seen through the partition").isFalse();
        assertThat(shadow.get(behind)).as("but recorded as hidden").isTrue();
        int front = g.cellOf(3.4, 3.0);
        assertThat(java.util.Arrays.stream(obs.cell, 0, obs.n).anyMatch(c -> c == front)).as("the partition face is seen").isTrue();
    }

    @Test
    void sightDoesNotSlipThroughADiagonalGapBetweenTouchingWalls() {
        Scene s = new Scene(List.of(PlannerFixtures.rect(0, 0, 8, 8)), List.of(PlannerFixtures.wall(2, 2, 6, 6)), List.of(), List.of(), List.of());
        Grid g = Grid.build(s, cfg);
        Visibility vis = new Visibility(g, cfg);
        int a = g.cellOf(3.1, 4.9), b = g.cellOf(4.9, 3.1); // either side of the diagonal wall
        assertThat(vis.clear(a % g.nx, a / g.nx, b % g.nx, b / g.nx)).isFalse();
    }

    // ---- coverage model ---------------------------------------------------------------------------------

    private Visibility.Obs single(int cell, float w, float back) {
        return new Visibility.Obs(new int[] {cell}, new float[] {w}, new float[] {0f}, new float[] {back}, 1);
    }

    @Test
    void aCellNeedsEnoughQualityAndWideEnoughViewAnglesToCountAsCovered() {
        Grid g = grid(PlannerFixtures.rectangularRoom());
        int c = g.cellOf(4, 3);
        CoverageState s = new CoverageState(g, cfg);
        s.add(single(c, 1.0f, 0f));
        assertThat(s.covered(c)).as("one good view is not enough to triangulate").isFalse();
        s.add(single(c, 1.0f, 0.02f));
        assertThat(s.covered(c)).as("enough quality but almost the same direction").isFalse();
        s.add(single(c, 1.0f, (float) Math.toRadians(30)));
        assertThat(s.covered(c)).as("quality and parallax").isTrue();

        CoverageState low = new CoverageState(g, cfg);
        low.add(single(c, 0.4f, 0f));
        low.add(single(c, 0.4f, (float) Math.toRadians(40)));
        assertThat(low.covered(c)).as("wide angle but poor quality (mass 0.8 < 1.5)").isFalse();
    }

    @Test
    void addingObservationsNeverLowersCoverageAndGainMatchesTheActualChange() {
        PlanRequest r = PlannerFixtures.rectangularRoom();
        Grid g = grid(r);
        Visibility vis = new Visibility(g, cfg);
        CoverageState s = new CoverageState(g, cfg);
        double coveredBefore = 0, weightedBefore = 0;
        double[][] poses = {{2, 2, 0}, {2, 3, 1.0}, {4, 2, 2.0}, {4, 4, -1.0}, {6, 3, 3.0}, {2.5, 4.5, 0.5}, {5, 1.5, 1.5}, {3, 3, -2.5}};
        for (double[] p : poses) {
            Visibility.Obs o = vis.facing(vis.around(p[0], p[1], null), p[2]);
            CoverageState.Gain gain = s.gain(o, null);
            s.add(o);
            CoverageState.Raw now = s.raw();
            assertThat(now.coveredAreaM2()).isGreaterThanOrEqualTo(coveredBefore - 1e-9);
            assertThat(now.weightedFraction()).isGreaterThanOrEqualTo(weightedBefore - 1e-9);
            assertThat(gain.coveredAreaM2()).as("predicted gain = actual change").isCloseTo(now.coveredAreaM2() - coveredBefore, within(1e-6));
            assertThat(gain.weighted()).isGreaterThanOrEqualTo(0);
            coveredBefore = now.coveredAreaM2();
            weightedBefore = now.weightedFraction();
        }
    }

    @Test
    void redundantCaptureMeasuresObservationQualityBeyondWhatCoverageNeeded() {
        Grid g = grid(PlannerFixtures.rectangularRoom());
        int c = g.cellOf(4, 3);
        CoverageState s = new CoverageState(g, cfg);
        assertThat(s.raw().redundantFraction()).isZero();
        s.add(single(c, 1.0f, 0f));
        s.add(single(c, 1.0f, (float) Math.toRadians(30)));
        double mass = 2.0, needed = cfg.requiredObservationMass();
        assertThat(s.raw().redundantFraction()).isCloseTo((mass - needed) / mass, within(1e-6));
        s.add(single(c, 1.0f, (float) Math.toRadians(60)));
        assertThat(s.raw().redundantFraction()).as("more observations of a covered cell are more redundancy").isCloseTo((3.0 - needed) / 3.0, within(1e-6));
    }

    @Test
    void observedRegionsCreditCoverageWithoutAnyPose() {
        Grid g = grid(PlannerFixtures.rectangularRoom());
        CoverageState s = new CoverageState(g, cfg);
        for (int c = 0; c < g.n; c++) {
            if (g.target[c] && g.centerX(c) < 4) {
                s.credit(c, 1.0);
            }
        }
        double pct = s.raw().coveredAreaM2() / s.raw().targetAreaM2();
        assertThat(pct).as("about the west half of the target area").isBetween(0.4, 0.6);
    }
}
