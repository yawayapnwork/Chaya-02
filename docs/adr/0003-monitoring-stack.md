# ADR 0003: Monitoring stack (Prometheus, Grafana, Loki, GlitchTip)

Status: accepted.

## Context

Operators need API latency, processing health (queue depth, stage duration and failure rate, reconstruction success
rate), storage usage, logs and error tracking. Before this, the only signal was a health endpoint that reported UP
while jobs sat unclaimed.

## Decision

- **Metrics:** Micrometer's Prometheus registry in the API (`/actuator/prometheus`, scrape-credential protected).
  Pipeline and storage gauges are recomputed from the database (`PlatformMetrics`) instead of counted in memory, so
  they survive restarts and agree with the operations dashboard. The metrics carry no tenant labels.
- **Logs:** Loki with Grafana Alloy. Promtail is not used: it is deprecated in favour of Alloy.
- **Dashboards:** Grafana, with provisioned data sources and a dashboard kept in git.
- **Errors:** GlitchTip, a Sentry-protocol-compatible and self-hostable tracker. It keeps error data (which can contain
  request context) on our infrastructure.
- Everything runs as a **separate compose project** (`infra/monitoring`). It never shares a network, database or Redis
  with the application.

## Consequences

- GlitchTip brings its own PostgreSQL, Valkey (a Redis fork) and a **Celery** worker. DEVELOPMENT_RULES.md rule 20
  excludes Celery and similar tools "unless a concrete requirement emerges (record it as an ADR)". This is that
  record. Celery is used only inside GlitchTip, a third-party service, and is isolated in the monitoring project. It is
  not part of Chaya's own architecture, and no Chaya code uses it.
- Alloy needs read access to the Docker socket to collect container logs, which is root-equivalent on the host.
  Accepted for single-host deployments. The production alternative is described in docs/monitoring.md.
- Several services here are AGPL-licensed (Grafana, Loki). They run unmodified as separate services (see
  THIRD_PARTY_LICENSES.md).
- Application SDK integration with GlitchTip is follow-up work. The requirements are in docs/monitoring.md.
