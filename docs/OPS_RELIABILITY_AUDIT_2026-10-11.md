# Operational reliability audit, 2026-10-11

**Scope:** the operations dashboard (`OpsDashboard.tsx`, `OpsDashboardService`), health endpoints, job leases and retries,
authentication and authorization, tenant guards, audit logging, Compose files, deployment workflows, migrations, MinIO
storage, and the backup and monitoring configuration. The existing infrastructure was kept: only concrete defects and
missing operational contracts were changed.

**Host:** Windows 11, Docker Desktop (7.5 GiB VM, about 2.6 GiB of it used by unrelated stacks, which were left
running). No GPU, no Mac, no physical device, no real deploy host.

## 1. Findings and what was done

| # | Sev | Finding | Fix | Test that now covers it |
|---|---|---|---|---|
| O1 | **High** | **`deploy.sh` did not roll back an unhealthy release.** `rollout` runs as an `if` condition, where bash suspends `set -e`. A failed health wait fell through to the Keycloak step and the public checks, and the deploy was recorded as successful whenever those passed. Rollback only happened when a public check failed. | The steps in `rollout` are chained with `&&` (`infra/deploy/deploy.sh`). | `scripts/deploy/test-deploy.sh` (CI `ops`): 8 of 26 checks failed before the fix, 26 pass after. |
| O2 | **High** | **Backups were never restored into an isolated environment.** `verify.sh` restores into a scratch database **on the live server**. It checks objects as files in the mirror directory, never through a restored object store, and never starts the application on the result. | New `scripts/backup/restore-drill.sh`: a throwaway Postgres and MinIO on an `--internal` network (same images as live), restore of the dump with its owners and of the object mirror, **every** referenced object checked by SHA-256, then the API image booted on the restored data. Everything is torn down, also on failure. It runs at the end of every stack smoke in CI. | Real stack data (stack smoke): 19/19 objects verified, API booted on V34. `scripts/backup/test-restore-drill.sh` (CI `ops`): a corrupted object, a missing object and a tampered dump each fail the drill. |
| O3 | **High** | **Worker liveness was guessed, not measured.** Nothing recorded that a worker existed. Health inferred it from the queue ("jobs wait and nothing runs" for 15 min). That rule cannot tell an idle worker from no worker, and it cannot name a stage no worker runs. That is exactly baseline finding F1: `POSE_ESTIMATION` jobs wait forever on the shipped deployment. | `V34__worker_presence.sql` plus `WorkerPresenceRepository`. Every claim poll (empty or not) and every heartbeat that holds a lease stamps the worker. Health gains `worker` (UP/DOWN/UNKNOWN), `processing.liveWorkers`, `processing.unservedStages` and the status `NO_WORKER`. The dashboard lists the workers. The gauge is `chaya_workers_live`, with the alert `ChayaNoLiveWorker`. Rows unseen for 7 days are pruned. | `JobRecoveryTest.workerLivenessIsMeasuredFromPollsAndUnservedStagesAreNamed`, `aBusyWorkersHeartbeatKeepsItLive`; `WorkerUnavailableHealthTest`; `HealthServiceTest`, `HealthControllerTest` |
| O4 | **Med** | **One job the lease enforcer could not settle stranded every expired job behind it.** The enforcer looped over expired leases in one `try`. An exception on one job (a constraint violation, a broken report) aborted the tick, so the next tick hit the same job first, and the others stayed RUNNING indefinitely. | Each job is settled in its own `try`, oldest lease first. When the normal settlement throws, the job and its run are failed directly with the same code and message (`failDirectly`; the audit entry notes that no stage record was written), so neither stays RUNNING. Only if that fails too does the job wait for the next tick, where `ChayaJobsStranded` sees it. | `JobRecoveryTest.aJobTheEnforcerCannotSettleNormallyIsStillFailedWithItsReasonAndDoesNotStrandTheOthers` (a test trigger makes one job's stage record throw: the other job is settled normally, and the broken one is failed with its reason and can be retried) |
| O5 | **Med** | **A job RUNNING in a run that had already ended stayed RUNNING forever.** `failSystem` returned without touching the job when the run was not RUNNING. No current code path creates that state, but nothing repaired it. | The job is failed with `WORKER_LOST` plus the run's status in the message, and audited. The run is left as it ended. | `JobRecoveryTest.aJobLeftRunningInARunThatAlreadyEndedIsFailedWithItsReasonAndTheRunIsUntouched` |
| O6 | **Med** | **One failed dashboard refresh blanked the section.** A transient failure (network, `503 DATABASE_UNAVAILABLE`) replaced the data with an error, and with 30 s auto-refresh one bad poll emptied the view. | The section keeps its last good data, marked "out of date", and shows the error with a retry. After a 401, 403 or 404 the data is dropped, because the caller may no longer see it. Each section header shows when it was last loaded. | `apps/web/e2e/ops.spec.ts` (Playwright), `lib/ops-view.test.ts` |
| O7 | **Med** | **Queue depth, stale jobs and worker liveness were not shown per venue.** The dashboard had status counts only. | `ops/jobs` returns `queue`: claimable queued jobs and the oldest age, running, lease-expired (stale) and past-budget jobs, unserved stages, and workers with their last contact. The UI shows it, marks lease-expired rows, and says "unavailable" (never 0) when an older API omits it. | `JobRecoveryTest`, `ops.spec.ts`, `ops-view.test.ts` |
| O8 | **Med** | **Health did not report the vision service.** Semantic search and POI embeddings depend on it. | `vision` component (UP, DOWN or NOT_CONFIGURED; `HttpVisionProbe`, `/health/ready`). DOWN degrades the report, NOT_CONFIGURED does not, and it never makes the API DOWN. | `HealthServiceTest.workersAndVisionAreReportedSeparatelyFromTheApiItself`, `HealthControllerTest` |
| O9 | **Med** | **Migrations met production data for the first time on the live database.** | `deploy.sh` step 4 restores the pre-deploy dump in isolation and boots the **new** API image on it (`restore-drill.sh`, `DRILL_DB_ONLY`). On failure the deploy stops with nothing live changed. It can be skipped with `DEPLOY_SKIP_REHEARSAL=1`, which is logged. | `test-deploy.sh` (gate and image wiring, with stubs); the drill itself on real data in the stack smoke |
| O10 | Low | **A missing `.env` variable surfaced mid-deploy, as a pull error.** | `compose config --quiet` runs first. It names the variable and never prints values; nothing is changed. | `test-deploy.sh` |
| O11 | Low | **Expired-lease jobs were not visible to alerting.** | `chaya_jobs_running_lease_expired`, with the alert `ChayaJobsStranded` (non-zero for 5 min means the enforcer is not running). `promtool check rules`: 15 rules OK. | promtool |
| O12 | Low | **CI did not test the deploy and backup scripts.** | New workflow `ops`: shellcheck, `test-deploy.sh`, `test-restore-drill.sh`, and a bundle check. It is added to the deploy gate (`require-green.sh`). `reconstruction-smoke` now also triggers on `scripts/backup/**`. | — |
| O13 | Low | `deploy.sh` wrote the health body to the fixed path `/tmp/chaya-api-health.json`. | Uses `mktemp`. | — |

### Checked and found correct (no change)

- **Dashboard data provenance.** Every figure comes from stored rows, queried by venue and organization. There are no
  hardcoded counts or static analytics. Cost is always `available: false` with a reason, because no pricing source
  exists. Measured stage time is labelled as time, not cost. Storage sizes are the recorded verified sizes.
- **Retries.** A retry re-queues the same job with a new attempt prefix and a new lease. The failed attempt's
  immutable stage record stays and is shown. Late reports with an old lease are refused. A duplicate report records
  nothing twice. A cancelled run publishes nothing. Each of these is now pinned by a test in `JobRecoveryTest`.
- **Authentication.** JWT signature, issuer, audience, expiry, unsigned tokens and the missing org claim
  (`AuthenticationTest`); venue claims and grants (`VenueRoleAuthorizationTest`, `TenantApiIsolationTest`); roles
  (`RoleAuthorizationTest`); service-to-service tokens (`ServiceAuthenticationTest`: workers only, never tenant data;
  mixed service and user roles rejected); public links (`PublicViewerLinkTest`: secrets stored hashed, expiry,
  revocation, read-only, own venue only). The identity provider being unreachable gives 503, not 401
  (`IdentityProviderUnavailableTest`).
- **Uploads.** Claimed type allow-list, size limits, exact part sizes, checksums, content sniffing (Tika), malware
  scanning that quarantines when the scanner is down or disabled and never accepts unscanned files
  (`UploadValidationTest`).
- **Rate limiting.** Exercised through the real filter in `RateLimiterTest`, and enabled in the stack smoke.
- **Privacy.** A privacy-disabled reconstruction is never served to a public link (`PrivacyLifecycleTest`). Skipping
  privacy needs an administrator and is audited (`PipelineControlPlaneTest`).
- **Tenant isolation, now including reconstruction artifacts.** The new `CrossTenantResourceIsolationTest` takes a
  real run through ARTIFACT_GENERATION. Then an administrator of another organization, a manager of another venue of
  the same organization, and a public-viewer token of the other organization's venue each try 15 venue-scoped paths:
  reconstructions and the `.ksplat`, scan versions and their artifacts, captures and media, processing, POIs, search,
  and the dashboard. They also try the id-swap form (the other tenant's ids under their own venue path), uploads,
  processing control, and routes with the other tenant's floor. All are refused, and denials are audited only in the
  caller's organization.

## 2. Verification run on 2026-10-11

| Suite | Command | Result |
|---|---|---|
| API, full | `mvn -B -ntp verify` (Testcontainers Postgres/pgvector and MinIO) | **530 run, 0 failures, 0 errors, 4 skipped** (`ClamAvLiveTest` ×2 and `KeycloakLiveTest` ×2, which are opt-in), BUILD SUCCESS |
| API, targeted | `-Dtest=JobRecoveryTest,CrossTenantResourceIsolationTest,HealthServiceTest,HealthControllerTest,DependenciesHealthIndicatorTest,WorkerUnavailableHealthTest,OpsDashboardApiTest,OpenApiContractTest,MigrationTest,TenantSchemaInvariantTest,PipelineControlPlaneTest,ServiceAuthenticationTest` | all pass (JobRecoveryTest 7, CrossTenantResourceIsolationTest 1, PipelineControlPlaneTest 20, …) |
| OpenAPI contract | regenerated with `-Dchaya.openapi.write=true`; additive only (`worker`, `vision`, `Processing.liveWorkers/staleRunningJobs/unservedStages`, `Jobs.queue`, `JobQueue`, `WorkerStatus`) | `OpenApiContractTest` passes |
| Web | `npm run lint`, `npm run typecheck`, `npm test`, `npm run build` | clean, clean, 215 passed, built |
| Web e2e | `npx playwright test e2e/ops.spec.ts` | 5 passed (real Chromium; the API is mocked; sign-in is the real PKCE flow against a mocked OIDC provider) |
| Stack smoke + restore drill | `scripts/ci/stack-smoke.sh` (real API image built from this tree, Postgres, MinIO, ClamAV, Keycloak, real worker code) | passed. Drill: DB 3 s, objects 4 s, 19/19 objects verified, API booted in 23 s, nothing left behind |
| Drill negative tests | `scripts/backup/test-restore-drill.sh` | 7 passed |
| Deploy control flow | `scripts/deploy/test-deploy.sh` | 26 passed (8 failed before the O1 fix) |
| Shell | `shellcheck -S warning` on `infra/deploy`, `scripts/{backup,deploy,ci}` | clean |
| Alert rules | `promtool check rules` (prom/prometheus v3.5.0) | 15 rules, OK |

## 3. External prerequisites and what remains unverified

- **A real deploy host.** `deploy.sh` (including the new rehearsal step and the fixed rollback) has run only against
  stubs. Run one deliberate failed deploy on staging and watch the rollback.
- **The `ops` workflow on GitHub.** Its steps were run locally; the workflow file itself has not run in Actions. Add
  `ops` to the required checks in branch protection.
- **Production-sized restore timings.** The drill measured a 268 KB dump and 19 objects. Recovery time on real data
  is unknown until the drill runs on the production host. Keycloak's database and off-site copies are still outside
  the backup scripts (docs/backup-recovery.md).
- **Monitoring.** The two new alerts pass promtool but have not fired against a live Prometheus. No Alertmanager is
  configured, so nobody is paged.
- **Real infrastructure cost.** No billing or pricing source exists. Cost stays "unavailable", by design. Recording it
  needs a measured source, such as cloud billing export or metered GPU hours.
- **GPU workers.** These are unchanged: baseline F1 still holds. Health now names the gap directly
  (`unservedStages: ["POSE_ESTIMATION"]`) instead of reporting a 15-minute stall.
- **The dashboard against a running stack.** It was tested in a real browser with a mocked API only.
- **RUNNING jobs with no lease.** Only tests create them (`ProcessingJobRepository.start`); production claims always
  set a lease. The enforcer does not settle such a row; a schema constraint would need the tests that insert them
  changed first.

## 4. Order of the last runs

The full `mvn verify` (530 tests) ran before the enforcer's `failDirectly` fallback (O4) was added. After that change,
`JobRecoveryTest`, `PipelineControlPlaneTest`, `PrivacyLifecycleTest` and `ArtifactIntegrityTest` were run again: 38 run,
0 failures. These are the classes that drive the lease enforcer or the report path. The stack smoke (with the restore
drill) also ran before that change; the fallback is not on its path. Nothing else changed afterwards except
documentation.
