---
status: accepted
date: 2026-10-22
---
# ADR-0012: Server is a modular monolith

## Context

Family scale (a few households, tens of devices), one database, deployment by non-experts.
Modules (tasks, calendar, location, budget, ...) should still be independent enough that agents can work on one
without touching the others.

## Decision

1. One server process and one database.
2. Code is split into feature modules: `identity`, `household` (incl. persons), `tags`, `tasks`, `shopping`,
   `calendar`, `location`, `budget`, plus infrastructure modules `sync`, `notifications`, `platform` (HTTP, DB, config).
3. Each feature module owns its tables and exposes a small public API; other modules must not access its tables directly.
4. Cross-module links (tags, persons, calendar agenda showing tasks) go through public APIs or domain events inside the process.
5. Boundaries are enforced by Gradle module structure and/or architecture tests.
6. The client mirrors the same feature-module split.

## Consequences

- Simple deployment (one container) and transactions within one database.
- Clear ownership per module; a module can be specified, built and tested on its own.

## Alternatives considered

- **Microservices** — operational cost without benefit at this scale.
- **Unstructured monolith** — fast at first, then tangled; harder for agents to change safely.
