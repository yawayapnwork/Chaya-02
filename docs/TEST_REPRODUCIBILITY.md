# Test reproducibility

What a clean checkout can run, what CI executes, and what nothing here executes. Written 2026-09-30 in response to
[ADVERSARIAL_REVIEW_2026-09-29.md](ADVERSARIAL_REVIEW_2026-09-29.md) C-1/C-2/C-5. Update it when a row changes. The
current state is first; the dated sections below it are records and are kept as written.

## Current state (2026-10-24, CI restoration on top of `42fd240`)

Run on 2026-10-24 on the Windows 11 workstation: Docker Desktop 29.6.2 with a 7.5 GiB VM, Intel graphics only (no CUDA),
no Mac, no phone. The code is the working tree of the commit `fix(ci): restore reproducible checks across project
services`. **None of this has run on GitHub Actions yet.** The commit is not pushed, so every CI statement below is
either a recorded run (with its id) or a local reproduction of the workflow's steps.

### Recorded CI failures: root cause and disposition

| Workflow (last red run) | Recorded failure | Class | Root cause | Disposition | Verified locally |
|---|---|---|---|---|---|
| `python` → `open3d` (39817750803, `e96d477`) | `test_geometric_cleanup_and_plane_fitting_run_on_a_real_synthetic_room`: `assert 1 >= 2` | **source defect** | GEOMETRIC_CLEANUP's radius filter kept points with at least 8 neighbours within 3 × the median spacing. A randomly sampled surface has about 1 + 9 ln 2 ≈ 7.2 there, so the filter removed about two thirds of a floor or wall, and PLANE_FITTING then lost them. A lattice has about 28 neighbours there, which is why the lattice benchmarks never saw it (FINAL_AUDIT P1-14). | The threshold is now 0.35 × the cloud's own median neighbour count, clamped to [3, 8] (`geometry_cleanup.radius_min_neighbors`, `CLEANUP_RADIUS_NEIGHBOR_SHARE`). The cap keeps it never stricter than before. The test is unchanged. A unit test that needs no Open3D was added (`tests/unit/test_radius_threshold.py`). | Open3D 0.19.0 image (`chaya-bench-open3d`, Python 3.12.14), `pytest -m open3d tests/gpu`: **11 passed** (1 failed before). B2c synthetic benchmark (5 seeds, ground truth): **all 119 non-timing metrics identical** to the 2026-10-11 record (`benchmarks/results/b2_geometry_cleanup_synthetic/2026-10-24T134606Z.json`). A sweep showed a share of 0.30 would lower B2c noise recall from 0.848 to 0.820, which is why 0.35 was chosen. B2 on the real splat was **not** re-run: its input is not on this host. |
| `docker` → `image (worker)` (39817750800) | Trivy: `libexpat1` 2.5.0-1+deb12u4, CVE-2025-59375 and CVE-2026-77214 (HIGH, fixed in deb12u5) | **invalid CI assumption** | The image is built with `cache-from: type=gha` (mode=max). Its `apt-get install` layer was replayed from cache, so packages from before the fix kept shipping. A fresh build of the same Dockerfile today already had deb12u5. The worker and vision images never ran `apt-get upgrade`. Inferred from the log: the full job log needs repository-admin rights, but `--log-failed` shows the installed version. | Every runtime stage upgrades its OS packages (`apt-get upgrade` / `apk upgrade`) behind `ARG OS_PACKAGES_REFRESH`. `docker.yml` sets it to the UTC date, so that layer is rebuilt at least daily. Scanner, severity and `--ignore-unfixed` are unchanged. | All four images built from the tree with the workflow's build argument: non-root (api, worker, vision 10001; web `node`), HEALTHCHECK present, Trivy 0.70.0 `--severity CRITICAL,HIGH --ignore-unfixed --scanners vuln,secret`: **exit 0 for api, web, worker and vision**. The worker has `libexpat1 2.5.0-1+deb12u5`. |
| `security` → `maven` (39817751013) | Dependency-Check: `tika-core` 3.2.2, CVE-2026-66755 (CVSS 7.5) | **dependency** | Vulnerable version pinned in `services/api/pom.xml` | `tika-core` 3.3.3 (newest 3.x; the fix is in 3.3.2). The threshold is unchanged. | `mvn -B -ntp verify`: **668 run, 0 failures, 0 errors, 4 skipped** (opt-in `ClamAvLiveTest`, `KeycloakLiveTest`). The API image ships `tika-core-3.3.3.jar`. Dependency-Check: **NOT RUN to completion**: the local run (no NVD key, building the database from nothing) was stopped when the workstation ran out of memory. Re-run it in CI `security` → `maven`; until then the CVE is closed only by version (3.3.3 ≥ the fixed 3.3.2). |
| `security` → `web` (not yet red in CI: last run 2026-10-22 passed) | `npm audit --omit=dev --audit-level=high` today: `next` 16.3.8 is **critical** (GHSA-2464-fhr4-w9xw RCE via image optimisation; GHSA-673q-mjm6-r5xr; GHSA-p3m5-59mp-jv5r) | **dependency** (advisories published after the last run) | `next` ≤ 16.4.0 | `next` 16.4.3 (exact pin; `package-lock.json`) | `npm audit`: **0 vulnerabilities**. Lint, typecheck, 389 unit tests, build and Playwright pass on 16.4.3 (below). Trivy on the rebuilt web image: exit 0. |
| `acceptance` → `c-cross-service` (40073568175) | 40/40 service tests pass; browser A6/B6 fail: one console error each | **source defect** | `next.config.ts` built the report-only CSP at **build** time, and no image build passes `CHAYA_PUBLIC_OIDC_ISSUER`. So `frame-src` lacked the identity provider, and every silent sign-in logged `Framing 'http://localhost:18081/' violates … "frame-src 'self'"` (recorded in `docs/e2e-evidence/2026-10-19-eth3d-office/acceptance/acceptance-c-viewer.json`). | Both CSP headers are set per request by `apps/web/proxy.ts` from the running container's `CHAYA_PUBLIC_*` (`lib/security-headers.ts`, 4 unit tests). The directives are unchanged; the policy stays report-only. | Production standalone server built with **no** `CHAYA_PUBLIC_*`, then started with them: `frame-src 'self' http://localhost:18081`; `/auth/callback` keeps `frame-ancestors 'self'`. Full acceptance run: **NOT RUN**: stopped while building its images when the workstation ran out of memory (the stack never started). So A6/B6 are **not** verified end to end. Re-run in CI `acceptance`, or locally with more free memory. |
| `ios` → `app` (39204149235, `e47948b`) | `ReferenceImageTests.testMissingAndMalformedImagesAreRejected`: "the fixture truncated halfway" did not throw (`AppLevelTests.swift:173`) | **source defect** | `ReferenceImages.make` relied on `CGImageSourceGetStatusAtIndex == .statusComplete`. For a source made from complete `Data`, ImageIO on the iOS 17.5 Simulator reports a PNG cut in half as complete. | The PNG is checked on the file itself first: signature, every chunk's length and CRC-32, IEND exactly at the end (`ChayaARCore/PNGIntegrity.swift`; pure Swift, so it is tested on Linux against the server's real target-image fixture). | `swift test` (`swift:5.10-jammy`): **86 passed**, 3 of them new. `swiftc -parse` on the ARKit file. **The app-level XCTest was NOT run: it needs macOS with Xcode** (only the `ios` workflow can run it). |
| `reconstruction-smoke` (documented red 2026-10-12; green in CI since) | — | **invalid CI assumption** (silent skips) | `worker-smoke` did not install torch, so `tests/smoke/test_splat_artifact_smoke.py` skipped as a whole module and the job still passed. Skips were never checked. The stack smoke's end-to-end test could skip the same way. | `worker-smoke` installs CPU torch 2.7.1 and the viewer library and fails on any skip. `image-smoke` allows only "torch is not installed": the production CPU image has no torch by design, and that module runs in `worker-smoke` and `python`. `stack-smoke.sh` fails on a skip of its end-to-end test. Test tools are pinned (`pytest==8.4.1`, `scikit-image==0.26.0`). | Worker smoke (host venv): **17 passed, 0 skipped**. Image smoke in the new worker image: **13 passed, 1 expected skip**; liveness is unhealthy before a control-plane call. Stack smoke: **passed**. End-to-end test 1/1, run stopped at POSE_ESTIMATION as designed, restore drill `RESULT: PASS`, API booted on restored data. |
| `deploy` (0 successes in 72 runs) | Every visible failure: `CI did not pass for <sha>` | **configuration error** (gate) plus **environment limitation** | (1) The gate accepted a gated workflow with **no** run for the commit as "path-filtered", and treated `skipped` as passed. A commit touching only `apps/web` could therefore deploy while `python` was red on `main`. (2) Red CI has blocked every deploy. (3) Only a `staging` Environment exists on GitHub; whether `DEPLOY_HOST`/`DEPLOY_SSH_KEY` secrets exist cannot be seen without admin rights, and no host has ever been deployed. | Every gated workflow now runs on **every push to `main`** (PR path filters kept). The gate requires `success` for `frontend backend python security reconstruction-smoke acceptance docker ops` on the exact commit: absent (after 3 min), skipped and cancelled all fail, and the newest attempt decides. `scripts/ci/test-require-green.sh` (10 cases) runs in `ops`. Branch-protection strategy: DEPLOYMENT.md "Branch protection and path filters". | `test-require-green.sh`: **10 passed**. **The deploy itself remains BLOCKED**: it needs a host, DNS and the Environment secrets (DEPLOYMENT.md §3). |
| `gpu` (0 runs) | — | **environment limitation** | No self-hosted runner labelled `gpu`. The test step also ignored pytest's exit status. | A GitHub-hosted `requirement` job states in the run summary that GPU acceptance is BLOCKED until `gpu-tests` runs. `gpu-tests` now fails on pytest's own status and on a missing JUnit file, not only on the summary's verdict. | **NOT RUN**: no CUDA host. Nothing here executes CUDA. |

