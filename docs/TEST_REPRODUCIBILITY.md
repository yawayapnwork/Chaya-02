# Test reproducibility

What a clean checkout can run, what CI executes, and what nothing here executes. Written 2026-09-30 in response to
[ADVERSARIAL_REVIEW_2026-09-29.md](ADVERSARIAL_REVIEW_2026-09-29.md) C-1/C-2/C-5. Update it when a row changes. The
current state is first; the dated sections below it are records and are kept as written.

## Current state (2026-10-12, `40beef5` plus the documentation audit)

### Local results

Run on 2026-10-12 on a Windows 11 workstation (Docker Desktop, 18 cores, no CUDA device, no Mac, no phone), from the
working tree. "Kind" says what the suite exercises for real.

| Suite | Command | Result | Kind |
|---|---|---|---|
| Fixture presence | `bash scripts/ci/check-fixtures.sh` | 12 fixtures committed, checksums match | — |
| Web lint, typecheck | `cd apps/web && npm run lint && npm run typecheck` | clean, clean | static |
| Web unit | `npm test` | **215 passed**, 0 skipped | unit; committed `.ksplat` fixture through the pinned viewer library |
| Web build | `npm run build` | built | — |
| Web e2e | `CI=1 npx playwright test` (Chromium, software WebGL, Node 24) | **18 passed**; 2 `ops.spec.ts` tests flaky under parallel load (passed on retry); `ops.spec.ts` alone, `--workers=1 --retries=0`: 5 passed | real browser and viewer library; **API and OIDC provider mocked** (`page.route`) |
| API | `mvn -B -ntp verify` in `maven:3.9-eclipse-temurin-21` (Testcontainers: pgvector pg17, GHCR MinIO mirror) | **531 run, 0 failures, 0 errors, 4 skipped** (`ClamAvLiveTest` ×2, `KeycloakLiveTest` ×2, opt-in), 9.5 min | real Postgres + pgvector and MinIO; Keycloak via `TestJwt`; ClamAV stubbed |
| Worker lint | `cd services/reconstruction && .venv/Scripts/python -m ruff check .` | clean | static |
| Worker CPU suite | `.venv/Scripts/python -m pytest -m "not gpu and not integration" -rs` (Python 3.12, CPU torch) | **566 passed, 2 skipped**, 17 deselected (3 min) | unit, orchestration, smoke, navmesh (real Recast/Detour, MinGW build), splat mechanics on CPU. Skipped: `tests/ml` (no `transformers`), one empty parameter set. |
| Benchmarks framework | `.venv/Scripts/python -m pytest benchmarks/tests` | 10 passed | unit |
| iOS core | `docker run swift:5.10-jammy … swift test` in `apps/ios-ar` | **55 passed** | `ChayaARCore` only; ARKit/UIKit not compiled on Linux |
| Deploy script | `bash scripts/deploy/test-deploy.sh` | 26 passed | `deploy.sh` control flow with stubbed docker/curl |
| Shell | `shellcheck -S warning` on `infra/deploy`, `scripts/{backup,deploy,ci}` (LF copies, as committed) | clean | static |
| Compose | `docker compose --env-file <filled .env.example> … config -q`: dev; dev + `pipeline` profile; dev + smoke overlay; dev + smoke + e2e overlays; monitoring; `infra/deploy` with a filled `.env.production.example` | all valid. With the unfilled template: "required variable … is missing a value", by design | config |
| Stack smoke | `SMOKE_API_PORT=18080 SMOKE_KEYCLOAK_PORT=18081 SMOKE_MINIO_PORT=19000 PYTHON=<worker venv python> bash scripts/ci/stack-smoke.sh` | **passed** (exit 0). The CPU stages succeeded; the run stopped with `DEPENDENCY_UNAVAILABLE` at POSE_ESTIMATION (attempt 1, then the retry, attempt 2). Restore drill: `RESULT: PASS`, 19/19 referenced objects verified, the API booted on the restored data. Nothing left running. | real API image, Postgres, MinIO, ClamAV, Keycloak, worker code |

**Not run on 2026-10-12, and not claimed:** the vision service tests (8, CLIP mocked; last run 2026-10-09), the
`ios` app and ARKit layer (needs macOS/Xcode), every `gpu`-marked test (needs CUDA, COLMAP, Open3D, real photos),
`tests/integration/test_minio_storage.py` (needs `CHAYA_IT_S3_*`), `tests/ml` (needs the ML evaluation image), the
full E2E harness (`scripts/e2e/e2e_validate.py`), image builds and Trivy, OWASP Dependency-Check, `scripts/backup/test-restore-drill.sh`.

### CI status on `main`

From `gh run list` / `gh run view --log-failed` on 2026-10-12. CI is **not green**; a passing local suite does not mean the
workflow passes.

| Workflow | Latest on `main` | Cause | Since |
|---|---|---|---|
| `backend` | pass | — | — |
| `frontend` | pass | — | — |
| `ops` | pass | — | added `bd6c69c` |
| `python` | **fail** | The CPU suite may not skip, and two tests skip on the runner: `tests/ml/test_training_smoke.py` (`transformers` is not installed by the workflow) and the viewer check in `tests/smoke/test_splat_artifact_smoke.py` (`apps/web` dependencies are not installed). The 565 others pass. | `7d7b177` (2026-10-10) |
| `reconstruction-smoke` | **fail** | `stack-smoke` passes its test (1 passed) and the restore drill (`RESULT: PASS`, 19/19 objects), then the script's cleanup cannot delete object files the drill's containers wrote (`rm: … Permission denied`) and exits 1. Run 38241830560. | `bd6c69c` (2026-10-11) |
| `docker` | **fail** | Trivy: `next` 16.3.6, CVE-2026-94483 (HIGH; fixed in 16.3.8) in the web image | — |
| `security` | **fail** | the web `npm audit` (same `next` advisory); Dependency-Check hits NVD request failures (no `NVD_API_KEY`) | — |
| `ios` | **fail** | `ReferenceImageTests.testAReferenceImageIsTheTargetImageAtItsPrintedWidthNamedWithTheAnchorId`: the fixture anchor has no `scanVersionId`/`poseId`, so `MarkerRegistry` correctly drops it (BASELINE_AUDIT_2026-10-09 F5). The app builds. | `fb7e65f` (2026-10-05) |
| `deploy` | skipped | needs a green `docker`; it has never succeeded | — |

None of these is fixed by the documentation audit, which changes no production behaviour.

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

## Unresolved (as of 2026-09-30)

Later status, added 2026-10-12 without changing the record below:

- MinIO: **resolved.** The images are mirrored to `ghcr.io/yawayapnwork/mirror/{minio,mc}` and pinned by digest in
  `AbstractIntegrationTest`, the compose files and `scripts/backup/common.sh` (infra/mirror/README.md); the mirror
  answers anonymous pulls (checked 2026-10-09), and the 2026-10-12 `mvn verify` ran on that pinned image (from the
  local Docker cache).
- iOS `app` job: it went green on 2026-09-30 (`f99b8a6`) and has been red since `fb7e65f` (see "CI status" above).
- security `maven` job and docker `api` image: superseded by the current CI status above.

The record:

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
