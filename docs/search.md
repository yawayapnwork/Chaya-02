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
3. Localises each 2D detection in 3D by finding which of the trained splat's own points reproject inside
   the detection's box in that camera (`chaya_worker.stages.semantic_indexing
   .associate_detection_with_geometry`, reusing the exact camera-projection math SEMANTIC_SEGMENTATION
   already uses and tests) and taking their centroid.
4. Crops the detection and embeds it with CLIP's **image** tower (`chaya_worker.clip_embeddings.ClipEmbedder`,
   `ViT-B-32`/`openai`, 512-d, L2-normalised).
5. Clusters detections of the same physical object seen across multiple frames (`cluster_by_distance`). Two
   detections merge only if they have the **same detector label and** lie within `object_cluster_distance_m` (0.75 m)
   of the cluster's running centroid. Proximity alone merged neighbours: a fire extinguisher 0.3 m from an exit sign
   became one POI with the average of two unrelated crops (docs/ADVERSARIAL_REVIEW.md CV-2). The label is a merge
   guard only. The cost is the other way round: an object labelled "sofa" in one frame and "couch" in another (the
   prompt has both) stays two POIs, a duplicate rather than a lost object.
6. Publishes a `DETECTED_OBJECTS` artifact: canonical position, image embedding, confidence, and the bounding box
   together with the frame it was measured in (both from the most confident member).

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

   Order: relevant results first, then text similarity, then image similarity, then id.
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
   - Each result keeps its spatial metadata: `floorId`, the canonical `x/y/z`, and for detected objects
     `detectionConfidence`, `boundingBox` and `sourceFrame`.
5. If `services/vision` is unavailable, the search **degrades** to Postgres trigram similarity on the label text
   (`pg_trgm`) rather than failing, and `matchType` reports `"lexical_fallback"` so a caller never mistakes it for
   semantic matching.
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
