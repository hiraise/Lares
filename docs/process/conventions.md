# Conventions

Preliminary. M0 completes this file with the concrete toolchain (formatter, linter, libraries) and commands.

## General

- Kotlin official code style; formatter and static analysis enforced in CI (tools chosen in M0).
- Package / module per feature (ADR-0012); no cross-feature access to internals.
- Terms from the [glossary](../domain/glossary.md); no synonyms in code (`Member`, not `FamilyUser`).
- Comments explain why, not what. Reference requirement IDs where the code implements a specific rule.
- No personal data (positions, amounts, names) in logs.

## Data and values

- IDs: UUIDv7, generated on the client for syncable entities.
- Money: `Long` minor units + ISO 4217 code. Never `Double`/`Float`.
- Time: UTC instants; event times carry an IANA zone id; birthdays and all-day dates are local dates.
- Every query in feature code is scoped by `household_id` (ADR-0010).

## API and contract

- Contract types live in `contract/`; server and client never define their own copies.
- Breaking contract changes bump the protocol version (ADR-0008).

## Dependencies

- License must be compatible with AGPL-3.0 (ADR-0003).
- No proprietary SDKs, analytics, crash reporters, ad SDKs (ADR-0006).
- Versions in the Gradle version catalog only.

## Git

- Until MVP: commits go to `main` after customer approval ([workflow](workflow.md#git-until-mvp)).
  After MVP: release flow defined at M6.
- Conventional Commits; scope = module (`tasks`, `sync`, `deploy`, `docs`); requirement IDs in the subject or body.
- One logical change per commit; docs updated in the same commit or PR.

## UI

- All strings in resources, en and ru.
- Every screen works offline with the data already synced, and shows sync state (FND-043).
- Accessibility: content descriptions, scalable text, sufficient contrast.
