# Semantic object search

How a natural-language (or voice) query like "where's the couch" finds a real, spatially located object
in a venue's reconstruction.

## Pipeline: detection to storage

`chaya_worker.stages.semantic_indexing` (SEMANTIC_INDEXING, the pipeline's 12th and last implemented
stage) runs once per successful reconstruction:

1. Samples every `semantic_indexing_sample_every`-th registered, posed frame.
2. Runs Grounding DINO (`chaya_worker.grounding_dino.GroundingDinoDetector`) open-vocabulary detection
   against `Settings.object_detection_prompt`. The checkpoint (default `IDEA-Research/grounding-dino-tiny`)
   is stock and **not fine-tuned** on any Chaya data -- `GroundingDinoDetector.fine_tuned` is always
   `False` today and is reported honestly in every stage output. The checkpoint is fully configurable
   (`Settings.grounding_dino_model`) so a future fine-tuned checkpoint (trained on data in the format
   `chaya_worker.datasets` defines) is a config change, not a code change.
3. Localises each 2D detection in 3D on the surface its box actually shows, or not at all (see "Localization" below;
   `chaya_worker.object_localization`). A detection without depth evidence is dropped, never given a position.
4. Crops the detection and embeds it with CLIP's **image** tower (`chaya_worker.clip_embeddings.ClipEmbedder`,
   `ViT-B-32`/`openai`, 512-d, L2-normalised).
5. Clusters detections of the same physical object seen across multiple frames (`cluster_by_distance`). Two
   detections merge only if they have the **same detector label and** lie within `object_cluster_distance_m` (0.75 m)
   of the cluster's running centroid. Proximity alone merged neighbours: a fire extinguisher 0.3 m from an exit sign
   became one POI with the average of two unrelated crops (docs/ADVERSARIAL_REVIEW.md CV-2). The label is a merge
   guard only. The cost is the other way round: an object labelled "sofa" in one frame and "couch" in another (the
   prompt has both) stays two POIs, a duplicate rather than a lost object.
6. Publishes a `DETECTED_OBJECTS` artifact: canonical position, image embedding, confidence, the bounding box
   together with the frame it was measured in (both from the most confident member), and `localization` (below). The
   report counts the detections rejected for lack of depth evidence, by reason.

## Localization: from a 2D box to a 3D point

The trace, end to end:

| Step | Where | What |
|---|---|---|
| Detection | `GroundingDinoDetector.detect` | A box in undistorted-frame pixels, a label, a confidence. |
| Camera projection | `semantic_segmentation.project_points`, `object_localization.camera_depth` | Every Gaussian **centre** of the trained (or merged) splat is projected into that frame's pinhole camera; in front of the camera, inside the image and on an unmasked pixel (privacy masks) counts as visible. Its depth along the optical axis is converted to metres with the calibrated frame's scale. |
| Depth / 3D association | `object_localization.localize_detection` | See below. |
| Multi-view | `semantic_indexing.cluster_by_distance` | Same label within 0.75 m merges into one object; the position is the members' centroid. |
| POI | `PipelineService#insertDetectedPoi` | `poi_version` with x/y/z, `detection_confidence`, `bounding_box`, and (V27) `localization_status`, `localization_uncertainty_m`, `localization`. A malformed localization claim is skipped, never stored. |
| Search result | `SemanticSearchService` | The same fields, plus `spatialStatus` (below). |
| Viewer | `SemanticSearchPanel`, `ViewerWorkspace` | Selecting a result selects that POI in the viewer, which draws POIs only in the frame on screen. The panel states the location's status and spread (`lib/search-location.ts`). |

**How a box becomes a point** (review CV-1: the old rule took the median of every centre projecting into the box, so a
small object in front of a wall was placed on the wall):

1. **Occlusion.** Visible centres are binned into 16 x 16 px cells; each cell keeps its nearest depth. A centre more than
   the depth tolerance (0.15 m + 5 % of depth) behind its cell's nearest is hidden behind other geometry and is never
   used as surface.
2. **Depth layers.** The unhidden centres in the box, sorted by depth, are split wherever consecutive depths are more
   than the tolerance apart. A layer with at least 12 centres is a candidate surface.
3. **The object is the nearest layer that covers the box**: it must occupy at least 25 % of the box's occupied cells. A
   thin occluder in front (a pole, a chair arm) does not, and is passed over; the background behind the object is
   never reached.
4. **Position** = the median of that layer's centres. **Depth spread** = 1.4826 x MAD of its depths.

**Rejected, not placed:** `NO_DEPTH` (no visible centre in the box), `INSUFFICIENT_DEPTH` (fewer than 12 unhidden
centres, or no layer of 12), `AMBIGUOUS_DEPTH` (no layer covers 25 % of the box).

