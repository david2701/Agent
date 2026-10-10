#!/usr/bin/env bash
# Restaura una copia de seguridad de Kairo en la base de datos del docker-compose.
# Uso: ./scripts/restore-backup.sh backups/kairo-20261010-0330.dump
# Reemplaza TODOS los datos actuales por los de la copia. Necesita el mismo .env con el que se hizo
# (KAIRO_ENCRYPTION_KEY descifra los tokens de Google, las claves de push y las cabeceras MCP).
set -euo pipefail
cd "$(dirname "$0")/.."
file="${1:-}"
[ -f "$file" ] || { echo "Uso: $0 <archivo .dump>" >&2; exit 1; }
read -r -p "Esto reemplaza los datos actuales de Kairo por los de $(basename "$file"). ¿Seguir? (escribe sí) " ok
[ "$ok" = "sí" ] || [ "$ok" = "si" ] || { echo "Cancelado."; exit 1; }
docker compose stop kairo
docker compose exec -T db pg_restore -U kairo -d kairo --clean --if-exists --no-owner < "$file"
docker compose start kairo
echo "Listo: datos restaurados desde $(basename "$file")."
