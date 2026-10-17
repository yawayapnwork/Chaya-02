# Final evidence-based audit against *Chaya_02_Winning_Solution.docx* (2026-10-17)

**Question:** does the repository deliver what the solution document promises?
**Code audited:** `main` at `f9f733b`. **No feature was implemented during this audit.**

## How to read this

Each requirement is classified by **execution evidence**, never by the existence of files or tests:

| Class | Meaning here |
|---|---|
| **IMPLEMENTED AND VERIFIED** | Ran with real collaborators and real inputs where the requirement needs them, and passed. Nothing in the requirement needs a venue or a device that was not available. |
| **IMPLEMENTED BUT NOT END-TO-END VERIFIED** | The code does the work and automated tests pass, but the requirement's real input (a venue capture, a GPU, a phone, a deploy host) has never been used. |
| **PARTIALLY IMPLEMENTED** | Part of what the document promises does not exist. What exists is described. |
| **NOT IMPLEMENTED** | Nothing in the repository does it. |
| **BLOCKED BY EXTERNAL PREREQUISITE** | Implemented code cannot run at all without something this project does not have (named). |

Evidence tags: **[today]** run during this audit; **[session]** run earlier on 2026-10-17 (recorded in DEPLOYMENT.md §11 and
docs/ACCEPTANCE.md); **[doc]** a dated record in the repository, not re-run. Synthetic data is always called synthetic.

### Executed today

| What | Result |
|---|---|
| GitHub Actions, latest run per workflow (`gh run list`) | `acceptance` **success** (d8d9333), `reconstruction-smoke` success, `backend` success, `ops` success. **Failing:** `docker` (2 CRITICAL CVEs in spring-webmvc 6.2.19), `frontend` (unit test `processing-live.test.ts:161` "Maximum call stack size exceeded" on the runner; passes 5/5 locally), `python` ("tests skipped in the CPU suite"), `security` (OWASP Dependency-Check cannot update NVD data), `ios` (`ReferenceImageTests` XCTUnwrap). `deploy`: **skipped on every run**; it has never succeeded. |
| B1 capture-path benchmark re-run (`python benchmarks/b1_capture_path/run.py`) | 662 of 669 numeric metrics **identical** to the committed 2026-09-24 result (commit 998e1bc); the 7 that differ are wall-clock "planning time" (36.5 → 48.2 ms etc.), expected to vary. Committed result files left unchanged. |
| Benchmark framework tests (`pytest benchmarks/tests`) | 10 passed |

### Executed earlier today (same codebase, before the UI commit)

Cross-service acceptance suite (`scripts/acceptance/run-acceptance.sh ci`): **40/40** plus browser **21/21** locally, and green in
GitHub Actions; web unit tests 330 and Playwright 64/64; worker CPU suites (unit 350, orchestration 67 + 1 allowed skip, smoke 14,
splat 91, navmesh 46); production-like `deploy.sh` run with automatic rollback, outages and restore drill; `test-deploy.sh`
50/50. All on synthetic scenes or real public data; none on a venue, a GPU or a phone.

---

## 1. Requirements traceability matrix

Abbreviations: **AC** = cross-service acceptance suite (docs/ACCEPTANCE.md, class C, synthetic scene, real services);
**IT** = Java integration tests (Testcontainers Postgres/MinIO); **API** paths are under `/api/v1`.

