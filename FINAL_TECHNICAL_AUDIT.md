# FINAL TECHNICAL AUDIT: CHAYA 02
**Independent Principal Engineer, QA Lead, Security Reviewer & Hackathon Technical Judge Report**

**Audit Date:** 2026-11-01  
**Repository State:** `main` @ `c22a04f` (clean working tree)  
**Host Environment:** Windows 11 Pro, Intel Core Ultra 5 125H (18 logical cores, 16 GB RAM), Intel Arc Integrated Graphics (Vendor ID: 0x8086, **No CUDA**), Docker Desktop 29.6.2, Node.js v24.14.0, Python 3.12.10.  
**Reference Document:** `Chaya_02_Winning_Solution.docx` (SHA-256: `3735c2b31028f8a8bd6163efa16e63a8de0c1e7bb76a2fa3d34faa29dcbfe11a`).

---

## 0. Executive Summary & Verdict

### Release Classification: **ENGINEERING DEMO READY**
*(Strictly rejected for REAL-VENUE DEMO READY and LIMITED PILOT READY)*

The engineering implementation of **Chaya 02** demonstrates rigorous systems engineering, exemplary multi-tenant security architecture, cryptographic artifact integrity, and sophisticated mathematical formulation of coordinate transformations, similarity registration, and NavMesh generation.

However, an unsparing audit against `Chaya_02_Winning_Solution.docx` reveals that **the product has never reconstructed a real indoor venue and has never operated on a physical mobile device in the field**:
1. **Zero CUDA GPU Execution:** gsplat 3D Gaussian Splatting training has never executed on a real dataset. The workstation and standard CI runners possess no NVIDIA GPU (`torch.cuda.is_available() == False`). `gpu.yml` has run 0 times.
2. **Zero Real-Venue Captures:** No operator has scanned an actual indoor facility with ground-truth metric control points. Public fixtures (ETH3D single office room, Sceaux outdoor château) stand in for pipeline evaluation.
3. **Zero Physical AR Device Sessions:** Neither the Android WebXR client nor the iOS ARKit application has executed on physical mobile hardware on-site.
4. **Zero Production Cloud Deployments:** The automated CD workflow (`deploy.yml`) has 0 successful deployments in 72 attempts; no public URL exists for live evaluation.

The system is certified as **ENGINEERING DEMO READY**: the control plane, data contracts, mathematical algorithms, and synthetic end-to-end flows pass completely, but claims of field-validated indoor navigation remain unproven.

---

## 1. Quality Gates Separation Matrix

| Quality Gate | Status | Verifiable Evidence |
|---|---|---|
| **1. Code Exists** | **MET** | Complete implementations in `services/api`, `services/reconstruction`, `apps/web`, `apps/ios-ar`, `services/vision`. |
| **2. Unit Tests Pass** | **MET** | `apps/web`: 472/472 pass; `services/reconstruction/tests/unit`: 559/559 pass; `apps/ios-ar` (`ChayaARCore`): 35/35 pass. |
| **3. Integration Tests Pass** | **MET** | `services/api`: Testcontainers (PostgreSQL 16, pgvector, MinIO, Keycloak, ClamAV) pass; Cross-service acceptance: 40/40 pass. |
| **4. Synthetic Fixture Works** | **MET** | 6×4×2m box viewer-scene loads and renders in WebGL; NavMesh bakes and routes standard/accessible paths on synthetic obstacle models; Rescan loop-closure and splice pass on synthetic clouds. |
| **5. Real Indoor Reconstruction Works** | **BLOCKED** | **0 real indoor venues reconstructed.** gsplat training requires CUDA compute capability >= 8.0 (missing on host and CI). ETH3D DSLR dataset reached CPU SfM only. |
| **6. Physical Navigation Works** | **BLOCKED** | **0 physical field tests executed.** No operator capture, no ground-truth surveyed venue, no mobile device on-site. |
| **7. Production Deployment Works** | **BLOCKED** | `deploy.yml` has 0 successes (50 skipped, 19 failed, 3 cancelled). No deployed public endpoint exists. |

---

## 2. Independent Validation of the 20-Step Journey

