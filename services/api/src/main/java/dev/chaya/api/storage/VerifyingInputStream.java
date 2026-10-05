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
 */
public final class VerifyingInputStream extends FilterInputStream {

    private final MessageDigest digest;
    private final String expectedSha256;
    private final String key;
    private boolean checked;

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
        int b = super.read();
        if (b < 0) {
            verify();
        } else {
            digest.update((byte) b);
        }
        return b;
    }

    @Override
    public int read(byte[] buf, int off, int len) throws IOException {
        int n = super.read(buf, off, len);
        if (n < 0) {
            verify();
        } else {
            digest.update(buf, off, n);
        }
        return n;
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
