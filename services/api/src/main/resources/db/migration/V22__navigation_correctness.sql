-- Navigation correctness (docs/ADVERSARIAL_REVIEW.md N-3, N-4, N-5; docs/navigation.md).
--
-- 1. navigation_edge.max_slope_deg: the steepest slope measured along the edge against the canonical up axis (+Z), in
--    degrees -- both polygons' face slopes and the centroid-to-centroid grade (chaya_worker.navmesh.build_routing_graphs).
--    NULL means "not measured" (hand-inserted graphs, anything baked before this migration). STEP_FREE routing refuses an
--    edge whose slope or clearance is NULL: an accessibility constraint is only applied to a measured value.
--
-- 2. floor_connection: the only way a route crosses floors. Each floor is reconstructed on its own, so two floors'
--    coordinates are never compared with each other; instead venue staff register an explicit connection between a
--    landing POI on one floor and a landing POI on another (each in its own floor's frame). Stairs and ramps are walked,
--    so their walked length is registered with them (measured on site, metres). Accessibility-relevant geometry that
--    no reconstruction can see across two floors -- a ramp's slope, a door or car's clear width -- is registered too, and
--    STEP_FREE uses a connection only when it is known and passes (dev.chaya.api.navigation.RouteService).

ALTER TABLE navigation_edge
    ADD COLUMN max_slope_deg double precision CHECK (max_slope_deg IS NULL OR (max_slope_deg >= 0 AND max_slope_deg <= 90));

CREATE TABLE floor_connection (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id   uuid NOT NULL,
    venue_id          uuid NOT NULL,
    connector_type    text NOT NULL CHECK (connector_type IN ('STAIRS', 'RAMP', 'ELEVATOR')),
    from_floor_id     uuid NOT NULL,
    from_poi_id       uuid NOT NULL,
    to_floor_id       uuid NOT NULL,
    to_poi_id         uuid NOT NULL,
    bidirectional     boolean NOT NULL DEFAULT true,
    length_m          double precision CHECK (length_m IS NULL OR length_m > 0),
    max_slope_deg     double precision CHECK (max_slope_deg IS NULL OR (max_slope_deg >= 0 AND max_slope_deg <= 90)),
    min_clearance_m   double precision CHECK (min_clearance_m IS NULL OR min_clearance_m > 0),
    status            text NOT NULL DEFAULT 'IN_SERVICE' CHECK (status IN ('IN_SERVICE', 'OUT_OF_SERVICE')),
    created_by        text NOT NULL,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    deleted_at        timestamptz,
    FOREIGN KEY (venue_id, organization_id) REFERENCES venue (id, organization_id) ON DELETE RESTRICT,
    FOREIGN KEY (from_floor_id, venue_id) REFERENCES floor (id, venue_id) ON DELETE RESTRICT,
    FOREIGN KEY (to_floor_id, venue_id) REFERENCES floor (id, venue_id) ON DELETE RESTRICT,
    FOREIGN KEY (from_poi_id, venue_id) REFERENCES poi (id, venue_id) ON DELETE RESTRICT,
    FOREIGN KEY (to_poi_id, venue_id) REFERENCES poi (id, venue_id) ON DELETE RESTRICT,
    CHECK (from_floor_id <> to_floor_id),
    -- A walked connector's distance is part of the route's distance, so it must be known.
    CONSTRAINT floor_connection_walked_length CHECK (connector_type = 'ELEVATOR' OR length_m IS NOT NULL),
    -- An elevator is not walked; a length on it would be double-counted as walking distance.
    CONSTRAINT floor_connection_elevator_not_walked CHECK (connector_type <> 'ELEVATOR' OR length_m IS NULL),
    UNIQUE (id, venue_id)
);

CREATE TRIGGER floor_connection_updated BEFORE UPDATE ON floor_connection
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE INDEX floor_connection_venue_idx ON floor_connection (venue_id) WHERE deleted_at IS NULL;
