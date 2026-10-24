#!/usr/bin/env bash
# Applies the repository rulesets from .github/rulesets/*.json to GitHub (ADR-0020): creates missing ones, updates
# existing ones by name. Also turns on "delete branch after merge". Needs gh logged in as a repository admin.
# Usage: tools/release/rulesets.sh
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
repo="$(gh repo view --json nameWithOwner -q .nameWithOwner)"

for file in "$root"/.github/rulesets/*.json; do
  name="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["name"])' "$file")"
  id="$(gh api "repos/$repo/rulesets" --jq ".[] | select(.name == \"$name\") | .id")"
  if [ -n "$id" ]; then
    gh api -X PUT "repos/$repo/rulesets/$id" --input "$file" > /dev/null
    echo "updated ruleset '$name' ($id)"
  else
    id="$(gh api -X POST "repos/$repo/rulesets" --input "$file" --jq .id)"
    echo "created ruleset '$name' ($id)"
  fi
done

gh api -X PATCH "repos/$repo" -F delete_branch_on_merge=true > /dev/null
echo "delete branch after merge: on"
