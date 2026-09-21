# CHAYA 02 — Architecture

Physical venue = **State 01**. Digital reconstruction = **State 02**.
Chaya 02 is a living digital twin: reconstructed, searchable, navigable, viewable in AR, and incrementally updatable. This document is the source of truth for system boundaries. Nothing here is implemented yet unless PROJECT_PLAN.md says so.

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
              │ authz, audit, jobs│◄──────►│ Redis       │
              └───┬───────────▲───┘        └─────────────┘
       presigned  │           │ job claim / status / results (HTTPS, worker credential)
       URLs       │           │
              ┌───▼───────────┴───┐
              │ MinIO (S3 API)    │◄──── Python workers (GPU/CPU)
              └───────────────────┘
```

Boundaries are hard: clients never talk to Postgres, Redis, or MinIO directly except through presigned URLs issued by the backend. Workers never touch the database; they use the backend's worker API and MinIO. Keycloak is the only issuer of identities.

## 2. Frontend (`apps/web`)
Next.js + TypeScript + Tailwind. Three.js with GaussianSplats3D renders `.ksplat` assets; CSS2DRenderer for object labels/annotations. Navigation queries run against a Recast navmesh (WASM) loaded in-browser, with the backend as the authority on which navmesh version is current. Auth via OIDC authorization code + PKCE against Keycloak; access tokens are held in memory, refresh handled by the OIDC client (see §7). The frontend is a separately deployable container and talks only to `/api/v1`.

## 3. Backend (`apps/api`)
Spring Boot (Java), single deployable monolith organised by module packages: `venue`, `capture`, `job`, `asset`, `semantic`, `navigation`, `ar`, `dashboard`, `audit`, `identity`. Spring Security as OAuth2 Resource Server validating Keycloak JWTs. Responsibilities: authorization (role + venue scope), metadata persistence, job lifecycle, presigned URL issuance, semantic search, path-data API, audit logging. It never runs reconstruction. Schema migrations via Flyway. Health via Spring Actuator with real dependency indicators (DB, Redis, MinIO).

## 4. Processing pipeline (`workers/`)
Python workers, one per stage, sharing a small common library (job client, S3 client, logging). Stages:

| # | Stage | Tooling |
|---|-------|---------|
| 1 | Media filtering (blur/exposure/duplicate rejection, frame extraction) | FFmpeg, OpenCV |
| 2 | Pose estimation | GLOMAP; COLMAP fallback when GLOMAP fails |
| 3 | Gaussian splat training + export to `.ksplat` | gsplat / Nerfstudio |
| 4 | Geometric cleanup & mesh | Open3D |
| 5 | Semantic segmentation | SegFormer or Mask2Former |
| 6 | Object detection + embeddings | Grounding DINO, CLIP |
| 7 | Navmesh generation | Recast (native build) |

Live capture-time bounds estimation, path planning and coverage scoring (steps 2–5 of the product workflow) run on the client/AR side against backend-provided contracts; the backend stores the resulting capture session and coverage record. Each stage declares its inputs and outputs as S3 keys and a versioned JSON manifest. Until a stage exists, the job for it is not created and the API reports `PIPELINE_UNAVAILABLE` for that capability. FAISS is not used unless a worker needs transient in-memory batch search; persistent search is pgvector.

## 5. Database (PostgreSQL + pgvector)
Core tables (all tenant-scoped rows carry `organization_id`, and venue-scoped rows carry `venue_id`):

- `organization`, `venue`, `venue_membership` (user id from Keycloak `sub`, role)
- `capture_session` (venue, operator, status, bounds, coverage summary)
- `media_asset` (S3 key, type, checksum, size, capture_session)
- `reconstruction` (venue, version, status, is_complete, supersedes)
- `reconstruction_region` (for partial rescans; region geometry, freshness timestamp)
- `processing_job` (see §10), `job_event` (append-only transitions)
- `scene_asset` (ksplat, mesh, navmesh; S3 key, checksum, reconstruction)
- `detected_object` (label, bbox/pose in venue frame, confidence, reconstruction, `embedding vector(N)`)
- `anchor` (fiducial id, pose in venue frame, physical size)
- `search_query_log`
- `audit_event` (append-only: actor, action, target, venue, timestamp, outcome)

Embedding dimension N is fixed by the CLIP model chosen (open decision D3). Every state-bearing table has explicit status enums enforced by CHECK constraints. Migrations are forward-only.

## 6. Object storage (MinIO)
S3-compatible; buckets `chaya-raw` (uploads), `chaya-derived` (frames, poses, splats, meshes, navmeshes). Keys are prefixed `org/{orgId}/venue/{venueId}/…` so tenant isolation is checkable by prefix. Clients upload/download via short-lived presigned URLs minted by the backend after an authorization check. Upload completion is confirmed by the backend (HEAD + checksum), not trusted from the client. Only versioned, immutable objects; a new reconstruction writes new keys.

## 7. Identity (Keycloak)
OAuth2/OIDC, one realm. Web: auth code + PKCE. Native/AR: auth code + PKCE via system browser. Access tokens are short-lived JWTs; refresh tokens are rotated. Realm roles: `platform_admin`; venue-scoped roles (`venue_admin`, `operator`, `viewer`) are stored in `venue_membership` in the backend DB, not in the token, so revocation is immediate and tokens stay small. Every request resolves `sub` → membership for the target venue; no membership means 404 (not 403) to avoid venue enumeration. Workers authenticate with a Keycloak client-credentials service account limited to the worker API. Security-relevant events are written to `audit_event`.

## 8. Navigation
The navmesh is generated by a worker with Recast from the cleaned geometry, using configurable agent radius/height/slope. Accessibility profiles (e.g. step-free) are separate navmesh builds, not runtime filters. The navmesh is a versioned `scene_asset`. Route queries run client-side with Detour/WASM for latency; a backend route endpoint is added only if a client cannot run WASM. No route is ever returned when no navmesh exists — the API returns `NAVMESH_UNAVAILABLE`.

## 9. AR clients
Android: WebXR in the Next.js app. iOS: native Swift/ARKit app (WebXR unsupported on iOS Safari). Both use the same backend path-data API (path polyline in venue frame + anchor list). Localization: multi-anchor fiducial relocalization gives a venue-frame transform; VIO interpolates between fixes; residual disagreement between anchors is reported and drift beyond a threshold prompts relocalization. Coordinates are always real: venue frame is defined by the reconstruction, and anchors are registered against it.

## 10. Asynchronous job flow
Job states: `QUEUED → CLAIMED → RUNNING → SUCCEEDED | FAILED | CANCELLED`, plus `RETRY_WAIT` (failed with attempts remaining). Only `SUCCEEDED` of the final stage sets `reconstruction.is_complete = true`; a reconstruction with any failed/absent stage stays `PARTIAL` or `FAILED` and is labelled so in API and UI.

1. Client requests a reconstruction → backend validates capture completeness, creates `reconstruction` + first `processing_job` (`QUEUED`), returns `202` with a job URL.
2. Worker polls `POST /api/v1/worker/jobs:claim` (lease with expiry, stored in DB; Redis is used for wake-up signalling/rate limiting only, DB is the source of truth).
3. Worker heartbeats extend the lease; expired leases return the job to `QUEUED` (attempt count incremented, capped).
4. Worker writes outputs to MinIO, then reports completion with output keys + checksums; backend verifies objects exist, records `scene_asset`s, enqueues the next stage.
5. Every transition inserts a `job_event` and, for user-visible ones, an `audit_event`.
6. Clients poll job status (SSE later if needed). No HTTP request blocks on reconstruction.

Incremental rescan: a capture scoped to a region creates a region-scoped reconstruction that is merged into the venue reconstruction as a new version; the previous version stays available until the new one is complete.

## 11. Infrastructure
Docker Compose for local dev: Postgres (pgvector image), MinIO, Redis, Keycloak. App containers are built per-service (`apps/web`, `apps/api`, `workers/*`) and pushed to GHCR by GitHub Actions; each deploys independently. GPU workers run outside Compose on a GPU host. No Kubernetes, Kafka, Celery, or RabbitMQ.

## 12. Data flow (summary)
Capture (AR client) → presigned upload → MinIO raw → job chain (filter → poses → splat → cleanup → segmentation → detection → navmesh) → derived assets + rows in Postgres → web/AR fetch assets via presigned URLs, search via pgvector, route via navmesh → dashboard reads job/coverage/freshness/search-log data.

## 13. API versioning
All HTTP under `/api/v1`. Breaking changes require `/api/v2`. Errors use RFC 9457 problem+json with a stable `code` (e.g. `PIPELINE_UNAVAILABLE`, `NAVMESH_UNAVAILABLE`, `VENUE_NOT_FOUND`) and an actionable `detail`. Worker manifests carry `schemaVersion`.
