# Capture-path planning

After a reconnaissance lap of about ten seconds, Chaya 02 estimates what the lap already covers and plans a
secondary route to improve reconstruction coverage. The planner is a custom, deterministic algorithm: no
generic navigation library, no learned model, no LLM, no randomness. The same input always produces the same
output, bit for bit.

Code: `services/api/src/main/java/dev/chaya/api/planning` (pure Java, no framework dependencies) and the
endpoint `POST /api/v1/venues/{v}/captures/{c}/route-plan` (`planning/api`).

## 1. Model and conventions
Plan view, metres. x is east, y is north; headings are radians counter-clockwise from +x (the API takes
degrees). The scene is rasterised onto a uniform grid of cell size `r` (default 0.25 m). Everything below is
defined on that grid; cell area is `a = r^2`.

**Inputs** (`PlanRequest`)

| Input | Meaning |
|---|---|
| `areas` | estimated room bounds, polygons; their union is the floor to scan |
| `walls` | interior partitions (block sight and walking). A doorway is a gap between wall segments |
| `obstacles` | furniture and columns (block sight and walking) |
| `noGoZones` | places the operator may not stand (they do not block sight) |
| `doorways` | optional annotations that raise the importance of the cells around them |
| `trajectory` | tracked recon-lap samples `(t, x, y, yaw?)` |
| `observedRegions` | optional coverage the device already reports (polygon + quality 0..1) |
| `candidates` | optional caller-supplied viewpoints, validated like generated ones |
| `config` | target capture distance `D`, field of view, minimum separation, and the tunables of section 9 |

**Outputs** (`PlanResult`): ordered waypoints (position, heading, type, reason, expected coverage gain, leg and
cumulative distance and time), the route polyline, estimated distance and capture time, route confidence,
baseline and planned coverage metrics with their difference, remaining uncovered regions, rejected candidates,
and diagnostics (grid size, walkable and reachable area, stop reason, warnings).

## 2. The algorithm
The nine steps of the specification map to code as follows.

| # | Step | Where |
|---|---|---|
| 1 | normalise the recon-lap trajectory | `TrajectoryNormalizer` |
| 2 | estimate the walkable region | `Grid` |
| 3 | identify candidate viewpoints | `CandidateGenerator` |
| 4 | estimate what each viewpoint observes | `Visibility` |
| 5 | score candidate viewpoints | `CoverageState.gain` |
| 6 | penalise redundant viewpoints | redundancy factor, minimum separation |
| 7 | prioritise high-information viewpoints | cell importance weights |
| 8 | solve for an efficient ordered route | sequential greedy, then 2-opt on walking distance |
| 9 | return a route with measurable coverage | replay of lap + route through `CoverageState` |

### Step 1: trajectory normalisation
Samples are processed in time order (stable). A sample is dropped if it has a non-finite value; if its time does
not advance; if it implies a speed above `v_max` (default 3 m/s, a tracking glitch); or if it moved less than
half a pose spacing (stationary jitter). The kept polyline is resampled by arc length at exactly `s` (default
0.25 m) with linear interpolation, always ending where the lap ended. Headings are interpolated along the
shortest arc if every kept sample has one; otherwise they are the direction of travel. Counts of dropped samples
are reported as warnings.

### Step 2: walkable region
Cells are `OUTSIDE` (beyond the bounds), `WALL`, `OBSTACLE` or `FREE`. Anything not `FREE` blocks sight and
walking. Clearance `c(i)` is the distance from a free cell to the nearest blocked face (two-pass chamfer
transform). A cell is **standable** if it is free, `c >= agentRadius`, and outside every no-go zone. The
**reachable** set is found by flood fill over 8-connected standable cells (no corner cutting) from the trajectory
cells: an area behind a solid wall is not reachable, an area behind a doorway gap is.

The **target set** `T` is everything that must be reconstructed: every free cell plus every blocked cell touching
a free cell (wall and obstacle faces). `|T| a` is the target area.

