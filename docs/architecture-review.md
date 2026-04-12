# MegaRepo Architecture Review

**Reviewer**: Senior Software Architect (Hexagonal Architecture / DDD)
**Date**: 2026-03-28
**Scope**: Full backend codebase (`app/` directory, 230 production Java files, 73 test files)

---

## 1. Executive Summary

MegaRepo has a **pragmatic module structure that is roughly 60% of the way to proper Hexagonal Architecture**. The `megarepo-core` module is clearly intended as the domain layer with ports (interfaces), and implementations live in outer modules. However, several significant boundary violations undermine this design. The good news: the violations are systematic and fixable without a full rewrite.

**Verdict**: Solid foundation, but the hexagon leaks in three critical places. Fixing the top 3 issues would bring this to a genuinely clean Ports & Adapters architecture.

---

## 2. What's Already Good

These are real strengths -- do not change them:

### 2.1 Clean Domain Abstractions in Core
The `megarepo-core` module defines excellent port interfaces:
- `BlobStore` (storage port) -- pure Java, no infrastructure imports
- `RepositoryConfigService` (repository port) -- clean interface returning `RepositoryConfig` record
- `PrivilegeEvaluator` (security port) -- minimal, focused
- `FormatPlugin` SPI -- well-designed plugin port with proper extension points
- Domain value types (`RepositoryConfig`, `BlobRef`, `BlobProperties`, `Blob`, `ComponentCoordinates`) are records with zero infrastructure dependencies

### 2.2 Proper Adapter Placement
- `FileBlobStore` and `S3BlobStore` in `megarepo-storage` correctly implement the `BlobStore` port from core
- `RepositoryConfigServiceImpl` in `megarepo-repository` correctly implements `RepositoryConfigService` from core
- `PrivilegeEvaluatorImpl` in `megarepo-security` correctly implements `PrivilegeEvaluator` from core
- Format plugins (Maven, PyPI, npm, raw, Docker) register via `FormatPluginRegistrar` -- textbook SPI pattern

### 2.3 Good Sealed Interface for Responses
`FormatResponse` is an excellent sealed interface with `ContentResponse`, `NotFoundResponse`, `RedirectResponse`, `ErrorResponse`, `CreatedResponse`. This is proper domain modeling -- the response algebra is in core, and outer layers interpret it. The `RepositoryRouter.writeResponse()` method does a clean pattern match.

### 2.4 Solid Supporting Patterns
- XO suffix convention for DTOs (clean separation of API contracts from domain)
- `GlobalExceptionHandler` maps domain exceptions to HTTP status codes
- Domain exceptions (`NotFoundException`, `ValidationException`, etc.) in core
- JSONB columns for format-specific config (avoids table-per-format, very pragmatic)
- `MultiDigestInputStream` in storage layer (right place for this concern)
- `BlobStoreManager` as a simple registry

### 2.5 Reasonable Test Coverage
73 test files for 230 production files is a solid ratio. Unit tests exist for core services (`HostedHandler`, `GroupHandler`, `ProxyFetchService`, policy enforcers). Integration tests use Testcontainers.

---

## 3. Architecture Violations (Ranked by Severity)

### VIOLATION #1: `HttpServletRequest` in Core Domain Port (CRITICAL)

**File**: `app/megarepo-core/src/main/java/de/bsnsoft/megarepo/core/format/FormatRequestHandler.java`

```java
import jakarta.servlet.http.HttpServletRequest;

public interface FormatRequestHandler {
    FormatResponse handleHostedGet(RepositoryConfig repo, String path, HttpServletRequest request);
    FormatResponse handleHostedPut(RepositoryConfig repo, String path, HttpServletRequest request);
    // ...
}
```

This is the single most damaging violation. The core domain port -- the contract that every format plugin must implement -- depends on `jakarta.servlet`. This means:
- The domain layer is coupled to HTTP/Servlet infrastructure
- Format plugins cannot be tested without mocking `HttpServletRequest`
- The core cannot be reused in a non-Servlet context (gRPC, CLI, etc.)
- The `megarepo-core` build.gradle.kts has `compileOnly(libs.spring.boot.starter.web)` specifically to enable this

