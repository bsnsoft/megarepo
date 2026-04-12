#!/bin/bash
# MegaRepo Load Test
# Tests concurrent downloads through proxy + uploads to hosted
#
# Prerequisites:
#   - MegaRepo running (default: http://localhost:8080)
#   - A proxy repository named "maven-central-proxy" pointing to Maven Central
#   - A hosted repository named "raw-hosted" of format "raw"
#   - curl installed
#
# Usage:
#   ./load-test.sh
#   MEGAREPO_URL=http://megarepo:8080 CONCURRENCY=20 REQUESTS=200 ./load-test.sh

set -euo pipefail

MEGAREPO_URL="${MEGAREPO_URL:-http://localhost:8080}"
CONCURRENCY="${CONCURRENCY:-10}"
REQUESTS="${REQUESTS:-100}"
USERNAME="${MEGAREPO_USER:-admin}"
PASSWORD="${MEGAREPO_PASS:-admin123}"

PROXY_REPO="${PROXY_REPO:-maven-central-proxy}"
HOSTED_REPO="${HOSTED_REPO:-raw-hosted}"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

passed=0
failed=0

log_info()  { echo -e "${BLUE}[INFO]${NC}  $*"; }
log_ok()    { echo -e "${GREEN}[OK]${NC}    $*"; }
log_warn()  { echo -e "${YELLOW}[WARN]${NC}  $*"; }
log_error() { echo -e "${RED}[ERROR]${NC} $*"; }

echo ""
echo "========================================"
echo "  MegaRepo Load Test"
echo "========================================"
echo ""
log_info "URL:         $MEGAREPO_URL"
log_info "Concurrency: $CONCURRENCY"
log_info "Requests:    $REQUESTS"
log_info "User:        $USERNAME"
echo ""

# ── Login ────────────────────────────────────────────────────────────────
log_info "Authenticating..."
TOKEN=$(curl -sf -X POST "$MEGAREPO_URL/api/v1/security/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"username\":\"$USERNAME\",\"password\":\"$PASSWORD\"}" \
    | grep -o '"token":"[^"]*"' | cut -d'"' -f4)

if [ -z "$TOKEN" ]; then
    log_error "Authentication failed. Check credentials and server URL."
    exit 1
fi
log_ok "Authenticated successfully."
echo ""

AUTH_HEADER="Authorization: Bearer $TOKEN"

# ── Test 1: Concurrent proxy fetches (same artifact) ────────────────────
echo "────────────────────────────────────────"
log_info "Test 1: $CONCURRENCY concurrent fetches of same artifact..."
echo ""

ARTIFACT_PATH="org/apache/commons/commons-lang3/3.14.0/commons-lang3-3.14.0.pom"
PROXY_URL="$MEGAREPO_URL/repository/$PROXY_REPO/$ARTIFACT_PATH"

test1_start=$(date +%s%N)
pids=()
test1_failures=0

for i in $(seq 1 "$CONCURRENCY"); do
    (
        status=$(curl -sf -o /dev/null -w "%{http_code}" \
            -H "$AUTH_HEADER" "$PROXY_URL" 2>/dev/null || echo "000")
        if [ "$status" != "200" ]; then
            exit 1
        fi
    ) &
    pids+=($!)
done

for pid in "${pids[@]}"; do
    if ! wait "$pid" 2>/dev/null; then
        test1_failures=$((test1_failures + 1))
    fi
done

test1_end=$(date +%s%N)
test1_ms=$(( (test1_end - test1_start) / 1000000 ))

if [ $test1_failures -eq 0 ]; then
    log_ok "Test 1 passed: $CONCURRENCY concurrent fetches in ${test1_ms}ms (0 failures)"
    passed=$((passed + 1))
else
    log_warn "Test 1: $test1_failures/$CONCURRENCY fetches failed (${test1_ms}ms)"
    failed=$((failed + 1))
fi
echo ""

# ── Test 2: Sequential uploads to hosted ────────────────────────────────
echo "────────────────────────────────────────"
log_info "Test 2: $REQUESTS sequential uploads to hosted repository..."
echo ""

test2_start=$(date +%s%N)
test2_failures=0

for i in $(seq 1 "$REQUESTS"); do
    status=$(curl -sf -o /dev/null -w "%{http_code}" -X PUT \
        "$MEGAREPO_URL/repository/$HOSTED_REPO/loadtest/file-$i.txt" \
        -H "$AUTH_HEADER" \
        -H "Content-Type: text/plain" \
        -d "Load test file $i - $(date -u +%Y-%m-%dT%H:%M:%SZ)" 2>/dev/null || echo "000")
    if [ "$status" != "201" ] && [ "$status" != "200" ] && [ "$status" != "204" ]; then
        test2_failures=$((test2_failures + 1))
    fi
