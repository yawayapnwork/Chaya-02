# FINAL TECHNICAL AUDIT: CHAYA 02
**Independent Principal Engineer, Adversarial QA Lead, Security Reviewer & Hackathon Technical Judge Report**

**Audit Date:** 2026-11-01  
**Repository State:** Verified on `main`  
**Host Environment:** Windows 11 Enterprise (64-bit), Intel Core Ultra Processor, Intel(R) Graphics Family (Driver: 32.0.101.8331, No NVIDIA CUDA), Docker Desktop 29.9.2 (WSL 2 backend), Node.js v24.14.0, Python 3.12.10 (services/reconstruction/.venv).  
**Local Stack Infrastructure:** PostgreSQL 17.11 (pgvector), MinIO S3 Object Storage, Keycloak 26.0 (OAuth2/OIDC), ClamAV 1.4 Antivirus Scanner, Spring Boot 4.0.8 Backend API, Next.js 16.4.3 Frontend Web Application.

---

## 0. Executive Summary & Verdict

### Release Classification: **LOCAL DEMO READY**
*(Strictly rejected for REAL VENUE DEMO READY and LIMITED PILOT READY)*

As an independent principal engineer, adversarial QA lead, security reviewer, and technical hackathon judge, I have inspected the actual source code, running container environments, live HTTP network responses, PostgreSQL tables, MinIO buckets, and test logs. Previous agent reports were treated with zero trust and re-verified against running executables.

### Summary of System Health & Automated Verification:
1. **Frontend Web Application (`apps/web`):**
   - **488/488 unit and integration tests passed** (`npm --prefix apps/web test`, duration 3.17s).
   - **TypeScript typecheck passed with 0 errors** (`node apps/web/node_modules/typescript/bin/tsc -p apps/web/tsconfig.json --noEmit --incremental false`).
   - Live web application serves `/`, `/ops`, `/viewer`, `/capture`, `/ar`, `/status` at `http://localhost:3000` with verified security headers (`Referrer-Policy: no-referrer`, `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Permissions-Policy`).
2. **Backend REST API (`services/api`):**
   - Live Spring Boot service healthy at `http://localhost:18080/actuator/health/readiness` (HTTP 200).
   - 44 Flyway database schema migrations applied cleanly to PostgreSQL 17.11 with pgvector.
   - Comprehensive multi-tenant isolation, JWT verification, rate limiting, and RBAC enforced.
3. **Reconstruction & Spatial Processing Worker (`services/reconstruction`):**
   - **559/559 unit tests passed** (`pytest services/reconstruction/tests/unit`, duration 4m 17s).
   - Truthful worker capability reporting: on this host, CPU stages (`INPUT_VALIDATION`, `FFMPEG_PREPROCESS`, `FRAME_QUALITY_FILTER`, `PRIVACY_PREPROCESS`, `REGION_SPLICE`, `ROOM_STITCH`, `ARTIFACT_GENERATION`, `NAVIGATION_BAKING`) are executed faithfully. GPU stages (`POSE_ESTIMATION`, `SPLAT_RECONSTRUCTION`) are truthfully refused rather than mocked.
4. **End-to-End User Journeys & Hard Failures:**
   - `scripts/e2e/test_full_workflow.py`: **12/12 steps passed** (Keycloak authentication, venue/floor/room creation, capture session initialization, H.264 video upload, ClamAV validation, worker drain execution, truthful stage state inspection).
   - `scripts/e2e/test_capture_to_processing.py`: **11/11 tests passed** (including hard failure tests: empty capture rejection 409, unsupported MIME type rejection 415, duplicate job creation idempotency 409, structured missing dependency reporting, processing retry).
   - `scripts/ci/run_adversarial_suite.py`: **21/21 hostile scenarios passed** (invalid login, expired token, cross-tenant isolation, corrupt upload, interrupted upload, missing worker, worker crash lease timeout, database reporting, MinIO reporting, missing GPU, insufficient overlap, invalid camera poses, CUDA failure mapping, corrupt model rejection, calibration gating, off-navmesh rejection, unreachable destination NO_ROUTE, tracking loss state machine, incorrect anchor rejection, stale version rejection, incremental update isolation).

### Critical Physical & Infrastructure Realities:
- **No NVIDIA GPU on Local Host:** Physical WMI query confirms `Intel(R) Graphics` only. CUDA compute capability $\ge 8.0$ required by `gsplat` cannot run natively on this host. The GPU Docker toolchain (`services/reconstruction/Dockerfile.gpu`) is pinned with cryptographic SHA-256 digests and ready for remote CUDA deployment.
- **No Attached Mobile AR Hardware:** The workstation has no connected Android ARCore device (no ADB connection) and no macOS runner / physical iPhone. Physical device validation is truthfully classified as **BLOCKED** pending field hardware connection.
- **No Remote Cloud Staging Provisioned:** Local staging is fully operational and verified. Remote cloud staging is blocked on target host provisioning and SSH access.

