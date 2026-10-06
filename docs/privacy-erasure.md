# Data erasure and PII staging lifecycle

Review findings E-1 (no data erasure) and S-7 (PII staging survives failed runs). Code: `dev.chaya.api.erasure`
(`ErasureService`, `ErasureObjectPurger`, `ErasureSweeper`, `ErasureController`), `PipelineService#sweepPiiStaging`,
migration `V30__data_erasure.sql`, the worker's `Orchestrator`. Tests: `ErasureTest`, `PrivacyLifecycleTest`, and the
worker's `test_stages_and_orchestrator.py`.

## 1. What can be erased

| Request | Who | What goes |
|---|---|---|
| `DELETE /api/v1/venues/{v}/captures/{c}[?cascade=true]` | org admin, or the venue's manager | The capture and everything derived from it (section 3). |
| `DELETE /api/v1/venues/{v}/scan-versions/{sv}[?cascade=true]` | org admin, or the venue's manager | The version, the run that produced it and its outputs, and every version derived from it. The capture it was made from (raw media) is kept: erase the capture to remove that too. |
| `DELETE /api/v1/venues/{v}` | org admin | Everything the venue holds. The venue row stays as a scrubbed tombstone (`name = 'erased'`, `deleted_at` set), because the audit log refers to it. It is 404 everywhere afterwards. |
| `GET /api/v1/erasures/{id}`, `GET /api/v1/erasures` | the requester, or an org admin (list: admin) | The erasure record. |

Answers: **200** with the erasure record when every object is already deleted, **202** while some still are
(`status: OBJECTS_PENDING`). The rows are gone in both cases. **403** `ERASURE_NOT_PERMITTED` for operators, viewers and
public links. These refusals are audited as `erasure.<target>` with outcome `DENIED`. **404** for another
organization or a non-member, as everywhere else. **409** `ERASURE_HAS_DEPENDENTS` lists the captures that would be
erased with the target (re-scans built on it). Repeat the request with `cascade=true` to erase them too.

**Idempotent.** Repeating a request for anything an erasure already removed returns that erasure's record. This
includes a capture that was erased as a dependent of another one. It also makes one more attempt at any objects left.
No second audit row is written.

## 2. Where capture data lives (inventory)

"Erased" means deleted, not flagged. Every row listed is hard-deleted, and every object is deleted from storage.

### Object storage (MinIO)

