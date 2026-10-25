---
id: M4-07
status: ready
depends_on: [M4-06]
requirements: [LOC-001, LOC-002, LOC-007, LOC-008, LOC-010, LOC-011, LOC-012, LOC-019, LOC-027]
---
# M4-07: Android sharing — foreground service, permissions, setup, pause, Settings › Геопозиция

## Goal

A member turns on location sharing on their Android phone: the app explains, asks for precise location «Всегда» and
notifications, suggests turning off battery optimization with brand hints, and starts a foreground service that tracks
in moving / still modes, uploads, survives restarts, answers «Обновить», pauses and resumes, and reports when location
is turned off or the permission is taken away. Settings › Геопозиция shows and controls it. The map screen comes in
M4-08; until then the setup is reached from Settings.

## Read first

- [Location design](../../architecture/location.md) § Client: Core (`SharingController`), Tracking policy, Android; UI
  «Sharing setup», «Settings › Геопозиция»
- [ADR-0028](../../adr/0028-battery-aware-tracking-service.md); [visual style](../../design/visual-style.md) § Component
  patterns (location privacy)
- [Location spec](../../product/features/location.md) LOC-001, LOC-002, LOC-007, LOC-008, LOC-010, LOC-012, scenarios
  1, 6, 7
- Code: `client/android/src/main/AndroidManifest.xml`, `LaresApplication.kt`, `notifications/AndroidNotifications.kt`
  (`createNotificationChannels`, `AndroidNotificationPoster`), `notifications/Receivers.kt`
  (`RescheduleRemindersReceiver`), `notifications/SyncWorker.kt`, `notifications/AndroidNotificationsModule.kt`,
  `notifications/PushService.kt`; `client/shared/…/notifications/NotificationsCoordinator.kt`,
  `ui/common/NotificationPermission.kt` (+ `.android.kt`, `.jvm.kt`: the expect/actual permission request pattern),
  `ui/settings/SettingsScreens.kt`, `ui/settings/NotificationSettings.kt` (`PermissionRow`), `ui/Navigation.kt`,
  `ui/UiModule.kt`, `ui/components/` (`ListGroup`, `ListRow`, `ConfirmDialog`, `NoticeBanner`, `PrimaryButton`); the M4-06
  `data/location/` package

## Scope

- Manifest: permissions and `LocationService` / action receiver of the design § Android.
- `client/android/…/location/`: `LocationService.kt`, `AndroidLocationSource.kt` (wraps `LocationManagerCompat`,
  provider choice, one-shot fix), `MotionTrigger.kt` (significant motion), `BatteryReader.kt`, `AndroidLocationTracking.kt`
  (`LocationTracking`), `AndroidLocationDevice.kt` (`LocationDevice`), `LocationResume.kt`, `LocationActionReceiver.kt`
  (pause 1 h / resume), `AndroidLocationModule.kt` (Koin, added in `LaresApplication`).
- Channel `location` (LOW) in `createNotificationChannels`; Android strings en/ru for the channel and the persistent
  notification.
- `RescheduleRemindersReceiver` and `SyncWorker` call `LocationResume.check()`; `NotificationsCoordinator` logout →
  `SharingController.disable()`.
- Shared UI `ui/location/`: `LocationPermissionRequest` (expect/actual like the notification permission:
  `rememberLocationPermissionRequest(onResult)` for fine, and a background step that opens app settings),
  `SharingSetupSheet.kt` + `SharingSetupLogic.kt` (pure step machine), `PauseSheet.kt`, `BatteryGuide.kt`
  (vendor hints), `LocationSettingsScreen.kt` + `LocationSettingsViewModel.kt`; `LocationSettingsDestination`; a row
  «Геопозиция» in Settings (every member).
- Icons if missing (`tools/design-assets/icons.txt` + rerun): `battery_alert`, `battery_charging_full`, `battery_1_bar`,
  `battery_full`, `location_off`, `play_arrow`, `refresh`.
- `client/shared` strings en/ru (`loc_*` prefix).

Out of scope: the map screen (M4-08), «Кто меня видит» / «О вас сообщают» (M4-10), history (M4-09).

## Decisions (do not revisit)