**Impact**: Every format plugin (`MavenRequestHandler`, `PypiRequestHandler`, `NpmRequestHandler`, `RawRequestHandler`, `DockerRequestHandler`) is infected by this coupling.

**What the handler actually needs from the request**: `InputStream` (body), `contentType`, `contentLength`, `remoteUser`, `remoteAddr`, `method`. That's it.

### VIOLATION #2: JPA Entities Leak Into Every Layer (HIGH)

Database entities (`AssetEntity`, `ComponentEntity`, `RepositoryEntity`, etc.) are used directly in:
- **REST controllers** (14 out of ~20 controllers import from `megarepo-database`)
- **Format plugins** (`MavenRequestHandler` directly uses `AssetJpaRepository`, `ComponentJpaRepository`)
- **Search module** (uses `EntityManager` with `ComponentEntity` and `AssetEntity`)
- **Repository engine** (`HostedHandler`, `ComponentService`, `AssetService` return `AssetEntity`/`ComponentEntity`)

The `RepositoryController` is the poster child: it directly calls `repositoryJpaRepository.findByName()`, creates `RepositoryEntity` instances, calls `groupMemberJpaRepository`, and wires group members -- all within the controller. There is no service layer for repository CRUD management.

This means:
- Changing the database schema forces changes across all layers
- Controllers contain business logic (group member wiring, attribute normalization)
- Format plugins are tightly coupled to JPA
- No domain model for Asset/Component -- only JPA entities exist

### VIOLATION #3: Spring Framework in Core Domain (MEDIUM)

**Files affected in `megarepo-core/src/main/java/`**:
- `FormatRegistry.java` -- `@Component` annotation
- `FormatPluginRegistrar.java` -- implements `InitializingBean`
- All 4 event classes -- extend `ApplicationEvent`

The core module has a Gradle dependency on `spring-boot-starter-web` (even if compileOnly). The domain shouldn't know about Spring at all. `FormatRegistry` should be a plain class; the `@Component` annotation should be in a configuration class in an outer module.

### VIOLATION #4: `RepositoryRouter` is a `@RestController` in `megarepo-repository` (MEDIUM)

**File**: `app/megarepo-repository/src/main/java/de/bsnsoft/megarepo/repository/RepositoryRouter.java`

The artifact-serving endpoint (`/repository/{name}/**`) is a Spring `@RestController` living in the repository engine module, not in `megarepo-rest-api`. This module also depends on `spring-boot-starter-web`. The repository module should be pure domain/application logic. The HTTP routing should be in `megarepo-rest-api`.

### VIOLATION #5: Format Plugins Bypass Service Layer (MEDIUM)

`MavenRequestHandler` directly uses `AssetJpaRepository` and `ComponentJpaRepository` instead of going through `AssetService`/`ComponentService`. The `storeAsset()` method duplicates logic from `AssetService.createAsset()` (blob storage, checksum computation, entity creation). Same for `findOrCreateComponent()` which duplicates `ComponentService.findOrCreate()`.

