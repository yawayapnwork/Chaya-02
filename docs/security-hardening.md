# Security and reliability hardening pass

Date: 2026-09-23. Scope: `services/api`, `services/reconstruction`, `services/vision`, `apps/web`, `infra/`.
Method: code and configuration review, dependency scanners, and new failure-mode tests. This is not a penetration
test.

Verdicts: **OK** means no change was needed. **Fixed** means changed in this pass. **Residual** means a known risk
that is still open, with the reason. **Unverified** means it was changed but not proven by a test on the audit
machine.

## Verification status of this pass

- **Web** (`apps/web`): lint, typecheck, the 58 unit tests and the production build pass.
- **Worker** (`services/reconstruction`): 101 passed, 12 skipped (GPU and integration tests). Includes the new
  model-loading tests.
- **API** (`services/api`): **not compiled or run.** The audit machine had no JDK 21 or Maven, and Docker was not
  running. The new and changed Java code and tests are listed below. Run `mvn verify` with Docker up before relying on
  them.
- **Compose files**: `docker compose config` passes. The containers were **not started**. Floating image tags were not
  pulled and are not verified.

## Findings by area

| # | Area | Verdict | Finding and action |
|---|---|---|---|
| 1 | Authentication | OK, and Fixed | Keycloak OIDC, RS256 only, issuer, expiry and audience are validated, `alg=none` is rejected (existing tests). **Fixed:** when Keycloak was unreachable, a valid token produced a generic 500. It now produces a 503 `AUTHENTICATION_UNAVAILABLE` (`DependencyFailureFilter`, `IdentityProviderUnavailableTest`). |
| 2 | Authorization | OK | `@PreAuthorize` on every controller plus `TenantGuard` in every venue-scoped service. The matrix is covered by `RoleAuthorizationTest` and `OpsDashboardApiTest`. No change. |
| 3 | Multi-tenant isolation | OK | Composite foreign keys (ADR 0002). Every query filters by organization and venue. A refused access returns 404 and is audited. No change. |
| 4 | File uploads | OK, and Fixed | See "Upload security" below. **Fixed:** `max-video-bytes` (4 GiB) was larger than clamd's maximum stream (4000 MiB). Videos in between could never be scanned, so they could never be accepted. The limit is now 4000 MiB. clamd replies are now read with a size bound. |
| 5 | MinIO | Fixed | The API and the worker both used the **MinIO root credentials**. Now `minio-init` creates private buckets and three scoped accounts. `chaya-api` has read, write and delete. `chaya-worker` has read-only access to raw and read/write to derived, with no delete. `chaya-backup` is read-only. Root is used only by `minio-init`. |
| 6 | PostgreSQL | Fixed | The API connected as the image's **superuser**. That meant SQL injection could escalate to superuser powers: `COPY … PROGRAM`, file access, and `session_replication_role`, which switches off the append-only and immutability triggers. Now the API connects as `POSTGRES_APP_USER`, which owns the schema but is `NOSUPERUSER` (`infra/docker/postgres/initdb`). Queries were reviewed: all use bound parameters, and the few concatenations are constants. **Residual:** the owning role can still `ALTER TABLE … DISABLE TRIGGER` on its own tables. Splitting migration (owner) from runtime (DML-only role) would close that, and needs a Flyway user configuration change. |
| 7 | Redis | Residual (documented) | Not used by any code. Kept password-protected and now bound to 127.0.0.1. ARCHITECTURE.md is corrected: rate limiting is in-process today. |
| 8 | Keycloak | Fixed, and Residual | **Fixed in the realm:** the redirect URI was narrowed from `http://localhost:3000/*` to the exact callback. The post-logout URI is now set. Brute-force protection was tightened (10 failures, then an increasing wait up to 15 min). A password policy was added (length 12, not the username or email, history 3). Login and admin events are enabled (30-day retention, no admin representation details). **Residual:** compose runs `start-dev` with embedded storage over HTTP. Production needs `start --optimized`, HTTPS, `KC_HOSTNAME_STRICT`, an external database, and `sslRequired: all`. |
| 9 | API rate limits | Fixed | There were none, including on the anonymous public-link exchange and on search, which runs a model inference per query. Added `RateLimiter` and `RateLimitFilter`. Per minute: 10 link exchanges per IP, 120 other anonymous requests per IP, 1200 requests per authenticated user, 60 searches per user. Worker service accounts are exempt. A spent budget returns 429 `RATE_LIMITED` with `Retry-After`. **Residual:** the limiter is in-process, which is correct for the single instance this project deploys. It needs a shared store if the API is scaled out. Behind a proxy, set `server.forward-headers-strategy` so the key is the real client IP. |
| 10 | CORS | OK, and Fixed | Explicit origins, and credentials are bearer headers. **Fixed:** startup now refuses a wildcard origin. |
| 11 | CSRF | OK | Disabled on purpose. No cookie or session authenticates anything, and credentials travel only in headers that a cross-site page cannot make the browser attach. The reasoning is now written in `SecurityConfig`. Re-enable CSRF if cookie authentication is ever added. |
| 12 | JWT validation | OK | See 1. The decoder accepts only RS256 keys from the JWKS, with issuer, expiry (60 s skew) and audience checks. A token without `org_id`, or with a service role mixed with user roles, is rejected. |
| 13 | Secrets | OK, and Fixed | `.env` is git-ignored and `.env.example` has no values. A pattern scan of the full git history (private keys, AWS key ids, `*PASSWORD=`/`*SECRET=` assignments) found only placeholders (`change-me`) and the throwaway credentials of an ephemeral test MinIO in a README. No key or credential files were ever committed. **Fixed:** root MinIO and Postgres credentials are separated from the service credentials (see 5 and 6). The metrics endpoint gets its own scrape credential. Backup credentials go through a mode-600 env file, never the command line. |
| 14 | Logs | OK, and Fixed | Log lines contain UUIDs, object keys (server-generated, UUID-only) and Keycloak subjects. No tokens, secrets or media were found. **Fixed:** error responses explicitly never include exception messages or stack traces (`server.error.*`). Dependency failures are logged server-side with the root cause, and returned to the client as a fixed text. Loki retention is 14 days. |
| 15 | PII | Fixed, and Residual | See "Privacy" below. **Residual:** raw captures (unblurred video) are kept in the raw bucket indefinitely, and backups include them. There is no retention job. `search_query` stores the query text with the actor id, also indefinitely. Both need a retention decision. |
| 16 | Model files | Fixed | Hugging Face weights were loaded from whatever `main` pointed to, and pickle-format weights (which execute code when loaded) were accepted. Now only safetensors weights load, unless `MODEL_ALLOW_PICKLE_WEIGHTS=true` is set deliberately. Revisions can be pinned (`GROUNDING_DINO_REVISION`, `SEMANTIC_SEGMENTATION_REVISION`), are recorded in the stage command record, and unpinned loads are logged. A failed load is a structured `DEPENDENCY_UNAVAILABLE` error saying how to fix it. `trust_remote_code` is not used anywhere. **Residual:** no commit SHAs are pinned yet (they could not be verified offline). Whether the default repositories ship safetensors is **unverified**: if one does not, that stage fails with a clear message. |
| 17 | SSRF and path traversal | OK | No server-side code fetches a user-supplied URL. The embedding service, Keycloak and MinIO URLs all come from configuration. Object keys are server-generated, and worker-reported keys are checked for prefix, `..` and `//`. The worker's tar extraction refuses anything other than flat regular files. Local storage checks containment. Downloads use the key's basename, prefixed with the artifact id. |
| 18 | Dependency vulnerabilities | Fixed, and Residual | **npm** (`npm audit`): 0 findings, full tree. **Worker** (pip-audit, installed base environment): 0. **Vision** (base): 0. **Maven:** not scanned locally (no Maven). **Fixed by review:** `tika-core` 3.0.0 is in the range of the critical XXE advisory CVE-2025-66516 (1.13–3.2.1) and was bumped to 3.2.2. Only magic-byte detection is used, so real exposure was low. **Unverified:** that 3.2.2 resolves and compiles. **CI:** `.github/workflows/security.yml` runs npm audit, pip-audit (both services) and OWASP Dependency-Check (CVSS ≥ 7 fails) on every PR and weekly. **Residual:** the Python dependencies are version ranges without a lock file, and the GPU extra was not scanned. |
| 19 | Container permissions | Fixed, and Residual | The worker and vision images already run as non-root users. **Fixed:** every published port in compose is now bound to 127.0.0.1 (previously all interfaces). `no-new-privileges` is set on all services, and the worker drops all capabilities. **Residual:** `minio:latest`, `mc:latest` and the unscanned images float. Pin them by digest in production. The Alloy log shipper mounts the Docker socket read-only, which is root-equivalent (see docs/monitoring.md). The vision service has no authentication, so it must stay on an internal network (it is not in compose). |
| 20 | Backup and recovery | Fixed | There was none. Added `scripts/backup/{backup,verify,restore}.sh` and docs/backup-recovery.md. |

