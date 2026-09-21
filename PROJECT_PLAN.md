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
| M8 | Pose estimation worker | GLOMAP with COLMAP fallback; pose manifest; failure handling | Sample dataset; failure-path test |
| M9 | Splat training worker | gsplat/Nerfstudio → `.ksplat`; asset registration; PARTIAL vs COMPLETE logic | GPU host run; completeness tests |
| M10 | Splat viewer | GaussianSplats3D viewer in web app, loads real scene assets | Manual + Playwright smoke |
| M11 | Geometry & segmentation | Open3D cleanup, SegFormer/Mask2Former | Output validation tests |
| M12 | Object detection & search | Grounding DINO + CLIP, pgvector index, search API, search log | Recall check on labelled scene |
| M13 | Navmesh & routing | Recast worker, Detour/WASM client, accessibility profiles | Route tests on known geometry |
| M14 | Capture guidance | Path planning and coverage metric done (docs/route-planning.md, synthetic-fixture tests + benchmark). Still to do: bounds estimation from the lap, AR breadcrumb UI, field validation against real reconstructions | Field capture |
| M15 | AR navigation | Android WebXR, iOS ARKit, fiducial anchors, VIO interpolation, path-data API | On-device tests |
| M16 | Incremental rescan | Region-scoped reconstruction and versioned merge | Rescan test preserves prior version |
| M17 | Operations dashboard | Freshness, job state, coverage gaps, search analytics | Dashboard tests against real data |
| M18 | CI/CD | GitHub Actions, GHCR images, independent deploys | Green pipeline |

CI (a minimal lint/test workflow) is added with M2/M4 as each component appears; M18 covers image publishing.

Order note: M14 may move earlier if capture-quality feedback blocks reconstruction quality work.