**Kept with each object:** `localization.status` = `MULTI_VIEW` when its members come from at least two frames, else
`SINGLE_VIEW`; `view_spread_m` (RMS distance of the members from the centroid: how much independent views disagree);
`depth_spread_m`; `uncertainty_m` = the larger of the two. These are **measured spreads, not errors against ground
truth**.

**Measured.** On a synthetic scene (`tests/unit/test_object_localization.py`: a 0.3 m object 2 m in front of a dense
wall), the placement is 0.05 m from the object's centre; the old rule's was 2.02 m. That is the only number there is.
Object-level placement accuracy on a real reconstruction has never been measured: SEMANTIC_INDEXING has never run on
one (review G-4).

**Not handled:**

- Gaussian footprints. Only centres are z-buffered; a large, sparse Gaussian can occlude pixels its centre does not
  reach. No depth is rendered.
- An object standing on a floor that recedes continuously in depth can share a layer with the floor around it; its
  horizontal position is then roughly right and its height too low.
- A detection fully hidden behind another surface is placed on that surface: the detector saw what was visible, and
  so does the placement.
- The thresholds (16 px, 0.15 m + 5 %, 12 centres, 25 %) were chosen on synthetic scenes, not calibrated.
- Detections of the same object under different labels ("sofa" / "couch") stay separate objects (see step 5).

The worker has no database access (see ARCHITECTURE.md). `dev.chaya.api.pipeline.PipelineService
#ingestDetectedObjects` reads that artifact when SEMANTIC_INDEXING succeeds and turns each object into a
real `poi` + `poi_version` row: `source = 'AUTO_DETECTED'`, `pipeline_run_id` for provenance, the floor of the capture,
the canonical position and coordinate frame, `detection_confidence`, and `bounding_box` with its `sourceFrame`. The
crop's vector is stored as `image_embedding`/`image_embedding_model` (V21__search_embedding_spaces.sql). A vector that
is not 512-d is skipped with a warning instead of failing the report. The object's text-space `embedding` is left
empty for the backfill below, exactly like a manual POI's.

## The embedding backfill (manual and detected POIs)

A POI placed or edited by venue staff (`PoiService`), or ingested from a detection, is stored without a text-space
embedding, and search cannot see it until it has one. `dev.chaya.api.search.PoiEmbeddingBackfill` runs every
`chaya.search.embedding-backfill-interval` (5 s) and asks `PoiEmbeddingService` to embed up to
`chaya.search.embedding-backfill-batch` (50) pending POI versions:

- **Which versions:** only the latest version of a live (not deleted) POI whose embedding is NULL. That is the same
  set the operations dashboard counts as "awaiting embedding". Superseded versions are never searched, so they are
  never embedded.
- **What text:** the POI's own metadata: label, category and tags, joined and de-duplicated case-insensitively
  (`PoiEmbeddingService.embeddingText`). Tags are where staff enter alternative names. There is no synonym table.
  Measured on docs/BENCHMARKS.md B3, this beats embedding the label alone on every query type. A detected object has
  only its label (the detection-prompt phrase Grounding DINO matched), so that is what is embedded.
- **Which model:** the same CLIP text tower as queries (`TextEmbeddingClient` → services/vision). The model id
  services/vision reports is stored in `embedding_model`.
- **How it writes:** the vision call happens outside any transaction. The write is `UPDATE ... WHERE embedding IS
  NULL`, which is the one update the `poi_version` immutability trigger allows, and a no-op if another API instance
  got there first. An embedding is never overwritten.
- **When vision is down:** nothing is written. The pass stops, logs once, and the backlog is worked off when vision
  returns. Search meanwhile falls back to lexical matching as before.

## How CLIP is used (verified)

| | Where | What |
|---|---|---|
| Model | worker `Settings.clip_model_name`/`clip_pretrained`; services/vision `CLIP_MODEL_NAME`/`CLIP_PRETRAINED` | open_clip `ViT-B-32` with the `openai` weights. Both sides report the id `open_clip:ViT-B-32:openai`. |
| Text tower | services/vision `ClipTextEncoder.embed` | Queries and every POI's text. open_clip's tokenizer lower-cases; the API lower-cases the query too. |
| Image tower | worker `ClipEmbedder.embed_images` | Detection crops only, with the model's own preprocessing. |
| Normalisation | both | Every vector is divided by its L2 norm before it leaves the encoder; a merged detection's mean vector is re-normalised. |
| Similarity | `SemanticSearchService` | pgvector cosine distance `<=>`; similarity = `1 - distance`, which for unit vectors is the dot product. |
| Dimension | `poi_version.embedding`, `image_embedding` | `vector(512)`, checked in the encoders, the backfill and ingestion; a wrong width is refused, never padded or truncated. |
| Model check | `SemanticSearchService` | Only rows whose `embedding_model` (and, for the image side, `image_embedding_model`) equals the model the query was embedded with are compared. Rows from any other model are left out, not scored in a foreign space. Nothing re-embeds them: the backfill only fills missing embeddings. |

