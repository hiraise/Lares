---
id: M3-05
status: done
depends_on: [M3-04]
requirements: [CAL-004, CAL-005, CAL-006, CAL-008, CAL-009, CAL-014, CAL-015, CAL-017, TAG-003, FND-064]
---
# M3-05: Calendar UI — the Calendar tab (month, list, filters) and the event page

## Goal

The «Календарь» tab replaces its placeholder: the month grid with personal-color dots and the chosen day's list, the
«Список» view, filters by person, tag and «Показывать задачи», and the event page with «Напомнить мне» and deleting one
occurrence, the following ones or all. «Неделя» shows a placeholder until M3-07; the editor opens a stub until M3-06.

## Read first

- [Calendar spec](../../product/features/calendar.md) — whole
- [Calendar design](../../architecture/calendar.md) § UI, § Models and views, § Writer
- [Visual style](../../design/visual-style.md) § Principles, § Colors (`primary` = today, `secondary` = date tiles),
  § Palette and personal colors, § Component patterns, § Icons; mockup `docs/design/mockups/Calendar.dc.html` (header,
  segmented control, weekday row, 44 dp cells, 32 dp day circle, 5 dp dots, day list with «Весь день» and the time
  column, FAB) — the bottom bar as in the app
- [Conventions § Client UI](../../process/conventions.md#client-ui)
- Code — tasks/shopping UI are the template: `ui/main/MainScaffold.kt` (`MainTab.CALENDAR → SectionPlaceholder`,
  `MainNavigation`), `ui/Navigation.kt` (destinations, `MainNavHost`), `ui/tasks/TasksScreen.kt` (filters sheet),
  `TaskDetailScreen.kt`/`TaskDetailViewModel.kt` (facts list, menu, `gone`), `TaskComponents.kt` (`TaskRow`,
  `TaskCheckbox`, `TagChips`, `AssigneeAvatars`), `ui/tasks/ReminderSheet.kt` (`EditorSheet`, `ReminderOptions`),
  `ui/components/*` (`ScreenHeader`, `SegmentedControl`, `ChoiceDialog`, `ConfirmDialog`, `Avatar`, `AppFilterChip`,
  `GradientFab`, `EmptyState`, `ListGroup`), `ui/common/DateTime.kt`, `ui/tasks/TasksLogic.kt` (day/time/rule texts),
  `platform/Preferences.kt` (`AppPreferences`); tests `jvmTest/…/ui/TaskUiFixture.kt`, `TasksViewModelTest.kt`,
  `TasksUiTest.kt`, `UiTestCore.kt`
- Client core from M3-04: `data/calendar/*`

## Scope

- Package `ui/calendar/`: `CalendarLogic.kt` (pure texts and formatting), `CalendarComponents.kt` (`MonthGrid`,
  `DayCell`, `DayList`, `EventRow`, `BirthdayRow`, `MemorableRow`, `CalendarTaskRow`, `DateTile`), `CalendarViewModel.kt`
  + `CalendarScreen.kt` (`CalendarRoute`), `CalendarFilterSheet.kt`, `EventViewModel.kt` + `EventScreen.kt`
  (`EventRoute`), `ScopeDialog.kt` (`RepeatScope { THIS, FOLLOWING, ALL }` + the three-option dialog).
- Routes `EventDestination(id: String, occurrence: String? = null)` (ISO date) and a stub
  `EventEditorDestination(id: String? = null, occurrence: String? = null, scope: String = "ALL", date: String? = null,
  hour: Int? = null, memorable: Boolean = false, personId: String? = null, tagId: String? = null)` that shows
  `SectionPlaceholderPage(Section.CALENDAR)` until M3-06; `MainNavigation.onEvent(id, occurrence?)`,
  `onEventEditor(args)`; `MainScaffold` `CALENDAR → CalendarRoute`.
- `AppPreferences.calendarView: String` (`month` default, `week`, `list`).
- Icons (add to `tools/design-assets/icons.txt`, visual-style § Icons, regenerate): `today`, `filter_list`,
  `celebration`, `event`, `event_busy` (check `icons.txt` first; skip those already there).
- Strings en/ru for everything below (plurals for ages, years and counts).

Out of scope: the week grid (M3-07), the editor (M3-06), Today/tag page/person page/settings/taps (M3-08).

## Decisions (do not revisit)

- **Tab:** `ScreenHeader(title)`: «Октябрь 2026» (month of the shown period; in «Список» the month of the first visible
  day), actions: ‹ and › (`chevron_left/right`; previous/next month or week; hidden in «Список»), `today` (only when the
  shown period or list position is not today), `filter_list` (badge dot when any filter is active). Under it
  `SegmentedControl` «Месяц · Неделя · Список» (stored in `AppPreferences.calendarView`). Active filters as
  `AppFilterChip`s with a `close` trailing icon («Тимур», «Метка: Школа», «Без задач»). `GradientFab(add)` for
  `calendar.create` → editor for the selected day (month), today (list).
- **Month:** `HorizontalPager` (initial page = current month at index 6000) of `MonthGrid`s; header row «Пн … Вс»
  (`weekdays_short`, Monday first, Sat/Sun in `onSurfaceVariant`); 6 rows of `DayCell` (44 dp high: 32 dp circle with the
  number `titleSmall` 600 — today filled `primary` with `onPrimary` text, selected (not today) 1.5 dp `primary` outline,
  other month 50 % alpha; under it up to 3 dots 5 dp, 3 dp apart, in `PaletteColor.tones().base` or `primary` for
  `null`, and a «+» glyph 8 sp for `more`). A tap selects the day; paging keeps the day of month when it exists, else
  the 1st. Under the grid the `DayList` of the selected day: header «Пятница, 23 октября» (`date_with_weekday`,
  «Сегодня, 23 октября» for today), then the rows; empty → «Ничего не запланировано» + `LinkButton` «Добавить» (with
  `calendar.create`).
- **List («Список»):** `LazyColumn` of non-empty `DayView`s from `observeDays(today, today + 90)`; when the last 10 rows
  become visible the range grows by 90 days (up to 2 years); a `LinkButton` «Показать прошлые» on top prepends 30 days
  each tap. Sticky-like day headers (`SectionHeader` style `titleSmall`) «Сегодня», «Завтра», «Суббота, 24 октября»,
  with the year when not the current one. Empty → `EmptyState(event, «Событий пока нет»)`.
- **Rows** (`ListGroup` per day, `ListDivider` between rows; min height 56 dp):
  - `EventRow`: 3 dp rounded side bar in the first participant's `tones().base` (`primary` for the whole family); time
    column 52 dp — timed «17:30» (`labelLarge` 600) over «18:45» (`bodySmall`, `onSurfaceVariant`), all-day «Весь день»,
    continuation «до 20 июля» / «с 10 июля»; title `bodyLarge` with a 14 dp `repeat` icon for repeating series; meta line
    `bodySmall` = participant first names joined «, » (or «Вся семья»), « · » place; trailing `AssigneeAvatars`-style
    avatars of participants (24 dp, max 3). A tap → `EventDestination(id, occurrence)`.
  - `BirthdayRow`: `cake` 24 dp in the person's color, «Бабушка Валя, 78 лет» or «День рождения: Дядя Коля», meta =
    relation. Tap → `PersonDestination(personId)`.
  - `MemorableRow`: `celebration` 24 dp in the first participant's color, «Годовщина свадьбы · 16 лет», meta = names.
    Tap → event page.
  - `CalendarTaskRow`: `TaskCheckbox` (ticks with the tasks snackbar/undo, `TaskTicker` like Today), title, meta
    «Задача · Дмитрий» (assignees or «Кто угодно») and «Просрочено» in `error` for overdue; tap → task page.
- **Filter sheet** (`EditorSheet` «Фильтр»): «Чей календарь» — chips «Все» + every person (avatar 20 dp + first name),
  single choice; «Метка» — chips «Любая» + tags in use (`TagChip`); `CheckRow` «Показывать задачи» (only with
  `tasks.view`); «Сбросить» `LinkButton`. The filter lives in the view model (not persisted).
- **Event page** (`AppPage` + `DetailTopBar(title = "", onBack)`; actions for `canEdit`: `edit`, `more_vert` menu with
  «Удалить»): title `headlineSmall`; a «Изменено только это повторение» `NoticeBanner`-style note (`info` icon) when the
  occurrence has an override; facts `ListGroup` of `ListRow`s with icons:
  `calendar_month` when («Вторник, 28 октября · 16:00–17:00», all-day «Вторник, 28 октября · весь день», multi-day
  «10 июля – 20 июля»), `repeat` rule («Каждую неделю: пн, ср · до 31 мая» — reuse `TasksLogic.rule` texts + «до …»),
  `location_on` place, `group` participants (avatars + names; «Вся семья»), `sell` tags (`TagChips`), `notes`,
  `alarm` «Напомнить мне» (supporting = the effective choice: «За 1 час», «Нет»; «(как в событии)» suffix when no own
  row) → sheet with «Как в событии» (only if the event has a reminder that applies to me) + the same options as the
  editor (`EventReminderOptions` in `CalendarLogic`: timed none/0/5/10/15/30/60/120/1440/2880/10080, all-day
  none/0/1440/2880/10080 — 0 for all-day = «В день события в 9:00») → `setMyReminder`/`clearMyReminder`; asks for
  notification permission like the task editor), `person` «Создал(а) Ольга». Memorable dates: when = «12 июня · каждый
  год», «16 лет» (if `countYears`), no reminder row (birthday setting applies — supporting text «Как дни рождения,
  в Настройках»).
