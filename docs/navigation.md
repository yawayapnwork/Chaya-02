# Navigation and routing

How a real walkable surface becomes a route with a distance, an estimated time, and (optionally)
accessibility constraints.

Everything below is in the canonical venue frame -- metres, +Z up ([coordinate-frames.md](coordinate-frames.md)).
That is only true of a reconstruction with a calibrated coordinate frame: without one, NAVIGATION_BAKING fails with
`NOT_CALIBRATED` and routing refuses the floor.

## Baking: reconstructed geometry to a navmesh and a routing graph

`chaya_worker.stages.navigation_baking` (NAVIGATION_BAKING, the pipeline's last stage) runs once per successful
reconstruction. Recast and Detour do the geometric work: the real upstream
[recastnavigation](https://github.com/recastnavigation/recastnavigation) library (zlib licence), release **1.6.0**,
built into a small CLI in this repository.

### The Recast/Detour tool: `chaya-navmesh`

`services/reconstruction/native/chaya-navmesh` is a C++ program linked against recastnavigation's `Recast` and `Detour`
libraries. Its `CMakeLists.txt` downloads the upstream release tarball pinned by tag **and SHA-256** (CMake refuses a
mismatch; `-DFETCHCONTENT_SOURCE_DIR_RECASTNAVIGATION=<dir>` builds offline from an unpacked copy of the same release).
`src/main.cpp` implements nothing geometric. It reads an OBJ, turns metre settings into Recast's voxel `rcConfig`,
calls the library in the order of recastnavigation's own `Sample_SoloMesh`, and serialises the result:

```
chaya-navmesh --version                     # "chaya-navmesh 1.0.0 recastnavigation 1.6.0"
chaya-navmesh bake --input g.obj --navmesh out.bin --report out.json  --cell-size-m ... (every setting, no defaults)
chaya-navmesh path --navmesh out.bin --start X Y Z --end X Y Z --half-extents X Y Z --output path.json
```

- `bake` runs the full solo-mesh pipeline:
  - walkable-triangle marking by slope (`rcMarkWalkableTriangles`). Faces in an OBJ group named `obstacle*` are forced
    unwalkable but still solid.
  - rasterisation; the low-hanging-obstacle, ledge and low-height span filters (agent climb and height).
  - compact heightfield, then erosion by the agent radius (`rcErodeWalkableArea`).
  - watershed regions (`rcBuildDistanceField`, `rcBuildRegions`), contours (`rcBuildContours`), polygons
    (`rcBuildPolyMesh`) and the detail mesh (`rcBuildPolyMeshDetail`).
  - the Detour tile (`dtCreateNavMeshData`). This is written verbatim as the navmesh file, which `dtNavMesh::init`
    loads.

  The report records the metre settings, the derived voxel settings, per-stage counts, and every ground polygon with
  its links and portal edges. The polygons are read back from the initialised `dtNavMesh`, so they are what a Detour
  query traverses.
- `path` runs Detour's own query: `findNearestPoly`, `findPath`, then `findStraightPath` (string pulling). A start or
  end off the navmesh, or disconnected from the other, is `PATH_NOT_FOUND`. A partial path is never returned.
- Exit status (also the report's `status`): 0 OK, 2 INVALID_ARGUMENTS, 3 INVALID_GEOMETRY, 4 NO_WALKABLE_SURFACE,
  5 NAVMESH_BUILD_FAILED, 6 PATH_NOT_FOUND, 7 IO_ERROR.

The worker finds the tool through `CHAYA_NAVMESH_BIN` or `PATH` (`chaya_worker.toolchain`). A binary that does not
answer `--version` as chaya-navmesh is rejected. The worker image (`services/reconstruction/Dockerfile`) builds and
installs it, and the `python` CI workflow builds it and fails if the real-tool tests skip.

Build locally from `services/reconstruction`:

```
cmake -S native/chaya-navmesh -B native/chaya-navmesh/build -DCMAKE_BUILD_TYPE=Release
cmake --build native/chaya-navmesh/build
```

### The coordinate boundary

Chaya is canonical metres with +Z up. Recast is metres with +Y up. Exactly one module converts between them,
`chaya_worker.recast_boundary`:

- `canonical_to_recast` / `recast_to_canonical`: the proper rotation of −90° about X.
- `canonical_half_extents_to_recast`: Detour search boxes.

Only `chaya_worker.recast` (the module that runs the tool) imports it. A unit test enforces that. `chaya_worker.navmesh`,
the stage and everything downstream only ever hold canonical coordinates.

### The stage

1. **Cleaned geometry → canonical metres.** Requires the work order's canonical coordinate frame (otherwise
   `NOT_CALIBRATED`). Its inputs are the cleaned splat (SPLAT_MERGED / SPLAT_CLEAN / SPLAT), PLANE_FITTING's planes and
   SEMANTIC_SEGMENTATION's cleaned labels, all moved into canonical metres. The floor is the lowest horizontal plane
   (within `navmesh_floor_max_tilt_deg` of +Z) with at least a quarter of the best-supported horizontal plane's inliers.
2. **Recast input geometry** (`chaya_worker.navmesh.geometry_from_reconstruction`):
   - Floor: an occupancy grid of `navmesh_floor_grid_m` cells over the floor inliers. Only cells with at least
     `navmesh_floor_min_points_per_cell` real floor points become walkable candidates, so unscanned gaps and the outside
     of an L-shaped room stay holes. This replaces the old convex-hull Delaunay step (review N-2).
   - Obstacles: wall/furniture points between the agent's climb height and its height become closed boxes, marked
     blocked.

   The geometry is published as **NAVMESH_INPUT_GEOMETRY** (a canonical-frame OBJ).
3. **Recast bake** (`chaya_worker.recast.bake`). Every setting comes from `RecastConfig`, and every field is named by its
   unit. Defaults are in `chaya_worker.settings`, each overridable by an env var of the same name upper-cased:

   | Setting | Default | Setting | Default |
   |---|---|---|---|
   | `navmesh_cell_size_m` | 0.1 | `navmesh_region_min_area_m2` | 0.64 |
   | `navmesh_cell_height_m` | 0.05 | `navmesh_region_merge_area_m2` | 4.0 |
   | `navmesh_agent_height_m` | 1.8 | `navmesh_edge_max_len_m` | 6.0 |
   | `navmesh_agent_radius_m` | 0.35 | `navmesh_edge_max_error_m` | 0.13 |
   | `navmesh_agent_max_climb_m` | 0.4 | `navmesh_verts_per_poly` | 6 (a count) |
   | `navmesh_agent_max_slope_deg` | 45 | `navmesh_detail_sample_dist_m` / `_max_error_m` | 0.6 / 0.05 |

   The old settings were Recast-demo voxel counts labelled as metres (for example `edge_max_len = 12`), and have been
   replaced.
4. **NAVMESH** (`navmesh.bin`, the Detour tile) and **NAVMESH_MANIFEST** (`navmesh-manifest.json`). The manifest
   records:
   - `status: READY`;
   - the navmesh's SHA-256, size and polygon count;
   - the source run, scan and scan version;
   - the splat, plane-model and label input artifacts (id, key, SHA-256);
   - the input-geometry checksum;
   - the canonical frame and the Recast frame with its conversion;
   - the Recast configuration in metres and in voxels;
   - the tool and recastnavigation versions;
   - per-stage build counts.
5. **NAVIGATION_GRAPH** (`chaya_worker.stages.navigation_baking.navigation_graph_document`): the Detour polygon graph.
   - Nodes are polygon centroids. Edges are Detour links, weighted by centroid distance.
   - `min_clearance_m` is the length of the Detour portal. Recast has already eroded that portal by the agent radius,
     so it is not the wall-to-wall width (review N-3).
   - **STANDARD**: every link.
   - **STEP_FREE**: only links between two polygons that are both within `navmesh_max_ramp_slope_deg` (~5°) of level,
     measured against canonical +Z.
   - The graph carries a `navmesh` block: `source: RECAST_NAVMESH`, `status: READY`, the navmesh SHA-256, and the tool
     and library versions.

Failure states, each a structured stage failure with nothing published: `NAVMESH_TOOL_UNAVAILABLE` (no chaya-navmesh
on the worker), `INVALID_GEOMETRY` (non-finite, out-of-range or empty geometry, or a plane referencing missing
Gaussians), `NO_WALKABLE_SURFACE` (no floor plane, no observed floor cell, or nothing left after slope, clearance and
erosion), `NAVMESH_BUILD_FAILED` (Recast/Detour failure, or more cells than one tile holds). Missing inputs remain
`INPUT_INVALID`, and a missing frame remains `NOT_CALIBRATED`.

### Ingestion

The worker has no database access (see ARCHITECTURE.md). `dev.chaya.api.pipeline.PipelineService#ingestNavigationGraph`
reads the `NAVIGATION_GRAPH` artifact when NAVIGATION_BAKING succeeds. It then checks two things:

- The graph must name the run's ACTIVE canonical frame (`ARTIFACT_FRAME_MISMATCH` otherwise).
- The graph must be bound to the NAVMESH artifact of the same report: source RECAST_NAVMESH, status READY, and
  `sha256` equal to that artifact's checksum. Otherwise the report is refused with `NAVMESH_BINDING_INVALID`.

Each profile is written as a `navigation_graph` (DRAFT, then ACTIVE, retiring the previous ACTIVE one) with its
nodes and edges. V18 stores the binding on the graph: `source = 'RECAST_NAVMESH'`, `pipeline_run_id`,
`navmesh_artifact_id`, `navmesh_manifest_artifact_id`, `navmesh_sha256`, `navmesh_tool_version`,
`recastnavigation_version`. A CHECK constraint makes RECAST_NAVMESH impossible without them. Every other graph
(hand-inserted, benchmark self-tests, test fixtures, anything from before V18) is `SYNTHETIC`, the column default.

Elevators are not detected from geometry -- a hallway scan generally cannot see inside one. Floor-to-floor
transitions are resolved entirely at query time (see below), not baked into any one floor's graph, because
`navigation_edge` can never span two different `graph_id`s (its foreign keys are scoped to one graph).

### What has and has not been validated

- **Validated with the real library:**
  - `services/reconstruction/tests/navmesh` runs chaya-navmesh (recastnavigation 1.6.0) over a tiny hand-made mesh,
    `tests/fixtures/navmesh/room_with_doorway.obj`, and asserts:
    - a Detour tile is produced;
    - walkable polygons exist at canonical floor height;
    - the blocked furniture, the wall and a 59.5° wedge are excluded (Euclidean clearance ≥ agent radius − edge
      error);
    - Detour routes around the furniture and through the doorway;
    - the path comes back in canonical coordinates;
    - every failure state is produced by the real tool.
  - The same package runs the whole stage through the orchestrator on a synthetic cleaned splat. That splat is an
    L-shaped floor with furniture, in a half-scale, +Y-up reconstruction frame. The tests assert that nothing is
    walkable in the unscanned quadrant and that Detour's path bends at the corner.
  - `PipelineControlPlaneTest` ingests the tool's real output for the fixture (`packages/contracts/fixtures/navmesh`)
    and routes on it through `RouteService`.
  - These tests pass on Windows (MinGW GCC 6.3) and on Linux (Debian bookworm GCC, the worker image's build stage).
- **Not validated:** no real reconstructed venue has been through NAVIGATION_BAKING. The stages before it need a CUDA
  GPU and Open3D (docs/E2E_VALIDATION.md), so nothing here shows routing quality on real scans. The splat-to-geometry
  step (occupancy grid, obstacle boxes) has only met synthetic point clouds.

## Routing: `POST /api/v1/navigation/routes`

`dev.chaya.api.navigation.RouteService` runs Dijkstra over `navigation_node`/`navigation_edge`. On a floor that is
routable, those rows are the Detour polygon graph of the navmesh Recast baked. This is the same polygon-corridor
search as Detour's `findPath`. It does not do Detour's string pulling (`findStraightPath`), so intermediate waypoints
are polygon centroids, not a smoothed path. Detour's own string-pulled query exists in the tool (`chaya-navmesh path`)
and is exercised by the worker tests, but the API does not run it at request time.

- **Navmesh only**: a floor is routed on only if its ACTIVE graph for the requested profile has `source =
  'RECAST_NAVMESH'`, meaning it was ingested bound to a NAVMESH artifact by checksum. If there is no such graph, or the
  ACTIVE graph is SYNTHETIC (hand-inserted), the route fails with `409 NAVMESH_NOT_READY`, which names the graph that
  was refused. There is no fallback to any other graph. The start and destination floors must have one. A multi-floor
  route never passes through an intermediate floor that lacks one. `chaya.navigation.accept-synthetic-graphs` (default `false`, not set in
  `application.yml`) exists only so `RouteServiceTest` can unit-test routing rules on hand-built graphs. The B4
  synthetic self-test also needs it on the stack it runs against.

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
client never has to guess what "accessible" excluded -- and `routingSources`: for every floor leg, the graph routed
on, its `source`, and the navmesh SHA-256 and recastnavigation version it was baked with.

`NAVMESH_NOT_READY` (409) is returned when a floor has no navmesh-backed graph (above). `ROUTE_UNAVAILABLE` (404) is
returned, never a fabricated path, whenever: the start or destination cannot be snapped within `chaya.navigation.node-snap-max-distance-meters`
of the graph, the graph is disconnected between them, an obstacle region removes the only path, or no
floor transition connects the start and destination floors (accessibly, when STEP_FREE was requested).
