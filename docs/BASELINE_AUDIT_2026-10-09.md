# Baseline audit, 2026-10-09

**Commit:** `1e99a4d` (main, clean tree before this audit) plus the two test-only edits in §6.
**Host:** Windows 11, 18 cores, 15.6 GB RAM (about 2 GB free: unrelated Docker stacks were running), Docker Desktop 29.6.2
(7.5 GiB VM). **No CUDA device. No physical phone, AR device, or Mac/Xcode was used.**
**Not available:** the "solution blueprint supplied separately" was not in the repository or the request, so it was not
used.

Every result below was produced on 2026-10-09 by the command shown, unless it is marked **(doc)**. That mark means the
evidence is recorded in the repository and was not re-run today.

---

## 1. Verdict

- **Nothing in the reconstruction → viewer → navigation chain has run on a real indoor venue or on a physical device.**
  No such capture exists, either in the repository or on this host (docs/E2E_VALIDATION.md §R).
- **First blocking stage of the real workflow:** see §5.
  - In the shipped deployment (`infra/deploy`), the first stage that cannot execute is **`POSE_ESTIMATION` (stage 5)**.
    No deployable worker image contains COLMAP/GLOMAP, and no GPU worker image or recipe exists in the repository.
  - On the test toolchain worker (CPU COLMAP), POSE_ESTIMATION runs, and the chain stops at
    **`SPLAT_RECONSTRUCTION` (stage 6)** with `DEPENDENCY_UNAVAILABLE: torch, gsplat, cuda`.
- **CI on `main` is red in 4 of 7 workflows.** The local suites are green, after one lint fix and one stale-test fix
  (§6). The red security gates block staging deploys: `deploy` runs only after a successful `docker` run, and every
  recent `deploy` run is `skipped`.

## 2. Findings, ranked by severity

