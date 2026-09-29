# Chaya 02: adversarial technical review (second pass)

**Date:** 2026-09-29
**Commit reviewed:** `8fa1769` (main, clean working tree)
**Stance:** hostile reviewer. Strengths are not listed. The earlier review ([ADVERSARIAL_REVIEW.md](ADVERSARIAL_REVIEW.md),
2026-09-25, `cf92005`) is used as a checklist; every one of its findings cited here was re-read in the current source.

**Method and evidence.** I read the implementation, not the plans. Three kinds of evidence are used, and each claim says
which:

- **source**: the file and line that shows it;
- **CI**: GitHub Actions results for `yawayapnwork/Chaya-02`, read with `gh run list` / `gh run view --log-failed` on
  2026-09-29;
- **local**: suites run in this session on the `8fa1769` tree: API `mvn verify` 364 tests, 0 failures, 4 skipped;
  web `npm test` 110 passed; Playwright `viewer.spec.ts` + `search.spec.ts` 8 passed; `ChayaARCore` `swift test`
  35 passed (Linux, Docker). These ran on this workstation, which still has the git-ignored fixture files (C-2).

Nothing was measured on a real venue in this review, and no number below comes from one. Where a number appears, it is
copied from a CI log or a committed result file, and the source is named.

---

## 0. Verdict

**Still reject as a digital-twin navigation product. The control plane remains the strongest part, but it is no longer
continuously verified.**

What changed since 2026-09-25 is real: a metric/gravity frame model, a real Recast/Detour baker, a `.ksplat` encoder that
the pinned viewer library loads, WebXR image tracking instead of hit tests, scan-version pinning. Four things undercut all
of it:

1. **Main has been red for five days.** Backend last passed on 2026-09-24, frontend and python on 2026-09-25,
   reconstruction-smoke on 2026-09-24. The security workflow has **never** passed (51 failures). The iOS app build has
   **never** passed. Deploy has **never** succeeded. Every "tests pass" claim made since then is a claim about one
   workstation (C-1).
2. **The evidence for two headline fixes is not in the repository.** The `.ksplat` fixtures that prove viewer
   compatibility (R-1) and the viewer-format validation (T-2) are matched by `.gitignore` (`*.ksplat`, `*.ply`). A clean
   checkout cannot run those tests; CI fails exactly there (C-2).
3. **No real capture has gone past `POSE_ESTIMATION`.** Training, cleanup, plane fitting and gravity, detection,
   navmesh from a reconstruction, the viewer on a real splat and every re-scan remain unexecuted on real data
   (E2E_VALIDATION.md §0: "NOT SUCCESSFUL"). The commit that added that driver is titled "test: validate real
   reconstruction to navigation pipeline" (`2256d9d`).
4. **Several newly "fixed" paths are wrong by construction, not just untested:** training discards lens distortion and
   learns privacy fills as scene colour (G-1, G-2); a re-scan's navigation re-bake drops every obstacle on the floor
   (N-1); step-free routing can never see a step (N-2); detected objects still land on whatever is behind them (CV-1).

---

## 1. Capability classification

Rules applied: an interface is not REAL; unit tests alone are not REAL if the real integration never executed; a path
proven only with files absent from the repository is not REAL.

