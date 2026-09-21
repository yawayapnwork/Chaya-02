package dev.chaya.api;

import dev.chaya.api.capture.MalwareScanner;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Test stand-in for clamd: reports INFECTED when the content carries the standard EICAR test
 * signature and CLEAN otherwise, and can be switched to UNAVAILABLE to exercise the fail-safe path.
 * The real clamd wire client is covered by ClamAvScannerTest (fake daemon) and, optionally, by
 * ClamAvLiveTest against a real ClamAV container.
 */
@TestConfiguration
class TestScannerConfig {

    static final String EICAR = "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*";
    static final AtomicBoolean SCANNER_DOWN = new AtomicBoolean(false);

    @Bean
    @Primary
    MalwareScanner testScanner() {
        return (InputStream content) -> {
            if (SCANNER_DOWN.get()) {
                return new MalwareScanner.ScanResult(MalwareScanner.Verdict.UNAVAILABLE, "scanner is down (test)");
            }
            try {
                String text = new String(content.readAllBytes(), StandardCharsets.ISO_8859_1);
                return text.contains(EICAR)
                    ? new MalwareScanner.ScanResult(MalwareScanner.Verdict.INFECTED, "Eicar-Test-Signature")
                    : new MalwareScanner.ScanResult(MalwareScanner.Verdict.CLEAN, null);
            } catch (IOException e) {
                return new MalwareScanner.ScanResult(MalwareScanner.Verdict.UNAVAILABLE, e.getMessage());
            }
        };
    }
}
