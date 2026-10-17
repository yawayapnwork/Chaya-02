# Acceptance testing: classifications, the cross-service suite, and what each result means

Chaya's tests answer different questions, and their counts are never added together. A pass in one classification says
nothing about another: 600 green unit tests do not show that a GPU reconstruction works, and a green cross-service suite
on a synthetic scene does not show that a real venue reconstructs. Every result below is PASS, FAIL or BLOCKED.
**BLOCKED** means a dependency the environment does not have (a GPU, a real venue, a device); it is reported with the
missing dependency and is never counted as a pass.

## The seven classifications

| Class | What it is | Real | Not real | Where | Runs in CI |
|---|---|---|---|---|---|
| **A. Unit** | One module, no services | the code under test | everything around it | Java untagged tests, worker `tests/unit`, web `npm test`, web Playwright specs (backend stubbed with `page.route`), vision `tests`, iOS `ChayaARCore` | yes: `backend` (step A), `python`, `frontend`, `ios` |
| **B. Service integration** | One service against real infrastructure | PostgreSQL/pgvector, MinIO (Testcontainers), Spring, HTTP | other services: the worker is played by the test (`PipelineTestSupport`), Keycloak by a test JWT issuer | Java `@Tag("integration")` (every `AbstractIntegrationTest` subclass, `MigrationTest`, `CoordinateFrameMigrationTest`), worker `tests/integration` | yes: `backend` (step B), `reconstruction-smoke` |
| **C. Synthetic fixture** | Real executables and real services on synthetic inputs | the code, the tools (FFmpeg, OpenCV, Recast/Detour, Open3D), and in the cross-service suite every service | the scene: synthetic, not a capture of anything | worker `tests/orchestration`, `tests/smoke`, `tests/splat` (CPU test renderer, not gsplat), `tests/navmesh` (real `chaya-navmesh`); **the cross-service acceptance suite** (below); `scripts/ci/stack-smoke.sh` | yes: `python`, `reconstruction-smoke`, `acceptance` |
| **D. Real-media pipeline** | Real photographs or video through the real stages a CPU host can run | the media and the CPU stages (COLMAP CPU) | GPU stages; a venue | `scripts/e2e/physical_pipeline.py`, `scripts/e2e/e2e_validate.py` (docs/E2E_VALIDATION.md) | no: needs a licensed dataset |
| **E. GPU reconstruction** | gsplat training on CUDA, the GPU worker image | everything up to the splat | a venue (unless the input is one) | worker `tests/gpu` (`pytest -m gpu`, `CHAYA_REQUIRE_GPU=1`), `services/reconstruction/Dockerfile.gpu` | no: no GPU runner |
| **F. Real-venue acceptance** | The whole workflow on a real, measured indoor capture | everything | nothing | `scripts/acceptance/real_venue.py` (docs/REAL_VENUE_ACCEPTANCE.md) | no: needs a venue and a GPU worker |
| **G. Physical-device AR** | ARKit/WebXR on a phone at the venue | the device, its sensors, the printed markers | — | docs/ar-ios-validation.md, docs/ar-android-validation.md | no: needs a device |

## The cross-service acceptance suite (classification C)

`services/reconstruction/tests/acceptance`, run by `scripts/acceptance/run-acceptance.sh`, in CI by the `acceptance`
workflow. It drives the product's real workflow across service boundaries, through the public API, Keycloak and the worker
control plane, and nothing is intercepted or mocked:

- **Real services:** the API container, Keycloak, PostgreSQL/pgvector, MinIO, ClamAV, the vision service (real CLIP), the
  web app in Chromium with the real GaussianSplats3D viewer.
