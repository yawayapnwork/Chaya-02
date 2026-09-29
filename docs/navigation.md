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
   - Nodes are polygon centroids (canonical metres). Edges are Detour links.
   - Every edge records what was measured on the canonical geometry (`chaya_worker.navmesh.build_routing_graphs`):
     - `length_m`: the 3-D centroid-to-centroid distance.
     - `rise_m`: the signed rise along canonical +Z (`CANONICAL_UP`) between the centroids.
     - `max_slope_deg`: the steepest of both polygons' face slopes and the centroid-to-centroid grade, each measured
       against `CANONICAL_UP`. The grade catches a step between two level polygons: two flat treads 0.17 m apart with
       centroids 0.3 m apart are each 0° but joined by a 29.5° edge. Rise and run are projections onto and off the up
       axis (`edge_rise_run`), never a coordinate index.
     - `min_clearance_m`: the length of the Detour portal. Recast has already eroded the walkable area by the agent
       radius, so it is not the wall-to-wall width (review N-3). It is the only clearance measured.
   - **STANDARD**: every link.
   - **STEP_FREE**: only links whose `max_slope_deg` is within `navmesh_max_ramp_slope_deg` (5°).
   - The graph carries an `edge_measurements` block naming the units, the up axis and how each field was measured, and
     a `navmesh` block: `source: RECAST_NAVMESH`, `status: READY`, the navmesh SHA-256, and the tool and library
     versions.

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

Ingestion stores `max_slope_deg` and `min_clearance_m` on `navigation_edge` (V22 added `max_slope_deg`). A missing
or invalid value is stored as NULL, meaning "not measured". STEP_FREE routing refuses NULL (below).

Stairs, ramps and elevators between floors are not detected from geometry: each floor is reconstructed on its own, and
a hallway scan cannot see inside an elevator. Floor-to-floor travel uses registered floor connections (below), not any
floor's graph. `navigation_edge` can never span two `graph_id`s anyway (its foreign keys are scoped to one graph).

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

## Floor connections: `/api/v1/venues/{venueId}/floor-connections`

`dev.chaya.api.navigation.FloorConnectionService` (V22 `floor_connection`). Venue staff register each connection a
route may use between two floors:

| Field | Meaning | Required |
|---|---|---|
| `connectorType` | `STAIRS`, `RAMP` or `ELEVATOR` | always |
| `fromFloorId`, `fromPoiId`, `toFloorId`, `toPoiId` | a landing POI on each floor, each placed in its own floor's frame | always |
| `bidirectional` | usable in both directions (default `true`) | – |
| `lengthM` | walked length in metres, measured on site | STAIRS and RAMP; refused for ELEVATOR |
| `minClearanceM` | narrowest clear width (ramp width, elevator door), metres | for STEP_FREE use |
| `maxSlopeDeg` | a ramp's steepest slope, degrees | for STEP_FREE use of a RAMP |

A multi-stop elevator is one connection per pair of floors it serves. `PUT …/{id}/status` sets `IN_SERVICE` or
`OUT_OF_SERVICE` (an elevator under maintenance); routing skips the latter. Creation is refused with `400
INVALID_FLOOR_CONNECTION` for:

- an unknown type;
- the same floor twice;
- stairs or a ramp without a length, or an elevator with one;
- a landing POI that is not on the floor it is named for.

## Routing: `POST /api/v1/navigation/routes`

`dev.chaya.api.navigation.RouteService` runs Dijkstra over `navigation_node`/`navigation_edge`. On a floor that is
routable, those rows are the Detour polygon graph of the navmesh Recast baked. This is the same polygon-corridor
search as Detour's `findPath`. It does not do Detour's string pulling (`findStraightPath`), so intermediate waypoints
are polygon centroids, not a smoothed path. Detour's own string-pulled query exists in the tool (`chaya-navmesh path`)
and is exercised by the worker tests, but the API does not run it at request time.

- **Navmesh only**: a floor is routed on only if its ACTIVE graph for the requested profile has `source =
  'RECAST_NAVMESH'` and was baked in the floor's current frame. There is no fallback to any other graph.
  `chaya.navigation.accept-synthetic-graphs` (default `false`, not set in `application.yml`) exists only so
  `RouteServiceTest` can unit-test routing rules on hand-built graphs. The B4 synthetic self-test also needs it on the
  stack it runs against.
