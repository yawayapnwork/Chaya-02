package dev.chaya.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/** The API authorization matrix (see docs/security.md), one row per assertion. */
class RoleAuthorizationTest extends ApiTest {

    private String tokenFor(UUID org, UUID venue, String role) {
        return TestJwt.user(org, role).venues(venue).token();
    }

    @Test
    void onlyAdminCreatesVenues() throws Exception {
        UUID org = fx.organization();
        UUID venue = fx.venue(org);
        String body = "{\"slug\":\"new-venue\",\"name\":\"New Venue\"}";
        for (String role : new String[] {"venue-manager", "operator", "viewer"}) {
            post("/api/v1/venues", tokenFor(org, venue, role), body).andExpect(status().isForbidden());
        }
        post("/api/v1/venues", TestJwt.user(org, "admin").token(), body)
            .andExpect(status().isOk()).andExpect(jsonPath("$.organizationId").value(org.toString()));
        assertThat(auditCount(org, "venue.create")).isEqualTo(1);
    }

    @Test
    void tokenWithoutAnyKnownRoleIsForbiddenEverywhere() throws Exception {
        UUID org = fx.organization();
        UUID venue = fx.venue(org);
        String t = TestJwt.user(org, "some-unrelated-role").venues(venue).token();
        get("/api/v1/venues", t).andExpect(status().isForbidden());
        get("/api/v1/venues/" + venue, t).andExpect(status().isForbidden());
    }

    @Test
    void venueUpdateIsForAdminAndManagerOnly() throws Exception {
        UUID org = fx.organization();
        UUID venue = fx.venue(org);
        String body = "{\"name\":\"Renamed\"}";
        for (String role : new String[] {"operator", "viewer"}) {
            call(HttpMethod.PATCH, "/api/v1/venues/" + venue, tokenFor(org, venue, role), body)
                .andExpect(status().isForbidden());
        }
        call(HttpMethod.PATCH, "/api/v1/venues/" + venue, tokenFor(org, venue, "venue-manager"), body)
            .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Renamed"));
        assertThat(auditCount(org, "venue.update")).isEqualTo(1);
    }

    @Test
    void scanCreationExcludesViewers() throws Exception {
        UUID org = fx.organization();
        UUID venue = fx.venue(org);
        String url = "/api/v1/venues/" + venue + "/scans";
        post(url, tokenFor(org, venue, "viewer"), null).andExpect(status().isForbidden());
        for (String role : new String[] {"operator", "venue-manager"}) {
            post(url, tokenFor(org, venue, role), null).andExpect(status().isCreated());
        }
        assertThat(auditCount(org, "scan.create")).isEqualTo(2);
    }

    @Test
    void jobControlIsAuditedAndClosedToViewers() throws Exception {
        UUID org = fx.organization();
        UUID venue = fx.venue(org);
        String op = tokenFor(org, venue, "operator");
        String viewer = tokenFor(org, venue, "viewer");

        String scanJson = post("/api/v1/venues/" + venue + "/scans", op, null).andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        String scanId = scanJson.replaceAll(".*\"scanId\":\"([^\"]+)\".*", "$1");
        String jobsUrl = "/api/v1/venues/" + venue + "/scans/" + scanId + "/jobs";

        post(jobsUrl, viewer, "{\"stage\":\"MEDIA_FILTER\"}").andExpect(status().isForbidden());
        String jobJson = post(jobsUrl, op, "{\"stage\":\"MEDIA_FILTER\"}").andExpect(status().isAccepted())
            .andReturn().getResponse().getContentAsString();
        String jobId = jobJson.replaceAll(".*\"jobId\":\"([^\"]+)\".*", "$1");

        post("/api/v1/venues/" + venue + "/jobs/" + jobId + "/cancel", viewer, null).andExpect(status().isForbidden());
        post("/api/v1/venues/" + venue + "/jobs/" + jobId + "/cancel", op, null).andExpect(status().isOk());
        // A cancelled job is terminal: retry is a conflict, not a silent success.
        post("/api/v1/venues/" + venue + "/jobs/" + jobId + "/retry", op, null).andExpect(status().isConflict());

        assertThat(auditCount(org, "job.enqueue")).isEqualTo(1);
        assertThat(auditCount(org, "job.cancel")).isEqualTo(1);
        assertThat(auditCount(org, "job.retry")).isZero();
    }

    @Test
    void poiWritesAreForAdminAndManagerAndAreAudited() throws Exception {
        UUID org = fx.organization();
        UUID venue = fx.venue(org);
        String url = "/api/v1/venues/" + venue + "/pois";

        post(url, tokenFor(org, venue, "operator"), POI_JSON).andExpect(status().isForbidden());
        post(url, tokenFor(org, venue, "viewer"), POI_JSON).andExpect(status().isForbidden());

        String mgr = tokenFor(org, venue, "venue-manager");
        String created = post(url, mgr, POI_JSON).andExpect(status().isCreated())
            .andExpect(jsonPath("$.version").value(1)).andReturn().getResponse().getContentAsString();
        String poiId = created.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

        call(HttpMethod.PUT, url + "/" + poiId, mgr, POI_JSON.replace("Exit", "Main Exit"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2))
            .andExpect(jsonPath("$.label").value("Main Exit"));
        // Version 1 is preserved as history.
        assertThat(jdbc.sql("SELECT count(*) FROM poi_version WHERE poi_id = :p").param("p", UUID.fromString(poiId))
            .query(Integer.class).single()).isEqualTo(2);

        call(HttpMethod.DELETE, url + "/" + poiId, mgr, null).andExpect(status().isNoContent());
        get(url + "/" + poiId, mgr).andExpect(status().isNotFound());

        assertThat(auditCount(org, "poi.create")).isEqualTo(1);
        assertThat(auditCount(org, "poi.update")).isEqualTo(1);
        assertThat(auditCount(org, "poi.delete")).isEqualTo(1);
    }

    @Test
    void viewersCanReadButNotWrite() throws Exception {
        UUID org = fx.organization();
        UUID venue = fx.venue(org);
        String viewer = tokenFor(org, venue, "viewer");
        get("/api/v1/venues/" + venue, viewer).andExpect(status().isOk());
        get("/api/v1/venues/" + venue + "/pois", viewer).andExpect(status().isOk());
        post("/api/v1/venues/" + venue + "/pois", viewer, POI_JSON).andExpect(status().isForbidden());
        post("/api/v1/venues/" + venue + "/public-links", viewer, "{\"ttl\":\"PT1H\"}").andExpect(status().isForbidden());
        get("/api/v1/audit-log", viewer).andExpect(status().isForbidden());
    }

    @Test
    void auditLogIsAdminOnlyAndOrganizationScoped() throws Exception {
        UUID orgA = fx.organization();
        UUID orgB = fx.organization();
        UUID venueA = fx.venue(orgA);
        post("/api/v1/venues", TestJwt.user(orgB, "admin").token(), "{\"slug\":\"b-venue\",\"name\":\"B\"}").andExpect(status().isOk());

        get("/api/v1/audit-log", tokenFor(orgA, venueA, "venue-manager")).andExpect(status().isForbidden());
        String body = get("/api/v1/audit-log", TestJwt.user(orgA, "admin").token()).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("b-venue").doesNotContain("venue.create");
    }
}
