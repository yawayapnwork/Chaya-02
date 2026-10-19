# End-to-end validation against *Chaya_02_Winning_Solution.docx* (2026-10-19)

**Question:** can Chaya-02 turn a real physical venue into a usable digital twin with object search and indoor
navigation?

**Answer: not demonstrated.** The workflow ran on real data up to and including Structure-from-Motion. There it
produced accurate camera poses: 1.7 mm RMSE against laser-scan ground truth, after a similarity fit. It also accepted a
real metric calibration, with a scale 1.67 % off. It then stopped, as designed, at Gaussian Splatting: this host has no
CUDA GPU. Everything that makes the twin usable comes after that point and has **not** been run on real data: the viewer
model, cleanup, segmentation, navmesh, detection, search, routes, AR and re-scan. **This is not a successful final
report.**

- **Code validated:** `main` at `e47948b`, unmodified. Nothing was committed or pushed.
- **Host:** Intel Core Ultra 5 125H, 16 GB RAM, Intel integrated graphics (no CUDA), Docker Desktop VM 7.5 GB, about
  5 GB free on C:. No Android or iOS device.
- **Evidence:** `docs/e2e-evidence/2026-10-19-eth3d-office/` (records and analysis scripts; no images, no secrets).

## Real data used

No venue capture with documented measurements exists on this machine. With the owner's agreement, the run used a public
real dataset:

- **ETH3D high-resolution multi-view benchmark, scene `office`** (`office_dslr_undistorted.7z`, 288,765,137 bytes, 7z
  CRCs verified, licence CC BY-NC-SA 4.0).
- 26 real DSLR photographs of one office room, 6221 × 4146 pixels.
- ETH3D's published camera calibration, and its ground-truth camera poses and 3-D points. These are registered to a
  laser scan, in metres.

What this data is and is not:

- It **is** real imagery of a real indoor room, with documented metric ground truth.
- It is **not** the project's venue. It is **not** phone video (stills only, so the video frame-extraction path was not
  exercised). It has **one room and one floor**. It contains **no staircase, reception, elevator or exit**.
- "Measurements" are distances between ETH3D ground-truth points (laser-registered), each marked at its ground-truth
  pixel in two photos. They play the role of tape measurements. No one measured this room for Chaya.

---

## 1. Execution report

### Phase 1: Real capture (through the production API: Keycloak, Spring API, MinIO, ClamAV, Tika, Postgres)

| Step | Result | Evidence |
|---|---|---|
| 1. Create venue | **Done.** Venue `eth3d-office` created by an admin token through `POST /venues`. The organization row was seeded in Postgres, since there is no API for creating one (as the acceptance runner does). | `tools/setup_venue.py`; ids in `report.json` |
| 2. Floors and rooms | **Floor done** (level 0). **Rooms not configured**: the dataset is one room, and multi-room capture was not exercised. | — |
| 3. Capture session | **Done** (`POST /captures` with the floor). | `run1.log` |
| 4. Upload real footage | **Done.** 26 real photos plus one camera-calibration metadata file went through the resumable multipart protocol with per-part SHA-256. All 27 were **ACCEPTED**. Footage is DSLR stills, not video. | `run1.log`, `media.json` |
| 5. Validate input media | **Done.** Per file: stored size and SHA-256 match, ClamAV scan clean, Tika detects `image/jpeg` matching the claim, decoded size 6221 × 4146. INPUT_VALIDATION **SUCCEEDED**: the calibration matched every image. | `media.json`, `stage-outputs/input-report.json` |
| 6. Privacy processing | **Ran and SUCCEEDED.** **Real-data defect**, see D1: the face detector reported 11 "faces" in 8 of 21 frames, but the frames checked contain no people. Up to 83 % of one frame was masked, and one frame was escalated to a solid fill. Screens: 0 detected (the four monitors in view were switched off). After privacy, the pre-privacy frame archives (`pii/…`) no longer exist in storage, as designed. | `stage-outputs/privacy-report.json`; MinIO listing in this report |
| 7. Calibration references | **Done.** 8 `DISTANCE` measurements (4 + 4 re-marked in registered views, see Phase 2) were recorded, all `201`. | `api-calibration.json` |
| 8. Capture metadata | **Done.** The PINHOLE calibration (fx 3437.84, fy 3435.95, cx 3127.19, cy 2066.98, CORNER origin) was accepted, then rescaled with the frames to 1621 × 1080. | `stage-outputs/camera-calibration.json` |

### Phase 2: Reconstruction

The workers ran on a local stack built from `e47948b`:

- the production CPU worker image (`acceptance-cpu-worker`): validation, frames, quality, privacy;
- the current worker code in a local COLMAP 3.8 (CPU) image (`colmap-cpu-worker`): POSE_ESTIMATION and
  SPLAT_RECONSTRUCTION.

