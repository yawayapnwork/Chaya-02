# Monitoring

Stack: Prometheus (metrics and alerts), Grafana (dashboards), Loki with Alloy (logs), GlitchTip (error tracking). It
runs as its own compose project, `infra/monitoring/docker-compose.yml`. Decision record:
docs/adr/0003-monitoring-stack.md.

```
                 HTTP Basic (METRICS_SCRAPE_PASSWORD)
 chaya-api ──── /actuator/prometheus ◄──────────── Prometheus ──► alerts.yml
   │  logs (file, JSON)                               │
   ▼                                                  ▼
 Alloy ── container logs (docker.sock, read-only) ─► Loki ◄── Grafana (dashboards: Prometheus + Loki)
                                                                 │
 web / api / worker ── Sentry protocol (DSN) ─► GlitchTip        │  127.0.0.1:3001
   (integration not wired yet: see below)       127.0.0.1:8000
```

## Running it

1. In `.env`, set `METRICS_SCRAPE_PASSWORD`, `GRAFANA_ADMIN_PASSWORD`, `GLITCHTIP_SECRET_KEY` and
   `GLITCHTIP_DB_PASSWORD`. Also set `MINIO_METRICS_TOKEN` to the token printed by
   `mc admin prometheus generate <alias>` (run it with an alias for the MinIO root account, for example inside the
   `minio-init` image). The line must exist even while it is empty: Compose refuses to start with the variable
   unset. Until it holds a token, the `minio` target is down and `ChayaStorageMetricsMissing` fires.
2. Start the API with the same `METRICS_SCRAPE_PASSWORD`. For logs in Loki, also set
   `LOGGING_FILE_NAME=<repo>/logs/chaya-api.log` and `LOGGING_STRUCTURED_FORMAT_FILE=ecs`.
3. `docker compose --env-file .env -f infra/monitoring/docker-compose.yml up -d`
4. Open Grafana at http://localhost:3001. The provisioned dashboard is **Chaya 02 — platform**.

Every port is bound to 127.0.0.1. Loki and Alloy have no published port.

## Metrics

| Requirement | Metric | Source |
|---|---|---|
| API latency | `http_server_requests_seconds_bucket` (histogram), grouped by `uri`, `status`, `outcome` | Spring Boot and Micrometer, `percentiles-histogram` enabled |
| Processing job duration | `chaya_stage_duration_24h_seconds{stage, quantile="0.5\|0.95\|max"}` | `pipeline_stage_run` durations from the last 24 h |
| Processing failure rate | `chaya_stage_runs_24h{stage, outcome}`. Rate = FAILED / all | `pipeline_stage_run`, last 24 h |
| Queue depth | `chaya_jobs{status}`, `chaya_jobs_queued_oldest_age_seconds` | `processing_job` now |
| Storage usage (recorded) | `chaya_storage_recorded_bytes{store="raw_media\|derived_artifacts"}` | sizes the database recorded at verification. **Not** a bucket listing: it cannot see `pii/` staging, logs or abandoned multipart uploads. |
| Storage usage (physical) | `minio_cluster_capacity_usable_free_bytes`, `minio_cluster_capacity_usable_total_bytes`, `minio_bucket_usage_total_bytes{bucket}` | MinIO's own cluster metrics (`/minio/v2/metrics/cluster`, bearer token). Alerts: `ChayaStorageLow` (< 15% free), `ChayaStorageMetricsMissing`. Checked on 2026-09-24 against `quay.io/minio/minio:latest`: the two capacity series are exported and the endpoint answers 403 without the token. `minio_bucket_usage_total_bytes` did not appear on that empty instance (it follows MinIO's usage scan) and is **unconfirmed**. The alert uses only the capacity series. |
| Reconstruction success rate | `chaya_pipeline_runs_finished_24h{status}`. Rate = SUCCEEDED / all finished | `pipeline_run`, last 24 h. PARTIAL is not counted as a success. |
| Freshness of the above | `chaya_metrics_refresh_ok`, `chaya_metrics_refreshed_seconds` | `PlatformMetrics` |

The `chaya_*` gauges are recomputed from the database every 30 s (`dev.chaya.api.metrics.PlatformMetrics`). They are
not counted in memory, so they survive restarts and agree with the operations dashboard. The cost is that "duration"
is a 24-hour window statistic, not a histogram of individual jobs. If a refresh fails, the series are **removed** and
`chaya_metrics_refresh_ok` drops to 0: a panel goes empty rather than showing a stale value.

`/actuator/prometheus` is closed unless `METRICS_SCRAPE_PASSWORD` is set, and then requires HTTP Basic with user
`prometheus` (constant-time compare, `SecurityConfig`). The metrics carry no tenant labels (no organization or venue
ids) and no user data.

## Alerts (`infra/monitoring/prometheus/alerts.yml`)

`ChayaApiDown`, `ChayaApiHighErrorRate` (more than 5% 5xx), `ChayaApiSlow` (p95 above 2 s), `ChayaRateLimiting`,
`ChayaMetricsRefreshFailing`, `ChayaQueueStalled` (oldest job waiting over 15 min with nothing running),
`ChayaStageFailureRateHigh`, `ChayaReconstructionSuccessLow`, `ChayaStorageLow` (MinIO under 15% usable capacity),
`ChayaStorageMetricsMissing` (MinIO not scraped, so storage usage is unknown rather than assumed fine). The thresholds are starting points and have not been
tuned against real traffic. No Alertmanager is configured: alerts show in Prometheus and Grafana only. Add
Alertmanager with a receiver to get paged.

## Logs

Alloy ships (a) the logs of every container on the Docker host, labelled `container`, `compose_project` and
`service`, and (b) the API's log file. Loki keeps logs for 14 days. What the logs contain: see
docs/security-hardening.md, finding 14. There are no tokens, secrets or media.

Alloy reads the Docker socket. Even read-only, that is root-equivalent on the host. That is acceptable for a single
dev or ops host. In production, prefer the Loki Docker log driver, or write logs to files that Alloy tails without
the socket.

## Errors (GlitchTip)

GlitchTip runs with open registration disabled. **The application SDKs are not integrated yet.** Nothing sends
errors to it today. Integration means adding the Sentry SDK to each service (`sentry-spring-boot-starter-jakarta`
for the API, `@sentry/nextjs` for the web app, `sentry-sdk` for the worker and vision), each enabled only when a DSN
is set. Before enabling it:

- configure scrubbing so that bearer tokens, the `X-Chaya-Viewer-Token` header and the `?link=` query parameter are
  never sent;
- disable request body capture, because upload parts are raw media.

## Not verified

The whole stack has not been started. What has been checked (2026-09-24): `promtool check rules` (10 rules) and
`promtool check config` with `prom/prometheus:v3.5.0`; `docker compose config` for this file; the MinIO metric names
and token authentication against a real MinIO (see the storage row above). The dashboard queries have not been run
against a live Prometheus, and the alerts have not fired against real data.
