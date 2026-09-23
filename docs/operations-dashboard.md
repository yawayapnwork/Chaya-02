# Operations dashboard

For venue staff: what state each venue's reconstruction is in, what processing is doing, and what failed.
Everything shown comes from stored rows or is computed from them at read time. Nothing is estimated, scored or filled in.
When a value does not exist, the API returns null and the UI says so ("never", "—", "Cost data unavailable").

Code: `services/api/.../ops` (`OpsDashboardController`, `OpsDashboardService`, `OpsSection`, `JobActions`, `OpsDtos`);
`apps/web/app/ops/page.tsx`, `apps/web/components/OpsDashboard.tsx`, `apps/web/lib/ops-api.ts`, `apps/web/lib/ops-view.ts`.
Access rules: docs/security.md, "Operations dashboard".

## Sections and their sources

| # | Section | Endpoint | Source |
|---|---|---|---|
| 1 | Venue overview | `ops/overview` | `venue`, counts of `floor`, `capture_session`, live `poi`, `processing_job` by status |
| 2 | Current reconstruction version | `ops/overview` | Per floor: newest FINALIZED `scan_version`; newest run whose ARTIFACT_GENERATION stage succeeded (the viewer's own definition, see `ReconstructionService`) with its `quality` and whether a `.ksplat` exists |
| 3 | Scan freshness | `ops/overview` | Timestamps (below) and their ages against the database clock |
| 4 | Coverage gaps | `ops/coverage` | Floors without a reconstruction or finalized version; measured coverage of the newest capture with a HUD room outline, recomputed by `CaptureHudService` (the capture planner) |
| 5 | Processing jobs | `ops/jobs` | `processing_job`, counts for all five states, newest 50 (filterable) |
| 6 | Processing failures | `ops/failures` | FAILED `pipeline_run`s; FAILED `pipeline_stage_run` records (immutable, so they survive retries); FAILED legacy jobs without a run |
| 7 | Storage, artifacts, cost | `ops/storage` | `capture_media` and `processing_artifact` sizes as recorded; `pipeline_stage_run` durations; cost (see below) |
| 8 | POI and search analytics | `ops/search-analytics` | `search_query` (written by every search); latest `poi_version` per live POI |
| 9 | Re-scan history | `ops/rescans` | `capture_session` rows with `parent_scan_version_id`, their run and resulting `scan_version` |
| 10 | Audit activity | `ops/audit` | `audit_log` rows of the venue |

## Freshness

No freshness score exists. Per floor the dashboard shows timestamps and their ages:

- **Last full-floor capture**: the newest `COALESCE(ended_at, started_at)` of a non-re-scan capture on the floor that
  produced a reconstruction. Capture times come from the capturing device. A device clock that runs ahead of the server
  gives a negative age. The age is shown that way and not corrected.
- **Last reconstruction**: when ARTIFACT_GENERATION last succeeded for the floor.
- **Last region re-scan**: the newest capture time of a re-scan whose version was finalized. It refreshes only its region.
- **Last capture started**: any capture on the floor, processed or not.

Floors are sorted oldest-first by last full-floor capture. Floors that were never captured sort first.

## Job states and retry availability

The job view shows Queued, Running, Completed (`SUCCEEDED`), Failed and Cancelled. For each job and failure the server
computes whether a retry or cancel would be accepted now, and through which existing endpoint (`JobActions`, unit tested):

- A job in a pipeline run is controlled through its run (`POST .../captures/{c}/processing/retry|cancel`). A retry is
  available only if the run is FAILED, the job belongs to the stage the run failed at, the job is that stage's latest,
  and `retry_count < max_retries`.
- A legacy job without a run is controlled directly (`POST .../jobs/{j}/retry|cancel`).

When an action is unavailable, the reason is shown. Retry and cancel buttons appear only for roles that may control
processing. The endpoints check everything again.

## Search analytics

The dashboard shows query count, zero-result count and rate, average and p95 latency, distinct searchers, the most
searched terms and the zero-result terms, over 7, 30, 90 or 365 days. The search log records the normalized query text,
the result count and the latency. It does not record which POIs were returned. "Most searched objects" is therefore shown
as the most searched **terms**, and the UI states this limit. Logging returned POI ids would be needed to rank objects.

## Cost

The system has no cost source: no pricing, billing or GPU-hour rate is recorded anywhere. `ops/storage` therefore always
returns `cost: {available: false, amount: null, currency: null, reason}`, and the UI shows "Cost data unavailable".
Measured worker time per stage (the sum of `pipeline_stage_run` durations) is shown beside it and is labelled as time,
not cost. When a real cost source is added, `OpsDashboardService#costUnavailable` is the single place that changes.

## Storage figures

Sizes are those recorded when each object was verified against object storage (size and checksum at registration). They
are not a live bucket listing. PII staging artifacts are counted separately: privacy preprocessing deletes their objects
and keeps the records. A `pipeline.pii_purge_incomplete` audit event for the venue shows as a warning.

## UI states

Every section independently shows loading, empty (with a specific message), error (with the server's message and a
retry), unauthorized (the roles the section needs), or signed-out. A section the caller's role may not read is never
requested. The page also handles signed out, no venue role (403 on `/venues`), no venues assigned, and a venue that is
not visible (404). Refresh is manual, with optional 30 s auto-refresh. During a refresh the previous data stays visible.

## Verification

- `JobActionsTest`: retry/cancel rules, coverage gap derivation, cost never invented, ages. A pure unit test.
- `OpsDashboardApiTest`: the role matrix for every section; tenant isolation (other venue or org: 404; no token: 401;
  service token: 403); job counts and retry availability from real rows; failures from stage records; search aggregates;
  artifact sums with cost unavailable; null freshness before any reconstruction; venue-scoped audit with admin-only
  denials. Runs on Testcontainers Postgres and MinIO, and is skipped without Docker.
- `apps/web/lib/ops-view.test.ts`: presentation rules. Missing values are never shown as 0, only FINAL is labelled
  final, and HTTP statuses map to section states.
- Not verified: the dashboard UI in a real browser against a running stack.
