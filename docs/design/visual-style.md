---
status: approved
updated: 2026-10-23
---
# Visual style

Binding look of the client: colors, type, shapes, icons, people, navigation, component patterns.
Chosen by the customer on 2026-10-23 ([ADR-0016](../adr/0016-visual-design-system.md)). Mockups: [mockups/](mockups/README.md).

Summary: **calm modern Material 3** — neutral, almost white background; white grouped cards; one saturated blue accent;
a blue → teal **"lake gradient"** reserved for the few main elements; thin outlined icons; Manrope; personal colors for people.

## Principles

1. Color is rare and means something: the accent marks actions and the current place, semantic colors mark state,
   personal colors mark people. Everything else is neutral.
2. One hero per screen at most (the gradient card), one main action (the gradient FAB).
3. Lists are grouped: rows inside one white card with inset dividers, not a card per row.
4. Big bold screen title on top-level screens; compact top bar on detail screens.
5. Same structure in light and dark themes; dark is designed, not inverted.
6. Text contrast ≥ 4.5:1 (≥ 3:1 for text ≥ 24 px or bold ≥ 18.7 px and for icons). Touch targets ≥ 48 dp.

## Color tokens

Dynamic color (Material You wallpaper colors) is **off**: the brand look is fixed.
Tokens map to Material 3 `ColorScheme` roles; extra roles live in a `FamilyHubColors` object provided via
`CompositionLocal`.

| Token (M3 role or extra) | Light | Dark | Use |
| --- | --- | --- | --- |
| `background`, `surface` | `#F3F4F7` | `#0E0F13` | Screen background |
| `surfaceContainerLowest` | `#FFFFFF` | `#1A1C22` | Cards, list groups, sheets, inputs on cards |
| `surfaceContainerLow` | `#F8F9FB` | `#15171C` | Bottom navigation bar (dark), subtle fills |
| `surfaceContainer` | `#FFFFFF` | `#15171C` | Navigation bar |
| `surfaceContainerHigh` | `#E7E9EE` | `#24262D` | Segmented-control track |
| `surfaceContainerHighest` | `#E0E3EA` | `#2A2D35` | Pressed fills |
| `onSurface`, `onBackground` | `#15171C` | `#E8E9EE` | Primary text |
| `onSurfaceVariant` | `#5B606B` | `#9A9FAB` | Secondary text, inactive icons |
| `outline` | `#8B909C` | `#6E7380` | Strong borders (rare) |
| `outlineVariant` | `#D5D9E2` | `#353841` | Input and chip borders |
| extra `divider` | `#ECEEF3` | `#26282F` | Inset dividers, nav top border |
| `primary` | `#2F5BEA` | `#8FB4FF` | Buttons, links, checked boxes, today in calendar |
| `onPrimary` | `#FFFFFF` | `#0B1D5C` | |
| `primaryContainer` | `#E4EBFF` | `#24345F` | Nav indicator, tonal buttons, counters |
| `onPrimaryContainer` | `#1B45C4` | `#C9D8FF` | Text/icons on primaryContainer; text links in light theme |
| `secondary` | `#0D6F93` | `#9FE0F2` | Calendar date tiles, places, "sharing location" |
| `onSecondary` | `#FFFFFF` | `#003545` | |
| `secondaryContainer` | `#DDF3FA` | `#123A4A` | |
| `onSecondaryContainer` | `#0D6F93` | `#9FE0F2` | |
| `inverseSurface` | `#15171C` | `#E8E9EE` | Selected filter chips, tab chips |
| `inverseOnSurface` | `#FFFFFF` | `#15171C` | |
| `error` | `#C8372B` | `#FF8A7E` | Overdue, deficit, destructive |
| `onError` | `#FFFFFF` | `#4A0D04` | |
| `errorContainer` | `#FDE7E4` | `#4A1F1A` | |
| `onErrorContainer` | `#8C1D14` | `#FFB4AB` | |
| extra `warning` / `warningContainer` | `#8A5300` / `#FFF0D6` | `#F2B45A` / `#3D2A0A` | Offline banner, category loans |
| extra `success` / `successContainer` | `#1F7A4A` / `#DCF3E5` | `#7AD3A0` / `#173826` | Positive states |

### Lake gradient

`linear-gradient(135°)`: light `#2F5BEA → #0A7BAD`, dark `#2A4FCF → #0F7FAE`. White text on it passes 4.5:1 at both ends.
In Compose: `Brush.linearGradient(listOf(start, end), start = Offset.Zero, end = Offset.Infinite)`.

Allowed only on:

- the hero card of a screen (at most one per screen): active shopping list on "Today", unallocated money in Budget;
- the main floating action button (FAB or extended FAB);
- the app icon and logo;
- the progress bar of the active shopping list.