| Location | Content | Erasure |
|---|---|---|
| raw bucket `org/{o}/venue/{v}/capture/{c}/raw/{media}` | uploaded video, photos, metadata (unblurred) | key from `capture_media` + the capture prefix; unfinished multipart uploads under it are aborted |
| derived bucket `org/{o}/venue/{v}/scan/{s}/run/{r}/{STAGE}/attempt-{n}/pii/…` | **temporary frames**: extracted frames, selected frames (unblurred) | registered keys + the scan (or run) prefix |
| derived bucket `…/attempt-{n}/…` | anonymised frames, privacy masks, poses, sparse model, splats, cleaned clouds, KSPLAT viewer asset, manifests, plane model, navmesh, navigation graph, `DETECTED_OBJECTS` (with CLIP crop embeddings), stage logs | registered keys (the worker's original and the sealed copy) + prefix |
| derived bucket `sealed/org/{o}/venue/{v}/…` | the API's verified copy of every registered artifact | registered keys + sealed prefix |
| either bucket, under the prefixes above | objects **no row names**: uploads of a stage that failed, crashed, lost its lease or had its report refused; a sealed copy whose discard did not happen | prefix listing. A prefix is declared empty only after `settle_after` (below). |

There are no thumbnails. Neither the API nor the worker generates any. The viewer streams the KSPLAT itself.

### PostgreSQL

| Table(s) | Content | Capture / version erasure | Venue erasure |
|---|---|---|---|
| `capture_session` | operator subject, device, timings, re-scan region | deleted | deleted |
| `capture_media`, `capture_media_part` | **original filename**, checksums, object key | deleted | deleted |
| `capture_hud_scene`, `capture_hud_pose_sample`, `capture_hud_quality_sample` | operator's walked path and frame-quality numbers | deleted | deleted |
| `scan`, `pipeline_run`, `processing_job`, `pipeline_stage_run`, `processing_artifact` | processing metadata: commands, errors, object keys | deleted | deleted |
| `pii_staging_purge`, `pii_staging_sweep` | purge records | deleted | deleted |
| `scan_version`, `scan_version_artifact` | version lineage, alignment/splice reports, pins | deleted | deleted |
| `coordinate_frame` | calibrations of the erased runs | deleted | deleted |
| `navigation_graph`, `navigation_node`, `navigation_edge` | baked from the reconstruction | deleted | deleted |
| `poi_version` (AUTO_DETECTED) | detector label, bounding box, CLIP **image embedding** of frame crops, text embedding | deleted | deleted |
| `poi_version` (MANUAL) | staff-entered | **kept, detached**: version and frame tags cleared | deleted |
| `poi` | identity | deleted when no version is left (soft-deleted if a floor connection or another graph still names it). A POI an erased re-scan had superseded is **restored**. | deleted |
| `ar_anchor_pose` | poses entered against an erased version or frame | deleted | deleted |
| `ar_anchor` | staff-entered marker | **kept**, unversioned and `UNCALIBRATED` if its pose went | deleted |
| `floor.current_scan_version_id` | the published version | falls back to the nearest surviving FINALIZED ancestor (via `ScanVersionService#promote`, all V28 checks), else unpublished | floor deleted |
| `search_query` | **free-text queries** with actor subject | not capture-derived: kept | deleted |
| `public_viewer_link`, `public_viewer_token` | link and token hashes | kept (venue-scoped). Erased data is unreachable through them (section 6). | deleted |
| `floor`, `space`, `floor_connection` | staff data | kept | deleted |
| `venue` | name, slug | kept | tombstone: name and slug scrubbed, `deleted_at` set |
| `audit_log` | who did what, ids and counts | **kept (append-only)**: section 5 | kept |
| `erasure_request`, `erasure_item`, `erasure_object` | the erasure record: ids, server-generated object keys, counts | written | written |

### Search indexes

Search is PostgreSQL only: the pgvector HNSW index over `poi_version.embedding`, and the trigram GIN index over
`poi_version.label`. There is no external search engine. Index entries point at table rows, and a deleted row is never
returned by a query from the moment the erasure commits. The index entries and the dead tuples are physically
reclaimed by autovacuum (section 7).

### Worker hosts

| Location | Content | Lifecycle |
|---|---|---|
| `WORKER_WORKDIR/{jobId}/` | downloaded inputs, frames, intermediate files (unblurred before privacy) | deleted when the job ends, whatever the outcome (`finally`). New: directories left by a killed process are deleted at worker startup (`Orchestrator#purge_stale_workdirs`). |
| uploads of a job the control plane took back | — | New: a job whose heartbeat says stop (cancelled, lease lost, data erased) **uploads nothing** (`Orchestrator#process`). Before, it uploaded its outputs and then did not report them, which left objects that no row named. |

### Caches

The API caches no capture data. The health check caches only its own status. The HUD broadcaster keeps only
subscriber lists. Redis is provisioned in `infra/docker` but nothing uses it. Artifact downloads carry
`Cache-Control: private, max-age=3600`: no shared cache may store them, but a **viewer's own browser** may keep bytes it
already downloaded for up to an hour (section 7). The web app has no service worker or IndexedDB cache, and the iOS app
has no artifact cache.

## 3. Dependency graph

```
capture_session ──► capture_media ──► capture_media_part          (raw objects, multipart uploads)
      │      └────► capture_hud_{scene,pose_sample,quality_sample}
      ▼
     scan ──► pipeline_run ──► processing_job ──► pipeline_stage_run ──► processing_artifact ──► objects (worker key, sealed key)
      │            │  ▲                                                     │      ├► pii_staging_purge
      │            │  └─ reconstruction_frame_run_id (re-scan runs)        │      ├► scan_version_artifact
      │            ├──► coordinate_frame ──► poi_version / ar_anchor(_pose) / navigation_graph / floor.current frame
      │            └──► pii_staging_sweep, navigation_graph, poi_version (AUTO_DETECTED)
      ▼
 scan_version ──► scan_version (children: re-scans) ──► capture_session.parent_scan_version_id (re-scan captures)
      ├──► scan_version_artifact, navigation_graph ──► navigation_node, navigation_edge
      ├──► poi_version (detected: deleted; manual: detached), poi.superseded_by (restored)
      ├──► ar_anchor_pose (deleted), ar_anchor (detached)
      └──► floor.current_scan_version_id (fall back / unpublish)
```

`ErasureService` computes the closure in a transaction-local temporary table, to a fixed point. A capture brings its
scan. A scan brings its versions and its run. A version brings its child versions, the captures that re-scan it, and
its run. A run brings the runs whose reconstruction frame it is. From those it derives jobs, stage runs, artifacts,
frames, graphs, media, anchor poses and detected POIs. Every rule is restricted to the venue. It then cuts the cycles
(run ⇄ version, version → parent, frame → artifact, re-scan capture → version) and deletes in dependency order. If
anything outside the closure still references a row (a case the rules do not foresee), the foreign key refuses the
delete. The erasure then fails as a whole and nothing is half-deleted.

The schema's immutability guards (artifacts, stage runs, jobs, runs, versions, frames, POI versions, anchor poses,
capture sessions and media, purge records) refuse UPDATE and DELETE unless the transaction-local setting
`chaya.erasure = on` is set. Only `ErasureService` sets it (`ErasureTest` checks the source tree), and only around its
own statements. It is `SET LOCAL`, so it ends with the transaction. INSERT guards always apply. The audit log is never
exempt.

