# Maven Test Project

Tests MegaRepo as a Maven repository manager (proxy + hosted).

## What this tests

- **Proxy**: Dependencies (commons-lang3, guava) are fetched through MegaRepo's
  `maven-central-proxy` which proxies Maven Central.
- **Hosted**: `mvn deploy` publishes the built artifact to `maven-releases`.
- **Mirror**: `settings.xml` configures MegaRepo as the sole Maven mirror,
  so all dependency resolution goes through MegaRepo.

## Prerequisites

- MegaRepo running at http://localhost:8080
- Repositories created via `../setup.sh`
- Maven 3.9+ installed

## Usage

```bash
# 1. Start MegaRepo
docker compose up  # from project root

# 2. Create repositories
cd test-projects && bash setup.sh

# 3. Build (fetches dependencies through proxy)
cd maven
mvn clean package -s settings.xml -B

# 4. Deploy artifact to hosted repo
mvn deploy -s settings.xml -B

# 5. Verify the deployed artifact is accessible
curl -u admin:admin123 http://localhost:8080/repository/maven-releases/com/example/megarepo-test/1.0.0/megarepo-test-1.0.0.jar -o /dev/null -w "%{http_code}"
```

## Troubleshooting

- If `mvn clean package` fails with connection errors, ensure MegaRepo is running
  and `maven-central-proxy` was created successfully.
- If `mvn deploy` returns 401, check credentials in `settings.xml`.
- If `mvn deploy` returns 400 for RELEASE policy, the artifact may already exist
  (ALLOW_ONCE policy). Bump the version or delete the existing artifact first.