- **Edit / delete scope:** one-off → edit opens the editor (scope ALL); delete → `ConfirmDialog(destructive)` «Удалить
  событие «Стоматолог»?». Repeating → `ScopeDialog` titled «Изменить повторяющееся событие» / «Удалить повторяющееся
  событие» with radio options «Только это», «Это и следующие», «Все» and «Продолжить» / «Удалить» → editor with the scope
  and occurrence, or `cancelOccurrence` / `deleteFollowing` / `delete`. After delete «Все» / a whole one-off → close the
  page; after «Только это» → close the page with snackbar «Повторение удалено». An event deleted elsewhere → close
  (`gone`).
- `CalendarViewModel(repository, tasksWriter, preferences, clock)`: state = view mode, shown month / week start,
  selected day, list range, filter, month cells, day list, list days, `canCreate`, `persons`, `tagsInUse`. Pure state
  building in `companion fun`s, like `TodayViewModel.build`.

## Steps

1. Logic and components with previews-free unit tests (`CalendarLogicTest`: every text form above, ru plurals «1 год,
   2 года, 5 лет», all-day / multi-day / continuation texts, reminder option lists).
2. Tab, view model, filter sheet; month and list; navigation and stub editor route.
3. Event page, scope dialog, reminder sheet.
4. Icons, strings.
5. Tests: `CalendarUiFixture` (Moscow, the seed-like data set of M3-04 in memory); `CalendarViewModelTest` (mode
   persistence, paging, selected day, filter combinations, list range growth, Kid without create → no FAB);
   `CalendarUiTest` (compose: month grid shows dots and today, tapping a day shows its rows, a birthday row opens the
   person, list headers «Сегодня»/«Завтра», filter chip removal, «Показывать задачи» off hides tasks);
   `EventScreenTest`/`EventViewModelTest` (facts for timed, all-day, multi-day, repeating with until, memorable; my
   reminder sheet writes the row; scope dialog → right writer call for each option; Kid sees no edit/delete).

## Acceptance

- `tools/dev/verify.sh :client:shared:jvmTest --tests '*Calendar*' --tests '*Event*'` while iterating; full gate at the end.
- Emulator walkthrough by the `emulator-tester` agent after `server.sh reset`, `seed.py family`, `seed.py tasks`,
  `seed.py calendar`: spec scenarios 2 (cancel one «Танцы», see it gone; «Это и следующие» delete), 3 (birthday row and
  age), 4, 5 (holiday on several days), 6 (dots, day list, filter Тимур), 8 (task ticked in «Список»), in ru and en,
  light and dark; compare with `Calendar.dc.html`.

## Docs to update

- Spec progress notes (*UI: …*) under the requirements covered; calendar design § UI if a detail changed; visual-style
  § Icons.
- STATUS.md; plan status.

## Stop and report if

- `HorizontalPager` in the Compose Multiplatform version in use cannot keep the grid's state across pages without
  recomposing all 42 cells on every frame (then report before replacing it with plain chevrons).

## Handoff

Russian summary: the Calendar tab works — month with colored dots and the day's plan, the list of upcoming days with
tasks, filters by person and tag, the event page with "remind me" and deleting one, following or all repeats. The week
view and the editor come next (M3-06, M3-07). Next M3-06.
