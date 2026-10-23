#!/usr/bin/env bash
# Format, build and test with short output: only errors, failed tests and test totals.
# The full Gradle log goes to build/dev/verify.log.
#
#   tools/dev/verify.sh                      # full gate: spotlessApply check :client:android:assembleDebug
#   tools/dev/verify.sh :server:test         # spotlessApply + the given tasks
#   tools/dev/verify.sh :client:shared:jvmTest --tests '*FamilyViewModelTest*'
#   tools/dev/verify.sh --no-format :server:compileKotlin
#
# Exit code is Gradle's. Needs Docker running for server and integration tests.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$ROOT/build/dev"
LOG="$OUT/verify.log"
mkdir -p "$OUT"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"

format=1
if [ "${1:-}" = "--no-format" ]; then format=0; shift; fi
if [ $# -eq 0 ]; then set -- check :client:android:assembleDebug; fi
tasks=("$@")
[ $format -eq 1 ] && tasks=(spotlessApply "${tasks[@]}")

marker="$OUT/.verify-start"
touch "$marker"
start=$(date +%s)
echo "gradle ${tasks[*]}"
(cd "$ROOT" && ./gradlew "${tasks[@]}" --console=plain >"$LOG" 2>&1)
code=$?
secs=$(($(date +%s) - start))

# Compile errors, lint violations, failed tests, Gradle failure reasons (deduplicated, capped).
grep -E '^e: |^\S+\.kt:[0-9]+:[0-9]+ |Lint error|FAILED$|> .*FAILED|^\* What went wrong|^> |error:|Exception:' "$LOG" \
  | grep -vE '^> Task |^> Configure|^> Transform|^> Run with|^> Get more help' \
  | sed "s|file://$ROOT/||; s|$ROOT/||" | awk '!seen[$0]++' | head -60

# Test totals and failure messages from JUnit XML written during this run.
python3 - "$ROOT" "$marker" "${tasks[@]}" <<'EOF'
import glob, os, re, sys
import xml.etree.ElementTree as ET
root, marker = sys.argv[1], os.path.getmtime(sys.argv[2])
totals = {}
fails = []
for f in glob.glob(os.path.join(root, "**/build/test-results/**/*.xml"), recursive=True):
    if os.path.getmtime(f) < marker:
        continue
    module = os.path.relpath(f, root).split("/build/")[0]
    try:
        suite = ET.parse(f).getroot()
    except ET.ParseError:
        continue
    t = totals.setdefault(module, [0, 0, 0])
    t[0] += int(suite.get("tests", 0))
    t[1] += int(suite.get("failures", 0)) + int(suite.get("errors", 0))
    t[2] += int(suite.get("skipped", 0))
    for case in suite.iter("testcase"):
        for bad in list(case.findall("failure")) + list(case.findall("error")):
            msg = (bad.get("message") or bad.text or "").strip().splitlines()
            fails.append(f"  {case.get('classname','').split('.')[-1]} > {case.get('name')}: {msg[0][:200] if msg else ''}")
for m, (n, bad, skip) in sorted(totals.items()):
    print(f"tests {m}: {n} run, {bad} failed, {skip} skipped")
if not totals and any(w in " ".join(sys.argv[3:]) for w in ("test", "Test", "check")):
    print("no test results written (tasks up to date; add --rerun to force)")
if fails:
    print("failed tests:")
    print("\n".join(fails[:30]))
EOF

if [ $code -eq 0 ]; then
  echo "BUILD OK (${secs}s)"
else
  echo "BUILD FAILED (${secs}s, exit $code). Full log: build/dev/verify.log"
fi
exit $code
