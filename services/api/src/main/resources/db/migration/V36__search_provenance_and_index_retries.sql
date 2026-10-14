-- Semantic search: where a detected object came from, and safe retries of the POI embedding backfill (docs/search.md).
--
-- Provenance. A detected object already names the pipeline run that produced it (pipeline_run_id, V12), its scan version
-- (scan_version_id) and the frame its box was measured in (bounding_box.sourceFrame). It did not name the artifact it was
-- ingested from, nor every frame its merged detections came from. From here on PipelineService#applyDetections stores:
--   detection_artifact_id  the DETECTED_OBJECTS processing_artifact the row was read from (checksum-verified on read)
--   detection_provenance   {artifactSha256, detectorModel, detectorFineTuned, embeddingModel, sourceFrame, sourceFrames,
--                           detectionsMerged}: what the worker reported for this object, as ingested
-- Rows ingested before this migration keep NULL here (their run is still recorded). The artifact reference is set by
-- ingestion for every new detection; it is not a NOT NULL constraint because older rows, and the embedding backfill's
-- updates of them, would violate it.

ALTER TABLE poi_version
    ADD COLUMN detection_artifact_id uuid REFERENCES processing_artifact (id) ON DELETE RESTRICT,
    ADD COLUMN detection_provenance  jsonb CHECK (detection_provenance IS NULL OR jsonb_typeof(detection_provenance) = 'object'),
    ADD CONSTRAINT poi_version_detection_provenance_detected_only
        CHECK ((detection_artifact_id IS NULL AND detection_provenance IS NULL) OR source = 'AUTO_DETECTED');

CREATE INDEX poi_version_detection_artifact_idx ON poi_version (detection_artifact_id) WHERE detection_artifact_id IS NOT NULL;

-- Embedding retries. The vision service can refuse one POI's text (HTTP 4xx) while working for every other. The backfill
-- used to take the oldest pending POI first, every pass: one such text stalled the whole backlog for good. A refused text
-- is now recorded here and retried with exponential backoff (PoiEmbeddingService), while the rest of the backlog proceeds.
-- An outage of the vision service is not recorded against any POI: the pass simply stops, as before.
CREATE TABLE poi_embedding_failure (
    poi_version_id  uuid PRIMARY KEY REFERENCES poi_version (id) ON DELETE CASCADE,
    attempts        integer NOT NULL CHECK (attempts > 0),
    last_error      text NOT NULL,
    last_attempt_at timestamptz NOT NULL DEFAULT now(),
    next_attempt_at timestamptz NOT NULL
);

CREATE INDEX poi_embedding_failure_next_idx ON poi_embedding_failure (next_attempt_at);
