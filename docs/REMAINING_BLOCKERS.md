# Chaya 02: Remaining Blockers & Field Deployment Remediation Plan

**Date:** 2026-11-01  
**Auditor:** Independent Principal Systems Engineer & Adversarial QA Lead  
**Current Release Classification:** **LOCAL DEMO READY**  
**Target Release Classifications:** **REAL VENUE DEMO READY** | **LIMITED PILOT READY**

---

## 1. Executive Summary of Blockers

While all software components, API contracts, local storage/database pipelines, web clients, and adversarial safety gates have passed 100% of automated unit, integration, and e2e testing on the development workstation, Chaya 02 cannot honestly be certified for **REAL VENUE DEMO READY** or **LIMITED PILOT READY** on this host. 

The blockers identified below are **strictly hardware, physical sensor, and remote deployment environment blockers** — not software architectural defects. The system truthfully reports its hardware limitations and fails closed rather than faking artifacts or generating synthetic illusions.

```
┌─────────────────────────────────────────────────────────────────────────────────────────────┐
│                               BLOCKER SEVERITY MATRIX                                       │
├──────────┬─────────────────────────────┬───────────────────────────────┬────────────────────┤
│ Severity │ Blocker Identification      │ Affected Subsystem            │ Blocking Gate      │
├──────────┼─────────────────────────────┼───────────────────────────────┼────────────────────┤
│ P0       │ NVIDIA CUDA GPU Hardware    │ Splat Reconstruction (gsplat) │ REAL-DATA TESTED   │
│ P1       │ Physical Mobile AR Devices  │ WebXR & ARKit Field Tracking  │ PHYSICAL-DEVICE    │
│ P2       │ Remote Cloud Infrastructure │ Public Ingress & Mobile TLS   │ DEPLOYED & VERIFIED│
│ P3       │ Physical Venue Ground Truth │ Laser Survey & Metric Control │ CALIBRATION VERIF. │
└──────────┴─────────────────────────────┴───────────────────────────────┴────────────────────┘
```

---

## 2. Detailed Technical Breakdown of Blockers

### Blocker 01 (P0): Physical NVIDIA CUDA GPU Hardware Unavailable on Host

- **Severity:** **P0 - Critical Blocker for Splat Reconstruction**
- **Affected Subsystem:** `services/reconstruction` (`chaya_worker.stages.splat_reconstruction`, `gsplat 1.5.3`)
- **Technical Root Cause:**
  - The development workstation is a Windows 11 laptop equipped with an Intel Core Ultra processor and `Intel(R) Graphics` only.
  - Native Windows query via WMI confirmed zero NVIDIA GPUs present:
    ```powershell
    Get-CimInstance Win32_VideoController | Select-Object Name, DriverVersion
    # Output: Intel(R) Graphics | 32.0.101.8331
    ```
  - PyTorch reports: `torch.cuda.is_available() == False`.
  - The Gaussian Splatting engine (`gsplat 1.5.3`) requires CUDA compute capability $\ge 8.0$ (NVIDIA Ampere, Ada Lovelace, Hopper, or Blackwell architecture) and dedicated VRAM ($\ge 16$ GB recommended).
  - Native COLMAP binary for SfM sparse pose estimation is packaged for Linux containers (`Dockerfile.gpu`) and not installed on the native Windows host path.
- **System Defense & Truthful Reporting:**
  - The worker explicitly introspects host capabilities via `chaya_worker.capabilities.assess()`:
    ```json
    {
      "code": "GPU_NOT_PRESENT",
      "subject": "cuda",
      "message": "no NVIDIA GPU is visible to this process"
    }
    ```
  - The worker **truthfully refuses** `SPLAT_RECONSTRUCTION` jobs. No fake point clouds or fabricated splats are written.
- **What Was Verified Locally:**
  - All CPU-bound reconstruction stages passed on real data (`INPUT_VALIDATION`, `FFMPEG_PREPROCESS`, `FRAME_QUALITY_FILTER`, `PRIVACY_PREPROCESS`, `REGION_SPLICE`, `ROOM_STITCH`).
  - Synthetic box fixture KSplat generation and validation passed (`apps/web/lib/ksplat.test.ts`, `services/reconstruction/tests/unit/test_ksplat_fixture.py`).
  - Splat training PyTorch CPU rasterization fallback and CUDA error handling verified (`test_reconstruction_cuda_pipeline.py`: 32/32 tests pass).
- **Exact Remediation Plan & Unblocking Steps:**
  1. Provision a remote GPU worker node (e.g., AWS `g5.2xlarge` with NVIDIA A10G 24GB or Azure `NC8as_T4_v3` / on-premise RTX 4090).
  2. Build and run the pre-configured GPU Docker image:
     ```bash
     docker build -t chaya-worker-gpu -f services/reconstruction/Dockerfile.gpu .
     docker run --gpus all --env-file .env -d chaya-worker-gpu
     ```
  3. Verify worker GPU capability advertisement:
     ```bash
     curl -s http://localhost:18080/actuator/health | jq .components.worker
     ```

---

### Blocker 02 (P1): Physical Mobile AR Hardware & Sensor Telemetry Unavailable

- **Severity:** **P1 - Critical Blocker for Field Navigation Verification**
- **Affected Subsystem:** `apps/web/lib/ar-workspace.tsx` (Android WebXR) and `apps/ios-ar` (Native iOS ARKit)
- **Technical Root Cause:**
  - The local development workstation has no connected Android smartphone via ADB (`adb devices` returns 0 devices).
  - The workstation is a Windows PC without macOS runner or connected physical iPhone, preventing native deployment of `apps/ios-ar/Sources/ChayaARCore`.
  - Visual-Inertial Odometry (VIO), smartphone IMU telemetry, rolling-shutter camera feeds, and real-time physical fiducial marker tracking cannot be physically experienced from a desktop browser.
