# Chaya 02: Real-World Venue Acceptance & Field Readiness Report

**Date:** 2026-11-01  
**Auditor:** Adversarial QA Lead & Principal Robotics Navigation Engineer  
**Classification:** **FIELD BLOCKED (LAB & CODE PROVEN)**  
**Reference Document:** `Chaya_02_Winning_Solution.docx`

---

## 1. Executive Summary

To achieve certification as **REAL VENUE DEMO READY** or **LIMITED PILOT READY**, an indoor navigation platform cannot merely pass synthetic unit tests or load pre-baked 3D geometry. It must withstand the hostile realities of the physical world:
1. Dynamic lighting variations and featureless drywall corridors.
2. Lens distortion and rolling-shutter artifacts from handheld smartphone sensors.
3. Visual-Inertial Odometry (VIO) drift accumulating across multi-hundred-meter walks.
4. Scale ambiguities where uncalibrated models deviate from physical tape-measured ground truth.
5. Multi-floor transitions via staircases and elevators without losing world coordinate tracking.

In adherence to zero-trust engineering ethics, this report certifies what has genuinely been proven on real datasets and what remains **BLOCKED** on physical field deployment.

---

## 2. Real-World Field Challenges & Engineering Defenses

### 2.1 Metric Scale Calibration & Ground-Truth Verification

| Physical Challenge | System Defense | Tolerance Threshold | Test Status |
|:---|:---|:---:|:---:|
| **Scale Ambiguity in Monocular SfM** | Monocular COLMAP SfM produces up-to-scale point clouds ($s$ is unknown). Without metric calibration, distance queries and velocities are physically meaningless. | Scale relative spread $\le 0.05$ | **PASS** (Unit & Fixture Gating) |
| **Tape-Measured Calibration Points** | The operator records $\ge 2$ physical metric distances or $\ge 3$ surveyed control points with laser meters ($\Delta \le 0.005$ m). | Scale residual $\le 0.02$ m | **PASS** (Algorithm Verified) |
| **Independent Check Measurements** | The system enforces held-out "check" measurements not used in calculating the similarity transform ($s, \mathbf{R}, \mathbf{t}$). | Max check relative error $\le 2.0\%$ | **PASS** (`real_venue.py` Step 0) |
| **Non-Canonical Reconstructions** | Reconstructions without verified gravity alignment (+Z up) and metric scale are strictly gated. | `require_canonical_frame == true` | **PASS** (Viewer withholds overlays) |

---

### 2.2 Visual-Inertial Odometry Drift & Anchor Relocalization

In physical venues, smartphone AR tracking drifts at approximately $1-3\%$ of distance walked. Chaya 02 handles this through a deterministic relocalization state machine:

```
  ┌──────────────┐       Marker Observed       ┌──────────────┐
  │  SEARCHING   │ ──────────────────────────> │ RELOCALIZING │
  └──────────────┘                             └──────┬───────┘
         ▲                                            │
         │  Tracking Lost / Drift Exceeded            │ Residual <= 0.30m & Tilt <= 15°
         │                                            ▼
  ┌──────────────┐        VIO Degraded         ┌──────────────┐
  │TRACKING_LOST │ <────────────────────────── │  LOCALIZED   │
  └──────────────┘                             └──────┬───────┘
         ▲                                            │
         │             Timeout > 3.0 s                │ Tracking Quality Drops
         └───────────────────────────────────── ┌─────▼────────┐
                                                │   LIMITED    │
                                                └──────────────┘
```

#### Enforced Relocalization Rules:
1. **Printed Marker Scale Factor ($\pm 20\%$):**
   - The observed image width $w_{\text{obs}}$ is compared against registered marker size $w_{\text{reg}}$.
   - $0.80 \le \frac{w_{\text{obs}}}{w_{\text{reg}}} \le 1.20$.
   - Markers scaled incorrectly (e.g. printed at 50% scale or viewed from extreme angles) are rejected immediately.
