# MegaRepo Upgrade Guide

## How Upgrades Work

MegaRepo uses [Flyway](https://flywaydb.org/) for database schema management.
When the application starts, Flyway automatically compares the current database
state against the migrations bundled in the Docker image and applies any pending
ones. This means:

**To upgrade: pull the new image, restart, done.** Flyway handles the rest.

There is no manual migration step, no separate migration tool, and no downtime
window beyond the restart itself (typically a few seconds).

## Upgrade Procedure

### 1. Back Up the Database

Always take a backup before upgrading. This is your safety net.

```bash
# Using docker compose (recommended)
docker compose exec db pg_dump -U megarepo megarepo > megarepo-backup-$(date +%Y%m%d-%H%M%S).sql

# Or with a standalone PostgreSQL client
pg_dump -h localhost -U megarepo megarepo > megarepo-backup-$(date +%Y%m%d-%H%M%S).sql
```

Also back up the blob store volume if you want a complete snapshot:

```bash
# Find the volume mount path (default: /opt/megarepo/data)
docker compose exec megarepo ls -la /opt/megarepo/data

# Copy it out (while the container is stopped to avoid inconsistency)
docker compose stop megarepo
docker cp $(docker compose ps -q megarepo):/opt/megarepo/data ./megarepo-data-backup
```

### 2. Pull the New Image

```bash
# Docker Hub (public)
docker pull bsnsoft/megarepo:latest

# Or pin a specific version
docker pull bsnsoft/megarepo:0.2.42
```

### 3. Restart

```bash
docker compose up -d megarepo
```

Flyway runs on startup. Check the logs to confirm migrations applied:

```bash
docker compose logs megarepo | grep -i flyway
```

You should see output like:

```
Successfully applied N migration(s) to schema "public"
```

### 4. Verify

```bash
# Check the application is running
curl -s http://localhost:8080/api/v1/status | jq .

# Check Flyway migration status via the database
docker compose exec db psql -U megarepo -c "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;"
```

## Rollback Procedure

Flyway migrations are forward-only. If an upgrade causes problems:

1. **Stop the new version:**
   ```bash
   docker compose stop megarepo
   ```

2. **Restore the database from backup:**
   ```bash
   docker compose exec -T db psql -U megarepo -c "DROP SCHEMA public CASCADE; CREATE SCHEMA public;"
   docker compose exec -T db psql -U megarepo < megarepo-backup-YYYYMMDD-HHMMSS.sql
   ```

3. **Run the old image:**
   ```bash
   # Pin the old version explicitly
   docker compose up -d megarepo
   ```

Do not run an older version of MegaRepo against a database that has been
migrated to a newer schema -- the application will either fail or produce
undefined behavior. Always restore the backup first.

## Migration History

| Migration | Description | Introduced |
|-----------|-------------|------------|
| V1 | Initial schema: repositories, components, assets, users, roles, privileges, blob stores, cleanup policies, scheduled tasks, routing rules | 0.1 |
| V2 | Seed default data: admin user, default blob store, built-in roles and privileges, scheduled tasks | 0.1 |
| V3 | Fix admin password hash (BCrypt re-encoding) | 0.1 |
| V4 | Audit log table with indexes on timestamp, repository, user, action | 0.1 |
| V5 | LDAP server configuration table | 0.2 |
| V6 | SSL/TLS certificate store | 0.2 |

All migrations are additive (new tables, new columns, data fixes). No migration
drops tables or removes columns.

## Version Compatibility

MegaRepo supports upgrading from any previous version to any newer version.
Flyway applies all intermediate migrations in order, so you can skip versions
safely (e.g., upgrade directly from 0.1 to 0.2 without intermediate stops).

**Supported upgrade paths:**

- Any 0.1.x to any 0.2.x -- automatic
- Any 0.2.x to any later 0.2.x -- automatic
- Future: any version to any later version (Flyway guarantees sequential migration)

**Not supported:**

- Downgrading (run old image against newer schema) -- restore from backup instead
- Skipping major versions once 1.0+ ships -- check release notes

## Breaking Changes

No breaking changes have been introduced so far. All migrations are additive.

If a future release introduces breaking changes, they will be documented here
and flagged in the release notes.

## Tips

- **Always test upgrades in staging first.** Deploy the new image against a copy
  of your production database before upgrading production.
- **Monitor disk space.** The `flyway_schema_history` table is tiny, but new
  features may add tables that grow over time.
- **Check release notes.** Even if Flyway handles schema changes automatically,
  there may be configuration changes (new environment variables, changed defaults).
- **Keep backups.** Automated daily `pg_dump` is cheap insurance.
