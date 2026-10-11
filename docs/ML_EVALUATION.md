# Semantic models: baseline, pipeline and what is blocked (2026-10-11)

**Bottom line.** Every model Chaya runs is a **stock, publicly pretrained checkpoint**. No model has been fine-tuned by
this project, and nothing in this report claims an accuracy gain from training. What exists now:

- a reproducible dataset pipeline;
- a gated, smoke-tested fine-tuning harness for segmentation and detection (tiny random models only; no real
  checkpoint);
- a held-out baseline of the stock models on 102 human-annotated indoor images.

Fine-tuning is blocked by three missing prerequisites (section 6). Two changes did measurably move behaviour, and
neither is training:

- a reviewed class mapping for the segmentation model's label set;
- a production defect in the detection path, found by running it: an argument the current `transformers` rejects. A
  guard against boxes with empty labels was added too.

The "winning solution blueprint" is not in the repository. This report treats its promises as stated in the request: custom-trained
segmentation of floor/wall/furniture/clutter, and fine-tuned indoor detection and retrieval.

## 1. What runs today (verified by loading it)

| Stage | Model | Revision | Licence | Fine-tuned by Chaya |
|---|---|---|---|---|
| SEMANTIC_SEGMENTATION | `nvidia/segformer-b0-finetuned-ade-512-512` (ADE20K-150) | `489d5cd8…` | **NVIDIA SegFormer licence: research/evaluation only** | no |
| SEMANTIC_INDEXING (detection) | `IDEA-Research/grounding-dino-tiny` | `a2bb814d…` | Apache-2.0 | no |
| SEMANTIC_INDEXING (crops), services/vision (queries) | open_clip `ViT-B-32` / `openai` | fixed tag | MIT | no |

The licence of the default segmentation weights is a release blocker independent of accuracy. The model card points to
https://github.com/NVlabs/SegFormer/blob/master/LICENSE, which permits use only "for research or evaluation purposes".
A checkpoint fine-tuned from these weights is a derivative work under the same terms. `chaya_worker.training` therefore
refuses to fine-tune it without `--accept-base-license research-only`, and every SEMANTIC_LABELS artifact now records
`commercial_use: false`.

## 2. Evaluation data

`coco-indoor-eval` 1.0.0, SHA-256 `ff843cb160565e2a5fbce405b74ea5d3b04dd8956e44826a107b95851a8be827`. It is built by
`python -m chaya_worker.datasets.coco_import` with a selection rule fixed before any model ran:

- COCO 2017 val, panoptic annotations (CC BY 4.0);
- images whose own licence allows commercial use and derivatives;
- no person in the image;
- at least 2% floor and at least 2% wall;
- the first 102 by image id, which is every image that qualified.

It holds 567 boxes in 43 categories and a semantic mask per image. Ground truth is COCO's human annotation, mapped to
Chaya's buckets by a table written before evaluation (`COCO_PANOPTIC_TO_BUCKET`). It is an **evaluation-only** set:
every image is in the test split.

Limits:

- COCO is not a venue. It contains homes, kitchens and offices, but no lobbies, corridors or exit signs.
- 102 images and 21–60 boxes per class are a small sample.
- Grounding DINO's pre-training (GoldG) includes Visual Genome images, which overlap COCO, so the detector numbers may
  be optimistic.

There are no stairs boxes, and stairs cover only 28,882 pixels, so the stairs figures have very wide intervals.

## 3. Baseline results (measured; `benchmarks/results/b6_semantic_models/latest.json`)

Host: 18-core x86-64 CPU, no GPU, torch 2.14.0+cpu, transformers 4.57.1, open_clip 3.3.0. Seed 0. The intervals are
95% percentile bootstrap over images, n = 1000.

**Reproducibility:** the benchmark was run twice from the same command (`2026-10-11T122646Z.json` and
`2026-10-11T124800Z.json`; the second added the label-quality metrics). Every shared metric was identical; only the
timings differed. The elapsed time was 972 s and 1312 s, the second on a shared CPU.

### 3a. Segmentation (stock SegFormer, then the class mapping)

| Bucket | IoU, keyword mapping (before) | IoU, reviewed mapping (after) |
|---|--:|--:|
| floor | 0.700 [0.640, 0.758] | 0.701 [0.641, 0.758] |
| wall (incl. ceiling) | 0.699 [0.657, 0.738] | 0.699 [0.657, 0.738] |
| furniture | 0.594 [0.547, 0.638] | **0.606** [0.557, 0.651] |
| clutter | 0.467 [0.392, 0.537] | **0.479** [0.403, 0.548] |
| stairs | 0.256 [0.000, 0.376] | 0.256 [0.000, 0.376] |
| **mIoU** | 0.543 [0.479, 0.577] | **0.548** [0.483, 0.582] |
| pixel accuracy | 0.769 | 0.774 |

