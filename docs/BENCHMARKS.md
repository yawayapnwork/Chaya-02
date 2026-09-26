# Chaya 02 technical benchmarks

**Date:** 2026-09-24.
**Code under test:** commit `372b5b5` plus the working tree, including the POI embedding job
(`PoiEmbeddingService`, [section 3](#3-benchmark-3-semantic-search)).
**Suite:** [`benchmarks/`](../benchmarks/README.md). **Raw results:** `benchmarks/results/<benchmark>/latest.json`.

## What this suite can and cannot show

The question is **which parts of Chaya 02 are our engineering contribution, and whether each one beats the obvious
alternative.** Every number below carries one of these labels, the same `kind` the result files use:

| Label | Meaning |
|---|---|
| **measured** | produced by running real code on the stated input during these runs |
| **estimate** | computed from measured values and configured constants (the formula is given) |
| **configuration** | a setting of the system or of the benchmark |
| **assumption** | taken as given; the text says what it stands in for |
| **reference** | an analytic comparison, for example what a random ranking would score |
| **unavailable** | could not be measured here; the missing data and the exact command are given |

**The limit that shapes everything:** this host has no CUDA GPU. `SPLAT_RECONSTRUCTION` cannot run
(docs/E2E_VALIDATION.md), so no venue has ever been reconstructed. Every measurement that needs a real
reconstruction is **unavailable**, including real coverage, rendering quality, real navigation graphs and real
rescan timings. What *was* measured used:

| Data | Kind | Used by |
|---|---|---|
| The running system: API, pgvector, CLIP vision service, embedding job | real | B3, B4 |
| A real trained 3D Gaussian Splat of an indoor room ("playroom", 1,916,379 Gaussians, Voxel51/gaussian_splatting on Hugging Face) | real | B2 |
| A real COLMAP reconstruction of 11 photographs of the Château de Sceaux (8,911 points with per-point reprojection error and track length) | real | B2, B5 |
| Hand-drawn floor plans with scripted recon laps (planner fixtures) | **synthetic** | B1 |
| An author-written venue of 30 POIs and 59 queries | **author-constructed** | B3 |
| A hand-made two-floor routing graph | **synthetic**, self-test only | B4 |

## Summary

| # | Our contribution | Beats the obvious alternative? | Strength of evidence |
|---|---|---|---|
| 1 | Capture-path planner | Yes, at equal walking distance, on all 7 synthetic scenes. **Not** at equal waypoint count on the largest one. | Synthetic scenes and the planner's own coverage model only. Real reconstruction coverage: **unavailable**. |
| 2 | Geometry cleanup | Statistical outlier removal targets weak points **6.6×** better than chance on real SfM evidence. The radius filter barely beats chance (1.3×) and removes over half the cloud. | Real data, proxy metric. Rendering quality, and semantic-aware cleanup: **unavailable**. |
| 3 | Semantic search (CLIP + embedding job + relevance filter) | Yes for synonyms: top-1 **58.8% vs 5.9%** lexical without the filter. With the relevance filter (calibrated on a separate venue), **86.7%** of nonsense queries now correctly return nothing (was 0%), at a cost: answerable top-1 **79.5% → 70.5%**. With vision down, fallback p95 is now **5 ms** (was 3.8 s). | Real system, small author-built datasets. |
| 4 | Accessible routing | The router's rules hold on a self-test graph (stairs, a narrow passage and a floor change avoided). | Synthetic self-test. Real venue: **unavailable**. |
| 5 | Incremental rescan | Alignment recovers known misalignments to ~0.02° when noise-free. The confidence gate rejected every failed alignment at the default setting (**73/73**). The gate is sensitive to voxel size. | Real geometry, controlled misalignment. Real rescan timings: **unavailable**. |

---

## 1. Benchmark 1: capture path

**Question.** After a ~10 s reconnaissance lap, does Chaya's planned route improve coverage more than simply
walking further?

**Conditions**, each with the same coverage model:

- **A:** the recon lap only.
- **B:** the recon lap plus Chaya's planned route.
- Two naive strategies with **the same number of waypoints** as B: a *boundary loop* (evenly spaced around the walls,
  facing the wall) and a *lawnmower* sweep.
- A lawnmower cut off at **the planner's own route length**.

**Run:** `python benchmarks/b1_capture_path/run.py`. This wraps `PlannerBenchmark`, extended in this work with
capture duration, redundant viewpoints and JSON output.

**Assumptions (read these first):**

- The 7 scenes are **hand-drawn synthetic floor plans** with scripted laps (`PlannerFixtures`). They stand in for
  real venues.
- Coverage is the **planner's own visibility/triangulation model** (docs/route-planning.md §3), not
  reconstructed geometry. The planner optimizes this same model, so B beating A is expected by construction. The
  informative comparison is B against the naive routes at equal effort.

**Configuration:**

- walking speed 0.5 m/s; dwell 3 s per waypoint; target capture distance 2 m (`PlannerConfig.defaults()`);
- a waypoint is **redundant** if, added in route order, it covers under 0.25 m² (four 0.25 m cells) of new area.

**Results** (predicted coverage and uncovered area are **measured** outputs of the model on these scenes; capture
time is an **estimate** = lap time + route length ÷ 0.5 m/s + waypoints × 3 s):

| Scene (synthetic) | A: lap only | B: planner | Boundary loop, same wp | Lawnmower, same wp | Lawnmower, same distance |
|---|---|---|---|---|---|
| Room 8×6 | 38.2% · 34.1 m² · 10 s | **92.6% · 4.1 m²** · 15.7 m · 80 s · 0 red. | 88.1% · 6.6 m² · 12.9 m | 88.8% · 6.2 m² · 25.1 m | 71.7% · 15.6 m² · 15.2 m |
| Room + doorway + corridor | 31.1% · 31.5 m² | **92.1% · 3.6 m²** · 20.3 m · 91 s · 0 red. | 72.7% · 12.5 m² · 8.5 m · 3 red. | 69.1% · 14.1 m² · 14.6 m | 69.1% · 14.1 m² · 14.6 m |
| Partition (occlusion) | 16.9% · 44.2 m² | **90.2% · 5.2 m²** · 21.6 m · 101 s · 0 red. | 69.2% · 16.4 m² · 17.6 m · 5 red. | 88.0% · 6.4 m² · 29.7 m · 1 red. | 78.6% · 11.4 m² · 21.3 m · 1 red. |
| Three rooms, two doorways | 18.7% · 45.8 m² | **89.8% · 5.8 m²** · 20.1 m · 99 s · 0 red. | 77.1% · 12.9 m² · 21.1 m · 1 red. | 85.3% · 8.3 m² · 31.0 m | 63.2% · 20.7 m² · 19.1 m |
| Obstacle + sealed neighbour | 28.2% · 54.4 m² | **81.0% · 14.4 m²** · 25.0 m · 121 s · 0 red. | 67.0% · 25.0 m² · 23.9 m · 4 red. | 78.9% · 15.9 m² · 42.4 m · 3 red. | 63.2% · 27.9 m² · 22.1 m |
| Office 16×10 + annex (20-waypoint cap) | 15.0% · 164.4 m² | 49.4% · 97.9 m² · 31.9 m · 138 s · 0 red. | **61.0% · 75.6 m²** · 52.8 m | 59.6% · 78.1 m² · 53.5 m | 41.3% · 113.7 m² · 26.6 m |
| Same office, cap 40 | 15.0% · 164.4 m² | **78.2% · 42.1 m²** · 61.9 m · 258 s · 0 red. | 72.7% · 52.9 m² · 68.3 m · 4 red. | 78.2% · 42.2 m² · 101.1 m · 1 red. | 62.6% · 72.3 m² · 61.2 m |

Cells read: predicted coverage · predicted uncovered area · secondary-route length · estimated total capture time ·
redundant waypoints ("red."; omitted where 0). Full numbers: `benchmarks/results/b1_capture_path/latest.json`.
Planning took 22–226 ms (**measured**, median of 7).

**What it shows:**

- **At equal walking distance** (the last column: the lawnmower cut at the last waypoint that fits in the planner's
  route length) the planner has higher predicted coverage on every scene, by 8–27 percentage points.
- **At equal waypoint count it is not always best.** On the office scene with the default 20-waypoint cap, the
  boundary loop (61.0%) and the lawnmower (59.6%) beat the planner (49.4%). They walk about 1.7× as far to do it.
  The planner trades coverage for distance. With the cap raised to 40, it matches the lawnmower's coverage in 61%
  of its distance.
- **The planner produced 0 redundant waypoints on every scene**; the naive routes produced up to 5. This is partly
  by design, because the planner stops adding waypoints below a minimum marginal gain. It still shows the naive
  routes spend effort on already-covered floor.
- It **does not** show real reconstruction coverage. Predicted coverage is a model; nothing here checks it against
  a reconstruction.

**Unavailable:**

| Measurement | Missing | Command |
|---|---|---|
| Reconstruction coverage and uncovered area, A vs B | Paired real captures of the same venue (A: lap only; B: lap + planned route), same operator and device, reconstructed on a GPU host, plus a surveyed floor plan in the reconstruction frame | `python benchmarks/b1_capture_path/reconstruction_coverage.py --floor-plan floor.json --splat A/splat-clean.ply --splat B/splat-clean.ply --labels A B --out coverage.json`. The metric definition is fixed in that file (floor cells with ≥ 3 Gaussians of opacity ≥ 0.1 within 0.10 m of floor height) and unit-tested. |
| Real capture duration | The same captures | `SELECT id, duration_seconds FROM capture_session WHERE id IN ('<A>', '<B>')` |
| Real redundant viewpoints | The same captures | `dropped.near_duplicate` in each run's `FRAME_QUALITY_REPORT` (written by `FRAME_QUALITY_FILTER`) |

## 2. Benchmark 2: geometry cleanup

**Question.** Do the pipeline's cleanup methods remove the right Gaussians?

**Methods** (the pipeline's own functions and settings, `chaya_worker.geometry_cleanup`): baseline (none);
opacity filtering; outlier filtering (statistical and radius); semantic-aware.

**Run:**

```
docker run ... chaya-bench-open3d python benchmarks/b2_geometry_cleanup/run.py \
  --ply playroom/point_cloud.ply --sfm-points castle-sfm/points3D.txt
```

**Which metrics are valid.** Whether a removal *improves* a splat can only be judged by re-rendering it at held-out
views (PSNR/SSIM). That needs torch, gsplat, a CUDA GPU and the scene's training frames, so it is **unavailable**.
What is valid without it:

- on the real splat: how much each method removes, how fast, and how much the methods agree;
- on the real SfM cloud: a **proxy** with real evidence. COLMAP records every point's reprojection error and track
  length, so a filter can be scored on whether it removes the points COLMAP itself found weakly supported.

**Assumptions:**

- A "weak" SfM point has track length ≤ 2, or reprojection error above the cloud's 90th percentile (0.748 px).
  This is COLMAP's evidence, not ground truth.
- Both inputs have arbitrary units (trained from COLMAP poses). The statistical filter is scale-invariant. The
  radius filter's production value (0.05 m) is metric, so it was also run at a **configured** scale-relative
  radius (3 × median nearest-neighbour distance).

### 2a. Real trained splat: "playroom", 1,916,379 Gaussians (measured)

| Method | Removed | Runtime | Median opacity of removed |
|---|--:|--:|--:|
| baseline | 0 | — | — |
| opacity filtering (opacity < 0.05) | 544,615 (28.4%) | 0.004 s | 0.025 |
| outlier filtering: statistical (20 neighbours, 2.0 σ) | 38,260 (2.0%) | 5.2 s | 0.101 |
| outlier filtering: radius, production (8 points within 0.05 units) | 234,063 (12.2%) | 7.5 s | 0.145 |
| outlier filtering: radius, scale-relative (8 within 0.0247) | 743,919 (38.8%) | 4.4 s | 0.152 |
| semantic-aware | **unavailable** | | |

The overlap of removed sets is **measured** as Jaccard similarity: opacity vs statistical 0.02, opacity vs radius
0.07–0.13. The methods remove **mostly different Gaussians**. Opacity filtering removes near-transparent ones;
outlier filtering removes isolated ones that are often fairly opaque.

### 2b. Real SfM cloud, COLMAP-evidence proxy (measured)

8,911 points, of which 14.1% are weak.

| Method | Removed | Weak among removed (precision) | Enrichment over chance | Weak points caught (recall) | Median reprojection error, removed / kept |
|---|--:|--:|--:|--:|--:|
| statistical outlier | 0.77% | 92.8% | **6.6×** | 5.1% | 0.90 / 0.32 px |
| radius, scale-relative | 55.7% | 18.7% | 1.3× | 73.6% | 0.34 / 0.30 px |

**What it shows:**

- **Statistical outlier removal is precise and conservative.** Almost everything it removes, COLMAP also
  distrusts, but it leaves 95% of the weak points in place.
- **The radius filter at this radius is close to indiscriminate** (1.3× chance) and removes over half the cloud.
  Its production value of 0.05 m cannot be judged without metric-scale data.
- It **does not** show that any method improves rendered quality, or anything about semantic-aware cleanup.

**Unavailable:**

| Measurement | Missing | Command |
|---|---|---|
| PSNR/SSIM per method at held-out views | CUDA GPU + torch/gsplat + the scene's training frames and poses | `python -m chaya_worker.benchmarks.cleanup_benchmark --ply <splat> --poses poses.json --sparse-model <sparse> --frames <frames> --sample-cameras 8 --out report.json` (on a GPU host) |
| Semantic-aware cleanup | `SEMANTIC_SEGMENTATION` labels for a splat (needs a full GPU pipeline run) | `python benchmarks/b2_geometry_cleanup/run.py --ply <splat> --labels <semantic-labels.json>` |
| Cleanup on a Chaya-reconstructed venue | Any Chaya reconstruction | the same run.py with the run's `SPLAT` artifact |

## 3. Benchmark 3: semantic search

**Change first.** Before this benchmark, **manually created POIs were never embedded**, so with the vision service
up, semantic search could not find them (docs/E2E_VALIDATION.md, F1). The schema already expected an asynchronous
embedder: the V5 comment, the immutability trigger's single allowed update, `poi_version_pending_embedding_idx`,
and the dashboard's "awaiting embedding" count. It did not exist. It now does:

- **`PoiEmbeddingService` + `PoiEmbeddingBackfill`** (services/api, `search` package). Every 5 s (**configuration**)
  the backfill embeds the latest version of each live POI that has no embedding.
- **The text embedded** is the label, category and tags, de-duplicated. That is the POI's own user-entered
  metadata, with no synonym table.
- **The model** is the same CLIP ViT-B/32 text tower as queries, and its id is recorded in `embedding_model`.
- **The write** is `UPDATE ... WHERE embedding IS NULL`: the one update the immutability trigger allows, and a
  no-op if another instance got there first.
- **When vision is down**, nothing is written and the backlog waits.
- **Tests:** 6 integration tests against real Postgres/pgvector. Full API suite: 277 tests, 0 failures.

**Question.** Does CLIP search, with that job, find POIs better than the system's non-semantic path (the pg_trgm
lexical fallback), and where does it fail?

**Run:** `python benchmarks/b3_semantic_search/run.py --env-file <stack env> --reps 3`. This runs through the real
`/search` endpoint of the E2E stack. The runner creates its own tenant, venue, floor and 30 POIs, waits for the
embedding job, then runs every query. It repeats the run with the vision container stopped (lexical fallback), and
finally runs an offline ablation of the embedded text using the same vision service.

**Dataset — assumption:**

- `benchmarks/b3_semantic_search/dataset.json`: 30 POIs of an imagined conference centre and **59 queries**
  (15 exact, 17 synonym, 12 ambiguous, 15 zero-result). SHA-256 `a12792c1…cec0e`.
- It is **author-constructed**, written before any result was seen. It is small, and its authors built the
  system. It is a regression and sanity benchmark, not evidence of real-world quality; real venue metadata and
  real user queries do not exist yet.
- **Rules:**
  - A *synonym* query shares no word with its answer's label, category or tags. The runner enforces this, and it
    caught one violation ("keynote hall" vs the tag "main hall") before any search was sent.
  - Top-1 and top-5 count any acceptable answer.
  - A *zero-result* query is answered correctly only by an empty list.

**Configuration:** top-k 10; the API's search rate limit of 60 per minute per user (requests paced below it);
backfill every 5 s.

**Results before the relevance filter and the circuit breaker** (measured, 2026-09-24,
`results/b3_semantic_search/2026-09-24T143316Z.json`; the changes and the rerun follow under
[Relevance filter and circuit breaker](#relevance-filter-and-circuit-breaker)):

| Query type (n) | CLIP semantic top-1 / top-5 | Lexical fallback top-1 / top-5 | Random ranking (reference) top-1 / top-5 |
|---|---|---|---|
| exact (15) | 100% / 100% | 100% / 100% | 3.3% / 16.7% |
| synonym (17) | **58.8% / 88.2%** | 5.9% / 5.9% | 4.5% / 21.5% |
| ambiguous (12) | 83.3% / 100% | 66.7% / 66.7% | 10.0% / 41.0% |
| all answerable (44) | **79.5% / 95.5%** | 54.5% / 54.5% | — |
| zero-result (15): correctly empty | **0%** | 80% | — |

| Latency | CLIP semantic | Lexical fallback (vision down) |
|---|---|---|
| server p50 / p95 (`search_query.latency_ms`, 236 requests) | 116 ms / 225 ms | 2 ms / 3,800 ms |
| client p50 / p95 (HTTP from the benchmark host, 177 requests) | 154 ms / 564 ms | 26 ms / 3,745 ms |

| Other measurements | Value |
|---|---|
| Time until 30 new POIs were all searchable by meaning | 7.9 s (backfill interval 5 s) |
| Top-1 similarity of correct answers: min / median | 0.804 / 0.919 |
| Top-1 similarity of zero-result queries: median / max | 0.836 / 0.868 |
| Offline ablation reproduces the API's top-5 | 59 / 59 queries |

**Ablation of the embedded text** (measured offline with the same model and cosine ranking):

| Embedded text | synonym top-1 / top-5 | ambiguous top-5 | all answerable top-1 / top-5 |
|---|---|---|---|
| label only | 52.9% / 82.4% | 91.7% | 77.3% / 90.9% |
| **label + category + tags** (what the job embeds) | **58.8% / 88.2%** | **100%** | **79.5% / 95.5%** |

**What it shows:**

- **CLIP semantic search is the contribution that matters for synonyms**: 10× the lexical top-1, and 88% of
  synonym queries have the answer in the top 5. For exact queries the two are equal.
- **Embedding the tags as well as the label helps** on every type. The composition was chosen before measuring,
  and the data supports it.
- **It never said "not here".** Every zero-result query returned 10 results. The correct answers' weakest raw
  similarity (0.804) is below the nonsense queries' strongest (0.868), so no single *raw* cut-off separates them.
  - "car rental" → Taxi stand (0.868);
  - "pharmacy" → Cafe;
  - "hotel rooms" → Meeting room 2.

  **Fixed** by a relevance filter relative to the venue (next section).
- **Hub answers.** A few POIs attract unrelated queries. "Lost and found" is the top result for 7 of the 15
  zero-result queries and 2 of the 7 missed synonyms ("loo", "evacuation route"). "Luggage storage" tops 3 missed
  synonyms ("jacket drop-off", "plug in my laptop", "cycle storage"). That is a known property of text embeddings,
  and a candidate for re-ranking or a per-POI similarity baseline.
- **The fallback had a latency cliff.** With vision down, about 1 search in 9 took ~3.7 s instead of ~2 ms. The
  slow ones recurred every ~10 s of wall clock, which matches the JVM's default 10-second cache for failed DNS
  lookups: the API re-resolved the missing `vision` host before falling back. **Fixed** by a circuit breaker (next
  section).

### Relevance filter and circuit breaker

**Relevance filter** (`SemanticSearchService`, docs/search.md step 4):

- A manual POI counts as a result only if its similarity exceeds the query's mean similarity to all searched POIs
  of the same source by a margin.
- Otherwise `results` is empty, and the 3 nearest candidates come back separately as `closestMatches`, shown in the
  viewer as "Nothing matching X here. Closest: ...".
- Detected objects, and scopes with fewer than 8 POIs, are not judged.

**Choosing the threshold without touching the test set:**

- A second, author-written **calibration venue** (a hospital floor: 30 POIs, 45 queries,
  `b3_semantic_search/calibration.json`) was written for this, and checked by the same synonym guard, which rejected
  4 of its first drafts.
- `calibrate.py` compared three scores on it, choosing each score's threshold by balanced accuracy (answerable
  queries still answered correctly, plus zero-result queries left empty).
- The winner on calibration was applied, frozen, to the test venue.

| Score | Threshold (from calibration) | Calibration: answered / empty | **Test (held out): answered / empty / synonym top-1** |
|---|---|---|---|
| none (before) | — | 73.3% / 0% | 79.5% / 0% / 58.8% |
| raw cosine | 0.875 | 63.3% / 100% | 61.4% / 100% / 17.6% |
| **margin over venue mean** (chosen: best on calibration) | **0.078** | **70.0% / 100%** | **70.5% / 86.7% / 47.1%** |
| z-score over venue | 2.18 | 66.7% / 100% | 72.7% / 66.7% / 47.1% |

(`results/b3_relevance_calibration/latest.json`; offline, same model and cosine as pgvector.)

**Circuit breaker** (`HttpTextEmbeddingClient`, docs/search.md step 5):

- The first failed call to vision opens it. Every later call fails immediately, so there is no DNS lookup.
- A probe on its own thread closes it once `/health/ready` answers 200.
- It is unit-tested against a real local HTTP server: an open breaker answers in < 50 ms without contacting the
  service, stays open while `/health/ready` says 503, and closes when ready.

**Rerun through the real API with both changes** (measured, `results/b3_semantic_search/2026-09-24T151915Z.json`):

| | Before | After |
|---|---|---|
| zero-result queries correctly empty | 0% (0/15) | **86.7% (13/15)**; still answered: "car rental" and "petrol station" → Taxi stand |
| answerable top-1 (results only) | 79.5% | 70.5% |
| synonym top-1 / top-5 | 58.8% / 88.2% | 47.1% / 52.9% |
| ambiguous top-1 / top-5 | 83.3% / 100% | 66.7% / 66.7% |
| exact top-1 | 100% | 100% |
| answerable queries now returning no results | — | 11 / 44. The right answer is the first closest match for 4 (breastfeeding, chapel, room, transport) and 2nd or 3rd for 3 (jacket drop-off, plug in my laptop, emergency). It is not offered at all for 4 (loo, evacuation route, heart attack, plenary session). |
| semantic server latency p50 / p95 | 116 / 225 ms | 123 / 367 ms (every in-scope POI is now scored, not only the index's top k) |
| **fallback server latency p50 / p95, vision down** | 2 / **3,800 ms** | 1 / **5 ms** |
| fallback client latency p95, vision down | 3,745 ms | 47 ms |

The live rerun reproduces the offline prediction exactly (70.5% / 86.7%).

**The trade-off is a product setting.** This is the margin on both venues, for the decision only. The threshold
stays at the calibration choice.

| margin | calibration: answered / empty | test: answered / empty / synonym top-1 |
|---|---|---|
| 0.050 | 73.3% / 33.3% | 79.5% / 13.3% / 58.8% |
| 0.060 | 73.3% / 73.3% | 79.5% / 66.7% / 58.8% |
| 0.070 | 70.0% / 86.7% | 75.0% / 80.0% / 47.1% |
| **0.078 (configured)** | 70.0% / 100% | 70.5% / 86.7% / 47.1% |
| 0.090 | 60.0% / 100% | 63.6% / 100% / 35.3% |

- **On venues of 30 POIs, the curve is steep and jumpy.** A lower margin (e.g. 0.06) keeps every answer on the test
  venue but empties only two thirds of nonsense queries.
- **Choosing it from these tables would be tuning on the test set.** Real pilot venues and real query logs should
  set `chaya.search.relevance-min-margin`.
- **What the filter does not fix:** synonyms whose answer is out-ranked by a hub POI ("loo" → Lost and found)
  still fail. They now return nothing rather than a wrong answer.

**Discarded run.** The first B3 run measured client latency through `localhost`, which on this Windows host tries
IPv6 first and stalls ~2 s per connection (measured: 2,058 ms via `localhost` vs 8 ms via `127.0.0.1`). Its
accuracy numbers were identical; its client latencies were an artifact of the benchmark host. It was deleted, and
the runner now uses `127.0.0.1`.

**Unavailable:**

| Measurement | Missing | Command |
|---|---|---|
| Accuracy on auto-detected POIs (CLIP image embeddings) and on text-to-text vs text-to-image ranking in one list | A `SEMANTIC_INDEXING` run (GPU) | rerun `benchmarks/b3_semantic_search/run.py` against a venue that has AUTO_DETECTED POIs |
| Accuracy on real venue metadata and real user queries | A real venue's POI list and a log of real queries with judged answers | replace `dataset.json` (same schema) and rerun |

## 4. Benchmark 4: navigation

**Question.** Does the accessible (STEP_FREE) route avoid stairs and narrow passages, and at what cost in distance?

**Metrics:**

- distance;
- floor transitions by type;
- stairs used: STAIRS transitions plus non-step-free edges;
- clearance violations: route edges with `min_clearance_m` below 0.9 m.

Route edges are recovered by matching the route's waypoints to the ACTIVE graph's edges, and read from
`navigation_edge`, the rows the router used.

**Run:**

- real venue: `python benchmarks/b4_navigation/run.py --env-file <env> --venue <id> --pairs pairs.json --user <user>`;
- self-test: `python benchmarks/b4_navigation/run.py --env-file <env> --synthetic`.

**Configuration:** minimum accessible clearance 0.9 m; walking speed 1.3 m/s standard, 1.0 m/s accessible;
transition times 20 s for stairs and 45 s for an elevator.

**Self-test — assumption: a hand-made graph, not a venue.** Two floors. Floor 0 has a 10 m corridor loop, a two-step
shortcut (not step-free) and a 0.7 m passage (step-free but too narrow). Stairs and an elevator connect to floor 1.
It was inserted through the same DRAFT → ACTIVE path the pipeline uses and routed by the real API.

| Pair | Standard | Accessible |
|---|---|---|
| A → Room E | 14.1 m, **1 stair** (the shortcut), 0 violations | 40.0 m, 0 stairs, 0 violations |
| B → Room E | 10.0 m, 0 stairs, **1 clearance violation** (0.7 m passage) | 30.0 m, 0 stairs, 0 violations |
| A → Cafe (level 1) | 22.1 m via **STAIRS** | 32.1 m via **ELEVATOR**, 0 stairs |

Durations are **estimates** (distance ÷ configured speed + configured transition time): 10.9 / 40.0 s, 7.7 / 30.0 s
and 37.0 / 77.1 s.

**What it shows:** the router applies its rules. The accessible profile avoided the stair shortcut, the narrow
passage and the staircase, at a cost of 10–26 m. It validates the benchmark harness and the rules. It says
**nothing** about real venues: the graph, and therefore every distance, is invented.

**Unavailable (real venue):** distance, transitions, stairs avoided and clearance violations.

- **Missing:** a venue with baked `STANDARD` and `STEP_FREE` graphs. `NAVIGATION_BAKING` needs a reconstruction
  (CUDA), plane fitting (Open3D) and chaya-navmesh (Recast/Detour).
- **Command:** the `--venue` form above, with origin-destination pairs sampled from the venue's POIs.

## 5. Benchmark 5: incremental rescan

**Question.** Is a regional rescan cheaper than a full reconstruction, and can its alignment be trusted?

**Run:**

```
docker run ... chaya-bench-open3d python benchmarks/b5_incremental_rescan/run.py \
  --sfm-points castle-sfm/points3D.txt --trials 8
```

### 5a. Alignment accuracy (measured: real geometry, controlled misalignment)

**Method.** The "existing reconstruction" is the real COLMAP castle cloud. Each trial simulates a rescan:

- crop a region (30% or 15% of the points);
- keep 80% of them (a different sampling);
- optionally add noise σ = h;
- apply a **known** rotation (2–90° about a random axis) plus a translation of 5% of D;
- ask the pipeline's own `align_region` (FPFH + RANSAC + point-to-plane ICP) to recover the transform.

Because the truth is known, the error is exact.

**Assumptions and configuration:**

- 8 seeds per condition;
- lengths relative to the cloud (**D** = bounding-box diagonal = 68.04 units; **h** = median nearest-neighbour
  spacing = 0.031 units);
- registration voxel 2h (production uses 0.05 m on metric splats);
- success = rotation error < 2° **and** translation error < 1% of D;
- production confidence gate 0.6 (`min_alignment_confidence`).

| Condition (region 30% / 15%) | Success | Median rotation error | Median translation error | Median confidence |
|---|---|---|---|---|
| no noise, 2°, 10°, 30° | 8/8 at every angle, both sizes | 0.016–0.036° | 0.003–0.006% D | 0.79 |
| no noise, 90° | 4/8 (30%), 3/8 (15%) | 41.6° / 74.0° | 2.5% / 5.0% D | 0.39 / 0.05 |
| noise 1h, any angle, voxel 2h | 0/8 at every angle, both sizes | 8.7–137.5° | 5.0–13.4% D | 0.00 |

**Voxel sensitivity** (30% region, noise 1h, 10° rotation), added after the noisy failures, to tell the algorithm
apart from the voxel choice:

| Voxel | Success | Median rotation error | Median confidence | Gate decisions correct |
|---|---|---|---|---|
| 2h | 0/8 | 11.7° | 0.00 | 8/8 (all failures rejected) |
| 4h | **8/8** | 0.13° | 0.535 | **0/8: every good alignment rejected** |
| 8h | **8/8** | 0.31° | 0.642 | 8/8 |
| 16h | 4/8 | 1.8° | 0.633 | 5/8. 3 near-misses (2.08–2.22° error) passed; the one gross failure (178°) was rejected |

**Confidence gate over the main grid** (128 trials): 55 successful alignments, 73 failed.

- The gate rejected **73/73 failed** alignments, and **0/55 successful** ones.
- A negative control (a mirrored crop that exists nowhere in the venue) was rejected **8/8**.

**What it shows:**

- **Alignment is accurate** when the rescan overlaps the venue and the voxel suits the noise: 0.016–0.036°
  noise-free, about 0.1–0.3° with noise at 4–8h. It fails for large rotations (90°), as FPFH + RANSAC is known to.
- **The gate stops wrong splices at the default setting.** No failed alignment passed it in the main grid.
- **The gate's calibration depends on the voxel.** The confidence formula, fitness × (1 − RMSE / voxel), moves with
  the voxel size. At 4h every accurate alignment was rejected; at 16h, near-misses passed. Production uses a fixed
  0.05 m voxel. **Whether that is calibrated to real splat noise is unmeasured**, and it is the first thing to
  check on real rescans.
- **Caveats:** open3d's multithreaded RANSAC is not bit-for-bit reproducible despite seeding. A repeat of the main
  grid gave 56 successes instead of 55. SfM points are much sparser than a splat.

### 5b. Scope (configuration, read from the code)

| | Full reconstruction | Regional rescan |
|---|---|---|
| Stages | 12 (`PipelineDefinition.STAGES`) | 13 if navigation is affected, 12 if not (`INCREMENTAL_STAGES`) |
| Only in this plan | `SEMANTIC_SEGMENTATION` | `REGION_ALIGNMENT`, `REGION_SPLICE` |
| Frames processed | the whole venue's capture | only the region's own capture |
| Navigation rebuild | always | only if an ACTIVE routing-graph node lies in the region; then the **whole floor** is re-baked (no per-tile bake) |
| Semantic re-index | whole venue | POIs whose latest position lies in the region polygon, plus new detections |

Note that **a regional rescan has no `SEMANTIC_SEGMENTATION` stage**, so its `GEOMETRIC_CLEANUP` runs without
semantic labels, even where the full run had them.

**Unavailable (real venue).** All of these are missing a full run and a regional run of the same venue, which
needs `SPLAT_RECONSTRUCTION` (CUDA):

| Measurement | Command |
|---|---|
| Processing time, full vs regional | `SELECT r.id, s.stage, extract(epoch FROM s.finished_at - s.started_at) FROM pipeline_stage_run s JOIN pipeline_run r ON r.id = s.run_id WHERE r.id IN ('<full>', '<regional>')` |
| Artifacts produced | `SELECT s.run_id, count(*) FROM processing_artifact a JOIN pipeline_stage_run s ON s.id = a.stage_run_id WHERE s.run_id IN ('<full>', '<regional>') GROUP BY 1` |
| Changed-area percentage | `scan_version.region_geometry` area ÷ floor area (`GET /venues/{v}/floors/{f}/scan-versions`) |
| Alignment error on a real re-capture | `scan_version.alignment_residual_m` (Open3D inlier RMSE); absolute error additionally needs surveyed control points |
| Navigation rebuild scope | `navigation_node` counts of the ACTIVE graph before and after |
| Semantic re-index scope | POIs soft-deleted by the regional run (`supersedePoisInRegion`) + AUTO_DETECTED rows it inserted |

## 6. Datasets that would turn "unavailable" into "measured"

| Dataset | Unlocks | Collection |
|---|---|---|
| **GPU host** with the reconstruction toolchain (DEPLOYMENT.md "GPU workers") | Everything below | — |
| Paired captures, A (lap only) vs B (lap + planned route), of ≥ 3 real venues, with surveyed floor plans | B1 real coverage, duration, redundancy | same operator, device and day; reconstruct both; register to the floor plan |
| The same venues' training frames and poses | B2 PSNR/SSIM; semantic-aware cleanup | produced by the pipeline runs above |
| Real POI lists and real search logs with judged answers | B3 on real queries; auto-detected vs manual ranking | from the first pilot venues |
| Baked navigation graphs of real multi-floor venues + origin-destination pairs | B4 on real venues | from the same pipeline runs |
| A real venue changed physically, then rescanned regionally, plus a full re-reconstruction as reference, with surveyed control points | B5 timings, scope, real alignment error, gate calibration of the 0.05 m voxel | one pilot venue |

## 7. Reproducing

- `benchmarks/README.md` has the commands and data sources.
- The two external datasets are downloaded at run time and never committed: the Sceaux castle photographs are the
  photographer's copyright, and the playroom splat comes from a Hugging Face dataset with its own terms.
- Framework self-tests: `python -m pytest benchmarks/tests` (7 passed).
- Hosts:
  - B1: Java 21 in `maven:3.9-eclipse-temurin-21`.
  - B2 and B5: `chaya-bench-open3d` (Python 3.12, open3d-cpu 0.19.0).
  - B3 and B4: the E2E stack on Windows 11 / Docker Desktop, 18 CPUs, no GPU.
