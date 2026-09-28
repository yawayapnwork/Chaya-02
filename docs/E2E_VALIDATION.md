# Chaya 02: end-to-end validation

## 0. Physical pipeline validation, 2026-09-28

**Goal:** prove capture → FFmpeg → COLMAP/GLOMAP → canonical metric frame → Gaussian/geometry → Recast navmesh →
route. The route must come from the processed geometry and the real navmesh, with nothing inserted by hand.

**Verdict: NOT SUCCESSFUL. The run is INCOMPLETE.**

- **5 of 12 stages ran, and all 5 SUCCEEDED** on real media. They run up to real COLMAP pose estimation.
- **The run stops at `SPLAT_RECONSTRUCTION`** with `DEPENDENCY_UNAVAILABLE`: torch, gsplat and cuda are missing,
  because this host has no CUDA GPU.
- **The 6 later stages were not run.** They include the Recast navmesh; no route exists.
- **The canonical metric frame could not be established either,** independently of the GPU: no measured distances
  or surveyed control points exist for any capture available here (0.4).
- **PROJECT_PLAN M9 (splat training) and M13 (navmesh & routing) stay not Done.**

**Code under test:** commit `4a537d1` plus the new driver `scripts/e2e/physical_pipeline.py`. No product code was
changed. No implementation error surfaced in the stages that ran, so there was nothing to fix. Worker suite at this
commit: `pytest` 247 passed, 13 skipped (Python 3.12 venv).

**Evidence:**

- `docs/e2e-evidence/physical-pipeline.json`: per-stage records.
- `docs/e2e-evidence/physical-run-log.txt`: console and structured worker log.
- `docs/e2e-evidence/physical-artifacts/<STAGE>/`: every non-PII JSON output, plus stdout/stderr logs.

Frame archives and the binary sparse model are identified by SHA-256 in the JSON. They are not committed, because of
the photographs' copyright.

### 0.1 Capture dataset

**The repository contains no real capture dataset.** Its only media are the fixture navmesh and screenshots.

The run therefore uses the same real, external capture as section 3: 11 photographs of the Château de Sceaux, from
OpenMVG's public structure-from-motion set ("Copyright 2012 Pierre MOULON").

- They are downloaded at test time. Nothing was generated or synthesised.
- The encoded video is byte-identical to the 09-24 run: `f960743d…21fb`.
- It is an **outdoor façade, not an indoor venue**. Even with a GPU, a navmesh from it would say little about
  indoor routing (0.4).

### 0.2 Commands

```bash
# Images from the working tree: the production CPU worker, plus the test-only CPU-COLMAP layer (section 5.2)
docker build -t chaya-physical-worker:base services/reconstruction
docker build -t chaya-physical-worker:colmap -f infra/ci/worker-colmap.Dockerfile \
  --build-context base=docker-image://chaya-physical-worker:base infra/ci
#   -> COLMAP 3.8 (built without CUDA), FFmpeg 5.1.9, chaya-navmesh 1.0.0 / recastnavigation 1.6.0; no GLOMAP

# Capture: 11 real photographs -> H.264, exactly as in section 2 (ffmpeg from the worker image)
for i in $(seq 7100 7110); do
  curl -O https://raw.githubusercontent.com/openMVG/ImageDataset_SceauxCastle/master/images/100_$i.JPG
done
ffmpeg -framerate 2 -pattern_type glob -i "*.JPG" -vf scale=1600:-2 -c:v libx264 -pix_fmt yuv420p -crf 18 -g 1 sceaux-castle.mp4
#   -> 4,917,797 bytes, 11 frames 1600x1202, 5.5 s, sha256 f960743de6220499436815d55489bd052323d67160367c2e41301cc8cb6921fb

# The full-venue plan through the worker's own claim loop: default settings, plus the documented CPU thread bound (6.2)
docker run --rm --memory 2g -e COLMAP_NUM_THREADS=2 -u 0 \
  -v <repo>/scripts:/repo/scripts:ro -v <capture dir>:/cap:ro -v <out>:/out chaya-physical-worker:colmap \
  python /repo/scripts/e2e/physical_pipeline.py --media /cap/sceaux-castle.mp4 --out /out/physical-pipeline.json --store /tmp/run
#   -> exit 2 (INCOMPLETE), 2026-09-28 14:27:35 to 14:29:05 +05:30
```

**How the driver works:**

- It runs the real `Orchestrator.run_once` claim loop with the real `Toolchain` and every stage class, unmodified.
- The control plane is stood in for by a sequencer. Each stage gets the inputs `PipelineService#inputs` would give it:
  - raw media to the first two stages;
  - earlier successful non-log outputs after that;
  - nothing PII-flagged after `PRIVACY_PREPROCESS`.
- It passes the ACTIVE `coordinateFrame`. There is none, because nothing was calibrated.
- It stops at the first failure, as `PipelineService#advance` does.
- Every output is re-read from storage and re-hashed.
- **Nothing is inserted:** no splat, plane model, graph, POI or frame.

The full control-plane path (API, MinIO, Keycloak, two workers) produced the same stop at the same stage in the
09-24 run (section 4.2, steps 9.1–10).

### 0.3 Per-stage record

Inputs are the artifacts handed to the stage. Their checksums are in `physical-pipeline.json`.

**Status of every output:** every output below was stored, and its size and SHA-256 matched the stored bytes
(`verified: true`).

**Coordinate frame:** the work order's `coordinateFrame` was `null` for every stage.

**Configuration:** each stage's full configuration snapshot and every command line are recorded in
`physical-pipeline.json` (`configuration`).

| # | Stage | Input | Output (bytes, SHA-256) | Status | Duration | Configuration | Output coordinate frame | Failure reason |
|---|---|---|---|---|---:|---|---|---|
| 1 | INPUT_VALIDATION | RAW_VIDEO `f960743d…` | INPUT_REPORT (400, `9afdd8f7…7b23`) | SUCCEEDED | 0.78 s | ffprobe | none (media) | |
| 2 | FFMPEG_PREPROCESS | RAW_VIDEO, INPUT_REPORT | FRAME_ARCHIVE (3,409,920, `1c8a46ad…aaa3`, PII); FRAME_MANIFEST (416, `328a0b00…3b10`) | SUCCEEDED | 0.61 s | `fps=2`, max height 1080; 11 frames | image pixels | |
| 3 | FRAME_QUALITY_FILTER | FRAME_ARCHIVE, FRAME_MANIFEST | FRAME_ARCHIVE_SELECTED (3,409,920, `de217a05…10b3`, PII); FRAME_QUALITY_REPORT (6,303, `1aaedb34…00a9`) | SUCCEEDED | 0.31 s | blur 40, duplicate distance 2.0, min 10 frames; 11 of 11 kept | image pixels | |
| 4 | PRIVACY_PREPROCESS | FRAME_ARCHIVE_SELECTED (+ reports) | FRAME_ARCHIVE_ANON (3,502,080, `356d0ab0…9b21`); PRIVACY_REPORT (8,898, `dbbc676c…2ca1`) | SUCCEEDED | 19.96 s | Haar faces + heuristic-quad, fail-closed | image pixels | Succeeded, but the over-masking persists (F5): 55 "faces", 10 of 11 frames escalated |
| 5 | POSE_ESTIMATION | FRAME_ARCHIVE_ANON (+ reports) | SPARSE_MODEL (3,246,080, `0463ccbb…c7eb`); POSES (4,001, `d8746106…9535`) | SUCCEEDED | 63.72 s | COLMAP 3.8 CPU: `feature_extractor` (SIMPLE_RADIAL, single camera, `use_gpu 0`, 2 threads); `exhaustive_matcher`; `mapper` (GLOMAP absent); `model_converter` | **RECONSTRUCTION** frame: arbitrary scale, rotation and origin; not metric | |
| 6 | SPLAT_RECONSTRUCTION | SPARSE_MODEL, POSES, FRAME_ARCHIVE_ANON | none | **FAILED** | 0.04 s | gsplat, 7000 iterations (never started) | — | `DEPENDENCY_UNAVAILABLE`: "missing: torch, gsplat, cuda". **Hardware: no CUDA device.** |
| 7 | SEMANTIC_SEGMENTATION | — | — | NOT_RUN | — | — | — | needs SPLAT; the worker also lacks torch, transformers |
| 8 | GEOMETRIC_CLEANUP | — | — | NOT_RUN | — | — | — | needs SPLAT; the worker also lacks Open3D |
| 9 | PLANE_FITTING (+ GRAVITY_ESTIMATE) | — | — | NOT_RUN | — | — | — | needs SPLAT_CLEAN; the worker also lacks Open3D |
| — | *Calibration* (`POST …/coordinate-frames`) | — | — | NOT_RUN | — | — | — | needs GRAVITY_ESTIMATE, **and** at least 2 measured distances or 3 surveyed control points. No such measurements exist for this capture. |
| 10 | ARTIFACT_GENERATION | — | — | NOT_RUN | — | — | — | needs SPLAT_CLEAN/SPLAT |
| 11 | SEMANTIC_INDEXING | — | — | NOT_RUN | — | — | — | needs the splat and a canonical frame; the worker also lacks torch, transformers, open_clip, PIL |
| 12 | NAVIGATION_BAKING (Recast) | — | — | NOT_RUN | — | — | — | needs the splat, PLANE_MODEL and a canonical frame. `chaya-navmesh` itself **is** present on this worker (`chaya-navmesh 1.0.0 recastnavigation 1.6.0`). |
| — | Route (`POST /navigation/routes`) | — | — | NOT_RUN | — | — | — | no NAVIGATION_GRAPH exists; the API refuses with `ROUTE_UNAVAILABLE` (section 4.2, step 16) |