Also fixed:
- **Flaky browser tests hidden by retries.** `playwright.config.ts` had `retries: 2`, so an intermittent failure passed as "flaky". Now there is one retry, for a trace only, and `failOnFlakyTests` in CI. That exposed a real race: the suite ran on `next dev`, whose on-demand compilation under the parallel software-WebGL specs sometimes exceeded the silent sign-in's 5 s timeout (`lib/auth.ts`), so a reload showed "Sign in" (`ui-audit.spec.ts:220`). The e2e server is now the **production build** (`npm run build && npm run start`; `PLAYWRIGHT_DEV=1` for the dev server). Production also exposed a dev-only assumption in the tab-order test: Tab past the last control lands on `<body>`. The test now ends the tab order there instead of counting `<body>` as a control, and the post-sign-in waits use the file's 15 s session bound. The browser suite still mocks the API and OIDC provider (`page.route`); the full-stack browser check is the acceptance suite's.
- **`scripts/ci/pytest-summary.py`** matched skip reasons against `message="collection skipped"` only, which hid the reason of a module-level skip. It now uses the message and the text, in every workflow that reads it.
- **`pip-audit`** in `security` is pinned (2.10.1). Every action was already pinned by commit SHA, and actionlint 1.7.7 passes on all 11 workflows (with `.github/actionlint.yaml`).

