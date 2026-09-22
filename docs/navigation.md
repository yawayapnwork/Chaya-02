# Navigation and routing

How a real walkable surface becomes a route with a distance, an estimated time, and (optionally)
accessibility constraints.

## Baking: reconstructed geometry to a routing graph

`chaya_worker.stages.navigation_baking` (NAVIGATION_BAKING, the pipeline's last stage) runs once per
successful reconstruction:

1. Takes PLANE_FITTING's largest floor-classified plane and projects its inlier points into the plane's
   own 2D frame (`chaya_worker.navmesh.project_to_plane`).
2. Delaunay-triangulates that footprint (`triangulate_walkable_area`) and carves out any triangle whose
   centroid is within an agent radius of a real wall/furniture point from SEMANTIC_SEGMENTATION's labels
   (`carve_obstacles`) -- a real walkable-surface computation, not an assumption that the whole floor plane
   is walkable.
3. Writes the walkable triangles as a Wavefront OBJ and hands it to **Recast** (`recast-cli`, a required
   external toolchain dependency gated exactly like COLMAP/GLOMAP -- see `chaya_worker.toolchain`) to
   build the actual navmesh polygons and their adjacency.
4. Turns Recast's own polygon output into two routing graphs (`build_routing_graphs`):
   - **STANDARD**: every adjacency Recast found.
   - **STEP_FREE**: only the adjacencies between two polygons that are *both* within an ADA-inspired ramp
     slope threshold (~5 degrees), computed from each polygon's real face normal
     (`polygon_slope_degrees`). This is a geometric computation on the baked mesh, not the STANDARD graph
     with a different name -- with any stairs present, STEP_FREE strictly has fewer edges.
   - Each edge's `min_clearance_m` is the real measured width of the portal (shared boundary) between its
     two polygons (`shared_edge_length`), not an assumption.

The worker has no database access (see ARCHITECTURE.md). `dev.chaya.api.pipeline.PipelineService
#ingestNavigationGraph` reads the `NAVIGATION_GRAPH` artifact when NAVIGATION_BAKING succeeds and writes
each profile as a `navigation_graph` (DRAFT, then promoted to ACTIVE, retiring whichever graph was
previously ACTIVE for that venue/floor/profile) with its `navigation_node`/`navigation_edge` rows.

Elevators are not detected from geometry -- a hallway scan generally cannot see inside one. Floor-to-floor
transitions are resolved entirely at query time (see below), not baked into any one floor's graph, because
`navigation_edge` can never span two different `graph_id`s (its foreign keys are scoped to one graph).

## Routing: `POST /api/v1/navigation/routes`

`dev.chaya.api.navigation.RouteService` does plain Dijkstra over `navigation_node`/`navigation_edge` --
Recast/Detour already did the hard geometric work offline; a weighted shortest path over the baked graph
is all real-time routing needs.

- **Accessibility** (`accessibility: "STEP_FREE"`, default `"STANDARD"`): selects the STEP_FREE-profile
  graph, additionally rejects any edge whose measured `min_clearance_m` is below
  `chaya.navigation.min-accessible-clearance-m`, and only crosses floors via an elevator, never stairs.
  This is never a renamed shortest path: the edges available to it are geometrically different, computed
  at bake time from real slope and clearance data.
- **Multi-floor**: a transition is found by matching a stairs- or elevator-categorised POI on the current
  floor to the closest same-category POI on an adjacent (stairs) or any (elevator) floor, within
  `chaya.navigation.transition-match-radius-meters` -- real POI positions placed by venue staff, not a
  fabricated link table. A route is then floor-local navigation, a transition, and floor-local navigation
  again, repeated per hop.
- **Dynamic obstacles**: `blockedRegions` in the request (each an axis-aligned box the AR client actually
  observed, on one floor) excludes any graph node that falls inside them, for that one query only --
  nothing is written to the database. This is the runtime-obstacle architecture the task calls for: the
  client reports what it detected, the server never invents a person or obstacle itself.
- **Distance and time**: `distanceMeters` is the summed walked distance across every leg. Walking speed is
  a real, commonly used pedestrian-planning constant (~1.3 m/s standard, ~1.0 m/s accessible); floor
  transitions add a flat, configurable time (stairs ~20 s, elevator ~45 s) on top of walking time, not
  counted as walked distance.

### Response

`RouteResponse` returns the full waypoint sequence (each tagged `START`/`WAYPOINT`/`TRANSITION`/
`DESTINATION` and its floor), `distanceMeters`, `estimatedDurationSeconds`, `floorTransitions` (each with
its connector type and the POI that anchors it), the resolved `accessibilityProfile`, and
`accessibilityConstraintsApplied` -- a human-readable list of exactly which constraints were active, so a
client never has to guess what "accessible" excluded.

`ROUTE_UNAVAILABLE` (404) is returned, never a fabricated path, whenever: no ACTIVE graph exists for a
floor, the start or destination cannot be snapped within `chaya.navigation.node-snap-max-distance-meters`
of the graph, the graph is disconnected between them, an obstacle region removes the only path, or no
floor transition connects the start and destination floors (accessibly, when STEP_FREE was requested).
