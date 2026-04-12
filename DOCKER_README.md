# MegaRepo

**The affordable artifact repository manager.** Maven, PyPI, npm, Raw, Docker — all in one.

[![License](https://img.shields.io/badge/license-BSL--1.1-blue)](https://bsnsoft.de/megarepo)

## Quick Start

```yaml
# docker-compose.yml
services:
  megarepo:
    image: bsnsoft/megarepo:latest
    ports:
      - "8080:8080"
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://db:5432/megarepo?stringtype=unspecified
      SPRING_DATASOURCE_USERNAME: megarepo
      SPRING_DATASOURCE_PASSWORD: changeme
      MEGAREPO_SECURITY_JWT_SECRET: changeme-jwt-secret
    depends_on:
      db:
        condition: service_healthy
  db:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: megarepo
      POSTGRES_USER: megarepo
      POSTGRES_PASSWORD: changeme
    volumes:
      - pgdata:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U megarepo"]
      interval: 5s
      timeout: 5s
      retries: 5
volumes:
  pgdata:
```

```bash
docker compose up -d
# Open http://localhost:8080 — login: admin / admin123
```

## Features

- **5 formats**: Maven, PyPI, npm, Raw, Docker
- **3 repo types**: Hosted, Proxy (with caching), Group
- **15 default repos** preconfigured on first startup
- **RBAC security** with JWT, LDAP support
- **Docker Registry V2** API compatible
- **Cleanup policies** with presets
- **Prometheus metrics** at `/actuator/prometheus`
- **Swagger API** at `/swagger-ui.html`
- **Zero CVEs** (Trivy verified)

## Pricing

**Free during beta** for everyone. After 1.0: 600 EUR/year per company. Free forever for <50 employees, universities, testing.

## Links

- **Website**: [bsnsoft.de/megarepo](https://bsnsoft.de/megarepo)
- **Docs**: [bsnsoft.de/megarepo/docs](https://bsnsoft.de/megarepo/docs.html)
- **Company**: [bsnsoft.de](https://www.bsnsoft.de)
- **Support**: ticket@bsnsoft.de
