package dev.chaya.api.planning;

import static dev.chaya.api.planning.PlannerFixtures.lap;
import static dev.chaya.api.planning.PlannerFixtures.rect;
import static org.assertj.core.api.Assertions.assertThat;

import dev.chaya.api.planning.RoomBoundsEstimator.Estimate;
import dev.chaya.api.planning.RoomBoundsEstimator.Issue;
import dev.chaya.api.planning.RoomBoundsEstimator.SurfaceObservation;
import dev.chaya.api.planning.geometry.Geometry;
import dev.chaya.api.planning.geometry.Point;
import dev.chaya.api.planning.geometry.Polygon;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Bounds estimation against hand-drawn ground truth. Observations come from {@link SyntheticRangeSensor}, a deterministic
 * ray caster standing in for the device's hit test; the walks are the planner fixtures' 2 Hz laps.
 */
class RoomBoundsEstimatorTest {

    private final RoomBoundsEstimator estimator = new RoomBoundsEstimator();

    private static Scene room(Polygon outline, Polygon... obstacles) {
        return new Scene(List.of(outline), List.of(), List.of(obstacles), List.of(), List.of());
    }

    private static List<String> codes(Estimate e) {
        return e.issues().stream().map(Issue::code).toList();
    }

    @Test
    void aRectangularRoomSweptFromAShortWalkIsEstimatedWithItsWalls() {
        Scene truth = room(rect(0, 0, 8, 6));
        List<TrajectorySample> walk = lap(1.0, 2, 2, 6, 2, 6, 4, 2, 4, 2, 2.5);
        List<SurfaceObservation> obs = new SyntheticRangeSensor(truth, 8).sweep(walk, 7, 6, 40, null);

        Estimate e = estimator.estimate(walk, obs, null);

        assertThat(e.status()).isEqualTo("ESTIMATED");
        assertThat(e.basis()).isEqualTo("SENSOR_EVIDENCE");
        assertThat(SyntheticRangeSensor.iou(e.area(), e.obstacles(), truth.areas().get(0), 0.05)).isGreaterThan(0.9);
        assertThat(e.areaM2()).isBetween(48 * 0.88, 48 * 1.05);
        assertThat(e.confidenceParts().boundaryObserved()).isGreaterThan(0.85);
        assertThat(e.confidence()).isGreaterThan(0.6);
        assertThat(SyntheticRangeSensor.containedShare(e.area(), truth.areas().get(0), 0.05))
            .as("never claims floor beyond the true walls by more than a cell").isGreaterThan(0.97);
        assertThat(e.assumptions()).isNotEmpty();
    }

    @Test
    void anLShapedRoomKeepsItsReflexCorner() {
        Polygon l = new Polygon(List.of(new Point(0, 0), new Point(9, 0), new Point(9, 4), new Point(4, 4), new Point(4, 8), new Point(0, 8)));
        Scene truth = room(l);
        List<TrajectorySample> walk = lap(1.0, 2, 6.5, 2, 2, 7.5, 2, 2, 2, 2, 5);
        Estimate e = estimator.estimate(walk, new SyntheticRangeSensor(truth, 8).sweep(walk, 7, 6, 40, null), null);

        assertThat(e.status()).isEqualTo("ESTIMATED");
        assertThat(SyntheticRangeSensor.iou(e.area(), e.obstacles(), l, 0.05)).isGreaterThan(0.88);
        assertThat(e.area().contains(7.5, 2)).as("east arm").isTrue();
        assertThat(e.area().contains(2, 7)).as("north arm").isTrue();
        assertThat(e.area().contains(7, 6.5)).as("the missing quadrant is not floor").isFalse();
        assertThat(e.area().vertices().size()).isBetween(6, 14);
    }

