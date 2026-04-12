# MegaRepo Deployment

## Docker Images

MegaRepo is published as a Docker image to two registries:

- **Docker Hub** (public): `bsnsoft/megarepo` -- no authentication required
- **GitLab Registry** (internal): `bsnsoft/megarepo` -- requires GitLab auth

### Quick Start

```bash
docker pull bsnsoft/megarepo:latest
```

Or in a `docker-compose.yml`:

```yaml
services:
  megarepo:
    image: bsnsoft/megarepo:latest
    ports:
      - "8080:8080"
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://db:5432/megarepo
      SPRING_DATASOURCE_USERNAME: megarepo
      SPRING_DATASOURCE_PASSWORD: megarepo
    depends_on:
      - db
  db:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: megarepo
      POSTGRES_USER: megarepo
      POSTGRES_PASSWORD: megarepo
    volumes:
      - pgdata:/var/lib/postgresql/data

volumes:
  pgdata:
```

## Docker Hub Publishing

The CI pipeline pushes to Docker Hub automatically when `DOCKERHUB_USERNAME` is configured.

### Required GitLab CI/CD Variables

Set these in GitLab under **Settings > CI/CD > Variables**:

- `DOCKERHUB_USERNAME`: Docker Hub username (e.g., `bsnsoft`)
- `DOCKERHUB_TOKEN`: Docker Hub access token (create at https://hub.docker.com/settings/security)

When these variables are not set, the pipeline skips the Docker Hub push and only publishes to the GitLab registry.
