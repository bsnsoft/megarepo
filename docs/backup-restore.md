# MegaRepo Backup & Restore

This document covers backup, restore, and disaster recovery for MegaRepo.
MegaRepo stores data in two places that both need to be backed up:

| Component       | What it contains                                   | Default location                       |
|-----------------|----------------------------------------------------|----------------------------------------|
| **PostgreSQL**  | Users, repositories, components, assets, audit logs | Docker volume `postgres-data`          |
| **Blob store**  | Actual artifact files (JARs, tarballs, wheels, ...) | Docker volume `megarepo-data` (`/opt/megarepo/data`) |

---

## 1. PostgreSQL Backup

### One-off dump

```bash
# From the host (PostgreSQL exposed on port 5432)
pg_dump -h localhost -p 5432 -U megarepo -d megarepo \
  --no-owner --no-acl --clean --if-exists \
  | gzip > megarepo-db-$(date +%Y%m%d-%H%M%S).sql.gz
```

### From a running Docker Compose stack

```bash
docker compose exec db pg_dump -U megarepo megarepo \
  | gzip > megarepo-db-$(date +%Y%m%d-%H%M%S).sql.gz
```

### Automated cron schedule

Run daily at 02:00, keep 14 days of backups:

```cron
0 2 * * * /opt/megarepo/tools/backup.sh -d /var/backups/megarepo -r 14 >> /var/log/megarepo-backup.log 2>&1
```

### Retention policy

| Environment | Frequency | Retention |
|-------------|-----------|-----------|
| Production  | Daily     | 14 days   |
| Staging     | Weekly    | 7 days    |

---

## 2. Blob Store Backup

### Local tar backup

```bash
tar czf megarepo-blobs-$(date +%Y%m%d-%H%M%S).tar.gz -C /opt/megarepo data/
```

### From Docker volume

```bash
# Find the volume mount path
docker volume inspect app-megarepo_megarepo-data --format '{{ .Mountpoint }}'

# Tar it directly
sudo tar czf megarepo-blobs-$(date +%Y%m%d).tar.gz \
  -C "$(docker volume inspect app-megarepo_megarepo-data --format '{{ .Mountpoint }}')" .
```

### rsync to remote server

```bash
rsync -avz --delete /opt/megarepo/data/ backup-server:/backups/megarepo/blobs/
```

### S3 bucket sync (requires AWS CLI)

```bash
aws s3 sync /opt/megarepo/data/ s3://my-bucket/megarepo/blobs/ \
  --delete --storage-class STANDARD_IA
```

---

## 3. Full Restore Procedure

### Prerequisites

- A clean PostgreSQL 16 instance (or the Docker Compose stack stopped)
- The database dump file (`megarepo-db-*.sql.gz`)
- The blob store archive (`megarepo-blobs-*.tar.gz`)

### Step 1: Stop MegaRepo

```bash
docker compose down
```

### Step 2: Restore the database

**Option A -- Into Docker Compose PostgreSQL:**

```bash
# Start only the database
docker compose up db -d

# Wait for it to be ready
until docker compose exec db pg_isready -U megarepo; do sleep 1; done

# Drop and recreate (clean slate)
docker compose exec db psql -U megarepo -c "DROP SCHEMA public CASCADE; CREATE SCHEMA public;"

# Restore
gunzip -c megarepo-db-20260328-020000.sql.gz \
  | docker compose exec -T db psql -U megarepo -d megarepo
```

**Option B -- Direct PostgreSQL connection:**

```bash
gunzip -c megarepo-db-20260328-020000.sql.gz \
  | psql -h localhost -p 5432 -U megarepo -d megarepo
```

### Step 3: Restore the blob store

```bash
# Clear existing data (if any)
docker volume rm app-megarepo_megarepo-data 2>/dev/null || true
docker volume create app-megarepo_megarepo-data

# Restore into the volume using a temporary container
docker run --rm \
  -v app-megarepo_megarepo-data:/opt/megarepo/data \
  -v "$(pwd)":/backup \
  alpine \
  tar xzf /backup/megarepo-blobs-20260328-020000.tar.gz -C /opt/megarepo/data

# Verify
docker run --rm \
  -v app-megarepo_megarepo-data:/opt/megarepo/data \
  alpine ls -la /opt/megarepo/data/blobs/default/
```

