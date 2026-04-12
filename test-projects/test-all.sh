#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

MEGAREPO_URL="${MEGAREPO_URL:-http://localhost:8080}"

PASS=0
FAIL=0
SKIP=0

echo "============================================"
echo " MegaRepo Integration Test Suite"
echo " URL: $MEGAREPO_URL"
echo "============================================"
echo ""

# -----------------------------------------------
# Helper: run a test phase and track result
# -----------------------------------------------
run_test() {
    local name="$1"
    shift
    echo "--- $name ---"
    if "$@"; then
        echo "  RESULT: PASS"
        PASS=$((PASS + 1))
    else
        echo "  RESULT: FAIL"
        FAIL=$((FAIL + 1))
    fi
    echo ""
}

skip_test() {
    local name="$1"
    local reason="$2"
    echo "--- $name ---"
    echo "  SKIPPED: $reason"
    SKIP=$((SKIP + 1))
    echo ""
}

# -----------------------------------------------
# 0. Setup repositories
# -----------------------------------------------
echo "=== Phase 0: Repository Setup ==="
echo ""
bash setup.sh
echo ""

# -----------------------------------------------
# 1. Maven tests
# -----------------------------------------------
echo "=== Phase 1: Maven Tests ==="
echo ""

if command -v mvn &> /dev/null; then
    run_test "Maven: Build (proxy fetch)" \
        mvn -f maven/pom.xml clean package -s maven/settings.xml -B -q

    run_test "Maven: Deploy to hosted" \
        mvn -f maven/pom.xml deploy -s maven/settings.xml -B -q

    run_test "Maven: Verify deployed artifact" \
        curl -sf -u admin:admin123 \
            "$MEGAREPO_URL/repository/maven-releases/com/example/megarepo-test/1.0.0/megarepo-test-1.0.0.jar" \
            -o /dev/null
else
    skip_test "Maven" "mvn not found in PATH"
fi

# -----------------------------------------------
# 2. Python/PyPI tests
# -----------------------------------------------
echo "=== Phase 2: Python/PyPI Tests ==="
echo ""

if command -v pip3 &> /dev/null || command -v pip &> /dev/null; then
    PIP_CMD="pip3"
    command -v pip3 &> /dev/null || PIP_CMD="pip"

    run_test "Python: pip install through proxy" \
        env PIP_CONFIG_FILE=python/pip.conf \
        $PIP_CMD install -r python/requirements.txt --target=python/lib -q --no-warn-script-location

    # Build and upload test (requires build + twine)
    if command -v python3 &> /dev/null && python3 -c "import build" 2>/dev/null; then
        run_test "Python: Build package" \
            python3 -m build python/ --outdir python/dist -q

        if command -v twine &> /dev/null; then
            run_test "Python: Upload to hosted (twine)" \
                twine upload \
                    --repository-url "$MEGAREPO_URL/repository/pypi-hosted/" \
                    -u admin -p admin123 \
                    --non-interactive \
                    python/dist/*
        else
            skip_test "Python: Upload" "twine not installed"
        fi
    else
        skip_test "Python: Build + Upload" "python3 build module not installed"
    fi
else
    skip_test "Python/PyPI" "pip not found in PATH"
fi

# -----------------------------------------------
# 3. npm tests
# -----------------------------------------------
echo "=== Phase 3: npm Tests ==="
echo ""

if command -v npm &> /dev/null; then
    run_test "npm: Install packages through proxy" \
        npm install --prefix npm --no-audit --no-fund --loglevel=error

    run_test "npm: Run test script" \
        npm test --prefix npm --loglevel=error
else
    skip_test "npm" "npm not found in PATH"
fi

# -----------------------------------------------
# 4. Raw format tests (curl-based)
# -----------------------------------------------
echo "=== Phase 4: Raw Format Tests ==="
echo ""

run_test "Raw: Upload file" \
    curl -sf -u admin:admin123 \
        -X PUT \
        -H "Content-Type: text/plain" \
        -d "Hello from MegaRepo raw test!" \
        "$MEGAREPO_URL/repository/raw-hosted/test/hello.txt" \
        -o /dev/null -w ""

run_test "Raw: Download file" \
    bash -c "CONTENT=\$(curl -sf '$MEGAREPO_URL/repository/raw-hosted/test/hello.txt') && \
             [ \"\$CONTENT\" = 'Hello from MegaRepo raw test!' ] && echo '  Content verified'"

run_test "Raw: Upload binary file" \
    bash -c "dd if=/dev/urandom bs=1024 count=10 2>/dev/null | \
             curl -sf -u admin:admin123 \
                 -X PUT \
                 -H 'Content-Type: application/octet-stream' \
                 --data-binary @- \
                 '$MEGAREPO_URL/repository/raw-hosted/test/random.bin' \
                 -o /dev/null -w ''"

run_test "Raw: Download binary and verify size" \
    bash -c "SIZE=\$(curl -sf '$MEGAREPO_URL/repository/raw-hosted/test/random.bin' | wc -c | tr -d ' ') && \
             [ \"\$SIZE\" -gt 0 ] && echo \"  Downloaded \${SIZE} bytes\""

# -----------------------------------------------
# 5. API verification tests
# -----------------------------------------------
echo "=== Phase 5: API Verification ==="
echo ""

run_test "API: Status endpoint" \
    curl -sf "$MEGAREPO_URL/api/v1/status" -o /dev/null

run_test "API: List repositories (authenticated)" \
    bash -c "TOKEN=\$(curl -sf -X POST '$MEGAREPO_URL/api/v1/security/auth/login' \
                 -H 'Content-Type: application/json' \
                 -d '{\"username\":\"admin\",\"password\":\"admin123\"}' | \
                 python3 -c \"import sys,json; print(json.load(sys.stdin)['token'])\") && \
             curl -sf '$MEGAREPO_URL/api/v1/repositories' \
                 -H \"Authorization: Bearer \$TOKEN\" -o /dev/null"

# -----------------------------------------------
# Summary
# -----------------------------------------------
echo "============================================"
echo " Test Results"
echo "  Passed:  $PASS"
echo "  Failed:  $FAIL"
echo "  Skipped: $SKIP"
echo "============================================"

if [ "$FAIL" -gt 0 ]; then
    echo ""
    echo "SOME TESTS FAILED"
    exit 1
else
    echo ""
    echo "ALL TESTS PASSED"
fi
