---
id: M4-10
status: ready
depends_on: [M4-09, M4-05]
requirements: [LOC-005, LOC-006, LOC-015, LOC-016, LOC-017, LOC-018, LOC-021]
---
# M4-10: Alerts, visibility rules, «Семья сейчас», person page, settings, notification taps, manual, M4 wrap-up

## Goal

Members turn on arrive / leave alerts for the people they see; every member sees who can see them and who gets alerts
about them; administrators edit «кто кого видит» and the history period; Today shows «Семья сейчас»; the person page
shows «Где сейчас»; Settings › Notifications has the location switches; taps on location notifications open the map
at the member; the user manual "Map" exists. All LOC MVP requirements are done.

## Read first

- [Location design](../../architecture/location.md) § UI (Member card alerts, Settings › Геопозиция, Family ›
  Геопозиция, Today, Person page, Notification taps, Settings › Notifications)
- [ADR-0025](../../adr/0025-location-visibility-rules.md)
- [Location spec](../../product/features/location.md) LOC-005, LOC-006, LOC-015…LOC-018, LOC-021, scenarios 4, 9, 10,
  § Permissions and privacy
- Code: `ui/main/TodayViewModel.kt` (`build`), `ui/main/TodayUpcoming.kt` (a Today block to copy),
  `ui/family/EditorViewModels.kt` (`PersonViewModel`, optional repositories as the last parameters),
  `ui/family/EditorScreens.kt` (`PersonRoute`), `ui/family/FamilyLogic.kt`, `ui/family/MemberViewModels.kt`
  (`adminCall`, online-only admin pattern), `FamilySettingsRoute`, `ui/settings/NotificationSettings.kt`,
  `platform/PendingNavigation.kt` (`NavTarget`, `submit`), `ui/Navigation.kt` (`NotificationTaps`), the M4-07…M4-09
  `ui/location/` package; manual `docs/user/en/calendar.md` and `ru/calendar.md` (structure)

## Scope

