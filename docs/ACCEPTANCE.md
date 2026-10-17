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

## Python tests: where each one runs (2026-10-17)

`services/reconstruction` (and `services/vision`), by marker. Every CI step reports passed / failed / expected skip / unexpected
skip or not executed from pytest's JUnit XML (`scripts/ci/pytest-summary.py`); a required step fails on any skip outside its
allowlist (today one reason: "got empty parameter set").

| Class | Tests | Selection | Runs in | Needs (installed, pinned, in that job) |
|---|---|---|---|---|
| CPU unit | `tests/unit`, `tests/test_*.py`, `tests/ml` (training harness on tiny random models), vision `tests` | `not gpu and not open3d and not integration and not acceptance` | `python` workflow, `test` job (required) | CPU torch 2.7.1, transformers 4.57.1, the pinned Grounding DINO processor files (cached; tests run with `HF_HUB_OFFLINE=1`) |
| Synthetic reconstruction (CPU) | `tests/orchestration`, `tests/smoke` (incl. the pinned viewer library check), `tests/splat` (CPU trainer), `tests/navmesh` (real Recast/Detour) | same, plus the `navmesh` and splat steps | `python`, `test` job (required) | FFmpeg, the built `chaya-navmesh`, Node 22 + `npm ci` in `apps/web` |
| Synthetic reconstruction (Open3D, CPU) | `tests/gpu/test_region_alignment.py` (real FPFH), the cleanup/plane-fitting test in `tests/gpu/test_reconstruction_toolchain.py` | `open3d` | `python`, `open3d` job (required) | `open3d-cpu` 0.19.0. Kept out of the `test` job: its "toolchain missing" contract tests need an environment without Open3D. These were marked `gpu` before and ran nowhere. |
| Python service integration | `tests/integration/test_minio_storage.py` | `integration` | `python`, `minio-integration` job (required) | A throwaway MinIO (pinned mirror digest, random root credentials) |
| External infrastructure | `tests/integration/test_stack_e2e.py`; `tests/acceptance` | `integration`; `acceptance` | `reconstruction-smoke` (stack smoke); `acceptance` workflow | The stack (API, Keycloak, MinIO, ClamAV, ...) |
| GPU-dependent | `tests/gpu` (gsplat/CUDA, COLMAP/GLOMAP, splat acceptance) | `gpu` | `gpu` workflow, self-hosted runner labelled `gpu`, started by hand (`CHAYA_REQUIRE_GPU=1`) | A CUDA host, `Dockerfile.gpu`, model weights at pinned revisions |
| Real-media validation | the `tests/gpu` tests gated on `CHAYA_TEST_IMAGE_SEQUENCE` (real photos) and `CHAYA_SPLAT_DATASET` (a real COLMAP project) | `gpu` + input | `gpu` workflow inputs `image_sequence` / `splat_dataset` | The dataset on the GPU runner. Left empty, those tests are reported as not executed. |

On a CPU runner the `test` job lists the `gpu`, stack and acceptance tests as **not executed**, with each reason; they are never
counted as passed.

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
- The scene has a 2.5 m south wall and an east wall (registration structure for the re-scan). The viewer's initial view
  looks from −Y, so the browser check orbits the camera 180° before reading the POI overlay's colour, and records both views.
- The re-scan covers the east half (x 3.0–6.1 m): the moved blue pillar, the unchanged green pillar and the walls' corner.

## Classes D to G: how to run them, and the evidence a result needs

None of these can run on a CI runner or on the workstation this was written on. They are **NOT RUN** until someone with
the dependency runs them and files the evidence below. A run without its evidence does not count.