| # | Capability | Classification | Basis |
|---|---|---|---|
| 1 | Canonical metric coordinate system | **PARTIALLY REAL** | `frame/CoordinateFrameService.java`: calibration from ≥2 measured distances or ≥3 control points, versioned, immutable, consumers refuse `NOT_CALIBRATED`. Exercised only with the synthetic fixture (`packages/contracts/fixtures/synthetic-calibration.json`). **No real capture has ever been calibrated** (E2E §0.4: no measured references exist). |
| 2 | Gravity alignment | **PARTIALLY REAL** | `chaya_worker/gravity.py` (floor plane + camera-up agreement) is sound as written; tested on synthetic planes. It consumes PLANE_FITTING output, which needs a splat: **never executed on real geometry**. No IMU source (the docstring says so). |
| 3 | Reconstruction-frame registration (re-scan) | **PARTIALLY REAL** | `similarity_registration.py`, `region_alignment.py`: similarity RANSAC/ICP, physical gates. B5 on a real SfM cloud with **simulated** misalignment. Never run on a real re-scan. |
| 4 | Gaussian training correctness | **PARTIALLY REAL** | Real gsplat loop with densification, time box, checkpoints (`splat_training.py`). **gsplat has never executed** (no CUDA anywhere). Wrong by construction: distortion discarded (G-1), privacy fills used as ground truth (G-2), no position-LR decay, SH degree 0 (G-3). |
| 5 | KSplat compatibility | **PARTIALLY REAL** | The encoder layout matches `@mkkellogg/gaussian-splats-3d` 0.4.7 per `lib/ksplat-compat.test.ts` and `e2e/ksplat-viewer.spec.ts` — **locally**. The fixtures they load are git-ignored; both fail on a clean checkout (C-2). Never exercised with a trained splat. |
| 6 | Real Recast integration | **REAL** (tool) | `native/chaya-navmesh`: upstream recastnavigation 1.6.0, `URL_HASH SHA256=`, real `rcBuild*`/`dtCreateNavMeshData`; built in the worker image and in `python.yml`. Proven on a committed fixture mesh. The *input* it is given is the problem (N-1, N-2). |
| 7 | Reconstruction → navigation E2E | **UNIMPLEMENTED (as executed)** | Every stage between POSE_ESTIMATION and NAVIGATION_BAKING has never run on real data; E2E §0 stops at SPLAT_RECONSTRUCTION. The API-side ingestion is REAL on the fixture navmesh. |
| 8 | Semantic object retrieval | **PARTIALLY REAL** | Search ranking is real and measured (B3, author-built data). Detection placement ignores occlusion (CV-1); Grounding DINO/CLIP detection has never run on a reconstruction; B3 uses COCO crops as stand-in detections. |
| 9 | Incremental re-scan | **PARTIALLY REAL** | Control plane and worker maths real, tested on synthetic clouds and B5. Never run on a real venue. Its navigation re-bake is defective (N-1). |
| 10 | Scan-version consistency | **PARTIALLY REAL** | V23 pins run, frame and artifacts; the API and viewer scope to a version (local tests). Gaps: pinning is by object key while storage content is mutable and unverified (S-2/R-8); full runs are not versions until bootstrapped and their graphs go live first; anchors carry a version tag but not a versioned pose (V-1 below). Its tests have never run in CI (C-1). |
| 11 | Android WebXR AR | **PARTIALLY REAL** | Real WebXR image-tracking session, route layer, relocalization states (`ArWorkspace.tsx`). Requires Chrome's `webxr-incubations` flag; **never run on a device**. |
| 12 | iOS ARKit | **INTERFACE ONLY** | `ChayaARCore` pure logic is REAL (35 tests, Linux). The ARKit/SceneKit layer and the app have **never compiled**: every `ios` CI run fails at `xcodebuild` (C-3). |
| 13 | Viewer artifact loading | **PARTIALLY REAL** | Real authenticated download → GaussianSplats3D, pixel-checked on a synthetic format fixture (locally; fixture git-ignored, C-2). No checksum check, no size cap (F-1). No real reconstruction ever viewed. |
| 14 | Accessible routing | **PARTIALLY REAL** | STEP_FREE fails closed on unmeasured slope/clearance (`RouteService#accessible`). But the navmesh input is one flat floor plane, so no step or ramp can ever exist in a baked graph; the profile is untestable on baked data (N-2). Clearance is portal length, not corridor width. |
| 15 | Multi-floor routing | **PARTIALLY REAL** | Staff-registered `floor_connection`s, no cross-floor coordinate comparison (`RouteService#usableHops`). Synthetic graphs only; no UI to register connections; not available on version-scoped routes. |
| 16 | Dynamic obstacles | **INTERFACE ONLY** | The API excludes nodes/edges inside request-supplied boxes. **No client ever sends `blockedRegions`** (grep: only the type in `lib/navigation-api.ts`); nothing detects an obstacle. |
| 17 | Security / tenant isolation | **PARTIALLY REAL** | API tenancy is REAL (`TenantGuard`, composite FKs, `TenantApiIsolationTest`). Storage tenancy is not: one worker credential may overwrite any tenant's published objects (S-2). Roles are global per token (S-8). A HIGH CVE ships in the API image (C-4). |
| 18 | Privacy processing | **PARTIALLY REAL** | Runs, fail-closed, but Haar cascades + heuristics; no person/body/plate detection; recall never measured; the "verification" re-runs the same detector (CV-6). PII staging survives failed runs (S-7). |
| 19 | Monitoring | **PARTIALLY REAL** | Prometheus rules exist (`infra/monitoring/prometheus/alerts.yml`: 10 alerts). No alert for PII purge failures, backups, stuck VALIDATING captures or lost worker heartbeats (O-3). Scrape targets are `host.docker.internal` (single-host only). |
| 20 | Backup / recovery | **PARTIALLY REAL** | `scripts/backup/*`: pg_dump + MinIO mirror to the same host; Keycloak users — the only store of tenancy — not backed up (`docs/backup-recovery.md:10`); no scheduled job, no restore drill in CI (O-1). |
| 21 | OpenAPI contract | **FAKE / MISLEADING** | `packages/contracts/openapi/v1.yaml` has 33 paths; the controllers declare 67 mappings. POIs, search, routes, anchors, relocalization, venues, reconstructions, scan versions, re-scan, public links and floor connections are absent. Nothing tests the document against the API (D-6). Presented as "the contract". |
| 22 | Organization management | **UNIMPLEMENTED** | No endpoint; organizations are inserted with SQL (DEPLOYMENT.md §3, E2E F3). |
| 23 | Data erasure | **UNIMPLEMENTED** | No erasure path for a venue, a capture or a person. Artifacts are write-once, `poi_version`/audit are append-only, search logs keep free text (E-1). |
| 24 | Redis usage | **UNIMPLEMENTED** | Provisioned in `infra/docker/docker-compose.yml` only; absent from `infra/deploy/docker-compose.yml`; no Java reference; no health check despite M2's scope. |
| 25 | CI/CD | **PARTIALLY REAL** (configured) / **NOT WORKING** (operated) | Eight workflows, well built. None of backend, frontend, python, security, smoke, iOS app or deploy is green on main (C-1). |
| 26 | Benchmark validity | **PARTIALLY REAL** | B2 and B5 use real geometry; B3 real system on author-built queries; B1 scores the planner with its own coverage model; B4 is a self-test. No benchmark touches a reconstruction produced by this pipeline (BENCHMARKS.md "limit that shapes everything"). |

