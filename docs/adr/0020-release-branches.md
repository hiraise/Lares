---
status: accepted
date: 2026-10-24
---
# ADR-0020: Work lands on release branches; main changes only by a release merge

## Context

Until now agents committed straight to `main` (workflow "Git until MVP") and a release was a tag pushed by hand
(ADR-0019 §4, §7). In October 2026 eleven commits piled up on local `main` with no clear point where they become a
release. The customer asked (2026-10-24) to close direct pushes to `main`, collect features on release branches, and
have a release merged into `main`, tagged, and built (APK and server images) automatically. The repository is public,
so GitHub rulesets are available without a paid plan.

## Decision

1. **`main` holds released code only.** A repository ruleset (`.github/rulesets/main.json`) forbids direct pushes,
   force pushes and deletion for everyone, admins included. `main` changes only through a pull request merged with a
   merge commit, after the required checks `build`, `deploy-smoke` and `release-pr` pass on a branch that is up to date
   with `main`. No review approval is required: the customer does not review code; their approval of the release is the
   merge.
2. **One branch per release: `release/X.Y.Z`**, created from `main` (`tools/release/release.sh start X.Y.Z`). Milestone
   releases (`0.2.0`) and patch releases (`0.2.1`) use the same kind of branch. Work items are committed to the active
   release branch after the customer approves them (as before) and pushed; CI checks every push. A short-lived
   `feature/<id>` branch with a pull request into the release branch is allowed for parallel or risky work. Release
   branches cannot be force-pushed (ruleset `release branches`); they are deleted after the merge.
3. **Closing a release.** `tools/release/release.sh finish` on the release branch moves `CHANGELOG.md` `[Unreleased]`
   to `X.Y.Z`, sets `version` in `gradle.properties`, pushes and opens the pull request into `main`. CI job `release-pr`
   (`tools/release/check.sh`) accepts only a `release/X.Y.Z` head branch whose version matches, is greater than every
   released version, has no tag yet and has CHANGELOG notes.
4. **The merge is the release.** Every push to `main` runs `.github/workflows/release.yml`: checks, signed APK
   (ADR-0019 §3 unchanged), multi-arch server image `ghcr.io/hiraise/lares-server:X.Y.Z` and `:latest`, and last the tag
   `vX.Y.Z` with the GitHub Release. The tag is created by the workflow, never by hand; a failed run is re-run, since
   nothing is tagged before everything else succeeded.
5. **Tags `v*` cannot be moved or deleted** (ruleset `version tags`).
6. **Two open release branches** (a patch while a milestone is in progress): after the patch is released, merge `main`
   into the milestone branch (`git merge origin/main`); `finish` refuses to run until that is done.
7. The rulesets live in `.github/rulesets/` and are applied with `tools/release/rulesets.sh`.

This supersedes ADR-0019 §4 and §7 (the rest of ADR-0019 stands) and the "Git until MVP" section of the workflow,
which planned a branch flow only at M6.

## Consequences

- Nothing reaches `main` or users without a release pull request and green CI; "commit on main" no longer exists.
- STATUS.md names the active release branch; agents check it out before committing.
- CI runs on release branch pushes and on pull requests; there is no CI run on `main` other than the release itself.
- Version and CHANGELOG conflicts are possible when `main` is merged into a second release branch; resolved by hand.
- Emergency changes to `main` go through a `release/X.Y.Z` patch branch like any other; disabling the ruleset is an
  admin action that needs the customer.

## Alternatives considered

- **Git flow with a `develop` branch** — one more long-lived branch to keep in sync; a release branch per version is
  enough for one customer and a few agents.
- **Feature branch and pull request for every work item** — each commit would wait ~15 min for CI before merging;
  kept optional.
- **Squash release merges** — would collapse a milestone into one commit and lose per-plan history.
- **Tag by hand after the merge** — one more manual step that can be forgotten or put on the wrong commit.
