# Navigation and routing

How a real walkable surface becomes a route with a distance, an estimated time, and (optionally)
accessibility constraints.

Everything below is in the canonical venue frame -- metres, +Z up ([coordinate-frames.md](coordinate-frames.md)).
That is only true of a reconstruction with a calibrated coordinate frame: without one, NAVIGATION_BAKING fails with
`NOT_CALIBRATED` and routing refuses the floor.

## Baking: reconstructed geometry to a routing graph

`chaya_worker.stages.navigation_baking` (NAVIGATION_BAKING, the pipeline's last stage) runs once per
successful reconstruction:

1. Requires the work order's canonical coordinate frame and moves the reconstruction into canonical metres. Among
   PLANE_FITTING's planes, the floor is the lowest horizontal one (within `navmesh_floor_max_tilt_deg` of +Z) that has
   at least a quarter of the best-supported horizontal plane's inliers (`chaya_worker.navmesh.select_floor_plane`).
   Its inliers' canonical (x, y) are the walkable footprint.
2. Delaunay-triangulates that footprint (`triangulate_walkable_area`) and carves out any triangle whose
   centroid is within an agent radius of a real wall/furniture point from SEMANTIC_SEGMENTATION's labels
   (`carve_obstacles`). Only obstacle points between the agent's climb height and its own height above the floor
   count (`obstacle_band_mask`), so floor clutter and the ceiling never block. The triangulation covers the convex
   hull of the floor points; review finding N-2 (routes across non-floor areas inside that hull) is still open.
3. Writes the walkable triangles as a Wavefront OBJ and hands it to **Recast** (`recast-cli`, a required
   external toolchain dependency gated exactly like COLMAP/GLOMAP -- see `chaya_worker.toolchain`) to
   build the actual navmesh polygons and their adjacency. Recast is +Y up; the OBJ is written, and its polygon output
   read back, through `chaya_worker.recast_boundary`, the only place the axes are converted. No `recast-cli`
   implementing this interface exists in the repository yet (review finding N-1).
4. Turns Recast's own polygon output into two routing graphs (`build_routing_graphs`):
   - **STANDARD**: every adjacency Recast found.
   - **STEP_FREE**: only the adjacencies between two polygons that are *both* within an ADA-inspired ramp
     slope threshold (~5 degrees), computed from each polygon's real face normal
     (`polygon_slope_degrees`). This is a geometric computation on the baked mesh, not the STANDARD graph
     with a different name -- with any stairs present, STEP_FREE strictly has fewer edges.
   - Each edge's `min_clearance_m` is the length, in metres, of the portal (shared boundary) between its two
     polygons (`shared_edge_length`). That is Recast's agent-radius-eroded portal, not the corridor width (review
     finding N-3).

The worker has no database access (see ARCHITECTURE.md). `dev.chaya.api.pipeline.PipelineService
#ingestNavigationGraph` reads the `NAVIGATION_GRAPH` artifact when NAVIGATION_BAKING succeeds and writes
each profile as a `navigation_graph` (DRAFT, then promoted to ACTIVE, retiring whichever graph was
previously ACTIVE for that venue/floor/profile) with its `navigation_node`/`navigation_edge` rows. The artifact must
name the run's ACTIVE canonical frame (`coordinate_frame.id`) or the whole stage report is refused
(`ARTIFACT_FRAME_MISMATCH`); the graph records it as `navigation_graph.coordinate_frame_id`.

Elevators are not detected from geometry -- a hallway scan generally cannot see inside one. Floor-to-floor
transitions are resolved entirely at query time (see below), not baked into any one floor's graph, because
`navigation_edge` can never span two different `graph_id`s (its foreign keys are scoped to one graph).

## Routing: `POST /api/v1/navigation/routes`

`dev.chaya.api.navigation.RouteService` does plain Dijkstra over `navigation_node`/`navigation_edge` --
Recast/Detour already did the hard geometric work offline; a weighted shortest path over the baked graph
is all real-time routing needs.

- **Frames**: the start floor (and the destination floor) must have a current canonical coordinate frame
  (`409 NAVIGATION_NOT_CALIBRATED`); the ACTIVE graph must have been baked in it (`409 NAVIGATION_FRAME_STALE`); the
  destination POI must be placed in it (`409 POI_NOT_CALIBRATED`). The request's `start` and `blockedRegions` are
  canonical metres on that floor.

- **Accessibility** (`accessibility: "STEP_FREE"`, default `"STANDARD"`): selects the STEP_FREE-profile
  graph, additionally rejects any edge whose measured `min_clearance_m` is below
  `chaya.navigation.min-accessible-clearance-m`, and only crosses floors via an elevator, never stairs.
  This is never a renamed shortest path: the edges available to it are geometrically different, computed
  at bake time from real slope and clearance data.
- **Multi-floor**: a transition is found by matching a stairs- or elevator-categorised POI on the current
  floor to the closest same-category POI on an adjacent (stairs) or any (elevator) floor, within
  `chaya.navigation.transition-match-radius-meters` -- real POI positions placed by venue staff, not a
  fabricated link table. A route is then floor-local navigation, a transition, and floor-local navigation
  again, repeated per hop. Comparing positions across floors is only meaningful when both floors share one venue
  datum, so both must be calibrated against surveyed control points (`VENUE_CONTROL_POINTS`); two `FLOOR_LOCAL`
  frames have unrelated origins and headings, and the route is refused (`409 FLOORS_NOT_REGISTERED`).
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
