# CHAYA 02 — Project Plan

Each milestone is independently testable and committable. A milestone is **Done** only when its "Verified by" column
has been met and nothing in it pretends to do work that isn't implemented. Code existing is not enough: a milestone whose
code exists but whose own verification has not been met is **Implemented**, with the unverified part named.

*2026-10-24:* the table below is the 2026-10-12 record. The current per-requirement evidence, with the seven
validation levels and P0–P2 priorities, is in [docs/BASELINE_AUDIT_2026-10-24.md](docs/BASELINE_AUDIT_2026-10-24.md).

Status last checked **2026-10-12** at `40beef5` (plus the documentation audit that wrote this table). Per-capability
evidence is in the [validation matrix](#validation-matrix) below; the commands and their results are in
[docs/TEST_REPRODUCIBILITY.md](docs/TEST_REPRODUCIBILITY.md).

| # | Milestone | Scope | Status (2026-10-12) | Verified by |
|---|-----------|-------|---------------------|-------------|
| M0 | Repo foundation | Docs, `.env.example`, `.gitignore`, layout | Done | Files exist; no secrets |
| M1 | Local infrastructure, backend/web skeletons | Compose file, health/version endpoints, status page | Done | Compose config valid; web lint/typecheck/test/build; `mvn verify` |
| M2 | Backend hardening | MinIO health, problem+json errors (Redis dropped from scope 2026-10-07: nothing uses it; ARCHITECTURE.md §3.1). There is **no Maven wrapper**: the earlier plan named `./mvnw`, which was never added. | Done | `mvn verify` (CI `backend`, green) |
| M3 | Identity & tenancy | Keycloak JWT resource server, org/venue tenancy carried in the token (docs/security.md), venue-scoped authz, audit log. **Organizations are created in SQL; there is no organization API.** | Done | Integration tests: cross-tenant access returns 404 (`TenantApiIsolationTest`, `CrossTenantResourceIsolationTest`) |
| M4 | Frontend skeleton | Next.js, Tailwind, OIDC login (PKCE), venue list from API | Done | Unit tests; Playwright drives the real PKCE flow against a mocked OIDC provider (`e2e/ops.spec.ts`). Never against a deployed Keycloak in CI. |
| M5 | Storage & capture sessions | Resumable chunked upload through the backend into MinIO, Tika/ClamAV validation, capture lifecycle, capture UI (docs/capture-ingestion.md) | Done for its stated verification. **Never uploaded from a phone in the field.** | Tests against real MinIO; real ClamAV in the stack smoke |
| M6 | Job engine | Pipeline runs, leases/heartbeats, retry, time box, worker API, stage records, worker presence (docs/pipeline.md) | Done | Java control-plane tests; Python orchestration + integration tests; stack smoke |
| M7 | Media filter worker | Input validation, FFmpeg extraction, quality filter, privacy preprocessing | Done. Privacy over-masks building texture on real photos (E2E §7 F5, open). | Worker tests; real public outdoor photos (E2E §0, §R) |
| M8 | Pose estimation worker | COLMAP feature extraction and matching; GLOMAP mapper with COLMAP mapper fallback; poses.json; structured failure when tools are missing | **Implemented.** COLMAP has run only on 11 public outdoor photos, in a test-only CPU image. **GLOMAP is in no image and has never run.** The image meant to run this stage in deployment (`Dockerfile.gpu`) has never been built. | Unit tests (tools stubbed); E2E §R (CPU COLMAP 3.8, 11/11 registered, 0.398 px); gpu-marked real-photo tests never run |
| M9 | Splat training worker | gsplat training (Adam, L1+D-SSIM) with adaptive density control, time-boxed COMPLETED/PARTIAL, resumable checkpoints, one colour convention to the viewer, `.ksplat` export, artifact manifest, preflight, pinned GPU image (docs/pipeline.md, "Splat training") | **Implemented; not validated.** **gsplat training has never executed; no real venue has been reconstructed.** Training mechanics validated on CPU with a test renderer only. | Colour-chain fixture; CPU training-mechanics tests (`tests/splat`); gpu-marked tests and `tests/gpu/test_splat_acceptance.py` **never run** (no CUDA host) |
| M10 | Splat viewer | Three.js + GaussianSplats3D viewer, version selection, POI overlays, voice-assisted search, in-browser size/SHA-256/format check of the served `.ksplat` | **Implemented; format-validated only.** A production-encoder synthetic fixture is rendered (docs/E2E_VALIDATION.md §V). **No real reconstruction has been viewed** (none exists: M9). Headless software WebGL only. | Playwright: pixel, camera and overlay checks on the fixture (API mocked); full-stack browser check (E2E §V.3) |
| M11 | Geometry & segmentation | Open3D statistical/radius/semantic-aware cleanup, RANSAC plane fitting, SegFormer segmentation (stock checkpoint) | **Implemented; fixture-validated.** Open3D-dependent tests skip in the CPU suite; never run on a real splat. The default SegFormer weights are **research-only** (docs/ML_EVALUATION.md). | Unit tests (Open3D/models stubbed); B2 cleanup benchmark (synthetic scene and real SfM geometry); B6 stock-model baseline |
| M12 | Object detection & search | Grounding DINO (stock checkpoint) + CLIP embeddings, pgvector HNSW index, search API with lexical fallback, manual-POI re-embedding, search log (docs/search.md). Dataset pipeline (chaya-dataset/2), fine-tuning harness, held-out baseline (B6). | **Implemented with stock models. Not done: any fine-tuned model** -- blocked on indoor training annotations, a GPU host, and a permissively licensed segmentation base model (docs/ML_EVALUATION.md) | Unit tests; integration tests (venue isolation, ranking, unavailable-model fallback); B3 (real CLIP on COCO crops); B6 baseline on 102 held-out COCO indoor images |
| M13 | Navmesh & routing | Recast/Detour baking (chaya-navmesh, recastnavigation 1.6.0 pinned by SHA-256) from a multi-level surface model of the cleaned, labelled splat; NAVMESH + NAVMESH_MANIFEST with provenance; STANDARD/STEP_FREE graphs from the Detour polygon links; Dijkstra + portal string-pulling in the API, only on navmesh-backed graphs (`NAVMESH_NOT_READY` otherwise); multi-floor transitions, dynamic obstacle exclusion (docs/navigation.md) | **Implemented; fixture-validated. Not validated on a real reconstructed venue** (the stages before baking need CUDA and Open3D). | Real-tool tests on synthetic venues (wall, doorways, furniture, column, step, 1:14 ramp; re-scan splice), every failure state produced; CI builds the tool and fails if these tests skip. API integration tests: route exists/unavailable, obstacle, accessibility, multi-floor, cross-venue. |
| M14 | Capture guidance | Path planning and coverage metric (docs/route-planning.md); live quality/coverage HUD (docs/capture-hud.md); automatic room-bounds estimation from a reconnaissance lap, scan planning, WebXR (ARCore) position tracking, trajectory import, measured-calibration evidence capture (docs/capture-calibration.md) | **Implemented; not field-validated.** No WebXR capture session has run on an ARCore device; no real capture has been planned or guided. | Unit tests; `CapturePlanApiTest`, `CapturePlanningPipelineTest`, bounds-planning benchmark on synthetic trajectories. **Field capture: not done.** |
| M15 | AR navigation | Android WebXR, iOS ARKit, fiducial anchors with versioned poses, multi-anchor relocalization, VIO interpolation, path-data API (docs/ar.md) | **Implemented; never run on a device.** The iOS app's XCTest job has been red since `fb7e65f` (a stale test fixture, BASELINE_AUDIT F5). | Unit tests (web, `ChayaARCore` on Linux Swift); Playwright for unsupported states only. **On-device tests: not done** (docs/ar-android-validation.md, docs/ar-ios-validation.md). |
| M16 | Incremental rescan | Region-scoped reconstruction and versioned merge; canonical-frame alignment (direct for surveyed re-scans, otherwise robust similarity); physical quality gates with `ALIGNMENT_REJECTED`; splice with seam measurement; finalization pins exactly the version's artifacts; `floor.current_scan_version_id` publication (V28); viewer, POIs, search and routing scoped per version (docs/rescan.md, "VERSIONING") | **Implemented; validated on synthetic clouds and in B5 only. No real venue has been re-scanned and merged.** | Worker math, alignment, splice and stage tests on synthetic clouds (CI); real FPFH in the Open3D image (manual); control-plane tests (rejection, parent untouched, lineage, publication); three versions end to end over HTTP; B5 on a real SfM cloud with simulated misalignment |
| M17 | Operations dashboard | Freshness, job state, queue depth, worker liveness, coverage gaps, search analytics, storage (docs/operations-dashboard.md) | Done for its stated verification. Browser checks use a mocked API. | `OpsDashboardApiTest`, `JobRecoveryTest` against real Postgres rows; Playwright `ops.spec.ts` |
| M18 | CI/CD | GitHub Actions, GHCR images, gated deploys with rollback and migration rehearsal (DEPLOYMENT.md) | **Implemented; not green.** On `main` at `40beef5`, `python`, `reconstruction-smoke`, `docker`, `security` and `ios` fail (docs/TEST_REPRODUCIBILITY.md, "CI status"). `deploy` has never succeeded: it is skipped when `docker` fails. No deploy to a real host has ever run. | Green pipeline: **not met** |

Order note: M14 may move earlier if capture-quality feedback blocks reconstruction quality work.

## Validation levels

Each level is a separate claim. A higher level is never implied by a lower one.

- **Implemented:** the code exists and does real work when its dependencies are present.
- **Unit-tested:** automated tests exercise it in isolation (dependencies stubbed or absent).
- **Integration-tested:** automated tests exercise it against real collaborators (Postgres, MinIO, Keycloak, ClamAV,
  the real worker against the real API, or the real external tool).
- **Fixture-validated:** the real code ran on committed or downloaded fixtures, synthetic or public.
- **Real-venue executed:** it ran on a real indoor venue capture through this pipeline.
- **Physical-device validated:** it ran on the target hardware: a phone capture or AR device, or a CUDA GPU worker.

**No row is real-venue executed, because no real venue capture exists.** "Real media" means the public outdoor Sceaux
photographs (docs/E2E_VALIDATION.md §0, §R): real camera input, but not a venue, and with no measurements.

## Validation matrix

As of 2026-10-12. "(doc)" means recorded evidence that was not re-run on 2026-10-12.

| Capability | Implemented | Unit-tested | Integration-tested | Fixture-validated | Real-venue executed | Physical-device validated |
|---|---|---|---|---|---|---|
| Identity, tenancy | Yes | Yes | Yes (Testcontainers; real Keycloak in the stack smoke) | n/a | No | n/a |
| Upload and validation (Tika, ClamAV) | Yes | Yes | Yes (real MinIO; real ClamAV in the stack smoke) | Synthetic video | No | **No**: never uploaded from a phone |
| Capture HUD, scan planning, bounds estimation | Yes | Yes | Yes (`CaptureHudApiTest`, `CapturePlanApiTest`) | Synthetic trajectories | No | **No**: no WebXR capture session on an ARCore device |
| Measured calibration evidence and calibration UI | Yes (points are picked in the reconstruction by hand; no triangulation) | Yes | Yes (`CaptureCalibrationTest`, `CoordinateFrameCalibrationTest`) | Synthetic calibration scenes | **No**: no measured references exist | n/a |
| INPUT_VALIDATION, FFMPEG_PREPROCESS, FRAME_QUALITY_FILTER | Yes | Yes | Yes (stack smoke) | Yes | No; real outdoor media: yes (doc) | n/a (CPU) |
| PRIVACY_PREPROCESS | Yes | Yes | Yes (stack smoke, PII purge checked) | Yes | No; real outdoor media: yes (doc), over-masks | n/a (CPU) |
| POSE_ESTIMATION | Yes (COLMAP; GLOMAP optional and in no image) | Yes (tools stubbed) | Only the `DEPENDENCY_UNAVAILABLE` path (stack smoke) | Real public outdoor photos, CPU COLMAP 3.8, test-only image (doc) | No | **No**: `Dockerfile.gpu` never built |
| Gravity alignment, canonical frames | Yes | Yes | Yes | Synthetic planes | No | n/a |
| SPLAT_RECONSTRUCTION (gsplat) | Yes | CPU training mechanics with a test renderer only | No | No (CUDA tests never run) | **No (blocked: no CUDA)** | **No** |
| SEMANTIC_SEGMENTATION, GEOMETRIC_CLEANUP, PLANE_FITTING | Yes | Yes (Open3D and models stubbed) | No (the Open3D toolchain test skips) | B2 synthetic and real SfM geometry; B6 stock-model baseline (doc) | No | No |
| ARTIFACT_GENERATION (`.ksplat`) → viewer | Yes | Yes | Worker smoke; full stack §V.3 (doc) | Synthetic `.ksplat`, pixel-checked in the pinned viewer | **No**: no real `.ksplat` exists | No (headless software WebGL only) |
| SEMANTIC_INDEXING, semantic search | Yes (stock Grounding DINO and CLIP) | Yes (vision: CLIP mocked) | Yes (`SemanticSearch*Test`) | B3 real CLIP; B6 (doc) | No | n/a |
| Fine-tuned segmentation / detection models | **No model trained**; harness only | Harness smoke with tiny random models (needs `transformers`) | n/a | n/a | No | No GPU |
| NAVIGATION_BAKING (Recast) | Yes | Yes | Yes (the API ingests real tool output) | Yes (real Recast/Detour on synthetic venues) | No | n/a (CPU) |
| Route generation | Yes | Yes | Yes (`RouteServiceTest`, `RoutePlanApiTest`) | On fixture navmeshes | No | n/a |
| Incremental re-scan, scan versions | Yes | Yes | Yes (control-plane and publication tests) | Synthetic clouds; B5 on a real SfM cloud with simulated misalignment (doc) | No | No |
| AR Android (WebXR) | Yes | Yes | Playwright: unsupported states only | n/a | No | **No**: never on a phone |
| AR iOS (ARKit) | Yes | Yes (`ChayaARCore`, Linux Swift) | App XCTest **red in CI** since `fb7e65f` | n/a | No | **No**: never on a phone |
| Health, worker liveness, ops dashboard | Yes | Yes | Yes (`JobRecoveryTest`, `OpsDashboardApiTest`) | n/a | No | n/a |
| Erasure | Yes | Yes | Yes (`ErasureTest`, `StoragePolicyTest`) | n/a | No | n/a |
| Deploy, rollback, backup and restore drill | Yes | Yes (`test-deploy.sh` with stubs, `test-restore-drill.sh`) | Restore drill on stack-smoke data: passes locally; the CI job fails at its cleanup step | n/a | **Never run on a real deploy host** | n/a |

### First stage a real venue run cannot pass

| Step | Can it run? |
|---|---|
| 0. A measured indoor capture | **Absent** (data, not code) |
| 1–4. Upload and CPU stages | Yes, on the shipped CPU worker |
| 5. POSE_ESTIMATION | Only on a worker with COLMAP. The shipped deployment claims it only if a `Dockerfile.gpu` worker is attached, and that image has **never been built**. Without it, health reports `NO_WORKER` with `unservedStages: ["POSE_ESTIMATION"]`. |
| 6. SPLAT_RECONSTRUCTION | **No host available:** needs CUDA. On a CPU worker: `DEPENDENCY_UNAVAILABLE: torch, gsplat, cuda`. |
| 7–12, calibration, viewer, search, route | Never reached with real data |

How to diagnose this on a running stack: [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md). What a real run needs:
[docs/REAL_VENUE_ACCEPTANCE.md](docs/REAL_VENUE_ACCEPTANCE.md).

An end-to-end regression test from a real venue run is deliberately **not** added yet. It may be cut from a compact
fixture only after the first successful real-venue run (docs/E2E_VALIDATION.md §R.4).
