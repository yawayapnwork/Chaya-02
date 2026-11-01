# REAL-WORLD ACCEPTANCE REPORT: CHAYA 02
**Field Validation, Calibration Standards & Ground Truth Verification**

**Audit Date:** 2026-11-01  
**Lead Auditor:** Independent Principal Engineer & QA Lead  
**Evaluation Target:** Real-World Readiness against `Chaya_02_Winning_Solution.docx`  
**Current Status:** **BLOCKED (Level 4 - Fixture Validated Only)**

---

## 1. Ground Truth Protocol & Rigorous Standards

A digital twin navigation system cannot claim physical readiness without documented, repeatable, and tamper-proof ground truth verification. The acceptance protocol enforces strict physical tolerances across every stage:

| Pipeline Stage | Acceptance Metric | Maximum Allowable Tolerance | Physical Verification Method |
|---|---|---|---|
| **Structure from Motion** | Camera-center RMSE | < 5.0 mm | Laser tracker / surveyed tripod stations |
| **Structure from Motion** | Reprojection Error | < 1.0 px | Mean reprojection over all registered 2D tracks |
| **Scale Calibration** | Scale Factor Residual | < 1.0 % | Held-out laser distance measurements (independent of fit) |
| **Coordinate Frame** | Transverse Spread | >= 0.5 m | Minimum perpendicular distance off collinear line |
| **Coordinate Frame** | Control Point Span | >= 2.0 m | Baseline length between furthest control points |
| **Geometry Cleanup** | Surface Retention | >= 90.0 % | Point density retention on ground-truth walls & floors |
| **Navigation Baking** | Accessible Slope | <= 4.76 deg (1:12) | Maximum grade across Detour polygon links |
| **Navigation Baking** | Corridor Clearance | >= 0.90 m | Minimum portal clearance between obstacle boundaries |
| **AR Relocalization** | Fix Residual | < 0.20 m | Kabsch/Umeyama solve residual across marker consensus |
| **End-to-End Navigation**| Arrival Error | < 0.50 m | Physical distance from target waypoint to user position |

---

## 2. Prerequisite Checklist Status

The official real-venue acceptance harness (`scripts/acceptance/real_venue.py`) enforces strict prerequisite gates before executing any stage or uploading media.

### Execution Log (Workstation Prerequisite Check)

```bash
python scripts/acceptance/real_venue.py \
  --api http://localhost:18080 \
  --token-file scratch/op.token \
  --venue none \
  --floor none \
  --media "%USERPROFILE%\Videos\Captures" \
  --thresholds scripts/acceptance/templates/thresholds.example.json \
  --out scratch/acceptance-out \
  --check-prerequisites
```

### Result: **Exit Code 5 (BLOCKED)**

| Prerequisite Check | Status | Exact Observed Finding | Required Remediation |
|---|---|---|---|
| **API Reachability** | **FAIL** | Connection refused (`WinError 10061`) | Deploy and maintain running API stack |
| **GPU Worker Presence** | **FAIL** | No live worker advertising `POSE_ESTIMATION` and `SPLAT_RECONSTRUCTION` | Attach CUDA GPU worker running `Dockerfile.gpu` |
| **Capture Metadata** | **FAIL** | `--capture-metadata` missing | Complete `capture-metadata.example.json` |
| **Written Consent** | **FAIL** | `--consent` missing | Obtain written site authorization & privacy agreement |
| **Survey Measurements** | **FAIL** | `--measurements` missing | Record >= 2 calibration distances and >= 1 held-out check |
| **Capture Media** | **FAIL** | Directory holds 0 MP4 videos and 0 valid JPEG/PNG frames | Perform real smartphone video scan |
| **Frozen Thresholds** | **PASS** | Template SHA-256 verified and pre-registered | Retain frozen thresholds file |
| **Local Disk Space** | **PASS** | > 15 GB free disk space available | Maintain sufficient workspace storage |

---

## 3. Public Fixture Evidence vs. Real Custom Venue

The repository has processed real photographic media, but **only from public academic and benchmark datasets**, never from a custom venue captured by an operator for Chaya 02.

```
┌────────────────────────────────────────────────────────────────────────┐
│                      DATASET VALIDATION SPECTRUM                       │
├────────────────────────────────────────────────────────────────────────┤
│ Synthetic L-Box   ──>   Sceaux Outdoor   ──>   ETH3D Office   ──>  ??? │
│ (Fully Tested)          (11 Photos SfM)        (26 Photos SfM)     Real│
│                                                                    Venue
└────────────────────────────────────────────────────────────────────────┘
```