**Known deviation, measured and not changed here.** open_clip 3.3 warns that `ViT-B-32` + `openai` is a QuickGELU
mismatch: the OpenAI checkpoint was trained with QuickGELU, `ViT-B-32` builds standard GELU. Worker and vision agree
with each other, so search is consistent, but neither runs the model exactly as trained. On B3's data
(`benchmarks/b3_semantic_search/quickgelu_check.py`) the as-trained `ViT-B-32-quickgelu` scores manual top-1 36/44
vs 35/44 and zero-shot crop labels 14/15 vs 13/15: within noise at this size. Switching would change every stored
vector's model id (and so, by the model check, hide them until re-embedded), which needs a re-embedding job that does
not exist yet.

## Query: text/voice to ranked results

1. The browser (`apps/web/components/SemanticSearchPanel.tsx`) sends a typed or voice-transcribed query
   string to `GET /api/v1/venues/{venueId}/search`. The Web Speech API
   (`apps/web/lib/voice-input.ts`) only ever produces that text string; it is never itself the matching
   mechanism.
2. `dev.chaya.api.search.SemanticSearchService` embeds the query with CLIP's text tower, via `TextEmbeddingClient` ->
   services/vision's `POST /v1/embed-text`, and learns which model produced it. There is no synonym table: "couch"
   finds "sofa" because CLIP puts related phrases near each other.
3. **Ranking.** Every POI in scope (the venue/organization via `TenantGuard`, an optional floor, an optional
   accessibility filter on `attributes->>'accessible'`) that has a text embedding from the query's model is scored
   (the whole scope, not the HNSW top k; a venue's POIs number in the hundreds to thousands).

   There are two embedding spaces, and they are never mixed (V21__search_embedding_spaces.sql):

   - **Text space: every POI, one scale.** `similarity` is the query's text-to-text cosine with the POI's own text: a
     manual POI's label, category and tags, a detected object's detector label. Results are ordered by it, so manual
     and detected POIs compete on equal terms. Before V21 a detected object was scored by its crop's image vector
     instead: a text query sits at cosine ~0.8-1.0 to POI texts but ~0.2-0.3 to even a matching crop, so detected
     objects sorted below every manual POI and were practically never returned (docs/ADVERSARIAL_REVIEW.md CV-4;
     measured in docs/BENCHMARKS.md B3: 0 of 22).
   - **Image space: detected objects only.** `imageSimilarity` is the query's text-to-image cosine with the crop. It
     is compared only with other text-to-image cosines, in the two ways CLIP's towers are meant to be compared:
     - *retrieval* (many images, one text): among results with the same text similarity (detected objects with the
       same label), the crop closer to the query comes first. "red chair" puts the red chair before the brown one.
     - *zero-shot classification* (one image, many texts): a detected object is also relevant if its crop is at least
       as close to the query as to **every** detector label in scope (needs at least 2 distinct labels). No threshold
       to calibrate: the competing labels are the reference. `matchedBy = IMAGE` marks results admitted only this way.

   - *lexical / fuzzy* (any POI): pg_trgm `strict_word_similarity` between the query and the POI's text (label,
     category, tags). A misspelt name is relevant if it reaches `chaya.search.lexical-min-similarity` (0.5):
     "recepton" scores 0.58 against the reception desk, which real CLIP ranks only fourth for that query; "fire
     extingusher" 0.75. Strict means whole words, so "sign" is 0.33 against "design studio" (the non-strict form
     gives 0.8). `matchedBy = LEXICAL` marks results admitted only this way. 0.5 was chosen on a handful of such pairs,
     not calibrated. Synonyms still come only from CLIP and the staff's tags: "settee" has no trigram in common with
     "couch".

   **Spatial validity.** Every result has a `spatialStatus`:

   | Status | Meaning |
   |---|---|
   | `VALID` | In the frame being viewed (the floor's current frame, or the selected version's); a detected object was also placed with depth evidence (`localization_status` set) |
   | `UNVERIFIED` | A detected object stored before depth-tested localization (V27): its position may be on whatever was behind it |
   | `STALE_FRAME` | In an older coordinate frame: the floor was re-reconstructed and the POI not re-placed |
   | `UNBOUND` | In no frame: the floor had no calibration when it was placed |

   **Order:** relevant results first; then **every `VALID` result before every other**; then the rank score; then image
   similarity; then id. The spatial tier is a sort key, not a weight, so no text, lexical or image similarity can lift
   a position that cannot be shown above one that can.

   **Rank score** (`rankScore`), within a tier:

   ```
   rank_score = similarity + 0.05 x lexical + 0.05 x (evidence - 1)
   evidence   = 1                                                 manual POI (staff placed it)
              = detection_confidence x 1.0 / 0.75 / 0.5           detected: MULTI_VIEW / SINGLE_VIEW / unverified
   ```

   The weights (`chaya.search.lexical-weight`, `chaya.search.evidence-weight`) are small on purpose: CLIP similarity
   stays the main signal, and they reorder near-ties (two chairs of the same label: the confidently detected,
   multi-view one first). The penalty is at most 0.05, and the lexical bonus at most 0.05. They are not calibrated:
   B3 has not been re-run with them. Every pre-existing real-CLIP ranking test still passes.
