---
status: approved
updated: 2026-10-22
---
# Vision

## Problem

A family runs many small processes: chores, shopping, birthdays, appointments, knowing where the kids are,
spending and saving money. Today these live in chats, memory, paper and several unconnected apps,
most of which sell the family's data or charge per-seat subscriptions.

## Product

One shared, private space for the family, hosted on the family's own server:

- **Tasks** — one-off and recurring chores, assigned to members, tracked to completion.
- **Shopping lists** — shared, usable in a store without network.
- **Calendar** — events and birthdays of relatives, with who it concerns and reminders.
- **Map** — where family members are (phones; GPS trackers possibly later), places and geofence alerts.
- **Budget** — income allocated to categories by rules, category deficits and inter-category loans, real debts.
- **Tags** — one tag (e.g. "Car") links tasks, events, purchases and spending, with a page showing all of it.

## Users

| Persona | Description |
| --- | --- |
| Admin parent | Tech-savvy. Runs the server on a VPS, sets up the family, manages devices. |
| Adult member | Non-technical spouse or adult relative. Uses the app daily, must never see server internals. |
| Child | Has a phone. An ordinary user; what they may do is set by the groups they are in (e.g. template "Kids"). |
| Relative | Grandparent, uncle, etc. Usually **not** a user: exists as a person with a birthday and contacts. |

Scale: one family of 2–10 members per household. One server instance may host several households.

## Principles

1. **Self-hosted and private.** Data lives only on the family's server. No telemetry, ads or third-party trackers.
2. **Free forever.** Open source (AGPL-3.0-or-later), no paid tiers, no monetization.
3. **Works offline.** Lists, tasks, calendar and budget work without network and sync later.
4. **Simple for non-technical members.** Server complexity is the admin's concern only.
5. **Transparent location sharing.** A tracked person always knows they are tracked.
6. **Cross-module by design.** Tags and persons connect modules instead of each module living alone.
7. **Flexible access.** No hardcoded family roles: the admin defines groups and permissions.

## Non-goals

- Cloud SaaS or hosted offering by the project.
- Social features beyond one household (public profiles, feeds).
- Bank integrations via proprietary APIs (CSV import may come later).
- Kids' gamification (points, rewards) — declined by the customer on 2026-10-22; may be revisited.
- Supporting closed tracker ecosystems (AirTag, SmartTag) — no open API exists.

## Platforms

Server: Linux, Docker, x86_64 and arm64; VPS with 1–2 GB RAM (ADR-0013). Client: Android first; web (M7); iOS and desktop later.