    @Test
    void anIrregularRoomWithASlantedWallIsFollowedByTheOutline() {
        Polygon p = new Polygon(List.of(new Point(0, 0), new Point(7, 0), new Point(9, 3), new Point(7, 6), new Point(0, 6)));
        Scene truth = room(p);
        List<TrajectorySample> walk = lap(1.0, 2, 2, 6, 2, 6.5, 4, 2, 4, 2, 2.5);
        Estimate e = estimator.estimate(walk, new SyntheticRangeSensor(truth, 8).sweep(walk, 7, 6, 40, null), null);

        assertThat(e.status()).isEqualTo("ESTIMATED");
        assertThat(SyntheticRangeSensor.iou(e.area(), e.obstacles(), p, 0.05)).isGreaterThan(0.88);
        assertThat(e.area().contains(8.3, 3)).as("the apex between the slanted walls").isTrue();
        assertThat(e.area().contains(8.5, 1)).as("outside the slanted wall").isFalse();
    }

    @Test
    void aWallTheCameraNeverFacedIsReportedAsFrontierAndTheEstimateIsOnlyALowerBound() {
        Scene truth = room(rect(0, 0, 8, 6));
        List<TrajectorySample> walk = lap(1.0, 2, 1.5, 6, 1.5, 6, 2.5, 2, 2.5, 2, 2);
        // The operator only ever points the camera east, south and west: never north.
        List<SurfaceObservation> obs = new SyntheticRangeSensor(truth, 8).sweep(walk, 7, 6, 40, new double[] {180, 360});

        Estimate e = estimator.estimate(walk, obs, null);

        assertThat(e.status()).isEqualTo("LOW_CONFIDENCE");
        assertThat(codes(e)).contains("UNOBSERVED_BOUNDARY", "LOW_CONFIDENCE");
        assertThat(e.frontier()).isNotEmpty();
        var largest = e.frontier().get(0);
        assertThat(Math.toDegrees(Geometry.angleDiff(Math.toRadians(largest.outwardDegrees()), Math.PI / 2)))
            .as("the unobserved stretch faces north").isLessThan(45);
        assertThat(e.areaM2()).as("a lower bound: smaller than the room").isLessThan(48 * 0.8);
        assertThat(SyntheticRangeSensor.containedShare(e.area(), truth.areas().get(0), 0.05)).isGreaterThan(0.97);
    }

    @Test
    void theSpaceHiddenBehindAPillarIsReportedAsUnobservedNeverAsFloorOrWall() {
        Polygon outline = rect(0, 0, 10, 6);
        Polygon pillar = rect(4.5, 2.5, 5.5, 3.5);
        Scene truth = room(outline, pillar);
        // The walk stays west of the pillar, so a wedge of floor east of it is hidden.
        List<TrajectorySample> walk = lap(1.0, 1.5, 1.5, 3.5, 1.5, 3.5, 4.5, 1.5, 4.5, 1.5, 2);
        Estimate e = estimator.estimate(walk, new SyntheticRangeSensor(truth, 9).sweep(walk, 7, 6, 40, null), null);

        assertThat(e.area()).isNotNull();
        boolean pillarIsFloor = e.area().contains(5.0, 3.0) && e.obstacles().stream().noneMatch(o -> o.contains(5.0, 3.0));
        assertThat(pillarIsFloor).as("the pillar is never claimed as floor").isFalse();
        assertThat(e.area().contains(8.0, 3.0) && e.obstacles().stream().noneMatch(o -> o.contains(8.0, 3.0))
            && e.unobservedInterior().stream().noneMatch(u -> u.contains(8.0, 3.0)))
            .as("the hidden floor right behind the pillar is not claimed as observed floor").isFalse();
        // Where observation stopped (the wedge behind the pillar and the unseen part of the east wall) is reported.
        assertThat(e.frontier().stream().anyMatch(f -> f.midX() > 5.0)).isTrue();
        assertThat(codes(e)).contains("UNOBSERVED_BOUNDARY");
    }

