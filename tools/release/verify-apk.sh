#!/usr/bin/env bash
# Checks that an APK is signed with the Lares release key (fingerprint in client/android/release-cert.sha256).
# Usage: tools/release/verify-apk.sh path/to/lares.apk
set -euo pipefail

apk="${1:?usage: verify-apk.sh APK}"
root="$(cd "$(dirname "$0")/../.." && pwd)"
expected="$(tr -d ':[:space:]' < "$root/client/android/release-cert.sha256" | tr '[:upper:]' '[:lower:]')"

sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
build_tools="$(ls "$sdk/build-tools" | sort -V | tail -1)"
apksigner="$sdk/build-tools/$build_tools/apksigner"

certs="$("$apksigner" verify --print-certs "$apk")"
# apksigner prints "Signer #1 certificate SHA-256 digest: …" (older) or "V2 Signer: certificate SHA-256 digest: …"
# (newer, one line per signature scheme). All lines must name the one release certificate.
actual="$(sed -n 's/^.*[Ss]igner.*certificate SHA-256 digest: //p' <<< "$certs" | sort -u)"

if [ "$actual" != "$expected" ]; then
  echo "APK $apk is not signed with the Lares release key" >&2
  echo "expected: $expected" >&2
  echo "actual:   ${actual:-none}" | tr '\n' ' ' >&2
  echo >&2
  exit 1
fi
echo "OK: $apk is signed with the Lares release key"