| # | Sev | Finding | Affected files | Reproduce | Next smallest corrective task |
|---|---|---|---|---|---|
| F1 | **High** | **No real-venue run is possible yet.** <br>• No measured indoor capture exists. <br>• `POSE_ESTIMATION` and every later stage need a worker that the repository does not build: the production CPU worker deliberately has no COLMAP, and no GPU image exists. In the shipped deployment, such a run waits in `QUEUED` until the run deadline fails it. <br>• Health reports `DEGRADED` ("processing stalled"). | `infra/deploy/docker-compose.yml:110` (`WORKER_STAGES`), `services/reconstruction/Dockerfile:7-10`, DEPLOYMENT.md §10 | `grep WORKER_STAGES infra/deploy/docker-compose.yml`; E2E §R.1 (doc) | Add a buildable POSE_ESTIMATION worker image to the repository: promote `infra/ci/worker-colmap.Dockerfile` with `COLMAP_NUM_THREADS`, or add a GPU Dockerfile. Then record one measured indoor capture. |
| F2 | **High** | **`python` CI has failed at the Lint step since `e0d0b97` (2026-10-05).** <br>• Cause: ruff `S106` on a test literal. <br>• Effect: the worker CPU suite, the navmesh guard and the splat guard **have not run in CI for 4 days**. CI shows red, so this is not a false green, but there is no CI regression signal. | `services/reconstruction/tests/unit/test_api_client_lease.py:43` | `cd services/reconstruction && .venv/Scripts/python -m ruff check .` → exit 1, 2 × S106 | **Fixed locally (§6.1).** Push it. Pin `ruff` in `pyproject.toml` (it is `>=0.6`), so a new ruff release cannot break lint unannounced. |
| F3 | **High** | **`reconstruction-smoke / stack-smoke` has failed on every run since `e0d0b97`.** It is the only test of real worker code against the real API, MinIO, ClamAV and Keycloak. <br>• Cause: the test predates artifact sealing. Registered objects now live under `sealed/org/…`, and the worker's originals under `org/…` are deleted, so `list_objects_v2(Prefix="org/")` returns nothing for the run (`assert ([])`). <br>• This is a test defect, not a product defect. | `services/reconstruction/tests/integration/test_stack_e2e.py:129` | `SMOKE_API_PORT=18080 SMOKE_KEYCLOAK_PORT=18081 SMOKE_MINIO_PORT=19000 PYTHON=services/reconstruction/.venv/Scripts/python.exe bash scripts/ci/stack-smoke.sh` → exit 1, `AssertionError: unblurred frames must have been deleted` (same as CI run 37920394139) | **Fixed locally and verified (§6.2):** exit 0, 1 passed. Push it. |
| F4 | **High** | **Security gates fail on `main`**, which also blocks staging deploys. <br>• `security/maven` (OWASP DC): `spring-core 6.2.19`, with CVEs up to CVSS 9.8 (e.g. CVE-2026-47884, -47890, -59313), and `spring-security-core / -oauth2-resource-server 6.5.11` (CVE-2026-59270, 9.1). <br>• `security/web` and `docker/web` (Trivy): `next` has 6 high advisories (fix: 16.4.0, outside the declared range); also `sharp <0.35.5` and `source-map-js`. <br>• `docker/api` (Trivy): the same Spring CVEs. | `services/api/pom.xml`, `apps/web/package.json`, `apps/web/package-lock.json` | `cd apps/web && npm audit --omit=dev --audit-level=high` → exit 1, 3 high. Maven DC: `gh run view 37784263664 --log-failed` | Bump the Spring Boot BOM (or override `spring-framework` / `spring-security`) to the fixed lines, and `next` to 16.4.x. Re-run `mvn verify` and the web checks. Dependency updates were out of scope for this audit. |
| F5 | Med | **The iOS `app` job fails one XCTest** (since `fb7e65f`). <br>• `ReferenceImageTests` builds an `Anchor` with no `scanVersionId` / `poseId`. <br>• `MarkerRegistry` correctly rejects such anchors as `.unversioned`, so `markers.first` is nil. <br>• Stale test. The app itself builds (`** BUILD SUCCEEDED **`). | `apps/ios-ar/App/ChayaARTests/AppLevelTests.swift:93-97`, `apps/ios-ar/Sources/ChayaARCore/MarkerRegistry.swift:36` | `gh run view $(gh run list -w ios.yml -L1 --json databaseId -q '.[0].databaseId') --log-failed` | Give the fixture anchor a `scanVersionId` and a `poseId`. Verify on the macOS runner (it cannot run here). |
| F6 | Med | **Legacy run-less job path: jobs can be created that can never finish.** <br>• `POST /venues/{v}/scans/{s}/jobs` enqueues a `processing_job` with `run_id = NULL` for any `JobStage`, including the 5 legacy names (`MEDIA_FILTER`, `SPLAT_TRAINING`, …) that no worker implements. <br>• The Python worker only calls `/claim`, `/heartbeat` and `/report`. `/report` answers `409 NOT_A_PIPELINE_JOB` for run-less jobs. <br>• `/internal/jobs/{id}/complete` and `/fail` have no production caller, only API tests. | `scan/ScanController.java:44`, `scan/ScanService.java:51`, `pipeline/PipelineService.java:283,436`, `processing/JobStage.java:26-31`, `job/InternalJobController.java`, `chaya_worker/api_client.py:94-109` | `grep -nE '/complete\|/fail' services/reconstruction/chaya_worker/api_client.py` → nothing | Decide whether to remove the endpoint, the legacy stages and the complete/fail paths, or to restrict it. Changing it changes the API contract, so it needs a decision (an ADR). |
| F7 | Med | **Calibration, which is on the critical path, has no UI.** Metric consumers return `NOT_CALIBRATED` until `POST /venues/{v}/reconstructions/{run}/coordinate-frames` is called. No web screen calls it, and no capture-time field records the tape-measured references it needs. The same gap applies to re-scan start and finalize, erasure, public links and floor connections (API-only). | `apps/web/lib/*` (no caller), `frame/CoordinateFrameController.java` | `grep -rn coordinate-frames apps/web/lib apps/web/components` → no POST | Add a minimal calibration form: two measured distances, posted to the existing endpoint. |
| F8 | Med | **Worker run outside Docker gets the API's storage account.** <br>• `chaya_worker/settings.py:248` reads `S3_ACCESS_KEY`. <br>• In `.env.example` that is the API's raw+derived **delete-capable** account. <br>• The template's "worker outside Docker" section gives no `S3_WORKER_*` mapping. Compose does the mapping, so containers are not affected. | `.env.example` (worker section), `services/reconstruction/chaya_worker/settings.py:248` | Read both files | Add `# S3_ACCESS_KEY=<S3_WORKER_ACCESS_KEY>` guidance to the worker section. |
| F9 | Med | **Stale or contradictory documentation.** See §7 for the full list. The worst cases: <br>• README says "No business functionality yet". <br>• ARCHITECTURE §5 and §10 describe a schema, job states and a claim URL that do not exist. <br>• ARCHITECTURE §2 describes in-browser Recast WASM, which contradicts §8 and the code. <br>• TEST_REPRODUCIBILITY still lists MinIO as unresolved. | see §7 | see §7 | One docs-only commit for the §7 items. |
| F10 | Low | **Integration boundaries tested only with doubles:** <br>• the vision service's 8 tests stub CLIP/torch (real CLIP only in the manual B3 benchmark, doc); <br>• all Playwright specs stub the API with `page.route`; <br>• `ClamAvLiveTest` and `KeycloakLiveTest` are opt-in and were skipped today. <br>Stack smoke does exercise real ClamAV and Keycloak (passed today). | `services/vision/tests/test_main.py`, `apps/web/e2e/*.spec.ts` | — | Add `ClamAvLiveTest` and `KeycloakLiveTest` to the stack-smoke job, which already runs both services. |
| F11 | Low | **Defaulted environment variables missing from the templates:** `PII_STAGING_RETENTION` (API), about 60 worker tunables (`GSPLAT_*`, `ALIGNMENT_*`, `CLEANUP_*`, …), and `CHAYA_VERSION` / `MINIO_IMAGE` / `MC_IMAGE` (deploy; set by `deploy.sh` or defaulted). | `.env.example`, `infra/deploy/.env.production.example` | §3, compose rows | Reference docs/pipeline.md from `.env.example`. No functional change. |
| F12 | Low | **Toolchain drift:** CI uses Node 22, this host Node 24. `ruff>=0.6` is unpinned (F2). The global Python 3.14 lacks OpenCV Haar cascades, so worker tests must use `.venv` (3.12). | `.github/workflows/frontend.yml:33`, `services/*/pyproject.toml` | — | Pin `ruff`. Optionally add `engines.node` to `package.json`. |

