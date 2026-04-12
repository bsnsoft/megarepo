# EGB — Effortless Git Branching (Agent Reference)

Compact description of the EGB branching and versioning model for AI agents and automated tooling.

## Core Principle

Versions are named at the start of a development cycle via `git tag`, not at the end. All version information is derived from git history using `git describe --tags`.

## Two Modes

### Applications (deployed services)

- **Version source:** `git describe --abbrev=10 --tags` → `1.96-42-gf97ab584a2`
- **No version in files.** Ever. The version is computed, not stored.
- **Branch hierarchy:** `master` → `staging-X.Y` → `release-X.Y`
- **Merge direction:** toward stabilization (master → staging → release) via merge. Toward development (release → staging → master) via merge. Cherry-pick only when a fix must reach a more stable branch (e.g. staging fix needed on release).
- **Bugfix rule:** fix on the branch closest to production where the bug exists, then merge back toward development.
- **Sprint start:** `git tag 1.97` on master. All subsequent commits are `1.97-N`.
- **Format:** dashes, not dots. Not SemVer. Commit count = build number.

### Libraries (published artifacts)

- **Version source:** `SNAPSHOT` in build file during development, tags for releases.
- **One version file:** `pom.xml` (or equivalent) contains `X.Y-SNAPSHOT` on master.
- **Release:** `git checkout -b release-X.Y && git tag X.Y.0`. CI overrides version from tag at build time.
- **Patches:** fix on release branch, `git tag X.Y.1`. No file change needed.
- **After release branch:** bump master to next SNAPSHOT immediately (e.g. `0.47-SNAPSHOT`).
- **Format:** dots, SemVer. Patch version is a human decision about compatibility.
- **Rule:** SNAPSHOT is internal only. No external consumer should depend on it.

## Key Rules

1. **Tag first.** Version is a declaration, not a conclusion.
2. **Apps: no version files.** `git describe` is the single source of truth.
3. **Libs: one SNAPSHOT bump per cycle.** The only version commit that ever exists.
4. **Two merge directions.** Toward stabilization = merge. Toward development = merge. Cherry-pick only when crossing toward stabilization (e.g. staging fix → release).
5. **Fix close to production.** Don't fix on master what only exists on release.
6. **CI computes everything.** `VERSION=$(git describe --abbrev=10 --tags)` for apps. Tag name override for libs.

## Decision Procedure

If-then rules for common scenarios:

- **If** changing an application build → **never** modify version fields in files. Version comes from `git describe`.
- **If** starting a new sprint → `git tag X.Y` on master. Done.
- **If** bug exists only in production → branch from `release-X.Y`, fix there, merge back toward development.
- **If** bug exists in staging and production → branch from `staging-X.Y`, fix there, merge back toward development. Cherry-pick to `release-X.Y` only if critical.
- **If** bug exists only in dev → fix on `master`.
- **If** preparing a library release → `git checkout -b release-X.Y && git tag X.Y.0`. CI overrides version from tag.
- **If** patching a library release → fix on `release-X.Y`, then `git tag X.Y.N`. No file changes.
- **If** release branch was just cut for a library → bump master to next SNAPSHOT immediately.

## Branch Naming

| Branch | Purpose |
|---|---|
| `master` | Active development |
| `staging-X.Y` | Pre-production stabilization (apps only) |
| `release-X.Y` | Production / stable release line |

## Version Examples

| Type | Development | Staging | Release | Patch |
|---|---|---|---|---|
| Application | `1.97-7-g3a8b2c1f0e` | `1.97-3-g...` (on staging branch) | `1.97-1-g...` (on release branch) | automatic via commit count |
| Library | `0.46-SNAPSHOT` | n/a | `0.46.0` (tag) | `0.46.1` (tag) |

## CI One-Liners

```bash
# Application version
VERSION=$(git describe --abbrev=10 --tags)

# Library release (tag-triggered)
mvn versions:set -DnewVersion=$CI_COMMIT_REF_NAME && mvn deploy
```

## What NOT to Do

- Don't store application versions in files (pom.xml, package.json, etc.)
- Don't cherry-pick toward development — use merge (release → staging → master is a merge, not a cherry-pick)
- Don't let external consumers use SNAPSHOT artifacts
- Don't forget to bump master after cutting a release branch (libs)
- Don't keep dead release branches around
- Don't use SemVer for applications or auto-counting for libraries
