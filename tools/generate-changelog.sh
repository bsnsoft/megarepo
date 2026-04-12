#!/usr/bin/env bash
#
# generate-changelog.sh - Generate a grouped markdown changelog between two git tags.
#
# Usage:
#   ./tools/generate-changelog.sh <from-tag> <to-tag>
#   ./tools/generate-changelog.sh 0.7 0.8
#   ./tools/generate-changelog.sh 0.7 0.8 > CHANGELOG-0.8.md
#
# In CI (.gitlab-ci.yml):
#   release_notes:
#     script:
#       - ./tools/generate-changelog.sh "$CI_COMMIT_BEFORE_SHA" "$CI_COMMIT_TAG" > release-notes.md
#     artifacts:
#       paths: [release-notes.md]

set -euo pipefail

FROM_TAG="${1:-}"
TO_TAG="${2:-}"

if [[ -z "$FROM_TAG" || -z "$TO_TAG" ]]; then
  echo "Usage: $0 <from-tag> <to-tag>" >&2
  echo "Example: $0 0.7 0.8" >&2
  exit 1
fi

# Verify refs exist
for ref in "$FROM_TAG" "$TO_TAG"; do
  if ! git rev-parse --verify "$ref" >/dev/null 2>&1; then
    echo "Error: ref '$ref' does not exist." >&2
    exit 1
  fi
done

# Collect commits (hash + subject)
COMMITS=$(git log --pretty=format:"%H %s" "${FROM_TAG}..${TO_TAG}" --)

if [[ -z "$COMMITS" ]]; then
  echo "No commits found between $FROM_TAG and $TO_TAG." >&2
  exit 0
fi

# Categorize commits into buckets
declare -a SPRINTS=()
declare -a FIXES=()
declare -a FEATURES=()
declare -a DOCS=()
declare -a CI_DEVOPS=()
declare -a OTHER=()

while IFS= read -r line; do
  hash="${line%% *}"
  subject="${line#* }"
  short_hash="${hash:0:7}"
  entry="- \`${short_hash}\` ${subject}"

  case "$subject" in
    Sprint\ *)        SPRINTS+=("$entry") ;;
    Fix:*|Fix\ *|fix:*|fix\ *|Hotfix:*|Hotfix\ *)
                      FIXES+=("$entry") ;;
    Feature:*|Feature\ *|feat:*|feat\ *|Add:*|Add\ *)
                      FEATURES+=("$entry") ;;
    Docs:*|Docs\ *|docs:*|docs\ *)
                      DOCS+=("$entry") ;;
    CI:*|CI\ *|ci:*|ci\ *|DevOps:*|DevOps\ *|Pipeline:*)
                      CI_DEVOPS+=("$entry") ;;
    *)                OTHER+=("$entry") ;;
  esac
done <<< "$COMMITS"

# Count total
TOTAL=$(echo "$COMMITS" | wc -l | tr -d ' ')
DATE=$(date +%Y-%m-%d)

# Output markdown
cat <<EOF
# MegaRepo Changelog: ${FROM_TAG} .. ${TO_TAG}

**Generated**: ${DATE}
**Commits**: ${TOTAL}

EOF

print_section() {
  local title="$1"
  shift
  local entries=("$@")
  if [[ ${#entries[@]} -gt 0 ]]; then
    echo "## ${title}"
    echo ""
    for entry in "${entries[@]}"; do
      echo "$entry"
    done
    echo ""
  fi
}

print_section "Sprints"          "${SPRINTS[@]+"${SPRINTS[@]}"}"
print_section "Features"         "${FEATURES[@]+"${FEATURES[@]}"}"
print_section "Bug Fixes"        "${FIXES[@]+"${FIXES[@]}"}"
print_section "Documentation"    "${DOCS[@]+"${DOCS[@]}"}"
print_section "CI / DevOps"      "${CI_DEVOPS[@]+"${CI_DEVOPS[@]}"}"
print_section "Other"            "${OTHER[@]+"${OTHER[@]}"}"
