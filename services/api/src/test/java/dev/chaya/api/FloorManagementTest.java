package dev.chaya.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;

/**
 * Floor management, recorded elevations, connection updates and the venue consistency report, through the real HTTP API
 * against real PostgreSQL. Every elevation, length and landing position below is a hand-made fixture value that exercises
 * a rule; none is a measurement of a building.
 */
class FloorManagementTest extends ApiTest {

    @Autowired ObjectMapper mapper;

    private record V(UUID org, UUID venue, String manager, String operator) {}

    private V venue() {
        UUID org = fx.organization();
        UUID venue = fx.venue(org);
        return new V(org, venue, TestJwt.user(org, "venue-manager").venues(venue).token(), TestJwt.user(org, "operator").venues(venue).token());
    }

    private String floors(V v) {
        return "/api/v1/venues/" + v.venue() + "/floors";
    }

    private String connections(V v) {
        return "/api/v1/venues/" + v.venue() + "/floor-connections";
    }

    private JsonNode json(org.springframework.test.web.servlet.ResultActions r) throws Exception {
        return mapper.readTree(r.andReturn().getResponse().getContentAsString());
    }

    private UUID floor(V v, int level, String name) throws Exception {
        return UUID.fromString(json(post(floors(v), v.manager(), "{\"level\":" + level + ",\"name\":\"" + name + "\"}")
            .andExpect(status().isCreated())).get("id").asText());
    }

    private org.springframework.test.web.servlet.ResultActions put(String url, String bearer, String body) throws Exception {
        return call(HttpMethod.PUT, url, bearer, body);
    }

    private org.springframework.test.web.servlet.ResultActions elevation(V v, UUID floor, String body) throws Exception {
        return put(floors(v) + "/" + floor + "/elevation", v.manager(), body);
    }

    /** A POI in the floor's current frame (as PoiService writes one). */
    private UUID poi(V v, UUID floor, double x, double y, double z) {
        UUID id = jdbc.sql("INSERT INTO poi (organization_id, venue_id, floor_id) VALUES (:o, :v, :f) RETURNING id")
            .param("o", v.org()).param("v", v.venue()).param("f", floor).query(UUID.class).single();
        jdbc.sql("""
                INSERT INTO poi_version (organization_id, venue_id, poi_id, version_number, label, category, tags, x, y, z,
                    coordinate_frame_id, scan_version_id, created_by)
                VALUES (:o, :v, :p, 1, 'landing', null, '{}', :x, :y, :z, (SELECT current_coordinate_frame_id FROM floor WHERE id = :f),
                    (SELECT current_scan_version_id FROM floor WHERE id = :f), 'test')""")
            .param("o", v.org()).param("v", v.venue()).param("p", id).param("f", floor).param("x", x).param("y", y).param("z", z).update();
        return id;
    }

    private String connection(String type, UUID fromFloor, UUID fromPoi, UUID toFloor, UUID toPoi, Double length, Double slope, Double clearance) {
        return "{\"connectorType\":\"" + type + "\",\"fromFloorId\":\"" + fromFloor + "\",\"fromPoiId\":\"" + fromPoi + "\",\"toFloorId\":\""
            + toFloor + "\",\"toPoiId\":\"" + toPoi + "\",\"bidirectional\":true,\"lengthM\":" + length + ",\"maxSlopeDeg\":" + slope
            + ",\"minClearanceM\":" + clearance + "}";
    }