| Class | Run | Evidence required for a PASS |
|---|---|---|
| D. Real media | docs/E2E_VALIDATION.md §0 and §2 (`scripts/e2e/physical_pipeline.py`, the e2e stack with the CPU-COLMAP worker); the dataset (openMVG Sceaux Castle frames or your own photographs) is not committed for licensing reasons | the dataset's checksums and licence; the per-stage record (`pipeline_stage_run` rows: worker, command, exit status, timings, artifact checksums); COLMAP's registered-frame ratio; where and why the run stopped |
| E. GPU | on a CUDA host: `docker build -f services/reconstruction/Dockerfile.gpu`, `python -m chaya_worker.splat_preflight` (`"ok": true`), then `CHAYA_REQUIRE_GPU=1 python -m pytest -m gpu -rA` (a missing requirement then FAILS instead of skipping) | `nvidia-smi` output; the preflight report; the pytest log with every GPU test passed and none skipped; `splat-training-report.json` of the acceptance scene (iterations, loss curve, final PSNR), the image digest |
| F. Real venue | docs/REAL_VENUE_ACCEPTANCE.md: `scripts/acceptance/real_venue.py` against a deployed stack with a GPU worker | `report.json` from the script (every step's status, artifact checksums, calibration RMS); the measured control distances and their tape/laser readings; screenshots of the viewer at stated positions; routes walked on site with their measured lengths |
| G. Device AR | docs/ar-ios-validation.md, docs/ar-android-validation.md | device model and OS; the filled-in validation record in those documents (tracking states, relocalization, marker consistency, route drift at stated checkpoints); a screen recording |

## Results, 2026-10-17

One table per classification, never one total. Host: Windows 11, Docker Desktop (7.5 GiB VM), no GPU, no device.

| Class | What ran | Command | Result |
|---|---|---|---|
| **A. Unit** | worker `tests/unit` + liveness + model loading | `pytest tests/unit tests/test_liveness.py tests/test_model_loading.py -m "not gpu and not integration and not acceptance"` (.venv, Python 3.12) | **350 passed** |
| A. Unit | web unit tests; lint; typecheck | `npm test`, `npm run lint`, `npx tsc --noEmit` (apps/web) | **322 passed**; lint and typecheck clean |
| A. Unit | vision service | `pytest` inside the vision image | **8 passed** |
| A. Unit | backend untagged tests; iOS `ChayaARCore`; web Playwright specs (stubbed backend) | — | **NOT RUN this session** (C: had too little free disk for the Maven and Swift containers; no Java, Swift or web-UI change was made). Last recorded: backend 584 tests / 4 skips (2026-10-14), iOS core 72 (2026-10-15) |
| **B. Service integration** | Java `@Tag("integration")` (Testcontainers) | `mvn verify -Dgroups=integration` (CI `backend`, step B) | **NOT RUN this session** (same reason); runs in CI |
| **C. Synthetic fixture (component)** | worker `tests/orchestration` | as above, `tests/orchestration` | **67 passed, 1 skipped** (the allowed empty parameter set) |
| C | worker `tests/smoke`, `tests/splat`, `tests/navmesh` (real `chaya-navmesh`, MinGW build) | as above | **14**, **91**, **46 passed** |
| **C. Cross-service acceptance** | the suite above, throwaway stack, real services | `scripts/acceptance/run-acceptance.sh ci` | **40 / 40 PASS** (final run, 2026-10-17, 21 min including image builds); every scenario 1–8 |
| C. Cross-service acceptance (browser) | real web app in Chromium against the same stack | (same command) | **21 / 21 PASS**: public link A1–A7, Keycloak PKCE sign-in B0–B6, version switch C1–C2, corrupt model D1 |
| **D. Real media** | — | docs/E2E_VALIDATION.md | **NOT RUN** (no licensed dataset on this host) |
| **E. GPU** | — | `CHAYA_REQUIRE_GPU=1 pytest -m gpu` on a CUDA host | **NOT RUN** (Intel integrated graphics only) |
| **F. Real venue** | — | `scripts/acceptance/real_venue.py` | **NOT RUN** (no venue, no GPU worker) |
| **G. Device AR** | — | docs/ar-ios-validation.md, docs/ar-android-validation.md | **NOT RUN** (no device) |

The cross-service suite took four runs to reach that result. Runs 1–3 failed on defects in the suite (below), run 4 passed
40/40 but its browser half was broken and still exited 0 (S1). Evidence of the final run: `acceptance-c.json`,
`acceptance-c-viewer.json`, `pytest.log` in `$ACC_OUT` (CI uploads them as `acceptance-c-evidence`).

## Findings, 2026-10-17

What running the suite for real found. Product behaviour first, then defects in the suite itself (all fixed).

**Product (recorded, not changed here):**

| # | Finding | Evidence | Status |
|---|---|---|---|
| P1 | **A re-scan region dominated by a moved object is rejected in DIRECT_CANONICAL mode.** Region = the blue pillar (moved 1 m) plus two walls and the floor meeting in a corner. Scaling about that corner leaves all three planes in place, and ICP's first correspondence distance (1.0 m, `ALIGNMENT_ICP_SCHEDULE_M`) is larger than the 0.5 m correction DIRECT mode may make, so the moved pillar was paired with its old position and the region shrank by 0.62 about the corner. | `scale_correction 0.38`, `direct_translation_correction 0.60 m`, `ALIGNMENT_REJECTED`; reproduced offline with `align_region` (scale 0.617). | **Fails safe** (nothing spliced, the venue unchanged, verified). A fix was tried and **not** kept: capping DIRECT mode's ICP distances at the allowed correction accepted this case, but also **accepted a calibration 25 % off in scale** at a wrong scale of 1.06–1.09 with every gate passing, which is worse. A robust choice needs a design and the B5 alignment benchmark. Open. |
| P2 | The same alignment's confidence is sensitive to exact grid geometry: with the long wall moved from y = 0 to y = 4 m, a near-perfect solution (scale 1.0001, 0.3 mm) was rejected at confidence 0.55; at y = 3.99 m it was accepted at 0.67. The overlap count changed from 44,721 to 12,696 points. | offline `align_region`, production gates | Unexplained; synthetic, exactly gridded points (not seen on real scans, which have not been measured either). Needs a look before trusting confidence near the 0.6 gate. |
| P3 | A 0.5 m move in the same east-half region is rejected on confidence (0.59 < 0.6) while the 1 m move is accepted (0.81). | offline `align_region` | Gate is conservative; recorded for the rescan design. |
| P4 | The viewer's initial and focus views stand outside the scene on its −Y side. In an enclosed venue (a wall on that side) the first view shows the outside of a wall. | browser A4: rgb(152,140,127) (the wall) above the Red pillar marker until the camera is orbited | UX question, not changed. |
| P5 | A detected POI and a manual POI at the same place draw two markers on top of each other; only the top one can be clicked. | browser A4.1 (`.chaya-poi-marker[title="Red pillar"]` never clickable) | UX question. |
| P6 | Switching floors to a run whose model has the same SHA-256 as the one already shown displayed it without requesting it again, so an object that is now corrupted in storage was not re-checked on that page. A fresh page requests it, the API ends the stream (`ERR_CONTENT_LENGTH_MISMATCH`) and the viewer shows "Could not load the reconstruction". | browser network log, floor 0 v1 → floor 1 | The bytes shown are the bytes the record names (verified when first loaded), so nothing wrong is displayed; recorded because it skips the storage re-check. Not investigated further. |
| P7 | The failure-propagation run fails at GEOMETRIC_CLEANUP (`INPUT_INVALID: SEMANTIC_LABELS has 95615 entries but the splat has 95622 Gaussians`), the first real stage that reads the labels, not at NAVIGATION_BAKING as the suite's docstring said. | 3.17/3.18 | Correct behaviour; docstring fixed. |

**The suite (fixed):**

| # | Defect | Effect | Fix |
|---|---|---|---|
| S1 | The browser check exited 0 with FAILs recorded, and the runner trusted the exit status | **browser failures could never fail the run** (run 4 exited 0 with 19 browser FAILs) | the check exits 1 on any FAIL; the runner also fails on any `"FAIL"` in either results file |
| S2 | The browser check opened `/viewer` without choosing a floor; the viewer auto-selects only a venue's single floor, and this venue has two | no model was ever requested (A1/B1 "no model request seen") | `pickFloor` uses the viewer's own floor selector |
| S3 | The version-switch and corrupt-model checks navigated with `page.goto`, which drops the sign-in (tokens are in memory only) | C/D always failed | driven through the page's selectors; D on its own fresh signed-in page (P6) |
| S4 | Any exception aborted the browser script | every later check unrecorded | each section records its failure and the next runs; diagnostics (page text, failed requests) on a missing model |
| S5 | `CHAYA_ALLOWED_ORIGINS` (smoke overlay) and chaya-web's redirect URI (realm file) are fixed to port 3000 | with `ACC_WEB_PORT` ≠ 3000, CORS failed and sign-in would fail | origin from `WEB_PORT`; the runner sets chaya-web's redirect and origin to the web URL (as `keycloak-configure.sh` does) |
| S6 | On Windows, `node` received the POSIX handoff path | browser check never ran (`ENOENT C:\tmp\...`) | native paths; a missing result file is recorded as FAIL |
| S7 | 5.7 queried right after creating POIs; embeddings are back-filled every 5 s | `UNFILTERED` instead of `FILTERED` (race) | waits for the product's own `awaitingEmbedding == 0` (ops search-analytics) and records it (5.7a) |
| S8 | 5.4 took the first auto-detected red pillar; floor 1 has one at the same (x, y) | flaky floor check | selects floor 0's detection |
| S9 | 7.4 read splice keys that do not exist; 7.5 read `rebuild` from the command record (it is in the NAVMESH_MANIFEST) | false failures | real keys, plus `merged == venue − removed + added` and the seam limit; 7.5 reads the published manifest (SHA-256 checked) |
| S10 | 7.10 routed version 1 to a POI placed on version 2 | `404 destination POI is not part of scan version` (correct API behaviour) | routes to a waypoint version 1 holds |
| S11 | The re-scan region (x ≥ 4.4, y ≤ 2.7) held only the moved pillar and a corner | rejected by the real alignment (P1) | region = east half, with the unchanged green pillar; the re-scan's detector stand-in also reports the green pillar |

## Capability matrix

C = the cross-service suite (`run-acceptance.sh ci`, a Docker host; in CI the `acceptance` workflow on `ubuntu-latest`).
Result = the final run of 2026-10-17. "Stand-in" = one of the four labelled stages (above).

| Capability | Test | Environment | Result | Remaining limitation |
|---|---|---|---|---|
| Operator login (Keycloak) | 1.1–1.6; browser B0 | C | PASS | password grant via a test client; PKCE via the real web app |
| Venue authorization | 1.7–1.10 | C | PASS | — |
| Expired token | 1.11–1.14 | C | PASS | accepted until exp + 60 s (Spring's clock skew), measured |
| Cross-tenant rejection | 1.15–1.18, 4.5, 5.6, 8.9 | C | PASS | — |
| Venue + capture session | 2.1 | C | PASS | organizations are seeded in SQL (no API exists) |
| Valid media upload | 2.2–2.3 | C | PASS | synthetic face video, not a venue capture |
| Resume interrupted upload | 2.4–2.6 | C | PASS | — |
| Reject invalid files | 2.7–2.9 | C | PASS | real ClamAV (EICAR) |
| Calibration evidence | 2.10–2.13 | C | PASS | control points by construction, not surveyed |
| Submit job, stage transitions | 3.1–3.6 | C | PASS | 4 stages stand-ins (labelled, checked by 3.4) |
| Worker lease handling | 3.11–3.16 | C | PASS | lease 40 s in the CI stack (300 s in production) |
| Artifact manifests | 3.7–3.10 | C | PASS | — |
| Failure propagation | 3.17–3.20 | C | PASS | failure induced through stand-in labels; the refusing stage is real |
| Scan-version publication | 3.21–3.22, 7.6 | C | PASS | — |
| Retrieve generated artifact | 4.1–4.6; browser A1, B1 | C | PASS | — |
| Load in the real viewer | browser A2–A5, B2–B5 | C (Chromium, software WebGL) | PASS | camera orbited first (P4) |
| Switch versions | browser C1–C2; 7.7–7.8 | C | PASS | — |
| Missing / corrupt models | 4.7–4.9; browser D1 | C | PASS | fresh page needed (P6) |
| Search indexed objects | 5.1–5.4 | C | PASS | detections are stand-ins (CLIP **text** vectors of labels) |
| Search venue isolation | 5.5–5.6 | C | PASS | — |
| Result coordinates | 5.4 | C | PASS | positions by construction |
| Empty results | 5.7a–5.7 | C | PASS | needs ≥ 8 embedded POI texts (product rule) |
| Route on the run's navmesh | 6.1–6.4 | C | PASS | real Recast bake of a synthetic scene |
| Accessible routing | 6.5–6.6, 6.11–6.12 | C | PASS | — |
| Unreachable destinations | 6.7–6.8 | C | PASS | — |
| Multi-floor transitions | 6.9–6.12 | C | PASS | connections registered by hand |
| Changed-region re-scan | 7.1–7.5 | C (real Open3D worker, test-only image) | PASS | P1–P3: a region dominated by a moved object is rejected |
| Historical versions | 7.6–7.10 | C | PASS | — |
| Persisted status | 8.1–8.3 | C | PASS | — |
| Failed worker detection | 8.4–8.5 | C | PASS | — |
| Retry | 8.6–8.8 | C | PASS | — |
| Dashboard access | 8.9–8.11 | C | PASS | — |
| Unit logic (worker, web, vision) | class A | host .venv / node / vision image | PASS (350 / 322 / 8) | backend, iOS, Playwright not re-run (disk) |
| Reconstruction quality (COLMAP, gsplat, models) | classes D, E | CUDA host, dataset | NOT RUN | evidence requirements above |
| Real venue end to end | class F | deployed stack + GPU worker + venue | NOT RUN | — |
| Device AR | class G | phone at the venue | NOT RUN | — |
