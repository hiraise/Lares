---
id: M4-09
status: done
depends_on: [M4-08]
requirements: [LOC-004, LOC-005, LOC-014, LOC-020, LOC-024]
---
# M4-09: History and places — day track with stops, place list, place editor with address search

## Goal

Those allowed see a member's day on the map — the track and the stops with times and places or addresses — for any day
the family keeps. Members with `location.manage_places` create, change and delete places by tapping the map, «Я здесь»
or an address search; everybody with the Map sees the place list and who is where.

## Read first

- [Location design](../../architecture/location.md) § UI «History», «Places»; § Contract (`Stops`, `PlaceData`
  limits); § Client Core (`history`, `search`, `PlacesWriter`)
- [ADR-0026](../../adr/0026-addresses-through-a-server-geocoder.md) (search on explicit submit only)
- [Location spec](../../product/features/location.md) LOC-004, LOC-005, LOC-020, LOC-024, scenarios 4 (place part), 5
- Code: the M4-08 `ui/location/` package (`FamilyMapView`, `MapViewState.track/stops/draftPlace`, `MapViewModel`),
  `ui/calendar/CalendarScreen.kt` (horizontal day / week strips), `ui/components/Fields.kt`, `ui/components/Dialogs.kt`
  (`ConfirmDialog`), `ui/tasks/TaskEditor*` (editor ViewModel + form pattern), `ui/common/DateTime.kt`

## Scope

- `LocationHistoryDestination(memberId, date: String? = null)`, `HistoryScreen.kt`, `HistoryViewModel.kt`,
  `HistoryLogic.kt` (pure: stop rows, day strip, camera bounds).
- `PlacesSheet.kt` (list from the «Места» button), `PlaceSheet.kt` (one place: name, address, who is there now, edit /
  delete), `PlaceEditorDestination(id: String? = null, lat: Double? = null, lon: Double? = null)`,
  `PlaceEditorScreen.kt`, `PlaceEditorViewModel.kt`, `PlaceForm.kt` (pure).
- Map interactions: a tap on a place circle / chip opens `PlaceSheet`; a long press on the map (managers only) opens the
  editor at that point; the member card's «История» opens the history.
- Strings en/ru; icons for `PlaceIcon` (add `work`, `fitness_center` if missing).

Out of scope: alerts on places (M4-10), Today and person page (M4-10).

## Decisions (do not revisit)

- History: top bar «Аня · История»; a horizontal strip of day chips from today back to `config().historyDays` («Сегодня»,
  «Вчера», «Пн, 20 окт.»); the map shows the track (member color) and stops; under it the stop list: «Школа №12 ·
  08:25–14:40», «Дома · с 16:50» (ongoing), address or «Без адреса» for stops outside places (address via
  `LocationRepository.address`, one per stop); a tap on a stop centres it; empty day «Нет данных за этот день»;
  `FORBIDDEN` / `NOT_FOUND` → closes with «Нет доступа к истории» (`UiNotices`). Offline → «История доступна, когда есть
  связь».
- Camera: fit the track bounds with 48 dp padding; a single point → zoom 16.
- Place editor: fields «Название» (required), icon chips (Дом, Школа, Работа, Спорт, Магазин, Место), the map with the
  draft circle (a tap moves the centre), «Я здесь» (the newest own fix, else hidden), radius slider 50–1000 m step 10
  with «150 м» label, «Адрес» search: a field + «Найти» button (IME action search), up to 5 results, a pick moves the
  centre and fills `address`; search hidden when `config().addresses` is false or the viewer lacks the permission.
  New place defaults: centre = the map centre when opened from the list (the tapped point after a long press), radius
  100, icon PLACE; the user picks the icon.
- Save through `PlacesWriter` (works offline); delete asks «Удалить место «Школа №12»? Подписки на него перестанут
  работать.»
- Place list rows: icon in a `secondaryContainer` circle, name, «Тимур, Аня здесь» or «Никого»; managers see «Добавить
  место».
- Members without `location.manage_places` see places read-only (no editor entry points).

## Steps

1. `HistoryLogic`, `HistoryViewModel`, `HistoryScreen`, navigation from the card.
2. `PlaceForm`, `PlaceEditorViewModel`, editor screen with search.
3. Places sheet, place sheet, map tap / long press wiring.
4. Tests:
   - `HistoryLogicTest` (LOC-004): stop rows texts (ongoing, address fallback), day strip length = `historyDays`, bounds.
   - `HistoryViewModelTest`: loading, empty day, forbidden → notice, offline text.
   - `PlaceFormTest` (LOC-020): validation (name required, radius bounds), changed fields only, «Я здесь».
   - `PlaceEditorViewModelTest` (LOC-024): search only on submit, results → centre and address, search hidden when off,
     offline save.
   - UI test: places sheet rows, editor fields, history list (JVM stand-in).
5. Emulator walkthrough by the `emulator-tester` agent (`seed.py family`, `seed.py location`; ru/en, light/dark): spec
   scenario 5 for Аня (stops «Дома», «Школа №12», «Дома с …»; times are relative to the seed run); `ui.sh type` is
   ASCII only, so create a place named `Basseyn` by the address search `Leningradsky prospekt 39`, by long press and by
   «Я здесь» (after `emu.sh geo`); change the radius; delete; as a kid: no editor, history of an adult refused.

## Acceptance

- `tools/dev/verify.sh :client:shared:jvmTest --tests '*History*' --tests '*Place*'` while iterating; full gate at the
  end. Walkthrough without open bugs.

## Docs to update

- Location design "Implementation notes (M4-09)"; spec progress notes; STATUS.md; plan status; CHANGELOG
  `[Unreleased]`.

## Stop and report if

- The map library cannot report long presses with coordinates (then: a «Добавить место здесь» button at the map centre
  — report before switching).

## Handoff

Russian summary: можно посмотреть день человека — маршрут и остановки «Школа №12 · 08:25–14:40»; места создаются
нажатием на карту, кнопкой «Я здесь» или поиском адреса, размер круга меняется ползунком. Screenshots. Next M4-10.
