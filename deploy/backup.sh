#!/bin/sh
# Kairo backups: a daily pg_dump to /backups and cleanup of the old ones.
# It is run by the "backup" service of docker-compose.yml. Variables:
#   BACKUP_TIME       local time of the backup (HH:MM, default 03:30)
#   BACKUP_KEEP_DAYS  days to keep them (default 14)
# Each successful backup writes the time to /backups/LAST_BACKUP_OK (a marker for monitoring).
# Manual use:  docker compose exec backup /backup.sh now
set -eu
DIR=/backups
TIME="${BACKUP_TIME:-03:30}"
KEEP="${BACKUP_KEEP_DAYS:-14}"
umask 077
mkdir -p "$DIR"

dump() {
  stamp=$(date +%Y%m%d-%H%M)
  tmp="$DIR/.kairo-$stamp.dump.part"
  # Custom format (compressed); restored with pg_restore (see scripts/restore-backup.sh)
  if pg_dump -Fc -Z 6 -f "$tmp"; then
    mv "$tmp" "$DIR/kairo-$stamp.dump"
    date -Iseconds > "$DIR/LAST_BACKUP_OK"
    echo "[backup] $(date -Iseconds) backup created: kairo-$stamp.dump ($(du -h "$DIR/kairo-$stamp.dump" | cut -f1))"
  else
    rm -f "$tmp"
    echo "[backup] $(date -Iseconds) ERROR: pg_dump failed" >&2
    return 1
  fi
  find "$DIR" -maxdepth 1 -name 'kairo-*.dump' -mtime +"$KEEP" -print -delete | sed 's/^/[backup] deleted: /'
}

if [ "${1:-}" = "now" ]; then
  dump
  exit $?
fi

# On start, if there is no backup yet, make one.
ls "$DIR"/kairo-*.dump >/dev/null 2>&1 || dump || true

while true; do
  now=$(date +%s)
  next=$(date -d "today $TIME" +%s)
  [ "$next" -le "$now" ] && next=$(date -d "tomorrow $TIME" +%s)
  echo "[backup] next backup: $(date -d "@$next" '+%Y-%m-%d %H:%M')"
  sleep $((next - now))
  dump || true
done