```
 ┌───────────────────────────────────────────────────────────────────────────────────────────┐
 │                                THE PRODUCT JOURNEY STATUS                                 │
 ├───────────────────────────────────────────────────────────────────────────────────────────┤
 │  [1] Open App (BLOCKED)         [2] Authenticate (PASS)         [3] Venue Select (PASS)   │
 │  [4] Capture Space (BLOCKED)    [5] Truthful HUD (PASS/BLOCKED) [6] Upload Media (PASS)   │
 │  [7] Privacy/File (PASS)        [8] Actual SfM (PASS-FIXTURE)   [9] CUDA gsplat (BLOCKED) │
 │ [10] Calibration (PASS-FIXTURE) [11] Cleanup (PASS-FIXTURE)    [12] Model Publish (PASS)  │
 │ [13] Web Viewer (PASS-FIXTURE)  [14] Index POIs (PASS)         [15] Search POI (PASS)     │
 │ [16] Calibrated Route (PASS)    [17] Localize Device (BLOCKED) [18] AR Guidance (BLOCKED) │
 │ [19] Destination Reached (BLOCK)[20] Incremental Rescan (PASS)                            │
 └───────────────────────────────────────────────────────────────────────────────────────────┘
```

### Step 1: Operator Opens Deployed Application
- **Source Implementation:** `apps/web/app/page.tsx`, `apps/web/components/VenueList.tsx`, `infra/deploy/Caddyfile`, `infra/deploy/docker-compose.yml`.
- **Input & Output Artifacts:** HTTP GET `/` -> HTML/JS bundle; `GET /api/public/config` -> runtime configuration JSON.
- **Executed Test:** `apps/web/e2e/ops.spec.ts`, Playwright UI suite (68 tests).
- **Runtime Environment:** Node.js v24 / Chromium; Docker Compose edge Caddy.
- **Evidence:** Frontend build succeeds with zero baked environment variables (`lib/public-config.ts`).
- **Status:** **BLOCKED for Production Deployment** (passes locally/in mocked CI; 0 successful production deployments).
- **Remaining Defect:** No public cloud host is deployed.
- **Responsible Subsystem:** `infra:deploy`, `apps/web`.

### Step 2: Operator Authenticates Successfully
- **Source Implementation:** `infra/deploy/keycloak`, `services/api/src/main/java/dev/chaya/api/security/SecurityConfig.java`, `apps/web/lib/session.ts`, `apps/web/lib/auth.ts`.
- **Input & Output Artifacts:** Authorization code -> JWT Bearer Token (`sub`, `org_id`, `venue_roles`, `exp`, RS256).
- **Executed Test:** `TenantApiIsolationTest.java`, `apps/web/lib/session.test.ts`, `test_01_authentication`.
- **Runtime Environment:** Keycloak 26.x container, Spring Security OAuth2 Resource Server.
- **Evidence:** Cross-tenant venue access returns 404; token expiration verified with 60s clock skew; invalid signatures rejected.
- **Status:** **PASS** (Integration-tested).
- **Remaining Defect:** Keycloak events are not replicated to the internal `audit_log` table.
- **Responsible Subsystem:** `services/api:security`.

### Step 3: Operator Creates or Selects Venue
- **Source Implementation:** `services/api/src/main/java/dev/chaya/api/venue/VenueController.java`, `VenueService.java`.
- **Input & Output Artifacts:** `POST /api/v1/venues` -> `VenueResponse` JSON; `GET /api/v1/venues` -> filtered venue list.
- **Executed Test:** `VenueApiTest.java`, `TenantApiIsolationTest.java`.
- **Runtime Environment:** Spring Boot, PostgreSQL 16.
- **Evidence:** Operator role cannot create venues (403); venue enumeration across tenants returns 404.
- **Status:** **PASS** (Integration-tested).
- **Remaining Defect:** Organizations must be created directly in SQL; no organization management API exists.
- **Responsible Subsystem:** `services/api:venue`.

### Step 4: Operator Captures a Real Indoor Space
- **Source Implementation:** `apps/web/components/CaptureWorkspace.tsx`, `apps/web/lib/capture-guidance.ts`.
- **Input & Output Artifacts:** Camera video stream / WebXR frames -> Reconnaissance trajectory, room bounds estimation.
- **Executed Test:** `apps/web/lib/capture-guidance.test.ts`, `apps/web/lib/capture-environments.test.ts`.
- **Runtime Environment:** Mobile browser WebXR (Android Chrome).
- **Evidence:** Benchmark B1 validates path planner on 7 synthetic floor plans. **No operator has performed a physical capture for Chaya 02.**
- **Status:** **BLOCKED** (Physical-device validated: NO).
- **Remaining Defect:** Lack of real capture data on physical target devices.
- **Responsible Subsystem:** `apps/web:capture`.

