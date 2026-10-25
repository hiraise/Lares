---
id: M4-08
status: ready
depends_on: [M4-07]
requirements: [LOC-002, LOC-003, LOC-009, LOC-010, LOC-011, LOC-012, LOC-013, LOC-014]
---
# M4-08: The Map screen — MapLibre, pins, places, family sheet, member card, «Обновить»

## Goal

More › Карта opens a real map: OpenStreetMap tiles in the light or dark style from the server, the places as circles,
a pin per visible member in their personal color with the accuracy circle, and the family sheet under it — the own
sharing row with «Пауза», then each member with place or address, how long ago and battery. A tap on a member centres
the map and opens their card with «Обновить». Updates arrive live; offline the last known positions show with «Нет
связи».

## Read first

- [Location design](../../architecture/location.md) § Map rendering, § UI «Map», «Member card»; § Client Core
  (`LocationRepository.observeNow`, `LocationTexts`)
- [ADR-0027](../../adr/0027-map-rendering-maplibre-compose.md), [ADR-0006](../../adr/0006-no-proprietary-dependencies.md)
- [Visual style](../../design/visual-style.md) § Colors (`secondary` for places and sharing), § Component patterns
  (Map, Location privacy); mockup `docs/design/mockups/Map.dc.html` (sizes and copy; visual-style wins)
- [Location spec](../../product/features/location.md) LOC-003, LOC-009, LOC-011, LOC-013, LOC-014, scenarios 2, 3, 6,
  7, 8
