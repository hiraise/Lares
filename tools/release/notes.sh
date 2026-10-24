#!/usr/bin/env bash
# Prints GitHub release notes for a version: its CHANGELOG.md section plus how to check the APK signature and, when the
# APK is given, its file checksum.
# Usage: tools/release/notes.sh 0.2.0 [lares-0.2.0.apk]
set -euo pipefail

version="${1:?usage: notes.sh VERSION [APK]}"
apk="${2:-}"
root="$(cd "$(dirname "$0")/../.." && pwd)"

section="$(awk -v v="$version" '
  index($0, "## [" v "]") == 1 { found = 1; next }
  found && /^## \[/ { exit }
  found { print }
' "$root/CHANGELOG.md")"

if [ -z "$(tr -d '[:space:]' <<< "$section")" ]; then
  echo "CHANGELOG.md has no section for $version" >&2
  exit 1
fi

fingerprint="$(tr -d '[:space:]' < "$root/client/android/release-cert.sha256")"

checksum=""
if [ -n "$apk" ]; then
  if command -v sha256sum > /dev/null; then
    hash="$(sha256sum "$apk" | cut -d' ' -f1)"
  else
    hash="$(shasum -a 256 "$apk" | cut -d' ' -f1)"
  fi
  checksum="
File SHA-256 of \`lares-$version.apk\` (also in \`lares-$version.apk.sha256\`; check with
\`sha256sum -c lares-$version.apk.sha256\`):

\`$hash\`
"
fi

cat <<NOTES
$(sed '/./,$!d' <<< "$section")

### Android APK

Install \`lares-$version.apk\` on the phone. Every release is signed with the same Lares key, so a new version installs
over the old one. Signing certificate SHA-256:

\`$fingerprint\`
$checksum
NOTES
