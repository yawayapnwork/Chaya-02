package dev.chaya.api.capture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.chaya.api.audit.AuditLogWriter;
import dev.chaya.api.audit.AuditLogWriter.ActorType;
import dev.chaya.api.audit.AuditLogWriter.AuditEvent;
import dev.chaya.api.audit.AuditLogWriter.Outcome;
import dev.chaya.api.capture.MalwareScanner.ScanResult;
import dev.chaya.api.capture.MalwareScanner.Verdict;
import dev.chaya.api.storage.ObjectStore;
import dev.chaya.api.storage.ObjectStore.PartEtag;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Asynchronous validation of an uploaded file. Nothing is ACCEPTED unless every check passes:
 *   assemble parts -> stored size == declared -> SHA-256 == declared -> malware scan is CLEAN ->
 *   real content type (Tika) matches the claim and the allowlist -> (metadata: valid JSON object).
 * Definite failures are REJECTED and the object is deleted. If a check could not be performed
 * (storage or scanner unavailable) the file is QUARANTINED and can be re-validated later.
 */
@Service
public class MediaValidationService {

    private static final Logger log = LoggerFactory.getLogger(MediaValidationService.class);
    private static final int HEAD_BYTES = 64 * 1024;
    private static final String SYSTEM_ACTOR = "system:media-validator";
    // Rejection/quarantine messages are returned to API clients, so they are fixed text: exception messages (storage
    // endpoints, hostnames, SDK internals) are logged here and never stored with the media.
    static final String STORAGE_UNAVAILABLE_MESSAGE =
        "object storage was unavailable during validation; the file is quarantined and can be re-validated";

    private record Job(UUID id, UUID orgId, UUID venueId, MediaKind kind, String status, String claimed, long size,
                       String sha, String key, String uploadId, boolean assembled) {}

    private record Outcome_(boolean accepted, boolean quarantined, String code, String message) {}