    @Test
    void aWallWithAnOpeningAtItsEndIsNeverTakenForTheRoomsLastWall() {
        // A 1 x 4 m partition splits a 10 x 6 m room; the walk stays west of it. Its face looks like a wall, but rays pass
        // the open ends: the room continues, so the estimate must not claim to be the room.
        Scene truth = room(rect(0, 0, 10, 6), rect(4.5, 1.0, 5.5, 5.0));
        List<TrajectorySample> walk = lap(1.0, 1.5, 1.5, 3.5, 1.5, 3.5, 4.5, 1.5, 4.5, 1.5, 2);
        Estimate e = estimator.estimate(walk, new SyntheticRangeSensor(truth, 8).sweep(walk, 7, 6, 40, null), null);
        assertThat(e.status()).isEqualTo("LOW_CONFIDENCE");
        assertThat(codes(e)).contains("OPENING_TO_UNOBSERVED_SPACE");
        assertThat(e.frontier().get(0).lengthMeters()).isGreaterThanOrEqualTo(0.8);
    }

    @Test
    void aPillarWalkedAroundBecomesAnObstacleWithoutAnUnobservedInterior() {
        Polygon outline = rect(0, 0, 10, 6);
        Polygon pillar = rect(4.5, 2.5, 5.5, 3.5);
        Scene truth = room(outline, pillar);
        List<TrajectorySample> walk = lap(1.0, 2, 1.2, 8, 1.2, 8, 4.8, 2, 4.8, 2, 1.6);
        // The camera looks all around at every step, so every face of the pillar is seen.
        Estimate e = estimator.estimate(walk, new SyntheticRangeSensor(truth, 8).sweep(walk), null);

        assertThat(e.status()).isEqualTo("ESTIMATED");
        assertThat(e.obstacles()).hasSize(1);
        Polygon o = e.obstacles().get(0);
        assertThat(o.contains(5.0, 3.0)).as("the pillar, interior included").isTrue();
        assertThat(o.area()).isBetween(0.6, 1.8);
        assertThat(e.unobservedInterior()).isEmpty();
        assertThat(SyntheticRangeSensor.iou(e.area(), e.obstacles(), new Polygon(outline.vertices()), 0.05)).isGreaterThan(0.85);
    }

    @Test
    void withoutAnySurfaceObservationTheBoundsAreNotEstimable() {
        List<TrajectorySample> walk = lap(1.0, 2, 2, 6, 2, 6, 4);
        Estimate e = estimator.estimate(walk, List.of(), null);
        assertThat(e.status()).isEqualTo("NOT_ESTIMABLE");
        assertThat(e.area()).isNull();
        assertThat(codes(e)).containsExactly("NO_SURFACE_OBSERVATIONS");

        Estimate none = estimator.estimate(List.of(), List.of(), null);
        assertThat(none.status()).isEqualTo("NOT_ESTIMABLE");
        assertThat(codes(none)).contains("NO_WALK");
    }

    @Test
    void fewObservationsGiveALowConfidenceEstimateNeverAnEstimatedOne() {
        Scene truth = room(rect(0, 0, 8, 6));
        List<TrajectorySample> walk = lap(1.0, 2, 2, 6, 2, 6, 4);
        List<SurfaceObservation> all = new SyntheticRangeSensor(truth, 8).sweep(walk, 1, 0, 90, null);
        Estimate e = estimator.estimate(walk, all.subList(0, Math.min(6, all.size())), null);
        assertThat(e.status()).isIn("LOW_CONFIDENCE", "NOT_ESTIMABLE");
        if (e.area() != null) {
            assertThat(codes(e)).contains("SPARSE_SURFACE_OBSERVATIONS");
        }
    }

