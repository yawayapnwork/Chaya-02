package dev.chaya.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.chaya.api.storage.ObjectStore;
import java.io.InputStream;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The whole ingestion path against real PostgreSQL and real MinIO. */
class CaptureIngestionTest extends CaptureTestSupport {

    @Autowired ObjectStore store;

    private String objectKey(UUID media) {
        return jdbc.sql("SELECT object_key FROM capture_media WHERE id = :m").param("m", media).query(String.class).single();
    }

    @Test
    void endToEndCaptureFromSessionToProcessingJob() throws Exception {
        var c = ctx();
        UUID capture = newCapture(c);
        get(capUrl(c, capture), c.operator()).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CREATED"))
            .andExpect(jsonPath("$.device.model").value("test-phone"))
            .andExpect(jsonPath("$.qualityState").value("NOT_ASSESSED"));

        byte[] first = png(1);
        var u1 = upload(c, capture, "IMAGE", "../../etc/frame 1.png", "image/png", first);
        var u2 = upload(c, capture, "IMAGE", "frame2.jpg", "image/jpeg", jpeg(2));
        var u3 = upload(c, capture, "IMAGE", "frame3.png", "image/png", png(3));
        var meta = upload(c, capture, "METADATA", "capture.json", "application/json", json("{\"fps\":30,\"lens\":\"wide\"}"));

        for (var u : new CaptureTestSupport.Upload[] {u1, u2, u3, meta}) {
            String settled = awaitSettled(c, capture, u.mediaId());
            assertThat(field(settled, "status")).as(settled).isEqualTo("ACCEPTED");
        }
        get(capUrl(c, capture), c.operator()).andExpect(jsonPath("$.status").value("UPLOADING"))
            .andExpect(jsonPath("$.mediaCount").value(4)).andExpect(jsonPath("$.acceptedMediaCount").value(4));

        // The object really is in MinIO, under a server-generated key, byte for byte.
        String key = objectKey(u1.mediaId());
        assertThat(key).matches("org/[0-9a-f-]{36}/venue/[0-9a-f-]{36}/capture/[0-9a-f-]{36}/raw/[0-9a-f-]{36}");
        assertThat(store.size(key)).isEqualTo(first.length);
        try (InputStream in = store.open(key)) {
            assertThat(in.readAllBytes()).isEqualTo(first);
        }
        // The DB stores metadata and verification results, never the bytes.
        var row = jdbc.sql("SELECT original_filename, detected_content_type, verified_sha256, scan_result FROM capture_media WHERE id = :m")
            .param("m", u1.mediaId()).query().singleRow();
        assertThat(row.get("original_filename")).isEqualTo("frame 1.png"); // "../../etc/" was stripped
        assertThat(row.get("detected_content_type")).isEqualTo("image/png");
        assertThat(row.get("verified_sha256")).isEqualTo(sha256(first));
        assertThat(row.get("scan_result")).isEqualTo("CLEAN");

        // Artifact listing.
        get(capUrl(c, capture) + "/media", c.operator()).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(4));

        // Finish the upload: state machine + storage integrity check.
        post(capUrl(c, capture) + "/complete-upload", c.operator(), "{\"durationSeconds\":42.5}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("READY_FOR_PROCESSING"))
            .andExpect(jsonPath("$.durationSeconds").value(42.5))
            .andExpect(jsonPath("$.endedAt").exists());

        // Processing: a real pipeline run starts and its first stage is queued. No worker has claimed it, so it honestly stays QUEUED.
        post(capUrl(c, capture) + "/processing", c.operator(), null).andExpect(status().isAccepted())
            .andExpect(jsonPath("$.captureStatus").value("PROCESSING"))
            .andExpect(jsonPath("$.jobs[0].stage").value("INPUT_VALIDATION"))
            .andExpect(jsonPath("$.run.status").value("RUNNING")).andExpect(jsonPath("$.run.quality").doesNotExist())
            .andExpect(jsonPath("$.run.stages.length()").value(12)).andExpect(jsonPath("$.run.stages[0].state").value("QUEUED"))
            .andExpect(jsonPath("$.run.stages[1].state").value("PENDING"))
            .andExpect(jsonPath("$.jobs[0].status").value("QUEUED"));
        get(capUrl(c, capture) + "/processing", c.operator()).andExpect(status().isOk())
            .andExpect(jsonPath("$.jobs.length()").value(1)).andExpect(jsonPath("$.scanId").exists());

        for (String action : new String[] {"capture.create", "media.upload_init", "media.upload_complete", "media.accept",
            "capture.upload_complete", "capture.start_processing"}) {
            assertThat(auditCount(c.org(), action)).as(action).isPositive();
        }
    }