| # | Requirement (document §) | Implementing source | API contract | Data / storage | Automated tests | Works with real inputs? | Venue / device validated? | Missing or incorrect vs. the document | Classification | Smallest corrective task |
|---|---|---|---|---|---|---|---|---|---|---|
| 1 | **Automatic capture planning**: 10 s recon lap → room bounds → optimal path, AR breadcrumbs (§3, §7) | `services/api/.../planning`, `.../hud`; `apps/web/components/CaptureHud.tsx`, `lib/capture-guidance.ts`, `lib/capture-minimap.ts` | `captures/{c}/hud/reconnaissance`, `hud/plan`, `hud/scene/estimate`, `route-plan` | `capture_*`/HUD tables (Postgres) | `CapturePlanApiTest`, `CapturePlanningPipelineTest`, `RoomBoundsEstimatorTest`; web unit; B1 | Synthetic floor plans only (B1, **reproduced [today]**). Beats baselines at equal distance on 7/7 scenes; **not** at equal waypoints on the largest. | **No.** No WebXR capture session has run on an ARCore phone [doc PROJECT_PLAN M14]. | "optimal" is not established (heuristic planner, own coverage model). | IMPLEMENTED BUT NOT END-TO-END VERIFIED | One guided capture of one room on an ARCore phone; record predicted vs observed coverage. |
| 2 | **Live capture quality & coverage**: "real-time sparse point cloud with a 3D coverage heatmap", per-frame blur scoring, on-device, "no server round-trip" (§3, §4) | `lib/capture-quality.ts` (Laplacian blur, FAST-corner texture), `lib/capture-tracking.ts`, `components/CaptureHud.tsx`; API `hud` package | `hud/pose`, `hud/quality`, `hud/status`, `hud/stream` (SSE) | HUD evidence tables | `CaptureHudApiTest`; web unit (`capture-quality`, `capture-guidance`) | Blur/texture scoring on real images (unit). Coverage is a **2D grid** computed by the API from poses. | **No** device session. | **No sparse point cloud, no 3D heatmap**; coverage needs the server (SSE), contrary to "no server round-trip". | PARTIALLY IMPLEMENTED | Correct the claim to "2D coverage grid + blur/texture scoring", or add WebXR depth-based points (feature). |
| 3 | **Multi-room stitching**: scan-per-room with automatic loop closure via shared doorway/hallway features (§3) | — (only the re-scan splice merges clouds) | — | — | — | — | — | No cross-capture registration exists; a floor is one capture/run. `grep` for loop closure / doorway features / room stitching: none. | **NOT IMPLEMENTED** | Register a second capture of a floor onto the floor's cloud with the existing REGION_ALIGNMENT feature mode, or drop the claim. |
| 4 | **Multi-floor reconstruction**: Z-axis floor segmentation; floor-transition graph with stairs/elevators as edges (§3) | `api/.../venue` (FloorService, floor connections), `navigation`; iOS `ChayaARCore/FloorHandoff.swift`; web `lib/floor-transitions.ts` | `floors`, `floor-connections`, `floor-connections/graph`, `floors/consistency`, `navigation/routes` | `floor`, `floor_connection`, elevation (V39) | IT; AC 6.9–6.12 (route via registered stairs; STEP_FREE refuses until the elevator is verified, then takes it) | Transition graph and multi-floor routing: yes, on real Recast bakes of synthetic floors [session, AC]. | No. | **No Z-axis segmentation**: each floor is captured and reconstructed separately; levels, elevations and connections are entered by staff (docs/navigation.md "Elevation is never measured"). | PARTIALLY IMPLEMENTED | State that floors are separate captures with staff-registered connections (doc fix). |
| 5 | **Gaussian Splatting** (gsplat, iteration budget, background-refine fallback, checkpointing) (§3, §4, §6.3, §7) | `chaya_worker/stages/splat_reconstruction.py`, `chaya_worker/splat*`; `Dockerfile.gpu` | worker control plane `internal/jobs/*` | MinIO derived bucket | CPU training mechanics with a **test renderer** (`tests/splat`, 91 passed); `tests/gpu` **never run** | **Never executed.** gsplat has never trained; no venue has been reconstructed; `Dockerfile.gpu` never built [doc REAL_VENUE_ACCEPTANCE, E2E_VALIDATION]. | No (no CUDA). | Background refinement after a time-budget overrun is **not implemented** (PARTIAL is kept, never published or refined). | **BLOCKED BY EXTERNAL PREREQUISITE** (CUDA GPU host) | Build `Dockerfile.gpu` on a CUDA host; run `python -m chaya_worker.splat_preflight` and `CHAYA_REQUIRE_GPU=1 pytest -m gpu`. |
| 6 | **Geometry cleanup** + floater ablation "reported as a PSNR/SSIM table" (§3, §4) | `stages/geometric_cleanup.py`, `stages/plane_fitting.py` (Open3D) | worker | MinIO | unit (Open3D stubbed); AC 3.x ran the **real Open3D worker** on a synthetic dense scene [session]; B2 | Real trained splat (playroom) and real SfM cloud, **proxy metric** (outlier targeting 6.6× chance) [doc B2]. | No real Chaya splat. | **No PSNR/SSIM ablation table exists** ("rendering quality: unavailable", BENCHMARKS.md). "Fine-tuned" segmentation driving it: stock model. | IMPLEMENTED BUT NOT END-TO-END VERIFIED | After #5: render held-out views with each cleanup variant and produce the PSNR/SSIM table. |
| 7 | **Semantic segmentation**, fine-tuned SegFormer/Mask2Former (§3, §4) | `stages/semantic_segmentation.py`; fine-tuning harness (docs/ML_EVALUATION.md) | worker | MinIO (`SEMANTIC_LABELS`) | harness smoke; B6 baseline | Stock SegFormer on 102 held-out COCO indoor images: mIoU **0.548** [doc B6]. In AC this stage is a labelled **stand-in**. | No. | **No fine-tuned model** (blocked: no licensed indoor masks, no GPU, research-only base licence). Default weights are **"research or evaluation" only**. | PARTIALLY IMPLEMENTED (fine-tuning blocked) | Choose a commercially licensed base checkpoint; label a first indoor set from real captures. |
| 8 | **Object detection**, Grounding DINO fine-tuned on ~200–300 labelled renders (§3) | `stages/semantic_indexing.py` | worker; `pois`, `search` | `poi` (pgvector) | B6; AC 5.x (indexing is a **stand-in**) | Stock Grounding DINO on COCO: mAP@0.5 **0.500** over 4 classes, "table" 0.07 [doc B6]. | No. | **No fine-tuned detector, no labelled render set.** | PARTIALLY IMPLEMENTED | Label the first 200 frames from real captures (after #5). |
| 9 | **CLIP + vector search**, "FAISS", synonyms/fuzzy, voice (§3, §4, §5.2) | `api/.../search` (`SemanticSearchService`, `QueryInterpreter`, `PoiEmbeddingBackfill`); `services/vision`; `components/SemanticSearchPanel.tsx` | `venues/{v}/search`, `pois` | `poi` + pgvector HNSW | `SemanticSearch*Test`, `SearchBenchmarkTest`; AC 5.1–5.7a with the **real vision service** [session]; vision tests | Yes: real CLIP, real API, real pgvector; B3 synonyms top-1 58.8% vs 5.9% lexical [doc]; detected-object path with real COCO crops. | Not needed for the mechanism; venue relevance unmeasured. | Vector store is **pgvector, not FAISS** (§3/§4 contradict §5.2). Voice: Web Speech API, only where the browser supports it. | IMPLEMENTED AND VERIFIED (mechanism; relevance on a venue unmeasured) | Replace "FAISS" with "pgvector (HNSW)" in the document. |
| 10 | **Accessible navigation**: Recast NavMesh, wheelchair toggle, multi-destination chaining; Recast-Detour WASM (§3, §4) | `chaya_worker` NAVIGATION_BAKING + `native/chaya-navmesh` (recastnavigation 1.6.0); `api/.../navigation`; `lib/multi-stop.ts` | `navigation/routes`, `navigation/routes/multi-stop` | NAVMESH artifacts (MinIO), graphs (Postgres) | navmesh 46 real-tool tests; `RouteServiceTest`, `RoutePlanApiTest`; AC 6.1–6.12 on **this run's real bake** [session] | Real Recast on synthetic venues; B4 synthetic self-test. | No real venue navmesh. | Routing is **server-side**; there is no Recast-Detour WASM client. | IMPLEMENTED BUT NOT END-TO-END VERIFIED | Route on the navmesh of the first real venue (after #5). |
| 11 | **Dynamic obstacle re-routing** "when the AR camera detects a person or moved object" (§3) | API `blockedRegions` exclusion; `lib/obstacle-reroute.ts` decision logic | `navigation/routes` (`blockedRegions`) | — | AC 6.8 (route refused around a reported obstacle) [session]; web unit | Server side: yes. | No. | **No obstacle detector produces observations**; "the client sends no blockedRegions, so there is no obstacle-triggered rerouting" (docs/ar.md). | PARTIALLY IMPLEMENTED | Feed WebXR depth/hit-test or a person detector into `blockedRegions`, or narrow the claim to "re-plans around reported obstacles". |
| 12 | **Android WebXR AR** (§3, §4) | `components/ArWorkspace.tsx`, `lib/ar-*`, `lib/webxr-support.ts` | `floors/{f}/anchors`, `anchors/relocalize`, `navigation/routes` | anchors, target images | web unit (`ar-*`); Playwright unsupported-state checks | No. | **Never run on a phone** (docs/ar-android-validation.md: NOT RUN). | AprilTag/ArUco/QR are **not detectable** in WebXR; only image targets. Android requires a Keycloak sign-in (the document promises no login on site). | IMPLEMENTED BUT NOT END-TO-END VERIFIED | Run docs/ar-android-validation.md on one ARCore phone. |
| 13 | **iOS ARKit app** (§3, §4) | `apps/ios-ar` (`ChayaARCore`, App) | same path-data API; viewer-link exchange | — | `ChayaARCore` 72 tests (Linux Swift) [doc]; App XCTest **red in CI** [today] | No. | **Never run on a device** (docs/ar-ios-validation.md: NOT RUN). | CI red since `fb7e65f` (`ReferenceImageTests`). No AprilTag detection in ARKit (image targets only). | IMPLEMENTED BUT NOT END-TO-END VERIFIED | Fix the stale XCTest fixture; then run docs/ar-ios-validation.md on an iPhone. |
| 14 | **Multi-anchor relocalization** "AprilTag", VIO interpolation, floor handoff (§3, §4) | `lib/ar-relocalization.ts`, `lib/ar-anchor-math.ts`; iOS `RelocalizationStateMachine.swift`, `FloorHandoff.swift`; API `ar` | `anchors/relocalize`, `anchors/{a}/calibrate`, `target.png` | anchors with versioned poses | web and Swift unit; API IT | Math on synthetic poses only. | **No.** | Markers are printed **image targets**, not AprilTags. Drift between fixes has never been measured. | IMPLEMENTED BUT NOT END-TO-END VERIFIED | Measure route drift at three checkpoints with two markers (device checklist §6). |
| 15 | **Incremental re-scan**: region capture, ICP/feature alignment, splice changed Gaussians, update navmesh/index (§3, §7) | `api/.../rescan`; `stages/region_alignment.py`, `region_splice.py`, re-bake; web `components/RescanStart.tsx` | `floors/{f}/rescan`, `scan-versions/*` | `scan_version`, pinned artifacts | AC 7.1–7.10 (real Open3D alignment, real splice, real re-bake identical outside the region) [session, CI green]; B5 real geometry | Synthetic scene; B5 on a real SfM cloud with simulated misalignment [doc]. | No real venue re-scan. | DIRECT-mode alignment **falsely rejects** a region dominated by a moved object (fails safe; docs/ACCEPTANCE.md P1). The region capture itself needs #5. | IMPLEMENTED BUT NOT END-TO-END VERIFIED | Design a robust DIRECT-mode refinement and validate it on the B5 benchmark before changing the gates. |
| 16 | **Version history** (§3, §7) | `api/.../rescan/ScanVersionService`; viewer version selector | `floors/{f}/scan-versions`, `scan-versions/{id}/artifacts/{kind}` | `scan_version` (V28+), `floor.current_scan_version_id` | IT; AC 3.21–3.22, 7.6–7.10; browser C1–C2 (real viewer switches and receives version 1's exact bytes) [session] | Yes (data feature, real services). | Not needed. | — | **IMPLEMENTED AND VERIFIED** | — |
| 17 | **Operations dashboard**: scan freshness, coverage gaps, job status **and cost**, POI search analytics (§3) | `api/.../ops`; `components/OpsDashboard.tsx` | `ops/*` (overview, coverage, jobs, failures, storage, search-analytics, rescans, audit) | Postgres | `OpsDashboardApiTest`, `JobRecoveryTest`; AC 8.1–8.11 [session]; Playwright `ops.spec.ts` | Yes, on real rows. | n/a | **No cost data**: "The system has no cost source" (docs/operations-dashboard.md); shown as unavailable. | PARTIALLY IMPLEMENTED (everything except cost verified) | Record a GPU-hour rate and stage durations per run; compute cost per run. |
| 18 | **Authentication** (Keycloak OIDC, Spring resource server, short-lived JWT + rotated refresh, RBAC, viewer links, service accounts, audit) (§5.1) | `api/.../security`, `publiclink`, `audit`; `infra/keycloak/chaya-realm.json`; `apps/web/lib/auth.ts` | all; `public/viewer-token`, `audit-log` | Keycloak DB; `audit_log` (V7) | `AuthenticationTest`, `KeycloakLiveTest` (opt-in); AC 1.1–1.14 (real Keycloak, real expiry) [session]; production-mode Keycloak with PKCE browser login and deploy service account [session] | Yes. | n/a | Access tokens 5 min (stricter than ~15). Viewer links are **opaque server-checked tokens**, not client-credentials JWTs (equivalent intent). Keycloak events are **not** written to `audit_log`. Single realm (no realm-per-tenant). | **IMPLEMENTED AND VERIFIED** (deviations noted) | Forward Keycloak admin/login events into `audit_log`, or state that they stay in Keycloak. |
| 19 | **Tenant isolation** (venue_id/org_id claims + service-layer checks) (§5.1) | `api/.../security` (tenancy), every service | all venue-scoped paths | Postgres row checks; MinIO tenant prefixes | `TenantApiIsolationTest`, `CrossTenantResourceIsolationTest`; AC 1.15–1.18, 3.8, 4.5, 5.6, 8.9 [session]; deploy check (another org's venue, capture and media 404) [session] | Yes. | n/a | — | **IMPLEMENTED AND VERIFIED** | — |
| 20 | **Upload security**: Tika content check, size/rate limits, ClamAV (§6.5) | `api/.../capture` (ingestion), `storage`, `security` (rate limits) | `captures/{c}/media/*` | MinIO raw bucket, quarantine | `CaptureIngestionTest`, `ClamAvLiveTest` (opt-in); AC 2.7–2.9 (checksum, content sniffing, **real ClamAV EICAR**) [session]; ClamAV outage → QUARANTINED [session] | Yes. | Never uploaded from a phone [doc]. | Rate limiting is **in-process**, not Spring Cloud Gateway (deviation, not a gap). | **IMPLEMENTED AND VERIFIED** | — |
| 21 | **Privacy**: RetinaFace/MediaPipe face blur, fine-tuned YOLOv8-nano screen/document blur, data policy (§6.1) | `chaya_worker/privacy/detectors.py`, `stages/privacy.py`; PII staging purge; erasure (`api/.../erasure`) | `erasures` | PII staging, erasure records | worker unit; stack smoke (PII purge checked); `ErasureTest` | Real outdoor photos: **55 false "faces" on a building** (E2E §7 F5, open) [doc]. | No people-in-venue test. | Faces: **OpenCV Haar cascades**, not RetinaFace/MediaPipe. Screens/documents: a **heuristic quadrilateral** detector, not a fine-tuned YOLOv8. Recall on real faces never measured. Policy: docs/privacy-erasure.md exists. | PARTIALLY IMPLEMENTED | Measure face recall/precision on a labelled set of indoor frames with people; replace the detector if recall is low. |
| 22 | **Backup & recovery**: pg_dump, mc mirror, off-machine copy (§6.6) | `scripts/backup/*` | — | dumps + object mirror in `BACKUP_DIR` | `test-restore-drill.sh`, `test-backup-ownership.sh`; stack smoke ends with backup + restore drill (CI green) [today]; pre-deploy dump + rehearsal on the production-like stack [session] | Yes, on test-stack data. | Never on a real host. | **No off-machine copy** (B2/USB) is implemented; Keycloak's database is dumped only by a documented manual command. | IMPLEMENTED BUT NOT END-TO-END VERIFIED | Add an off-site sync step and include the Keycloak dump in `backup.sh`. |
| 23 | **CI/CD**: build/test, GHCR images, pipeline smoke, SSH deploy, secrets, Checkstyle/SpotBugs + ESLint (§5.3) | `.github/workflows/*` | — | GHCR | 12 workflows | `backend`, `ops`, `acceptance`, `reconstruction-smoke` green; `docker`, `frontend`, `python`, `security`, `ios` **red** [today] | — | **deploy has never succeeded** (skipped behind `docker`). **No Checkstyle/SpotBugs** (ESLint and ruff only). | PARTIALLY IMPLEMENTED | Fix the five red workflows (P0/P1 list); add SpotBugs or drop the claim. |
| 24 | **Deployment / hosting**: Vercel frontend, Render backend, Neon Postgres, UptimeRobot, Kaggle GPU, home-server fallback; CDN for `.ksplat`; Redis (§4, §5.2, §5.4) | `infra/deploy` (single Docker host: Caddy, all services), `deploy.sh`, `deploy.yml` | `/api/v1/health` | — | `test-deploy.sh` 50/50; production-like stack run incl. rollback [session] | Production-like stack on a workstation [session]. | **Never deployed to a real host.** | None of Vercel/Render/Neon/UptimeRobot/Kaggle exists: replaced by one self-hosted Docker host. **No CDN.** **Redis removed** (unused). No always-on judge link exists. | BLOCKED BY EXTERNAL PREREQUISITE (host, DNS, secrets) — and the document's hosting design is NOT IMPLEMENTED | Provision one staging host; run the `deploy` workflow once, including one deliberate failed deploy. |
| 25 | **Performance**: LOD by frame time, low-end Android, BrowserStack; time-boxed processing (§6.7, §7) | `lib/device-profile.ts`, `lib/frame-quality.ts`, `SplatViewerCanvas.tsx`; pipeline time budget | — | — | web unit; pipeline time-budget tests | Only headless software WebGL on a synthetic `.ksplat`. | **No device measurement**, no BrowserStack run, no real model to load. | No FPS/load-time figure exists for any device or real model. | IMPLEMENTED BUT NOT END-TO-END VERIFIED | Measure load time and median frame time of the first real `.ksplat` on one low-end Android phone. |
| 26 | **Benchmark reproducibility** (§4 "benchmark table", §8) | `benchmarks/*`, `benchmarks/results/*/latest.json` | — | results JSON with environment, git state, input checksums | `benchmarks/tests` 10 passed [today] | **B1 reproduced [today]** (all non-timing metrics identical). B2/B5 need downloaded datasets not on this host; B3/B4 a running stack; B6 the `chaya-ml-eval` image and a dataset kept outside the repo. | — | B6 dataset lives outside the repo (rebuild command documented). | IMPLEMENTED BUT NOT END-TO-END VERIFIED (B1 verified; B2–B6 not re-run today) | Re-run B3/B4 on the acceptance stack and B2/B5 with the documented downloads; commit the run files. |

### Other promises in the document

| Promise | Evidence | Classification |
|---|---|---|
| GLOMAP as the primary SfM (§4, §7) | GLOMAP is in **no image**, including `Dockerfile.gpu`; COLMAP CPU ran on 11 public outdoor photos only [doc E2E §R] | PARTIALLY IMPLEMENTED (code path optional; never run) |
| Redis cache/session store (§5.2) | Removed 2026-10-07: nothing uses it (ARCHITECTURE.md §3.1) | NOT IMPLEMENTED (by decision) |
| Monitoring: Prometheus/Grafana/Loki/GlitchTip (§6.4) | `infra/monitoring` compose; `compose config` valid [session]; alert rules; never run against a deployment | IMPLEMENTED BUT NOT END-TO-END VERIFIED |
| Licensing audit (§6.2) | `scripts/licenses/*`, THIRD_PARTY_LICENSES.md ("not a statement of compliance"); SegFormer weights flagged research-only | IMPLEMENTED (finding: licence blocker, P1) |
| Failure handling (§6.3): ORB pre-flight, AR tracking-loss handling, offline upload queue, gsplat checkpointing | FAST-corner (ORB keypoint) pre-flight in the browser; offline queue is **IndexedDB** (not SQLite), verified by `capture-offline.spec.ts`; checkpoint/resume implemented, never run on GPU | IMPLEMENTED BUT NOT END-TO-END VERIFIED |
| Postgres job queue (§6.9) | `ProcessingJobRepository` (`FOR UPDATE SKIP LOCKED`), leases, `JobRecoveryTest`, AC 3.11–3.16 (real lease expiry) | IMPLEMENTED AND VERIFIED |
| Usability test with non-technical users (§6.8) | No record | NOT IMPLEMENTED |
| Unit economics from real observed GPU billing; venue validation conversation (§9) | No cost source; no record of a venue conversation | NOT IMPLEMENTED |
| "each with a solution that was actually run, not just proposed" (§10) | Contradicted by rows 5–8, 11–14, 21, 24, 25 | **Incorrect claim** |

---

## 2. Remaining defects, ranked

### P0: the promised product cannot be demonstrated, or the claim is false as written

| ID | Defect | Evidence | Smallest fix |
|---|---|---|---|
| P0-1 | **No real reconstruction has ever been produced.** gsplat never trained; `Dockerfile.gpu` never built. Everything downstream (real viewer model, cleanup, segmentation, detection, venue navmesh, real re-scan) is unexercised on real data. | REAL_VENUE_ACCEPTANCE.md, E2E_VALIDATION.md, PROJECT_PLAN M9 | A CUDA host: build the GPU image, preflight, `pytest -m gpu`, then one venue run (checklist §5). |
| P0-2 | **`docker` workflow red: 2 CRITICAL CVEs** (spring-webmvc 6.2.19, fixed in a later Spring release). This blocks every staging deploy. | `gh run view 39030707019` [today] | Bump Spring Framework / Boot to the fixed patch; re-run `docker`. |
| P0-3 | **No deployed environment exists**; the `deploy` workflow has never succeeded. The document's always-on judge link does not exist. | `gh run list` [today]; DEPLOYMENT.md §11 | After P0-2: provision a staging host and secrets; run `deploy`. |
| P0-4 | **The solution document misstates the delivered system**: fine-tuned detector and segmentation, a PSNR/SSIM ablation table, FAISS, AprilTag, live camera obstacle detection, multi-room loop closure, real-device testing, observed cost, "actually run". | This matrix | Revise the document to match rows 1–26 before submission. |

### P1: a promised feature is missing, or CI is red

| ID | Defect | Evidence | Smallest fix |
|---|---|---|---|
| P1-1 | `ios` red: `ReferenceImageTests` XCTUnwrap (stale fixture since `fb7e65f`) | `gh run view 39014117937` [today] | Update the fixture to a calibrated, versioned anchor. **Update, same day: fixed (not yet run in CI).** The image was never the problem: the test anchor had no `scanVersionId`/`poseId`, so `AnchorSupport.of` (since `fb7e65f`) returns `.unversioned`, the registry is empty and `XCTUnwrap(markers.first)` fails before any PNG or ARKit code runs (run log: Xcode 15.4 15F31d, iOS 17.5 SDK, 11 app tests, 1 failure). The production rule stays; the test anchor is now versioned. The ad-hoc 512 px test image is replaced by the server's real target artwork, committed at `packages/contracts/fixtures/ar/` (1024 x 1024 RGB PNG, no EXIF orientation), copied into the test bundle by `project.yml`, and kept pixel-identical to `ImageTarget.render` by `services/api` `ImageTargetFixtureTest`. `ReferenceImages.make` now also rejects a truncated/incomplete image and a non-positive width. New tests: empty, random, signature-only and truncated images; aspect ratio and `.up` orientation; a marker without an image is unresolvable; unversioned anchors get no image; `ARReferenceImage.validate` on the fixture (asserted on a device; reported as a skip if the Simulator cannot run it). Checked here: `swiftc -parse`, core `swift test` 72/72, the Java fixture test, actionlint. Not compiled or run on macOS yet. Physical detection and relocalization are still not validated (docs/ar-ios-validation.md). |
| P1-2 | `python` red: a test skips in the CPU suite (CI forbids skips) | `gh run view 39025959119` [today] | Find the skipped test in the run log; install its dependency in the job or move it out of the CPU selection. **Update, same day: fixed (not yet run in CI).** Two tests skipped, not one: `tests/ml` (no `transformers`) and the pinned viewer library check (no `npm ci` in `apps/web`). Both now run with pinned dependencies. Also: 5 Open3D-on-CPU tests marked `gpu` ran nowhere (now an `open3d` job), and `test_minio_storage.py` ran nowhere (now a `minio-integration` job). **One of the newly running tests fails on a real defect**, see P1-14. |
| P1-14 | **GEOMETRIC_CLEANUP's radius filter removes ~59 % of any irregularly sampled surface**: with `cleanup_radius_nb_points=8` within 3 × the mean nearest-neighbour spacing, a uniformly sampled surface has 9π/4 ≈ 7.07 expected neighbours at any density. Floors and walls are thinned before PLANE_FITTING (min 200 inliers), which then misses planes. Explains B2's "removes over half the cloud". Lattices are unaffected, which is why the acceptance suite passed. | Measured with Open3D 0.19: keeps 40.9 % of the test's clean floor+wall, 41.8 % of a 100× denser random surface, 100 % of a lattice. `test_geometric_cleanup_and_plane_fitting_run_on_a_real_synthetic_room`: `assert 1 >= 2`. | Retune the radius filter (e.g. require fewer neighbours, or scale the radius so the expected count is well above the threshold), then re-run B2 (real splat) and B2c (synthetic, ground truth) to confirm noise removal still holds. |
| P1-3 | `frontend` red: `processing-live.test.ts:161` overflows the call stack on the runner (an unbounded fake reconnect loop spread into `Math.max`) | `gh run view 39030706996` [today]; passes 5/5 locally | Bound the harness loop and use a reduce instead of spreading. **Update, same day: fixed (not yet run in CI).** The exact cause was a clock mismatch, not reconnects: `followProcessing` measured its poll window with `Date.now()` while waiting through the injected `sleep`, which the test made instant, so the poll loop busy-spun about 290,000 times in 4 s. Reproduced with `--stack-size=150`; time is now injectable (`deps.now`); 11 regression tests added. |
| P1-4 | `security` red: OWASP Dependency-Check cannot update NVD data | run 39030707022 [today] | Add the `NVD_API_KEY` secret (external credential). |
| P1-5 | Default SegFormer weights are licensed for research/evaluation only | docs/ML_EVALUATION.md | Switch to a commercially licensed segmentation checkpoint. |
| P1-6 | Multi-room stitching does not exist (row 3) | grep, docs | See row 3. |
| P1-7 | Dynamic obstacle rerouting has no detector (row 11) | docs/ar.md | See row 11. |
| P1-8 | AprilTag/ArUco/QR anchors are undetectable on both platforms; the document specifies AprilTag | docs/ar.md, MarkerRegistry.swift | Use image targets in the document, or add a fiducial detector (feature). |
| P1-9 | Privacy detection weaker than promised; false positives open (F5); recall unmeasured | E2E §7 F5 | Measure, then replace the Haar detector. |
| P1-10 | No cost tracking (dashboard and business model depend on it) | operations-dashboard.md | See row 17. |
| P1-11 | Re-scan DIRECT alignment falsely rejects a region dominated by a moved object; a naive fix accepted a 25 % scale error | ACCEPTANCE.md findings P1 | Design and benchmark (B5) a robust refinement first. |
| P1-12 | GLOMAP absent from every image | Dockerfiles | Add GLOMAP to `Dockerfile.gpu`, or call COLMAP primary. |
| P1-13 | Android AR requires a Keycloak sign-in (document: no login on site); iOS uses the viewer link | ArWorkspace.tsx, NavigationViewModel.swift | Accept a viewer link on the Android AR page as iOS does. |

### P2: deviations, documentation and polish

Z-axis floor segmentation absent (row 4); no background refinement after a time-budget overrun (row 5); Keycloak events not in
`audit_log`; no Checkstyle/SpotBugs; no CDN; no off-site backup copy, and the Keycloak DB is not in `backup.sh`; HUD has no
point cloud or 3D heatmap (row 2); viewer opens outside enclosing walls, overlapping POI markers, same-hash floor switch skips
the storage re-check (ACCEPTANCE.md P4–P6); alignment confidence sensitivity on gridded synthetic geometry (P2 there); the
hosting design (Vercel/Render/Neon) and Redis were replaced (document fix); no usability test; no device performance figures.

---

## 3. Reproducible verification commands

Run from the repository root (Git Bash on Windows, or Linux). Each line states what it proves.

```bash
# CI state of the audited commit (needs gh auth)
gh run list --limit 30 --json workflowName,conclusion,headSha --jq '.[] | "\(.headSha[0:7]) \(.workflowName): \(.conclusion)"'

# Cross-service acceptance, class C (real API, Keycloak, Postgres/pgvector, MinIO, ClamAV, vision, web, workers; synthetic scene)
bash scripts/acceptance/run-acceptance.sh ci            # expect 40/40 + browser 21/21, exit 0

# Backend unit (A) and integration (B), separately counted
(cd services/api && mvn -B -ntp test -DexcludedGroups=integration,live && mvn -B -ntp verify -Dgroups=integration)

# Worker CPU suites (no GPU): unit, orchestration, smoke, splat mechanics, real Recast
(cd services/reconstruction && python -m pytest -m "not gpu and not integration and not acceptance" tests/unit tests/orchestration tests/smoke tests/splat tests/navmesh)

# Web: unit, lint, typecheck, production build, browser (mocked backend)
(cd apps/web && npm test && npm run lint && npm run typecheck && npm run build && CI=1 npx playwright test --workers=2)

# Deployment control flow and backups
bash scripts/deploy/test-deploy.sh && bash scripts/backup/test-restore-drill.sh

# Benchmarks
python -m pytest benchmarks/tests
python benchmarks/b1_capture_path/run.py                # compare with benchmarks/results/b1_capture_path/latest.json

# GPU (CUDA host only): must FAIL rather than skip when a requirement is missing
docker build -f services/reconstruction/Dockerfile.gpu -t chaya-worker-gpu services/reconstruction
docker run --rm --gpus all chaya-worker-gpu python -m chaya_worker.splat_preflight
docker run --rm --gpus all -e CHAYA_REQUIRE_GPU=1 chaya-worker-gpu python -m pytest -m gpu -rA

# Real venue (deployed stack + GPU worker): docs/REAL_VENUE_ACCEPTANCE.md
python scripts/acceptance/real_venue.py --help
```

## 4. Known environmental blockers

| Blocker | Blocks | Owner action |
|---|---|---|
| No CUDA GPU (audit host: Intel integrated graphics) | gsplat, `Dockerfile.gpu`, `pytest -m gpu`, every real reconstruction, rows 5–8, 10, 15, 25 | Provide an A10/L4-class host or a scripted Kaggle/Colab runner |
| No ARCore Android phone | rows 1, 2, 12, 14 (Android) | One ARCore device with Chrome |
| No iPhone and no Mac with Xcode | rows 13, 14 (iOS) | One ARKit iPhone and a Mac (or CI) to install the build |
| No real venue and no measured control points | real-venue acceptance (§5) | Site access, a tape or laser measure, printed image targets |
| No deploy host, DNS or production secrets | rows 24, 22 (real host), P0-3 | A Linux host, three DNS names, GitHub Environment secrets |
| No `NVD_API_KEY` | `security` workflow | Request a free NVD API key; add as a secret |
| No licensed indoor training data | fine-tuning (rows 7, 8) | Label frames from the project's own captures |
| Workstation C: drive nearly full (about 4 GB free) | building images and Maven containers locally | Free disk, or compact Docker Desktop's disk image |
| B2/B5 input data not on the host (playroom splat, Sceaux SfM) | re-running B2/B5 | Download per benchmarks/README.md |

## 5. Real-venue acceptance checklist

Prerequisites: deployed stack (row 24), a GPU worker (row 5), site access. Script: `scripts/acceptance/real_venue.py`;
procedure: docs/REAL_VENUE_ACCEPTANCE.md. Each item needs the stated evidence; without it, it does not count.

1. **Venue setup:** organization, venue, floors and Keycloak users created. Evidence: IDs, roles.
2. **Control points:** at least 3 points per floor measured with tape or laser; distances recorded. Evidence: measurement sheet.
3. **Guided capture:** recon lap → plan → capture on a phone. Evidence: plan, HUD coverage before and after, files accepted.
4. **Reconstruction:** every stage SUCCEEDED; no stand-in stage. Evidence: `report.json` (stage records with worker, command, exit status, artifact checksums).
5. **Calibration:** RMS against the measured points. Evidence: calibration report; RMS below the documented limit.
6. **Viewer:** model loads on desktop and on a phone. Evidence: screenshots at stated positions; load time; median frame time.
7. **Cleanup and segmentation:** floors, walls and furniture labelled sensibly. Evidence: label counts; a sample of reviewed frames.
8. **Search:** 10 real queries (including synonyms). Evidence: top-3 results judged correct or wrong by a person.
9. **Navigation:** 5 routes, including one step-free and one multi-floor, walked on site. Evidence: measured walked length against the route length.
10. **Privacy:** frames with people and screens. Evidence: privacy report; per-frame review of missed faces and false blurs.
11. **Re-scan:** move one object; re-scan the region. Evidence: alignment report (gates), splice report, navmesh outside-region consistency; old version still served.
12. **Operations:** freshness, job history, failures and search analytics show this venue. Evidence: dashboard screenshots.
13. **Backup:** backup and restore drill on the venue's data. Evidence: drill report with SHA-256 matches.

## 6. Physical-device acceptance checklist

Procedures: docs/ar-android-validation.md, docs/ar-ios-validation.md. Record device model, OS version, browser or app build.

| # | Check | Android (Chrome, WebXR) | iOS (ARKit app) | Evidence |
|---|---|---|---|---|
| 1 | AR session starts; unsupported states named correctly | ✓ | ✓ | screen recording |
| 2 | Image-target marker localizes; a blank wall never does | ✓ | ✓ | status log |
| 3 | Two markers on one floor agree; a swapped print is refused | ✓ | ✓ | residuals shown |
| 4 | Route drawn; drift measured at 3 checkpoints with a tape | ✓ | ✓ | table: checkpoint, error (m) |
| 5 | Step-free route takes the elevator; floor handoff at the connection | ✓ | ✓ | recording, status log |
| 6 | Network loss keeps a drawn route usable; recovers online | ✓ | ✓ | recording |
| 7 | Tracking loss freezes the overlay and relocalizes | ✓ | ✓ | recording |
| 8 | Capture HUD: WebXR position tracking, coverage, reshoot flags | ✓ | n/a | HUD export |
| 9 | Upload from the phone over mobile data, interrupted and resumed | ✓ | n/a | server media records |
| 10 | Viewer on a low-end phone: load time, median frame time, quality step-down | ✓ | n/a | figures |
| 11 | iOS banner removed only after all of the above pass (`DeviceValidation.status`) | n/a | ✓ | commit |

## 7. Remaining implementation plan (smallest first)

1. **Make CI green** (P0-2, P1-1 to P1-4): Spring patch bump; iOS fixture; the skipped Python test; bound the flaky web test;
   add `NVD_API_KEY`. Done when every workflow is green on one commit.
2. **Correct the solution document** (P0-4), using rows 1–26: stock models, pgvector, image targets, a 2D coverage grid,
   floors as separate captures, server-side routing, one self-hosted Docker host, no cost data yet.
3. **Deploy staging once** (P0-3): host, DNS, secrets; run `deploy` including one deliberate failed deploy.
4. **GPU run** (P0-1): build `Dockerfile.gpu`, preflight, `pytest -m gpu`, one synthetic splat, then the real-venue checklist (§5).
5. **Device runs** (rows 12–14): the device checklist (§6) on one Android and one iPhone; record drift.
6. **Close feature gaps only after 4–5**, by impact: cost tracking (P1-10); viewer link on Android AR (P1-13); privacy
   detector measurement and replacement (P1-9); a licensed segmentation base (P1-5); obstacle producer (P1-7); multi-room
   registration (P1-6); robust DIRECT re-scan refinement (P1-11).
7. **Measurement deliverables:** PSNR/SSIM cleanup ablation (row 6), device performance (row 25), re-run B2–B6 (row 26),
   usability test (§6.8), fine-tuning once labelled venue data exists (rows 7–8).

## 8. Final technical readiness assessment

**The control plane is production-grade and verified; the reconstruction and AR halves, which are the product's
differentiators, have never run on their real inputs.**

- **Verified with real collaborators:**
  - authentication and tenant isolation;
  - upload security;
  - the processing control plane: jobs, leases, retries and failure propagation;
  - version history and the operations dashboard, except cost;
  - semantic search, with real CLIP;
  - deployment mechanics with rollback, backups and the restore drill, on a production-like stack.

  The cross-service acceptance suite passes 40/40 against real services, also in CI. Stand-ins replace exactly the four
  stages that need a GPU or real footage.
- **Implemented but unproven:** guided capture, Gaussian splatting, cleanup on real splats, venue navigation, the AR clients
  and multi-anchor relocalization, and real re-scans. Each is blocked by hardware (a GPU, phones), a venue, or both.
- **Missing relative to the document:**
  - multi-room stitching;
  - automatic floor segmentation;
  - live obstacle detection;
  - AprilTag detection;
  - fine-tuned models;
  - the PSNR/SSIM ablation;
  - cost tracking;
  - the hosting design;
  - usability testing.
- **Release readiness:** **not ready.** CI has five red workflows, including a CRITICAL CVE that blocks deployment. No
  environment has ever been deployed, and no venue has been reconstructed.
- **Demo readiness:** a staging deployment showing upload → processing control plane → versioning → search → navigation →
  operations on synthetic or pre-made scenes is achievable after steps 1–3 of the plan. A live venue demo additionally needs
  steps 4–5. The document should be corrected first, because several of its claims are untrue as written.
