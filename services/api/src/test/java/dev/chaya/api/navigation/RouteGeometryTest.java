package dev.chaya.api.navigation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import org.junit.jupiter.api.Test;

/** RouteService's pure geometry: the segment-versus-obstacle test and the metric polyline length. */
class RouteGeometryTest {

    private static boolean crosses(double ax, double ay, double bx, double by) {
        // a 0.4 m square obstacle centred on (2, 0)
        return RouteService.segmentIntersectsBox(new double[]{ax, ay, 0}, new double[]{bx, by, 0}, 1.8, -0.2, 2.2, 0.2);
    }

    @Test
    void aSegmentPassingThroughTheBoxIsBlockedEvenWithBothEndpointsOutside() {
        assertThat(crosses(0, 0, 4, 0)).isTrue();
        assertThat(crosses(2, -3, 2, 3)).isTrue();
        assertThat(crosses(0, -2, 4, 2)).as("diagonally through the centre").isTrue();
    }

    @Test
    void aSegmentPassingBesideOrShortOfTheBoxIsNotBlocked() {
        assertThat(crosses(0, 0.5, 4, 0.5)).isFalse();
        assertThat(crosses(0, 0, 1.7, 0)).isFalse();
        assertThat(crosses(0, -2, 4, 3)).as("diagonal that misses the corner").isFalse();
    }

    @Test
    void touchingTheBoundaryCountsAsBlocked() {
        assertThat(crosses(0, 0.2, 4, 0.2)).isTrue();
        assertThat(crosses(0, 0, 1.8, 0)).isTrue();
    }

    @Test
    void aDegenerateSegmentIsAPointInBoxTest() {
        assertThat(crosses(2, 0, 2, 0)).isTrue();
        assertThat(crosses(3, 0, 3, 0)).isFalse();
    }

    @Test
    void theObstacleIsAFullHeightFootprint() {
        assertThat(RouteService.segmentIntersectsBox(new double[]{0, 0, 5}, new double[]{4, 0, 5}, 1.8, -0.2, 2.2, 0.2)).isTrue();
    }

    @Test
    void polylineLengthIsThreeDimensionalMetres() {
        assertThat(RouteService.polylineLength(List.of(new double[]{0, 0, 0}, new double[]{3, 4, 0}, new double[]{3, 4, 2})))
            .isCloseTo(7.0, within(1e-12));
        assertThat(RouteService.polylineLength(List.of(new double[]{0, 0, 0}, new double[]{4, 0, 3}))).isCloseTo(5.0, within(1e-12));
    }
}