- Member card: «Сообщать: прибытие и уход» → a sheet with the places as check rows (`PlacesWriter.setAlert`); the place
  sheet: «Сообщать мне» with the members I see as check rows (writes each member's row).
- Settings › Геопозиция: «Кто меня видит» (from `watchers()`: names with «где сейчас» / «с историей»; «Администраторы
  видят всех» note) and «О вас сообщают» («Мама — Школа №12, Дом»). Online only; offline «Доступно, когда есть связь».
- `LocationRulesDestination`, `LocationRulesScreen.kt`, `LocationRulesViewModel.kt`, `LocationRulesLogic.kt` (pure:
  sentences, editing the list); entry «Геопозиция: кто кого видит» in Family settings (administrators).
- `HistoryDaysChoice` on the same screen («Хранить историю: 7 · 14 · 30 · 60 · 90 дней», `api.updateHousehold`).
- Today block `TodayFamilyNow.kt` after «Скоро»; `TodayViewModel` gets `LocationRepository` (optional, last parameter).
- Person page «Где сейчас» row (+ «История» with HISTORY) for members the viewer sees; `PersonViewModel` gets
  `LocationRepository` (optional, last).
- Settings › Notifications: switches «Места: прибытие и уход» (`location.place`), «Передача геопозиции выключена»
  (`location.sharing`, administrators only).
- `PendingNavigation`: `("location", memberId)` → `NavTarget.MapMember(memberId)`; `NotificationTaps` opens
  `MapDestination(memberId)`; the persistent notification tap (M4-07) uses the same target.
- Manual `docs/user/en/map.md`, `docs/user/ru/map.md`, `docs/user/README.md` "Available"; CHANGELOG `[Unreleased]`.
- M4 wrap-up: spec status `approved` stays until acceptance; every LOC MVP requirement `done`; STATUS (M4 acceptance
  next with the checklist), plans README.

Out of scope: trackers (TRK), LOC-023.

## Decisions (do not revisit)

- Alerts sheet rows: places by name; the row for a place the member is in now shows «сейчас здесь». Saving writes one
  derived row per (member, me); turning all off writes an empty set.
- «Кто меня видит» lists members only (no rules): name + level text; the viewer's admins are included with «с
  историей».
- Rules screen: list of sentences built by `LocationRulesLogic.sentence(rule, names)` — «Взрослые видят всех · с
  историей», «Дети видят: Взрослые · где сейчас», «Бабушка видит: Аня · где сейчас»; tap → edit sheet: «Кто» (Все /
  groups / members chips), «Кого» (Все / groups / members), «Что видно» (segmented «Где сейчас · С историей»), «Удалить»;
  «Добавить правило»; the note «Администраторы всегда видят всех с историей. Каждый видит свою историю.»; changes are
  saved with one `PUT` on «Сохранить» (the whole list), errors shown per row; offline the screen is read-only with the
  admin offline text.
- Today «Семья сейчас»: title row «Семья сейчас» + «Карта»; up to 4 rows (members I see, not me, sharing or paused, by
  name): avatar 32 dp, name, status line (`LocationTexts.status`, short form without battery); the block is hidden
  without `location.view` or when nobody is visible. A tap opens `MapDestination(memberId)`. Data from
  `observeNow()` only while Today is visible (the flow's `WhileSubscribed`).
- Person page: «Где сейчас» `ListRow` with the status line; tap → Map at the member; «История» row with HISTORY level.
  Not shown for relatives (persons without a member) or members the viewer cannot see.
- Manual sections (en, mirrored ru): Where to find the map; Turning on sharing (permissions, battery, brand tips);
  What others see; Pause (who may); Обновить; Places; Arrive and leave notifications; History; Who sees whom
  (administrators); Addresses and privacy (what goes to the address service, how the administrator turns it off); Who
  may do what (Adults / Kids table); Not yet (trackers, offline maps).

- Today and the person page expose location as a separate `familyNow` / `whereNow` `StateFlow` with
  `stateIn(viewModelScope, WhileSubscribed(5_000))`, collected with `collectAsStateWithLifecycle` — `TodayViewModel.state`
  and `PersonViewModel` use `Eagerly`, and folding `observeNow()` into them would keep the location socket open in the
  background. Addresses for both through `LocationRepository.address()` in the view models (members not in a place and
  not moving).
- The person page also gets «Сообщать: прибытие и уход» (opens the same alerts sheet) for members the viewer sees.
- History period: the screen shows `config().historyDays`; saving calls `api.updateHousehold(locationHistoryDays)` and
  then refreshes `config()` (the local `household` table has no such column).
- Settings › Notifications: `PermissionRow` there is private — copy the pattern, do not reuse it;
  `NotificationSettingsRepository` lives in `notifications/Notifications.kt`.

## Steps

1. Alerts UI on the member card and the place sheet.
2. Settings › Геопозиция watchers sections.
3. Rules screen and history period.
4. Today block, person page, notification switches, taps.
5. Manual, CHANGELOG, wrap-up docs.
6. Tests:
   - `LocationRulesLogicTest` (LOC-006): sentences in ru/en, add / edit / delete, validation errors mapped to rows.
   - `LocationRulesViewModelTest`: load, save one PUT, `VALIDATION_FAILED` per row, offline read-only, history days.
   - `TodayViewModelTest` additions (LOC-016): block rows, hidden without permission, order and limit 4.
   - `FamilyViewModelTest` additions for `PersonViewModel` (LOC-017): row shown only for visible members, history only with HISTORY.
   - `AlertsSheetTest` (LOC-021): writes the derived row, empty set off.
   - `MainAndSettingsViewModelTest` additions (LOC-018): admin-only switch.
   - `notifications/NotificationsTest` additions (the `location` target in `PendingNavigation`).
7. Emulator walkthrough by the `emulator-tester` agent (`seed.py family`, `seed.py location`; ru/en, light/dark):
   spec scenario 4 (alert for Аня on «Школа №12», then `seed.py move Аня` out and in → the notification, tap opens the
   map at Аня; Аня's watchers as Аня via a login code; `seed.py move` uses the stored seed sessions), scenario 9 (a rule for a member, history 14 days), scenario 10
   (Today block), the person page row, both notification switches.

## Acceptance

- `tools/dev/verify.sh` full gate green.
- Walkthrough without open bugs; screenshots for the handoff: Map, history, place editor, rules, Today block (ru, light
  and dark).

## Docs to update

- Spec: every LOC MVP requirement `done` with progress notes; features README unchanged (status `approved` until the
  customer's acceptance).
- Location design "Implementation notes (M4-10)"; roadmap § M4 "Implemented: …"; user manual; CHANGELOG; STATUS.md (M4
  acceptance next; checklist in the handoff), plans README status, history.md.

## Stop and report if

- A rule edit needs to express "deny" (it cannot, ADR-0025) to match what the customer asked during the walkthrough —
  that is a product question.

## Handoff

Russian summary: можно включить уведомления «прибытие / уход» на нужные места, каждый видит, кто видит его и кто
получает о нём уведомления, администратор настраивает «кто кого видит» и срок истории; на «Сегодня» — «Семья
сейчас»; на странице человека — «Где сейчас»; есть инструкция «Карта». M4 готов к приёмке: checklist for the customer
(install on the family's phones, turn on sharing everywhere, a school day with alerts, pause, «Обновить», a kid turning
location off, battery over a day on Xiaomi / Samsung if available).
