package dev.chaya.api.planning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import dev.chaya.api.planning.geometry.Geometry;
import dev.chaya.api.planning.geometry.Point;
import dev.chaya.api.planning.geometry.Polygon;
import dev.chaya.api.planning.geometry.Segment;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class GeometryAndTrajectoryTest {

    private final PlannerConfig cfg = PlannerConfig.defaults();

    // ---- geometry ---------------------------------------------------------------------------------------

    @Test
    void polygonAreaAndContainmentIncludingConcaveShapes() {
        assertThat(PlannerFixtures.rect(0, 0, 8, 6).area()).isCloseTo(48, within(1e-9));
        Polygon ell = new Polygon(List.of(new Point(0, 0), new Point(4, 0), new Point(4, 2), new Point(2, 2), new Point(2, 4), new Point(0, 4)));
        assertThat(ell.area()).isCloseTo(12, within(1e-9));
        assertThat(ell.contains(1, 3)).isTrue();
        assertThat(ell.contains(3, 1)).isTrue();
        assertThat(ell.contains(3, 3)).as("the notch of the L").isFalse();
        assertThat(ell.contains(-1, 1)).isFalse();
        assertThat(new Polygon(List.of(new Point(0, 0), new Point(1, 1), new Point(2, 2))).isValid()).as("collinear = zero area").isFalse();
        assertThat(new Polygon(List.of(new Point(0, 0), new Point(1, 0), new Point(Double.NaN, 1))).isValid()).isFalse();
    }

    @Test
    void segmentIntersectionAndDistance() {
        assertThat(Geometry.segmentsIntersect(PlannerFixtures.wall(0, 0, 4, 4), PlannerFixtures.wall(0, 4, 4, 0))).isTrue();
        assertThat(Geometry.segmentsIntersect(PlannerFixtures.wall(0, 0, 4, 0), PlannerFixtures.wall(0, 1, 4, 1))).as("parallel").isFalse();
        assertThat(Geometry.segmentsIntersect(PlannerFixtures.wall(0, 0, 2, 0), PlannerFixtures.wall(2, 0, 4, 3))).as("touching end points").isTrue();
        assertThat(Geometry.segmentsIntersect(PlannerFixtures.wall(0, 0, 3, 0), PlannerFixtures.wall(2, 0, 5, 0))).as("collinear overlap").isTrue();
        assertThat(Geometry.segmentsIntersect(PlannerFixtures.wall(0, 0, 1, 0), PlannerFixtures.wall(2, 0, 3, 0))).as("collinear disjoint").isFalse();
        assertThat(Geometry.distancePointSegment(2, 3, PlannerFixtures.wall(0, 0, 4, 0))).isCloseTo(3, within(1e-9));
        assertThat(Geometry.distancePointSegment(-3, 4, PlannerFixtures.wall(0, 0, 4, 0))).as("clamped to the end point").isCloseTo(5, within(1e-9));
    }

    @Test
    void anglesWrapAroundTheCircle() {
        assertThat(Geometry.angleDiff(Math.toRadians(179), Math.toRadians(-179))).isCloseTo(Math.toRadians(2), within(1e-9));
        assertThat(Geometry.wrapAngle(3 * Math.PI + 0.1)).isCloseTo(-Math.PI + 0.1, within(1e-9));
        assertThat(Geometry.angleDiff(0, Math.PI)).isCloseTo(Math.PI, within(1e-9));
    }

    // ---- trajectory normalisation (step 1) --------------------------------------------------------------------

    @Test
    void theFixtureLapIsAboutTenSeconds() {
        var lap = PlannerFixtures.lap(1.0, 1.5, 1.5, 6.5, 1.5, 6.5, 4.5, 4.5, 4.5);
        assertThat(lap.get(lap.size() - 1).t()).isCloseTo(10.0, within(0.51));
    }

    @Test
    void aStraightWalkIsResampledAtExactlyThePoseSpacingWithHeadingFromMotion() {
        var r = TrajectoryNormalizer.normalize(PlannerFixtures.lap(1.0, 0, 0, 5, 0), cfg);
        assertThat(r.lengthMeters()).isCloseTo(5, within(1e-6));
        assertThat(r.poses()).hasSize(21); // 0, 0.25, ..., 5.0
        for (int i = 0; i + 1 < r.poses().size(); i++) {
            assertThat(Math.hypot(r.poses().get(i + 1).x() - r.poses().get(i).x(), r.poses().get(i + 1).y() - r.poses().get(i).y()))
                .isCloseTo(0.25, within(1e-6));
        }
        assertThat(r.poses()).allSatisfy(p -> assertThat(p.yaw()).isCloseTo(0, within(1e-9)));
        assertThat(r.yawSource()).isEqualTo("derived-from-motion");
    }

    @Test
    void badSamplesAreDroppedAndCounted() {
        List<TrajectorySample> in = new ArrayList<>();
        in.add(new TrajectorySample(0, 0, 0, null));
        in.add(new TrajectorySample(0.5, Double.NaN, 0, null));        // non-finite
        in.add(new TrajectorySample(1.0, 1, 0, null));
        in.add(new TrajectorySample(1.5, 60, 0, null));                // 118 m/s: a tracking glitch
        in.add(new TrajectorySample(1.0, 1.02, 0, null));              // same time as a kept sample: does not advance
        in.add(new TrajectorySample(2.0, 1.05, 0, null));              // stationary jitter (5 cm)
        in.add(new TrajectorySample(3.0, 2, 0, null));
        var r = TrajectoryNormalizer.normalize(in, cfg);
        assertThat(r.droppedNonFinite()).isEqualTo(1);
        assertThat(r.droppedSpeedSpikes()).isEqualTo(1);
        assertThat(r.droppedOutOfOrder()).isEqualTo(1);
        assertThat(r.droppedStationary()).isEqualTo(1);
        assertThat(r.lengthMeters()).isCloseTo(2.0, within(1e-6));
        assertThat(r.poses().get(r.poses().size() - 1).x()).as("always ends where the lap ended").isCloseTo(2.0, within(1e-9));
    }

    @Test
    void samplesAreOrderedByTimeAndYawInterpolatesAlongTheShortArc() {
        List<TrajectorySample> in = List.of(
            new TrajectorySample(2.0, 2, 0, Math.toRadians(-170)),
            new TrajectorySample(0.0, 0, 0, Math.toRadians(170)));
        var r = TrajectoryNormalizer.normalize(in, cfg);
        assertThat(r.yawSource()).isEqualTo("observed");
        assertThat(r.poses().get(0).x()).isCloseTo(0, within(1e-9));
        double mid = r.poses().get(r.poses().size() / 2).yaw();
        assertThat(Geometry.angleDiff(mid, Math.PI)).as("halfway between 170 and -170 degrees is 180, not 0").isLessThan(Math.toRadians(3));
    }

    @Test
    void aSingleSampleYieldsASinglePoseAndAnEmptyLapIsRefused() {
        assertThat(TrajectoryNormalizer.normalize(List.of(new TrajectorySample(0, 1, 2, null)), cfg).poses()).hasSize(1);
        assertThat(TrajectoryNormalizer.normalize(List.of(), cfg).poses()).isEmpty();
        assertThatThrownBy(() -> new CapturePathPlanner().plan(PlannerFixtures.request(
            new Scene(List.of(PlannerFixtures.rect(0, 0, 4, 4)), List.of(), List.of(), List.of(), List.of()), List.of())))
            .isInstanceOf(PlanningException.class).extracting("code").isEqualTo("NO_USABLE_TRAJECTORY");
    }

    @Test
    void invalidConfigurationIsRejectedWithTheFieldName() {
        assertThatThrownBy(() -> PlannerConfig.defaults().toBuilder().fovDegrees(5).build())
            .isInstanceOf(PlanningException.class).hasMessageContaining("config.fovDegrees");
        assertThatThrownBy(() -> PlannerConfig.defaults().toBuilder().targetDistance(0).build()).hasMessageContaining("targetDistance");
        assertThatThrownBy(() -> PlannerConfig.defaults().toBuilder().redundancyPenalty(2).build()).hasMessageContaining("redundancyPenalty");
        assertThatThrownBy(() -> PlannerConfig.defaults().toBuilder().poseSpacing(0.01).build()).hasMessageContaining("poseSpacing");
    }
}
