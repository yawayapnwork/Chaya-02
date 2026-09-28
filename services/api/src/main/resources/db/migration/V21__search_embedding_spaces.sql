-- Semantic search: one column per CLIP tower, so no query can compare a text vector with an image vector by accident.
--
-- Before this migration poi_version.embedding held a CLIP TEXT-tower vector for manual POIs (their label, category and
-- tags, PoiEmbeddingService) but a CLIP IMAGE-tower vector for AUTO_DETECTED ones (the detection crop,
-- chaya_worker.clip_embeddings), and search ranked both by one cosine. A text query lands at cosine ~0.8 to almost any
-- short text but ~0.2-0.3 to even a matching image, so detected objects sorted below every manual POI
-- (docs/ADVERSARIAL_REVIEW.md CV-4).
--
-- From here on:
--   embedding        always the CLIP text tower, of the POI's own text. For a detected object that text is its detector
--                    label; the embedding backfill fills it like any other pending POI. Every searchable POI has one, so
--                    all of them are ranked in the same space.
--   image_embedding  the CLIP image tower, of the detection crop(s) (L2-normalised mean over merged detections). Only
--                    ever compared with text-tower vectors of the same model (docs/search.md, "Ranking").

ALTER TABLE poi_version
    ADD COLUMN image_embedding       vector(512),
    ADD COLUMN image_embedding_model text,
    ADD CONSTRAINT poi_version_image_embedding_model_pair CHECK ((image_embedding IS NULL) = (image_embedding_model IS NULL)),
    ADD CONSTRAINT poi_version_image_embedding_detected_only CHECK (image_embedding IS NULL OR source = 'AUTO_DETECTED');

-- Move the image vectors of existing detected objects to their own column. Their text embedding is then missing, so the
-- backfill embeds their label within one interval. This is a one-off data migration of rows the immutability trigger
-- would otherwise protect: nothing but the two embedding pairs changes, and each vector moves unchanged.
ALTER TABLE poi_version DISABLE TRIGGER poi_version_guard;
UPDATE poi_version
   SET image_embedding = embedding, image_embedding_model = embedding_model, embedding = NULL, embedding_model = NULL
 WHERE source = 'AUTO_DETECTED' AND embedding IS NOT NULL;
ALTER TABLE poi_version ENABLE TRIGGER poi_version_guard;

COMMENT ON COLUMN poi_version.embedding IS
    'CLIP text-tower embedding of the POI''s own text (label, category, tags; a detected object''s detector label). Set once.';
COMMENT ON COLUMN poi_version.image_embedding IS
    'AUTO_DETECTED only: CLIP image-tower embedding of the detection crop(s). Compared only with text-tower vectors of the same model.';
