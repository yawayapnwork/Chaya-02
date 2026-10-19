-- Processing resource usage and cost (docs/operations-dashboard.md, "Resources and cost").
--
--   * job_attempt_usage: one row per job ATTEMPT (job_id, attempt = retry_count + 1), written once when the attempt ends:
--     from the worker's stage report (what the worker measured), or by the control plane when it ends the attempt itself
--     (lease lost, time budget exceeded, cancelled). A retry is a new attempt and a new row; a duplicate or late report
--     of the same attempt can never add a second one. Times are measured; nothing is filled in. GPU figures exist only
--     when the worker observed them, with the reason when it did not.
--   * cost_rate_set: every rate card (chaya.cost.rates) the API has priced with, by content hash. A usage row that was
--     priced names the rate set, the rate entry and the formula it used; an unpriced row says why. These are ESTIMATES.
--   * infrastructure_bill: provider-billed amounts recorded by an admin with their evidence reference (an invoice or
--     billing-export id). Never computed, never allocated to scans.

CREATE TABLE cost_rate_set (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    sha256     char(64) NOT NULL UNIQUE CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    currency   char(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    config     jsonb NOT NULL CHECK (jsonb_typeof(config) = 'object'),
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TRIGGER cost_rate_set_immutable BEFORE UPDATE OR DELETE ON cost_rate_set
    FOR EACH ROW EXECUTE FUNCTION immutable_record();

CREATE TABLE job_attempt_usage (
    job_id                  uuid NOT NULL REFERENCES processing_job (id) ON DELETE RESTRICT,
    attempt                 integer NOT NULL CHECK (attempt >= 1),
    organization_id         uuid NOT NULL,
    venue_id                uuid NOT NULL,
    scan_id                 uuid NOT NULL,
    run_id                  uuid REFERENCES pipeline_run (id) ON DELETE RESTRICT,
    scan_version_id         uuid,
    stage                   text NOT NULL,
    stage_run_id            uuid REFERENCES pipeline_stage_run (id) ON DELETE RESTRICT,
    outcome                 text NOT NULL CHECK (outcome IN ('SUCCEEDED', 'FAILED', 'INTERRUPTED', 'CANCELLED')),
    worker_id               text,
    resource_class          text CHECK (resource_class IS NULL OR length(resource_class) BETWEEN 1 AND 100),
    started_at              timestamptz NOT NULL,
    ended_at                timestamptz NOT NULL,
    -- WORKER_REPORT: the worker's own start/finish; LAST_HEARTBEAT: the worker's last proof of life (it was lost);
    -- CONTROL_PLANE_DETECTION: when the control plane ended it; CANCEL_REQUEST: when an operator cancelled it.
    end_basis               text NOT NULL CHECK (end_basis IN ('WORKER_REPORT', 'LAST_HEARTBEAT', 'CONTROL_PLANE_DETECTION',
                                                                'CANCEL_REQUEST')),
    wall_seconds            double precision NOT NULL CHECK (wall_seconds >= 0 AND wall_seconds < 'Infinity'),
    cpu_seconds             double precision CHECK (cpu_seconds IS NULL OR (cpu_seconds >= 0 AND cpu_seconds < 'Infinity')),
    gpu_seconds             double precision CHECK (gpu_seconds IS NULL OR (gpu_seconds >= 0 AND gpu_seconds < 'Infinity')),
    gpu_model               text,
    gpu_peak_memory_bytes   bigint CHECK (gpu_peak_memory_bytes IS NULL OR gpu_peak_memory_bytes >= 0),
    gpu_unmeasured_reason   text,
    artifact_bytes          bigint NOT NULL DEFAULT 0 CHECK (artifact_bytes >= 0),
    measurements            jsonb NOT NULL CHECK (jsonb_typeof(measurements) = 'object'),
    cost_rate_set_id        uuid REFERENCES cost_rate_set (id) ON DELETE RESTRICT,
    cost_currency           char(3) CHECK (cost_currency IS NULL OR cost_currency ~ '^[A-Z]{3}$'),
    estimated_cost          numeric(18, 6) CHECK (estimated_cost IS NULL OR estimated_cost >= 0),
    cost_basis              jsonb CHECK (cost_basis IS NULL OR jsonb_typeof(cost_basis) = 'object'),
    cost_unavailable_reason text,
    recorded_at             timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (job_id, attempt),
    FOREIGN KEY (venue_id, organization_id) REFERENCES venue (id, organization_id) ON DELETE RESTRICT,
    FOREIGN KEY (scan_id, venue_id) REFERENCES scan (id, venue_id) ON DELETE RESTRICT,
    FOREIGN KEY (scan_version_id) REFERENCES scan_version (id) ON DELETE RESTRICT,
    CHECK (ended_at >= started_at),
    CHECK ((gpu_seconds IS NULL) = (gpu_unmeasured_reason IS NOT NULL)),
    CHECK ((estimated_cost IS NULL) = (cost_currency IS NULL)),
    CHECK ((estimated_cost IS NULL) = (cost_basis IS NULL)),
    CHECK ((estimated_cost IS NULL) = (cost_unavailable_reason IS NOT NULL)),
    CHECK (estimated_cost IS NULL OR cost_rate_set_id IS NOT NULL)
);
CREATE INDEX job_attempt_usage_venue_idx ON job_attempt_usage (venue_id, ended_at);
CREATE INDEX job_attempt_usage_version_idx ON job_attempt_usage (scan_version_id) WHERE scan_version_id IS NOT NULL;
CREATE INDEX job_attempt_usage_run_idx ON job_attempt_usage (run_id) WHERE run_id IS NOT NULL;
CREATE TRIGGER job_attempt_usage_immutable BEFORE UPDATE OR DELETE ON job_attempt_usage
    FOR EACH ROW WHEN (NOT chaya_erasure_active()) EXECUTE FUNCTION immutable_record();

CREATE TABLE infrastructure_bill (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id    uuid NOT NULL,
    venue_id           uuid NOT NULL,
    provider           text NOT NULL CHECK (length(provider) BETWEEN 1 AND 100),
    period_start       date NOT NULL,
    period_end         date NOT NULL,
    amount             numeric(18, 2) NOT NULL CHECK (amount >= 0),
    currency           char(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    evidence_reference text NOT NULL CHECK (length(btrim(evidence_reference)) BETWEEN 1 AND 300),
    description        text CHECK (description IS NULL OR length(description) <= 1000),
    recorded_by        text NOT NULL,
    recorded_at        timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (venue_id, organization_id) REFERENCES venue (id, organization_id) ON DELETE RESTRICT,
    CHECK (period_end >= period_start)
);
CREATE INDEX infrastructure_bill_venue_idx ON infrastructure_bill (venue_id, period_start);
CREATE TRIGGER infrastructure_bill_immutable BEFORE UPDATE OR DELETE ON infrastructure_bill
    FOR EACH ROW WHEN (NOT chaya_erasure_active()) EXECUTE FUNCTION immutable_record();
