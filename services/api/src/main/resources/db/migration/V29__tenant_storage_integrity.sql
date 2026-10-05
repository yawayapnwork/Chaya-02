-- Tenant integrity at the object-storage boundary and in the worker protocol (docs/security.md, "Object storage" and
-- "Service-to-service authentication"; review S-2).

-- 1. Job leases. A claim issues a random lease token, returned once in the work order; only its SHA-256 is kept.
--    Heartbeat, report, complete and fail must present it (X-Chaya-Lease-Token), so a worker can act only on the job it
--    was handed -- not on another tenant's job, and not on its own job after the lease expired and the job was re-queued.
ALTER TABLE processing_job ADD COLUMN lease_token_sha256 text
    CONSTRAINT processing_job_lease_token_sha256_check CHECK (lease_token_sha256 ~ '^[0-9a-f]{64}$');

-- 2. Sealed artifacts. At registration the API copies each reported object to sealed/<its key, with a nonce directory>,
--    hashes the copy, and records the copy only if it matches the reported SHA-256. Worker storage accounts cannot write
--    or delete under sealed/ (infra/docker/minio/init.sh), so a registered artifact cannot be replaced afterwards.
--    worker_object_key is the key the worker wrote (deleted once the registration commits). Rows from before this
--    migration are unsealed: their bytes are still verified against checksum_sha256 on every read.
ALTER TABLE processing_artifact
    ADD COLUMN sealed boolean NOT NULL DEFAULT false,
    ADD COLUMN worker_object_key text;

-- 3. Every object key is under its own row's tenant prefix (org/{org}/venue/{venue}/, or sealed/ + that). Enforced for
--    every new row; NOT VALID leaves rows written before this migration unchecked rather than failing the migration.
ALTER TABLE processing_artifact ADD CONSTRAINT processing_artifact_key_in_tenant CHECK (
       (NOT sealed AND worker_object_key IS NULL
            AND object_key LIKE 'org/' || organization_id || '/venue/' || venue_id || '/_%')
    OR (sealed
            AND object_key LIKE 'sealed/org/' || organization_id || '/venue/' || venue_id || '/_%'
            AND worker_object_key LIKE 'org/' || organization_id || '/venue/' || venue_id || '/_%')
) NOT VALID;

ALTER TABLE capture_media ADD CONSTRAINT capture_media_key_in_tenant CHECK (
    object_key LIKE 'org/' || organization_id || '/venue/' || venue_id || '/_%'
) NOT VALID;