## 3. Commands run and their results

The scratch environment files used random values outside the repository, and were never written into it.

| Suite | Command | Exit | Result | Kind of test |
|---|---|---:|---|---|
| Fixture presence | `bash scripts/ci/check-fixtures.sh` | 0 | 12 fixtures committed, checksums match | — |
| Web lint | `cd apps/web && npm run lint` | 0 | clean | static |
| Web typecheck | `npm run typecheck` | 0 | clean | static |
| Web unit | `npm test` (node --test) | 0 | **143 passed**, 0 skipped | unit, synthetic fixtures (incl. the committed `.ksplat`) |
| Web build | `npm run build` | 0 | built, 9 static pages | — |
| Web e2e | `CI=1 npx playwright test` (Chromium 1.63, software WebGL) | 0 | **14 passed**, 0 retries | real browser and viewer library; **API mocked** (`page.route`); synthetic `.ksplat` fixture |
| Web prod audit | `npm audit --omit=dev --audit-level=high` | **1** | 3 high (`next`, `sharp`, `source-map-js`) | F4 |
| Worker lint | `cd services/reconstruction && .venv/Scripts/python -m ruff check .` | **1 → 0** | 2 × S106 before the §6.1 fix, clean after | F2 |
| Worker tests | `.venv/Scripts/python -m pytest -q -rs` (3.12, CPU torch 2.14) | 0 | **442 passed, 17 skipped**, 0 failed (3 m 22 s) | unit and synthetic fixtures. Real tools: FFmpeg, chaya-navmesh (Recast 1.6.0, MinGW build) |
| … skipped (not passed) | same | — | 4 gsplat/CUDA; 3 `CHAYA_TEST_IMAGE_SEQUENCE` unset; 6 Open3D absent (1 toolchain, 5 region alignment); 2 MinIO (`CHAYA_IT_S3_*`); 1 stack (`CHAYA_IT_*`); 1 empty parameter set. Total 17. | prerequisites absent |
| Navmesh guard | `pytest -m navmesh` | 0 | **46 passed**, 0 skipped | real Recast/Detour on synthetic venues |
| Splat guard | `pytest tests/splat tests/unit/test_splat_color.py` | 0 | **60 passed** | CPU training mechanics with a test renderer. **Not gsplat/CUDA.** |
| Benchmarks framework | `pytest benchmarks/tests` | 0 | 7 passed | unit |
| Vision | `docker run python:3.12-slim … pip install -e .[dev]; ruff check .; pytest` | 0 / 0 | ruff clean; **8 passed** | **CLIP mocked** (model extra not installed) |
| API | `docker run maven:3.9-eclipse-temurin-21 … mvn -B -ntp verify` (Testcontainers: pgvector pg17, GHCR MinIO mirror) | **1** | 452 run, 0 failures, **5 errors**, 4 skipped | the errors were a harness defect: see the next row |
| API, rerun | `… mvn test -Dtest=StoragePolicyTest`, with `infra/` mounted | 0 | **5 passed** | real MinIO, real `infra/docker/minio/init.sh` |
| API effective | — | — | **452 run, 0 failures, 0 errors, 4 skipped** (`ClamAvLiveTest` ×2, `KeycloakLiveTest` ×2, opt-in). The 5 CI-guarded Testcontainers classes all ran with 0 skips. | real Postgres+pgvector and MinIO. Keycloak via `TestJwt`, ClamAV stubbed |
| Stack smoke, before the fix | `SMOKE_*_PORT=… PYTHON=… bash scripts/ci/stack-smoke.sh` | **1** | `test_stack_e2e` FAILED at line 131 (reproduces CI) | **real** API image, Postgres, MinIO, ClamAV (signatures), Keycloak, worker code |
| Stack smoke, after the §6.2 fix | same | 0 | **1 passed**. INPUT_VALIDATION → PRIVACY_PREPROCESS succeeded; the run stopped `DEPENDENCY_UNAVAILABLE` at POSE_ESTIMATION; stack torn down (0 containers, 0 volumes left) | real infrastructure, synthetic video |
| iOS core | `docker run swift:5.10-jammy … swift test` (in `apps/ios-ar`) | 0 | **43 passed** | unit (`ChayaARCore` only; ARKit/UIKit not compiled here) |
| Compose, template as-is | `docker compose --env-file <blank> -f … config -q` for all 5 files | 1 (×5) | "required variable … is missing a value". **By design** (`.env.example` header). | config |
| Compose, filled | dev; smoke overlay; e2e overlay; monitoring (`.env.example` filled) | 0 (×4) | valid | config |
| Compose, deploy | `infra/deploy` with `.env.production.example` filled, plus `CHAYA_VERSION` | 0 | valid | config |
| MinIO mirror | anonymous GHCR token + `GET /v2/yawayapnwork/mirror/{minio,mc}/manifests/<digest>` | — | HTTP 200 for both. **Publicly pullable.** | registry |
| CI status | `gh run list` / `gh run view --log-failed` | — | green: `backend`, `frontend`. Red: `python` (Lint), `reconstruction-smoke` (stack-smoke), `security` (maven, web), `docker` (Trivy api, web), `ios` (app XCTest). `deploy`: skipped. | — |

