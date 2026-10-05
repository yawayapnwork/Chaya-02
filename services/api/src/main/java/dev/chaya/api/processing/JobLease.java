package dev.chaya.api.processing;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * A job lease token: 256 random bits issued with each claim, sent back by the worker as {@value #HEADER} on every call
 * about that job. Only its SHA-256 is stored (processing_job.lease_token_sha256), like public-link secrets. Every claim
 * issues a new one, so a worker whose lease expired cannot act on the job after it was re-queued and claimed again, and a
 * worker can never act on a job (of any tenant) that it did not claim.
 */
public final class JobLease {

    public static final String HEADER = "X-Chaya-Lease-Token";
    private static final SecureRandom RANDOM = new SecureRandom();

    private JobLease() {}

    public static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return "cjl_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** SHA-256 of the token, lowercase hex; null for a missing token (which then matches no lease). */
    public static String hash(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
