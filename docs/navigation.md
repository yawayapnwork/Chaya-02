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
    - a narrow doorway of 0.5 m width (< 0.70 m agent diameter) is completely eroded by Recast's `rcErodeWalkableArea`
      and Detour fails routing (`PATH_NOT_FOUND`, status 6);
    - dead ends and completely walled-off regions are unreachable and Detour refuses traversal (`PATH_NOT_FOUND`) rather
      than cutting through walls;
    - endpoints outside walkable boundaries fail to snap (`findNearestPoly` / `PATH_NOT_FOUND`);
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

## Floors: `/api/v1/venues/{venueId}/floors`

`dev.chaya.api.venue.FloorService`. A floor is what captures, scan versions, POIs, navigation graphs and floor
connections are recorded against. A capture names its floor when it is created (`floorId`), and its run and scan version
belong to that floor.

| Call | Who | What |
|---|---|---|
| `GET /floors` | any role | Live floors by level, with `currentScanVersionId` and `elevation` (or null) |
| `POST /floors` `{level, name}` | admin, venue-manager | Create. A level is unique among live floors (`409 FLOOR_LEVEL_TAKEN`) |
| `PUT /floors/{id}` `{level, name}` | admin, venue-manager | Rename or re-level, refused if the new level contradicts recorded elevations |
| `PUT /floors/{id}/elevation` `{elevationM, source, reference?}` | admin, venue-manager | Record the floor's elevation |
| `DELETE /floors/{id}/elevation` | admin, venue-manager | Withdraw it: the elevation is unknown again |
| `DELETE /floors/{id}` | admin, venue-manager | Soft-delete a floor nothing relies on. `409 FLOOR_IN_USE` while it publishes a scan version, a connection lands on it, or a capture of it is in progress. Its level becomes free |
| `GET /floors/consistency` | admin, venue-manager, operator | The consistency report (below) |

**Elevation is never measured or estimated by Chaya.** Each floor is reconstructed and calibrated on its own, so how high
one floor is above another is not something the system observes. V39 stores what staff record: `elevationM` above the
venue's elevation reference, `source` `SURVEYED` or `MEASURED_ON_SITE`, a free-text `reference` (what 0 m is), and who
recorded it and when. Unknown stays null; the viewer and capture pages show "elevation not recorded".

**Recorded values are checked against each other when written** (`FloorGeometry`); a contradiction is refused with 400
rather than stored:
- levels and elevations must agree in order: a higher level is higher up (`FLOOR_ELEVATION_INCONSISTENT`);
- a stair or ramp must be at least as long as the rise between its floors' elevations, plus 0.1 m
  (`CONNECTION_GEOMETRY_INCONSISTENT` when the connection is registered, `FLOOR_ELEVATION_INCONSISTENT` when the elevation
  is); and a ramp's registered maximum slope must be at least the average slope its rise and length imply;
- a stair steeper than 50° on average is reported as a warning (`STAIR_GEOMETRY_IMPLAUSIBLE`), not refused.

**Consistency report and cross-floor alignment.** Chaya never merges geometry of different floors, and never stitches
floors automatically. Floors relate in exactly two ways:
1. through registered floor connections (below), which is all routing uses;
2. through a shared surveyed datum. A floor calibrated with surveyed control points (`VENUE_CONTROL_POINTS`,
   docs/coordinate-frames.md) is in the survey's coordinates. The control-point fit refuses calibrations whose RMS
   residual exceeds 5 cm (`422`), so an unreliable alignment never becomes a floor's frame.

`GET /floors/consistency` checks every recorded relation. For each connection whose two floors are both calibrated to
control points, it compares the two landing POIs in that shared datum:
- their vertical separation against the recorded elevations (0.3 m tolerance: `ELEVATION_DISAGREES_WITH_SURVEY`);
- an elevator's landings must be vertically aligned within 2.5 m (`LANDINGS_NOT_VERTICALLY_ALIGNED`);
- a stair's or ramp's landings must be no farther apart than its walked length (`LANDINGS_FARTHER_THAN_WALKED_LENGTH`).

Each such floor pair is `CONSISTENT` or `INCONSISTENT`. Floors with local frames are `NOT_COMPARABLE`: their frames share
nothing, and nothing about their relative position is claimed. The check assumes both floors were calibrated against
the same survey. Chaya cannot tell two surveys apart, and a contradiction may mean they were not. The measured landing
separation is reported as evidence; it is never written into a floor's elevation.

Incremental re-scans of a room or region of one floor are aligned to that floor by REGION_ALIGNMENT, which refuses
low-confidence registrations (docs/rescan.md). No method aligns two independently captured floors from shared visual
features: a staircase seen from both floors is not used to register them.

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
| `accessibilityVerifiedAt`, `accessibilityVerifiedBy` | when and by whom the connector's accessibility was verified on site (V37); read-only | for STEP_FREE use |

`GET …/floor-connections/graph?accessibility=STANDARD|STEP_FREE` returns the floor-transition graph routing uses for a
profile, with the same rules as `RouteService`:
- each floor and whether it is routable (and why not);
- each usable direction of each connection;
- every unusable connection and why;
- the connected components: floors mutually reachable. A floor alone in its component is disconnected for that
  profile, and a route to it fails with `FLOOR_CONNECTION_UNAVAILABLE`.

A multi-stop elevator is one connection per pair of floors it serves. `PUT …/{id}/status` sets `IN_SERVICE` or
`OUT_OF_SERVICE` (an elevator under maintenance); routing skips the latter.

