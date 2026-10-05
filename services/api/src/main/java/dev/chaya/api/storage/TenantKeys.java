package dev.chaya.api.storage;

import java.util.UUID;

/**
 * The object-key layout that ties every stored object to one tenant (docs/security.md, "Object storage").
 *
 * <pre>
 *   org/{org}/venue/{venue}/...           written by the API (raw media) or by a worker (a stage attempt's output prefix)
 *   sealed/org/{org}/venue/{venue}/...    a registered artifact: the API's server-side copy, SHA-256 verified at
 *                                          registration. Worker accounts can read but never write or delete under sealed/
 *                                          (infra/docker/minio/init.sh).
 * </pre>
 *
 * Every key the API builds comes from here, and every key it reads on behalf of a venue is checked against that venue's
 * prefixes first, so a database row that names another tenant's object (a bug, a bad migration, a forged report) is
 * refused instead of served.
 */
public final class TenantKeys {

    public static final String SEALED_ROOT = "sealed/";

    private TenantKeys() {}

    /** "org/{org}/venue/{venue}/": everything this venue owns lives under it (or under its sealed twin). */
    public static String prefix(UUID organizationId, UUID venueId) {
        if (organizationId == null || venueId == null) {
            throw new IllegalArgumentException("an object key needs an organization and a venue");
        }
        return "org/" + organizationId + "/venue/" + venueId + "/";
    }

    public static String sealedPrefix(UUID organizationId, UUID venueId) {
        return SEALED_ROOT + prefix(organizationId, venueId);
    }

    /** Whether the key is a well-formed path under this venue's own (or sealed) prefix. */
    public static boolean belongsTo(String key, UUID organizationId, UUID venueId) {
        if (key == null || organizationId == null || venueId == null || key.contains("..") || key.contains("//")
            || key.contains("\\") || key.startsWith("/")) {
            return false;
        }
        String own = prefix(organizationId, venueId);
        String sealed = SEALED_ROOT + own;
        return (key.startsWith(own) && key.length() > own.length()) || (key.startsWith(sealed) && key.length() > sealed.length());
    }

    /** Throws unless {@link #belongsTo}. The message names the key but never another tenant's ids beyond it. */
    public static String require(String key, UUID organizationId, UUID venueId) {
        if (!belongsTo(key, organizationId, venueId)) {
            throw new TenantKeyViolation(key);
        }
        return key;
    }

    /**
     * The sealed key for a worker-written key under this venue: the same path under sealed/, with a per-registration
     * nonce directory before the file name. The file name is kept (workers name downloaded inputs after it), the pii/
     * segment is kept (the chaya-recon policy denies any key containing /pii/), and the nonce makes two registrations of
     * the same worker key distinct objects, so discarding one can never delete the other.
     */
    public static String sealed(String workerKey, UUID organizationId, UUID venueId, UUID nonce) {
        String own = prefix(organizationId, venueId);
        if (!belongsTo(workerKey, organizationId, venueId) || !workerKey.startsWith(own)) {
            throw new TenantKeyViolation(workerKey);
        }
        int slash = workerKey.lastIndexOf('/');
        return SEALED_ROOT + workerKey.substring(0, slash + 1) + nonce + "/" + workerKey.substring(slash + 1);
    }

    public static boolean isSealed(String key) {
        return key != null && key.startsWith(SEALED_ROOT);
    }

    /** An object key outside the tenant it was used for. Never expected; always a defect or an attack. */
    public static final class TenantKeyViolation extends RuntimeException {
        public TenantKeyViolation(String key) {
            super("object key " + key + " is outside the tenant it was used for");
        }
    }
}
