---
status: accepted
date: 2026-10-22
---
# ADR-0007: Distribution: APK first, Google Play and F-Droid after MVP

## Context

Early users are the customer's family. Functionally the app is identical from any source (ADR-0006).
Google Play adds convenience and trust: installation without "unknown sources", automatic updates, no Play Protect
warnings, discoverability for other families. It also adds overhead: developer account ($25) and identity verification;
a closed test with at least 12 testers for 14 days for new personal accounts; background-location declaration with a
demo video and manual review; a permanently running demo server with a reviewer account; privacy policy and Data safety form.
Google is also rolling out mandatory developer verification for apps installed outside Play (from 2026 in some countries,
globally from 2027), which affects APK and F-Droid installs.

## Decision

1. **Until MVP (M0–M5):** signed APKs for the family (GitHub Releases once the repository exists).
2. **From day one** the app complies with Google Play policies: current target SDK, prominent disclosure before
   requesting background location, in-app account deletion, minimal permissions, privacy policy text.
3. **M6 Public release:** F-Droid listing. Whether to publish on Google Play is decided by the customer at M6;
   if yes, the Play closed test doubles as the family-and-friends beta.
4. **Signing:** aim for reproducible builds so F-Droid can ship builds signed with the project key,
   letting users switch channels without reinstalling.
5. Single build for all channels (follows from ADR-0006).

## Consequences

- No store overhead while the product changes fast; keeping Play compliance costs almost nothing.
- At M6: re-check developer verification rules; a developer account may be needed even without a Play listing.
- Installing and updating the app from the family's own server is a possible later feature; it is not allowed for Play-distributed builds.

## Alternatives considered

- **Google Play from the start** — store overhead during the period when only the family uses the app.
- **F-Droid and APK only, no Play ever** — harder installation and updates for non-technical relatives; kept open until M6.
