# CHAYA 02 — Project Plan

Each milestone is independently testable and committable. "Done" means its tests pass and nothing in it pretends to do work that isn't implemented.

| # | Milestone | Scope | Verified by |
|---|-----------|-------|-------------|
| M0 | Repo foundation | Docs, `.env.example`, `.gitignore`, layout (this commit) | Files exist; no secrets |
| M1 | Local infrastructure, backend/web skeletons | Compose file, health/version endpoints, status page (foundation commit) | Compose config valid; web lint/typecheck/test/build; `mvn verify` |
| M2 | Backend hardening | Redis/MinIO health, problem+json errors, Maven wrapper | `./mvnw verify` |
| M3 | Identity & tenancy | Keycloak JWT resource server, org/venue/membership tables, venue-scoped authz, audit_event | Integration tests: cross-tenant access returns 404 |
| M4 | Frontend skeleton | Next.js, Tailwind, OIDC login, venue list from API | Unit + Playwright login test |
| M5 | Storage & capture sessions | Done: resumable chunked upload through the backend into MinIO, Tika/ClamAV validation, capture lifecycle, capture UI (see docs/capture-ingestion.md) | Tests against real MinIO |
| M6 | Job engine | Done: pipeline runs, leases/heartbeats, retry, time box, worker API, stage records (docs/pipeline.md) | Java control-plane tests; Python orchestration + integration tests |
| M7 | Media filter worker | Done: input validation, FFmpeg extraction, quality filter, privacy preprocessing | Worker tests on real media |
| M8 | Pose estimation worker | Done: GLOMAP with COLMAP fallback; poses.json; structured failure when tools are missing | Unit tests; real-dataset gpu-marked tests |
| M9 | Splat training worker | Implemented; training mechanics validated on CPU only; **gsplat training never executed and no real venue reconstructed**. gsplat training (Adam, L1+D-SSIM) with adaptive density control, a time-boxed loop reporting COMPLETED or PARTIAL, resumable checkpoints, one colour convention to the viewer, `.ksplat` export, artifact manifest (docs/pipeline.md, "Splat training") | Colour-chain fixture; CPU training-mechanics tests (`tests/splat`, CI fails on a skip); gpu-marked tests (skipped: no GPU) |
| M10 | Splat viewer | Done: Three.js + GaussianSplats3D viewer, real reconstruction selection, POI overlays, voice-assisted search | Playwright smoke |
| M11 | Geometry & segmentation | Done: Open3D statistical/radius/semantic-aware cleanup, RANSAC plane fitting, SegFormer segmentation | Unit tests; cleanup benchmark harness |
| M12 | Object detection & search | Done: Grounding DINO (stock checkpoint) + CLIP embeddings, pgvector HNSW index, search API with lexical fallback, search_query log (docs/search.md) | Unit tests; integration tests (venue isolation, ranking, unavailable-model fallback) |
| M13 | Navmesh & routing | Navmesh baking and routing are implemented. Validated on a fixture mesh only; **not validated on a real reconstructed venue**. Baking uses the real Recast/Detour library: chaya-navmesh, built against recastnavigation 1.6.0 pinned by SHA-256. It publishes a NAVMESH + NAVMESH_MANIFEST with full provenance, and STANDARD/STEP_FREE graphs from the Detour polygon links. The Dijkstra routing API routes only on navmesh-backed graphs (`NAVMESH_NOT_READY` otherwise), with multi-floor transitions and dynamic obstacle exclusion (docs/navigation.md). No real venue has been baked: the stages before baking need CUDA and Open3D. | Real-tool tests: the fixture mesh and a synthetic cleaned splat are baked and routed by Recast/Detour, and every failure state is produced (Windows and Linux; CI builds the tool and fails if these tests skip). The API ingests the real tool output and routes on it. Integration tests cover route exists/unavailable, obstacle, accessibility, multi-floor and cross-venue. |
| M14 | Capture guidance | Path planning and coverage metric done (docs/route-planning.md, synthetic-fixture tests + benchmark). Live capture-quality and coverage HUD done: client-side blur/motion/exposure/feature/spacing signals, manual position marking, coverage/planned-path replayed live through the same planner (docs/capture-hud.md). Still to do: bounds estimation from an automatic recon lap, on-device SLAM-lite/AR positioning, field validation against real reconstructions | Field capture |
| M15 | AR navigation | Android WebXR, iOS ARKit, fiducial anchors, VIO interpolation, path-data API | On-device tests |
| M16 | Incremental rescan | Implemented; validated on synthetic clouds and in B5 only; **no real venue has been re-scanned and merged**. Region-scoped reconstruction and a versioned merge. Alignment is in the canonical frame: direct for surveyed re-scans, otherwise a robust similarity. Physical quality gates, with an explicit ALIGNMENT_REJECTED status. The splice replaces a volume, measures the seam, and records exactly what was replaced. Downstream changes apply only when the version finalizes (docs/rescan.md) | Worker math, alignment, splice and stage tests on synthetic clouds (CI); real FPFH in the Open3D image; control-plane tests: rejection, parent untouched, lineage, deferred POIs; B5 on a real SfM cloud with simulated misalignment |
| M17 | Operations dashboard | Freshness, job state, coverage gaps, search analytics | Dashboard tests against real data |
| M18 | CI/CD | GitHub Actions, GHCR images, independent deploys | Green pipeline |

CI (a minimal lint/test workflow) is added with M2/M4 as each component appears; M18 covers image publishing.

Order note: M14 may move earlier if capture-quality feedback blocks reconstruction quality work.
