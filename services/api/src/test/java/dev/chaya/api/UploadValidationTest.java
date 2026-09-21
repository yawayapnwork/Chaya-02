package dev.chaya.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.chaya.api.storage.ObjectStore;
import dev.chaya.api.storage.StorageException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Every way an upload can be refused, and that refused bytes never stay in storage. */
class UploadValidationTest extends CaptureTestSupport {

    @Autowired ObjectStore store;

    @AfterEach
    void scannerBackUp() {
        TestScannerConfig.SCANNER_DOWN.set(false);
    }

    private String key(UUID media) {
        return jdbc.sql("SELECT object_key FROM capture_media WHERE id = :m").param("m", media).query(String.class).single();
    }

    private void assertRejected(Ctx c, UUID capture, CaptureTestSupport.Upload u, String code) throws Exception {
        String settled = awaitSettled(c, capture, u.mediaId());
        assertThat(field(settled, "status")).as(settled).isEqualTo("REJECTED");
        assertThat(field(settled, "rejectionCode")).isEqualTo(code);
        assertThatThrownBy(() -> store.size(key(u.mediaId()))).as("rejected object must be deleted").isInstanceOf(StorageException.class);
    }

    // ---- claims refused up front -------------------------------------------------------------

    @Test
    void unsupportedClaimedMimeTypesAreRefusedBeforeAnyByteIsAccepted() throws Exception {
        var c = ctx();
        UUID capture = newCapture(c);
        for (String type : new String[] {"application/x-msdownload", "text/html", "application/zip", "image/svg+xml", ""}) {
            post(capUrl(c, capture) + "/media", c.operator(), initBody("IMAGE", "x.bin", type, 100, "a".repeat(64)))
                .andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
        }
        // A type that is valid for another kind is still wrong for this one.
        post(capUrl(c, capture) + "/media", c.operator(), initBody("IMAGE", "x.mp4", "video/mp4", 100, "a".repeat(64)))
            .andExpect(status().isUnsupportedMediaType());
        // Parameters and case do not sneak past the allowlist check.
        post(capUrl(c, capture) + "/media", c.operator(), initBody("IMAGE", "x.png", "IMAGE/PNG; charset=binary", 100, "a".repeat(64)))
            .andExpect(status().isCreated());
        assertThat(jdbc.sql("SELECT count(*) FROM capture_media WHERE capture_session_id = :c").param("c", capture)
            .query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void oversizedFilesAreRefused() throws Exception {
        var c = ctx();
        UUID capture = newCapture(c);
        // Test profile: images are limited to 1 MiB.
        post(capUrl(c, capture) + "/media", c.operator(), initBody("IMAGE", "big.png", "image/png", 1_048_577, "a".repeat(64)))
            .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.code").value("FILE_TOO_LARGE"));
        post(capUrl(c, capture) + "/media", c.operator(), initBody("METADATA", "big.json", "application/json", 1_048_577, "a".repeat(64)))
            .andExpect(status().isPayloadTooLarge());
        post(capUrl(c, capture) + "/media", c.operator(), initBody("IMAGE", "zero.png", "image/png", 0, "a".repeat(64)))
            .andExpect(status().isBadRequest());
    }

    @Test
    void malformedChecksumsAreRefused() throws Exception {
        var c = ctx();
        UUID capture = newCapture(c);
        for (String sha : new String[] {"abc", "G".repeat(64), "a".repeat(63)}) {
            post(capUrl(c, capture) + "/media", c.operator(), initBody("IMAGE", "x.png", "image/png", 100, sha))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_CHECKSUM"));
        }
    }

    @Test
    void partsMustMatchTheDeclaredSizeExactly() throws Exception {
        var c = ctx();
        UUID capture = newCapture(c);
        byte[] video = mp4(PART + 100);
        var u = init(c, capture, "VIDEO", "v.mp4", "video/mp4", video, sha256(video));
        // Part 1 must be exactly PART bytes: one byte more, or fewer, is refused; nothing is stored.
        sendPart(c, capture, u, 1, new byte[PART + 1], null).andExpect(status().isPayloadTooLarge())
            .andExpect(jsonPath("$.code").value("PART_TOO_LARGE"));
        sendPart(c, capture, u, 1, new byte[PART - 1], null).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("PART_SIZE_MISMATCH"));
        sendPart(c, capture, u, 3, new byte[100], null).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_PART"));
        assertThat(jdbc.sql("SELECT count(*) FROM capture_media_part WHERE media_id = :m").param("m", u.mediaId())
            .query(Integer.class).single()).isZero();
    }

    // ---- checksum verification ---------------------------------------------------------------

    @Test
    void aCorruptedPartIsDetectedImmediately() throws Exception {
        var c = ctx();
        UUID capture = newCapture(c);
        byte[] png = png(9);
        var u = init(c, capture, "IMAGE", "a.png", "image/png", png, sha256(png));
        sendPart(c, capture, u, 1, png, sha256(json("something else"))).andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("CHECKSUM_MISMATCH"));
        assertThat(jdbc.sql("SELECT count(*) FROM capture_media_part WHERE media_id = :m").param("m", u.mediaId())
            .query(Integer.class).single()).isZero();
    }

