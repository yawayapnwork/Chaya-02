-- How well a detected object's 3D position is supported (review CV-1; docs/search.md, "Localization").
--
-- SEMANTIC_INDEXING now places a detection only on the surface its box shows (occlusion-tested, nearest covering depth
-- layer; chaya_worker.object_localization) and drops detections without depth evidence. What survives carries:
--   localization_status        MULTI_VIEW (seen and placed consistently from >= 2 frames) or SINGLE_VIEW (one frame)
--   localization_uncertainty_m the measured spread of the placement: the larger of the views' disagreement and the
--                              thickness of the surface it rests on. A spread, not an error against ground truth.
--   localization               the full record (method, views, spreads, coverage)
-- A detected object stored before this (placed without any depth test) has none of them; search reports it as
-- UNVERIFIED and ranks it below every spatially valid result. A manual POI never has them: staff placed it.

ALTER TABLE poi_version
    ADD COLUMN localization_status text,
    ADD COLUMN localization_uncertainty_m double precision,
    ADD COLUMN localization jsonb,
    ADD CONSTRAINT poi_version_localization_status_check
        CHECK (localization_status IS NULL OR localization_status IN ('MULTI_VIEW', 'SINGLE_VIEW')),
    ADD CONSTRAINT poi_version_localization_uncertainty_check
        CHECK (localization_uncertainty_m IS NULL
               OR (localization_uncertainty_m >= 0 AND localization_uncertainty_m < 'Infinity'::double precision)),
    ADD CONSTRAINT poi_version_localization_complete
        CHECK ((localization_status IS NULL) = (localization_uncertainty_m IS NULL)),
    ADD CONSTRAINT poi_version_localization_detected_only
        CHECK (localization_status IS NULL OR source = 'AUTO_DETECTED');
