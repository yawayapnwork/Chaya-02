# Chaya 02 technical benchmark suite

Five benchmarks, each asking whether a part of Chaya 02 does better than the obvious alternative. The results,
and what they do and do not show, are in [docs/BENCHMARKS.md](../docs/BENCHMARKS.md).

## Rules

**Every number is a `Metric` with a `kind`** (`common/results.py`). The schema rejects a value without one.

| kind | meaning |
|---|---|
| `measured` | produced by running real code on the stated input during the run |
| `estimate` | computed from measured values and configured constants; the note gives the formula |
| `configuration` | a setting of the system or of the benchmark |
| `assumption` | taken as given, for example a dataset standing in for real venues; the note says what it stands in for |
| `reference` | an analytic comparison, for example the score of a random ranking |
| `unavailable` | could not be measured here; the note says why, and `command` says how to measure it |

**No number is invented.** Where the real data does not exist yet, the benchmark reports `unavailable` with the
exact command to run once it does.

## Layout

| Path | What |
|---|---|
| `common/results.py` | metric schema, run writer (environment, git state, input checksums) |
| `common/stack.py` | access to a running stack: API, Keycloak, Postgres, containers |
| `b1_capture_path/run.py` | planner vs recon lap vs naive routes (wraps `PlannerBenchmark`) |
| `b1_capture_path/reconstruction_coverage.py` | floor-plan coverage of real reconstructions (for when paired captures exist) |
| `b2_geometry_cleanup/run.py` | cleanup methods on a real trained splat, plus a COLMAP-evidence proxy |
| `b3_semantic_search/run.py`, `dataset.json` | semantic vs lexical search through the real API |
| `b4_navigation/run.py` | STANDARD vs STEP_FREE routes through the real router |
| `b5_incremental_rescan/run.py` | alignment accuracy under a known misalignment, and rescan scope |
| `docker/open3d.Dockerfile` | worker package + Open3D (CPU), for B2 and B5 |
| `tests/` | tests of the framework itself: `python -m pytest benchmarks/tests` |
| `results/<benchmark>/<timestamp>.json` | one file per run; `latest.json` is the newest |

## Running

Prerequisites:

- The E2E stack from docs/E2E_VALIDATION.md section 2, for B3 and B4. `<env>` is that stack's env file.
- Python 3.12 with `requests`, `boto3` and `numpy`; the reconstruction service's venv has them.
- Docker, for B1 (when Maven is not installed) and for B2/B5 (Open3D).

```bash
python benchmarks/b1_capture_path/run.py
python benchmarks/b3_semantic_search/run.py --env-file <env> --reps 3
python benchmarks/b4_navigation/run.py --env-file <env> --synthetic

docker build -f benchmarks/docker/open3d.Dockerfile -t chaya-bench-open3d .
docker run --rm -v "$PWD:/repo" -v <data>:/data:ro -w /repo chaya-bench-open3d \
  python benchmarks/b2_geometry_cleanup/run.py --ply /data/playroom/point_cloud.ply --sfm-points /data/castle-sfm/points3D.txt
docker run --rm -v "$PWD:/repo" -v <data>:/data:ro -w /repo chaya-bench-open3d \
  python benchmarks/b5_incremental_rescan/run.py --sfm-points /data/castle-sfm/points3D.txt
```

**Data, downloaded at run time and never committed:**

- `castle-sfm/points3D.txt`: COLMAP run on OpenMVG's Sceaux Castle photographs, exactly as in
  docs/E2E_VALIDATION.md section 2 (the fixture video, then `colmap feature_extractor`, `exhaustive_matcher`,
  `mapper`, and `model_converter --output_type TXT`).
- `playroom/point_cloud.ply`: `https://huggingface.co/datasets/Voxel51/gaussian_splatting/resolve/main/FO_dataset/playroom/point_cloud/iteration_30000/point_cloud.ply`.
  The dataset card says Apache-2.0; the underlying Deep Blending imagery has its own terms.
