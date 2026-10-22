---
status: accepted
date: 2026-10-22
---
# ADR-0010: Household is the tenant boundary

## Context

FND-002: one instance can host several households (e.g. the admin also hosts their parents' family).
Data of different households must never leak.

## Decision

1. Every domain record belongs to exactly one household and carries `household_id`.
2. All data access is scoped by household in the persistence layer; there is no unscoped query API for feature code.
3. Users are global to the instance; membership (`Member`) links a user to a household; permissions come from groups (ADR-0014).
   The model allows a user in several households; the MVP UI works with one.
4. Sync cursors and change sequences are per household (ADR-0008).
5. Cross-household isolation is covered by dedicated integration tests for every module.
6. PostgreSQL row-level security as defense in depth: decided in M0.

## Consequences

- Household id is part of every repository method and most indexes.
- The instance admin (server operator) can technically read all households; see ADR-0011.
