# Troubleshooting: the first failing pipeline stage

A run stops at the **first** stage that fails; later stages are never queued, and earlier artifacts are kept. So
troubleshooting is always: find that stage, read its code, fix the prerequisite, retry. Nothing here bypasses a check or
injects an artifact. Written 2026-10-12 against `40beef5`; the codes come from `chaya_worker` and `dev.chaya.api`.

On today's infrastructure the expected first failure is known in advance (PROJECT_PLAN.md, "First stage a real venue run
cannot pass"): **POSE_ESTIMATION** has no worker unless a GPU worker is attached, and **SPLAT_RECONSTRUCTION** needs
CUDA, which no available host has.

## 1. Is anything serving the stage?

```
curl -s https://<api>/api/v1/health        # local: http://localhost:8080/api/v1/health
```

| What health says | Meaning | Fix |
|---|---|---|
| `worker: DOWN` | No worker has polled or heartbeated within 3 minutes (`worker_presence`) | Start or fix the worker: `docker compose logs worker`; `docker compose exec worker python -m chaya_worker.liveness` (exit 0 = it has reached the API recently). Bad client secret or MinIO account shows here. |
| `processing.status: NO_WORKER`, `processing.unservedStages: [...]` | Claimable jobs wait for a stage that no live worker **offers**. A worker offers only the configured stages it can execute (docs/pipeline.md, "Which worker serves which stage"), so a GPU worker without its GPU, or before its model weights are fetched, does not serve those stages | On the worker: `python -m chaya_worker.capabilities` prints each refused stage and why (exit 1 if any is refused); for SPLAT_RECONSTRUCTION, `python -m chaya_worker.splat_preflight`. Then attach or fix a worker for that stage. `POSE_ESTIMATION` and the other GPU stages need the GPU worker (DEPLOYMENT.md §10). A legacy run-less stage (`MEDIA_FILTER`, `SPLAT_TRAINING`, …) can never be served (ARCHITECTURE.md §10): cancel it. |
| `processing.status: STALLED` | Work waited 15 minutes while nothing ran | Check worker logs; check the job's lease in the dashboard |
| `processing.staleRunningJobs > 0` | RUNNING jobs whose lease expired and that the enforcer has not failed yet | Normally cleared within 30 s; if it persists, the enforcer is failing (API log; alert `ChayaJobsStranded`) |
| `vision: DOWN` | Search falls back to lexical matching; new POI embeddings wait | Not a pipeline failure. Check the vision container (`/health/ready`; the first start downloads CLIP). |

## 2. Which stage failed, and why?

```
curl -s -H "Authorization: Bearer $TOKEN" https://<api>/api/v1/venues/<v>/captures/<c>/processing
```

Read `run.status`, `run.failureStage`, `run.failureCode`, then that stage's `lastRun`: `errorCode`, `errorMessage`,
`errorDetails` (structured; for a missing dependency it lists what is missing), the external `command`, `exitStatus`, and
the stdout/stderr log artifacts. The same is in the web app (`/capture` → processing panel, with an explanation of each
code) and in `/ops` (failures, jobs, workers). Worker logs are JSON lines with `job_id`, `run_id` and `stage`.

## 3. Codes by stage