---

## 2. Findings

Severity: **CRITICAL** blocks any claim of the capability; **HIGH** wrong results, data exposure or loss under realistic
conditions; **MEDIUM** edge-case errors or misleading output; **LOW** hygiene.

### 2.1 Verification and delivery (C)

#### C-1 · CRITICAL · Main has not been green since 2026-09-24; every later "Done" rests on one workstation
- **Files:** `.github/workflows/*.yml`; `services/api/src/test/java/dev/chaya/api/AbstractIntegrationTest.java:51`
- **Problem (CI):** last success per workflow — backend `18c9627` (2026-09-24), reconstruction-smoke `18c9627`
  (2026-09-24), frontend and python `eccb111` (2026-09-25). `security`: 51 failures, 0 successes. `ios`: 3 runs, 3
  failures. `deploy`: 13 failures, 4 skipped, 0 successes. Causes on the latest runs:
  - backend: `quay.io/minio/minio:latest` pull refused (`unauthorized`); 229 of 359 tests error out. The test image is
    unpinned while both compose files pin a digest.
  - reconstruction-smoke: the pinned MinIO image is refused by the registry the same way.
  - frontend and python: missing fixtures (C-2); python also `test_umeyama_recovers…` at `1.7e-06 < 1e-06` (C-5).
  - security: OWASP Dependency-Check crashes updating its own database (`Value too long for column "URL …"`).
  - docker: the API image fails its Trivy gate (C-4).
- **Why it matters:** the scan-version work, the navigation metric fixes and the semantic-ranking fix were all merged
  red. The deploy gate correctly refuses every commit, so nothing reviewed here has been deployable since 2026-09-24.
- **Remediation:** make CI the definition of done. Pin the Testcontainers MinIO image by digest and mirror it (and
  the compose MinIO) to GHCR; commit the fixtures (C-2); fix C-4 and C-5; pin Dependency-Check to a release whose DB
  schema accepts the current NVD feed (or cache the DB) and keep the CVSS gate. Protect `main` with required checks.
- **Required test:** all eight workflows green on a fresh push of `main`, then a deploy run that reaches `staging`.

#### C-2 · CRITICAL · The fixtures that prove `.ksplat` compatibility and viewer loading are git-ignored
- **Files:** `.gitignore:48` (`*.ksplat`), `.gitignore:50` (`*.ply`); missing
  `packages/contracts/fixtures/ksplat/scene.{ksplat,ply}`, `packages/contracts/fixtures/viewer-scene/scene.{ksplat,ply}`;
  consumers `apps/web/lib/ksplat-compat.test.ts`, `apps/web/e2e/viewer-fixture.ts`,
  `services/reconstruction/tests/unit/test_ksplat_fixture.py`, `test_viewer_scene_fixture.py`
- **Problem:** `git ls-files packages/contracts/fixtures` lists `generate_*.py`, `cloud.json`, `fixture.json` and
  `.gitattributes` but none of the four binaries. E2E_VALIDATION.md §V.1 calls `scene.ksplat` "committed" with a
  SHA-256. CI fails with `ENOENT … fixtures/ksplat/scene.ksplat` (frontend) and `FileNotFoundError …` (python, 5 tests).
