---
status: accepted
date: 2026-10-22
---
# ADR-0002: Languages of code, docs, UI and communication

## Context

The customer speaks Russian. The project is open source and should be approachable for outside contributors.
Agents work equally well in both languages.

## Decision

| What | Language |
| --- | --- |
| Code, identifiers, comments, commit messages, PRs | English |
| Technical docs (`docs/`, AGENTS.md, ADRs, specs) | English |
| README and user manuals | English and Russian (`README.md`, `README.ru.md`, `docs/user/<lang>/`) |
| UI | Localized; `en` and `ru` from the first release; English is the fallback |
| Communication with the customer, milestone changelogs for the customer | Russian |

Product terms that the customer uses in Russian are mapped in [glossary](../domain/glossary.md).

## Consequences

- No hardcoded UI strings; resources are added for both languages in the same change.
- When the customer asks something in Russian, answer in Russian but keep repository artifacts in English.
