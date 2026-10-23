---
status: accepted
date: 2026-10-23
---
# ADR-0019: Releases are signed with one permanent Lares key and cut per milestone

## Context

Releases `v0.1.0` and `v0.1.1` (M0-10 workflow) had no release keystore, so `v0.1.1` shipped a debug APK
(`app.lares.android.debug`). A CI runner creates a new debug key on every run, so one debug APK cannot be updated by the
next one. Android installs an update only when it is signed with the same key, and a lost key means every user has to
uninstall and reinstall. Google developer verification for sideloaded apps (2026–2027) and F-Droid both identify an
app by its signing certificate. The customer asked for signing and for a flow that does not release on every commit
(2026-10-23).

## Decision

1. **One permanent key for Lares only.** RSA 4096, PKCS12, alias `lares`, valid 100 years, certificate
   `CN=Lares, O=Lares`. It is not shared with other apps of the owner, so a leak or a hand-over affects Lares alone.
2. **Where the key lives.** Original on the customer's Mac in `~/.android/lares-release/` (`release.jks`,
   `password.txt`) with the archive `~/.android/lares-release.zip` as the customer's off-machine backup. A copy is in the
   GitHub repository secrets `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`,
   `ANDROID_KEY_PASSWORD`. Never in git.
3. **The certificate fingerprint is public and checked.** `client/android/release-cert.sha256` holds the SHA-256 of the
   certificate. The release workflow fails if the secrets are missing or if the APK is not signed with exactly that
   certificate (`tools/release/verify-apk.sh`). It never publishes a debug APK. Release notes show the fingerprint.
4. **Commits are not releases.** Every push to `main` runs CI only (checks and a debug APK kept 14 days for testing).
   A release happens only on a tag `vX.Y.Z`.
5. **When to release** (customer, 2026-10-23): after each milestone (M1 → `0.2.0`, M2 → `0.3.0`, …) and patch releases
   (`0.2.1`) for bug fixes in between; no beta builds. Each release needs the customer's approval (AGENTS.md rule 9).
6. **Versions.** SemVer; `0.x` until the MVP, `1.0.0` with the MVP (M6). The single source is `version` in
   `gradle.properties`; Android `versionCode` = `MAJOR * 1_000_000 + MINOR * 1_000 + PATCH`.
7. **How to release.** `tools/release/release.sh X.Y.Z --push` moves `CHANGELOG.md` `[Unreleased]` to the version,
   sets the version, commits `chore: release X.Y.Z`, tags and pushes. The workflow takes the notes from that CHANGELOG
   section. Steps: [process/release.md](../process/release.md).

## Consequences

- Every signed release installs over the previous one. The first signed release installs next to the `0.1.1` debug
  build (different application id); its users remove `0.1.1` and sign in again. Family data stays on the server.
- Losing both the Mac copy and the backup means no updates for installed apps. Key rotation (APK Signature Scheme v3)
  is possible only while the old key is still available.
- The same fingerprint is what we register for Google developer verification and give F-Droid (reproducible builds) later.
- Git work on `main` with commit approval stays as decided until the MVP ([workflow](../process/workflow.md#git-until-mvp)).

## Alternatives considered

- **Reuse the key of the owner's other app (babylog)** — works technically, but a leak affects both apps and Lares
  could not be handed to other maintainers without the other app's key.
- **Release on every push to `main`** — users would get unfinished work; versions would lose meaning.
- **Beta (pre-release) builds before each release** — declined by the customer for now; can be added later as
  `vX.Y.Z-beta.N` tags marked pre-release.
- **Ship the debug APK when no key is configured** (previous behaviour) — produces APKs that can never be updated.
