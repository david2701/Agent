#!/bin/bash
# Capturas de Kairo en el simulador de iPhone y iPad (CI), con un servidor de prueba local.
# Uso: bash ci/screenshots.sh <ruta a Kairo.app> <carpeta de salida>
set -uo pipefail
APP="$1"
OUT="$2"
HERE="$(cd "$(dirname "$0")" && pwd)"
BUNDLE=$(/usr/libexec/PlistBuddy -c 'Print CFBundleIdentifier' "$APP/Info.plist")
mkdir -p "$OUT"

python3 "$HERE/mock_server.py" 8099 > "$OUT/mock.log" 2>&1 &
MOCK=$!
trap 'kill $MOCK 2>/dev/null' EXIT
sleep 1

# Ejecuta un comando con límite de tiempo (macOS no trae `timeout`)
wd() {
  local t="$1"; shift
  "$@" & local pid=$!
  ( sleep "$t"; kill -9 "$pid" 2>/dev/null && echo "  [tiempo agotado: $*]" ) & local w=$!
  wait "$pid"; local rc=$?
  kill "$w" 2>/dev/null; wait "$w" 2>/dev/null
  return $rc
}
log() { echo "[$(date +%T)] $*"; }

# Elige el primer simulador disponible cuyo nombre empiece por alguno de los prefijos dados
pick() {
  xcrun simctl list devices available -j | python3 -c '
import json, sys
prefixes = sys.argv[1:]
devs = [d for rt, ds in json.load(sys.stdin)["devices"].items() if "iOS" in rt for d in ds if d.get("isAvailable")]
for p in prefixes:
    for d in devs:
        if d["name"].startswith(p):
            print(d["udid"], d["name"], sep="|"); sys.exit(0)
' "$@"
}

shoot() { # udid nombre-archivo sección apariencia
  local udid="$1" name="$2" section="$3" look="$4"
  log "$name"
  wd 30 xcrun simctl ui "$udid" appearance "$look"
  SIMCTL_CHILD_KAIRO_AUTOLOGIN="http://127.0.0.1:8099|demo@kairo.local|demo" \
  SIMCTL_CHILD_KAIRO_SECTION="$section" \
  SIMCTL_CHILD_KAIRO_SCREENSHOT=1 \
  SIMCTL_CHILD_TZ="America/Bogota" \
    wd 60 xcrun simctl launch --terminate-running-process "$udid" "$BUNDLE"
  sleep 8
  wd 30 xcrun simctl io "$udid" screenshot --type=png "$OUT/$name.png" > /dev/null && echo "  $name.png"
}

run_device() { # "udid|nombre" prefijo-archivo secciones...
  local udid="${1%%|*}" label="${1#*|}" prefix="$2"; shift 2
  log "== $label ($udid)"
  wd 120 xcrun simctl boot "$udid" 2>/dev/null
  wd 420 xcrun simctl bootstatus "$udid" -b > /dev/null
  log "arrancado"
  wd 30 xcrun simctl status_bar "$udid" override --time "2026-10-10T09:41:00-05:00" --batteryState charged --batteryLevel 100 --cellularBars 4 --wifiBars 3
  wd 180 xcrun simctl install "$udid" "$APP" || { log "no se pudo instalar"; return 1; }
  log "instalado"
  # Primer arranque con la consola de la app (stdout/stderr), por si se cierra al abrir
  SIMCTL_CHILD_KAIRO_AUTOLOGIN="http://127.0.0.1:8099|demo@kairo.local|demo" SIMCTL_CHILD_KAIRO_SCREENSHOT=1 \
    wd 25 xcrun simctl launch --console-pty --terminate-running-process "$udid" "$BUNDLE" > "$OUT/$prefix-consola.txt" 2>&1
  log "consola: $(wc -l < "$OUT/$prefix-consola.txt") líneas"
  tail -40 "$OUT/$prefix-consola.txt"
  for spec in "$@"; do
    local section="${spec%%:*}" look="${spec#*:}"
    shoot "$udid" "$prefix-$section-$look" "$section" "$look"
  done
  wd 60 xcrun simctl shutdown "$udid"
}

log "simuladores disponibles:"
xcrun simctl list devices available | grep -E "iPhone|iPad" | head -20
PHONE=$(pick "iPhone 17 Pro" "iPhone 17" "iPhone 16 Pro" "iPhone")
PAD=""
[ -n "${KAIRO_SHOTS_IPAD:-}" ] && PAD=$(pick "iPad Pro 13" "iPad Pro" "iPad Air" "iPad")

# El iPad arranca en segundo plano mientras se toman las del iPhone
[ -n "$PAD" ] && { xcrun simctl boot "${PAD%%|*}" 2>/dev/null & }
[ -n "$PHONE" ] && run_device "$PHONE" iphone today:light today:dark tasks:light calendar:dark lists:light notes:light family:dark chat:light settings:dark
[ -n "$PAD" ] && run_device "$PAD" ipad today:light tasks:dark calendar:light notes:light
# Informes de fallo de la app, si los hubo
cp ~/Library/Logs/DiagnosticReports/Kairo*.ips "$OUT/" 2>/dev/null && log "hay informes de fallo"
ls "$OUT"
