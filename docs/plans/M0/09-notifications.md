---
id: M0-09
status: ready
depends_on: [M0-08]
requirements: [FND-050, FND-051, FND-052]
---
# M0-09: Notifications — reminders, UnifiedPush, background sync

## Goal

Notification infrastructure for all later modules: local time-based reminders that fire without network,
event notifications from the server through UnifiedPush (no Google services), content-free "sync" pushes that wake
the app to sync, periodic background sync, notification channels and per-type muting.

## Read first

- [ADR-0006](../../adr/0006-no-proprietary-dependencies.md) — push design (binding)
- [versions.md § UnifiedPush](versions.md#unifiedpush) — connector version, Web Push requirements, server library, ntfy notes
- [API](../../architecture/api.md): `/me/push`, `/me/push/test`, `/me/notification-settings`
- [Sync protocol § realtime events](../../architecture/sync.md#realtime-events)
- Client core (M0-06), app shell and settings (M0-07)

## Scope

- Contract: `NotificationType`, push payload, push registration and notification settings DTOs.
- Server: migration `V5__notifications.sql`, `NotificationService`, Web Push sender, sync tickles, endpoints.
- Android: UnifiedPush integration, notification channels, reminder scheduler, background sync worker,
  POST_NOTIFICATIONS permission flow, "Notifications" settings section.

Out of scope: concrete module notifications (task assigned/overdue come in M1 using this infrastructure).

## Decisions (do not revisit)

- **NotificationType** (contract enum, stable serial names): `SYSTEM_TEST` (M0), `TASK_ASSIGNED`, `TASK_OVERDUE`,
  `TASK_COMPLETED` (declared now, used in M1). Each type maps to an Android channel: `reminders`, `family_activity`, `system`.
- **Push payload** (JSON, ≤ 2 KB cleartext — UnifiedPush allows at most 3993 bytes; encrypted per RFC 8291 `aes128gcm`): `PushPayload(kind: "sync" | "notification", seq: Long? = null,
  type: NotificationType? = null, title: String? = null, body: String? = null, entityType: String? = null, entityId: String? = null)`.
  Texts are rendered on the server in the member's language (M0: `SYSTEM_TEST` only, en/ru strings on the server).
- **Sync tickles**: on `HouseholdChangeBus` events, the server sends `kind = "sync"` to every registered push endpoint of the
  household's sessions, **throttled per household to at most one push per 30 s with a trailing send** (changes during a
  window always produce a push at the end of it). The originating device also gets the tickle (harmless). Phones react by
  enqueuing an expedited one-time sync work.
- **Muting** (FND-052): per member and type, stored on the server (`notification_setting`), so muted notifications are
  not sent at all; the Android app mirrors the settings for local reminders.
- **Reminders**: `ReminderScheduler` interface in `commonMain` (`schedule(id, at: Instant, title, body, channel)`,
  `cancel(id)`, `rescheduleAll()`); Android implementation with `AlarmManager.setExactAndAllowWhileIdle` when
  `canScheduleExactAlarms()` is true, otherwise `setAndAllowWhileIdle`. Request `SCHEDULE_EXACT_ALARM` through the system
  settings screen with an explanation (do **not** use `USE_EXACT_ALARM`). Scheduled reminders are stored in a local
  SQLite table so they can be re-registered on boot (`RECEIVE_BOOT_COMPLETED`) and after app updates.
- **Background sync**: WorkManager periodic work every 15 min with a network constraint; one-time expedited work on
  push tickles; both call `SyncEngine.syncNow()` (the engine is already single-flight).
- **POST_NOTIFICATIONS** (Android 13+): requested after login with a short explanation screen, not at first launch.

## Steps

1. **Contract**: `NotificationType`, `PushPayload`, `PushRegistrationRequest` (fields per versions.md § UnifiedPush:
   `endpoint`, `p256dh`, `auth` — all required, encryption is mandatory — and `language` (`en`/`ru`, the device UI language,
   used to render notification texts)), `NotificationSettings(muted: Set<NotificationType>)` (lenient enum set).
   `NotificationType` uses the lenient serializer; unknown types in a push are ignored by the app.
2. **Server migration `V5__notifications.sql`**: `push_registration(session_id uuid PK FK ON DELETE CASCADE, household_id,
   member_id, endpoint text NOT NULL, p256dh text NOT NULL, auth text NOT NULL, language text NOT NULL, created_at, updated_at)`;
   `instance_secret(name text PK, value text NOT NULL)` (VAPID keys);
   `notification_setting(member_id FK, type text, muted boolean, PK(member_id, type))`.
3. **Server sender**: `WebPushSender` built on `com.interaso:webpush` (MIT). It always sends a VAPID header (ntfy accepts it):
   generate the VAPID key pair on first start and store it in a new table `instance_secret(name PK, value)` (not a file —
   the container filesystem is ephemeral); load it via the library's key API. Send with `TTL` (e.g. 86400 for
   notifications, 600 for sync tickles), `Content-Encoding: aes128gcm`. Handle results: expired (404/410) → delete the
   registration; other failures → log and drop (no retry queue in M0).
   **SSRF guard** (UnifiedPush recommendation): refuse endpoints whose host resolves to a non-global IP (loopback,
   RFC 1918, RFC 4193, link-local) unless the host is listed in `FAMILYHUB_PUSH_ALLOWED_HOSTS` (comma-separated);
   validate on `PUT /me/push` and again before sending.
