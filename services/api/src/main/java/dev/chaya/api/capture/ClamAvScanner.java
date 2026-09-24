package dev.chaya.api.capture;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * clamd client using the INSTREAM protocol over TCP. Fails safe: if scanning is disabled, the
 * daemon is unreachable, or clamd reports an error (for example the stream exceeds its size limit),
 * the verdict is UNAVAILABLE and the caller must not accept the file.
 */
@Component
public class ClamAvScanner implements MalwareScanner {

    private static final Logger log = LoggerFactory.getLogger(ClamAvScanner.class);
    private static final int CHUNK = 64 * 1024;
    private static final int CONNECT_TIMEOUT_MS = 3_000;
    private static final int READ_TIMEOUT_MS = 120_000;
    private static final int MAX_REPLY_BYTES = 4096;

    private final ClamAvProperties props;

    public ClamAvScanner(ClamAvProperties props) {
        this.props = props;
    }

    @Override
    public ScanResult scan(InputStream content) {
        if (!props.enabled()) {
            return new ScanResult(Verdict.UNAVAILABLE, "malware scanning is not enabled (CLAMAV_ENABLED=false)");
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(props.host(), props.port()), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(READ_TIMEOUT_MS);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            out.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
            byte[] buf = new byte[CHUNK];
            int n;
            while ((n = content.read(buf)) > 0) {
                out.writeInt(n); // big-endian length prefix, as clamd expects
                out.write(buf, 0, n);
            }
            out.writeInt(0);
            out.flush();
            return parse(readResponse(socket.getInputStream()));
        } catch (IOException e) {
            log.warn("clamd scan failed: {}", e.getMessage());
            // The detail reaches API clients (rejection message): no exception text, which names hosts and ports.
            return new ScanResult(Verdict.UNAVAILABLE, "malware scanner unreachable or failed; the file is quarantined");
        }
    }

    /** clamd's PING command; PONG means the daemon is up and has loaded its signatures. */
    @Override
    public String health() {
        if (!props.enabled()) {
            return DISABLED;
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(props.host(), props.port()), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(CONNECT_TIMEOUT_MS);
            socket.getOutputStream().write("zPING\0".getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            return "PONG".equals(readResponse(socket.getInputStream()).trim()) ? "UP" : "DOWN";
        } catch (IOException e) {
            return "DOWN";
        }
    }

    static ScanResult parse(String response) {
        String r = response.trim();
        if (r.endsWith("OK")) {
            return ScanResult.clean();
        }
        if (r.endsWith("FOUND")) {
            String signature = r.replaceFirst("^stream:\\s*", "").replaceFirst("\\s*FOUND$", "");
            return new ScanResult(Verdict.INFECTED, signature);
        }
        return new ScanResult(Verdict.UNAVAILABLE, "scanner error: " + r);
    }

    private static String readResponse(InputStream in) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) > 0) { // clamd terminates the reply with NUL (zINSTREAM) or EOF
            if (bytes.size() >= MAX_REPLY_BYTES) {
                throw new IOException("clamd reply exceeds " + MAX_REPLY_BYTES + " bytes");
            }
            bytes.write(b);
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }
}
