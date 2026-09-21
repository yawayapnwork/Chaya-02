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
| `venue_id` | UUID, or list of UUIDs, of venues the user is restricted to. Absent = no venue access (except `admin`) |
| `aud`, `iss`, `exp` | Validated as above |

`org_id` and `venue_id` come from Keycloak **user attributes** (`org_id` single value, `venue_id` multi-valued) through the `chaya-tenant` client scope. Only administrators of Keycloak can set them; users cannot change their own.

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

## Tenant enforcement
Two independent layers:
1. **URL/method rules** (`SecurityConfig`, `@PreAuthorize`): who may call the endpoint at all.
2. **`TenantGuard.requireVenue`**, called first by every service method that takes a venue id: the venue must be in the actor's organization, not deleted, and permitted by `venue_id` (admins: any venue of their organization). Every query afterwards also filters by `venue_id` and `organization_id`.
3. **Database**: composite foreign keys make cross-organization references impossible (see ADR 0002).

A refused venue access is audited (`venue.access`, outcome `DENIED`, in the *caller's* organization with the attempted id in metadata) and returned as 404.

## API authorization matrix

| Endpoint | admin | venue-manager | operator | viewer | public viewer | service |
|---|:-:|:-:|:-:|:-:|:-:|:-:|
| `GET /api/v1/health`, `/version` | public | public | public | public | public | public |
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
| `GET /venues/{id}/floors` | ✔ | ✔ | ✔ | ✔ | ✔ | ✘ |
| `POST /venues/{id}/floors` | ✔ | ✔ | ✘ | ✘ | ✘ | ✘ |
| `POST/GET/DELETE /venues/{id}/public-links` | ✔ | ✔ | ✘ | ✘ | ✘ | ✘ |
| `POST /api/v1/public/viewer-token` | public (link secret is the credential) | | | | | |
| `GET /api/v1/audit-log` (own org) | ✔ | ✘ | ✘ | ✘ | ✘ | ✘ |
| `POST /api/v1/internal/jobs/claim`, `/{id}/heartbeat`, `/{id}/report`, `/{id}/complete`, `/{id}/fail` | ✘ | ✘ | ✘ | ✘ | ✘ | ✔ |
| `POST .../captures/{c}/processing[/retry\|/cancel]`, `GET .../processing` | ✔ | ✔ | ✔ | ✘ | ✘ | ✘ |
| `POST .../processing` with `privacyEnabled:false` | ✔ | ✘ | ✘ | ✘ | ✘ | ✘ |

"(own)" = restricted to venues in the `venue_id` claim. Everything not listed is denied.

## Public viewer links
Purpose: let anyone with a link view one venue without an account, with a credential that is narrow, short-lived, and revocable.

1. A `admin`/`venue-manager` creates a link: `POST /venues/{id}/public-links {label, ttl}`. `ttl` ≤ `chaya.security.public-link-max-ttl` (30 days). The response contains the **link secret** (`chl_…`, 256 bits of `SecureRandom`) once. Only its SHA-256 is stored.
2. The visitor's page calls `POST /api/v1/public/viewer-token {secret}`. On success the backend returns an opaque **access token** (`cvt_…`, 256 bits), valid for `min(10 minutes, link expiry)`; again only the hash is stored. Unknown, expired and revoked secrets all return the same 404.
3. Requests carry `X-Chaya-Viewer-Token`. The backend maps the token to a `PUBLIC_VIEWER` actor bound to exactly one venue and organization. A bad, expired or revoked token is a hard 401; sending it together with a bearer token is a 400.
4. Every token use joins back to its link, so revocation (`DELETE /venues/{id}/public-links/{linkId}`) is immediate.

What it can do: `GET` the venue and its POIs. What it cannot do: reach other venues (404), list venues, write anything, create links, read the audit log, or call worker endpoints — those endpoints do not list `PUBLIC_VIEWER`, and the tests assert each of them.

No JWT is signed by the backend: the viewer token is an opaque, server-side-checked credential, so there is no signing key to protect. Not yet built (noted for later): rate limiting on `viewer-token`, and access counters per link.

## Service-to-service authentication
Workers authenticate to Keycloak with the OAuth2 client-credentials grant as client `chaya-worker` (secret from `CHAYA_WORKER_CLIENT_SECRET`, never committed). Its service account has the `service` realm role and gets the `chaya-api` audience. Workers call `/api/v1/internal/**` with `Authorization: Bearer <token>`.

- `/api/v1/internal/**` requires `ROLE_SERVICE` (URL rule and `@PreAuthorize`); anonymous → 401, users (even admins) → 403.
- Service tokens have no `org_id` and no access to tenant endpoints; a token combining `service` with user roles is rejected.
- Contract: `POST /internal/jobs/claim {stage|stages, workerId}` (204 when nothing is queued), `POST /internal/jobs/{id}/heartbeat`, `POST /internal/jobs/{id}/report` (the stage record; see docs/pipeline.md). `complete`/`fail` remain only for legacy jobs outside a pipeline run. Claiming is atomic (`FOR UPDATE SKIP LOCKED`), transitions follow the job state machine, and each call is audited with actor type `SERVICE`.
- Artifact registration is part of the stage report and is verified server-side (key prefix, object exists with the reported size, PII rules).

## Audit
Written in the same transaction as the operation (so both commit or neither): `venue.create`, `venue.update`, `scan.create`, `job.enqueue`, `job.cancel`, `job.retry`, `job.claim`, `job.complete`, `job.fail`, `poi.create`, `poi.update`, `poi.delete`, `public_link.create`, `public_link.revoke`, `public_link.exchange`. Refused venue access (`venue.access`, `DENIED`) is written in its own transaction so it survives the rejection. Secrets and tokens are never written to the log. The table is append-only at the database level.

## Keycloak setup
`infra/keycloak/chaya-realm.json` is imported by Docker Compose (`start-dev --import-realm`). It defines the five realm roles; client scopes for `sub`, realm roles, the API audience and the tenant claims; the public PKCE client `chaya-web`; the bearer-only `chaya-api`; and the confidential `chaya-worker` service account. It contains no users with passwords and no secrets (`${CHAYA_WORKER_CLIENT_SECRET}` is substituted from the environment). The realm also declares `org_id` and `venue_id` in the Keycloak user profile, editable and visible by administrators only. This is required: without it Keycloak silently drops unmanaged attributes and the claims never reach the token (verified against Keycloak 26.0). After the first start, create users in the admin console and set their `org_id` / `venue_id` attributes and roles.
