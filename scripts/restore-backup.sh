#!/usr/bin/env bash
# Restores a Kairo backup into the docker-compose database.
# Usage: ./scripts/restore-backup.sh backups/kairo-20261010-0330.dump
# It replaces ALL the current data with the backup's. It needs the same .env it was made with
# (KAIRO_ENCRYPTION_KEY decrypts the Google tokens, the push keys and the MCP headers).
# The backups folder also holds LAST_BACKUP_OK, a marker with the time of the last successful backup.
set -euo pipefail
cd "$(dirname "$0")/.."
file="${1:-}"
[ -f "$file" ] || { echo "Usage: $0 <.dump file>" >&2; exit 1; }
read -r -p "This replaces Kairo's current data with the data in $(basename "$file"). Continue? (type yes) " ok
[ "$ok" = "yes" ] || [ "$ok" = "y" ] || [ "$ok" = "sí" ] || [ "$ok" = "si" ] || { echo "Cancelled."; exit 1; }
docker compose stop kairo
docker compose exec -T db pg_restore -U kairo -d kairo --clean --if-exists --no-owner < "$file"
docker compose start kairo
echo "Done: data restored from $(basename "$file")."
