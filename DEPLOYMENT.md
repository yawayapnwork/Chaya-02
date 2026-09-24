# Deploying Chaya 02

How Chaya 02 is built, shipped, started, checked, rolled back, backed up and recovered.

- **Target:** one Docker host per environment (staging, production), using Docker Compose. There is no Kubernetes
  (DEVELOPMENT_RULES.md, rule 20).
- **GPU workers** run on separate GPU hosts ([GPU workers](#gpu-workers)).
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
| `reconstruction-smoke` | PRs touching the pipeline, main, nightly | see [Smoke tests](#smoke-tests) |
| `deploy` | after `docker` succeeds on main (staging), or manually (production, rollback) | CI passed for the images' commit ([deploy gate](#deploy-gate)); the images exist; the deploy with automatic rollback; public health checks from outside |

**Branch protection (GitHub settings, not in the repository).** Require `frontend`, `backend`, `python`,
`docker`, `reconstruction-smoke` and `security` on `main`. Because these workflows are path-filtered, mark them as
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
    structured, retryable failure at the first GPU stage. It then retries once.
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

Set in `infra/deploy/docker-compose.yml`, so they are not needed in `.env`:

- The service URLs.
- `CHAYA_PUBLIC_*`, derived from the domains.
- `KEYCLOAK_JWKS_URI`, which points at the internal network.
- `SERVER_FORWARD_HEADERS_STRATEGY=native`, so rate limits key on the real client address.
- `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`, for JSON logs.

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
   - Organizations are created in SQL; there is no API for that yet.
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

DEGRADED (HTTP 200 on `/api/v1/health`) means the API works, but either the malware scanner is down (uploads are
quarantined) or the processing queue is stalled (no worker is claiming jobs). It is visible on `/status`, in Grafana,
and in the `ChayaQueueStalled` alert.

## 7. Deploying and rolling back

**Normal path:** merge to `main`. `docker` publishes the `sha-<commit>` images, and `deploy` rolls them out to
**staging**. When staging looks right, run `deploy` with environment `production` and the same version. A reviewer
approves it through the GitHub Environment protection.

What `infra/deploy/deploy.sh <version>` does on the host:

1. Pulls every image of that version. A bad tag fails here, before anything changes.
2. Takes a pre-deploy `pg_dump` (the restore point).
3. Runs `compose up -d`.
4. Waits up to 10 minutes for postgres, keycloak, api, web and worker to be **healthy**.
5. Applies the Keycloak settings.
6. Checks `https://<api>/api/v1/health` (200) and `https://<app>/api/health` (200).

If any step after the switch fails, it **automatically rolls back** to the previous version in
`.deployed-version`, waits for health again, and exits non-zero. History is kept in `.deploy-history`.

**Manual rollback:** run the `deploy` workflow with the earlier version, or on the host run
`./deploy.sh <previous-tag>`. Images are immutable, so this redeploys exactly what ran before. The schema stays
where it is. That is safe under the migration rule in section 5; if that rule was broken, see Recovery.

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
- **Verification:** `verify.sh` restores into a scratch database and checks a sample of objects against the
  checksums the database recorded.
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
| Worker unavailable | Health shows DEGRADED with processing STALLED, and the queue waits. Restart the worker, then check `docker compose logs worker` and its health status. Jobs a worker was running when it died are failed as `WORKER_LOST` within 5 minutes, and can be retried from the operations dashboard. |
| Host lost | Rebuild it (section 3), restore Postgres (both databases) and MinIO from off-site backups, then deploy the recorded version. |

Every restore should end with `verify.sh`, a green `/api/v1/health`, and a note of what was restored and why.

## 10. GPU workers

The CPU `worker` in this compose file claims only the stages its image can run (`WORKER_STAGES`). Pose estimation,
splat reconstruction, segmentation, cleanup, plane fitting, semantic indexing and navigation baking need a GPU host
with COLMAP/GLOMAP, CUDA, PyTorch, gsplat, Open3D and recast-cli (docs/pipeline.md). No GPU image is built by CI.
Building one belongs with the GPU host's provisioning, because it depends on the CUDA driver.

A GPU worker needs:

- the same package (`pip install ./services/reconstruction[reconstruction]`);
- `WORKER_STAGES` set to the GPU stages, which all run **after** `PRIVACY_PREPROCESS`;
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

**Not verified:**

- A real deploy: `deploy.yml` and `deploy.sh` have never run against a host with public DNS, ACME certificates
  and GHCR images, and nor has the automatic rollback path. Do it on staging first, including one deliberate failed
  deploy to watch the rollback.
- The GHCR push, SBOM and provenance attestation, and the Trivy scan. These only run in GitHub Actions.
- The `security` workflow's OWASP Dependency-Check. It needs the NVD database (`NVD_API_KEY`).
- `require-green.sh` against the live GitHub API. Only its decision logic was tested.
- GPU workers: no GPU image exists, and the full reconstruction (`pytest -m gpu`) needs a GPU host.
- The Playwright e2e job.