- Service exactly as the design § Android. `onStartCommand` actions: `START`, `FRESH_FIX`, `PAUSE_CHANGED`, `STOP`;
  `START_STICKY`. It calls `startForeground` first thing (within 5 s), then subscribes. Location callbacks on a
  dedicated `HandlerThread`; queue and uploads on `Dispatchers.IO` in the service scope.
- Provider: `fused` when `LocationManagerCompat.hasProvider(lm, "fused")` and API ≥ 31, else `gps`; plus `passive` in
  STILL. Requests from `TrackingPolicy.request(mode, Build.VERSION.SDK_INT, hasMotionSensor)`.
- Paused: the service stays in the foreground with «Пауза до 15:30» / «Пауза» and «Продолжить»; no location requests;
  a 1-minute coroutine tick resumes when the pause expires (`SharingController`).
- Notification: channel `location`, ongoing, `ic_location_on`, title «Вы делитесь геопозицией» (paused «Пауза до
  15:30»), text «Lares», tap → `MainActivity` with `EXTRA_ENTITY_TYPE = "location"` and the own member id; action «Пауза
  на 1 час» only when the viewer has `location.pause_own`.
- `LocationResume.check()` (from boot, package replaced, `SyncWorker`, app start): enabled here and service not running
  → if fine + background granted and location on → start (try / catch `ForegroundServiceStartNotAllowedException` →
  `reportOff(STOPPED)`); missing permission → `reportOff(PERMISSION_REVOKED)`; location off → `reportOff(LOCATION_DISABLED)`;
  each with the phone notification «Передача геопозиции остановлена — откройте Lares» (channel `system`, once per reason).
- Location off while running (`PROVIDERS_CHANGED`) → `reportOff(LOCATION_DISABLED)` and the service waits; on → resumes
  and the next upload brings the state back.
- Setup steps (`SharingSetupLogic`, pure, tested): `EXPLAIN` → `FINE` (system dialog) → `BACKGROUND` (text with
  `backgroundOptionLabel()`, button «Открыть настройки», checks on return) → `NOTIFICATIONS` (only if not granted, API
  33+) → `BATTERY` (skipped when unrestricted; «Отключить экономию» → `requestIgnoreBatteryOptimizations`, then vendor
  hints, «Готово») → `DONE` (calls `SharingController.enable()`). Refusals keep the sheet on the step with an explanation;
  «Не сейчас» closes. Without background permission sharing is not started (spec LOC-001).
- Vendor hints by `Build.MANUFACTURER` (lowercase contains): `xiaomi|redmi|poco` — «Включите «Автозапуск» и выберите
  «Нет ограничений» в «Контроле активности»»; `huawei|honor` — «Запуск приложений → Lares → Управлять вручную, включите
  все три переключателя»; `samsung` — «Батарея → Без ограничений; уберите Lares из «Спящих приложений»»;
  `oneplus|oppo|realme` — «Батарея → Не оптимизировать; разрешите работу в фоне»; others — a generic text. Text only, no
  external links.
- Settings › Геопозиция: status row (Вы делитесь / Пауза до … / Выключено / Нужны разрешения / «Не удаётся передать
  геопозицию»), actions «Включить передачу» (setup sheet), «Пауза» (sheet «На 1 час · До утра · Пока не включу»,
  `location.pause_own` only) / «Продолжить»; «Экономия заряда» row (status + guide); a note «Передачу нельзя выключить в
  приложении» for members without `location.pause_own`.
- Stale uploads warning and «Геопозицией теперь делится другой телефон» notice exactly as the design (local
  notifications, channel `system`).
- Members without `location.pause_own` never see «Пауза» anywhere (service action, settings).
- `LocationService` is a plain `Service` with its own `CoroutineScope` (no new dependency for `LifecycleService`) and a
  process-wide `running: StateFlow<Boolean>`; a null intent (sticky restart) = START; every start outside the visible
  app is wrapped in try / catch (`ForegroundServiceStartNotAllowedException`, `SecurityException`) → `reportOff(STOPPED)`
  once (design § Android). The notification icon: copy the generated `location_on` vector to
  `client/android/src/main/res/drawable/ic_notification_location.xml` (Android notifications use `R.drawable`).
