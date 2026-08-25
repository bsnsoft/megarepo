# MegaRepo Architecture (arc42)

**Version**: 0.2-beta | **Date**: 2026-03-28 | **Author**: bsnsoft.de

---

## 1. Introduction & Goals

### What is MegaRepo?

MegaRepo is an affordable artifact repository manager -- a Sonatype Nexus alternative
targeting small companies and individuals. It stores, proxies, and serves build artifacts
for Maven, PyPI, npm, Docker, and raw formats. Commercial license at $200/month flat fee
(no per-seat pricing).

### Business Goals

| # | Goal | Priority |
|---|------|----------|
| 1 | Support 5 artifact formats (Maven, PyPI, npm, Docker, raw) with hosted/proxy/group repos | Must |
| 2 | Drop-in replacement for Nexus with lower cost and simpler operations | Must |
| 3 | Single-binary deployment (Docker image) with PostgreSQL as only dependency | Must |
| 4 | Extensible format system -- add new formats without touching core | High |
| 5 | Production-ready for teams of 5-50 developers | High |

### Quality Goals

| # | Quality | Measure |
|---|---------|---------|
| 1 | **Reliability** | Proxy cache serves artifacts when upstream is down |
| 2 | **Performance** | < 200ms p95 for cached artifact downloads |
| 3 | **Simplicity** | `docker compose up` gets a working instance in < 60 seconds |
| 4 | **Extensibility** | New format plugin requires zero changes to core modules |
| 5 | **Maintainability** | Module boundaries enforce separation of concerns |

---

## 2. Constraints

### Technical Constraints

| Constraint | Rationale |
|------------|-----------|
| Java 21 with preview features | Records, sealed interfaces, pattern matching |
| Spring Boot 3.4.x | Ecosystem maturity, auto-configuration for modules |
| PostgreSQL 16 | JSONB support, full-text search, no embedded DB needed |
| React + Tailwind CSS v4 | Admin SPA bundled into the Spring Boot jar |
| Gradle with Kotlin DSL | Multi-module build with version catalog |
| Single-process deployment | Target audience runs one instance, not a cluster |

### Organizational Constraints

| Constraint | Detail |
|------------|--------|
| Small team | 3 dev agents per sprint + 1 DevOps + 1 reviewer |
| Rapid cadence | Sprint-based, release every 10 sprints |
| CI/CD | GitLab CI, images published to Docker Hub |

---

## 3. Context (System Scope)

```
                                  +------------------+
                                  |   Developers     |
                                  | (mvn, pip, npm,  |
                                  |  docker, curl)   |
                                  +--------+---------+
                                           |
                                  artifact upload/download
                                  /repository/{name}/**
                                           |
+----------------+          +--------------+---------------+          +----------------+
| Maven Central  |<---proxy-|                              |---admin->| Admin Browser  |
| PyPI.org       |  fetch   |         MegaRepo             |  React   | /api/v1/**     |
| npmjs.org      |          |                              |  SPA     |                |
| Docker Hub     |          +---------+----------+---------+          +----------------+
+----------------+                    |          |
                                      |          |
                              +-------+--+  +----+-------+
                              |PostgreSQL|  |Blob Storage |
                              |   16     |  | File / S3  |
                              +----------+  +------------+

External integrations:
  - CI/CD pipelines (Jenkins, GitLab CI, GitHub Actions) -- artifact consumers
  - LDAP servers -- optional external authentication
  - S3-compatible storage -- optional blob backend (AWS S3, MinIO)
  - Reverse proxies (nginx, Traefik) -- TLS termination in production
```

---

## 4. Solution Strategy

