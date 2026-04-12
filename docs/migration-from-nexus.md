# Migrating from Sonatype Nexus to MegaRepo

This guide covers migrating from Sonatype Nexus Repository Manager (OSS or Pro) to MegaRepo. MegaRepo is designed as a Nexus-compatible alternative, so many concepts map directly.

---

## 1. Concept Mapping

| Nexus Concept | MegaRepo Equivalent | Notes |
|---|---|---|
| Hosted repository | Hosted repository | Same concept, same behavior |
| Proxy repository | Proxy repository | Same concept; MegaRepo caches remotely fetched artifacts |
| Group repository | Group repository | Same concept; ordered member list, merged responses |
| Blob store (file) | Blob store (file) | Default blob store created automatically |
| Blob store (S3) | Not yet supported | File-based only for now |
| Realms | JWT + RBAC | MegaRepo uses JWT tokens, not session-based auth |
| Roles | Roles | Privilege-based roles, nestable |
| Privileges | Privileges | Format/repository/action scoped, same model |
| Content selectors | Not yet supported | Use repository-level privileges instead |
| Routing rules | Routing rules | Available via `/api/v1/routing-rules` |
| Cleanup policies | Cleanup policies | Available via `/api/v1/cleanup-policies` |
| Tasks (scheduled) | Scheduled tasks | Pre-configured for cleanup, compaction, cache purge |
| Docker Bearer Token | Docker V2 API | MegaRepo serves `/v2/` directly |
| Nexus REST API (`/service/rest/`) | MegaRepo API (`/api/v1/`) | Similar structure, different base path |
| Repository endpoint `/repository/{name}/` | `/repository/{name}/` | Same URL pattern |
| Anonymous access | Anonymous access | Enabled by default with browse/read privileges |
| LDAP integration | LDAP integration | Available via `/api/v1/security/ldap` |

---

## 2. Repository Migration

### 2.1 Inventory Your Nexus Repositories

List all repositories in Nexus:

```bash
curl -u admin:admin123 https://nexus.example.com/service/rest/v1/repositories | jq '.[].name'
```

Note each repository's format, type (hosted/proxy/group), remote URL (for proxies), and group members.

### 2.2 Create Matching Repositories in MegaRepo

MegaRepo creates 15 default repositories on first run (see the Admin Guide, Section 3). If your Nexus setup uses standard names, many will already exist. For additional repositories:

```bash
# Authenticate
TOKEN=$(curl -s -X POST http://megarepo:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}' | jq -r '.accessToken')

# Create a hosted repository
curl -X POST http://megarepo:8080/api/v1/repositories \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{
    "name": "my-maven-releases",
    "format": "maven",
    "type": "HOSTED",
    "online": true,
    "blobStoreName": "default",
    "attributes": {}
  }'

# Create a proxy repository
curl -X POST http://megarepo:8080/api/v1/repositories \
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

# Create a group repository
curl -X POST http://megarepo:8080/api/v1/repositories \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{
    "name": "maven-all",
    "format": "maven",
    "type": "GROUP",
    "online": true,
    "blobStoreName": "default",
    "attributes": {
      "group": { "memberNames": ["maven-central", "my-maven-releases"] }
    }
  }'
```

### 2.3 Proxy Repositories

Proxy repositories do not need artifact migration. Once created with the same remote URL, MegaRepo will cache artifacts on first request. Just recreate the proxy with the same remote URL and clients will transparently populate the cache.

---

## 3. Artifact Migration (Hosted Repositories)

Hosted repositories contain your proprietary artifacts. These must be explicitly migrated.

### 3.1 Export from Nexus

**Option A: Download via Nexus API (recommended for small repos)**

```bash
# List all components in a Nexus hosted repo
NEXUS=https://nexus.example.com
REPO=maven-releases

# Page through all components
CONTINUATION=""
while true; do
  RESPONSE=$(curl -s -u admin:admin123 \
    "$NEXUS/service/rest/v1/components?repository=$REPO&continuationToken=$CONTINUATION")

  echo "$RESPONSE" | jq -r '.items[].assets[].downloadUrl' >> urls.txt

  CONTINUATION=$(echo "$RESPONSE" | jq -r '.continuationToken // empty')
  [ -z "$CONTINUATION" ] && break
done

# Download all artifacts preserving directory structure
while read -r url; do
  path="${url#$NEXUS/repository/$REPO/}"
  mkdir -p "export/$REPO/$(dirname "$path")"
  curl -s -u admin:admin123 -o "export/$REPO/$path" "$url"
done < urls.txt
```

