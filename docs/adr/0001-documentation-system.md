---
status: accepted
date: 2026-10-22
---
# ADR-0001: Documentation system and ADRs

## Context

All design, code and validation are done by AI agents; the human customer only sets goals and accepts results.
Agents start each session without memory of previous ones, so the repository must answer quickly:
what is the project, what is the current state, what rules and decisions apply, what to do next.
Humans are secondary readers of technical docs; they get README and user manuals.

## Decision

1. `AGENTS.md` at the root is the single entry point for agents (cross-tool convention). `CLAUDE.md` imports it.
2. `docs/STATUS.md` holds the current state and next steps; updated at the end of every work session.
3. Decisions are recorded as ADRs in `docs/adr/` (MADR-style, short). Accepted ADRs are immutable and binding.
4. Product scope lives in `docs/product/`: vision, roadmap, feature specs with requirement IDs, open questions.
5. Domain language (`docs/domain/glossary.md`) maps English code terms to Russian product terms.
6. Stable IDs (`ADR-`, `M`, `Q-`, `<AREA>-NNN`) are referenced from code, tests and commits for traceability.
7. Markdown only, Mermaid for diagrams, everything in the repo next to the code.

## Consequences

- An agent gets oriented by reading AGENTS.md → STATUS.md → relevant spec and ADRs, without scanning code.
- Docs maintenance is part of the Definition of Done; stale docs are treated as bugs.
- External wikis and trackers are optional mirrors, never the source of truth (GitHub Issues may be used for work items once the repo exists).

## Alternatives considered

- **Wiki / Notion** — separate from code, drifts, not visible to agents in the working tree.
- **Code-only (README + comments)** — loses the "why" and the product scope.
- **arc42 full template** — too heavy for the current size; its useful parts are covered by architecture/overview.md.