The production GPU image (`Dockerfile.gpu`) cannot be built or run here: it needs a CUDA base image and more disk than
is free.

| # | Stage | Status | Output (measured) |
|---|---|---|---|
| 1 | Frame extraction (FFMPEG_PREPROCESS, image path) | **SUCCEEDED** | 26 frames at 1621 × 1080; calibration rescaled. Wall time 9.95 s. |
| 2 | Quality filtering (FRAME_QUALITY_FILTER) | **SUCCEEDED**, with defect D2 | 21 of 26 kept. 5 rejected as "blurred" (sharpness 15–36 vs. threshold 40); the one inspected is sharp but low-texture. |
| — | Privacy (PRIVACY_PREPROCESS) | **SUCCEEDED**, with defect D1 | See Phase 1. |
| 3 | SfM pose estimation (POSE_ESTIMATION, COLMAP 3.8 CPU) | **Attempt 1 FAILED; attempt 2 SUCCEEDED** | Attempt 1: `FEATURE_EXTRACTION_FAILED`, COLMAP killed by the kernel OOM killer (2.5 GB RSS on 18 threads; defect D3). Attempt 2, with `COLMAP_NUM_THREADS=4` and the API retry: **14 of 21 frames registered** (minimum ratio 0.6). Accuracy: **1.7 mm RMSE, 4.0 mm max** camera-centre error against ETH3D ground truth after a 7-DoF similarity fit (cameras span 1.3 m). GLOMAP: not present ("not found on PATH"). |
| 4 | Gaussian Splatting on CUDA | **FAILED: BLOCKED** | `DEPENDENCY_UNAVAILABLE`: "torch is not installed; gsplat is not installed". This is the correct refusal; no placeholder was produced. **No CUDA GPU on this host.** |
| 5–10 | Cleanup, segmentation, navmesh, detection, embeddings, publication | **NOT RUN** | All depend on stage 4. The run is `FAILED` at SPLAT_RECONSTRUCTION, with retries remaining. |

**Calibration on real data:**

- Each recorded point was triangulated from its marked pixels through this run's real poses (`tools/calib_eval.py`):
  the operator step the API documents as not automated.
- `POST /calibration` was then submitted twice:
  - with `RECONSTRUCTED_FLOOR_PLANE` gravity: `409 GRAVITY_UNAVAILABLE`. This is correct: no floor plane exists without
    the later stages.
  - with gravity `NONE`: **201**, frame `METRIC`, `scale 0.173646`, `scaleRelativeSpread 2.87 %` (limit 3 %),
    `gravityStatus NOT_ALIGNED`.
- The ground-truth scale (similarity fit to ETH3D) is 0.176592, so **the accepted metric scale is 1.67 % low**.
- Only 1 of the 4 originally marked distances was resolvable: their marks were in frames that did not register. See D6.

**Every stage's status and output** is in `processing.json`: stage, attempt, worker, command, exit status, artifacts
with SHA-256. The 9 retrievable artifacts were re-downloaded from MinIO, and **9/9 SHA-256 values match** the recorded
ones.

### Phase 3: Viewer. **BLOCKED** (no real `.ksplat`: stage 4)

Not run on real data. Today's acceptance browser check loaded a **synthetic** fixture `.ksplat` in Chromium:

- B1–B5, C1–C2 and D1 passed: load, splat count, render, POI overlay, orbit controls, version switch, corrupt model
  refused.
- **A6/B6 FAILED**: an unexpected console error (defect D5).

That proves nothing about orientation, scale or appearance of a real reconstruction.

### Phase 4: Semantic search. **BLOCKED**, and **NOT TESTABLE on this dataset**

No detections or embeddings exist (stages 8–9 not run). Independently, a single office room contains none of the four
requested objects, so even with a GPU these queries could not count as successes:

| Query | Status |
|---|---|
| Find the nearest staircase | NOT TESTED: blocked; not present in the dataset |
| Find the reception | NOT TESTED: blocked; not present in the dataset |
| Find the elevator | NOT TESTED: blocked; not present in the dataset |
| Find the nearest accessible exit | NOT TESTED: blocked; not present in the dataset |

The search mechanism (real CLIP vision service, pgvector) passed today's acceptance tests 5.x on **synthetic** indexed
objects.

### Phase 5: Navigation. **BLOCKED** (no real navmesh: stage 7)

Today's acceptance tests 6.x passed on a real Recast bake of a **synthetic** venue:
- route generation and obstacle avoidance;
- step-free routing;
- unreachable destinations refused with a reason;
- crossing floors only through registered connections.

None of this has been compared with a real walkable path.

### Phase 6: AR. **BLOCKED** (no physical devices)

| Platform | Result |
|---|---|
| Android (Chrome, WebXR) | **NOT RUN**: no ARCore device. Procedure: docs/ar-android-validation.md (including §6d obstacle sensing). |
| iOS (ARKit app) | **NOT RUN**: no iPhone and no Mac/Xcode. Procedure: docs/ar-ios-validation.md, docs/ar.md "Obstacle sensing (iOS)". |