4. **Server service + endpoints**: `PUT /api/v1/me/push`, `DELETE /api/v1/me/push`, `POST /api/v1/me/push/test`
   (sends `SYSTEM_TEST` to the caller's session), `GET` and `PUT /api/v1/me/notification-settings`;
   `NotificationService.notify(householdId, memberIds, type, payloadBuilder)` respecting mutes;
   tickle publisher subscribed to `HouseholdChangeBus` with the 30 s debounce.
5. **Android UnifiedPush** (connector 3.x, see versions.md): on login →
   `UnifiedPush.tryUseCurrentOrDefaultDistributor(context) { ok -> if (ok) UnifiedPush.register(context) }` (no VAPID key
   needed on the client for ntfy). Subclass `PushService`: `onNewEndpoint(endpoint, instance)` → `PUT /me/push` with
   `endpoint.url`, `endpoint.pubKeySet.pubKey` (p256dh), `endpoint.pubKeySet.auth`; `onUnregistered` → `DELETE /me/push`;
   `onRegistrationFailed` → show status in settings; `onMessage(message, instance)` → the connector already decrypted
   `message.content` → parse `PushPayload`: `kind=sync` → enqueue expedited sync work; `kind=notification` → show it on the
   type's channel; tap opens the app. Manifest: service with intent action `org.unifiedpush.android.connector.PUSH_EVENT`.
   On logout → `UnifiedPush.unregister(context)`. **Do not** add `embedded-fcm-distributor` (it relies on Google Play
   Services — ADR-0006).
6. **Android notifications**: channels created at app start (names/descriptions localized); `NotificationPoster` helper.
7. **Reminder scheduler** as decided, plus a "Diagnostics" item in settings: "Test reminder in 1 minute".
8. **WorkManager**: `SyncWorker` (periodic + expedited one-time), scheduled after login, cancelled on logout. Expedited work
   on Android < 12 runs as a foreground service: implement `getForegroundInfo()` returning a low-priority "Syncing…"
   notification on the `system` channel; use `OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST`.
9. **Settings → Notifications**:
   - Status line: "Instant notifications: connected via <distributor>" / "not set up" with a help text explaining
     how to install ntfy (F-Droid or Google Play) and point it to the family server.
   - Distributor picker when several are installed.
   - "Send test notification" (server test endpoint).
   - Toggles per notification type (M0 shows only types with UI: system test; others appear in M1).
   - Exact alarm permission status with a button to grant it.
10. **Tests**:
    - Server: a fake push endpoint (Ktor test server) receives an encrypted request with the expected headers after
      `/me/push/test`; muted type → nothing sent; 410 response deletes the registration; tickle debounce sends at most
      one push per 30 s per household (test clock); tickles skip the originating session.
    - Client (jvmTest): payload parsing and routing (`sync` → sync request, `notification` → poster call) with fakes;
      reminder store re-registration logic.
11. **Device check** (emulator, if available): install ntfy from F-Droid is not possible on a stock emulator image without
    network policies — instead verify: local test reminder fires; WorkManager periodic work is enqueued
    (`adb shell dumpsys jobscheduler | grep familyhub`); notification permission flow; settings screen states.
    Report UnifiedPush end-to-end as "requires a phone with ntfy" if it cannot be checked.

## Acceptance

- `./gradlew check :client:android:assembleDebug` passes.
- Server push tests pass with a fake endpoint; test reminder verified on the emulator or reported as skipped.

## Docs to update

- Foundation spec: FND-050…052 → `done` (or `doing` with a note if end-to-end push is unverified).
- User manual (`docs/user/en|ru/`): new page "Instant notifications with ntfy" (how to install ntfy, connect it, test).
- STATUS.md; plan status.

## Stop and report if

- The UnifiedPush connector or Web Push library from versions.md cannot be used without proprietary dependencies.

## Handoff

Russian summary: how reminders and instant notifications work now, what the family needs to install (ntfy) for instant
notifications, what was verified and what needs a real phone; next plan M0-10.
