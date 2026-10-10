#!/usr/bin/env bash
# Generates .env with random secrets from .env.example. It does not overwrite an existing .env.
set -euo pipefail
cd "$(dirname "$0")/.."
if [ -f .env ]; then
  echo ".env already exists; it is not modified." >&2
  exit 1
fi
command -v openssl >/dev/null || { echo "You need openssl" >&2; exit 1; }
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
echo "Done: .env created."
echo "Initial dashboard password: $owner  (change it in Settings > Security)"
echo "Fill in NAN_API_KEY, KAIRO_OWNER_EMAIL and KAIRO_PUBLIC_URL in .env"