    @Test
    void wholeFileChecksumIsVerifiedAgainstTheStoredBytes() throws Exception {
        var c = ctx();
        UUID capture = newCapture(c);
        byte[] png = png(10);
        // The client declares the checksum of different bytes.
        var u = init(c, capture, "IMAGE", "a.png", "image/png", png, sha256(png(11)));
        sendAllParts(c, capture, u);
        completeMedia(c, capture, u);
        assertRejected(c, capture, u, "CHECKSUM_MISMATCH");
    }

    // ---- real content inspection -------------------------------------------------------------

    @Test
    void contentThatDoesNotMatchTheClaimedTypeIsRejected() throws Exception {
        var c = ctx();
        UUID capture = newCapture(c);
        // Declared PNG, bytes are a JPEG.
        assertRejected(c, capture, upload(c, capture, "IMAGE", "a.png", "image/png", jpeg(1)), "CONTENT_TYPE_MISMATCH");
        // Declared PNG, bytes are a Windows executable header.
        byte[] exe = ("MZ" + "\u0090\u0000\u0003".repeat(20)).getBytes(StandardCharsets.ISO_8859_1);
        assertRejected(c, capture, upload(c, capture, "IMAGE", "photo.png", "image/png", exe), "UNSUPPORTED_CONTENT");
        // Declared MP4, bytes are plain text.
        assertRejected(c, capture, upload(c, capture, "VIDEO", "v.mp4", "video/mp4", json("not a video at all")), "UNSUPPORTED_CONTENT");
    }

    @Test
    void metadataMustBeAJsonObject() throws Exception {
        var c = ctx();
        UUID capture = newCapture(c);
        assertRejected(c, capture, upload(c, capture, "METADATA", "m.json", "application/json", json("{not json")), "INVALID_METADATA");
        assertRejected(c, capture, upload(c, capture, "METADATA", "n.json", "application/json", json("[1,2,3]")), "INVALID_METADATA");
    }

    // ---- malware scanning fails safe ---------------------------------------------------------

    @Test
    void infectedFilesAreRejectedAndDeleted() throws Exception {
        var c = ctx();
        UUID capture = newCapture(c);
        byte[] png = png(20);
        byte[] infected = new byte[png.length + TestScannerConfig.EICAR.length()];
        System.arraycopy(png, 0, infected, 0, png.length);
        System.arraycopy(TestScannerConfig.EICAR.getBytes(StandardCharsets.ISO_8859_1), 0, infected, png.length,
            TestScannerConfig.EICAR.length());
        assertRejected(c, capture, upload(c, capture, "IMAGE", "a.png", "image/png", infected), "MALWARE_DETECTED");
    }

    @Test
    void contentIsScannedBeforeItIsInterpreted() throws Exception {
        var c = ctx();
        UUID capture = newCapture(c);
        // A standalone EICAR text file declared as a PNG would fail the type check, but it must be
        // reported as malware: scanning happens first, before any type sniffing.
        byte[] eicarOnly = TestScannerConfig.EICAR.getBytes(StandardCharsets.ISO_8859_1);
        assertRejected(c, capture, upload(c, capture, "IMAGE", "e.png", "image/png", eicarOnly), "MALWARE_DETECTED");
    }

    @Test
    void whenTheScannerIsUnavailableFilesAreQuarantinedNeverAccepted() throws Exception {
        var c = ctx();
        UUID capture = newCapture(c);
        TestScannerConfig.SCANNER_DOWN.set(true);
        var u = upload(c, capture, "IMAGE", "a.png", "image/png", png(30));
        String settled = awaitSettled(c, capture, u.mediaId());
        assertThat(field(settled, "status")).isEqualTo("QUARANTINED");
        assertThat(field(settled, "rejectionCode")).isEqualTo("SCANNER_UNAVAILABLE");
        assertThat(store.size(key(u.mediaId()))).isPositive(); // kept, so it can be re-validated

        // A quarantined file blocks the capture from completing.
        post(capUrl(c, capture) + "/complete-upload", c.operator(), null)
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("UPLOADS_NOT_SETTLED"));

        // Scanner comes back; retrying validation accepts it.
        TestScannerConfig.SCANNER_DOWN.set(false);
        completeMedia(c, capture, u);
        assertThat(field(awaitSettled(c, capture, u.mediaId()), "status")).isEqualTo("ACCEPTED");
    }

    @Test
    void theRealClamAvClientDoesNotPretendWhenScanningIsDisabled() {
        var scanner = new dev.chaya.api.capture.ClamAvScanner(new dev.chaya.api.capture.ClamAvProperties(false, "localhost", 3310));
        var result = scanner.scan(new java.io.ByteArrayInputStream(new byte[] {1, 2, 3}));
        assertThat(result.verdict()).isEqualTo(dev.chaya.api.capture.MalwareScanner.Verdict.UNAVAILABLE);
        var unreachable = new dev.chaya.api.capture.ClamAvScanner(new dev.chaya.api.capture.ClamAvProperties(true, "127.0.0.1", 1));
        assertThat(unreachable.scan(new java.io.ByteArrayInputStream(new byte[] {1})).verdict())
            .isEqualTo(dev.chaya.api.capture.MalwareScanner.Verdict.UNAVAILABLE);
    }
}