- **Why it matters:** R-1 and T-2 are marked fixed on the strength of these tests. On any machine but the author's the
  evidence does not exist, and the generators are not run by CI to recreate it.
- **Remediation:** add `!packages/contracts/fixtures/**/*.ksplat` and `!…/*.ply` exceptions (the `.gitattributes`
  already mark them binary) and commit the files; or have CI regenerate them and compare against committed SHA-256s.
- **Required test:** frontend and python workflows green on a clean checkout; a CI step that fails if any file named in
  a fixture's `fixture.json` is untracked.

#### C-3 · HIGH · The iOS app has never compiled
- **Files:** `.github/workflows/ios.yml` (`app` job), `apps/ios-ar/App/project.yml`
- **Problem (CI):** every `app` run fails: `The project 'ChayaAR' cannot be opened because it is in a future Xcode
  project file format (77)`. XcodeGen 2.46.0 (unpinned `brew install`) writes a format the `macos-14` image's Xcode
  cannot read. Only the Linux `core` job passes.
- **Why it matters:** AR-2 was closed as "Addressed in code" with "the `ios` CI workflow is their first compile". That
  first compile has not happened: the ARKit session, SceneKit renderer and app are unverified Swift.
- **Remediation:** pin XcodeGen and set `options.xcodeVersion`/`projectFormat` in `project.yml` to the runner's Xcode,
  or move to `macos-15`.
- **Required test:** the `app` job builds for `generic/platform=iOS` and runs the Simulator tests green.

#### C-4 · HIGH · The shipped API image contains a fixable HIGH CVE
- **File:** `services/api/pom.xml` (jackson-databind via Spring Boot BOM)
- **Problem (CI, docker workflow, Trivy):** `jackson-databind 2.21.4`, `CVE-2026-68497`, HIGH, fixed in `2.21.6`.
- **Why it matters:** the image gate works and blocks publishing; the dependency has not been bumped.
- **Remediation:** override `jackson-bom` to 2.21.6 (or the Boot patch release that carries it).
- **Required test:** docker workflow `image (api, …)` green.

#### C-5 · LOW · A numerical test is tighter than the platform
- **File:** `services/reconstruction/tests/unit/test_similarity_registration.py` (`test_umeyama_recovers_scale_rotation_and_translation_exactly`)
- **Problem (CI):** `assert 1.7075472925031877e-06 < 1e-06` (rotation), `2.09e-06 < 1e-06` (translation) on the
  GitHub runner; passes locally.
- **Remediation:** a tolerance derived from the float64 conditioning of the fixture (e.g. `1e-5`), stated in the test.
- **Required test:** the same test on CI and locally.

### 2.2 Reconstruction and geometry (G)

#### G-1 · HIGH · Training discards lens distortion
- **Files:** `chaya_worker/stages/pose_estimation.py:34` (`--ImageReader.camera_model SIMPLE_RADIAL`),
  `chaya_worker/colmap_txt.py:23-25`, `chaya_worker/stages/splat_reconstruction.py:58-71`
- **Problem:** COLMAP is told to estimate a radial coefficient `k`; `parse_cameras_txt` drops it; `build_cameras` builds
  a pinhole `K`; the frames are never undistorted (`grep undistort` finds nothing). gsplat renders a pinhole image and the
  loss compares it with the distorted photo.
- **Why it matters:** systematic reprojection error growing towards the image edges: blur and floaters in the splat,
  and geometry the navmesh and object placement then inherit. Phone wide lenses have strong radial distortion.
- **Remediation:** run `colmap image_undistorter` after POSE_ESTIMATION and train on its PINHOLE output, or reject any
  camera model with non-zero distortion until then.
- **Required test:** a synthetic distorted-image fixture: projecting known 3D points through the training camera must
  land within 0.5 px of their distorted observations after undistortion.

#### G-2 · HIGH · Privacy fills are trained as scene content
- **Files:** `chaya_worker/stages/splat_reconstruction.py:156` (`FRAME_ARCHIVE_ANON`), `splat_training.py:393-452`
- **Problem:** training uses the anonymised frames as ground truth with no loss mask. Blurred and solid-filled regions
  become Gaussians. E2E_VALIDATION.md F5 recorded 55 false faces and solid fills on 10 of 11 frames of a building.
- **Why it matters:** façade/wall texture replaced by blocks in the viewer, and the same blocks feed detection crops and
  segmentation.
