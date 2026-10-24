---
id: M3-08
status: ready
depends_on: [M3-07, M3-03]
requirements: [CAL-004, CAL-005, CAL-006, CAL-007, CAL-016, CAL-018, CAL-019, TAG-004, TSK-022, FND-052, FND-063]
---
# M3-08: Calendar everywhere — Today «Скоро», tag page, person dates, settings, notification taps, manual

## Goal

The calendar shows up across the app: «Скоро» on Today, the «События» section of the tag page, birthdays with age and
memorable dates on the person's page with «Добавить памятную дату», the notification switches and the birthday reminder
choice in Settings, and taps on event, birthday and push notifications open the right page. The user manual
"Calendar" (en, ru) and the M3 wrap-up close the milestone.

## Read first

- [Calendar spec](../../product/features/calendar.md) CAL-004, CAL-005, CAL-006, CAL-007, CAL-016, CAL-018, CAL-019,
  § Tags
- [Calendar design](../../architecture/calendar.md) § UI (last bullet), § Reminders, § Notification settings
- [Tasks design § Notification taps](../../architecture/tasks.md#notification-taps) and § Today block and tag page
- Mockup `docs/design/mockups/Main.dc.html` — the «Скоро · Календарь» block (date tiles «26 окт», cake row «Бабушка
  Валя, 78 лет / Понедельник · через 3 дня», event row «Родительское собрание / Среда, 18:30 · Школа №12» with avatars)
- Code: `ui/main/TodayViewModel.kt`, `ui/main/Sections.kt` (`TodayScreen`, `TodayTasks`, `TodayActions`),
  `ui/main/TodayShopping.kt`; `ui/family/TagPage.kt`; `ui/family/EditorScreens.kt` (`PersonRoute`),
  `EditorViewModels.kt` (`PersonViewModel`); `ui/settings/NotificationSettings.kt`; `platform/PendingNavigation.kt`
  (`NavTarget`, `TaskLinks`), `ui/Navigation.kt` (`NotificationTaps`), `ui/main/MainScaffold.kt`,
  `notifications/PushMessageHandler.kt`; `docs/user/en/tasks.md`, `docs/user/ru/tasks.md`, `docs/user/README.md`;
  tests `TodayViewModelTest.kt`, `TasksUiTest.kt` (Today block tests), `TagViewModelTest.kt`, `FamilyViewModelTest.kt`,
  `MainAndSettingsViewModelTest.kt`

## Scope

- Today: `TodayUiState.upcoming: TodayUpcomingUi?` (from `CalendarRepository.observeUpcoming()`, only with
  `calendar.view`), block `TodayUpcoming` after the tasks block; `TodayActions.onEvent`, `onPerson`, `onCalendar`.
- Tag page: «События» section after shopping (`observeForTag`), `TagRoute(onEvent)`.
- Person page (`PersonRoute`, existing persons only, `calendar.view`): under the birthday field a supporting line
  «Исполнится 78 · через 3 дня» (or «Сегодня 78 лет»); a «Даты» `ListGroup` with the person's memorable dates (rows open
  the event) and a `LinkButton` «Добавить памятную дату» (`calendar.create`) → editor with `memorable = true,
  personId`.
- Settings › Notifications: `SwitchableNotificationTypes` gains `EVENT_CHANGED`, `EVENT_REMINDER` after the task types;
  a `ListRow` «Дни рождения» (supporting = the choice) → `ChoiceDialog` «Не напоминать · Только в день · За 1 день · За 3
  дня · За неделю · За 2 недели» (0, 1, 3, 7, 14); «Не напоминать» mutes `BIRTHDAY_REMINDER`, the others unmute it and
  call `setBirthdayDaysBefore`.
- Notification taps: `NavTarget.Event(id, occurrence: LocalDate?)`, `EventGone`, `Person(id)`; `PendingNavigation.submit`
  maps `"event"` (entity id `"<id>"` or `"<id>:<date>"`) and `"person"`; `EventLinks` (like `TaskLinks`, one
  `syncNow()` if missing); `NotificationTaps` opens `EventDestination` / `PersonDestination`, or switches to the Calendar
  tab with «Событие удалено».
- Manual `docs/user/en/calendar.md`, `docs/user/ru/calendar.md` (+ README index); CHANGELOG "Unreleased" entry;
  roadmap M3 note; TSK-022 → `done`.

