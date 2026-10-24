# Chaya 02

Digital-twin platform for physical venues: State 01 (the venue) -> State 02 (its living digital reconstruction).

> **Status update (2026-10-24):** the current baseline is
> [docs/BASELINE_AUDIT_2026-10-24.md](docs/BASELINE_AUDIT_2026-10-24.md). Two things in the record below have changed
> since it was written. First, real **indoor** public photos (ETH3D `office`) have since run through upload, privacy,
> COLMAP SfM and metric calibration. That is still not a venue capture, and gsplat has still never trained. Second, CI
> has changed: `reconstruction-smoke` is green, while `python` (open3d), `docker` (worker image), `security`,
> `acceptance` (browser) and `ios` are red.

## Status (2026-10-12)

Every stage of the capture → reconstruction → viewer → search → navigation → AR chain is **implemented** in code, and
most of it is unit- and integration-tested. **None of it has run on a real indoor venue, a CUDA GPU, or a physical
phone or AR device.** Known limitations that remain open:

- **GPU:** gsplat training (SPLAT_RECONSTRUCTION) has never executed. The GPU worker image (`Dockerfile.gpu`) has never
  been built. In the shipped deployment no worker claims POSE_ESTIMATION unless a GPU worker is attached.
- **Metric calibration:** no measured indoor references exist, so no reconstruction has ever been calibrated to metres.
  Calibration points are picked in the reconstruction by hand.
- **Real venue:** no measured indoor capture exists. Real-media evidence is 11 public outdoor photographs.
- **AR devices:** the Android WebXR and iOS ARKit clients have never run on a device.
- **Models:** all segmentation, detection and embedding models are stock checkpoints; none is fine-tuned. The default
  segmentation weights are licensed for research only.
- **CI:** red on `main` in `python`, `reconstruction-smoke`, `docker`, `security` and `ios`; `deploy` has never
  succeeded and no real deploy host has been used.

Per-capability status at six validation levels: [PROJECT_PLAN.md](PROJECT_PLAN.md#validation-matrix). Commands and
results: [docs/TEST_REPRODUCIBILITY.md](docs/TEST_REPRODUCIBILITY.md).

## Documents

| Document | What it covers |
|---|---|
| [ARCHITECTURE.md](ARCHITECTURE.md) | System boundaries, data flow, API contracts, artifact lifecycle, scan versions, current vs planned design |
| [PROJECT_PLAN.md](PROJECT_PLAN.md) | Milestones and the validation matrix |
| [DEPLOYMENT.md](DEPLOYMENT.md) | CI/CD, production environment, migrations, health, deploy and rollback, backups, recovery, GPU workers |
| [DEVELOPMENT_RULES.md](DEVELOPMENT_RULES.md) | Engineering rules, including how status is claimed |
| [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md) | Finding and fixing the first failing pipeline stage |
| [docs/pipeline.md](docs/pipeline.md), [docs/rescan.md](docs/rescan.md) | Stages, runs, artifacts, re-scans and versions in detail |
| [docs/TEST_REPRODUCIBILITY.md](docs/TEST_REPRODUCIBILITY.md) | What runs where, current CI state |
| [docs/BASELINE_AUDIT_2026-10-24.md](docs/BASELINE_AUDIT_2026-10-24.md) | Current baseline: P0/P1/P2, requirement traceability, specification reconciliation, path to the first real-venue demo |
| [docs/E2E_VALIDATION.md](docs/E2E_VALIDATION.md), [docs/REAL_VENUE_ACCEPTANCE.md](docs/REAL_VENUE_ACCEPTANCE.md) | Recorded end-to-end evidence; the procedure for a real-venue run |
| [.env.example](.env.example) | Local configuration template |

## Layout

| Path | What |
|---|---|
| `apps/web` | Next.js web app: capture, HUD, viewer, search, Android WebXR AR, operations dashboard |
| `apps/ios-ar` | Native iOS ARKit navigation app (`ChayaARCore` Swift package + app) |
| `services/api` | Spring Boot control plane (`/api/v1`), Flyway migrations |
| `services/reconstruction` | Python pipeline worker (`chaya_worker`), `native/chaya-navmesh` (Recast/Detour CLI), CPU and GPU Dockerfiles |
| `services/vision` | FastAPI service that embeds search queries with CLIP |
| `packages/contracts` | Generated OpenAPI (`openapi/v1.yaml`), stage artifact contract, test fixtures |
| `infra/docker` | Local development infrastructure (Compose) |
| `infra/ci` | Compose overlays and the test-only COLMAP worker image used by smoke and E2E runs |
| `infra/deploy` | Production Compose, Caddy, `deploy.sh` |
| `infra/monitoring` | Prometheus, Grafana, Loki, alert rules |
| `scripts` | CI, backup/restore, deploy tests, E2E and acceptance harnesses |
| `benchmarks` | B1–B6 benchmark harnesses and recorded results |

## Run locally

Prerequisites: Docker with Compose v2; Node 22 (CI) or 24; Python 3.12 for the worker; JDK 21 and Maven 3.9 for the API
on the host (or Docker only, see step 3b). About 5 GB of Docker memory for the whole stack.

Labels: **[verified]** was run on 2026-10-12 on Windows 11 with Docker Desktop; **[config verified]** means
`docker compose … config` passed but the stack was not started that day; **[unverified]** names the missing
prerequisite.

1. Configuration. Copy the template and fill in **every blank value** (Compose refuses to start otherwise):
   ```
   cp .env.example .env
   ```
2. Infrastructure: Postgres + pgvector, MinIO (+ bucket and account setup), ClamAV, Keycloak (realm imported).
   **[config verified]**
   ```
   docker compose --env-file .env -f infra/docker/docker-compose.yml up -d
   ```
3. API. Flyway applies `services/api/src/main/resources/db/migration/V*.sql` (V1–V43) at startup; readiness stays DOWN
   until it finishes.
   - a. On the host, with the variables from `.env` exported (`POSTGRES_*`, `S3_*`, `KEYCLOAK_ISSUER_URI` are required):
     **[unverified]** on the audit host, which has no JDK 21 or Maven.
     ```
     set -a; . ./.env; set +a
     cd services/api && mvn spring-boot:run
     ```
   - b. Or as a container on the same infrastructure (what the stack smoke does): **[config verified]**; the stack smoke
     ran it locally on 2026-10-11 (docs/OPS_RELIABILITY_AUDIT_2026-10-11.md).
     ```
     docker compose --env-file .env -f infra/docker/docker-compose.yml -f infra/ci/docker-compose.smoke.yml up -d --build
     ```
4. Web app on http://localhost:3000. It reads `CHAYA_API_BASE_URL` (server side) and `NEXT_PUBLIC_*` (browser, `next dev`
   fallback) from the environment, not from the root `.env`: **[unverified]** (`next dev` was not started; `npm run build`
   was verified).
   ```
   cd apps/web && npm ci
   set -a; . ../../.env; set +a
   npm run dev
   ```