**Not run here, and not claimed:**

- the gsplat/CUDA, COLMAP-on-real-photos and Open3D tests (no GPU, no Open3D);
- `ios` `app` (needs macOS);
- the full E2E stack harness (`scripts/e2e/e2e_validate.py`; memory);
- `docker` image builds and Trivy (CI evidence used instead);
- OWASP Dependency-Check locally (CI evidence used instead).

## 4. Workflow map

"Persistence" lists Flyway tables (V1–V31). Stage names are `JobStage`. "UI" is a page or component in `apps/web`.

| Workflow | API endpoints (`/api/v1`) | Persistence | Background processing | Output artifacts | UI |
|---|---|---|---|---|---|
| Sign-in, tenancy | `GET /venues`, `POST /venues` (admin), `PATCH /venues/{v}`, `GET/POST /venues/{v}/floors`. Organizations: **SQL only** (no API) | `organization`, `venue`, `floor`, `space`, `audit_log` | — | — | `/` (venue list), `/auth/callback` (Keycloak PKCE) |
| Capture and upload | `POST /venues/{v}/captures`; `POST …/media`; `PUT …/media/{m}/parts/{n}`; `POST …/media/{m}/complete`; `POST …/complete-upload` | `capture_session`, `capture_media`, `capture_media_part` | in-process `MediaValidationService` (Tika, ClamAV) | raw object in `chaya-raw` | `/capture`: **file upload** of a video recorded elsewhere. No in-browser recording. |
| Capture HUD and guidance | `PUT …/hud/scene`, `POST …/hud/pose`, `POST …/hud/quality`, `GET …/hud/status`, `GET …/hud/stream` (SSE); `POST …/route-plan` | `capture_hud_scene`, `capture_hud_pose_sample`, `capture_hud_quality_sample` | — (client-side signals) | — | `CaptureHud` (camera preview, room size). **No caller** for `route-plan`. |
| Processing | `POST/GET …/captures/{c}/processing`, `…/processing/retry`, `…/processing/cancel`. Worker: `POST /internal/jobs/claim`, `/{j}/heartbeat`, `/{j}/report` | `scan`, `pipeline_run`, `pipeline_stage_run`, `processing_job`, `processing_artifact`, `pii_staging_purge`, `pii_staging_sweep` | INPUT_VALIDATION → FFMPEG_PREPROCESS → FRAME_QUALITY_FILTER → PRIVACY_PREPROCESS → POSE_ESTIMATION → SPLAT_RECONSTRUCTION → SEMANTIC_SEGMENTATION → GEOMETRIC_CLEANUP → PLANE_FITTING → ARTIFACT_GENERATION → SEMANTIC_INDEXING → NAVIGATION_BAKING. `PipelineEnforcer` handles leases and deadlines. | INPUT_REPORT, FRAME_ARCHIVE(_SELECTED/_ANON), FRAME_MANIFEST, FRAME_QUALITY_REPORT, PRIVACY_MASKS/REPORT, SPARSE_MODEL, POSES, SPLAT (or SPLAT_PARTIAL), SPLAT_CLEAN, KSPLAT, NAVMESH, NAVMESH_MANIFEST, NAVIGATION_GRAPH… All are copied to `sealed/org/…` at registration. | `ProcessingPanel` (in `/capture`); `/ops` job retry and cancel |
| Legacy jobs (F6) | `POST /venues/{v}/scans`, `POST …/scans/{s}/jobs`, `POST /venues/{v}/jobs/{j}/{cancel,retry}`; `/internal/jobs/{j}/{complete,fail}` | `processing_job` (`run_id` NULL) | none: no worker can finish a run-less job | — | `/ops` uses `jobs/{j}/{kind}` for pipeline jobs |
| Calibration | `POST/GET …/reconstructions/{run}/coordinate-frames`; `GET …/floors/{f}/coordinate-frame` | `coordinate_frame` | — | — | **None** (F7). The viewer only shows calibrated / not calibrated. |
| Versions and re-scan | `POST …/floors/{f}/rescan`, `GET …/scan-versions`, `POST …/scan-versions/finalize-current` | `scan_version`, `scan_version_artifact`, `floor.current_scan_version_id` (V28) | REGION_ALIGNMENT, REGION_SPLICE (incremental only) | SPLAT_MERGED, alignment reports | Read-only (`/ops` re-scans, version scope in the viewer). **No UI** to start or finalize. |
| Viewer | `GET …/floors/{f}/reconstructions[/latest]`, `GET …/reconstructions/{run}`, `GET …/scan-versions/{sv}/reconstruction`, `GET …/{reconstructions/{run} or scan-versions/{sv}}/artifacts/{kind}`; public: `POST /public/viewer-token`, `…/public-links` | `public_viewer_link`, `public_viewer_token` | — | KSPLAT, checked for size, SHA-256 and format in the browser (`artifact-integrity.ts`) | `/viewer` (`SplatViewerCanvas`, GaussianSplats3D 0.4.7). **No UI** to manage public links. |
| POIs and search | `GET/POST/PUT/DELETE …/pois`; `GET …/search` | `poi`, `poi_version` (pgvector), `search_query` | SEMANTIC_INDEXING (detections + CLIP image embeddings); `PoiEmbeddingBackfill` (manual POIs, via vision) | — | `SemanticSearchPanel` in `/viewer` |
| Navigation | `POST /navigation/routes`; `…/floor-connections` (CRUD) | `navigation_graph`, `navigation_node`, `navigation_edge`, `floor_connection` | NAVIGATION_BAKING (chaya-navmesh) | NAVMESH, NAVMESH_MANIFEST | Route overlay in `/viewer` and `/ar`. **No UI** for floor connections. |
| AR | `…/floors/{f}/anchors` (CRUD), `…/{a}/calibrate`, `…/{a}/target.png`, `…/anchors/relocalize` | `ar_anchor`, `ar_anchor_pose` | — | — | `/ar` (Android WebXR); `apps/ios-ar` (native) |
| Ops | `GET /venues/{v}/ops/{access,overview,coverage,jobs,failures,storage,search-analytics,rescans,audit}`; `GET /audit-log` | read models over the tables above | — | — | `/ops` |
| Erasure | `DELETE /venues/{v}`, `DELETE …/captures/{c}`, `DELETE …/scan-versions/{sv}`, `GET /erasures[/{id}]` | `erasure_request`, `erasure_item`, `erasure_object` | `ErasureSweeper` | — | **None** |
| Health | `GET /health`, `GET /version`; web: `/api/health`, `/api/health/live` | — | — | — | `/status` |

