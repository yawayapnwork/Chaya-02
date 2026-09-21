package dev.chaya.api.capture;

import dev.chaya.api.audit.AuditService;
import dev.chaya.api.security.Actor;
import dev.chaya.api.security.TenantGuard;
import dev.chaya.api.storage.ObjectStore;
import dev.chaya.api.storage.StorageProperties;
import dev.chaya.api.web.ApiException;
import dev.chaya.api.web.NotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Resumable, chunked media upload through the backend into MinIO (S3 multipart).
 *
 * <p>The backend is in the data path on purpose: bytes are never trusted until the server has
 * verified size, checksum and real content type and had them scanned. Object keys are generated
 * here; the client-supplied file name is sanitized and kept as metadata only.
 */
@Service
public class MediaService {

    public record InitRequest(MediaKind kind, String filename, String contentType, long sizeBytes, String sha256) {}

    public record InitResult(UUID mediaId, int partSizeBytes, int totalParts) {}

    public record MediaView(UUID id, MediaKind kind, MediaStatus status, String filename, String claimedContentType,
                            String detectedContentType, long sizeBytes, String sha256, String rejectionCode,
                            String rejectionMessage, int totalParts, int partSizeBytes, List<Integer> uploadedParts,
                            Instant createdAt) {}

    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");

    private final JdbcClient jdbc;
    private final TenantGuard guard;
    private final AuditService audit;
    private final CaptureService captures;
    private final ObjectStore store;
    private final UploadProperties uploads;
    private final MediaValidationService validation;
    private final TransactionTemplate tx;
    private final String bucket;

    public MediaService(JdbcClient jdbc, TenantGuard guard, AuditService audit, CaptureService captures,
                        ObjectStore store, UploadProperties uploads, MediaValidationService validation,
                        TransactionTemplate tx, StorageProperties storage) {
        this.bucket = storage.bucket();
        this.jdbc = jdbc;
        this.guard = guard;
        this.audit = audit;
        this.captures = captures;
        this.store = store;
        this.uploads = uploads;
        this.validation = validation;
        this.tx = tx;
    }

    static String normalizeContentType(String raw) {
        if (raw == null) {
            return "";
        }
        int semicolon = raw.indexOf(';');
        return (semicolon >= 0 ? raw.substring(0, semicolon) : raw).trim().toLowerCase(Locale.ROOT);
    }

