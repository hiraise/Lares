# Changelog

All notable user-visible changes. Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Versioning: [SemVer](https://semver.org/) from the first release.

## [Unreleased]

### Added

- Server installation (M0-10): one Docker Compose setup for a VPS with automatic HTTPS, optional own ntfy server for
  instant notifications, backup and restore scripts, installation manual in English and Russian. Release builds publish
  server images for x86_64 and ARM64 and attach the Android APK.
- Notifications (M0-09): reminders that fire on the phone without internet; instant notifications through ntfy
  (UnifiedPush, no Google services) with a test button; background sync every 15 minutes and right after family
  changes; Settings › Notifications to turn notification types off, check permissions and send a test reminder.
- Project documentation: vision, roadmap, feature outlines, architecture decisions.