**COLMAP's analysis of the stored SPARSE_MODEL** (`colmap model_analyzer`):

- 1 camera; **11 of 11 images registered**;
- **5,814 points**, 25,215 observations, mean track length 4.34;
- **mean reprojection error 0.399 px**.

COLMAP is not bit-for-bit deterministic across runs: a first pass of the same command gave 5,792 points and 0.403 px.
That pass also set `MIN_FRAMES=3`, an override that was unnecessary and was dropped for the recorded run.

### 0.4 Where execution stops, and why

1. **`SPLAT_RECONSTRUCTION`: hardware.**
   - The host GPU is Intel integrated graphics (`Win32_VideoController`: "Intel(R) Graphics"). There is no NVIDIA
     device and no `nvidia-smi`.
   - gsplat's rasteriser is CUDA-only, and the stage deliberately has no CPU path.
   - Every later stage consumes this splat.
   - **Using the COLMAP sparse points in place of a trained splat was not done.** The stage itself defines an
     unoptimised SfM seed as "not a reconstruction", so doing this would substitute for the blocked stage.
2. **Canonical metric frame: data, independent of the GPU.**
   - The frame is produced by the control plane's calibration (`CoordinateFrameService`). It combines PLANE_FITTING's
     GRAVITY_ESTIMATE with either at least 2 agreeing measured distances or at least 3 surveyed control points.
   - Reconstruction units are never assumed to be metres.
   - The public Sceaux photographs come with no physical measurements, and none can be honestly invented.
   - So even on a GPU host this capture could not reach NAVIGATION_BAKING, which fails `NOT_CALIBRATED` without a
     frame.
3. **Suitability of the capture.**
   - It is an exterior façade shot from a few metres away, not a walkable interior.
   - It is a valid SfM test.
   - It is a poor navmesh test: the visible ground is a small strip in front of the building.

### 0.5 What is needed for a successful run

1. **A real indoor capture that carries its own metric references:**
   - a short walk-through video of a corridor or room;
   - at least two tape-measured distances between identifiable points (door width, corridor length), or three
     surveyed control points;
   - recorded at capture time and kept with the dataset.
2. **An NVIDIA GPU worker** (compute capability ≥ 7.0) with the reconstruction toolchain: DEPLOYMENT.md "GPU workers",
   torch + gsplat, Open3D, transformers, plus COLMAP (GLOMAP optional). `chaya-navmesh` is already in the worker
   image.
3. Then, through the full stack (`scripts/e2e/e2e_validate.py`, section 2), with the GPU worker in place of the
   toolchain worker. `physical_pipeline.py` shows how far a capture gets. It cannot calibrate or resume, and COLMAP is
   not deterministic, so a re-run would produce a different reconstruction from the one that was calibrated.
   1. Run the capture until the run fails `NOT_CALIBRATED` (at SEMANTIC_INDEXING).
   2. Calibrate that run with the measured distances
      (`POST /api/v1/venues/{venueId}/reconstructions/{runId}/coordinate-frames`).
   3. Retry. Only the failed stage and those after it re-run (section 4.2, step 10.2).
   4. Request a route.

   Only then may M9 and M13 be marked Done.

---

## V. Digital-twin viewing path, 2026-09-28

The required path is real reconstruction → production splat export → stored artifact → authenticated viewer request →
GaussianSplats3D → rendered scene.

**Two different claims, validated separately:**

| Claim | Status |
|---|---|
| **REAL VENUE RECONSTRUCTION VALIDATION**: a reconstruction of a real venue, viewed | **NOT DONE.** No real reconstruction exists here: `SPLAT_RECONSTRUCTION` needs a CUDA GPU (section 0). Nothing below is, or is called, a venue reconstruction. |
| **FORMAT VALIDATION**: a deterministic synthetic Gaussian scene, exported by the production exporter and taken through the rest of the path | **PASS**, in CI and on the full running stack. Routing is BLOCKED: no navmesh exists. |

**Product defect found and fixed.** The digital-twin viewer drew nothing on desktop browsers: it showed a black canvas
while reporting the scene as loaded with every splat counted (V.4).

- The defect has been there since the viewer was introduced (`a158287`).
- The previous Playwright test passed anyway, because it checked the splat count and never the pixels.

Evidence (no screenshots; every visual claim is a measured value):

| File | Contents |
|---|---|
| `viewer-format-results.json`, `viewer-format-run-log.txt` | full-stack harness: exporter, storage, calibration, POIs, route, HTTP authorization |
| `viewer-format-check.json`, `viewer-format-check.txt` | real Chromium against the real web container: public link and Keycloak sign-in |
| `viewer-format-stack.txt` | image ids, worker stage set, library versions |

### V.1 The fixture

`packages/contracts/fixtures/viewer-scene`. Its `fixture.json` says, in words: "NOT a reconstruction of any venue".

- **The scene.** 3,701 Gaussians in canonical metres (+Z up):
  - a 6 m × 4 m checkerboard floor at z = 0 (flat Gaussians);
  - red, green and blue pillars, each 2 m tall.
- **The frame.** It is stored in a "reconstruction frame" with a known similarity: scale 2.5, a COLMAP-like −Y up, a 30°
  yaw and an offset. Calibration and canonical placement are therefore exercised, not an identity.
- **The bytes.** `generate_viewer_scene.py` writes `scene.ply` and runs the **production ARTIFACT_GENERATION stage**
  (`chaya_worker.stages.artifact_generation`) on it. The stage's KSPLAT output is committed as `scene.ksplat`
  (167,964 B, sha256 `af64e4f4…f48d`).
- **The binding.** `tests/unit/test_viewer_scene_fixture.py` fails unless the committed `.ksplat` is byte-identical to
  what that stage writes today. It passes on Windows and on Linux (Debian, the worker image).
- **Control points and POI positions.** Both are exact **by construction** of the synthetic scene. They are not a
  survey, and are labelled so wherever they are used.

### V.2 FORMAT VALIDATION in CI (no backend)

These run in the existing `frontend` CI job (`npm test`, `npm run test:e2e`).

- **`apps/web/lib/viewer-scene-fixture.test.ts`** uses the pinned `KSplatLoader` (GaussianSplats3D 0.4.7):
  - it reads all 3,701 splats;
  - every position and every colour/opacity matches the library's own `PlyLoader` on `scene.ply`;
  - the viewer's own placement math (`lib/coordinate-frame`) with the fixture's similarity reproduces the 6 × 4 × 2 m
    box.
- **`apps/web/e2e/ksplat-viewer.spec.ts`**, rewritten. The JSON API is mocked; the artifact response carries the
  fixture's exact bytes. It checks:
  - **Artifact request.** One request, carrying `X-Chaya-Viewer-Token` and no `Authorization`. The SHA-256 of the
    bytes the browser received equals the fixture's.
  - **Splat count.** GaussianSplats3D reports 3,701.
  - **Rendering.** More than 5 % of the canvas's sampled pixels are drawn. The pixels are read back from the WebGL
    canvas inside a `requestAnimationFrame` callback that runs after the viewer's render.
  - **POI overlay.** Three POI markers appear. The pixels just above the "Red pillar" marker are red, so the overlay and
    the splat share one placement. Clicking the marker selects that POI.
  - **Camera interaction.** A mouse drag changes the rendered frame.
