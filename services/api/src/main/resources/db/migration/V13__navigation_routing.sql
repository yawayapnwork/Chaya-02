-- Real pedestrian routing over navigation_graph/navigation_node/navigation_edge (V6__navigation.sql),
-- which existed but nothing wrote or read until now. Two additions the routing engine needs:
--
-- 1. navigation_node.connector_type: which kind of vertical circulation a CONNECTOR node is. A CONNECTOR
--    on two adjacent floors' graphs that share the same poi_id (a stairs/elevator POI placed by venue
--    staff -- see dev.chaya.api.navigation.RouteService) is how a route crosses floors: the schema never
--    allows an edge between two different graph_id values (see navigation_edge's composite FKs), so a
--    floor transition is a distinct routing step, not a normal weighted edge. This is why the task's
--    "floor-local navigation + transition + next-floor navigation" shape is a real constraint here, not
--    just an implementation choice.
-- 2. navigation_edge.min_clearance_m: the narrowest real passage width the baked geometry measured along
--    that edge. The accessible profile rejects edges below chaya.navigation.min-accessible-clearance-m
--    instead of only trusting the STEP_FREE graph's own edge selection, which only knows about stairs.

ALTER TABLE navigation_node
    ADD COLUMN connector_type text CHECK (connector_type IN ('STAIRS', 'ELEVATOR', 'ESCALATOR', 'RAMP')),
    ADD CONSTRAINT navigation_node_connector_type_only_for_connectors
        CHECK ((kind = 'CONNECTOR') = (connector_type IS NOT NULL));

ALTER TABLE navigation_edge
    ADD COLUMN min_clearance_m double precision CHECK (min_clearance_m IS NULL OR min_clearance_m > 0);
