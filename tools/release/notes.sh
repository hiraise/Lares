#!/usr/bin/env bash
# Prints GitHub release notes for a version: its CHANGELOG.md section plus how to check the APK signature.
# Usage: tools/release/notes.sh 0.2.0
set -euo pipefail

version="${1:?usage: notes.sh VERSION}"
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

cat <<NOTES
$(sed '/./,$!d' <<< "$section")

### Android APK

Install \`lares-$version.apk\` on the phone. Every release is signed with the same Lares key, so a new version installs
over the old one. Signing certificate SHA-256:

\`$fingerprint\`
NOTES
