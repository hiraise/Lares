# Documentation map

Technical documentation, written for AI agents first and humans second (see [ADR-0001](adr/0001-documentation-system.md)).
User-facing manuals live in [user/](user/).

## Where things are

| Need to know | Go to |
| --- | --- |
| What is happening right now | [STATUS.md](STATUS.md) |
| Why the product exists, goals, non-goals | [product/vision.md](product/vision.md) |
| Milestones and their scope | [product/roadmap.md](product/roadmap.md) |
| What a feature must do (requirements with IDs) | [product/features/](product/features/) |
| Questions waiting for the customer | [product/open-questions.md](product/open-questions.md) |
| Terms and their Russian equivalents | [domain/glossary.md](domain/glossary.md) |
| Entities and relations | [domain/model.md](domain/model.md) |
| System structure | [architecture/overview.md](architecture/overview.md) |
| Sync protocol | [architecture/sync.md](architecture/sync.md) |
| Authentication and onboarding | [architecture/auth.md](architecture/auth.md) |
| HTTP API conventions and endpoints | [architecture/api.md](architecture/api.md) |
| Implementation plans for sessions | [plans/](plans/README.md) |
| Why something is the way it is | [adr/](adr/README.md) |
| How to work: lifecycle, DoD, escalation | [process/workflow.md](process/workflow.md) |
| Code conventions | [process/conventions.md](process/conventions.md) |
| Testing strategy | [process/testing.md](process/testing.md) |

## Identifiers

| ID | Meaning | Example |
| --- | --- | --- |
| `ADR-NNNN` | Architecture decision record | ADR-0004 |
| `M<N>` | Milestone | M1 |
| `Q-NNN` | Open question for the customer | Q-003 |
| `<AREA>-NNN` | Requirement in a feature spec | TSK-012 |

Requirement areas: `FND` foundation (identity, household, persons, sync, notifications), `TAG` tags, `TSK` tasks,
`SHP` shopping lists, `CAL` calendar, `LOC` location, `BUD` budget, `TRK` GPS trackers, `WEB` web client.

Reference IDs everywhere: in code comments, test names, commit messages, PR descriptions.
IDs are never reused. A removed requirement keeps its ID with status `dropped`.

## Rules for writing docs

- **One fact, one place.** Link instead of copying.
- **Front matter** on specs and ADRs: `status`, `updated` / `date`. Status values are listed in each folder's README.
- **Short and structured.** Lists and tables over prose. No filler.
- **Mark uncertainty.** `ASSUMPTION:` for reversible defaults not confirmed by the customer, `TBD:` for unknowns.
- **ADRs are immutable once accepted**, except for the status line and a "Superseded by" link.
- **STATUS.md is updated at the end of every work session.**
- Diagrams: Mermaid in Markdown, so they diff and render on GitHub.
