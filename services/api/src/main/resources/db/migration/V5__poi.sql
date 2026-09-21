-- Points of interest.
--
-- poi is the stable identity; poi_version holds the semantic and spatial data and is immutable
-- (a change creates a new version). The only permitted update to a poi_version is filling in its
-- embedding once, which happens asynchronously after creation.
--
-- Embedding: vector(512) matches CLIP ViT-B/32. The model is not final (open decision D3); changing
-- the dimension before any embeddings exist is a trivial ALTER. No ANN index is created yet: it is
-- added together with the search feature, when real embeddings exist to size it against.

CREATE TABLE poi (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL,
    venue_id        uuid NOT NULL,
    floor_id        uuid,
    space_id        uuid,
    deleted_at      timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (venue_id, organization_id) REFERENCES venue (id, organization_id) ON DELETE RESTRICT,
    FOREIGN KEY (floor_id, venue_id) REFERENCES floor (id, venue_id) ON DELETE RESTRICT,
    FOREIGN KEY (space_id, venue_id) REFERENCES space (id, venue_id) ON DELETE RESTRICT,
    CHECK (space_id IS NULL OR floor_id IS NOT NULL),
    UNIQUE (id, venue_id)
);

CREATE TABLE poi_version (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL,
    venue_id        uuid NOT NULL,
    poi_id          uuid NOT NULL,
    version_number  integer NOT NULL CHECK (version_number > 0),
    label           text NOT NULL CHECK (length(btrim(label)) > 0),
    category        text,
    description     text,
    tags            text[] NOT NULL DEFAULT '{}',
    -- Position in the floor reconstruction frame, metres.
    x               double precision NOT NULL,
    y               double precision NOT NULL,
    z               double precision NOT NULL,
    attributes      jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(attributes) = 'object'),
    -- Reconstruction this position was measured against, when it came from one.
    scan_version_id uuid,
    embedding       vector(512),
    embedding_model text,
    created_by      text NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (poi_id, venue_id) REFERENCES poi (id, venue_id) ON DELETE RESTRICT,
    FOREIGN KEY (venue_id, organization_id) REFERENCES venue (id, organization_id) ON DELETE RESTRICT,
    FOREIGN KEY (scan_version_id, venue_id) REFERENCES scan_version (id, venue_id) ON DELETE RESTRICT,
    CHECK ((embedding IS NULL) = (embedding_model IS NULL)),
    UNIQUE (poi_id, version_number),
    UNIQUE (id, venue_id)
);

CREATE FUNCTION poi_version_guard() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'poi_version % cannot be deleted; soft-delete the poi instead', OLD.id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    -- Everything except the embedding pair must be unchanged, and an embedding is set only once.
    IF (to_jsonb(OLD) - 'embedding' - 'embedding_model') IS DISTINCT FROM
       (to_jsonb(NEW) - 'embedding' - 'embedding_model')
       OR OLD.embedding IS NOT NULL THEN
        RAISE EXCEPTION 'poi_version % is immutable (only a missing embedding may be filled in)', OLD.id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER poi_version_guard BEFORE UPDATE OR DELETE ON poi_version
    FOR EACH ROW EXECUTE FUNCTION poi_version_guard();
CREATE TRIGGER poi_updated BEFORE UPDATE ON poi FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Access patterns: POIs of a floor / venue; latest version of a POI; versions still lacking an embedding.
CREATE INDEX poi_venue_idx ON poi (venue_id) WHERE deleted_at IS NULL;
CREATE INDEX poi_floor_idx ON poi (floor_id) WHERE deleted_at IS NULL;
CREATE INDEX poi_space_idx ON poi (space_id) WHERE deleted_at IS NULL;
CREATE INDEX poi_version_latest_idx ON poi_version (poi_id, version_number DESC);
CREATE INDEX poi_version_pending_embedding_idx ON poi_version (created_at) WHERE embedding IS NULL;
