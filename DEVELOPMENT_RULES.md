# CHAYA 02 — Development Rules

## Engineering principles
1. No fake functionality.
2. No unnecessary dependencies.
3. No premature microservices.
4. Keep clear boundaries between frontend, backend, processing workers, and AR clients.
5. Every persistent entity needs an explicit data model.
6. API contracts must be versionable.
7. Errors must be explicit and actionable.
8. Configuration uses environment variables.
9. Secrets are never committed.
10. Multi-tenant isolation is designed in from the beginning.
11. Every important operation is auditable.
12. All long-running reconstruction operations are asynchronous.
13. Never block an HTTP request while GPU reconstruction runs.
14. Every processing job has lifecycle states.
15. The system tolerates processing failures.
16. A partial reconstruction is never presented as a successful final reconstruction.
17. Health checks are real (they check actual dependencies).
18. Tests are added alongside implementation.
19. Prefer boring, maintainable engineering over clever abstractions.
20. No Kubernetes, Kafka, Celery, RabbitMQ, or similar unless a concrete requirement emerges (record it as an ADR).

## What "no fake functionality" means in practice
- No random, hardcoded, or placeholder outputs standing in for scan results, detections, paths, AR coordinates, or AI responses.
- Test fixtures live under `test` directories only and are never seeded into non-test environments.
- If a capability cannot run, fail with a stable, actionable code instead of producing output, and show that state in
  the UI. In the pipeline: a missing tool, library or GPU is `DEPENDENCY_UNAVAILABLE` (structured, retryable, with the
  missing items listed); a stage that is planned but not written is `STAGE_NOT_IMPLEMENTED` (none exists today); a
  metric consumer without a calibrated frame is `NOT_CALIBRATED`. In the API: `NAVMESH_NOT_READY`,
  `VERSION_NOT_FINALIZED`, `DATABASE_UNAVAILABLE` and the other problem+json codes in `packages/contracts/openapi/v1.yaml`.
  (Earlier versions of this file named a `PIPELINE_UNAVAILABLE` code; it was never implemented.)

## Claiming status
- A capability's status names the highest level actually reached: implemented, unit-tested, integration-tested,
  fixture-validated, real-venue executed, physical-device validated (PROJECT_PLAN.md). Code existing is "implemented",
  never "done" or "validated".
- Never describe a stage as unimplemented when it is implemented but blocked by a missing dependency, and never
  describe it as working when it has only been tested with stubs or fixtures. Name the missing prerequisite.
- Validation records (docs/E2E_VALIDATION.md, the dated audits, benchmark results) are history: add a dated note when
  something changes; do not rewrite or delete a recorded failure.
- A command in the documentation has either been run (say when) or is labelled with the prerequisite that kept it from
  being run.

## Workflow
- Small commits, one milestone slice each; tests in the same commit.
- Conventional-style messages (`feat(api): …`).
- Before changing existing files, read them; explain conflicts before destructive changes.
- Architectural changes update ARCHITECTURE.md in the same commit; significant decisions get a short ADR in `docs/adr/`.

## Security
- `.env` files are git-ignored; only `.env.example` (no real values) is committed.
- Every tenant-scoped query filters by organization/venue; cross-tenant access returns 404.
- Presigned URLs are short-lived and issued only after authorization.
- Audit events are append-only.
