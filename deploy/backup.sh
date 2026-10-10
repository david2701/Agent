#!/bin/sh
# Copias de seguridad de Kairo: un pg_dump diario a /backups y limpieza de las viejas.
# Lo ejecuta el servicio "backup" de docker-compose.yml. Variables:
#   BACKUP_TIME       hora local de la copia (HH:MM, por defecto 03:30)
#   BACKUP_KEEP_DAYS  días que se guardan (por defecto 14)
# Uso manual:  docker compose exec backup /backup.sh now
set -eu
DIR=/backups
TIME="${BACKUP_TIME:-03:30}"
KEEP="${BACKUP_KEEP_DAYS:-14}"
umask 077
mkdir -p "$DIR"

dump() {
  stamp=$(date +%Y%m%d-%H%M)
  tmp="$DIR/.kairo-$stamp.dump.part"
  # Formato custom (comprimido); se restaura con pg_restore (ver scripts/restore-backup.sh)
  if pg_dump -Fc -Z 6 -f "$tmp"; then
    mv "$tmp" "$DIR/kairo-$stamp.dump"
    date -Iseconds > "$DIR/ULTIMA_COPIA_OK"
    echo "[backup] $(date -Iseconds) copia creada: kairo-$stamp.dump ($(du -h "$DIR/kairo-$stamp.dump" | cut -f1))"
  else
    rm -f "$tmp"
    echo "[backup] $(date -Iseconds) ERROR: falló pg_dump" >&2
    return 1
  fi
  find "$DIR" -maxdepth 1 -name 'kairo-*.dump' -mtime +"$KEEP" -print -delete | sed 's/^/[backup] borrada: /'
}

if [ "${1:-}" = "now" ]; then
  dump
  exit $?
fi

# Al arrancar, si no hay ninguna copia todavía, hace una.
ls "$DIR"/kairo-*.dump >/dev/null 2>&1 || dump || true

while true; do
  now=$(date +%s)
  next=$(date -d "today $TIME" +%s)
  [ "$next" -le "$now" ] && next=$(date -d "tomorrow $TIME" +%s)
  echo "[backup] próxima copia: $(date -d "@$next" '+%Y-%m-%d %H:%M')"
  sleep $((next - now))
  dump || true
done