- **Real workers:** the production CPU worker image claims `INPUT_VALIDATION`, `FFMPEG_PREPROCESS`, `FRAME_QUALITY_FILTER`,
  `PRIVACY_PREPROCESS`, `REGION_SPLICE`, `ARTIFACT_GENERATION` and `NAVIGATION_BAKING` (real Recast/Detour). A test-only
  Open3D worker (`benchmarks/docker/open3d.Dockerfile`, running the checkout's worker code) claims `GEOMETRIC_CLEANUP`,
  `PLANE_FITTING` and `REGION_ALIGNMENT`. In production those three run on GPU hosts; the stage code is the same.
- **Labelled stand-ins: exactly four stages.** `POSE_ESTIMATION`, `SPLAT_RECONSTRUCTION`, `SEMANTIC_SEGMENTATION` and
  `SEMANTIC_INDEXING` need COLMAP, CUDA and models over frames that depict the scene; the capture is a synthetic video of a
  face, so there is no scene in it to reconstruct. The suite claims them through the worker API as the worker service
  account, reports `command.standIn = true`, and publishes outputs derived from the committed synthetic viewer scene
  (`packages/contracts/fixtures/viewer-scene`, with a floor dense enough to be observed ground, see below). The stand-in
  detections carry CLIP **text** vectors of their labels, not image crops. A stand-in is never reported as a
  reconstruction, and `test_03_processing` fails if any other stage was stood in.

### What it covers

| Scenario | Tests | What is checked |
|---|---|---|
| 1. Authentication | `test_01_authentication` | Keycloak login with tenant claims; the venue list scoped to the token; no token, a broken signature and a wrong password refused; another venue of the same organization 404; a viewer cannot write; an operator cannot create a venue; a real token expiry (10 s client) refused once `exp` plus the validator's 60 s clock skew has passed; cross-tenant venue, captures, search, POIs and ops all 404 |
| 2. Capture | `test_02_capture` | session on a floor; a real video accepted by checksum, content sniffing and ClamAV; a two-part upload interrupted after part 1, reopened by `clientFileId`, the server naming the parts it holds, completed from the remaining part; a wrong checksum, text sent as video and EICAR all refused; control-point evidence recorded against video frames, persisted, used by the active frame, and the calibration attempt recorded |
| 3. Processing | `test_03_processing` | the full plan in order on real workers; only the four stages stood in; transitions seen through the persisted status; every artifact checksummed and under its tenant's prefix; the navmesh baked by the real tool; an abandoned claim's lease expiring, failed `WORKER_LOST`, a late report from the lost worker refused, the retry running attempt 2, earlier stages not repeated; a real stage failing on inconsistent upstream data, nothing after it running; a failed scan publishing nothing and the previous version staying current |
| 4. Viewer | `test_04_viewer`, then `scripts/e2e/viewer_format_check.cjs` | the `.ksplat` from the real `ARTIFACT_GENERATION`, served to a signed-in manager and through a public link, byte-identical to its record and passing the production viewer contract; refused to anonymous, cross-tenant and cross-venue callers; raw kinds 400; an unknown version 404; a corrupted stored model never delivered intact. In Chromium: the real web app loads it through both paths, renders it, places the POIs and draws the route |
| 5. Search | `test_05_search` | objects from `SEMANTIC_INDEXING` ingested as POIs; a query embedded by the real vision service; the red pillar first at its canonical position; venue isolation (a same-named POI in a sibling venue never appears; other tenants 404); a query with no match |
| 6. Navigation | `test_06_navigation` | a route on the navmesh this very run baked (its SHA-256), around the real obstacles; a step-free route; an off-floor destination and a fenced-in one refused; a two-floor route through registered stairs, step-free refused until the elevator's accessibility is verified, then taken |
| 7. Rescan | `test_07_rescan` | a changed-region capture through the real alignment (its own gates, a real method), the real splice and the real whole-floor re-bake (identical outside the region); version 1 kept, its model still served, still routable on its own navmesh; version 2 current and routed on |
| 8. Operations | `test_08_operations` | the persisted status (revision, progress from completed stages only); jobs listed; the lost worker and the real stage failure reported; a retry re-running only the failed stage and counting down; the dashboard's role matrix (viewer, operator, manager, other tenant, other venue, anonymous, service token) |

### Running it

```bash
# A throwaway stack (random credentials, removed afterwards, volumes included): what CI runs.
scripts/acceptance/run-acceptance.sh ci

# A stack that is already up, for example a staging host deployed from infra/deploy:
ACC_WEB_URL=https://app.<domain> scripts/acceptance/run-acceptance.sh existing <docker-compose.yml> <.env> <compose project>
```

`existing` creates two organizations, their venues and users, and four pipeline runs in the target, and starts a
test-only Open3D worker on its internal network for the duration. It corrupts one stored model on purpose and restores it
after the browser check. **Shared stacks:** the stand-ins claim only stages no shipped CPU worker claims. Run it against a
stack whose GPU workers are stopped, or they will race the stand-ins.

Evidence: `$ACC_OUT/acceptance-c.json` (every test and check, with what was expected and what happened),
`acceptance-c-viewer.json` (browser), `pytest.log`. Exit status 0 means every test passed, 1 means something failed, 3
means nothing failed but something was BLOCKED.

### Limits of what C proves

- The reconstruction is synthetic. Pose, splat, segmentation and detection quality are not tested here (classes D, E, F).
- The scene is the fixture's frame, floor and pillars, but with a 0.025 m floor and pillar sampling. The committed
  fixture's 0.1 m lattice puts one Gaussian per navmesh cell, and the real bake rightly refuses that as nothing walkable
  (`NO_WALKABLE_SURFACE`); with 0.1 m pillars next to a dense floor, the real cleanup rightly removes the pillars as
  sparse outliers. Both are the real stages behaving correctly on bad input.
- The lost-worker test waits for a real lease expiry: 40 s in the CI stack (`CHAYA_PIPELINE_LEASESECONDS`), 300 s on a
  production configuration.

## Classes D to G: how to run them, and the evidence a result needs

None of these can run on a CI runner or on the workstation this was written on. They are **NOT RUN** until someone with
the dependency runs them and files the evidence below. A run without its evidence does not count.

| Class | Run | Evidence required for a PASS |
|---|---|---|
| D. Real media | docs/E2E_VALIDATION.md §0 and §2 (`scripts/e2e/physical_pipeline.py`, the e2e stack with the CPU-COLMAP worker); the dataset (openMVG Sceaux Castle frames or your own photographs) is not committed for licensing reasons | the dataset's checksums and licence; the per-stage record (`pipeline_stage_run` rows: worker, command, exit status, timings, artifact checksums); COLMAP's registered-frame ratio; where and why the run stopped |
| E. GPU | on a CUDA host: `docker build -f services/reconstruction/Dockerfile.gpu`, `python -m chaya_worker.splat_preflight` (`"ok": true`), then `CHAYA_REQUIRE_GPU=1 python -m pytest -m gpu -rA` (a missing requirement then FAILS instead of skipping) | `nvidia-smi` output; the preflight report; the pytest log with every GPU test passed and none skipped; `splat-training-report.json` of the acceptance scene (iterations, loss curve, final PSNR), the image digest |
| F. Real venue | docs/REAL_VENUE_ACCEPTANCE.md: `scripts/acceptance/real_venue.py` against a deployed stack with a GPU worker | `report.json` from the script (every step's status, artifact checksums, calibration RMS); the measured control distances and their tape/laser readings; screenshots of the viewer at stated positions; routes walked on site with their measured lengths |
| G. Device AR | docs/ar-ios-validation.md, docs/ar-android-validation.md | device model and OS; the filled-in validation record in those documents (tracking states, relocalization, marker consistency, route drift at stated checkpoints); a screen recording |

## Results

See "Results, 2026-10-17" below: one table per classification, never one total.
