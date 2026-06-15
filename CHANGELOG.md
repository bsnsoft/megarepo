# Changelog

## Unreleased

### Features
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

