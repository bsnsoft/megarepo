# Changelog

## Unreleased

### Features
- **Repository copy button copies the full URL** — the copy action on the
  repositories list now puts the absolute URL (scheme + host +
  `/repository/<name>`) on the clipboard instead of just the path. (osTicket
  #558135)
- **Delete components and assets from the UI** — the component detail page has
  delete buttons (with confirmation) for the whole component and for individual
  assets. The management API (`DELETE /api/v1/components/{id}`,
  `DELETE /api/v1/assets/{id}`) now removes the backing blob(s) from storage as
  well as the database rows, and component deletion cascades to its assets
  (previously these leaked blobs / orphaned assets). (osTicket #558135)
- **Password-gated NuGet API key reveal (Account → NuGet API Key)** — the key is
  now hidden behind a password prompt (Sonatype-style); revealing it re-verifies
  the password server-side (`POST /api/v1/security/users/me/verify-password`).
  The key is a bearer token (sent as `X-NuGet-ApiKey` or `Authorization: Bearer`)
  — now stated plainly in the UI and admin guide. (osTicket #558135)
- **Legacy NuGet V2 (OData) read support for hosted repositories** — `$metadata`,
  `FindPackagesById()`, `Packages(Id='…',Version='…')` and `Search()` are served
  as OData/Atom XML so older clients (V2 sources, legacy nuget.exe) can list,
  inspect and download packages. Content is shared with V3 (the Atom entries link
  to the V3 flat-container download); push is unchanged. Hosted-only; the full
  OData query grammar (`$filter`/`$orderby`/…) and V2 proxying are out of scope.
  (osTicket #558135)
- **NuGet API key in the UI (Account → API Key)** — the personal access token
  that doubles as the `dotnet nuget push --api-key` value (and the npm/Maven
  bearer token) is now surfaced on the Account page: view (masked, with
  show/copy) and **Reset** to regenerate it
  (`POST /api/v1/security/auth/regenerate-token`). Note: tokens are stateless
  JWTs, so a reset does not retroactively invalidate the old key before it
  expires — a persistent, individually revocable personal-access-token model is
  planned. (osTicket #117649)
- **Outbound proxy configurable in the UI (System → HTTP)** — the global forward
  proxy (`enabled/host/port/username/password/non-proxy-hosts`) can now be set at
  runtime in the web UI and takes effect immediately without a restart. The
  deployment-side `megarepo.outbound-proxy.*` (Helm/env) configuration remains
  the fallback and is used until the UI is configured. The proxy password is
  write-only (never returned to the browser; blank on save keeps the stored
  value). New table `outbound_proxy_settings` (migration `V10`). (osTicket
  #117649)

### Fixed
- **npm proxy: cached packages now appear in Browse** — an npm proxy repository
  passed the upstream packument through unchanged, so every `dist.tarball` still
  pointed at the upstream registry. npm and pnpm therefore downloaded packages
  directly from upstream: nothing was cached, no components were created, and
  Browse stayed empty. Tarball URLs are now rewritten to point back at the
  repository, so downloads flow through MegaRepo and are cached and listed like
  any other format. Tarball URLs served by a *different* host than the configured
  `remoteUrl` (a registry with a separate download CDN) are left untouched.
  (GitHub #1)
- **npm proxy: unscoped packages were never registered as components** — the
  coordinate extractor only recognised the short tarball layout
  (`-/pkg-1.0.0.tgz`) and not the layout registries actually serve
  (`pkg/-/pkg-1.0.0.tgz`), so proxied unscoped packages were cached as assets but
  never linked to a component. Scoped packages were unaffected. (GitHub #1)
- **npm proxy: scoped-package metadata requests were very slow** — three separate
  causes, all fixed. Upstream fetches now advertise `Accept-Encoding: gzip` and
  inflate the response (the JDK HTTP client does neither on its own); the
  client's request for the abbreviated packument
  (`Accept: application/vnd.npm.install-v1+json`, which npm and pnpm send on
  every install) is forwarded upstream and cached separately from the full
  document; and metadata responses are no longer tagged with a *strong* ETag,
  which had silently prevented the servlet container from compressing them. For
  `@typescript-eslint/parser` this takes the response from ~15.7 MB uncompressed
  to ~2.5 MB on the wire. (GitHub #1)
- Admin guide: corrected the NuGet API-key login snippet to read the `token`
  field (was `accessToken`).

## 0.8 (2026-03-29)

### Highlights
- **7 milestones completed** (0.2 through 0.8)
- **113 issues closed** across 56 sprints
- **5 format plugins**: Maven, PyPI, npm, Raw, Docker
- **Real-world validated**: Spring Boot built through proxy (245 artifacts)
- **Production-ready**: Swagger API, admin guide, migration guide, backup tools

### Features
- Docker Registry V2 support (push/pull, proxy, multi-arch, GC)
- 15 default repositories on first startup
- Proxy cache TTL configuration + upstream authentication
- Format-specific search fields (Maven GAV, npm scope, PyPI)
- File upload widget for Raw repositories
- User/role CRUD complete (create, edit, delete)
- Group repository member picker
- Cleanup policy presets (simple mode + advanced)
- Task scheduling and execution
- License management with active user tracking (30-day audit log)
- Blob store create/manage UI with storage metrics
- Repository edit page (online/offline, proxy URL, group members)
- Account page with password change, profile editing

### Infrastructure
- Prometheus metrics (/actuator/prometheus) with custom gauges/counters
- Grafana dashboard JSON template
- Production docker-compose.yml with health checks, memory limits
- nginx TLS reverse proxy template
- Helm chart for Kubernetes deployment
- CI pipeline: build, test, integration test (Docker API), package, deploy
- Docker Hub publishing (bsnsoft/megarepo)

### Security
- JWT authentication with Docker token auth flow
- Anonymous access scoped to repository reads only
- SSRF protection on proxy URLs
- Path traversal protection
- Input validation on all DTOs
- Tomcat thread exhaustion fix
- OWASP Top 10 scan passed (except rate limiting)

### Documentation
- Admin guide, migration guide (from Nexus), upgrade guide
- Backup & restore procedures with scripts
- TLS setup guide, nginx proxy guide
- Monitoring guide with alert rules
- Support process with SLA tiers
- arc42 architecture document (IST/SOLL)
- API documentation via Swagger UI

### UI
- Professional design system (Inter font, consistent spacing)
- Tailwind CSS v4 with proper @layer cascade
- SVG icons throughout (no emoji)
- Responsive layout with mobile sidebar
- DE/EN landing page at bsnsoft.de/megarepo
- Impressum + Datenschutz (legal compliance)