2. **Gravity Tilt Limit ($\le 15^\circ$):**
   - The angle between device gravity vector $+Y_{\text{dev}}$ and venue canonical $+Z_{\text{venue}}$ must not exceed $15^\circ$.
   - Prevents upside-down or sideways relocalizations caused by reflections or ambiguous fiducial sightings.
3. **Multi-Anchor Residual Filter:**
   - When multiple markers are visible in the same frame, translation residual must be $\le 0.30$ m and rotation residual $\le 8.0^\circ$.
   - Conflicting markers are isolated via RANSAC; if only two conflicting markers are visible, the system fails closed to `SEARCHING`.
4. **Limited Tracking Grace Window ($\le 3.0$ s):**
   - If device tracking drops (e.g., camera obstructed by a hand or passerby), the route remains frozen for 3.0 seconds.
   - If tracking is not restored within 3.0 seconds, state transitions to `TRACKING_LOST` and the route overlay is hidden to prevent guiding users through walls.

---

### 2.3 Wheelchair Accessibility & ADA Grade Limits

Physical venues require strict adherence to accessibility standards:
- **Maximum Walkable Grade:** ADA $1:12$ standard slope is $4.76^\circ$. Standard walking allows up to $45^\circ$ for stairs.
- **Corridor Clearance:** Wheelchair pathways require a minimum clear width of $0.90$ m ($36$ inches).
- **Step-Free Enforcement:** When an operator or visitor selects `stepFree = true`, `CorridorPath.java` closes all NavMesh edges with steps $> 0.03$ m or slope $> 4.76^\circ$.
- **Fails-Closed Architecture:** Unmeasured corridors or regions without verified NavMesh baking return HTTP 409 `NAVMESH_NOT_READY` rather than guessing.

---

### 2.4 Multi-Room Traversal & Incremental Rescans

In operational buildings, physical spaces change frequently (furniture rearranged, seasonal exhibits, construction):
1. **Overlap Requirement:**
   - Incremental rescanning requires $\ge 30\%$ geometric overlap with unchanged adjacent areas.
   - Verified in `services/reconstruction/tests/unit/test_region_splice.py`.
2. **Non-Destructive Versioning:**
   - Capture runs generate new scan versions (`v1`, `v2`, etc.).
   - A failed rescan run (e.g. insufficient feature matches in a dark room) aborts cleanly without corrupting the active published version.
   - Certified twins remain viewable and routable throughout rescan processing.

---

## 3. Physical Field Test Requirements Checklist

To elevate Chaya 02 from **LOCAL DEMO READY** to **REAL VENUE DEMO READY**, the following physical on-site tests must be completed:

- [ ] **Physical Venue Survey:**
  - Complete multi-room capture of an actual commercial facility ($> 500$ m$^2$).
  - Record $\ge 5$ ground-truth control points using a calibrated laser meter.
- [ ] **CUDA GPU Dense Reconstruction:**
  - Run COLMAP feature extraction and matcher across $\ge 200$ high-resolution frames.
  - Execute `gsplat` training for 30,000 iterations on an NVIDIA RTX 4090 or A10G GPU.
  - Achieve PSNR $\ge 26.0$ dB and SSIM $\ge 0.85$ on held-out validation views.
- [ ] **Android ARCore Field Validation:**
  - Open `/ar` in Chrome on a physical Google Pixel 8 or Samsung Galaxy S24.
  - Relocalize against a wall-mounted printed image target.
  - Walk a 100-meter route through multiple corridors; measure physical alignment error ($< 0.35$ m at 5 m).
- [ ] **iOS ARKit Field Validation:**
  - Build and install `apps/ios-ar` on a physical iPhone 15 Pro / 16 Pro.
  - Validate SceneKit 3D route tube projection over physical floor surfaces.
  - Test elevator/staircase floor handoff between two distinct levels.
- [ ] **Cloud Edge Deployment:**
  - Deploy Docker Compose stack to an internet-facing cloud host with public HTTPS termination.
  - Validate sub-100 ms API response times over public 5G mobile networks.
