-- Navigation graphs. Nodes and edges may only change while their graph is a DRAFT; once ACTIVE the
-- graph is a fixed routing surface, and changes ship as a new graph.

CREATE TABLE navigation_graph (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL,
    venue_id        uuid NOT NULL,
    floor_id        uuid,
    scan_version_id uuid,
    profile         text NOT NULL DEFAULT 'STANDARD' CHECK (profile IN ('STANDARD', 'STEP_FREE')),
    status          text NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'ACTIVE', 'RETIRED')),
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (venue_id, organization_id) REFERENCES venue (id, organization_id) ON DELETE RESTRICT,
    FOREIGN KEY (floor_id, venue_id) REFERENCES floor (id, venue_id) ON DELETE RESTRICT,
    FOREIGN KEY (scan_version_id, venue_id) REFERENCES scan_version (id, venue_id) ON DELETE RESTRICT,
    UNIQUE (id, venue_id)
);

CREATE TABLE navigation_node (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL,
    venue_id        uuid NOT NULL,
    graph_id        uuid NOT NULL,
    floor_id        uuid NOT NULL,
    kind            text NOT NULL DEFAULT 'WAYPOINT'
                    CHECK (kind IN ('WAYPOINT', 'POI', 'ENTRANCE', 'CONNECTOR')),
    poi_id          uuid,
    x               double precision NOT NULL,
    y               double precision NOT NULL,
    z               double precision NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (graph_id, venue_id) REFERENCES navigation_graph (id, venue_id) ON DELETE RESTRICT,
    FOREIGN KEY (floor_id, venue_id) REFERENCES floor (id, venue_id) ON DELETE RESTRICT,
    FOREIGN KEY (poi_id, venue_id) REFERENCES poi (id, venue_id) ON DELETE RESTRICT,
    FOREIGN KEY (venue_id, organization_id) REFERENCES venue (id, organization_id) ON DELETE RESTRICT,
    CHECK ((kind = 'POI') = (poi_id IS NOT NULL)),
    UNIQUE (id, graph_id)
);

CREATE TABLE navigation_edge (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL,
    venue_id        uuid NOT NULL,
    graph_id        uuid NOT NULL,
    from_node_id    uuid NOT NULL,
    to_node_id      uuid NOT NULL,
    length_m        double precision NOT NULL CHECK (length_m > 0),
    step_free       boolean NOT NULL DEFAULT true,
    bidirectional   boolean NOT NULL DEFAULT true,
    created_at      timestamptz NOT NULL DEFAULT now(),
    -- Composite keys force both endpoints to live in the same graph as the edge.
    FOREIGN KEY (from_node_id, graph_id) REFERENCES navigation_node (id, graph_id) ON DELETE RESTRICT,
    FOREIGN KEY (to_node_id, graph_id) REFERENCES navigation_node (id, graph_id) ON DELETE RESTRICT,
    FOREIGN KEY (graph_id, venue_id) REFERENCES navigation_graph (id, venue_id) ON DELETE RESTRICT,
    FOREIGN KEY (venue_id, organization_id) REFERENCES venue (id, organization_id) ON DELETE RESTRICT,
    CHECK (from_node_id <> to_node_id),
    UNIQUE (graph_id, from_node_id, to_node_id)
);

CREATE FUNCTION navigation_graph_content_guard() RETURNS trigger AS $$
DECLARE
    v_graph uuid;
    v_status text;
BEGIN
    v_graph := CASE WHEN TG_OP = 'DELETE' THEN OLD.graph_id ELSE NEW.graph_id END;
    SELECT status INTO v_status FROM navigation_graph WHERE id = v_graph;
    IF v_status IS DISTINCT FROM 'DRAFT' THEN
        RAISE EXCEPTION 'navigation_graph % is % and its nodes and edges are immutable', v_graph, v_status
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER navigation_node_guard BEFORE INSERT OR UPDATE OR DELETE ON navigation_node
    FOR EACH ROW EXECUTE FUNCTION navigation_graph_content_guard();
CREATE TRIGGER navigation_edge_guard BEFORE INSERT OR UPDATE OR DELETE ON navigation_edge
    FOR EACH ROW EXECUTE FUNCTION navigation_graph_content_guard();
CREATE TRIGGER navigation_graph_updated BEFORE UPDATE ON navigation_graph
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- At most one ACTIVE graph per venue, floor and profile (floor-less graphs are keyed on the venue).
CREATE UNIQUE INDEX navigation_graph_one_active_idx
    ON navigation_graph (venue_id, coalesce(floor_id, '00000000-0000-0000-0000-000000000000'::uuid), profile)
    WHERE status = 'ACTIVE';
CREATE INDEX navigation_node_graph_idx ON navigation_node (graph_id);
CREATE INDEX navigation_node_poi_idx ON navigation_node (poi_id) WHERE poi_id IS NOT NULL;
CREATE INDEX navigation_edge_from_idx ON navigation_edge (graph_id, from_node_id);
CREATE INDEX navigation_edge_to_idx ON navigation_edge (graph_id, to_node_id);