### Step 3: candidate viewpoints
Deterministic, in priority order, each on a standable and reachable cell, one candidate per cell:
supplied viewpoints (validated); a viewpoint in each doorway and `D` in front of and behind it; for each
corner, the cell about `D` away with a clear line to it; a ring of cells about `D` from the walls, thinned to
the minimum separation; and a regular lattice over the floor, subsampled evenly if it exceeds the candidate
budget. Supplied viewpoints that fail validation are returned with a reason: `NOT_FINITE`, `OUTSIDE_BOUNDS`,
`IN_OBSTACLE`, `TOO_CLOSE_TO_WALL`, `IN_NO_GO_ZONE`, `UNREACHABLE`, `DUPLICATE`.

### Step 4: what a viewpoint observes
A camera at position `p` sees target cell `c` if `d = |c - p|` is in range, the line of sight is clear, and (for
an oriented view) the bearing is inside the field of view.

- Range quality: `w_d(d) = max(0, 1 - |d - D| / D)`. It peaks at the target distance `D` and is zero at 0 and `2D`.
  Views with quality below `q_min` (default 0.2) are ignored, so the usable range is `[D q_min, D (2 - q_min)]`.
- Field of view: with `alpha` the angle between the bearing and the heading and `F` the field of view,
  `w_f = 1 - 0.5 (alpha / (F/2))^2` for `alpha <= F/2`, else the cell is not seen. Quality of one view is `w = w_d w_f`.
- Line of sight: a discrete line (Bresenham) between the two cell centres; any blocked cell strictly between them
  hides the target, and a step that would slip diagonally between two blocked cells is blocked too.

Cells in range but hidden are recorded as **shadow**: the occlusion zones of the recon lap.

## 3. The coverage metric
For each target cell `c`, given a set `S` of views, let `A(c)` be the sum of view qualities and `gamma(c)` the
widest angle between any two directions the cell has been seen from (the triangulation angle). The cell score is

```
f(c | S) = min(1, A(c) / A0) * min(1, gamma(c) / gamma0)
```

with `A0` = 1.5 (about two good views) and `gamma0` = 10 degrees by default. A cell is **covered** when
`f(c) = 1`: seen well enough, and from far enough apart, to triangulate a point. `f` is monotone: adding a view
never lowers it, and the tests check that.

The quantities the product exposes, computed for the recon lap alone (baseline) and for the lap plus the planned
route (planned), by the same code:

| Metric | Definition |
|---|---|
| **coverage %** | `100 * a * |{c in T : f(c) = 1}| / (a |T|)`, the share of target area that is covered |
| **uncovered area** | `a |T| - covered area`, m2 |
| **weighted coverage %** | `100 * sum I(c) f(c) / sum I(c)`: partial coverage counts, important cells count more |
| **redundant capture %** | `100 * (sum A(c) - sum min(A(c), A0)) / sum A(c)`: observation quality beyond what coverage needed |
| **route length** | metres walked on the secondary route; `pathLengthMeters` also includes the lap |
| **waypoints** | number of planned stops |

`observedRegions` credit a cell `quality * A0` of observation mass with the triangulation requirement assumed
met. Comparing baseline and planned answers "what does this route buy": `improvement` is exactly their difference.

**Weights `I(c)`** (importance): 1 for plain floor; 1.3 for wall and obstacle faces and floor within 0.5 m of
them; 2 within 0.75 m of a corner (polygon vertices with a turn of 15 degrees or more, obstacle vertices, wall
ends); 2 within half a doorway width plus 0.5 m of a doorway. The largest applicable weight is used.

## 4. Choosing waypoints (steps 5-7)
State starts as the recon lap. Repeatedly, for every unused candidate `v` (not within the minimum separation of a
chosen waypoint, and reachable) and every heading `h` in `headingCount` equally spaced directions:

