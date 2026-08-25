#!/usr/bin/env bash
# =============================================================================
# MegaRepo Upgrade Test Script
#
# Tests that upgrading from one version to another preserves data and applies
# Flyway migrations correctly.
#
# Usage:
#   ./test-upgrade.sh [OLD_IMAGE] [NEW_IMAGE]
#
# Examples:
#   ./test-upgrade.sh bsnsoft/megarepo:0.1.0 bsnsoft/megarepo:0.2.0
#   ./test-upgrade.sh bsnsoft/megarepo:0.1.0 bsnsoft/megarepo:latest
#
# What it does:
#   1. Starts the OLD version with a fresh database
#   2. Seeds test data (user, repository, component)
#   3. Stops the old version
#   4. Starts the NEW version against the same database
#   5. Verifies Flyway migrations ran successfully
#   6. Verifies seeded data survived the upgrade
#   7. Cleans up
# =============================================================================

set -euo pipefail

OLD_IMAGE="${1:-bsnsoft/megarepo:0.1.0}"
NEW_IMAGE="${2:-bsnsoft/megarepo:latest}"
PROJECT_NAME="megarepo-upgrade-test"
DB_PASSWORD="upgrade-test-pw"
DB_CONTAINER="${PROJECT_NAME}-db"
APP_CONTAINER="${PROJECT_NAME}-app"
NETWORK="${PROJECT_NAME}-net"
APP_PORT=18080
MAX_WAIT=60

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

pass() { echo -e "${GREEN}[PASS]${NC} $1"; }
fail() { echo -e "${RED}[FAIL]${NC} $1"; FAILURES=$((FAILURES + 1)); }
info() { echo -e "${YELLOW}[INFO]${NC} $1"; }

FAILURES=0

cleanup() {
    info "Cleaning up containers and network..."
    docker rm -f "$DB_CONTAINER" "$APP_CONTAINER" 2>/dev/null || true
    docker network rm "$NETWORK" 2>/dev/null || true
}
trap cleanup EXIT

wait_for_healthy() {
    local container="$1"
    local seconds=0
    while [ $seconds -lt $MAX_WAIT ]; do
        if docker exec "$container" pg_isready -U megarepo >/dev/null 2>&1; then
            return 0
        fi
        sleep 1
        seconds=$((seconds + 1))
    done
    fail "Database did not become healthy within ${MAX_WAIT}s"
    exit 1
}

wait_for_app() {
    local seconds=0
    while [ $seconds -lt $MAX_WAIT ]; do
        if curl -sf "http://localhost:${APP_PORT}/api/v1/status" >/dev/null 2>&1; then
            return 0
        fi
        sleep 2
        seconds=$((seconds + 2))
    done
    fail "Application did not start within ${MAX_WAIT}s"
    docker logs "$APP_CONTAINER" 2>&1 | tail -30
    exit 1
}

# =============================================================================
echo ""
echo "================================================================"
echo "  MegaRepo Upgrade Test"
echo "  Old: $OLD_IMAGE"
echo "  New: $NEW_IMAGE"
echo "================================================================"
echo ""

# Clean up any previous run
cleanup 2>/dev/null || true

# Create network
docker network create "$NETWORK"

# -----------------------------------------------------------------------------
info "Step 1: Starting PostgreSQL"
# -----------------------------------------------------------------------------
docker run -d \
    --name "$DB_CONTAINER" \
    --network "$NETWORK" \
    -e POSTGRES_DB=megarepo \
    -e POSTGRES_USER=megarepo \
    -e POSTGRES_PASSWORD="$DB_PASSWORD" \
    postgres:16-alpine

wait_for_healthy "$DB_CONTAINER"
pass "PostgreSQL is ready"

# -----------------------------------------------------------------------------
info "Step 2: Starting OLD version ($OLD_IMAGE)"
# -----------------------------------------------------------------------------
docker run -d \
    --name "$APP_CONTAINER" \
    --network "$NETWORK" \
    -p "${APP_PORT}:8080" \
    -e SPRING_DATASOURCE_URL="jdbc:postgresql://${DB_CONTAINER}:5432/megarepo?stringtype=unspecified" \
    -e SPRING_DATASOURCE_USERNAME=megarepo \
    -e SPRING_DATASOURCE_PASSWORD="$DB_PASSWORD" \
    "$OLD_IMAGE"

wait_for_app
pass "Old version started successfully"

# Record migration count before upgrade
OLD_MIGRATION_COUNT=$(docker exec "$DB_CONTAINER" \
    psql -U megarepo -t -c "SELECT COUNT(*) FROM flyway_schema_history WHERE success = true;" 2>/dev/null | tr -d ' ')
info "Old version has $OLD_MIGRATION_COUNT successful migrations"

# -----------------------------------------------------------------------------
info "Step 3: Seeding test data"
# -----------------------------------------------------------------------------