Never on text, whole screens, regular buttons or several cards in a row.

### Palette (tags and personal colors)

One fixed palette of 10 colors serves tags and people (`PaletteColor` in `contract/`). Avatars and pins use the base
color with white initials in both themes (contrast ≥ 4.8:1). Chips use container + on-container.

| `PaletteColor` | Base (avatar, dot, pin; light chip text) | Light chip container | Dark chip text | Dark chip container |
| --- | --- | --- | --- | --- |
| `RED` | `#C14B41` | `#FFE2DE` | `#FF9F94` | `#67332D` |
| `ORANGE` | `#B75600` | `#FFE3D4` | `#FFA268` | `#643618` |
| `YELLOW` | `#926C00` | `#FFE5B6` | `#EBAF00` | `#563E03` |
| `GREEN` | `#4B7E39` | `#CFF3BC` | `#90C778` | `#2D4922` |
| `TEAL` | `#008074` | `#B4F5E9` | `#67C9BA` | `#004B43` |
| `BLUE` | `#2775BD` | `#DCE9FF` | `#85BCFF` | `#244467` |
| `PURPLE` | `#8360BA` | `#F1E3FF` | `#CBA9FF` | `#4B3A65` |
| `PINK` | `#AE5385` | `#FFE0EC` | `#FF98CE` | `#61334C` |
| `BROWN` | `#92694D` | `#FFE3D1` | `#DFAF8E` | `#5C3B21` |
| `GRAY` | `#6F7277` | `#E6E8EE` | `#B6B9BE` | `#3F4247` |

Values were generated in the HCT color space (equal tone per role), so all colors look equally strong.

## Typography

Font: **Manrope** (SIL OFL 1.1), static instances 400, 500, 600, 700, 800 bundled in `composeResources/font`.
Numbers in amounts, times and counters use tabular figures (`fontFeatureSettings = "tnum"`).

| M3 role | Size / line height | Weight | Letter spacing | Use |
| --- | --- | --- | --- | --- |
| `displaySmall` | 34 / 40 | 800 | −0.02 em | Big amounts (Budget hero) |
| `headlineLarge` | 32 / 38 | 800 | −0.02 em | Top-level screen title ("Сегодня", "Задачи") |
| `headlineSmall` | 22 / 28 | 800 | −0.01 em | Detail screen title, hero card title |
| `titleLarge` | 18 / 24 | 700 | 0 | Section header on a screen |
| `titleMedium` | 16 / 22 | 700 | 0 | Emphasized row title, card title |
| `bodyLarge` | 16 / 24 | 500 | 0 | Inputs, shopping items |
| `bodyMedium` | 15 / 22 | 600 | 0 | List row title |
| `bodySmall` | 13 / 18 | 500 | 0 | Secondary row text, hints |
| `labelLarge` | 14 / 20 | 700 | 0 | Buttons, chips, group labels |
| `labelMedium` | 12 / 16 | 600 (700 selected) | 0 | Navigation labels, small chips |
| `labelSmall` | 11 / 14 | 700 | 0.04 em, uppercase | Date-tile month |

Respect the system font scale; layouts must survive 130 % with Russian strings.

## Shape, spacing, elevation

- Shapes: `extraSmall` 8 dp (tag chips), `small` 14 dp (inputs, icon tiles, date tiles), `medium` 16 dp,
  `large` 20 dp (cards, list groups, FAB), `extraLarge` 28 dp (bottom sheets, top corners). Hero card 24 dp.
  Buttons and filter chips: fully rounded (pill).
- Spacing tokens (`Spacing`): 4, 8, 12, 16, 24, 32 dp. Screen side padding 16 dp; title block 20 dp;
  gap between sections 24 dp; between a section header and its card 10 dp; row padding 14 × 16 dp.
- Elevation: flat cards (no shadow). Shadows only for the FAB, the hero card (soft, tinted) and floating map controls.

## Icons

**Material Symbols Outlined, weight 250**, optical size 24, grade 0 (Apache 2.0).
Default state outlined (`FILL 0`); the selected navigation item uses the filled variant (`FILL 1`).
Sizes: 24 dp standard, 20 dp inline in chips and meta lines, 16 dp inside 13 px text.

Weight 250 exists only in the variable font, so icons are generated: a script in `tools/design-assets/` instantiates the
variable font at `wght=250, opsz=24, GRAD=0` (FILL 0 and 1) with fonttools and writes Android XML vector drawables
(`ic_<name>.xml`, `ic_<name>_filled.xml`) for the icons listed in `tools/design-assets/icons.txt`. Generated files are
committed; adding an icon = add its name to the list and rerun. Do not use `material-icons-core` in new UI.