| Decision | Strategy |
|----------|----------|
| **Format extensibility** | Plugin SPI (`FormatPlugin`) registered via Spring auto-configuration. Each format is an independent Gradle module. |
| **Repository types** | Three core abstractions -- hosted (local storage), proxy (remote cache), group (virtual merge) -- handled by the repository engine, not by format plugins. |
| **Schema flexibility** | JSONB `attributes` column on `RepositoryEntity` holds format-specific config. Avoids table-per-format proliferation. |
| **Storage abstraction** | `BlobStore` port in core, implemented by `FileBlobStore` (local disk) and `S3BlobStore`. Selected per repository. |
| **Security** | JWT-based authentication, RBAC with privileges per repository, optional LDAP integration. |
| **Search** | PostgreSQL full-text search over component/asset metadata. No Elasticsearch dependency. |
| **Deployment** | Single fat JAR in Alpine-based Docker image. Helm chart for Kubernetes. |

---

## 5. Building Block View (IST -- Current State)

### 5.1 Module Overview

17 Gradle modules, 230 production Java files, 73 test files.

```
megarepo-app ....................... Spring Boot assembly (main class, fat JAR)
  |
  +-- megarepo-rest-api ........... Management REST API (/api/v1/**)
  |     21 controllers, DTOs (XO suffix), GlobalExceptionHandler
  |
  +-- megarepo-repository ......... Repository engine
  |     RepositoryRouter, HostedHandler, GroupHandler, ProxyFetchService,
  |     AssetService, ComponentService, AuditService
  |
  +-- megarepo-core ............... Domain ports, SPI, value types
  |     FormatPlugin, FormatRequestHandler, FormatResponse (sealed),
  |     BlobStore, RepositoryConfig, RepositoryConfigService,
  |     PrivilegeEvaluator, domain exceptions, events
  |
  +-- megarepo-database ........... JPA entities, Flyway migrations
  |     16 entities, 16 JPA repositories, JSONB converter
  |
  +-- megarepo-storage ............ Blob store implementations
  |     FileBlobStore, S3BlobStore, BlobStoreManager, MultiDigestInputStream
  |
  +-- megarepo-security ........... AuthN/AuthZ
  |     JWT token service, RBAC, PrivilegeEvaluatorImpl, LDAP adapter
  |
  +-- megarepo-search ............. PostgreSQL full-text search
  +-- megarepo-tasks .............. Scheduled cleanup, compaction
  +-- megarepo-web-ui ............. React SPA (Vite + Tailwind CSS v4)
  |
  +-- megarepo-format-maven ....... Maven2 format plugin
  +-- megarepo-format-pypi ........ PyPI format plugin
  +-- megarepo-format-npm ......... npm format plugin
  +-- megarepo-format-docker ...... Docker Registry V2 plugin
  +-- megarepo-format-raw ......... Raw (generic) format plugin
  |
  +-- megarepo-bom ................ Bill of Materials (version alignment)
  +-- megarepo-integration-tests .. Testcontainers + Playwright E2E
```

### 5.2 Actual Dependency Graph

```
megarepo-rest-api
  --> megarepo-core             OK (inward)
  --> megarepo-repository       OK (uses services)
  --> megarepo-database         !! VIOLATION: controllers import JPA entities directly
  --> megarepo-security         OK
  --> megarepo-search           OK
  --> megarepo-storage          OK (BlobStoreController)
  --> megarepo-tasks            OK

megarepo-repository
  --> megarepo-core             OK (inward)
  --> megarepo-database         OK (adapter layer)
  --> megarepo-storage          OK (blob operations)
  --> spring-boot-starter-web   !! VIOLATION: RepositoryRouter is a @RestController here

megarepo-format-* (maven, pypi, npm, docker, raw)
  --> megarepo-core             OK (implements FormatPlugin SPI)
  --> megarepo-repository       OK (uses AssetService, ComponentService)
  --> megarepo-database         !! VIOLATION: direct JPA repository usage
  --> megarepo-storage          !! VIOLATION: bypasses service layer

megarepo-core
  --> spring-boot-starter-web   !! VIOLATION: compileOnly for HttpServletRequest
  --> spring (annotations)      !! VIOLATION: @Component on FormatRegistry,
                                   ApplicationEvent on domain events

megarepo-security  --> megarepo-core, megarepo-database     OK
megarepo-search    --> megarepo-core, megarepo-database      OK
megarepo-storage   --> megarepo-core                         OK
megarepo-database  --> megarepo-core                         OK
```

