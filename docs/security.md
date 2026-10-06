# Chaya 02 — Authentication and authorization

Authentication is delegated to Keycloak (OIDC). The backend is an OAuth2 Resource Server: it only ever
validates tokens, it never issues user or service JWTs and never sees passwords. All authorization
decisions are made server-side from the validated token; nothing the browser or a client says about
itself is trusted.

## Token validation
- Signature: RS256 against Keycloak's JWKS (`<issuer>/protocol/openid-connect/certs`, cached, selected by `kid`). Unsigned (`alg=none`) and wrongly signed tokens are rejected.
- Claims checked: `iss` equals `KEYCLOAK_ISSUER_URI`; `exp`/`nbf`; `aud` contains `KEYCLOAK_AUDIENCE` (default `chaya-api`, added by the `chaya-api-audience` client scope).
- A token that verifies but lacks `org_id` (and is not a service token) is rejected with 401.
- Responses: no/invalid token → 401; valid token, insufficient role → 403; resource missing **or not visible to the caller** → 404.

## Token claims

| Claim | Meaning |
|---|---|
| `sub` | Stable user id; recorded as actor in audit logs and as operator on captures |
| `realm_access.roles` | Keycloak realm roles (see below); unknown roles are ignored |
| `org_id` | UUID of the caller's organization. Required for user tokens |
| `venue_id` | UUID, or list of UUIDs, of venues the user's realm roles apply to. Absent = no venue access (except `admin`) |
| `venue_roles` | `"<venueId>:<role>"`, or a list of them: `venue-manager`, `operator` or `viewer` at that one venue. A malformed entry, or a grant of `admin`/`service`, makes the token invalid (401) |
| `aud`, `iss`, `exp` | Validated as above |

`org_id`, `venue_id` and `venue_roles` come from Keycloak **user attributes** (`org_id` single value, the other two multi-valued) through the `chaya-tenant` client scope. Only administrators of Keycloak can set them; users cannot change their own.

Design note: tenancy and role travel in the token (as requested) instead of a `venue_membership` table. The cost is that revocation takes effect when the access token expires (5 minutes in the shipped realm) rather than instantly.

## Role model

| Role | Scope | Intent |
|---|---|---|
| `admin` | Every venue of `org_id` | Organization administration, venue creation, audit log |
| `venue-manager` | Venues in `venue_id` | Edit venue, POIs, public links; run captures and jobs |
| `operator` | Venues in `venue_id` | Capture and control processing; cannot edit content |
| `viewer` | Venues in `venue_id` | Read-only |
| `service` | Not tenant-bound | Processing workers only; must not be combined with user roles |
| `PUBLIC_VIEWER` | One venue | Assigned by the backend to public-link sessions; never appears in a Keycloak token |

Authorities are `ROLE_<NAME>` with `-` mapped to `_` (`venue-manager` → `ROLE_VENUE_MANAGER`).

### Roles are per venue (review S-8)
A user's roles at one venue are (`Actor.rolesAt`):
1. `admin` (realm role): every role, at every venue of `org_id`;
2. otherwise, if `venue_roles` names the venue: exactly the roles granted there;
3. otherwise, if `venue_id` names the venue: the realm roles (the original model, kept for existing users);
4. otherwise none.

`VenueScopeFilter` runs after authentication and before authorization. For every request under
`/api/v1/venues/{venueId}/` by a member of that venue it replaces the actor with `Actor.atVenue(venueId)`: that venue's
roles only, and access to that venue only. `@PreAuthorize`, `TenantGuard` and the services' own role checks
(`OpsSection`, the privacy opt-out, …) therefore all see the venue's roles. A user granted
`["A:venue-manager", "B:viewer"]` edits A and only reads B. Non-members pass through unchanged and are refused as 404 by
`TenantGuard`. `POST /navigation/routes` names its venue in the body; its controller applies the same narrowing.
For a path that names no venue (`GET /venues`, `/audit-log`) the coarse check uses every role the user holds anywhere;
those endpoints then filter by venue (`GET /venues`) or require `admin`. New users should get `venue_roles`; a realm
role combined with `venue_id` still applies at every listed venue.

