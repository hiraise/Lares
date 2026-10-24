# Release process

Decisions: [ADR-0019](../adr/0019-release-signing-and-flow.md) (signing, when to release),
[ADR-0020](../adr/0020-release-branches.md) (branches). Releases need the customer's approval.

## Branches

| Branch | What | Who pushes |
| --- | --- | --- |
| `main` | Released code only. Protected: no direct push, only a release pull request with green CI | nobody; the release merge |
| `release/X.Y.Z` | The next release. Work items are committed here after the customer's approval | agents |
| `feature/<id>` (optional) | Parallel or risky work, pull request into the release branch | agents |

The active release branch is named in [STATUS.md](../STATUS.md). Every push to a release branch and every pull request
runs CI (checks, smoke test, debug APK kept 14 days).

## When

- After each milestone is accepted: minor version (`0.2.0`, `0.3.0`, …). `1.0.0` is the MVP.
- Bug fixes between milestones: patch version (`0.2.1`), on its own branch `release/0.2.1`.

## Steps

1. **Start** (once per release): `tools/release/release.sh start X.Y.Z` creates `release/X.Y.Z` from `origin/main` and
   pushes it. Write the branch into STATUS.md.
2. **Work**: commit approved work items on the branch, `git push`. Keep `CHANGELOG.md` `[Unreleased]` up to date
   (English, short, for users).
3. **Finish**: when the customer approved the release, on the branch run `tools/release/release.sh finish`. It moves
   `[Unreleased]` to `X.Y.Z`, sets the version, commits `chore: release X.Y.Z`, pushes and opens the pull request
   `Release X.Y.Z` into `main`. CI job `release-pr` checks branch name, version, tag and notes.
4. **Merge**: when CI is green, `gh pr merge <url> --merge` (or the customer presses "Merge" on GitHub). Only merge
   commits are allowed into `main`.
5. **Watch** `gh run list --workflow release.yml`. The workflow runs all checks, builds and verifies the signed APK,
   pushes `ghcr.io/hiraise/lares-server:X.Y.Z` and `:latest`, then creates the tag `vX.Y.Z` and the GitHub release with
   the CHANGELOG notes. The release branch is deleted automatically after the merge.
6. Tell the customer in Russian what was released, with the release link. Start the next release branch.

If the release workflow fails: nothing is tagged yet; fix the cause (secrets, registry) and re-run the failed run
(`gh run rerun <id> --failed`). If the code itself is broken, release the next patch version from a new
`release/X.Y.Z` branch. Published tags cannot be moved or deleted.

**Two release branches at once** (a patch during a milestone): after the patch is released, merge `main` into the
milestone branch (`git merge origin/main`, resolve `CHANGELOG.md` by hand). `finish` refuses to run otherwise.

## Repository rules

Rulesets are files in [.github/rulesets/](../../.github/rulesets/) and are applied with `tools/release/rulesets.sh`
(gh logged in as an admin; also after moving the repository).

| Ruleset | Applies to | Rules |
| --- | --- | --- |
| `main` | default branch | no push, no force push, no deletion; pull request with merge commit; checks `build`, `deploy-smoke`, `release-pr` green on an up-to-date branch; no bypass |
| `release branches` | `release/**` | no force push |
| `version tags` | `v*` | no move, no deletion |

Renaming a CI job means updating `main.json` and re-running the script.

## Signing key

| What | Where |
| --- | --- |
| Keystore and password (original) | Customer's Mac: `~/.android/lares-release/release.jks`, `password.txt` |
| Backup | `~/.android/lares-release.zip`, kept off the machine by the customer |
| CI copy | GitHub secrets `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` (`lares`), `ANDROID_KEY_PASSWORD` |
| Certificate SHA-256 (public) | `client/android/release-cert.sha256` |

Check any APK: `tools/release/verify-apk.sh lares-X.Y.Z.apk`.

Build a signed APK locally (rarely needed):

```sh
k=~/.android/lares-release
ANDROID_KEYSTORE_FILE=$k/release.jks ANDROID_KEY_ALIAS=lares \
ANDROID_KEYSTORE_PASSWORD="$(cat $k/password.txt)" ANDROID_KEY_PASSWORD="$(cat $k/password.txt)" \
  ./gradlew :client:android:assembleRelease
```

Restore the CI secrets from the keystore (e.g. after moving the repository):

```sh
k=~/.android/lares-release
base64 -i $k/release.jks | tr -d '\n' | gh secret set ANDROID_KEYSTORE_BASE64
gh secret set ANDROID_KEYSTORE_PASSWORD < $k/password.txt
gh secret set ANDROID_KEY_PASSWORD < $k/password.txt
printf lares | gh secret set ANDROID_KEY_ALIAS
```

Never commit the keystore or the password, never paste them into chats, issues or logs.
