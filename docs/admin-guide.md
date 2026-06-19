# MegaRepo Administration Guide

## 1. Installation

### Docker Compose (Recommended)

Create a `docker-compose.yml`:

```yaml
services:
  megarepo:
    image: bsnsoft/megarepo:latest
    ports:
      - "8080:8080"
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://db:5432/megarepo?stringtype=unspecified
      SPRING_DATASOURCE_USERNAME: megarepo
      SPRING_DATASOURCE_PASSWORD: ${POSTGRES_PASSWORD:-megarepo}
      MEGAREPO_SECURITY_JWT_SECRET: ${MEGAREPO_JWT_SECRET:-change-me-in-production}
      MEGAREPO_DATA_DIRECTORY: /opt/megarepo/data
      MEGAREPO_BLOB_STORES_DEFAULT_PATH: /opt/megarepo/data/blobs/default
    volumes:
      - megarepo-data:/opt/megarepo/data
    depends_on:
      db:
        condition: service_healthy
    restart: unless-stopped

  db:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: megarepo
      POSTGRES_USER: megarepo
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:-megarepo}
    volumes:
      - postgres-data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U megarepo"]
      interval: 5s
      timeout: 5s
      retries: 5
    ports:
      - "5432:5432"
    restart: unless-stopped

volumes:
  megarepo-data:
  postgres-data:
```

Create a `.env` file alongside it:

```bash
POSTGRES_PASSWORD=<strong-random-password>
MEGAREPO_JWT_SECRET=<random-string-at-least-32-chars>
```

Start with:

```bash
docker compose up -d
```

MegaRepo is available at `http://localhost:8080`. Log in with `admin` / `admin123`.

### Helm / Kubernetes

MegaRepo ships a Helm chart with a bundled PostgreSQL subchart (Bitnami).

```bash
helm install megarepo ./helm/megarepo \
  --set postgresql.auth.password=<db-password> \
  --set env.MEGAREPO_SECURITY_JWT_SECRET=<jwt-secret>
```

Key values in `values.yaml`:

| Value | Default | Description |
|---|---|---|
| `image.repository` | `bsnsoft/megarepo` | Container image |
| `image.tag` | `latest` | Image tag — pin a release tag for reproducible deployments |
| `image.pullPolicy` | `Always` | Keep `Always` when using the mutable `latest` tag |
| `persistence.enabled` | `true` | Enable PVC for blob storage |
| `persistence.size` | `20Gi` | Blob store PVC size |
| `persistence.mountPath` | `/data` | Mount path inside container |
| `postgresql.enabled` | `true` | Deploy bundled PostgreSQL |
| `postgresql.auth.password` | `megarepo` | DB password (change this!) |
| `externalDatabase.host` | `""` | External DB when subchart disabled |
| `ingress.enabled` | `false` | Enable Ingress resource |
| `resources.requests.memory` | `256Mi` | Memory request |
| `resources.limits.memory` | `1Gi` | Memory limit |

For TLS with Ingress:

```yaml
ingress:
  enabled: true
  className: nginx
  annotations:
    cert-manager.io/cluster-issuer: letsencrypt-prod
  hosts:
    - host: megarepo.example.com
      paths:
        - path: /
          pathType: Prefix
  tls:
    - secretName: megarepo-tls
      hosts:
        - megarepo.example.com
```

Health probes are pre-configured against `/actuator/health`.

### Bare Metal

Requirements: Java 21+, PostgreSQL 16+.

1. Create a PostgreSQL database:
   ```sql
   CREATE USER megarepo WITH PASSWORD '<password>';
   CREATE DATABASE megarepo OWNER megarepo;
   ```

2. Download the MegaRepo JAR or build from source:
   ```bash
   ./gradlew :megarepo-app:bootJar
   ```

