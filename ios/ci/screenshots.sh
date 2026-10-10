#!/bin/bash
# Kairo screenshots on the iPhone and iPad simulators (CI), against a local mock server.
# Usage: bash ci/screenshots.sh <path to Kairo.app> <output folder>
# Environment:
#   KAIRO_LANG=en|es    language of the app UI (default: en)
#   KAIRO_SHOTS_IPAD=1  also capture the iPad
set -uo pipefail
APP="$1"
OUT="$2"
HERE="$(cd "$(dirname "$0")" && pwd)"
BUNDLE=$(/usr/libexec/PlistBuddy -c 'Print CFBundleIdentifier' "$APP/Info.plist")
mkdir -p "$OUT"

# App language and locale. English files have no language tag; Spanish ones get "-es" after the device name.
LANG_CODE="${KAIRO_LANG:-en}"
case "$LANG_CODE" in
  es) LOCALE_ARGS=(-AppleLanguages "(es)" -AppleLocale es_ES); TAG="-es" ;;
  *)  LOCALE_ARGS=(-AppleLanguages "(en)" -AppleLocale en_US); TAG="" ;;
esac

python3 "$HERE/mock_server.py" 8099 > "$OUT/mock.log" 2>&1 &
MOCK=$!
trap 'kill $MOCK 2>/dev/null' EXIT
sleep 1

# Runs a command with a time limit (macOS does not ship `timeout`)
wd() {
  local t="$1"; shift
  "$@" & local pid=$!
  ( sleep "$t"; kill -9 "$pid" 2>/dev/null && echo "  [timed out: $*]" ) & local w=$!
  wait "$pid"; local rc=$?
  kill "$w" 2>/dev/null; wait "$w" 2>/dev/null
  return $rc
}
log() { echo "[$(date +%T)] $*"; }

# Picks the first available simulator whose name starts with one of the given prefixes
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

shoot() { # udid file-name section appearance
  local udid="$1" name="$2" section="$3" look="$4"
  log "$name"
  wd 30 xcrun simctl ui "$udid" appearance "$look"
  SIMCTL_CHILD_KAIRO_AUTOLOGIN="http://127.0.0.1:8099|demo@example.com|demo" \
  SIMCTL_CHILD_KAIRO_SECTION="$section" \
  SIMCTL_CHILD_KAIRO_SCREENSHOT=1 \
  SIMCTL_CHILD_TZ="America/New_York" \
    wd 60 xcrun simctl launch --terminate-running-process "$udid" "$BUNDLE" "${LOCALE_ARGS[@]}"
  sleep 8
  wd 30 xcrun simctl io "$udid" screenshot --type=png "$OUT/$name.png" > /dev/null && echo "  $name.png"
}

run_device() { # "udid|name" file-prefix sections...
  local udid="${1%%|*}" label="${1#*|}" prefix="$2"; shift 2
  log "== $label ($udid)"
  wd 120 xcrun simctl boot "$udid" 2>/dev/null
  wd 420 xcrun simctl bootstatus "$udid" -b > /dev/null
  log "booted"
  wd 30 xcrun simctl status_bar "$udid" override --time "2026-10-10T09:41:00-04:00" --batteryState charged --batteryLevel 100 --cellularBars 4 --wifiBars 3
  wd 180 xcrun simctl install "$udid" "$APP" || { log "could not install"; return 1; }
  log "installed"
  # First launch with the app console (stdout/stderr), in case it closes on open
  SIMCTL_CHILD_KAIRO_AUTOLOGIN="http://127.0.0.1:8099|demo@example.com|demo" SIMCTL_CHILD_KAIRO_SCREENSHOT=1 \
    wd 25 xcrun simctl launch --console-pty --terminate-running-process "$udid" "$BUNDLE" "${LOCALE_ARGS[@]}" > "$OUT/$prefix-console.txt" 2>&1
  log "console: $(wc -l < "$OUT/$prefix-console.txt") lines"
  tail -40 "$OUT/$prefix-console.txt"
  for spec in "$@"; do
    local section="${spec%%:*}" look="${spec#*:}"
    shoot "$udid" "$prefix-$section-$look" "$section" "$look"
  done
  wd 60 xcrun simctl shutdown "$udid"
}

log "language: $LANG_CODE"
log "available simulators:"
xcrun simctl list devices available | grep -E "iPhone|iPad" | head -20
PHONE=$(pick "iPhone 17 Pro" "iPhone 17" "iPhone 16 Pro" "iPhone")
PAD=""
[ -n "${KAIRO_SHOTS_IPAD:-}" ] && PAD=$(pick "iPad Pro 13" "iPad Pro" "iPad Air" "iPad")

# The iPad boots in the background while the iPhone shots are taken
[ -n "$PAD" ] && { xcrun simctl boot "${PAD%%|*}" 2>/dev/null & }
[ -n "$PHONE" ] && run_device "$PHONE" "iphone$TAG" today:light today:dark tasks:light calendar:dark lists:light notes:light family:dark chat:light settings:dark
[ -n "$PAD" ] && run_device "$PAD" "ipad$TAG" today:light tasks:dark calendar:light notes:light
# App crash reports, if there were any
cp ~/Library/Logs/DiagnosticReports/Kairo*.ips "$OUT/" 2>/dev/null && log "there are crash reports"
ls "$OUT"
