# CHAYA 02 — Architecture

Physical venue = **State 01**. Digital reconstruction = **State 02**.
Chaya 02 is a living digital twin: reconstructed, searchable, navigable, viewable in AR, and incrementally updatable.
This document is the source of truth for system boundaries and describes the system **as built** (checked against the
code on 2026-10-12). What is designed but not built, or built but not validated, is separated out in
[§16](#16-current-vs-planned-behaviour). How far each part has been validated is in
[PROJECT_PLAN.md](PROJECT_PLAN.md#validation-matrix): being described here does not mean it has run on a real venue or
device.

## 1. System boundaries

```
 ┌────────────┐  ┌──────────────┐  ┌────────────────┐
 │ Web (Next) │  │ Android WebXR│  │ iOS ARKit app  │   CLIENTS
 └─────┬──────┘  └──────┬───────┘  └───────┬────────┘
       └────────────────┴──────────────────┘
                  HTTPS + JWT (Keycloak)
                        │
              ┌─────────▼─────────┐        ┌─────────────┐
              │ Backend (Spring)  │◄──────►│ PostgreSQL  │
              │ REST /api/v1      │        │ + pgvector  │
              │ authz, audit, jobs│        └─────────────┘
              └───┬───────────▲───┘
   bytes streamed │           │ claim / heartbeat / report (HTTPS, worker service account)
   through the API│           │
              ┌───▼───────────┴───┐
              │ MinIO (S3 API)    │◄──── Python workers (CPU host; GPU hosts)
              └───────────────────┘
                        ▲
   services/vision ─────┘ (no storage access; the API calls it to embed search queries)
```

Boundaries are hard: clients never talk to Postgres or MinIO. Uploads and downloads are streamed **through the API**,
never through presigned URLs, so tenant scope, PII rules and SHA-256 verification apply to every byte
(docs/security.md, "Object storage"). Workers never touch the database; they use the backend's worker API and MinIO,
with a storage account that cannot delete or overwrite registered artifacts. Keycloak is the only issuer of identities.

## 2. Frontend (`apps/web`)
Next.js + TypeScript + Tailwind. Three.js with GaussianSplats3D **0.4.7** (exact pin) renders `.ksplat` assets after
checking their size, SHA-256 and format against what the API registered (`lib/artifact-integrity.ts`). POIs, routes and
search results are drawn per scan version (`lib/version-scope.ts`). **Routes are computed by the API** (§8); the browser
only draws them. Auth is OIDC authorization code + PKCE against Keycloak (`oidc-client-ts`); tokens are held in memory.
Browser configuration is read at request time from `CHAYA_PUBLIC_*` so one image serves every environment. Pages:
`/` (venues), `/capture` (upload, HUD, scan planning, calibration, processing), `/viewer`, `/ar` (Android WebXR),
`/ops`, `/status`. The frontend is a separately deployable container and talks only to `/api/v1`.

## 3. Backend (`services/api`)
Spring Boot (Java 21), a single deployable monolith organised by packages: `venue`, `capture`, `hud`, `planning`,
`scan`, `pipeline`, `processing`, `job`, `frame`, `reconstruction`, `rescan`, `poi`, `search`, `navigation`, `ar`,
`publiclink`, `erasure`, `ops`, `health`, `metrics`, `audit`, `security`, `storage`, `web`. Spring Security as an OAuth2
resource server validating Keycloak JWTs. Responsibilities: authorization (role + venue scope from the token), metadata
persistence, the pipeline control plane, artifact sealing and streaming, calibration, scan versions, semantic search,
routing, AR anchors, audit logging. **It never runs reconstruction.** Schema migrations via Flyway (§5). Health
(`/api/v1/health`) checks the real dependencies: database, MinIO, Keycloak JWKS, ClamAV, the vision service, measured
worker presence and processing-queue progress (DEPLOYMENT.md §6). Metrics, logs and error tracking: docs/monitoring.md
(ADR 0003).

### 3.1 Coordination and the single-instance constraint
PostgreSQL is the only shared state. There is no Redis, cache server or message broker, and none is needed for the
deployment this project ships: **exactly one API instance** per environment (`infra/deploy/docker-compose.yml`,
DEPLOYMENT.md). What is safe with any number of instances, and what is not:

| Mechanism | Where | Shared through | Correct with several API instances? |
|---|---|---|---|
| Job claim, leases, retries | `ProcessingJobRepository` | DB (`FOR UPDATE SKIP LOCKED`) | Yes |
| Run / version / frame state transitions | `PipelineService`, `ScanVersionService`, `CoordinateFrameService` | DB row locks | Yes |
| Worker presence | `WorkerPresenceRepository` | DB (`worker_presence`, V34) | Yes |
| Rate limits | `RateLimiter` | in-process counters | No: each instance enforces its own budget |
| Capture HUD live stream (SSE) | `CaptureHudBroadcaster`, `CaptureHudService` | in-process subscriber lists and versions | No: a subscriber sees only updates made on its own instance |
| Media validation resume | `MediaValidationService` | in-process executor; every instance resubmits `VALIDATING` media on start | No: duplicate validation |
| Periodic sweeps | `PipelineEnforcer`, `ErasureSweeper`, `PoiEmbeddingBackfill` | `@Scheduled` on every instance | Not designed for it (no leader election or advisory lock) |

Scaling the API out is therefore a design change, not a configuration change: rate limits and HUD fan-out need a
shared store (Redis is the obvious candidate), and the sweeps and validation resume need DB advisory locks or a
leader. Redis must not be re-added to the deployment before code uses it.

## 4. Processing pipeline (`services/reconstruction`)
One Python package (`chaya_worker`) runs every stage. A worker process claims only the stages listed in its
`WORKER_STAGES`, so the same code is deployed as a CPU worker and as GPU workers. Each stage checks its dependencies
first (`chaya_worker.toolchain`) and fails with a structured, retryable `DEPENDENCY_UNAVAILABLE` when one is missing;
**no stage produces output without running the real algorithm**. The normative description is
[docs/pipeline.md](docs/pipeline.md).

| # | Stage | Tooling | Image that claims it |
|---|-------|---------|----------------------|
| 1 | INPUT_VALIDATION | checksums, FFmpeg/OpenCV decode, calibration metadata | CPU (`Dockerfile`) |
| 2 | FFMPEG_PREPROCESS | FFmpeg | CPU |
| 3 | FRAME_QUALITY_FILTER | OpenCV (blur, exposure, near-duplicates) | CPU |
| 4 | PRIVACY_PREPROCESS | OpenCV detectors, blur, verification; fails closed | CPU |
| 5 | POSE_ESTIMATION | COLMAP features and matching; GLOMAP mapper if present, else COLMAP mapper | GPU (`Dockerfile.gpu`, CPU COLMAP 3.9) |
| 6 | SPLAT_RECONSTRUCTION | gsplat 1.5.3 on CUDA (torch 2.7.1+cu126) | GPU |
| 7 | SEMANTIC_SEGMENTATION | SegFormer (stock, research-only licence), projected onto the splat | GPU |
| 8 | GEOMETRIC_CLEANUP | Open3D outlier removal, semantic-aware filtering | GPU |
| 9 | PLANE_FITTING | Open3D RANSAC, gravity estimate | GPU |
| 10 | ARTIFACT_GENERATION | `.ksplat` encoder (pinned viewer layout), manifest, viewer bundle | CPU |
| 11 | SEMANTIC_INDEXING | Grounding DINO (stock) + CLIP ViT-B/32 embeddings, 3D localisation; metric | GPU |
| 12 | NAVIGATION_BAKING | chaya-navmesh (Recast/Detour, recastnavigation 1.6.0); metric | CPU |
| R1 | REGION_ALIGNMENT (re-scan only) | Open3D FPFH + ICP, robust similarity, quality gates; metric | GPU |
| R2 | REGION_SPLICE (re-scan only) | numpy; metric | CPU |
| S1 | ROOM_REGISTRATION (stitch only) | Open3D FPFH, gravity-yaw RANSAC, point-to-plane ICP, free-space gate, pose graph with loop closure; metric | GPU |
| S2 | ROOM_STITCH (stitch only) | numpy/scipy: Gaussian transform, voxel ownership, misalignment and seam checks; metric | CPU |

The CPU image has FFmpeg, OpenCV and chaya-navmesh and deliberately **no** COLMAP, GLOMAP, CUDA, gsplat or Open3D. The
GPU image has **never been built** (no GPU host was available); see DEPLOYMENT.md §10. `infra/ci/worker-colmap.Dockerfile`
is a test-only CPU COLMAP layer used by the E2E harness. Stages marked *metric* read the calibrated coordinate frame
from their work order and fail with `NOT_CALIBRATED` without one (§7a).

`services/vision` is not a pipeline worker: it is a small, always-on FastAPI service with one job -- embedding a live
search query's text with the same CLIP model SEMANTIC_INDEXING used to embed detected objects
(`chaya_worker.clip_embeddings`), so `dev.chaya.api.search` can rank POIs by cosine similarity. See docs/search.md.

Capture-time bounds estimation, path planning and coverage scoring run in the client (signals) and the API
(`hud`, `planning`: `RoomBoundsEstimator`, `CapturePathPlanner`); see docs/capture-hud.md and docs/route-planning.md.
FAISS is not used; persistent vector search is pgvector.

## 5. Database (PostgreSQL + pgvector)
Flyway migrations `V1`–`V43` in `services/api/src/main/resources/db/migration`, forward-only, applied at API startup
(DEPLOYMENT.md §5). Every tenant-scoped row carries `organization_id`, every venue-scoped row `venue_id`, and composite
foreign keys make cross-organization references impossible (ADR 0002). State columns are enums enforced by CHECK
constraints and transition triggers; stage records, artifacts and finalized versions are write-once.

| Area | Tables |
|---|---|
| Tenancy | `organization`, `venue`, `floor`, `space` (no membership table: roles and venue grants travel in the token, §7) |
| Capture | `capture_session`, `capture_media`, `capture_media_part`, `capture_measurement`, `capture_measurement_observation`, `capture_calibration_attempt` |
| HUD and planning | `capture_hud_scene`, `capture_hud_pose_sample`, `capture_hud_quality_sample`, `capture_hud_surface_sample`, `capture_hud_tracking_event`, `capture_plan` |
| Processing | `scan`, `pipeline_run`, `pipeline_stage_run`, `processing_job`, `processing_artifact`, `worker_presence`, `pii_staging_purge`, `pii_staging_sweep` |
| Frames and versions | `coordinate_frame`, `coordinate_frame_measurement`, `scan_version`, `scan_version_artifact` (`floor.current_scan_version_id`, V28) |
| Semantics | `poi`, `poi_version` (`embedding vector(512)`, HNSW), `search_query` |
| Navigation | `navigation_graph`, `navigation_node`, `navigation_edge`, `floor_connection` |
| AR | `ar_anchor`, `ar_anchor_pose` |
| Sharing, erasure, audit | `public_viewer_link`, `public_viewer_token`, `erasure_request`, `erasure_item`, `erasure_object`, `audit_log` (append-only) |

## 6. Object storage (MinIO)
S3-compatible; buckets `chaya-raw` (uploads) and `chaya-derived` (stage outputs, logs, sealed artifacts). Keys are
prefixed `org/{orgId}/venue/{venueId}/…`, so tenant isolation is checkable by prefix, and the database refuses a row
whose key is outside its own tenant (V29). Raw media is uploaded in resumable parts **through the API**, which verifies
size and checksum, sniffs the type (Tika) and scans it (ClamAV) before accepting it. Separate storage accounts for the
API, the CPU worker (raw read-only), GPU workers (no raw bucket, no `pii/`) and backups (read-only); DEPLOYMENT.md §2.
The artifact lifecycle is in §14.

## 7. Identity (Keycloak)
OAuth2/OIDC, one realm (`chaya`, imported from `infra/keycloak/chaya-realm.json`). Web: authorization code + PKCE. The
access token carries the tenant: `org_id`, `venue_id` and a realm role (`admin`, `venue-manager`, `operator`, `viewer`).
`TenantGuard` checks every venue-scoped call against them; access to another tenant's venue is answered **404**, not
403, and audited in the caller's organization. Workers authenticate with the `chaya-worker` client-credentials service
account (role `service`), which is limited to the worker API and can never read tenant data. Public viewer links issue
short-lived `X-Chaya-Viewer-Token`s bound to one venue. Details: [docs/security.md](docs/security.md).
**Organizations and users are provisioned outside the API** (SQL and the Keycloak admin console).

## 7a. Coordinate frames
Every stored position -- POIs, AR anchor poses, navigation graphs, route waypoints, re-scan regions -- is in the
canonical venue frame: metres, right-handed, +Z up (opposite gravity), origin fixed by the frame's horizontal datum. A
reconstruction's own frame (COLMAP/GLOMAP output) has arbitrary scale, rotation and origin; the only way from it to
metres is a calibrated, versioned similarity transform `X_canonical = s·R·X_reconstruction + t` in `coordinate_frame`,
owned by the control plane and built from measured references (at least two measured distances, or at least three
surveyed control points) plus a real gravity source (the reconstructed floor plane, operator floor points, or the
control points). Without it, metric consumers report `NOT_CALIBRATED` instead of assuming metres. Recast's +Y-up axes
and AR devices' +Y-up tracking frames are converted at one named boundary each. The normative description is
[docs/coordinate-frames.md](docs/coordinate-frames.md). The measurements themselves are recorded with the capture that
observed them (`capture_measurement`: value and unit as entered, floor, and the image pixels each point is seen at) and
every calibration attempt from them, accepted or refused, is kept; recording evidence never makes a reconstruction
metric on its own ([docs/capture-calibration.md](docs/capture-calibration.md)). The operator picks each measured point's
reconstruction coordinates by hand: the marked pixels are not triangulated automatically.

## 8. Navigation
The navmesh is generated by NAVIGATION_BAKING with Recast from the cleaned geometry (a multi-level surface model of the
cleaned, labelled splat: walkable surfaces at their own heights, obstacles as solids, steps as their own Recast area),
using configurable agent radius/height/slope. Accessibility (step-free) is one navmesh with two graphs: STEP_FREE leaves
out every link over a step or steeper than an accessible ramp, and the API applies measured clear width and slope
limits per edge. The navmesh is a versioned artifact (`NAVMESH`, pinned by its scan version). Route queries run in the
API (`RouteService`): Dijkstra over the Detour polygon graph, then the corridor is string-pulled through the stored
Detour portals (the funnel algorithm of Detour's `findStraightPath`), so a route stays inside the navmesh
(docs/navigation.md). No client runs Detour. No route is ever returned when no navmesh exists — the API returns
`NAVMESH_NOT_READY`.

## 9. AR clients
Android: WebXR (`immersive-ar`, ARCore) in the Next.js app (`/ar`). iOS: native Swift/ARKit app (`apps/ios-ar`; WebXR is
unsupported on iOS Safari). Both use the same backend path-data API (path polyline in venue frame + anchor list).
Localization: multi-anchor fiducial relocalization gives a venue-frame transform; VIO interpolates between fixes;
residual disagreement between anchors is reported and drift beyond a threshold prompts relocalization. Anchors are
registered in the floor's current canonical coordinate frame (§7a) with versioned poses; device poses arrive in the
device's gravity-aligned +Y-up convention and are converted at one boundary. See [docs/ar.md](docs/ar.md). **Neither
client has run on a physical device.**

## 10. Asynchronous job flow
Job states: `QUEUED → RUNNING → SUCCEEDED | FAILED | CANCELLED` (`FAILED → QUEUED` for a bounded retry). Run states:
`RUNNING`, `SUCCEEDED` (quality `FINAL`), `PARTIAL` (time budget ran out after a reconstruction artifact existed),
`FAILED` (retryable), `CANCELLED`. Database constraints enforce that `FINAL` goes only with `SUCCEEDED` and `PARTIAL`
only with `PARTIAL`; a partial reconstruction is never presented as final.

1. An operator calls `POST /api/v1/venues/{v}/captures/{c}/processing` → the API validates the capture, creates a
   `scan`, a `pipeline_run` and a DRAFT `scan_version`, queues the first stage as a `processing_job`, and answers `202`.
2. Workers poll `POST /api/v1/internal/jobs/claim` with their stage list. The claim takes a lease (default 300 s) under
   `FOR UPDATE SKIP LOCKED` and returns a work order: verified input artifacts, the attempt's output prefix, the
   deadline and, for metric stages, the calibrated frame. Every poll stamps `worker_presence`.
3. `POST …/{job}/heartbeat` every 60 s extends the lease and tells the worker to stop if the run was cancelled.
   `PipelineEnforcer` (every 30 s) fails expired leases as `WORKER_LOST` and overruns as `TIME_LIMIT_EXCEEDED`.
4. The worker writes outputs under its prefix, then sends one `POST …/{job}/report` with the stage record and the
   artifacts. The API seals and re-hashes each artifact (§14), checks the stage's required outputs against
   `packages/contracts/pipeline/stage-artifacts.json`, records the immutable `pipeline_stage_run`, and queues the next
   stage. A failed stage stops the run; `POST …/processing/retry` re-queues it.
5. Every transition is audited. Clients follow `GET …/processing/stream` (SSE, `ProcessingBroadcaster`) and recover from the polled snapshot
   `GET …/processing` (docs/pipeline.md, "Live progress"). No HTTP request
   blocks on reconstruction.

The legacy `POST /venues/{v}/scans/{s}/jobs` path and `/internal/jobs/{id}/complete|fail` still exist and create jobs
outside a run that no shipped worker can finish (BASELINE_AUDIT_2026-10-09 F6; health and the dashboard name such
stages as unserved). Removing them is an API-contract decision that has not been taken.

## 11. Infrastructure
Docker Compose for local development (`infra/docker`: Postgres with pgvector, MinIO, ClamAV, Keycloak, and the CPU
worker under the `pipeline` profile) and for production (`infra/deploy`: Caddy, web, API, CPU worker, vision, Postgres,
MinIO, ClamAV, Keycloak). There is no Redis: nothing needs a shared in-memory store while the API runs as **exactly one
instance** (§3.1). Images are built per service and pushed to GHCR by GitHub Actions; each deploys independently. GPU
workers run outside Compose on a GPU host. No Kubernetes, Kafka, Celery, or RabbitMQ.

## 12. Data flow

```
capture (web /capture: video/photos recorded elsewhere, HUD + scan plan + measured references)
  └─ resumable upload through the API ─► chaya-raw (Tika + ClamAV; quarantined if unscanned)
       └─ POST …/processing ─► scan + pipeline_run + DRAFT scan_version
            └─ CPU worker: INPUT_VALIDATION → FFMPEG_PREPROCESS → FRAME_QUALITY_FILTER → PRIVACY_PREPROCESS
                 └─ GPU worker: POSE_ESTIMATION → SPLAT_RECONSTRUCTION → SEMANTIC_SEGMENTATION
                                → GEOMETRIC_CLEANUP → PLANE_FITTING
                      └─ CPU worker: ARTIFACT_GENERATION (.ksplat)
                           └─ GPU worker: SEMANTIC_INDEXING ── needs a calibrated frame: NOT_CALIBRATED until
                                │                              POST …/captures/{c}/calibration (or …/coordinate-frames)
                                │                              then …/processing/retry
                                └─ CPU worker: NAVIGATION_BAKING (Recast)
                                     └─ last report: finalize + publish the scan_version
                                          (pins its artifacts; floor.current_scan_version_id)
  ─► viewer (/viewer: .ksplat streamed and verified), search (pgvector + vision), routes (RouteService), AR anchors
  ─► operations dashboard reads runs, jobs, workers, coverage, storage and the search log
```

A re-scan (`POST …/floors/{f}/rescan`) runs the first seven stages on the new region only, then REGION_ALIGNMENT and
REGION_SPLICE against the parent version, then re-derives PLANE_FITTING, ARTIFACT_GENERATION, SEMANTIC_INDEXING and
NAVIGATION_BAKING for the whole floor (§15, docs/rescan.md).

A multi-room floor is captured one room at a time. A room capture (`roomId`) runs the plan up to ARTIFACT_GENERATION,
and its version is a room version that is never published as the floor's. A stitch (`POST …/floors/{f}/stitches`)
is a run with no media. ROOM_REGISTRATION places the rooms' finalized clouds in the anchor room's frame, with pairwise
registration and loop closure. ROOM_STITCH merges their Gaussians without doubled surfaces. PLANE_FITTING,
ARTIFACT_GENERATION and NAVIGATION_BAKING then run on the merged floor, and NAVIGATION_BAKING refuses a floor whose
rooms are disconnected (docs/multi-room.md).

## 13. API contracts
All HTTP is under `/api/v1`. Breaking changes require `/api/v2` and `openapi/v2.yaml`. The contract is
`packages/contracts/openapi/v1.yaml`, **generated** from the controllers by springdoc and checked both ways by
`OpenApiContractTest` (every mapping documented, nothing documented that is not served, real responses match their
schemas) and by the web client's `lib/api-contract.test.ts` (every path the client calls is in the document);
packages/contracts/README.md explains how to regenerate it. The running API serves it at `GET /api/v1/openapi` to
authenticated callers. Errors are RFC 9457 problem+json with a stable `code` and an actionable `detail`, except 401
(empty body) and `/health`'s 503. The worker ↔ control-plane contract is the work order and the stage report
(docs/pipeline.md, "The stage contract") plus `packages/contracts/pipeline/stage-artifacts.json` (what each stage reads
and must publish), tested on both sides.

| Area | Main endpoints (`/api/v1`) |
|---|---|
| Venues | `GET/POST /venues`, `PATCH /venues/{v}`, `GET/POST /venues/{v}/floors` |
| Capture | `POST /venues/{v}/captures`, `…/media`, `PUT …/media/{m}/parts/{n}`, `…/complete`, `…/complete-upload`; HUD `…/hud/*`; plan `…/route-plan`; measurements and calibration `…/captures/{c}/measurements`, `…/calibration` |
| Processing | `POST/GET …/captures/{c}/processing`, `…/processing/retry`, `…/processing/cancel`; worker `POST /internal/jobs/claim`, `/{j}/heartbeat`, `/{j}/report` |
| Frames | `POST/GET /venues/{v}/reconstructions/{run}/coordinate-frames`, `GET …/floors/{f}/coordinate-frame` |
| Versions | `POST …/floors/{f}/rescan`, `GET …/scan-versions`, `POST …/scan-versions/finalize-current` |
| Rooms, stitching | `GET/POST …/floors/{f}/rooms`, `POST …/rooms/{r}/versions/finalize-current`, `GET/POST …/floors/{f}/stitches`, `GET …/stitches/{s}` |
| Viewer | `GET …/floors/{f}/reconstructions[/latest]`, `…/reconstructions/{run}`, `…/scan-versions/{sv}/reconstruction`, `…/artifacts/{kind}`; public links `…/public-links`, `POST /public/viewer-token` |
| POIs, search | `GET/POST/PUT/DELETE …/pois`, `GET …/search` |
| Navigation | `POST /navigation/routes`, `…/floor-connections` |
| AR | `…/floors/{f}/anchors`, `…/anchors/{a}/calibrate`, `…/anchors/{a}/target.png`, `…/anchors/relocalize` |
| Operations | `GET /venues/{v}/ops/*`, `GET /audit-log`, `GET /health`, `GET /version` |
| Erasure | `DELETE /venues/{v}`, `…/captures/{c}`, `…/scan-versions/{sv}`, `GET /erasures[/{id}]` |

## 14. Artifact lifecycle
1. **Written by a worker** under the attempt's server-issued prefix,
   `org/{org}/venue/{venue}/scan/{scan}/run/{run}/{STAGE}/attempt-{n}/…`, in `chaya-derived`. PII-bearing staging
   objects (unblurred frames) live under a `pii/` segment.
2. **Reported** in the stage report with size and SHA-256. The API refuses keys outside the prefix, wrong PII flags,
   and missing required outputs (`STAGE_OUTPUT_MISSING`, `ARTIFACT_UNREADABLE`, …).
3. **Sealed** (`ArtifactSealer`): copied server-side to `sealed/org/…/{nonce}/{file}`, the copy re-hashed and refused on
   a size or checksum mismatch, the `processing_artifact` row recorded write-once, the worker's original deleted. Worker
   accounts are denied writes and deletes under `sealed/`.
4. **Consumed**: later stages receive sealed keys in their work orders and verify the hash; the API verifies the hash
   again on every read and while streaming to the viewer (`artifact.integrity_violation` is audited on a mismatch).
5. **Pinned** by a scan version at finalization (`scan_version_artifact`, §15). A retry writes a new attempt; earlier
   artifacts are never changed.
6. **Purged or erased**: PII staging objects (unanonymised frames) are purged once the run no longer needs them; a
   FAILED run keeps them for `PII_STAGING_RETENTION` (default 24 h) so that it can be retried. Erasure requests delete a capture's, version's or venue's objects and rows (docs/privacy-erasure.md).
   Backups never copy `pii/` (docs/backup-recovery.md).

## 15. Scan versions
A `scan_version` is one reconstruction of one floor. Since V28 every run is a DRAFT version from its start. It becomes
FINALIZED exactly once (or `ALIGNMENT_REJECTED` for a re-scan that fails the alignment gates) and is then immutable.
Finalization requires an ACTIVE calibration of the run's own reconstruction frame and the version's own `KSPLAT` and
cloud (`SPLAT_CLEAN`, or `SPLAT_MERGED` for a re-scan); otherwise `NOT_CALIBRATED` or `VERSION_INCOMPLETE`. A version
pins exactly its own artifacts; a re-scan inherits only its parent's detections, never navigation. Version numbers are
unique per floor and never reused.

Publication: the last stage report of a run that succeeded with a calibrated reconstruction promotes its version in one
transaction (`PipelineService#tryPromote`): finalize, write detections as POIs, swap the floor's active routing graphs,
adopt the frame, set `floor.current_scan_version_id`. If anything refuses, the promotion rolls back to a savepoint, the
run's result is kept, and the version stays DRAFT; `POST …/scan-versions/finalize-current` retries it. The viewer lists
only FINALIZED versions and opens the floor's current one; POIs, search, routes and anchors can be scoped to any
finalized version and refuse a DRAFT one (`VERSION_NOT_FINALIZED`). Full rules: [docs/rescan.md](docs/rescan.md),
"VERSIONING".

Since V41, a version can also be a **room version** (`space_id`; a trigger keeps it from ever being the floor's
current version) or a **stitch version**. A stitch version records the room versions, clouds, labels and calibrations
it combines (`scan_version_stitch_source`, write-once), plus its registration, stitch and connectivity reports. It is
published like any version, and only when every stage has succeeded (docs/multi-room.md, "Version management").

## 16. Current vs planned behaviour

The "winning solution blueprint" that this project was built against is **not in the repository**, so its text cannot
be compared line by line. This table compares the product promises recorded in this repository (the target design that
earlier versions of this document described, and the promises quoted in docs/ML_EVALUATION.md) with what exists.
*2026-10-24:* the blueprint (still kept outside the repository) has now been compared section by section, and the
recommended decisions are recorded: [docs/BASELINE_AUDIT_2026-10-24.md](docs/BASELINE_AUDIT_2026-10-24.md) §3 and §6.

| Promise | Current behaviour | Planned / missing |
|---|---|---|
| Capture guided on the phone, with live coverage and an automatic scan plan | Implemented in the web app (HUD, reconnaissance-lap bounds estimation, planned path, WebXR tracking on ARCore). The `/capture` page uploads a video recorded elsewhere; there is no in-browser recording. | Field validation on a phone; in-browser recording; iOS capture (the iOS app does navigation only) |
| Multi-room reconstruction: rooms scanned separately, aligned through shared doorway and hallway features with loop closure | Implemented (docs/multi-room.md): room captures, ROOM_REGISTRATION (FPFH, verified placements, free-space gate, pose graph with loop closure), ROOM_STITCH, connectivity-checked navigation. Tested on a synthetic floor only; with real FPFH there it refuses rather than misplaces rooms (Benchmark 7) | Any run on real rooms (needs a GPU host); thresholds tuned on real captures |
| Pose estimation with GLOMAP, COLMAP as fallback | COLMAP feature extraction and mapper; GLOMAP is used only if a binary is present, and no image contains one | GLOMAP in the GPU image; any run on a real venue |
| Gaussian splat training on GPU, exported to `.ksplat` | Implemented (gsplat 1.5.3, pinned CUDA image, preflight, acceptance test); `.ksplat` export verified against the pinned viewer | **Never executed**: the GPU image has never been built and no CUDA host was available |
| Metric digital twin | Calibration from measured references, versioned frames, `NOT_CALIBRATED` everywhere metres matter | Automatic triangulation of marked points; any real calibration (no measured capture exists) |
| Custom-trained segmentation (floor/wall/furniture/clutter) and fine-tuned indoor detection and retrieval | Stock SegFormer (research-only licence), stock Grounding DINO, stock CLIP; a reviewed class mapping; dataset and training harness; B6 baseline | Any trained checkpoint: blocked on indoor annotations, a GPU, and a permissively licensed base model (docs/ML_EVALUATION.md §6) |
| Semantic search | pgvector cosine ranking with lexical fallback; manual POIs re-embedded | Relevance on a real venue |
| Navigation on the reconstructed venue, step-free routes | Recast/Detour baking and server-side routing on synthetic venues | Any real venue; in-browser Recast (WASM) was planned once and dropped: routing is server-side |
| AR navigation (Android WebXR, iOS ARKit) | Both clients implemented, unit-tested | Any run on a device |
| Incremental re-scan with versioned merge | Implemented; validated on synthetic clouds and B5 | A real re-scan |
| Live job status | SSE (`GET …/processing/stream`) with the polled snapshot `GET …/processing` for recovery; the capture HUD has its own stream | — |
| Organizations and users through the product | SQL and the Keycloak admin console | An organization/user administration API and UI |
| Automated deploys from green CI | Workflows, deploy gate, rollback, migration rehearsal, restore drill exist | CI is red on `main`; no deploy has succeeded; no real host |
