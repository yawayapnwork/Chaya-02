-- Incremental re-scan: explicit rejection and a complete, immutable lineage record (docs/rescan.md, "VERSIONING").
--
-- 1. ALIGNMENT_REJECTED: a version whose alignment failed a quality gate. It is terminal and immutable like FINALIZED.
--    It is never merged, never current, and never retried: a new re-scan starts a new version. A version stuck in DRAFT
--    now only means an attempt that has not finished, or failed for some other reason.
ALTER TABLE scan_version DROP CONSTRAINT IF EXISTS scan_version_status_check;
ALTER TABLE scan_version ADD CONSTRAINT scan_version_status_check CHECK (status IN ('DRAFT', 'FINALIZED', 'ALIGNMENT_REJECTED'));

-- 2. What a version records, beyond V15's columns:
--    alignment_report  the worker's full alignment report (mode, every gate with its value and threshold, the metrics, the
--                      correction and frame transforms, the exact input artifacts), whatever the outcome
--    splice_report     exactly what was replaced: the volume, the counts, the seam measurement, the input and output
--                      artifacts, and the SPLICE_INDEX artifact that lists every removed and added Gaussian
--    created_by        the operator who started the re-scan
--    rejected_at       when the alignment was rejected
ALTER TABLE scan_version
    ADD COLUMN alignment_report jsonb CHECK (alignment_report IS NULL OR jsonb_typeof(alignment_report) = 'object'),
    ADD COLUMN splice_report jsonb CHECK (splice_report IS NULL OR jsonb_typeof(splice_report) = 'object'),
    ADD COLUMN created_by text,
    ADD COLUMN rejected_at timestamptz,
    ADD CONSTRAINT scan_version_rejected_at_check CHECK ((status = 'ALIGNMENT_REJECTED') = (rejected_at IS NOT NULL));

-- 3. The alignment methods chaya_worker.region_alignment now reports. FEATURE_RANSAC_ICP stays valid for rows written
--    before this migration.
ALTER TABLE scan_version DROP CONSTRAINT IF EXISTS scan_version_alignment_method_check;
ALTER TABLE scan_version ADD CONSTRAINT scan_version_alignment_method_check
    CHECK (alignment_method IS NULL OR alignment_method IN ('FEATURE_RANSAC_ICP', 'DIRECT_CANONICAL_ICP', 'FEATURE_SIMILARITY_ICP'));

-- 4. A rejected version is as immutable as a finalized one.
CREATE OR REPLACE FUNCTION scan_version_guard() RETURNS trigger AS $$
BEGIN
    IF OLD.status IN ('FINALIZED', 'ALIGNMENT_REJECTED') THEN
        RAISE EXCEPTION 'scan_version % is % and immutable', OLD.id,
            CASE OLD.status WHEN 'FINALIZED' THEN 'finalized' ELSE 'alignment-rejected' END
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