- **Change, after minus before:** +0.0048 mIoU, paired 95% CI [+0.0007, +0.0102]. The predictions are the same for
  both; only the label mapping differs. This is a small, real improvement of the mapping, not of the model. The ground
  truth was mapped with the same review rule, so read it as "the mapping is more consistent", not as independent
  evidence.
- 20 of the 150 ADE20K labels are mapped differently. The two that matter for the pipeline:
  - `bannister` and `escalator` were **clutter**, the one class geometric cleanup deletes when isolated, while
    `railing` was furniture;
  - `barrel` was furniture only because it contains "bar".
- **The confidence floor:** 81.4% of labelled pixels reach `semantic_confidence_min` = 0.5, and those are 82.6% accurate
  (77.4% over all pixels). The rest cast no vote in SEMANTIC_SEGMENTATION.
- **Failure cases:**
  - furniture/clutter confusion is the largest error (confusion matrix in the result file);
  - stairs are mostly missed: IoU 0.26 with an interval reaching 0;
  - ramps cannot be measured, because no ramp is labelled anywhere.

### 3b. Detection (stock Grounding DINO, production prompt and thresholds)

| Class (prompt phrases) | GT boxes | AP@0.5 | Precision @ 0.35 | Recall @ 0.35 |
|---|--:|--:|--:|--:|
| chair (`chair`) | 60 | 0.565 | 0.653 | 0.533 |
| couch (`sofa`, `couch`) | 31 | 0.721 | 0.605 | 0.742 |
| dining table (`table`) | 21 | **0.074** | 0.200 | 0.143 |
| potted plant (`plant`) | 29 | 0.641 | 0.588 | 0.690 |
| bench | 0 | not measurable | | |
| **mAP@0.5, 4 classes** | | **0.500** | | |

- Inference takes 8.1 s per image on this CPU.
- The other 13 prompt phrases (door, window, exit sign, reception desk, …) have **no ground truth** in COCO and are
  unmeasured.
- **"table" fails.** The detector puts "table" on many boxes that are not COCO dining tables (desks, counters, side
  tables) and misses most of those that are.
- **Label-quality failure (production impact):**
  - At the evaluation threshold of 0.10, 5,878 boxes came back with an **empty phrase** (no text token passed
    `text_threshold`).
  - Many others came back with **merged phrases** ("desk reception desk counter", "restroom sign exit sign").
  - At the production threshold 0.35 (485 boxes): **no empty phrase**, but **163 boxes (33.6%) carry a phrase that is
    not one prompt phrase** (several merged, or a word fragment). SEMANTIC_INDEXING uses that phrase as the POI's name
    and as its clustering key. So a third of auto-detected POIs would be named like "desk reception desk counter", and
    the same object seen under two merged phrases stays two POIs. **Not fixed**: choosing one phrase per box (for
    example the highest-scoring prompt phrase from the token logits) changes the detection contract and needs its own
    evaluation.
  - An empty phrase would have been passed straight through as the POI label, which `poi_version.label` (non-blank)
    refuses. That happens only below the production threshold here, for example if `OBJECT_DETECTION_BOX_THRESHOLD`
    is lowered. SEMANTIC_INDEXING now drops such boxes and counts them (`dropped_without_phrase`). This is a guard, not
    an observed production failure.
- **3D localization** is a separate measurement, and it is not available here: COCO has no 3D truth. It is covered only
  on synthetic scenes (`tests/unit/test_object_localization.py`).

### 3c. Retrieval (stock CLIP ViT-B/32; the query is the bare category name)

| Measurement | Value |
|---|--:|
| text → ground-truth crop: recall@1 / recall@5 | 0.535 / 0.744 |
| text → ground-truth crop: precision@5 / mAP | 0.395 / 0.383 |
| crop → category, zero-shot top-1 (43 categories) | 0.441 |
| text → **detector** crop (threshold 0.35): recall@5 / precision@5 / mAP | 0.667 / 0.293 / 0.408 |

These numbers keep detection quality separate from retrieval quality:

- the ground-truth crop rows measure CLIP alone;
- the detector crop rows measure what search would rank end to end, minus 3D.

## 4. Geometry cleanup

**Real data (unchanged, docs/BENCHMARKS.md B2).** Semantic-aware cleanup on a real splat still cannot be measured: it
needs SEMANTIC_SEGMENTATION labels on a reconstruction, and that needs a GPU pipeline run.