- **System Defense & Truthful Reporting:**
  - The WebXR implementation in `apps/web` gracefully detects browser capabilities via `navigator.xr.isSessionSupported("immersive-ar")`. If unsupported, it displays structured error banners (`WEBXR_UNAVAILABLE`, `IMAGE_TRACKING_UNSUPPORTED`) rather than crashing or showing blank screens.
  - The AR relocalization state machine strictly rejects observations with size mismatch ($> 20\%$), excessive tilt ($> 15^\circ$), or high residual ($> 0.30$ m).
- **What Was Verified Locally:**
  - 488/488 Next.js unit/integration tests passed, including `ar-navigation.test.ts`, `ar-anchor-math.test.ts`, `ar-relocalization.test.ts`, and `webxr-support.test.ts`.
  - 35/35 Swift unit tests in `apps/ios-ar/Tests/ChayaARCoreTests` passed.
  - NavMesh path generation and ADA step-free routing verified (`CorridorPathTest.java`).
- **Exact Remediation Plan & Unblocking Steps:**
  1. Connect a physical Android ARCore-certified device (e.g. Google Pixel 8, Samsung Galaxy S24) or iPhone 15/16 Pro to the local network.
  2. For Android WebXR:
     - Open Chrome on mobile device.
     - Navigate to `https://<stage-host>/ar?venueId=<id>&floorId=<id>`.
     - Point camera at printed calibration marker ($0.20$ m $\times$ $0.20$ m).
     - Verify transition: `SEARCHING` $\to$ `RELOCALIZING` $\to$ `LOCALIZED`.
  3. For iOS ARKit:
     - Build `apps/ios-ar` in Xcode 16 on a macOS workstation and install via TestFlight or direct cable.
     - Verify SceneKit route rendering, floor transitions, and relocalization.

---

### Blocker 03 (P2): Remote Cloud Infrastructure & Public Ingress TLS Unavailable

- **Severity:** **P2 - Deployment & Integration Blocker for Multi-Device Field Pilot**
- **Affected Subsystem:** Public Ingress, TLS Termination, Cross-Device Synchronization
- **Technical Root Cause:**
  - The current deployment runs entirely on `localhost` (`127.0.0.1`).
  - Android WebXR requires a secure context (`https://` or `localhost`). Remote mobile phones cannot open WebXR camera sessions over insecure `http://<lan-ip>:3000`.
  - No remote cloud server is provisioned with public DNS, domain certificates, or reverse proxy edge routing.
- **System Defense & Truthful Reporting:**
  - Deployment bundle automated test suite (`scripts/deploy/test-deploy.sh`) verifies all deployment configs, SSH deployment scripts, and Nginx edge proxy configs (50/50 checks pass).
- **What Was Verified Locally:**
  - Full local Docker Compose stack (`chaya-postgres-1`, `chaya-minio-1`, `chaya-keycloak-1`, `chaya-clamav-1`, `chaya-api-1`) and Next.js frontend running and healthy.
  - 12/12 End-to-end workflow steps and 21/21 adversarial tests pass on local endpoints.
- **Exact Remediation Plan & Unblocking Steps:**
  1. Provision a remote Linux host (Ubuntu 24.04 LTS, e.g. AWS EC2 `t3.xlarge` or Hetzner `AX52`).
  2. Configure DNS record `demo.chaya.dev` pointing to the public IP.
  3. Run the automated deployment script:
     ```bash
     bash infra/deploy/deploy.sh --host demo.chaya.dev --ssl-email ops@chaya.dev
     ```
  4. Verify HTTPS health:
     ```bash
     curl -sI https://demo.chaya.dev/api/health
     ```

---

### Blocker 04 (P3): Physical Venue Metric Control Survey Pending

- **Severity:** **P3 - Metric Accuracy Gate for Physical Pilot**
- **Affected Subsystem:** `chaya_worker.stages.plane_fitting`, `VenueFrames.java`
- **Technical Root Cause:**
  - Monocular reconstruction produces scale-free coordinate models.
  - To certify accuracy $< 0.05$ m across a real $1,000$ m$^2$ building, an operator must measure physical laser control distances across key architectural features.
- **System Defense & Truthful Reporting:**
  - The system forbids publishing any scan version as `is_current = true` if metric calibration scale residual exceeds $0.05$ m.
- **Exact Remediation Plan & Unblocking Steps:**
  1. Survey $\ge 5$ physical control points in the venue using a Leica Disto laser meter.
  2. Input measurements into the venue calibration interface (`/ops/calibration`).
  3. Execute metric alignment and verify scale residual is $< 0.02$ m prior to publication.

---

## 3. Summary of Blocker Resolutions Matrix

| Blocker ID | Prerequisite Action | Estimated Effort | Unblocked Gate |
|:---|:---|:---:|:---|
| **P0: GPU Worker** | Launch AWS `g5.2xlarge` with `Dockerfile.gpu` | 2 hours | `REAL-DATA TESTED (GPU)` |
| **P1: Mobile AR** | Connect physical Pixel 8 / iPhone 15 Pro | 1 hour | `PHYSICAL-DEVICE TESTED` |
| **P2: Cloud Edge** | Deploy stack to public host with Let's Encrypt | 2 hours | `DEPLOYED & VERIFIED (Cloud)` |
| **P3: Laser Survey** | Measure 5 physical distances in target building | 3 hours | `REAL VENUE DEMO READY` |
