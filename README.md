# MegaRepo

Affordable artifact repository manager by [bsnsoft.de](https://bsnsoft.de). A Sonatype Nexus alternative.

**Website**: [bsnsoft.de/megarepo](https://bsnsoft.de/megarepo)

## Pricing

- **Free** for private users and companies with fewer than 50 employees
- **600 EUR/year per instance** for larger organizations
- No per-seat pricing, no license server, no phone-home

## Features

- **4 Formats** -- Maven, PyPI, npm, Raw
- **3 Repository Types** -- Hosted, Proxy (upstream caching), Group (aggregation)
- **Security** -- JWT + HTTP Basic auth, RBAC, LDAP, configurable anonymous access
- **Search** -- Full-text search across all repositories (PostgreSQL pg_trgm)
- **Cleanup Policies** -- By age, download count, regex, or release-only retention
- **Storage** -- Local filesystem or S3-compatible blob stores
- **Web UI** -- React SPA for repository browsing, artifact upload, user/role management
- **REST API** -- Full management API with Swagger UI
- **Simple Deployment** -- One JAR + PostgreSQL. Docker Compose or Helm chart.

## Quick Start

```bash
docker compose up
```

Open [http://localhost:8080](http://localhost:8080) and log in with:

| Username | Password   |
|----------|------------|
| `admin`  | `admin123` |

That's it. MegaRepo is ready to receive artifacts.

## Client Configuration

All format-native endpoints are served at `/repository/{repository-name}/`. Replace `localhost:8080` with your server address and `my-maven-hosted` (etc.) with the name of the repository you created in the UI.

### Maven

Add the following to your `~/.m2/settings.xml`:

```xml
<settings>
  <servers>
    <server>
      <id>megarepo</id>
      <username>admin</username>
      <password>admin123</password>
    </server>
  </servers>

  <mirrors>
    <mirror>
      <id>megarepo</id>
      <mirrorOf>*</mirrorOf>
      <url>http://localhost:8080/repository/my-maven-group/</url>
    </mirror>
  </mirrors>
</settings>
```

To deploy artifacts, add a `distributionManagement` block to your `pom.xml`:

```xml
<distributionManagement>
  <repository>
    <id>megarepo</id>
    <url>http://localhost:8080/repository/my-maven-hosted/</url>
  </repository>
  <snapshotRepository>
    <id>megarepo</id>
    <url>http://localhost:8080/repository/my-maven-snapshots/</url>
  </snapshotRepository>
</distributionManagement>
```

Then deploy with:

```bash
mvn deploy
```

### PyPI (pip)

Create or edit `~/.pip/pip.conf` (Linux/macOS) or `%APPDATA%\pip\pip.ini` (Windows):

```ini
[global]
index-url = http://admin:admin123@localhost:8080/repository/my-pypi-group/simple/
trusted-host = localhost
```

To upload packages, configure `~/.pypirc`:

```ini
[distutils]
index-servers = megarepo

[megarepo]
repository = http://localhost:8080/repository/my-pypi-hosted/
username = admin
password = admin123
```

Then upload with:

```bash
twine upload --repository megarepo dist/*
```

### npm

Create or edit `.npmrc` in your project root or home directory:

```ini
registry=http://localhost:8080/repository/my-npm-group/
//localhost:8080/repository/my-npm-group/:_auth=YWRtaW46YWRtaW4xMjM=
//localhost:8080/repository/my-npm-hosted/:_auth=YWRtaW46YWRtaW4xMjM=
```

> The `_auth` value is the Base64 encoding of `admin:admin123`.

To publish:

```bash
npm publish --registry=http://localhost:8080/repository/my-npm-hosted/
```

### Raw (curl)

Upload any file:

```bash
curl -u admin:admin123 \
  --upload-file my-artifact.tar.gz \
  http://localhost:8080/repository/my-raw-hosted/path/to/my-artifact.tar.gz
```

Download:

```bash
curl -O http://localhost:8080/repository/my-raw-hosted/path/to/my-artifact.tar.gz
```

## Building from Source

Requires Java 21 and Docker (for integration tests with Testcontainers).

```bash
# Build all modules and run tests
./gradlew build

# Run locally (start PostgreSQL first)
docker compose up db -d
./gradlew :megarepo-app:bootRun

# Run full stack via Docker Compose
docker compose up --build

# Run integration tests only
./gradlew :megarepo-integration-tests:test
```

## Architecture

MegaRepo is a 15-module Gradle multi-project with a plugin-based format system.

```
megarepo-app                  Spring Boot assembly (single deployable JAR)
  megarepo-rest-api           Management REST API controllers
    megarepo-repository       Repository engine (hosted, proxy, group)
      megarepo-core           Plugin SPI and domain interfaces
      megarepo-storage        Blob store abstraction (filesystem-backed)
      megarepo-database       JPA entities, Flyway migrations
    megarepo-security         JWT authentication, RBAC, user management
    megarepo-search           PostgreSQL full-text search (pg_trgm)
  megarepo-tasks              Scheduled cleanup and compaction
  megarepo-web-ui             React SPA (admin panel)
  megarepo-format-maven       Maven2 layout, metadata, checksums
  megarepo-format-pypi        PyPI Simple API
  megarepo-format-npm         npm registry protocol
  megarepo-format-raw         Generic binary asset storage
```

Format plugins implement the `FormatPlugin` SPI and are loaded at runtime via Spring Boot auto-configuration. Repository types (hosted, proxy, group) are core abstractions shared across all formats.

## API

Interactive API documentation is available at:

```
http://localhost:8080/swagger-ui.html
```

Key endpoint categories under `/api/v1/`:

| Endpoint                      | Description                              |
|-------------------------------|------------------------------------------|
| `/api/v1/repositories`        | Create, update, delete, list repositories |
| `/api/v1/components`          | Browse and manage components             |
| `/api/v1/assets`              | Browse and manage individual assets      |
| `/api/v1/search`              | Full-text search across repositories     |
| `/api/v1/security/auth`       | Login, token refresh                     |
| `/api/v1/security/users`      | User management (CRUD)                   |
| `/api/v1/security/roles`      | Role management (CRUD)                   |
| `/api/v1/security/anonymous`  | Anonymous access settings                |
| `/api/v1/blobstores`          | Blob store management                    |
| `/api/v1/tasks`               | Scheduled task management                |
| `/api/v1/status`              | Health check                             |

Format-native endpoints (used by Maven, pip, npm, curl) are served at:

```
/repository/{repository-name}/**
```

## Configuration

MegaRepo is configured via `application.yml` or environment variables. Key properties:

| Property                                  | Default                    | Description                          |
|-------------------------------------------|----------------------------|--------------------------------------|
| `server.port`                             | `8080`                     | HTTP listen port                     |
| `spring.datasource.url`                   | `jdbc:postgresql://...`    | PostgreSQL JDBC URL                  |
| `spring.datasource.username`              | `megarepo`                 | Database username                    |
| `spring.datasource.password`              | `megarepo`                 | Database password                    |
| `megarepo.data-directory`                 | `./data`                   | Base path for all file storage       |
| `megarepo.blob-stores.default-path`       | `./data/blobs/default`     | Default blob store file path         |
| `megarepo.security.jwt.secret`            | (set via env var)          | JWT signing secret (change in prod!) |
| `megarepo.security.jwt.access-token-expiry`  | `30m`                   | Access token lifetime                |
| `megarepo.security.jwt.refresh-token-expiry` | `7d`                    | Refresh token lifetime               |
| `megarepo.security.default-admin-password`| `admin123`                 | Initial admin password               |
| `megarepo.proxy.connect-timeout`          | `30s`                      | Proxy repository connect timeout     |
| `megarepo.proxy.read-timeout`             | `60s`                      | Proxy repository read timeout        |
| `spring.servlet.multipart.max-file-size`  | `1GB`                      | Maximum upload file size             |

Environment variables follow Spring Boot conventions: `MEGAREPO_SECURITY_JWT_SECRET`, `SPRING_DATASOURCE_URL`, etc.

## Deployment

### Docker Compose (recommended)

```bash
docker compose up -d
```

This starts MegaRepo and PostgreSQL 16. Data is persisted in Docker volumes (`megarepo-data` and `postgres-data`).

### Standalone JAR

```bash
# Build the JAR
./gradlew :megarepo-app:bootJar

# Run (PostgreSQL must be available)
java -jar megarepo-app/build/libs/megarepo.jar \
  --spring.datasource.url=jdbc:postgresql://db-host:5432/megarepo \
  --megarepo.security.jwt.secret=your-secret-here
```

### Environment Variables for Production

At minimum, set these in production:

```bash
MEGAREPO_JWT_SECRET=your-secure-random-secret
SPRING_DATASOURCE_URL=jdbc:postgresql://your-db-host:5432/megarepo
SPRING_DATASOURCE_USERNAME=megarepo
SPRING_DATASOURCE_PASSWORD=a-strong-password
```

## Tech Stack

- Java 21 + Spring Boot 3.4
- PostgreSQL 16
- React + Tailwind CSS (Web UI)
- Docker / Docker Compose / Helm

## License

Business Source License 1.1 -- see [LICENSE](LICENSE) for details. Source code is available for reading, auditing, and non-production use. Production use requires a commercial license from [BSNSoft Solutions GmbH](https://bsnsoft.de). After 4 years, each release converts to Apache License 2.0.