- Android versions (`LocationDevice.status()` and `SharingSetupLogic`): FINE is requested together with COARSE;
  «approximate only» counts as a refusal. API 26–28: `background = fine`, the BACKGROUND step is skipped. API 29: request
  `ACCESS_BACKGROUND_LOCATION` in the system dialog. API 30+: the app settings step with
  `PackageManager.getBackgroundPermissionOptionLabel()` (`backgroundOptionLabel()` returns «Разрешить в любом режиме»
  below 30, unused).
- The row «Геопозиция» in Settings is shown to every member (everybody shares; the server allows `/me/sharing` and
  `/me/watchers` for all).
- Pushes and upload-response refreshes go through `SharingController.onRefreshRequest()` (M4-06), never straight to the
  service.

## Steps

1. Manifest, module, channel, `AndroidLocationSource`, `MotionTrigger`, `BatteryReader`.
2. `LocationService` with the policy, queue, uploader, connectivity and provider receivers.
3. `AndroidLocationTracking`, `AndroidLocationDevice`, `LocationResume`, receivers, push → fresh fix, logout.
4. Shared UI: permission request, setup sheet and logic, pause sheet, battery guide, Settings › Геопозиция.
5. Tests:
   - `SharingSetupLogicTest` (LOC-001, LOC-008): step order, skips, refusals, battery skip when unrestricted.
   - `LocationSettingsViewModelTest` (LOC-010): status texts per `MySharing`; pause choices call the controller; kid has
     no pause.
   - `BatteryGuideTest`: manufacturer mapping.
   - UI test (`ui/location/LocationSettingsUiTest`): rows, pause sheet, setup sheet steps with a fake `LocationDevice`.
6. Emulator walkthrough by the `emulator-tester` agent (give it: `seed.py family`, `seed.py location`, `emu.sh geo`,
   `emu.sh route home-school`; ru and en, light and dark):
   1. Settings › Геопозиция › «Включить передачу»: all setup steps; the persistent notification appears.
   2. `emu.sh route home-school` (the service starts in MOVING): `seed.py call GET /location/now --as dima` shows Ольга
      along the route, then at school (speed and «В пути» only if `emu.sh route` could send a velocity, see M4-06).
   3. Pause «На 1 час» from the notification and from Settings; `now` shows PAUSED; «Продолжить».
   4. `adb shell cmd location set-location-enabled false` → `now` shows OFF_ON_PHONE / LOCATION_DISABLED; `… true` →
      SHARING.
   5. `seed.py call POST /location/members/<olga_id from seed.json>/refresh --as dima` → the emulator has no push
      distributor, so the request arrives with the next upload (`refreshRequested`) and the fresh fix follows it; check
      it within 5–15 minutes (or after moving with `emu.sh geo`).
   6. `adb reboot`, then `tools/dev/emu.sh start` (restores `adb reverse`) → the service is back without opening the
      app.
   7. `adb shell pm revoke app.lares.android.debug android.permission.ACCESS_FINE_LOCATION` (kills the process), then
      reopen the app → the phone notification and OFF_ON_PHONE / PERMISSION_REVOKED.

## Acceptance

- `tools/dev/verify.sh :client:shared:jvmTest --tests '*Location*' --tests '*Sharing*'` while iterating; full gate
  (incl. `:client:android:assembleDebug`) at the end.
- The walkthrough report without open bugs (or with bugs fixed and re-checked).

## Docs to update

- Location design "Implementation notes (M4-07)"; dev-setup: emulator location notes.
- Spec progress notes (*Android: …*; real-phone checks pending for the M4 acceptance: Xiaomi / Samsung battery
  behaviour); STATUS.md; plan status.

## Stop and report if

- `startForeground` with type `location` fails on the emulator image although fine location is granted.
- The platform `fused` provider gives no fixes from `geo fix` and `gps` does (switch the emulator path, note it).

## Handoff

Russian summary: на телефоне можно включить передачу геопозиции — приложение объясняет, просит разрешения и помогает
отключить экономию заряда; в шторке видно «Вы делитесь геопозицией», есть пауза; передача продолжается после
перезагрузки, отвечает на «Обновить», а если геопозицию выключили — сервер об этом узнаёт. Screenshots of the setup and
the notification. Next M4-08.
