#!/usr/bin/env bash
# Checks that a branch may be merged into main as a release (ADR-0020): the branch is release/X.Y.Z, gradle.properties
# says X.Y.Z, the tag vX.Y.Z does not exist yet, X.Y.Z is greater than every released version, CHANGELOG.md has notes
# for X.Y.Z. Run by CI (job release-pr) on every pull request into main and by release.sh finish.
# Usage: tools/release/check.sh release/0.2.0
set -euo pipefail

branch="${1:?usage: check.sh BRANCH}"
root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$root"

fail() { echo "::error::release check: $*" >&2; exit 1; }

[[ "$branch" =~ ^release/([0-9]+\.[0-9]+\.[0-9]+)$ ]] ||
  fail "only release/X.Y.Z branches can be merged into main, got '$branch' (docs/process/release.md)"
version="${BASH_REMATCH[1]}"

gradle_version="$(sed -n 's/^version=//p' gradle.properties)"
[ "$gradle_version" = "$version" ] ||
  fail "gradle.properties has version=$gradle_version, branch is $branch; run tools/release/release.sh finish"

released="$(git ls-remote --tags --refs origin 'v*' | sed -n 's|.*refs/tags/v\([0-9]*\.[0-9]*\.[0-9]*\)$|\1|p')"
grep -qx "$version" <<< "$released" && fail "tag v$version already exists"
latest="$(sort -V <<< "$released" | tail -1)"
if [ -n "$latest" ]; then
  [ "$(printf '%s\n%s\n' "$latest" "$version" | sort -V | tail -1)" = "$version" ] ||
    fail "version $version must be greater than the latest release $latest"
fi

tools/release/notes.sh "$version" > /dev/null || fail "CHANGELOG.md has no notes for $version"

echo "Release $version can be merged into main (latest release: ${latest:-none})."
