# Changelog

All notable user-visible changes. Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Versioning: [SemVer](https://semver.org/) from the first release.

## [Unreleased]

### Added

- Tasks tab (M1-05): my tasks and all tasks by sections (overdue, today, upcoming, no date), chips «Мои», «Все»,
  «Поручено мной», tag filter, search and filters by assignee and state; tick a task off right in the list and undo it.
  Task page with all details, checklist, history of a routine, skip one occurrence, delete one task or the whole
  series.
- Task editor (M1-06): create and edit tasks — title, notes, date and time, repeat (every day, week, month, year, on
  chosen weekdays, or a set time after the last completion), assignees or «Кто угодно», tags (a new tag right from the
  editor), a reminder, «Сообщить мне, когда выполнят», and a checklist when creating.

### Changed

- Android APKs in releases are signed with a permanent Lares key, so new versions install over old ones. The first
  signed release installs as a separate app next to the 0.1.1 test build: remove 0.1.1 and sign in again (family data
  stays on the server).

## [0.1.1] - 2026-10-23

### Added

- Server installation (M0-10): one Docker Compose setup for a VPS with automatic HTTPS, optional own ntfy server for
  instant notifications, backup and restore scripts, installation manual in English and Russian. Release builds publish
  server images for x86_64 and ARM64 and attach the Android APK.
- Notifications (M0-09): reminders that fire on the phone without internet; instant notifications through ntfy
  (UnifiedPush, no Google services) with a test button; background sync every 15 minutes and right after family
  changes; Settings › Notifications to turn notification types off, check permissions and send a test reminder.
- Project documentation: vision, roadmap, feature outlines, architecture decisions.