**Option B: Copy the blob store directly (fastest for large repos)**

If you have file system access to the Nexus data directory:

```bash
# Nexus stores blobs in: <nexus-data>/blobs/<blob-store>/content/
# The internal structure uses volume/chapter/hash paths, not artifact paths.
# This approach requires using the Nexus export task or REST API instead.
```

Note: Nexus blob store internals use content-addressable storage. Direct file copy is not practical. Use the API approach or Nexus's built-in export task.

### 3.2 Import into MegaRepo

**Maven artifacts** -- deploy using `mvn deploy:deploy-file` or direct PUT:

```bash
# Upload a single Maven artifact via PUT
curl -u deployer:password -X PUT \
  "http://megarepo:8080/repository/maven-releases/com/example/mylib/1.0/mylib-1.0.jar" \
  --upload-file mylib-1.0.jar

# Upload a POM
curl -u deployer:password -X PUT \
  "http://megarepo:8080/repository/maven-releases/com/example/mylib/1.0/mylib-1.0.pom" \
  --upload-file mylib-1.0.pom
```

**Bulk upload script for Maven:**

```bash
#!/bin/bash
MEGAREPO=http://megarepo:8080
REPO=maven-releases
USER=admin
PASS=admin123

find export/maven-releases -type f | while read -r file; do
  path="${file#export/maven-releases/}"
  echo "Uploading: $path"
  curl -s -u "$USER:$PASS" -X PUT \
    "$MEGAREPO/repository/$REPO/$path" \
    --upload-file "$file"
done
```

**npm artifacts:**

```bash
# Publish a tarball
npm publish my-package-1.0.0.tgz --registry http://megarepo:8080/repository/npm-hosted/
```

**PyPI artifacts:**

```bash
# Upload with twine
twine upload --repository-url http://megarepo:8080/repository/pypi-hosted/ dist/*
```

**Raw artifacts:**

```bash
curl -u admin:admin123 -X PUT \
  "http://megarepo:8080/repository/raw-hosted/path/to/file.zip" \
  --upload-file file.zip
```

---

## 4. User & Role Migration

### 4.1 Export Nexus Users

```bash
curl -s -u admin:admin123 \
  https://nexus.example.com/service/rest/v1/security/users | jq '.[] | {userId, firstName, lastName, emailAddress, roles, status}'
```

### 4.2 Create Users in MegaRepo

```bash
TOKEN=$(curl -s -X POST http://megarepo:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}' | jq -r '.accessToken')

curl -X POST http://megarepo:8080/api/v1/security/users \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{
    "userId": "jdoe",
    "firstName": "Jane",
    "lastName": "Doe",
    "email": "jdoe@example.com",
    "password": "tempPassword123",
    "status": "CHANGE_PASSWORD",
    "roles": ["nx-anonymous"]
  }'
```

Set `status` to `CHANGE_PASSWORD` so users are forced to set a new password on first login.

### 4.3 Migrate Roles

MegaRepo ships with `nx-admin` and `nx-anonymous`. For custom Nexus roles:

```bash
curl -X POST http://megarepo:8080/api/v1/security/roles \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{
    "id": "maven-deployer",
    "name": "Maven Deployer",
    "description": "Deploy to Maven hosted repositories",
    "privileges": [
      "nx-repository-view-*-*-read",
      "nx-repository-view-*-*-browse"
    ],
    "nestedRoles": []
  }'
```

The privilege naming convention matches Nexus: `nx-repository-view-{format}-{repo}-{action}`.

### 4.4 LDAP

If your Nexus uses LDAP, configure MegaRepo's LDAP integration via the API (`/api/v1/security/ldap`) or the admin UI. The LDAP-authenticated users will be mapped to MegaRepo roles.

---

## 5. Client Reconfiguration

### 5.1 Maven (`settings.xml`)

Replace Nexus URLs with MegaRepo URLs. The `/repository/{name}/` path pattern is the same.

```xml
<settings>
  <mirrors>
    <mirror>
      <id>megarepo</id>
      <mirrorOf>*</mirrorOf>
      <!-- Was: https://nexus.example.com/repository/maven-public/ -->
      <url>http://megarepo.example.com:8080/repository/maven-public/</url>
    </mirror>
  </mirrors>

  <servers>
    <server>
      <id>megarepo-releases</id>
      <username>deployer</username>
      <password>s3curePassw0rd</password>
    </server>
    <server>
      <id>megarepo-snapshots</id>
      <username>deployer</username>
      <password>s3curePassw0rd</password>
    </server>
  </servers>
</settings>
```

