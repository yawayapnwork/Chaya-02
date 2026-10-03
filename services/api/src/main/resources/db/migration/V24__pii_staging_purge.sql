-- Review S-7: PII staging (the run's pii/ objects: unanonymised frames) must not outlive what needs it. The control plane
-- deletes the objects (PipelineService#purgePii) when the privacy stage succeeds, when the run ends SUCCEEDED, PARTIAL or
-- CANCELLED, and when a FAILED run has been FAILED for chaya.pipeline.pii-staging-retention (a sweep, so a deletion that
-- failed is retried too). processing_artifact is write-once, so a purge is recorded here instead: one row per artifact
-- whose object was deleted. A purged artifact is never offered to a stage again (PipelineService#inputs).
CREATE TABLE pii_staging_purge (
    artifact_id uuid PRIMARY KEY REFERENCES processing_artifact (id) ON DELETE RESTRICT,
    run_id      uuid NOT NULL REFERENCES pipeline_run (id) ON DELETE RESTRICT,
    reason      text NOT NULL,
    purged_at   timestamptz NOT NULL DEFAULT now()
);

CREATE TRIGGER pii_staging_purge_immutable BEFORE UPDATE OR DELETE ON pii_staging_purge
    FOR EACH ROW EXECUTE FUNCTION immutable_record();

CREATE INDEX pii_staging_purge_run_idx ON pii_staging_purge (run_id);
-- the sweep looks for PII artifacts without a purge row
CREATE INDEX processing_artifact_pii_idx ON processing_artifact (stage_run_id) WHERE contains_pii;