done

test2_end=$(date +%s%N)
test2_ms=$(( (test2_end - test2_start) / 1000000 ))
test2_rps=0
if [ $test2_ms -gt 0 ]; then
    test2_rps=$(( REQUESTS * 1000 / test2_ms ))
fi

if [ $test2_failures -eq 0 ]; then
    log_ok "Test 2 passed: $REQUESTS uploads in ${test2_ms}ms (~${test2_rps} req/s, 0 failures)"
    passed=$((passed + 1))
else
    log_warn "Test 2: $test2_failures/$REQUESTS uploads failed (${test2_ms}ms, ~${test2_rps} req/s)"
    failed=$((failed + 1))
fi
echo ""

# ── Test 3: Concurrent mixed workload ───────────────────────────────────
echo "────────────────────────────────────────"
log_info "Test 3: $CONCURRENCY concurrent mixed read+write operations..."
echo ""

test3_start=$(date +%s%N)
pids=()
test3_failures=0

for i in $(seq 1 "$CONCURRENCY"); do
    (
        # Alternate between reads and writes
        if [ $((i % 2)) -eq 0 ]; then
            # Read from proxy
            status=$(curl -sf -o /dev/null -w "%{http_code}" \
                -H "$AUTH_HEADER" "$PROXY_URL" 2>/dev/null || echo "000")
        else
            # Write to hosted
            status=$(curl -sf -o /dev/null -w "%{http_code}" -X PUT \
                "$MEGAREPO_URL/repository/$HOSTED_REPO/loadtest/concurrent-$i.txt" \
                -H "$AUTH_HEADER" \
                -H "Content-Type: text/plain" \
                -d "Concurrent test file $i" 2>/dev/null || echo "000")
        fi
        if [ "$status" = "000" ]; then
            exit 1
        fi
    ) &
    pids+=($!)
done

for pid in "${pids[@]}"; do
    if ! wait "$pid" 2>/dev/null; then
        test3_failures=$((test3_failures + 1))
    fi
done

test3_end=$(date +%s%N)
test3_ms=$(( (test3_end - test3_start) / 1000000 ))

if [ $test3_failures -eq 0 ]; then
    log_ok "Test 3 passed: $CONCURRENCY mixed ops in ${test3_ms}ms (0 failures)"
    passed=$((passed + 1))
else
    log_warn "Test 3: $test3_failures/$CONCURRENCY mixed ops failed (${test3_ms}ms)"
    failed=$((failed + 1))
fi
echo ""

# ── Test 4: Burst upload ────────────────────────────────────────────────
echo "────────────────────────────────────────"
BURST_SIZE=$((CONCURRENCY * 2))
log_info "Test 4: Burst upload of $BURST_SIZE files concurrently..."
echo ""

test4_start=$(date +%s%N)
pids=()
test4_failures=0

for i in $(seq 1 "$BURST_SIZE"); do
    (
        status=$(curl -sf -o /dev/null -w "%{http_code}" -X PUT \
            "$MEGAREPO_URL/repository/$HOSTED_REPO/loadtest/burst-$i.txt" \
            -H "$AUTH_HEADER" \
            -H "Content-Type: text/plain" \
            -d "Burst test file $i with payload padding $(head -c 1024 /dev/urandom | base64)" 2>/dev/null || echo "000")
        if [ "$status" = "000" ]; then
            exit 1
        fi
    ) &
    pids+=($!)
done

for pid in "${pids[@]}"; do
    if ! wait "$pid" 2>/dev/null; then
        test4_failures=$((test4_failures + 1))
    fi
done

test4_end=$(date +%s%N)
test4_ms=$(( (test4_end - test4_start) / 1000000 ))

if [ $test4_failures -eq 0 ]; then
    log_ok "Test 4 passed: $BURST_SIZE burst uploads in ${test4_ms}ms (0 failures)"
    passed=$((passed + 1))
else
    log_warn "Test 4: $test4_failures/$BURST_SIZE burst uploads failed (${test4_ms}ms)"
    failed=$((failed + 1))
fi
echo ""

# ── Results ─────────────────────────────────────────────────────────────
echo "========================================"
echo "  Results"
echo "========================================"
echo ""
log_info "Passed: $passed"
log_info "Failed: $failed"
echo ""

# Fetch metrics if available
log_info "Server metrics:"
curl -sf -H "$AUTH_HEADER" "$MEGAREPO_URL/api/v1/metrics" 2>/dev/null | python3 -m json.tool 2>/dev/null || log_warn "Could not fetch server metrics."
echo ""

if [ $failed -gt 0 ]; then
    log_error "Some tests had failures. Check server logs for details."
    exit 1
else
    log_ok "All load tests passed."
    exit 0
fi
