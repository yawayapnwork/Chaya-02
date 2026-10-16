# Deploying Chaya 02

How Chaya 02 is built, shipped, started, checked, rolled back, backed up and recovered.

- **Target:** one Docker host per environment (staging, production), using Docker Compose. There is no Kubernetes
  (DEVELOPMENT_RULES.md, rule 20).
- **One API instance.** Do not scale `api` (no `--scale api=N`, no `deploy.replicas`): rate limits, the capture HUD
  stream, media-validation resume and the periodic sweeps are in-process, and PostgreSQL is the only shared store.
  There is no Redis. Workers may be scaled freely; job claims are row-locked in the database. ARCHITECTURE.md §3.1
  lists what would have to change first.
- **GPU workers** run on separate GPU hosts ([GPU workers](#10-gpu-workers)).
- **Deployment files:** `infra/deploy/`.
- **Pipeline files:** `.github/workflows/`.

```
                 Internet
                    │ 80/443
              ┌─────▼─────┐   edge network
              │   Caddy   │── TLS (ACME), security headers, /actuator blocked, Keycloak /admin IP-allowlisted
              └─┬───┬───┬─┘
       app.*    │   │   │  auth.*
          ┌─────▼┐ ┌▼───┴──┐ ┌──────────┐
          │ web  │ │  api  │ │ keycloak │
          └──────┘ └┬──────┘ └────┬─────┘
                    │  internal network (no internet route)
   ┌────────┬───────┼─────────┬───┴─────┬──────────┐
┌──▼───┐ ┌──▼──┐ ┌──▼───┐ ┌───▼───┐ ┌───▼──┐ ┌─────▼──┐
│worker│ │minio│ │clamav│ │vision │ │postgres (chaya + keycloak DBs)
└──────┘ └─────┘ └──┬───┘ └───┬───┘ └──────┘
                    └─ egress ─┘  (signature updates, model weights only)
```

## 1. CI/CD pipeline

| Workflow | Runs on | What it proves |
|---|---|---|
| `frontend` | PRs and main touching `apps/web` | lint, unit tests, typecheck, a production build **with no environment baked in**, Playwright e2e against a mocked backend |
| `backend` | PRs and main touching `services/api` | `mvn verify`: unit tests plus integration tests against real PostgreSQL/pgvector and MinIO (Testcontainers). The job fails if the Testcontainers suites were skipped instead of run. |
| `python` | PRs and main touching `services/reconstruction`, `services/vision` | ruff (pyflakes, bugbear, isort, pyupgrade, bandit) and pytest. The CPU stages run for real; GPU and live-stack suites skip themselves. |
| `security` | PRs, main, weekly | npm audit, pip-audit, OWASP Dependency-Check (CVSS ≥ 7 fails), and license reports (THIRD_PARTY_LICENSES.md) |
| `docker` | PRs (build and scan only), main, and `v*.*.*` tags (build, scan, push) | builds all four images; checks they run as non-root and declare a HEALTHCHECK; Trivy fails on fixable HIGH/CRITICAL findings; pushes to GHCR with an SBOM and provenance attestation |
| `reconstruction-smoke` | PRs touching the pipeline or the backup scripts, main, nightly | see [Smoke tests](#smoke-tests). The stack smoke ends with a backup of the stack's real data and an isolated restore drill on which this build's API must boot ([section 8](#8-backups)). |
| `ops` | PRs and main touching `infra/deploy`, `scripts/backup`, `scripts/deploy`, `scripts/ci` | shellcheck; `deploy.sh`'s control flow against stubs (`scripts/deploy/test-deploy.sh`: config validation, the rehearsal gate, automatic rollback, a failed rollback, the restore hint after a schema change, no `.env` secret in the output); the restore drill against good and corrupted backups (`scripts/backup/test-restore-drill.sh`) |
| `deploy` | after `docker` succeeds on main (staging), or manually (production, rollback) | CI passed for the images' commit ([deploy gate](#deploy-gate)); the images exist; the deploy with automatic rollback; public health checks from outside |

**Actions are pinned by commit SHA** (`uses: owner/action@<40-char sha> # vX.Y.Z`), not by tag. A tag can be moved
or deleted: `docker` and `deploy` hold `packages: write`, `id-token: write` and the deploy SSH key, so a re-pointed
tag would run someone else's code with those. It has already bitten this repository once: the `docker` workflow
referenced `aquasecurity/trivy-action@0.28.0`, a tag that no longer exists upstream (only `v0.28.0` does), so every
`docker` run would have failed at the scan step and staging would never have deployed. Dependabot
(`.github/dependabot.yml`, `github-actions`) updates the SHA and the version comment together. When adding an
action, pin it the same way: `git ls-remote --tags https://github.com/<owner>/<action>` gives the commit (use the
`^{}` line for annotated tags).

**Branch protection (GitHub settings, not in the repository).** Require `frontend`, `backend`, `python`,
`docker`, `reconstruction-smoke`, `security` and `ops` on `main`. Because these workflows are path-filtered, mark them as
required only together with a branch ruleset that treats skipped workflows as passing, or drop the path filters.

### Deploy gate

`docker` does not wait for the test workflows. Without a gate, an image whose tests failed, or were still running,
would reach staging on every push to `main`. So before anything is pulled, `deploy` resolves the commit the images
were built from, and runs `scripts/ci/require-green.sh` against it:

- For each of `frontend`, `backend`, `python`, `security`, `reconstruction-smoke` and `docker`, the latest run for
  **that exact commit** must have succeeded.
- Runs still in progress are waited for, up to 45 minutes. The backend suite often finishes after the images.
- A workflow with **no** run for the commit is reported and allowed. The test workflows are path-filtered, so a
  commit that touched only `apps/web` has no `backend` run.
- Emergency override: the manual run's `allow_unverified` input. It deploys anyway, and a warning naming the actor is
  left in the run log. Production deploys still need the Environment reviewer.

The gate also fixes which configuration is deployed. `deploy` checks out the **images' commit**, not the branch
head, and bundles that commit's compose file, Caddyfile and scripts. So a rollback to `sha-abc1234` runs that
release's own configuration, not a newer compose file it was never tested with.

**Images.** `ghcr.io/<owner>/chaya-{api,web,worker,vision}`. Tags:

- `sha-<7-char commit>`: immutable. **Deploy by this tag, or by a release version.**
- `main`: moving. Never deployed.
- `<x.y.z>` and `<x.y>`: from git tags.

Each image is built once and promoted from staging to production unchanged: none of them contains
environment-specific values or secrets. The web app reads its browser configuration at request time
(`lib/public-config.ts`).

### Smoke tests

Normal CI does **not** run GPU reconstruction.

- **Worker smoke** (`services/reconstruction/tests/smoke`) follows one capture through the worker's own claim loop:
  - The capture is a real H.264 video of a real face, made with FFmpeg, plus a metadata file.
  - The stages that run for real are: input validation, frame extraction, frame quality filter, privacy (a real face
    is detected and blurred), and **artifact generation**, which produces a `.ksplat` that decodes back to its
    input, a manifest with checksums, and the viewer bundle.
  - Artifact generation needs a Gaussian splat, which only the excluded GPU stage can produce. The test supplies a
    small splat **fixture** instead, labelled as such and recorded as an upstream input in the manifest.
  - Every other stage is listed in `EXCLUDED_IN_CI` with the reason it is excluded. Its orchestration contract is
    tested separately: it fails in a structured way, produces no artifacts, and never reports success. A test fails
    if any pipeline stage is neither run nor excluded.
- **Image smoke** runs the same test inside the production worker image. It also checks that the image's health
  check fails before the worker has contacted the control plane.
- **Stack smoke** (`scripts/ci/stack-smoke.sh`) runs the real API container, Postgres, MinIO, ClamAV and Keycloak:
  - It seeds a tenant and an operator, uploads a capture through the ingestion API, lets ClamAV scan it, starts
    processing, and runs the real worker code against the control plane.
  - It asserts that the CPU stages succeed, that PII staging objects are purged, and that the run stops with a
    structured, retryable failure at POSE_ESTIMATION, the first stage that needs a tool the runner lacks (COLMAP). It
    then retries once.
  - It ends with a backup of the stack's data and an isolated restore drill. **In GitHub Actions the job has failed
    since `bd6c69c` (2026-10-11) after both had passed**: the cleanup cannot delete the restored object files that the
    drill's containers wrote (`rm: … Permission denied`), so the script exits 1. Locally (Docker Desktop) it passes.
  - Run it locally with `PYTHON=python bash scripts/ci/stack-smoke.sh`.

The full reconstruction is tested only on a GPU host: `pytest -m gpu` in `services/reconstruction`.

## 2. Environment variables and secrets

The production template is `infra/deploy/.env.production.example`. The filled-in file lives on the host at
`/srv/chaya/.env` (mode 600) and is never committed. `docker compose` refuses to start if a required value is
missing.

| Variable | Used by | Secret | Notes |
|---|---|:-:|---|
| `CHAYA_REGISTRY` | compose | | `ghcr.io/<owner>`, lowercase |
| `CHAYA_VERSION` | compose | | Set by `deploy.sh`, not in `.env` |
| `CHAYA_APP_DOMAIN`, `CHAYA_API_DOMAIN`, `CHAYA_AUTH_DOMAIN` | caddy, web, api, keycloak | | DNS must point at the host before the first deploy |
| `CHAYA_ACME_EMAIL` | caddy | | Let's Encrypt contact |
| `KEYCLOAK_ADMIN_ALLOW` | caddy | | CIDRs allowed to reach `/admin` on the auth domain |
| `POSTGRES_USER` / `POSTGRES_PASSWORD` | postgres, backups | ✔ | Bootstrap superuser. Not used by any application. |
| `POSTGRES_APP_USER` / `POSTGRES_APP_PASSWORD` | api | ✔ | Owns the schema; not a superuser |
| `KEYCLOAK_DB_PASSWORD` | postgres, keycloak | ✔ | Keycloak's own database and role |
| `MINIO_ROOT_USER` / `MINIO_ROOT_PASSWORD` | minio, minio-init, restore | ✔ | Root. Used only to create buckets and accounts. |
| `S3_ACCESS_KEY` / `S3_SECRET_KEY` | api | ✔ | Read, write and delete on both buckets |
| `S3_WORKER_ACCESS_KEY` / `S3_WORKER_SECRET_KEY` | worker | ✔ | Raw: read only. Derived: read and write. No delete. For the CPU worker on this host only. |
| `S3_RECON_ACCESS_KEY` / `S3_RECON_SECRET_KEY` | GPU workers | | Derived: read and write, except `pii/` staging. No raw bucket. No delete. Created only when set. |
| `S3_BACKUP_ACCESS_KEY` / `S3_BACKUP_SECRET_KEY` | backups | ✔ | Read only |
| `KEYCLOAK_ADMIN` / `KEYCLOAK_ADMIN_PASSWORD` | keycloak | ✔ | Temporary bootstrap admin. Create a named admin, then remove the bootstrap one. |
| `CHAYA_WORKER_CLIENT_SECRET` | keycloak (realm import), worker | ✔ | The worker service account |
| `METRICS_SCRAPE_PASSWORD` | api, Prometheus | ✔ | Empty means `/actuator/prometheus` is closed |
| `BACKUP_DIR`, `BACKUP_KEEP_DAYS` | backups | | Keep `BACKUP_DIR` outside the deploy directory, on encrypted storage |

Optional, with defaults (not in the template):

| Variable | Used by | Default | Notes |
|---|---|---|---|
| `MINIO_IMAGE`, `MC_IMAGE` | compose (host `.env`) | the pinned GHCR mirror digests | Only to stage a MinIO upgrade ([section 7](#7-deploying-and-rolling-back)) |
| `DEPLOY_SKIP_REHEARSAL` | `deploy.sh` (shell environment) | unset | `1` skips the migration rehearsal (logged; emergencies only) |
| `PII_STAGING_RETENTION` | api | `PT24H` | How long a FAILED run keeps unanonymised frames for a retry. **Not passed through by `infra/deploy/docker-compose.yml`**: the default applies unless the `api` service's `environment` is edited. |
| `CHAYA_RATE_LIMIT_ENABLED` | api | `true` | Same: not passed through; docs/security.md, "Rate limits" |
| `GSPLAT_*`, `ALIGNMENT_*`, `CLEANUP_*`, … | workers | see `chaya_worker/settings.py` | Worker tunables (docs/pipeline.md). Set them in the GPU worker's own environment; the CPU `worker` service passes none through. |

Set in `infra/deploy/docker-compose.yml`, so they are not needed in `.env`:

- The service URLs.
- `CHAYA_PUBLIC_*`, derived from the domains.
- `KEYCLOAK_JWKS_URI`, which points at the internal network.
- `SERVER_FORWARD_HEADERS_STRATEGY=native`, so rate limits key on the real client address.
- `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`, for JSON logs.

**Local and hosted configuration are separate files.**

| | Local development | Hosted (staging, production) |
|---|---|---|
| Template | `.env.example` (repo root, copied to `.env`) | `infra/deploy/.env.production.example` (copied to `/srv/chaya/.env`) |
| Compose file | `infra/docker/docker-compose.yml`: infrastructure only. The API and web run on the host (README "Run locally"). The worker is under `--profile pipeline`. | `infra/deploy/docker-compose.yml`: everything, images from the registry |
| Network addresses | The host's own processes use `localhost:<port>`. Every published port is bound to `127.0.0.1`. The worker container reaches the host API through `host.docker.internal`. | Containers use service names (`api:8080`, `minio:9000`, `keycloak:8080`). The browser uses the three public HTTPS names. Only Caddy publishes ports (80, 443). |
| Keycloak | `start-dev`, embedded storage, issuer `http://localhost:8081/realms/chaya` | `start`, PostgreSQL storage, issuer `https://$CHAYA_AUTH_DOMAIN/realms/chaya`. The API fetches keys over the internal network (`KEYCLOAK_JWKS_URI`) and checks the public issuer and the `chaya-api` audience. |

No container is configured with `localhost` for another service: inside a container, `localhost` is the container
itself. The remaining `localhost` addresses are of two kinds:
- health checks that probe their own container;
- in development, Keycloak's public hostname (`KC_HOSTNAME`). That is the issuer the browser and the host-run API use,
  not an address any container calls.

**What is checked before anything serves:**

| Check | Where | What happens |
|---|---|---|
| A required variable is missing or empty | Compose (`${VAR:?}`); `deploy.sh` step 1 | Refused, naming the variable, with its value never printed. Nothing starts. |
| A malformed `.env` line | `scripts/backup/env-file.sh` | Refused with the line number |
| `S3_ENDPOINT`, `KEYCLOAK_ISSUER_URI`, `KEYCLOAK_JWKS_URI` not absolute http(s) URLs; a `CHAYA_ALLOWED_ORIGINS` entry with a path, query or wildcard | API startup (`ConfigChecks`, `StorageProperties`, `SecurityProperties`, `CorsConfig`) | The API does not start, and the message names the variable. An origin with a path would otherwise match no browser request, and every call from the web app would fail CORS. |
| Missing worker settings; `CHAYA_API_URL`, `CHAYA_TOKEN_URL` or `S3_ENDPOINT` not http(s) URLs; a misspelt `WORKER_STAGES` entry | Worker startup (`Settings.require_service_config`) | The worker exits. A misspelt stage would otherwise be claimed by no one, and its jobs would wait until their deadline. |
| The browser configuration (`CHAYA_PUBLIC_*`) is incomplete | Web readiness (`/api/health`) | Not ready, naming the missing variables |
| Database, both buckets, identity provider | API readiness | Not ready (section 6) |

**Not used:** Chaya has no Redis or other cache or queue (the job queue is PostgreSQL). The only Redis-compatible
service in the repository is GlitchTip's own Valkey in the optional monitoring stack. It also issues no presigned
object-storage URLs: uploads and downloads go through the API after the venue and organization checks, so MinIO is
never exposed to browsers and sits on the internal network only.

**GitHub stores only the SSH access for each environment:** secrets `DEPLOY_HOST`, `DEPLOY_USER`, `DEPLOY_SSH_KEY`
and `DEPLOY_KNOWN_HOSTS` (the host's pinned key: `ssh-keyscan -t ed25519 <host>`, verified out of band), and
variables `DEPLOY_PATH`, `APP_URL` and `API_URL`. Registry pulls use the job's short-lived `GITHUB_TOKEN`, which is
logged out on the host after the deploy. No image contains a secret. The `docker` workflow's Trivy step also runs
a secret scan on every image.

**Rotation:**

- **Database or MinIO service account:** change the value in `.env`, then apply it:
  - Postgres: `ALTER ROLE ... PASSWORD`.
  - MinIO: rerun `docker compose up minio-init`, which updates the accounts.
  - Then redeploy the same version.
- **`CHAYA_WORKER_CLIENT_SECRET`:** change it in the Keycloak admin console (client `chaya-worker`, Credentials) and
  in `.env`, then restart the worker.

## 3. First-time host setup

1. **Host:** a Linux host with Docker Engine and the Compose plugin, and a `deploy` user in the `docker` group.
   Open ports 80 and 443 only.
2. **DNS:** A/AAAA records for the three domains, pointing at the host.
3. **Configuration:** `mkdir -p /srv/chaya /srv/chaya-backups`. Copy `.env.production.example` to
   `/srv/chaya/.env`, fill it in, and `chmod 600` it.
4. **First deploy:** run the `deploy` workflow (environment, version). The first deploy has no previous version to
   roll back to, and no database to back up.
5. **Keycloak:**
   - Log in to the admin console from an allowed address.
   - Create a named administrator and delete the bootstrap admin.
   - Create users, with their `org_id` and `venue_id` attributes (docs/security.md).
   - Organizations (and their venues, if not created through the API by an admin) are created in SQL; there is no
     organization API. `scripts/ci/stack-smoke.sh` ("seed") shows the statements and the Keycloak user setup.
6. **Backups:** schedule them ([section 8](#8-backups)).

## 4. Startup order

Compose enforces this order with `depends_on` conditions, not with sleeps:

1. **postgres** (healthy: `pg_isready`). On first start, init scripts create the app role, the `vector` and
   `pg_trgm` extensions, and Keycloak's database.
2. **minio** (healthy: `mc ready`), then **minio-init** (must *complete successfully*: private buckets and
   service accounts).
3. **clamav** (healthy: `clamdcheck`; the first start downloads signatures, allow up to 5 minutes), and
   **keycloak** (healthy: `/health/ready`; imports the realm on first start).
4. **api** starts after 1–3. Flyway migrations run **before** it reports ready. It is healthy only when its
   readiness probe passes.
5. **web** and **worker** start after the API is healthy.
6. **caddy** starts after web, api and keycloak are healthy, so no traffic reaches a service that isn't ready.
7. `deploy.sh` then runs `keycloak-configure.sh` (sets this environment's redirect URIs and web origins) and checks
   the public endpoints.

## 5. Migrations

- **What:** Flyway runs automatically at API startup, as the non-superuser app role. It applies
  `services/api/src/main/resources/db/migration/V*.sql` in order and validates the ones already applied.
  Readiness stays DOWN until it finishes, so traffic never reaches a half-migrated API.
- **Forward-only:** there are no down-migrations. Every migration must be **backward compatible with the previous
  release**, which is the expand/contract pattern:
  - add columns and tables first;
  - backfill;
  - switch the code over;
  - drop old structures only in a later release, once no deployed version uses them.

  This is what makes image rollback safe.
- **Before every deploy,** `deploy.sh` takes a `pg_dump`. If a release with an incompatible migration must be undone,
  restore that dump (see Recovery). If a deploy fails after the schema version changed, the deploy log prints the
  exact restore command.
- **Superuser-only operations** (a new extension, for example) cannot run from Flyway as the app role. Add them to
  `infra/docker/postgres/initdb/10-app-role.sh` for new hosts, and run them once by hand as `POSTGRES_USER` on
  existing hosts, before deploying.

## 6. Health checks

No service reports healthy just because its process started.

| Service | Liveness (process) | Readiness (used by Docker HEALTHCHECK, Caddy, deploy) | Ready means |
|---|---|---|---|
| api | `/actuator/health/liveness` | `/actuator/health/readiness` | Startup and migrations finished, **and** the database, object storage and identity provider (JWKS) answer. Details: `/api/v1/health` (UP / DEGRADED / DOWN per component; public, cached 5 s). Liveness never includes dependencies, so an outage makes the API unready rather than putting it in a restart loop. |
| web | `/api/health/live` | `/api/health` | The browser configuration (`CHAYA_PUBLIC_*`) is complete, **and** the API is reachable over the internal network. It stays ready if the API reports itself DOWN: `/status` then shows users what is broken. |
| worker | n/a (no HTTP) | `python -m chaya_worker.liveness` | A control-plane call (claim or heartbeat) **succeeded** within 3 × max(poll, heartbeat) + 30 s. It is unhealthy before its first successful call, and whenever it cannot reach the API or has bad credentials. |
| vision | `/health/live` | `/health/ready` (and `/health`) | The CLIP model is **loaded**, not merely installed. It returns 503 during the first weight download or if loading fails, with the reason. |
| keycloak | — | `:9000/health/ready` | Keycloak's own readiness check (database connected) |
| postgres, minio, clamav | — | `pg_isready`, `mc ready local`, `clamdcheck.sh` | The service's own readiness check |

DEGRADED (HTTP 200 on `/api/v1/health`) means the API works, but something it relies on is impaired:

| Component | Required to serve? | DEGRADED when |
|---|---|---|
| `malwareScanner` | no | DOWN or DISABLED: uploads are quarantined, never accepted unscanned |
| `worker` | no | DOWN: no worker polled for work or heartbeated within `chaya.health.worker-live-after` (3 min). Measured from `worker_presence` (V34), not inferred from the queue. |
| `processing.status` | no | `NO_WORKER`: claimable queued work has a stage no live worker offers (`processing.unservedStages` names it, for example `POSE_ESTIMATION` on a host without a COLMAP worker); `STALLED`: work waited 15 min while nothing ran |
| `vision` | no | DOWN: semantic search falls back and new POI embeddings wait. `NOT_CONFIGURED` does not degrade. |
| `processing.staleRunningJobs` | — | informational: RUNNING jobs whose lease expired and that the lease enforcer has not failed yet |

Object storage counts as UP only when **both** buckets (`S3_BUCKET_RAW`, `S3_BUCKET_DERIVED`) exist and the API's
account can reach them (2026-10-15). `minio-init` creates them, and the API's least-privilege account has no
`s3:CreateBucket`. Before this change, a missing bucket read as UP ("created on first upload"), and only the raw
bucket was checked, so a stack whose `minio-init` had not run reported ready while every upload and pipeline write
failed. A missing bucket is now storage DOWN, and the API logs which bucket once.

**Readiness answers within a probe's timeout during an outage (2026-10-16).** On the production compose, with
postgres stopped, `/actuator/health/readiness` took 10 to 24 s to answer 503. Docker's and Caddy's probes give up
after 5 s, so the API was still taken out of rotation, but by timeout rather than by an answer, and each probe held a
request thread for that long. There were two causes:
- The health check is serialized and cached for 5 s, but the cache counted from when a check *started*. A check against
  a dead database takes about 5 s (the connection timeout), so every caller queued behind it found the result already
  expired and ran another full check in turn.
- Readiness also included Spring's own `db` indicator, which waited on the connection pool a second time before
  `dependencies` (which checks the database itself) ran.

Now callers that waited for a check share its result, the cache counts from the end of a check, and readiness is
`readinessState,dependencies`. Measured on the same stack: 503 after 5.0 s for a fresh check and 0.1 s from the cache,
including three concurrent callers. `HealthServiceTest` covers the queueing (the old code ran three checks where the
new one runs one).

DOWN (503) is decided by the database, object storage and identity provider only. The report is public, so it carries
status words, counts and stage names, never worker ids. It is visible on `/status`, in Grafana, and in the
`ChayaNoLiveWorker`, `ChayaJobsStranded` and `ChayaQueueStalled` alerts.

## 7. Deploying and rolling back

**Normal path:** merge to `main`. `docker` publishes the `sha-<commit>` images, and `deploy` rolls them out to
**staging**. When staging looks right, run `deploy` with environment `production` and the same version. A reviewer
approves it through the GitHub Environment protection.

What `infra/deploy/deploy.sh <version>` does on the host:

1. Validates the configuration (`compose config --quiet`). A missing variable is named, its value is never printed,
   and nothing has changed yet.
2. Pulls every image of that version. A bad tag fails here, before anything changes.
3. Takes a pre-deploy `pg_dump` (the restore point). Whether this is a first deploy is decided by the database volume,
   not by whether postgres is running: a stopped database with data is started and backed up. A host that has
   deployed before but has no database volume is refused (`DEPLOY_ALLOW_EMPTY_DATABASE=1` overrides), since `up`
   would otherwise create a new, empty database and report success on it.
4. **Migration rehearsal:** restores that dump into an isolated throwaway database (an `--internal` Docker network,
   no ports, random credentials) and boots the **new** API image on it (`scripts/backup/restore-drill.sh` with
   `DRILL_DB_ONLY=1`). Flyway applies the new migrations to a copy of production data and Hibernate validates the
   mapping. If that fails, the deploy stops with nothing live changed (`rehearsal-failed` in `.deploy-history`).
   `DEPLOY_SKIP_REHEARSAL=1` skips it for an emergency; the log says so. It costs one restore of the dump.
5. Runs `compose up -d`.
6. Waits up to 10 minutes for postgres, keycloak, api, web and worker to be **healthy**.
7. Applies the Keycloak settings.
8. Checks `https://<api>/api/v1/health` (200) and `https://<app>/api/health` (200).

If any step after the switch fails, it **automatically rolls back** to the previous version in
`.deployed-version`, waits for health again, and exits non-zero. History is kept in `.deploy-history`. If the schema
version moved during the failed deploy, the log prints the exact `restore.sh` command for the pre-deploy dump.

**A deploy is never left half done (2026-10-15).**
- **It runs in its own session.** `deploy.sh` re-runs itself under `setsid`, writes everything to
  `.deploy-logs/<time>-<version>.log`, and the calling process only streams that log. If the SSH connection drops, or
  the workflow is cancelled or hits its 30-minute timeout, the deploy still finishes or rolls back, and the log says
  which. Before this change, sshd's hang-up killed `deploy.sh` wherever it was, possibly between `compose up` and the
  health wait, leaving an unverified version running and recorded nowhere. `test-deploy.sh` reproduces that: with the
  old behaviour, its "session hung up mid-rollout" case fails.
- **One deploy at a time.** `deploy.sh` holds `.deploy.lock` (`flock`), so a second deploy on the same host refuses to
  start. The workflow checks the lock **before** uploading the bundle, so it never replaces the compose file and
  scripts under a running deploy. Its `rsync --delete` keeps `.deploy.lock` and `.deploy-logs/`.
- **Without util-linux.** On a host without `setsid`/`flock` (not a supported deploy host; Git Bash on Windows, for
  example) the script runs inline and says that concurrent deploys are not prevented.

**The host `.env` is read the way Compose reads it (2026-10-15).** The scripts (`deploy.sh`, `keycloak-configure.sh`,
`scripts/backup/*`, the stack smoke) used to `source` it as shell. Two documented values broke that:
- `KEYCLOAK_ADMIN_ALLOW=203.0.113.0/24 198.51.100.0/24` (space-separated, as the template says) ran the second range
  as a command;
- an unfilled `ghcr.io/<owner>` was read as a redirect.

A password containing `$` or a backtick also meant something different to bash and to Compose.

**Fixed 2026-10-16: a stopped database skipped the backup and the rehearsal.** Step 3 used to check only whether the
postgres container was running. On an existing host whose database had stopped (a reboot, an operator, an outage that
left it down), the log said "postgres not running yet (first deploy): no backup to take", and the new version's
migrations then ran on the live data with no restore point and no rehearsal. The production-like stack below was in
exactly that state. `test-deploy.sh` covers it (10 of its checks fail on the old script).

**Fixed 2026-10-15: the migration rehearsal could never pass on a deploy host.** `restore-drill.sh` read the newest
migration number from `services/api/src/main/resources/db/migration`, a directory that exists only in a source
checkout, not in the deploy bundle. On a host that read failed, and under `set -e` the drill died with a bare
`RESULT: FAIL` and no reason. So `deploy.sh` stopped every deploy after the first ("REHEARSAL FAILED … Nothing live
was changed"), and only `DEPLOY_SKIP_REHEARSAL=1` got past it. It went unnoticed because the drill had only run from a
checkout (stack smoke), and `test-deploy.sh` stubs it out. The production-like run below hit it on its second deploy.

The number is now read only when the directory exists; on a host, the API image applies its own migrations in step 6.
An unexpected failure now logs its line and command. `test-restore-drill.sh` runs the drill from a real bundle,
including the database-only rehearsal.
`scripts/backup/env-file.sh` now parses the file with Compose's rules and executes nothing. Quotes are literal, a
` #` starts a comment, and a variable already set in the environment wins. A malformed line is refused by line number,
never by content. Unlike Compose, it does not expand `${VAR}` inside values, so don't use references in `.env`.

**Fixed 2026-10-11:** until then a service that never became healthy did **not** trigger the rollback. `rollout` runs
as an `if` condition, where bash suspends `set -e`, so a failed health wait fell through to the public checks, and the
deploy was recorded as successful whenever those passed. The steps are now chained with `&&`, and
`scripts/deploy/test-deploy.sh` covers the case (it failed 8 of 26 checks before the fix).

**Manual rollback:** run the `deploy` workflow with the earlier version, or on the host run
`./deploy.sh <previous-tag>`. Images are immutable, so this redeploys exactly what ran before. The schema stays
where it is. That is safe under the migration rule in section 5; if that rule was broken, see Recovery.

**Third-party images.** `deploy.sh` runs `compose pull` on every deploy, so any floating tag in the compose file
can change under a deploy or a rollback. How each one is pinned:

| Image | Pin | Why |
|---|---|---|
| `chaya-*` | `sha-<commit>` | Immutable by construction |
| `ghcr.io/yawayapnwork/mirror/minio`, `…/mirror/mc` | release tag **and** `@sha256` digest | The project's own byte-identical GHCR mirror of `quay.io/minio/minio:RELEASE.2025-09-07T16-13-09Z` / `quay.io/minio/mc:RELEASE.2025-08-13T08-35-41Z` (linux/amd64), because the upstream registries no longer allow anonymous pulls; provenance and the update procedure are in [infra/mirror/README.md](infra/mirror/README.md). `latest` would silently upgrade the object store holding every capture. Watch for MinIO security releases yourself: nothing tracks them automatically. Same pins in `infra/docker`, `scripts/backup/common.sh` and the API's Testcontainers setup. |
| `postgres` (`pgvector:pg17`), `keycloak:26.0`, `clamav:1.4`, `caddy:2.10-alpine` | major/minor tag | Patch releases (security fixes) are taken on the next deploy. A major upgrade (Postgres especially: a new major needs `pg_upgrade` or dump/restore) is a deliberate change to this file. |

To upgrade MinIO: mirror the new release (infra/mirror/README.md), set `MINIO_IMAGE`/`MC_IMAGE` in the host `.env` on
staging, deploy, run `verify.sh`, then move the pins.

## 8. Backups

These are the same scripts as in development (docs/backup-recovery.md). On the host, run:

```bash
CHAYA_COMPOSE_FILE=/srv/chaya/docker-compose.yml COMPOSE_NETWORK=chaya_internal ENV_FILE=/srv/chaya/.env \
  /srv/chaya/scripts/backup/backup.sh && /srv/chaya/scripts/backup/verify.sh
```

Schedule it daily with cron or a systemd timer, and alert if it fails.

- **PostgreSQL:** dated `pg_dump -Fc` dumps with SHA-256 files. Every deploy also takes one.
- **MinIO:** an additive mirror through the read-only account. Deletions in the live buckets do **not** propagate,
  so an accidental or malicious delete cannot also destroy the backup. PII staging objects are never copied, and
  rejected uploads are pruned from the mirror using the database (docs/backup-recovery.md).
- **Ownership (fixed 2026-10-16).** The mirror is written by the `mc` container, which runs as root. On a Linux host
  every mirrored file used to be root-owned, so the user running the backups could not delete from it. That broke
  `backup.sh`'s own removal of rejected and **erased** objects (`rm: Permission denied`: an erasure stayed in the
  backup), removing old backups, and `reconstruction-smoke`'s cleanup. `mc_run` now hands ownership back to the invoking
  user after every run, which also repairs a mirror written before the fix. `scripts/backup/test-backup-ownership.sh`
  (in the `ops` workflow) checks it as a non-root user; run as uid 1001 on Linux, the old `common.sh` fails 5 of its 6
  checks.
- **Verification:** `restore-drill.sh` is the check that counts: it restores the dump **and** the object mirror into
  a throwaway Postgres and MinIO on an isolated network, checks every referenced object by SHA-256, and boots the API
  image on the result (docs/backup-recovery.md, "Restore drill"). Schedule it after each backup:
  `... backup.sh && DRILL_API_IMAGE=<registry>/chaya-api:<deployed version> .../restore-drill.sh`. `verify.sh` is the
  lighter check (scratch database on the live server, sampled objects in the mirror directory).
- **Not covered by these scripts:**
  - Keycloak's database (users, credentials, sessions). It is in the same Postgres server, so dump it too:
    `docker compose --env-file .env -f docker-compose.yml exec -T postgres sh -c 'pg_dump -U "$POSTGRES_USER" -d keycloak -Fc' > /srv/chaya-backups/keycloak-$(date -u +%Y%m%dT%H%M%SZ).dump`.
  - Caddy's certificate volume. It is re-issued automatically.
  - Off-site copies. `BACKUP_DIR` is local: replicate it to separate storage with its own retention.

## 9. Recovery

| Situation | Action |
|---|---|
| A new release is unhealthy | This is automatic: `deploy.sh` rolls back. Check `.deploy-history` and the workflow log. |
| A new release is healthy but wrong | Run `deploy` with the previous version. |
| A migration broke data, or the previous version is not schema-compatible | Stop the application (`docker compose stop web api worker`). Run `scripts/backup/verify.sh <pre-deploy dump>`, then `scripts/backup/restore.sh postgres <dump> --yes-replace-live-data`. Deploy the matching previous version. |
| Database lost | Provision a host (section 3) with the **same** `.env`. Start only postgres, then restore the latest verified dump. Deploy the recorded version. |
| Objects lost | `scripts/backup/restore.sh minio --yes-replace-live-data`. Then run `verify.sh` against the current dump. |
| Keycloak unavailable | The API answers `503 AUTHENTICATION_UNAVAILABLE` and readiness goes DOWN. Recover Keycloak or restore its dump. User sessions resume when it is back. |
| Worker unavailable | Health shows DEGRADED with `worker` DOWN (or processing `NO_WORKER` naming the stage nobody serves), and the operations dashboard lists each worker with its last contact. Restart the worker, then check `docker compose logs worker` and its health status. Jobs a worker was running when it died are failed as `WORKER_LOST` within the lease (5 min) plus one enforcer tick (30 s), and can be retried from the operations dashboard. The failed attempt stays on record. |
| A run fails or waits at a stage | Follow [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md): find the first failed or unserved stage, read its error code, fix the prerequisite, then retry the run. |
| Host lost | Rebuild it (section 3), restore Postgres (both databases) and MinIO from off-site backups, then deploy the recorded version. |

Before restoring, run `restore-drill.sh <dump>` on the chosen backup: it proves the backup restores without touching
live data. Every restore should end with a green `/api/v1/health` (or DEGRADED for a known reason), and a note of what
was restored and why.

## 10. GPU workers

The CPU `worker` in this compose file claims only the stages its image can run (`WORKER_STAGES`): the four stages
before and including privacy preprocessing, REGION_SPLICE, ARTIFACT_GENERATION and, since 2026-10-10, NAVIGATION_BAKING
(`chaya-navmesh` is in the CPU image; before that no shipped worker claimed the last stage). Every other stage --
POSE_ESTIMATION, SPLAT_RECONSTRUCTION, SEMANTIC_SEGMENTATION, GEOMETRIC_CLEANUP, REGION_ALIGNMENT, PLANE_FITTING and
SEMANTIC_INDEXING -- needs a GPU worker with COLMAP, CUDA, PyTorch, gsplat, Open3D and the models (docs/pipeline.md).
**Without one, a run waits at POSE_ESTIMATION** until its deadline, and health reports `processing.status: NO_WORKER`
with `unservedStages: ["POSE_ESTIMATION"]` ([docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md)). No GPU image is built
by CI, and **`Dockerfile.gpu` has never been built or run**: there was no GPU host and not enough disk on the audit
workstation.

**GPU worker.** Build `services/reconstruction/Dockerfile.gpu` (docs/pipeline.md, "GPU worker image"). It pins:

- CUDA 12.6;
- torch 2.7.1+cu126;
- gsplat 1.5.3, compiled with its CUDA kernels;
- Open3D 0.19, transformers 4.52.4 and open_clip 2.32;
- the closure of every other Python package (`gpu/constraints-gpu.txt`).

Its default `WORKER_STAGES` is every stage the CPU worker does not run: POSE_ESTIMATION, SPLAT_RECONSTRUCTION,
SEMANTIC_SEGMENTATION, GEOMETRIC_CLEANUP, REGION_ALIGNMENT, PLANE_FITTING and SEMANTIC_INDEXING. Model weights are kept
in a `/models` volume. Fill it once at the pinned revisions:

```
docker run --rm -v chaya-models:/models chaya-worker-gpu python /opt/chaya/fetch_models.py
```

The revisions are SegFormer `489d5cd8…`, Grounding DINO `a2bb814d…` and open_clip `ViT-B-32/openai`. The vision
service must use the same CLIP model (`CLIP_MODEL_NAME`, `CLIP_PRETRAINED`); otherwise image embeddings and search
queries are in different spaces. Host: an NVIDIA GPU with compute
capability ≥ 7.0, a driver for CUDA 12.6 (≥ 560), and the NVIDIA Container Toolkit (`docker run --gpus all`). Before
giving it jobs, run `python -m chaya_worker.splat_preflight` in it (exit 0 = can train), then the GPU acceptance test
(docs/pipeline.md, "GPU acceptance"). Do **not** `pip install gsplat` from PyPI on a worker: that wheel has no
compiled kernels (preflight refuses it with `GSPLAT_CUDA_BACKEND_MISSING`).

A GPU worker for the other GPU stages needs:

- the same package (`pip install ./services/reconstruction[reconstruction]`);
- `WORKER_STAGES` set to the GPU stages, which all run **after** `PRIVACY_PREPROCESS`;
- on a worker that runs `POSE_ESTIMATION` **without** a GPU, `COLMAP_NUM_THREADS` (for example 2). COLMAP otherwise
  starts one CPU SIFT thread per host core, at about 450 MiB each for 1600×1200 frames, so an 18-core host wants
  about 8.5 GiB and is OOM-killed (docs/E2E_VALIDATION.md, 6.2). GPU workers extract on the GPU and leave it unset;
- the `chaya-worker` client secret and the **reconstruction** MinIO account (`S3_RECON_ACCESS_KEY` /
  `S3_RECON_SECRET_KEY` on the main host, passed to the GPU worker as its `S3_ACCESS_KEY` / `S3_SECRET_KEY`). That
  account cannot read the raw bucket or the `pii/` staging objects. **Do not** give a GPU host the `S3_WORKER_*`
  account: it can read every tenant's unblurred captures, and only the CPU worker, which runs privacy
  preprocessing, needs it. If a GPU host is ever given a pre-privacy stage, that stage fails with an access error
  instead of quietly reading raw media;
- network access to the API and MinIO over a private link or VPN. **Do not** publish MinIO or the API's internal
  port to the internet for this.

Its health signal is the same liveness file (`python -m chaya_worker.liveness`).

## 11. What has and has not been verified

Checked on 2026-09-24, on a Windows workstation with Docker Desktop. The same commands run on Linux in CI.

**Verified by running it:**

| What | Result |
|---|---|
| `backend` steps | `mvn verify`: 271 tests, 0 failures, against real PostgreSQL and MinIO. The 4 skipped are the opt-in live ClamAV and Keycloak suites. |
| `frontend` steps | lint, 61 unit tests, typecheck, and a production build with **no** `CHAYA_PUBLIC_*` / `NEXT_PUBLIC_*` values set. The Playwright e2e job was not run locally. |
| `python` steps | ruff clean on both services. Worker: 116 passed, 12 skipped (GPU and live-stack suites). Vision: 7 passed. |
| Worker smoke | 11 passed. The capture → claim loop → CPU stages → ARTIFACT_GENERATION chain runs, and each of the 9 excluded stages keeps its contract (structured failure, no artifacts). |
| Stack smoke (`scripts/ci/stack-smoke.sh`) | Passed against the real API image, Postgres, MinIO, ClamAV (real signatures) and Keycloak. The upload was scanned and accepted, the CPU stages ran, and the run stopped with `DEPENDENCY_UNAVAILABLE` at `POSE_ESTIMATION`. The run found a bug, since fixed: after the test step the cleanup trap ran in the wrong directory. That left the stack running on success, and meant a failure printed no API log. |
| Images | All four build, and all four run as non-root (`api`, `worker` and `vision` as UID 10001, `web` as `node`). Every image declares a HEALTHCHECK. One worker build failed on a stalled package download: the Python images now use a 60 s pip timeout and 10 retries. |
| "Healthy" means ready | Worker: its liveness check exits 1 before its first control-plane call. Web without configuration or API: `/api/health/live` 200, `/api/health` 503. API with no database: startup fails with an explicit Flyway connection error, then exits 1 and is marked unhealthy. It never reports ready. Keycloak 26.0: the compose health command fails during startup and passes once `/health/ready` is UP (about 50 s). |
| Configuration | `actionlint` (with shellcheck) passes on every workflow. `shellcheck` passes on the deploy, CI and backup scripts. `docker compose config` passes for `infra/deploy` with the example environment, and `caddy validate` passes for the Caddyfile. |
| Deploy gate | `scripts/ci/require-green.sh` was tested against recorded run lists. A pass with a path-filtered workflow absent exits 0. A failed run exits 1 and names it. A run still in progress is waited for, then fails at the timeout. |
| Backups | backup → verify → restore, end to end (docs/backup-recovery.md, "What has been verified"). |

**Second pass, same day: running each workflow's steps found two things that would have failed CI on GitHub.**

| Finding | Fix | Re-verified |
|---|---|---|
| `aquasecurity/trivy-action@0.28.0` no longer exists upstream (`git ls-remote`: only `v0.28.0`). Every `docker` run would fail at the scan, so staging would never deploy (it triggers on a successful `docker` run). | Every action pinned by commit SHA ([section 1](#1-cicd-pipeline)); Trivy action `v0.36.0`. | `actionlint` clean. |
| With the scan running, `api` and `web` **failed** it: Trivy 0.70.0 (the action's default), fixable HIGH/CRITICAL. api: Spring Boot 3.5.6 stack (CRITICAL in `spring-security-web`, `tomcat-embed-core`, `netty-handler`; HIGH in pgjdbc, Spring Framework, Jackson, Micrometer) and `libexpat` in the Alpine base. web: all inside npm, which the base image ships and the runtime never runs. | Spring Boot 3.5.16, with pgjdbc 42.7.12, netty 4.1.137 and Tomcat 10.1.60 set ahead of Boot's managed versions (`services/api/pom.xml`); `apk upgrade` in the API runtime stage; npm, npx, corepack and yarn removed from the web runtime stage. | All four images scan clean with the workflow's exact flags (`--severity CRITICAL,HIGH --ignore-unfixed --scanners vuln,secret`). `mvn verify` on Boot 3.5.16: 271 tests, 0 failures, all five Testcontainers suites ran. The web container still serves: no package managers, `/api/health/live` 200, `/api/health` 503 with no API, `/status` renders. |
| MinIO and `mc` defaulted to `:latest`, re-pulled on every deploy and rollback. | Pinned by release tag and digest ([section 7](#7-deploying-and-rolling-back)). | Both pins resolve; `docker compose config` passes for `infra/deploy` and `infra/docker`. |

Also re-run in this pass, on Linux containers matching CI (Node 22, Python 3.12, Temurin 21): frontend lint, 61 unit
tests, typecheck and a build with no environment; ruff and pytest for both Python services (worker 116 passed and 12
skipped, vision 7 passed); the worker image smoke (11 passed inside the production image, liveness fails before the
first control-plane call); `npm audit` and `pip-audit` for both services: no findings. The stack smoke was re-run
against the upgraded API image and the pinned MinIO: readiness UP with every dependency UP, the worker ran the CPU
stages through the real API, and the run stopped with a structured failure at the first GPU stage (1 passed).

**Third pass, 2026-10-11 (operational reliability audit, docs/OPS_RELIABILITY_AUDIT_2026-10-11.md):**

| What | Result |
|---|---|
| `scripts/deploy/test-deploy.sh` | 26 checks pass. Before the `rollout` fix (section 7), 8 failed: an unhealthy version was recorded as deployed. |
| Stack smoke with the restore drill | Passed on this host: real API image built from this tree, Postgres, MinIO, ClamAV, Keycloak, real worker code. Then `backup.sh` (dump and object mirror) and `restore-drill.sh`: database restored in 3 s, objects in 4 s, **19/19** referenced objects matched their recorded SHA-256, and the API image booted on the restored data (Flyway V34 validated). No container or network was left behind. |
| `scripts/backup/test-restore-drill.sh` | 7 checks pass: an intact backup passes; a corrupted object, a missing object and a dump that does not match its `.sha256` each fail, and the bad object is named. |
| shellcheck | clean (`-S warning`) on `infra/deploy/*.sh`, `scripts/backup/*.sh`, `scripts/deploy/*.sh`, `scripts/ci/*.sh` (the committed LF versions) |

**Fourth pass, 2026-10-16 (deployment audit), on a production-like stack.** `infra/deploy/docker-compose.yml` from a
real `bundle.sh` bundle, Caddy with its internal CA on `*.chaya.localhost`, Keycloak in production mode (`start`, its
own Postgres database), the internal network, images built from this tree with the production Dockerfiles. Only
`compose pull` was stubbed (local images, no registry). This is not a deployed environment: no public DNS, no ACME, no
GHCR.

| What | Result |
|---|---|
| Upgrade onto existing data (`deploy.sh`, real Docker) | The stack had been left with postgres stopped. The old script would have called that a first deploy; the fixed one started postgres, took the dump, rehearsed the migrations on a restored copy (2 organizations, 2 venues), rolled out, and passed every health and public check (133 to 139 s). |
| Automatic rollback (real Docker, first time) | A release whose web container exits: rehearsal passed, web never became healthy, rolled back to the previous version, health and public checks passed, exit 1, `.deployed-version` unchanged, `badweb failed` then `prodcheck3 rollback-ok` in the history. The web app was unavailable for about 90 s; single-host compose replaces containers in place, there is no blue/green. |
| Broken API release | Stopped at the rehearsal: `rehearsal-failed`, the running containers untouched, no drill container left behind. |
| A missing image | Stopped at the rehearsal with "Nothing live was changed" (here `compose pull` was stubbed; on a host step 2 fails first). |
| Post-deploy checks (27) | TLS against the CA, `/actuator` 404 through the proxy, HSTS, HTTP→HTTPS 308, only Caddy publishes ports, no token / forged token 401, API role not a superuser and owning the schema, pgvector present, all 40 migrations applied and none failed, Keycloak tokens through the proxy, tenant isolation (another organization's venue, capture and media: 404), an upload scanned by ClamAV and accepted, the stored object byte-identical (read with the backup account), the worker account unable to delete raw objects, a live worker recorded. 27/27, twice (before and after the failed deploys). |
| Browser login | Chromium → web app → Keycloak login page (authorization code + PKCE) → back to the app, which lists the operator's venue from the API (three API calls, all 200). |
| Worker registration | The worker's token comes from `http://keycloak:8080` (internal), the API accepts only the public issuer `https://auth…`; the worker is live (health `liveWorkers: 1`), so issuer and audience agree across both names. |
| Restarts | API container restart: ready again in 21 s, routed by Caddy 7 s later, data intact, worker live again. Whole stack (`compose restart`): serving again in 50 s, data intact. |
| Outages (each dependency stopped, then started) | Storage: DOWN within 7 s, 503, readiness 503, recovered in 13 s without an API restart. Database: DOWN within 9 s, 503, recovered in 12 s. Keycloak: `identityProvider` DOWN within 6 s; no token 401; a forged token 503 (it cannot be checked, never accepted); a genuine unexpired token still accepted (cached signing keys); recovered in 25 s. ClamAV: DEGRADED with `malwareScanner` DOWN within 4 s, an upload during the outage QUARANTINED, not accepted. |
| Backup and full restore drill on the stack's data, with the `mc_run` change | Backup of the database and both buckets, then the drill: 2/2 referenced objects matched their SHA-256, the API booted on the restored data. |
| Configuration | `compose config` passes for `infra/docker` (with the CI overlays), `infra/deploy` and `infra/monitoring`. A removed variable is refused with its name (`POSTGRES_APP_PASSWORD`, `CHAYA_VERSION`). The API started with a wrong or missing setting exits 1 without printing any secret value; the database is connected first, so with no database that is the error reported. |
| Frontend | `next` 16.3.8 (from 16.3.6; six advisories, including the CVE that failed the `docker` image scan) and `npm audit fix` for `sharp` 0.35.5 and `source-map-js` 1.2.2 (lockfile only). `npm audit --omit=dev`: 0. Lint, 322 unit tests, typecheck and the production build with no environment: pass. What remains in a full `npm audit` is the lint toolchain (`eslint-config-next` → `braces`); npm's only offered fix is a downgrade to v14. |
| Scripts | `test-deploy.sh` 50/50 (Linux), `test-restore-drill.sh` 10/10, `test-backup-ownership.sh` 6/6 (as uid 1001 on Linux), shellcheck clean, `ops.yml` passes actionlint. |
| Backend | `HealthServiceTest`, `DependenciesHealthIndicatorTest`, `HealthControllerTest`, `AuthenticationTest`, `ConfigChecksTest`, `JobRecoveryTest`: 40/40. |

The readiness fix was measured with a test image (the `prodcheck2` API image plus the recompiled `HealthService` class,
and the readiness group set through the environment), not a full rebuild: the build host's disk was full.

**Not verified:**

- The `docker`, `security` and `reconstruction-smoke` workflows with the 2026-10-16 fixes. They address the recorded
  causes (the `next` advisory; root-owned backup files at cleanup), but have not run in GitHub Actions.
- The migration rehearsal (`deploy.sh` step 4) against a real host's dump and registry images. Its logic is tested with
  stubs, and the drill it runs is tested on real data in the stack smoke.
- A real deploy: `deploy.yml` and `deploy.sh` have never run against a host with public DNS, ACME certificates
  and GHCR images. The automatic rollback has run only on the production-like stack above. Do it on staging first,
  including one deliberate failed deploy to watch the rollback.
- The GHCR push, SBOM and provenance attestation. These only run in GitHub Actions. (The Trivy scan was reproduced
  locally with the same Trivy version and flags, not in Actions.)
- New advisories. The image scan gates on what is fixable **today**, so a green image can fail a week later with no
  code change. The fix is a dependency or base-image bump, as in the second pass above.
- The `security` workflow's OWASP Dependency-Check. It needs the NVD database (`NVD_API_KEY`).
- `require-green.sh` against the live GitHub API. Only its decision logic was tested.
- GPU workers: `services/reconstruction/Dockerfile.gpu` exists since 2026-10-10 but has never been built (only
  `docker build --check`), and the full reconstruction (`pytest -m gpu`, `tests/gpu/test_splat_acceptance.py`) needs a
  GPU host.
- The Playwright e2e job.

**Status on 2026-10-12 (documentation audit).** The passes above are records of what was verified on those dates; they
are not the current CI state. On `main` at `40beef5`:

- `backend`, `frontend` and `ops` pass.
- `python` fails: the CPU suite may not skip, and two tests now skip on the runner (`tests/ml` needs `transformers`;
  the viewer check in `tests/smoke/test_splat_artifact_smoke.py` needs `npm ci` in `apps/web`). Red since `7d7b177`.
- `reconstruction-smoke` fails at the cleanup step after the stack smoke and the restore drill have passed (section 1).
- `docker` fails the Trivy gate: `next` 16.3.6 has CVE-2026-94483 (HIGH, fixed in 16.3.8). `security` fails on the same
  web audit; its Dependency-Check job hits NVD request failures without `NVD_API_KEY`.
- `ios` fails one stale XCTest (BASELINE_AUDIT_2026-10-09 F5).
- `deploy` has been skipped on every run since 2026-10-04 (it needs a green `docker`); every earlier run failed or was
  cancelled. **It has never succeeded.**

Details and the local results: [docs/TEST_REPRODUCIBILITY.md](docs/TEST_REPRODUCIBILITY.md).
