package dev.chaya.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * Cross-tenant access to the resources a reconstruction produces, from a real run: reconstructions and their artifacts,
 * scan versions, uploads, search, routes, the operations dashboard, and public-viewer tokens. Three outsiders try each:
 * an administrator of another organization, a manager of another venue of the same organization, and a public-viewer
 * token issued for the other organization's venue. Every attempt is a 404 that reveals nothing (an unknown id answers the
 * same), including the id-swap form: a foreign resource id under the caller's own venue path.
 */
class CrossTenantResourceIsolationTest extends PipelineTestSupport {

    @Test
    void anotherTenantCannotReachAReconstructionOrAnythingItProduced() throws Exception {
        Started s = startRun();
        runThrough("PLANE_FITTING");
        succeed(claimExpecting("ARTIFACT_GENERATION"));
        UUID venueA = s.c().venue();
        UUID floorA = s.c().floor();
        UUID versionA = jdbc.sql("SELECT scan_version_id FROM pipeline_run WHERE id = :r").param("r", s.run()).query(UUID.class).single();
        // The owner can read it: the refusals below are about tenancy, not a broken fixture.
        get("/api/v1/venues/" + venueA + "/reconstructions/" + s.run(), s.c().operator()).andExpect(status().isOk());

        UUID orgB = fx.organization();
        UUID venueB = fx.venue(orgB);
        String adminB = TestJwt.user(orgB, "admin").token();
        UUID venueA2 = fx.venue(s.c().org());
        String managerA2 = TestJwt.user(s.c().org(), "venue-manager").venues(venueA2).token();
        String linkB = post("/api/v1/venues/" + venueB + "/public-links", TestJwt.user(orgB, "venue-manager").venues(venueB).token(),
                "{\"label\":\"b\",\"ttl\":\"PT1H\"}").andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()
            .replaceAll(".*\"secret\":\"([^\"]+)\".*", "$1");
        String viewerB = post("/api/v1/public/viewer-token", null, "{\"secret\":\"" + linkB + "\"}").andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString().replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");

        String a = "/api/v1/venues/" + venueA;
        List<String> paths = List.of(
            a + "/reconstructions/" + s.run(),
            a + "/reconstructions/" + s.run() + "/artifacts/KSPLAT",
            a + "/floors/" + floorA + "/reconstructions",
            a + "/floors/" + floorA + "/reconstructions/latest",
            a + "/floors/" + floorA + "/scan-versions",
            a + "/scan-versions/" + versionA + "/reconstruction",
            a + "/scan-versions/" + versionA + "/artifacts/KSPLAT",
            a + "/captures/" + s.capture(),
            a + "/captures/" + s.capture() + "/media",
            a + "/captures/" + s.capture() + "/processing",
            a + "/pois",
            a + "/search?q=exit",
            a + "/ops/overview",
            a + "/ops/jobs",
            a + "/ops/storage");
        // Id swap: the caller's own venue in the path, the other tenant's resource id after it.
        String b = "/api/v1/venues/" + venueB;
        List<String> swapped = List.of(
            b + "/reconstructions/" + s.run(),
            b + "/reconstructions/" + s.run() + "/artifacts/KSPLAT",
            b + "/floors/" + floorA + "/reconstructions",
            b + "/floors/" + floorA + "/scan-versions",
            b + "/scan-versions/" + versionA + "/reconstruction",
            b + "/scan-versions/" + versionA + "/artifacts/KSPLAT",
            b + "/captures/" + s.capture() + "/media");

        for (String p : paths) {
            get(p, adminB).andExpect(status().isNotFound());
            get(p, managerA2).andExpect(status().isNotFound());
            int viaLink = withViewerToken(HttpMethod.GET, p, viewerB, null).andReturn().getResponse().getStatus();
            assertThat(viaLink).as("public-viewer token of another organization's venue: " + p).isIn(403, 404);
        }
        for (String p : swapped) {
            get(p, adminB).andExpect(status().isNotFound());
            int viaLink = withViewerToken(HttpMethod.GET, p, viewerB, null).andReturn().getResponse().getStatus();
            assertThat(viaLink).as("public-viewer token, id swap: " + p).isIn(403, 404);
        }

        // Uploads and processing control into the other tenant's capture.
        post(a + "/captures/" + s.capture() + "/media", adminB, initBody("IMAGE", "x.png", "image/png", 10, "a".repeat(64)))
            .andExpect(status().isNotFound());
        post(a + "/captures/" + s.capture() + "/processing/retry", adminB, null).andExpect(status().isNotFound());
        post(a + "/captures/" + s.capture() + "/processing/cancel", managerA2, null).andExpect(status().isNotFound());

        // Routes: the venue is in the body, and the floor and POI ids are the other tenant's.
        String route = "{\"venueId\":\"%s\",\"floorId\":\"%s\",\"start\":[0,0,0],\"destinationPoiId\":\"%s\"}";
        post("/api/v1/navigation/routes", adminB, route.formatted(venueA, floorA, UUID.randomUUID())).andExpect(status().isNotFound());
        int swappedRoute = post("/api/v1/navigation/routes", adminB, route.formatted(venueB, floorA, UUID.randomUUID()))
            .andReturn().getResponse().getStatus();
        assertThat(swappedRoute).as("another tenant's floor under the caller's venue").isIn(404, 409, 422);

        // The other organization's refusals are audited in its own organization, never in the victim's.
        assertThat(auditCount(orgB, "venue.access", "DENIED")).isPositive();
        assertThat(jdbc.sql("SELECT count(*) FROM audit_log WHERE organization_id = :o AND venue_id = :v AND outcome = 'DENIED'")
            .param("o", orgB).param("v", venueA).query(Integer.class).single()).as("a denied row never names the victim's venue").isZero();
        // Nothing of org A changed.
        assertThat(processing(s).get("run").get("status").asText()).isEqualTo("RUNNING");
    }
}
