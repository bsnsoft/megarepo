#!/bin/bash
set -euo pipefail

MEGAREPO_URL="${MEGAREPO_URL:-http://localhost:8080}"
MEGAREPO_USER="${MEGAREPO_USER:-admin}"
MEGAREPO_PASS="${MEGAREPO_PASS:-admin123}"

echo "============================================"
echo " MegaRepo Repository Setup"
echo " URL: $MEGAREPO_URL"
echo "============================================"
echo ""

# -----------------------------------------------
# 1. Wait for MegaRepo to be available
# -----------------------------------------------
echo "Waiting for MegaRepo to become available..."
MAX_WAIT=120
WAITED=0
until curl -sf "$MEGAREPO_URL/api/v1/status" > /dev/null 2>&1; do
    if [ "$WAITED" -ge "$MAX_WAIT" ]; then
        echo "ERROR: MegaRepo did not become available within ${MAX_WAIT}s"
        exit 1
    fi
    echo "  ...not ready yet (${WAITED}s elapsed)"
    sleep 3
    WAITED=$((WAITED + 3))
done
echo "MegaRepo is up!"
echo ""

# -----------------------------------------------
# 2. Login and get JWT token
# -----------------------------------------------
echo "Logging in as '$MEGAREPO_USER'..."
LOGIN_RESPONSE=$(curl -sf -X POST "$MEGAREPO_URL/api/v1/security/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"username\":\"$MEGAREPO_USER\",\"password\":\"$MEGAREPO_PASS\"}")

TOKEN=$(echo "$LOGIN_RESPONSE" | python3 -c "import sys, json; print(json.load(sys.stdin)['token'])")

if [ -z "$TOKEN" ]; then
    echo "ERROR: Failed to obtain JWT token"
    exit 1
fi
echo "Login successful (token obtained)"
echo ""

# -----------------------------------------------
# 3. Helper function to create a repository
# -----------------------------------------------
CREATED=0
FAILED=0

create_repo() {
    local json="$1"
    local name
    name=$(echo "$json" | python3 -c "import sys, json; print(json.load(sys.stdin)['name'])")

    local http_code
    http_code=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$MEGAREPO_URL/api/v1/repositories" \
        -H "Authorization: Bearer $TOKEN" \
        -H "Content-Type: application/json" \
        -d "$json")

    if [ "$http_code" = "201" ]; then
        echo "  [OK]   $name (HTTP $http_code)"
        CREATED=$((CREATED + 1))
    elif [ "$http_code" = "400" ] || [ "$http_code" = "409" ]; then
        echo "  [SKIP] $name (HTTP $http_code - may already exist)"
    else
        echo "  [FAIL] $name (HTTP $http_code)"
        FAILED=$((FAILED + 1))
    fi
}

# -----------------------------------------------
# 4. Create repositories
# -----------------------------------------------
echo "Creating repositories..."
echo ""
echo "--- Maven ---"

create_repo '{
    "name": "maven-central-proxy",
    "format": "maven2",
    "type": "PROXY",
    "online": true,
    "blobStoreName": "default",
    "attributes": {
        "proxy": {
            "remoteUrl": "https://repo1.maven.org/maven2/"
        }
    }
}'

create_repo '{
    "name": "maven-releases",
    "format": "maven2",
    "type": "HOSTED",
    "online": true,
    "blobStoreName": "default",
    "attributes": {
        "maven": {
            "versionPolicy": "RELEASE",
            "layoutPolicy": "STRICT"
        },
        "writePolicy": "ALLOW_ONCE"
    }
}'

create_repo '{
    "name": "maven-snapshots",
    "format": "maven2",
    "type": "HOSTED",
    "online": true,
    "blobStoreName": "default",
    "attributes": {
        "maven": {
            "versionPolicy": "SNAPSHOT",
            "layoutPolicy": "STRICT"
        },
        "writePolicy": "ALLOW"
    }
}'

# Note: Group member management requires a separate API endpoint that may not yet exist.
# The group repo is created here; members must be added once the API supports it.
create_repo '{
    "name": "maven-public",
    "format": "maven2",
    "type": "GROUP",
    "online": true,
    "blobStoreName": "default",
    "attributes": {
        "group": {
            "memberNames": ["maven-releases", "maven-snapshots", "maven-central-proxy"]
        }
    }
}'

echo ""
echo "--- PyPI ---"

create_repo '{
    "name": "pypi-proxy",
    "format": "pypi",
    "type": "PROXY",
    "online": true,
    "blobStoreName": "default",
    "attributes": {
        "proxy": {
            "remoteUrl": "https://pypi.org/"
        }
    }
}'

create_repo '{
    "name": "pypi-hosted",
    "format": "pypi",
    "type": "HOSTED",
    "online": true,
    "blobStoreName": "default",
    "attributes": {
        "writePolicy": "ALLOW"
    }
}'

echo ""
echo "--- npm ---"

create_repo '{
    "name": "npm-proxy",
    "format": "npm",
    "type": "PROXY",
    "online": true,
    "blobStoreName": "default",
    "attributes": {
        "proxy": {
            "remoteUrl": "https://registry.npmjs.org/"
        }
    }
}'

create_repo '{
    "name": "npm-hosted",
    "format": "npm",
    "type": "HOSTED",
    "online": true,
    "blobStoreName": "default",
    "attributes": {
        "writePolicy": "ALLOW"
    }
}'

echo ""
echo "--- Raw ---"

create_repo '{
    "name": "raw-hosted",
    "format": "raw",
    "type": "HOSTED",
    "online": true,
    "blobStoreName": "default",
    "attributes": {
        "writePolicy": "ALLOW"
    }
}'

# -----------------------------------------------
# 5. Summary
# -----------------------------------------------
echo ""
echo "============================================"
echo " Setup Complete"
echo "  Created: $CREATED"
echo "  Failed:  $FAILED"
echo "============================================"
echo ""
echo "Repository endpoints:"
echo "  Maven proxy:      $MEGAREPO_URL/repository/maven-central-proxy/"
echo "  Maven releases:   $MEGAREPO_URL/repository/maven-releases/"
echo "  Maven snapshots:  $MEGAREPO_URL/repository/maven-snapshots/"
echo "  Maven group:      $MEGAREPO_URL/repository/maven-public/"
echo "  PyPI proxy:       $MEGAREPO_URL/repository/pypi-proxy/"
echo "  PyPI hosted:      $MEGAREPO_URL/repository/pypi-hosted/"
echo "  npm proxy:        $MEGAREPO_URL/repository/npm-proxy/"
echo "  npm hosted:       $MEGAREPO_URL/repository/npm-hosted/"
echo "  Raw hosted:       $MEGAREPO_URL/repository/raw-hosted/"

if [ "$FAILED" -gt 0 ]; then
    exit 1
fi