# Get auth token
TOKEN=$(curl -sf "http://localhost:${APP_PORT}/api/v1/security/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"username":"admin","password":"admin123"}' | grep -o '"token":"[^"]*"' | cut -d'"' -f4) || true

if [ -n "$TOKEN" ]; then
    pass "Authenticated as admin"

    # Create a test repository to verify data survives upgrade
    CREATE_RESULT=$(curl -sf -o /dev/null -w "%{http_code}" \
        "http://localhost:${APP_PORT}/api/v1/repositories" \
        -H "Content-Type: application/json" \
        -H "Authorization: Bearer $TOKEN" \
        -d '{
            "name": "upgrade-test-repo",
            "format": "raw",
            "type": "hosted",
            "online": true,
            "blobStoreName": "default"
        }') || CREATE_RESULT="000"

    if [ "$CREATE_RESULT" = "200" ] || [ "$CREATE_RESULT" = "201" ]; then
        pass "Created test repository 'upgrade-test-repo'"
    else
        info "Could not create test repository (HTTP $CREATE_RESULT) -- API may differ between versions"
    fi
else
    info "Could not authenticate -- skipping data seeding (API may differ between versions)"
fi

# Record data counts before upgrade
REPO_COUNT_BEFORE=$(docker exec "$DB_CONTAINER" \
    psql -U megarepo -t -c "SELECT COUNT(*) FROM repositories;" 2>/dev/null | tr -d ' ')
USER_COUNT_BEFORE=$(docker exec "$DB_CONTAINER" \
    psql -U megarepo -t -c "SELECT COUNT(*) FROM users;" 2>/dev/null | tr -d ' ')
info "Before upgrade: $REPO_COUNT_BEFORE repositories, $USER_COUNT_BEFORE users"

# -----------------------------------------------------------------------------
info "Step 4: Stopping old version"
# -----------------------------------------------------------------------------
docker rm -f "$APP_CONTAINER"
pass "Old version stopped"

# -----------------------------------------------------------------------------
info "Step 5: Starting NEW version ($NEW_IMAGE)"
# -----------------------------------------------------------------------------
docker run -d \
    --name "$APP_CONTAINER" \
    --network "$NETWORK" \
    -p "${APP_PORT}:8080" \
    -e SPRING_DATASOURCE_URL="jdbc:postgresql://${DB_CONTAINER}:5432/megarepo?stringtype=unspecified" \
    -e SPRING_DATASOURCE_USERNAME=megarepo \
    -e SPRING_DATASOURCE_PASSWORD="$DB_PASSWORD" \
    "$NEW_IMAGE"

wait_for_app
pass "New version started successfully"

# -----------------------------------------------------------------------------
info "Step 6: Verifying Flyway migrations"
# -----------------------------------------------------------------------------

NEW_MIGRATION_COUNT=$(docker exec "$DB_CONTAINER" \
    psql -U megarepo -t -c "SELECT COUNT(*) FROM flyway_schema_history WHERE success = true;" 2>/dev/null | tr -d ' ')
info "New version has $NEW_MIGRATION_COUNT successful migrations"

if [ "$NEW_MIGRATION_COUNT" -ge "$OLD_MIGRATION_COUNT" ]; then
    pass "Migration count increased or stayed the same ($OLD_MIGRATION_COUNT -> $NEW_MIGRATION_COUNT)"
else
    fail "Migration count decreased ($OLD_MIGRATION_COUNT -> $NEW_MIGRATION_COUNT)"
fi

# Check for failed migrations
FAILED_MIGRATIONS=$(docker exec "$DB_CONTAINER" \
    psql -U megarepo -t -c "SELECT COUNT(*) FROM flyway_schema_history WHERE success = false;" 2>/dev/null | tr -d ' ')
if [ "$FAILED_MIGRATIONS" = "0" ]; then
    pass "No failed migrations"
else
    fail "$FAILED_MIGRATIONS failed migration(s) found"
fi

# Print migration history
info "Full migration history:"
docker exec "$DB_CONTAINER" \
    psql -U megarepo -c "SELECT installed_rank, version, description, success, installed_on FROM flyway_schema_history ORDER BY installed_rank;" 2>/dev/null

# -----------------------------------------------------------------------------
info "Step 7: Verifying data survived upgrade"
# -----------------------------------------------------------------------------

REPO_COUNT_AFTER=$(docker exec "$DB_CONTAINER" \
    psql -U megarepo -t -c "SELECT COUNT(*) FROM repositories;" 2>/dev/null | tr -d ' ')
USER_COUNT_AFTER=$(docker exec "$DB_CONTAINER" \
    psql -U megarepo -t -c "SELECT COUNT(*) FROM users;" 2>/dev/null | tr -d ' ')
info "After upgrade: $REPO_COUNT_AFTER repositories, $USER_COUNT_AFTER users"

if [ "$REPO_COUNT_AFTER" -ge "$REPO_COUNT_BEFORE" ]; then
    pass "Repository count preserved ($REPO_COUNT_BEFORE -> $REPO_COUNT_AFTER)"
else
    fail "Repository count decreased ($REPO_COUNT_BEFORE -> $REPO_COUNT_AFTER)"
fi

if [ "$USER_COUNT_AFTER" -ge "$USER_COUNT_BEFORE" ]; then
    pass "User count preserved ($USER_COUNT_BEFORE -> $USER_COUNT_AFTER)"
else
    fail "User count decreased ($USER_COUNT_BEFORE -> $USER_COUNT_AFTER)"
fi

# Verify the app is actually serving requests
HTTP_STATUS=$(curl -sf -o /dev/null -w "%{http_code}" "http://localhost:${APP_PORT}/api/v1/status") || HTTP_STATUS="000"
if [ "$HTTP_STATUS" = "200" ]; then
    pass "Application responding on /api/v1/status"
else
    fail "Application not responding (HTTP $HTTP_STATUS)"
fi

# =============================================================================
echo ""
echo "================================================================"
if [ $FAILURES -eq 0 ]; then
    echo -e "  ${GREEN}ALL CHECKS PASSED${NC}"
else
    echo -e "  ${RED}$FAILURES CHECK(S) FAILED${NC}"
fi
echo "================================================================"
echo ""

exit $FAILURES
