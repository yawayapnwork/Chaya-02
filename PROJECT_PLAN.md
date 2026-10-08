# CHAYA 02 — Project Plan

Each milestone is independently testable and committable. "Done" means its tests pass and nothing in it pretends to do work that isn't implemented.

| # | Milestone | Scope | Verified by |
|---|-----------|-------|-------------|
| M0 | Repo foundation | Docs, `.env.example`, `.gitignore`, layout (this commit) | Files exist; no secrets |
| M1 | Local infrastructure, backend/web skeletons | Compose file, health/version endpoints, status page (foundation commit) | Compose config valid; web lint/typecheck/test/build; `mvn verify` |
| M2 | Backend hardening | MinIO health, problem+json errors, Maven wrapper (Redis dropped from scope 2026-10-07: nothing uses it; ARCHITECTURE.md §3.1) | `./mvnw verify` |
| M3 | Identity & tenancy | Keycloak JWT resource server, org/venue/membership tables, venue-scoped authz, audit_event | Integration tests: cross-tenant access returns 404 |
| M4 | Frontend skeleton | Next.js, Tailwind, OIDC login, venue list from API | Unit + Playwright login test |
| M5 | Storage & capture sessions | Done: resumable chunked upload through the backend into MinIO, Tika/ClamAV validation, capture lifecycle, capture UI (see docs/capture-ingestion.md) | Tests against real MinIO |
| M6 | Job engine | Done: pipeline runs, leases/heartbeats, retry, time box, worker API, stage records (docs/pipeline.md) | Java control-plane tests; Python orchestration + integration tests |
| M7 | Media filter worker | Done: input validation, FFmpeg extraction, quality filter, privacy preprocessing | Worker tests on real media |
| M8 | Pose estimation worker | Done: GLOMAP with COLMAP fallback; poses.json; structured failure when tools are missing | Unit tests; real-dataset gpu-marked tests |
| M9 | Splat training worker | Implemented; training mechanics validated on CPU only; **gsplat training never executed and no real venue reconstructed**. gsplat training (Adam, L1+D-SSIM) with adaptive density control, a time-boxed loop reporting COMPLETED or PARTIAL, resumable checkpoints, one colour convention to the viewer, `.ksplat` export, artifact manifest (docs/pipeline.md, "Splat training") | Colour-chain fixture; CPU training-mechanics tests (`tests/splat`, CI fails on a skip); gpu-marked tests (skipped: no GPU) |
| M10 | Splat viewer | Done: Three.js + GaussianSplats3D viewer, real reconstruction selection, POI overlays, voice-assisted search. Format-validated only: a production-exporter synthetic fixture is served by the real stack and rendered (docs/E2E_VALIDATION.md section V). **No real venue reconstruction has been viewed** (none exists: M9). Until 2026-09-28 the desktop viewer drew nothing (`gpuAcceleratedSort`; fixed). | Playwright: pixel, camera and overlay checks on the fixture; full-stack browser check |
| M11 | Geometry & segmentation | Done: Open3D statistical/radius/semantic-aware cleanup, RANSAC plane fitting, SegFormer segmentation | Unit tests; cleanup benchmark harness |
| M12 | Object detection & search | Done: Grounding DINO (stock checkpoint) + CLIP embeddings, pgvector HNSW index, search API with lexical fallback, search_query log (docs/search.md) | Unit tests; integration tests (venue isolation, ranking, unavailable-model fallback) |
| M13 | Navmesh & routing | Navmesh baking and routing are implemented. Validated on synthetic geometry fixtures only; **not validated on a real reconstructed venue**. The Recast input is a multi-level surface model of the cleaned, labelled splat (steps, ramps, walls, furniture); routes are string-pulled through the Detour portals; clearance is measured on the obstacle geometry. Baking uses the real Recast/Detour library: chaya-navmesh, built against recastnavigation 1.6.0 pinned by SHA-256. It publishes a NAVMESH + NAVMESH_MANIFEST with full provenance, and STANDARD/STEP_FREE graphs from the Detour polygon links. The Dijkstra routing API routes only on navmesh-backed graphs (`NAVMESH_NOT_READY` otherwise), with multi-floor transitions and dynamic obstacle exclusion (docs/navigation.md). No real venue has been baked: the stages before baking need CUDA and Open3D. | Real-tool tests: the fixture mesh, a synthetic L-shaped splat and a synthetic venue (wall, two doorways, furniture, column, step, 1:14 ramp; re-scan splice) are baked and routed by Recast/Detour, and every failure state is produced (Windows and Linux; CI builds the tool and fails if these tests skip). The API ingests the real tool output and routes on it. Integration tests cover route exists/unavailable, obstacle, accessibility, multi-floor and cross-venue. |
| M14 | Capture guidance | Path planning and coverage metric done (docs/route-planning.md, synthetic-fixture tests + benchmark). Live capture-quality and coverage HUD done: client-side blur/motion/exposure/feature/spacing signals, manual position marking, coverage/planned-path replayed live through the same planner (docs/capture-hud.md). Still to do: bounds estimation from an automatic recon lap, on-device SLAM-lite/AR positioning, field validation against real reconstructions | Field capture |
| M15 | AR navigation | Android WebXR, iOS ARKit, fiducial anchors, VIO interpolation, path-data API | On-device tests |
| M16 | Incremental rescan | Implemented; validated on synthetic clouds and in B5 only; **no real venue has been re-scanned and merged**. Region-scoped reconstruction and a versioned merge. Alignment is in the canonical frame: direct for surveyed re-scans, otherwise a robust similarity. Physical quality gates, with an explicit ALIGNMENT_REJECTED status. The splice replaces a volume, measures the seam, and records exactly what was replaced. Downstream changes apply only when the version finalizes. Each finalized version records its run and coordinate frame and pins the exact artifacts it is made of (its own; only detections are inherited from its parent, never navigation); POIs, navigation graphs and AR anchors name their version; numbers are unique per floor; the viewer, POI list, search and routing can be scoped to one version (docs/rescan.md, "VERSIONING"). No `floor.current_scan_version_id` yet | Worker math, alignment, splice and stage tests on synthetic clouds (CI); real FPFH in the Open3D image; control-plane tests: rejection, parent untouched, lineage, deferred POIs; version tests: database guarantees, and three versions end to end over HTTP (lineage, artifact ownership, viewer switching, per-version POIs and routes); B5 on a real SfM cloud with simulated misalignment |
| M17 | Operations dashboard | Freshness, job state, coverage gaps, search analytics | Dashboard tests against real data |
| M18 | CI/CD | GitHub Actions, GHCR images, independent deploys | Green pipeline |