## 4. Phases, retries and settling

1. **Rows**: one transaction. The closure is computed. Every object key and prefix it owns is written to
   `erasure_object`. The rows are deleted. Floors are re-published. The `erasure_request` (status `OBJECTS_PENDING`),
   the `erasure_item` rows and the audit row are written. All of this commits, or none of it does.
2. **Objects**: `ErasureObjectPurger#purge` runs right after commit, on every repeated request, and every minute
   (`ErasureSweeper`, `chaya.erasure.sweep-interval`). A key is done once its delete succeeds. A prefix has its
   multipart uploads aborted and every object under it deleted. It is done only when a listing **after
   `settle_after`** finds it empty. The request becomes `COMPLETED` when nothing is left (audited
   `erasure.completed`). Failures leave it `OBJECTS_PENDING` with `last_error` and an attempt count.

`settle_after` is the erasure time, plus `lease-seconds + deadline-grace-seconds` if a job of the closure was `RUNNING`.
That worker may still be uploading. Its job row is gone, so its next heartbeat answers `keepGoing: false`, it stops
without uploading anything more, and its report is refused. Anything it uploaded in the meantime is under a listed
prefix and is deleted.

Gauge `chaya_erasure_pending_overdue` and alert `ChayaErasureIncomplete` fire when an erasure is still pending an
hour after it could have completed.

## 5. Audit

`erasure.capture`, `erasure.scan_version` and `erasure.venue` (outcome `SUCCESS` or `DENIED`) and `erasure.completed`.
Metadata holds the erasure id, `cascade`, row counts per table, and the ids of floors that were re-published or
unpublished. It never holds file names, device data, labels, query text or object content (`ErasureTest` checks this).
The existing audit rows of the erased data stay. They hold ids, stage names, error codes and actor subjects, not
content (security-hardening finding 14). The audit trail is append-only, so an erasure cannot remove the record that
it happened.

## 6. Public reachability