This means:
- Event publishing is inconsistent (direct saves skip `AssetCreatedEvent`/`ComponentCreatedEvent`)
- Audit logging is inconsistent (the proxy path audits; the Maven handler doesn't directly)
- Two places to maintain the same create/update logic

### VIOLATION #6: No Domain Model for Asset/Component (LOW-MEDIUM)

There is `RepositoryConfig` (a domain record in core) but no corresponding `Asset` or `Component` domain type. The JPA entity IS the domain model. This is acceptable for now but limits future refactoring.

---

## 4. Dependency Flow Analysis

### Current Dependency Graph (Actual)
```
megarepo-rest-api
  -> megarepo-core         (correct: inward)
  -> megarepo-database     (VIOLATION: controller -> infra directly)
  -> megarepo-repository   (correct: uses services)
  -> megarepo-security     (correct: uses service)
  -> megarepo-search       (correct)
  -> megarepo-storage      (correct: for BlobStoreController)
  -> megarepo-tasks        (correct)

megarepo-repository
  -> megarepo-core         (correct: inward)
  -> megarepo-database     (correct: adapter implements port)
  -> megarepo-storage      (correct: uses blob store)

megarepo-format-maven (and other formats)
  -> megarepo-core         (correct: implements SPI)
  -> megarepo-repository   (correct: uses services)
  -> megarepo-storage      (VIOLATION: should go through service)
  -> megarepo-database     (VIOLATION: direct JPA repo usage)

megarepo-security
  -> megarepo-core         (correct: implements port)
  -> megarepo-database     (correct: adapter)

megarepo-search
  -> megarepo-core         (correct)
  -> megarepo-database     (correct: adapter, but uses entities as return types)
```

### Ideal Dependency Graph
```
megarepo-rest-api      -> megarepo-core, megarepo-repository, megarepo-security (NO database)
megarepo-repository    -> megarepo-core, megarepo-database, megarepo-storage (correct)
megarepo-format-*      -> megarepo-core (ONLY -- use ports, not adapters)
megarepo-security      -> megarepo-core, megarepo-database (correct)
megarepo-search        -> megarepo-core, megarepo-database (correct)
megarepo-database      -> megarepo-core (correct)
megarepo-storage       -> megarepo-core (correct)
```

---

## 5. Top 10 Improvements (Ranked by Impact/Effort Ratio)

### 1. Remove `HttpServletRequest` from `FormatRequestHandler` (HIGH IMPACT, MEDIUM EFFORT)

Introduce a `FormatRequest` record in core:

```java
// In megarepo-core
public record FormatRequest(
    InputStream body,
    String contentType,
    long contentLength,
    String remoteUser,
    String remoteAddr,
    String method,
    Map<String, String> headers
) {}
```

Update `FormatRequestHandler` to use `FormatRequest` instead of `HttpServletRequest`. The conversion from `HttpServletRequest` to `FormatRequest` happens once in `RepositoryRouter` (the adapter).

**Files to change**: `FormatRequestHandler.java`, `RepositoryRouter.java`, all 5 format `*RequestHandler` implementations, `GroupHandler.java`.

Remove `compileOnly(libs.spring.boot.starter.web)` from core's build.gradle.kts.

### 2. Extract Service Layer for Repository CRUD (HIGH IMPACT, MEDIUM EFFORT)

Create a `RepositoryManagementService` in `megarepo-repository` that encapsulates:
- Repository CRUD (currently inline in `RepositoryController`)
- Group member wiring (currently inline in `RepositoryController.wireGroupMembers()`)
- Attribute normalization/validation (currently inline in `RepositoryController.normalizeAndValidateAttributes()`)

This removes ~150 lines of business logic from `RepositoryController` and makes it a thin adapter.

**File**: New `app/megarepo-repository/src/main/java/de/bsnsoft/megarepo/repository/RepositoryManagementService.java`

### 3. Route Format Plugins Through `AssetService`/`ComponentService` (HIGH IMPACT, MEDIUM EFFORT)

`MavenRequestHandler.storeAsset()` and `findOrCreateComponent()` duplicate `AssetService.createAsset()` and `ComponentService.findOrCreate()`. Refactor format handlers to call the existing services instead of using JPA repositories directly.

This eliminates the `megarepo-database` dependency from format plugins and ensures consistent event publishing and audit logging.

**Files to change**: `MavenRequestHandler.java`, `ChecksumFileHandler.java`, `MavenMetadataGenerator.java` (and equivalents in other format modules).

### 4. Move `RepositoryRouter` to `megarepo-rest-api` (MEDIUM IMPACT, LOW EFFORT)

Move `RepositoryRouter.java` from `megarepo-repository` to `megarepo-rest-api`. It's a `@RestController` -- it belongs with the other controllers. The repository module should not depend on `spring-boot-starter-web`.

**Files to move**: `RepositoryRouter.java`, `ActivityBroadcaster.java` (SSE concerns belong in the web layer), `ActivityEvent.java`.

### 5. Remove Spring Annotations from Core (MEDIUM IMPACT, LOW EFFORT)

- Remove `@Component` from `FormatRegistry` -- register it as a bean via `@Bean` method in an auto-configuration class
- Replace `ApplicationEvent` in domain events with plain records; use a `DomainEventPublisher` port in core that the outer layer adapts to Spring's `ApplicationEventPublisher`
- Remove `InitializingBean` from `FormatPluginRegistrar` -- use `@PostConstruct` or `@Bean(initMethod)` in the configuration

This lets `megarepo-core` be a pure Java module with zero Spring dependency.

### 6. Introduce Domain Types for Asset and Component (MEDIUM IMPACT, MEDIUM EFFORT)

Create in core:
```java
public record Asset(UUID id, UUID repositoryId, String path, String format,
                    String contentType, long size, Map<String, String> checksums,
                    Instant lastDownloaded, Instant createdAt) {}

public record Component(UUID id, UUID repositoryId, String format,
                        String namespace, String name, String version,
                        Instant createdAt) {}
```

`AssetService` and `ComponentService` return these instead of `AssetEntity`. JPA entities stay in `megarepo-database`, mapped at the service boundary.

### 7. Reduce Direct JPA Usage in REST Controllers (MEDIUM IMPACT, HIGH EFFORT)

Currently 14 out of ~20 controllers import JPA entities/repositories directly. For each, the pattern is the same: extract a service in the appropriate module, have the controller call the service.

Priority order:
1. `RepositoryController` (most complex, biggest gain)
2. `ComponentController` / `AssetController` (already have services, just need to use them)
3. `CleanupPolicyController` (has inline business logic)
4. `SearchController` (already uses `SearchService`, but also uses JPA repos for enrichment)

The CRUD-only controllers (`RoutingRuleController`, `TaskController`, `SecurityAnonymousController`) can remain as-is -- creating a service for trivial CRUD is over-engineering.

### 8. Introduce `AssetRepository` Port in Core (LOW IMPACT, MEDIUM EFFORT)

Define an `AssetRepository` interface in core (not JPA-specific) and implement it in `megarepo-database`. This would let format plugins depend only on the port, not the JPA implementation.

Only worth doing after #3 (routing through service layer) reduces the need.

### 9. Extract `ProxyFetchService` Request Context Access (LOW IMPACT, LOW EFFORT)

`ProxyFetchService.resolveCurrentRequest()` reaches into `RequestContextHolder` to get the current `HttpServletRequest`. This couples the service to the Servlet runtime. Instead, pass `user` and `ip` as parameters from the caller (which already has access to the request).

**File**: `app/megarepo-repository/src/main/java/de/bsnsoft/megarepo/repository/proxy/ProxyFetchService.java` (line 170-173, 454-465)

### 10. Make `BlobStoreManager` a Core Port (LOW IMPACT, LOW EFFORT)

`BlobStoreManager` is in `megarepo-storage` but is used by format plugins and repository services. Define a `BlobStoreRegistry` interface in core (just the `get(name)` method), implement it in `megarepo-storage`.

---

## 6. IOSP and Code Quality Assessment

### IOSP Compliance (Integration Operation Segregation Principle)

**Good**: `HostedHandler` is a clean integration class -- it orchestrates `AssetService` and `ComponentService` without doing operations itself. `RepositoryRouter.handleGet()` is also a good integrator.

**Bad**: `MavenRequestHandler.storeAsset()` (line 268-334) violates IOSP -- it does stream reading, blob storage, entity lookup/creation, checksum handling, and metadata setting all in one method. This is both an operation and an integration.

**Bad**: `RepositoryController.create()` is an integration that also performs operations (attribute normalization, group member wiring).

**Bad**: `ProxyFetchService.cacheRemoteContent()` (line 261-341) is 80 lines of mixed integration and operation logic.

### Method Length
- Most methods are under 30 lines (good)
- `MavenRequestHandler.storeAsset()` is 67 lines (too long)
- `RepositoryController.normalizeAndValidateAttributes()` is 60 lines (too long, should be a separate validator)
- `ProxyFetchService.cacheRemoteContent()` is 80 lines (too long)

### Error Handling
- Domain exceptions are well-defined in core (`NotFoundException`, `ValidationException`, `ConflictException`, `AccessDeniedException`)
- `GlobalExceptionHandler` is comprehensive and well-structured
- **Issue**: `UserService` throws `IllegalArgumentException` instead of domain exceptions
- **Issue**: `AuditService.saveEntry()` silently catches all exceptions -- audit failures should at least be retried
- **Issue**: `AssetService.deleteAsset()` silently swallows blob deletion failures

### Naming Conventions
- Consistent `*Entity` suffix for JPA entities
- Consistent `*JpaRepository` suffix
- Consistent `*XO` suffix for DTOs
- `*Service` for application services
- `*Controller` for REST endpoints
- **Minor inconsistency**: `HostedHandler` and `GroupHandler` don't follow the `*Service` pattern, but they're not services in the traditional sense -- acceptable

---

## 7. What NOT to Change

1. **The module structure itself** -- 15 modules is appropriate for this codebase. Don't merge or split them.
2. **JSONB attributes pattern** -- the `Map<String, Object> attributes` approach is pragmatic and avoids table-per-format explosion.
3. **`FormatPlugin` SPI design** -- the plugin interface is well-designed. Just fix the `HttpServletRequest` leak.
4. **`FormatResponse` sealed interface** -- perfect domain modeling.
5. **Continuation-token pagination** -- correct for API stability.
6. **`BlobRef.toExternalForm()` / `parse()`** -- clean serialization boundary.
7. **Spring auto-configuration for modules** -- each module has its own `*AutoConfiguration` class. This is proper Spring Boot idiom.
8. **Flyway migrations** -- don't try to "hexagonalize" the database migration mechanism.
9. **Trivial CRUD controllers** (`RoutingRuleController`, `TaskController`, `SecurityAnonymousController`) -- adding a service layer for simple pass-through CRUD is ceremony, not architecture.
10. **The test structure** -- Testcontainers + Playwright + unit tests is the right combination.

---

## 8. Recommended Refactoring Priority

| Sprint | Changes | Effort | Impact |
|--------|---------|--------|--------|
| Next | #1 (Remove HttpServletRequest from core), #5 (Remove Spring from core) | 2-3 days | Fixes the foundation |
| Next+1 | #3 (Route format plugins through services), #4 (Move RepositoryRouter) | 2-3 days | Eliminates database dependency from formats |
| Next+2 | #2 (Extract RepositoryManagementService), #9 (Fix ProxyFetchService) | 1-2 days | Clean controller layer |
| Later | #6 (Domain types), #7 (Reduce JPA in controllers), #8, #10 | 3-5 days | Polish, not critical |

Items #1 and #5 together take `megarepo-core` from "pretending to be a domain layer" to "actually being a domain layer." This is the single highest-ROI change.

---

## 9. Summary Metrics

| Aspect | Score | Notes |
|--------|-------|-------|
| Module boundaries | 6/10 | Good structure, porous boundaries |
| Domain purity (core) | 4/10 | Servlet + Spring + no Asset/Component domain types |
| Dependency direction | 7/10 | Mostly inward, violated by format plugins and controllers |
| Port/Adapter pattern | 7/10 | `BlobStore`, `RepositoryConfigService`, `PrivilegeEvaluator` are proper ports |
| SPI/Plugin design | 9/10 | `FormatPlugin` is excellent (minus the HttpServletRequest) |
| Code quality | 7/10 | Good naming, some long methods, reasonable test coverage |
| IOSP compliance | 5/10 | Mixed; some clean integrators, some god-methods |
| Overall Hexagonal | 6/10 | The intent is there; execution needs the top 3 fixes |
