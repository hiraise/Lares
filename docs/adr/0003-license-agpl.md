---
status: accepted
date: 2026-10-22
---
# ADR-0003: License: AGPL-3.0-or-later

## Context

The project is pure open source with no monetization. The customer wants the code and any modifications
to stay open, including when someone runs a modified server as a service for others.

## Decision

The whole repository (server, client, contract, deploy files) is licensed under **GNU AGPL-3.0-or-later**.
The full text is in `LICENSE`. New source files may carry an SPDX header: `SPDX-License-Identifier: AGPL-3.0-or-later`.

## Consequences

- Forks that offer the server over a network must publish their changes.
- Dependencies must be compatible with AGPL-3.0 (permissive licenses such as MIT, BSD, Apache-2.0 are fine).
  Check the license before adding any dependency.
- F-Droid accepts AGPL apps. Apple App Store and GPL-family licenses have known friction; revisit before an iOS release.

## Alternatives considered

- **GPL-3.0** — does not cover the "run as a service" case.
- **MIT / Apache-2.0** — allow closed commercial forks; the customer declined.
