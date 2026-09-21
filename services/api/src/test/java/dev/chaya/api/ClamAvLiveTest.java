package dev.chaya.api;

import static org.assertj.core.api.Assertions.assertThat;

import dev.chaya.api.capture.ClamAvProperties;
import dev.chaya.api.capture.ClamAvScanner;
import dev.chaya.api.capture.MalwareScanner.Verdict;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Runs the production ClamAvScanner against a real clamd. Skipped unless CLAMAV_LIVE_PORT is set:
 *   docker run -d -p 3310:3310 clamav/clamav:1.4   (wait until clamd answers)
 *   CLAMAV_LIVE_PORT=3310 mvn test -Dtest=ClamAvLiveTest
 */
@EnabledIfEnvironmentVariable(named = "CLAMAV_LIVE_PORT", matches = "\\d+")
class ClamAvLiveTest {

    private ClamAvScanner scanner() {
        return new ClamAvScanner(new ClamAvProperties(true, "localhost", Integer.parseInt(System.getenv("CLAMAV_LIVE_PORT"))));
    }

    @Test
    void cleanContentIsCleanAndEicarIsDetected() throws Exception {
        assertThat(scanner().scan(new ByteArrayInputStream(CaptureTestSupport.png(1))).verdict()).isEqualTo(Verdict.CLEAN);

        var infected = scanner().scan(new ByteArrayInputStream(TestScannerConfig.EICAR.getBytes(StandardCharsets.ISO_8859_1)));
        assertThat(infected.verdict()).isEqualTo(Verdict.INFECTED);
        assertThat(infected.detail()).containsIgnoringCase("eicar");
    }

    @Test
    void largeCleanStreamsAreScannedInFull() {
        byte[] big = new byte[30 * 1024 * 1024]; // above clamd's 25 MB default StreamMaxLength: needs the raised limit
        var result = scanner().scan(new ByteArrayInputStream(big));
        assertThat(result.verdict()).as(String.valueOf(result.detail())).isEqualTo(Verdict.CLEAN);
    }
}