### 5.3 Hexagonal Violations Summary

| # | Violation | Severity | Location |
|---|-----------|----------|----------|
| V1 | `HttpServletRequest` in core SPI | CRITICAL | `FormatRequestHandler.java` in megarepo-core |
| V2 | JPA entities leak into controllers and format plugins | HIGH | 14 of ~20 controllers, all format handlers |
| V3 | Spring annotations in core domain | MEDIUM | `FormatRegistry` (@Component), events (ApplicationEvent) |
| V4 | `RepositoryRouter` is a @RestController in megarepo-repository | MEDIUM | Should be in megarepo-rest-api |
| V5 | Format plugins bypass service layer | MEDIUM | MavenRequestHandler uses JPA repos directly |
| V6 | No domain model for Asset/Component | LOW | JPA entity IS the domain model |

---

## 6. Building Block View (SOLL -- Target State)

### 6.1 Target Module Structure

```
                        +---------------------------+
                        |      megarepo-app         |  (assembly only)
                        +---------------------------+
                        |      megarepo-rest-api    |  (HTTP adapters)
                        |  - All @RestControllers   |
                        |  - RepositoryRouter       |  <-- moved from repository
                        |  - DTOs (XO)              |
                        |  - HttpServletRequest      |  <-- conversion to FormatRequest HERE
                        +------------+--------------+
                                     |
              +----------------------+------------------------+
              |                      |                        |
   +----------+--------+  +---------+----------+  +----------+---------+
   | megarepo-security |  | megarepo-repository |  | megarepo-search    |
   | (AuthN/AuthZ      |  | (domain services)   |  | (FTS adapter)     |
   |  adapter)         |  |  - HostedHandler     |  +--------------------+
   +-------------------+  |  - GroupHandler       |
                          |  - ProxyFetchService  |
                          |  - AssetService       |
                          |  - ComponentService   |
                          |  - RepoMgmtService    |  <-- new: extracted from controller
                          +----------+------------+
                                     |
              +----------------------+------------------------+
              |                      |                        |
   +----------+--------+  +---------+----------+  +----------+---------+
   | megarepo-database |  | megarepo-storage    |  | megarepo-tasks     |
   | (JPA adapter)     |  | (blob adapter)      |  | (scheduler)        |
   +-------------------+  +--------------------+  +--------------------+
              |                      |                        |
              +----------------------+------------------------+
                                     |
                        +------------+--------------+
                        |      megarepo-core        |  PURE DOMAIN
                        |                           |
                        |  Ports (interfaces):      |
                        |  - FormatPlugin SPI       |
                        |  - FormatRequestHandler   |  <-- uses FormatRequest record
                        |  - FormatResponse sealed  |
                        |  - BlobStore              |
                        |  - RepositoryConfigService|
                        |  - PrivilegeEvaluator     |
                        |  - AssetRepository (new)  |
                        |  - DomainEventPublisher   |  <-- replaces ApplicationEvent
                        |                           |
                        |  Value types (records):   |
                        |  - RepositoryConfig       |
                        |  - FormatRequest (new)    |
                        |  - Asset (new)            |
                        |  - Component (new)        |
                        |  - BlobRef, Blob          |
                        |  - ComponentCoordinates   |
                        |                           |
                        |  NO Spring, NO Servlet,   |
                        |  NO JPA -- pure Java      |
                        +---------------------------+
                                     ^
                                     |
              +----------------------+------------------------+
              |              |              |            |
   +----------+--+  +-------+---+  +-------+---+  +----+--------+
   | format-maven|  |format-pypi|  | format-npm|  |format-docker|  ...
   |             |  |           |  |           |  |             |
   | Depends ONLY on megarepo-core (+ megarepo-repository)     |
   | NO megarepo-database, NO megarepo-storage                 |
   +-------------+  +-----------+  +-----------+  +------------+
```

### 6.2 Key Changes from IST to SOLL

