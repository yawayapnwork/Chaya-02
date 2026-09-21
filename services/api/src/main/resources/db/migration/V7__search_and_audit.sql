-- Search analytics and the audit log.

CREATE TABLE search_query (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id  uuid NOT NULL,
    venue_id         uuid NOT NULL,
    actor_id         text,                          -- null for anonymous/kiosk use
    query_normalized text NOT NULL CHECK (length(btrim(query_normalized)) > 0),
    result_count     integer NOT NULL CHECK (result_count >= 0),
    latency_ms       integer NOT NULL CHECK (latency_ms >= 0),
    created_at       timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (venue_id, organization_id) REFERENCES venue (id, organization_id) ON DELETE RESTRICT
);

-- Dashboard: recent queries per venue; top queries and zero-result queries per venue.
CREATE INDEX search_query_venue_time_idx ON search_query (venue_id, created_at DESC);
CREATE INDEX search_query_venue_text_idx ON search_query (venue_id, query_normalized);
CREATE INDEX search_query_zero_results_idx ON search_query (venue_id, created_at DESC) WHERE result_count = 0;

-- Append-only audit trail. organization_id is mandatory; venue_id is set for venue-scoped actions.
CREATE TABLE audit_log (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES organization (id) ON DELETE RESTRICT,
    venue_id        uuid,
    actor_id        text NOT NULL,                  -- Keycloak subject, or a service account id
    actor_type      text NOT NULL CHECK (actor_type IN ('USER', 'SERVICE')),
    action          text NOT NULL CHECK (length(btrim(action)) > 0),
    resource_type   text NOT NULL CHECK (length(btrim(resource_type)) > 0),
    resource_id     uuid,
    outcome         text NOT NULL CHECK (outcome IN ('SUCCESS', 'DENIED', 'FAILURE')),
    metadata        jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(metadata) = 'object'),
    occurred_at     timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (venue_id, organization_id) REFERENCES venue (id, organization_id) ON DELETE RESTRICT
);

CREATE FUNCTION audit_log_append_only() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'audit_log is append-only (% rejected)', TG_OP
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER audit_log_append_only BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION audit_log_append_only();

CREATE INDEX audit_log_org_time_idx ON audit_log (organization_id, occurred_at DESC);
CREATE INDEX audit_log_venue_time_idx ON audit_log (venue_id, occurred_at DESC) WHERE venue_id IS NOT NULL;
CREATE INDEX audit_log_resource_idx ON audit_log (resource_type, resource_id) WHERE resource_id IS NOT NULL;
CREATE INDEX audit_log_actor_idx ON audit_log (actor_id, occurred_at DESC);
