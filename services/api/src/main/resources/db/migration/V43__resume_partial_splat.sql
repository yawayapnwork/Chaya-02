-- Resuming a time-boxed Gaussian Splatting run (docs/pipeline.md, "Resuming training"; gaussian-splatting-audit D-3).
--
-- A PARTIAL run is terminal (V10, V17) with one exception, added here: a run that ended PARTIAL because the time budget
-- stopped SPLAT_RECONSTRUCTION's training (failure_stage SPLAT_RECONSTRUCTION, failure_code TIME_LIMIT_EXCEEDED) may go
-- back to RUNNING, so PipelineService#retry can resume that training from its checkpoint. Every other PARTIAL run (the
-- budget ran out between stages) stays terminal, and every other transition is exactly as before. Whether a resumable
-- checkpoint exists is checked by the API, which refuses the retry otherwise.
CREATE OR REPLACE FUNCTION pipeline_run_guard() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'pipeline_run % cannot be deleted', OLD.id USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF OLD.reconstruction_frame_run_id IS NULL AND NEW.reconstruction_frame_run_id IS NOT NULL
       AND (to_jsonb(NEW) - 'reconstruction_frame_run_id' - 'updated_at') = (to_jsonb(OLD) - 'reconstruction_frame_run_id' - 'updated_at') THEN
        RETURN NEW;
    END IF;
    IF NEW.id <> OLD.id OR NEW.organization_id <> OLD.organization_id OR NEW.venue_id <> OLD.venue_id
       OR NEW.scan_id <> OLD.scan_id OR NEW.stages <> OLD.stages OR NEW.privacy_enabled <> OLD.privacy_enabled
       OR NEW.reconstruction_frame_run_id IS DISTINCT FROM OLD.reconstruction_frame_run_id THEN
        RAISE EXCEPTION 'pipeline_run % identity, plan and reconstruction frame are immutable', OLD.id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF NEW.status <> OLD.status AND NOT (
           (OLD.status = 'RUNNING' AND NEW.status IN ('SUCCEEDED', 'PARTIAL', 'FAILED', 'CANCELLED'))
        OR (OLD.status = 'FAILED'  AND NEW.status = 'RUNNING')
        OR (OLD.status = 'PARTIAL' AND NEW.status = 'RUNNING'
            AND OLD.failure_stage = 'SPLAT_RECONSTRUCTION' AND OLD.failure_code = 'TIME_LIMIT_EXCEEDED')) THEN
        RAISE EXCEPTION 'invalid pipeline_run transition % -> %', OLD.status, NEW.status
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF NEW.status = OLD.status AND OLD.status IN ('SUCCEEDED', 'PARTIAL', 'CANCELLED') THEN
        RAISE EXCEPTION 'pipeline_run % is terminal (%)', OLD.id, OLD.status USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
