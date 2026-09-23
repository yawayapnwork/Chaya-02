-- Incremental re-scan (docs/rescan.md): capture only a changed region, align it against the venue's
-- existing reconstruction, splice it in, and record the result as a new immutable ScanVersion.
--
-- 1. Two new pipeline stages. Never part of a full-venue reconstruction plan (see
--    dev.chaya.api.pipeline.PipelineDefinition); only PipelineDefinition.INCREMENTAL_STAGES uses them.
ALTER TABLE processing_job DROP CONSTRAINT processing_job_stage_check;
ALTER TABLE processing_job ADD CONSTRAINT processing_job_stage_check CHECK (stage IN (
    'INPUT_VALIDATION', 'FFMPEG_PREPROCESS', 'FRAME_QUALITY_FILTER', 'PRIVACY_PREPROCESS',
    'POSE_ESTIMATION', 'SPLAT_RECONSTRUCTION', 'SEMANTIC_SEGMENTATION', 'GEOMETRIC_CLEANUP',
    'REGION_ALIGNMENT', 'REGION_SPLICE',
    'PLANE_FITTING', 'ARTIFACT_GENERATION', 'NAVIGATION_BAKING', 'SEMANTIC_INDEXING',
    'MEDIA_FILTER', 'SPLAT_TRAINING', 'GEOMETRY_CLEANUP', 'SEGMENTATION', 'OBJECT_DETECTION', 'NAVMESH_GENERATION'));

-- 2. pipeline_run gains an optional scan_version_id: set only for an incremental re-scan run, so the
--    worker's work order can carry the parent version's region geometry and REGION_ALIGNMENT/REGION_SPLICE
--    can be pointed at the right ScanVersion lineage. NULL for an ordinary full-venue run.
ALTER TABLE pipeline_run ADD COLUMN scan_version_id uuid;
ALTER TABLE pipeline_run
    ADD FOREIGN KEY (scan_version_id, scan_id) REFERENCES scan_version (id, scan_id) ON DELETE RESTRICT;

-- 3. capture_session gains the operator's region selection (steps 1-2 of the product flow: select an
--    existing version, select the changed region). Both columns are set together, only for a re-scan
--    capture; NULL/NULL for an ordinary capture. region_geometry is a JSON polygon in the floor's venue
--    frame: {"points": [[x, y], ...]}, at least 3 vertices (enforced in RescanService, which also checks
--    the polygon has a real, non-degenerate area before ever creating the row).
ALTER TABLE capture_session
    ADD COLUMN parent_scan_version_id uuid,
    ADD COLUMN region_geometry jsonb CHECK (region_geometry IS NULL OR jsonb_typeof(region_geometry) = 'object'),
    ADD CONSTRAINT capture_session_rescan_check CHECK ((parent_scan_version_id IS NULL) = (region_geometry IS NULL));
ALTER TABLE capture_session
    ADD FOREIGN KEY (parent_scan_version_id, venue_id) REFERENCES scan_version (id, venue_id) ON DELETE RESTRICT;

-- 4. scan_version gains the immutable record of how it was produced (docs/rescan.md "VERSIONING"):
--    parent_version_id already exists (V3); the rest is added here. All of it is written once, before
--    finalization, by RescanService -- scan_version rows are otherwise unchanged (still DRAFT-mutable,
--    FINALIZED-immutable, per the existing scan_version_guard trigger).
ALTER TABLE scan_version
    ADD COLUMN region_geometry        jsonb CHECK (region_geometry IS NULL OR jsonb_typeof(region_geometry) = 'object'),
    ADD COLUMN alignment_method       text CHECK (alignment_method IS NULL OR alignment_method IN ('FEATURE_RANSAC_ICP')),
    ADD COLUMN alignment_confidence   double precision CHECK (alignment_confidence IS NULL OR (alignment_confidence >= 0 AND alignment_confidence <= 1)),
    ADD COLUMN alignment_residual_m   double precision CHECK (alignment_residual_m IS NULL OR alignment_residual_m >= 0),
    ADD COLUMN changed_artifact_kinds text[] NOT NULL DEFAULT '{}',
    ADD COLUMN processing_config      jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(processing_config) = 'object'),
    ADD CONSTRAINT scan_version_alignment_together_check
        CHECK ((alignment_method IS NULL) = (alignment_confidence IS NULL));

CREATE INDEX capture_session_parent_version_idx ON capture_session (parent_scan_version_id) WHERE parent_scan_version_id IS NOT NULL;
CREATE INDEX scan_version_parent_idx ON scan_version (parent_version_id) WHERE parent_version_id IS NOT NULL;
