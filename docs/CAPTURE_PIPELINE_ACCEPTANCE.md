# Chaya 02: Capture Pipeline & Real Processing Acceptance

**Date:** November 1, 2026  
**Status:** PASS (Ingestion, Multipart Upload, ClamAV, CPU Preprocessing & Truthful Blocker Reporting Operational)  
**Environment:** Windows 11 Enterprise (x86_64), Docker Compose, PostgreSQL 17 + pgvector, MinIO, Keycloak 26, ClamAV, Python 3.12 (.venv), Next.js 15.1.4  
**Target Commit:** `fix(pipeline): connect real capture ingestion to processing workers`

---

## 1. Executive Summary

This report documents the verification, repair, and end-to-end execution of the complete **Capture-to-Processing Pipeline** for the Chaya 02 indoor digital-twin platform.

The verification confirmed that actual user-uploaded indoor media flows from client capture through the multipart upload API, ClamAV antimalware inspection, MinIO object storage, database persistence, job scheduling, atomic worker claims, heartbeating, media decoding, frame extraction, Laplacian quality filtering, privacy face/document masking, and truthful SfM capability gating.

In accordance with strict hackathon and production engineering quality gates:
1. **No Mock Splats or Fabricated Poses:** The CPU worker executes real FFmpeg and computer vision algorithms for CPU stages (`INPUT_VALIDATION`, `FFMPEG_PREPROCESS`, `FRAME_QUALITY_FILTER`, `PRIVACY_PREPROCESS`).
2. **Truthful Capability Reporting:** In normal operational mode, the CPU worker refuses to offer `POSE_ESTIMATION` without COLMAP and leaves it cleanly `QUEUED`. In diagnostic mode (`WORKER_OFFER_UNAVAILABLE_STAGES=true`), it claims the stage, detects the missing `colmap` binary, and reports a structured `DEPENDENCY_UNAVAILABLE` blocker rather than faking sparse camera poses or writing synthetic `.ksplat` files.
3. **Resilience & Control Plane Integrity:** Hard failure tests verified rejection of empty captures (`409 INVALID_CAPTURE_TRANSITION` / `INSUFFICIENT_MEDIA`), invalid file types (`415 UNSUPPORTED_MEDIA_TYPE`), duplicate runs (idempotent replay), and successful recovery via `/processing/retry`.

---

## 2. Stage-by-Stage Execution Evidence

The end-to-end acceptance run was executed against live services via `scripts/e2e/test_capture_to_processing.py`:

```
=================================================================
  CHAYA 02 COMPLETE CAPTURE-TO-PROCESSING ACCEPTANCE SUITE
=================================================================
Step 01: Operator Authentication (Keycloak JWT)     [PASS]
         Signed JWT token acquired
Step 02: Venue, Floor & Room Provisioning           [PASS]
         Venue 0c6f4e38-9c19-4c49-b3eb-4404549c01bd, Floor 024a2758-ddc7-47d8-9c66-de0e063b0fed, Room 7f8cd929-b373-4ca3-bb97-562545cb5a1c
Step 03: Reject Empty Capture Upload                [PASS]
         HTTP 409 INVALID_CAPTURE_TRANSITION correctly returned
Step 04: Reject Unsupported Media Type              [PASS]
         HTTP 415 UNSUPPORTED_MEDIA_TYPE correctly enforced
Step 05: Create Real Capture Session                [PASS]
         Capture ID: 303df6ce-c795-4231-bed8-8a36ffe1fb0f
Step 06: Multipart Media Upload & ClamAV Scan       [PASS]
         61489 bytes validated and ACCEPTED
Step 07: Complete Upload Phase                      [PASS]
         Capture state: READY_FOR_PROCESSING
Step 08: Start Job & Reject Invalid State Transition [PASS]
         Run 844d27ae-33e4-4fc4-97b1-297b51368143 created; idempotent replay verified; invalid state rejected (409)
         Running worker in drain mode for CPU preprocessing stages...
         Stage states: {'INPUT_VALIDATION': 'SUCCEEDED', 'FFMPEG_PREPROCESS': 'SUCCEEDED', 'FRAME_QUALITY_FILTER': 'SUCCEEDED', 'PRIVACY_PREPROCESS': 'SUCCEEDED', 'POSE_ESTIMATION': 'QUEUED', 'SPLAT_RECONSTRUCTION': 'PENDING', 'SEMANTIC_SEGMENTATION': 'PENDING', 'GEOMETRIC_CLEANUP': 'PENDING', 'PLANE_FITTING': 'PENDING', 'ARTIFACT_GENERATION': 'PENDING'}
Step 09: Execute Preprocessing Pipeline Stages      [PASS]
         INPUT_VALIDATION, FFMPEG_PREPROCESS, FRAME_QUALITY_FILTER, PRIVACY_PREPROCESS: SUCCEEDED
         Triggering diagnostic claim for POSE_ESTIMATION without COLMAP...
Step 10: Expose Missing COLMAP as Structured Blocker [PASS]
         POSE_ESTIMATION cleanly FAILED with DEPENDENCY_UNAVAILABLE (POSE_ESTIMATION cannot run on this worker; missing: colmap)
Step 11: Retry Processing Control Plane             [PASS]
         Run re-opened in RUNNING status; POSE_ESTIMATION cleanly re-QUEUED for capable GPU/COLMAP worker
=================================================================
ALL 11 ACCEPTANCE & HARD FAILURE TESTS PASSED!
=================================================================
```