1. **`FormatRequest` record in core** replaces `HttpServletRequest`:
   ```
   FormatRequest(InputStream body, String contentType, long contentLength,
                 String remoteUser, String remoteAddr, String method,
                 Map<String, String> headers)
   ```
   Conversion happens once in `RepositoryRouter` (the HTTP adapter).

2. **Core becomes pure Java** -- remove `@Component`, `ApplicationEvent`, `InitializingBean`.
   Use `DomainEventPublisher` port interface; Spring adapter publishes to `ApplicationEventPublisher`.

3. **Format plugins lose database dependency** -- route all persistence through
   `AssetService`/`ComponentService` in megarepo-repository.

4. **Domain types for Asset/Component** -- JPA entities stay in megarepo-database;
   services map to/from domain records at the boundary.

5. **`RepositoryRouter` moves to megarepo-rest-api** -- megarepo-repository loses
   its `spring-boot-starter-web` dependency.

### 6.3 Dependency Rule

All arrows point inward. Outer modules depend on inner modules, never the reverse.

```
  Adapters (rest-api, database, storage, security, format-*)
      |
      v
  Application Services (repository, search, tasks)
      |
      v
  Domain (core) -- zero infrastructure dependencies
```

---

## 7. Runtime View

### 7.1 Proxy Cache Hit

```
Client                RepositoryRouter       FormatRequestHandler     AssetService      BlobStore
  |  GET /repository/   |                       |                       |                  |
  |  maven-central/     |                       |                       |                  |
  |  org/foo/bar.jar    |                       |                       |                  |
  |-------------------->|                       |                       |                  |
  |                     | handleProxyGet()      |                       |                  |
  |                     |---------------------->|                       |                  |
  |                     |                       | findAsset(repo, path) |                  |
  |                     |                       |---------------------->|                  |
  |                     |                       |       AssetEntity     |                  |
  |                     |                       |<----------------------|                  |
  |                     |                       | get(blobRef)          |                  |
  |                     |                       |------------------------------------->|   |
  |                     |                       |       InputStream     |              |   |
  |                     |                       |<-------------------------------------|   |
  |                     | ContentResponse       |                       |                  |
  |                     |<----------------------|                       |                  |
  | 200 OK + stream     |                       |                       |                  |
  |<--------------------|                       |                       |                  |
```

### 7.2 Proxy Cache Miss

```
Client          RepositoryRouter    FormatRequestHandler    ProxyFetchService    RemoteHttpClient    BlobStore
  |  GET ...        |                     |                       |                    |                |
  |---------------->|                     |                       |                    |                |
  |                 | handleProxyGet()    |                       |                    |                |
  |                 |--->|                |                       |                    |                |
  |                 |    | asset not in DB |                      |                    |                |
  |                 |    | fetchRemote()   |                      |                    |                |
  |                 |    |--------------->|                       |                    |                |
  |                 |    |               | fetch(remoteUrl+path)  |                    |                |
  |                 |    |               |----------------------->|                    |                |
  |                 |    |               |    200 + InputStream   |                    |                |
  |                 |    |               |<-----------------------|                    |                |
  |                 |    |               | store(stream)          |                    |                |
  |                 |    |               |----------------------------------------------->|             |
  |                 |    |               |    BlobRef             |                    |  |             |
  |                 |    |               |<-----------------------------------------------|             |
  |                 |    |               | save AssetEntity + ComponentEntity          |                |
  |                 |    | ContentResponse|                       |                    |                |
  |                 |    |<--------------|                        |                    |                |
  |                 | ContentResponse    |                       |                    |                |
  | 200 OK + stream |<---|               |                       |                    |                |
  |<----------------|    |               |                       |                    |                |
```

### 7.3 Hosted Upload (PUT)

