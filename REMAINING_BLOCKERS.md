# REMAINING BLOCKERS & TECHNICAL REMEDIATION ROADMAP: CHAYA 02
**Hostile Engineering Audit Blocker Log**

**Audit Date:** 2026-11-01  
**Lead Auditor:** Independent Principal Engineer & QA Lead  
**Scope:** Actionable Blockers preventing `REAL-VENUE DEMO READY` and `LIMITED PILOT READY` Classifications  

---

## 0. Blocker Taxonomy & Priority Scale

| Priority | Definition | Impact on Release | Count |
|---|---|---|---|
| **P0 (Critical)** | Blocks real digital twin creation, physical navigation, or renders marketing claims false | Releases classified as NOT READY or ENGINEERING DEMO ONLY | **5** |
| **P1 (High)** | Degradation of reconstruction quality, licensing conflict, or cross-platform toolchain failure | Blocks unsupervised field deployment and general release | **5** |
| **P2 (Medium)** | Operational fragility, missing polish, non-functional cosmetic discrepancies | Tech debt / post-pilot hardening | **6** |

---

## 1. P0 Critical Blockers (Release Blockers)

### [P0-1] No CUDA GPU Host for gsplat 3D Gaussian Reconstruction
- **Problem Statement:** The repository contains fully implemented gsplat training orchestration (`chaya_worker.stages.splat_reconstruction`), but it has never run on real data. The local audit host possesses only Intel integrated graphics (`torch.cuda.is_available() == False`), and standard GitHub-hosted CI runners provide no GPUs. `gpu.yml` has run 0 times.
- **Evidence:** `nvidia-smi` not found; `assess()` reports `GPU_NOT_PRESENT`; `services/reconstruction/Dockerfile.gpu` has never been built on a production runner.
- **Exact Remediation:**
  1. Provision a dedicated GPU worker instance (e.g. AWS `g6.xlarge` or GCP `g2-standard-4` with NVIDIA L4, 24 GB VRAM, driver >= 560.28).
  2. Build `services/reconstruction/Dockerfile.gpu`.
  3. Execute `python -m chaya_worker.splat_preflight` on the instance.
  4. Run `CHAYA_REQUIRE_GPU=1 pytest -m gpu` with `CHAYA_SPLAT_DATASET` pointing to a real COLMAP project.

### [P0-2] Absence of Measured Real Venue Capture Data
- **Problem Statement:** No operator has captured an actual physical indoor facility for Chaya 02 with surveyed control points. All pipeline testing currently relies on synthetic mathematical scenes or academic datasets (ETH3D single office room, Sceaux outdoor photos).
- **Evidence:** `%USERPROFILE%\Videos\Captures` is empty; `scripts/acceptance/real_venue.py --check-prerequisites` exits code 5 (BLOCKED).
- **Exact Remediation:**
  1. Secure formal access permission and complete `consent.json`.
  2. Tape-measure or laser-survey >= 3 control point distances with recorded measurement uncertainty.
  3. Capture a continuous 4K video scan of the venue (multi-height continuous loops) using a modern smartphone.
  4. Place calibration targets and populate `capture-metadata.json`.

### [P0-3] Zero On-Device Physical AR Testing
- **Problem Statement:** The physical AR navigation journey has never been validated in a physical building on supported hardware. The web AR client relies on experimental Chrome incubation flags (`#webxr-incubations`), and the native iOS app has never been deployed to an iPhone.
- **Evidence:** 0 field session logs exist; `apps/ios-ar` has never been built in Xcode on a physical Mac or deployed via TestFlight.
- **Exact Remediation:**
  1. Enroll iOS test devices in Apple Developer Program; generate provisioning profile for `apps/ios-ar`.
  2. Deploy iOS build to iPhone 13 Pro or newer (LiDAR equipped).
  3. Print physical `IMAGE_TARGET` markers with calibrated millimeter dimensions.
  4. Validate on-device relocalization residual (< 0.20 m) and live path line rendering.

### [P0-4] No Working Cloud Deployment or Judge-Accessible URL
- **Problem Statement:** The automated deployment workflow (`.github/workflows/deploy.yml`) has 0 successful runs in 72 attempts (50 skipped, 19 failed, 3 cancelled). There is no live staging or production URL available for hackathon technical judges.
- **Evidence:** CI deploy history in GitHub Actions; `deploy.sh` has never completed on a public host.
- **Exact Remediation:**
  1. Provision a cloud VM (Ubuntu 24.04 LTS, 4 vCPU, 16 GB RAM, 100 GB NVMe).
  2. Configure DNS A-records for `app.<domain>`, `api.<domain>`, `auth.<domain>`.
  3. Populate deployment secrets in GitHub Actions repository settings.
  4. Trigger `deploy.yml` with a clean release tag.