### Step 5: Truthful Capture Quality Feedback
- **Source Implementation:** `apps/web/components/CaptureHud.tsx`, `apps/web/lib/capture-quality.ts`, `chaya_worker/stages/frame_quality.py`.
- **Input & Output Artifacts:** Video frames -> Sharpness score, coverage 2D grid, lighting score, velocity warnings.
- **Executed Test:** `apps/web/lib/capture-quality.test.ts`, `test_capture_quality_and_privacy.py`.
- **Runtime Environment:** Client Canvas/WebGL; Worker Python CPU.
- **Evidence:** Edge-normalized blur metric rejects truly blurred frames while preserving plain indoor walls; coverage updates verified.
- **Status:** **PASS (Unit/Fixture) / BLOCKED (Field Device)**.
- **Remaining Defect:** Capture HUD renders a 2D floor grid, not a 3D sparse point cloud or 3D coverage heatmap as promised in the blueprint.
- **Responsible Subsystem:** `apps/web:capture`, `services/reconstruction:frame_quality`.

### Step 6: Media Uploaded Successfully
- **Source Implementation:** `apps/web/lib/capture-queue.ts`, `services/api/.../CaptureUploadController.java`, `CaptureUploadService.java`.
- **Input & Output Artifacts:** Chunked HTTP POST -> MinIO `s3://chaya/{tenant}/{venue}/captures/{id}/raw.mp4`, SHA-256 seal.
- **Executed Test:** `CaptureUploadServiceTest.java`, `apps/web/lib/capture-queue.test.ts`, `test_02_capture`.
- **Runtime Environment:** Spring Boot, MinIO S3 storage.
- **Evidence:** Resumable chunk upload tested against network interruptions; EICAR test file and mismatched checksums rejected.
- **Status:** **PASS** (Integration-tested).
- **Remaining Defect:** Mobile field upload over high-latency cellular connections unverified.
- **Responsible Subsystem:** `services/api:capture`.

### Step 7: Privacy and File Validation Execute
- **Source Implementation:** `chaya_worker/stages/input_validation.py`, `ffmpeg_preprocess.py`, `privacy.py`, Apache Tika, ClamAV.
- **Input & Output Artifacts:** Raw media -> `frames-manifest.json`, `privacy-report.json`, blurred frame images.
- **Executed Test:** `test_privacy_preprocess.py`, `test_capture_quality_and_privacy.py`, CI stack smoke.
- **Runtime Environment:** Worker CPU (OpenCV, FFmpeg, ClamAV, Tika).
- **Evidence:** Verified redaction of detected faces and license plates; non-image polyglots rejected; Tika CVE-2026-66755 patched in 3.3.2.
- **Status:** **PASS** (Integration-tested).
- **Remaining Defect:** Haar cascade face detector creates false positives on textured indoor walls, causing up to 83% texture loss on plain architectural features.
- **Responsible Subsystem:** `services/reconstruction:privacy`.

### Step 8: Actual SfM Estimates Camera Poses
- **Source Implementation:** `chaya_worker/stages/pose_estimation.py`, COLMAP 3.8, GLOMAP.
- **Input & Output Artifacts:** Masked frames -> `poses.json`, `cameras.bin`, `images.bin`, `points3D.bin`.
- **Executed Test:** `test_camera_model.py`, `test_real_dataset.py`, ETH3D office validation (`docs/VALIDATION_2026-10-19.md`).
- **Runtime Environment:** CPU COLMAP (Docker/Linux).
- **Evidence:** ETH3D office: 14/21 frames registered with 1.7 mm camera-center RMSE; Sceaux: 11/11 registered (0.398 px reprojection error).
- **Status:** **PASS (Fixture-validated) / BLOCKED (Real Venue)**.
- **Remaining Defect:** GLOMAP is absent from all worker images despite being claimed as primary mapper; `Dockerfile.gpu` has never been deployed.
- **Responsible Subsystem:** `services/reconstruction:pose_estimation`.