```
Client           RepositoryRouter     FormatRequestHandler     AssetService       BlobStore
  | PUT /repository/  |                     |                       |                |
  | my-hosted/        |                     |                       |                |
  | com/foo/1.0/a.jar |                     |                       |                |
  |------------------>|                     |                       |                |
  |                   | handleHostedPut()   |                       |                |
  |                   |-------------------->|                       |                |
  |                   |                     | store(stream, meta)   |                |
  |                   |                     |---------------------->|                |
  |                   |                     |                       | store(blob)    |
  |                   |                     |                       |--------------->|
  |                   |                     |                       |    BlobRef     |
  |                   |                     |                       |<---------------|
  |                   |                     |                       | save entity    |
  |                   |                     |    CreatedResponse    |                |
  |                   |                     |<----------------------|                |
  |                   | CreatedResponse     |                       |                |
  | 201 Created       |<-------------------|                       |                |
  |<------------------|                     |                       |                |
```

### 7.4 Group Resolution

```
Client           RepositoryRouter     GroupHandler       GroupMemberResolver     FormatRequestHandlers
  | GET /repository/  |                   |                     |                       |
  | maven-group/      |                   |                     |                       |
  | org/foo/bar.jar   |                   |                     |                       |
  |------------------>|                   |                     |                       |
  |                   | handleGet(GROUP)  |                     |                       |
  |                   |------------------>|                     |                       |
  |                   |                   | resolveMembers()    |                       |
  |                   |                   |------------------->|                        |
  |                   |                   |  [hosted-a, proxy-b]|                       |
  |                   |                   |<-------------------|                        |
  |                   |                   | try hosted-a first  |                       |
  |                   |                   |--------------------------------------->|    |
  |                   |                   |   NotFoundResponse  |                  |    |
  |                   |                   |<---------------------------------------|    |
  |                   |                   | try proxy-b         |                       |
  |                   |                   |--------------------------------------->|    |
  |                   |                   |   ContentResponse   |                  |    |
  |                   |                   |<---------------------------------------|    |
  |                   | ContentResponse   |                     |                       |
  | 200 OK + stream   |<-----------------|                     |                       |
  |<------------------|                   |                     |                       |
```

### 7.5 Search

```
Client            SearchController      SearchService         PostgreSQL
  | GET /api/v1/      |                     |                     |
  | search?q=spring   |                     |                     |
  |------------------>|                     |                     |
  |                   | search(query, token)|                     |
  |                   |-------------------->|                     |
  |                   |                     | ts_query + ts_rank  |
  |                   |                     |-------------------->|
  |                   |                     |    ranked results   |
  |                   |                     |<--------------------|
  |                   |  PageResponse<XO>   |                     |
  |                   |<--------------------|                     |
  | 200 + JSON        |                     |                     |
  |<------------------|                     |                     |
```

---

## 8. Deployment View

### 8.1 Docker Compose (Development / Small Production)

```
+-------------------------------------------------------+
|  Docker Host                                           |
|                                                        |
|  +---------------------+    +----------------------+   |
|  | megarepo:8080       |    | postgres:16-alpine   |   |
|  | eclipse-temurin:21  |--->| :5432                |   |
|  | -jre-alpine         |    |                      |   |
|  |                     |    | Volume: postgres-data |   |
|  | Volume: megarepo-   |    +----------------------+   |
|  |   data (/opt/       |                               |
|  |   megarepo/data)    |                               |
|  +---------------------+                               |
+-------------------------------------------------------+
```

- Image: `eclipse-temurin:21-jre-alpine` (~180 MB)
- Runs as non-root user `megarepo`
- Health check: `wget http://localhost:8080/api/v1/status`
- Blob storage: local filesystem volume or S3

### 8.2 Kubernetes / Helm

```
+-- Kubernetes Cluster ----------------------------------+
|                                                        |
|  +-- Namespace: megarepo -------------------------+    |
|  |                                                |    |
|  |  Ingress (nginx/traefik)                       |    |
|  |    |                                           |    |
|  |    v                                           |    |
|  |  Service (ClusterIP:8080)                      |    |
|  |    |                                           |    |
|  |    v                                           |    |
|  |  Deployment (1 replica)                        |    |
|  |    megarepo container                          |    |
|  |    Resources: 256Mi-1Gi RAM, 250m-1000m CPU    |    |
|  |    PVC: 20Gi (blob storage)                    |    |
|  |    Probes: liveness (60s), readiness (30s)     |    |
|  |                                                |    |
|  |  StatefulSet (Bitnami PostgreSQL subchart)     |    |
|  |    PVC: 8Gi                                    |    |
|  |                                                |    |
|  +------------------------------------------------+    |
+--------------------------------------------------------+
```