```
G(v,h)    = sum over cells c seen by (v,h) of  I(c) a [ f(c | S + (v,h)) - f(c | S) ]      marginal weighted gain
rho(v,h)  = observation quality falling on already-covered cells / total observation quality   redundancy
score     = G (1 - lambda rho) / (beta + walk(current, v))
```

`lambda` (default 0.5) is the redundancy penalty; `walk` is the shortest walking distance from the last chosen
waypoint (Dijkstra on the standable grid); `beta = travelWeight * D` regularises very short legs. The best
`(v,h)` wins; ties are broken by higher gain, then shorter walk, then the fixed candidate and heading order, so
the outcome is deterministic. The leg walked to the waypoint is added to the state too (a walked leg is
captured), then the waypoint's view. The loop stops when the covered share of the target area reaches
`stopCoverage` (default 95%), `maxWaypoints` is reached, or the best gain is below `minMarginalGain`.
The stop reason is returned.

Redundant viewpoints are handled three ways: a candidate can only help by the marginal gain it adds (a view of
covered cells adds nothing); the factor `(1 - lambda rho)` further penalises mostly-redundant views; and the
minimum separation forbids two waypoints closer than a configured distance.

## 5. Ordering the route (step 8)
The chosen waypoints are reordered by 2-opt on geodesic walking distance with the start (where the lap ended)
fixed and the end open, repeating strict improvements until none remain. Legs are then smoothed by
line-of-sight string pulling on the standable grid, so the polyline never crosses a wall or obstacle. 2-opt does
not lengthen the greedy order; diagnostics report both `constructionRouteMeters` and the final distance.

## 6. Measuring the result (step 9)
The final route is replayed from the baseline in its final order: for each waypoint, the poses along the leg
(every `s` metres, heading = direction of travel) and then the waypoint view are added. Each waypoint's
`expectedCoverageGain` is the covered area added by its leg and view in that order, so the gains sum exactly to
`planned - baseline`. Capture time is `distance / walkSpeed + waypoints * dwell` (defaults 0.5 m/s and 3 s).

Because reordering changes which legs capture what, measured coverage after ordering can differ by a few points
from the coverage the construction loop stopped at; both are returned (`constructionCoveragePercent`).

## 7. Waypoint type
The type is derived from what the waypoint measurably adds, not from where the candidate came from:
`DOORWAY` if it stands within half a doorway width plus 1 m of a doorway or at least 25% of its gain is in
doorway cells; else `CORNER` if at least 25% of its gain is at corners; else `OCCLUSION` if at least 30% of its
gain is in cells the recon lap could see (in range) but not see past geometry; else `BOUNDARY` if at least 50%
is wall or boundary surface; else `COVERAGE` (filling uncovered floor). The `reason` text states the shares.

## 8. Uncovered regions and confidence
Uncovered regions are 8-connected components of target cells that are not covered, at least two cells large,
with area, centroid and bounding box, largest first. `NOT_OBSERVABLE_FROM_EVALUATED_VIEWPOINTS` means no
evaluated reachable viewpoint sees most of the region (a sealed room, or an area no candidate could reach);
`BELOW_COVERAGE_TARGET` means it is observable but the planner stopped first.

**Route confidence** is a heuristic in [0, 1], not a calibrated probability:

```
confidence = E_traj * (0.5 + 0.5 E_reach) * (0.5 + 0.5 E_plan)
E_traj  = (fraction of lap poses on free floor of the scene) * min(1, lap length / (2 D))
E_reach = reachable standable area / all standable area
E_plan  = planned coverage fraction
```

