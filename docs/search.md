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
3. Results are ranked by real pgvector cosine similarity (`embedding <=> query`, the
   `poi_version_embedding_hnsw_idx` HNSW index), scoped to the venue/organization (`TenantGuard`), an
   optional floor, and an optional accessibility filter (`attributes->>'accessible'`).
4. If `services/vision` is unavailable, the search **degrades** to Postgres trigram similarity on the
   label text (`pg_trgm`) rather than failing outright, and `SearchResponse.matchType` reports
   `"lexical_fallback"` so a caller never mistakes it for real semantic matching.
5. Every query is logged to `search_query` (venue-scoped, for the dashboard's zero-result/top-query views).

## Fine-tuning dataset

`chaya_worker.datasets` defines the on-disk format for project-specific annotations that a future
Grounding DINO fine-tuning run would train on: `manifest.json`, `images/`, `images.jsonl`,
`annotations.jsonl` (see `chaya_worker/datasets/scaffold.py`'s module docstring). It is independent of, and
does not require, the runtime SEMANTIC_INDEXING pipeline above. No fine-tuning has happened yet.