### Step 9: Actual CUDA gsplat Training Reconstructs Space
- **Source Implementation:** `chaya_worker/stages/splat_reconstruction.py`, `splat_training.py`, `gsplat` (PyTorch CUDA).
- **Input & Output Artifacts:** Poses + sparse points -> `iteration_*.pt`, `splat.ply`, `splat_manifest.json`.
- **Executed Test:** `test_reconstruction_cuda_pipeline.py` (CPU test renderer only), `test_splat_training.py`.
- **Runtime Environment:** Requires NVIDIA GPU (CUDA compute capability >= 8.0, driver >= 560).
- **Evidence:** `nvidia-smi` is absent on host; `torch.cuda.is_available() == False`; `gh run list --workflow gpu.yml` has 0 runs.
- **Status:** **BLOCKED (P0)**.
- **Remaining Defect:** gsplat has never executed on real venue data.
- **Responsible Subsystem:** `services/reconstruction:splat_reconstruction`.

### Step 10: Reconstruction Calibrated Against Real Measurements
- **Source Implementation:** `services/api/.../CoordinateFrameService.java`, `Triangulator.java`, `apps/web/components/CalibrationPanel.tsx`.
- **Input & Output Artifacts:** Ground-truth distance pairs / control points -> `coordinate_frame` record (scale factor, gravity quaternion, origin).
- **Executed Test:** `CoordinateFrameCalibrationTest.java`, `TriangulatorTest.java`, `test_calibration_gates.py`.
- **Runtime Environment:** Spring Boot, PostgreSQL, Web UI.
- **Evidence:** Metric scale calibration verified on synthetic scenes and ETH3D ground truth (1.67% scale error). Collinearity gate enforces >= 0.5m transverse spread.
- **Status:** **PASS (Fixture-validated) / BLOCKED (Real Venue)**.
- **Remaining Defect:** Control points require manual identification across multiple frames; no automated multi-view triangulation on site.
- **Responsible Subsystem:** `services/api:frame`.

### Step 11: Geometry Cleanup Executes
- **Source Implementation:** `chaya_worker/stages/geometric_cleanup.py`, `geometry_cleanup.py`, `plane_fitting.py`, Open3D.
- **Input & Output Artifacts:** `splat.ply` -> `cleaned.ply`, `planes.json`.
- **Executed Test:** `test_calibrated_geometry_and_cleanup.py`, Benchmark B2/B2c.
- **Runtime Environment:** Python Open3D 0.19.0.
- **Evidence:** Outlier removal isolates weak points 6.6x better than chance; radius threshold retuned to prevent thinning real surfaces.
- **Status:** **PASS** (Fixture-validated).
- **Remaining Defect:** Semantic cleanup relies on SegFormer weights licensed for research/evaluation only (`ML_EVALUATION.md`).
- **Responsible Subsystem:** `services/reconstruction:geometric_cleanup`.

### Step 12: Valid Gaussian Model Generated and Published
- **Source Implementation:** `chaya_worker/stages/artifact_generation.py`, `ksplat.py`, `services/api/.../ScanVersionService.java`.
- **Input & Output Artifacts:** `cleaned.ply` -> `scene.ksplat`, SHA-256 digest, `scan_version.model_artifact_id`.
- **Executed Test:** `test_ksplat_validation.py`, `apps/web/lib/artifact-integrity.test.ts`, `test_04_viewer`.
- **Runtime Environment:** Python worker, MinIO, Spring API.
- **Evidence:** Verified exact level-0 byte structure matching `@mkkellogg/gaussian-splats-3d` 0.4.7; SHA-256 seal prevents delivery of corrupted models.
- **Status:** **PASS** (Integration-tested).
- **Remaining Defect:** Published model is a synthetic format fixture (6×4×2m box), not a real reconstruction.
- **Responsible Subsystem:** `services/reconstruction:ksplat`, `services/api:scan_version`.

### Step 13: Web Viewer Loads Genuine Model
- **Source Implementation:** `apps/web/components/SplatViewerCanvas.tsx`, `apps/web/components/ViewerWorkspace.tsx`.
- **Input & Output Artifacts:** `scene.ksplat` -> WebGL canvas, orbital controls, POI pin overlay.
- **Executed Test:** `apps/web/e2e/ksplat-viewer.spec.ts`, `scripts/e2e/viewer_format_check.cjs`.
- **Runtime Environment:** Browser WebGL (Chromium software WebGL in CI).
- **Evidence:** Renders fixture box, verifies non-zero rendered splats, camera controls, and POI pin placement (`gpuAcceleratedSort: false`).
- **Status:** **PASS (Fixture-validated) / BLOCKED (Real Reconstruction)**.
- **Remaining Defect:** Never rendered a million-splat real venue reconstruction in WebGL; memory headroom on mobile browsers untested.
- **Responsible Subsystem:** `apps/web:viewer`.