Helm chart: `app/helm/megarepo/` with support for:
- External PostgreSQL (`externalDatabase.*`)
- Ingress with TLS (cert-manager compatible)
- Custom environment variables and Spring config injection
- Image from `bsnsoft/megarepo`

---

## 9. Cross-Cutting Concerns

### 9.1 Security

| Layer | Mechanism |
|-------|-----------|
| **Authentication** | JWT tokens (JJWT library), issued via `/api/v1/security/auth/login` |
| **Authorization** | RBAC with privileges per repository (read/write/admin). `PrivilegeEvaluator` port in core, implemented in megarepo-security. |
| **Anonymous access** | Configurable per-instance (allow/deny read for unauthenticated users) |
| **LDAP** | Optional external authentication source, configured via admin API |
| **Secrets** | JWT secret via environment variable `MEGAREPO_SECURITY_JWT_SECRET` |
| **Path traversal** | `RepositoryRouter.containsPathTraversal()` rejects `..` segments, URL-encoded variants, null bytes |
| **Redirect safety** | Blocks `javascript:`, `data:`, and protocol-relative URLs in redirect responses |

### 9.2 Error Handling

- Domain exceptions in `megarepo-core`: `NotFoundException`, `ValidationException`, `ConflictException`, `AccessDeniedException`
- `GlobalExceptionHandler` in megarepo-rest-api maps exceptions to HTTP status codes
- `FormatResponse.ErrorResponse` for format-specific errors with status codes
- Known gap: `AuditService.saveEntry()` silently swallows exceptions

### 9.3 Logging

- SLF4J + Logback (Spring Boot default)
- Audit log: separate `audit_log` table tracking downloads, uploads, deletes with user, IP, timestamp
- Activity broadcast: SSE stream for real-time UI updates

### 9.4 Monitoring

- Spring Boot Actuator endpoints (health, readiness, liveness)
- `/api/v1/status` health endpoint
- Kubernetes probes configured in Helm chart
- `BlobStoreMetrics` per blob store (size, count)
- `MetricsService` for repository-level and system-level metrics

### 9.5 Data Integrity

- Checksums computed on upload via `MultiDigestInputStream` (MD5, SHA-1, SHA-256)
- Checksum files (`.md5`, `.sha1`) served as computed values from DB columns, not stored as blobs
- `ETag` header set from SHA-1 checksum on download

---

## 10. Architecture Decisions

### ADR-1: PostgreSQL over Embedded Database

**Context**: Nexus uses embedded OrientDB/H2, causing data corruption issues at scale.

**Decision**: PostgreSQL 16 as the sole database, even for small deployments.

**Rationale**: JSONB columns for format-specific config, full-text search without
Elasticsearch, mature replication and backup tooling, no custom clustering needed.
The operational cost of "bring a PostgreSQL" is offset by the reliability gain.

### ADR-2: JSONB for Format-Specific Configuration

**Context**: Each format (Maven, PyPI, npm, Docker) has unique repository settings
(version policy, layout policy, remote URL, Docker API version, etc.).

**Decision**: Single `attributes JSONB` column on `RepositoryEntity` instead of
separate tables per format.

**Rationale**: Adding a format plugin requires zero schema migrations. The JSONB
column is validated at the application layer by `FormatPlugin.validateRepositoryConfig()`.
Queries that filter by format attributes use PostgreSQL JSONB operators.

### ADR-3: Plugin SPI over Monolith

**Context**: Supporting 5+ artifact formats in one codebase risks a monolith where
format-specific code is tangled with core logic.

**Decision**: `FormatPlugin` SPI interface in megarepo-core. Each format is an
independent Gradle module registered via Spring Boot auto-configuration.