- maplibre-compose 0.20 docs: getting started, styling, layers, interaction (https://maplibre.org/maplibre-compose/)
- Code: `ui/Navigation.kt` (`SectionPageDestination` → `SectionPlaceholderPage`), `ui/main/MoreScreen.kt` (Map tile),
  `ui/main/Sections.kt`, `ui/components/Avatar.kt`, `ui/theme/Palette.kt` (`tones()`), `ui/components/Pickers.kt`
  (`NoticeBanner`), `ui/calendar/*` (a ViewModel + Route + Screen set to copy), `ui/UiModule.kt`,
  `gradle/libs.versions.toml`, `client/shared/build.gradle.kts`, `client/android/build.gradle.kts`

## Scope

- Dependencies (license check: BSD-3-Clause, BSD-2-Clause native; note in the handoff): `maplibre-compose = "0.20.0"`;
  `libs.maplibre.compose` in `client/shared` `androidMain`; `libs.maplibre.compose.runtime.opengl.android` `runtimeOnly`
  in `client/android`. Check with `./gradlew :client:android:dependencies` that no `com.google.android.gms` artifact
  appears.
- `ui/location/FamilyMap.kt` (expect), `androidMain/…/ui/location/FamilyMap.android.kt`, `jvmMain/…/FamilyMap.jvm.kt`
  exactly as the design § Map rendering; `MapViewState`, `MapCameraRequest(kind: ALL | MEMBER(id) | PLACE(id) |
  POINT(lat, lon, zoom))`.
- `ui/location/MapScreen.kt`, `MapViewModel.kt`, `MapLogic.kt` (pure: pins, rows, camera choice, battery icon),
  `MemberCard.kt`; `MapDestination(memberId: String? = null, placeId: String? = null)`; the More tile and
  `SectionPageDestination(MAP)` open it; the sharing row opens the M4-07 setup / pause sheets.
- Address resolution in the view model: for each visible row with a member not in a place and not moving, call
  `LocationRepository.address()` once per rounded position; show the distance to the nearest place while waiting or when
  null.
- Strings en/ru.

Out of scope: history (M4-09), place editor and list (M4-09), alerts on the card (M4-10), Today and person page (M4-10).

## Decisions (do not revisit)

- Layout from the mockup: map full screen; top row (16 dp margins): title pill «Карта» (48 dp, radius 24, surface,
  shadow) and a 48 dp round «Места» button (`push_pin`; opens the places list from M4-09 — in this plan a sheet with the
  place names that centres the map); `my_location` 48 dp rounded-square button above the sheet: first tap «Показать
  всех» (fit all pins), when all are shown «Моё местоположение» (centre on me). No «layers» button (assumption: the
  style follows the app theme).
- Family sheet: `BottomSheetScaffold`, peek height = sharing row + up to 3 member rows; expanded shows all. Sharing row:
  8 dp `success` dot + «Вы делитесь местоположением» (`secondary`, 13 sp bold) + outlined «Пауза» (32 dp) when sharing
  here; «Пауза до 15:30» + «Продолжить» when paused; «Включить передачу геопозиции» (tonal button) when not sharing here;
  «Не удаётся передать геопозицию» (`warning` text) when `STALE_UPLOADS`. Member rows: avatar 40 dp, name (15 sp bold),
  status line (`LocationTexts.status`, 13 sp `onSurfaceVariant`), battery icon + percent on the right (red `error` at
  ≤ 15 %, `battery_charging_full` while charging).
- Pins: own personal color; grey (`outline`) when paused, off, never-shared-but-had-a-fix or stale; members with no fix
  have no pin but a row. Accuracy circles only when accuracy ≥ 30 m. Place circles: HOME icon → `primary`, others →
  `secondary` (visual style).
- Member card (the sheet's content after a pin or row tap, back returns to the list): avatar, name, status line,
  «Точность 20 м», battery, «Обновить» (`location` level ≥ CURRENT and status SHARING; while waiting a progress
  indicator; done when a newer `last.recordedAt` arrives; after 90 s «Телефон не ответил»; `CONFLICT` → «На паузе»),
  «История» (HISTORY level; navigates to M4-09's destination — until then hidden), the camera centres on the member at
  zoom 16.
- Styles: `config().mapStyleUrl` / `mapDarkStyleUrl` by the current theme; without config (offline first run) a plain
  `surfaceVariant` background with «Карта появится, когда будет связь».
- Offline: `NoticeBanner` «Нет связи. Показаны последние известные места.»; POLLING shows nothing special.
- Accessibility: the map has a content description «Карта семьи»; every pin is also a row, so screen readers use the
  sheet; the tester navigates by rows.
- Without `location.view` the More tile is hidden (existing gating).
- `FamilyMapView` has `onMapLongClick` (used by M4-09) and the `MapViewState` fields of the design (`trackColor`,
  `MapStop`, `draftPlace`); place labels are text-only `SymbolLayer`s under the circles (no icon images).
- No own «Вы» row in the sheet (the mockup has none): the sharing row stands for the viewer, who has a pin.
- `MapViewModel` exposes the location state with `stateIn(viewModelScope, WhileSubscribed(5_000))`; `MapScreen` collects
  with `collectAsStateWithLifecycle`, so the live socket closes when the screen is not visible.

## Steps

1. Dependencies, license notes, `FamilyMapView` expect / actual (render the style, pins and places; clicks).
2. `MapLogic` (pure) and `MapViewModel` (observeNow, config, sharing state, addresses, refresh).
3. `MapScreen`, sheet, member card, navigation, More tile.
4. Tests:
   - `MapLogicTest` (LOC-003, LOC-013): pins and colors per status; rows order (by name, no own row); battery
     classes; camera choices; distance fallback text.
   - `MapViewModelTest` (LOC-011, LOC-014): refresh progress → done on a newer fix → timeout text after 90 s; `CONFLICT`
     text; addresses requested once per rounded position and never for members in a place or moving.
   - `MapUiTest` (JVM stand-in): sheet rows and statuses for the seeded family, tap a row opens the card, «Обновить»
     visible only when allowed, offline banner.
5. Emulator walkthrough by the `emulator-tester` agent (`seed.py family`, `seed.py location`, ru/en, light/dark;
   screenshots of the map in both themes): spec scenarios 2, 3 («Обновить» on Тимур, then `seed.py move Тимур …` answers as his sharing session), 6
   (pause from the sheet), 7 (`seed.py call PUT /location/me/sharing '{"status":"off_on_phone","reason":
   "location_disabled"}' --as Тимур` — the stored seed session is his sharing session), 8 (log in as a kid
   with `seed.py login-code Тимур`: only the adults are listed); airplane mode → «Нет связи».

## Acceptance

- `tools/dev/verify.sh :client:shared:jvmTest --tests '*Map*'` while iterating; full gate at the end.
- The debug APK size growth is noted in the handoff.
- Walkthrough report without open bugs.

## Docs to update

- Location design "Implementation notes (M4-08)"; visual-style § Map if the implemented sheet differs from the text.
- Spec progress notes (*UI: …*); STATUS.md; plan status; CHANGELOG `[Unreleased]` (Added: the map).

## Stop and report if

- maplibre-compose 0.20.0 does not resolve or compile with Kotlin 2.4.21 / CMP 1.12.2 / AGP 9.3.3 (try the Vulkan
  runtime only if the OpenGL one fails to render; any version change needs a new decision).
- The OpenFreeMap style renders no text (font names differ): report the style's `glyphs` and font stack.

## Handoff

Russian summary: в «Ещё › Карта» теперь настоящая карта: места кругами, члены семьи значками своего цвета, внизу список
— где каждый, как давно и сколько заряда; можно нажать на человека и «Обновить». Screenshots (light, dark). Next M4-09.
