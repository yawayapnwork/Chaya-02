-- Semantic object search: provenance for machine-detected POIs, and the ANN index over their CLIP
-- embeddings that V5__poi.sql's comment said would arrive "together with the search feature".
--
-- Design choice: a detected object becomes an ordinary poi/poi_version row (source = 'AUTO_DETECTED'),
-- not a separate table. poi_version.embedding/embedding_model already existed for exactly this ("filled
-- in once asynchronously after creation" -- V5's comment); adding a parallel detected_object table would
-- just duplicate that schema. The dataset used to eventually fine-tune the detector
-- (chaya_worker.datasets) is deliberately NOT modelled here: it is files (JSONL + images), the
-- conventional format for object-detection training data, not application state.

CREATE EXTENSION IF NOT EXISTS pg_trgm;

ALTER TABLE poi_version
    ADD COLUMN source                text NOT NULL DEFAULT 'MANUAL' CHECK (source IN ('MANUAL', 'AUTO_DETECTED')),
    ADD COLUMN detection_confidence  double precision CHECK (detection_confidence IS NULL OR detection_confidence BETWEEN 0 AND 1),
    -- Pixel bounding box in the source frame this detection came from: {x, y, width, height, frameWidth,
    -- frameHeight, sourceFrame}. Present only for AUTO_DETECTED rows that had one (see bbox_px in
    -- chaya_worker.stages.semantic_indexing's DETECTED_OBJECTS artifact).
    ADD COLUMN bounding_box          jsonb CHECK (bounding_box IS NULL OR jsonb_typeof(bounding_box) = 'object'),
    -- Which pipeline run produced an AUTO_DETECTED row; mandatory provenance for anything machine-written.
    ADD COLUMN pipeline_run_id       uuid REFERENCES pipeline_run (id) ON DELETE RESTRICT,
    ADD CONSTRAINT poi_version_auto_detected_has_provenance
        CHECK (source = 'MANUAL' OR pipeline_run_id IS NOT NULL);

-- Approximate nearest-neighbour search over embeddings (cosine distance, matching how
-- dev.chaya.api.search queries with the <=> operator). HNSW needs no training step and pgvector skips
-- NULL embeddings automatically, so rows still awaiting their asynchronous embedding are simply absent
-- from it until poi_version_pending_embedding_idx's backlog is processed.
CREATE INDEX poi_version_embedding_hnsw_idx ON poi_version USING hnsw (embedding vector_cosine_ops);

-- Fallback lexical search (chaya.search.lexical-fallback): trigram similarity on the label, used only
-- when the embedding model is unavailable (dev.chaya.api.search.EmbeddingUnavailableException) so a
-- search request degrades instead of failing outright. Never the primary "semantic" ranking mechanism.
CREATE INDEX poi_version_label_trgm_idx ON poi_version USING gin (label gin_trgm_ops);

CREATE INDEX poi_version_source_idx ON poi_version (source);