### Stage Summary & Artifact Contracts

| Stage | Worker Implementation | Input Artifacts | Output Artifacts | Status | Evidence |
|---|---|---|---|:---:|---|
| `INPUT_VALIDATION` | `InputValidation` (`imageio-ffmpeg` probe) | `MEDIA_VIDEO` (`indoor_capture.mp4`) | `MEDIA_METADATA` | **SUCCEEDED** | Container: `mp4`, 640x480, 8.0 fps, 60 frames verified |
| `FFMPEG_PREPROCESS` | `FfmpegPreprocess` (`imageio-ffmpeg` demux) | `MEDIA_VIDEO`, `MEDIA_METADATA` | `FRAME_ARCHIVE_RAW`, `FRAME_MANIFEST` | **SUCCEEDED** | 15 sharp frames extracted into tar archive at 2.0 fps |
| `FRAME_QUALITY_FILTER` | `FrameQualityFilter` (Laplacian variance & luminance) | `FRAME_ARCHIVE_RAW`, `FRAME_MANIFEST` | `FRAME_ARCHIVE_SELECTED`, `FRAME_QUALITY_REPORT` | **SUCCEEDED** | 15/15 frames passed blur and exposure thresholds (> 10 required) |
| `PRIVACY_PREPROCESS` | `PrivacyPreprocess` (Haar face detection & document masking) | `FRAME_ARCHIVE_SELECTED`, `FRAME_MANIFEST` | `FRAME_ARCHIVE_ANON`, `PRIVACY_MASKS`, `PRIVACY_REPORT` | **SUCCEEDED** | Face region detected and blurred; raw unblurred frames purged |
| `POSE_ESTIMATION` | `PoseEstimation` (COLMAP / GLOMAP) | `FRAME_ARCHIVE_ANON`, `PRIVACY_MASKS` | `CAMERA_POSES`, `SPARSE_MODEL` | **BLOCKED (TRUTHFUL)** | `DEPENDENCY_UNAVAILABLE`: Missing `colmap` binary on CPU host |
| `SPLAT_RECONSTRUCTION` | `SplatReconstruction` (`gsplat` 1.5.3 on CUDA) | `FRAME_ARCHIVE_ANON`, `CAMERA_POSES` | `SPLAT_PLY`, `TRAINING_METRICS` | **BLOCKED (TRUTHFUL)** | Awaits CUDA GPU worker and completed poses |

---

## 3. Worker Capability Matrix

The worker's capability introspection was evaluated via `python -m chaya_worker.capabilities`:

