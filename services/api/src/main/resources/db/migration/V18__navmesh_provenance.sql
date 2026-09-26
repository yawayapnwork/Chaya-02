-- Navigation graphs are routable only when they were derived from a real navmesh (docs/navigation.md).
--
-- NAVIGATION_BAKING builds a Detour navmesh with the real Recast/Detour library (the chaya-navmesh tool,
-- services/reconstruction/native/chaya-navmesh) and publishes it as a NAVMESH artifact, with a NAVMESH_MANIFEST recording its
-- provenance. The NAVIGATION_GRAPH it publishes is the Detour polygon graph of that navmesh, bound to it by checksum.
-- Ingestion (dev.chaya.api.pipeline.PipelineService#ingestNavigationGraph) records that binding here, and
-- dev.chaya.api.navigation.RouteService routes only on graphs that carry it.
--
-- Every other graph -- hand-inserted, benchmark self-tests, test fixtures, and anything that existed before this migration
-- (no real navmesh was ever baked before it) -- is SYNTHETIC, the column default, and is refused for routing
-- (NAVMESH_NOT_READY) unless chaya.navigation.accept-synthetic-graphs is set, which only tests do.

ALTER TABLE navigation_graph
    ADD COLUMN source text NOT NULL DEFAULT 'SYNTHETIC' CHECK (source IN ('RECAST_NAVMESH', 'SYNTHETIC')),
    ADD COLUMN pipeline_run_id uuid REFERENCES pipeline_run (id) ON DELETE RESTRICT,
    ADD COLUMN navmesh_artifact_id uuid REFERENCES processing_artifact (id) ON DELETE RESTRICT,
    ADD COLUMN navmesh_manifest_artifact_id uuid REFERENCES processing_artifact (id) ON DELETE RESTRICT,
    ADD COLUMN navmesh_sha256 text CHECK (navmesh_sha256 IS NULL OR navmesh_sha256 ~ '^[0-9a-f]{64}$'),
    ADD COLUMN navmesh_tool_version text,
    ADD COLUMN recastnavigation_version text,
    ADD CONSTRAINT navigation_graph_navmesh_provenance CHECK (
        source <> 'RECAST_NAVMESH'
        OR (pipeline_run_id IS NOT NULL AND navmesh_artifact_id IS NOT NULL AND navmesh_manifest_artifact_id IS NOT NULL
            AND navmesh_sha256 IS NOT NULL AND navmesh_tool_version IS NOT NULL AND recastnavigation_version IS NOT NULL));

CREATE INDEX navigation_graph_navmesh_artifact_idx ON navigation_graph (navmesh_artifact_id) WHERE navmesh_artifact_id IS NOT NULL;