Also fixed:

- **Web headers:** `Referrer-Policy: no-referrer`, so public viewer link secrets in `?link=` never reach another
  origin. Also `X-Content-Type-Options`, `X-Frame-Options: DENY`, a `Permissions-Policy` that grants only what the
  app uses, and an enforced CSP for framing, plugins, `<base>` and form targets. The full resource CSP is
  **report-only** until it has been verified in a browser against the splat viewer (workers, WASM) and the camera
  capture.
- **Health endpoint:** `/health` is public, and every hit fanned out to the database, S3, Keycloak and clamd. Results
  are now cached for 5 s and every check is time-bounded.

## Upload security (verified in code; existing tests in `UploadValidationTest`, `CaptureIngestionTest`)

| Check | Where | Behaviour |
|---|---|---|
| Claimed MIME | `MediaService.init` | Allowlist per kind. Anything else returns 415 before any byte is accepted. |
| Actual file type | `MediaValidationService` | Apache Tika magic-byte detection on the stored bytes. It must be in the allowlist and in the same format family as the claim, otherwise the file is REJECTED and deleted. |
| File size | `init` and `uploadPart` and validation | Per-kind maximum (video 4000 MiB, image 50 MiB, metadata 1 MiB). Each part must be exactly its expected length. The stored size must equal the declared size. |
| Filename | `FilenameSanitizer` | Directory parts removed, NFKD-normalised, reduced to `[A-Za-z0-9._ -]`, no `..`, no leading dots, at most 100 characters. Stored as metadata only, never used in a path. |
| Path | `MediaService.init` | The object key is `org/{uuid}/venue/{uuid}/capture/{uuid}/raw/{uuid}`, containing no client text. |
| Checksum | part and file | Optional per-part SHA-256 (422 on mismatch), and a mandatory whole-file SHA-256 recomputed from storage. |
| Malware scan | `ClamAvScanner` | clamd INSTREAM runs **before** any parsing. INFECTED means rejected and deleted. If the scanner is unavailable or disabled, the file is QUARANTINED and never accepted. The scanner's state now appears in `/health`. |
| Malformed input | as above | Bad JSON metadata, a wrong part size, a checksum mismatch, a type mismatch, or too many files each produce a specific error code (existing tests). |

