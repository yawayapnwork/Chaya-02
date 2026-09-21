package dev.chaya.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;

class PublicViewerLinkTest extends ApiTest {

    private record Setup(UUID org, UUID venue, UUID otherVenue, String manager, String secret, UUID linkId) {}

    private Setup setup() throws Exception {
        UUID org = fx.organization();
        UUID venue = fx.venue(org);
        UUID other = fx.venue(org);
        String mgr = TestJwt.user(org, "venue-manager").venues(venue, other).token();
        String json = post("/api/v1/venues/" + venue + "/public-links", mgr, "{\"label\":\"lobby\",\"ttl\":\"PT1H\"}")
            .andExpect(status().isCreated()).andExpect(jsonPath("$.secret").exists())
            .andReturn().getResponse().getContentAsString();
        String secret = json.replaceAll(".*\"secret\":\"([^\"]+)\".*", "$1");
        UUID id = UUID.fromString(json.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1"));
        return new Setup(org, venue, other, mgr, secret, id);
    }

    private String exchange(String secret) throws Exception {
        String json = post("/api/v1/public/viewer-token", null, "{\"secret\":\"" + secret + "\"}")
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");
    }

    @Test
    void secretsAreStoredOnlyAsHashesAndNeverAudited() throws Exception {
        Setup s = setup();
        String token = exchange(s.secret());
        assertThat(s.secret()).startsWith("chl_");
        assertThat(token).startsWith("cvt_");
        assertThat(jdbc.sql("SELECT count(*) FROM public_viewer_link WHERE secret_hash = :s").param("s", s.secret())
            .query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM public_viewer_token WHERE token_hash = :t").param("t", token)
            .query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM audit_log WHERE metadata::text LIKE :p OR action LIKE :p")
            .param("p", "%" + s.secret() + "%").query(Integer.class).single()).isZero();
        assertThat(auditCount(s.org(), "public_link.create")).isEqualTo(1);
        assertThat(auditCount(s.org(), "public_link.exchange")).isEqualTo(1);
    }

    @Test
    void viewerTokenReadsItsOwnVenueOnly() throws Exception {
        Setup s = setup();
        String token = exchange(s.secret());
        withViewerToken(HttpMethod.GET, "/api/v1/venues/" + s.venue(), token, null)
            .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(s.venue().toString()));
        withViewerToken(HttpMethod.GET, "/api/v1/venues/" + s.venue() + "/pois", token, null).andExpect(status().isOk());
        // Same organization, different venue: invisible.
        withViewerToken(HttpMethod.GET, "/api/v1/venues/" + s.otherVenue(), token, null).andExpect(status().isNotFound());
        withViewerToken(HttpMethod.GET, "/api/v1/venues/" + s.otherVenue() + "/pois", token, null).andExpect(status().isNotFound());
    }

    @Test
    void viewerTokenCannotWriteOrAdminister() throws Exception {
        Setup s = setup();
        String token = exchange(s.secret());
        String v = "/api/v1/venues/" + s.venue();
        withViewerToken(HttpMethod.POST, v + "/pois", token, POI_JSON).andExpect(status().isForbidden());
        withViewerToken(HttpMethod.POST, v + "/scans", token, null).andExpect(status().isForbidden());
        withViewerToken(HttpMethod.PATCH, v, token, "{\"name\":\"x\"}").andExpect(status().isForbidden());
        withViewerToken(HttpMethod.POST, v + "/public-links", token, "{\"ttl\":\"PT1H\"}").andExpect(status().isForbidden());
        withViewerToken(HttpMethod.GET, v + "/public-links", token, null).andExpect(status().isForbidden());
        withViewerToken(HttpMethod.GET, "/api/v1/venues", token, null).andExpect(status().isForbidden());
        withViewerToken(HttpMethod.POST, "/api/v1/venues", token, "{\"slug\":\"abc\",\"name\":\"n\"}").andExpect(status().isForbidden());
        withViewerToken(HttpMethod.GET, "/api/v1/audit-log", token, null).andExpect(status().isForbidden());
        withViewerToken(HttpMethod.POST, "/api/v1/internal/jobs/claim", token, "{\"stage\":\"MEDIA_FILTER\"}")
            .andExpect(status().isForbidden());
    }