### [P0-5] Solution Document Blueprint Misrepresentations
- **Problem Statement:** `Chaya_02_Winning_Solution.docx` claims multiple capabilities that do not exist in the codebase:
  - Claims "fine-tuned Grounding DINO and SegFormer models" (only stock checkpoints exist).
  - Claims "FAISS vector database" (PostgreSQL pgvector HNSW is implemented; FAISS is not used).
  - Claims "Redis caching" (Redis was removed from scope on 2026-10-07; nothing uses it).
  - Claims "GLOMAP as primary Structure-from-Motion mapper" (GLOMAP is absent from all container images).
  - Claims "AprilTag fiducial tracking" (only custom `IMAGE_TARGET` markers are detectable).
  - Claims "3D sparse point cloud and coverage heatmap HUD" (only 2D occupancy grid is implemented).
- **Evidence:** Source code analysis and `docs/BASELINE_AUDIT_2026-10-24.md`.
- **Exact Remediation:**
  1. Reconcile the solution document with verified capabilities prior to any final submission.

---

## 2. P1 High Priority Blockers

### [P1-1] Haar Cascade Face Detector False Positives on Architectural Textures
- **Problem:** The OpenCV Haar cascade detector in `chaya_worker/privacy/detectors.py` misclassifies repetitive indoor wall textures and sconce lighting as human faces. In testing on ETH3D `office`, it masked up to 83% of a valid architectural frame, causing that frame to fail SfM registration.
- **Remediation:** Replace Haar cascade with a lightweight deep learning face detector (e.g. MediaPipe BlazeFace or YuNet ONNX) with a high confidence threshold.

### [P1-2] GLOMAP Absent from Production Reconstruction Worker Images
- **Problem:** The solution document advertises GLOMAP for fast global Structure-from-Motion. However, neither `Dockerfile` nor `Dockerfile.gpu` compiles or installs GLOMAP. The codebase falls back to CPU COLMAP.
- **Remediation:** Either add GLOMAP build instructions to `Dockerfile.gpu` or formally update the specification to designate COLMAP as the permanent primary mapper.

### [P1-3] SegFormer Model Weights Licensed for Research Only
- **Problem:** The default segmentation weights (`nvidia/segformer-b0-finetuned-ade-512-512`) are subject to the NVIDIA Source Code License-NC (non-commercial / research evaluation only). This prevents commercial licensing of the platform.
- **Remediation:** Replace SegFormer with a permissively licensed commercial base model (e.g. Apache 2.0 or MIT licensed segmentation checkpoint trained on ADE20k).

### [P1-4] Native Windows `chaya-navmesh` Binary Incompatibility
- **Problem:** On Windows workstations, running `chaya-navmesh.exe` built with 32-bit MinGW causes NTSTATUS `0xC0000142` (`Illegal System DLL Relocation`) due to ASLR address space collisions with Windows 11 system DLLs. NavMesh tests fail when run natively on Windows without Docker.
- **Remediation:** Recompile `services/reconstruction/native/chaya-navmesh` using MSVC (Visual Studio 2022) or a 64-bit MinGW-w64 toolchain, or document that NavMesh tests must run inside the Linux container.

### [P1-5] Android WebXR Browser Flag Requirements
- **Problem:** Mobile WebXR image tracking relies on `navigator.xr.isSessionSupported("immersive-ar")` with the `webxr-incubations` flag enabled in `chrome://flags`. On standard consumer devices, the browser fails closed with `IMAGE_TRACKING_UNSUPPORTED`.
- **Remediation:** Implement a QR code / marker detection fallback using WebCodecs / BarcodeDetector API for devices without WebXR incubation support.

---

## 3. P2 Medium Priority Polish & Technical Debt

1. **Keycloak Event Synchronization:** Keycloak login/logout events are not piped into the PostgreSQL `audit_log` table.
2. **Offsite Backup Replication:** `scripts/backup/backup.sh` creates local dumps only; no automated S3 cross-region replication or offsite backup target exists.
3. **CDN Integration:** Splat models (`.ksplat`) and camera media are served directly from MinIO via Caddy proxy; no global CDN distribution layer is configured.
4. **Single-Instance API Constraint:** The Spring Boot backend cannot be horizontally scaled (`deploy.replicas > 1`) because rate limiting, the capture HUD stream, and media chunk reassembly are kept in-memory.
5. **No Z-Axis Floor Auto-Segmentation:** Multi-floor separation requires operators to enter floor connections manually; the pipeline cannot automatically partition multi-story scans along the Z-axis.
6. **No Maven Wrapper:** `services/api` lacks `./mvnw`, requiring system-installed Maven.

---

## 4. Remediation Milestones & Path to Pilot

```
Milestone 1: GPU Infrastructure
  └── Provision NVIDIA L4 runner -> Build Dockerfile.gpu -> Verify gsplat preflight
Milestone 2: Real Venue Capture & Ground Truth
  └── Secure building consent -> Tape-measure 3 control points -> Capture 4K video scan
Milestone 3: Cloud Deployment
  └── Spin up staging host -> Configure Caddy DNS -> Execute successful deploy.yml
Milestone 4: Physical Mobile AR Validation
  └── Deploy iOS app via TestFlight -> Execute on-site relocalization -> Verify arrival tolerance < 0.5m
```
