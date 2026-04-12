# MegaRepo Load Tests

Performance and load tests for MegaRepo using [k6](https://k6.io).

## Prerequisites

Install k6:

```bash
# macOS
brew install k6

# Debian/Ubuntu
sudo gpg -k
sudo gpg --no-default-keyring --keyring /usr/share/keyrings/k6-archive-keyring.gpg \
  --keyserver hkp://keyserver.ubuntu.com:80 --recv-keys C5AD17C747E3415A3642D57D77C6C491D6AC1D68
echo "deb [signed-by=/usr/share/keyrings/k6-archive-keyring.gpg] https://dl.k6.io/deb stable main" \
  | sudo tee /etc/apt/sources.list.d/k6.list
sudo apt-get update && sudo apt-get install k6

# Docker (no install needed)
docker run --rm -i grafana/k6 run - <scripts/full-load.js
```

MegaRepo must be running and accessible. Default: `http://localhost:8080`.

## Quick Start

```bash
# Run the full load test against local MegaRepo
./run.sh

# Run against a remote instance
./run.sh http://megarepo.example.com

# Run just the smoke test
./run.sh http://localhost:8080 smoke
```

## k6 Test Scripts

| Script | Description | VUs | Duration |
|--------|-------------|-----|----------|
| `scripts/api-smoke.js` | Smoke test all API endpoints (repos, users, search, status, license) | 1 | ~10s |
| `scripts/upload-download.js` | Upload Maven artifacts (POM + JAR), download them back, search | 1-10 | ~2 min |
| `scripts/browse-heavy.js` | Simulate concurrent users browsing repos, searching, viewing status | 1-20 | ~2.5 min |
| `scripts/full-load.js` | Combined workload: 40% upload/download, 30% browse, 30% search | 1-50 | ~8 min |

## Configuration

All scripts accept environment variables:

| Variable | Default | Description |
|----------|---------|-------------|
| `BASE_URL` | `http://localhost:8080` | MegaRepo base URL |
| `USERNAME` | `admin` | Login username |
| `PASSWORD` | `admin123` | Login password |

Pass via k6:

```bash
k6 run --env BASE_URL=http://megarepo:8080 --env USERNAME=testuser --env PASSWORD=secret scripts/full-load.js
```

## Thresholds

The tests include built-in pass/fail thresholds:

- **API smoke**: p95 latency < 500ms, error rate < 1%
- **Upload/download**: upload p95 < 1000ms, download p95 < 500ms, errors < 5% (upload) / 1% (download)
- **Browse-heavy**: browse p95 < 500ms, search p95 < 800ms, errors < 1%
- **Full load**: upload p95 < 1000ms, download p95 < 500ms, search p95 < 800ms, overall p95 < 1000ms, errors < 1%

## Running Individual Scripts

```bash
k6 run --env BASE_URL=http://localhost:8080 scripts/api-smoke.js
k6 run --env BASE_URL=http://localhost:8080 scripts/upload-download.js
k6 run --env BASE_URL=http://localhost:8080 scripts/browse-heavy.js
k6 run --env BASE_URL=http://localhost:8080 scripts/full-load.js
```

## Legacy Shell Script

The `load-test.sh` bash script provides a simpler curl-based load test that does not require k6.

### Prerequisites (legacy)

- `curl` installed
- A proxy repository named `maven-central-proxy` pointing to Maven Central
- A hosted repository named `raw-hosted` of format `raw`

### Usage (legacy)

```bash
./load-test.sh

MEGAREPO_URL=http://megarepo:8080 CONCURRENCY=20 REQUESTS=200 ./load-test.sh
```

### Legacy tests

1. **Concurrent proxy fetches** -- N concurrent downloads of the same Maven artifact through a proxy
2. **Sequential uploads** -- N sequential file uploads to a hosted raw repository
3. **Concurrent mixed workload** -- Alternating reads and writes in parallel
4. **Burst upload** -- 2xN concurrent uploads with larger payloads