### Step 14: Actual Venue Objects and POIs Indexed
- **Source Implementation:** `chaya_worker/stages/semantic_indexing.py`, `services/api/.../PoiService.java`, Grounding DINO, CLIP.
- **Input & Output Artifacts:** RGB frames + point cloud -> 3D bounding boxes -> `poi` rows with pgvector HNSW embeddings.
- **Executed Test:** `test_semantic_indexing.py`, `SearchBenchmarkTest.java`, Benchmark B3.
- **Runtime Environment:** Vision service (CLIP ViT-B/32), PostgreSQL pgvector.
- **Evidence:** 30 POIs and 15 real COCO object crops evaluated; 22/22 detected object queries placed in top-5 results; query latency < 5 ms.
- **Status:** **PASS** (Integration-tested).
- **Remaining Defect:** No fine-tuned models exist (stock checkpoints only); no venue-specific indoor training annotations.
- **Responsible Subsystem:** `services/reconstruction:semantic_indexing`, `services/vision`.

### Step 15: User Searches for Real Destination
- **Source Implementation:** `services/api/.../SemanticSearchService.java`, `QueryInterpreter.java`, `apps/web/components/SemanticSearchPanel.tsx`.
- **Input & Output Artifacts:** Text/voice query -> CLIP vector -> Cosine similarity + lexical fallback -> Ranked POIs.
- **Executed Test:** `apps/web/e2e/search.spec.ts`, `SemanticSearchRankingTest.java`, `test_05_search`.
- **Runtime Environment:** Spring Boot, pgvector, Next.js.
- **Evidence:** Synonym retrieval verified (top-1 58.8% vs 5.9% lexical); relevance score threshold filters 86.7% nonsense queries; fallback to lexical search on vision service outage verified.
- **Status:** **PASS** (Integration-tested).
- **Remaining Defect:** Evaluated on synthetic fixture venue, not real complex multi-floor facility.
- **Responsible Subsystem:** `services/api:search`.

### Step 16: Route Generated from Real Calibrated Geometry
- **Source Implementation:** `chaya_worker/stages/navigation_baking.py`, `chaya-navmesh` (Recast/Detour 1.6.0), `services/api/.../RouteService.java`.
- **Input & Output Artifacts:** NavGeometry -> `navmesh.bin`, `navmesh_manifest.json` -> Polyline waypoints, step-free flag, slope metadata.
- **Executed Test:** `test_venue_navigation.py`, `RouteServiceTest.java`, `test_06_navigation`.
- **Runtime Environment:** Native C++ Recast/Detour, Spring Boot.
- **Evidence:** Real Recast solo-mesh build; Dijkstra + portal string-pulling; step-free filter excludes slopes > 4.76 deg (1:12 ADA); dynamic obstacle avoidance verified.
- **Status:** **PASS (Fixture-validated) / BLOCKED (Real Venue)**.
- **Remaining Defect:** Navmesh baking executed only on synthetic geometry models; native Windows binary toolchain requires 64-bit MinGW/MSVC.
- **Responsible Subsystem:** `services/reconstruction:navmesh`, `services/api:route`.

### Step 17: Supported Mobile Device Localizes Within Venue
- **Source Implementation:** `apps/web/lib/ar-relocalization.ts`, `ar-marker-tracking.ts`, `apps/ios-ar/.../RelocalizationStateMachine.swift`.
- **Input & Output Artifacts:** Camera frame marker observation (ID, 6-DoF pose) -> Umeyama rigid solve -> Device-to-venue transform matrix.
- **Executed Test:** `apps/web/lib/ar-relocalization.test.ts`, `NavigationReliabilityTests.swift`.
- **Runtime Environment:** Android Chrome WebXR (`#webxr-incubations`) / iOS ARKit.
- **Evidence:** Rigorous mathematical unit tests verify multi-marker consensus, moved marker isolation, drift thresholding.
- **Status:** **BLOCKED on Physical Device** (0 real hardware sessions).
- **Remaining Defect:** WebXR image tracking requires non-standard experimental flags; iOS app never deployed to an iPhone.
- **Responsible Subsystem:** `apps/web:ar`, `apps/ios-ar`.

