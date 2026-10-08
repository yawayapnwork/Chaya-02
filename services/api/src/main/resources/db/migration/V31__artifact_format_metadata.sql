-- Viewer artifact format validation (review F-1 and table row 13; packages/contracts/viewer/README.md).
--
-- A KSPLAT used to be published with only its SHA-256 and size: any bytes a worker reported under that kind became the
-- viewer asset, and the pinned viewer library checks nothing but the version bytes. Publishing now validates the sealed
-- copy against packages/contracts/viewer/ksplat-contract.json (dev.chaya.api.pipeline.KsplatValidator) and records what
-- its headers say -- format version, compression level, spherical-harmonics degree, section and splat counts, and the
-- viewer library the contract is for -- next to the checksum and size, so a viewer can check the bytes it downloads
-- against all three.
--
-- NOT VALID: KSPLAT rows published before this migration carry no metadata and stay servable (the viewer still
-- validates their headers itself); every KSPLAT inserted from now on must have it.

ALTER TABLE processing_artifact ADD COLUMN format_metadata jsonb;

ALTER TABLE processing_artifact ADD CONSTRAINT processing_artifact_ksplat_validated
    CHECK (kind IS DISTINCT FROM 'KSPLAT'
           -- coalesce: with no metadata the comparison is NULL, which a CHECK would let through
           OR coalesce(format_metadata ->> 'format' = 'ksplat' AND (format_metadata ->> 'splatCount')::bigint > 0, false))
    NOT VALID;
