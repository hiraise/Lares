# Project status

Updated: 2026-10-25

## Phase

**M0 Foundation — all plans M0-01…M0-11 done; M0 acceptance with the customer pending.
M1 Tasks — all plans M1-01…M1-07 done; M1 acceptance with the customer next.
M2 Shopping lists — all plans M2-01…M2-06 done; M2 acceptance with the customer next.
M3 Calendar — M3-01…M3-05 done; next `/plan-run M3-06`.**
Detailed plans for implementation sessions are in [docs/plans/](plans/README.md).

## Active milestone

[M1 Tasks](product/roadmap.md#m1-tasks): implemented, customer acceptance pending (together with M0). Specs
`foundation`, `tags` and `tasks` approved. Next: [M2 Shopping lists](product/roadmap.md#m2-shopping-lists) — plans ready
([M2 plans](plans/README.md#m2-plans)), shopping spec approved.

**Active release branch: `release/0.4.0`** (next: M3). Commit approved work here; `main` is protected
([release process](process/release.md), ADR-0020). Latest release: [v0.3.0](https://github.com/hiraise/lares/releases/tag/v0.3.0)
(M2, 2026-10-24).

## Last completed

- 2026-10-25 M3-05 Calendar tab and event page: month pager with personal-color dots and the day's list, «Список» from
  today (growing forward, «Показать прошлые»), ‹ › and «Сегодня», filters by person, tag and «Показывать задачи» with
  removable chips, the view remembered on the device; rows for events, birthdays (open the person), memorable dates and
  tasks (ticked off there); the event page with facts, «Напомнить мне» and «Только это / Это и следующие / Все» for
  edit and delete. «Неделя» and the editor are placeholders until M3-07 / M3-06. 21 new tests, emulator walkthrough
  of spec scenarios 2–6 and 8 (cold-start month bug found and fixed).
- 2026-10-25 M3-04 Calendar in the client core: events, changed occurrences and own reminders work offline; views for
  day lists, month, week, «Скоро», tag page and person dates; one writer for «Только это» / «Это и следующие» / «Все»;
  local reminders for events, birthdays and memorable dates; `seed.py calendar`; 29 unit and 7 multi-device tests.
- 2026-10-25 M3-03 Event notifications on the server: participants who are members learn when they are added to an
  event, when its date, time or place changes and when it or one occurrence is cancelled — not for their own changes,
  past events or memorable dates; one muteable type `event.changed`; 8 new server tests.
- 2026-10-24 M3-02 Calendar on the server: tables, sync handlers for events, changed occurrences and personal reminders
  (seen only by their member), permissions, cascade on delete, housekeeping, birthday reminder lead time in the
  notification settings; 14 new server tests.
- 2026-10-24 M3-01 Calendar contract: event, changed-occurrence and personal-reminder data, derived row ids, occurrence
  computation with overrides, birthdays and memorable dates, permission rules, validators, new notification types and
  the birthday lead-time setting; 47 new contract tests.
- 2026-10-24 M3 planning: calendar spec detailed (CAL-001…CAL-022) and approved, customer answered Q-021 (event
  reminders to participants plus personal ones), Q-022 (birthday reminders daily from 3 days before, configurable),
  Q-023 (tasks with a date in the calendar), Q-024 (week as a grid by hours). Design in
  [architecture/calendar.md](architecture/calendar.md), ADR-0022 (series + occurrence rows with derived ids, household
  time), ADR-0023 (personal reminder rows visible only to their member), plans M3-01…M3-08.
- 2026-10-24 Release **v0.3.0** (M2 Shopping lists): release pull request #2 merged, signed APK, server image
  `ghcr.io/hiraise/lares-server:0.3.0`, GitHub release. Fixed on the way: CI flake in `ShoppingListViewModelTest`.
- 2026-10-24 M2-06 Shopping everywhere: the active list as the gradient hero card first on Today (count, what is left,
  who is in the store, «Открыть», «Ещё N списка»), the «Покупки» section on the tag page (an item opens its list
  filtered by the tag), user manual "Shopping lists" (en, ru); all shopping MVP requirements done; 9 new tests,
  emulator walkthrough of spec scenarios 1–7 without bugs.
- 2026-10-24 M2-05 Shopping item sheet, suggestions, tags: long press opens the item sheet (name, quantity, note,
  department remembered for all lists, tags, who added and bought, «Убрать из списка» with undo); «+ Яйца» chips with
  «Больше не предлагать»; autocomplete while typing; list tags, tags on cards and rows, tag filter; 13 new tests.
- 2026-10-24 M2-04 Shopping UI: the Shopping tab with list cards and creating a list; the list page with offline banner,
  progress and "who is shopping", quick add, departments, ticking, «Куплено» with clear and undo, rename, grouping,
  delete; 21 new tests.
- 2026-10-24 M2-03 Shopping in the client core: local tables (schema 4, data schema 3), adapters with the server's
  checks, pure views (departments, bought, suggestions, autocomplete, active list, who is shopping, tag page), repository,
  writer (quick add with matching and department memory), `seed.py shopping` / `tick`; 36 unit and 7 multi-device tests.
- 2026-10-24 M2-02 Shopping on the server: tables, list and item sync handlers with `ShoppingRules` checks, cascade
  tombstones on list delete, `shopping.view` visibility, housekeeping; 13 server tests.
- 2026-10-24 M2-01 Shopping contract: data classes and enums, quick-add parser, department guessing (dictionary passes
  the design table), write rules and validators; 25 contract tests.
- 2026-10-24 M2 planning: shopping spec detailed and approved (Q-018…Q-020 answered), [shopping design](architecture/shopping.md),
  ADR-0021 (items kept as the list's memory: suggestions, no duplicates, remembered departments), plans M2-01…M2-06.
- 2026-10-24 Release **v0.2.0** (M0 + M1) through the new flow: release pull request #1 merged, signed APK, server
  image `ghcr.io/hiraise/lares-server:0.2.0` (amd64, arm64, public), GitHub release. Fixed on the way: notification
  switches lost when changed during the first load (CI flake in `MainAndSettingsViewModelTest`).
- 2026-10-24 Release branches (ADR-0020): `main` protected by rulesets (no push, only a release pull request with
  green CI, merge commits), work on `release/X.Y.Z`, `tools/release/release.sh start|finish`, CI job `release-pr`
  (`tools/release/check.sh`), a merge into `main` tags `vX.Y.Z` and publishes the APK and images automatically;
  rulesets in `.github/rulesets/` applied by `tools/release/rulesets.sh`.
- 2026-10-24 M1-07 Tasks everywhere: Today block «Задачи на сегодня» with ticking and «Добавить», tag page (from
  Family › Tags and tag chips), four task switches in Settings › Notifications, a tap on a task notification or
  reminder opens the task; user manual "Tasks" (en, ru); 11 new tests, emulator walkthrough.
- 2026-10-24 M1-06 Task editor: create and edit with date and time, repeat (presets, custom, after completion),
  assignees or "anyone", tags (new tag from the editor), reminder, "tell me when it's done", checklist on create;
  19 new tests, emulator walkthrough in ru/en, light/dark.
- 2026-10-24 M1-05 Tasks UI: the Tasks tab (chips, search, filters, sections, ticking off with undo) and the task page
  (checklist, routine history, skip, delete one occurrence or the whole series); editor stub until M1-06; 19 new tests,
  emulator walkthrough in ru/en, light/dark.
- 2026-10-24 M1-04 Tasks in the client core: local tables and adapters (same rights as the server), views (groups,
  overdue every minute, "done today", filters, counts), writer for every action, automatic local task reminders,
  `seed.py tasks`; 31 unit and 5 multi-device tests.
- 2026-10-23 M1-03 Task notifications on the server: `afterCommit` hook, `TaskNotifier` (assigned, completed),
  overdue job every 60 s with `overdue_check_at` tracking (one notice per occurrence, 09:00 next day for date-only
  tasks, nothing stale after downtime); 18 server tests.
- 2026-10-23 M1-02 Tasks on the server: tables, three sync handlers with `TaskRules` checks (new `authorize` hook),
  reference checks, immutable completions, cascade tombstones, `tasks.view` visibility, housekeeping; 13 server tests.
- 2026-10-23 M1-01 Tasks contract: task data classes, repeat rules and current-occurrence logic (`TaskSchedule`),
  `TaskRules`, validators, `task.reminder` type; 103 contract tests.
- 2026-10-23 Release signing and flow (ADR-0019): permanent Lares key (GitHub secrets; original in
  `~/.android/lares-release/`), release workflow fails without it and checks the certificate fingerprint, notes from
  CHANGELOG, `tools/release/release.sh`, versionCode derived from the version, [release process](process/release.md).
- 2026-10-23 Project renamed FamilyHub → **Lares** (Q-001): package and application id `app.lares`, env vars
  `LARES_*`, image `ghcr.io/hiraise/lares-server`, repository `github.com/hiraise/lares`.
- 2026-10-23 M0-11 Load simulation: `tools/loadsim` + `deploy/scripts/loadtest.sh`. Simulated family (10 devices,
  30 min): server RSS avg 232 / max 237 MiB (target ≤ 256), p95 52 ms (≤ 100), cold start 1.9–2.7 s (≤ 10), whole
  stack ~320 MiB — ADR-0013 targets met after JVM tuning ([baseline](architecture/perf/M0-baseline.md)).
- 2026-10-23 M0-10 Deploy and operations: Docker Compose stack (HTTPS, optional ntfy), backup/restore, smoke test,
  release workflow (multi-arch image, APK), install manual en/ru.
- 2026-10-23 M0-09 Notifications: local reminders, UnifiedPush (ntfy), background sync, Settings › Notifications.
- 2026-10-23 M0-08 Family management screens.
- Full log: [history.md](history.md).

## Next steps

1. M0 and M1 acceptance with the customer (APK v0.2.0, walkthrough on real phones, Russian changelog; checklist in the M1-07
   handoff).
2. Once a VPS and domain exist: install by the manual and check instant notifications end to end on a real phone (FND-051).
3. M4 plan: re-run the load test with location uploads every 30 s (ADR-0015).
4. After the M1 acceptance: set the tasks spec to `implemented`.
5. M2 acceptance with the customer (checklist in the M2-06 handoff); after it set the shopping spec to `implemented`.
6. Execute M3-06…M3-08 (`/plan-run M3-06`).

## Plan progress

See the table in [plans README](plans/README.md#m0-plans).

## Waiting for the customer

- For a real installation: a VPS (1 vCPU / 2 GB / 20 GB) and a domain with DNS access.

## Risks

| Risk | Impact | Mitigation |
| --- | --- | --- |
| Google developer verification for sideloaded apps (rolling out from 2026, global 2027) | APK / F-Droid installs may require a verified developer | Re-check before M6; a developer account likely covers it |
| Loss or leak of the release signing key | No updates for installed apps / fake updates | Lares-only key, backup archive kept off the machine by the customer, fingerprint checked in the release workflow (ADR-0019) |
| OEM battery optimizations (Xiaomi, Huawei, Samsung) kill background location | Gaps in location tracking | In-app guide to exclude the app from battery optimization; detect and warn about stale positions |
| JVM server memory above budget | Stack does not fit a 1 GB VPS | M0-11: RSS max 237 of 256 MiB after tuning, stack ~320 MiB; re-measure in M4 with location; next lever GraalVM native image |
| Compose Multiplatform web target is Beta | Web client (M7) may need workarounds | Re-evaluate at M7; fallback is a separate web UI |
| Sync engine complexity | Data loss or conflicts | Protocol accepted (sync.md, ADR-0008); server invariants (M0-05) and multi-device convergence tests (M0-06) run in `check` |
| Flexible permissions make UI and testing harder | Confusing group editor, permission bugs | Templates "Adults"/"Kids", plain wording, permission matrix tests (ADR-0014) |
| Emulator cannot reach the host's ports on this Mac (`10.0.2.2` times out) | Manual checks against the dev server | `adb reverse tcp:8080 tcp:8080` and `http://localhost:8080` (dev-setup) |
| Emulator is slow on this Mac (software GPU, cold start ~9 s, ANR dialogs under load) | Walkthroughs are slow; real ANRs are hard to tell from emulator stalls | Check main-thread CPU (`/proc/<pid>/task/<pid>/stat`) and ANR traces before blaming the app; real phone for M0 acceptance |
| Pre-release UI libraries (CMP Material3 alpha, navigation RC) | Possible API changes or bugs | Versions paired by JetBrains with CMP 1.12.2; upgrade when stable |
| ktlint 1.8 parses only Kotlin ≤ 2.2 syntax | Lint failures on newest syntax | Avoid newest syntax; report instead of disabling lint |
| Flaky multi-device test `SyncScenariosTest` 10 (restore reset) failed once in the full gate, passed 3 reruns | CI noise | Watch in CI; investigate if it repeats |
| Medium-effort sessions deviate from plans | Inconsistent code | Plans fix all decisions; "stop and report" rules; customer approves each commit |

## Dev environment notes

- Host: macOS (Apple Silicon), Homebrew, Docker. JDK 21 (Homebrew), Android SDK from Android Studio
  (`~/Library/Android/sdk`) and the `lares` AOSP emulator;
  see [dev-setup](process/dev-setup.md).
- The shell's default `java` may be older than 21; Gradle uses JDK 21 from `~/.gradle/gradle.properties`. To run
  `server/build/install/server/bin/server` directly, set `JAVA_HOME` to that JDK.
- Host port 5432 may be taken by another local PostgreSQL; the dev compose file accepts `LARES_DEV_DB_PORT`.