No physical AR validation is claimed. Unit tests of the AR state machines and math passed earlier today (web 383,
Swift core 83); they are synthetic.

### Phase 7: Incremental re-scan. **BLOCKED** (needs a real base reconstruction: stage 4)

Today's acceptance tests 7.x passed on **synthetic** geometry: real Open3D alignment, real splice, re-bake identical
outside the region, and older versions preserved.

### Phase 8: Performance. See §3. Only stages that ran were measured.

### Phase 9: Failure recovery

| Failure | Tested how | Result |
|---|---|---|
| Invalid media | Acceptance 2.x (`test_invalid_files_are_rejected`): **synthetic** files, real ClamAV and Tika | PASS |
| Insufficient image overlap / SfM failure | **Not reproduced with real data.** No non-overlapping real set of at least 20 images exists here. The real SfM run did fail (out of memory, below) and was reported correctly. | NOT TESTED (overlap) |
| SfM process failure | **Real**: COLMAP killed by OOM on real images | `FEATURE_EXTRACTION_FAILED`, retryable; nothing after it ran; the API retry re-ran only that stage, which then succeeded. **PASS** |
| GPU unavailable | **Real**: SPLAT_RECONSTRUCTION on a worker without torch/gsplat | `DEPENDENCY_UNAVAILABLE`, run `FAILED`, no placeholder artifact. **PASS** |
| GPU out of memory | Needs a GPU | **BLOCKED** |
| Worker crash | Acceptance 3.x (`test_a_lost_worker_lease_is_detected_failed_and_retried`): a real lease expiry, synthetic job | PASS |
| Network interruption | Acceptance 2.x (`test_an_interrupted_upload_resumes_with_only_the_missing_parts`): synthetic file | PASS. Client-side offline queue: earlier Playwright evidence, not re-run. |
| Object storage outage | **Real**: MinIO stopped on the live stack while uploading a real photo | Readiness and health `503`; upload refused with `503 STORAGE_UNAVAILABLE` "retry later". After restart: readiness `200`, and the same photo uploaded and **ACCEPTED**. **PASS** |
| Authentication expiration | Acceptance 1.x (`test_an_expired_token_is_refused`) with real Keycloak | PASS. Found by the real run: the real-venue harness cannot survive token expiry (D4). |
| Corrupt artifacts | Acceptance 4.x (missing/corrupt model refused) and browser D1: synthetic model | PASS |
| Unreachable destinations | Acceptance 6.x (`test_unreachable_destinations_are_refused_with_a_reason`): synthetic navmesh | PASS |
| AR tracking loss | Unit tests only (synthetic); no device | **BLOCKED** (device) |

The acceptance suite ran today on `e47948b` against real services: **40/40 passed in 4 min 38 s.** Its scene is
synthetic, and it uses labelled stand-ins for POSE_ESTIMATION, SPLAT_RECONSTRUCTION, SEMANTIC_SEGMENTATION and
SEMANTIC_INDEXING.

---

## 2. Requirements compliance matrix

Classes, as requested:

| Class | Meaning here |
|---|---|
| **VERIFIED WITH REAL DATA** | Executed today on the ETH3D real photos, or against real services where the requirement needs no venue data. |
| **VERIFIED WITH SYNTHETIC DATA ONLY** | Executed (today, or in a dated record) only on synthetic scenes or fixtures. |
| **IMPLEMENTED BUT UNVERIFIED** | Code exists; no execution evidence for the requirement as stated. |
| **PARTIALLY IMPLEMENTED** | Part of what the blueprint promises does not exist. |
| **NOT IMPLEMENTED** | Nothing does it. |
| **BLOCKED BY EXTERNAL REQUIREMENTS** | Implemented, but verification needs hardware, devices, hosting or data this project does not have (named). |

Evidence tags: **[today]** run on 2026-10-19; **[audit]** docs/FINAL_AUDIT_2026-10-17.md, not re-run today.

### Capture

| # | Requirement (blueprint §) | Class | Source | Test evidence | Actual result | Remaining limitation | Corrective action |
|---|---|---|---|---|---|---|---|
| 1 | Auto-planned capture path from a recon lap (§3, §7) | VERIFIED WITH SYNTHETIC DATA ONLY | `api/.../planning`, `.../hud`; `CaptureHud.tsx` | B1 benchmark [audit] | Synthetic floor plans only | Never run on a phone | One guided capture on an ARCore phone |
| 2 | Live coverage HUD with sparse point cloud and 3-D heatmap; per-frame blur scoring (§3) | PARTIALLY IMPLEMENTED | `lib/capture-quality.ts`, `hud` API | Web unit tests [today: 383 web unit pass] | 2-D coverage grid; no point cloud or 3-D heatmap; server round-trip | The same Laplacian-style sharpness misjudges low-texture views (D2) | Add depth-based points; replace sharpness with a blur measure robust to texture |