Public links are venue-scoped, and every read re-checks the database. Once an erasure commits:

- an erased version, its run and their artifacts are 404 to every viewer, including a public-link token that was issued
  before the erasure (`/scan-versions/{sv}/reconstruction`, `/scan-versions/{sv}/artifacts/{kind}`,
  `/reconstructions/{run}/artifacts/{kind}`), and the floor's "latest" reconstruction is the fallback version or
  nothing;
- an erased venue's links and tokens are deleted: the token is 401, the link secret no longer exchanges (404), and the
  venue is 404 even to its own admins.

## 7. PII staging policy (S-7)

Unanonymised frames (`pii/` staging) are deleted:

| When | How |
|---|---|
| PRIVACY_PREPROCESS succeeded | registered PII artifacts purged at once |
| run ends SUCCEEDED, PARTIAL or CANCELLED | registered PII artifacts purged at once |
| run ends FAILED | kept for `chaya.pipeline.pii-staging-retention` (24 h) so it can be retried, then purged by the sweep |
| **new**: any finished run, once the above is due and `lease + grace` has passed | the run's worker and sealed prefixes are **listed**, and every `*/pii/*` object is deleted, including uploads no artifact row names. Recorded in `pii_staging_sweep` and audited as `pipeline.pii_orphans_purged`. A run that is retried and fails again is listed again. |
| capture or venue erased | everything, immediately (sections 3–4) |

Gauge `chaya_pii_staging_unswept_runs`, alert `ChayaPiiStagingUnswept`.

## 8. What cannot participate yet

| Store | Why | Bound / mitigation |
|---|---|---|
| **PostgreSQL dumps** (`$BACKUP_DIR/postgres`) | dated, immutable files | They age out after `BACKUP_KEEP_DAYS` (14 days). **Restoring an older dump brings erased rows back.** After any restore, re-issue every erasure whose `requested_at` is later than the dump. Keep the list from `GET /api/v1/erasures` before restoring, because the restored database does not have it. |
| **MinIO backup mirror** (`$BACKUP_DIR/minio`) | additive mirror | `backup.sh` now deletes every erased key and prefix from the mirror on each run. Copies made before then remain until the next backup run. |
| **Dead tuples and index entries** | PostgreSQL MVCC | Never returned by a query after commit. They are reclaimed by autovacuum, typically within minutes to hours. The bytes may persist in data files and WAL until then. There is no WAL archiving or PITR configured. If one is added, its retention bounds erasure. |
| **Application and worker logs** (Loki, container logs) | not addressable by record | They hold ids, object keys (server-generated) and actor subjects, no media or free text (security-hardening finding 14). Loki retention is 14 days. |
| **A viewer's browser cache** | outside the system | `Cache-Control: private, max-age=3600`: at most one hour, only on a device that already downloaded the bytes. |
| **Audit log** | append-only by design | Keeps ids and actor subjects of erased resources, no content. |
| **Keycloak** (operator and admin accounts) | identity, not capture data | No person-level ("data subject") erasure exists. People who appear in captures cannot be identified by the system (there is no face recognition), so the unit of erasure is the capture. Erasing an *account's* traces (`operator_id`, `created_by`, `search_query.actor_id`, `audit_log.actor_id`) across venues is not implemented. |
| **Search history of a kept venue** | not capture-derived | `search_query` (free text) is erased only with its venue. It still has no retention period (security-hardening finding 15). |
| **Raw captures of kept venues** | retention policy, not erasure | Erasable on request, but there is still no automatic retention job (finding 15). |
| **Worker local disk after a host failure** | a killed worker's directory can only be cleaned by a worker starting on that host | `purge_stale_workdirs` runs at startup. A host that never runs the worker again keeps the directory. Workdirs should be on ephemeral storage. |
| **Object versioning** | not enabled on the buckets | If MinIO versioning or object locking is enabled (docs/backup-recovery.md suggests it as an option), erasure must also delete non-current versions. It does not do so today. |