    @Test
    void revokingALinkKillsItsTokensImmediately() throws Exception {
        Setup s = setup();
        String token = exchange(s.secret());
        String v = "/api/v1/venues/" + s.venue();
        withViewerToken(HttpMethod.GET, v, token, null).andExpect(status().isOk());

        call(HttpMethod.DELETE, v + "/public-links/" + s.linkId(), s.manager(), null).andExpect(status().isNoContent());

        withViewerToken(HttpMethod.GET, v, token, null).andExpect(status().isUnauthorized());
        post("/api/v1/public/viewer-token", null, "{\"secret\":\"" + s.secret() + "\"}").andExpect(status().isNotFound());
        assertThat(auditCount(s.org(), "public_link.revoke")).isEqualTo(1);
        // Revoking twice is an error, not a silent success.
        call(HttpMethod.DELETE, v + "/public-links/" + s.linkId(), s.manager(), null).andExpect(status().isNotFound());
    }

    @Test
    void expiredTokenAndExpiredLinkAreRejected() throws Exception {
        Setup s = setup();
        String token = exchange(s.secret());
        String v = "/api/v1/venues/" + s.venue();

        jdbc.sql("UPDATE public_viewer_token SET created_at = now() - interval '2 hours', expires_at = now() - interval '1 hour'").update();
        withViewerToken(HttpMethod.GET, v, token, null).andExpect(status().isUnauthorized());

        jdbc.sql("UPDATE public_viewer_link SET created_at = now() - interval '2 hours', expires_at = now() - interval '1 hour' WHERE id = :i")
            .param("i", s.linkId()).update();
        post("/api/v1/public/viewer-token", null, "{\"secret\":\"" + s.secret() + "\"}").andExpect(status().isNotFound());
    }

    @Test
    void tokenLifetimeNeverOutlivesTheLink() throws Exception {
        UUID org = fx.organization();
        UUID venue = fx.venue(org);
        String mgr = TestJwt.user(org, "admin").token();
        String json = post("/api/v1/venues/" + venue + "/public-links", mgr, "{\"ttl\":\"PT1M\"}")
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String secret = json.replaceAll(".*\"secret\":\"([^\"]+)\".*", "$1");
        String tokenJson = post("/api/v1/public/viewer-token", null, "{\"secret\":\"" + secret + "\"}")
            .andReturn().getResponse().getContentAsString();
        String linkExpiry = json.replaceAll(".*\"expiresAt\":\"([^\"]+)\".*", "$1");
        String tokenExpiry = tokenJson.replaceAll(".*\"expiresAt\":\"([^\"]+)\".*", "$1");
        assertThat(java.time.Instant.parse(tokenExpiry)).isBeforeOrEqualTo(java.time.Instant.parse(linkExpiry));
    }

    @Test
    void invalidInputsAreRejected() throws Exception {
        Setup s = setup();
        post("/api/v1/public/viewer-token", null, "{\"secret\":\"chl_wrong\"}").andExpect(status().isNotFound());
        post("/api/v1/public/viewer-token", null, "{}").andExpect(status().isBadRequest());
        withViewerToken(HttpMethod.GET, "/api/v1/venues/" + s.venue(), "cvt_bogus", null).andExpect(status().isUnauthorized());
        // The link secret itself is not an access token.
        withViewerToken(HttpMethod.GET, "/api/v1/venues/" + s.venue(), s.secret(), null).andExpect(status().isUnauthorized());
        // Too-long lifetime.
        post("/api/v1/venues/" + s.venue() + "/public-links", s.manager(), "{\"ttl\":\"P365D\"}").andExpect(status().isBadRequest());
        // Both credentials at once are refused.
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/venues/" + s.venue())
                .header("X-Chaya-Viewer-Token", "cvt_x").header(HttpHeaders.AUTHORIZATION, "Bearer " + s.manager()))
            .andExpect(status().isBadRequest());
    }

    @Test
    void onlyManagersOfTheVenueCanCreateLinks() throws Exception {
        Setup s = setup();
        UUID stranger = fx.organization();
        post("/api/v1/venues/" + s.venue() + "/public-links", TestJwt.user(stranger, "admin").token(), "{\"ttl\":\"PT1H\"}")
            .andExpect(status().isNotFound());
        // A manager restricted to a different venue cannot mint one for this venue.
        String restricted = TestJwt.user(s.org(), "venue-manager").venues(s.otherVenue()).token();
        post("/api/v1/venues/" + s.venue() + "/public-links", restricted, "{\"ttl\":\"PT1H\"}").andExpect(status().isNotFound());
    }
}
