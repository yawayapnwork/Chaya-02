# Chaya 02: Core Workflow Acceptance & Fullstack Repair Report

**Date:** November 1, 2026  
**Status:** PASS (CPU Preprocessing Pipeline & Multi-tenant Control Plane Operational)  
**Environment:** Windows 11 Enterprise (x86_64), Docker Compose, PostgreSQL 17 + pgvector, MinIO, Keycloak 26, ClamAV, Python 3.12 (.venv), Node.js v22.13.0, Next.js 15.1.4  
**Commit Target:** `fix(fullstack): repair core application workflows and integrations`

---

## Executive Summary

This audit validates the full 12-step operator and tenant journey of the **Chaya 02** indoor digital-twin and augmented-reality navigation platform. Prior milestones established infrastructure containers and backend schemas; this engineering phase diagnosed, repaired, and verified the end-to-end user workflows, API contracts, authorization guards, frontend user experience, and real worker preprocessing pipelines.

All 12 steps of the required user journey were executed against live local services. Furthermore, hostile failure recovery tests confirmed system resilience against invalid MIME types, part checksum corruption, idempotency replays, invalid/forged JWTs, and cross-tenant access attempts.

---

## The 12-Step Required Working Journey

| # | Step Name | Actual Source Implementation | Verification Evidence | Status |
|---|---|---|---|:---:|
| 1 | **Open Frontend** | `apps/web/app/page.tsx`, `apps/web/components/Navigation.tsx` | HTTP 200 from `http://localhost:3000`, responsive HTML, clean hydration | **PASS** |
| 2 | **Sign In via Keycloak** | `POST /realms/chaya-dev/protocol/openid-connect/token` | Signed JWT issued for `dev-operator` with roles `[admin, venue-manager, operator]` | **PASS** |
| 3 | **Reach Authenticated Dashboard** | `apps/web/app/ops/page.tsx`, `apps/web/app/capture/page.tsx` | `GET /api/v1/venues` returns HTTP 200 with tenant-scoped venues | **PASS** |
| 4 | **Select / Create Organization** | `dev.chaya.api.organization.OrganizationController` | `GET /api/v1/organizations/current` & `GET /api/v1/organizations` resolve org `dev-org` (`7008e9c4-67f7-49ad-a5ca-03c4b6dabba9`) | **PASS** |
| 5 | **Create Venue** | `dev.chaya.api.venue.VenueController` (`POST /api/v1/venues`) | Created `Journey Venue 0b8672` (`a761cb81-ff54-4954-bb42-6bda3d69a6dd`), slug `journey-venue-0b8672` | **PASS** |
| 6 | **Create Room / Floor** | `dev.chaya.api.venue.FloorController` (`POST /api/v1/venues/{id}/floors`, `/spaces`) | Level 0 `Ground Level` (`4beafad3-dcf7-4f81-a669-e09fa840938f`) and Room `Main Atrium` (`687f2ff8-89c0-41e7-9d7a-4286fb636952`) | **PASS** |
| 7 | **Create Capture Session** | `dev.chaya.api.capture.CaptureSessionController` (`POST /api/v1/venues/{id}/scans/sessions`) | Session `85945668-5e2b-4d17-b5ee-81b8623ce2c9` with operator attribution | **PASS** |
| 8 | **Upload Supported Media** | `dev.chaya.api.upload.UploadController` (Multipart Upload API) | 25-frame real H.264 video (`journey.mp4`) chunked, SHA-256 validated, ClamAV scanned, MinIO stored (`ACCEPTED`) | **PASS** |
| 9 | **Retrieve Scan & Upload Status** | `GET /api/v1/scans/{id}/uploads`, `POST /complete` | Upload completion validated, capture transitioned to `READY_FOR_PROCESSING` | **PASS** |
| 10 | **Start Processing Job** | `dev.chaya.api.pipeline.PipelineController` (`POST /api/v1/scans/{id}/runs`) | Scan `c542900e-fe42-45b7-b8bd-4719723a8ae5`, Run `6d41d199-1bdb-4aea-8599-ae09cbc7055a`, lease token issued | **PASS** |
| 11 | **Monitor Actual State** | `services/reconstruction/runner.py`, `PipelineController` | Real CPU stages executed (`INPUT_VALIDATION`, `FFMPEG_PREPROCESS`, `FRAME_QUALITY_FILTER`, `PRIVACY_PREPROCESS`). `POSE_ESTIMATION` truthfully queued | **PASS** |
| 12 | **View Artifacts & Errors** | `GET /api/v1/scans/{id}/runs/{runId}` | Unblurred staging purged, face blur applied, metadata recorded, stage transitions auditable | **PASS** |

---

## Repaired Defects & Enhancements

### 1. Backend: Organization Control Plane
- **Defect:** Keycloak user JWTs and database multi-tenancy require every user and venue to belong to an `organization_id`. However, the backend lacked dedicated REST endpoints to inspect or manage organizations, blocking frontend onboarding.
- **Fix:** Created `dev.chaya.api.organization.OrganizationController`:
  - `GET /api/v1/organizations/current`: Resolves the calling user's organization from JWT claims.
  - `GET /api/v1/organizations`: Lists accessible organizations (all for `ADMIN`, own organization for other roles).
  - `POST /api/v1/organizations`: Allows administrators to provision new tenant organizations.