5. A tenant and a user. There is no organization API: insert `organization` and `venue` rows with SQL, then create a
   Keycloak user in realm `chaya` with attributes `org_id` and `venue_id` and a realm role (`viewer`,
   `operator`, `venue-manager` or `admin`). `scripts/ci/stack-smoke.sh` (section "seed") does exactly this and is the reference.
   Roles and claims: [docs/security.md](docs/security.md).
6. Pipeline worker (CPU stages only). **[config verified]**
   ```
   docker compose --env-file .env -f infra/docker/docker-compose.yml --profile pipeline up -d reconstruction-worker
   ```
   Or outside Docker (`cd services/reconstruction`, `py -3.12 -m venv .venv`, `.venv/Scripts/pip install -e ".[dev]"`,
   `python -m chaya_worker`) with `CHAYA_API_URL`, `CHAYA_TOKEN_URL`, `CHAYA_CLIENT_SECRET`, `S3_ENDPOINT` and
   `S3_ACCESS_KEY`/`S3_SECRET_KEY` set to the **worker** account (`S3_WORKER_*`), never the API's.
   This worker has no COLMAP, CUDA, gsplat or Open3D: a run stops with `DEPENDENCY_UNAVAILABLE` at POSE_ESTIMATION. That
   is the expected result on a machine without a GPU worker (docs/TROUBLESHOOTING.md).
7. Optional: the vision service for semantic search (`pip install -e ".[model]"` in `services/vision`, then
   `uvicorn app.main:app --port 8090`). **[unverified]** outside a container; the image runs it in the E2E overlay.
   Without it search falls back to lexical matching and health reports `vision` DOWN or NOT_CONFIGURED.
8. GPU worker: [DEPLOYMENT.md §10](DEPLOYMENT.md#10-gpu-workers). **[unverified]**: needs an NVIDIA GPU (compute
   capability ≥ 7.0) and about 15 GB of disk to build; it has never been built.

Check it: `curl http://localhost:8080/api/v1/health` (per-component UP / DEGRADED / DOWN), and `/status` in the web app.

## Tests

| Suite | Command | Last local result (2026-10-12) |
|---|---|---|
| Fixtures present | `bash scripts/ci/check-fixtures.sh` | 12 fixtures, checksums match |
| Web | `cd apps/web && npm ci && npm run lint && npm run typecheck && npm test && npm run build` | clean, clean, 215 passed, built |
| Web e2e | `cd apps/web && npx playwright install chromium && CI=1 npm run test:e2e` | 18 passed (2 flaky under parallel load, passed on retry); API mocked |
| API | `cd services/api && mvn -B -ntp verify` (needs Docker for Testcontainers; run in `maven:3.9-eclipse-temurin-21` on the audit host) | 531 run, 0 failures, 4 opt-in skips |
| iOS core | `cd apps/ios-ar && swift test` (Linux or macOS Swift 5.10) | 55 passed (in `swift:5.10-jammy`) |
| Worker | `cd services/reconstruction && .venv/Scripts/python -m ruff check . && .venv/Scripts/python -m pytest -m "not gpu and not integration" -rs` | ruff clean; 566 passed, 2 skipped |
| Benchmarks framework | `services/reconstruction/.venv/Scripts/python -m pytest benchmarks/tests` | 10 passed |
| Deploy script | `bash scripts/deploy/test-deploy.sh` | 26 passed |
| Stack smoke | `PYTHON=<python with the worker installed> bash scripts/ci/stack-smoke.sh` (set `SMOKE_*_PORT` if 8080/8081/9000 are taken) | passed: stopped at POSE_ESTIMATION as expected; restore drill passed (fails in CI at cleanup, docs/TEST_REPRODUCIBILITY.md) |

On Linux/macOS use `.venv/bin/python`. GPU, real-photo, live-MinIO and live-stack tests are opt-in and skip with a
reason; docs/TEST_REPRODUCIBILITY.md lists each and what it needs.