## 5. Validation status per stage

Key:

- ✔ shown today.
- ✔(doc) recorded evidence, not re-run today.
- ◐ partial (the meaning is given in the cell).
- ✘ no.
- n/a: not applicable.

"Fixture-validated" means synthetic or public data. "Real media (outdoor)" is the Sceaux photo set from E2E §R, which is
**not** an indoor venue.

| Stage | Implemented in code | Unit-tested | Integration-tested | Fixture-validated | Real indoor venue | Physical device |
|---|---|---|---|---|---|---|
| Identity, tenancy | ✔ | ✔ | ✔ Testcontainers. ✔ real Keycloak in stack smoke. | n/a | ✘ | n/a |
| Upload, validation (Tika, ClamAV) | ✔ | ✔ | ✔ real MinIO. ✔ real ClamAV in stack smoke. | ✔ synthetic video | ✘ | ✘ never uploaded from a phone |
| Capture HUD, guidance | ✔ | ✔ | ✔ `CaptureHudApiTest` | ✔ synthetic (B1, doc) | ✘ | ✘ |
| INPUT_VALIDATION, FFMPEG, FRAME_QUALITY | ✔ | ✔ | ✔ stack smoke | ✔ | ✘. Real outdoor media ✔(doc §R). | n/a (CPU) |
| PRIVACY_PREPROCESS | ✔ | ✔ | ✔ stack smoke (PII purge checked) | ✔ | ✘. Real outdoor media ✔(doc), over-masks (CV-6). | n/a |
| POSE_ESTIMATION | ✔ COLMAP. ◐ GLOMAP never in a run image. | ✔ (tools stubbed) | ◐ stack smoke covers only the `DEPENDENCY_UNAVAILABLE` path. 3 real-photo tests skipped. | ✘ | ✘. Real outdoor media ✔(doc): 11/11 registered, 0.398 px. | ✘ no GPU host |
| Metric calibration | ✔ API only | ✔ | ✔ `CoordinateFrameCalibrationTest` | ✔ synthetic | ✘ no measurements exist | n/a |
| SPLAT_RECONSTRUCTION (gsplat) | ✔ | ◐ CPU mechanics only (60 tests) | ✘ | ✘ 4 CUDA tests skipped | ✘ | ✘ **no CUDA** |
| SEGMENTATION, CLEANUP, PLANE_FITTING | ✔ | ✔ (Open3D stubbed on this host) | ✘ the Open3D toolchain test is skipped | ◐ B2 (doc) | ✘ | ✘ |
| ARTIFACT_GENERATION → viewer | ✔ | ✔ | ◐ viewer specs with a mocked API. ✔(doc) full stack, §V.3. | ✔ synthetic `.ksplat`, pixel-checked | ✘ no real `.ksplat` exists (§V.6) | ✘ headless software WebGL only |
| SEMANTIC_INDEXING, search | ✔ | ✔ (vision: CLIP mocked) | ✔ `SemanticSearch*Test` | ✔(doc) B3, real CLIP | ✘ | n/a |
| NAVIGATION_BAKING | ✔ Recast 1.6.0 | ✔ | ✔ the API ingests real tool output | ✔ 46 real-Recast tests | ✘ | n/a |
| Route generation | ✔ | ✔ | ✔ `RouteServiceTest`, `RoutePlanApiTest` | ✔ on fixture navmeshes | ✘ | n/a |
| Incremental re-scan | ✔ | ✔ | ✔ control-plane tests | ✔ synthetic. ✔(doc) B5 on a real SfM cloud. | ✘ | ✘ |
| AR Android (WebXR) | ✔ | ✔ | ◐ Playwright: unsupported states only | n/a | ✘ | ✘ **never on a phone** |
| AR iOS (ARKit) | ✔ | ✔ core 43 (Linux). ✘ app XCTest fails in CI (F5). | ✘ | n/a | ✘ | ✘ **never on a phone** |
| Ops dashboard | ✔ | ✔ | ✔ `OpsDashboardApiTest` | n/a | ✘ | n/a |
| Erasure | ✔ | ✔ | ✔ `ErasureTest`, `StoragePolicyTest` | n/a | ✘ | n/a |

