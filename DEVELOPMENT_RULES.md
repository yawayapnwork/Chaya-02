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
- If a pipeline stage is missing, expose a real contract and return `PIPELINE_UNAVAILABLE` (HTTP 501/503 problem+json). UI shows that state explicitly.

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
