-- AR marker size (docs/ar.md, "Android: WebXR").
--
-- WebXR image tracking (the only marker detection a WebXR browser offers; docs/ar.md) must be told each image's
-- printed width in metres, and it reports the width it measured, which the client checks against this. An anchor's
-- printed size is therefore part of its registration. It is required for IMAGE_TARGET anchors, enforced by
-- dev.chaya.api.ar.AnchorService for every write; rows written before this migration keep NULL until re-registered.
ALTER TABLE ar_anchor
    ADD COLUMN marker_size_m double precision CHECK (marker_size_m IS NULL OR (marker_size_m > 0 AND marker_size_m <= 5));
