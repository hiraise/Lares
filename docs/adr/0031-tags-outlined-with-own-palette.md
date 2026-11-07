---
status: accepted
date: 2026-11-07
---
# ADR-0031: Tags are outlined chips with a color dot and have a palette of 16 colors

## Context

ADR-0016 gave tags and persons one palette of 10 colors, and tag chips were filled with the color's container. With
four or five persons half of the palette is taken by personal colors, so a tag often has the same color as somebody:
the customer saw a blue «Машина» tag next to their own blue events and read it as "the car is me" (2026-11-07). The
customer chose both remedies offered: tags look different from persons, and tags get more colors.

## Decision

Amends ADR-0016 (palette and tag chips in visual-style.md).

1. A solid color fill marks a person only (avatar, pin, calendar dot and bar). A tag chip is outlined
   (`outlineVariant`, radius 8) with an 8 dp dot in the tag's color and `onSurface` text; in the tag picker a selected
   tag is filled like a selected filter chip (`inverseSurface`, check) and keeps its dot. Lists of tags already used a
   dot and stay.
2. `PaletteColor` gains six colors for tags only: `rose`, `olive`, `cyan`, `indigo`, `magenta`, `slate`, generated in
   HCT at the tones of the existing ten (base 48, light container 92, dark text 75, dark container 28). Tags pick from
   all 16 (`PaletteColors.tagColors`, around the color wheel); persons from the first ten
   (`PaletteColors.personColors`), and person validation rejects the others.
3. The wire names are appended; older clients decode the new names to gray (the lenient enum). An older server
   rejects a new name (strict decoding, `VALIDATION_FAILED`), so a new color needs the server of 0.5.2 or later. No
   migration.

## Consequences

- A tag and a person of the same color no longer look alike; tags are calmer on screens with many people.
- A tag of a new color shows gray on phones still on 0.5.1 or older until they update; on a server older than 0.5.2
  choosing a new color fails to sync and the phone reports the rejected change.

## Alternatives considered

- **A separate muted palette for tags only** — a small dot needs a saturated color to be told apart; with the outlined
  chip the shared hues no longer collide, so tags keep the ten familiar colors and add six.
- **One larger palette for everyone** — fewer collisions but they still happen, and neighbouring hues get confused.
