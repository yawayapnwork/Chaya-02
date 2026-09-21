package dev.chaya.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

class TenantApiIsolationTest extends ApiTest {

    @Test
    void anotherOrganizationSeesNothingAndIsNotToldTheVenueExists() throws Exception {
        UUID orgA = fx.organization();
        UUID orgB = fx.organization();
        UUID venueA = fx.venue(orgA);
        String adminB = TestJwt.user(orgB, "admin").token();

        get("/api/v1/venues/" + venueA, adminB).andExpect(status().isNotFound());
        get("/api/v1/venues", adminB).andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
        get("/api/v1/venues/" + venueA + "/pois", adminB).andExpect(status().isNotFound());
        post("/api/v1/venues/" + venueA + "/scans", adminB, null).andExpect(status().isNotFound());
        post("/api/v1/venues/" + venueA + "/public-links", adminB, "{\"ttl\":\"PT1H\"}").andExpect(status().isNotFound());
        // A random id is indistinguishable from a foreign one.
        get("/api/v1/venues/" + UUID.randomUUID(), adminB).andExpect(status().isNotFound());

        // The refused attempts are audited in the caller's organization, not the victim's.
        assertThat(auditCount(orgB, "venue.access", "DENIED")).isGreaterThanOrEqualTo(4);
        assertThat(auditCount(orgA, "venue.access", "DENIED")).isZero();
    }

    @Test
    void writesAcrossOrganizationsAreRejectedAndChangeNothing() throws Exception {
        UUID orgA = fx.organization();
        UUID orgB = fx.organization();
        UUID venueA = fx.venue(orgA);
        String adminB = TestJwt.user(orgB, "admin").token();

        call(HttpMethod.PATCH, "/api/v1/venues/" + venueA, adminB, "{\"name\":\"Hijacked\"}")
            .andExpect(status().isNotFound());
        post("/api/v1/venues/" + venueA + "/pois", adminB, POI_JSON).andExpect(status().isNotFound());
        assertThat(jdbc.sql("SELECT name FROM venue WHERE id = :v").param("v", venueA).query(String.class).single())
            .isEqualTo("Test Venue");
        assertThat(jdbc.sql("SELECT count(*) FROM poi WHERE venue_id = :v").param("v", venueA)
            .query(Integer.class).single()).isZero();
    }

    @Test
    void venueRestrictedUserCannotReachOtherVenuesOfTheSameOrganization() throws Exception {
        UUID org = fx.organization();
        UUID venue1 = fx.venue(org);
        UUID venue2 = fx.venue(org);
        String op = TestJwt.user(org, "operator").venues(venue1).token();

        get("/api/v1/venues/" + venue1, op).andExpect(status().isOk());
        get("/api/v1/venues/" + venue2, op).andExpect(status().isNotFound());
        post("/api/v1/venues/" + venue2 + "/scans", op, null).andExpect(status().isNotFound());
        get("/api/v1/venues", op).andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].id").value(venue1.toString()));
    }

    @Test
    void multiVenueClaimGrantsExactlyThoseVenues() throws Exception {
        UUID org = fx.organization();
        UUID v1 = fx.venue(org);
        UUID v2 = fx.venue(org);
        UUID v3 = fx.venue(org);
        String t = TestJwt.user(org, "viewer").venues(v1, v2).token();
        get("/api/v1/venues/" + v1, t).andExpect(status().isOk());
        get("/api/v1/venues/" + v2, t).andExpect(status().isOk());
        get("/api/v1/venues/" + v3, t).andExpect(status().isNotFound());
    }

    @Test
    void nonAdminWithoutVenueClaimHasNoVenueAccess() throws Exception {
        UUID org = fx.organization();
        UUID venue = fx.venue(org);
        String t = TestJwt.user(org, "operator").token();
        get("/api/v1/venues", t).andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
        get("/api/v1/venues/" + venue, t).andExpect(status().isNotFound());
    }

    @Test
    void venueClaimOfAnotherOrganizationsVenueDoesNotBypassOrganizationCheck() throws Exception {
        UUID orgA = fx.organization();
        UUID orgB = fx.organization();
        UUID venueB = fx.venue(orgB);
        // Token says org A but names a venue of org B.
        String t = TestJwt.user(orgA, "operator").venues(venueB).token();
        get("/api/v1/venues/" + venueB, t).andExpect(status().isNotFound());
        get("/api/v1/venues", t).andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void poisAndJobsAreScopedToTheVenueInThePath() throws Exception {
        UUID org = fx.organization();
        UUID venue1 = fx.venue(org);
        UUID venue2 = fx.venue(org);
        String mgr = TestJwt.user(org, "venue-manager").venues(venue1, venue2).token();

        String created = post("/api/v1/venues/" + venue1 + "/pois", mgr, POI_JSON).andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        String poiId = created.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
        // Same organization and same caller, but the POI belongs to venue1.
        get("/api/v1/venues/" + venue2 + "/pois/" + poiId, mgr).andExpect(status().isNotFound());
        call(HttpMethod.DELETE, "/api/v1/venues/" + venue2 + "/pois/" + poiId, mgr, null).andExpect(status().isNotFound());

        String scan = post("/api/v1/venues/" + venue1 + "/scans", mgr, null).andReturn().getResponse().getContentAsString();
        String scanId = scan.replaceAll(".*\"scanId\":\"([^\"]+)\".*", "$1");
        post("/api/v1/venues/" + venue2 + "/scans/" + scanId + "/jobs", mgr, "{\"stage\":\"MEDIA_FILTER\"}")
            .andExpect(status().isNotFound());
    }
}
