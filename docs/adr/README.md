# Architecture Decision Records

Format: short MADR-style records, see [template.md](template.md). Process: [ADR-0001](0001-documentation-system.md).

Status values: `proposed` (direction chosen, details or customer confirmation pending), `accepted` (binding),
`superseded by ADR-NNNN`, `deprecated`.

| ADR | Title | Status | Date |
| --- | --- | --- | --- |
| [0001](0001-documentation-system.md) | Documentation system and ADRs | accepted | 2026-10-22 |
| [0002](0002-languages.md) | Languages of code, docs, UI and communication | accepted | 2026-10-22 |
| [0003](0003-license-agpl.md) | License: AGPL-3.0-or-later | accepted | 2026-10-22 |
| [0004](0004-kotlin-full-stack.md) | Kotlin full stack: Ktor server, Kotlin/Compose Multiplatform client | accepted | 2026-10-22 |
| [0005](0005-postgresql-docker-vps.md) | PostgreSQL, Docker Compose, VPS as primary target | accepted | 2026-10-22 |
| [0006](0006-no-proprietary-dependencies.md) | No proprietary dependencies: maps, location, push | accepted | 2026-10-22 |
| [0007](0007-distribution.md) | Distribution: APK first, Google Play and F-Droid after MVP | accepted | 2026-10-22 |
| [0008](0008-offline-first-sync.md) | Offline-first client with server-authoritative sync | accepted | 2026-10-23 |
| [0009](0009-gps-trackers-via-traccar.md) | GPS trackers via Traccar compatibility | proposed | 2026-10-22 |
| [0010](0010-household-tenancy.md) | Household is the tenant boundary | accepted | 2026-10-22 |
| [0011](0011-no-e2ee-initially.md) | No end-to-end encryption in initial versions | accepted | 2026-10-22 |
| [0012](0012-modular-monolith.md) | Server is a modular monolith | accepted | 2026-10-22 |
| [0013](0013-server-resource-budget.md) | Server resource budget | accepted (§2 amended by 0015) | 2026-10-22 |
| [0014](0014-group-based-permissions.md) | Group-based permissions | accepted | 2026-10-22 |
| [0015](0015-load-measurement-amendment.md) | Load measurement in M0 without location uploads (amends 0013) | accepted | 2026-10-22 |
| [0016](0016-visual-design-system.md) | Visual design system: neutral Material 3 with a lake-gradient accent | accepted | 2026-10-23 |

## When to write an ADR

- Choosing between real alternatives with lasting consequences (framework, protocol, data model principle).
- Adding a significant dependency or infrastructure component.
- Deviating from an accepted ADR or convention (write a superseding ADR).

Not for: routine library upgrades, naming of a single class, anything easily reversible within a day.