| Stage | Code | Cause | Fix |
|---|---|---|---|
| any | `DEPENDENCY_UNAVAILABLE` | A tool, library, model or GPU is missing on the worker that claimed the job. `errorDetails.missing` names it. | Run the stage on a worker that has it (`WORKER_STAGES`), then retry. On a CPU worker this is the expected result for POSE_ESTIMATION and later GPU stages. |
| any | `WORKER_LOST` | The lease expired (worker died, OOM-killed, network) | Check the worker host. A CPU COLMAP worker without `COLMAP_NUM_THREADS` is OOM-killed on many-core hosts (DEPLOYMENT.md §10). Retry. |
| any | `TIME_LIMIT_EXCEEDED` | The run's time budget ran out. If a reconstruction existed, the run is `PARTIAL`, never final. | Retry with a larger `timeBudgetSeconds`, or a faster worker |
| any | `REPORT_REJECTED`, `ARTIFACT_CHECKSUM_MISMATCH`, `ARTIFACT_SIZE_MISMATCH`, `ARTIFACT_MISSING` | The API refused the stage report: object missing, wrong size or hash, or a key outside the attempt's prefix | Usually storage trouble between worker and MinIO; check the worker's MinIO account and network. Retry. |
| any | `STAGE_OUTPUT_MISSING`, `ARTIFACT_UNREADABLE`, `DETECTED_OBJECTS_INVALID`, `NAVIGATION_GRAPH_INVALID` | The stage reported success without a required output, or with an unreadable one (`packages/contracts/pipeline/stage-artifacts.json`) | A worker defect or version mismatch between worker and API. Check the worker image version. |
| INPUT_VALIDATION | `INPUT_INVALID`, `INPUT_INSUFFICIENT` | A file fails its checksum, does not decode, or there is too little media | Re-upload; check the capture's media in `/capture` |
| FFMPEG_PREPROCESS | `FFMPEG_FAILED`, `NO_FRAMES` | FFmpeg could not extract frames | Check the stderr log artifact; re-encode the source |
| FRAME_QUALITY_FILTER | `INSUFFICIENT_QUALITY_FRAMES` | Too many frames blurred, badly exposed or duplicated | Recapture: slower, better lit (the capture HUD flags these live) |
| PRIVACY_PREPROCESS | `PRIVACY_VERIFICATION_FAILED`, `PRIVACY_FRAME_UNREADABLE`, `PRIVACY_WRITE_FAILED` | Anonymisation could not be verified (fails closed) | Check the privacy report; an admin may run without privacy (audited; never public) |
| POSE_ESTIMATION | `FEATURE_EXTRACTION_FAILED`, `FEATURE_MATCHING_FAILED`, `MAPPING_FAILED`, `MODEL_CONVERSION_FAILED`, `POSE_ESTIMATION_INSUFFICIENT` | COLMAP could not register enough frames | More overlap and texture; check the COLMAP log artifact. `POSE_ESTIMATION_INSUFFICIENT` means too few cameras registered. |
| SPLAT_RECONSTRUCTION | `DEPENDENCY_UNAVAILABLE` with `errorDetails.preflight` | Only from a worker in the diagnostic mode (`WORKER_OFFER_UNAVAILABLE_STAGES`), or one whose environment changed after its last capability check. The preflight codes say what: `GPU_NOT_PRESENT` (no NVIDIA device visible: CPU host, or no `--gpus all`), `CUDA_DRIVER_INCOMPATIBLE` (driver < 560.28 for CUDA 12.6), `CUDA_UNAVAILABLE` (GPU visible but unusable to torch: toolkit, `CUDA_VISIBLE_DEVICES`), `GPU_MEMORY_INSUFFICIENT`, `GPU_CAPABILITY_UNSUPPORTED`, `TORCH_WITHOUT_CUDA`, `TOOL_MISSING` | Each problem carries its `fix`. The supported matrix is in docs/pipeline.md, "Supported hardware and software"; the full check on a GPU host is `bash scripts/gpu/acceptance.sh` |
| SPLAT_RECONSTRUCTION | `GSPLAT_CUDA_BACKEND_MISSING`, `GSPLAT_INCOMPATIBLE` | gsplat without compiled CUDA kernels (the PyPI wheel), or drift from the image's version lock | Use the `Dockerfile.gpu` image; run `python -m chaya_worker.splat_preflight` in it (exit 0 = can train) |
| SPLAT_RECONSTRUCTION | `SPLAT_GPU_OUT_OF_MEMORY`, `GPU_RUNTIME_ERROR` | CUDA OOM or runtime fault | Lower the `GSPLAT_*` caps or use a larger GPU, then retry. A retry trains again: checkpoints are recorded, but nothing resumes them automatically (docs/pipeline.md, "Splat training"). |
| SPLAT_RECONSTRUCTION | `SPLAT_IO_FAILED` | A file operation during training failed (not a checkpoint write: those are recorded in the report's `checkpoint`, and training continues) | Check `details.errno` / `details.filename`, the worker's free disk space and permissions, then retry |
| SPLAT_RECONSTRUCTION | `SPLAT_QUALITY_INSUFFICIENT`, `SPLAT_QUALITY_UNVERIFIED` | Training completed, but the cloud renders the held-out views no better than its SfM seed, or its loss did not fall (or no valid measurement could be made). Nothing is published | Read `quality.reasons` and the per-view PSNR in `splat-training-report.json`. Capture again with more overlap, or raise `GSPLAT_ITERATIONS`. Don't lower the `GSPLAT_QUALITY_*` thresholds to get it published |
| SPLAT_RECONSTRUCTION | `CHECKPOINT_CORRUPT`, `CHECKPOINT_MISMATCH`, `CONFIG_INVALID` | A checkpoint given as input does not fit this run, or the configuration is invalid | Fix the configuration; run without the checkpoint input |
| GEOMETRIC_CLEANUP | `CLEANUP_EMPTIED_SCENE` | Cleanup would remove everything | Check the splat and the `CLEANUP_*` settings |
| SEMANTIC_INDEXING, NAVIGATION_BAKING, REGION_ALIGNMENT, REGION_SPLICE | `NOT_CALIBRATED` | No calibrated coordinate frame for this reconstruction; expected on every first full run | Record measured references and calibrate (`/capture` → calibration, or `POST …/captures/{c}/calibration`; docs/capture-calibration.md), then `POST …/processing/retry` |
| NAVIGATION_BAKING | `NAVMESH_TOOL_UNAVAILABLE` | No chaya-navmesh binary on the worker | Use the CPU worker image (it builds it) or set `CHAYA_NAVMESH_BIN` |
| NAVIGATION_BAKING | `NAVMESH_LABELS_UNAVAILABLE`, `NAVMESH_BUILD_FAILED`, `NAVMESH_REGION_INCONSISTENT` | No semantic labels for the baked splat; Recast failed; a re-scan's navmesh disagrees with the parent outside the region | See docs/navigation.md; the baking report artifact names the cause |
| REGION_ALIGNMENT, REGION_SPLICE | `ALIGNMENT_REJECTED`, `SPLICE_REJECTED` | The re-scan failed the physical quality gates; the parent version is untouched | Recapture the region with more overlap, or survey control points (docs/rescan.md, "QUALITY GATES") |

## 4. After the last stage: the version is not published

A run can succeed and its version still stay DRAFT if publication refuses (the run's result is kept):

| Code | Fix |
|---|---|
| `NOT_CALIBRATED`, `VERSION_FRAME_MISMATCH` | Calibrate the run's own reconstruction, then `POST …/scan-versions/finalize-current` |
| `VERSION_INCOMPLETE`, `VERSION_INCONSISTENT` | A required artifact (`KSPLAT`, the cloud, or a complete navigation set) is missing or mismatched; check the run's artifacts |
| `PARENT_NOT_CURRENT` | A re-scan's parent is no longer the floor's current version; re-scan from the current one |
| `DETECTIONS_UNREADABLE` | `DETECTED_OBJECTS` could not be read back; retry SEMANTIC_INDEXING |

Downstream symptoms: the viewer lists only FINALIZED versions; routing answers `NAVMESH_NOT_READY` until a version with a
navmesh is published; version-scoped reads of a DRAFT answer `VERSION_NOT_FINALIZED`.

## 5. Retry

```
curl -s -X POST -H "Authorization: Bearer $TOKEN" https://<api>/api/v1/venues/<v>/captures/<c>/processing/retry
```

Or "Retry" in `/capture` or `/ops`. A retry re-queues only the failed stage (attempt n+1, bounded by `max_retries`,
default 3); earlier successful stages are not repeated. The failed attempt's record stays visible.

## 6. Deployment, infrastructure and backup failures

### Deploy script failures (`deploy.sh`)

| Symptom | Cause | Remedy |
|---|---|---|
| Step 1 exits non-zero: missing variable | A required variable in `/srv/chaya/.env` is missing or empty | Check `/srv/chaya/.env` against `infra/deploy/.env.production.example`. Secrets are never printed. |
| Step 4 stops: `rehearsal-failed` | Flyway migration or Hibernate mapping failed on a restored copy of production data | The live containers were untouched. Review `.deploy-logs/<time>-<version>.log`. Fix the migration in the release, or set `DEPLOY_SKIP_REHEARSAL=1` only for emergency non-schema rollouts. |
| Step 6/8 fails: automatic rollback triggered | Web, API, or worker failed health checks (`/actuator/health/readiness` or `/api/health`) | Rollback automatically restored the previous image tag in `.deployed-version`. Check `.deploy-logs/` for the unhealthy container's log. If the schema migrated before failure, restore the pre-deploy dump with `scripts/backup/restore.sh <dump>`. |
| `keycloak-configure.sh` exits 1: `Invalid user credentials` | The bootstrap admin was deleted and `CHAYA_DEPLOY_CLIENT_SECRET` is misconfigured | Ensure `CHAYA_DEPLOY_CLIENT_SECRET` in `.env` matches client `chaya-deploy` in Keycloak realm `chaya`. To recover bootstrap admin: run `kc.sh bootstrap-admin user` in the Keycloak container. |

### Upload and malware scanner degradation

| Symptom | Cause | Remedy |
|---|---|---|
| Upload status is `QUARANTINED`, code `SCANNER_UNAVAILABLE` | ClamAV scanner container is stopped, initializing signatures, or disabled (`CLAMAV_ENABLED=false`) | Uploads are quarantined to prevent unvalidated file intake. Completing capture responds `409 UPLOADS_NOT_SETTLED`. Check ClamAV health (`clamdcheck.sh`). Once scanner is UP, complete upload to trigger automatic re-validation. |
| Upload status is `REJECTED`, code `MALWARE_DETECTED` | ClamAV detected infected or malicious payload (e.g. EICAR test string) | Object is immediately deleted from S3 raw bucket. Audit event `media.reject` is logged. Client must upload clean source media. |

### Restore drill and backup failures (`restore-drill.sh`)

| Symptom | Cause | Remedy |
|---|---|---|
| `checksum mismatch` | Dump file does not match `.dump.sha256` | Backup file is corrupted or was edited. Do not restore. Use the previous intact daily dump. |
| `MISSING OR CORRUPT <key>` | Database references an accepted medium or non-PII artifact not present in MinIO mirror or with mismatched SHA-256 | Verify that the MinIO mirror completed without errors. Ensure the read-only backup account has permission across all buckets. |
| `rm: Permission denied` on backup cleanup | Mirror files were created by root rather than the backup runner | `mc_run` in `scripts/backup/common.sh` maps container UID/GID to the invoking user (`$(id -u):$(id -g)`). Ensure backup scripts run as the designated host deploy user. |