Icon names in use (Material Symbols): `home`, `task_alt`, `shopping_cart`, `calendar_month`, `apps`, `location_on`,
`account_balance_wallet`, `group`, `sell`, `settings`, `notifications`, `cloud_done`, `cloud_off`, `cloud_upload`,
`error`, `add`, `check`, `search`, `tune`, `repeat`, `schedule`, `cake`, `more_vert`, `arrow_back`, `chevron_left`,
`chevron_right`, `expand_more`, `history`, `person_add`, `shield_person`, `schedule_send`, `qr_code_scanner`, `lock`,
`language`, `devices`, `help`, `push_pin`, `layers`, `my_location`, `pause`, `school`, `restaurant`, `directions_car`,
`beach_access`, `swap_horiz`, `event_repeat`, `local_grocery_store`, `battery_3_bar`, `battery_5_bar`, `battery_6_bar`;
added in M0-07: `visibility`, `visibility_off`, `close`, `logout`, `delete`, `info`, `qr_code_2`, `content_paste`, `person`,
`palette`, `sync`.

## People

- Every person (member or relative) has a **personal color** from the palette (FND-034). It is assigned automatically
  on creation (least-used color in the household, ties by palette order) and can be changed in the profile.
- Avatar: photo when set (FND-033, M5b), else initials (first letter of the name; two letters only if two persons
  share a first letter) in white on the personal color. With a photo, a 2 dp ring in the personal color.
- The personal color also marks the person's calendar events (dots, side bar), map pins and assignee avatars.
  It is never used for UI state.

## Navigation

Bottom navigation bar (FND-064, Q-013): **Сегодня · Задачи · Покупки · Календарь · Ещё**.
Sections the member has no access to are hidden (FND-028); "Сегодня" and "Ещё" are always present.
"Ещё" opens a screen with the profile card, tiles for Карта, Бюджет, Семья, Метки (only those allowed) and a list:
settings, notifications, sync, devices, help.

M3 `NavigationBar`: container `surfaceContainer` with a 1 dp `divider` top border, indicator pill 56 × 32 dp in
`primaryContainer`, selected icon filled in `onPrimaryContainer`, selected label `onSurface` 700, others
`onSurfaceVariant` 600.

## Component patterns

- **Screen header (top level):** date or context line (`bodySmall` 600, `onSurfaceVariant`) above the title
  (`headlineLarge`); actions on the right: sync indicator and own avatar on "Today", search/filter elsewhere.
- **Detail top bar:** back arrow, `headlineSmall` title, up to two actions.
- **List group:** white card, radius 20, rows 14 × 16 dp, divider inset to the text start.
- **Task checkbox:** circle 24 dp, 1.5 dp border `onSurfaceVariant` (overdue: `error`); done = filled `primary` with a check.
- **Shopping checkbox:** rounded square 26 dp, radius 8 — different from tasks on purpose ("buy" vs "do").
- **Filter chips:** pill 36 dp; selected = `inverseSurface` fill; unselected = `outlineVariant` border.
- **Segmented control:** track `surfaceContainerHigh`, selected segment white with a hairline shadow.
- **Buttons:** primary = filled `primary`, pill, 52 dp in forms; tonal = `primaryContainer`; text buttons in
  `onPrimaryContainer`. The gradient is not a button style.
- **FAB:** 60 dp, radius 20, gradient, white icon; extended FAB 56 dp with label on list screens.
- **Date tile:** 48 × 52 dp, radius 14, `secondaryContainer`, day `titleLarge` 800, month `labelSmall`.
- **Progress bar:** 6–8 dp, rounded, track `divider`, fill `primary` (gradient only for the active shopping list);
  overspent = `error` on `errorContainer`.
- **Sync state (FND-043):** top-bar icon `cloud_done` when synced; when offline or pending a small chip with `cloud_off`
  or `cloud_upload` and the pending count; inside screens where it matters (shopping in a store) a `warningContainer`
  banner "Нет сети. Отметки сохранены и отправятся, когда связь появится."
- **Location privacy (principle 5):** the map sheet always shows "Вы делитесь местоположением" with a pause action
  when the member is sharing.
- **Map:** OpenStreetMap tiles from the admin-configured source (ADR-0006), avatar pins with a white 3 dp border,
  places as translucent circles with a dashed outline in `primary` (home) or `secondary` (others), a bottom sheet with
  the family list.

## App icon

Rounded square with the lake gradient and a white outlined house containing two figures (see `Login.dc.html` mockup).
Android adaptive icon: gradient background layer, white foreground glyph; monochrome layer = the glyph.