    @Test
    void resumableMultipartUploadOfALargerFile() throws Exception {
        var c = ctx();
        UUID capture = newCapture(c);
        byte[] video = mp4(PART + 1024);
        var u = init(c, capture, "VIDEO", "walkthrough.mp4", "video/mp4", video, sha256(video));
        assertThat(u.totalParts()).isEqualTo(2);

        // Send only the last part, "lose the connection", then look at what the server has.
        byte[] tail = Arrays.copyOfRange(video, PART, video.length);
        sendPart(c, capture, u, 2, tail, sha256(tail)).andExpect(status().isNoContent());
        get(capUrl(c, capture) + "/media/" + u.mediaId(), c.operator())
            .andExpect(jsonPath("$.uploadedParts[0]").value(2)).andExpect(jsonPath("$.status").value("PENDING"));
        // Completing now is refused and names the missing part.
        post(capUrl(c, capture) + "/media/" + u.mediaId() + "/complete", c.operator(), null)
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MISSING_PARTS"));

        // Resume: send the missing part; re-sending a part that already exists is harmless.
        byte[] head = Arrays.copyOfRange(video, 0, PART);
        sendPart(c, capture, u, 1, head, sha256(head)).andExpect(status().isNoContent());
        sendPart(c, capture, u, 2, tail, sha256(tail)).andExpect(status().isNoContent());
        completeMedia(c, capture, u);

        String settled = awaitSettled(c, capture, u.mediaId());
        assertThat(field(settled, "status")).as(settled).isEqualTo("ACCEPTED");
        assertThat(field(settled, "detectedContentType")).isEqualTo("video/mp4");
        assertThat(store.size(objectKey(u.mediaId()))).isEqualTo(video.length);

        post(capUrl(c, capture) + "/complete-upload", c.operator(), null).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("READY_FOR_PROCESSING"));
    }

    @Test
    void storageIntegrityIsCheckedBeforeACaptureBecomesReady() throws Exception {
        var c = ctx();
        UUID capture = newCapture(c);
        var u = upload(c, capture, "VIDEO", "a.mp4", "video/mp4", mp4(2048));
        assertThat(field(awaitSettled(c, capture, u.mediaId()), "status")).isEqualTo("ACCEPTED");

        store.delete(objectKey(u.mediaId())); // someone removes the object behind our back

        post(capUrl(c, capture) + "/complete-upload", c.operator(), null)
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STORAGE_INTEGRITY"));
        get(capUrl(c, capture), c.operator()).andExpect(jsonPath("$.status").value("FAILED"))
            .andExpect(jsonPath("$.failureCode").value("STORAGE_INTEGRITY"));
    }

    @Test
    void healthReportsRealStorageStatus() throws Exception {
        get("/api/v1/health", null).andExpect(status().isOk()).andExpect(jsonPath("$.storage").value("UP"));
    }

    @Test
    void floorsCanBeListedAndCreatedAndAreVenueScoped() throws Exception {
        var c = ctx();
        String mgr = TestJwt.user(c.org(), "venue-manager").venues(c.venue()).token();
        post("/api/v1/venues/" + c.venue() + "/floors", mgr, "{\"level\":1,\"name\":\"Mezzanine\"}").andExpect(status().isCreated());
        get("/api/v1/venues/" + c.venue() + "/floors", c.operator()).andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2));
        post("/api/v1/venues/" + c.venue() + "/floors", c.operator(), "{\"level\":2,\"name\":\"x\"}").andExpect(status().isForbidden());

        // A floor of another venue cannot be used for a capture.
        UUID otherVenue = fx.venue(c.org());
        UUID otherFloor = fx.floor(c.org(), otherVenue, 0);
        post("/api/v1/venues/" + c.venue() + "/captures", c.operator(), "{\"floorId\":\"" + otherFloor + "\"}")
            .andExpect(status().isNotFound());
    }
}
