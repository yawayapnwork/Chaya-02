package dev.chaya.api;

import static org.assertj.core.api.Assertions.assertThat;

import dev.chaya.api.capture.ClamAvProperties;
import dev.chaya.api.capture.ClamAvScanner;
import dev.chaya.api.capture.FilenameSanitizer;
import dev.chaya.api.capture.MalwareScanner.Verdict;
import dev.chaya.api.capture.TikaContentTypeDetector;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Pure unit tests: no Spring, no Docker. */
class UploadUnitTest {

    @Test
    void filenamesAreSanitizedAgainstTraversalAndJunk() {
        assertThat(FilenameSanitizer.sanitize("../../etc/passwd")).isEqualTo("passwd");
        assertThat(FilenameSanitizer.sanitize("..\\..\\windows\\system32\\cmd.exe")).isEqualTo("cmd.exe");
        assertThat(FilenameSanitizer.sanitize("/absolute/path/video.mp4")).isEqualTo("video.mp4");
        assertThat(FilenameSanitizer.sanitize("evil\u0000name.png")).doesNotContain("\u0000");
        assertThat(FilenameSanitizer.sanitize(".hidden")).isEqualTo("hidden");
        assertThat(FilenameSanitizer.sanitize("a..b...c.png")).doesNotContain("..");
        assertThat(FilenameSanitizer.sanitize("<script>alert(1)</script>.png")).doesNotContain("<").doesNotContain(">");
        assertThat(FilenameSanitizer.sanitize("")).isEqualTo("file");
        assertThat(FilenameSanitizer.sanitize(null)).isEqualTo("file");
        assertThat(FilenameSanitizer.sanitize("x".repeat(500))).hasSize(100);
        assertThat(FilenameSanitizer.sanitize("name;rm -rf.png")).doesNotContain(";");
    }

    @Test
    void tikaDetectsTypesFromBytesNotFromNames() throws Exception {
        var tika = new TikaContentTypeDetector();
        assertThat(tika.detect(CaptureTestSupport.png(1))).isEqualTo("image/png");
        assertThat(tika.detect(CaptureTestSupport.jpeg(1))).isEqualTo("image/jpeg");
        assertThat(tika.detect(CaptureTestSupport.mp4(2048))).isEqualTo("video/mp4");
        assertThat(tika.detect("MZ\u0090\u0000\u0003\u0000\u0000\u0000".getBytes(StandardCharsets.ISO_8859_1)))
            .isEqualTo("application/x-msdownload");
    }

    // ---- clamd wire protocol against a fake daemon ---------------------------------------------

    /** Runs a one-shot clamd stand-in that records the INSTREAM payload and answers with the given reply. */
    private static int fakeClamd(String reply, AtomicReference<byte[]> received) throws IOException {
        ServerSocket server = new ServerSocket(0);
        Thread t = new Thread(() -> {
            try (server; Socket s = server.accept()) {
                DataInputStream in = new DataInputStream(s.getInputStream());
                byte[] cmd = in.readNBytes(10);
                assertThat(new String(cmd, StandardCharsets.US_ASCII)).isEqualTo("zINSTREAM\0");
                ByteArrayOutputStream body = new ByteArrayOutputStream();
                int len;
                while ((len = in.readInt()) > 0) {
                    body.write(in.readNBytes(len));
                }
                received.set(body.toByteArray());
                s.getOutputStream().write((reply + "\0").getBytes(StandardCharsets.US_ASCII));
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        });
        t.setDaemon(true);
        t.start();
        return server.getLocalPort();
    }

    @Test
    void clamAvClientStreamsContentAndInterpretsVerdicts() throws Exception {
        AtomicReference<byte[]> received = new AtomicReference<>();
        int port = fakeClamd("stream: OK", received);
        byte[] content = new byte[200_000];
        java.util.Arrays.fill(content, (byte) 7);
        var clean = new ClamAvScanner(new ClamAvProperties(true, "127.0.0.1", port)).scan(new ByteArrayInputStream(content));
        assertThat(clean.verdict()).isEqualTo(Verdict.CLEAN);
        assertThat(received.get()).isEqualTo(content);

        int port2 = fakeClamd("stream: Win.Test.EICAR_HDB-1 FOUND", new AtomicReference<>());
        var infected = new ClamAvScanner(new ClamAvProperties(true, "127.0.0.1", port2)).scan(new ByteArrayInputStream(new byte[] {1}));
        assertThat(infected.verdict()).isEqualTo(Verdict.INFECTED);
        assertThat(infected.detail()).isEqualTo("Win.Test.EICAR_HDB-1");

        int port3 = fakeClamd("INSTREAM size limit exceeded. ERROR", new AtomicReference<>());
        var error = new ClamAvScanner(new ClamAvProperties(true, "127.0.0.1", port3)).scan(new ByteArrayInputStream(new byte[] {1}));
        assertThat(error.verdict()).as("a scanner error must never count as clean").isEqualTo(Verdict.UNAVAILABLE);
    }
}