## 9. Configuration
All defaults are in `PlannerConfig.defaults()`; the API accepts overrides by name (unknown names are refused,
`maxCandidates` and `maxGridCells` are server limits). Resolution 0.25 m, agent radius 0.30 m, target distance
`D` 2.0 m, field of view 70 degrees, minimum separation 1.0 m, 8 headings, 20 waypoints, stop coverage 95%,
`A0` 1.5, `gamma0` 10 degrees, `q_min` 0.2, `lambda` 0.5, `beta` = 1 D, 0.5 m/s, 3 s dwell. Invalid values are
refused with `INVALID_CONFIG` naming the field.

## 10. Properties and limits
- **Deterministic**: no randomness, fixed iteration order, no hash-ordered collections; tested by planning twice
  and comparing whole results.
- **Bounded**: grid, candidate and input sizes are limited; complexity is roughly
  `O(waypoints x candidates x headings x cells seen)` for selection plus Dijkstra per waypoint. Measured times are
  in section 11.
- **2D**: the model is plan view. It does not represent ceiling height, floor level changes, viewing incidence on
  surfaces, or dynamic obstacles. Wall and obstacle faces are cells, not oriented surfaces, and grazing views are
  not penalised beyond range and field of view.
- **Estimates in, estimates out**: room bounds come from a 10 s lap and may be wrong; the planner treats them as
  given. Coverage is the model's prediction, not a measurement of a reconstruction: it says which views should
  make cells triangulable, not that the reconstruction will succeed.
- `gamma` remembers up to six view directions per cell; for cells seen more often it is a lower bound.
- The greedy loop is a heuristic. It carries no optimality guarantee, and coverage as defined is not strictly
  submodular.

## 11. Testing and benchmark
`mvn test` runs the planner tests: geometry, trajectory normalisation, grid and reachability, visibility and
occlusion, the coverage model (monotone; predicted gain equals actual change), and the planner on deterministic
synthetic fixtures (rectangular room, room with doorway, room with occlusion, multiple rooms, invalid candidates,
duplicate and redundant waypoints, unreachable rooms) with invariants checked on every plan. The fixtures are
hand-drawn geometry used only for tests and the benchmark; they are not application data. Deliberately breaking
line of sight or the separation rule makes several tests fail, which is how the tests were checked.

`scripts/benchmark-planner.sh [--csv out.csv]` compares, with one coverage metric: the recon lap alone, the
planner, a boundary loop and a lawnmower sweep at the same waypoint count, and the lawnmower cut off at the
planner's route length.

**Measured on the synthetic fixtures** (Java 21, 18 cores, 2026-09-21; planner time is the median of 7 runs after
3 warm-ups). Nothing here describes real captures.

| fixture | strategy | wp | route m | coverage % | uncovered m2 | ms |
|---|---|--:|--:|--:|--:|--:|
| rectangular room 8x6 | recon lap only | 0 | 0 | 38.2 | 34.1 | |
| | planner | 13 | 15.7 | 92.6 | 4.1 | 73 |
| | boundary loop, 13 wp | 13 | 12.9 | 88.1 | 6.6 | |
| | lawnmower, 13 wp | 13 | 25.1 | 88.8 | 6.2 | |
| | lawnmower, same distance | 7 | 15.2 | 71.7 | 15.6 | |
| room with partition | recon lap only | 0 | 0 | 16.9 | 44.2 | |
| | planner | 17 | 21.6 | 90.2 | 5.2 | 56 |
| | boundary loop, 17 wp | 17 | 17.6 | 69.2 | 16.4 | |
| | lawnmower, 17 wp (13 reachable) | 13 | 29.7 | 88.0 | 6.4 | |
| | lawnmower, same distance | 8 | 21.3 | 78.6 | 11.4 | |
| office 16x10 + annex, cap 20 | recon lap only | 0 | 0 | 15.0 | 164.4 | |
| | planner | 20 | 31.9 | 49.4 | 97.9 | 285 |
| | boundary loop, 20 wp | 20 | 52.8 | 61.0 | 75.6 | |
| | lawnmower, 20 wp | 20 | 53.5 | 59.6 | 78.1 | |
| | lawnmower, same distance | 10 | 26.6 | 41.3 | 113.7 | |
| office, cap 40 | planner | 40 | 61.9 | 78.2 | 42.1 | 517 |
| | boundary loop, 40 wp | 40 | 68.3 | 72.7 | 52.9 | |
| | lawnmower, 40 wp | 40 | 101.1 | 78.2 | 42.3 | |
| | lawnmower, same distance | 23 | 61.2 | 62.6 | 72.3 | |

