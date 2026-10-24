#!/usr/bin/env bash
# Release branches (ADR-0020). main is protected and changes only through a merged release/X.Y.Z pull request; the merge
# starts .github/workflows/release.yml, which tags vX.Y.Z and publishes the APK and server images.
#
#   release.sh start X.Y.Z   create release/X.Y.Z from origin/main and push it; work items are committed there
#   release.sh finish        on release/X.Y.Z: move CHANGELOG [Unreleased] to X.Y.Z, set the version, commit, push,
#                            open the pull request into main (merge it only after the customer approved the release)
#
# Process: docs/process/release.md.
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$root"

fail() { echo "release: $*" >&2; exit 1; }
usage() { fail "usage: release.sh start X.Y.Z | release.sh finish"; }
clean() { [ -z "$(git status --porcelain)" ] || fail "working tree is not clean"; }
greater() { [ "$1" != "$2" ] && [ "$(printf '%s\n%s\n' "$1" "$2" | sort -V | tail -1)" = "$1" ]; }

start() {
  local version="${1:-}" branch main_version
  [[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || fail "version must be MAJOR.MINOR.PATCH, got '$version'"
  branch="release/$version"
  clean
  git fetch -q --tags origin main
  git show-ref -q --verify "refs/heads/$branch" && fail "local branch $branch already exists"
  [ -z "$(git ls-remote --heads origin "$branch")" ] || fail "branch $branch already exists on origin"
  git rev-parse -q --verify "refs/tags/v$version" > /dev/null && fail "tag v$version already exists"
  main_version="$(git show origin/main:gradle.properties | sed -n 's/^version=//p')"
  greater "$version" "$main_version" || fail "version $version must be greater than $main_version on main"

  git switch -q -c "$branch" origin/main
  git push -q -u origin "$branch"
  echo "Started $branch from origin/main ($(git rev-parse --short HEAD)). Commit work items here and push."
}

finish() {
  local branch version unreleased today url
  branch="$(git branch --show-current)"
  [[ "$branch" =~ ^release/([0-9]+\.[0-9]+\.[0-9]+)$ ]] || fail "not on a release/X.Y.Z branch (on '$branch')"
  version="${BASH_REMATCH[1]}"
  clean
  git fetch -q --tags origin main
  git merge-base --is-ancestor origin/main HEAD ||
    fail "origin/main has commits that are not in $branch; run: git merge origin/main"
  if git ls-remote --exit-code -q --heads origin "$branch" > /dev/null; then
    git fetch -q origin "$branch"
    git merge-base --is-ancestor "origin/$branch" HEAD || fail "origin/$branch has commits that are not here; pull first"
  fi

  if [ "$(sed -n 's/^version=//p' gradle.properties)" != "$version" ]; then
    unreleased="$(awk '/^## \[Unreleased\]/ { f = 1; next } f && /^## \[/ { exit } f { print }' CHANGELOG.md)"
    [ -n "$(tr -d '[:space:]' <<< "$unreleased")" ] || fail "CHANGELOG.md [Unreleased] is empty"
    today="$(date +%Y-%m-%d)"
    awk -v v="$version" -v d="$today" '
      /^## \[Unreleased\]/ && !done { print; print ""; print "## [" v "] - " d; done = 1; next }
      { print }
    ' CHANGELOG.md > CHANGELOG.md.tmp && mv CHANGELOG.md.tmp CHANGELOG.md
    awk -v v="$version" '/^version=/ { print "version=" v; next } { print }' gradle.properties > gradle.properties.tmp &&
      mv gradle.properties.tmp gradle.properties
    git add CHANGELOG.md gradle.properties
    git commit -q -m "chore: release $version"
    echo "Committed version $version and its CHANGELOG section."
  fi

  tools/release/check.sh "$branch"
  git push -q -u origin "$branch"

  url="$(gh pr list --base main --head "$branch" --state open --json url -q '.[0].url')"
  if [ -z "$url" ]; then
    url="$(gh pr create --base main --head "$branch" --title "Release $version" \
      --body "$(tools/release/notes.sh "$version")")"
  fi
  echo "Release pull request: $url"
  echo "After CI is green and the customer approved: gh pr merge $url --merge"
}

case "${1:-}" in
  start) start "${2:-}" ;;
  finish) [ $# -eq 1 ] || usage; finish ;;
  *) usage ;;
esac