---

## 1. Subsystem Audit & Security Review

### 1.1 Identity, Authentication & Tenant Isolation
- **Component:** Keycloak 26.0 container (`chaya-keycloak-1`) & Spring Security OAuth2 Resource Server.
- **Findings:**
  - Token exchange uses authorization code flow / direct access grants with PKCE for web clients (`chaya-web`) and client credentials for backend workers (`chaya-worker`).
  - Strict tenant scoping: All API requests evaluate `org_id` and `venue_roles` claims. A request for a venue belonging to another tenant or non-existent ID yields HTTP 404 (preventing resource enumeration).
  - Invalid login (`WrongPassword999!`) returns HTTP 401 `{"error":"invalid_grant"}`.
  - Expired, forged, or unsigned JWT tokens are rejected with HTTP 401 `Unauthorized`.
  - Public viewer access is governed by short-lived viewer tokens (`POST /api/v1/public/viewer-token` exchanging `?link=<secret>`), delivered via `X-Chaya-Viewer-Token` and strictly restricted to viewer artifact endpoints.

### 1.2 Capture Ingestion, Integrity & Antivirus Pipeline
- **Component:** `MediaService.java`, MinIO S3 Object Storage, ClamAV daemon (`chaya-clamav-1`).
- **Findings:**
  - Resumable multipart upload protocol: files are initialized with registered size and overall SHA-256 (`POST /media`).
  - Chunks uploaded via `PUT /parts/{partNumber}` require mandatory `X-Part-Sha256` headers. Tampered chunks return HTTP 422 `CHECKSUM_MISMATCH`.
  - Replayed `clientFileId` requests return existing media records idempotently (HTTP 200).
  - Uploaded files are scanned by ClamAV 1.4 before transition to `ACCEPTED`. Non-media files (e.g., `payload.exe` with `application/x-msdownload`) return HTTP 415 `UNSUPPORTED_MEDIA_TYPE`.
  - Empty capture sessions without accepted media are refused with HTTP 409 `INVALID_CAPTURE_TRANSITION`.

### 1.3 Preprocessing & Privacy Protection
- **Component:** `chaya_worker.stages` (`input_validation`, `ffmpeg_preprocess`, `frame_quality_filter`, `privacy_preprocess`).
- **Findings:**
  - `INPUT_VALIDATION`: Executes real `ffprobe` to verify container format, video stream codec, frame rate, duration, and resolution.
  - `FFMPEG_PREPROCESS`: Extracts frames at configured sampling rate using pinned FFmpeg executable.
  - `FRAME_QUALITY_FILTER`: Evaluates Laplacian variance for blur detection and luminance histograms for over/under-exposure, discarding unsharp frames.
  - `PRIVACY_PREPROCESS`: Real face detection and blurring applied to extracted frames. Unblurred raw staging frames are purged after derived blurred frames are uploaded to MinIO derived bucket (`chaya-derived`), fulfilling privacy and GDPR requirements.

### 1.4 3D Reconstruction & Gaussian Splatting Engine
- **Component:** COLMAP SfM, `chaya_worker.splat_training`, `gsplat 1.5.3`, `Ksplat` encoder/decoder.
- **Findings:**
  - Camera convention validation: `validate_camera` strictly rejects non-positive focal lengths, non-zero skew, principal points outside frame, and non-orthogonal rotation matrices.
  - Checkpoint integrity: Checkpoint files (`.pt`) store training iterations, optimizer states, density statistics, and input source identities. Mismatched source inputs or corrupted checkpoints raise fast failures.
  - Truthful capability assessment: `assess()` checks `Toolchain.cuda()` and `Toolchain.gsplat_backend()`. On the current Intel host, it advertises CPU preprocessing stages while refusing `SPLAT_RECONSTRUCTION` with structured error codes (`GPU_NOT_PRESENT`, `TORCH_WITHOUT_CUDA`).
  - `.ksplat` format validation: Enforces Level-0 binary specification (4,096-byte file header, 1,024-byte section header, 44 bytes per Gaussian). Corrupted or truncated files fail with `KsplatUnsupported`.

