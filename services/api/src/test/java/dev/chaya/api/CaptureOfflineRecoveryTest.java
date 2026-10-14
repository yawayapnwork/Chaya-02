package dev.chaya.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * What the server guarantees a capture client that retries after losing its connection (docs/capture-ingestion.md,
 * "Offline capture and the local upload queue"): a repeated session or file creation finds what the first attempt made,
 * concurrent retries make one record, a changed floor is reported before anything is created, an expired token is
 * refused, and an erased capture stays erased. Real HTTP through MockMvc, real PostgreSQL and MinIO.
 */
class CaptureOfflineRecoveryTest extends CaptureTestSupport {

    private String createBody(Ctx c, UUID floor, UUID clientSessionId, String expected) {
        return "{\"floorId\":\"" + floor + "\",\"device\":{\"source\":\"test\"},\"clientSessionId\":\"" + clientSessionId + "\""
            + (expected == null ? "" : ",\"expectedScanVersion\":" + expected) + "}";
    }

    private String capturesUrl(Ctx c) {
        return "/api/v1/venues/" + c.venue() + "/captures";
    }

    private int captureCount(Ctx c) {
        return jdbc.sql("SELECT count(*) FROM capture_session WHERE venue_id = :v").param("v", c.venue()).query(Integer.class).single();
    }

