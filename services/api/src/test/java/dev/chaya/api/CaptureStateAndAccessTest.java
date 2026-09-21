package dev.chaya.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpMethod;

class CaptureStateAndAccessTest extends CaptureTestSupport {

    // ---- state machine -----------------------------------------------------------------------

    @Test
    void invalidTransitionsAreRejectedByTheApi() throws Exception {
        var c = ctx();
        UUID capture = newCapture(c);
        // Nothing uploaded yet: cannot finish or process.
        post(capUrl(c, capture) + "/complete-upload", c.operator(), null)
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_CAPTURE_TRANSITION"));
        post(capUrl(c, capture) + "/processing", c.operator(), null)
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_CAPTURE_TRANSITION"));

        // One accepted image is not enough media.
        var u = upload(c, capture, "IMAGE", "a.png", "image/png", png(1));
        assertThat(field(awaitSettled(c, capture, u.mediaId()), "status")).isEqualTo("ACCEPTED");
        post(capUrl(c, capture) + "/complete-upload", c.operator(), null)
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INSUFFICIENT_MEDIA"));
        get(capUrl(c, capture), c.operator()).andExpect(jsonPath("$.status").value("UPLOADING"));

        // Add a video, finish, then the lifecycle only moves forward.
        var v = upload(c, capture, "VIDEO", "v.mp4", "video/mp4", mp4(4096));
        assertThat(field(awaitSettled(c, capture, v.mediaId()), "status")).isEqualTo("ACCEPTED");
        post(capUrl(c, capture) + "/complete-upload", c.operator(), null).andExpect(status().isOk());
        post(capUrl(c, capture) + "/complete-upload", c.operator(), null).andExpect(status().isConflict());
        post(capUrl(c, capture) + "/media", c.operator(), initBody("IMAGE", "late.png", "image/png", 10, "a".repeat(64)))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CAPTURE_NOT_ACCEPTING_UPLOADS"));
        post(capUrl(c, capture) + "/processing", c.operator(), null).andExpect(status().isAccepted());
        post(capUrl(c, capture) + "/processing", c.operator(), null).andExpect(status().isConflict());
    }

    @Test
    void endTimeCannotPrecedeStartTime() throws Exception {
        var c = ctx();
        UUID capture = newCapture(c);
        var v = upload(c, capture, "VIDEO", "v.mp4", "video/mp4", mp4(4096));
        awaitSettled(c, capture, v.mediaId());
        post(capUrl(c, capture) + "/complete-upload", c.operator(), "{\"endedAt\":\"2001-01-01T00:00:00Z\"}")
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_END_TIME"));
    }

