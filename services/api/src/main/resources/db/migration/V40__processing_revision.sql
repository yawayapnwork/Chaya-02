-- A totally ordered revision of a capture's processing state (docs/pipeline.md, "Live progress").
--
-- Every row a capture's processing status is read from -- the capture session, its pipeline run, the run's stage
-- records and its processing jobs -- carries change_seq, a value from one sequence assigned when the row is inserted and
-- re-assigned whenever it changes in a way the status shows. A job's lease renewal (a heartbeat) is not such a change.
--
-- The status's revision is the SUM of those values over the capture's rows, read in one statement (one snapshot). Each
-- committed change replaces a row's value by a strictly larger one (a later nextval) or adds a row, and nothing is
-- deleted (except by erasure, after which the capture is gone), so the revision strictly increases with every committed
-- change and two reads of the same committed state give the same revision. This holds even when transactions commit in
-- another order than they drew their sequence values, which a MAX would not. Clients order and de-duplicate updates by it.
--
-- The trigger is named to run after the tables' own guards (BEFORE triggers fire in name order), which compare rows and
-- must not see change_seq move.

CREATE SEQUENCE pipeline_change_seq;

ALTER TABLE capture_session ADD COLUMN change_seq bigint NOT NULL DEFAULT nextval('pipeline_change_seq');
ALTER TABLE pipeline_run ADD COLUMN change_seq bigint NOT NULL DEFAULT nextval('pipeline_change_seq');
ALTER TABLE pipeline_stage_run ADD COLUMN change_seq bigint NOT NULL DEFAULT nextval('pipeline_change_seq');
ALTER TABLE processing_job ADD COLUMN change_seq bigint NOT NULL DEFAULT nextval('pipeline_change_seq');

CREATE FUNCTION processing_change_seq_bump() RETURNS trigger AS $$
DECLARE
    ignored text[] := ARRAY['change_seq', 'updated_at'];
BEGIN
    IF TG_TABLE_NAME = 'processing_job' THEN
        ignored := ignored || ARRAY['lease_expires_at'];
    END IF;
    IF (to_jsonb(NEW) - ignored) IS DISTINCT FROM (to_jsonb(OLD) - ignored) THEN
        NEW.change_seq := nextval('pipeline_change_seq');
    ELSE
        NEW.change_seq := OLD.change_seq;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER zz_processing_change_seq BEFORE UPDATE ON capture_session
    FOR EACH ROW EXECUTE FUNCTION processing_change_seq_bump();
CREATE TRIGGER zz_processing_change_seq BEFORE UPDATE ON pipeline_run
    FOR EACH ROW EXECUTE FUNCTION processing_change_seq_bump();
CREATE TRIGGER zz_processing_change_seq BEFORE UPDATE ON processing_job
    FOR EACH ROW EXECUTE FUNCTION processing_change_seq_bump();