- **Metric frames**: the start and destination floors must have a current canonical frame (metric scale,
  gravity-aligned +Z), and the destination POI must be placed in it. The request's `start` and `blockedRegions` are
  canonical metres on their floor, in that frame.
- **Snapping**: the start and the destination each join the nearest graph node within
  `chaya.navigation.node-snap-max-distance-meters` whose straight segment to them crosses no reported obstacle.
- **Accessibility** (`accessibility: "STEP_FREE"`, default `"STANDARD"`) fails closed. It routes on the STEP_FREE
  graph and uses an edge only when all of these hold:
  - it was baked step-free;
  - its `min_clearance_m` was measured and is at least `chaya.navigation.min-accessible-clearance-m` (0.9 m);
  - its `max_slope_deg` was measured and is at most `chaya.navigation.max-accessible-slope-deg` (4.76°, 1:12).

  An unmeasured value excludes the edge; it is never treated as passing. Between floors, STEP_FREE uses only an
  ELEVATOR or RAMP connection with a registered `minClearanceM` of at least the same 0.9 m, and, for a ramp, a
  registered `maxSlopeDeg` within the same limit. It never uses stairs.
- **Multi-floor**: no coordinate on one floor is ever compared with a coordinate on another. A route crosses floors
  only through a registered, in-service connection where:
  - both landing POIs still exist, are still on their floors, and are placed in their floors' current frames;
  - both floors are routable for the profile.

  The search is a Dijkstra over points: the start, connection landings and the destination. Moving between two points
  on one floor is a real floor-local route over that floor's graph. Crossing a connection costs its registered length,
  or its elevator time. The search minimises estimated duration, so it can chain connections through intermediate
  floors. Two floors therefore no longer need a shared venue datum.
- **Dynamic obstacles**: each `blockedRegions` entry is an axis-aligned horizontal box the AR client observed on one
  floor. It blocks that floor's full height, for one query only; nothing is written to the database. It excludes:
  - every graph node inside it;
  - every edge whose segment crosses it (Liang–Barsky segment/box clipping), even with both endpoints outside;
  - a start or destination snap segment that crosses it.

  A start or destination inside a box has no route. Blocking is tested on the centroid-to-centroid segments the route
  reports, not on Detour's polygon interiors: a box that clips a polygon without crossing any of its edge segments does
  not block it.
- **Distance and time**:
  - An edge's routing weight is the 3-D distance between its nodes' canonical positions. The stored `length_m` is
    not read.
  - A leg's distance is the 3-D length of its reported waypoint polyline.
  - `distanceMeters` is the sum of the legs plus the registered `lengthM` of every stairs or ramp connection used.
  - `estimatedDurationSeconds` adds up:
    - walked leg length ÷ walking speed (1.3 m/s standard, 1.0 m/s STEP_FREE);
    - stairs length ÷ `stairs-speed-mps` (0.5 m/s);
    - ramp length ÷ walking speed;
    - `elevator-transition-seconds` (45 s) per elevator ride.

### Response

`RouteResponse` returns:

- `waypoints`: the full sequence, each tagged `START`, `WAYPOINT`, `TRANSITION` or `DESTINATION`, with its floor.
- `distanceMeters` and `estimatedDurationSeconds`.
- `floorTransitions`: each crossing, with the connector type, the connection id, the departure (`poiId`) and arrival
  (`toPoiId`) landings, and its own distance and duration.
- `accessibilityProfile`.
- `accessibilityConstraintsApplied`: a human-readable list of exactly which constraints were active, including that
  unmeasured slope and clearance were excluded.
- `routingSources`: for every floor leg, the graph routed on, its `source`, and the navmesh SHA-256 and
  recastnavigation version it was baked with.

### Failure states

A failure never returns a fabricated or partial path.

| Code | HTTP | When |
|---|---|---|
| `METRIC_CALIBRATION_REQUIRED` | 409 | The start or destination floor has no current metric, gravity-aligned frame, or the destination POI is not placed in it (or not on any floor). |
| `NAVMESH_NOT_READY` | 409 | The start or destination floor has no ACTIVE RECAST_NAVMESH graph for the profile, or has one baked in an older frame. The message names the refused graph. |
| `FLOOR_CONNECTION_UNAVAILABLE` | 404 | The destination is on another floor, and no chain of usable registered connections joins the floors for this profile. The message lists each unusable connection and why. |
| `NO_ACCESSIBLE_ROUTE` | 404 | STEP_FREE was requested and no path meets the measured constraints. The message counts the edges excluded for each reason. |
| `NO_ROUTE` | 404 | STANDARD was requested and the graph does not connect the start and destination: it is disconnected, a point is too far to snap, or reported obstacles cut the path. |