### Local results (2026-10-24)

| Suite | Command | Result |
|---|---|---|
| Fixtures | `bash scripts/ci/check-fixtures.sh` | 13 committed, checksums match |
| Web lint, typecheck, unit, build | `cd apps/web && npm run lint && npm run typecheck && npm test && npm run build` | clean, clean, **389 passed**, built (Next.js 16.4.3) |
| Web browser (mocked API/OIDC) | `cd apps/web && CI=1 npx playwright test` (production server, 2 workers, `failOnFlakyTests`) | **65 passed, 0 flaky, three consecutive runs** (before the fixes: 1 flaky on `next dev`, then 1 failure on the production build) |
| Web dependencies | `npm audit --omit=dev --audit-level=high` | 0 vulnerabilities |
| API | `mvn -B -ntp verify` (`maven:3.9-eclipse-temurin-21`, Testcontainers pgvector pg17 + GHCR MinIO mirror; read-only snapshot copied to `/w`) | **668 run, 0 failures, 0 errors, 4 opt-in skips**, BUILD SUCCESS, 14 min 8 s |
| Worker lint | `cd services/reconstruction && .venv/Scripts/python -m ruff check .` | clean |
| Worker CPU suite (CI selection) | `pytest -m "not gpu and not open3d and not integration and not acceptance"` (host venv, Python 3.12.10, torch 2.14 CPU; MinGW `chaya-navmesh`) | **735 passed, 2 skipped**: the allowlisted empty parameter set, and `tests/ml` (no `transformers` in the host venv; the required-suite summary flags it, as CI would) |
| Worker `tests/ml` | in `chaya-ml-eval` (transformers 4.57.1), `HF_HUB_OFFLINE=1` | 6 passed |
| Worker Open3D tests | `pytest -m open3d tests/gpu` in Open3D 0.19.0 | **11 passed** |
| Worker smoke | `pytest -m smoke tests/smoke` + `pytest-summary.py --mode required` | 17 passed, no skips |
| Worker image smoke | as `reconstruction-smoke` `image-smoke` | 13 passed, 1 expected skip; liveness fails before a control-plane call |
| Stack smoke + restore drill | `SMOKE_API_PORT=18080 SMOKE_KEYCLOAK_PORT=18081 SMOKE_MINIO_PORT=19000 PYTHON=<venv python> bash scripts/ci/stack-smoke.sh` | **passed**; restore drill `RESULT: PASS` |
| Python dependencies | `pip-audit==2.10.1 -r <frozen> --no-deps --disable-pip` per service, clean venvs | no known vulnerabilities (both) |
| Vision lint | `ruff check .` | clean. Vision tests: not re-run (green in CI `python` → `test (vision)` on `e96d477`) |
| Images | `docker build --build-arg OS_PACKAGES_REFRESH=<date>` ×4, then Trivy 0.70.0 with the workflow's flags | all four: non-root, HEALTHCHECK, **Trivy exit 0** |
| Deploy, gate, backups | `bash scripts/deploy/test-deploy.sh`; `bash scripts/ci/test-require-green.sh`; `bash scripts/backup/test-restore-drill.sh`; `bash scripts/backup/test-backup-ownership.sh`; `bash scripts/deploy/bundle.sh` | 42 passed; 10 passed; 10 passed; 6 passed; bundle contains the restore drill |
| Static | `shellcheck -S warning` (ops glob); `actionlint` 1.7.7 | clean; clean |
| iOS core | `swift test` in `swift:5.10-jammy` (with `packages/contracts/fixtures/ar`) | **86 passed** |
| Cross-service acceptance | `ACC_API_PORT=18080 ACC_KEYCLOAK_PORT=18081 ACC_WEB_PORT=13000 ACC_WEB_URL=http://localhost:13000 bash scripts/acceptance/run-acceptance.sh ci` | **NOT RUN**: stopped while building its images when the workstation ran out of memory (the stack never started). So A6/B6 are **not** verified end to end. Re-run in CI `acceptance`, or locally with more free memory. |

