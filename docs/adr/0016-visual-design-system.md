---
status: accepted
date: 2026-10-23
---
# ADR-0016: Visual design system: neutral Material 3 with a lake-gradient accent

## Context

Before module UI (M0-07 onward) the client needs a fixed look so screens built in different sessions stay consistent.
The M0-07 plan had a placeholder theme (sage green, Material defaults). On 2026-10-23 the customer reviewed two rounds of
options (palettes, shapes, icon sets, fonts, avatars) and then screen mockups, and chose:

- color: the "lake gradient" direction: neutral surfaces, blue accent, blue → teal gradient on main elements only
  (first-round sage, blue, terracotta and plum palettes were rejected as "ordinary");
- character: "calm": medium radii, normal density;
- icons: thin outlined icons (Material Symbols Outlined, weight 250), preferred over Lucide;
- font: Manrope;
- people: photo when available, otherwise initials, plus a personal color for every person;
- a "Today" home screen that summarizes all sections.

Constraints: no proprietary dependencies (ADR-0006), license compatibility with AGPL (ADR-0003),
Compose Multiplatform client (ADR-0004), accessibility (conventions: contrast, scalable text).

## Decision

1. The look is specified in [docs/design/visual-style.md](../design/visual-style.md) (tokens, type scale, shapes,
   icons, people, navigation, component patterns). It is binding for all client UI; mockups in `docs/design/mockups/`
   illustrate it.
2. Material 3 with **fixed brand colors**; dynamic color is off.
3. One accent (`primary` blue); the lake gradient is limited to the hero card, the main FAB, the app icon and the active
   shopping-list progress.
4. Font: Manrope (OFL 1.1), bundled as static instances; tabular figures for numbers.
5. Icons: Material Symbols Outlined (Apache 2.0) at weight 250, generated into XML vector drawables by a script in
   `tools/design-assets/` (fonttools) and committed. `material-icons-core` is not used in new UI.
6. One 10-color palette (`PaletteColor`, renamed from the planned `TagColor`) serves tags and personal colors of persons.
7. Navigation: "Today" home screen and a bottom bar "Today · Tasks · Shopping · Calendar · More"
   (confirmed by the customer, Q-013).

## Consequences

- M0-07 implements the theme, fonts, icon pipeline, Today placeholder and the new bar; M0-08 uses personal colors.
- Contract change before M0-05 is implemented: `PaletteColor` shared by tags and persons, `PersonData.color`.
- Each module milestone adds its block to the Today screen.
- Requires Python with fonttools on the developer machine to regenerate icons and fonts (not to build the app).
- Two bundled assets with their own licenses (OFL, Apache 2.0) must be listed in the in-app licenses screen.
- Person photos (FND-033) need file storage, planned in milestone M5b; until then avatars show initials.

## Alternatives considered

- **Dynamic color (Material You)** — every family member would see a different app; no recognizable look.
- **Monochrome UI with per-section colors** — offered; the customer preferred one brand accent.
- **Lucide icons** — liked for thin lines, but about half the icon count of Material Symbols; thin Material weights
  give the same feel with full coverage.
- **Material Symbols font rendered with ligatures at runtime** — the variable font is ~10 MB; generated vectors are small
  and tint like any icon.
- **Roboto, Inter, Nunito** — Manrope chosen for expressive titles and neat figures (budget).