    @Test
    void unusableObservationsAreDiscardedAndCounted() {
        Scene truth = room(rect(0, 0, 8, 6));
        List<TrajectorySample> walk = lap(1.0, 2, 2, 6, 2, 6, 4, 2, 4, 2, 2.5);
        List<SurfaceObservation> obs = new ArrayList<>(new SyntheticRangeSensor(truth, 8).sweep(walk, 7, 6, 40, null));
        obs.add(new SurfaceObservation(2, 2, Double.NaN, 3, "WALL", null));
        obs.add(new SurfaceObservation(2, 2, 40, 2, "WALL", 180.0)); // 38 m: beyond any trustworthy hit test
        obs.add(new SurfaceObservation(2, 2, 3, 3, "CEILING", null));
        Estimate e = estimator.estimate(walk, obs, null);
        assertThat(e.evidence().rejectedObservations()).isEqualTo(3);
        assertThat(codes(e)).contains("OBSERVATIONS_REJECTED");
        assertThat(e.area().maxX()).isLessThan(8.5);
    }

    @Test
    void aMeasuredOutlineIsCheckedAgainstTheEvidenceNotChanged() {
        Scene truth = room(rect(0, 0, 8, 6));
        List<TrajectorySample> walk = lap(1.0, 2, 2, 6, 2, 6, 4, 2, 4, 2, 2.5);
        List<SurfaceObservation> obs = new SyntheticRangeSensor(truth, 8).sweep(walk, 7, 6, 40, null);

        Estimate good = estimator.estimate(walk, obs, rect(0, 0, 8, 6));
        assertThat(good.status()).isEqualTo("MEASURED");
        assertThat(good.basis()).isEqualTo("OPERATOR_MEASUREMENT");
        assertThat(good.area()).isEqualTo(rect(0, 0, 8, 6));
        assertThat(good.confidence()).isGreaterThan(0.8);
        assertThat(codes(good)).doesNotContain("OUTLINE_CONTRADICTED_BY_WALK", "SURFACES_OUTSIDE_OUTLINE");

        // The operator typed 5 x 6 instead of 8 x 6: the walk and the observed east wall contradict it.
        Estimate wrong = estimator.estimate(walk, obs, rect(0, 0, 5, 6));
        assertThat(wrong.status()).isEqualTo("MEASURED");
        assertThat(codes(wrong)).contains("OUTLINE_CONTRADICTED_BY_WALK", "SURFACES_OUTSIDE_OUTLINE");
        assertThat(wrong.confidence()).isZero();

        Estimate unverified = estimator.estimate(walk, List.of(), rect(0, 0, 8, 6));
        assertThat(codes(unverified)).contains("OUTLINE_UNVERIFIED");
        assertThat(unverified.confidence()).isZero();
    }

    @Test
    void aTrackingLossSplitsTheWalkAndNothingIsAssumedAcrossIt() {
        Scene truth = room(rect(0, 0, 8, 6));
        List<TrajectorySample> a = lap(1.0, 1.5, 1.5, 3, 1.5);
        List<TrajectorySample> walk = new ArrayList<>(a);
        double t0 = a.get(a.size() - 1).t() + 5;
        for (TrajectorySample s : lap(1.0, 6.5, 4.5, 5, 4.5)) {
            walk.add(new TrajectorySample(t0 + s.t(), s.x(), s.y(), null, 1));
        }
        Estimate e = estimator.estimate(walk, new SyntheticRangeSensor(truth, 8).sweep(walk, 7, 6, 40, null), null);
        assertThat(e.evidence().walkSegments()).isEqualTo(2);
        assertThat(e.evidence().walkedMeters()).as("the jump across the loss is not walked distance").isCloseTo(3.0, org.assertj.core.api.Assertions.within(0.6));
        assertThat(codes(e)).contains("TRACKING_INTERRUPTED");
    }

    @Test
    void theEstimateIsDeterministic() {
        Polygon l = new Polygon(List.of(new Point(0, 0), new Point(9, 0), new Point(9, 4), new Point(4, 4), new Point(4, 8), new Point(0, 8)));
        List<TrajectorySample> walk = lap(1.0, 2, 6.5, 2, 2, 7.5, 2);
        List<SurfaceObservation> obs = new SyntheticRangeSensor(room(l), 8).sweep(walk, 7, 6, 40, null);
        assertThat(estimator.estimate(walk, obs, null)).isEqualTo(estimator.estimate(walk, obs, null));
    }
}