- **Remediation:** publish the anonymisation masks from PRIVACY_PREPROCESS (non-PII, boxes only) and exclude masked
  pixels from L1/D-SSIM.
- **Required test:** train (CPU test renderer) on two views of a known scene where one view has a solid fill; the fill's
  colour must not appear in renders from the other view.

#### G-3 · MEDIUM · Training departs from the reference recipe without saying so
- **File:** `chaya_worker/splat_training.py:116-121`, `settings.py:56-80`
- **Problem:** constant position learning rate (3DGS decays 1.6e-4 → 1.6e-6); SH degree 0 only; all frames held as
  float32 in RAM (R-6 unchanged: 11 frames at 1600×1202 are ~250 MB, a 1,000-frame walk is ~23 GB).
- **Remediation:** exponential position-LR schedule; configurable SH degree; lazy image loading.
- **Required test:** a GPU-marked convergence test on a public scene with PSNR recorded — which cannot run until a GPU
  worker exists.

#### G-4 · CRITICAL · Nothing after POSE_ESTIMATION has run on real data
- **Evidence:** docs/E2E_VALIDATION.md §0 (verdict NOT SUCCESSFUL, stops at SPLAT_RECONSTRUCTION); BENCHMARKS.md
  preamble.
- **Why it matters:** items 1–5, 7–9, 13–14 of §1 are unexecuted on real input; gsplat, gravity, cleanup,
  segmentation, detection, baking from a reconstruction and re-scan have no real-world run.
- **Remediation / required test:** E2E_VALIDATION.md §0.5 as written: an indoor capture with tape-measured distances,
  a CUDA worker, calibration, retry, a route. Until then no milestone that depends on it may be called Done.

### 2.3 Navigation (N)

#### N-1 · HIGH · A re-scan's navigation re-bake removes every wall and furniture obstacle on the floor
- **Files:** `pipeline/PipelineDefinition.java` (`INCREMENTAL_STAGES` has no `SEMANTIC_SEGMENTATION`);
  `chaya_worker/stages/navigation_baking.py` (`labels_inputs = ctx.inputs_of("SEMANTIC_LABELS_CLEAN")`, and the
  `len(labels) == len(cloud)` check)
- **Problem:** in a re-scan run there is no `SEMANTIC_LABELS_CLEAN`; even if there were, it would describe the region's
  cloud, not `SPLAT_MERGED`. The stage logs `obstacles_source: none` and bakes the **whole floor** from floor-plane
  occupancy alone, then that graph becomes ACTIVE on finalization.
- **Why it matters:** every re-scan that touches the graph silently replaces an obstacle-aware navmesh with one that
  only knows where floor points were seen. Anything standing on the floor that left floor points visible around it
  becomes walkable.
- **Remediation:** carry the parent's labels through the splice (splice the label array with the same indices as the
  Gaussians, `SPLICE_INDEX` already has them) and add SEMANTIC_SEGMENTATION for the region; or refuse to re-bake
  without labels.
- **Required test:** orchestration test: a re-scan of a floor with a furniture block outside the region; the re-baked
  navmesh must still exclude that block.

#### N-2 · HIGH · The navmesh can never contain a step, so STEP_FREE is untestable on real output
- **Files:** `chaya_worker/stages/navigation_baking.py` (`select_floor_plane`, only `floor.inlier_indices` are
  walkable), `chaya_worker/navmesh.py:170-245` (`geometry_from_reconstruction`)
- **Problem:** walkable geometry is the inliers of one near-horizontal RANSAC plane, quantised to flat quads. Stairs,
  ramps and raised areas are not inliers and are never walkable. The STEP_FREE graph therefore differs from STANDARD only
  by grid noise.
- **Why it matters:** the accessibility guarantee is vacuous on baked data: a route can never be refused for a step
  because steps are never there, and a level change inside a floor is simply a hole.
- **Remediation:** feed Recast the cleaned surface (all near-horizontal surfaces, or a meshed splat) and let its slope
  and climb filters decide walkability.
- **Required test:** bake a synthetic floor with one 0.18 m step and one 1:12 ramp; STANDARD crosses both, STEP_FREE
  crosses only the ramp.

#### N-3 · MEDIUM · Route waypoints are polygon centroids, not a path inside the navmesh
- **Files:** `navigation/RouteService.java` (class comment: "without its string-pulling"), `routeWithinFloor`;
  `ARCHITECTURE.md` §8 (says Detour runs client-side)
- **Problem:** the segment between the centroids of two adjacent convex polygons need not cross their shared portal;
  it can leave the walkable area. `chaya-navmesh` already implements a Detour path query that the API does not use.