## Validation levels (reconstruction → digital twin chain)

Each level is a separate claim. A higher level is never implied by a lower one.

- **Code implemented:** the stage exists and does real work when its dependencies are present.
- **Fixture validated:** automated tests exercise the real code on committed or downloaded fixtures, synthetic or
  public.
- **Real venue executed:** it ran on a real indoor venue capture through this pipeline.
- **Physical device validated:** it ran on the target hardware: a phone capture or AR device, or a CUDA GPU worker.

Last checked 2026-10-08 at `20ef642` (docs/E2E_VALIDATION.md §R). **No row is real-venue executed, because no real
venue capture exists.** "Real media" means the public outdoor Sceaux photographs (E2E §R and §0): real camera input,
but not a venue, and with no measurements.

| Stage | Code implemented | Fixture validated | Real venue executed | Physical device validated |
|---|---|---|---|---|
| Capture (web capture UI, HUD) | Yes (M5, M14) | Yes (unit/Playwright) | No | No (never field-captured on a phone) |
| Media ingestion (upload, Tika/ClamAV) | Yes (M5) | Yes (real MinIO) | No | n/a |
| INPUT_VALIDATION, FFMPEG_PREPROCESS, FRAME_QUALITY_FILTER | Yes | Yes | No; **real media: yes** (§R, HEAD) | n/a (CPU) |
| PRIVACY_PREPROCESS (+ masks) | Yes | Yes | No; real media: yes. It over-masks (CV-6). | n/a (CPU) |
| Camera metadata / intrinsics, distortion | Yes | Yes (synthetic cameras) | No; real media: SfM self-calibration only, no device intrinsics | No |
| POSE_ESTIMATION (SfM) | Yes; COLMAP only, GLOMAP never in a run image | gpu-marked tests skip in CI | No; **real media: yes**, CPU COLMAP, 11/11 registered, 0.398 px | No GPU run |
| Metric calibration (`coordinate-frames`) | Yes | Yes (synthetic-calibration fixture) | **No**: no measured references exist | n/a |
| Gravity alignment | Yes | Yes (synthetic planes) | No | No |
| SPLAT_RECONSTRUCTION (gsplat) | Yes | CPU training mechanics with a test renderer only | **No (blocked: no CUDA)** | **No** |
| GEOMETRIC_CLEANUP, PLANE_FITTING | Yes | Yes (unit tests; B2 on real geometry) | No | No |
| SEMANTIC_SEGMENTATION, SEMANTIC_INDEXING | Yes | Yes (unit tests; B3 on COCO crops) | No | No |
| NAVIGATION_BAKING (Recast) | Yes (recastnavigation 1.6.0) | Yes (synthetic venue fixtures, real tool) | No | n/a (CPU) |
| `.ksplat` export → pinned viewer | Yes | Yes (viewer-scene format fixture, pixel-checked) | No | No |
| Semantic search API | Yes | Yes (integration tests, B3) | No | n/a |
| Route generation | Yes | Yes (on real Recast output of fixtures) | No | n/a |
| AR navigation (Android WebXR, iOS ARKit) | Yes (M15) | Unit tests; the iOS app builds in CI | No | **No**: never run on a phone |

An end-to-end regression test from a real venue run is deliberately **not** added yet. It may be cut from a compact
fixture only after the first successful real-venue run (E2E §R.4).

CI (a minimal lint/test workflow) is added with M2/M4 as each component appears; M18 covers image publishing.

Order note: M14 may move earlier if capture-quality feedback blocks reconstruction quality work.