## Privacy: faces, screens and documents before reconstruction

The worker's `PRIVACY_PREPROCESS` stage detects faces (OpenCV Haar cascades: frontal, alternate frontal, profile in
both orientations) and screens/documents (a quadrilateral heuristic). It pixelates and blurs them, re-runs face
detection on the output, and escalates to a solid fill for up to 5 rounds. It **fails closed**: if a detector is
missing, a frame is unreadable, or a face is still detectable, the stage fails and nothing is published. On the
control plane, stages after privacy never receive PII-flagged inputs (`PipelineService.inputs`), may not publish PII
(`PII_AFTER_PRIVACY`), and PII staging objects are deleted once privacy succeeds or the run is cancelled.

**Gap found and fixed:** those guarantees depended on the **worker's own `containsPii` flag** for output produced
*before* privacy. A buggy or compromised worker could label an unblurred frame archive as non-PII. It would then have
been handed to reconstruction and never purged. The control plane now enforces the rule itself. In a privacy-enabled
run, a stage before `PRIVACY_PREPROCESS` may publish unflagged output only if it is a report or log
(`application/json`, `text/plain`). Anything else is refused with 409 `PII_FLAG_REQUIRED`
(`imageOutputBeforePrivacyMustBeFlaggedAsPiiWhateverTheWorkerSays`). The real worker already complies. The test
fixture was labelling every artifact `application/octet-stream`; it now uses realistic content types.