    @Test
    void managersCreateRenameAndRelevelFloorsOperatorsCannotAndLevelsStayUnique() throws Exception {
        V v = venue();
        UUID ground = floor(v, 0, "Ground");
        post(floors(v), v.manager(), "{\"level\":0,\"name\":\"Again\"}").andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("FLOOR_LEVEL_TAKEN"));
        post(floors(v), v.operator(), "{\"level\":5,\"name\":\"x\"}").andExpect(status().isForbidden());
        put(floors(v) + "/" + ground, v.manager(), "{\"level\":-1,\"name\":\"Lower ground\"}").andExpect(status().isOk())
            .andExpect(jsonPath("$.level").value(-1)).andExpect(jsonPath("$.name").value("Lower ground"));
        JsonNode list = json(get(floors(v), v.operator()).andExpect(status().isOk()));
        assertThat(list.get(0).get("elevation").isNull()).as("no elevation is invented").isTrue();
    }

    @Test
    void anElevationIsRecordedWithItsSourceAndRefusedWhenItContradictsTheLevelsOrAConnector() throws Exception {
        V v = venue();
        UUID ground = floor(v, 0, "Ground");
        UUID first = floor(v, 1, "First");
        elevation(v, ground, "{\"elevationM\":0.0,\"source\":\"SURVEYED\",\"reference\":\"ground floor FFL\"}").andExpect(status().isOk());
        JsonNode f1 = json(elevation(v, first, "{\"elevationM\":4.2,\"source\":\"MEASURED_ON_SITE\"}").andExpect(status().isOk()));
        assertThat(f1.get("elevation").get("source").asText()).isEqualTo("MEASURED_ON_SITE");
        assertThat(f1.get("elevation").get("recordedBy").asText()).isNotBlank();

        elevation(v, first, "{\"elevationM\":4.2,\"source\":\"GUESSED\"}").andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_ELEVATION"));
        elevation(v, first, "{\"elevationM\":-2.0,\"source\":\"SURVEYED\"}").andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("FLOOR_ELEVATION_INCONSISTENT"));
        // Swapping the levels would put level 0 above level 1.
        put(floors(v) + "/" + ground, v.manager(), "{\"level\":2,\"name\":\"Ground\"}").andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("FLOOR_ELEVATION_INCONSISTENT"));

        // A stair registered too short for the recorded rise is refused; one long enough is accepted.
        UUID a = poi(v, ground, 1, 1, 0);
        UUID b = poi(v, first, 1, 1, 0);
        post(connections(v), v.manager(), connection("STAIRS", ground, a, first, b, 3.0, 30.0, 1.2)).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("CONNECTION_GEOMETRY_INCONSISTENT"));
        post(connections(v), v.manager(), connection("STAIRS", ground, a, first, b, 7.5, 34.0, 1.2)).andExpect(status().isCreated());
        // ...and an elevation that would make that stair too short is refused afterwards.
        elevation(v, first, "{\"elevationM\":9.0,\"source\":\"SURVEYED\"}").andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("FLOOR_ELEVATION_INCONSISTENT"));
        // Withdrawn: the elevation is unknown again.
        call(HttpMethod.DELETE, floors(v) + "/" + first + "/elevation", v.manager(), null).andExpect(status().isOk())
            .andExpect(jsonPath("$.elevation").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void correctingAConnectionWithdrawsItsAccessibilityVerificationOnlyWhenSomethingItReliesOnChanged() throws Exception {
        V v = venue();
        UUID ground = floor(v, 0, "Ground");
        UUID first = floor(v, 1, "First");
        UUID a = poi(v, ground, 1, 1, 0);
        UUID b = poi(v, first, 1, 1, 0);
        String id = json(post(connections(v), v.manager(), connection("ELEVATOR", ground, a, first, b, null, null, 1.1))
            .andExpect(status().isCreated())).get("id").asText();
        put(connections(v) + "/" + id + "/accessibility-verification", v.manager(), "{\"verified\":true}").andExpect(status().isOk());

        JsonNode same = json(put(connections(v) + "/" + id, v.manager(), connection("ELEVATOR", ground, a, first, b, null, null, 1.1))
            .andExpect(status().isOk()));
        assertThat(same.get("accessibilityVerifiedAt").isNull()).as("an identical registration keeps the verification").isFalse();
        JsonNode narrower = json(put(connections(v) + "/" + id, v.manager(), connection("ELEVATOR", ground, a, first, b, null, null, 0.95))
            .andExpect(status().isOk()));
        assertThat(narrower.get("minClearanceM").asDouble()).isEqualTo(0.95);
        assertThat(narrower.get("accessibilityVerifiedAt").isNull()).as("a changed width must be verified again").isTrue();
        put(connections(v) + "/" + id, v.operator(), connection("ELEVATOR", ground, a, first, b, null, null, 1.1)).andExpect(status().isForbidden());
    }

    @Test
    void aFloorNothingReliesOnCanBeDeletedAndItsLevelReusedButAFloorInUseCannot() throws Exception {
        V v = venue();
        UUID ground = floor(v, 0, "Ground");
        UUID first = floor(v, 1, "First");
        UUID spare = floor(v, 2, "Spare");
        post(connections(v), v.manager(), connection("ELEVATOR", ground, poi(v, ground, 0, 0, 0), first, poi(v, first, 0, 0, 0), null, null, 1.1))
            .andExpect(status().isCreated());
        call(HttpMethod.DELETE, floors(v) + "/" + first, v.manager(), null).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("FLOOR_IN_USE"));
        call(HttpMethod.DELETE, floors(v) + "/" + spare, v.manager(), null).andExpect(status().isNoContent());
        assertThat(json(get(floors(v), v.operator())).findValuesAsText("name")).containsExactly("Ground", "First");
        floor(v, 2, "New level 2");
    }

    @Test
    void theConsistencyReportComparesFloorsOnlyInASharedSurveyedDatumAndFlagsContradictions() throws Exception {
        V v = venue();
        UUID ground = floor(v, 0, "Ground");
        UUID first = floor(v, 1, "First");
        UUID local = floor(v, 2, "Local frame");
        fx.calibratedFloor(v.org(), v.venue(), ground, "VENUE_CONTROL_POINTS");
        fx.calibratedFloor(v.org(), v.venue(), first, "VENUE_CONTROL_POINTS");
        fx.calibratedFloor(v.org(), v.venue(), local, "FLOOR_LOCAL");
        elevation(v, ground, "{\"elevationM\":0.0,\"source\":\"SURVEYED\"}").andExpect(status().isOk());
        elevation(v, first, "{\"elevationM\":4.2,\"source\":\"SURVEYED\"}").andExpect(status().isOk());

        // An elevator whose landings are above each other, 4.2 m apart: consistent with the survey and the elevations.
        UUID g = poi(v, ground, 10, 5, 0.0);
        UUID f = poi(v, first, 10.3, 5.1, 4.2);
        post(connections(v), v.manager(), connection("ELEVATOR", ground, g, first, f, null, null, 1.1)).andExpect(status().isCreated());
        // Stairs to a floor with an unrelated local frame: nothing to compare.
        post(connections(v), v.manager(), connection("STAIRS", first, poi(v, first, 2, 2, 4.2), local, poi(v, local, 0, 0, 0), 7.0, 33.0, 1.2))
            .andExpect(status().isCreated());
        JsonNode report = json(get(floors(v) + "/consistency", v.operator()).andExpect(status().isOk()));
        assertThat(report.get("consistent").asBoolean()).isTrue();
        assertThat(report.get("pairs").findValuesAsText("alignment")).containsExactly("CONSISTENT", "NOT_COMPARABLE");
        assertThat(report.get("findings").findValuesAsText("detail")).anySatisfy(d -> assertThat(d).contains("4.20 m vertically"));

        // A second elevator whose upper landing is 15 m away in the shared datum, and 1 m lower than the elevations say.
        UUID g2 = poi(v, ground, 30, 5, 0.0);
        UUID f2 = poi(v, first, 45, 5, 3.2);
        post(connections(v), v.manager(), connection("ELEVATOR", ground, g2, first, f2, null, null, 1.1)).andExpect(status().isCreated());
        JsonNode bad = json(get(floors(v) + "/consistency", v.manager()).andExpect(status().isOk()));
        assertThat(bad.get("consistent").asBoolean()).isFalse();
        assertThat(bad.get("pairs").findValuesAsText("alignment")).contains("INCONSISTENT");
        assertThat(bad.get("findings").findValuesAsText("code")).contains("LANDINGS_NOT_VERTICALLY_ALIGNED", "ELEVATION_DISAGREES_WITH_SURVEY");
    }
}
