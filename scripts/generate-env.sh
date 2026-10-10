#!/usr/bin/env bash
# Genera .env con secretos aleatorios a partir de .env.example. No sobrescribe un .env existente.
set -euo pipefail
cd "$(dirname "$0")/.."
if [ -f .env ]; then
  echo ".env ya existe; no se modifica." >&2
  exit 1
fi
command -v openssl >/dev/null || { echo "Necesitas openssl" >&2; exit 1; }
jwt=$(openssl rand -hex 32)
key=$(openssl rand -base64 32)
db=$(openssl rand -hex 18)
owner=$(openssl rand -base64 18 | tr -d '/+=' | cut -c1-20)
sed -e "s|^KAIRO_JWT_SECRET=.*|KAIRO_JWT_SECRET=$jwt|" \
    -e "s|^KAIRO_ENCRYPTION_KEY=.*|KAIRO_ENCRYPTION_KEY=$key|" \
    -e "s|^KAIRO_DB_PASSWORD=.*|KAIRO_DB_PASSWORD=$db|" \
    -e "s|^KAIRO_OWNER_PASSWORD=.*|KAIRO_OWNER_PASSWORD=$owner|" \
    .env.example > .env
chmod 600 .env
echo "Listo: .env creado."
echo "Contraseña inicial del dashboard: $owner  (cámbiala en Ajustes > Seguridad)"
echo "Completa NAN_API_KEY, KAIRO_OWNER_EMAIL y KAIRO_PUBLIC_URL en .env"