- **`apps/web/e2e/viewer.spec.ts`.** The two tests that stubbed the download with a request that never resolved now get
  the fixture's bytes:
  - uncalibrated: it renders, and no POIs are drawn;
  - calibrated: it renders, and the POIs are placed.
- **Scene waits.** They use a 60 s budget (`SCENE_TIMEOUT`): software WebGL takes about 5 s per page alone, and much
  longer with many pages in parallel.
- **Result.** 12/12 Playwright tests in two full runs, and 12/12 when the three scene tests were repeated four times
  serially. With the scene tests repeated ×4 on 4 parallel workers, 11/12 passed. The failure was a floor list that
  never loaded under that load, before any artifact was requested.
- **Unit suites.** `npm run lint` and `npm run typecheck` are clean; `npm test` gives 106/106.

**Negative control.** With the old device profile restored (`gpuAcceleratedSort: !isMobileUa`), `ksplat-viewer.spec.ts`
fails at the rendering check, with 0 % of pixels drawn.

### V.3 FORMAT VALIDATION on the full running stack

**What is real:**

- Postgres, MinIO, ClamAV and Keycloak;
- the API image (`chaya-api:smoke`);
- the web image and the CPU worker image, both built from the working tree.

**The worker.** It claims only `ARTIFACT_GENERATION`: a scratch compose override sets `WORKER_STAGES`, so it cannot race
the stand-ins.

**Stand-ins (not executed).**

- The harness claims `INPUT_VALIDATION` … `PLANE_FITTING` through the worker API, as the worker service account, and
  reports each as SUCCEEDED with `command.standIn = true` and a note saying the stage was not executed.
- Only two of them publish anything:
  - the pose stand-in publishes a labelled placeholder `POSES` (`{"standIn": true, "poses": []}`), because calibration
    binds to a run's POSES;
  - the splat stand-in publishes the fixture PLY as `SPLAT` (`format-fixture-scene.ply`).
- The run's capture media is a 2 s ffmpeg test pattern (13,868 B). It passed real ClamAV and content sniffing, but
  carries no scene and is used by no stage.
- Nothing after `ARTIFACT_GENERATION` is stood in.

```bash
# Throwaway env: fresh random values for every key of .env.example (+ S3_RECON_*), outside the repository.
# viewer-override.yml: services.reconstruction-worker.environment.WORKER_STAGES=ARTIFACT_GENERATION
C="docker compose -p chaya-viewer --env-file <scratch>/viewer.env -f infra/docker/docker-compose.yml \
   -f infra/ci/docker-compose.smoke.yml -f infra/ci/docker-compose.e2e.yml -f <scratch>/viewer-override.yml --profile pipeline"
$C build api web reconstruction-worker
$C down -v --remove-orphans            # 15:56:16; 0 volumes left
$C up -d postgres redis minio minio-init keycloak clamav api web reconstruction-worker   # all healthy at 15:58:36 +05:30

ffmpeg -f lavfi -i testsrc=duration=2:size=640x360:rate=10 -c:v libx264 -pix_fmt yuv420p carrier.mp4
services/reconstruction/.venv/Scripts/python scripts/e2e/viewer_format_validation.py --env-file <scratch>/viewer.env \
  --project chaya-viewer --carrier carrier.mp4 --out docs/e2e-evidence/viewer-format-results.json \
  --handoff <scratch>/viewer-handoff.json                    # secrets: kept outside the evidence
cd apps/web && HANDOFF=<scratch>/viewer-handoff.json OUT=../../docs/e2e-evidence/viewer-format-check.json \
  node ../../scripts/e2e/viewer_format_check.cjs            # 16:01:55 to 16:02:28 +05:30
```

**Harness: 18 PASS, 0 FAIL, 1 BLOCKED.**

| Step | Check | Result |
|---|---|---|
| V3 | Stages before the exporter | 9 labelled stand-ins; `pipeline_stage_run.command->>'standIn' = true` for each |
| V4 | Production exporter | `ARTIFACT_GENERATION` SUCCEEDED on `viewer-format-cpu-worker` (the real container), `gaussian_count` 3,701. Outputs: KSPLAT, ARTIFACT_MANIFEST, VIEWER_BUNDLE. **PASS** |
| V4.1 | Stored artifact | MinIO object 167,964 B; sha256 of the stored bytes == `processing_artifact.checksum_sha256` == the committed fixture (`af64e4f4…f48d`). The worker container's exporter wrote the same bytes as the committed fixture. **PASS** |
| V4.2 | Manifest provenance | lists `SPLAT format-fixture-scene.ply` by checksum; `coordinateSpace: RECONSTRUCTION`. **PASS** |
| V5 | Calibration (real API) | 5 control points by construction → 201, canonical, `VENUE_CONTROL_POINTS`, scale 2.5, RMS 1.0e-15 m. **PASS** |
| V5.1 | POIs | 3 manual POIs at the pillar bases, `frameStatus CURRENT`. **PASS** |
| V6 | Route | 409: "no STANDARD navigation graph baked from a Recast navmesh; run NAVIGATION_BAKING…"; 0 `navigation_graph` rows. **BLOCKED** (no navmesh) |
| V7.1 | Public viewer token → artifact | 200, `application/octet-stream`, `nosniff`, `private, max-age=3600`, bytes == fixture. **PASS** |
| V7.2 | Manager (Keycloak JWT) → artifact | 200, bytes == fixture. **PASS** |
| V7.3–V7.8 | Refusals | no credentials 401; the venue-A token under venue B 404; venue B's manager 404; raw `SPLAT` kind 400; a revoked link's token 401. `reconstructions/latest` returns this run with the fixture's sha256 and a canonical frame. **PASS** |

**Browser: 12 PASS, 0 FAIL, 1 BLOCKED.** Real Chromium, the real web container. A is the public link; B is the Keycloak
PKCE sign-in as the venue manager.

| Check | A: public link | B: signed-in manager |
|---|---|---|
| HTTP artifact retrieval (as the browser received it) | 200, 167,964 B, `X-Chaya-Viewer-Token` only, sha256 == fixture | 200, `Authorization: Bearer` only, sha256 == fixture |
| Format / viewer initialisation | "Splats 3,701", no load error | same |
| Rendered scene | 10.3 % of pixels drawn | 7.7 % |
| POI overlay | 3 markers; rgb(215, 41, 41) above the Red pillar marker; click selects it | same |
| Camera interaction | drag: mean cell difference 13.4/255; 12.0 % drawn after | 11.7/255; 12.1 % |
| Console errors | 0 | 0 |
| Route overlay | **BLOCKED**: route 409; the panel shows the API's reason; nothing drawn | not exercised |

### V.4 Defect: the viewer drew nothing on desktop (fixed)

- **Symptom.**
  - The desktop viewer showed a black canvas.
  - The sidebar still said "Splats 3,701", and there was no error.
  - POI markers were projected into the empty scene.
  - The old `ksplat-viewer.spec.ts` passed: it waited for the splat count only.
- **Found by.** The new pixel check. Instrumenting WebGL showed one draw call in 3 s of rendering.
- **Cause.** `lib/device-profile.ts` set `gpuAcceleratedSort: !isMobileUa`, so it was true on every desktop browser. In
  GaussianSplats3D 0.4.7, with GPU-computed distances:
  - `addSplatScene` never uploads splat centres to the sort worker (it pushes them only `if (!this.gpuAcceleratedSort)`);
  - the worker clamps every sort to `min(splatRenderCount, uploadedSplatCount)`, which is 0;
  - so the sort reports 0 splats to render, the mesh's `instanceCount` stays 0, and three.js draws nothing.

  Observed in the worker messages: out `sort {splatRenderCount: 3701}`, in `sortDone {splatRenderCount: 0}`.
- **Fix.** `gpuAcceleratedSort: false`, which is the library's own default, with the reason documented at the field.
- **Verified.**
  - All 3,701 splats become instances.
  - The CI spec and the full-stack check render (V.2, V.3).
  - The negative control fails with the old value.
