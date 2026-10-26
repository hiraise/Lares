# Changelog

All notable user-visible changes. Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Versioning: [SemVer](https://semver.org/) from the first release.

## [Unreleased]

### Added

- Location sharing on Android (M4-07): Settings › «Геопозиция» turns on sharing with a short setup — precise location,
  «Allow all the time», notifications and battery saving with hints for Xiaomi, Huawei/Honor, Samsung, OnePlus/Oppo/
  Realme. While sharing, the notification «Вы делитесь геопозицией» is always shown; pause «На 1 час», «До утра» or
  «Пока не включу» for those allowed to pause, «Продолжить». Sharing continues after a restart of the phone, answers
  «Обновить», and tells the family when location is turned off or the permission is taken away. The map follows.

## [0.4.0] - 2026-10-25

### Added

- Family calendar (M3): the Calendar tab with «Месяц» (dots in each person's color, the chosen day's list), «Неделя»
  (a grid by hours, overlapping events side by side, a tap on a free hour creates an event there) and «Список»; filters
  by person and tag, and tasks with a due date ticked off right in the calendar.
- Events with times or all day, over several days, with participants or for the whole family, place, notes, tags and a
  reminder; repeats until a chosen day, changed or deleted «Только это», «Это и следующие» or «Все»; «Напомнить мне»
  for your own reminder on any event.
- Birthdays from the family's profiles with ages, memorable dates such as wedding anniversaries with the count of years,
  and reminders about them every morning from a few days before (Settings › Notifications › «Дни рождения»).
- Calendar everywhere (M3-08): «Скоро» on the Today screen, the «События» section on each tag's page, the next birthday
  and memorable dates with «Добавить памятную дату» on a person's page, switches for calendar notifications and event
  reminders; tapping an event notification or reminder opens the event, a birthday reminder opens the person. New user
  manual "Calendar" in English and Russian.

## [0.3.0] - 2026-10-24

### Added

- Each release now includes `lares-X.Y.Z.apk.sha256`, and the release notes show the APK file SHA-256, so you can check
  that the download is complete and unchanged.
- Shopping lists (M2-04): the Shopping tab with the family's lists and progress; on a list add several items in one line,
  items grouped by store department, tick them off in the store even without network, «Куплено» with clearing and
  undo; rename, grouping and delete in the list menu.
- Shopping suggestions and editing (M2-05): chips with what the list bought before under the empty field and matching
  names from all lists while typing; a long press opens the item sheet (name, quantity, note, department, tags, who
  added and bought) with «Убрать из списка» and undo; a department you correct is remembered for that product in all
  lists; tags on lists and items and a tag filter on the list page.
- Shopping everywhere (M2-06): the Today screen starts with the list you need now — its name, «6 из 9», what is left,
  who is in the store right now and «Открыть», plus «Ещё 2 списка» when other lists have something to buy. Each tag's
  page shows the items to buy and the lists with that tag; an item opens its list filtered by the tag. New user manual
  "Shopping lists" in English and Russian.

## [0.2.0] - 2026-10-24

### Added

- Tasks tab (M1-05): my tasks and all tasks by sections (overdue, today, upcoming, no date), chips «Мои», «Все»,
  «Поручено мной», tag filter, search and filters by assignee and state; tick a task off right in the list and undo it.
  Task page with all details, checklist, history of a routine, skip one occurrence, delete one task or the whole
  series.
- Task editor (M1-06): create and edit tasks — title, notes, date and time, repeat (every day, week, month, year, on
  chosen weekdays, or a set time after the last completion), assignees or «Кто угодно», tags (a new tag right from the
  editor), a reminder, «Сообщить мне, когда выполнят», and a checklist when creating.
- Tasks everywhere (M1-07): the Today screen shows your overdue and today's tasks, you tick them off right there, and
  «Добавить» creates a new task. Each tag has its own page with its tasks. Settings › Notifications can turn off task
  notifications and task reminders one by one. Tapping a task notification or reminder opens the task. New user
  manual "Tasks" in English and Russian.

### Changed

- Android APKs in releases are signed with a permanent Lares key, so new versions install over old ones. The first
  signed release installs as a separate app next to the 0.1.1 test build: remove 0.1.1 and sign in again (family data
  stays on the server).

### Fixed

- Settings › Notifications: a switch turned off right after opening the screen, or two switches turned off quickly one
  after another, no longer turn themselves back on.

## [0.1.1] - 2026-10-23

### Added

- Server installation (M0-10): one Docker Compose setup for a VPS with automatic HTTPS, optional own ntfy server for
  instant notifications, backup and restore scripts, installation manual in English and Russian. Release builds publish
  server images for x86_64 and ARM64 and attach the Android APK.
- Notifications (M0-09): reminders that fire on the phone without internet; instant notifications through ntfy
  (UnifiedPush, no Google services) with a test button; background sync every 15 minutes and right after family
  changes; Settings › Notifications to turn notification types off, check permissions and send a test reminder.
- Project documentation: vision, roadmap, feature outlines, architecture decisions.
