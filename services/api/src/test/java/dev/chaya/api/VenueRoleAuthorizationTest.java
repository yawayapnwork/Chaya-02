package dev.chaya.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * The authorization matrix across tenants, venues and roles (docs/security.md, "API authorization matrix"), including
 * per-venue role grants (review S-8). Every refusal is checked to change nothing.
 *
 * <pre>
 *   org A: venue A1, venue A2          org B: venue B1
 * </pre>
 */
class VenueRoleAuthorizationTest extends ApiTest {

    private record World(UUID orgA, UUID a1, UUID a2, UUID orgB, UUID b1) {}

    private World world() {
        UUID orgA = fx.organization();
        UUID orgB = fx.organization();
        return new World(orgA, fx.venue(orgA), fx.venue(orgA), orgB, fx.venue(orgB));
    }

    private String venueName(UUID venue) {
        return jdbc.sql("SELECT name FROM venue WHERE id = :v").param("v", venue).query(String.class).single();
    }

    private org.springframework.test.web.servlet.ResultActions rename(UUID venue, String token, String name) throws Exception {
        return call(HttpMethod.PATCH, "/api/v1/venues/" + venue, token, "{\"name\":\"" + name + "\"}");
    }

    // ---- review S-8: a user who manages venue A and views venue B cannot edit B --------------------------------------

    @Test
    void aManagerOfOneVenueWhoOnlyViewsAnotherCannotEditTheOther() throws Exception {
        World w = world();
        String split = TestJwt.user(w.orgA()).grant(w.a1(), "venue-manager").grant(w.a2(), "viewer").token();

        rename(w.a1(), split, "Managed").andExpect(status().isOk());
        rename(w.a2(), split, "Hijacked").andExpect(status().isForbidden());
        assertThat(venueName(w.a2())).isEqualTo("Test Venue");
        post("/api/v1/venues/" + w.a2() + "/pois", split, POI_JSON).andExpect(status().isForbidden());
        post("/api/v1/venues/" + w.a2() + "/public-links", split, "{\"ttl\":\"PT1H\"}").andExpect(status().isForbidden());
        post("/api/v1/venues/" + w.a2() + "/scans", split, null).andExpect(status().isForbidden());
        assertThat(jdbc.sql("SELECT count(*) FROM poi WHERE venue_id = :v").param("v", w.a2()).query(Integer.class).single()).isZero();

        // Reading B is allowed; the services themselves see only B's roles.
        get("/api/v1/venues/" + w.a2(), split).andExpect(status().isOk());
        get("/api/v1/venues/" + w.a2() + "/ops/access", split).andExpect(status().isOk())
            .andExpect(jsonPath("$.roles").value(org.hamcrest.Matchers.contains("VIEWER")));
        get("/api/v1/venues/" + w.a1() + "/ops/access", split).andExpect(status().isOk())
            .andExpect(jsonPath("$.roles").value(org.hamcrest.Matchers.contains("VENUE_MANAGER")));
        // and the listing shows both venues it holds a role at, nothing else
        String venues = get("/api/v1/venues", split).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(venues).contains(w.a1().toString(), w.a2().toString()).doesNotContain(w.b1().toString());
    }

    @Test
    void aVenueGrantOverridesBroaderRealmRolesAtThatVenue() throws Exception {
        World w = world();
        // Realm role venue-manager applies at A1 (venue_id); the explicit grant makes the user a viewer at A2.
        String t = TestJwt.user(w.orgA(), "venue-manager").venues(w.a1()).grant(w.a2(), "viewer").token();
        rename(w.a1(), t, "Managed").andExpect(status().isOk());
        rename(w.a2(), t, "Hijacked").andExpect(status().isForbidden());
        assertThat(venueName(w.a2())).isEqualTo("Test Venue");
    }

    @Test
    void anOperatorGrantCanRunCapturesButNotEditContent() throws Exception {
        World w = world();
        String op = TestJwt.user(w.orgA()).grant(w.a1(), "operator").token();
        post("/api/v1/venues/" + w.a1() + "/scans", op, null).andExpect(status().isCreated());
        rename(w.a1(), op, "Renamed").andExpect(status().isForbidden());
        post("/api/v1/venues/" + w.a1() + "/pois", op, POI_JSON).andExpect(status().isForbidden());
        get("/api/v1/audit-log", op).andExpect(status().isForbidden());
    }

    @Test
    void aGrantGivesNoAccessToAnyOtherVenueOrOrganization() throws Exception {
        World w = world();
        String mgr = TestJwt.user(w.orgA()).grant(w.a1(), "venue-manager").token();
        get("/api/v1/venues/" + w.a2(), mgr).andExpect(status().isNotFound());
        rename(w.a2(), mgr, "Hijacked").andExpect(status().isNotFound());
        get("/api/v1/venues/" + w.b1(), mgr).andExpect(status().isNotFound());
        // A grant naming another organization's venue is useless: the token's org_id still bounds everything.
        String forged = TestJwt.user(w.orgA()).grant(w.b1(), "venue-manager").token();
        get("/api/v1/venues/" + w.b1(), forged).andExpect(status().isNotFound());
        rename(w.b1(), forged, "Hijacked").andExpect(status().isNotFound());
        assertThat(venueName(w.a2())).isEqualTo("Test Venue");
        assertThat(venueName(w.b1())).isEqualTo("Test Venue");
        // Venue creation and the organization audit log stay organization-admin only.
        post("/api/v1/venues", mgr, "{\"slug\":\"new-venue\",\"name\":\"New Venue\"}").andExpect(status().isForbidden());
        get("/api/v1/audit-log", mgr).andExpect(status().isForbidden());
    }