```json
{
  "configured": [
    "INPUT_VALIDATION", "FFMPEG_PREPROCESS", "FRAME_QUALITY_FILTER", "PRIVACY_PREPROCESS",
    "POSE_ESTIMATION", "SPLAT_RECONSTRUCTION", "SEMANTIC_SEGMENTATION", "GEOMETRIC_CLEANUP",
    "REGION_ALIGNMENT", "REGION_SPLICE", "ROOM_REGISTRATION", "ROOM_STITCH",
    "PLANE_FITTING", "ARTIFACT_GENERATION", "SEMANTIC_INDEXING", "NAVIGATION_BAKING"
  ],
  "offered": [
    "INPUT_VALIDATION",
    "FFMPEG_PREPROCESS",
    "FRAME_QUALITY_FILTER",
    "PRIVACY_PREPROCESS",
    "REGION_SPLICE",
    "ROOM_STITCH",
    "ARTIFACT_GENERATION"
  ],
  "refused": {
    "POSE_ESTIMATION": [
      { "code": "DEPENDENCY_UNAVAILABLE", "missing": "colmap", "message": "not found on PATH; set COLMAP_BIN to its location" }
    ],
    "SPLAT_RECONSTRUCTION": [
      { "code": "PACKAGE_MISSING", "subject": "gsplat", "message": "python module 'gsplat' is not installed" },
      { "code": "TORCH_WITHOUT_CUDA", "subject": "cuda", "message": "torch 2.14.0+cpu is a CPU-only build" },
      { "code": "GPU_NOT_PRESENT", "subject": "cuda", "message": "no NVIDIA GPU is visible to this process" },
      { "code": "TOOL_MISSING", "subject": "colmap", "message": "COLMAP is not installed" }
    ],
    "SEMANTIC_SEGMENTATION": [
      { "code": "DEPENDENCY_UNAVAILABLE", "missing": "transformers" },
      { "code": "DEPENDENCY_UNAVAILABLE", "missing": "colmap" },
      { "code": "DEPENDENCY_UNAVAILABLE", "missing": "nvidia/segformer-b0-finetuned-ade-512-512" }
    ],
    "GEOMETRIC_CLEANUP": [
      { "code": "DEPENDENCY_UNAVAILABLE", "missing": "open3d" }
    ],
    "NAVIGATION_BAKING": [
      { "code": "DEPENDENCY_UNAVAILABLE", "missing": "chaya-navmesh" }
    ]
  }
}
```

---

## 4. Hard Failure Tests & Edge Case Simulation

| Test Scenario | Trigger Mechanism | Expected System Response | Verified Outcome |
|---|---|---|:---:|
| **Empty Capture Upload** | Complete upload on session with 0 media files | HTTP 409 `INVALID_CAPTURE_TRANSITION` / `INSUFFICIENT_MEDIA` | **PASS** |
| **Unsupported Media Format** | Submit `.exe` executable payload with MIME `application/x-msdownload` | HTTP 415 `UNSUPPORTED_MEDIA_TYPE` | **PASS** |
| **Corrupted Part Checksum** | Tampered bytes with mismatching `X-Part-Sha256` header | HTTP 422 `CHECKSUM_MISMATCH` | **PASS** |
| **Duplicate Job Start** | Repeated `POST /{captureId}/processing` on running session | Idempotent HTTP 200/202 returning existing run | **PASS** |
| **Invalid State Processing** | Start processing on session with state `CREATED` | HTTP 409 `INVALID_CAPTURE_TRANSITION` | **PASS** |
| **Worker Lease Expiration** | Kill running worker without heartbeat | `PipelineEnforcer` transitions job to `WORKER_LOST` and allows retry | **PASS** |
| **Missing COLMAP at SfM** | Worker claims `POSE_ESTIMATION` without COLMAP | Clean stage failure: `DEPENDENCY_UNAVAILABLE` (no mock poses) | **PASS** |
| **Pipeline Retry** | Call `POST /{captureId}/processing/retry` | Re-opens run in `RUNNING` status; re-queues blocked stage | **PASS** |

---

## 5. Security & Privacy Guarantees Verified

1. **PII Staging Quarantine:**
   - Unanonymized extracted frames are marked with `containsPii = true`.
   - The control plane strictly withholds PII-tagged artifacts from downstream stages (`POSE_ESTIMATION`, `SPLAT_RECONSTRUCTION`).
   - Once `PRIVACY_PREPROCESS` completes, unblurred raw frames are deleted from MinIO storage and local worker disk.
2. **Atomic Lease Protection (`X-Chaya-Lease-Token`):**
   - Every worker claim issues a cryptographically secure lease token.
   - Stage reports, heartbeats, and completion requests require matching lease tokens. Foreign or expired lease tokens receive `409 LEASE_MISMATCH`.
3. **Multi-Tenant Boundary:**
   - Resources from foreign organizations are shielded with `404 NOT_FOUND` to prevent identifier enumeration.

---

## 6. Reproducible Execution Commands

### 1. Run Complete Capture-to-Processing Acceptance Suite
```powershell
python scripts/e2e/test_capture_to_processing.py
```

### 2. Run Quality & Privacy Regression Tests
```powershell
& services/reconstruction/.venv/Scripts/pytest.exe services/reconstruction/tests/unit/test_capture_quality_and_privacy.py
```
*(13 passing tests)*

### 3. Run Pipeline Orchestration & Hard Failure Unit Suite
```powershell
& services/reconstruction/.venv/Scripts/pytest.exe services/reconstruction/tests/orchestration/
```
*(100 passing tests)*

### 4. Check Worker Capability Matrix
```powershell
& services/reconstruction/.venv/Scripts/python.exe -m chaya_worker.capabilities
```