    private final JdbcClient jdbc;
    private final ObjectStore store;
    private final ContentTypeDetector detector;
    private final MalwareScanner scanner;
    private final AuditLogWriter auditWriter;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;
    private final ExecutorService executor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "media-validation");
        t.setDaemon(true);
        return t;
    });

    public MediaValidationService(JdbcClient jdbc, ObjectStore store, ContentTypeDetector detector, MalwareScanner scanner,
                                  AuditLogWriter auditWriter, TransactionTemplate tx, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.store = store;
        this.detector = detector;
        this.scanner = scanner;
        this.auditWriter = auditWriter;
        this.tx = tx;
        this.mapper = mapper;
    }

    public void submit(UUID mediaId) {
        executor.submit(() -> {
            try {
                validate(mediaId);
            } catch (RuntimeException e) {
                log.error("validation of media {} crashed", mediaId, e);
                finish(mediaId, new Outcome_(false, true, "VALIDATION_ERROR", "validation could not be completed; it can be retried"), null, null, null);
            }
        });
    }

    /** After a restart, files that were mid-validation are validated again. */
    @EventListener(ApplicationReadyEvent.class)
    void resumeInterrupted() {
        try {
            store.ensureBucket();
        } catch (RuntimeException e) {
            log.warn("object storage not ready at startup: {}", e.getMessage());
        }
        jdbc.sql("SELECT id FROM capture_media WHERE status = 'VALIDATING'").query(UUID.class).list().forEach(this::submit);
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }

    void validate(UUID mediaId) {
        Job j = jdbc.sql("SELECT id, organization_id, venue_id, kind, status, claimed_content_type, declared_size_bytes, "
                + "declared_sha256, object_key, upload_id, assembled FROM capture_media WHERE id = :id").param("id", mediaId)
            .query((rs, i) -> new Job(rs.getObject("id", UUID.class), rs.getObject("organization_id", UUID.class),
                rs.getObject("venue_id", UUID.class), MediaKind.valueOf(rs.getString("kind")), rs.getString("status"),
                rs.getString("claimed_content_type"), rs.getLong("declared_size_bytes"), rs.getString("declared_sha256"),
                rs.getString("object_key"), rs.getString("upload_id"), rs.getBoolean("assembled"))).optional().orElse(null);
        if (j == null || !j.status().equals("VALIDATING")) {
            return;
        }

        // 1. Assemble the multipart upload (once).
        if (!j.assembled()) {
            try {
                List<PartEtag> parts = jdbc.sql("SELECT part_number, etag FROM capture_media_part WHERE media_id = :m")
                    .param("m", mediaId).query((rs, i) -> new PartEtag(rs.getInt("part_number"), rs.getString("etag"))).list();
                store.completeMultipart(j.key(), j.uploadId(), parts);
                jdbc.sql("UPDATE capture_media SET assembled = true WHERE id = :m").param("m", mediaId).update();
            } catch (RuntimeException e) {
                log.warn("media {}: could not assemble the upload", mediaId, e);
                finish(mediaId, new Outcome_(false, true, "STORAGE_UNAVAILABLE", STORAGE_UNAVAILABLE_MESSAGE), j, null, null);
                return;
            }
        }

        // 2. Size, checksum and head bytes in a single pass over the stored object.
        String actualSha;
        byte[] head;
        try {
            long stored = store.size(j.key());
            if (stored != j.size()) {
                finish(mediaId, reject("SIZE_MISMATCH", "stored size " + stored + " differs from declared size " + j.size()), j, null, null);
                return;
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new DigestInputStream(store.open(j.key()), digest)) {
                head = in.readNBytes(HEAD_BYTES);
                in.transferTo(java.io.OutputStream.nullOutputStream());
            }
            actualSha = HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException | RuntimeException e) {
            log.warn("media {}: could not read the stored object", mediaId, e);
            finish(mediaId, new Outcome_(false, true, "STORAGE_UNAVAILABLE", STORAGE_UNAVAILABLE_MESSAGE), j, null, null);
            return;
        }
        if (!actualSha.equals(j.sha())) {
            finish(mediaId, reject("CHECKSUM_MISMATCH", "content SHA-256 does not match the declared checksum"), j, actualSha, null);
            return;
        }

        // 3. Malware scan first: nothing is interpreted (type sniffing, JSON parsing) before the bytes are scanned.
        // Anything but CLEAN keeps the file out.
        ScanResult scan;
        try (InputStream in = store.open(j.key())) {
            scan = scanner.scan(in);
        } catch (IOException | RuntimeException e) {
            log.warn("media {}: could not read the object for scanning", mediaId, e);
            scan = new ScanResult(Verdict.UNAVAILABLE, STORAGE_UNAVAILABLE_MESSAGE);
        }
        if (scan.verdict() == Verdict.INFECTED) {
            finish(mediaId, reject("MALWARE_DETECTED", "malware signature found: " + scan.detail()), j, actualSha, null);
            return;
        }
        if (scan.verdict() == Verdict.UNAVAILABLE) {
            finish(mediaId, new Outcome_(false, true, "SCANNER_UNAVAILABLE", scan.detail()), j, actualSha, null);
            return;
        }
        // 4. Real content type, from the bytes.
        String detected = detector.detect(head);
        Outcome_ typeProblem = checkContentType(j, detected);
        if (typeProblem != null) {
            finish(mediaId, typeProblem, j, actualSha, detected);
            return;
        }
        if (j.kind() == MediaKind.METADATA) {
            detected = "application/json";
            try (InputStream in = store.open(j.key())) {
                JsonNode json = mapper.readTree(in);
                if (json == null || !json.isObject()) {
                    finish(mediaId, reject("INVALID_METADATA", "metadata files must contain a JSON object"), j, actualSha, detected);
                    return;
                }
            } catch (IOException e) {
                finish(mediaId, reject("INVALID_METADATA", "metadata file is not valid JSON"), j, actualSha, detected);
                return;
            }
        }

        finish(mediaId, new Outcome_(true, false, null, null), j, actualSha, detected);
    }

    private Outcome_ checkContentType(Job j, String detected) {
        if (j.kind() == MediaKind.METADATA) {
            return Set.of("application/json", "text/plain").contains(detected) ? null
                : reject("CONTENT_TYPE_MISMATCH", "metadata file content is " + detected + ", not JSON");
        }
        if (!UploadProperties.allowedTypes(j.kind()).contains(detected)) {
            return reject("UNSUPPORTED_CONTENT", "file content is " + detected + ", which is not an accepted " + j.kind() + " format");
        }
        if (!UploadProperties.sameFamily(j.claimed(), detected)) {
            return reject("CONTENT_TYPE_MISMATCH", "file was declared as " + j.claimed() + " but its content is " + detected);
        }
        return null;
    }

    private static Outcome_ reject(String code, String message) {
        return new Outcome_(false, false, code, message);
    }

    /** Records the outcome and its audit row atomically. Rejected files are removed from storage. */
    private void finish(UUID mediaId, Outcome_ o, Job j, String sha, String detected) {
        if (!o.accepted() && !o.quarantined() && j != null) {
            try {
                store.delete(j.key());
            } catch (RuntimeException e) {
                log.warn("could not delete rejected object {}: {}", j.key(), e.getMessage());
            }
        }
        String status = o.accepted() ? "ACCEPTED" : o.quarantined() ? "QUARANTINED" : "REJECTED";
        tx.executeWithoutResult(s -> {
            int rows = jdbc.sql("UPDATE capture_media SET status = :st, verified_sha256 = :sha, detected_content_type = :dt, "
                    + "scan_result = :scan, rejection_code = :rc, rejection_message = :rm, validated_at = now() "
                    + "WHERE id = :id AND status = 'VALIDATING'")
                .param("st", status).param("sha", sha).param("dt", detected).param("scan", o.accepted() ? "CLEAN" : null)
                .param("rc", o.code()).param("rm", o.message()).param("id", mediaId).update();
            if (rows == 1 && j != null) {
                auditWriter.record(new AuditEvent(j.orgId(), j.venueId(), SYSTEM_ACTOR, ActorType.SERVICE,
                    o.accepted() ? "media.accept" : o.quarantined() ? "media.quarantine" : "media.reject",
                    "capture_media", mediaId, o.accepted() ? Outcome.SUCCESS : Outcome.FAILURE,
                    o.code() == null ? Map.of() : Map.of("code", o.code())));
            }
        });
    }
}