### First blocking stage in the real capture → viewer → navigation workflow

| Step | Can it run? | Evidence |
|---|---|---|
| 0. A measured indoor capture | **Absent** (data, not code) | E2E §R |
| 1–4. Upload and CPU stages | Yes | stack smoke today; E2E §R (doc) |
| **5. POSE_ESTIMATION** | **No, in the shipped deployment.** No worker claims it: `infra/deploy` `WORKER_STAGES`, and no COLMAP/GPU image is in the repository. It runs only on the CI/test image `infra/ci/worker-colmap.Dockerfile`. | §2 F1 |
| **6. SPLAT_RECONSTRUCTION** | **No, on any available host:** `DEPENDENCY_UNAVAILABLE: torch, gsplat, cuda` | E2E §R.2 (doc) |
| 7–12, calibration, viewer, search, route | Never reached | — |

**The first blocking stage is `POSE_ESTIMATION`, for lack of a deployable worker.** Behind it, `SPLAT_RECONSTRUCTION`
needs CUDA hardware. A real run needs both, and a measured indoor capture before either.

## 6. Code changes made by this audit (test-only; no production behaviour changed)

Neither change is committed.

1. **`services/reconstruction/tests/unit/test_api_client_lease.py:43`.** Added `# noqa: S106` to a dummy test credential
   (`client_secret="s"`).
   - **Why:** the lint error stops `python.yml` before any worker test runs (F2).
   - **Verified:** `ruff check .` exits 0, and the file's 3 tests pass.
