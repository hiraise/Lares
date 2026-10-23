#!/usr/bin/env bash
# Emulator lifecycle for manual checks of the Android app.
#
#   tools/dev/emu.sh start        # boot the headless `lares` AVD if needed, wait for boot, adb reverse
#   tools/dev/emu.sh start --window   # same with a visible window (for the customer to look at)
#   tools/dev/emu.sh install      # build + install the debug APK, then launch
#   tools/dev/emu.sh launch       # (re)start the app (force-stop first)
#   tools/dev/emu.sh fresh        # clear app data (back to the Server screen), launch
#   tools/dev/emu.sh link URL     # open a deep link (invite / login payload from tools/dev/seed.py)
#   tools/dev/emu.sh status | stop
#
# In the app the server address is http://localhost:8080 (adb reverse; 10.0.2.2 does not work on this Mac).
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$ROOT/build/dev"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ADB="$ANDROID_HOME/platform-tools/adb"
PKG=app.lares.android.debug
ACT=app.lares.android.MainActivity
PORT="${LARES_PORT:-8080}"
mkdir -p "$OUT"

booted() { [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; }

start() {
  if ! "$ADB" devices | grep -q 'emulator-.*device$'; then
    local view=(-no-window -gpu swiftshader_indirect)
    [ "${1:-}" = "--window" ] && view=(-gpu auto)
    nohup "$ANDROID_HOME/emulator/emulator" -avd lares "${view[@]}" -no-audio -no-boot-anim \
      >"$OUT/emulator.log" 2>&1 &
    echo "booting emulator (log build/dev/emulator.log)"
  fi
  "$ADB" wait-for-device
  for _ in $(seq 1 90); do booted && break; sleep 2; done
  booted || { echo "emulator did not boot"; tail -5 "$OUT/emulator.log"; exit 1; }
  "$ADB" reverse "tcp:$PORT" "tcp:$PORT" >/dev/null
  echo "emulator ready ($("$ADB" shell wm size | tr -d '\r' | sed 's/Physical size: //')), reverse :$PORT"
}

launch() {
  "$ADB" shell am force-stop "$PKG"
  "$ADB" logcat -c
  "$ADB" shell am start -W -n "$PKG/$ACT" >/dev/null
  echo "app launched"
}

case "${1:-status}" in
  start) start "${2:-}" ;;
  install)
    (cd "$ROOT" && ./gradlew :client:android:installDebug -q --console=plain 2>&1 | grep -E '^e: |FAILED|wrong' | head -20)
    launch
    ;;
  launch) launch ;;
  fresh) "$ADB" shell pm clear "$PKG" >/dev/null && launch ;;
  link)
    "$ADB" shell am start -a android.intent.action.VIEW -d "'$2'" >/dev/null 2>&1 && echo "opened $2"
    ;;
  stop) "$ADB" emu kill >/dev/null 2>&1; echo "emulator stopped" ;;
  status)
    if booted; then echo "emulator booted"; "$ADB" reverse --list; else echo "emulator not running"; fi
    ;;
  *) sed -n '2,12p' "$0"; exit 2 ;;
esac