    @Test
    void malformedOrOverreachingGrantsInvalidateTheToken() throws Exception {
        World w = world();
        for (Object claim : List.of(w.a1() + ":admin", w.a1() + ":service", w.a1() + ":owner", "not-a-venue:viewer",
                w.a1().toString(), List.of(w.a1() + ":viewer", "garbage"))) {
            String t = TestJwt.user(w.orgA()).rawVenueRoles(claim).token();
            get("/api/v1/venues/" + w.a1(), t).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void routePlanningWithTheVenueInTheBodyIsScopedToo() throws Exception {
        World w = world();
        // A member of A1 holding no role there (venue_id without any realm role) may not plan routes in A1, even though a
        // grant elsewhere gives the token a role.
        String t = TestJwt.user(w.orgA()).venues(w.a1()).grant(w.a2(), "viewer").token();
        String body = "{\"venueId\":\"" + w.a1() + "\",\"floorId\":\"" + UUID.randomUUID() + "\",\"start\":[0,0,0],"
            + "\"destinationPoiId\":\"" + UUID.randomUUID() + "\"}";
        post("/api/v1/navigation/routes", t, body).andExpect(status().isForbidden());
        // and a non-member is refused as not found, as for every venue-scoped path
        String body2 = body.replace(w.a1().toString(), w.b1().toString());
        post("/api/v1/navigation/routes", t, body2).andExpect(status().isNotFound());
    }

    // ---- the full matrix: same-org / cross-org, same-venue / cross-venue, anonymous, public link ----------------------

    @Test
    void theTenantMatrix() throws Exception {
        World w = world();
        String adminA = TestJwt.user(w.orgA(), "admin").token();
        String adminB = TestJwt.user(w.orgB(), "admin").token();
        String legacyManagerA1 = TestJwt.user(w.orgA(), "venue-manager").venues(w.a1()).token();
        String operatorA1 = TestJwt.user(w.orgA(), "operator").venues(w.a1()).token();
        String viewerA1 = TestJwt.user(w.orgA(), "viewer").venues(w.a1()).token();

        // same organization, same venue
        get("/api/v1/venues/" + w.a1(), viewerA1).andExpect(status().isOk());
        rename(w.a1(), viewerA1, "x").andExpect(status().isForbidden());
        rename(w.a1(), operatorA1, "x").andExpect(status().isForbidden());
        rename(w.a1(), legacyManagerA1, "Managed").andExpect(status().isOk());
        rename(w.a2(), adminA, "Admin renamed").andExpect(status().isOk()); // admin: every venue of the organization

        // same organization, other venue: invisible (404). A role that may not edit at all is refused before any venue
        // lookup (403, the same for every venue id, existing or not), so neither answer reveals that the venue exists.
        for (String t : List.of(legacyManagerA1, operatorA1, viewerA1)) {
            get("/api/v1/venues/" + w.a2(), t).andExpect(status().isNotFound());
        }
        rename(w.a2(), legacyManagerA1, "x").andExpect(status().isNotFound());
        rename(w.a2(), operatorA1, "x").andExpect(status().isForbidden());
        rename(UUID.randomUUID(), operatorA1, "x").andExpect(status().isForbidden());

        // other organization: invisible, admins included
        get("/api/v1/venues/" + w.b1(), adminA).andExpect(status().isNotFound());
        rename(w.b1(), adminA, "x").andExpect(status().isNotFound());
        get("/api/v1/venues/" + w.a1(), adminB).andExpect(status().isNotFound());
        assertThat(get("/api/v1/venues", adminB).andReturn().getResponse().getContentAsString())
            .contains(w.b1().toString()).doesNotContain(w.a1().toString(), w.a2().toString());

        // anonymous: nothing but health and the link exchange
        get("/api/v1/venues", null).andExpect(status().isUnauthorized());
        get("/api/v1/venues/" + w.a1(), null).andExpect(status().isUnauthorized());
        rename(w.a1(), null, "x").andExpect(status().isUnauthorized());
        get("/api/v1/venues/" + w.a1() + "/floors", null).andExpect(status().isUnauthorized());
        get("/api/v1/health", null).andExpect(status().isOk());

        // public link for A1: that venue, read-only
        String link = post("/api/v1/venues/" + w.a1() + "/public-links", legacyManagerA1, "{\"label\":\"m\",\"ttl\":\"PT1H\"}")
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()
            .replaceAll(".*\"secret\":\"([^\"]+)\".*", "$1");
        String viewerToken = post("/api/v1/public/viewer-token", null, "{\"secret\":\"" + link + "\"}").andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString().replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");
        withViewerToken(HttpMethod.GET, "/api/v1/venues/" + w.a1(), viewerToken, null).andExpect(status().isOk());
        withViewerToken(HttpMethod.GET, "/api/v1/venues/" + w.a2(), viewerToken, null).andExpect(status().isNotFound());
        withViewerToken(HttpMethod.GET, "/api/v1/venues/" + w.b1(), viewerToken, null).andExpect(status().isNotFound());
        withViewerToken(HttpMethod.PATCH, "/api/v1/venues/" + w.a1(), viewerToken, "{\"name\":\"x\"}").andExpect(status().isForbidden());
        withViewerToken(HttpMethod.GET, "/api/v1/venues", viewerToken, null).andExpect(status().isForbidden());
        withViewerToken(HttpMethod.POST, "/api/v1/venues/" + w.a1() + "/scans", viewerToken, null).andExpect(status().isForbidden());

        assertThat(venueName(w.a1())).isEqualTo("Managed");
        assertThat(venueName(w.b1())).isEqualTo("Test Venue");
    }
}
