package dev.chaya.api.rescan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Pure geometry, no database, no Spring context -- mirrors chaya_worker.region_splice's own
 * point-in-polygon/shoelace tests (tests/unit/test_region_splice.py) so both implementations are checked
 * against the same cases. */
class PolygonGeometryTest {

    private static final List<List<Double>> SQUARE = List.of(
        List.of(0.0, 0.0), List.of(0.0, 1.0), List.of(1.0, 1.0), List.of(1.0, 0.0));

    @Test
    void areaOfUnitSquareAndLargerRectangle() {
        assertThat(PolygonGeometry.area(SQUARE)).isCloseTo(1.0, within(1e-9));
        List<List<Double>> rectangle = List.of(List.of(0.0, 0.0), List.of(0.0, 2.0), List.of(5.0, 2.0), List.of(5.0, 0.0));
        assertThat(PolygonGeometry.area(rectangle)).isCloseTo(10.0, within(1e-9));
    }

    @Test
    void areaWorksWithWholeNumberCoordinatesParsedAsIntegers() {
        // Simulates a polygon round-tripped through a generic Map<String, Object> JSON parse, where
        // whole-number coordinates come back as Integer, not Double -- see PipelineService#regionGeometryOf.
        List<List<Integer>> wholeNumberSquare = List.of(List.of(0, 0), List.of(0, 4), List.of(4, 4), List.of(4, 0));
        assertThat(PolygonGeometry.area(wholeNumberSquare)).isCloseTo(16.0, within(1e-9));
    }

    @Test
    void pointInPolygonUnitSquare() {
        assertThat(PolygonGeometry.pointInPolygon(0.5, 0.5, SQUARE)).isTrue();
        assertThat(PolygonGeometry.pointInPolygon(1.5, 0.5, SQUARE)).isFalse();
        assertThat(PolygonGeometry.pointInPolygon(-0.1, 0.5, SQUARE)).isFalse();
        assertThat(PolygonGeometry.pointInPolygon(0.5, -0.1, SQUARE)).isFalse();
    }

    @Test
    void pointInPolygonWithANonConvexPolygon() {
        // An "L" shape: the notch at (1.5, 1.5) is outside the shape even though it is within the bounding box.
        List<List<Double>> lShape = List.of(
            List.of(0.0, 0.0), List.of(0.0, 2.0), List.of(1.0, 2.0), List.of(1.0, 1.0), List.of(2.0, 1.0), List.of(2.0, 0.0));
        assertThat(PolygonGeometry.pointInPolygon(0.5, 0.5, lShape)).isTrue();
        assertThat(PolygonGeometry.pointInPolygon(1.5, 1.5, lShape)).isFalse();
        assertThat(PolygonGeometry.pointInPolygon(1.5, 0.5, lShape)).isTrue();
    }
}