Out of scope: anything new in the Calendar tab, the editor or the week view.

## Decisions (do not revisit)

- **Today «Скоро»** (`testTag("today_upcoming")`): `SectionHeader(«Скоро»)` + `LinkButton` «Календарь» (opens the tab);
  a `ListGroup` of at most 5 rows + «Ещё N» row (opens the tab in «Список»). Row: `DateTile` 44 dp (radius 12,
  `secondaryContainer`, «26» `titleSmall` 700 over «окт» `labelSmall`; today's tile in `primaryContainer`), title
  (`bodyLarge`; birthdays «Бабушка Валя, 78 лет» with a 16 dp `cake` before it), supporting «Понедельник · через 3 дня»
  / «Сегодня, 16:00 · Клиника на Ленина» / «Завтра, весь день»; trailing participant avatars (24 dp, max 3). Hidden when
  empty. Order on Today: shopping hero, tasks, «Скоро».
- **Tag page «События · N»** (`testTag("tag_events")`): rows with the `DateTile` and «Вторник, 28 октября · 16:00»;
  shown only when it has rows; the "nothing else" empty state also accounts for events.
- Relative day words («Сегодня», «Завтра», «через N дней» plural, weekday names) in `CalendarLogic` (M3-05), extended
  here if needed; all relative to the household-zone today.
- `PushMessageHandler` already passes the payload's entity; nothing else changes on the push path.
- Manual sections (en and ru, same structure as `tasks.md`): the three views and filters; creating an event (all-day,
  several days, participants, «Вся семья»); repeats and «Только это / Это и следующие / Все» (incl. "changing the
  whole schedule resets single changes", "your own reminder is not carried to the new part of a split series");
  reminders («Напомнить мне», who gets the event's reminder); birthdays (from the person's profile, ages, 29 February)
  and the birthday setting; memorable dates; tasks in the calendar; what notifications arrive; who may change what.

## Steps

1. Today block and tests (`TodayViewModelTest`: upcoming built, hidden without `calendar.view`; compose test for the
   block rows and «Ещё N»).
2. Tag page section and tests (`TagViewModelTest`).
3. Person page dates and tests (`FamilyViewModelTest` / a new `PersonDatesTest`: age line, memorable rows, add button
   rights).
4. Settings switches and birthday choice; tests in `MainAndSettingsViewModelTest` (choice → mute/unmute and days; the
   value survives a refresh).
5. Notification taps (`PendingNavigationTest` cases for event, event with date, person, deleted event) and Android
   reminder targets (`Reminders.target` from M3-04 already maps the ids).
6. Manual en/ru, README index, CHANGELOG, roadmap note, spec statuses (`done` for the CAL MVP requirements delivered,
   with progress notes).

## Acceptance

- Full gate `tools/dev/verify.sh`.
- Emulator walkthrough (`emulator-tester`) after `server.sh reset` + `seed.py family tasks shopping calendar`: Today
  «Скоро» against `Main.dc.html` (scenario 9); an event reminder (set «Напомнить мне» on an event starting in a few
  minutes) and a birthday reminder (choice «Только в день» with a relative born today, then the diagnostics reminder
  path) fire and their taps open the event and the person; the «Школа» tag page with the parents' meeting; the person
  page dates and «Добавить памятную дату»; Settings switches and the birthday choice; ru/en, light/dark. Push
  notifications (CAL-007) are covered by the server tests; on a real phone they wait for the deployed server (FND-051).

## Docs to update

- Spec: statuses and progress notes; spec `status: implemented` only after the customer's M3 acceptance (leave
  `approved`).
- [Foundation spec](../../product/features/foundation.md) FND-063 note (calendar block), FND-052 note (new switches).
- [Tasks spec](../../product/features/tasks.md) TSK-022 `done`; [tags spec](../../product/features/tags.md) TAG-002/003/004
  notes (calendar part).
- Calendar design if a detail changed; STATUS.md (M3 done, acceptance next), history.md; plan status.

## Stop and report if

- The push payload cannot carry the occurrence date within `PushPayload.MAX_BYTES` (it does not need to: taps from push
  open the next occurrence).

## Handoff

Russian summary for the customer, with the M3 acceptance checklist: what to try on the phones (scenarios 1–9 of the
spec), what notifications to expect, known limits (no private events, no other time zones, no Google/Apple calendar
sync yet). Next: M3 acceptance and the v0.4.0 release.
