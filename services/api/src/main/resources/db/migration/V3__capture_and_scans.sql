-- Capture sessions, scans and scan versions.
--
-- A scan_version is one specific reconstruction state of a floor. While DRAFT it may be edited;
-- once FINALIZED it (and its provenance) is immutable and cannot be deleted. Corrections are made
-- by creating a new version that references parent_version_id.

CREATE TABLE capture_session (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL,
    venue_id        uuid NOT NULL,
    floor_id        uuid,
    operator_id     text NOT NULL,               -- Keycloak subject of the operator
    status          text NOT NULL DEFAULT 'IN_PROGRESS'
                    CHECK (status IN ('IN_PROGRESS', 'COMPLETED', 'ABANDONED')),
    started_at      timestamptz NOT NULL DEFAULT now(),
    ended_at        timestamptz,
    bounds          jsonb CHECK (bounds IS NULL OR jsonb_typeof(bounds) = 'object'),
    coverage        jsonb CHECK (coverage IS NULL OR jsonb_typeof(coverage) = 'object'),
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (venue_id, organization_id) REFERENCES venue (id, organization_id) ON DELETE RESTRICT,
    FOREIGN KEY (floor_id, venue_id) REFERENCES floor (id, venue_id) ON DELETE RESTRICT,
    CHECK ((status = 'IN_PROGRESS') = (ended_at IS NULL)),
    UNIQUE (id, venue_id)
);

CREATE TABLE scan (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id    uuid NOT NULL,
    venue_id           uuid NOT NULL,
    capture_session_id uuid NOT NULL,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (venue_id, organization_id) REFERENCES venue (id, organization_id) ON DELETE RESTRICT,
    FOREIGN KEY (capture_session_id, venue_id) REFERENCES capture_session (id, venue_id) ON DELETE RESTRICT,
    UNIQUE (id, venue_id)
);

CREATE TABLE scan_version (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id   uuid NOT NULL,
    venue_id          uuid NOT NULL,
    scan_id           uuid NOT NULL,
    floor_id          uuid NOT NULL,
    version_number    integer NOT NULL CHECK (version_number > 0),
    parent_version_id uuid,
    status            text NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'FINALIZED')),
    -- Provenance: pipeline versions, tool parameters, input media checksums. Written by the
    -- pipeline; must be a non-empty JSON object at finalization.
    provenance        jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(provenance) = 'object'),
    finalized_at      timestamptz,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (scan_id, venue_id) REFERENCES scan (id, venue_id) ON DELETE RESTRICT,
    FOREIGN KEY (venue_id, organization_id) REFERENCES venue (id, organization_id) ON DELETE RESTRICT,
    FOREIGN KEY (floor_id, venue_id) REFERENCES floor (id, venue_id) ON DELETE RESTRICT,
    CHECK ((status = 'FINALIZED') = (finalized_at IS NOT NULL)),
    CHECK (status = 'DRAFT' OR provenance <> '{}'::jsonb),
    UNIQUE (scan_id, version_number),
    UNIQUE (id, scan_id),
    UNIQUE (id, venue_id)
);

-- Added after the table so the self-reference can target the (id, scan_id) unique key.
ALTER TABLE scan_version
    ADD FOREIGN KEY (parent_version_id, scan_id) REFERENCES scan_version (id, scan_id) ON DELETE RESTRICT;

CREATE FUNCTION scan_version_guard() RETURNS trigger AS $$
BEGIN
    IF OLD.status = 'FINALIZED' THEN
        RAISE EXCEPTION 'scan_version % is finalized and immutable', OLD.id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER scan_version_guard BEFORE UPDATE OR DELETE ON scan_version
    FOR EACH ROW EXECUTE FUNCTION scan_version_guard();

CREATE INDEX capture_session_venue_idx ON capture_session (venue_id, started_at DESC);
CREATE INDEX scan_venue_idx ON scan (venue_id, created_at DESC);
CREATE INDEX scan_capture_session_idx ON scan (capture_session_id);
-- Latest finalized reconstruction of a floor is the hot read for viewers and navigation.
CREATE INDEX scan_version_floor_latest_idx ON scan_version (floor_id, finalized_at DESC)
    WHERE status = 'FINALIZED';

CREATE TRIGGER capture_session_updated BEFORE UPDATE ON capture_session FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER scan_updated BEFORE UPDATE ON scan FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER scan_version_updated BEFORE UPDATE ON scan_version FOR EACH ROW EXECUTE FUNCTION set_updated_at();