**Controlled synthetic scenes (new; `benchmarks/results/b2_geometry_cleanup_synthetic/latest.json`).**

- 5 seeds × 163,318 points with per-point ground truth.
- The scene has:
  - a floor, and a mezzanine 1.02 m up;
  - 6 stairs and a 1:12 ramp onto a dais;
  - walls and a ceiling;
  - furniture, including thin table legs and a bannister;
  - dense small clutter;
  - noise: 2.4k floaters, a 1.6k near-surface halo, and 40 floater clusters.
- Geometry, opacities and noise are **assumptions** (`SCENE_PARAMS`), so the results describe these structures only.
- PSNR/SSIM are not reported: there are no ground-truth renderings.

| Method (production settings) | Noise removed (recall) | Removed that is noise (precision) | Stairs kept | Ramp kept | Mezzanine kept | Thin furniture kept |
|---|--:|--:|--:|--:|--:|--:|
| opacity < 0.05 (not in the production chain) | 0.487 | 0.185 | 0.945 | 0.941 | 0.944 | 0.946 |
| statistical outlier | 0.555 | 1.000 | 1.000 | 1.000 | 1.000 | 1.000 |
| radius outlier | 0.848 | 0.9995 | 1.000 | 1.000 | 1.000 | 0.9998 |
| semantic-aware alone, oracle labels | 0.013 | 1.000 | 1.000 | 1.000 | 1.000 | 1.000 |
| production chain, oracle labels | 0.857 | 0.9995 | 1.000 | 1.000 | 1.000 | 0.9998 |
| production chain, labels with the stock model's **measured** confusion | 0.863 | **0.373** | **0.960** | 0.984 | 0.982 | **0.910** |

What this shows:

- **On these scenes the outlier filters do the work.** The production chain's statistical and radius steps remove 86%
  of the noise, and the removed points are almost all noise. Stairs, the ramp and the mezzanine survive.
- **Semantic-aware cleanup adds almost nothing with perfect labels.** A floater gets the bucket of the surface behind it,
  so it is rarely isolated "clutter".
- **With the stock model's error rates it removes real structure.** It took 4% of the stairs, 6% of the walls and 9% of
  the thin furniture, and its removals were 37% noise.
