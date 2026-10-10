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
  xcrun simctl ui "$udid" appearance "$look"
  SIMCTL_CHILD_KAIRO_AUTOLOGIN="http://127.0.0.1:8099|demo@kairo.local|demo" \
  SIMCTL_CHILD_KAIRO_SECTION="$section" \
  SIMCTL_CHILD_KAIRO_SCREENSHOT=1 \
  SIMCTL_CHILD_TZ="America/Bogota" \
    xcrun simctl launch --terminate-running-process "$udid" "$BUNDLE" > /dev/null
  sleep 9
  xcrun simctl io "$udid" screenshot --type=png "$OUT/$name.png" > /dev/null && echo "  $name.png"
}

run_device() { # "udid|nombre" prefijo-archivo secciones...
  local udid="${1%%|*}" label="${1#*|}" prefix="$2"; shift 2
  echo "== $label"
  xcrun simctl boot "$udid" 2>/dev/null
  echo "  arrancando…"; xcrun simctl bootstatus "$udid" -b > /dev/null; echo "  listo ($(date +%T))"
  xcrun simctl status_bar "$udid" override --time "9:41" --batteryState charged --batteryLevel 100 --cellularBars 4 --wifiBars 3 2>/dev/null
  xcrun simctl install "$udid" "$APP" || { echo "no se pudo instalar"; return 1; }
  for spec in "$@"; do
    local section="${spec%%:*}" look="${spec#*:}"
    shoot "$udid" "$prefix-$section-$look" "$section" "$look"
  done
  xcrun simctl shutdown "$udid"
}

PHONE=$(pick "iPhone 17 Pro" "iPhone 17" "iPhone 16 Pro" "iPhone")
PAD=$(pick "iPad Pro 13" "iPad Pro" "iPad Air" "iPad")

[ -n "$PHONE" ] && run_device "$PHONE" iphone today:light today:dark tasks:light calendar:dark lists:light family:dark chat:light notes:dark reminders:light settings:dark
[ -n "$PAD" ] && run_device "$PAD" ipad today:light tasks:dark calendar:light notes:light family:dark
ls "$OUT"
