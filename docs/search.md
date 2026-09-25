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
4. Crops the detection and embeds it with real CLIP image embeddings (`chaya_worker.clip_embeddings
   .ClipEmbedder`, `ViT-B-32`/`openai`, 512-d).
5. Clusters detections of the same physical object seen across multiple frames by **3D proximity only**
   (`cluster_by_distance`) -- never by matching label text, since label text is not the search mechanism.
6. Publishes a `DETECTED_OBJECTS` artifact (position, embedding, confidence, bounding box, source frame).

The worker has no database access (see ARCHITECTURE.md). `dev.chaya.api.pipeline.PipelineService
#ingestDetectedObjects` reads that artifact when SEMANTIC_INDEXING succeeds and turns each object into a
real `poi` + `poi_version` row (`source = 'AUTO_DETECTED'`, `pipeline_run_id` set for provenance,
`embedding`/`embedding_model` set) -- see `V12__semantic_search.sql`.

## Manual POIs: the embedding backfill

A POI placed or edited by venue staff (`PoiService`) is stored without an embedding, and pgvector search cannot see
it until it has one. `dev.chaya.api.search.PoiEmbeddingBackfill` runs every
`chaya.search.embedding-backfill-interval` (5 s) and asks `PoiEmbeddingService` to embed up to
`chaya.search.embedding-backfill-batch` (50) pending POI versions:

- **Which versions:** only the latest version of a live (not deleted) POI whose embedding is NULL. That is the same
  set the operations dashboard counts as "awaiting embedding". Superseded versions are never searched, so they are
  never embedded.
- **What text:** the POI's own metadata: label, category and tags, joined and de-duplicated case-insensitively
  (`PoiEmbeddingService.embeddingText`). Tags are where staff enter alternative names. There is no synonym table.
  Measured on docs/BENCHMARKS.md B3, this beats embedding the label alone on every query type.
- **Which model:** the same CLIP text tower as queries (`TextEmbeddingClient` → services/vision). The model id
  services/vision reports is stored in `embedding_model`, as for detected objects.
- **How it writes:** the vision call happens outside any transaction. The write is `UPDATE ... WHERE embedding IS
  NULL`, which is the one update the `poi_version` immutability trigger allows, and a no-op if another API instance
  got there first. An embedding is never overwritten.
- **When vision is down:** nothing is written. The pass stops, logs once, and the backlog is worked off when vision
  returns. Search meanwhile falls back to lexical matching as before.

Manual POIs therefore carry CLIP **text** embeddings (compared text-to-text), while detected objects carry CLIP
**image** embeddings (compared text-to-image). The two similarity ranges differ, and ranking both in one list has
not been measured yet: no venue has detected objects (docs/BENCHMARKS.md B3, "Unavailable").

## Query: text/voice to ranked results

1. The browser (`apps/web/components/SemanticSearchPanel.tsx`) sends a typed or voice-transcribed query
   string to `GET /api/v1/venues/{venueId}/search`. The Web Speech API
   (`apps/web/lib/voice-input.ts`) only ever produces that text string; it is never itself the matching
   mechanism.
2. `dev.chaya.api.search.SemanticSearchService` embeds the query with the **same** CLIP model, via
   `TextEmbeddingClient` -> `services/vision`'s `POST /v1/embed-text` (CLIP's text tower). This -- not a
   hardcoded synonym table -- is why "couch", "sofa" and "seating" all rank a labelled-"sofa" POI highly:
   CLIP's text and image towers were trained so semantically related text and images land near each other
   in the same space.
3. Every POI in scope (the venue/organization via `TenantGuard`, an optional floor, an optional accessibility filter
   on `attributes->>'accessible'`) is scored by real pgvector cosine similarity (`embedding <=> query`), and the
   top k by similarity are returned in that order. Scoring the whole scope, rather than asking the HNSW index for
   the top k only, is what makes step 4 possible; a venue's POIs number in the hundreds to thousands.
4. **Relevance.** CLIP places almost any two short phrases at cosine ~0.8, so the nearest POIs come back even for
   things the venue does not have ("swimming pool" -> "Water fountain"). A match of a **manually placed** POI counts
   as a result only if its similarity exceeds the query's **mean similarity to all searched POIs of the same source**
   by `chaya.search.relevance-min-margin` (0.078). A real match stands out from the rest of the venue; a query for
   something absent is about equally close to everything.
   - If nothing clears the margin, `results` is **empty** and up to `chaya.search.closest-matches` (3) nearest
     candidates are returned separately in `closestMatches`. The web panel shows them as "Nothing matching X
     here. Closest: ...", never as matches, so an answer that fell below the bar is still one click away.
   - `relevance` says what happened: `FILTERED`, `UNFILTERED` or `LEXICAL`. Each result carries its
     `relevanceMargin`.
   - **Not judged**, and returned as plain top-k (`UNFILTERED`):
     - detected objects, which carry CLIP image embeddings, on a different scale, for which no threshold has been
       calibrated;
     - any scope with fewer than `chaya.search.relevance-min-pois` (8) comparable POIs, where the mean is not
       meaningful.
   - **How 0.078 was chosen:** on a separate calibration venue (`benchmarks/b3_semantic_search/calibration.json`,
     `calibrate.py`), by balanced accuracy, from three candidate scores: raw cosine, margin over the mean, and
     z-score. It was never tuned on the test venue. The trade-off is measured in docs/BENCHMARKS.md B3: some
     correct answers, mostly synonyms, no longer count as results but are offered as closest matches.
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