- The label errors were drawn independently per point, which is pessimistic: multi-view voting correlates and partly
  corrects them. So this is a risk to verify on a real labelled splat, not a verdict. Raising
  `SEMANTIC_MIN_VOTE_SHARE` (new; default 0 keeps today's behaviour) labels narrow winners `unknown`, which cleanup
  never removes.
- **Opacity thresholding** depends entirely on the assumed 10% of semi-transparent surface Gaussians. It is not part of
  the production chain.

## 5. What changed in the code

Pipeline and contracts:

- **Class mapping.** `chaya_worker.semantic_classes` has three mapping methods:
  - `canonical`, for checkpoints trained on Chaya's buckets (previously "furniture" mapped to clutter);
  - a reviewed ADE20K-150 table, versioned `2026-10-11.1`;
  - the keyword rule, as a fallback.

  SEMANTIC_LABELS records which mapping and which weights produced the labels, and says they are model predictions.
  Ramps map to floor at run time; stairs stay walkable. `SEMANTIC_MIN_VOTE_SHARE` marks narrow winners `unknown`, and the
  report gives the distribution of winning shares. The interfaces of the cleanup, splice and navigation stages are
  unchanged.
- **Provenance** (`chaya_worker.model_provenance`):
  - every SEMANTIC_LABELS, DETECTED_OBJECTS and indexing report carries the model id, the requested and resolved
    revision, the licence, and `fine_tuned`;
  - `fine_tuned` is true only for a local checkpoint with a valid `chaya_model_card.json`, which names the base model,
    the dataset version and SHA-256, the seed and steps, and was never trained on the test split.
- **Detector fixes** (both found by running the real code):
  - `transformers` ≥ 4.51 renamed `box_threshold` to `threshold`, and the worker image installs `transformers`
    unpinned, so `detector.detect()` would have failed with an unexpected-argument error. `post_process` now passes the
    argument under whichever name the installed version accepts;
  - boxes with an empty phrase are dropped and counted, instead of being published as a blank POI label. This is a
    guard: none occurred at the production threshold on this set.
- **Search index** (API, V35): when the CLIP model changes, the backfill re-embeds the text of every POI from another
  model. The trigger allows exactly that: a new vector of a different model. Renaming a vector's model and same-model
  overwrites are refused. Detection-crop vectors need a SEMANTIC_INDEXING re-run (docs/search.md, "Embedding model
  changes").

Data and training:

- **Datasets** (`chaya_worker.datasets`, schema `chaya-dataset/2`):
  - versioned manifest with source, licence, annotation method and preprocessing;
  - splits, content hashes, semantic masks, and a per-image licence;
  - **privacy evidence**: a captured frame enters only as the anonymised bytes of a fail-closed PRIVACY_PREPROCESS run
    (`capture_import`; there is no path for raw frames).

  The validator rejects:
  - malformed boxes, masks or classes;
  - duplicates, and split leakage (by capture group or by identical bytes);
  - missing annotations;
  - unapproved model proposals, because an unverified prediction is never ground truth.

  It warns on class imbalance. `--lock` records the dataset SHA-256, and training and evaluation refuse a dataset that
  changed since its lock.
- **Training** (`chaya_worker.training.segmentation`, `.detection`) refuses to start without:
  - a locked dataset with train and val splits;
  - an acknowledged base-model licence;
  - a GPU (`--allow-cpu` is for smoke tests only).

  It seeds everything, keeps the best epoch by validation metric, never reads the test split, and writes safetensors
  plus the model card.

## 6. What is blocked, and what unblocks it

| Promised capability | Blocked by | To unblock |
|---|---|---|
| Fine-tuned floor/wall/furniture/clutter segmentation | (1) No legally usable **indoor training masks**: COCO train images carry mixed, partly non-commercial licences, and no Chaya capture has been annotated. (2) **The base weights are research-only.** (3) **No GPU**: this host has integrated graphics only. | Annotate anonymised venue frames (`capture_import`, then masks), or license a dataset. Choose a permissively licensed base model. Then run `python -m chaya_worker.training.segmentation --dataset <locked> --base-model <id> --base-revision <sha> --out ckpt/` on a CUDA host and evaluate with B6 against this baseline. |
| Fine-tuned indoor detection (venue vocabulary: exit sign, reception desk, …) | (1) **No boxes for the venue vocabulary** in any usable dataset. COCO has instance boxes for only 6 of the 19 prompt phrases. (2) No GPU. | Annotate the venue phrases on anonymised frames, then `python -m chaya_worker.training.detection …`, then B6 `--det-model ckpt/`. |
| Fine-tuned CLIP for retrieval | No harness (out of scope: it would retrain the search embedding space), no indoor image–text pairs, no GPU | — |
| Real 3D localization accuracy | Surveyed object positions in a captured venue | a capture with control points |
| Semantic-aware cleanup on real data | SEMANTIC_LABELS for a real splat (a GPU pipeline run) | `python benchmarks/b2_geometry_cleanup/run.py --ply <splat> --labels <semantic-labels.json>` |

## 7. Commands

```bash
# Evaluation image (extends the vision service image; adds transformers 4.57.1 and the worker)
docker build --build-arg INSTALL_MODEL=true -t chaya-vision services/vision
docker build -f benchmarks/docker/ml-eval.Dockerfile --build-arg BASE=chaya-vision -t chaya-ml-eval services/reconstruction

# Evaluation dataset (downloads only the COCO val panoptic files, by HTTP range requests, and the 102 images)
python -m chaya_worker.datasets.coco_import --out /data/coco-indoor-eval
python -m chaya_worker.datasets.validate /data/coco-indoor-eval --lock     # expect sha256 ff843cb1…

# Baseline (CPU: about 16 min; models download once into the cache volume at the pinned revisions)
docker run --rm -v "$PWD:/repo" -v /data:/data -v <hf-cache-volume>:/home/vision/.cache -w /repo \
  -e PYTHONPATH=/repo/services/reconstruction chaya-ml-eval \
  python benchmarks/b6_semantic_models/run.py --dataset /data/coco-indoor-eval --bootstrap 1000 --seed 0

# A trained checkpoint, compared on the same lock
... run.py --dataset /data/coco-indoor-eval --seg-model ckpt/seg --seg-revision "" --det-model ckpt/det --det-revision ""

# Controlled cleanup comparison (Open3D image)
docker run --rm -v "$PWD:/repo" -w /repo -e PYTHONPATH=/repo/services/reconstruction chaya-bench-open3d \
  python benchmarks/b2_geometry_cleanup/synthetic.py --seeds 5 --confusion benchmarks/results/b6_semantic_models/<run>.json

# Tests
services/reconstruction: python -m pytest tests/unit/test_semantic_models.py tests/unit/test_datasets.py
chaya-ml-eval:           python -m pytest tests/ml          # training harness smoke tests on tiny random models
benchmarks:              python -m pytest benchmarks/tests/test_synthetic_cleanup_scene.py
```
