# Changelog

## Unreleased

### Features
- **Repository firewall, phase 1: identity, sources and audit mode** — components
  are identified by package URL (purl) instead of a CPE product name guessed from
  the artifact name, with a version scheme per ecosystem (Maven, npm, PyPI, NuGet,
  Docker, raw) so "1.0.0-beta" and "1.0.0" are ordered the way the ecosystem
  orders them. Advisories come from NVD, OSV and GHSA. A new **AUDIT mode**
  records what enforcement *would* do without blocking anything, and a comparison
  report shows CPE-based against purl-based matching on the existing data, so an
  operator can see the difference before switching. All enforcement sits behind a
  global master switch that is off by default. (osTicket #155155)
- **Repository firewall, phase 2: quarantine, rules, exemptions and upload
  enforcement** — a blocked component now enters a quarantine state machine and is
  released automatically once it stops matching (rather than staying blocked until
  someone notices). Five rule types were added on top of the vulnerability check:
  minimum age, unknown component, license, typosquatting and namespace confusion.
  Exemptions carry a scope and an expiry and have a request flow, so an override
  is a dated decision instead of a permanent hole. Uploads are evaluated, not just
  downloads, and the firewall has its own UI. (osTicket #155155)
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

### Security
Three authorization gaps of the same class, all present since the first public
release (the NVD firewall API since 0.10.0-beta). They share one cause: MegaRepo
expresses authorization only as filter-chain matchers, and the chain ends with
`/api/v1/**` merely `authenticated()` — so a controller without its own matcher
was silently open to **every** signed-in account, including the read-only
`nx-viewer` role that is also the LDAP default when no group mapping applies.
Operators who ran an affected version should assume any account could reach these
endpoints and rotate accordingly.

- **Privilege escalation through user administration** — `POST` and
  `PUT /api/v1/security/users` accepted a free-form `roles` list from any
  signed-in account, so a read-only user could grant themselves `nx-admin` and
  defeat every other role check in the product. Role administration, anonymous
  access, LDAP, SSL and system settings were writable the same way, and
  `PUT /api/v1/security/users/{id}/change-password` was open to every account.
  User, role and system administration now require `nx-admin`, and `UserService`
  refuses a role change from a non-administrator as a second line of defence.
  (osTicket #155155)
- **NVD firewall administration was open to every signed-in account** —
  `/api/v1/security/nvd-firewall` returned the configured NVD API key in
  cleartext, let the firewall be switched off with a `PUT`, and let the whitelist
  be written. Now `nx-admin`. Rotate the API key if an affected version was
  reachable by non-administrators. (osTicket #155155)
- **Operational administration and artifact deletion required no privilege** —
  running a scheduled task (which includes cleanup, and therefore deletes
  artifacts), reading blob stores (whose `GET` returned the S3
  `secretAccessKey`), editing cleanup policies and routing rules, invalidating
  caches, editing the blacklist, reading audit and activity logs, and `DELETE` on
  repositories, components and assets are all `nx-admin` now. Creating and
  updating repositories, uploading, searching, metrics and the remaining read
  endpoints were deliberately left as they were, because documented provisioning
  scripts and CI identities use them without administrator rights. (osTicket
  #155155)

### UI
- **Screens and controls follow the server's rules** — the operational screens
  that became administrator-only (tasks, blob stores, cleanup policies, routing
  rules, audit log) are no longer offered to accounts that cannot use them, and
  the delete controls for repositories, components and assets are hidden the same
  way. The repository screens themselves stay open, because creating and updating
  a repository is deliberately still open on the server. The blob store picker on
  the create screen falls back to a name field for non-administrators instead of
  a select that silently lists nothing. This is presentation only — the server
  enforces every one of these rules for itself. (osTicket #155155)

### Fixed
- **Scheduled tasks seeded at installation never ran** — the seeded tasks
  (including the NVD firewall sync and the cleanup tasks) were written without a
  `next_run`, and the scheduler only considers tasks that have one. They were
  therefore skipped forever on every installation, and only a manual run did
  anything. `next_run` is now derived from the cron expression during a scheduler
  pass. A missing `next_run` deliberately does **not** mean "due immediately" —
  manual-only tasks would then fire every minute. (osTicket #155155)
- **Group repositories bypassed the firewall completely** — a download resolved
  through a group repository ran neither enforcement nor audit, so any group was a
  way around the firewall. The resolving member now governs mode, policy and
  attribution, and a block is final: the group does not fall through to the next
  member that happens to hold a clean copy. A 403 names both the group and the
  member. Setting a firewall mode on the group itself is now rejected with a 400
  instead of being silently ignored. (osTicket #155155)
- **Uploads and downloads reached different firewall verdicts** — the two paths
  had grown apart in 17 ways, among them ignored exemptions, a release button for
  components classified as malicious, rejection of components that had been
  cleared, and grandfathering without an audit trail. Both directions now decide
  through the same assembly, which makes the divergence unrepresentable rather
  than merely fixed. (osTicket #155155)
- **Visual Studio could not browse NuGet proxy repositories** — the upstream
  service index was matched with an exact `@type` comparison, but the NuGet V3
  spec allows versioned spellings (`SearchQueryService/3.5.0`), and feeds may
  publish only those. The search resource then stayed unresolved and
  `/v3/search` answered 502. Resources are now grouped by base type with a
  documented, document-order-independent preference (named variant first,
  otherwise highest version). (osTicket #155155)
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