### Comparative Analysis:

| Property | ETH3D `office` (Public Dataset) | Château de Sceaux (Public Dataset) | Required Real Venue |
|---|---|---|---|
| **Source Media** | 26 DSLR photos (Nikon D810) | 11 outdoor DSLR photos | Smartphone video / continuous frames |
| **Environment** | Single static office room | Outdoor historic monument | Multi-room indoor facility with corridors |
| **Metric Scale** | Laser scan ground truth (mm) | Arbitrary SfM scale | Tape / laser distance control points |
| **Registered Frames** | **14 / 21 kept frames** (66.7%) | **11 / 11 frames** (100%) | >= 90.0% frame registration |
| **Reprojection Error**| 0.52 px | 0.398 px | < 1.0 px |
| **Scale Error** | 1.67 % vs laser ground truth | Uncalibrated | < 1.0 % |
| **Objects Present** | Desks, chairs, monitor | Stone facade, statues | Staircase, elevator, exit, reception |
| **NavMesh Tested** | No (SfM only) | No (SfM only) | Standard & step-free multi-floor graph |
| **Physical AR Tested**| No | No | On-device marker tracking & navigation |

---

## 4. Calibration Engine & Coordinate Frame Verification

The coordinate frame transformation and calibration subsystem (`services/api/.../CoordinateFrameService.java`) was rigorously evaluated against synthetic mathematical scenarios and ETH3D data:

### Key Architectural Protections:
1. **Collinearity and Transverse Spread Gate:**
   - Control points must maintain >= 0.5 m transverse spread off any best-fit line. Collinear points leave rotation about the line unconstrained; the service explicitly rejects collinear sets with `COLLINEAR_POINTS`.
   - Verified by: `CoordinateFrameCalibrationTest.controlPointsWithSmallTransverseSpreadAreRefused`.
2. **Duplicate Reference Rejection:**
   - Distance references sharing identical endpoint identifiers or labels are rejected.
   - Verified by: `CoordinateFrameCalibrationTest.duplicateDistanceReferencesAreRefused`.
3. **Independent Holdout Verification:**
   - Distance measurements marked `"role": "check"` are held out completely from the Procrustes / Umeyama scale fit. The reconstructed distance multiplied by the fitted scale is compared independently against the physical tape reading.
   - Verified by: `CoordinateFrameService.validateHoldouts`.
4. **Coordinate Frame Retirement & Reprojection:**
   - Recalibrating the same reconstruction updates the canonical frame and reprojects existing POIs and AR anchors exactly.
   - Superseded navigation graphs in old frames are retired (`status = 'RETIRED'`) to prevent routing on stale geometry.
   - Verified by: `CoordinateFrameCalibrationTest.recalibratingTheSameReconstructionSupersedesTheFrameAndReprojectsPoisAndAnchorsExactly`.

---

## 5. Physical AR & Mobile Client Audit

Neither client application has executed in a real venue:

### Android WebXR Client (`apps/web/components/ArWorkspace.tsx`):
- Relies on the experimental `webxr-incubations` flag in Chromium for WebXR image tracking (`navigator.xr.isSessionSupported("immersive-ar")`).
- On standard consumer Android devices without incubation flags enabled, the client properly fails closed with `IMAGE_TRACKING_UNSUPPORTED`.
- Tested extensively with unit and Playwright mocks (`webxr-support.test.ts`, `ar.spec.ts`), but **0 physical runs on an Android device have been conducted**.

### iOS ARKit Client (`apps/ios-ar`):
- Architecture embeds `ChayaARCore` (pure Swift models and relocalization state machine) and `ChayaARKitSession` (ARKit camera and SceneKit overlays).
- Compiles cleanly and passes 35 unit tests in Linux Swift (`swift test`).
- However, the Xcode workspace has never been deployed to a physical iPhone via TestFlight or Xcode USB deployment.
- CI pipeline for iOS was disabled due to macOS runner prerequisites.

---

## 6. Real-World Acceptance Verdict

### Verdict: **NOT READY FOR REAL-VENUE ACCEPTANCE**

The mathematical engines, safety gates, and calibration services are fully implemented and verified against synthetic and public fixtures. However, until the team performs:
1. An operator capture of a physical venue with written consent;
2. Reconstruction on a genuine CUDA GPU runner;
3. Ground-truth calibration with measured tape distances;
4. An on-site navigation session using a physical smartphone;

the real-world acceptance milestone cannot be granted.