The complete table (all fixtures) is what the script prints. What it does and does not show:
- At the same walking distance the planner covered more than the lawnmower in every fixture measured.
- At the same waypoint count it is not always ahead: in the office fixture with the default cap of 20 it stops
  after 32 m of walking at 49% and both fixed strategies, which walk about 53 m, reach about 60%. With the cap raised to
  40 it ties the lawnmower at 78% while walking 62 m instead of 101 m. Waypoint count is not a like-for-like
  budget because the planner's waypoints are close together; compare distance.
- Two comparators are simple by design; this is not a comparison against every possible planner, and synthetic
  geometry is not evidence about real rooms. Field trials against real reconstructions are still to do.

## 12. Reconnaissance, room bounds and priorities
Automatic capture planning (docs/capture-hud.md) adds three inputs in front of the same planner. Code:
`Reconnaissance`, `RoomBoundsEstimator`, `EstimatedScene`, `PriorityRegion`.

**Trajectory segments.** `TrajectorySample.segment` marks tracking breaks. The normaliser resamples each segment on its
own and never joins two: the first sample after a break is always kept, the speed and jitter rules compare samples of
one segment only, and the length across a break is not counted. `Reconnaissance.trajectory` assigns segments from stored
positions: a new one at every reported loss, every gap over 1.5 s between tracked samples, every change of source class,
and between manual marks more than 60 s apart.

