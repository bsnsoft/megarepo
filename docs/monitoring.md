# Monitoring MegaRepo with Prometheus and Grafana

MegaRepo exposes metrics via a Prometheus-compatible endpoint at `/actuator/prometheus`.
This endpoint requires authentication (same as `/actuator/metrics`).

## Prometheus Scrape Configuration

Add the following job to your `prometheus.yml`:

```yaml
scrape_configs:
  - job_name: 'megarepo'
    scrape_interval: 15s
    metrics_path: /actuator/prometheus
    basic_auth:
      username: admin
      password: <your-admin-password>
    static_configs:
      - targets: ['megarepo-host:8080']
        labels:
          instance: 'production'
```

If MegaRepo runs behind a reverse proxy with TLS:

```yaml
scrape_configs:
  - job_name: 'megarepo'
    scheme: https
    scrape_interval: 15s
    metrics_path: /actuator/prometheus
    basic_auth:
      username: admin
      password: <your-admin-password>
    static_configs:
      - targets: ['megarepo.example.com:443']
```

## Key Metrics

### Custom MegaRepo Metrics

| Metric | Type | Description |
|--------|------|-------------|
| `megarepo_repositories_count` | Gauge | Number of configured repositories |
| `megarepo_components_count` | Gauge | Total number of components across all repositories |
| `megarepo_storage_bytes_total` | Gauge | Total storage used by all assets (bytes) |
| `megarepo_users_active_count` | Gauge | Number of active user accounts |
| `megarepo_artifacts_downloads_total` | Counter | Total artifact downloads |
| `megarepo_artifacts_uploads_total` | Counter | Total artifact uploads |
| `megarepo_proxy_cache_hits_total` | Counter | Proxy cache hits |
| `megarepo_proxy_cache_misses_total` | Counter | Proxy cache misses (remote fetches) |

### JVM and Spring Boot Metrics (auto-provided)

| Metric | Description |
|--------|-------------|
| `jvm_memory_used_bytes` | JVM memory usage by area |
| `jvm_threads_live_threads` | Current live thread count |
| `hikaricp_connections_active` | Active database connections |
| `hikaricp_connections_idle` | Idle database connections |
| `http_server_requests_seconds_*` | HTTP request duration (histograms) |
| `process_cpu_usage` | Process CPU usage |
| `disk_free_bytes` | Free disk space |

## Grafana Dashboard

Import the JSON below via Grafana > Dashboards > Import > Paste JSON.