A registered width or slope is a claim, so STEP_FREE does not rely on it alone. `PUT …/{id}/accessibility-verification`
with `{"verified": true}` (ADMIN, VENUE_MANAGER) records who confirmed on site that the connector is usable without steps
and that its registered clear width (and a ramp's slope) are right; `{"verified": false}` withdraws it. Stairs cannot be
verified, and neither can a connection with nothing registered to verify (no width, or a ramp without a slope):
`400 INVALID_FLOOR_CONNECTION`. `PUT …/{id}` (ADMIN, VENUE_MANAGER) corrects a registration (type, landings,
direction, length, slope, clear width). If anything step-free routing relies on changes, the verification is withdrawn
and must be recorded again, so a verification always refers to the values it was given for. Routes planned afterwards
use the new registration. Connections that existed before V37 start unverified: step-free routes between
floors fail closed (`FLOOR_CONNECTION_UNAVAILABLE`, naming each unverified connection) until staff verify them. Creation is refused with `400
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
  `chaya.navigation.node-snap-max-distance-meters` whose straight segment to them crosses no reported obstacle. Ties go
  to the lower node id, so the same request always gives the same path.
- **Endpoints**: before any search, each endpoint is checked against its floor's graph. A point outside the horizontal
  footprint of the graph's polygon centroids and portals by more than `chaya.navigation.endpoint-bounds-margin-m` (1 m),
  more than 1 m below its lowest walkable surface or more than 2.5 m above its highest (an AR client sends its camera
  position, held up to eye height) is `422 START_OUT_OF_BOUNDS` / `DESTINATION_OUT_OF_BOUNDS`. A point inside the
  footprint but with no polygon centroid within the snap distance is `422 START_OFF_NAVMESH` / `DESTINATION_OFF_NAVMESH`.
  Limitation: the graph stores polygon centroids, not polygon outlines, so a point inside a thin wall that is within the
  snap distance of a polygon is snapped, not refused.
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
- **Dynamic obstacles**: each `blockedRegions` entry is an axis-aligned horizontal box the client observed on one
  floor, with `observedAt` (required) and optionally the `coordinateFrameId` it was localized in. It blocks that floor's
  full height, for one query only; nothing is written to the database and no navmesh is rebuilt. An entry is rejected,
  and reported in the response's `rejectedObstacles` rather than routed around, when it was observed more than
  `chaya.navigation.obstacle-max-age-seconds` (120 s) ago (`STALE_OBSERVATION`), is stamped more than
  `obstacle-max-future-skew-seconds` (10 s) in the future (`FUTURE_OBSERVATION`), or names another frame than the one the
  floor is routed in (`FRAME_MISMATCH`). An entry without `observedAt` is `400 INVALID_BLOCKED_REGION`. An accepted box
  excludes:
  - every graph node inside it;
  - every edge whose centroid-to-centroid segment crosses it (Liang–Barsky segment/box clipping), even with both endpoints
    outside;
  - every edge whose Detour portal it narrows below the walking body (below). Where the edge stays open, the route is
    string-pulled through the part of the portal the boxes leave free;
  - a start or destination snap segment that crosses it.

  The opening at a portal is the portal extended by `chaya.navigation.agent-radius-m` (0.35 m) at both ends -- Recast put
  the portal's endpoints where a body's centre can be -- or, when wider, the edge's measured clear width centred on the
  portal (Recast rounds erosion up to whole voxels: in the venue fixture the 1.1 m doorway's portal is only 0.2 m long). The
  boxes cut that opening into free pieces, and the edge stays open when the widest is at least 2 × the agent radius
  (0.7 m), or the accessible clear width (0.9 m) for STEP_FREE. This is accurate to about one voxel (0.1 m).

  A start or destination inside a box has no route. The reported path is checked as well: if the string-pulled path
  crosses a box, the route falls back to portal midpoints, then to the checked centroid segments, and never reports a
  path through a reported obstacle.
- **Graph cache**: a graph's nodes and edges are immutable once it leaves DRAFT (V6), so `RouteService` keeps up to
  `chaya.navigation.graph-cache-max-graphs` (64) of them in memory, keyed by graph id. Which graph a floor routes on is
  still read from the database on every request (ACTIVE status, frame, scan version), so a re-bake, a new version or a
  new frame takes effect immediately and a retired graph is only ever used by a request pinned to its version. Obstacles,
  accessibility rules and endpoint checks are applied to a per-request copy and are never cached.
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
- `rejectedObstacles`: every reported obstacle that was not routed around, with its index in the request and why.

### Failure states

A failure never returns a fabricated or partial path.

| Code | HTTP | When |
|---|---|---|
| `METRIC_CALIBRATION_REQUIRED` | 409 | The start or destination floor has no current metric, gravity-aligned frame, or the destination POI is not placed in it (or not on any floor). |
| `NAVMESH_NOT_READY` | 409 | The start or destination floor has no ACTIVE RECAST_NAVMESH graph for the profile, or has one baked in an older frame. The message names the refused graph. |
| `FLOOR_CONNECTION_UNAVAILABLE` | 404 | The destination is on another floor, and no chain of usable registered connections joins the floors for this profile. The message lists each unusable connection and why. |
| `NO_ACCESSIBLE_ROUTE` | 404 | STEP_FREE was requested and no path meets the measured constraints. The message counts the edges excluded for each reason. |
| `NO_ROUTE` | 404 | STANDARD was requested and the graph does not connect the start and destination: it is disconnected, or reported obstacles cut the path. |
| `START_OUT_OF_BOUNDS`, `DESTINATION_OUT_OF_BOUNDS` | 422 | The point is outside the floor graph's footprint (plus the margin), or far above or below its walkable surfaces. |
| `START_OFF_NAVMESH`, `DESTINATION_OFF_NAVMESH` | 422 | No navmesh polygon is within the snap distance of the point. |

A connection is unusable when it is out of service, when it is stairs and STEP_FREE was requested, when a width or
slope STEP_FREE needs is unregistered or fails, when STEP_FREE was requested and its accessibility was not verified on
site, when a landing is not in its floor's current frame, or when a floor is not routable. The edge-exclusion reasons for `NO_ACCESSIBLE_ROUTE` are: unmeasured clearance, too narrow, unmeasured
slope, too steep, not step-free, and reported obstacles.

Request validation returns `400`: `INVALID_ACCESSIBILITY`, `INVALID_BLOCKED_REGION` (non-finite bounds, min > max, or
no `observedAt`), or `INVALID_START` (non-finite).

## Multi-stop routes: `POST /api/v1/navigation/routes/multi-stop`

`RouteService#routeMultiStop`. The request is a route request with `stopPoiIds` (1 to `chaya.navigation.max-stops`,
default 10, each once) instead of one destination, and `optimizeOrder`.

