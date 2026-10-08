package dev.chaya.api.pipeline;

import dev.chaya.api.storage.ObjectStore;
import dev.chaya.api.storage.StorageException;
import dev.chaya.api.storage.TenantKeys;
import dev.chaya.api.web.ApiException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;

/**
 * Turns a worker-written object into a registered, immutable one (review S-2). Workers write under their stage
 * attempt's prefix with an account that can write anywhere in the derived bucket except sealed/. At registration the API
 * (whose account can write sealed/):
 * <ol>
 *   <li>copies the object server-side to {@link TenantKeys#sealed} -- a key no worker credential can write or delete;</li>
 *   <li>hashes <em>the copy</em> and compares it, and its size, with what the worker reported. Hashing the copy rather
 *       than the original closes the window in which the original could be swapped between check and copy;</li>
 *   <li>records the sealed key. The original is deleted only after the registration commits ({@link #deleteOriginal}),
 *       and a sealed copy whose registration did not commit is removed ({@link #discard}).</li>
 * </ol>
 * Every later read (the next stage's inputs, the viewer, the API's own parsing) uses the sealed key and is verified
 * against the registered SHA-256 again.
 */
public class ArtifactSealer {

    private static final Logger log = LoggerFactory.getLogger(ArtifactSealer.class);

    private final ObjectStore derived;

    public ArtifactSealer(ObjectStore derived) {
        this.derived = derived;
    }

    /** A sealed copy and, for a format the API validates (KSPLAT), what its headers say; stored with the artifact. */
    public record Sealed(String key, Map<String, Object> format) {}

    /** Seals one reported object of this tenant. Refuses (409) a missing object, a size or a SHA-256 that does not match
     * the report, a key outside the tenant's own prefix and, for a KSPLAT, a file that does not meet the viewer contract
     * (KsplatValidator) -- checked on the sealed copy, after its hash, so what was validated is what will be served. */
    public Sealed seal(String kind, String workerKey, String reportedSha256, long reportedSize, UUID organizationId, UUID venueId) {
        boolean ksplat = "KSPLAT".equals(kind);
        if (ksplat) {
            KsplatValidator.requirePlausibleSize(reportedSize); // before copying anything
        }
        String target;
        try {
            target = TenantKeys.sealed(workerKey, organizationId, venueId, UUID.randomUUID());
        } catch (TenantKeys.TenantKeyViolation e) {
            throw new ApiException(HttpStatus.CONFLICT, "ARTIFACT_INVALID", e.getMessage());
        }
        try {
            derived.copy(workerKey, target);
        } catch (StorageException e) {
            throw new ApiException(HttpStatus.CONFLICT, "ARTIFACT_MISSING", "artifact " + workerKey + " was not found in storage");
        }
        try {
            long size = derived.size(target);
            if (size != reportedSize) {
                throw new ApiException(HttpStatus.CONFLICT, "ARTIFACT_SIZE_MISMATCH",
                    "artifact " + workerKey + " is " + size + " bytes in storage but was reported as " + reportedSize);
            }
            String sha = derived.sha256(target);
            if (!sha.equals(reportedSha256)) {
                throw new ApiException(HttpStatus.CONFLICT, "ARTIFACT_CHECKSUM_MISMATCH",
                    "artifact " + workerKey + " hashes to " + sha + " in storage but was reported as " + reportedSha256);
            }
            if (!ksplat) {
                return new Sealed(target, null);
            }
            try (InputStream in = derived.open(target)) {
                return new Sealed(target, KsplatValidator.validate(in, size).asMetadata());
            }
        } catch (IOException e) {
            discard(target);
            throw new ApiException(HttpStatus.CONFLICT, "ARTIFACT_MISSING", "artifact " + workerKey + " could not be read back");
        } catch (RuntimeException e) {
            discard(target);
            throw e;
        }
    }

    /** Removes a sealed copy whose registration did not commit. Best effort: an orphan is unreferenced, never served. */
    public void discard(String sealedKey) {
        try {
            derived.delete(sealedKey);
        } catch (RuntimeException e) {
            log.warn("could not remove unregistered sealed copy {}: {}", sealedKey, e.getMessage());
        }
    }

    /** Removes the worker's original once its sealed copy is registered. Best effort: the original is never referenced
     * again, and the PII purge deletes processing_artifact.worker_object_key as well as the sealed key, so a PII
     * original that survives this is still purged with its run. */
    public void deleteOriginal(String workerKey) {
        try {
            derived.delete(workerKey);
        } catch (RuntimeException e) {
            log.warn("could not remove worker original {} after sealing: {}", workerKey, e.getMessage());
        }
    }
}