### Step 18: AR Guidance Directs User to Destination
- **Source Implementation:** `apps/web/components/ArWorkspace.tsx`, `apps/web/lib/ar-navigation.ts`, `ARSessionManager.swift`.
- **Input & Output Artifacts:** Calibrated camera pose + route waypoints -> 3D projected guidance line, floor transition banners.
- **Executed Test:** `apps/web/lib/navigation-safety.test.ts`, `apps/web/lib/user-journeys.test.ts`.
- **Runtime Environment:** Mobile AR view.
- **Evidence:** AR state machine transitions correctly between `INITIALIZING`, `TRACKING`, `RELOCALIZING`, `TRACKING_LOST`; hides route during tracking loss.
- **Status:** **BLOCKED on Physical Device**.
- **Remaining Defect:** Visual occlusion against physical real-world furniture unverified.
- **Responsible Subsystem:** `apps/web:ar`, `apps/ios-ar`.

### Step 19: User Reaches Destination Within Accuracy Tolerances
- **Source Implementation:** Physical field acceptance protocol against surveyed ground truth (< 0.5 m error).
- **Input & Output Artifacts:** Physical arrival coordinates vs survey ground truth.
- **Executed Test:** None.
- **Runtime Environment:** Physical building.
- **Evidence:** Zero field runs.
- **Status:** **BLOCKED**.
- **Remaining Defect:** Complete physical journey has never been performed.
- **Responsible Subsystem:** Field QA / Integration.

### Step 20: Changed Region Rescanned Without Corrupting Previous Version
- **Source Implementation:** `chaya_worker/stages/region_alignment.py`, `region_splice.py`, `services/api/.../RescanService.java`.
- **Input & Output Artifacts:** Rescan media -> Sub-volume crop -> Similarity registration -> Seam measurement -> New version `v2`.
- **Executed Test:** `test_rescan_consistency.py`, `test_region_splice.py`, `test_07_rescan`.
- **Runtime Environment:** Python worker, PostgreSQL, MinIO.
- **Evidence:** Quality gates enforce inlier ratio, scale tolerance, and seam step; parent version `v1` remains active and routable until `v2` finalization; rollback restores `v1`.
- **Status:** **PASS** (Integration-tested on synthetic scenes).
- **Remaining Defect:** Never executed on a physical venue modification.
- **Responsible Subsystem:** `services/reconstruction:rescan`, `services/api:rescan`.

---

## 3. Hostile Failure Tests Audit

The following table records the defensive mechanisms and test verification for the 24 hostile failure modes:

