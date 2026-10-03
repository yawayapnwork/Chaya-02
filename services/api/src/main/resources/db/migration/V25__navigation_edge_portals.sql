-- Review N-3: a route must follow the navmesh, not jump between polygon centroids. Each edge of a Recast-baked graph is
-- a Detour link between two convex polygons; it now stores the link's portal (the shared polygon edge, two points in
-- canonical metres), so RouteService can string-pull the polygon corridor through the portals (the funnel algorithm
-- Detour's findStraightPath uses) instead of reporting centroids. NULL for graphs ingested before this column existed
-- and for SYNTHETIC graphs; RouteService refuses to route a RECAST_NAVMESH graph without portals (NAVMESH_NOT_READY:
-- bake again).
ALTER TABLE navigation_edge
    ADD COLUMN portal_ax double precision,
    ADD COLUMN portal_ay double precision,
    ADD COLUMN portal_az double precision,
    ADD COLUMN portal_bx double precision,
    ADD COLUMN portal_by double precision,
    ADD COLUMN portal_bz double precision,
    ADD CONSTRAINT navigation_edge_portal_complete CHECK (
        num_nulls(portal_ax, portal_ay, portal_az, portal_bx, portal_by, portal_bz) IN (0, 6));
