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
chaya-navmesh --version                     # "chaya-navmesh 1.1.0 recastnavigation 1.6.0"
chaya-navmesh bake --input g.obj --navmesh out.bin --report out.json  --cell-size-m ... (every setting, no defaults)
chaya-navmesh path --navmesh out.bin --start X Y Z --end X Y Z --half-extents X Y Z --output path.json [--step-free]
```

- `bake` runs the full solo-mesh pipeline:
  - walkable-triangle marking by slope (`rcMarkWalkableTriangles`). Faces in an OBJ group named `obstacle*` are forced
    unwalkable but still solid. Walkable faces in a group named `level_change*` (the ground beside a step) get their own
    Recast area, `AREA_LEVEL_CHANGE` (1). Recast keeps an area in regions and polygons of its own, and those polygons
    carry the Detour flag `POLYFLAG_LEVEL_CHANGE` (0x02) as well as `POLYFLAG_WALK`.
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
  `--step-free` excludes `POLYFLAG_LEVEL_CHANGE` polygons from the query filter.
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

1. **Cleaned geometry → canonical metres.** The stage requires the work order's canonical coordinate frame (otherwise
   `NOT_CALIBRATED`). Its inputs are moved into canonical metres:
   - the cleaned splat (SPLAT_MERGED / SPLAT_CLEAN / SPLAT);
   - the semantic labels of **exactly that splat** (SEMANTIC_LABELS_MERGED / SEMANTIC_LABELS_CLEAN / SEMANTIC_LABELS);
   - PLANE_FITTING's planes.

   Labels are required: without them a wall or a sofa cannot be told from the floor. A missing labels artifact, or
   one whose length is not the splat's, fails with `NAVMESH_LABELS_UNAVAILABLE` rather than baking an obstacle-free
   floor (review N-1; re-scans splice their labels, see docs/rescan.md). The floor plane is the lowest horizontal
   plane (within `navmesh_floor_max_tilt_deg` of +Z) with at least a quarter of the best-supported horizontal plane's
   inliers. It is only the **reference height**: ground is looked for from 0.5 m below it to
   `navmesh_max_level_above_floor_m` above it.
2. **The surface model** (`chaya_worker.navmesh.build_surface_model`). This is a horizontal grid of
   `navmesh_floor_grid_m` cells over the cleaned points (review N-2: no longer the inliers of one floor plane).
   - **Ground**: points labelled `floor` or `stairs` (`GROUND_LABELS`; `semantic_classes` buckets stairs, staircases
     and steps as `stairs`, never as furniture). Per cell, ground is the median height of the **lowest** band of at
     least `navmesh_floor_min_points_per_cell` ground points no thicker than `navmesh_ground_band_m`. Floors, stair
     treads, landings and ramps are each a surface at its own height. A stray point below the floor or a table top
     above it does not decide the height. A cell with no such band is a **hole** and gets no geometry: never the
     convex hull.
   - **Obstacles**: points labelled `wall`, `furniture`, `clutter` or `unknown` (`OBSTACLE_LABELS`; unclassified
     geometry where people walk is not assumed passable). A point blocks when it is between the agent's climb
     (0.4 m) and its height (1.8 m) above the **local** ground: the cell's own, else the lowest observed
     neighbour's (a sofa hides the floor under it), else the reference height. Low clutter is stepped over. A lintel,
     the ceiling, or anything above head height does not block.
   - **Level changes**: 4-neighbour cells whose ground heights differ by more than `navmesh_step_min_rise_m` and at
     most the climb. Both cells are marked. A larger difference is a ledge, which Recast's climb filter already
     refuses to connect.
   - **Width**: for each free cell, the clear width available to a body centred there:
     `2 × distance to the nearest obstacle or hole − one cell` (a scipy distance transform; the cell is subtracted so
     the width is never overstated), capped at `navmesh_width_cap_m`.
3. **Recast input geometry** (`geometry_from_surface`), published as **NAVMESH_INPUT_GEOMETRY** (a canonical-frame
   OBJ). This is exactly what Recast is given, after `chaya_worker.recast_boundary` rotates it to +Y up:
   - `walkable_candidates`: one quad per ground cell. Each corner sits at the mean height of the cells sharing it
     that are level with this one, within `navmesh_step_min_rise_m`. A ramp is therefore one continuous sloped
     surface whose real slope Recast's slope test sees, and a step stays a discontinuity.
   - `level_change`: the quads of level-change cells.
   - `obstacle`: one closed box per obstacle cell, from 0.1 m below the lowest ground to the top of the obstacle
     geometry in the band (at least `navmesh_obstacle_min_height_m` above its local ground).
   - Nothing at all over holes.

   Units are metres from start to finish. No coordinate leaves the canonical frame except at the boundary.
4. **Recast bake** (`chaya_worker.recast.bake`). Every setting comes from `RecastConfig`, and every field is named by its
   unit. Defaults are in `chaya_worker.settings`, each overridable by an env var of the same name upper-cased:

   | Setting | Default | Setting | Default |
   |---|---|---|---|
   | `navmesh_cell_size_m` | 0.1 | `navmesh_region_min_area_m2` | 0.64 |
   | `navmesh_cell_height_m` | 0.05 | `navmesh_region_merge_area_m2` | 4.0 |
   | `navmesh_agent_height_m` | 1.8 | `navmesh_edge_max_len_m` | 6.0 |
   | `navmesh_agent_radius_m` | 0.35 | `navmesh_edge_max_error_m` | 0.13 |
   | `navmesh_agent_max_climb_m` | 0.4 | `navmesh_verts_per_poly` | 6 (a count) |
   | `navmesh_agent_max_slope_deg` | 45 | `navmesh_detail_sample_dist_m` / `_max_error_m` | 0.6 / 0.05 |
   | `navmesh_floor_grid_m` (surface cell) | 0.1 | `navmesh_floor_min_points_per_cell` | 3 (a count) |
   | `navmesh_ground_band_m` | 0.05 | `navmesh_step_min_rise_m` | 0.03 |
   | `navmesh_max_level_above_floor_m` | 2.0 | `navmesh_accessible_width_m` / `navmesh_width_cap_m` | 0.9 / 2.0 |

   The old settings were Recast-demo voxel counts labelled as metres (for example `edge_max_len = 12`), and have been
   replaced.
5. **NAVMESH** (`navmesh.bin`, the Detour tile) and **NAVMESH_MANIFEST** (`navmesh-manifest.json`). The manifest
   records:
   - `status: READY`;
   - the navmesh's SHA-256, size and polygon count;
   - the source run, scan and scan version;
   - the splat, plane-model and label input artifacts (id, key, SHA-256);
   - the input geometry: checksum, triangle counts (obstacle, level change), the representation and the surface
     model's summary (cells, ground/obstacle/level-change cells, height range);
   - the canonical frame and the Recast frame with its conversion;
   - the Recast configuration in metres and in voxels;
   - the tool and recastnavigation versions;
   - per-stage build counts.
6. **NAVIGATION_GRAPH** (`chaya_worker.stages.navigation_baking.navigation_graph_document`): the Detour polygon graph.
   - Nodes are polygon centroids: horizontal position from Detour, height from the reconstructed surface where there
     is one. Edges are Detour links.
   - Every edge records what was measured on the canonical geometry (`chaya_worker.navmesh.build_routing_graphs`):
     - `length_m`: the 3-D centroid-to-centroid distance.
     - `rise_m`: the signed rise along canonical +Z (`CANONICAL_UP`) between the centroids.
     - `max_slope_deg`: the steepest of the reconstructed surface slope under both polygons and the
       centroid-to-centroid grade. Both use the surface model's heights (medians of real points), not Recast's
       polygons. Recast's heights are quantised to the 0.05 m cell height and would turn a gentle ramp into false
       5–10° grades. Recast's polygon geometry is used only where the surface has no ground. Rise and run are
       projections onto and off the up axis (`edge_rise_run`), never a coordinate index.
     - `level_change`: either polygon is over a step (`POLYFLAG_LEVEL_CHANGE`).
     - `min_clearance_m`: the clear width along the edge, measured on the obstacle and hole geometry
       (`SurfaceModel.edge_clear_width_m`). It is the bottleneck of the widest path from the roomiest cell of one
       polygon to the roomiest cell of the other, through the cells of both. A doorway Recast left inside one
       polygon is crossed by that path, wherever the polygon boundaries lie (review N-3). In the venue fixture the
       1.1 m doorway measures 1.1 m and the 1.6 m one 1.5 m.
     - `portal_width_m`: the Detour portal's own length, after agent-radius erosion. Recorded, not used for routing.
     - `portal`: the portal's two endpoints in canonical metres. The API string-pulls routes through them.
   - **STANDARD**: every link.
   - **STEP_FREE**: only links over no level change and with `max_slope_deg` within `navmesh_max_ramp_slope_deg` (5°).
   - The graph carries an `edge_measurements` block naming the units, the up axis and how each field was measured, and
     a `navmesh` block: `source: RECAST_NAVMESH`, `status: READY`, the navmesh SHA-256, and the tool and library
     versions.

Failure states, each a structured stage failure with nothing published: `NAVMESH_TOOL_UNAVAILABLE` (no chaya-navmesh
on the worker), `NAVMESH_LABELS_UNAVAILABLE` (no semantic labels for exactly the splat being baked), `INVALID_GEOMETRY`
(non-finite, out-of-range or empty geometry, or a plane referencing missing Gaussians), `NO_WALKABLE_SURFACE` (no floor
plane, no ground in the floor's height range or no dense ground cell, or nothing left after slope, clearance and
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
or invalid value is stored as NULL, meaning "not measured". STEP_FREE routing refuses NULL (below). It also stores each
edge's portal (V25: `portal_ax` … `portal_bz`, all or none). A graph with an edge lacking a valid portal is refused
with `409 NAVIGATION_GRAPH_INVALID`.

Steps, stairs and ramps **within** a floor's height range are in the navmesh: treads and ramps are ground at their own
heights, risers are level changes (above). Stairs, ramps and elevators **between floors** are not detected from
geometry: each floor is reconstructed on its own, and a hallway scan cannot see inside an elevator. Floor-to-floor travel uses registered floor connections (below), not any
floor's graph. `navigation_edge` can never span two `graph_id`s anyway (its foreign keys are scoped to one graph).

### What has and has not been validated

- **Validated with the real library, on synthetic geometry:**
  - `services/reconstruction/tests/navmesh/test_venue_navigation.py` builds the deterministic venue of
    `tests/navmesh/venue_scene.py` (labelled points, as GEOMETRIC_CLEANUP leaves them):
    - a 12 × 7 m floor split by a wall with a 1.1 m and a 1.6 m doorway (lintels above head height);
    - a sofa with the floor under it unseen, a column and a railing;
    - a platform 0.17 m up, reached over a step or up a 1:14 ramp.

    It then runs it through the surface model, Recast (chaya-navmesh 1.1.0, recastnavigation 1.6.0) and Detour, and
    asserts that:
    - the Recast input holds both levels, every obstacle, level changes only at the riser, and the ramp as a
      4.1° slope;
    - the wall blocks except through its doorways;
    - the sofa, the column and the railing block, and a Detour route goes round the sofa;
    - every point of a string-pulled path is on the navmesh;
    - STANDARD crosses the step and STEP_FREE takes the ramp, in the Detour query and in the graphs under the API's
      rules;
    - without the ramp, STEP_FREE fails closed while STANDARD still routes;
    - the measured clearance decides the doorway: 0.9 m required uses the 1.1 m door, 1.3 m uses only the 1.6 m one,
      1.8 m has no route, and with the wide door walled up 1.3 m has no route;
    - the bake is deterministic.
  - `test_venue_stage.py` runs the same venue through NAVIGATION_BAKING with the orchestrator, in a scaled, rotated,
    +Y-up reconstruction frame, and routes on the result. It also runs a re-scan (review N-1) as a re-scan work order
    does: REGION_SPLICE splices the venue's and the region's labels; NAVIGATION_BAKING re-bakes the whole floor from
    the merged cloud, blocks a crate added inside the region, still keeps the sofa, wall and column outside it, and
    verifies the parent and merged scenes identical outside the region (docs/rescan.md, "NAVIGATION"). Lost obstacles,
    the region's cloud alone, labels or planes of another cloud and a parent cloud the splice did not use are refused.
    Without the venue's labels no merged labels are published and the re-bake is refused.
  - `test_recast_fixture.py` covers the hand-made `room_with_doorway.obj` and every failure state of the tool.
  - `test_navigation_baking_stage.py` covers the L-shaped floor (nothing walkable in the unscanned quadrant) and
    `NAVMESH_LABELS_UNAVAILABLE`.
  - `PipelineControlPlaneTest` ingests the tool's real output for the venue (`packages/contracts/fixtures/navmesh`,
    generated by `generate_navmesh_fixture.py` through the stage's own geometry code). It then routes on it through
    `RouteService`, asserting that:
    - routes are string-pulled, and no point of any segment is in the wall or the sofa;
    - STANDARD climbs the step and STEP_FREE goes up the ramp;
    - the required clear width picks the doorway or fails with `NO_ACCESSIBLE_ROUTE`;
    - a graph edge without a portal is refused.
  - These tests ran on Windows (MinGW GCC 6.3) on 2026-10-03. The worker image and CI build the tool from the same
    source on Linux.
- **Not validated:** no real reconstructed venue has been through NAVIGATION_BAKING. The stages before it need a CUDA
  GPU and Open3D (docs/E2E_VALIDATION.md). Nothing here shows routing quality on real scans: label quality, point
  density on floors and stairs, and reconstruction height noise against `navmesh_step_min_rise_m` are all unmeasured.

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
search as Detour's `findPath`. The corridor found is then **string-pulled** through the stored portals
(`CorridorPath`, the funnel algorithm of Detour's `findStraightPath`), so the reported path stays inside the navmesh
polygons and turns only at portal corners, keeping their heights (review N-3). Each leg's routing source names its
`pathMethod`:

- `STRING_PULLED`: the normal case.
- `PORTAL_MIDPOINTS`: when the string-pulled path would cross a reported obstacle. Still inside the corridor.
- `POLYGON_CENTROIDS`: SYNTHETIC test graphs without portals, or when both of the above cross a reported obstacle.
  The centroid segments are the ones checked against obstacles.

A RECAST_NAVMESH graph ingested before portals were recorded is not routable: `NAVMESH_NOT_READY`, bake again.

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

  A start or destination inside a box has no route. An edge is excluded when its centroid-to-centroid segment crosses
  a box, not when a box merely clips a polygon. The reported path is checked as well: if the string-pulled path
  crosses a box, the route falls back to portal midpoints, then to the checked centroid segments, and never reports a
  path through a reported obstacle.
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
| Walkable surface, obstacles, erosion | **measured** from the reconstruction, then Recast | The surface model: the lowest dense band of floor/stairs points per cell (multi-level), and wall/furniture/clutter/unknown points in the agent's band above their local ground. Recast applies its slope, climb, height and radius filters. Depends on the semantic labels being right: a sofa labelled floor is ground. |
| Steps (level changes) | **measured** | Neighbouring ground cells more than `navmesh_step_min_rise_m` (0.03 m) apart. A smaller lip is not detected. Reconstruction height noise above 0.03 m would mark false steps, which makes STEP_FREE fail closed more often. Neither has been measured on real data. |
| Node positions, edge 3-D length, route distance on a floor | **measured** (derived from the canonical geometry) | Polygon centroids and polyline length. |
| Edge slope (`max_slope_deg`) | **measured** against canonical +Z | The reconstructed surface slope under both polygons, and the centroid grade on reconstructed heights. Steps are caught by the level-change flag as well as the grade. |
| Edge clearance (`min_clearance_m`) | **measured** on the obstacle geometry | The bottleneck of the widest path between the two polygons, where width is what a body centred at a cell has (2 × distance to the nearest obstacle or hole − one cell). Resolution is one 0.1 m cell, conservatively. Measured on the reconstruction's obstacles and holes, so an unscanned area beside a corridor narrows it. |
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
  - `CorridorPathTest` covers string-pulling: on an L-shaped corridor every segment lies inside the polygons and the path
    turns exactly at the inner corner; a jogging corridor that the centroid polyline leaves, but the pulled path does not.
  - `tests/unit/test_navigation_baking.py` covers slope, rise and run against canonical +Z, and a step between two
    level polygons.
- **Not validated:**
  - No real venue has been routed on.
  - No real multi-floor venue has registered connections.
  - No real reconstruction has put a stair or ramp into the navmesh input. The synthetic venue fixture does (above).