**Limits, stated plainly:**

- Detection is classical CV, not a guarantee. Haar cascades miss faces that are small, turned away, occluded or badly
  lit. The screen/document heuristic misses tilted or dim screens and does not detect text on arbitrary surfaces. The
  privacy report records which detector ran. A learned detector can replace it behind `RegionDetector`.
- "Where required" applies: an administrator can run a pipeline with `privacyEnabled:false`. That choice is audited
  (`pipeline.start` metadata).
- Raw media in the raw bucket is never blurred. It is the original capture, and the pipeline reads it only in the two
  stages before privacy. See finding 15 for retention.

## Failure testing

| Scenario | Behaviour | Test |
|---|---|---|
| Database unavailable | Controller: 503 `DATABASE_UNAVAILABLE` (new handler). Inside a security filter (for example public-viewer token lookup): 503 via `DependencyFailureFilter`. Hikari now gives up after 5 s instead of 30 s. `/health`: `database DOWN`, overall DOWN (503). Pipeline gauges are cleared and `chaya_metrics_refresh_ok=0`. | `DependencyFailureFilterTest`, `ApiExceptionHandlerTest`, `HealthServiceTest` |
| MinIO unavailable | Operations raise `StorageException`, which becomes 503 `STORAGE_UNAVAILABLE`. Uploads mid-validation are QUARANTINED, not accepted. `/health`: `storage DOWN` within 3 s. | `ObjectStoreUnavailableTest` (real S3 client against a closed port), `ApiExceptionHandlerTest`, `HealthServiceTest` |
| Keycloak unavailable | A valid token cannot be verified. Previously a 500; now 503 `AUTHENTICATION_UNAVAILABLE` with `Retry-After`. `/health`: `identityProvider DOWN`. | `IdentityProviderUnavailableTest` (real Nimbus decoder against a closed port) |
| Reconstruction worker unavailable | Previously invisible: jobs sat QUEUED and health said UP. Now `/health` reports `processing: STALLED` (a claimable job older than 15 min with nothing running) and overall DEGRADED. There is also the `ChayaQueueStalled` alert and the `chaya_jobs_queued_oldest_age_seconds` gauge. A worker that dies mid-job is already failed as `WORKER_LOST` by the lease enforcer (existing). | `WorkerUnavailableHealthTest` (integration), `HealthServiceTest` |
| Malformed upload | See the upload table. | existing `UploadValidationTest` and `CaptureIngestionTest` |
| Expired token | 401. | existing `AuthenticationTest.expiredTokenIs401`, `PublicViewerLinkTest.expiredTokenAndExpiredLinkAreRejected` |
| Revoked public link | Its viewer tokens stop working immediately (401). | existing `PublicViewerLinkTest.revokingALinkKillsItsTokensImmediately` |

`/health` semantics: **DOWN** (HTTP 503) means the database, object storage or identity provider is unreachable.
**DEGRADED** (HTTP 200) means the malware scanner is down or disabled (uploads quarantined), or the queue is stalled.
The body names the component. Components report fixed words only, never exception text, because the endpoint is
public. The web status page (`/status`) now shows each component, where it previously showed "unreachable" for any
503.

## Upgrading an existing installation

1. **Postgres:** a volume created before this change has no app role. Run the SQL in docs/backup-recovery.md,
   "Moving an existing database to the app role", then set `POSTGRES_APP_USER` and `POSTGRES_APP_PASSWORD`.
2. **MinIO:** set `MINIO_ROOT_USER` and `MINIO_ROOT_PASSWORD` to the **old** `S3_ACCESS_KEY` and `S3_SECRET_KEY`
   values (the existing root). Choose new values for `S3_ACCESS_KEY`/`S3_SECRET_KEY` (API), `S3_WORKER_*` and
   `S3_BACKUP_*`. Then `docker compose up minio-init`.
3. **Metrics:** set `METRICS_SCRAPE_PASSWORD` if Prometheus should scrape. It is closed by default.