### Reconstruction

| # | Requirement (blueprint §) | Class | Source | Test evidence | Actual result | Remaining limitation | Corrective action |
|---|---|---|---|---|---|---|---|
| 3 | Multi-room stitching with loop closure (§3) | VERIFIED WITH SYNTHETIC DATA ONLY | `stages/room_registration.py`, `room_stitch.py` | Benchmark 7, real FPFH [audit note 2026-10-18] | 0/5 synthetic trials stitched under the default policy | No real room stitched (GPU) | Real two-room capture after GPU access |
| 4 | Multi-floor: Z-axis floor segmentation and a transition graph (§3) | PARTIALLY IMPLEMENTED | `venue` floors and connections, `navigation` | Acceptance 6.x [today] | Transition graph routes; floors are staff-entered | No automatic Z segmentation | Implement it, or correct the claim |
| 5 | FFmpeg ingestion and quality filtering (§4, §7) | VERIFIED WITH REAL DATA | `stages/ffmpeg_preprocess.py`, `frame_quality_filter.py` | Real run [today] | 26 real photos → 1621 × 1080 frames; 21 kept | 5 sharp low-texture frames rejected (D2); video path not exercised with real footage | Fix D2; run with real phone video |
| 6 | SfM: COLMAP (fallback) | VERIFIED WITH REAL DATA | `stages/pose_estimation.py` | Real run, poses vs. ETH3D GT [today] | 14/21 registered; **1.7 mm RMSE** (similarity-aligned) | 33 % of kept frames unregistered (low-texture walls, privacy masks); default threads cause OOM (D3) | D3; re-measure registration after D1/D2 |
| 7 | SfM: GLOMAP primary (§4) | NOT IMPLEMENTED (in any image) | optional code path | Worker toolchain report [today]: "glomap not found" | — | — | Add GLOMAP to `Dockerfile.gpu`, or call COLMAP primary |
| 8 | Gaussian Splatting (gsplat), iteration budget, checkpointing, background refine (§3, §4, §6.3) | BLOCKED BY EXTERNAL REQUIREMENTS (CUDA GPU) | `stages/splat_reconstruction.py`, `Dockerfile.gpu` | Real run [today]: `DEPENDENCY_UNAVAILABLE` | Never trained | Background refinement after overrun: not implemented [audit] | A10/L4-class host: build `Dockerfile.gpu`, preflight, `pytest -m gpu`, re-run this capture |
| 9 | Geometry cleanup (Open3D) (§3, §4) | VERIFIED WITH SYNTHETIC DATA ONLY | `geometric_cleanup.py`, `plane_fitting.py` | Acceptance 3.x [today] | Real Open3D on a synthetic scene | Radius-filter thinning defect P1-14 [audit], not re-verified | After #8: run on a real splat |
| 10 | Floater-removal ablation as a PSNR/SSIM table (§3, §4) | NOT IMPLEMENTED | — | — | No table exists | Needs a real splat | After #8 |
| 11 | Fine-tuned semantic segmentation (§3, §4) | PARTIALLY IMPLEMENTED | `semantic_segmentation.py` | B6 stock model [audit]; acceptance uses a stand-in | Stock SegFormer; weights research-only | No fine-tuning; licence | Licensed base model; label real frames |
| 12 | Fine-tuned Grounding DINO detection (§3) | PARTIALLY IMPLEMENTED | `semantic_indexing.py` | B6 [audit] | Stock model | No labelled render set | After #8: label real frames |

### Search, navigation and AR

| # | Requirement (blueprint §) | Class | Source | Test evidence | Actual result | Remaining limitation | Corrective action |
|---|---|---|---|---|---|---|---|
| 13 | CLIP embeddings and vector search, synonyms, voice (§3, §4, §5.2) | VERIFIED WITH SYNTHETIC DATA ONLY | `api/.../search`, `services/vision` | Acceptance 5.x with the real vision service [today] | Mechanism works | pgvector, not FAISS; relevance on a venue unmeasured; Phase 4 queries untestable here | Real venue with those objects |
| 14 | Recast navmesh, wheelchair toggle, multi-destination (§3, §4) | VERIFIED WITH SYNTHETIC DATA ONLY | `NAVIGATION_BAKING`, `chaya-navmesh`, `api/.../navigation` | Acceptance 6.x [today] | Real Recast on a synthetic venue | Server-side only (no Detour WASM); no real walkable path compared | After #8: route a real navmesh; walk it |
| 15 | Obstacle rerouting from the AR camera (§3) | BLOCKED BY EXTERNAL REQUIREMENTS (devices) | `lib/obstacle-*.ts`, `ObstacleSensing.swift`, `ObstacleSensor.swift` | Unit tests [today: web 383, Swift 83] on synthetic depth | Implemented | No device; Android has no person detector; ARKit adapter only parse-checked | ar-android-validation.md §6d; iOS checklist |
| 16 | Android WebXR AR (§3, §4) | BLOCKED BY EXTERNAL REQUIREMENTS (ARCore phone) | `ArWorkspace.tsx`, `lib/ar-*` | Unit tests [today] | Never run on a phone | Android needs a Keycloak sign-in | Run docs/ar-android-validation.md |
| 17 | iOS ARKit app (§3, §4) | BLOCKED BY EXTERNAL REQUIREMENTS (iPhone, Mac) | `apps/ios-ar` | Swift core 83 [today]; app XCTest in CI only | Never run on a device | — | Run docs/ar-ios-validation.md |
| 18 | Multi-anchor AprilTag relocalization, VIO interpolation, floor handoff (§3, §4) | PARTIALLY IMPLEMENTED | `ar-relocalization.ts`, `AnchorMath.swift`, `FloorHandoff.swift` | Unit tests [today] | Image-target markers, not AprilTag | Drift never measured | AprilTag detector, or correct the claim; device drift measurement |