    @Test
    void aRepeatedSessionCreationReturnsTheSameSessionAndNeverASecondOne() throws Exception {
        Ctx c = ctx();
        UUID client = UUID.randomUUID();
        String first = post(capturesUrl(c), c.operator(), createBody(c, c.floor(), client, null))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String again = post(capturesUrl(c), c.operator(), createBody(c, c.floor(), client, null))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(field(again, "id")).isEqualTo(field(first, "id"));
        assertThat(captureCount(c)).isEqualTo(1);

        // The same id for another floor is a client bug or a tampered replay: refused, nothing created.
        UUID otherFloor = fx.floor(c.org(), c.venue(), 1);
        post(capturesUrl(c), c.operator(), createBody(c, otherFloor, client, null)).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("CLIENT_SESSION_CONFLICT"));
        // Another operator's id space is separate: their session is theirs, never the first operator's.
        String otherOperator = TestJwt.user(c.org(), "operator").venues(c.venue()).token();
        String theirs = post(capturesUrl(c), otherOperator, createBody(c, c.floor(), client, null))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        assertThat(field(theirs, "id")).isNotEqualTo(field(first, "id"));
        assertThat(captureCount(c)).isEqualTo(2);
    }

    @Test
    void concurrentRetriesOfOneSessionCreateExactlyOneSession() throws Exception {
        Ctx c = ctx();
        UUID client = UUID.randomUUID();
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<Callable<Integer>> calls = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                calls.add(() -> post(capturesUrl(c), c.operator(), createBody(c, c.floor(), client, null)).andReturn().getResponse().getStatus());
            }
            List<Integer> codes = new ArrayList<>();
            for (Future<Integer> f : pool.invokeAll(calls)) {
                codes.add(f.get());
            }
            assertThat(codes).containsOnly(200, 201);
            assertThat(codes).filteredOn(code -> code == 201).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(captureCount(c)).isEqualTo(1);
    }

    @Test
    void aSessionRecordedAgainstAnotherPublishedScanVersionIsRefusedUntilTheClientConfirms() throws Exception {
        Ctx c = ctx();
        fx.calibratedFloor(c.org(), c.venue(), c.floor(), "FLOOR_LOCAL");
        fx.publishDrafts(c.org(), c.venue(), c.floor());
        String floors = get("/api/v1/venues/" + c.venue() + "/floors", c.operator()).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        String current = field(floors, "currentScanVersionId");
        assertThat(current).as("the floor publishes a version").isNotNull().isNotEqualTo("null");

        int before = captureCount(c); // publishing the fixture version made its own capture
        // Recorded offline before anything was published: the floor has changed since.
        post(capturesUrl(c), c.operator(), createBody(c, c.floor(), UUID.randomUUID(), "{\"versionId\":null}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SCAN_VERSION_CHANGED"));
        assertThat(captureCount(c)).as("nothing created").isEqualTo(before);
        // Recorded against what the floor still publishes: created.
        post(capturesUrl(c), c.operator(), createBody(c, c.floor(), UUID.randomUUID(), "{\"versionId\":\"" + current + "\"}"))
            .andExpect(status().isCreated());
        // The operator confirmed: the client sends no expectation, and the session is created.
        post(capturesUrl(c), c.operator(), createBody(c, c.floor(), UUID.randomUUID(), null)).andExpect(status().isCreated());
    }

    private String initWithClientId(Ctx c, UUID capture, byte[] bytes, UUID clientFileId, int expectedStatus) throws Exception {
        String body = "{\"kind\":\"VIDEO\",\"filename\":\"walk.mp4\",\"contentType\":\"video/mp4\",\"sizeBytes\":" + bytes.length
            + ",\"sha256\":\"" + sha256(bytes) + "\",\"clientFileId\":\"" + clientFileId + "\"}";
        return post(capUrl(c, capture) + "/media", c.operator(), body).andExpect(status().is(expectedStatus))
            .andReturn().getResponse().getContentAsString();
    }

    @Test
    void aRetriedFileUploadResumesTheSameUploadAndIsNeverPublishedTwice() throws Exception {
        Ctx c = ctx();
        UUID capture = newCapture(c);
        byte[] video = mp4(PART + 1024); // two parts
        UUID clientFile = UUID.randomUUID();

        String first = initWithClientId(c, capture, video, clientFile, 201);
        UUID media = UUID.fromString(field(first, "mediaId"));
        Upload u = new Upload(media, 2, video);
        // Part 1 arrives, then the connection drops before the answer to a retried init is seen.
        byte[] part1 = java.util.Arrays.copyOfRange(video, 0, PART);
        sendPart(c, capture, u, 1, part1, sha256(part1)).andExpect(status().isNoContent());
        String retried = initWithClientId(c, capture, video, clientFile, 200);
        assertThat(field(retried, "mediaId")).isEqualTo(media.toString());
        assertThat(field(retried, "existing")).isEqualTo("true");
        assertThat(field(retried, "status")).isEqualTo("PENDING");
        String partial = get(capUrl(c, capture) + "/media/" + media, c.operator()).andReturn().getResponse().getContentAsString();
        assertThat(partial).contains("\"uploadedParts\":[1]");

        // The client resumes with the missing part only, and the file is accepted once.
        byte[] part2 = java.util.Arrays.copyOfRange(video, PART, video.length);
        sendPart(c, capture, u, 2, part2, sha256(part2)).andExpect(status().isNoContent());
        // A duplicate of an already-sent part is harmless.
        sendPart(c, capture, u, 2, part2, sha256(part2)).andExpect(status().isNoContent());
        completeMedia(c, capture, u);
        assertThat(field(awaitSettled(c, capture, media), "status")).isEqualTo("ACCEPTED");

        // A retry after acceptance gets the verdict, not a second upload.
        String afterAccept = initWithClientId(c, capture, video, clientFile, 200);
        assertThat(field(afterAccept, "status")).isEqualTo("ACCEPTED");
        assertThat(jdbc.sql("SELECT count(*) FROM capture_media WHERE capture_session_id = :c").param("c", capture)
            .query(Integer.class).single()).isEqualTo(1);

        // The same client id for other bytes is refused rather than silently attached to the wrong file.
        initWithClientId(c, capture, mp4(4096), clientFile, 409);
    }

    @Test
    void concurrentRetriesOfOneFileOpenExactlyOneUpload() throws Exception {
        Ctx c = ctx();
        UUID capture = newCapture(c);
        byte[] video = mp4(4096);
        UUID clientFile = UUID.randomUUID();
        ExecutorService pool = Executors.newFixedThreadPool(6);
        List<String> ids = new ArrayList<>();
        try {
            List<Callable<String>> calls = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                calls.add(() -> {
                    String body = "{\"kind\":\"VIDEO\",\"filename\":\"walk.mp4\",\"contentType\":\"video/mp4\",\"sizeBytes\":" + video.length
                        + ",\"sha256\":\"" + sha256(video) + "\",\"clientFileId\":\"" + clientFile + "\"}";
                    return field(post(capUrl(c, capture) + "/media", c.operator(), body).andReturn().getResponse().getContentAsString(), "mediaId");
                });
            }
            for (Future<String> f : pool.invokeAll(calls)) {
                ids.add(f.get());
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(ids).doesNotContainNull().containsOnly(ids.get(0));
        assertThat(jdbc.sql("SELECT count(*) FROM capture_media WHERE capture_session_id = :c").param("c", capture)
            .query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void anExpiredTokenIsRefusedAndCreatesNothing() throws Exception {
        Ctx c = ctx();
        String expired = TestJwt.user(c.org(), "operator").venues(c.venue()).lifetime(Duration.ofMinutes(-5)).token();
        post(capturesUrl(c), expired, createBody(c, c.floor(), UUID.randomUUID(), null)).andExpect(status().isUnauthorized());
        assertThat(captureCount(c)).isZero();
    }

    @Test
    void anErasedCaptureIsGoneForAClientThatComesBackWithItsFiles() throws Exception {
        Ctx c = ctx();
        UUID client = UUID.randomUUID();
        String created = post(capturesUrl(c), c.operator(), createBody(c, c.floor(), client, null))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID capture = UUID.fromString(field(created, "id"));
        String manager = TestJwt.user(c.org(), "venue-manager").venues(c.venue()).token();
        call(HttpMethod.DELETE, capUrl(c, capture), manager, null).andExpect(status().isOk());

        byte[] video = mp4(4096);
        initWithClientId(c, capture, video, UUID.randomUUID(), 404);
        get(capUrl(c, capture), c.operator()).andExpect(status().isNotFound());
        // Synchronising the session again does not bring it back: erasure is final.
        post(capturesUrl(c), c.operator(), createBody(c, c.floor(), client, null)).andExpect(status().isGone())
            .andExpect(jsonPath("$.code").value("CAPTURE_ERASED"));
        assertThat(captureCount(c)).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM capture_client_session_tombstone WHERE client_session_id = :cs").param("cs", client)
            .query(Integer.class).single()).as("only the client's random id and the operator are kept").isEqualTo(1);
        // A venue the operator can no longer see answers 404 the same way.
        Ctx elsewhere = ctx();
        post("/api/v1/venues/" + elsewhere.venue() + "/captures", c.operator(), "{}").andExpect(status().isNotFound());
    }
}
