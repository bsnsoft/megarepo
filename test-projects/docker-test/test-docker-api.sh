#!/bin/bash
# =============================================================================
# MegaRepo Docker V2 API Integration Test
# =============================================================================
# Tests Docker registry V2 API using only curl (no Docker CLI needed).
# Works in CI environments without Docker-in-Docker.
#
# Tests hosted push/pull cycle and proxy cache functionality.
#
# Usage:
#   MEGAREPO_URL=http://localhost:8080 MEGAREPO_USER=admin MEGAREPO_PASS=admin123 ./test-docker-api.sh
#
# Exit codes:
#   0 - all tests passed
#   1 - one or more tests failed
# =============================================================================
set -uo pipefail

MEGAREPO_URL="${MEGAREPO_URL:-http://localhost:8080}"
MEGAREPO_USER="${MEGAREPO_USER:-admin}"
MEGAREPO_PASS="${MEGAREPO_PASS:-admin123}"
AUTH="${MEGAREPO_USER}:${MEGAREPO_PASS}"

HOSTED_REPO="${DOCKER_HOSTED_REPO:-docker-hosted}"
PROXY_REPO="${DOCKER_PROXY_REPO:-docker-hub-proxy}"
TEST_IMAGE="ci-test-$(date +%s)"

PASS=0
FAIL=0
TOTAL=0

# -- Helpers ------------------------------------------------------------------

pass() {
    PASS=$((PASS + 1))
    TOTAL=$((TOTAL + 1))
    echo "  PASS: $1"
}

fail() {
    FAIL=$((FAIL + 1))
    TOTAL=$((TOTAL + 1))
    echo "  FAIL: $1"
}

assert_http() {
    local expected="$1" actual="$2" label="$3"
    if [ "$actual" = "$expected" ]; then
        pass "$label (HTTP $actual)"
    else
        fail "$label (expected HTTP $expected, got $actual)"
    fi
}

sha256_hex() {
    # Portable sha256: works on Linux (sha256sum) and macOS (shasum)
    if command -v sha256sum &>/dev/null; then
        sha256sum | awk '{print $1}'
    else
        shasum -a 256 | awk '{print $1}'
    fi
}

# =============================================================================
# SECTION 1: Hosted Docker repository - push and pull cycle
# =============================================================================

echo "============================================"
echo " MegaRepo Docker V2 API Tests"
echo " URL: $MEGAREPO_URL"
echo " Hosted repo: $HOSTED_REPO"
echo " Proxy repo:  $PROXY_REPO"
echo "============================================"
echo ""

# -- 1.1 V2 API version check ------------------------------------------------
echo "--- 1.1 V2 API version check ---"
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" -u "$AUTH" \
    "$MEGAREPO_URL/repository/$HOSTED_REPO/v2/")
assert_http "200" "$HTTP_CODE" "V2 version check"
echo ""

# -- 1.2 Push a config blob --------------------------------------------------
echo "--- 1.2 Push config blob ---"