- **Considered and rejected.** A stale camera matrix (the external camera's `matrixWorld` is not updated before the
  library's first sort) was suspected too. Once the sort fix was in, rendering was identical with or without
  `camera.updateMatrixWorld()`, so that change was **not** made.

### V.5 What remains

- **REAL VENUE RECONSTRUCTION VALIDATION** needs a real reconstruction: a GPU worker and a real, measured capture
  (section 0.5).
- **Route overlay.** It needs `NAVIGATION_BAKING` on a real, calibrated reconstruction; no navmesh exists here.
- **Integrity check.** The viewer does not check the downloaded bytes against the `sha256` the API returns. The tests
  above do that check themselves. A mismatch in the app today would surface only as a load error or a wrong scene.
  This is a product gap and was left open.
- **Headless rendering only.** Rendering was measured in headless Chromium with software WebGL. No physical GPU or
  mobile browser was used.

---

*Sections 1–9 below are the 2026-09-24 full-stack validation (API, Keycloak, MinIO, both workers, browser). They are
unchanged, except that section 9 lists the files the 2026-09-28 validations (sections 0 and V) added.*

**Date:** 2026-09-24.
**Code under test:** commit `372b5b5` plus the working tree listed in [section 9](#9-files-added-or-changed). That
includes the CI/CD hardening done earlier the same day (Spring Boot 3.5.16, new API and web runtime images, pinned
MinIO) and the integration fixes in [section 6](#6-integration-failures-found-and-fixed).

**Final clean run:** 2026-09-24 18:55:16 to 19:02:46 +05:30, on a stack started from nothing at 18:53:06 (all services healthy at 18:55:01).

| | PASS | FAIL | BLOCKED |
|---|---:|---:|---:|
| Scripted workflow (`results.json`, 88 checks) | 70 | 2 | 16 |
| Browser, real Chromium (`viewer-check.json`, 8 checks) | 6 | 1 | 1 |

**Not everything passed.**

- **FAIL (3 checks, one cause):** semantic search cannot find manually created POIs while the embedding model is
  up. Manual POIs are never given an embedding. This is a product gap, not an environment problem:
  [F1](#7-open-findings-not-fixed).
- **BLOCKED (17 checks, one cause):** this host has no CUDA GPU. The pipeline now runs **five** real stages,
  through `POSE_ESTIMATION` (real COLMAP: 11 of 11 frames registered). It then stops honestly at
  `SPLAT_RECONSTRUCTION`: `DEPENDENCY_UNAVAILABLE, missing: torch, gsplat, cuda`. Every later stage consumes
  that splat, so the following are BLOCKED:
  - the reconstruction artifacts;
  - version registration;
  - 3D viewer loading;
  - detected POIs;
  - navigation and route display;
  - rescan and scan-version history.

  For each of those, the API's refusal was checked to be structured and correct.
- **Everything else passed.** That covers authentication, authorization, upload validation, object storage, the
  processing jobs that can run here, the public viewer, audit logging and POI versioning.

**Evidence** is in [`docs/e2e-evidence/`](e2e-evidence/). Secrets and tokens are redacted there; a scan for every
secret value used in the run found none.

| File | Contents |
|---|---|
| `results.json` | every scripted check: expected, actual, status, blocked dependency, evidence |
| `run-log.txt` | the harness console output |
| `viewer-check.json`, `viewer-check.txt` | the browser checks |
| `viewer-public.png`, `viewer-search.png`, `viewer-route.png`, `viewer-signed-in.png` | screenshots |
| `stack.txt` | containers, image IDs, each worker's toolchain report, host hardware |

---

## 1. Environment

| Item | Value |
|---|---|
| Host | Windows 11 Home 10.0.26200. Intel Core Ultra 5 125H, 15.6 GB RAM. **GPU: Intel integrated graphics only.** No NVIDIA device, no `nvidia-smi`. |
| Container runtime | Docker Desktop, engine 29.6.2, Compose 5.3.1. WSL2 VM with 7.5 GiB RAM and 18 CPUs. Other, unrelated projects' containers were using about 1.9 GiB of it. |
| Compose project | `chaya-e2e` = `infra/docker/docker-compose.yml` + `infra/ci/docker-compose.smoke.yml` + `infra/ci/docker-compose.e2e.yml`, profile `pipeline` |
| PostgreSQL + pgvector | `pgvector/pgvector:pg17`. `vector 0.8.6`, `pg_trgm 1.6`. All 16 Flyway migrations applied by the API at start. |
| Redis | `redis:7-alpine`, password-protected. Reachable, but **unused by every Chaya service** (F4). |
| MinIO | `quay.io/minio/minio:RELEASE.2025-09-07T16-13-09Z@sha256:14ce…936e`, pinned. Private buckets `chaya-raw` and `chaya-derived`. Five accounts from `minio-init`: api, worker, recon, backup, plus root. |
| ClamAV | `clamav/clamav:1.4`, real signatures. Uploads are never accepted unscanned. |
| Keycloak | `quay.io/keycloak/keycloak:26.0`, realm imported from `infra/keycloak/chaya-realm.json` |
| Backend | `services/api` image built from its Dockerfile: Spring Boot 3.5.16, JRE 21 Alpine, UID 10001, read-only root filesystem |
| Processing service (CPU) | `services/reconstruction` production image, UID 10001. `WORKER_STAGES` = the six stages the image can run, as in `infra/deploy` (`stack.txt`). |
| Processing service (toolchain) | **Test-only** image `infra/ci/worker-colmap.Dockerfile`: the production worker plus Debian's CPU build of COLMAP 3.8. Stands in for a GPU host's worker. Claims `POSE_ESTIMATION` and every later toolchain stage, with the restricted `chaya-recon` storage account. Memory limit 2 GiB, `COLMAP_NUM_THREADS=2`. |
| Vision service | `services/vision` image: CPU PyTorch 2.14, `open_clip` 3.3, `ViT-B-32/openai` (512-d). **Runs in its container** with a 2 GiB limit. The previous attempt could not do this; see [6.1](#61-vision-service-loads-the-model-once-per-concurrent-request-and-is-oom-killed). |
| Frontend | `apps/web` image (Next.js standalone, `node:22-alpine`, no package managers in the runtime) |
| Browser | Playwright 1.63 headless Chromium |
| Host ports | web 3000, Keycloak 8081, API 18080, vision 18090, MinIO 19000, Postgres 55432 |

Every credential was a random, throwaway value, written to an env file outside the repository.

## 2. Commands

```bash
# 0. Clean environment. C is the compose command for the whole E2E stack.
ENV=<scratch>/e2e.env   # random secrets for every key of .env.example, plus S3_RECON_* and the host ports above
C="docker compose -p chaya-e2e --env-file $ENV -f infra/docker/docker-compose.yml \
   -f infra/ci/docker-compose.smoke.yml -f infra/ci/docker-compose.e2e.yml --profile pipeline"
$C down -v --remove-orphans                 # containers, volumes (database, objects, model cache) and network
docker volume ls -q --filter label=com.docker.compose.project=chaya-e2e   # -> nothing

# 1. Build every image from the working tree, no cache, fresh base images (377 s)
$C build --no-cache --pull

# 2. Start everything; all services reported healthy after about 2 minutes (vision downloads CLIP weights on start)
$C up -d postgres redis minio minio-init keycloak clamav api web vision reconstruction-worker toolchain-worker

# 3. Capture fixture: 11 real photographs of a real building, encoded as an H.264 video
#    (not committed: the photographs carry the photographer's copyright; see section 3)
for i in $(seq 7100 7110); do
  curl -O https://raw.githubusercontent.com/openMVG/ImageDataset_SceauxCastle/master/images/100_$i.JPG
done
ffmpeg -framerate 2 -pattern_type glob -i "*.JPG" -vf scale=1600:-2 -c:v libx264 -pix_fmt yuv420p -crf 18 -g 1 \
  sceaux-castle.mp4
#    -> 4,917,797 bytes, 5.5 s, 11 frames of 1600x1202,
#       sha256 f960743de6220499436815d55489bd052323d67160367c2e41301cc8cb6921fb

# 4. Scripted workflow: API, Keycloak, MinIO, Postgres, both workers, vision
services/reconstruction/.venv/Scripts/python -u scripts/e2e/e2e_validate.py --env-file $ENV \
  --fixture sceaux-castle.mp4 --duration 5.5 --pipeline-timeout 1200 --out docs/e2e-evidence/results.json \
  --vision-stop "docker stop chaya-e2e-vision-1" --vision-start "docker start chaya-e2e-vision-1" \
  --handoff <scratch>/handoff.json

# 5. Browser checks against the same stack (public link, then Keycloak sign-in as the manager)
cd apps/web && HANDOFF=<scratch>/handoff.json OUT_DIR=../../docs/e2e-evidence node ../../scripts/e2e/viewer_check.cjs

# 6. Regression checks for the code changed in this validation
cd services/reconstruction && ruff check . && python -m pytest       # Python 3.12 container: 117 passed, 12 skipped
cd services/vision && ruff check . && python -m pytest               # 8 passed
cd apps/web && npm run lint && npm run typecheck && npm test && npx playwright test   # 61/61 unit, 6/6 Playwright
```

## 3. Test scenario

**The fixture is a real capture with real parallax:** 11 photographs of the Château de Sceaux, taken from different
positions. This is OpenMVG's public structure-from-motion sample set
(`github.com/openMVG/ImageDataset_SceauxCastle`, "Copyright 2012 Pierre MOULON").

- The earlier attempt used a single flat photograph panned across the frame. With no parallax, a
  structure-from-motion failure on it would have said nothing about the pipeline.
- The photographs are downloaded at test time and not committed, because no license beyond the copyright notice is
  stated. The SHA-256 of the encoded video is in section 2.

**The workflow:**

1. **Tenant setup.** An organization is created in SQL; there is no API for it (F3). Two venues (A and B) are
   created as an admin; a venue manager may not create venues (F2).
2. **As the venue manager of venue A:**
   1. create a floor and a capture session;
   2. upload the fixture through the multipart ingestion API;
   3. the API validates it: SHA-256, content sniffing and a real ClamAV scan;
   4. start processing.
3. **Processing.** The CPU worker container runs the four preprocessing stages. The toolchain worker container runs
   `POSE_ESTIMATION` with real COLMAP, then claims `SPLAT_RECONSTRUCTION`.
4. **Storage check.** Every stored artifact is read back from MinIO and compared byte for byte (size and SHA-256)
   with its database record.
5. **Downstream steps:** version registration, the reconstruction API, POIs, semantic search (with the vision
   service up, then down), navigation, public links, rescan and version history. Each is tested for success
   where the environment allows it. Otherwise it is tested for an honest, structured refusal; nothing is
   substituted for a missing stage.
6. **Authorization** is tested across roles, venues, organizations, the worker service account, the reconstruction
   storage account and anonymous viewers.
7. **The audit log** is checked for coverage and immutability.
8. **The real web app** is driven in Chromium twice: through a public viewing link, and signed in as the manager
   through Keycloak (authorization code + PKCE, client `chaya-web`).

**Identity.** `chaya-web` deliberately has no password grant. Scripted API calls therefore use a **test-only** public
client, `chaya-e2e`, which has the same client scopes, the same approach as `scripts/ci/stack-smoke.sh`. Every
token is a real Keycloak-signed JWT verified by the API against Keycloak's JWKS. The browser sign-in (B4) uses the
real `chaya-web` login flow.

## 4. Results

**Status meanings:**

- **PASS:** the observed behaviour matched the expectation.
- **FAIL:** it did not.
- **BLOCKED:** the step cannot run without a dependency this host lacks, named in the table. For every BLOCKED
  row, the system's refusal itself was checked: a structured 404, a retryable `DEPENDENCY_UNAVAILABLE`, or an honest
  empty state. None was a fabricated success.

The *Evidence* column gives the `step` id in `results.json` / `run-log.txt`, or a file.

### 4.1 Platform health

| # | Check | Expected | Actual | Result |
|---|---|---|---|---|
| 0.1 | `GET /api/v1/health` | 200 UP | 200 UP. Database, storage, identity provider and malware scanner UP; queue IDLE. | PASS |
| 0.2 | API readiness | 200 | 200 UP | PASS |
| 0.3 | API version | 200 | `chaya-api 0.1.0-SNAPSHOT`, api v1 | PASS |
| 0.4 | Database | 16 migrations; vector and pg_trgm installed | 16 of 16; `vector 0.8.6`, `pg_trgm 1.6` | PASS |
| 0.5 | Web `/api/health` | 200, API reachable | 200; backend reachable, UP | PASS |
| 0.6 | Vision `/health/ready` | CLIP loaded | 200, `open_clip:ViT-B-32:openai`, modelAvailable=true | PASS |
| 0.7 | Redis | PONG | PONG (unused by Chaya, F4) | PASS |

### 4.2 Workflow

| Step | Scenario | Expected | Actual | Result | Blocked dependency | Evidence |
|---|---|---|---|---|---|---|
| 1 | Authenticate as venue manager | Keycloak JWT: `venue-manager`, `org_id`, `venue_id`, `aud=chaya-api` | 200. Role `venue-manager`; org and venue claims match; `aud=chaya-api` | PASS | | 1 |
| 1.1–1.4 | Token handling | own venue only; missing, tampered or wrong-password tokens rejected | sees exactly venue A; 401; 401; Keycloak 401 `invalid_grant` | PASS | | 1.1–1.4 |
| 2 | Create organization | organization exists | Created **in SQL** (the documented procedure; no API exists, F3). A second organization was created for isolation tests. | PASS (via SQL) | | 2 |
| 3.0 | Manager creates a venue | 403 (admin-only) | 403 | PASS | | 3.0 |
| 3 | Create venue (as admin) | 200 with id | 200; row scoped to the organization, `Asia/Kolkata` | PASS | | 3, 3.1 |
| 4 | Create floor (manager) | 201 | 201 `Ground floor`, level 0; row in `floor` | PASS | | 4, 4.1 |
| 5 | Create capture session | CREATED | 201 `CREATED`; operator = the manager's subject | PASS | | 5 |
| 6 | Upload the real fixture | init 201, part 204, complete 202 | 4,917,797 bytes in 1 part (8 MiB part size); 204 / 202 | PASS | | 6.1, 6 |
| 7 | Validate the upload | ACCEPTED | `ACCEPTED`: SHA-256 verified, sniffed as `video/quicktime` (MP4 family), ClamAV clean | PASS | | 7 |
| 7.1 | Object storage | raw object == fixture | `s3://chaya-raw/org/…/capture/…/raw/<media>`: 4,917,797 bytes; SHA-256 equals the fixture and the database record | PASS | | 7.1 |
| 7.2–7.3 | Bucket security | backup account cannot write; anonymous GET 403 | `AccessDenied`; 403 | PASS | | 7.2, 7.3 |
| 7.4 | Wrong declared checksum | REJECTED | `REJECTED` / `CHECKSUM_MISMATCH` | PASS | | 7.4 |
| 7.5 | EICAR test file | never ACCEPTED | `REJECTED` / `MALWARE_DETECTED` "Eicar-Test-Signature" | PASS | | 7.5 |
| 7.6 | Complete the capture upload | 200 | 200, `READY_FOR_PROCESSING` | PASS | | 7.6 |
| 8 | Create processing job | 202; first stage QUEUED | 202, run RUNNING, privacy on; `processing_job INPUT_VALIDATION QUEUED` | PASS | | 8, 8.1 |
| 9.1 | INPUT_VALIDATION | SUCCEEDED | SUCCEEDED, exit 0, 1.0 s, CPU worker (`ffprobe`) | PASS | | 9.1 |
| 9.2 | FFMPEG_PREPROCESS | SUCCEEDED | SUCCEEDED, exit 0, 1.1 s: 11 frames (`ffmpeg -vf fps=2,scale=…1080`) | PASS | | 9.2 |
| 9.3 | FRAME_QUALITY_FILTER | SUCCEEDED | SUCCEEDED, 0.8 s: selected frames + quality report | PASS | | 9.3 |
| 9.4 | PRIVACY_PREPROCESS | SUCCEEDED | SUCCEEDED, 20.9 s. Anonymised frames + privacy report. The report shows 55 "face" regions on a building with no people in it: false positives (F5). | PASS (see F5) | | 9.4; `PRIVACY_REPORT` |
| 9.5 | POSE_ESTIMATION | runs if the toolchain is present | **SUCCEEDED**, exit 0, 77 s, on `e2e-toolchain-worker`: real COLMAP 3.8 on CPU (`--SiftExtraction.use_gpu 0 --SiftExtraction.num_threads 2`), exhaustive matcher, COLMAP mapper. **11 of 11 frames registered.** `SPARSE_MODEL` + `POSES` stored. (Offline, the same COLMAP commands on the unmasked 1600×1202 frames: 11/11 registered, 8,874 points, 0.41 px mean reprojection error.) | **PASS** | | 9.5 |
| 10 | SPLAT_RECONSTRUCTION | runs, or fails honestly | Run `FAILED` at `SPLAT_RECONSTRUCTION`: `DEPENDENCY_UNAVAILABLE` "missing: torch, gsplat, cuda"; retryable; quality null | **BLOCKED** | **CUDA GPU** + PyTorch + gsplat | 10 |
| 10 | SEMANTIC_SEGMENTATION | runs | not reached (PENDING) | **BLOCKED** | the splat, plus torch, transformers and SegFormer weights | 10 |
| 10 | GEOMETRIC_CLEANUP | runs | not reached | **BLOCKED** | the splat, plus Open3D | 10 |
| 10 | PLANE_FITTING | runs | not reached | **BLOCKED** | the cleaned splat, plus Open3D | 10 |
| 10 | ARTIFACT_GENERATION | runs (CPU stage) | not reached: it consumes the splat | **BLOCKED** | upstream splat | 10 |
| 10 | SEMANTIC_INDEXING | runs | not reached | **BLOCKED** | the splat, plus torch, transformers, open_clip and Grounding DINO weights | 10 |
| 10 | NAVIGATION_BAKING | runs | not reached | **BLOCKED** | plane fitting output, plus `recast-cli` | 10 |
| 10.1 | A failed run is not a success | quality null, capture stays PROCESSING | `FAILED`, quality null, capture `PROCESSING` | PASS | | 10.1 |
| 10.2 | Retry | only the failed stage re-runs | 202; SPLAT attempt 2 fails the same way. The five earlier stages were not repeated (attempts = 1). | PASS | | 10.2 |
| 11 | Store generated artifacts | every artifact in MinIO, checksums match | 23 records across 11 kinds, including `SPARSE_MODEL` and `POSES`. **21 verified byte for byte**; the other 2 are PII frame archives, deliberately purged. | PASS | | 11 |
| 11.1–11.2 | PII purge | no `/pii/` object left; purge audited | 0 left; 1 `pipeline.pii_purged` row | PASS | | 11.1, 11.2 |
| 11.a | GPU-worker storage account is least-privilege | `chaya-recon` cannot read raw media or write `pii/` | `AccessDenied` on both (checked after the run) | PASS | | `stack.txt`; this section |
| 11.3 | Reconstruction artifacts (splat, `.ksplat`, manifest, viewer bundle) | stored | none: the producing stages did not run | **BLOCKED** | SPLAT_RECONSTRUCTION (CUDA) | 11.3 |
| 12 | Register reconstruction version | FINALIZED `scan_version` | Refused correctly: 404 "no successful full-venue reconstruction exists yet for this floor to finalize as a version" | **BLOCKED** | a SUCCEEDED run (CUDA) | 12 |
| 13 | Load the reconstruction in the viewer | viewer shows the splat | API: list `[]`, latest 404 "no reconstruction is available for this floor yet". Browser: "No reconstruction available" (B1.1). | **BLOCKED** | reconstruction artifact (CUDA) | 13, B1.1 |
| 14 | Create POIs | 4 created | 4 manual POIs (Reception desk, Accessible restroom, Cafe, Elevator A), version 1 each | PASS | | 14 |
| 14.1 | Index POIs for vector search | `poi_version.embedding` set | **All 4 `embedding IS NULL`** (`source=MANUAL`) | **FAIL** | (none: product gap F1) | 14.1 |
| 14.2 | Auto-detected POIs | ingested from `DETECTED_OBJECTS` | stage not reached | **BLOCKED** | SEMANTIC_INDEXING (CUDA-dependent chain) | 14.2 |
| 15.0 | Vision embeds query text | 512-d CLIP vector | 200, 512-d, `open_clip:ViT-B-32:openai`, from the **vision container** | PASS | | 15.0 |
| 15 | Search "restroom" with vision up | `matchType=embedding`, "Accessible restroom" first | 200, `matchType=embedding`, **no results** | **FAIL** | (none: F1) | 15 |
| 15.1 | Search with the vision container stopped | `lexical_fallback`, finds it | 200, `lexical_fallback`, `["Accessible restroom"]` | PASS | | 15.1 |
| 15.2 | Search analytics | queries logged | 2 `search_query` rows | PASS | | 15.2 |
| 16 | Request a navigation route | waypoints | 404 `ROUTE_UNAVAILABLE`; 0 `navigation_graph` rows | **BLOCKED** | NAVIGATION_BAKING (CUDA-dependent chain + `recast-cli`) | 16 |
| 17 | Display the route in the viewer | polyline over the splat | Browser shows the API's reason: "No route available: no route could be found…" (B3) | **BLOCKED** | steps 13 and 16 | 17, B3 |
| 18.1–18.2 | Manager on venue B (same organization) | 404 read, 404 write | 404, 404 | PASS | | 18.1, 18.2 |
| 18.3 | Manager reads the organization audit log | 403 | 403 | PASS | | 18.3 |
| 18.4–18.6 | `viewer` role | read POIs 200; create POI or capture 403 | 200 (4 POIs); 403; 403 | PASS | | 18.4–18.6 |
| 18.7–18.8 | Admin of another organization | venue A 404; list `[]` | 404; `[]` | PASS | | 18.7, 18.8 |
| 18.9–18.10 | Service-account separation | worker token on user endpoints 403; user token on `/internal/jobs/claim` 403 | 403; 403 | PASS | | 18.9, 18.10 |
| 18.11 | Refusals audited | DENIED rows naming venue B | 2 `venue.access DENIED` rows | PASS | | 18.11 |
| 19.1–19.2 | Create a public link | secret returned, stored only as a hash | 201 `chl_…`; 0 rows contain it | PASS | | 19.1, 19.2 |
| 19.3–19.4 | Anonymous viewer | token scoped to venue A; venue, POIs and search readable | 200 `cvt_…`, venue A; 200 / 200 (4 POIs) / 200 | PASS | | 19.3, 19.4 |
| 19.5 | Public reconstruction load | 200 | 404, no reconstruction | **BLOCKED** | step 13 | 19.5 |
| 19.6–19.8 | Public token limits | write 403; venue B 404; captures 403 | 403; 404; 403 | PASS | | 19.6–19.8 |
| 19.9–19.10 | Revocation | secret and token stop working; unknown secret rejected | revoke 204; exchange 404; issued token 401; unknown secret 404 | PASS | | 19.9, 19.10 |
| 20 | Create a regional rescan | rescan capture against a FINALIZED parent | None exists. A request naming a non-existent parent is refused: 404 "scan version not found". | **BLOCKED** | FINALIZED `scan_version` (step 12) | 20 |
| 20.1 | Rescan region validation | 400 for a 2-point region | 400 | PASS | | 20.1 |
| 21 | Scan-version history | versions with parent links | 200 `[]`: nothing could be finalized | **BLOCKED** | step 12 | 21 |
| 21.1–21.2 | POI version history | update creates v2; v1 kept and immutable | v1 `Cafe` z=-4, v2 `Cafe & bakery` z=-4.5. Database trigger rejects the UPDATE: "poi_version … is immutable". | PASS | | 21.1, 21.2 |
| 22 | Audit log (admin API) | covers the workflow | 200, 45 entries. Covers venue, floor, capture, media accept and reject, every pipeline transition, `job.claim`, POI create and update, public link create/exchange/revoke, and `venue.access` DENIED. | PASS | | 22 |
| 22.1 | Venue-scoped audit (operations dashboard) | manager sees venue A entries | 200, 41 entries | PASS | | 22.1 |
| 22.2 | Audit log immutable | UPDATE rejected | Database trigger: "audit_log is append-only" | PASS | | 22.2 |

**Audit rows at the end of the run** (`action:outcome=count`):

- `capture.create:SUCCESS=1`, `capture.upload_complete:SUCCESS=1`, `capture.start_processing:SUCCESS=1`
- `floor.create:SUCCESS=1`
- `job.claim:SUCCESS=7`
- `media.upload_init:SUCCESS=3`, `media.upload_complete:SUCCESS=3`, `media.accept:SUCCESS=1`, `media.reject:FAILURE=2`
- `pipeline.start:SUCCESS=1`, `pipeline.stage_succeeded:SUCCESS=5`, `pipeline.stage_failed:SUCCESS=2`,
  `pipeline.failed:SUCCESS=2`, `pipeline.retry:SUCCESS=1`, `pipeline.pii_purged:SUCCESS=1`
- `poi.create:SUCCESS=4`, `poi.update:SUCCESS=1`
- `public_link.create:SUCCESS=1`, `public_link.exchange:SUCCESS=1`, `public_link.revoke:SUCCESS=1`
- `venue.access:DENIED=4`
- `venue.create:SUCCESS=2`

### 4.3 Browser (real web app, real API, real Keycloak)

| # | Scenario | Expected | Actual | Result | Blocked dependency | Evidence |
|---|---|---|---|---|---|---|
| B1 | Open `/viewer?link=<secret>` | floors, POIs and reconstructions load; token sent only as `X-Chaya-Viewer-Token` | Link exchanged (200). Floors, reconstructions and POIs all 200. Every venue call carried `X-Chaya-Viewer-Token` and **no** `Authorization`. 4 POIs listed. | PASS | | `viewer-public.png`, B1 |
| B1.1 | Reconstruction state | a scene, or an explicit empty state | "No reconstruction available. This floor has not produced a viewable reconstruction yet." | PASS (UI) | 3D scene: CUDA chain | `viewer-public.png` |
| B2 | Search "restroom" in the viewer | "Accessible restroom" in the results | `GET /search` 200; results panel "No matches." | **FAIL** | (none: F1) | `viewer-search.png` |
| B3 | Route "Reception desk" → "Elevator A" | route drawn | `POST /navigation/routes` 404. The panel shows the API's reason. | **BLOCKED** | NAVIGATION_BAKING | `viewer-route.png` |
| B-A | Console errors (public) | none unexpected | 1 console line: Chromium's own log of the expected route 404. 0 unexpected. | PASS | | B-A |
| B4.1 | Sign-in redirect | Keycloak login; `response_type=code`, `code_challenge_method=S256`, `client_id=chaya-web` | exactly that, at `localhost:8081/realms/chaya/protocol/openid-connect/auth` | PASS | | B4.1 |
| B4 | Signed-in manager in the viewer | own venue, floors and POIs as a Bearer JWT | Login form submitted for the throwaway test manager, then back via `/auth/callback`. Every API call used `Authorization: Bearer`. Venues and POIs 200. "E2E Venue", 4 POIs, honest empty reconstruction state. | PASS | | `viewer-signed-in.png`, B4 |
| B-B | Console errors (signed in) | none | 0 | PASS | | B-B |

### 4.4 Coverage by area

| Area | Verdict |
|---|---|
| API | PASS for everything reachable. Structured, correct refusals where upstream data is missing. |
| Database | PASS: migrations, pgvector/pg_trgm, tenant scoping, immutability triggers (`poi_version`, `audit_log`) |
| Authentication | PASS: real Keycloak JWTs checked against JWKS; missing or tampered tokens rejected; browser PKCE login through `chaya-web` |
| Authorization | PASS: roles, venue scoping, organization isolation, service-account separation, public-token scope and revocation, least-privilege storage accounts (api, worker, recon, backup) |
| Object storage | PASS: raw and derived buckets, checksum-verified artifacts, private buckets, PII purge |
| Processing jobs | PASS for orchestration and **five real stages** (4 CPU + COLMAP pose estimation) across two workers, including retry. **BLOCKED** from `SPLAT_RECONSTRUCTION` on (CUDA). |
| Viewer | PASS for the public-link and signed-in flows, with honest empty states. **BLOCKED** for 3D splat rendering (no artifact). |
| Semantic search | **FAIL** with the model up (F1). PASS for the CLIP service in its container and for the lexical fallback. **BLOCKED** for auto-detected POIs. |
| Navigation | **BLOCKED**: no navigation graph without NAVIGATION_BAKING |
| Versioning | PASS for POI versioning. **BLOCKED** for scan versions, rescan and version history. |
| Audit logging | PASS |

## 5. Blocked dependencies

### 5.1 The root cause: no CUDA GPU

This is each worker's own toolchain report, from `stack.txt`:

| Stage | Needs | Here | Effect |
|---|---|---|---|
| POSE_ESTIMATION | COLMAP (GLOMAP optional); GPU optional | COLMAP 3.8 (CPU) in the toolchain worker | **Ran, SUCCEEDED** |
| **SPLAT_RECONSTRUCTION** | **CUDA device**, torch, gsplat (its rasteriser is CUDA-only) | Intel integrated graphics; no torch, no gsplat | **The first blocked stage.** Structured `DEPENDENCY_UNAVAILABLE`, retryable. |
| SEMANTIC_SEGMENTATION | the splat, plus torch, transformers, SegFormer | not reached | no per-point classes |
| GEOMETRIC_CLEANUP, PLANE_FITTING | the splat, plus Open3D | not reached | no cleaned splat, no planes |
| ARTIFACT_GENERATION | the splat (a CPU stage in the CPU worker) | not reached | no `.ksplat`, manifest or viewer bundle, so nothing for the viewer |
| SEMANTIC_INDEXING | the splat, plus torch, transformers, open_clip, Grounding DINO | not reached | no detected, embedded POIs |
| NAVIGATION_BAKING | planes, plus `recast-cli` | not reached | no navigation graph, so no routes |

Installing Open3D, transformers and the rest on CPU would not unblock anything: every stage after
`SPLAT_RECONSTRUCTION` consumes its splat. **Feeding the pipeline a fixture splat instead would be a mock of the
blocked stage, so it was not done.** (The worker's own CI smoke test uses a labelled fixture splat, to exercise
`ARTIFACT_GENERATION` in isolation. It is not presented as a reconstruction, and it is not used here.)

**What cannot be exercised positively without a SUCCEEDED run:**

- `scan-versions/finalize-current` (step 12)
- `reconstructions/latest` (13, 19.5)
- routing and route display (16, 17, B3)
- rescan (20)
- scan-version history (21)
- auto-detected POIs (14.2)

**To unblock:** run the toolchain worker on an NVIDIA host with the reconstruction toolchain installed
(DEPLOYMENT.md "GPU workers": `pip install chaya-worker[reconstruction]`, COLMAP/GLOMAP, CUDA, `recast-cli`), then
re-run the harness. Steps 10–17 and 19.5–21 are already written to PASS once the data exists.

### 5.2 Deviations from a production deployment

1. **The toolchain worker is a test-only image** (`infra/ci/worker-colmap.Dockerfile`): CPU COLMAP on top of the
   production worker. It plays the role of a GPU host's worker (the same stage split and the same restricted
   storage account), but it has no GPU.
2. **A test-only Keycloak client, `chaya-e2e`** (password grant), is used for the scripted API calls. The browser
   check uses the real `chaya-web` PKCE flow.
3. **The organization is created in SQL**, because no API exists for it (F3).
4. **The fixture is not committed**, because of the photographs' copyright (section 3).

## 6. Integration failures found and fixed

Only integration failures were fixed. No features were added.

### 6.1 Vision service loads the model once per concurrent request, and is OOM-killed

- **Symptom:** the vision container was OOM-killed during its first start, under a 2 GiB limit and then under
  3 GiB. The previous validation attempt hit the same thing: it hung the Docker engine twice, was put down to a
  small VM, and fell back to running vision on the host.
- **Cause:** `ClipTextEncoder._ensure_loaded()` had no lock, and FastAPI runs sync handlers on a thread pool. Every
  request that arrived while the model was still loading saw `_model is None` and **started its own load**. That
  includes Docker's HEALTHCHECK every 30 s, which fires during a first-start weight download that takes longer
  than that, and search requests too.
- **Measured** in the vision image:
  - one load: 11 s, peak 1,270 MiB;
  - four concurrent readiness calls: 46 s each, memory pinned at the 3,072 MiB limit.
- **Fix (`services/vision/app/clip_text_encoder.py`):**
  - a lock with a double-checked load, so the model is loaded exactly once;
  - readiness answers `503 "… is loading"` immediately while a load is in progress, instead of queueing;
  - `embed()` waits for the single load.
- **Verified:**
  - A new unit test fails on the old code and passes on the fix; the vision suite has 8 passed.
  - Four concurrent readiness calls under a 2 GiB limit: one load (12 s), three immediate "loading" answers, peak
    1,618 MiB, no OOM.
  - In the final run, vision started from an **empty** model cache under its own HEALTHCHECK: 605 MB download,
    10 probes, no OOM, 0 restarts. It served every embedding in the run.

### 6.2 POSE_ESTIMATION on a CPU host is OOM-killed by COLMAP's default thread count

- **Symptom:** the stage supports CPU hosts (it passes `--SiftExtraction.use_gpu 0` when `nvidia-smi` is absent),
  but COLMAP was killed during feature extraction under both a 2 GiB and a 5 GiB limit.
- **Cause:**
  - COLMAP starts one SIFT thread per host core, and each CPU thread holds about 450 MiB for a 1600×1200 frame.
    Measured: 1 thread, peak 909 MiB; 4 threads, peak 2,269 MiB.
  - On this 18-core host that is about 8.5 GiB.
  - COLMAP ignores the container's CPU set: with `--cpuset-cpus 0-3` (3 GiB limit) it was still killed.
- **Fix (`services/reconstruction`):**
  - a `COLMAP_NUM_THREADS` setting (`Settings.colmap_num_threads`), passed as `--SiftExtraction.num_threads` and
    `--SiftMatching.num_threads`;
  - the default (-1) passes nothing, so commands on GPU hosts are unchanged;
  - the value is recorded in the stage's configuration snapshot.
- **Verified:**
  - New unit test: no flags by default, both flags when set. The worker suite has 117 passed, 12 skipped.
  - With `COLMAP_NUM_THREADS=2` the stage succeeded in the run: 77 s, 11 of 11 frames registered.
  - The standalone run peaked at 1,214 MiB.
- **Recommendation (not changed):** set `COLMAP_NUM_THREADS` on any CPU-only worker. The default still
  over-allocates on many-core hosts without a GPU.

### 6.3 Earlier the same day, re-verified here

Both fixes below were made by the previous validation attempt and are part of the working tree under test.

- **The vision image did not own its model-cache directory** (`services/vision/Dockerfile`). A named volume mounted
  at `/home/vision/.cache` came up root-owned, so the weight download failed with `Permission denied`.
  - **Fix:** create the directory, owned by `vision`, in the image.
  - **Verified again:** the final run started from a fresh volume and downloaded the weights.
- **The public-viewer token was sent in the wrong header** (`apps/web/lib/session.ts`, `capture-api.ts`,
  `reconstruction-api.ts`). The web app sent the opaque token as `Authorization: Bearer`, which the API parses as a
  JWT and rejects with 401. The API expects `X-Chaya-Viewer-Token`.
  - **Fix:** send the header that matches the active session.
  - `e2e/viewer.spec.ts` now asserts the correct header.
  - **Verified again:** B1 checks the header on every real request. Lint, typecheck, 61/61 unit tests and 6/6
    Playwright tests pass.

### 6.4 Test harness defect (not a product bug)

The first run of this validation had a third FAIL: step 22, admin audit log, HTTP 401.

- **Cause:** the harness reused a user access token beyond its 300 s lifespan. Keycloak's session records show the
  admin token issued at 18:44:24; step 22 ran at about 18:51:15, after it expired, including Spring's 60 s clock
  skew. The API was right to refuse it.
- **Fix:** the harness now re-issues a user's token shortly before it expires, as the web app's silent renew does.
- **Result:** the run was repeated **from a clean environment**; step 22 passes. The numbers in this document come
  from that clean run only.

## 7. Open findings (not fixed)

- **F1: manually created POIs are never embedded, so semantic search cannot find them.** This causes the 3 FAILs
  (14.1, 15, B2).
  - **Update, same day, after this validation:** the missing embedder was built (`PoiEmbeddingService` /
    `PoiEmbeddingBackfill`, docs/search.md "Manual POIs"). It was verified by the API test suite and by
    docs/BENCHMARKS.md Benchmark 3, where 30 new manual POIs were searchable by meaning within 7.9 s.
  - **This E2E run was not repeated** with it: the FAILs above are what this run observed.
  - `PoiService` writes `poi_version` without an embedding; only `SEMANTIC_INDEXING` sets one.
  - The schema expects an asynchronous embedder that does not exist:
    - V5 says the embedding "happens asynchronously after creation";
    - the immutability trigger allows exactly that one update;
    - `poi_version_pending_embedding_idx` indexes the backlog;
    - the operations dashboard counts POIs "awaiting embedding".
  - Result: with the vision service **up**, search returns `matchType=embedding` and no results. The same POI is
    found only when vision is **down** (lexical fallback). That is backwards for users.
  - Writing that embedder is new functionality, and involves a ranking decision: label text embeddings are
    compared text-to-text, while detected POIs carry CLIP *image* embeddings compared text-to-image. So it is left
    open.
- **F2: `venue-manager` cannot create a venue.** Venue creation is admin-only (3.0), so step 3 was done as an
  admin. This looks intentional.
- **F3: no organization API.** Organizations are created in SQL, as documented in DEPLOYMENT.md §3.
- **F4: Redis is provisioned but unused** (documented in the compose file).
- **F5: the privacy stage over-masks architecture.**
  - On 11 photographs of a building with no people, the Haar face detector reported **55 faces**: windows and
    dormers.
  - The fail-closed policy escalated 10 of the 11 frames to solid-fill blocks covering large parts of the façade
    (`PRIVACY_REPORT` totals: `face=55`, `escalated_frames=10`).
  - Pose estimation still registered every frame here. On real venues, masking this much texture will cost
    reconstruction quality.
  - The report itself says detection is classical CV and "not a guarantee". Improving the detector is a
    product/model change, so it is left open.
  - A side-by-side comparison was reviewed locally but is not committed (copyright of the photographs).

## 8. Reproducing

1. Follow section 2 on a host with Docker and about 5 GiB free for the stack.
2. On an NVIDIA host with the reconstruction toolchain, replace the toolchain worker with a real GPU worker
   (DEPLOYMENT.md "GPU workers"). The same harness then exercises steps 10–21 positively.

## 9. Files added or changed

| File | Kind | Why |
|---|---|---|
| `services/vision/app/clip_text_encoder.py`, `services/vision/tests/test_main.py` | fix + test | 6.1 |
| `services/reconstruction/chaya_worker/settings.py`, `…/stages/pose_estimation.py`, `tests/unit/test_units.py` | fix + test | 6.2 |
| `services/vision/Dockerfile` | fix (earlier attempt) | 6.3 |
| `apps/web/lib/session.ts`, `apps/web/lib/capture-api.ts`, `apps/web/lib/reconstruction-api.ts`, `apps/web/e2e/viewer.spec.ts` | fix + test (earlier attempt) | 6.3 |
| `infra/ci/worker-colmap.Dockerfile` | test infrastructure | toolchain worker with CPU COLMAP (5.2) |
| `infra/ci/docker-compose.e2e.yml` | test infrastructure | vision, web and the toolchain worker; stage split; memory limits |
| `scripts/e2e/e2e_validate.py` | test harness | scripted workflow; pose checks; token renewal (6.4); redacted secrets |
| `scripts/e2e/viewer_check.cjs` | test harness | browser checks, including the Keycloak PKCE sign-in |
| `docs/e2e-evidence/*` | evidence | see the top of this document. Logs are `.txt`, because `*.log` is gitignored. |
| `scripts/e2e/physical_pipeline.py` | test harness (2026-09-28) | physical-pipeline driver (section 0) |
| `docs/e2e-evidence/physical-*` | evidence (2026-09-28) | section 0 |
| `packages/contracts/fixtures/viewer-scene/*`, `services/reconstruction/tests/unit/test_viewer_scene_fixture.py` | FORMAT VALIDATION fixture + test (2026-09-28) | section V.1 |
| `apps/web/lib/device-profile.ts` | fix (2026-09-28) | V.4: the desktop viewer drew nothing |
| `apps/web/lib/viewer-scene-fixture.test.ts`, `apps/web/e2e/viewer-fixture.ts`, `apps/web/e2e/ksplat-viewer.spec.ts`, `apps/web/e2e/viewer.spec.ts` | tests (2026-09-28) | V.2 |
| `scripts/e2e/viewer_format_validation.py`, `scripts/e2e/viewer_format_check.cjs` | test harness (2026-09-28) | V.3 |
| `docs/e2e-evidence/viewer-format-*` | evidence (2026-09-28) | section V |