| Failure Mode | Trigger Mechanism | Expected System Response | Verifying Test | Status |
|---|---|---|---|---|
| **1. Missing GPU** | Worker started on CPU host claiming reconstruction | Assessment detects missing CUDA, refuses stage with `GPU_NOT_PRESENT` | `test_adversarial_4_report_gpu_availability_without_usable_gpu` | **PASS** |
| **2. Corrupt Media** | Upload truncated or non-media binary | Rejected at validation stage with `INVALID_MEDIA` | `test_02_capture` (acceptance suite) | **PASS** |
| **3. Poor Image Overlap** | Capture frames with insufficient visual overlap | SfM registration fails closed with `INSUFFICIENT_OVERLAP` | `test_real_venue_harness.py` | **PASS** |
| **4. False Privacy Detections** | Architectural texture falsely flagged as PII | Masks applied, but frame kept unless over-masked (>80%) | `test_capture_quality_and_privacy.py` | **PASS** |
| **5. SfM Failure** | Untextured or chaotic frames | Stage marks `FAILED`, records missing registered cameras | `test_real_venue_harness.py` | **PASS** |
| **6. Invalid Camera Poses** | Non-invertible view matrices or zero focal length | Rejected by `validate_camera` with `CameraConventionError` | `test_reconstruction_cuda_pipeline.py` | **PASS** |
| **7. CUDA Out-of-Memory** | High Gaussian count exceeding VRAM | Worker frees cache, checkpoints state, marks `CUDA_OOM` | `test_reconstruction_cuda_pipeline.py` | **PASS** |
| **8. Training Timeout** | Wall-clock budget exceeded during optimization | Loop terminates cleanly, exports `SPLAT_PARTIAL` | `test_reconstruction_cuda_pipeline.py` | **PASS** |
| **9. Failed Checkpoint Resume** | Resuming from altered or corrupt checkpoint file | `load_checkpoint` validates SHA, raises `CheckpointCorrupt` | `test_checkpoint_persistence.py` | **PASS** |
| **10. Corrupted .ksplat** | Single flipped byte in stored model artifact | Loader detects SHA-256 mismatch, halts WebGL render | `ksplat-viewer.spec.ts` | **PASS** |
| **11. Uncalibrated Geometry** | Requesting metric route without coordinate frame | API rejects route request with `NOT_CALIBRATED` (409) | `RouteServiceTest.java` | **PASS** |
| **12. Invalid NavMesh** | Geometry with no walkable surface | Baker raises `NO_WALKABLE_SURFACE`, publishes no graph | `test_recast_boundary.py` | **PASS** |
| **13. Wrong-Floor Destination** | Routing between floors with no connection | Rejected with `FLOOR_CONNECTION_UNAVAILABLE` | `RouteServiceTest.java` | **PASS** |
| **14. Unreachable POI** | Destination isolated behind obstacle wall | Router returns `NO_ROUTE_FOUND` with unreachable diagnostic | `search-navigation.spec.ts` | **PASS** |
| **15. Stale Semantic Index** | Querying POIs after new rescan finalization | POIs in modified region marked superseded, excluded | `test_07_rescan` | **PASS** |
| **16. Expired Authentication** | JWT expires during active capture or navigation | Frontend catches 401, pauses queue, prompts renewal | `capture-offline.spec.ts` | **PASS** |
| **17. Unauthorized Venue Access** | Caller attempts access to another tenant's venue | `TenantGuard` returns 404 (prevents enumeration) | `TenantApiIsolationTest.java` | **PASS** |
| **18. Missing Worker** | Worker crashes while holding stage claim lease | Heartbeat expires, job marked `WORKER_LOST`, retried | `JobRecoveryTest.java` | **PASS** |
| **19. Network Interruption** | Connection drops during large chunked upload | Client stores chunk locally, resumes missing offset | `capture-offline.spec.ts` | **PASS** |
| **20. Tracking Loss** | Camera covered or fast motion disrupts VIO | AR workspace hides guidance line, enters `TRACKING_LOST` | `apps/web/lib/ar-relocalization.test.ts` | **PASS** |
| **21. Incorrect AR Anchor** | Moved marker or conflicting fiducial detected | Consensus filter rejects discordant anchor as outlier | `apps/web/lib/ar-anchor-math.test.ts` | **PASS** |
| **22. Failed Incremental Update** | Rescan fails alignment gates | Rejected with `ALIGNMENT_REJECTED`; parent scan untouched | `test_rescan_consistency.py` | **PASS** |
| **23. Interrupted Deployment** | Health check fails on newly deployed container | `deploy.sh` executes automatic rollback to prior image | `scripts/deploy/test-deploy.sh` | **PASS** |
| **24. Restore Without Artifacts** | DB restored with missing MinIO storage | `restore-drill.sh` validates SHA-256 for all artifacts | `scripts/backup/test-restore-drill.sh` | **PASS** |

---

## 4. Final Technical Judge Verdict

### Classification: **ENGINEERING DEMO READY**

```
┌────────────────────────────────────────────────────────────────────────┐
│  VERDICT SUMMARY                                                       │
├────────────────────────────────────────────────────────────────────────┤
│  Control Plane & Security Architecture:      GRADE A (Exceptional)     │
│  Data Integrity & Error Handling:            GRADE A (Exemplary)       │
│  Mathematical Rigor (Frames, Registration):  GRADE A- (Solid)          │
│  Machine Learning Execution:                 GRADE D (Stubbed/No GPU)  │
│  Physical Field Validation:                  GRADE F (Non-existent)    │
│  Production Cloud Deployment:                GRADE F (Unsuccessful)    │
├────────────────────────────────────────────────────────────────────────┤
│  OVERALL RATING: ENGINEERING DEMO READY                                │
└────────────────────────────────────────────────────────────────────────┘
```

The team has constructed an extraordinary engineering foundation. The codebase avoids shortcuts: error handling is strict, security boundaries are verified, and mathematical edge cases are properly guarded.

However, a digital twin product cannot be judged on synthetic tests alone. Until an actual physical building is scanned by an operator, reconstructed via a real CUDA gsplat runner, calibrated to surveyed tape measurements, and navigated by a user holding a physical smartphone, the system cannot be awarded `REAL-VENUE DEMO READY` or `LIMITED PILOT READY`.
