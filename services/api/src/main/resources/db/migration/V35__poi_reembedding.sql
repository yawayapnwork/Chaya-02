-- Re-embedding POI text when the CLIP model changes (docs/search.md, "Embedding model changes").
--
-- Search compares only vectors of the query's own model (SemanticSearchService), so a POI embedded by an earlier model is
-- never mixed into another model's space -- but until now it was also never re-embedded: the backfill filled only missing
-- embeddings, and the immutability trigger forbade replacing one. After a model change every existing POI silently
-- dropped out of semantic search.
--
-- The guard now also allows replacing the text-embedding pair (embedding, embedding_model) as a whole with a NEW vector of a
-- DIFFERENT model, and nothing else (renaming the model of an existing vector stays forbidden). Replacing a vector with another one of the same model stays forbidden: within one
-- model an embedding is still set once. image_embedding (the detection crop, CLIP's image tower) is not touched here:
-- re-computing it needs the crops, i.e. re-running SEMANTIC_INDEXING; until then search ignores image vectors of another
-- model and ranks such a detected object by its label's text embedding.

CREATE OR REPLACE FUNCTION poi_version_guard() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'poi_version % cannot be deleted; soft-delete the poi instead', OLD.id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF OLD.scan_version_id IS NULL AND NEW.scan_version_id IS NOT NULL
       AND (to_jsonb(NEW) - 'scan_version_id') = (to_jsonb(OLD) - 'scan_version_id') THEN
        RETURN NEW;
    END IF;
    IF (to_jsonb(OLD) - 'embedding' - 'embedding_model') IS DISTINCT FROM
       (to_jsonb(NEW) - 'embedding' - 'embedding_model') THEN
        RAISE EXCEPTION 'poi_version % is immutable (only its text embedding may be filled in or moved to a new model)', OLD.id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF OLD.embedding IS NULL THEN
        RETURN NEW; -- filling in a missing embedding (V5)
    END IF;
    -- Re-embedding in another model's space: a new model AND a new vector. Relabelling an old vector as another model
    -- (same vector, new name) is exactly the mixing of spaces this must prevent, so it is refused.
    IF NEW.embedding IS NOT NULL AND NEW.embedding_model IS NOT NULL
       AND NEW.embedding_model IS DISTINCT FROM OLD.embedding_model
       AND NEW.embedding IS DISTINCT FROM OLD.embedding THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'poi_version % already has an embedding of model % (it may only be replaced by another model''s)',
        OLD.id, OLD.embedding_model USING ERRCODE = 'integrity_constraint_violation';
END;
$$ LANGUAGE plpgsql;

CREATE INDEX poi_version_embedding_model_idx ON poi_version (embedding_model) WHERE embedding IS NOT NULL;
