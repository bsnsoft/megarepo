#!/usr/bin/env bash
#
# backup.sh - Back up MegaRepo PostgreSQL database and blob store.
#
# Usage:
#   ./tools/backup.sh                          # Uses defaults
#   ./tools/backup.sh -d /backups -r 7         # Custom dir, 7-day retention
#   PGHOST=db.prod PGUSER=megarepo ./tools.backup.sh  # Custom PG connection
#
# Environment variables (all optional, sensible defaults):
#   PGHOST          PostgreSQL host           (default: localhost)
#   PGPORT          PostgreSQL port           (default: 5432)
#   PGUSER          PostgreSQL user           (default: megarepo)
#   PGDATABASE      PostgreSQL database       (default: megarepo)
#   PGPASSWORD      PostgreSQL password       (reads from env or .pgpass)
#   BACKUP_DIR      Where to store backups    (default: /var/backups/megarepo)
#   BLOB_DIR        Blob store data directory (default: /opt/megarepo/data)
#   RETENTION_DAYS  Days to keep old backups  (default: 14)
#   COMPOSE_PROJECT Docker compose project    (default: auto-detect)

set -euo pipefail

# --- Configuration -----------------------------------------------------------

BACKUP_DIR="${BACKUP_DIR:-/var/backups/megarepo}"
BLOB_DIR="${BLOB_DIR:-/opt/megarepo/data}"
RETENTION_DAYS="${RETENTION_DAYS:-14}"

PGHOST="${PGHOST:-localhost}"
PGPORT="${PGPORT:-5432}"
PGUSER="${PGUSER:-megarepo}"
PGDATABASE="${PGDATABASE:-megarepo}"

TIMESTAMP=$(date +%Y%m%d-%H%M%S)
DB_BACKUP_FILE="${BACKUP_DIR}/db/megarepo-db-${TIMESTAMP}.sql.gz"
BLOB_BACKUP_FILE="${BACKUP_DIR}/blobs/megarepo-blobs-${TIMESTAMP}.tar.gz"

# --- Parse args --------------------------------------------------------------

while getopts "d:r:h" opt; do
  case "$opt" in
    d) BACKUP_DIR="$OPTARG"
       DB_BACKUP_FILE="${BACKUP_DIR}/db/megarepo-db-${TIMESTAMP}.sql.gz"
       BLOB_BACKUP_FILE="${BACKUP_DIR}/blobs/megarepo-blobs-${TIMESTAMP}.tar.gz"
       ;;
    r) RETENTION_DAYS="$OPTARG" ;;
    h)
      echo "Usage: $0 [-d backup_dir] [-r retention_days]"
      echo "  -d  Backup directory (default: /var/backups/megarepo)"
      echo "  -r  Retention in days (default: 14)"
      exit 0
      ;;
    *) exit 1 ;;
  esac
done

# --- Helpers -----------------------------------------------------------------

log() { echo "[$(date '+%H:%M:%S')] $*"; }
fail() { log "ERROR: $*" >&2; exit 1; }

check_command() {
  command -v "$1" >/dev/null 2>&1 || fail "'$1' is required but not installed."
}

# --- Preflight ---------------------------------------------------------------

check_command pg_dump
check_command tar
check_command gzip

mkdir -p "${BACKUP_DIR}/db" "${BACKUP_DIR}/blobs"

# --- Database backup ---------------------------------------------------------

log "Backing up PostgreSQL database '${PGDATABASE}' on ${PGHOST}:${PGPORT}..."

pg_dump \
  -h "$PGHOST" \
  -p "$PGPORT" \
  -U "$PGUSER" \
  -d "$PGDATABASE" \
  --no-owner \
  --no-acl \
  --clean \
  --if-exists \
  --format=plain \
  | gzip > "$DB_BACKUP_FILE"

DB_SIZE=$(du -h "$DB_BACKUP_FILE" | cut -f1)
log "Database backup complete: ${DB_BACKUP_FILE} (${DB_SIZE})"

# --- Blob store backup -------------------------------------------------------

if [[ -d "$BLOB_DIR" ]]; then
  log "Backing up blob store at ${BLOB_DIR}..."
  tar czf "$BLOB_BACKUP_FILE" -C "$(dirname "$BLOB_DIR")" "$(basename "$BLOB_DIR")"
  BLOB_SIZE=$(du -h "$BLOB_BACKUP_FILE" | cut -f1)
  log "Blob store backup complete: ${BLOB_BACKUP_FILE} (${BLOB_SIZE})"
else
  log "WARNING: Blob directory '${BLOB_DIR}' not found, skipping blob backup."
fi

# --- Cleanup old backups -----------------------------------------------------

log "Removing backups older than ${RETENTION_DAYS} days..."
find "${BACKUP_DIR}/db"    -name "megarepo-db-*.sql.gz"    -mtime +"$RETENTION_DAYS" -delete 2>/dev/null || true
find "${BACKUP_DIR}/blobs" -name "megarepo-blobs-*.tar.gz" -mtime +"$RETENTION_DAYS" -delete 2>/dev/null || true

REMAINING_DB=$(find "${BACKUP_DIR}/db" -name "megarepo-db-*.sql.gz" | wc -l | tr -d ' ')
REMAINING_BLOBS=$(find "${BACKUP_DIR}/blobs" -name "megarepo-blobs-*.tar.gz" | wc -l | tr -d ' ')
log "Retained backups: ${REMAINING_DB} database, ${REMAINING_BLOBS} blob store"

# --- Summary -----------------------------------------------------------------

log "Backup complete."
log "  Database:   ${DB_BACKUP_FILE}"
if [[ -d "$BLOB_DIR" ]]; then
  log "  Blob store: ${BLOB_BACKUP_FILE}"
fi
log "  Retention:  ${RETENTION_DAYS} days"
