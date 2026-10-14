package dev.chaya.api.venue;

import static org.assertj.core.api.Assertions.assertThat;

import dev.chaya.api.venue.FloorGeometry.FloorFacts;
import dev.chaya.api.venue.FloorGeometry.Finding;
import dev.chaya.api.venue.FloorGeometry.Severity;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The consistency rules between recorded floor elevations, connector measurements and surveyed landing positions. The
 * numbers are hand-made cases of each rule, not measurements of a building. */
class FloorGeometryTest {

    private static List<String> codes(List<Finding> f) {
        return f.stream().map(x -> x.code() + ":" + x.severity()).toList();
    }

    @Test
    void levelsAndRecordedElevationsMustAgreeInOrder() {
        assertThat(FloorGeometry.elevationOrder(List.of(new FloorFacts("g", 0, 0.0), new FloorFacts("1", 1, 4.2),
            new FloorFacts("2", 2, null)))).isEmpty();
        assertThat(codes(FloorGeometry.elevationOrder(List.of(new FloorFacts("g", 0, 0.0), new FloorFacts("1", 1, -3.0)))))
            .containsExactly("FLOOR_ELEVATION_INCONSISTENT:ERROR");
        assertThat(FloorGeometry.elevationOrder(List.of(new FloorFacts("g", 0, 4.2), new FloorFacts("1", 1, 4.2))))
            .as("two levels at one elevation").hasSize(1);
    }

    @Test
    void aWalkedConnectorMustBeLongEnoughForItsRiseAndARampNoGentlerThanItsAverage() {
        assertThat(FloorGeometry.connector("STAIRS", 7.0, 32.0, 0.0, 3.5)).isEmpty();
        assertThat(codes(FloorGeometry.connector("STAIRS", 3.0, null, 0.0, 4.2))).containsExactly("CONNECTOR_SHORTER_THAN_RISE:ERROR");
        assertThat(codes(FloorGeometry.connector("STAIRS", 4.0, null, 0.0, 3.6))).containsExactly("STAIR_GEOMETRY_IMPLAUSIBLE:WARNING");
        // A 12 m ramp rising 1 m averages 4.8 degrees: a registered maximum of 3 degrees cannot be right.
        assertThat(codes(FloorGeometry.connector("RAMP", 12.0, 3.0, 0.0, 1.0))).containsExactly("RAMP_SLOPE_INCONSISTENT:ERROR");
        assertThat(FloorGeometry.connector("RAMP", 12.0, 5.0, 0.0, 1.0)).isEmpty();
        assertThat(FloorGeometry.connector("ELEVATOR", null, null, 0.0, 40.0)).as("an elevator is not walked").isEmpty();
        assertThat(FloorGeometry.connector("STAIRS", 3.0, null, null, 4.2)).as("unknown elevation: nothing to compare").isEmpty();
    }

    @Test
    void landingsInASharedSurveyDatumAreComparedWithEachOtherAndTheRecordedElevations() {
        double[] ground = {10, 5, 0.0};
        List<Finding> lift = FloorGeometry.landingsInSharedDatum("ELEVATOR", null, ground, new double[]{10.4, 5.2, 4.2}, 0.0, 4.2);
        assertThat(codes(lift)).containsExactly("LANDINGS_MEASURED:OK");
        assertThat(lift.get(0).detail()).contains("4.20 m vertically");
        assertThat(codes(FloorGeometry.landingsInSharedDatum("ELEVATOR", null, ground, new double[]{20, 5, 4.2}, 0.0, 4.2)))
            .contains("LANDINGS_NOT_VERTICALLY_ALIGNED:ERROR");
        assertThat(codes(FloorGeometry.landingsInSharedDatum("ELEVATOR", null, ground, new double[]{10, 5, 4.2}, 0.0, 3.0)))
            .contains("ELEVATION_DISAGREES_WITH_SURVEY:ERROR");
        assertThat(codes(FloorGeometry.landingsInSharedDatum("STAIRS", 6.0, ground, new double[]{20, 5, 4.2}, null, null)))
            .contains("LANDINGS_FARTHER_THAN_WALKED_LENGTH:ERROR");
        assertThat(FloorGeometry.hasErrors(FloorGeometry.landingsInSharedDatum("STAIRS", 8.0, ground, new double[]{16, 5, 4.2}, 0.0, 4.2)))
            .isFalse();
        assertThat(lift.stream().allMatch(f -> f.severity() == Severity.OK)).isTrue();
    }
}
