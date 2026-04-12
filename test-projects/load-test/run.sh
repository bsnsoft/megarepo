#!/bin/bash
# MegaRepo k6 Load Test Runner
#
# Usage:
#   ./run.sh                              # full load test against localhost:8080
#   ./run.sh http://megarepo.example.com  # full load test against remote
#   ./run.sh http://localhost:8080 smoke  # smoke test only
#
# Environment variables:
#   BASE_URL  - MegaRepo base URL (overridden by first positional arg)
#   USERNAME  - Login username (default: admin)
#   PASSWORD  - Login password (default: admin123)

set -euo pipefail

BASE_URL="${1:-${BASE_URL:-http://localhost:8080}}"
SCRIPT="${2:-full-load}"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)/scripts"

RED='\033[0;31m'
GREEN='\033[0;32m'
BLUE='\033[0;34m'
NC='\033[0m'

echo ""
echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}  MegaRepo k6 Load Tests${NC}"
echo -e "${BLUE}========================================${NC}"
echo ""
echo -e "  Target:  ${GREEN}${BASE_URL}${NC}"
echo -e "  Script:  ${GREEN}${SCRIPT}${NC}"
echo ""

# Check k6 is installed
if ! command -v k6 &>/dev/null; then
    echo -e "${RED}Error: k6 is not installed.${NC}"
    echo ""
    echo "Install k6:"
    echo "  macOS:  brew install k6"
    echo "  Linux:  sudo snap install k6  (or see https://k6.io/docs/get-started/installation/)"
    echo "  Docker: docker run --rm -i grafana/k6 run - <script.js"
    exit 1
fi

case "$SCRIPT" in
    smoke|api-smoke)
        echo "Running API smoke test..."
        k6 run --env BASE_URL="$BASE_URL" "$SCRIPT_DIR/api-smoke.js"
        ;;
    upload|upload-download)
        echo "Running upload/download test..."
        k6 run --env BASE_URL="$BASE_URL" "$SCRIPT_DIR/upload-download.js"
        ;;
    browse|browse-heavy)
        echo "Running browse-heavy test..."
        k6 run --env BASE_URL="$BASE_URL" "$SCRIPT_DIR/browse-heavy.js"
        ;;
    full|full-load)
        echo "Running full load test (8 minutes)..."
        k6 run --env BASE_URL="$BASE_URL" "$SCRIPT_DIR/full-load.js"
        ;;
    all)
        echo "Running all tests sequentially..."
        echo ""
        echo "--- Smoke Test ---"
        k6 run --env BASE_URL="$BASE_URL" "$SCRIPT_DIR/api-smoke.js"
        echo ""
        echo "--- Upload/Download Test ---"
        k6 run --env BASE_URL="$BASE_URL" "$SCRIPT_DIR/upload-download.js"
        echo ""
        echo "--- Browse-Heavy Test ---"
        k6 run --env BASE_URL="$BASE_URL" "$SCRIPT_DIR/browse-heavy.js"
        echo ""
        echo "--- Full Load Test ---"
        k6 run --env BASE_URL="$BASE_URL" "$SCRIPT_DIR/full-load.js"
        ;;
    *)
        echo -e "${RED}Unknown script: $SCRIPT${NC}"
        echo ""
        echo "Available scripts:"
        echo "  smoke           API smoke test (1 VU, single pass)"
        echo "  upload-download Upload and download artifacts (ramp to 10 VUs)"
        echo "  browse-heavy    Read-heavy browsing simulation (ramp to 20 VUs)"
        echo "  full-load       Combined mixed workload (ramp to 50 VUs, 8 min)"
        echo "  all             Run all tests sequentially"
        exit 1
        ;;
esac