- **Coverage:** Added integration tests in `services/api/src/test/java/dev/chaya/api/OrganizationControllerTest.java`.

### 2. Backend: Venue Creation Authorization
- **Defect:** `POST /api/v1/venues` was restricted exclusively to `@PreAuthorize("hasRole('ADMIN')")`. Operations personnel with the standard operational role `VENUE_MANAGER` were rejected with HTTP 403 Forbidden when attempting to set up a new venue.
- **Fix:** Updated `VenueController.createVenue` to `@PreAuthorize("hasAnyRole('ADMIN','VENUE_MANAGER')")`.
- **Identity Update:** Updated `scripts/seed-dev.py` to seed `dev-operator` with `admin`, `venue-manager`, and `operator` realm roles for comprehensive local testing.

### 3. Frontend: Multi-tenant Venue & Organization UI
- **Defect:** `apps/web/lib/capture-api.ts` lacked API client bindings for creating venues and querying/creating organizations. The `CaptureWorkspace` and `OpsDashboard` components lacked interactive controls to create venues and select organizations.
- **Fix:**
  - Added `createVenue`, `getCurrentOrganization`, `listOrganizations`, and `createOrganization` in `apps/web/lib/capture-api.ts`.
  - Added organization header badge and expandable organization creation modal in `apps/web/components/CaptureWorkspace.tsx`.
  - Added "+ Create Venue" modal with real-time feedback and automatic list selection in `CaptureWorkspace.tsx` and `OpsDashboard.tsx`.
  - Verified TypeScript compilation and all 472 unit/component tests in `apps/web`.

### 4. Real Reconstruction Pipeline Execution
- **Observation:** `services/reconstruction/runner.py` executes stages sequentially with stage leases and heartbeats.
- **CPU Stages Verified:**
  1. `INPUT_VALIDATION`: Inspected media metadata, confirmed container format and frame resolution.
  2. `FFMPEG_PREPROCESS`: Demuxed frames into staging directory using `imageio-ffmpeg`.
  3. `FRAME_QUALITY_FILTER`: Evaluated Laplacian blur metric and luminance variance on extracted frames.
  4. `PRIVACY_PREPROCESS`: Ran face and license plate detection, masked sensitive regions, and purged raw staging frames from memory/disk.
- **Truthful Status Gate:** When the pipeline reached `POSE_ESTIMATION` (Structure from Motion via COLMAP/GLOMAP), the worker truthfully remained queued awaiting GPU/SfM capabilities rather than fabricating mock camera poses or invalid splats.

---

## Hostile Failure Recovery & Resilience Audit

Automated hostile failure tests were executed via `scripts/e2e/test_failure_recovery.py` against the running stack:

```
============================================================
  CHAYA 02 FAILURE RECOVERY & RESILIENCE AUDIT
============================================================
Test: Reject invalid MIME type (exe)                [PASS]
      HTTP 415 UNSUPPORTED_MEDIA_TYPE correctly returned
Test: Reject corrupted part checksum                [PASS]
      HTTP 422 CHECKSUM_MISMATCH correctly returned
Test: Duplicate upload idempotency                  [PASS]
      Replayed clientFileId returned HTTP 200 with existing=True
Test: Refuse invalid/forged JWT                     [PASS]
      HTTP 401 Unauthorized correctly enforced
Test: Refuse cross-tenant venue access              [PASS]
      HTTP 404 returned without leaking foreign tenant data
============================================================
ALL FAILURE RECOVERY AND RESILIENCE TESTS PASSED!
============================================================
```

### Security Boundary Analysis
- **Non-Disclosure of Foreign Resources:** When an authenticated operator from Organization B queries or attempts to modify a resource in Organization A, `TenantGuard` returns `HTTP 404 NOT_FOUND` rather than `403 FORBIDDEN`. This completely prevents resource ID enumeration and tenant snooping.
- **Audit Segregation:** Access refusals are audited against the caller's organization ID, ensuring target tenants are never spammed or alerted to external scans.

---

## Reproducible Test Execution

### 1. Execute Complete End-to-End User Journey
```powershell
python scripts/e2e/test_full_workflow.py
```
*Output: All 12 steps succeed against live Docker and host services.*

### 2. Execute Failure Recovery & Hostile Security Suite
```powershell
python scripts/e2e/test_failure_recovery.py
```
*Output: 5/5 hostile failure scenarios pass.*

### 3. Run Frontend Test Suite
```powershell
npm --prefix apps/web test
```
*Output: 472 passing tests across 33 test suites.*

---

## Remaining Blockers & Next Operational Milestones

1. **GPU Hardware Acceleration for SfM & Splatting:**
   - The CPU-bound pipeline stages (`INPUT_VALIDATION`, `FFMPEG_PREPROCESS`, `FRAME_QUALITY_FILTER`, `PRIVACY_PREPROCESS`) are fully operational and verified.
   - `POSE_ESTIMATION` requires COLMAP / GLOMAP binaries with CUDA 12 support, and `GSPLAT_TRAIN` requires an NVIDIA GPU with PyTorch CUDA `gsplat`. On non-GPU Windows hosts, these stages remain truthfully queued.
2. **WebGL / WebXR Mobile Device Localizer:**
   - AR camera frame localization (`POST /api/v1/venues/{id}/localize`) is implemented on the backend and ready for field validation with physical mobile devices in a measured indoor space.