    @Test
    void theDatabaseRefusesInvalidCaptureTransitionsEvenWhenBypassingTheApi() throws Exception {
        var c = ctx();
        UUID capture = newCapture(c);
        assertThatThrownBy(() -> jdbc.sql("UPDATE capture_session SET status = 'PROCESSING' WHERE id = :c").param("c", capture).update())
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("invalid capture_session transition");
        jdbc.sql("UPDATE capture_session SET status = 'FAILED', failure_code = 'TEST' WHERE id = :c").param("c", capture).update();
        // FAILED is terminal.
        assertThatThrownBy(() -> jdbc.sql("UPDATE capture_session SET status = 'UPLOADING' WHERE id = :c").param("c", capture).update())
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM capture_session WHERE id = :c").param("c", capture).update())
            .isInstanceOf(DataIntegrityViolationException.class);
        // A failed capture no longer accepts media rows.
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO capture_media (organization_id, venue_id, capture_session_id, kind, original_filename, "
                + "claimed_content_type, declared_size_bytes, declared_sha256, bucket, object_key, part_size_bytes, total_parts) "
                + "VALUES (:o, :v, :c, 'IMAGE', 'x', 'image/png', 1, :h, 'b', 'k', 1, 1)")
            .param("o", c.org()).param("v", c.venue()).param("c", capture).param("h", "a".repeat(64)).update())
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("no longer accepts media");
    }

    @Test
    void captureStatusEnumMatchesTheDocumentedLifecycle() {
        var s = dev.chaya.api.capture.CaptureStatus.class;
        assertThat(dev.chaya.api.capture.CaptureStatus.CREATED.canTransitionTo(dev.chaya.api.capture.CaptureStatus.UPLOADING)).isTrue();
        assertThat(dev.chaya.api.capture.CaptureStatus.CREATED.canTransitionTo(dev.chaya.api.capture.CaptureStatus.PROCESSING)).isFalse();
        assertThat(dev.chaya.api.capture.CaptureStatus.READY_FOR_PROCESSING.canTransitionTo(dev.chaya.api.capture.CaptureStatus.UPLOADING)).isFalse();
        assertThat(dev.chaya.api.capture.CaptureStatus.COMPLETED.allowedNext()).isEmpty();
        assertThat(dev.chaya.api.capture.CaptureStatus.FAILED.allowedNext()).isEmpty();
        assertThat(s.getEnumConstants()).hasSize(8);
    }

    // ---- authentication and authorization ----------------------------------------------------

    @Test
    void unauthenticatedAndUnderprivilegedCallersAreRefused() throws Exception {
        var c = ctx();
        UUID capture = newCapture(c);
        String base = capUrl(c, capture);
        get(base, null).andExpect(status().isUnauthorized());
        post("/api/v1/venues/" + c.venue() + "/captures", null, "{}").andExpect(status().isUnauthorized());

        String viewer = TestJwt.user(c.org(), "viewer").venues(c.venue()).token();
        get(base, viewer).andExpect(status().isForbidden());
        get(base + "/media", viewer).andExpect(status().isForbidden());
        post("/api/v1/venues/" + c.venue() + "/captures", viewer, "{}").andExpect(status().isForbidden());
        post(base + "/processing", viewer, null).andExpect(status().isForbidden());

        // Anonymous public-link visitors never see raw captures.
        String mgr = TestJwt.user(c.org(), "venue-manager").venues(c.venue()).token();
        String link = post("/api/v1/venues/" + c.venue() + "/public-links", mgr, "{\"ttl\":\"PT1H\"}").andReturn().getResponse().getContentAsString();
        String secret = link.replaceAll(".*\"secret\":\"([^\"]+)\".*", "$1");
        String tok = post("/api/v1/public/viewer-token", null, "{\"secret\":\"" + secret + "\"}").andReturn().getResponse().getContentAsString();
        String viewerToken = tok.replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");
        withViewerToken(HttpMethod.GET, base, viewerToken, null).andExpect(status().isForbidden());
        withViewerToken(HttpMethod.POST, "/api/v1/venues/" + c.venue() + "/captures", viewerToken, "{}").andExpect(status().isForbidden());

        // Service accounts have no tenant access.
        get(base, TestJwt.service().token()).andExpect(status().isForbidden());
    }

    @Test
    void anotherOrganizationCannotSeeOrTouchACapture() throws Exception {
        var c = ctx();
        UUID capture = newCapture(c);
        var u = upload(c, capture, "IMAGE", "a.png", "image/png", png(1));
        awaitSettled(c, capture, u.mediaId());

        String otherOrgAdmin = TestJwt.user(fx.organization(), "admin").token();
        String base = capUrl(c, capture);
        get(base, otherOrgAdmin).andExpect(status().isNotFound());
        get(base + "/media", otherOrgAdmin).andExpect(status().isNotFound());
        get(base + "/media/" + u.mediaId(), otherOrgAdmin).andExpect(status().isNotFound());
        post(base + "/media", otherOrgAdmin, initBody("IMAGE", "x.png", "image/png", 10, "a".repeat(64))).andExpect(status().isNotFound());
        post(base + "/complete-upload", otherOrgAdmin, null).andExpect(status().isNotFound());
        post(base + "/processing", otherOrgAdmin, null).andExpect(status().isNotFound());
        get(base + "/processing", otherOrgAdmin).andExpect(status().isNotFound());
    }

    @Test
    void anOperatorOfOneVenueCannotReachAnotherVenuesCaptures() throws Exception {
        var c = ctx();
        UUID capture = newCapture(c);
        var u = upload(c, capture, "IMAGE", "a.png", "image/png", png(1));
        awaitSettled(c, capture, u.mediaId());

        // Same organization, but this operator only holds venue2.
        UUID venue2 = fx.venue(c.org());
        String op2 = TestJwt.user(c.org(), "operator").venues(venue2).token();
        // Through venue 1 (not theirs):
        get(capUrl(c, capture), op2).andExpect(status().isNotFound());
        post("/api/v1/venues/" + c.venue() + "/captures", op2, "{}").andExpect(status().isNotFound());
        // Through their own venue with venue 1's ids: the capture is not in that venue.
        String viaVenue2 = "/api/v1/venues/" + venue2 + "/captures/" + capture;
        get(viaVenue2, op2).andExpect(status().isNotFound());
        get(viaVenue2 + "/media/" + u.mediaId(), op2).andExpect(status().isNotFound());
        post(viaVenue2 + "/media", op2, initBody("IMAGE", "x.png", "image/png", 10, "a".repeat(64))).andExpect(status().isNotFound());
        post(viaVenue2 + "/media/" + u.mediaId() + "/complete", op2, null).andExpect(status().isNotFound());
        get("/api/v1/venues/" + venue2 + "/captures", op2).andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
        assertThat(auditCount(c.org(), "venue.access", "DENIED")).isPositive();
    }

    @Test
    void mediaIdsCannotBeUsedThroughADifferentCaptureOfTheSameVenue() throws Exception {
        var c = ctx();
        UUID captureA = newCapture(c);
        UUID captureB = newCapture(c);
        byte[] png = png(5);
        var u = init(c, captureA, "IMAGE", "a.png", "image/png", png, sha256(png));
        sendPart(c, captureB, u, 1, png, null).andExpect(status().isNotFound());
        get(capUrl(c, captureB) + "/media/" + u.mediaId(), c.operator()).andExpect(status().isNotFound());
    }
}