### Step 4: Start MegaRepo

```bash
docker compose up -d
```

### Step 5: Verify

```bash
# Check application health
curl -f http://localhost:8080/actuator/health

# Check a known artifact is accessible
curl -I http://localhost:8080/repository/maven-central/org/apache/commons/commons-lang3/3.14.0/commons-lang3-3.14.0.pom
```

---

## 4. Disaster Recovery

### Scenario: Database is corrupted but blobs are intact

1. Stop MegaRepo: `docker compose down`
2. Restore DB from latest backup (Step 2 above)
3. Start MegaRepo: `docker compose up -d`
4. Hosted repositories will work immediately
5. Proxy caches will refetch on demand -- no manual action needed

### Scenario: Blob store is lost but database is intact

1. Stop MegaRepo: `docker compose down`
2. Restore blobs from latest backup (Step 3 above)
3. Start MegaRepo: `docker compose up -d`
4. If blob backups are stale, some assets in the DB will reference missing blobs.
   These will return 404 until re-uploaded (hosted) or re-fetched (proxy).

### Scenario: Both database and blobs are lost

1. Follow the full restore procedure (Steps 1-5)
2. If no backup exists: recreate with `docker compose up -d` -- MegaRepo will run
   first-time setup automatically. Proxy caches rebuild on demand.
   Hosted artifact data is permanently lost.

### Scenario: Need to migrate to a new server

```bash
# On old server
./tools/backup.sh -d /tmp/megarepo-migration

# Transfer
scp -r /tmp/megarepo-migration/ newserver:/tmp/megarepo-migration/

# On new server -- install Docker, copy docker-compose.yml, then:
# Restore DB
gunzip -c /tmp/megarepo-migration/db/megarepo-db-*.sql.gz \
  | docker compose exec -T db psql -U megarepo -d megarepo

# Restore blobs
docker run --rm \
  -v app-megarepo_megarepo-data:/opt/megarepo/data \
  -v /tmp/megarepo-migration/blobs:/backup \
  alpine sh -c 'tar xzf /backup/megarepo-blobs-*.tar.gz -C /opt/megarepo/data'

docker compose up -d
```

---

## 5. Automated Backup Script

A ready-to-use backup script is provided at `tools/backup.sh`.

### Usage

```bash
# Default: backs up to /var/backups/megarepo, 14-day retention
./tools/backup.sh

# Custom backup directory and 7-day retention
./tools/backup.sh -d /mnt/nfs/backups/megarepo -r 7

# Override PostgreSQL connection
PGHOST=db.internal PGPASSWORD=secret ./tools/backup.sh
```

### What it does

1. Dumps PostgreSQL with `pg_dump` (gzipped)
2. Creates a tar.gz of the blob store directory
3. Deletes backups older than the retention period
4. Logs progress to stdout (redirect to file for cron)

### Directory structure after backup

```
/var/backups/megarepo/
  db/
    megarepo-db-20260328-020000.sql.gz
    megarepo-db-20260327-020000.sql.gz
  blobs/
    megarepo-blobs-20260328-020000.tar.gz
    megarepo-blobs-20260327-020000.tar.gz
```

---

## 6. Docker-Specific Backup

When running MegaRepo via Docker Compose, data lives in Docker volumes.
You do not need to stop the stack for backups, but be aware:

- **Database**: `pg_dump` is consistent (uses MVCC snapshot), safe while running.
- **Blob store**: `tar` of a live directory may include partially-written files.
  For maximum consistency, briefly stop MegaRepo (not the DB) during blob backup.

### Quick one-liner: backup from running stack

```bash
# Database
docker compose exec db pg_dump -U megarepo megarepo | gzip > db-backup.sql.gz

# Blobs (using a sidecar container to access the volume)
docker run --rm \
  -v app-megarepo_megarepo-data:/data:ro \
  -v "$(pwd)":/backup \
  alpine tar czf /backup/blobs-backup.tar.gz -C /data .
```

### Consistent blob backup (brief downtime)

```bash
# Stop only the app, keep DB running
docker compose stop megarepo

# Backup blobs
docker run --rm \
  -v app-megarepo_megarepo-data:/data:ro \
  -v "$(pwd)":/backup \
  alpine tar czf /backup/blobs-backup.tar.gz -C /data .

# Restart
docker compose start megarepo
```
