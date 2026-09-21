-- Tenancy hierarchy: organization -> venue -> floor -> space.
--
-- Isolation rule: every child row carries organization_id (and venue_id where relevant) and
-- references its parent with a COMPOSITE foreign key, e.g. (venue_id, organization_id) ->
-- venue(id, organization_id). A row can therefore never point at a venue of another organization,
-- even through application bugs.
--
-- Deletion semantics: business entities are soft-deleted (deleted_at). All foreign keys are
-- ON DELETE RESTRICT, so a hard delete of a parent that still has children fails.

CREATE FUNCTION set_updated_at() RETURNS trigger AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TABLE organization (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    slug        text NOT NULL UNIQUE CHECK (slug ~ '^[a-z0-9][a-z0-9-]{1,62}$'),
    name        text NOT NULL CHECK (length(btrim(name)) > 0),
    deleted_at  timestamptz,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE venue (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES organization (id) ON DELETE RESTRICT,
    slug            text NOT NULL CHECK (slug ~ '^[a-z0-9][a-z0-9-]{1,62}$'),
    name            text NOT NULL CHECK (length(btrim(name)) > 0),
    timezone        text NOT NULL DEFAULT 'UTC',
    deleted_at      timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, slug),
    UNIQUE (id, organization_id)
);

CREATE TABLE floor (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL,
    venue_id        uuid NOT NULL,
    level           integer NOT NULL,
    name            text NOT NULL CHECK (length(btrim(name)) > 0),
    -- Fiducial anchor configuration in the floor reconstruction frame. The shape is owned by the
    -- AR contract (packages/contracts); the database only requires a JSON object.
    anchor_config   jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(anchor_config) = 'object'),
    deleted_at      timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (venue_id, organization_id) REFERENCES venue (id, organization_id) ON DELETE RESTRICT,
    UNIQUE (venue_id, level),
    UNIQUE (id, venue_id)
);

CREATE TABLE space (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL,
    venue_id        uuid NOT NULL,
    floor_id        uuid NOT NULL,
    name            text NOT NULL CHECK (length(btrim(name)) > 0),
    kind            text NOT NULL DEFAULT 'ROOM',
    -- Optional 2D footprint polygon in the floor frame; validated by the API.
    boundary        jsonb CHECK (boundary IS NULL OR jsonb_typeof(boundary) = 'array'),
    deleted_at      timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (floor_id, venue_id) REFERENCES floor (id, venue_id) ON DELETE RESTRICT,
    FOREIGN KEY (venue_id, organization_id) REFERENCES venue (id, organization_id) ON DELETE RESTRICT,
    UNIQUE (id, floor_id),
    UNIQUE (id, venue_id)
);

-- Access patterns: list venues of an org, floors of a venue, spaces of a floor (live rows only).
CREATE INDEX venue_org_idx ON venue (organization_id) WHERE deleted_at IS NULL;
CREATE INDEX floor_venue_idx ON floor (venue_id, level) WHERE deleted_at IS NULL;
CREATE INDEX space_floor_idx ON space (floor_id) WHERE deleted_at IS NULL;

CREATE TRIGGER organization_updated BEFORE UPDATE ON organization FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER venue_updated BEFORE UPDATE ON venue FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER floor_updated BEFORE UPDATE ON floor FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER space_updated BEFORE UPDATE ON space FOR EACH ROW EXECUTE FUNCTION set_updated_at();
