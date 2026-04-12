# Release Checklist: 0.2-beta

## Pre-release

- [ ] All tests pass (`./gradlew build`)
- [ ] CHANGELOG.md updated with final date
- [ ] README.md is current

## Tag & Build

- [ ] `git tag 0.2` on main branch
- [ ] CI pipeline builds Docker image successfully
- [ ] Docker Hub credentials configured (`DOCKERHUB_USERNAME`, `DOCKERHUB_TOKEN`)
- [ ] Verify `bsnsoft/megarepo:0.2-*` image available on Docker Hub
- [ ] Verify `docker compose up` works with released image

## Post-release

- [ ] Update website download section with version (bsnsoft.de/megarepo)
- [ ] Close GitLab milestone "Sprint 30: Release 0.2-beta"
- [ ] Close GitLab issue #22
- [ ] Announce release