### Re-scan and operations

| # | Requirement (blueprint §) | Class | Source | Test evidence | Actual result | Remaining limitation | Corrective action |
|---|---|---|---|---|---|---|---|
| 19 | Incremental re-scan: ICP/feature alignment, splice, update navmesh/index (§3, §7) | VERIFIED WITH SYNTHETIC DATA ONLY | `api/.../rescan`, `region_alignment.py`, `region_splice.py` | Acceptance 7.x [today] | Real Open3D on synthetic geometry | DIRECT-mode false rejection (P1-11) [audit] | After #8: real re-scan |
| 20 | Version history (§3, §7) | VERIFIED WITH SYNTHETIC DATA ONLY | `ScanVersionService` | Acceptance 3.21–3.22, 7.x, browser C1–C2 [today] | Old version served byte-exact | No real versions exist | — |
| 21 | Ops dashboard: freshness, coverage gaps, jobs, search analytics (§3) | VERIFIED WITH SYNTHETIC DATA ONLY | `api/.../ops`, `OpsDashboard.tsx` | Acceptance 8.x [today] | Works on synthetic runs | — | — |
| 22 | Processing cost tracked from real observed usage (§4, §9) | Measurement: VERIFIED WITH REAL DATA. Estimate: IMPLEMENTED BUT UNVERIFIED. Billed cost: BLOCKED BY EXTERNAL REQUIREMENTS | `api/.../cost`, V42, `chaya_worker/resources.py` | `job_attempt_usage` rows of the real run [today] | 7 attempt rows with wall and CPU time, the OOM attempt included; GPU null with reason; cost `NO_RATES_CONFIGURED` | No GPU, no rate card, no provider bill | Configure rates on the GPU host; record a real bill |

### Platform, security and data

| # | Requirement (blueprint §) | Class | Source | Test evidence | Actual result | Remaining limitation | Corrective action |
|---|---|---|---|---|---|---|---|
| 23 | Keycloak OIDC, JWT, RBAC, viewer links, service accounts, audit (§5.1) | VERIFIED WITH REAL DATA (real services; no venue data needed) | `api/.../security`, realm | Real run used real users, roles and venue claims [today]; acceptance 1.x [today] | Works | Keycloak events not in `audit_log` [audit] | — |
| 24 | Tenant isolation (§5.1) | VERIFIED WITH REAL DATA (real services) | tenancy checks | Acceptance 1.15–1.18 etc. [today]; real run's org/venue-scoped keys [today] | Works | — | — |
| 25 | Upload security: Tika, ClamAV, limits (§6.5) | VERIFIED WITH REAL DATA | `api/.../capture` | 27 real files [today]; EICAR in acceptance [today] | All real files scanned, sniffed, accepted | ClamAV size limit for >2 GiB videos [audit] | — |
| 26 | Face blur (RetinaFace/MediaPipe) and screen/document blur (YOLOv8) (§6.1) | PARTIALLY IMPLEMENTED | `chaya_worker/privacy` | Real run [today] | Haar faces and a heuristic quad; **11 false faces in an unoccupied office**, up to 83 % of a frame masked | Recall unmeasured (no people in the data) | D1: replace the detector; measure on frames with people |
| 27 | Object storage (MinIO) and Postgres/pgvector (§5.2) | VERIFIED WITH REAL DATA | `storage`, migrations | Real artifacts re-hashed 9/9 [today]; real outage and recovery [today] | Works; a storage outage is refused and recovered | — | — |
| 28 | Redis (§5.2) | NOT IMPLEMENTED (removed by decision) | — | [audit] | — | — | Correct the blueprint |
| 29 | Postgres job queue, leases, retries (§6.9) | VERIFIED WITH REAL DATA | `ProcessingJobRepository`, `PipelineService` | Real OOM failure, bounded API retry, success [today]; lease loss in acceptance [today] | Works | — | — |