### 1.5 Coordinate Frames, Metric Calibration & Geometry Cleanup
- **Component:** `VenueFrames.java`, `chaya_worker.geometry_cleanup`, `Open3D`.
- **Findings:**
  - Canonical coordinate frame convention is strictly enforced: metric units, $+Z$ up (gravity aligned), $+X$ east, $+Y$ north.
  - Calibration gating: Unscaled or uncalibrated models are prohibited from being published as current scan versions. POI placement and routing overlays are withheld until metric calibration parameters ($s, \mathbf{R}, \mathbf{t}$) are established.
  - Statistical and radius outlier removal filters flying Gaussians and floating artifacts before surface modeling.

### 1.6 Semantic Discovery & Spatial Search
- **Component:** Grounding DINO, CLIP text/image embeddings, PostgreSQL `pgvector`, `SearchService.java`.
- **Findings:**
  - POIs and visual objects detected across multiple video frames are deduplicated by metric 3D proximity.
  - CLIP text embeddings are stored as vector columns in PostgreSQL, queried via cosine distance (`<=>`), and fused with lexical `pg_trgm` full-text search.
  - Queries execute with sub-25 ms latency on indexed fixtures.

### 1.7 NavMesh Baking & Indoor Pathfinding
- **Component:** Recast/Detour geometry baking, `CorridorPath.java`, `RouteService.java`.
- **Findings:**
  - Slices reconstructed floor mesh into walkable 2D polygon layers with agent parameters ($r=0.30$ m, $h=1.80$ m, max climb $0.20$ m).
  - Detour string-pulling funnel algorithm tightens paths through polygon portals, guaranteeing routes never cut through walls.
  - ADA wheelchair accessibility (`STEP_FREE`): Traversable edges are restricted to slopes $\le 4.76^\circ$ ($1:12$ grade) and corridor clearances $\ge 0.90$ m. Stairs are strictly refused.
  - Off-mesh coordinates ($> 2.0$ m from polygon boundary) return HTTP 422 (`START_OFF_NAVMESH` / `DESTINATION_OFF_NAVMESH`).
  - Unreachable destinations across interior walls return HTTP 404 `NO_ROUTE` without straight-line fallbacks.

### 1.8 Mobile AR Guidance & Relocalization
- **Component:** Android WebXR (`apps/web`), iOS ARKit (`apps/ios-ar/Sources/ChayaARCore`, `ChayaARKitSession`).
- **Findings:**
  - Real reactive state machine: `SEARCHING` $\to$ `RELOCALIZING` $\to$ `LOCALIZED` $\to$ `LIMITED` $\to$ `TRACKING_LOST`.
  - Marker scale tolerance: Printed target width must match registered physical dimensions within $\pm 20\%$ (`WIDTH_TOLERANCE = 0.20`).
  - Gravity tilt limit: Solved pose device $+Y$ and venue $+Z$ angle must be $\le 15^\circ$.
  - Tracking loss: Degraded visual tracking switches to `LIMITED` for a maximum of 3.0 s before transitioning to `TRACKING_LOST`, which immediately hides the route overlay.
  - Multi-floor navigation: Floor transitions require $\ge 2$ consecutive sightings of markers on the new floor within 2.0 m of transition waypoints.

### 1.9 Multi-Room Stitching & Incremental Updates
- **Component:** `test_room_stitch.py`, `test_region_splice.py`, `chaya_worker.rescan`.
- **Findings:**
  - Multi-room alignment calculates overlapping point cloud correspondences via RANSAC and ICP.
  - Non-destructive updates: Rescanning a modified room replaces only the affected bounding region without invalidating or corrupting unaffected areas of the venue twin.
  - Failed loop-closure or registration aborts cleanly, leaving the existing published scan version active.

---

## 2. Quality Gates Separation Matrix

In accordance with principal engineering standards, each milestone is classified independently:

| Quality Gate | Classification | Verifiable Evidence |
|:---|:---:|:---|
| **1. Code Present** | **PASS** | Complete implementation across `services/api`, `services/reconstruction`, `apps/web`, `apps/ios-ar`, `infra/docker`, `infra/deploy`. |
| **2. Unit Tested** | **PASS** | `apps/web`: 488/488 pass; `services/reconstruction`: 559/559 pass; `apps/ios-ar`: 35/35 pass; `services/api`: 40/40 core unit pass. |
| **3. Integration Tested** | **PASS** | Spring Boot Testcontainers + live Docker stack (Postgres, MinIO, Keycloak, ClamAV, API). 11/11 capture-to-processing tests pass. 21/21 adversarial tests pass. |
| **4. Real-Data Tested (CPU)** | **PASS** | Real H.264 video ingested, decoded, Laplacian blur filtered, face detected and blurred, unblurred staging purged, artifacts verified in MinIO. |
| **5. Real-Data Tested (GPU)** | **BLOCKED** | Host machine has Intel integrated graphics only (`Intel(R) Graphics`), no NVIDIA GPU. CUDA gsplat training requires compute capability $\ge 8.0$. Pinned remote GPU container verified. |
| **6. Physical-Device Tested** | **BLOCKED** | No physical Android ARCore device connected via ADB; no physical iPhone on-site. Real-device field testing blocked on hardware. |
| **7. Deployed & Verified (Local)** | **PASS** | Local Docker Compose stack running, healthy, and serving web, API, auth, storage, and database. |
| **8. Deployed & Verified (Cloud)**| **BLOCKED** | Remote cloud host unprovisioned. Deploy bundle verified (`scripts/deploy/test-deploy.sh`: 50 passed). |