- **Why it matters:** the AR route line and the reported distance can cut corners through walls.
- **Remediation:** route through portal midpoints and string-pull (Detour `findStraightPath`), or call the tool.
- **Required test:** an L-shaped corridor fixture: every route segment must lie inside the navmesh polygons.

#### N-4 · MEDIUM · Full-reconstruction graphs go live before any version exists (N-7 residual)
- **File:** `pipeline/PipelineService.java` (`ingestOneNavigationGraph(…, run.scanVersionId() == null)`)
- **Problem:** a full run's graphs are ACTIVE at ingestion; the run may still fail later, and it is a version only if
  someone calls `finalize-current`.
- **Remediation:** make every run a DRAFT version at start and activate graphs on finalization, as re-scans do.
- **Required test:** a full run that fails after NAVIGATION_BAKING leaves the previous ACTIVE graph in place.

#### N-5 · MEDIUM · Dynamic obstacles have no producer
- **Files:** `apps/web/lib/navigation-api.ts:55` (type only); no use in `ArWorkspace.tsx` or the iOS app
- **Remediation:** have AR clients report observed blockages (or remove the capability from scope).
- **Required test:** an AR-client unit test that turns a tracked occlusion into a `blockedRegions` entry.

### 2.4 Semantic retrieval (CV)

#### CV-1 · HIGH (unchanged) · Detection placement ignores occlusion
- **File:** `chaya_worker/stages/semantic_indexing.py:45-54`
- **Problem:** the position is the median of every splat centre that projects into the box, with no depth test
  (`project_points` only checks in-front and in-image).
- **Why it matters:** small objects in front of walls are placed on the wall; POIs, search results and routes to them
  are wrong.
- **Remediation:** render depth (or z-buffer the centres) and keep only points within a band of the nearest depth in
  the box; require multi-view consistency.
- **Required test:** synthetic scene with a small object 2 m in front of a wall; placement error < 0.2 m.

#### CV-6 · HIGH (unchanged) · Privacy detection is classical and self-verified
- **File:** `chaya_worker/privacy/detectors.py:66-135`, `stages/privacy.py:56-68`
- **Problem:** Haar cascades for faces, heuristics for screens; no people/bodies, no licence plates. The "verification"
  loop re-detects with the same cascades, so a missed face is never found. Recall on people was never measured.
- **Remediation:** a learned face/person detector with a measured recall on a labelled set; publish that number.
- **Required test:** recall ≥ a stated threshold on a labelled indoor set; a CI test on a small licensed subset.

### 2.5 Security, privacy and data (S, E)

#### S-2 · HIGH (unchanged, now also undermines scan versions) · Published objects are mutable and never re-verified
- **Files:** `infra/docker/minio/init.sh:37-41` (`chaya-worker`: `PutObject` on `$DER/*`, all tenants);
  `pipeline/PipelineService.java` `verifyStored` (size only); `reconstruction/ReconstructionService.java` (serves the
  worker-reported `checksum_sha256`)
- **Problem:** any worker credential can overwrite any tenant's published artifact after it is recorded; the control
  plane checks only size at report time (R-8), never the hash; the bucket has no versioning or object lock. V23's
  "exact artifacts" are exact by key, not by content.
- **Remediation:** per-run write credentials (STS/assume-role scoped to the run prefix) or server-side copy into a
  locked bucket at report time; verify SHA-256 server-side; enable bucket versioning/object lock on `chaya-derived`.
- **Required test:** overwrite a published KSPLAT with the worker account; the API must refuse to serve it (hash
  mismatch) or the write must be denied.

#### S-6 · MEDIUM (unchanged) · Privacy-disabled reconstructions reach anonymous links
- **Files:** `reconstruction/ReconstructionService.java` (no `privacy_enabled` filter), `publiclink/*`
- **Remediation:** refuse PUBLIC_VIEWER for runs with `privacy_enabled = false`.
- **Required test:** a public-link token gets 404 for such a run.

#### S-7 · HIGH (unchanged) · PII staging survives failed runs, silently
- **File:** `pipeline/PipelineService.java` (`purgePii` called only after a successful PRIVACY_PREPROCESS and on cancel)
- **Problem:** a run that fails before privacy, or is abandoned FAILED, keeps raw frames in `…/pii/`; purge failures are
  an audit row with no alert (`alerts.yml` has none).
- **Remediation:** purge on every terminal state and on a TTL sweep; alert on `pipeline.pii_purge_incomplete`.
- **Required test:** fail a run at FRAME_QUALITY_FILTER; its `pii/` objects are gone within the sweep interval.