- Each segment is exactly a single route (everything above applies) from the previous reached point to the next stop.
  All segments share one request's graphs, obstacles and floor connections. A multi-floor segment crosses connections as
  usual, so a tour can go up a lift and come back down.
- **Order**: without `optimizeOrder` the stops are visited in the order given. With it, the order with the least total
  estimated duration is chosen, starting at the start and ending at whichever stop is last: Held-Karp dynamic programming
  over the segment durations, exact (at most 12 stops). Ties are broken deterministically (the same request always gets
  the same order).
- **Unreachable stops**: a stop that cannot be reached gets the failure code a single route to it would get (`NO_ROUTE`,
  `NO_ACCESSIBLE_ROUTE`, `FLOOR_CONNECTION_UNAVAILABLE`, `DESTINATION_OUT_OF_BOUNDS`, `VERSION_MISMATCH`,
  `POI_NOT_FOUND`, ...), and the route continues from the last stop it did reach. Problems with the start fail the whole
  request. When no stop can be reached the answer is `404 NO_REACHABLE_STOP`, listing each stop's reason.
- **Response**: `visitOrder` (reached stops in order), `orderOptimized`, `complete` (every stop reached), `stops` (each
  requested stop's `requestedIndex`, `visitIndex`, `REACHED` or `UNREACHABLE` with its code and message), `segments`
  (each a full route response), and the joined route: `waypoints` (an intermediate destination is a `STOP` waypoint;
  the point shared by two segments is listed once), summed `distanceMeters` and `estimatedDurationSeconds`,
  `accessibilityConstraintsApplied`, `routingSources` and `rejectedObstacles`.
- Request validation: `400 INVALID_STOPS` (none, a duplicate, or too many).

## Clients: presenting and following a route

The web clients share pure, unit-tested modules; the components only wire positions and requests into them.

- **Instructions** (`apps/web/lib/route-instructions.ts`): depart, each turn of at least 20° at an inner vertex of a
  floor's polyline (slight below 45°, sharp from 135°; left and right about canonical +Z), each floor connection ("Take the
  lift to Level 1"), each intermediate stop, and arrival, with the distance to the next instruction. Distances are the
  route's own 3-D polyline lengths in canonical metres plus the registered walked length of each stairs or ramp
  connection; the step between two floors' waypoints is never measured, because the two floors' frames are unrelated.
- **Progress** (`locateOnRoute`): the device is projected onto the route in plan view, and only from its previous
  position up to 5 m further along, unless it is more than 3 m off the route, so a route that passes the same place twice
  is neither jumped ahead on nor walked back. Remaining distance is measured along the route.
- **Arrival** (`updateArrival`): a stop or the destination counts as reached only when, continuously for 2 s, the device
  is localized on its floor, on the segment that ends there, and within 1.5 m of it both along the route and in a straight
  line. A tracking gap restarts the dwell. Stops are reached in order; the route then stays complete.
- **Obstacles** (`apps/web/lib/obstacle-reroute.ts`): only observations from a real source on the device are reported,
  and observations older than 120 s are dropped. A reroute is requested only when a new or moved obstacle (moved by more
  than 0.3 m) comes within the walking body's radius of the path still ahead, and at most every 5 s. The AR clients'
  source is camera depth (and, on iOS, Vision person detection): see "Camera obstacle sensing" below. The viewer has no
  obstacle source. A device without depth says the route does not adapt to obstacles.
- **Viewer** (`ViewerWorkspace`): one or more destinations, in a list that can be reordered, trimmed, or put in the
  fastest order (the multi-stop endpoint with `optimizeOrder`). Each segment is one route request, cached by scene, scan
  version, profile, obstacles and endpoints (`apps/web/lib/multi-stop.ts`): changing one destination re-requests only the
  segments into and out of it. Segments routed on different graphs of one floor (a republish between requests) are never
  joined: the cache is dropped and the whole route planned again. The route's stretches on the floor shown are drawn
  with a marker at each stop and the destination, and the destinations' POI markers are highlighted. Both profiles are
  shown, the step-free one with what it guarantees. A route can be cancelled or recalculated; an expired sign-in or
  viewer link is reported as such (with a sign-in action), and a network failure offers a retry and is planned again
  when the connection returns.
- **Android AR** (`ArWorkspace`): the next instruction and the distance left along the route; arrival as above; a
  recalculate action.
- **Transitions that change during navigation** (`apps/web/lib/floor-transitions.ts`). While a route over floor
  connections is shown, the viewer (every 60 s) and the Android AR session (every 30 s) re-read the venue's connections.
  If one on the route was removed, went out of service, no longer runs in the route's direction, or (step-free) lost its
  verification, the route is planned again. A replanned route that still uses the same connection is reported, not
  replanned again. The server applies the same rules on every request, so a fresh route never uses such a connection.
- **Viewer floors**:
  - The floor selector labels each floor with its level and recorded elevation (or "elevation not recorded").
  - The floor on screen is shown in a badge.
  - A multi-floor route offers "Show <floor>" for its other floors. The route planned on current versions is carried
    to that floor, and its leg is drawn only if that floor's routing source matches the version and frame shown
    (`routeOverlay`).
  - A route on a pinned historical version stays on its floor. The worker's own Detour query (`chaya_worker.recast.find_path`) also fails with
`NO_ROUTE`.

## Camera obstacle sensing

The AR clients can notice a person or a moved object in the way of the route and plan around it. The server side is
unchanged. It still routes around the `blockedRegions` a client reports, for one request, and rejects stale,
future-dated and other-frame observations ("Dynamic obstacles" above). What is new is a real source for those regions on
the device.

Code: `apps/web/lib/obstacle-sensing.ts`, `obstacle-tracker.ts`, `obstacle-reroute.ts`, `obstacle-status.ts` (pure), and
`ar-depth-obstacles.ts` (WebXR, device-only), wired in `components/ArWorkspace.tsx`. On iOS the same rules are in
`ChayaARCore/ObstacleSensing.swift` (pure), with `ChayaARKitSession/ObstacleSensor.swift` (ARKit and Vision, device-only)
wired in `ARSessionManager`.

### What each device can sense

| | Depth | People | Other objects | Status |
|---|---|---|---|---|
| iPhone/iPad with LiDAR (iPhone 12 Pro and later Pro models, iPad Pro 2020 and later) | ARKit scene depth, with ARKit's per-pixel confidence | Vision `VNDetectHumanRectanglesRequest`, on device | Depth standing in the route corridor (`UNCLASSIFIED`) | Implemented, **not device-validated** |
| iPhone/iPad with A12 or later, no LiDAR | ARKit person segmentation with depth (`estimatedDepthData`, on person pixels only) | Vision, as above | **None**: there is no depth for anything but people | Implemented, **not device-validated** |
| Older iOS devices | none | none | none | Sensing unavailable; the overlay says so |
| Android, Chrome, ARCore device with the Depth API | WebXR `depth-sensing`, CPU-optimized (an optional session feature) | **None**: no person detector runs in the browser | Depth standing in the route corridor (`UNCLASSIFIED`) | Implemented, **not device-validated** |
| Android without ARCore depth, or a browser that does not grant `depth-sensing` | none | none | none | Sensing unavailable; the overlay says so |

There is no person detection on Android. The web app has no on-device detection model, and adding one would mean
shipping model weights and reading camera pixels through WebXR `camera-access`. The existing detection models (Grounding
DINO and SegFormer in the reconstruction worker) run offline on the server. Sending live camera frames to the server to
use them was ruled out for privacy and latency reasons. On Android, a person in the corridor is found by depth like any
other object, as `UNCLASSIFIED`. It is then treated as an object, with the longer lifetime and no "walking by" exemption.

### From a camera frame to a placed obstacle

A 2-D box says nothing about where something is in 3-D. An obstacle is placed **only** from depth measured in the same
camera frame (`localizeDetection`, `corridorOccupancy`), and nothing estimates depth from a box's size or position.

1. **Same frame.** The detection, the depth and the camera pose must carry the same platform frame timestamp (within
   1 ms). Otherwise the result is `FRAME_MISMATCH`.
2. **Localized and tracking normally.** Without a device-to-venue transform the result is `NOT_LOCALIZED`. With limited
   or lost tracking it is `TRACKING_NOT_NORMAL`. Sensing does not run at all in those states.
3. **Depth inside the box.** Only the inner 70% of the box is sampled, because its edges hold background. Samples the
   platform marks as low confidence are dropped. There must be at least 8 samples (`TOO_FEW_DEPTH_SAMPLES`, or
   `NO_DEPTH`).
4. **One surface.** At least half of the samples must lie within max(0.25 m, 10%) of the median range. No more than 10%
   may be nearer than that surface: a thin or partly hidden object in front of a wall is ambiguous, so it is rejected
   rather than placed at the wall (`INCONSISTENT_DEPTH`). The range must be 0.3–5 m (`OUT_OF_RANGE`).
5. **Into the venue frame.** Points go camera → AR world (the platform's measured camera pose) → canonical venue (the
   world-anchor-corrected relocalization transform).
6. **On the floor.** The surface must reach within 1.2 m of the route's floor, rise at least 0.3 m above it, top out
   below 2.5 m, and never sit more than 0.4 m below it. A wrong transform (translated, or with the axis change missing)
   or an overhead sign fails this check (`NOT_ON_FLOOR`).
7. **Footprint and uncertainty.** The footprint is the 10th–90th percentile of the points in plan view. It is extended
   **0.25 m** away from the camera, because depth only sees the near surface. That figure is a stated size allowance
   (about a body's depth), not a measurement. The footprint is then grown by the uncertainty:
   `2 × (depth error at that range) + localization residual`. The depth error is 1 cm + 0.5 cm/m² for LiDAR, and
   3 cm + 2 cm/m² for ARKit person depth and ARCore depth. These are conservative orders of magnitude for each class of
   sensor, not a calibration of any one device. An unknown residual (localized from one marker) counts as 0.25 m, and
   the observation records that it was not measured. Above 0.75 m of uncertainty the result is `TOO_UNCERTAIN`; with
   ARCore depth that happens from about 4.3 m.
8. **Confidence** is the detector score times the share of depth samples that agree. It is a quality figure, not a
   probability.

**Corridor occupancy** works on depth alone. A measured point is an obstacle candidate when all of these hold:
- it is 0.3–1.8 m above the route's floor;
- it is within 0.25 m of the path still ahead in plan view;
- it is no more than 4 m from the camera.

Candidates are clustered on a 0.25 m grid, and a cluster needs at least 8 points. The route was planned on the scanned
static geometry, and Recast keeps the walking body's radius (0.35 m) between the route and that geometry. So walls and
door jambs stay outside the 0.25 m corridor, and something standing in it was **not part of the scan**: a moved or new
object, or a person. That is the evidence used to tell dynamic obstacles from static geometry. Its limit is that an
error in the reconstruction or the localization larger than about 0.1 m can bring static geometry into the corridor.
The confirmation rule and the "seen clear" rule below then act on it like on any other obstacle.

### Tracking over time

- **One obstacle, not many.** An observation joins the nearest track of a compatible category within 0.75 m plus both
  uncertainties. A Vision person and a depth cluster at the same place are one track, a person.
- **Confirmation.** A track is reported only after two sightings.
- **People walking by.** A person moving faster than 0.4 m/s (smoothed velocity) is not routed around: they will be
  somewhere else by the time the traveller gets there. The overlay says that people are moving near the route. A person
  who stands still blocks like an object.
- **Occlusion.** A track that is not detected again is kept while its place is hidden behind something nearer, or out
  of view. Hidden means the depth along that direction is more than 0.5 m short of it. It is also kept while depth still
  shows something there that the detector missed. It is dropped once its place has been measured clear (depth more than
  0.5 m beyond it) three times.
- **Expiry.** A person not seen for 10 s, or an object not seen for 30 s, is dropped, so no person becomes a permanent
  obstacle. A new floor or a new coordinate frame drops every track. A region is stamped with its last sighting, which
  the server checks against its 120 s limit.

### Rerouting

- **When.** A confirmed, not-moving obstacle that comes within the walking body's radius of the path still ahead, and
  that the current route did not already avoid, triggers a reroute. There is at most one request every 5 s and one at a
  time, and only when online. Obstacles beside or behind the traveller do nothing. An obstacle whose footprint contains
  the traveller is never sent, because the server has no route from inside a box.
- **The request.** It is the same route request as before, from where the device is now, with the same destination,
  the same accessibility profile (STEP_FREE stays step-free) and the same scan-version pinning, plus `blockedRegions` in
  the localization's coordinate frame. The AR session, its localization and the world anchor are untouched. Only the
  route and its instructions are replaced when the answer arrives.
- **No way around.** If the answer is `NO_ROUTE` (the obstacle blocks the only way, or the only step-free way), the
  current route is kept and the overlay explains why. The same obstacles never ask again; a new one does.
- **Restoring the normal route.** Once everything the route avoided has been gone for 5 s, the route is planned again
  without it, and the overlay says "back on the usual route".

### What the user sees

The obstacle lines live in the AR overlay on both clients (`obstacleMessages` / `ObstacleStatus.messages`):
- "A person is in the way about 3 m ahead (estimated position, ±0.3 m)." Positions are always estimates with their
  uncertainty, never shown as verified. "Something" is used for unclassified obstacles.
- Rerouting status: "Finding a way around…", "Route changed to avoid an obstruction", "back on the usual route".
- Why a route could not be recalculated: no way around, no step-free way around, or a request failure. In each case
  the current route is kept.
- "Obstacle detection paused (tracking is limited / not localized). Watch for people and objects yourself."
- "Obstacle detection is not available on this device (…)" when there is no depth.
- How often sensing runs and its p90 latency.

### Performance

| | Android (WebXR) | iOS |
|---|---|---|
| Where it runs | In the XR frame callback | On a background serial queue, never on the ARKit delegate queue |
| Work per run | Synchronous depth sampling of a 40 × 30 grid (20 × 15 when backed off) | Vision on the camera image; 12 × 16 depth samples per person box; a 32 × 24 corridor grid (16 × 12 when backed off) |
| Rate | At most 4 runs a second | At most 3 runs a second |
| Back-off | Interval doubles up to every 2 s while the 90th-percentile run takes more than 6 ms | Interval doubles up to every 2 s while the 90th-percentile run takes more than 150 ms |

A run never overlaps the previous one (`ObstacleScheduler`). Sensing makes **no network request** of its own. Route
requests are bounded as above. The latency of each run is measured and shown in the overlay.

### Privacy

No camera image is stored or sent:
- **Android** reads depth values only (not camera pixels), at a sparse grid, inside the frame callback.
- **iOS** reads the camera image and depth buffers in place on the device, for Vision and depth sampling, and releases
  them when the run ends.

Only obstacle footprints leave the device: boxes in venue metres with a time and a coordinate frame, in the route
request. They carry no image, no identity and no person attribute, and the server does not store them. Route requests
keep their venue authorization: the same authenticated, venue-scoped route endpoint as before. The iOS camera usage
text now says the camera is also used to notice obstacles and that images stay on the device.

### Verification and what it does not prove

Pure tests cover detection placement, tracking and rerouting separately, on synthetic depth that is ray-cast from boxes
through the real camera → AR world → venue chain.

`apps/web/lib/obstacle-sensing.test.ts` (localization) covers:
- static placement with its uncertainty, and an unknown residual;
- no depth, too little depth, low-confidence depth, and depth that is not one surface;
- a far, a low-score and a too-uncertain detection;
- tracking loss, no localization, and depth or a detection from another frame;
- a wrong transform (translated, axis change missing) and an overhead sign;
- corridor occupancy against walls beside the route, the floor and overhead beams, and multiple objects;
- visibility for occlusion, projection round trips, and the scheduler's bounds and back-off;
- one end-to-end case: depth → tracking → reroute → request body → clearing → restore.

`obstacle-tracker.test.ts` (tracking) covers:
- confirmation;
- a moving person versus a standing one;
- person and object lifetimes;
- temporary occlusion;
- multiple obstacles and duplicate detectors;
- frame changes.

`obstacle-reroute.test.ts` (route recalculation) covers:
- an obstacle outside the route;
- restoring the normal route after the hold;
- an unreachable reroute that does not ask again;
- an obstacle at the traveller;
- the existing staleness, interval and shift rules.

`obstacle-status.test.ts` covers what the overlay says.

`ObstacleSensingTests.swift` repeats these cases for the iOS core, including the route request body with
`blockedRegions` and STEP_FREE. The server's own tests cover the reroute on the real navmesh, including the accessible
case (`RealNavmeshNavigationTest`: a bin narrowing the 1.1 m doorway reroutes STEP_FREE through the 1.6 m one), a
`NO_ROUTE` when obstacles cut every path, and stale and other-frame rejection (`RouteServiceTest`).

**Not verified, and not provable without devices:**
- **Detection accuracy.** How often Vision finds people (and misses them, or fires on mannequins and posters) in a
  venue. That needs a labelled set of real venue frames, which does not exist yet.
- **Real depth.** The quality of real LiDAR, ARKit person depth or ARCore depth, and so whether the error figures above
  hold. On Android, two things are assumed from the WebXR Depth Sensing specification and still need confirming on a
  device: that `getDepthInMeters` takes top-left-origin normalized view coordinates, and that it returns distance along
  the view's −Z axis.
- **Real-time behaviour.** Inference latency, frame rate and battery on a real phone, and whether ARKit warns about
  retained frames while Vision runs.
- **The ARKit adapter, and the WebXR adapter's interaction with three.js.** `ObstacleSensor.swift` is parse-checked
  only; it is type-checked in the macOS CI job. `ar-depth-obstacles.ts` is type-checked and built, and neither has run.

Synthetic obstacle tests do not prove camera-based detection works. The device procedures are
[ar-android-validation.md](ar-android-validation.md) §6d and the iOS checklist in [ar.md](ar.md#obstacle-sensing-ios).

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
| Dynamic obstacles | **reported by the client** | `blockedRegions`. The server never invents one. The AR clients place them from measured camera depth; the 0.25 m far-side allowance, the per-sensor depth error figures and the 0.25 m unknown-residual allowance are **assumed** ("Camera obstacle sensing"). |

### Routing: what has and has not been validated

- **On real Recast/Detour output (synthetic venue geometry):** the navmesh in `packages/contracts/fixtures/navmesh` is
  the real output of chaya-navmesh 1.1.0 / recastnavigation 1.6.0 for the synthetic venue of
  `services/reconstruction/tests/navmesh/venue_scene.py`. On 2026-10-14 it was regenerated with the tool built on this
  machine (MinGW GCC 6.3): `navmesh.bin` and the manifest were byte-identical to the committed files, and the graph
  differed only by an empty `source` block the tests overwrite. Two test classes ingest it through the pipeline's own
  NAVIGATION_BAKING ingestion and route with production settings (no synthetic graphs):
  - `PipelineControlPlaneTest`: string-pulled routes clear of the wall and the sofa; STANDARD climbs the step, STEP_FREE
    takes the ramp; the required clear width picks the doorway or fails with `NO_ACCESSIBLE_ROUTE`; a graph edge without
    a portal is refused.
  - `RealNavmeshNavigationTest`:
    - a three-stop route, in the given order and optimised (optimised is never slower), every segment clear of walls and
      furniture;
    - an obstacle in the 1.1 m doorway reroutes through the 1.6 m one, and the same observation 10 minutes old does not;
      both doorways blocked fails `NO_ROUTE`;
    - a bin leaving about 0.85 m of the 1.1 m doorway: still walkable, but STEP_FREE goes through the wide doorway;
    - endpoints outside the navmesh are refused, and a start at camera height is accepted;
    - 32 concurrent requests (mixed profiles and obstacles) give exactly the sequential answers;
    - two floors, each its own ingested navmesh in its own frame, joined by stairs and a lift: STANDARD takes the stairs;
      STEP_FREE is refused until the lift is verified, then takes it, and is refused again when the lift is out of
      service;
    - the floor reconstructed again: a destination in the superseded frame is refused, the current state routes on the
      new graph, and a request pinned to the old scan version still routes on the old navmesh, with identical waypoints;
    - the configured clear width (1.3 m, 1.8 m) decides the doorway or fails closed.
- **Algorithmic unit tests only.** These use synthetic fixtures and are not evidence of navigation in any venue.
  - `RouteServiceTest` hand-builds graphs, frames and connections to test the routing rules:
    - unknown and insufficient clearance, steep and unmeasured slope, and steps;
    - stairs, elevators and ramps, including fail-closed width and slope, and unverified accessibility;
    - multi-floor routing between floors with unrelated frames, and chained connections;
    - out-of-service connections, and landings in a stale frame;
    - an obstacle that blocks an edge without covering a node, and rerouting around a dynamic obstacle;
    - stale, future and wrong-frame obstacle observations;
    - endpoints out of bounds and off the navmesh;
    - multi-stop: kept and optimised order, unreachable intermediate stops, step-free stops, a tour up a lift and back;
    - the graph cache: a republished graph is used at once; concurrent requests;
    - metric distance and duration from the geometry rather than stored lengths;
    - every failure code.
  - `RouteGeometryTest` covers segment/box blocking and polyline length.
  - `CorridorPathTest` covers string-pulling: on an L-shaped corridor every segment lies inside the polygons and the path
    turns exactly at the inner corner; a jogging corridor that the centroid polyline leaves, but the pulled path does not.
  - `tests/unit/test_navigation_baking.py` covers slope, rise and run against canonical +Z, and a step between two
    level polygons.
  - Web (`npm test`): `route-instructions.test.ts` (turns, connector distances, monotone progress, arrival dwell and
    the premature-arrival cases: through a wall, on the wrong floor, on another floor's leg), `obstacle-reroute.test.ts`,
    `multi-stop.test.ts` (only changed segments re-requested, unreachable stops, interruptions, republished graphs),
    `viewer-route.test.ts`, and `navigation-safety.test.ts` (deterministic test suite verifying the 11 safety requirements:
    narrow corridors and doorways rejecting insufficient clearance, dead ends and disconnected regions refusing routing,
    slopes and stairs with strict ADA limits, step-free alternatives requiring verified elevators, rejection of incorrect
    floor transitions, Liang-Barsky obstacle detection intersecting smoothed routes, endpoint snapping limits distinguishing
    out-of-bounds from off-navmesh, uncalibrated geometry refusal, stale navigation artifact rejection, no valid route
    contracts with zero partial path fabrication, and multi-destination waypoint chaining with isolated stop failures).
    Playwright (`e2e/viewer-interaction.spec.ts`) drives the viewer against a mocked API: several destinations, highlighting,
    removal without re-requests, recalculate, cancel, and an expired sign-in.
- **Not validated:**
  - No real venue has been routed on. No real reconstruction has been through NAVIGATION_BAKING (the stages before it
    need a CUDA GPU and Open3D, docs/E2E_VALIDATION.md).
  - No real multi-floor venue has registered or verified connections.
  - No real reconstruction has put a stair or ramp into the navmesh input. The synthetic venue fixture does (above).
  - No device has reported an obstacle: no client has an obstacle source, so dynamic obstacles have only been exercised
    with obstacles the tests report.
  - Instructions, progress and arrival have never run on a device walking a real route.

### Multi-floor: what has and has not been validated

- **On real Recast/Detour output:** `RealNavmeshNavigationTest`. Each floor is the real navmesh of the synthetic venue
  ingested by a full pipeline run, in its own frame.
  - Two floors joined by stairs and a lift.
  - Three floors with stairs 0–1 and 1–2 and a lift 0–2: STANDARD climbs both flights through the middle floor;
    STEP_FREE rides the verified lift; with the lift out of service STEP_FREE fails and the step-free graph has three
    components.
  - A historical (superseded) scan version still routes on its own navmesh.
  - A calibration whose control points do not fit is refused (`422`) and the floor keeps its trusted frame.
- **On synthetic graphs:** `RouteServiceTest` (disconnected floors and the floor-transition graph per profile; stairs
  closed, lift re-registered and verification withdrawn, lift closed), `FloorManagementTest` (floor management,
  elevation rules, connection corrections, the consistency report with consistent, inconsistent and not-comparable
  pairs), `FloorGeometryTest`.
- **Clients:**
  - `floor-transitions.test.ts` and `ar-relocalization.test.ts`: floor identification (pending, confirmed, unexpected,
    ambiguous) and the transition re-check.
  - `ar-integration.test.ts`: a two-floor session through an ambiguous view.
  - Playwright (Chromium): the viewer's floor badge, elevation labels, the "Show <floor>" control, and one replan when a
    lift on the route closes.
- **Not validated:**
  - All multi-floor fixtures are the same synthetic floor ingested several times. The landings, elevations and control
    points are hand-made, and the floors are related only by hand-registered connections or by the identity "survey"
    frames of the fixtures. **Nothing here shows that floors of a real building align**, or that automatic multi-floor
    stitching works: there is no automatic stitching.
  - No real building's elevations, connections or control points have been recorded.
  - AR floor identification has not run on a device: real marker visibility across atriums and stairwells, and how
    long identification takes in practice, are unknown.

### Separation of Evidence: Synthetic Fixtures vs Real Venue Acceptance

Deterministic test passes against synthetic geometry (Recast solo-mesh bakes, Detour string pulling, Dijkstra path searches, and mocked API responses) demonstrate that the geometric mathematics, coordinate transforms, and safety checks operate according to specification. **However, synthetic fixture success is not evidence of safe or accessible navigation in a physical building.**

Under no circumstances may a venue or route advertise metre-based accuracy, physical AR guidance, or wheelchair accessibility based solely on synthetic fixture tests or unverified user toggles. The product must strictly distinguish:
- **Synthetic verification**: Mathematical proof that algorithms behave deterministically on idealized, synthetic test inputs.
- **Physical acceptance**: On-site empirical evidence that physical geometry, clearances, slopes, and transitions match measured building ground truth.

### Physical validation still required

Before navigation can be called validated in a venue, these need doing on site, with a real reconstruction:

1. Bake a real, calibrated reconstruction of at least one floor (a GPU host) and check the navmesh against the building:
   walls and furniture block, doorways connect, no walkable area is missing or invented. Measure semantic-label quality
   on floors, stairs and furniture, and reconstruction height noise against `navmesh_step_min_rise_m` (0.03 m).
2. Measure a sample of doorway and corridor widths with a tape and compare them with `min_clearance_m`; walk ramps with
   an inclinometer and compare with `max_slope_deg`.
3. For every floor connection: measure stairs and ramp walked lengths, door and car widths and ramp slopes, register
   them, and only then record the accessibility verification. Confirm each landing POI is where the connector really is.
4. Walk routes with a localized device (Android WebXR and iOS): check that the drawn route matches the floor, that
   distance remaining agrees with a measured wheel, that turns are announced where the corridor turns, that arrival is
   declared within about 1.5 m and not before, and that floor handoffs happen at the connector.
5. Walk STEP_FREE routes with a wheelchair user (or a wheelchair-width trolley) and confirm every passage and lift is
   usable as claimed.
6. If an obstacle source is added to a client, validate its observations (position error, latency, false positives)
   before reporting them to routing.
7. Multi-floor:
   - Record each floor's elevation from a survey or a levelled measurement.
   - Calibrate floors that must share a datum against one survey's control points, then run
     `GET /floors/consistency` and resolve every contradiction.
   - Walk a stairs route and an elevator route between real floors with a device, checking floor identification at
     each landing (including atriums where another floor's markers are visible) and the replan when a lift is taken out
     of service.


## Validated accessible indoor pathfinding and real-venue navigation acceptance (2026-10-31)

### 1. Calibrated geometry & walkable surface extraction
Walkable geometry is baked exclusively from validated, metric-calibrated reconstruction artifacts (`canonical = true`, `point_source = TRIANGULATED_OBSERVATIONS`). Surfaces lacking calibration fail with `NOT_CALIBRATED` / `METRIC_CALIBRATION_REQUIRED`.
- **Surface model ground vs elevated surfaces**: The ground layer (`chaya_worker.navmesh.build_surface_model`) takes the median height of the lowest dense band ($\ge 3$ points within a 0.05 m band) of points labelled `floor` or `stairs`. Desks, table tops, counters, and ceiling clutter are strictly filtered out and never rasterized as walkable geometry.
- **Hole preservation**: Cells lacking dense ground points remain holes and receive no geometry; convex hulls are never formed over unscanned spaces.
- **Obstacle rasterization**: Points labelled `wall`, `furniture`, `clutter`, or `unknown` in the agent height band (0.4 m to 1.8 m above local ground) are baked as solid obstacle boxes extending 0.1 m below ground level.

### 2. Documented agent profiles & clearance assumptions
- **Standard agent profile**: Radius 0.35 m (body diameter 0.70 m), height 1.80 m, maximum climb 0.40 m, maximum slope 45°. Recast's `rcErodeWalkableArea` erodes the walkable boundary by 0.35 m. Any opening or doorway narrower than 0.70 m is completely eroded and returns `NO_ROUTE`.
- **Step-free accessible profile**: Maximum ramp slope 4.76° (1:12 ADA limit; worker bakes at 5.0° and API enforces 4.76°), minimum measured clearance 0.90 m along corridor edges and floor connections. Any unmeasured slope or clearance excludes the edge.
- **Certification distinction**: Step-free routing is mathematically and contractually distinguished from certified wheelchair accessibility. Floor transitions require verified on-site inspection (`accessibilityVerifiedAt`, `accessibilityVerifiedBy`); unverified elevators or ramps fail closed (`FLOOR_CONNECTION_UNAVAILABLE`).

### 3. Nearest-point snapping, collision constraints & metric length
- **Bounded snapping**: Start and destination coordinates snap to the nearest walkable polygon centroid within `node-snap-max-distance-meters` (5.0 m). Endpoints exceeding horizontal footprint margins (1.0 m) or vertical thresholds (1.0 m below / 2.5 m above walkable surface) are refused with `START_OUT_OF_BOUNDS` / `DESTINATION_OUT_OF_BOUNDS` (HTTP 422).
- **Collision-free path smoothing**: Detour string-pulling (funnel algorithm through stored canonical portal endpoints) generates paths where every vertex and segment stays strictly within walkable polygons. Continuous trajectory sampling at 2 cm resolution verifies that paths maintain $\ge 0.22\text{ m}$ safety margin from all walls, columns, sofas, and railings ($r_{\text{agent}} - e_{\text{max}} = 0.35 - 0.13\text{ m}$).
- **Metric length**: Edge weights and polyline distances derive from true 3D Euclidean distances in canonical metres, never from quantized grid indices or fabricated straight lines.

### 4. Real-venue corridor route acceptance
Empirical acceptance test (`test_real_venue_corridor_acceptance_with_independent_measurements_and_physical_obstacles` in `services/reconstruction/tests/navmesh/test_venue_navigation.py`):
- **Corridor setup**: Independently measured corridor (10.0 m length, 2.4 m width) with a known physical center column (0.4 m × 0.4 m at $x \in [4.8, 5.2]$, $y \in [1.0, 1.4]$, height 2.4 m).
- **Measured ground truth**:
  - Direct Euclidean distance between start (0.5, 1.2, 0.0) and destination (9.5, 1.2, 0.0): $9.00\text{ m}$.
  - Computed navigable route distance: $9.08\text{ m}$ (safety detour $+0.08\text{ m}$ to maintain $\ge 0.22\text{ m}$ clearance from the column). Measured distance error vs true geometry is $< 0.1\%$.
  - Collision verification: 0 of 460 sampled path points penetrate the column safety margin.
  - Out-of-bounds start (-2.0, 1.2, 0.0) strictly fails snapping with `NO_ROUTE`.

### 5. Dynamic obstacle observation & rerouting pipeline
- **Perception transform**: Camera depth points within candidate obstacle regions transform from camera frame $\rightarrow$ AR world $\rightarrow$ canonical venue coordinate frame.
- **Freshness & confidence**: Observations require platform tracking normalcy, frame synchronization ($\le 1\text{ ms}$), confidence $\ge 0.5$, and freshness $\le 120\text{ s}$ (`obstacle-max-age-seconds`). Stale observations are rejected with `STALE_OBSERVATION`.
- **Temporal tracking**: Obstacles require $\ge 2$ consecutive sightings to confirm. Moving persons ($> 0.4\text{ m/s}$) are tracked as passing and not routed around. Static tracks expire after 10 s (persons) or 30 s (objects).
- **Corridor intrusion & rerouting**: When a confirmed obstacle intersects the route corridor within the walking body's radius, a reroute request with temporary `blockedRegions` is issued (at most once every 5 s). If all bypasses are cut, `NO_ROUTE` is reported while maintaining previous instructions.
- **Perception honesty**: Devices lacking hardware depth sensors explicitly display `Obstacle detection is not available on this device`, never claiming live obstacle avoidance.