**Bounds estimation.** A plan-view occupancy grid (`r_b` = 0.2 m) with free and occupied votes per cell:
- every walked position clears a disc of 0.3 m (the operator's footprint), and so does the straight line between two
  consecutive samples of one segment;
- a WALL hit at `h` seen from camera position `o` clears the cells of the discrete line `o -> h` and votes `h`'s cell
  occupied; a FLOOR hit clears its own cell; hits farther than 8 m are discarded;
- two consecutive wall hits from origins within 0.5 m clear the quadrilateral between their rays when the hits lie on one
  surface (normals within 25 degrees, hits at most 0.75 m apart) or, without normals, at most 0.3 m apart; between
  same-surface hits the surface is voted occupied.

A cell is occupied when `occ >= 1` and `2 occ >= free`, free when it has free votes, else unknown. The bounds are the
4-connected free component containing the walk, closed with a 5 x 5 square (gaps up to two cells, never onto occupied
cells). Holes enclosed by it are split: occupied cells are observed obstacle faces (unknown cells enclosed by those faces
alone are the obstacle's solid interior); the remaining unknown pockets are **unobserved interior** (inside the room,
not standable, not blocking sight, prioritised). The outer boundary is traced on cell edges and simplified with
Douglas-Peucker (tolerance 0.75 `r_b`). Each boundary edge whose outside cell is, or touches, an occupied cell is
observed; the rest is **frontier**: where observation stopped, not a wall.

```
confidence = boundaryObserved x interiorObserved x density
boundaryObserved = observed boundary length / boundary length
interiorObserved = 1 - unobserved interior area / area
density          = min(1, wall observations / 12)
```

ESTIMATED requires confidence >= 0.75, boundaryObserved >= 0.85, at least 12 wall observations, and no frontier stretch
of 0.8 m or more (an opening the room may continue through, such as a doorway or the end of a partition); otherwise
LOW_CONFIDENCE, a lower bound of the room. Fewer than 1 m2 of free region, no walk, or no wall observation at all is
NOT_ESTIMABLE. A measured outline is not estimated, only checked: walked positions more than 0.3 m outside it, observed
walls more than 0.3 m outside it, and edges with no observed wall within 0.3 m are reported.

**Priority regions.** Every frontier stretch becomes a band (cells within 0.75 m of it, importance at least 2.5) and every
unobserved pocket a polygon (importance at least 2.0); cells in them are flagged UNOBSERVED. A waypoint with at least 25%
of its gain there (after the doorway rule) is typed `UNOBSERVED`. Because the bounds include only observed floor,
waypoints stand only where floor was observed; a plan looks past the frontier, and the next estimate grows the bounds.

**Live assessment and progress.** `CapturePathPlanner.assess` is the baseline replay alone (no candidate search), with
gaps labelled NOT_OBSERVED or INSUFFICIENTLY_OBSERVED. `CapturePathPlanner.progress` judges a stored plan against raw
samples recorded after it (visit radius 0.5 m; heading within half the field of view when every sample has one) and
re-checks each waypoint's standability and reachability in the current bounds.

**Benchmark** (`scripts/benchmark-planner.sh --bounds`, `BoundsPlanningBenchmark`; Java 21, 18 cores, 2026-10-11).
Walls are observed with `SyntheticRangeSensor`: a noise-free plan-view ray caster that pans the camera at 40 deg/s with
7 rays 6 degrees apart per 2 Hz sample. Every route is measured on the TRUE room; "oracle" is the planner given the true
room. Nothing here is field accuracy.

| fixture | bounds | conf | IoU | precision | recall | wp | not standable in truth | true coverage % | oracle % |
|---|---|--:|--:|--:|--:|--:|--:|--:|--:|
| rectangular 8x6 | ESTIMATED | 0.99 | 0.94 | 1.00 | 0.94 | 9 | 0 | 88.8 | 95.2 |
| rectangular, north wall never faced | LOW_CONFIDENCE | 0.56 | 0.42 | 1.00 | 0.42 | 7 | 0 | 64.9 | 93.0 |
| rectangular, 6 observations only | LOW_CONFIDENCE | 0.03 | 0.27 | 1.00 | 0.27 | 7 | 0 | 70.8 | 95.2 |
| L-shaped 9x8 | ESTIMATED | 1.00 | 0.93 | 1.00 | 0.93 | 11 | 0 | 88.5 | 94.3 |
| slanted walls | ESTIMATED | 0.98 | 0.94 | 1.00 | 0.94 | 11 | 0 | 92.8 | 89.2 |
| 10x6 + pillar, one side walked | LOW_CONFIDENCE | 0.69 | 0.53 | 1.00 | 0.53 | 10 | 0 | 60.6 | 93.1 |
| 10x6 + partition, one side walked | LOW_CONFIDENCE | 0.91 | 0.44 | 1.00 | 0.44 | 8 | 0 | 50.2 | 92.1 |
| office 16x10 + annex (cap 20) | LOW_CONFIDENCE | 0.46 | n/a | n/a | n/a | 20 | 0 | 56.3 | 49.4 |

- Precision is 1.00 everywhere: the estimate never claimed floor outside the true room by more than a cell, and no
  waypoint was placed where the true room has no standable floor.
- Every case rated ESTIMATED has IoU >= 0.93; every partial view is LOW_CONFIDENCE. One estimate is not the room when
  the walk could not see all of it; iterating (walk the plan looking around, re-estimate, re-plan) closed the gap:
  north wall never faced: IoU 0.42 -> 0.94 (ESTIMATED) after 1 round; partition: 0.44 -> 0.51 -> 0.93 (ESTIMATED) after 2.
- Estimation takes 1-4 ms per call on these rooms. On a 640-sample walk, `assess` (the live tick) took 26 ms against
  37 ms for a full `plan`: the baseline replay dominates both.
- The existing planner benchmark (section 11) is unchanged by these additions.