#### S-8 · MEDIUM (unchanged) · Roles are global to the token, not per venue
- **File:** `security/JwtActorConverter.java` (roles from `realm_access`, venues from `venue_id`)
- **Remediation:** per-venue role claims or a membership table (ARCHITECTURE §7's own design).
- **Required test:** a user who manages venue A and views venue B cannot edit B's POIs.

#### E-1 · HIGH · No data erasure exists
- **Files:** none (absent); constraints that block it: `processing_artifact_guard`, `poi_version_guard`, audit
  append-only, `search_query` free text
- **Why it matters:** captures of indoor spaces contain people; there is no way to honour a deletion request or to
  offboard a tenant.
- **Remediation:** a documented erasure operation (venue, capture, subject) that deletes objects, tombstones rows and
  records the erasure in the audit log.
- **Required test:** erase a venue; no object under its prefix remains and its rows are unreadable through the API.

#### E-2 · MEDIUM · No organization management
- **Evidence:** no controller maps `/organizations`; DEPLOYMENT.md §3 inserts rows by SQL.
- **Remediation:** an admin-only organization API with audit.
- **Required test:** create/rename/disable an organization through the API; a disabled one gets 404 everywhere.

### 2.6 Scan versions (V)

#### V-1 · MEDIUM · Anchors carry a version tag but not a versioned pose
- **Files:** `ar/AnchorService.java` (`UPDATE ar_anchor …`, `scan_version_id = floor_current_scan_version(:f)`),
  `frame/CoordinateFrameService.java` `reprojectAnchors` (updates poses in place, keeps the tag)
- **Problem:** anchor rows are mutable; a recalibration moves the pose into a new frame while the tag still names a
  version pinned to the old frame. There is no anchor history per version, and AR clients never ask for a version.
- **Remediation:** version anchors like POIs (immutable pose rows with frame and version) or drop the per-version claim
  for anchors.
- **Required test:** recalibrate a versioned reconstruction; the anchor as of that version keeps its original pose.

#### V-2 · MEDIUM · No "current version" and version-less full runs
- **Files:** `reconstruction/ReconstructionService.java#latestForFloor`, `rescan/RescanService.java#finalizeCurrent`
- **Problem:** "latest" is the newest listed run, versioned or not; nothing records which version is live.
- **Remediation:** `floor.current_scan_version_id`, set only on finalization; default reads use it.
- **Required test:** a newer unversioned full run does not replace the current version for viewers.

### 2.7 Operations (O)

#### O-1 · HIGH (unchanged) · Backups are same-host and omit the tenancy store
- **Files:** `scripts/backup/backup.sh`, `docs/backup-recovery.md:10`
- **Problem:** dumps and the MinIO mirror land in `$BACKUP_DIR` on the same host; Keycloak users (which hold `org_id`
  and `venue_id`, the whole tenancy model) are not backed up; no schedule; no restore drill in CI.
- **Remediation:** off-host encrypted copy; Keycloak DB in the backup set; a CI job that restores into a fresh stack
  and runs the smoke checks.
- **Required test:** that restore job, green.

#### O-3 · MEDIUM (unchanged) · Missing alerts
- **File:** `infra/monitoring/prometheus/alerts.yml`
- **Problem:** no alerts for PII purge failures, backup age, captures stuck in VALIDATING, worker heartbeat loss per
  stage.
- **Required test:** rule unit tests (`promtool test rules`) for each.

#### O-5 · MEDIUM · Redis is claimed but absent
- **Files:** `PROJECT_PLAN.md` M2 ("Redis/MinIO health"), `ARCHITECTURE.md` §1/§10, `infra/docker/docker-compose.yml:41`
- **Problem:** Redis runs only in the dev compose, is not in production compose, is used by no code and is not
  health-checked.
- **Remediation:** remove it from scope and diagrams until a use exists (the rate limiter is in-process), or implement
  the shared limiter it was provisioned for.
- **Required test:** —

### 2.8 Viewer (F)

#### F-1 · MEDIUM (unchanged) · Viewer downloads without checksum or size cap
- **File:** `apps/web/lib/reconstruction-api.ts` (`fetchArtifact`)
- **Remediation:** stream-hash with the returned `sha256`, refuse on mismatch; cap by the advertised `sizeBytes`.
- **Required test:** a Playwright case where the served bytes differ from the advertised hash shows an integrity error.

### 2.9 Documents and contract (D)

#### D-6 · MEDIUM (unchanged) · The OpenAPI document is not the contract
- **File:** `packages/contracts/openapi/v1.yaml` (33 paths vs 67 controller mappings)
- **Remediation:** generate it from the controllers (springdoc) and diff in CI, or add the missing paths and a contract
  test that fails on drift.
- **Required test:** CI fails when a controller mapping is missing from the document.

#### D-7 · MEDIUM · Architecture and commit history overstate what exists
- **Files:** `ARCHITECTURE.md` §8 (client-side Detour/WASM routing; the API routes), §5 and §7 (`venue_membership`;
  a "superseded in part" note exists, but §5's table and §7's body still describe it), §10 (`CLAIMED`/`RETRY_WAIT` states that do not exist),
  §1 (Redis as a backend store); `chaya_worker/stages/navigation_baking.py` docstring (floor transitions "from
  stairs/elevator POIs", replaced by `floor_connection`); commit `2256d9d` "validate real reconstruction to navigation
  pipeline" for a run whose documented verdict is NOT SUCCESSFUL.
- **Remediation:** correct the sections; never title a commit "validate" when the validation failed.

#### B-1 · MEDIUM · Benchmark conclusions are narrower than their summaries read
- **File:** `docs/BENCHMARKS.md` summary table
- **Problem:** B1 measures the planner against its own coverage model; B4 is a self-test; B3's queries, venue and
  calibration venue are author-built; no benchmark uses an output of this pipeline. Result files are dated 09-24 to
  09-28 against older commits and are not re-run by CI.
- **Remediation:** state per row that it is not a product measurement; add a CI job that re-runs the synthetic
  benchmarks and fails on regression.

---

## 3. Status of the 2026-09-25 findings

| Finding | Now |
|---|---|
| R-1 `.ksplat` layout | Fixed in code; evidence not reproducible from the repository (C-2). |
| R-2 metric/gravity | Implemented; never run on real data (G-4). |
| R-3, R-4, R-5 | Addressed in code; gsplat never executed. |
| R-6 frames in RAM | Open (G-3). |
| R-8 checksums not verified | Open (S-2). |
| CV-1 occlusion | Open. |
| CV-4 mixed ranking | Fixed, measured on author-built data. |
| CV-6 privacy detector | Open. |
| N-1 fictional recast-cli | Fixed (real Recast). |
| N-2 convex hull | Replaced by occupancy grid; single-plane input remains (N-2 here). |
| N-3/N-4 accessibility | Fail-closed filter real; untestable on baked data (N-2 here). |
| N-5 multi-floor frames | Fixed. |
| N-6 blocked edges | Fixed in the API; no producer (N-5 here). |
| N-7 graph promotion | Fixed for re-scans; open for full runs (N-4 here). |
| N-8 routing location | Open (D-7). |
| AR-1 web hit test | Replaced by image tracking; not device-validated; needs a Chrome flag. |
| AR-2 iOS | Code written; never compiled (C-3). |
| AR-3 `physicalPose` unused | Open (`AnchorService` stores it, nothing reads it). |
| V-4, V-5 | Fixed in code and local tests (V23); V-1, V-2 here remain. |
| S-1 single worker credential | Open (same design; one service account claims every tenant's jobs). |
| S-2, S-6, S-7, S-8 | Open. |
| O-1, O-3 | Open. |
| D-6 OpenAPI | Open. |

---

## 4. PROJECT_PLAN.md

No milestone's status was changed. Checked against each row's own "Verified by" column:

- **None newly satisfies its acceptance criteria.** Every automated criterion that says "tests pass" is currently red in
  CI (C-1); M18's criterion is literally "green pipeline".
- **Rows that claim more than their criteria support** (left unchanged, as this task only permits status changes
  where criteria are now met; flagged for the owner):
  - **M8** "Done: GLOMAP with COLMAP fallback" — GLOMAP is not in any image that has run ("no GLOMAP", E2E §0.2) and
    the "real-dataset gpu-marked tests" skip everywhere.
  - **M10** "Done" — its Playwright evidence depends on git-ignored fixtures (C-2); frontend CI red.
  - **M2** scope "Redis … health" — not implemented (O-5).
  - **M3** scope "membership tables" — replaced by token attributes (docs/security.md); criterion (cross-tenant 404)
    passes locally only.
  - **M11**, **M12** "Done" — criteria met by unit/integration tests locally, but the stages never ran on a
    reconstruction; M12's detection placement is defective (CV-1).

---

## 5. Not checked

- Anything requiring a GPU, a real indoor capture with measurements, an Android device with the WebXR flag, or a Mac.
- The full-stack E2E harness (`scripts/e2e/e2e_validate.py`) was not re-run for this review.
- Load, soak and multi-instance behaviour.
