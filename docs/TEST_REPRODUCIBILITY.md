# Test reproducibility

What a clean checkout can run, what CI executes, and what nothing here executes. Written 2026-09-30 in response to
[ADVERSARIAL_REVIEW_2026-09-29.md](ADVERSARIAL_REVIEW_2026-09-29.md) C-1/C-2/C-5. Update it when a row changes.

## Clean-checkout results (2026-09-30)

Run from a fresh `git clone -c core.autocrlf=false` of the working tree (no ignored files present: no `.venv`,
`node_modules`, build output or local fixtures), inside Linux containers that repeat the workflow steps:

| Suite | Command (as in the workflow) | Result |
|---|---|---|
| Fixture presence | `bash scripts/ci/check-fixtures.sh` | 12 required fixtures committed, none ignored/untracked, checksums match |
| Web (`frontend.yml` steps in `mcr.microsoft.com/playwright:v1.63.0-noble`, Node 24; CI uses Node 22) | `npm ci && npm run lint && npm test && npm run typecheck && npm run build && CI=1 npm run test:e2e` | 110/110 unit, 12/12 Playwright (incl. `ksplat-viewer.spec.ts` rendering the committed `.ksplat`) |
| Worker (`python.yml`, `python:3.12`) | `ruff check .`; `pytest -m "not gpu and not integration"`; navmesh and splat guards | 255 passed, 1 allowed skip (empty parameter set); navmesh 21 passed; splat 20 passed |
| Vision (`python.yml`) | `ruff check .`; `pytest` | 8 passed |
| Benchmarks framework (not in a workflow) | `pytest benchmarks/tests` | 7 passed |
| API (`backend.yml`, `maven:3.9-eclipse-temurin-21`) | `mvn -B -ntp verify` | 364 run, 0 failures, 4 skipped (opt-in ClamAV/Keycloak live); jar ships jackson-databind 2.21.7 — **but see below** |

The API run used a MinIO image **cached on the workstation**. The backend and stack-smoke suites are therefore **not**
reproducible from a clean checkout on a machine without that cache; see "Unresolved" below.

## What was wrong

- **Fixtures only on one workstation (C-2).** `*.ksplat`/`*.ply` are ignored repository-wide, so the four fixtures in
  `packages/contracts/fixtures/{ksplat,viewer-scene}` were never committed. They are now re-included by exact path in
  `.gitignore` and committed; see [packages/contracts/fixtures/README.md](../packages/contracts/fixtures/README.md).
  Regenerated from scratch with their generators, they were byte-identical to the local copies.
  `scripts/ci/check-fixtures.sh` (run first in `frontend.yml` and `python.yml`) fails on any fixture that is present
  but ignored or untracked.
- **A test measured below its metric's resolution (C-5).** `tests/rescan_scene.rotation_error_deg` used
  `arccos((tr R - 1) / 2)`, which cannot resolve angles below ~1e-6 degrees in float64; CI's 1.7e-6/2.1e-6 were that
  noise. It now uses `atan2(sin, cos)` (~1e-14 degrees on the same estimates); the 1e-6 degree bound is unchanged.
- **cwd-relative fixture path.** `apps/web/e2e/viewer-fixture.ts` resolved the fixture from `process.cwd()`; it is now
  relative to the file.

## Not executed by CI (and not claimed)

| Tests | Needs | Where they can run |
|---|---|---|
| `services/reconstruction/tests/gpu/*` (`-m gpu`) | CUDA + gsplat, COLMAP/GLOMAP, Open3D, a folder of real photos (`CHAYA_TEST_IMAGE_SEQUENCE`) | A GPU host only. CPU runs of the splat trainer (`tests/splat`, CPU renderer) test the training loop's logic, **not** gsplat/CUDA. |
| `tests/integration/test_stack_e2e.py` | The running stack | `reconstruction-smoke` / `stack-smoke` (blocked, see below) |
| `tests/integration/test_minio_storage.py` | `CHAYA_IT_S3_*` pointing at a MinIO | No workflow sets these; manual only |
| `ClamAvLiveTest`, `KeycloakLiveTest` (API) | `CLAMAV_LIVE_PORT`, `KEYCLOAK_LIVE_*` | Manual only |
| iOS app target, `App/ChayaARTests` | macOS + Xcode | `ios.yml` `app` job (never green yet; see below) |
| ARKit / WebXR camera tracking | A physical iPhone / Android device | Manual: docs/ar-ios-validation.md, docs/ar-android-validation.md |

`python.yml` runs the gpu/integration selection separately and writes each test with its skip reason to the job
summary, so a green job shows exactly what it did not execute. The CPU selection fails the job on any skip.

## Unresolved

- **MinIO image (backend, reconstruction-smoke).** `quay.io/minio/*` and `docker.io/minio/*` now refuse anonymous pulls
  (`401`/`unauthorized`), including the digest pinned in the compose files. `AbstractIntegrationTest` still uses
  `quay.io/minio/minio:latest`; local runs pass only because the image is cached on the workstation. Choosing a
  replacement source (a mirror in this project's GHCR, a community build, or authenticated pulls) is an open decision.
  The same pull failure affects `infra/docker`, `infra/deploy` and `scripts/backup` on any host without a cached image.
- **iOS `app` job.** `project.yml` now pins `projectFormat: xcode15_0` for the macos-14 runner's Xcode 15.4; this has not
  run on macOS yet.
- **security `maven` job.** Dependency-Check is bumped to 12.2.2 (fixes the NVD `URL` column crash); not yet run in CI.
- **docker `api` image.** `jackson-bom` overridden to 2.21.7 (CVE-2026-68497 fixed in 2.21.6); the Trivy gate has not
  re-run.