A connection is unusable when it is out of service, when it is stairs and STEP_FREE was requested, when a width or
slope STEP_FREE needs is unregistered or fails, when a landing is not in its floor's current frame, or when a floor is
not routable. The edge-exclusion reasons for `NO_ACCESSIBLE_ROUTE` are: unmeasured clearance, too narrow, unmeasured
slope, too steep, not step-free, and reported obstacles.

Request validation returns `400`: `INVALID_ACCESSIBILITY`, `INVALID_BLOCKED_REGION` (non-finite bounds or min > max),
or `INVALID_START` (non-finite). The worker's own Detour query (`chaya_worker.recast.find_path`) also fails with
`NO_ROUTE`.

## What is measured and what is assumed

| Quantity | Status | Source and limits |
|---|---|---|
| Canonical frame: metres, +Z against gravity | **measured** at calibration | Known distances or control points give scale. The floor plane, operator floor points or control points give gravity ([coordinate-frames.md](coordinate-frames.md)). |
| Walkable surface, obstacles, erosion | **measured** from the reconstruction, then Recast | Observed floor cells, and wall/furniture points in the agent's height band. Recast applies its slope, climb, height and radius filters. |
| Node positions, edge 3-D length, route distance on a floor | **measured** (derived from the canonical geometry) | Polygon centroids and polyline length. |
| Edge slope (`max_slope_deg`) | **measured** against canonical +Z | Polygon face normals and the centroid grade. These come from the coarse Detour polygons, not the detail mesh, so a step smaller than what the coarse polygons show is not seen. |
| Edge clearance (`min_clearance_m`) | **measured**, conservatively | The Detour portal length after agent-radius erosion. The true corridor is wider by about two agent radii. A portal can also cross the corridor at an angle. |
| Agent size: radius 0.35 m, height 1.8 m, climb 0.4 m, max slope 45° | **assumed** | Worker settings (`chaya_worker.settings`). |
| Accessibility limits: 0.9 m clear width, 4.76° slope | **assumed** (ADA-derived) | `chaya.navigation.*`. The worker bakes STEP_FREE at 5° (`navmesh_max_ramp_slope_deg`); the API then applies 4.76°. |
| Floor connections: existence, landings, stairs/ramp walked length, ramp slope, clear width | **registered by staff**, not reconstructed | `floor_connection`. Chaya cannot check them against geometry. |
| Walking speeds (1.3 / 1.0 m/s), stairs speed (0.5 m/s), elevator ride (45 s) | **assumed** planning constants | `chaya.navigation.*`. |
| Dynamic obstacles | **reported by the client** | `blockedRegions`. The server never invents one. |

### Routing: what has and has not been validated

- **With real Recast output:** `PipelineControlPlaneTest` ingests the real Recast output for the fixture mesh (see the
  baking section) and routes on it.
- **Algorithmic unit tests only.** These use synthetic fixtures and are not evidence of navigation in any venue.
  - `RouteServiceTest` hand-builds graphs, frames and connections to test the routing rules:
    - unknown and insufficient clearance, steep and unmeasured slope, and steps;
    - stairs, elevators and ramps, including fail-closed width and slope;
    - multi-floor routing between floors with unrelated frames, and chained connections;
    - out-of-service connections, and landings in a stale frame;
    - an obstacle that blocks an edge without covering a node, and rerouting around a dynamic obstacle;
    - metric distance and duration from the geometry rather than stored lengths;
    - every failure code.
  - `RouteGeometryTest` covers segment/box blocking and polyline length.
  - `tests/unit/test_navigation_baking.py` covers slope, rise and run against canonical +Z, and a step between two
    level polygons.
- **Not validated:**
  - No real venue has been routed on.
  - No real multi-floor venue has registered connections.
  - No reconstruction has put a stair or ramp into the navmesh input. The input is still the single floor plane, so a
    floor's STEP_FREE and STANDARD graphs differ only where that one plane's polygons do.
