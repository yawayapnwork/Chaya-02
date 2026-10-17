package dev.chaya.api.storage;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Passes an object's bytes through while hashing them, and fails with {@link IntegrityException} at end of stream unless
 * they hash to the SHA-256 recorded when the artifact was registered (review S-2). A streamed HTTP response is then cut
 * short: with a Content-Length set, every client sees a failed download, never a complete-looking tampered one.
 *
 * <p>The object's last byte is held back until the whole object has been verified. Without that, a tampered object of the
 * registered size reached the client in full before the hash was checked at end of stream: exactly Content-Length bytes and
 * a 200, indistinguishable from a good download (found by the cross-service acceptance suite, docs/ACCEPTANCE.md).
 */
public final class VerifyingInputStream extends FilterInputStream {

    private final MessageDigest digest;
    private final String expectedSha256;
    private final String key;
    private boolean checked;
    /** The last byte read from the object and not yet passed on, or -1. */
    private int held = -1;
    private byte[] scratch = new byte[0];

    public VerifyingInputStream(InputStream in, String expectedSha256, String key) {
        super(in);
        if (expectedSha256 == null || !expectedSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("no recorded SHA-256 to verify " + key + " against");
        }
        this.expectedSha256 = expectedSha256;
        this.key = key;
        try {
            this.digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public int read() throws IOException {
        byte[] one = new byte[1];
        int n = read(one, 0, 1);
        return n < 0 ? -1 : one[0] & 0xff;
    }

    @Override
    public int read(byte[] buf, int off, int len) throws IOException {
        if (len == 0) {
            return 0;
        }
        while (true) {
            if (scratch.length < len) {
                scratch = new byte[len];
            }
            int n = in.read(scratch, 0, len);
            if (n < 0) {
                verify(); // a mismatch throws here, and the held byte is never passed on
                if (held < 0) {
                    return -1;
                }
                buf[off] = (byte) held;
                held = -1;
                return 1;
            }
            digest.update(scratch, 0, n);
            // Pass on the held byte and all but the last of the new ones; hold that last one back.
            int out = 0;
            if (held >= 0) {
                buf[off] = (byte) held;
                out = 1;
            }
            System.arraycopy(scratch, 0, buf, off + out, n - 1);
            out += n - 1;
            held = scratch[n - 1] & 0xff;
            if (out > 0) {
                return out;
            }
            // Only one byte arrived and it is now held: read on rather than return 0 for a non-empty request.
        }
    }

    @Override
    public int available() throws IOException {
        return 0;
    }

    @Override
    public long skip(long n) throws IOException {
        throw new IOException("a verified stream cannot skip");
    }

    @Override
    public boolean markSupported() {
        return false;
    }

    private void verify() throws IntegrityException {
        if (checked) {
            return;
        }
        checked = true;
        String actual = HexFormat.of().formatHex(digest.digest());
        if (!actual.equals(expectedSha256)) {
            throw new IntegrityException(key, expectedSha256, actual);
        }
    }

    /** The stored bytes are not the ones that were registered. */
    public static final class IntegrityException extends IOException {
        private final String key;

        public IntegrityException(String key, String expected, String actual) {
            super("object " + key + " hashes to " + actual + ", not the registered " + expected);
            this.key = key;
        }

        public String key() {
            return key;
        }
    }
}
