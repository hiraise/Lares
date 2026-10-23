# Release process

Decision: [ADR-0019](../adr/0019-release-signing-and-flow.md). Releases need the customer's approval.

## When

- After each milestone is accepted: minor version (`0.2.0`, `0.3.0`, …). `1.0.0` is the MVP.
- Bug fixes between milestones: patch version (`0.2.1`).
- Commits to `main` are never releases; CI only checks them.

## Steps

1. Make sure `CHANGELOG.md` `[Unreleased]` lists the user-visible changes (English, short, for users).
2. The working tree is clean, `main` is committed, the customer approved the release.
3. `tools/release/release.sh X.Y.Z --push` (without `--push` it only commits and tags locally; then
   `git push origin main vX.Y.Z`).
4. Watch `gh run list --workflow release.yml`. The workflow runs all checks, builds and verifies the signed APK, pushes
   `ghcr.io/hiraise/lares-server:X.Y.Z` and `:latest`, and creates the GitHub release with the CHANGELOG notes.
5. Tell the customer in Russian what was released, with the release link.

If the workflow fails after the tag was pushed: fix on `main` and release the next patch version. Do not move or
delete published tags.

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