**Rationale**: Format plugins are isolated modules with their own dependencies
(e.g., `jackson-dataformat-xml` only in maven, not in core). New formats are added
by creating a module and implementing the SPI. The `FormatRegistry` discovers
plugins at startup.

### ADR-4: Tailwind CSS v4

**Context**: Admin UI needs to be functional and maintainable by backend developers.

**Decision**: React SPA with Tailwind CSS v4 (utility-first CSS), bundled by Vite
and served from the Spring Boot jar.

**Rationale**: Tailwind avoids the need for a dedicated CSS architecture. Components
are styled inline with utility classes. No BEM, no CSS modules, no design system
required. v4 was chosen for its zero-config CSS-first setup.

### ADR-5: Continuation-Token Pagination

**Context**: Offset/limit pagination breaks when items are inserted or deleted
between pages.

**Decision**: All list endpoints use opaque continuation tokens instead of page
numbers.

**Rationale**: API stability under concurrent writes. The token encodes the last
seen ID, making pagination consistent even when the dataset changes. Matches the
Nexus API convention, easing migration.

---

## 11. Risks & Technical Debt

### Risk 1: HttpServletRequest in Core SPI (CRITICAL)

**What**: `FormatRequestHandler` -- the contract every format plugin implements --
takes `jakarta.servlet.http.HttpServletRequest`. This couples the domain to Servlet
infrastructure.

**Impact**: Format plugins cannot be tested without mocking `HttpServletRequest`.
Core cannot be reused in non-Servlet contexts (gRPC, CLI tools). Every format module
carries this infection.

**Mitigation**: Introduce `FormatRequest` record in core (body, contentType, headers).
Convert from `HttpServletRequest` once in `RepositoryRouter`. Estimated effort: 2-3 days.

### Risk 2: JPA Entity Leakage Across Boundaries (HIGH)

**What**: `AssetEntity` and `ComponentEntity` are used directly in REST controllers,
format plugins, and search module. There are no domain-level `Asset`/`Component` types.

**Impact**: Database schema changes ripple across all layers. Format plugins are
coupled to JPA. Business logic lives in controllers (e.g., `RepositoryController`
does group member wiring and attribute validation inline).

**Mitigation**: Route format plugins through `AssetService`/`ComponentService` (eliminates
direct JPA usage). Extract `RepositoryManagementService` from controller. Introduce
domain records for Asset/Component long-term. Estimated effort: 3-5 days.

### Risk 3: Single-Instance Architecture (MEDIUM)

**What**: MegaRepo is designed as a single-process application. No clustering,
no shared-nothing architecture, no distributed blob locking.

**Impact**: Vertical scaling only. If the instance goes down, artifact serving stops.
Not suitable for large enterprises (100+ developers with HA requirements).

**Mitigation**: Acceptable for target market (small teams). PostgreSQL provides
data durability. S3 blob store enables stateless-ish operation. If HA becomes a
requirement: add read replicas + load balancer for GET traffic, use S3 as shared
blob store. This is a future concern, not a current blocker.

### Risk 4: Proxy Fetch Thundering Herd (MEDIUM)

**What**: `ProxyFetchService` uses `ConcurrentHashMap<String, CompletableFuture>`
to coalesce concurrent requests for the same artifact, but this is in-process only.

**Impact**: Multiple concurrent cache misses for the same artifact cause one
upstream fetch (good), but the coalescing map is not persisted and not shared
across restarts.

**Mitigation**: The current approach is correct for single-instance. The negative
cache (`NegativeCacheService`) prevents repeated upstream requests for missing
artifacts. If scaling to multiple instances, use PostgreSQL advisory locks for
distributed fetch coordination.

### Risk 5: No Automated Performance Testing (LOW)

**What**: No load tests or performance benchmarks in CI. The p95 < 200ms quality
goal is not validated automatically.

**Impact**: Performance regressions could ship undetected. Proxy caching behavior
under load is untested.

**Mitigation**: Add a Gatling or k6 test suite targeting cached downloads, proxy
fetches, and search queries. Run as a nightly CI job, not on every commit.
