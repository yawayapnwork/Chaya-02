# Real-venue acceptance

How to prove the capture-to-digital-twin workflow on a **real indoor venue**, and what the repository's tests do and do
not prove without one.

**Status (2026-10-10): never run.** No measured indoor capture exists. No NVIDIA GPU was available, so the GPU worker
image (`services/reconstruction/Dockerfile.gpu`) has not been built and SPLAT_RECONSTRUCTION has never trained on CUDA.
Everything below the GPU boundary is verified with fixtures only.

## Fixture tests and real-input tests are separate

| Kind | Where | What it proves | What it does not prove |
|---|---|---|---|
| Control-plane contract (fixture worker) | `services/api` `PipelineOutputContractTest`, `PipelineControlPlaneTest`, `ScanVersionPublicationTest`, … via `PipelineTestSupport` | Every stage's required outputs (`packages/contracts/pipeline/stage-artifacts.json`); the failures, retries and publication rules; search and routing on what a run published. The fixture worker publishes the **real kinds** and the real shapes of the documents the control plane reads. Like the real worker, it fails the metric stages with `NOT_CALIBRATED` without a frame. | Its bytes are placeholders, and its navmesh is the synthetic Recast venue. It is not a reconstruction. |
| Worker contract | `services/reconstruction` `tests/unit/test_stage_artifact_contract.py` | What each stage reads and writes, from its source; every plan is satisfiable (privacy on or off, full or incremental); every planned stage is claimed by a shipped worker. | Nothing about a stage's output quality. |
| Recast fixtures | `packages/contracts/fixtures/navmesh`, `tests/navmesh` | The real Recast/Detour tool on a synthetic venue. | A real venue's walkable surface. |
| Viewer fixtures | `packages/contracts/fixtures/ksplat`, `apps/web/lib/ksplat-compat.test.ts`, `e2e/ksplat-viewer.spec.ts` | The encoder, checked against the pinned viewer library, and rendering in a browser. | A real `.ksplat`. |
| CPU splat smoke | `tests/smoke/test_splat_artifact_smoke.py` | Training plumbing (CPU **test renderer**, not gsplat), then ARTIFACT_GENERATION, then the viewer library. | gsplat training. |
| GPU acceptance | `tests/gpu/test_splat_acceptance.py` | Real gsplat on CUDA, on a synthetic or `CHAYA_SPLAT_DATASET` project. | **Not run** here. |
| **Real-venue acceptance** | `scripts/acceptance/real_venue.py` | The whole workflow on your capture, through the production API. | **Not run**: no capture, no GPU. |

## The run

Prerequisites:

- A deployed stack (`infra/deploy`), with its CPU worker claiming the stages before privacy, plus REGION_SPLICE,
  ARTIFACT_GENERATION and NAVIGATION_BAKING.
- At least one GPU worker built from `services/reconstruction/Dockerfile.gpu`. It claims every other stage. Its
  `/models` volume is filled once (`python /opt/chaya/fetch_models.py`), and `python -m chaya_worker.splat_preflight`
  in it prints `"ok": true`.
- A venue and floor, and an operator's token. A venue manager's token also shows the job leases.
- The capture directory: the walkthrough video and/or photos, plus a camera-calibration metadata JSON if the intrinsics
  are known (`docs/capture-ingestion.md`).
- `measurements.json`: at least two tape-measured distances or three surveyed control points. Each measured point is
  marked in at least two photos or video frames. The bodies are `POST …/captures/{id}/measurements`'s
  (`docs/capture-calibration.md`), with `"media": "<file name>"` in each observation instead of a `mediaId`.

```
python scripts/acceptance/real_venue.py --api https://api.<domain> --token-file operator.token \
  --venue <venueId> --floor <floorId> --media ./capture --measurements measurements.json \
  --search "exit sign" --search "reception desk" --route-from 1.0,2.0,0.0 --route-to "exit sign" \
  --viewer-check --out ./acceptance-<date>
```

The run stops with exit status **3** once POSE_ESTIMATION has a reconstruction and SEMANTIC_INDEXING, the first metric
stage, has failed `NOT_CALIBRATED`. `calibration-needed.json` then lists every measured point. Open the run's
reconstruction, read off each point's reconstruction coordinates, and write `resolved.json`:

```json
{"points": {"door 2.14 width": {"A": [x, y, z], "B": [x, y, z]}, "corridor": {"A": [...], "B": [...]}},
 "gravity": {"source": "RECONSTRUCTED_FLOOR_PLANE"}}
```

Then resume:

```
python scripts/acceptance/real_venue.py … --out ./acceptance-<date> --resume --resolved-points resolved.json
```

The script never retries a failed stage on its own. `--retry-failed N` allows N retries of retryable failures. It never
bypasses a refusal, never injects an artifact, and never publishes anything itself.

## What `report.json` records

| Section | Content |
|---|---|
| `media` | each file's SHA-256, size, server verdict and decoded pixel size; whether its upload was resumed |
| `measurements`, `calibrationEvidence`, `calibration` | what was recorded; the evidence state; the accepted frame or the refusal |
| `timeline` | every change of run and stage state, with each job's worker id, lease expiry and retry count |
| `failure` | the stage, code and message where the run stopped, if it did |
| `artifacts` | every stage's artifacts: kind, tenant-scoped key (this venue, this run), content type, size, SHA-256 |
| `downloads`, `manifest` | the viewer's artifacts as served by the API, re-hashed; the manifest's reference to the served `.ksplat` |
| `publication`, `reconstruction` | the run is the floor's current version, and its frame is canonical |
| `pois`, `search` | POIs of the version; for each `--search`, the match type (`semantic` or `lexical_fallback`) and the detected objects found |
| `routes` | STANDARD and STEP_FREE routes to a detected POI, and the navmesh they ran on (it must be this run's NAVMESH) |
| `viewerCheck` | the served `.ksplat` loaded by the pinned viewer library |

`result` is `VERIFIED` only if nothing in `problems` is listed.

## The boundary that remains unverified

1. **SPLAT_RECONSTRUCTION on CUDA.** gsplat 1.5.3 training has never executed. Run `tests/gpu/test_splat_acceptance.py`
   on a GPU worker first (`docs/pipeline.md`, "GPU acceptance").
2. **The GPU worker image.** It is not built: there was no disk space for a CUDA build, and no GPU. `docker build --check`
   passes, and its full Python dependency closure resolves.
3. **SEMANTIC_SEGMENTATION, GEOMETRIC_CLEANUP, PLANE_FITTING, REGION_ALIGNMENT, SEMANTIC_INDEXING with their real models**
   (Open3D, SegFormer, Grounding DINO, CLIP). The model revisions are pinned in the image, but have not been loaded
   from `/models`.
4. **Calibration on a real reconstruction.** The measured points have to be picked by hand: the marked pixels are not
   triangulated automatically (`docs/capture-calibration.md`).
5. **Search relevance and route quality on a real venue.** They have been tested on fixtures only.
6. **The acceptance script itself.** It has been checked statically only (lint, argument parsing). The local stack could
   not be brought up next to the other stacks on this workstation: 3.5 GB of disk was left.