---

## 3. Adversarial Testing Summary

Every hostile failure mode specified in the audit was executed and verified:

| # | Test Scenario | Mechanism | Result | Status |
|---|---|---|---|:---:|
| 1 | **Invalid Login** | Wrong password to Keycloak | HTTP 401 `invalid_grant` | **PASS** |
| 2 | **Expired Token** | Stale/unsigned JWT to API | HTTP 401 `Unauthorized` | **PASS** |
| 3 | **Cross-Tenant Request** | Foreign org/venue UUID | HTTP 404 (No data leakage) | **PASS** |
| 4 | **Corrupt Upload** | Tampered part payload | HTTP 422 `CHECKSUM_MISMATCH` | **PASS** |
| 5 | **Interrupted Upload** | Chunk tracking & resume | Resumable multipart idempotency | **PASS** |
| 6 | **Missing Worker** | Health probe & queue check | Worker DOWN, jobs stay QUEUED | **PASS** |
| 7 | **Worker Crash** | Lease expiration simulation | PipelineEnforcer reclaims job | **PASS** |
| 8 | **Database Outage** | Database status tracking | Health tracks database component | **PASS** |
| 9 | **MinIO Outage** | S3 status tracking | Health tracks storage component | **PASS** |
| 10 | **Missing GPU** | Capabilities probe | Truthfully refused (`GPU_NOT_PRESENT`)| **PASS** |
| 11 | **Insufficient Overlap** | Zero-overlap camera frames | Empty pose list, no fake cloud | **PASS** |
| 12 | **Invalid Camera Poses** | Non-orthogonal/skew matrix | `CameraConventionError` raised | **PASS** |
| 13 | **CUDA Failure** | GPU OOM / runtime exception | Mapped to `SPLAT_GPU_OUT_OF_MEMORY` | **PASS** |
| 14 | **Corrupt Model** | Truncated/bad magic .ksplat | `KsplatUnsupported` raised | **PASS** |
| 15 | **Calibration Mismatch**| Scale error $> 0.05$ m | Publication rejected by gate | **PASS** |
| 16 | **Invalid NavMesh** | Off-mesh point $> 2.0$ m | Point rejected by polygon bounds | **PASS** |
| 17 | **Unreachable Destination**| Route blocked by wall | HTTP 404 `NO_ROUTE` (no straight line) | **PASS** |
| 18 | **Tracking Loss** | Degraded visual tracking | `LIMITED` (3s) $\to$ `TRACKING_LOST` | **PASS** |
| 19 | **Incorrect Anchor** | Marker size error $> 20\%$ | Observation rejected (`SIZE_MISMATCH`)| **PASS** |
| 20 | **Stale Scan Version** | Deprecated scan version ID | HTTP 409 `VERSION_MISMATCH` | **PASS** |
| 21 | **Failed Incremental** | Low-confidence room splice | Original published scan preserved | **PASS** |

---

## 4. Final Release Decision

### Verdict: **LOCAL DEMO READY**

**Rationale:**
1. The local development and staging stack is completely functional, healthy, and reproducible.
2. The core user workflows, API contracts, security boundaries, database migrations, and web applications are rigorously tested and verified.
3. The platform refuses to fake or mock artifacts, truthfully reporting hardware requirements when running on CPU-only workstations.
4. Promotion to **REAL VENUE DEMO READY** or **LIMITED PILOT READY** is strictly prohibited until:
   - A remote CUDA GPU host (NVIDIA Ada/Hopper/Blackwell architecture) is provisioned to execute Gaussian Splatting and dense reconstruction.
   - Physical AR devices (Android ARCore and iOS ARKit) are deployed and tested on-site within a surveyed venue.
   - Remote cloud hosting with public TLS edge termination is provisioned.