2. **`services/reconstruction/tests/integration/test_stack_e2e.py:129`.** The PII-deletion check now lists both `org/` and
   `sealed/org/`, instead of only `org/`. Its assertion is unchanged: there are run objects, and none contains `/pii/`.
   - **Why:** registered artifacts moved to `sealed/` in `e0d0b97`, so the old listing was empty, and the test failed on
     every CI run since (F3).
   - **Verified:** `scripts/ci/stack-smoke.sh` exits 1 before the change and 0 after it, on the real stack.

**New file:** this report.

## 7. Stale or contradictory documentation (F9)

| Where | What it says | What the code does |
|---|---|---|
| `README.md` "Status" | "foundation … No business functionality yet" | 22 controllers, 31 migrations, a 12-stage pipeline, AR clients |
| `ARCHITECTURE.md` §2 | "Navigation queries run against a Recast navmesh (WASM) loaded in-browser" | no WASM. Routes are computed server-side (`RouteService`). §8 itself says "No client runs Detour". |
| `ARCHITECTURE.md` §4 | `services/navigation` "(created when each worker is implemented)" | does not exist. Baking is `services/reconstruction/native/chaya-navmesh`. |
| `ARCHITECTURE.md` §5 | tables `venue_membership`, `media_asset`, `reconstruction`, `reconstruction_region`, `job_event`, `scene_asset`, `detected_object`, `anchor`, `search_query_log`, `audit_event` | none of these exist. The real tables are listed in §4 here: `pipeline_run`, `processing_artifact`, `poi_version`, `ar_anchor`, `search_query`, `audit_log`, … |
| `ARCHITECTURE.md` §10 | states `CLAIMED`, `RETRY_WAIT`; `POST /api/v1/worker/jobs:claim`; `reconstruction.is_complete` | job states are `QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED`; claim is `/api/v1/internal/jobs/claim`; runs end `SUCCEEDED / PARTIAL / FAILED / CANCELLED` |
| `PROJECT_PLAN.md` M2 | "Maven wrapper … `./mvnw verify`" | no `mvnw` in `services/api` (also `infra/monitoring/prometheus/prometheus.yml:10`) |
| `PROJECT_PLAN.md` M8 | "Done: GLOMAP with COLMAP fallback" | its own table says "COLMAP only, GLOMAP never in a run image" |
| `PROJECT_PLAN.md` M16 | "No `floor.current_scan_version_id` yet" | added by `V28__current_scan_version.sql` |
| `docs/TEST_REPRODUCIBILITY.md` "Unresolved" | MinIO images unpullable; `AbstractIntegrationTest` uses `quay.io/minio/minio:latest` | pinned to the GHCR mirror by digest (`AbstractIntegrationTest.java:56`); the mirror answers anonymous pulls (§3). Counts (110 web / 255 worker / 364 API) are out of date: 143 / 442 / 452. |
| `DEPLOYMENT.md` §11 | the 2026-09-24 counts (271 API, 61 web, 116 worker) | see §3 |
| `scripts/ci/stack-smoke.sh` header; `test_stack_e2e.py:103` | "GPU stages … (POSE_ESTIMATION)"; "stages 6-12 are not implemented" | stages 6–12 are implemented. They are blocked by missing tools, not by missing code. |

## 8. Prerequisites missing on this host

- **CUDA GPU:** blocks SPLAT_RECONSTRUCTION and the 4 gsplat tests.
- **Open3D:** 6 tests skipped.
- **COLMAP/GLOMAP on the host:** 3 real-photo SfM tests skipped. COLMAP exists only in `chaya-physical-worker:colmap`.
- **macOS/Xcode:** the iOS `app` job.
- **Android or iOS device:** AR.
- **A measured indoor capture.**
- **Memory:** about 2 GB free. That ruled out the full E2E harness alongside the other running stacks.
