---
status: accepted
date: 2026-10-22
---
# ADR-0004: Kotlin full stack: Ktor server, Kotlin/Compose Multiplatform client

## Context

- Android first, with deep native needs: background location (foreground service), local notifications, battery handling.
- iOS, desktop and web are wanted later, ideally from the same codebase.
- All code is written by AI agents: a single strongly typed language and shared types between server and client
  catch contract drift at compile time.
- Server runs on a small VPS.

## Decision

1. **Language:** Kotlin everywhere.
2. **Server:** Ktor on the JVM (current LTS JDK, exact version fixed in M0).
3. **Client:** Kotlin Multiplatform + Compose Multiplatform. Shared: domain, data, sync, UI.
   Platform code only where needed (location service, notifications). Targets: Android now;
   web (Wasm; Beta as of Compose Multiplatform 1.9), iOS and desktop later.
4. **Contract module** shared by server and client: DTOs (kotlinx.serialization), route definitions, validation rules, sync protocol types.
5. **Build:** Gradle with Kotlin DSL and a version catalog; one monorepo.
6. Concrete libraries (DB access, DI, local DB, migrations, logging) are chosen in M0 and recorded in
   [conventions](../process/conventions.md) or separate ADRs.

## Consequences

- One toolchain and one set of conventions for every agent.
- Android gets full platform APIs without bridges.
- JVM server uses roughly 200–300 MB RAM; acceptable on a 1–2 GB VPS together with PostgreSQL.
- Gradle builds are slower than Go/TS toolchains; mitigated by build cache and module boundaries.
- iOS builds require macOS (available on the development machine).
- Web via Compose is Beta; re-evaluate at M7 (fallback: separate web UI consuming the same API).

## Alternatives considered

- **Flutter + Go/TypeScript server** — mature UI on all platforms, but background location relies on plugins
  (the most capable one is commercial for Android release builds), native code goes through platform channels,
  and two languages without shared types.
- **React Native + TypeScript server** — one language, big ecosystem; weaker for long-running native background services.
- **Native Android + separate native iOS later** — best native fit, double UI work for iOS.
- **Go server** — smaller memory footprint, but loses shared code with the client; memory is not a constraint on a VPS.
  Reviewed again with the customer: see ADR-0013.