# Minimal OCI config (empty rootfs)
CONFIG_JSON='{"architecture":"amd64","os":"linux","rootfs":{"type":"layers","diff_ids":[]},"config":{}}'
CONFIG_SIZE=${#CONFIG_JSON}
CONFIG_DIGEST="sha256:$(echo -n "$CONFIG_JSON" | sha256_hex)"

# Initiate upload
UPLOAD_HEADERS=$(curl -s -X POST -u "$AUTH" -D - -o /dev/null \
    "$MEGAREPO_URL/repository/$HOSTED_REPO/v2/$TEST_IMAGE/blobs/uploads/")
UPLOAD_HTTP=$(echo "$UPLOAD_HEADERS" | head -1 | grep -o '[0-9][0-9][0-9]')
UPLOAD_LOCATION=$(echo "$UPLOAD_HEADERS" | grep -i "^Location:" | tr -d '\r' | awk '{print $2}')

if [ -z "$UPLOAD_LOCATION" ]; then
    fail "Blob upload initiation - no Location header"
else
    assert_http "201" "$UPLOAD_HTTP" "Blob upload initiation (POST)"

    # Complete upload with monolithic PUT
    SEPARATOR="?"
    if echo "$UPLOAD_LOCATION" | grep -q '?'; then
        SEPARATOR="&"
    fi
    COMPLETE_HTTP=$(curl -s -o /dev/null -w "%{http_code}" -X PUT -u "$AUTH" \
        -H "Content-Type: application/octet-stream" \
        -H "Content-Length: $CONFIG_SIZE" \
        --data-binary "$CONFIG_JSON" \
        "${MEGAREPO_URL}${UPLOAD_LOCATION}${SEPARATOR}digest=${CONFIG_DIGEST}")
    assert_http "201" "$COMPLETE_HTTP" "Config blob upload complete (PUT)"
fi
echo ""

# -- 1.3 Push a layer blob ---------------------------------------------------
echo "--- 1.3 Push layer blob ---"

# Create a small fake layer (just some bytes)
LAYER_DATA="MegaRepo CI test layer $(date -u +%Y-%m-%dT%H:%M:%SZ)"
LAYER_SIZE=${#LAYER_DATA}
LAYER_DIGEST="sha256:$(echo -n "$LAYER_DATA" | sha256_hex)"

UPLOAD_HEADERS=$(curl -s -X POST -u "$AUTH" -D - -o /dev/null \
    "$MEGAREPO_URL/repository/$HOSTED_REPO/v2/$TEST_IMAGE/blobs/uploads/")
UPLOAD_LOCATION=$(echo "$UPLOAD_HEADERS" | grep -i "^Location:" | tr -d '\r' | awk '{print $2}')

if [ -z "$UPLOAD_LOCATION" ]; then
    fail "Layer blob upload initiation - no Location header"
else
    SEPARATOR="?"
    if echo "$UPLOAD_LOCATION" | grep -q '?'; then
        SEPARATOR="&"
    fi
    COMPLETE_HTTP=$(curl -s -o /dev/null -w "%{http_code}" -X PUT -u "$AUTH" \
        -H "Content-Type: application/octet-stream" \
        -H "Content-Length: $LAYER_SIZE" \
        --data-binary "$LAYER_DATA" \
        "${MEGAREPO_URL}${UPLOAD_LOCATION}${SEPARATOR}digest=${LAYER_DIGEST}")
    assert_http "201" "$COMPLETE_HTTP" "Layer blob upload complete"
fi
echo ""

# -- 1.4 Push a manifest -----------------------------------------------------
echo "--- 1.4 Push manifest ---"

MANIFEST=$(cat <<MANIFEST_EOF
{
  "schemaVersion": 2,
  "mediaType": "application/vnd.docker.distribution.manifest.v2+json",
  "config": {
    "mediaType": "application/vnd.docker.container.image.v1+json",
    "size": $CONFIG_SIZE,
    "digest": "$CONFIG_DIGEST"
  },
  "layers": [
    {
      "mediaType": "application/vnd.docker.image.rootfs.diff.tar.gzip",
      "size": $LAYER_SIZE,
      "digest": "$LAYER_DIGEST"
    }
  ]
}
MANIFEST_EOF
)

MANIFEST_HTTP=$(curl -s -o /dev/null -w "%{http_code}" -X PUT -u "$AUTH" \
    -H "Content-Type: application/vnd.docker.distribution.manifest.v2+json" \
    --data-binary "$MANIFEST" \
    "$MEGAREPO_URL/repository/$HOSTED_REPO/v2/$TEST_IMAGE/manifests/1.0")
assert_http "201" "$MANIFEST_HTTP" "Manifest push"
echo ""

# -- 1.5 Pull manifest back --------------------------------------------------
echo "--- 1.5 Pull manifest ---"

PULL_BODY=$(curl -s -u "$AUTH" \
    -H "Accept: application/vnd.docker.distribution.manifest.v2+json" \
    "$MEGAREPO_URL/repository/$HOSTED_REPO/v2/$TEST_IMAGE/manifests/1.0")
PULL_HTTP=$(curl -s -o /dev/null -w "%{http_code}" -u "$AUTH" \
    -H "Accept: application/vnd.docker.distribution.manifest.v2+json" \
    "$MEGAREPO_URL/repository/$HOSTED_REPO/v2/$TEST_IMAGE/manifests/1.0")
assert_http "200" "$PULL_HTTP" "Manifest pull"

# Verify manifest content
if echo "$PULL_BODY" | python3 -c "import json,sys; d=json.load(sys.stdin); assert d['config']['digest']=='$CONFIG_DIGEST'" 2>/dev/null; then
    pass "Manifest content integrity (config digest matches)"
else
    fail "Manifest content integrity (config digest mismatch)"
fi
echo ""

# -- 1.6 Pull blobs back -----------------------------------------------------
echo "--- 1.6 Pull blobs ---"

CONFIG_PULL_HTTP=$(curl -s -o /dev/null -w "%{http_code}" -u "$AUTH" \
    "$MEGAREPO_URL/repository/$HOSTED_REPO/v2/$TEST_IMAGE/blobs/$CONFIG_DIGEST")
assert_http "200" "$CONFIG_PULL_HTTP" "Config blob pull"

LAYER_PULL_HTTP=$(curl -s -o /dev/null -w "%{http_code}" -u "$AUTH" \
    "$MEGAREPO_URL/repository/$HOSTED_REPO/v2/$TEST_IMAGE/blobs/$LAYER_DIGEST")
assert_http "200" "$LAYER_PULL_HTTP" "Layer blob pull"

# Verify blob content integrity
PULLED_CONFIG=$(curl -s -u "$AUTH" \
    "$MEGAREPO_URL/repository/$HOSTED_REPO/v2/$TEST_IMAGE/blobs/$CONFIG_DIGEST")
PULLED_DIGEST="sha256:$(echo -n "$PULLED_CONFIG" | sha256_hex)"
if [ "$PULLED_DIGEST" = "$CONFIG_DIGEST" ]; then
    pass "Config blob integrity (digest verified)"
else
    fail "Config blob integrity (expected $CONFIG_DIGEST, got $PULLED_DIGEST)"
fi
echo ""

# -- 1.7 HEAD requests -------------------------------------------------------
echo "--- 1.7 HEAD requests ---"

HEAD_MANIFEST_HTTP=$(curl -s -o /dev/null -w "%{http_code}" -u "$AUTH" \
    -I -H "Accept: application/vnd.docker.distribution.manifest.v2+json" \
    "$MEGAREPO_URL/repository/$HOSTED_REPO/v2/$TEST_IMAGE/manifests/1.0")
assert_http "200" "$HEAD_MANIFEST_HTTP" "HEAD manifest"

HEAD_BLOB_HTTP=$(curl -s -o /dev/null -w "%{http_code}" -u "$AUTH" \
    -I "$MEGAREPO_URL/repository/$HOSTED_REPO/v2/$TEST_IMAGE/blobs/$CONFIG_DIGEST")
assert_http "200" "$HEAD_BLOB_HTTP" "HEAD blob"
echo ""

# -- 1.8 Tags list -----------------------------------------------------------
echo "--- 1.8 Tags list ---"

TAGS_HTTP=$(curl -s -o /dev/null -w "%{http_code}" -u "$AUTH" \
    "$MEGAREPO_URL/repository/$HOSTED_REPO/v2/$TEST_IMAGE/tags/list")
assert_http "200" "$TAGS_HTTP" "Tags list endpoint"

TAGS_BODY=$(curl -s -u "$AUTH" \
    "$MEGAREPO_URL/repository/$HOSTED_REPO/v2/$TEST_IMAGE/tags/list")
if echo "$TAGS_BODY" | python3 -c "import json,sys; d=json.load(sys.stdin); assert '1.0' in d['tags']" 2>/dev/null; then
    pass "Tags list contains pushed tag '1.0'"
else
    fail "Tags list missing pushed tag '1.0'"
fi
echo ""

# -- 1.9 Catalog -------------------------------------------------------------
echo "--- 1.9 Catalog ---"

CATALOG_HTTP=$(curl -s -o /dev/null -w "%{http_code}" -u "$AUTH" \
    "$MEGAREPO_URL/repository/$HOSTED_REPO/v2/_catalog")
assert_http "200" "$CATALOG_HTTP" "Catalog endpoint"
echo ""

# =============================================================================
# SECTION 2: Proxy Docker repository (Docker Hub)
# =============================================================================

echo "--- 2.0 Check proxy repo exists ---"
PROXY_EXISTS=$(curl -s -u "$AUTH" "$MEGAREPO_URL/api/v1/repositories" | \
    python3 -c "import json,sys; repos=json.loads(sys.stdin.read()); print('yes' if any(r['name']=='$PROXY_REPO' for r in repos) else 'no')" 2>/dev/null)

if [ "$PROXY_EXISTS" != "yes" ]; then
    echo "  Creating proxy repository: $PROXY_REPO"
    CREATE_HTTP=$(curl -s -o /dev/null -w "%{http_code}" -X POST -u "$AUTH" \
        -H "Content-Type: application/json" \
        -d "{\"name\":\"$PROXY_REPO\",\"format\":\"docker\",\"type\":\"PROXY\",\"online\":true,\"blobStoreName\":\"default\",\"attributes\":{\"proxy\":{\"remoteUrl\":\"https://registry-1.docker.io/\"}}}" \
        "$MEGAREPO_URL/api/v1/repositories")
    if [ "$CREATE_HTTP" = "200" ] || [ "$CREATE_HTTP" = "201" ]; then
        echo "  Created proxy repository"
    else
        fail "Could not create proxy repository (HTTP $CREATE_HTTP)"
    fi
fi
echo ""

echo "--- 2.1 Proxy V2 check ---"
PROXY_V2_HTTP=$(curl -s -o /dev/null -w "%{http_code}" -u "$AUTH" \
    "$MEGAREPO_URL/repository/$PROXY_REPO/v2/")
assert_http "200" "$PROXY_V2_HTTP" "Proxy V2 version check"
echo ""

echo "--- 2.2 Pull alpine manifest via proxy ---"
PROXY_MANIFEST_HTTP=$(curl -s -o /dev/null -w "%{http_code}" -u "$AUTH" \
    -H "Accept: application/vnd.docker.distribution.manifest.v2+json" \
    -H "Accept: application/vnd.docker.distribution.manifest.list.v2+json" \
    -H "Accept: application/vnd.oci.image.index.v1+json" \
    "$MEGAREPO_URL/repository/$PROXY_REPO/v2/library/alpine/manifests/3.19")
assert_http "200" "$PROXY_MANIFEST_HTTP" "Proxy manifest pull (alpine:3.19)"

PROXY_MANIFEST=$(curl -s -u "$AUTH" \
    -H "Accept: application/vnd.oci.image.index.v1+json" \
    -H "Accept: application/vnd.docker.distribution.manifest.list.v2+json" \
    "$MEGAREPO_URL/repository/$PROXY_REPO/v2/library/alpine/manifests/3.19")
if echo "$PROXY_MANIFEST" | python3 -c "import json,sys; d=json.load(sys.stdin); assert d['schemaVersion']==2" 2>/dev/null; then
    pass "Proxy manifest is valid (schemaVersion=2)"
else
    fail "Proxy manifest invalid or missing schemaVersion"
fi
echo ""

echo "--- 2.3 Proxy tags list ---"
PROXY_TAGS_HTTP=$(curl -s -o /dev/null -w "%{http_code}" -u "$AUTH" \
    "$MEGAREPO_URL/repository/$PROXY_REPO/v2/library/alpine/tags/list")
assert_http "200" "$PROXY_TAGS_HTTP" "Proxy tags list (alpine)"

PROXY_TAGS=$(curl -s -u "$AUTH" \
    "$MEGAREPO_URL/repository/$PROXY_REPO/v2/library/alpine/tags/list")
if echo "$PROXY_TAGS" | python3 -c "import json,sys; d=json.load(sys.stdin); assert len(d.get('tags',[])) > 10" 2>/dev/null; then
    pass "Proxy tags list has >10 tags"
else
    fail "Proxy tags list is unexpectedly small"
fi
echo ""

echo "--- 2.4 Proxy caching verification ---"
SEARCH_RESULT=$(curl -s -u "$AUTH" "$MEGAREPO_URL/api/v1/search?q=alpine")
if echo "$SEARCH_RESULT" | python3 -c "
import json,sys
data=json.loads(sys.stdin.read())
proxy_items=[i for i in data['items'] if i['repository']=='$PROXY_REPO']
assert len(proxy_items)>0, 'No proxy items in search'
" 2>/dev/null; then
    pass "Proxied image appears in search results (cached)"
else
    fail "Proxied image not found in search results"
fi
echo ""

echo "--- 2.5 Pull specific manifest by digest via proxy ---"
# Extract an amd64 digest from the manifest list
AMD64_DIGEST=$(echo "$PROXY_MANIFEST" | python3 -c "
import json,sys
d=json.load(sys.stdin)
for m in d.get('manifests',[]):
    p=m.get('platform',{})
    if p.get('architecture')=='amd64' and p.get('os')=='linux':
        print(m['digest']); break
" 2>/dev/null)

if [ -n "$AMD64_DIGEST" ]; then
    DIGEST_HTTP=$(curl -s -o /dev/null -w "%{http_code}" -u "$AUTH" \
        -H "Accept: application/vnd.oci.image.manifest.v1+json" \
        "$MEGAREPO_URL/repository/$PROXY_REPO/v2/library/alpine/manifests/$AMD64_DIGEST")
    assert_http "200" "$DIGEST_HTTP" "Proxy manifest pull by digest ($AMD64_DIGEST)"
else
    fail "Could not extract amd64 digest from manifest list"
fi
echo ""

# =============================================================================
# SECTION 3: Error handling
# =============================================================================

echo "--- 3.1 Non-existent image ---"
NOT_FOUND_HTTP=$(curl -s -o /dev/null -w "%{http_code}" -u "$AUTH" \
    -H "Accept: application/vnd.docker.distribution.manifest.v2+json" \
    "$MEGAREPO_URL/repository/$HOSTED_REPO/v2/does-not-exist/manifests/latest")
# Should return 404
assert_http "404" "$NOT_FOUND_HTTP" "Non-existent manifest returns 404"
echo ""

echo "--- 3.2 Non-existent blob ---"
MISSING_BLOB_HTTP=$(curl -s -o /dev/null -w "%{http_code}" -u "$AUTH" \
    "$MEGAREPO_URL/repository/$HOSTED_REPO/v2/does-not-exist/blobs/sha256:0000000000000000000000000000000000000000000000000000000000000000")
assert_http "404" "$MISSING_BLOB_HTTP" "Non-existent blob returns 404"
echo ""

# =============================================================================
# Summary
# =============================================================================

echo "============================================"
echo " RESULTS: $PASS passed, $FAIL failed (out of $TOTAL)"
echo "============================================"

if [ "$FAIL" -gt 0 ]; then
    exit 1
fi
exit 0