In your `pom.xml` `<distributionManagement>`:

```xml
<distributionManagement>
  <repository>
    <id>megarepo-releases</id>
    <url>http://megarepo.example.com:8080/repository/maven-releases/</url>
  </repository>
  <snapshotRepository>
    <id>megarepo-snapshots</id>
    <url>http://megarepo.example.com:8080/repository/maven-snapshots/</url>
  </snapshotRepository>
</distributionManagement>
```

### 5.2 Gradle (`build.gradle.kts`)

```kotlin
repositories {
    maven {
        url = uri("http://megarepo.example.com:8080/repository/maven-public/")
        // credentials if anonymous read is disabled
        credentials {
            username = "deployer"
            password = "s3curePassw0rd"
        }
    }
}

publishing {
    repositories {
        maven {
            name = "megarepo"
            url = uri("http://megarepo.example.com:8080/repository/maven-releases/")
            credentials {
                username = "deployer"
                password = "s3curePassw0rd"
            }
        }
    }
}
```

### 5.3 npm (`.npmrc`)

```ini
# Was: //nexus.example.com/repository/npm-public/:_authToken=...
registry=http://megarepo.example.com:8080/repository/npm-public/
//megarepo.example.com:8080/repository/npm-hosted/:_authToken=<your-jwt-token>
```

### 5.4 pip / PyPI (`pip.conf`)

```ini
[global]
index-url = http://megarepo.example.com:8080/repository/pypi-public/simple/
trusted-host = megarepo.example.com
```

For publishing (`.pypirc`):

```ini
[distutils]
index-servers = megarepo

[megarepo]
repository = http://megarepo.example.com:8080/repository/pypi-hosted/
username = deployer
password = s3curePassw0rd
```

### 5.5 Docker

```bash
# If MegaRepo is behind HTTPS (recommended for Docker):
docker login megarepo.example.com:8080
docker tag myimage:latest megarepo.example.com:8080/myimage:latest
docker push megarepo.example.com:8080/myimage:latest

# For pulling through the group:
docker pull megarepo.example.com:8080/library/alpine:latest
```

---

## 6. Verification

After migration, verify each format works end-to-end.

### Maven

```bash
# Resolve dependencies through the new repo
mvn clean install -s settings-megarepo.xml

# Deploy an artifact
mvn deploy -s settings-megarepo.xml
```

### npm

```bash
npm install --registry http://megarepo.example.com:8080/repository/npm-public/ lodash
npm publish --registry http://megarepo.example.com:8080/repository/npm-hosted/
```

### PyPI

```bash
pip install --index-url http://megarepo.example.com:8080/repository/pypi-public/simple/ requests
twine upload --repository megarepo dist/*
```

### Docker

```bash
docker pull megarepo.example.com:8080/library/alpine:latest
docker push megarepo.example.com:8080/myimage:latest
```

### Checklist

- [ ] All hosted artifacts are accessible in MegaRepo
- [ ] Proxy repositories cache artifacts from remotes
- [ ] Group repositories aggregate members correctly
- [ ] CI/CD pipelines build and deploy successfully
- [ ] All developers have updated their local client configs
- [ ] Docker builds resolve base images through MegaRepo

---

## 7. Rollback Plan

Do not decommission Nexus until MegaRepo is fully verified.

### Recommended Approach

1. **Run both in parallel.** Keep Nexus running (read-only if possible) during the migration window.
2. **Use DNS or reverse proxy switching.** Point `repo.example.com` to MegaRepo. If issues arise, switch back to Nexus.
3. **Set a migration window.** Typically 1-2 weeks of parallel operation is sufficient.
4. **Monitor build logs.** Watch CI/CD for failed dependency resolutions or deploy errors.

### Quick Rollback

If MegaRepo has issues:

1. Revert client configs (settings.xml, .npmrc, pip.conf) to Nexus URLs.
2. Or switch DNS/reverse proxy back to Nexus.
3. No data loss: Nexus still has all original artifacts.

### Point of No Return

Nexus can be decommissioned when:

- All builds have run successfully against MegaRepo for at least one full release cycle.
- No client still points to the old Nexus URL (check Nexus access logs).
- Backup of the Nexus data directory is archived, in case you ever need to recover an old artifact.
