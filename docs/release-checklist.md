# Release Checklist

MegaRepo is an application under [EGB](EGB.md): the version is **not** stored in
any file, it is `git describe --abbrev=10 --tags`. A tag names a development
cycle at its start; every commit after it is `<tag>-<count>-g<sha>`, and that
string is the image tag. There is nothing to "bump" — releasing means tagging and
letting CI build.

Every push to `main` already builds and publishes
`bsnsoft/megarepo:<git describe>` **and** `bsnsoft/megarepo:latest`
(`.github/workflows/ci.yml`, job `package`). A named release is therefore mostly
a decision about what to call the current state, not a separate build.

## Pre-release

- [ ] Full local build green: `cd app && ./gradlew build`
      (integration tests need a Postgres — see [deployment.md](deployment.md))
- [ ] **CI on `main` is green.** The `package` job only runs after `build` and
      `test`; while `test` is red nothing is published, and `:latest` silently
      keeps pointing at the last green commit.
- [ ] `CHANGELOG.md` — the `Unreleased` section describes what actually changed,
      including a `Security` section if any authorization or credential handling
      changed
- [ ] `README.md` current
- [ ] Upgrade notes written if a migration changes existing data
      ([upgrade-guide.md](upgrade-guide.md))

## Tag & build

- [ ] `git tag X.Y.Z` on `main` (dash-format follows automatically; use
      `-beta` while the line is pre-1.0)
- [ ] `git push origin X.Y.Z` — the tag push triggers the same workflow
- [ ] CI green for the tag
- [ ] `bsnsoft/megarepo:X.Y.Z` visible on Docker Hub
- [ ] `docker compose up` works against the published image

## Post-release

- [ ] Download section on bsnsoft.de/megarepo updated
- [ ] Announce, and if the release closes a security gap, say so plainly with
      what operators need to rotate
- [ ] Close the corresponding GitHub issues

## Note on issue tracking

MegaRepo lives on **GitHub** (`github.com/bsnsoft/megarepo`, BSL 1.1). The
GitLab mirror under `claude-managed/` is archived and read-only — do not close
milestones or issues there.