4. **Relevance.** CLIP places almost any two short phrases at cosine ~0.8, so the nearest POIs come back even for
   things the venue does not have ("swimming pool" -> "Water fountain"). A POI, manual or detected, counts as a result
   if its text similarity exceeds the query's **mean similarity to the distinct POI texts in scope** by
   `chaya.search.relevance-min-margin` (0.078), or if the image test above admits it. A real match stands out from the
   rest of the venue; a query for something absent is about equally close to everything.
   - The mean is over **distinct** text vectors, so 200 detected "chair"s count once, not 200 times; otherwise a
     venue full of chairs would pull the mean up to "chair" and filter out the query "chair" itself. For a venue whose
     POI texts are all different (the calibration and test venues) this is the same mean as before.
   - If nothing is relevant, `results` is **empty** and up to `chaya.search.closest-matches` (3) nearest
     candidates are returned separately in `closestMatches`. The web panel shows them as "Nothing matching X
     here. Closest: ...", never as matches, so an answer that fell below the bar is still one click away.
   - `relevance` says what happened: `FILTERED`, `UNFILTERED` or `LEXICAL`. Each result carries its
     `relevanceMargin` and, when filtered, `matchedBy` (`TEXT`, `IMAGE` or `TEXT_AND_IMAGE`).
   - **Not judged** (plain top-k, `UNFILTERED`): a scope with fewer than `chaya.search.relevance-min-pois` (8)
     distinct POI texts, where the mean is not meaningful.
   - **How 0.078 was chosen:** on a separate calibration venue (`benchmarks/b3_semantic_search/calibration.json`,
     `calibrate.py`), by balanced accuracy, from three candidate scores: raw cosine, margin over the mean, and
     z-score. It was never tuned on the test venue. The trade-off is measured in docs/BENCHMARKS.md B3: some
     correct answers, mostly synonyms, no longer count as results but are offered as closest matches. It was
     calibrated on manual POI texts; detected labels are short class names in the same text space, and B3's mixed
     condition measures how it behaves on them, but no separate calibration venue with detections exists.
   - Each result keeps its spatial metadata: `floorId`, the canonical `x/y/z`, `spatialStatus`, and for detected
     objects `detectionConfidence`, `boundingBox`, `sourceFrame`, `localizationStatus` and `localizationUncertaintyM`.
5. If `services/vision` is unavailable, the search **degrades** to Postgres trigram similarity on the label text
   (`pg_trgm`) rather than failing, and `matchType` reports `"lexical_fallback"` so a caller never mistakes it for
   semantic matching. Its results keep the same spatial tiers.
   - `HttpTextEmbeddingClient` puts a **circuit breaker** in front of the vision service. The first failed call
     (unreachable, an error, or a malformed reply) opens it. From then on every call fails immediately, without
     touching DNS or the network, so searches fall back in milliseconds and the POI embedding backfill (on
     Spring's shared scheduler thread) stops at once.
   - A probe on its own daemon thread checks `GET /health/ready` every `chaya.search.vision-probe-interval` (5 s)
     and closes the breaker once the model is loaded again.
   - Without the breaker, when the vision container was gone, the JVM re-resolved its hostname every 10 s (its
     negative DNS cache) and each lookup took ~3.7 s, so about one search in nine stalled
     (docs/BENCHMARKS.md B3).
6. Every query is logged to `search_query` with the number of **results** (not closest matches), so queries the
   venue has nothing for appear in the dashboard's zero-result view.

## Fine-tuning dataset

`chaya_worker.datasets` defines the on-disk format for project-specific annotations that a future
Grounding DINO fine-tuning run would train on: `manifest.json`, `images/`, `images.jsonl`,
`annotations.jsonl` (see `chaya_worker/datasets/scaffold.py`'s module docstring). It is independent of, and
does not require, the runtime SEMANTIC_INDEXING pipeline above. No fine-tuning has happened yet.
