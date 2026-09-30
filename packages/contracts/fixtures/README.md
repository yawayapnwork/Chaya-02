# Test fixtures

Every file here is **committed** so that a clean checkout can run the test suites that load it. None of them is a
capture, a reconstruction of a real venue or a measurement: each is either a hand-chosen mathematical fixture or the
output of production code on a synthetic input, written by the generator next to it.

A fixture that is only on one workstation is not evidence. `scripts/ci/check-fixtures.sh` fails if any file under this
directory (or `services/reconstruction/tests/fixtures`) is present but git-ignored or untracked, or if a file a test
loads is missing from the index; the `frontend` and `python` workflows run it before their tests.

| Fixture | What it is | Why it is committed | Consumers | Pinned to production code by |
|---|---|---|---|---|
| `ksplat/cloud.json` | 3 hand-chosen Gaussians (the input) | Ground truth the Node test derives expected values from, independently of Python | `apps/web/lib/ksplat-compat.test.ts`, `services/reconstruction/tests/unit/test_ksplat_fixture.py` | — (it is the input) |
| `ksplat/scene.ksplat` (5 KB) | `chaya_worker.ksplat.write_ksplat(cloud)` | The viewer-compatibility proof: the pinned GaussianSplats3D's `KSplatLoader` must read the bytes the worker writes | `ksplat-compat.test.ts` | `test_ksplat_fixture.py` (byte equality with the encoder's output today) |
| `ksplat/scene.ply` (0.6 KB) | `chaya_worker.ply.write_ply(cloud)` | The library's own `PlyLoader` route for the same cloud, the cross-check | `ksplat-compat.test.ts` | `test_ksplat_fixture.py` (byte equality) |
| `viewer-scene/scene.ply` (246 KB) | A synthetic 6 x 4 m floor with three 2 m pillars (3,701 Gaussians) in a known non-identity frame | The viewer FORMAT-VALIDATION scene: Playwright renders it and checks pixels and placement, so it needs a recognisable, placeable scene rather than a handful of points | `apps/web/lib/viewer-scene-fixture.test.ts`, `apps/web/e2e/viewer-fixture.ts` (`viewer.spec.ts`, `ksplat-viewer.spec.ts`), `scripts/e2e/viewer_format_*` | `test_viewer_scene_fixture.py` (values against the generator; SHA-256 against `fixture.json`) |
| `viewer-scene/scene.ksplat` (164 KB) | The ARTIFACT_GENERATION stage's KSPLAT output for `scene.ply` | What the viewer downloads in the specs above | same | `test_viewer_scene_fixture.py` (byte equality with the stage's output today) |
| `viewer-scene/fixture.json` | Similarity, control points, POIs (exact by construction), SHA-256 of the two files | Expected values for the placement assertions | same | `test_viewer_scene_fixture.py` |
| `navmesh/*` | Real `chaya-navmesh` (Recast/Detour) output for `services/reconstruction/tests/fixtures/navmesh/room_with_doorway.obj` | The API ingests and routes on real Detour output without building the native tool | `PipelineControlPlaneTest`, `ScanVersionLineageTest` | `tests/navmesh/test_recast_fixture.py` (needs the built tool) |
| `synthetic-calibration.json` | A hand-chosen similarity and points | The same frame maths is checked in Python, Java and TypeScript | `test_frames.py`, `SimilarityTest`, `coordinate-frame.test.ts`, … | `generate_synthetic_calibration.py` |
| `search/clip-vit-b32-openai.json` | Real CLIP ViT-B/32 vectors (see its `provenance`) | Ranking tests with real embeddings without running CLIP | `SemanticSearchRankingTest` | its `provenance` field |

`*.ksplat` and `*.ply` are git-ignored repository-wide (captures and reconstructions must never be committed); the root
`.gitignore` re-includes exactly the four files above by path, and the `.gitattributes` in `ksplat/` and `viewer-scene/`
mark them binary so line-ending conversion never touches them.

## Regenerating

If a "pinned to production code" test fails, the production code changed. Regenerate, then re-run the consumers:

    services/reconstruction/.venv/Scripts/python packages/contracts/fixtures/ksplat/generate_ksplat_fixture.py
    services/reconstruction/.venv/Scripts/python packages/contracts/fixtures/viewer-scene/generate_viewer_scene.py

(`python` instead of the venv path on Linux/macOS.) Both generators are deterministic: run from scratch on 2026-09-30
they reproduced the committed bytes exactly.