3. Run:
   ```bash
   java --enable-preview -jar megarepo-app.jar \
     --spring.datasource.url=jdbc:postgresql://localhost:5432/megarepo?stringtype=unspecified \
     --spring.datasource.username=megarepo \
     --spring.datasource.password=<password> \
     --megarepo.security.jwt.secret=<jwt-secret> \
     --megarepo.data-directory=/var/lib/megarepo/data \
     --megarepo.blob-stores.default-path=/var/lib/megarepo/data/blobs/default
   ```

---

## 2. Configuration Reference

All properties can be set via `application.yml`, environment variables, or CLI flags. Environment variables use uppercase with underscores (e.g. `MEGAREPO_SECURITY_JWT_SECRET`).

### Database

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/megarepo?stringtype=unspecified
    username: megarepo
    password: megarepo
```

### JWT Authentication

```yaml
megarepo:
  security:
    jwt:
      secret: change-me-in-production   # REQUIRED: random string, min 32 chars
      access-token-expiry: 12h          # UI session token lifetime (work-day friendly)
      refresh-token-expiry: 7d          # Refresh token lifetime
    default-admin-password: admin123    # Initial admin password (first run only)
```

**UI sessions**: the web UI renews its token automatically every 15 minutes
while it is open (sliding session), so an active session never expires
mid-work; `access-token-expiry` is the maximum idle time before a new login
is required. When a session does expire, the UI redirects to its own login
screen — the `WWW-Authenticate: Basic` challenge (and with it the browser's
native credentials popup) is only sent on the repository endpoints
(`/repository/**`, `/v2/**`), where Maven, npm, pip and Docker clients need it.

In the Helm chart both lifetimes are exposed as `auth.accessTokenExpiry` and
`auth.refreshTokenExpiry`.

### Blob Store

```yaml
megarepo:
  data-directory: ./data
  blob-stores:
    default-path: ./data/blobs/default  # File system path for the default blob store
```

### Proxy Settings

```yaml
megarepo:
  proxy:
    user-agent: MegaRepo/1.0
    connect-timeout: 10s
    read-timeout: 30s
    retry-on-timeout: 1
```

### Running Behind a Corporate Proxy

If MegaRepo itself has no direct internet access and all egress traffic must go
through a corporate forward proxy (often with authentication), configure the
global outbound proxy. It applies to **all upstream fetches** of proxy
repositories (Maven Central, PyPI, npmjs, Docker Hub, ...).

```yaml
megarepo:
  outbound-proxy:
    enabled: true
    host: proxy.corp.example.com
    port: 3128
    # Optional: proxy authentication (Basic)
    username: megarepo
    password: change-me
    # Optional: hosts that bypass the proxy ('*' wildcard supported)
    non-proxy-hosts:
      - localhost
      - "*.internal.example.com"
```

The same settings via environment variables (e.g. for Docker, Kubernetes, or
Helm `values.yaml` → container env):

```bash
MEGAREPO_OUTBOUNDPROXY_ENABLED=true
MEGAREPO_OUTBOUNDPROXY_HOST=proxy.corp.example.com
MEGAREPO_OUTBOUNDPROXY_PORT=3128
MEGAREPO_OUTBOUNDPROXY_USERNAME=megarepo
MEGAREPO_OUTBOUNDPROXY_PASSWORD=change-me
MEGAREPO_OUTBOUNDPROXY_NONPROXYHOSTS=localhost,*.internal.example.com
```

With the bundled Helm chart, use the `outboundProxy` block in `values.yaml` —
it maps onto exactly these environment variables:

```yaml
outboundProxy:
  enabled: true
  host: proxy.corp.example.com
  port: 3128
  username: megarepo
  # Either inline (ends up in the pod spec) ...
  password: change-me
  # ... or preferably from an existing Kubernetes Secret:
  # existingSecret: megarepo-proxy-credentials
  # passwordKey: proxy-password
  nonProxyHosts:
    - localhost
    - "*.internal.example.com"
```

Notes:

- **Proxy auth for HTTPS upstreams**: the JDK disables Basic authentication for
  CONNECT tunneling by default (`jdk.http.auth.tunneling.disabledSchemes=Basic`).
  When `megarepo.outbound-proxy` is enabled with credentials, MegaRepo clears
  this property programmatically at startup so authenticated proxies also work
  for `https://` upstreams. If you set the property yourself (e.g. via
  `JAVA_TOOL_OPTIONS`), your value wins and is left untouched.
- **UI configuration takes precedence.** The values above are the
  *deployment-side defaults*. An administrator can override them at runtime under
  **System → HTTP** (see below); once saved, the UI configuration wins. If the UI
  has never been used, the deployment-side configuration here remains
  authoritative — so env-only installs are unaffected.
- **Legacy JVM properties keep working.** With `enabled: false` (the default),
  behavior is unchanged: `JAVA_TOOL_OPTIONS="-Dhttp.proxyHost=... -Dhttp.proxyPort=...
  -Dhttps.proxyHost=... -Dhttps.proxyPort=..."` is still honored. Note that this
  legacy path cannot carry proxy credentials — for authenticated proxies use
  `megarepo.outbound-proxy.*`.
- **Startup log**: when enabled, MegaRepo logs one INFO line
  `Outbound proxy enabled: <host>:<port> (auth: yes/no, ...)`. The password is
  never logged.
- Per-repository HTTP proxy settings (repository attribute `proxy.httpProxy`)
  take precedence over the global outbound proxy for that repository.

#### Configuring the proxy in the UI (System → HTTP)

The outbound proxy can also be configured at runtime in the web UI under
**System → HTTP** — useful when you cannot redeploy to change proxy settings, or
when the proxy details are not known at deployment time.

- Toggle **Route upstream traffic through a forward proxy**, then fill in
  **Host**, **Port**, optional **Username/Password**, and optional
  **Non-proxy hosts** (comma-separated, `*` wildcard supported).
- **Save** applies the change **immediately** — no restart. The live upstream
  HTTP client is rebuilt in place, so the next proxy-repository fetch uses the
  new configuration.
- **Precedence and fallback.** As soon as you save here, the UI configuration
  takes precedence over `megarepo.outbound-proxy.*` (Helm/env). A badge on the
  page shows whether the active configuration comes from the UI
  (*Managed in UI*) or from the deployment-side defaults (*Using environment
  defaults*). Installs that never touch this page keep using their env/Helm
  configuration exactly as before.
- **Password handling.** The proxy password is **write-only**: it is stored in
  the database and never sent back to the browser. Leave the password field
  blank when editing to keep the stored password unchanged; type a new value to
  replace it. As with LDAP bind passwords and SSL key material, MegaRepo stores
  this secret as a database column (there is no encryption-at-rest layer);
  protect the database accordingly.

### TLS

```yaml
server:
  ssl:
    enabled: true
    key-store: file:/path/to/keystore.p12
    key-store-password: changeit
    key-store-type: PKCS12
```

In production, TLS termination at a reverse proxy (nginx, Traefik, cloud LB) is usually preferable. Set `server.forward-headers-strategy: native` (already the default) so MegaRepo respects `X-Forwarded-*` headers.

### Upload Limits

```yaml
spring:
  servlet:
    multipart:
      max-file-size: 1GB
      max-request-size: 1GB
```

### License

```yaml
megarepo:
  license:
    path: ./megarepo.license   # or set MEGAREPO_LICENSE_PATH
```

---

## 3. Default Setup

On first run (empty database), MegaRepo automatically creates:

**Blob store:** `default` (file-based, at the configured `blob-stores.default-path`)

**Repositories (18 total):**

| Name | Format | Type | Remote URL |
|---|---|---|---|
| `maven-releases` | maven | hosted | -- |
| `maven-snapshots` | maven | hosted | -- |
| `maven-central` | maven | proxy | https://repo1.maven.org/maven2/ |
| `maven-public` | maven | group | maven-central, maven-releases, maven-snapshots |
| `npm-hosted` | npm | hosted | -- |
| `npm-proxy` | npm | proxy | https://registry.npmjs.org/ |
| `npm-public` | npm | group | npm-proxy, npm-hosted |
| `pypi-hosted` | pypi | hosted | -- |
| `pypi-proxy` | pypi | proxy | https://pypi.org/simple/ |
| `pypi-public` | pypi | group | pypi-proxy, pypi-hosted |
| `nuget-hosted` | nuget | hosted | -- |
| `nuget-proxy` | nuget | proxy | https://api.nuget.org/v3/index.json |
| `nuget-public` | nuget | group | nuget-proxy, nuget-hosted |
| `raw-hosted` | raw | hosted | -- |
| `docker-hosted` | docker | hosted | -- |
| `docker-hub-proxy` | docker | proxy | https://registry-1.docker.io/ |
| `docker-public` | docker | group | docker-hub-proxy, docker-hosted |

**Users:**

| User | Role | Initial Password |
|---|---|---|
| `admin` | `nx-admin` (full access) | `admin123` (status: CHANGE_PASSWORD) |
| `anonymous` | `nx-anonymous` (read/browse) | -- |

**Scheduled tasks:**
- Cleanup repositories: daily at 01:00
- Compact blob store: daily at 02:00
- Purge negative cache: every 15 minutes

---

## 4. User Management

### RBAC Model

MegaRepo uses a role-based access control model with three layers:

- **Privileges** define fine-grained permissions: an action (`read`, `browse`, `*`), a format (`maven`, `*`), and a repository pattern (`maven-central`, `*`).
- **Roles** bundle one or more privileges. Roles can nest other roles.
- **Users** are assigned one or more roles.

Built-in (read-only) roles:

| Role | Privileges |
|---|---|
| `nx-admin` | `nx-all` (full access to everything) |
| `nx-anonymous` | Browse and read all repositories |

### Creating Users via API

```bash
# Authenticate first
TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}' | jq -r '.accessToken')

# Create a user
curl -X POST http://localhost:8080/api/v1/security/users \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{
    "userId": "deployer",
    "firstName": "CI",
    "lastName": "Deployer",
    "email": "deployer@example.com",
    "password": "s3curePassw0rd",
    "status": "ACTIVE",
    "roles": ["nx-anonymous"]
  }'
```

### Creating Custom Roles

```bash
curl -X POST http://localhost:8080/api/v1/security/roles \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{
    "id": "maven-deployer",
    "name": "Maven Deployer",
    "description": "Can read and write to Maven repositories",
    "privileges": [
      "nx-repository-view-*-*-read",
      "nx-repository-view-*-*-browse"
    ],
    "nestedRoles": []
  }'
```

### Anonymous Access

Anonymous access is enabled by default. The `anonymous` user has browse/read access to all repositories. To disable, update the anonymous access settings via the admin UI or API.

---

## 5. Repository Management

### Repository Types

- **Hosted**: stores artifacts uploaded directly (deploys, uploads).
- **Proxy**: caches artifacts fetched from a remote repository (Maven Central, npmjs.org, etc.). Subsequent requests are served from the local cache.
- **Group**: merges multiple hosted and proxy repositories under one URL. Clients configure one URL; MegaRepo searches member repos in order.

### Format-Native Endpoints

Clients access repositories at their native protocol endpoints:

| Format | Endpoint |
|---|---|
| Maven | `http://megarepo:8080/repository/{name}/` |
| npm | `http://megarepo:8080/repository/{name}/` |
| PyPI | `http://megarepo:8080/repository/{name}/` |
| NuGet | `http://megarepo:8080/repository/{name}/index.json` (V3 service index) |
| Raw | `http://megarepo:8080/repository/{name}/` |
| Docker | `http://megarepo:8080/v2/` (uses `megarepo.docker.default-repository` config) |

### Manual Uploads (Web UI / REST)

Besides the format-native publish mechanisms (`mvn deploy`, `npm publish`, `twine upload`, raw `PUT`), artifacts can be uploaded manually into **hosted** repositories — via the **Upload** page in the Web UI or via `POST /api/v1/components/upload` (multipart/form-data, authenticated). Proxy and group repositories are read-only.

| Format | Upload input | Notes |
|---|---|---|
| Maven | File(s) + groupId/artifactId/version, optional classifier/extension per file | Coordinates can alternatively be read from an uploaded `.pom`; a minimal POM can be generated (`generatePom=true`). `maven-metadata.xml` is regenerated automatically after upload. |
| npm | Package tarball (`.tgz`, from `npm pack`) | Name/version are read from the embedded `package.json`; registry metadata is generated dynamically. |
| PyPI | Distribution file (`.whl` / `.tar.gz`) + optional `name`/`version` | Name/version are derived from the standard distribution filename if omitted. |
| NuGet | Package (`.nupkg`, from `dotnet pack`) | Id/version/metadata are read from the embedded `.nuspec`; service index, version lists, registrations and search are generated dynamically. |
| Raw | File(s) + optional `directory` or `path` | Equivalent to a direct `PUT`. |
| Docker | — | Not supported: images consist of manifests + layers and must be pushed via `docker push` (Registry V2 API). |

```bash
# Maven: upload a JAR with coordinates, generate a POM, regenerate metadata
curl -X POST "http://localhost:8080/api/v1/components/upload?repository=maven-internal" \
  -H "Authorization: Bearer $TOKEN" \
  -F "groupId=com.example" -F "artifactId=my-lib" -F "version=1.0.0" \
  -F "generatePom=true" \
  -F "asset0=@my-lib-1.0.0.jar"

# npm: upload a tarball created with `npm pack`
curl -X POST "http://localhost:8080/api/v1/components/upload?repository=npm-internal" \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@my-pkg-1.2.3.tgz"

# NuGet: upload a package created with `dotnet pack`
curl -X POST "http://localhost:8080/api/v1/components/upload?repository=nuget-hosted" \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@MyPackage.1.0.0.nupkg"
```

Per-file attributes for Maven use the `<field>.<attribute>` convention, e.g. `-F "asset1=@my-lib-1.0.0-sources.jar" -F "asset1.classifier=sources" -F "asset1.extension=jar"`.

### Creating a Repository via API

```bash
curl -X POST http://localhost:8080/api/v1/repositories \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{
    "name": "maven-internal",
    "format": "maven",
    "type": "HOSTED",
    "online": true,
    "blobStoreName": "default",
    "attributes": {}
  }'
```

For a proxy repository, include `attributes.proxy.remoteUrl`:

```bash
curl -X POST http://localhost:8080/api/v1/repositories \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{
    "name": "jcenter-proxy",
    "format": "maven",
    "type": "PROXY",
    "online": true,
    "blobStoreName": "default",
    "attributes": {
      "proxy": { "remoteUrl": "https://jcenter.bintray.com/" }
    }
  }'
```

### NuGet

MegaRepo implements the **NuGet V3 protocol** (service index, flat container, registrations, search). The legacy V2/OData API is not provided — only the push endpoint uses the traditional `api/v2/package` path, as all NuGet clients do.

**Register the source** (hosted, proxy, or group repository — the URL always points at the repository's `index.json`):

```bash
dotnet nuget add source http://megarepo:8080/repository/nuget-public/index.json \
  --name megarepo
```

**API key = MegaRepo token.** `dotnet nuget push` authenticates via the `X-NuGet-ApiKey` header; MegaRepo accepts its standard JWT there (the same token used for `Authorization: Bearer`):

```bash
TOKEN=$(curl -s -X POST http://megarepo:8080/api/v1/security/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"deployer","password":"…"}' | jq -r '.token')

dotnet nuget push MyPackage.1.0.0.nupkg \
  --source megarepo --api-key "$TOKEN"
```

**Getting and resetting the API key in the UI.** Each user's API key is simply
their personal access token, so it is available without any API scripting on the
**Account** page (top-right user menu → *Account* → *NuGet API Key*):

- **Access** — the key is hidden behind a password prompt (Sonatype-style):
  enter your account password and click *Reveal key* before it is shown
  (masked by default; then use *Show* / *Copy*, or *Lock* to hide it again).
  Paste it as the `--api-key` value for `dotnet nuget push`, or as the bearer
  token / password for npm and Maven. The password is re-checked server-side via
  `POST /api/v1/security/users/me/verify-password`; LDAP-backed accounts have no
  local password and cannot use this reveal flow (use the login API to mint a
  token instead).
- **Reset** — *Reset API key* issues a fresh token for the account
  (`POST /api/v1/security/auth/regenerate-token`). Use this if a key may have
  leaked or as part of routine rotation; update any tooling that used the old
  key afterwards.

> **Note on revocation.** API keys are stateless JWTs, so a reset does **not**
> immediately invalidate the previous key — the old token keeps working until it
> expires (`megarepo.security.jwt.access-token-expiry`, default 12h). For
> true immediate revocation, lock or disable the user account, or rotate
> `megarepo.security.jwt.secret` (which invalidates *all* issued tokens). A
> persistent, individually revocable personal-access-token model is planned; see
> the changelog.

Pushing an already existing version returns `409 Conflict` — NuGet versions are immutable. A version can be removed with `dotnet nuget delete MyPackage 1.0.0 --source megarepo --api-key "$TOKEN"` (this deletes the package version; MegaRepo does not keep unlisted-but-restorable versions).

**Restore** works against the same source:

```bash
dotnet restore   # uses the configured source
# or explicitly:
dotnet restore --source http://megarepo:8080/repository/nuget-public/index.json
```

Notes:

- All flat-container paths are **lowercase** (`v3-flatcontainer/{id}/{version}/…`), matching the dotnet client's URL construction. Mixed-case requests are tolerated and normalized.
- Proxy repositories are configured with the upstream **service index URL** as remote URL (default `https://api.nuget.org/v3/index.json`). The upstream resource map is cached using the repository's metadata TTL; packages and version lists are cached like every other proxy format.
- Group repositories serve their own service index and merge member version lists; package downloads are answered by the first member that has the package.

### Proxy Cache Behavior

Proxy repositories cache remote artifacts locally. Cached artifacts are stored in the configured blob store. MegaRepo includes a scheduled task to purge negative cache entries every 15 minutes (configurable). The proxy connects with:

- Connect timeout: 10s (configurable via `megarepo.proxy.connect-timeout`)
- Read timeout: 30s (configurable via `megarepo.proxy.read-timeout`)
- 1 retry on timeout (configurable via `megarepo.proxy.retry-on-timeout`)

---

## 6. Backup & Restore

### Backup

Two things to back up:

1. **PostgreSQL database** (metadata, users, roles, repository config):
   ```bash
   pg_dump -h localhost -U megarepo megarepo > megarepo-backup-$(date +%F).sql
   ```
   Or in Docker:
   ```bash
   docker compose exec db pg_dump -U megarepo megarepo > megarepo-backup-$(date +%F).sql
   ```

2. **Blob store directory** (actual artifact files):
   ```bash
   tar czf megarepo-blobs-$(date +%F).tar.gz /opt/megarepo/data/blobs/
   ```
   Or back up the Docker volume:
   ```bash
   docker run --rm -v megarepo-data:/data -v $(pwd):/backup alpine \
     tar czf /backup/megarepo-blobs-$(date +%F).tar.gz /data
   ```

### Restore

1. Stop MegaRepo.
2. Restore the database:
   ```bash
   psql -h localhost -U megarepo megarepo < megarepo-backup-2026-03-28.sql
   ```
3. Restore the blob store to the same path.
4. Start MegaRepo. Flyway will skip already-applied migrations.

---

## 7. Monitoring

### Health Endpoint

MegaRepo exposes Spring Boot Actuator at:

```
GET /actuator/health
```

Response when healthy:

```json
{ "status": "UP" }
```

This endpoint is used by Kubernetes liveness/readiness probes and Docker health checks.

### Logging

MegaRepo logs to stdout (standard for containers). Key log messages:

- `MegaRepo started on port 8080` -- successful startup
- `Initialized blob store 'default' (type=file)` -- blob store ready
- `Created default repository: maven-central (maven/PROXY)` -- first-run setup
- `Failed to initialize blob store` -- blob store misconfiguration

In Docker Compose, view logs with:

```bash
docker compose logs -f megarepo
```

### Metrics

The `/api/v1/metrics` endpoint provides repository and system metrics for monitoring dashboards.

---

## 8. Upgrading

### Docker Compose

```bash
docker compose pull megarepo
docker compose up -d megarepo
```

Flyway runs automatically on startup and applies any pending database migrations. No manual SQL scripts are needed.

### Kubernetes / Helm

Recommended — pin the new release tag explicitly (tags are listed on
[Docker Hub](https://hub.docker.com/r/bsnsoft/megarepo/tags)):

```bash
helm upgrade megarepo ./helm/megarepo --set image.tag=<new-version> --reuse-values
```

When staying on the default `latest` tag, simply run `helm upgrade` again with
your existing values: the chart stamps a per-release rollout annotation into
the pod template, so the deployment restarts and (with the default
`pullPolicy: Always`) pulls the newest image.

> **Note:** Chart versions before 2026-06-12 used `pullPolicy: IfNotPresent`
> and no rollout annotation. With those chart versions a `helm upgrade` on
> `latest` changed nothing — the pod kept running, and even a manual restart
> reused the node-cached old image. Update the chart from the repository
> first; the next `helm upgrade` then rolls out correctly. Alternatively force
> a one-off fresh pull with:
>
> ```bash
> kubectl rollout restart deployment <release-name>-megarepo
> ```
>
> (only effective once the pod spec carries `imagePullPolicy: Always`).

### Bare Metal

Replace the JAR file and restart the service. Flyway handles migrations on startup.

### Pre-Upgrade Checklist

1. Back up the database and blob store (see Section 6).
2. Check the release notes for any breaking changes.
3. Test the upgrade in a staging environment first.
4. Ensure the application starts and `/actuator/health` returns UP.

---

## 9. Troubleshooting

### Port 8080 Already in Use

```
Web server failed to start. Port 8080 was already in use.
```

Change the port: `server.port=9090` or `SERVER_PORT=9090`.

### Database Connection Refused

```
Unable to obtain connection from database
```

- Verify PostgreSQL is running: `pg_isready -h localhost -p 5432`
- Check `SPRING_DATASOURCE_URL` points to the correct host/port
- In Docker Compose, ensure `depends_on` with `service_healthy` is set (it is by default)

### Flyway Migration Conflicts

```
Migration checksum mismatch
```

This means a migration file was modified after being applied. Never edit applied migrations. Contact support if this occurs after an upgrade.

### Proxy Timeout to Remote Repository

```
Read timed out ... for remote repository maven-central
```

- Increase `megarepo.proxy.read-timeout` (default 30s)
- Check network/firewall rules from the MegaRepo host to the remote URL
- Verify DNS resolution works inside the container

### Blob Store Path Not Writable

```
Failed to initialize blob store 'default'
```

- Ensure the data directory exists and is writable by the application user
- In Docker, check the volume mount: `megarepo-data:/opt/megarepo/data`
- On bare metal, ensure the configured `megarepo.blob-stores.default-path` directory has correct permissions

### Out of Memory

Increase JVM heap:
```bash
JAVA_OPTS="-Xmx1g" docker compose up -d
```
Or in Helm: `env.JAVA_OPTS: "-Xmx768m"`.

### Large File Upload Fails

If uploads fail for large artifacts, check the multipart limits:
```yaml
spring.servlet.multipart.max-file-size: 1GB
spring.servlet.multipart.max-request-size: 1GB
```
Also check any reverse proxy limits (e.g. nginx `client_max_body_size`).
