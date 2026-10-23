#!/usr/bin/env bash
# Prepares a release (ADR-0019): moves CHANGELOG [Unreleased] to the new version, sets version in gradle.properties,
# commits and tags vVERSION. With --push it also pushes main and the tag, which starts .github/workflows/release.yml.
# Run only after the customer approved the release.
# Usage: tools/release/release.sh 0.2.0 [--push]
set -euo pipefail

version="${1:?usage: release.sh VERSION [--push]}"
push="${2:-}"
root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$root"

fail() { echo "release: $*" >&2; exit 1; }

[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || fail "version must be MAJOR.MINOR.PATCH, got $version"
[ -z "$push" ] || [ "$push" = --push ] || fail "unknown option $push"
[ "$(git branch --show-current)" = main ] || fail "not on main"
[ -z "$(git status --porcelain)" ] || fail "working tree is not clean"
git rev-parse -q --verify "refs/tags/v$version" > /dev/null && fail "tag v$version already exists"

current="$(sed -n 's/^version=//p' gradle.properties)"
[ "$(printf '%s\n%s\n' "$current" "$version" | sort -V | tail -1)" = "$version" ] && [ "$current" != "$version" ] ||
  fail "new version $version must be greater than $current"

git fetch -q origin main
[ -z "$(git rev-list HEAD..origin/main)" ] || fail "origin/main has commits that are not in local main"

unreleased="$(awk '/^## \[Unreleased\]/ { f = 1; next } f && /^## \[/ { exit } f { print }' CHANGELOG.md)"
[ -n "$(tr -d '[:space:]' <<< "$unreleased")" ] || fail "CHANGELOG.md [Unreleased] is empty"

today="$(date +%Y-%m-%d)"
awk -v v="$version" -v d="$today" '
  /^## \[Unreleased\]/ && !done { print; print ""; print "## [" v "] - " d; done = 1; next }
  { print }
' CHANGELOG.md > CHANGELOG.md.tmp && mv CHANGELOG.md.tmp CHANGELOG.md
awk -v v="$version" '/^version=/ { print "version=" v; next } { print }' gradle.properties > gradle.properties.tmp &&
  mv gradle.properties.tmp gradle.properties

tools/release/notes.sh "$version" > /dev/null

git add CHANGELOG.md gradle.properties
git commit -q -m "chore: release $version"
git tag -a "v$version" -m "Lares $version"
echo "Prepared release $version (commit $(git rev-parse --short HEAD), tag v$version)."

if [ "$push" = --push ]; then
  git push origin main "v$version"
  echo "Pushed. Release build: gh run list --workflow release.yml"
else
  echo "Not pushed. To publish: git push origin main v$version"
fi