## Tenant enforcement
Two independent layers:
1. **URL/method rules** (`SecurityConfig`, `@PreAuthorize`): who may call the endpoint at all.
2. **`TenantGuard.requireVenue`**, called first by every service method that takes a venue id: the venue must be in the actor's organization, not deleted, and permitted by `venue_id` (admins: any venue of their organization). Every query afterwards also filters by `venue_id` and `organization_id`.
3. **Database**: composite foreign keys make cross-organization references impossible (see ADR 0002), and every object
   key column is CHECK-constrained to its own row's tenant prefix (V29). `TenantSchemaInvariantTest` enforces both on the
   real migrated schema (see "Tenant inventory").

Roles are narrowed to the venue of the request before layer 1 runs (see "Roles are per venue").

A refused venue access is audited (`venue.access`, outcome `DENIED`, in the *caller's* organization with the attempted id in metadata) and returned as 404.

## API authorization matrix

| Endpoint | admin | venue-manager | operator | viewer | public viewer | service |
|---|:-:|:-:|:-:|:-:|:-:|:-:|
| `GET /api/v1/health`, `/version` | public | public | public | public | public | public |
| `GET /actuator/prometheus` | HTTP Basic `prometheus` + `METRICS_SCRAPE_PASSWORD` only; closed when unset | | | | | |
| `GET /api/v1/venues` | ✔ | ✔ (own) | ✔ (own) | ✔ (own) | ✘ | ✘ |
| `POST /api/v1/venues` | ✔ | ✘ | ✘ | ✘ | ✘ | ✘ |
| `GET /venues/{id}` | ✔ | ✔ | ✔ | ✔ | ✔ (its venue) | ✘ |
| `PATCH /venues/{id}` | ✔ | ✔ | ✘ | ✘ | ✘ | ✘ |
| `POST /venues/{id}/scans` | ✔ | ✔ | ✔ | ✘ | ✘ | ✘ |
| `POST /venues/{id}/scans/{s}/jobs`, `/jobs/{j}/cancel`, `/retry` | ✔ | ✔ | ✔ | ✘ | ✘ | ✘ |
| `GET /venues/{id}/pois[/{p}]` | ✔ | ✔ | ✔ | ✔ | ✔ | ✘ |
| `POST/PUT/DELETE /venues/{id}/pois` | ✔ | ✔ | ✘ | ✘ | ✘ | ✘ |
| `GET/POST /venues/{id}/captures[/...]` (create, upload, complete, processing) | ✔ | ✔ | ✔ | ✘ | ✘ | ✘ |
| `POST .../captures/{c}/route-plan` (stateless planner; capture must be CREATED or UPLOADING) | ✔ | ✔ | ✔ | ✘ | ✘ | ✘ |
| `POST .../floors/{f}/rescan`, `POST .../scan-versions/finalize-current` | ✔ | ✔ | ✔ | ✘ | ✘ | ✘ |
| `GET .../floors/{f}/scan-versions` | ✔ | ✔ | ✔ | ✔ | ✘ | ✘ |
| `GET /venues/{id}/floors` | ✔ | ✔ | ✔ | ✔ | ✔ | ✘ |
| `POST /venues/{id}/floors` | ✔ | ✔ | ✘ | ✘ | ✘ | ✘ |
| `GET .../floors/{f}/anchors[/{a}]`, `POST .../anchors/relocalize` | ✔ | ✔ | ✔ | ✔ | ✔ | ✘ |
| `POST/PUT/DELETE .../floors/{f}/anchors`, `POST .../anchors/{a}/calibrate` | ✔ | ✔ | ✔ | ✘ | ✘ | ✘ |
| `POST/GET/DELETE /venues/{id}/public-links` | ✔ | ✔ | ✘ | ✘ | ✘ | ✘ |
| `DELETE /venues/{id}/captures/{c}`, `DELETE /venues/{id}/scan-versions/{sv}` (erasure, docs/privacy-erasure.md) | ✔ | ✔ | ✘ | ✘ | ✘ | ✘ |
| `DELETE /venues/{id}` (venue erasure) | ✔ | ✘ | ✘ | ✘ | ✘ | ✘ |
| `GET /api/v1/erasures/{id}` (own org; requester or admin), `GET /api/v1/erasures` | ✔ | ✔ (own) | ✘ | ✘ | ✘ | ✘ |
| `POST /api/v1/public/viewer-token` | public (link secret is the credential) | | | | | |
| `GET /api/v1/audit-log` (own org) | ✔ | ✘ | ✘ | ✘ | ✘ | ✘ |
| `POST /api/v1/internal/jobs/claim`, `/{id}/heartbeat`, `/{id}/report`, `/{id}/complete`, `/{id}/fail` | ✘ | ✘ | ✘ | ✘ | ✘ | ✔ |
| `POST .../captures/{c}/processing[/retry\|/cancel]`, `GET .../processing` | ✔ | ✔ | ✔ | ✘ | ✘ | ✘ |
| `POST .../processing` with `privacyEnabled:false` | ✔ | ✘ | ✘ | ✘ | ✘ | ✘ |
| `GET /venues/{id}/ops/access`, `/ops/overview` (venue overview, current version, freshness) | ✔ | ✔ | ✔ | ✔ | ✘ | ✘ |
| `GET /venues/{id}/ops/coverage`, `/ops/jobs`, `/ops/failures`, `/ops/storage`, `/ops/rescans` | ✔ | ✔ | ✔ | ✘ | ✘ | ✘ |
| `GET /venues/{id}/ops/search-analytics`, `/ops/audit` | ✔ | ✔ | ✘ | ✘ | ✘ | ✘ |

"(own)" = restricted to venues in the `venue_id` claim. Everything not listed is denied.

## Operations dashboard
The dashboard (`/ops` in the web app, `GET /api/v1/venues/{id}/ops/*`) is read-only; its retry/cancel buttons call the existing processing endpoints, which enforce their own rules. Section access is one table, `dev.chaya.api.ops.OpsSection`, used both to enforce (403 `OPS_SECTION_FORBIDDEN`, checked before the venue) and to answer `GET .../ops/access`, so the UI never renders a section the server would refuse. Venue scope is `TenantGuard.requireVenue` as everywhere else.

- Viewers see venue state only (overview, current version, freshness) -- no capture, processing or audit data.
- Operators additionally see capture/processing data (coverage, jobs, failures, storage, re-scans), matching the capture and job endpoints they may call.
- Venue managers additionally see search analytics and **their own venue's** audit trail. This is narrower than `GET /audit-log` (organization-wide, admin only), which is unchanged. Audit metadata is never returned.
- Admins additionally see refused `venue.access` attempts that targeted this venue (those rows have no `venue_id`; they are matched on `metadata.attemptedResourceId`).

## Rate limits
`RateLimitFilter` runs after authentication, using fixed one-minute windows (`chaya.rate-limit.*`). Each window
allows 10 `POST /public/viewer-token` per client address, 120 other anonymous requests per address, 1200 requests per
authenticated user or viewer session, and 60 searches per caller. Worker service accounts are exempt. A spent budget
returns `429` with `Retry-After` and code `RATE_LIMITED`. The counters are in-process, which is correct for one API
instance. Behind a reverse proxy, configure `server.forward-headers-strategy` so the key is the client, not the proxy.

## Dependency outages
If a dependency is down, the API answers `503` with a code: `DATABASE_UNAVAILABLE`, `STORAGE_UNAVAILABLE`, or
`AUTHENTICATION_UNAVAILABLE` (Keycloak's signing keys cannot be fetched, so no token can be verified). It never
answers `401`, which would wrongly tell a user their valid token is bad. `GET /api/v1/health` names the failing
component. See docs/security-hardening.md, "Failure testing".

## Public viewer links
Purpose: let anyone with a link view one venue without an account, with a credential that is narrow, short-lived, and revocable.

1. A `admin`/`venue-manager` creates a link: `POST /venues/{id}/public-links {label, ttl}`. `ttl` ≤ `chaya.security.public-link-max-ttl` (30 days). The response contains the **link secret** (`chl_…`, 256 bits of `SecureRandom`) once. Only its SHA-256 is stored.
2. The visitor's page calls `POST /api/v1/public/viewer-token {secret}`. On success the backend returns an opaque **access token** (`cvt_…`, 256 bits), valid for `min(10 minutes, link expiry)`; again only the hash is stored. Unknown, expired and revoked secrets all return the same 404.
3. Requests carry `X-Chaya-Viewer-Token`. The backend maps the token to a `PUBLIC_VIEWER` actor bound to exactly one venue and organization. A bad, expired or revoked token is a hard 401; sending it together with a bearer token is a 400.
4. Every token use joins back to its link, so revocation (`DELETE /venues/{id}/public-links/{linkId}`) is immediate.

What it can do: `GET` the venue, its floors, anchors and routes, and what each floor **explicitly publishes**: FINALIZED
scan versions and their pinned, verified artifacts, and the current version's POIs (list, search and by id). It is
never shown a run trained without privacy preprocessing, nor anything derived from one (its reconstruction,
artifacts and detected POIs; `PublicExposure`), nor a POI of a draft or superseded version, even by id. What it cannot do: reach other venues (404), list venues, write anything, create links, read the audit log, or call worker endpoints — those endpoints do not list `PUBLIC_VIEWER`, and the tests assert each of them.

No JWT is signed by the backend: the viewer token is an opaque, server-side-checked credential, so there is no signing key to protect. Link exchange is rate limited per client address (see Rate limits). Not yet built: access counters per link. The viewer page sends `Referrer-Policy: no-referrer`, so the `?link=` secret never leaves the origin in a Referer header. It does remain in browser history.

## Service-to-service authentication
Workers authenticate to Keycloak with the OAuth2 client-credentials grant as client `chaya-worker` (secret from `CHAYA_WORKER_CLIENT_SECRET`, never committed). Its service account has the `service` realm role and gets the `chaya-api` audience. Workers call `/api/v1/internal/**` with `Authorization: Bearer <token>`.

- `/api/v1/internal/**` requires `ROLE_SERVICE` (URL rule and `@PreAuthorize`); anonymous → 401, users (even admins) → 403.
- Service tokens have no `org_id` and no access to tenant endpoints; a token combining `service` with user roles is rejected.
- Contract: `POST /internal/jobs/claim {stage|stages, workerId}` (204 when nothing is queued), `POST /internal/jobs/{id}/heartbeat`, `POST /internal/jobs/{id}/report` (the stage record; see docs/pipeline.md). `complete`/`fail` remain only for legacy jobs outside a pipeline run. Claiming is atomic (`FOR UPDATE SKIP LOCKED`), transitions follow the job state machine, and each call is audited with actor type `SERVICE`.
- **Leases.** The service role says a caller is *a* worker, not which job it may act on. Each claim's work order carries
  a fresh `leaseToken` (256 random bits; only its SHA-256 is stored, `processing_job.lease_token_sha256`). Heartbeat,
  report, complete and fail act only with `X-Chaya-Lease-Token` set to it: otherwise the report/complete/fail is
  `409 LEASE_MISMATCH` and the heartbeat answers `keepGoing: false`. A worker therefore cannot report on, extend or
  finish a job of any tenant that it did not claim, nor its own job after the lease expired and the job was re-queued
  (a retry clears the lease; the next claim issues a new one). The Python worker keeps the token per job
  (`chaya_worker.api_client`).
- Every input key in a work order is checked to belong to the job's own tenant before the order is handed out.
- Artifact registration is part of the stage report and is verified server-side: key under the attempt's prefix, PII
  rules, and the object is sealed (see "Object storage").

## Object storage

### Key layout
Every object key is under its tenant (`dev.chaya.api.storage.TenantKeys`):

| Prefix | Written by | What |
|---|---|---|
| `org/{org}/venue/{venue}/capture/{capture}/raw/{media}` | API | Raw capture media (raw bucket) |
| `org/{org}/venue/{venue}/scan/{scan}/run/{run}/{STAGE}/attempt-{n}/…` | Worker | A stage attempt's output, before registration |
| `sealed/org/{org}/venue/{venue}/…/{nonce}/{file}` | API only | A registered artifact: the sealed copy (derived bucket) |

The API checks a key against the requested venue before streaming it (`TenantKeys.require`), and the database refuses a
`processing_artifact` or `capture_media` row whose key is outside the row's own organization and venue (V29 CHECK
constraints). The bytes are always streamed through the API, never via presigned URLs.

### Sealed, verified artifacts (review S-2)
When a worker reports an object, `ArtifactSealer`:
1. copies it server-side to `sealed/…` with a per-registration nonce directory (the file name and any `pii/` segment are kept);
2. hashes **the copy** and refuses the report (`409 ARTIFACT_CHECKSUM_MISMATCH` / `ARTIFACT_SIZE_MISMATCH` /
   `ARTIFACT_MISSING`) unless its size and SHA-256 match the report. The copy is then removed and nothing is registered;
3. records the sealed key (`processing_artifact.sealed`, `worker_object_key`). After the registration commits it
   deletes the worker's original. A sealed copy whose registration fails is removed.

Worker storage accounts have an explicit Deny on writes and deletes under `sealed/` (`infra/docker/minio/init.sh`), so
a registered artifact cannot be replaced by any worker credential. Every read verifies the bytes again against the
registered SHA-256 (`ObjectStore.openVerified`): the next stage's inputs (the worker checks the hash too), the API's own
parsing of detections, navigation graphs and gravity estimates, and the viewer. A mismatch while streaming ends the
response before its Content-Length, so the client sees a failed download, and is audited as
`artifact.integrity_violation` (outcome `FAILURE`). The PII purge deletes both the sealed key and the worker key.

### Storage accounts
| Account | Raw bucket | Derived bucket |
|---|---|---|
| `chaya-api` | read, write, delete | read, write, delete (the only writer of `sealed/`) |
| `chaya-worker` | read | read; write except `sealed/`; no delete |
| `chaya-recon` (post-privacy workers) | none | read except any `*/pii/*` key; write except `sealed/` and `pii/`; no delete |
| `chaya-backup` | read | read |
| anonymous | none | none |

`StoragePolicyTest` runs the real `init.sh` against MinIO and checks each row of this table with that account's own
credentials, below the API.

**Residual (not addressed).** The worker accounts are shared by all tenants. A compromised worker credential can still
*read* every tenant's raw media and derived objects, and write unsealed objects under any tenant's prefix. It cannot
change what is registered: sealed copies are out of its reach, and a forged object fails the hash check at registration.
Per-tenant or per-run credentials (STS scoped to the run prefix) would close this. Artifacts registered before V29 are
unsealed, but they are still verified on every read. A server-side copy is a single `CopyObject`, so objects over 5 GB
cannot be sealed.

## Tenant inventory
Every table that carries tenant or version scope (from the migrated schema; `TenantSchemaInvariantTest` writes this
table to `services/api/target/tenant-inventory.md` and fails if a table breaks the rules below):

| Table | organization_id | venue_id | scan_version_id |
|---|---|---|---|
| `ar_anchor` | NOT NULL | NOT NULL | nullable |
| `ar_anchor_pose` | NOT NULL | NOT NULL | NOT NULL |
| `audit_log` | NOT NULL | nullable | — |
| `capture_hud_pose_sample` | NOT NULL | NOT NULL | — |
| `capture_hud_quality_sample` | NOT NULL | NOT NULL | — |
| `capture_hud_scene` | NOT NULL | NOT NULL | — |
| `capture_media` | NOT NULL | NOT NULL | — |
| `capture_session` | NOT NULL | NOT NULL | — |
| `coordinate_frame` | NOT NULL | NOT NULL | — |
| `floor` | NOT NULL | NOT NULL | — |
| `floor_connection` | NOT NULL | NOT NULL | — |
| `navigation_edge` | NOT NULL | NOT NULL | — |
| `navigation_graph` | NOT NULL | NOT NULL | nullable |
| `navigation_node` | NOT NULL | NOT NULL | — |
| `pipeline_run` | NOT NULL | NOT NULL | nullable |
| `pipeline_stage_run` | NOT NULL | NOT NULL | — |
| `poi` | NOT NULL | NOT NULL | — |
| `poi_version` | NOT NULL | NOT NULL | nullable |
| `processing_artifact` | NOT NULL | NOT NULL | nullable |
| `processing_job` | NOT NULL | NOT NULL | nullable |
| `public_viewer_link` | NOT NULL | NOT NULL | — |
| `scan` | NOT NULL | NOT NULL | — |
| `scan_version` | NOT NULL | NOT NULL | — |
| `scan_version_artifact` | — | — | NOT NULL |
| `search_query` | NOT NULL | NOT NULL | — |
| `space` | NOT NULL | NOT NULL | — |
| `venue` | NOT NULL | — | — |

- Every table with `venue_id` has `organization_id` and a foreign key over `(venue_id, organization_id)`, except
  `audit_log`: a refused cross-tenant attempt is recorded in the caller's organization with `venue_id` NULL.
- Every `scan_version_id` column references `scan_version`.
- `scan_version_artifact` has no tenant columns of its own. Its guard trigger allows a pin only of the version's own
  run's artifact, or of one its parent version pinned, and the parent is same-venue by composite key.
- Tables without tenant columns: `organization` (the tenant itself), and `capture_media_part`, `pii_staging_purge` and
  `public_viewer_token`, which reference a `capture_media`, `processing_artifact` or `public_viewer_link` row that has them
  and are only ever reached through it. Likewise `pii_staging_sweep` (a `pipeline_run`), and `erasure_item` and
`erasure_object` (an `erasure_request`, which has both tenant columns).

## Audit
Written in the same transaction as the operation (so both commit or neither): `venue.create`, `venue.update`, `scan.create`, `job.enqueue`, `job.cancel`, `job.retry`, `job.claim`, `job.complete`, `job.fail`, `poi.create`, `poi.update`, `poi.delete`, `public_link.create`, `public_link.revoke`, `public_link.exchange`, `erasure.capture`, `erasure.scan_version`, `erasure.venue` (counts and ids only, docs/privacy-erasure.md; a refusal is `DENIED` in its own transaction). Refused venue access (`venue.access`, `DENIED`) is written in its own transaction so it survives the rejection. Secrets and tokens are never written to the log. The table is append-only at the database level.

## Keycloak setup
`infra/keycloak/chaya-realm.json` is imported by Docker Compose (`start-dev --import-realm`). It defines the five realm roles; client scopes for `sub`, realm roles, the API audience and the tenant claims; the public PKCE client `chaya-web`; the bearer-only `chaya-api`; and the confidential `chaya-worker` service account. It contains no users with passwords and no secrets (`${CHAYA_WORKER_CLIENT_SECRET}` is substituted from the environment). The realm also declares `org_id` and `venue_id` in the Keycloak user profile, editable and visible by administrators only. This is required: without it Keycloak silently drops unmanaged attributes and the claims never reach the token (verified against Keycloak 26.0). After the first start, create users in the admin console and set their `org_id` / `venue_id` attributes and roles.