### Not run on 2026-10-24, and why

| Check | Missing prerequisite |
|---|---|
| iOS app build and app-level XCTest (`ios` → `app`) | macOS with Xcode 15.4 (only the `ios` workflow on `macos-14`) |
| `gpu` workflow, `tests/gpu` CUDA tests, `Dockerfile.gpu` build | An NVIDIA GPU host registered as a self-hosted runner labelled `gpu` (none exists) |
| The `deploy` workflow | A deploy host, DNS, and the `staging` Environment's secrets; and the commit pushed to `main` with green CI |
| Any workflow on GitHub for this commit | The commit is not pushed (pushing is the owner's decision). Until it is, "green in CI" is not claimed for any change here. |
| Cross-service acceptance (`run-acceptance.sh ci`) and OWASP Dependency-Check | Started; both stopped when the workstation ran out of memory (acceptance while building images, Dependency-Check while building its NVD database). Not restarted. |
| B2 on the real splat | `benchmarks/b2_geometry_cleanup` input (playroom splat) is not on this host |
| Physical-device AR checks | An ARCore phone and an iPhone |

### Known residual (not changed here)

- **Python images have no lock file.** They install version ranges from `pyproject.toml` (docs/security-hardening.md
  #18). `pip-audit` scans what resolves today, so an image built on another day can resolve differently.
- **Base images are pinned by tag, not digest**, except the GPU image's CUDA bases. The daily OS refresh takes Debian
  and Alpine fixes; a new base digest still arrives only when the tag moves.

## Record: state on 2026-10-12 (`40beef5` plus the documentation audit)

*2026-10-24:* superseded by the section above. The tables below are kept as the 2026-10-12 record.

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