```json
{
  "annotations": { "list": [] },
  "editable": true,
  "fiscalYearStartMonth": 0,
  "graphTooltip": 1,
  "id": null,
  "links": [],
  "panels": [
    {
      "title": "Repositories",
      "type": "stat",
      "gridPos": { "h": 4, "w": 4, "x": 0, "y": 0 },
      "targets": [
        { "expr": "megarepo_repositories_count", "legendFormat": "Repositories" }
      ],
      "fieldConfig": {
        "defaults": { "thresholds": { "steps": [{ "color": "blue", "value": null }] } }
      }
    },
    {
      "title": "Components",
      "type": "stat",
      "gridPos": { "h": 4, "w": 4, "x": 4, "y": 0 },
      "targets": [
        { "expr": "megarepo_components_count", "legendFormat": "Components" }
      ],
      "fieldConfig": {
        "defaults": { "thresholds": { "steps": [{ "color": "blue", "value": null }] } }
      }
    },
    {
      "title": "Storage Used",
      "type": "stat",
      "gridPos": { "h": 4, "w": 4, "x": 8, "y": 0 },
      "targets": [
        { "expr": "megarepo_storage_bytes_total", "legendFormat": "Bytes" }
      ],
      "fieldConfig": {
        "defaults": {
          "unit": "bytes",
          "thresholds": { "steps": [{ "color": "green", "value": null }, { "color": "red", "value": 8e10 }] }
        }
      }
    },
    {
      "title": "Active Users",
      "type": "stat",
      "gridPos": { "h": 4, "w": 4, "x": 12, "y": 0 },
      "targets": [
        { "expr": "megarepo_users_active_count", "legendFormat": "Users" }
      ],
      "fieldConfig": {
        "defaults": { "thresholds": { "steps": [{ "color": "blue", "value": null }] } }
      }
    },
    {
      "title": "Artifact Downloads / min",
      "type": "timeseries",
      "gridPos": { "h": 8, "w": 12, "x": 0, "y": 4 },
      "targets": [
        { "expr": "rate(megarepo_artifacts_downloads_total[5m]) * 60", "legendFormat": "Downloads/min" }
      ]
    },
    {
      "title": "Artifact Uploads / min",
      "type": "timeseries",
      "gridPos": { "h": 8, "w": 12, "x": 12, "y": 4 },
      "targets": [
        { "expr": "rate(megarepo_artifacts_uploads_total[5m]) * 60", "legendFormat": "Uploads/min" }
      ]
    },
    {
      "title": "Proxy Cache Hit Ratio",
      "type": "timeseries",
      "gridPos": { "h": 8, "w": 12, "x": 0, "y": 12 },
      "targets": [
        {
          "expr": "rate(megarepo_proxy_cache_hits_total[5m]) / (rate(megarepo_proxy_cache_hits_total[5m]) + rate(megarepo_proxy_cache_misses_total[5m]))",
          "legendFormat": "Cache Hit Ratio"
        }
      ],
      "fieldConfig": {
        "defaults": { "unit": "percentunit", "min": 0, "max": 1 }
      }
    },
    {
      "title": "HTTP Request Duration (p95)",
      "type": "timeseries",
      "gridPos": { "h": 8, "w": 12, "x": 12, "y": 12 },
      "targets": [
        {
          "expr": "histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket[5m])) by (le))",
          "legendFormat": "p95 latency"
        }
      ],
      "fieldConfig": {
        "defaults": { "unit": "s" }
      }
    },
    {
      "title": "JVM Memory",
      "type": "timeseries",
      "gridPos": { "h": 8, "w": 12, "x": 0, "y": 20 },
      "targets": [
        { "expr": "jvm_memory_used_bytes{area=\"heap\"}", "legendFormat": "Heap Used" },
        { "expr": "jvm_memory_max_bytes{area=\"heap\"}", "legendFormat": "Heap Max" }
      ],
      "fieldConfig": {
        "defaults": { "unit": "bytes" }
      }
    },
    {
      "title": "Database Connections",
      "type": "timeseries",
      "gridPos": { "h": 8, "w": 12, "x": 12, "y": 20 },
      "targets": [
        { "expr": "hikaricp_connections_active{pool=\"MegaRepoPool\"}", "legendFormat": "Active" },
        { "expr": "hikaricp_connections_idle{pool=\"MegaRepoPool\"}", "legendFormat": "Idle" },
        { "expr": "hikaricp_connections_max{pool=\"MegaRepoPool\"}", "legendFormat": "Max" }
      ]
    }
  ],
  "schemaVersion": 39,
  "tags": ["megarepo"],
  "templating": { "list": [] },
  "time": { "from": "now-1h", "to": "now" },
  "title": "MegaRepo Overview",
  "uid": "megarepo-overview"
}
```

## Alert Rules

Add these to your Prometheus `alert.rules.yml` (or configure as Grafana alerts):

```yaml
groups:
  - name: megarepo
    rules:
      - alert: MegaRepoStorageHigh
        expr: megarepo_storage_bytes_total / disk_total_bytes > 0.8
        for: 10m
        labels:
          severity: warning
        annotations:
          summary: "MegaRepo storage usage above 80%"
          description: "Storage usage is {{ $value | humanizePercentage }}. Consider cleanup policies or expanding storage."

      - alert: MegaRepoHighErrorRate
        expr: |
          sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m]))
          / sum(rate(http_server_requests_seconds_count[5m])) > 0.01
        for: 5m
        labels:
          severity: critical
        annotations:
          summary: "MegaRepo error rate above 1%"
          description: "HTTP 5xx error rate is {{ $value | humanizePercentage }} over the last 5 minutes."

      - alert: MegaRepoSlowResponses
        expr: |
          histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket[5m])) by (le)) > 5
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "MegaRepo p95 response time above 5s"
          description: "95th percentile response time is {{ $value }}s. Check database and blob store performance."

      - alert: MegaRepoDBConnectionPoolExhausted
        expr: hikaricp_connections_active{pool="MegaRepoPool"} >= hikaricp_connections_max{pool="MegaRepoPool"} - 2
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "MegaRepo DB connection pool nearly exhausted"
          description: "Only {{ $value }} connections remaining. Consider increasing maximum-pool-size."

      - alert: MegaRepoDown
        expr: up{job="megarepo"} == 0
        for: 1m
        labels:
          severity: critical
        annotations:
          summary: "MegaRepo instance is down"
          description: "Prometheus cannot reach MegaRepo at {{ $labels.instance }}."
```
