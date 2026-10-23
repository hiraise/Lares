#!/usr/bin/env bash
# Drive the app on the emulator by visible text. Cheap to read: prefer `dump` over screenshots.
#
#   tools/dev/ui.sh dump                 # visible texts with tap coordinates and state flags
#   tools/dev/ui.sh tap "Сохранить" [N]  # tap the N-th (0-based) element with this text; waits up to 8 s
#   tools/dev/ui.sh wait "Готово" [S]    # wait until the text is on screen (default 8 s); exit 1 if not
#   tools/dev/ui.sh has "Ошибка"         # exit 0 if the text is on screen now
#   tools/dev/ui.sh field N              # tap the N-th (0-based) text input
#   tools/dev/ui.sh type "olga"          # type ASCII text into the focused field (spaces ok, no Cyrillic)
#   tools/dev/ui.sh clear [N]            # delete N characters in the focused field (default 40)
#   tools/dev/ui.sh xy X Y | back | enter | key CODE | up | down
#   tools/dev/ui.sh shot NAME            # small PNG (long side 1200 px) → build/dev/shots/NAME.png
#   tools/dev/ui.sh crash                # FATAL / ANR lines in logcat since the last launch
#
# Text match: exact first, then case-insensitive substring. Matches `text` and `content-desc`.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$ROOT/build/dev"
SHOTS="$OUT/shots"
ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
XML="$OUT/ui.xml"
mkdir -p "$SHOTS"

grab() {
  for _ in 1 2 3 4 5; do
    "$ADB" exec-out uiautomator dump /dev/tty 2>/dev/null | sed 's/UI hierchary dumped to.*//' >"$XML"
    grep -q '<node' "$XML" && return 0
    sleep 0.5
  done
  echo "ui dump failed (is the app in front? tools/dev/emu.sh launch)" >&2
  return 1
}

# py MODE [ARG] [N]: parse $XML. Modes: list | find TEXT N | field N
py() {
  python3 - "$XML" "$@" <<'EOF'
import re, sys
xml, mode = sys.argv[1], sys.argv[2]
nodes = []
for m in re.finditer(r"<node ([^>]*?)/?>", open(xml, encoding="utf-8").read()):
    a = dict(re.findall(r'([\w-]+)="([^"]*)"', m.group(1)))
    b = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", a.get("bounds", ""))
    if not b:
        continue
    x1, y1, x2, y2 = map(int, b.groups())
    if x2 <= x1 or y2 <= y1:
        continue
    a["xy"] = ((x1 + x2) // 2, (y1 + y2) // 2)
    nodes.append(a)
label = lambda a: a.get("text") or a.get("content-desc") or ""
fields = [a for a in nodes if "EditText" in a.get("class", "")]
if mode == "list":
    for a in nodes:
        t = label(a)
        f = "EditText" in a.get("class", "")
        if not t and not f:
            continue
        flags = [k for k in ("checked", "selected", "focused") if a.get(k) == "true"]
        if a.get("enabled") == "false":
            flags.append("disabled")
        if f:
            flags.append(f"field#{fields.index(a)}")
        print(f"{a['xy'][0]},{a['xy'][1]}  {t[:70]}" + (f"  [{' '.join(flags)}]" if flags else ""))
elif mode == "find":
    t, n = sys.argv[3], int(sys.argv[4])
    hits = [a for a in nodes if t in (a.get("text"), a.get("content-desc"))]
    if not hits:
        hits = [a for a in nodes if t.lower() in label(a).lower()]
    if len(hits) > n:
        print(*hits[n]["xy"])
elif mode == "field":
    n = int(sys.argv[3])
    if len(fields) > n:
        print(*fields[n]["xy"])
EOF
}

find_xy() { # TEXT N SECONDS
  local deadline=$(($(date +%s) + ${3:-8})) xy
  while :; do
    grab && xy="$(py find "$1" "$2")"
    [ -n "$xy" ] && { echo "$xy"; return 0; }
    [ "$(date +%s)" -ge "$deadline" ] && return 1
    sleep 0.7
  done
}

case "${1:-}" in
  dump) grab && py list ;;
  tap)
    xy="$(find_xy "$2" "${3:-0}" 8)" || { echo "not found: $2"; exit 1; }
    "$ADB" shell input tap $xy; sleep 0.8; echo "tapped $2 at $xy"
    ;;
  wait) find_xy "$2" 0 "${3:-8}" >/dev/null && echo "seen: $2" || { echo "not seen: $2"; exit 1; } ;;
  has) grab && [ -n "$(py find "$2" 0)" ] ;;
  field)
    grab && xy="$(py field "${2:-0}")"
    [ -n "${xy:-}" ] || { echo "no field #${2:-0}"; exit 1; }
    "$ADB" shell input tap $xy; sleep 0.4; echo "focused field #${2:-0}"
    ;;
  type)
    if printf '%s' "$2" | LC_ALL=C grep -q '[^ -~]'; then
      echo "type supports ASCII only; use Latin test data"; exit 1
    fi
    "$ADB" shell input text "'${2// /%s}'"; sleep 0.3
    ;;
  clear) "$ADB" shell input keyevent KEYCODE_MOVE_END; for _ in $(seq 1 "${2:-40}"); do echo 67; done | xargs "$ADB" shell input keyevent ;;
  xy) "$ADB" shell input tap "$2" "$3"; sleep 0.8 ;;
  back) "$ADB" shell input keyevent 4; sleep 0.6 ;;
  enter) "$ADB" shell input keyevent 66; sleep 0.6 ;;
  key) "$ADB" shell input keyevent "$2"; sleep 0.5 ;;
  up) "$ADB" shell input swipe 540 1800 540 700 300; sleep 0.6 ;;
  down) "$ADB" shell input swipe 540 700 540 1800 300; sleep 0.6 ;;
  shot)
    f="$SHOTS/${2:-shot}.png"
    "$ADB" exec-out screencap -p >"$f" && sips -Z "${SHOT_SIZE:-1200}" "$f" >/dev/null && echo "$f"
    ;;
  crash)
    c="$({ "$ADB" logcat -d -b crash; "$ADB" logcat -d | grep -A2 'ANR in'; } | head -40)"
    [ -n "$c" ] && echo "$c" || echo "no crash"
    ;;
  *) sed -n '2,16p' "$0"; exit 2 ;;
esac
