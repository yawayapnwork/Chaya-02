# ADR 0002: Domain model and integrity strategy

Status: accepted. Supersedes the table list in ARCHITECTURE.md section 5 where they differ:
`reconstruction` is modelled as `scan` + `scan_version`, `scene_asset` as `processing_artifact`,
`detected_object` is not a separate table (see the embeddings bullet below; V12__semantic_search.sql),
and POIs are versioned (`poi` + `poi_version`).

- **Tenant isolation in the schema:** child rows carry `organization_id` and reference their parent with composite foreign keys, e.g. `(venue_id, organization_id) -> venue(id, organization_id)`. Cross-organization references are impossible at the database level.
- **Deletion:** organization, venue, floor, space and poi are soft-deleted (`deleted_at`). All foreign keys are `ON DELETE RESTRICT`. Jobs, artifacts, finalized scan versions, poi versions and audit rows are never deleted.
- **Immutability via triggers:** finalized `scan_version`, `processing_artifact` (write-once), `poi_version` (only a missing embedding may be filled, once), `audit_log` (append-only), and nodes/edges of non-DRAFT navigation graphs.
- **Job state machine:** QUEUED -> RUNNING | CANCELLED; RUNNING -> SUCCEEDED | FAILED | CANCELLED; FAILED -> QUEUED (counted retry, bounded by `max_retries`); SUCCEEDED and CANCELLED are terminal. Enforced by a trigger and mirrored in `JobStatus` for early failure.
- **Embeddings:** `poi_version.embedding vector(512)` (CLIP ViT-B/32, "openai" pretrained weights via open_clip -- decision D3 resolved; see chaya_worker.clip_embeddings and services/vision). An HNSW ANN index (`poi_version_embedding_hnsw_idx`) was added with the search feature in V12__semantic_search.sql, as planned. A Grounding-DINO-detected object is not a separate `detected_object` row: it becomes a `poi`/`poi_version` with `source = 'AUTO_DETECTED'`, `pipeline_run_id` set, and its own `detection_confidence`/`bounding_box` -- see dev.chaya.api.pipeline.PipelineService#ingestDetectedObjects.
- **Persistence code:** plain SQL through `JdbcClient` for job and audit writes. JPA entities are added per feature when a read/write model needs them.
