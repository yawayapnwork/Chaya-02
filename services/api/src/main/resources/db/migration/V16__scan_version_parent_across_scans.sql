-- Fix: an incremental re-scan's version could never be created.
--
-- V3 declared scan_version.parent_version_id as a composite foreign key (parent_version_id, scan_id) ->
-- scan_version (id, scan_id): a parent had to belong to the SAME scan. V15's re-scan design (docs/rescan.md,
-- RescanService#startIncrementalProcessing) deliberately creates the new version under the re-scan's own scan, with its
-- parent being the finalized version of an EARLIER scan of the same floor -- so every such insert violated the V3 key and
-- starting processing for a re-scan failed with 409 CONFLICT. Found by running RescanControlPlaneTest against real
-- PostgreSQL in CI.
--
-- The lineage is per venue (and per floor, which RescanService checks: VERSION_WRONG_FLOOR), not per scan. The new key
-- keeps the tenant guarantee of ADR 0002 -- a parent can never be in another venue, hence never in another
-- organization -- while allowing versions to chain across scans.
ALTER TABLE scan_version DROP CONSTRAINT IF EXISTS scan_version_parent_version_id_scan_id_fkey;
ALTER TABLE scan_version
    ADD CONSTRAINT scan_version_parent_version_venue_fkey
        FOREIGN KEY (parent_version_id, venue_id) REFERENCES scan_version (id, venue_id) ON DELETE RESTRICT,
    ADD CONSTRAINT scan_version_parent_not_self_check CHECK (parent_version_id IS DISTINCT FROM id);