    /** Validates the claim (type, size, checksum format, count) and opens a multipart upload. */
    public InitResult init(Actor actor, UUID venueId, UUID captureId, InitRequest r) {
        CaptureService.CaptureView capture = captures.get(actor, venueId, captureId);
        if (!capture.status().acceptsMedia()) {
            throw new ApiException(HttpStatus.CONFLICT, "CAPTURE_NOT_ACCEPTING_UPLOADS",
                "capture is " + capture.status() + "; media can only be added while it is CREATED or UPLOADING");
        }
        if (r.kind() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_KIND", "kind must be VIDEO, IMAGE or METADATA");
        }
        String claimed = normalizeContentType(r.contentType());
        if (!UploadProperties.allowedTypes(r.kind()).contains(claimed)) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE",
                "content type '" + claimed + "' is not accepted for " + r.kind() + "; allowed: "
                    + UploadProperties.allowedTypes(r.kind()));
        }
        if (r.sizeBytes() <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SIZE", "sizeBytes must be positive");
        }
        long max = uploads.maxBytes(r.kind());
        if (r.sizeBytes() > max) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE",
                r.kind() + " files are limited to " + max + " bytes (declared " + r.sizeBytes() + ")");
        }
        String sha = r.sha256() == null ? "" : r.sha256().toLowerCase(Locale.ROOT);
        if (!SHA256.matcher(sha).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CHECKSUM", "sha256 must be 64 lowercase hex characters");
        }
        int existing = jdbc.sql("SELECT count(*) FROM capture_media WHERE capture_session_id = :c").param("c", captureId)
            .query(Integer.class).single();
        if (existing >= uploads.maxFilesPerCapture()) {
            throw new ApiException(HttpStatus.CONFLICT, "TOO_MANY_FILES",
                "a capture is limited to " + uploads.maxFilesPerCapture() + " files");
        }

        int partSize = uploads.partSizeBytes();
        int totalParts = (int) ((r.sizeBytes() + partSize - 1) / partSize);
        if (totalParts > 10_000) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "file needs more than 10000 parts");
        }
        UUID mediaId = UUID.randomUUID();
        // Server-generated key: only UUIDs, never any client text.
        String key = "org/%s/venue/%s/capture/%s/raw/%s".formatted(actor.organizationId(), venueId, captureId, mediaId);
        String filename = FilenameSanitizer.sanitize(r.filename());
        String uploadId = store.beginMultipart(key, claimed);
        try {
            tx.executeWithoutResult(s -> {
                jdbc.sql("INSERT INTO capture_media (id, organization_id, venue_id, capture_session_id, kind, original_filename, "
                        + "claimed_content_type, declared_size_bytes, declared_sha256, bucket, object_key, upload_id, "
                        + "part_size_bytes, total_parts) VALUES (:id, :o, :v, :c, :k, :f, :ct, :size, :sha, "
                        + ":bucket, :key, :up, :ps, :tp)")
                    .param("id", mediaId).param("o", actor.organizationId()).param("v", venueId).param("c", captureId)
                    .param("k", r.kind().name()).param("f", filename).param("ct", claimed).param("size", r.sizeBytes())
                    .param("sha", sha).param("bucket", bucket).param("key", key).param("up", uploadId)
                    .param("ps", partSize).param("tp", totalParts).update();
                if (capture.status() == CaptureStatus.CREATED) {
                    jdbc.sql("UPDATE capture_session SET status = 'UPLOADING' WHERE id = :c AND status = 'CREATED'")
                        .param("c", captureId).update();
                }
                audit.success(actor, venueId, "media.upload_init", "capture_media", mediaId,
                    Map.of("kind", r.kind().name(), "sizeBytes", r.sizeBytes()));
            });
        } catch (RuntimeException e) {
            store.abortMultipart(key, uploadId);
            throw e;
        }
        return new InitResult(mediaId, partSize, totalParts);
    }

    /** Stores one part. Idempotent: re-sending a part replaces it, which is how interrupted uploads resume. */
    public void uploadPart(Actor actor, UUID venueId, UUID captureId, UUID mediaId, int partNumber, InputStream body,
                           long contentLength, String claimedPartSha256) throws IOException {
        guard.requireVenue(actor, venueId);
        Row m = require(actor, venueId, captureId, mediaId);
        if (m.status() != MediaStatus.PENDING) {
            throw new ApiException(HttpStatus.CONFLICT, "MEDIA_NOT_UPLOADING", "media is " + m.status() + "; parts can no longer be sent");
        }
        if (partNumber < 1 || partNumber > m.totalParts()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PART", "part number must be between 1 and " + m.totalParts());
        }
        long expected = partNumber < m.totalParts() ? m.partSize() : m.size() - (long) m.partSize() * (m.totalParts() - 1);
        if (contentLength < 0) {
            throw new ApiException(HttpStatus.LENGTH_REQUIRED, "LENGTH_REQUIRED", "a Content-Length header is required");
        }
        if (contentLength > expected) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "PART_TOO_LARGE",
                "part " + partNumber + " must be exactly " + expected + " bytes (got " + contentLength + ")");
        }
        if (contentLength != expected) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PART_SIZE_MISMATCH",
                "part " + partNumber + " must be exactly " + expected + " bytes (got " + contentLength + ")");
        }
        byte[] data = body.readNBytes((int) expected);
        if (data.length != expected || body.read() != -1) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PART_SIZE_MISMATCH", "request body does not match Content-Length");
        }
        String actual = sha256Hex(data);
        if (claimedPartSha256 != null && !claimedPartSha256.equalsIgnoreCase(actual)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "CHECKSUM_MISMATCH",
                "part " + partNumber + " arrived corrupted (checksum mismatch); send it again");
        }
        String etag = store.uploadPart(m.objectKey(), m.uploadId(), partNumber, data);
        jdbc.sql("INSERT INTO capture_media_part (media_id, part_number, etag, size_bytes, sha256) VALUES (:m, :p, :e, :s, :h) "
                + "ON CONFLICT (media_id, part_number) DO UPDATE SET etag = EXCLUDED.etag, size_bytes = EXCLUDED.size_bytes, "
                + "sha256 = EXCLUDED.sha256, uploaded_at = now()")
            .param("m", mediaId).param("p", partNumber).param("e", etag).param("s", data.length).param("h", actual).update();
    }

    /** Ends the upload of one file and hands it to asynchronous validation. Also retries a QUARANTINED file. */
    public void complete(Actor actor, UUID venueId, UUID captureId, UUID mediaId) {
        guard.requireVenue(actor, venueId);
        Row m = require(actor, venueId, captureId, mediaId);
        if (m.status() == MediaStatus.PENDING) {
            int have = jdbc.sql("SELECT count(*) FROM capture_media_part WHERE media_id = :m").param("m", mediaId)
                .query(Integer.class).single();
            if (have != m.totalParts()) {
                throw new ApiException(HttpStatus.CONFLICT, "MISSING_PARTS",
                    "only " + have + " of " + m.totalParts() + " parts were received; missing: " + missingParts(mediaId, m.totalParts()));
            }
        } else if (m.status() != MediaStatus.QUARANTINED) {
            throw new ApiException(HttpStatus.CONFLICT, "MEDIA_NOT_COMPLETABLE", "media is " + m.status());
        }
        tx.executeWithoutResult(s -> {
            int rows = jdbc.sql("UPDATE capture_media SET status = 'VALIDATING', rejection_code = NULL, rejection_message = NULL "
                    + "WHERE id = :m AND status = :from").param("m", mediaId).param("from", m.status().name()).update();
            if (rows == 0) {
                throw new ApiException(HttpStatus.CONFLICT, "MEDIA_NOT_COMPLETABLE", "media changed state concurrently");
            }
            audit.success(actor, venueId, "media.upload_complete", "capture_media", mediaId, Map.of());
            // Start validation only after this transaction is visible to the worker thread.
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    validation.submit(mediaId);
                }
            });
        });
    }

    public MediaView get(Actor actor, UUID venueId, UUID captureId, UUID mediaId) {
        guard.requireVenue(actor, venueId);
        return view(require(actor, venueId, captureId, mediaId));
    }

    public List<MediaView> list(Actor actor, UUID venueId, UUID captureId) {
        captures.get(actor, venueId, captureId);
        return jdbc.sql(ROW_SELECT + " WHERE m.capture_session_id = :c AND m.venue_id = :v AND m.organization_id = :o ORDER BY m.created_at")
            .param("c", captureId).param("v", venueId).param("o", actor.organizationId())
            .query(MediaService::mapRow).list().stream().map(this::view).toList();
    }

    // ---- internals -------------------------------------------------------------------------

    private record Row(UUID id, MediaKind kind, MediaStatus status, String filename, String claimed, String detected,
                       long size, String sha, String verifiedSha, String objectKey, String uploadId, int partSize,
                       int totalParts, String rejectionCode, String rejectionMessage, Instant createdAt) {}

    private static final String ROW_SELECT = "SELECT m.id, m.kind, m.status, m.original_filename, m.claimed_content_type, "
        + "m.detected_content_type, m.declared_size_bytes, m.declared_sha256, m.verified_sha256, m.object_key, m.upload_id, "
        + "m.part_size_bytes, m.total_parts, m.rejection_code, m.rejection_message, m.created_at FROM capture_media m";

    private static Row mapRow(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new Row(rs.getObject("id", UUID.class), MediaKind.valueOf(rs.getString("kind")),
            MediaStatus.valueOf(rs.getString("status")), rs.getString("original_filename"),
            rs.getString("claimed_content_type"), rs.getString("detected_content_type"),
            rs.getLong("declared_size_bytes"), rs.getString("declared_sha256"), rs.getString("verified_sha256"),
            rs.getString("object_key"), rs.getString("upload_id"), rs.getInt("part_size_bytes"),
            rs.getInt("total_parts"), rs.getString("rejection_code"), rs.getString("rejection_message"),
            rs.getTimestamp("created_at").toInstant());
    }

    private Row require(Actor actor, UUID venueId, UUID captureId, UUID mediaId) {
        return jdbc.sql(ROW_SELECT + " WHERE m.id = :m AND m.capture_session_id = :c AND m.venue_id = :v AND m.organization_id = :o")
            .param("m", mediaId).param("c", captureId).param("v", venueId).param("o", actor.organizationId())
            .query(MediaService::mapRow).optional().orElseThrow(() -> new NotFoundException("media not found"));
    }

    private MediaView view(Row m) {
        List<Integer> parts = jdbc.sql("SELECT part_number FROM capture_media_part WHERE media_id = :m ORDER BY part_number")
            .param("m", m.id()).query(Integer.class).list();
        return new MediaView(m.id(), m.kind(), m.status(), m.filename(), m.claimed(), m.detected(), m.size(), m.sha(),
            m.rejectionCode(), m.rejectionMessage(), m.totalParts(), m.partSize(), parts, m.createdAt());
    }

    private List<Integer> missingParts(UUID mediaId, int total) {
        List<Integer> have = jdbc.sql("SELECT part_number FROM capture_media_part WHERE media_id = :m").param("m", mediaId)
            .query(Integer.class).list();
        List<Integer> missing = new ArrayList<>();
        for (int p = 1; p <= total; p++) {
            if (!have.contains(p)) {
                missing.add(p);
            }
        }
        return missing;
    }

    static String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
