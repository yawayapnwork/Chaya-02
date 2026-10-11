-- Worker liveness (docs/operations-dashboard.md "Workers", docs/monitoring.md).
--
-- Until now nothing recorded that a worker exists: liveness was inferred from the queue ("jobs wait and nothing runs"),
-- which cannot tell an idle worker from no worker, and cannot say which stage has nobody to run it. Every claim poll
-- (empty or not) and every heartbeat now stamps the calling worker's row, so liveness is a measured timestamp.
--
-- Workers are shared infrastructure that serve every tenant, so this table carries no organization or venue. It holds
-- no job, tenant or capture reference, only what the worker said about itself and which service account said it.
-- Rows not seen for 7 days are pruned by the pipeline enforcer (worker ids that churn with container hostnames would
-- otherwise accumulate).

CREATE TABLE worker_presence (
    worker_id       text PRIMARY KEY CHECK (length(worker_id) BETWEEN 1 AND 200),
    service_subject text NOT NULL CHECK (length(service_subject) BETWEEN 1 AND 200),
    -- The stages the worker last offered to claim (JobStage names). A heartbeat-only worker keeps its last list.
    stages          text[] NOT NULL,
    first_seen_at   timestamptz NOT NULL DEFAULT now(),
    last_seen_at    timestamptz NOT NULL DEFAULT now(),
    -- The last time a claim poll returned a job (NULL: it never got one).
    last_claimed_at timestamptz
);

CREATE INDEX worker_presence_last_seen ON worker_presence (last_seen_at DESC);