### Failure handling, operations and infrastructure

| # | Requirement (blueprint §) | Class | Source | Test evidence | Actual result | Remaining limitation | Corrective action |
|---|---|---|---|---|---|---|---|
| 30 | Failure handling: ORB pre-flight, tracking loss, offline queue, gsplat checkpoints (§6.3) | IMPLEMENTED BUT UNVERIFIED | browser capture, AR reducers, splat checkpointing | Unit tests and earlier Playwright [audit] | — | Device and GPU parts blocked | Device and GPU runs |
| 31 | Monitoring: Prometheus, Grafana, Loki, GlitchTip (§6.4) | IMPLEMENTED BUT UNVERIFIED | `infra/monitoring` | compose config [audit] | — | Never run against a deployment | Run with the staging deploy |
| 32 | Backup and DR, off-machine copy (§6.6) | VERIFIED WITH SYNTHETIC DATA ONLY; off-site: NOT IMPLEMENTED | `scripts/backup` | Restore drill [audit] | — | No off-site copy; Keycloak DB not in `backup.sh` | [audit] P2 |
| 33 | CI/CD, GHCR, SSH deploy, Checkstyle/SpotBugs (§5.3) | PARTIALLY IMPLEMENTED (not re-checked today) | `.github/workflows` | [audit] | `deploy` had never succeeded | No SpotBugs/Checkstyle | [audit] |
| 34 | Hosting (Vercel, Render, Neon, UptimeRobot, Kaggle, CDN) (§5.4) | NOT IMPLEMENTED (replaced by one self-hosted host); deploy BLOCKED (host, DNS, secrets) | `infra/deploy` | [audit] | — | No judge link | Provision staging |
| 35 | Licensing audit (§6.2) | IMPLEMENTED (finding: SegFormer weights research-only) | `scripts/licenses` | [audit] | — | P1-5 | Licensed checkpoint |
| 36 | Device-tier performance, LOD by frame time (§6.7) | IMPLEMENTED BUT UNVERIFIED | `device-profile.ts`, `frame-quality.ts` | Unit tests | No device or real-model figures | — | Low-end Android with a real `.ksplat` |
| 37 | Usability test with non-technical users (§6.8) | NOT IMPLEMENTED | — | — | — | — | Run it after a real venue exists |
| 38 | Unit economics from real GPU billing; venue validation conversation (§9) | NOT IMPLEMENTED / BLOCKED (no GPU billing) | cost tracking (#22) | — | — | — | GPU host with billing |
| 39 | Metric scale from measured references (§7, coordinate frames) | VERIFIED WITH REAL DATA | `CoordinateFrameService`, `captures/{id}/calibration` | Real calibration [today] | Accepted; **scale 1.67 % low** vs. laser-registered GT; gravity not aligned (no floor plane) | Operator must triangulate points; marks in unregistered views are accepted silently (D6) | D6; automatic triangulation |

---

## 3. Actual performance measurements (only what ran; nothing estimated)

| Measurement | Value | Source and caveat |
|---|---|---|
| Upload of the capture (26 photos plus metadata, 280 MB) | 4 min 57 s from harness start to the last file ACCEPTED, including per-file ClamAV, Tika and SHA-256 | `run1.log`. Each request from Python on Windows to `localhost` carried about 2 s of client name-resolution overhead (also seen in the outage test), so this is **not** server throughput. |
| INPUT_VALIDATION | 9.93 s wall, 9.67 s CPU | `job_attempt_usage` (V42) |
| FFMPEG_PREPROCESS | 9.95 s wall, 12.84 s CPU; 7.5 MB frame archive | same |
| FRAME_QUALITY_FILTER | 0.39 s wall, 0.71 s CPU | same |
| PRIVACY_PREPROCESS | 6.69 s wall, 74.60 s CPU | same |
| POSE_ESTIMATION, attempt 1 (OOM) | 35.56 s wall, 99.80 s CPU, then killed | same; 18 threads, 2.5 GB RSS at kill |
| POSE_ESTIMATION, attempt 2 | **24.70 s wall, 87.70 s CPU**, 4 threads; sparse model 620 KiB, poses 6.6 KiB | same |
| SPLAT_RECONSTRUCTION | 0.22 s until refused | same |
| GPU execution time | **none**: no GPU | GPU columns null, with the reason recorded |
| Processing run, start to failure at SPLAT | 3 min 29 s (06:07:42 → 06:11:11 UTC), including the OOM attempt and a manual worker restart | `processing.json` |
| SfM accuracy | 1.7 mm RMSE, 4.0 mm max, 14 cameras | `pose-eval.json` |
| Calibration accuracy | −1.67 % scale vs. GT | `api-calibration-gravity-none.json` |
| Artifact sizes | frames 7.5 MB; selected 6.6 MB; anonymized 5.3 MB; privacy masks 140 KiB; sparse model 620 KiB | `processing.json` |
| Storage outage | readiness 503 while MinIO was down; MinIO healthy and uploads accepted again within 22 s of the stop (outage test included) | `outage/` |
| Acceptance suite (synthetic scene, real services) | 40 tests in 4 min 38 s | `acceptance/pytest.log` |

**Not measured, because the stage never ran:** reconstruction to `.ksplat`, `.ksplat` size, viewer load time and frame
rate, search latency, route latency, AR tracking and relocalization, and incremental update duration.

**Resource note:** with the stack, the COLMAP worker and the owner's other stacks running, host free RAM fell to about
0.9 GB. The owner had to stop the other stacks before SfM could finish.

---

## 4. Remaining defects (new today, from real data; prior defects stay as listed in FINAL_AUDIT)

| ID | Severity | Defect | Evidence | Smallest fix |
|---|---|---|---|---|
| D1 | P1 | **Privacy face detector produces false positives on real indoor frames**, and they cost reconstruction. 11 "faces" in 8 of 21 frames of an office; the 2 frames reviewed show no person. 83 % of DSC_0221 was masked, and that frame did not register in SfM. One frame escalated to a solid fill. Confirms audit P1-9 with real data. | `privacy-report.json`; frames reviewed visually | Replace Haar with a modern face detector (as the blueprint names); measure precision and recall on labelled indoor frames, with and without people |
| D2 | P1 | **FRAME_QUALITY_FILTER rejects sharp, low-texture frames as "blurred".** The sharpness score (Laplacian variance) measures texture, not blur. 5 of 26 real DSLR frames were dropped (scores 10–36 vs. 40); the one inspected is in focus, showing a plain wall and a whiteboard. Indoor venues are dominated by plain walls. | `quality-report.json`; DSC_0223 reviewed | Use a blur measure normalized by edge content (e.g. edge-width or gradient ratio), or reject only relative to neighbouring frames; add a real-image regression case |
| D3 | P2 | **COLMAP uses every core by default (`COLMAP_NUM_THREADS=-1`) with no memory bound.** It was OOM-killed at 2.5 GB RSS on 18 threads; 4 threads succeeded. | Kernel OOM log; usage rows | Derive the thread count from available memory, or document a memory requirement per thread on the worker |
| D4 | P2 | **`scripts/acceptance/real_venue.py` reads its bearer token once**, so any real-venue run longer than the access-token lifetime (5 min) fails mid-run. A `--resume` also rewrites `report.json` without its `media` section. | Script source; resumed report | Refresh the token (client credentials or a refresh token), or re-read the token file per request; merge reports on resume |
| D5 | P2 | **The report-only CSP's `frame-src` omits the identity provider in every built web image**, including the production deploy. `next.config.ts` reads `CHAYA_PUBLIC_OIDC_ISSUER` at build time, and no compose file passes it as a build argument. Browser checks A6/B6 fail; the policy cannot be promoted to enforcing without breaking silent sign-in. | `acceptance-c-viewer.json` | Pass the issuer as a build `ARG`, or emit the CSP per request from runtime env (the comment in `next.config.ts` already says a proxy is needed) |
| D6 | P2 | **Calibration evidence marked in frames that never register is accepted silently.** 3 of 4 original distances became unresolvable only after SfM, and the operator must triangulate points by hand (documented limitation). | `calibration-eval.json` | After POSE_ESTIMATION, report which measurements have at least 2 registered views; automate triangulation through `poses.json` |

Open from FINAL_AUDIT and **re-confirmed today**: GLOMAP absent (P1-12); gsplat never trained (P0-1); no device AR
validation. All other FINAL_AUDIT defects were **not** re-verified today.

---

## 5. Reproduction commands

From the repository root (Git Bash on Windows; Docker Desktop with at least 7.5 GB for the VM and other stacks stopped).
`EV=docs/e2e-evidence/2026-10-19-eth3d-office` and `W=<an empty work directory>`.

```bash
# 1. Real dataset (ETH3D office, about 289 MB) into its own directory; verify the 7z CRCs while extracting
curl -L -C - --retry 5 -o $W/dl/office_dslr_undistorted.7z https://www.eth3d.net/data/office_dslr_undistorted.7z
python -m pip install --target $W/pytools py7zr
python -I $EV/tools/extract.py $W/pytools $W/dl/office_dslr_undistorted.7z $W/eth3d

# 2. Capture directory (photos hard-linked plus calibration metadata) and ground-truth distance measurements
python -I $EV/tools/prepare_eth3d.py $W/eth3d/office $W/venue

# 3. Stack on HEAD, kept up after the acceptance suite (expect 40/40; browser A6/B6 fail, see D5)
KEEP_STACK=1 ACC_OUT=$W/acc ACC_API_PORT=18080 ACC_KEYCLOAK_PORT=18081 ACC_WEB_PORT=13000 \
  ACC_WEB_URL=http://localhost:13000 bash scripts/acceptance/run-acceptance.sh ci

# 4. COLMAP worker (current code, local COLMAP 3.8 CPU image) for POSE_ESTIMATION and SPLAT_RECONSTRUCTION.
#    colmap.env: the CHAYA_* and S3_* variables of chaya-acceptance-reconstruction-worker-1, plus WORKER_ID,
#    WORKER_STAGES=POSE_ESTIMATION,SPLAT_RECONSTRUCTION, WORKER_WORKDIR=/tmp/work, PYTHONPATH=/src, COLMAP_NUM_THREADS=4
MSYS_NO_PATHCONV=1 docker run -d --name chaya-validation-colmap --network chaya-acceptance_default --memory 5g \
  --env-file colmap.env -v "$(cygpath -w $PWD/services/reconstruction):/src:ro" --entrypoint python \
  chaya-physical-worker:colmap -m chaya_worker

# 5. Organization (SQL, as the acceptance runner does), users, venue and floor (8 h test client; see D4)
ORG=$(docker exec chaya-acceptance-postgres-1 psql -q -U postgres -d chaya -At \
  -c "INSERT INTO organization (slug, name) VALUES ('validation-eth3d','Validation org') RETURNING id")
python -I $EV/tools/setup_venue.py http://localhost:18081 http://localhost:18080 <kc admin> <kc admin password> $ORG $W/venue

# 6. The real run (stops at SPLAT_RECONSTRUCTION on a host without CUDA)
python -I scripts/acceptance/real_venue.py --api http://localhost:18080 --token-file $W/venue/private/operator.token \
  --venue <venue> --floor <floor> --media $W/venue/capture --measurements $W/venue/measurements.json --out $W/venue/run1 \
  --retry-failed 1 --search staircase --search reception --search elevator --search "accessible exit" --viewer-check

# 7. Download the run's artifacts (mc cp from chaya-derived/<run prefix>), then evaluate against ground truth
python -I $EV/tools/eval_poses.py <artifacts dir> $W/venue/gt_cameras.json pose-eval.json media.json
python -I $EV/tools/prepare_eth3d_registered.py $W/eth3d/office $W/venue registered.json eth3d-gt-registered-distance
python -I $EV/tools/calib_eval.py <artifacts dir> $W/venue/measurements-registered.json media.json <out dir>
python -I $EV/tools/record_and_calibrate.py http://localhost:18080 <operator token> <venue> <capture> \
  $W/venue/measurements-registered.json media.json <out dir>/resolved.json api-calibration.json

# 8. Object-storage outage
docker stop chaya-acceptance-minio-1
python -I $EV/tools/storage_outage.py http://localhost:18080 <token> <venue> <floor> <photo> down down.json
docker start chaya-acceptance-minio-1
python -I $EV/tools/storage_outage.py http://localhost:18080 <token> <venue> <floor> <photo> up up.json

# Teardown
docker rm -f chaya-validation-colmap && docker compose -p chaya-acceptance down -v

# The remaining phases, on a CUDA host, a deployed stack and physical devices:
#   docs/REAL_VENUE_ACCEPTANCE.md, docs/ar-android-validation.md, docs/ar-ios-validation.md
```

---

## 6. Final readiness assessment

- **Objective (venue → usable digital twin with search and navigation): NOT ACHIEVED. Validation stopped at the GPU
  boundary.** No real reconstruction, viewer model, search index, navmesh, route, AR session or re-scan exists, so none
  can be claimed.
- **What real data shows works:**
  - Authenticated, tenant-scoped capture of real media with content and malware checks.
  - Real privacy processing that runs and purges pre-privacy frames, but over-masks (D1).
  - Frame extraction and calibration rescaling.
  - Real COLMAP SfM, with millimetre-level pose accuracy on the frames it registers.
  - Metric calibration accepted within 1.67 % of laser ground truth.
  - Honest failure handling for OOM, missing GPU dependencies and a storage outage.
  - Bounded retries.
  - Per-attempt resource accounting with no invented GPU or cost figures.
- **What real data shows needs work before a venue run:**
  - The privacy detector (D1) and blur filter (D2) both discard real, useful frames. Together with low-texture walls,
    only 14 of 26 photos reached the model.
  - COLMAP's default thread count needs a memory bound (D3).
  - The real-venue harness cannot outlive a token (D4).
- **Release readiness:** not ready (as in FINAL_AUDIT, plus D1–D6).
- **Next step that unblocks the most:** one CUDA host (A10/L4-class). Build `Dockerfile.gpu`, re-run this exact capture
  (the commands above), then a real venue capture of a space that contains the searched objects, then the device
  checklists on one ARCore phone and one iPhone.
