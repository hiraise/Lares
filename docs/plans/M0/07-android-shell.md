---
id: M0-07
status: ready
depends_on: [M0-06]
requirements: [FND-001, FND-010, FND-011, FND-013, FND-014, FND-028, FND-043, FND-060, FND-061, FND-062]
---
# M0-07: Android app — onboarding, auth, main scaffold, settings

## Goal

A user can install the APK, connect to a server (typed URL or QR), create the family (setup), log in by password or QR,
join by invite, and land in the main screen with sections filtered by permissions, a sync status indicator and
a settings screen. UI in Compose Multiplatform (`client/shared/commonMain`), Android shell in `client/android`.

## Read first

- [Foundation spec](../../product/features/foundation.md), [auth design](../../architecture/auth.md)
- [ADR-0002](../../adr/0002-languages.md) (en/ru, no hardcoded strings), [ADR-0007](../../adr/0007-distribution.md) (Play-compliant from day one)
- Client core from M0-06: `SessionManager`, repositories, `SyncEngine.status`, Koin modules
- [versions.md](versions.md): navigation, lifecycle-viewmodel, Material3, CameraX, ZXing, appcompat

## Scope

Screens: Server, Setup, Login, Invite accept, QR scanner, Main scaffold (section placeholders), Sync status sheet,
Settings (profile, appearance, sessions, about, logout, delete account). Deep links `familyhub://invite` and `familyhub://login`.

Out of scope: family management (M0-08), notifications UI (M0-09), real module content (M1+).

## Decisions (do not revisit)

- **Architecture**: one `ViewModel` (JetBrains multiplatform lifecycle) per screen in `commonMain`; immutable
  `UiState` data class exposed as `StateFlow`; user actions are plain functions. Screens are stateless composables taking
  state + callbacks; a thin route composable connects them to the ViewModel (`koinViewModel()`).
- **Navigation**: JetBrains multiplatform `navigation-compose` with `@Serializable` route objects. Two graphs:
  `onboarding` (Server → Setup | Login | InviteAccept, QrScanner) and `main` (Sections, Settings, and later Family).
  `SessionManager.state` decides the start graph.
- **Main layout**: Material 3 `Scaffold`; bottom `NavigationBar` shows only sections the member has `*.view` for
  (order: Tasks, Shopping, Calendar, Map, Budget; labels "Задачи/Tasks", "Списки/Lists", "Календарь/Calendar",
  "Карта/Map", "Бюджет/Budget"). Each section is a placeholder screen: icon + "Coming in the next versions".
  Top app bar: title = current section, sync status icon, avatar button opening a menu with "Family" (disabled until M0-08)
  and "Settings". If no section is allowed, show a friendly empty state.
- **Sync indicator**: icon states SYNCED (cloud-done), PENDING/SYNCING (cloud-upload with pending count badge),
  OFFLINE (cloud-off), ERROR (error). Tap → bottom sheet with last sync time, pending count, unseen rejections
  (localized by `ErrorCode`), "Sync now". New rejections also show a snackbar.
- **Theme** (`ui/theme/`): Material 3, dynamic color **off**. Light: primary `#2F6F5E`, onPrimary `#FFFFFF`,
  primaryContainer `#B4EFDB`, onPrimaryContainer `#002019`, secondary `#4C6359`, tertiary `#3F6375`,
  background/surface `#F6FBF7`. Dark: primary `#98D3BF`, onPrimary `#00382D`, primaryContainer `#145142`,
  onPrimaryContainer `#B4EFDB`, secondary `#B3CCC0`, tertiary `#A7CCE1`, background/surface `#0F1512`.
  Other roles: Material defaults. Spacing tokens 4/8/12/16/24/32 dp in `Spacing` object. Typography: Material defaults.
- **Strings**: Compose Multiplatform resources, `composeResources/values/strings.xml` (en) and `values-ru/strings.xml`.
  `ErrorCode` → string mapping function in one file.
- **Language and theme override** (FND-061/062): stored in DataStore; theme applied in `App()`. Language on Android via
  `AppCompatDelegate.setApplicationLocales` (MainActivity extends `AppCompatActivity` — it **requires an AppCompat theme**:
  add `client/android/src/main/res/values/themes.xml` with `Theme.FamilyHub` (parent `Theme.AppCompat.DayNight.NoActionBar`,
  window background matching the surface colors) and set `android:theme="@style/Theme.FamilyHub"` on the application;
  add `locales_config.xml` and
  `AppLocalesMetadataHolderService` with `autoStoreLocales=true`). Options: System, English, Русский.
- **QR scanner**: `expect @Composable fun QrScanner(onResult: (String) -> Unit, onCancel: () -> Unit)` in `commonMain`;
  Android actual = CameraX preview + ZXing `QRCodeReader` analyzer (no ML Kit — ADR-0006), camera permission requested
  with an explanation; JVM actual = text field to paste the payload (tests/desktop). Results are parsed with `DeepLinks.parse`.
- **Deep links**: intent filters for `familyhub://invite` and `familyhub://login` in `client/android`; MainActivity forwards
  URIs to a shared `DeepLinkHandler` (StateFlow); onboarding consumes it (server URL + code prefilled).
- **Network security**: release builds HTTPS only. Debug builds allow cleartext to `10.0.2.2` and `localhost` via
  `src/debug/res/xml/network_security_config.xml` (for the emulator → local dev server).
- Server URL input: trim; prepend `https://` if no scheme; strip trailing `/`.
- Android: `minSdk 26`, target/compile SDK per versions.md, `applicationId app.familyhub.android`, debug suffix `.debug`.

## Steps

1. **Theme, spacing, base components** (`ui/theme`, `ui/components`): `FamilyHubTheme`, primary/secondary buttons with
   loading state, password field with visibility toggle, error text from `ErrorCode`, full-screen loading, empty state.
2. **ServerScreen**: URL field, "Connect", "Scan QR code". Calls `SessionManager.connect`. Errors: unreachable,
   not a FamilyHub server, `PROTOCOL_UNSUPPORTED` ("Update the app or the server"). Routes to Setup or Login.
3. **SetupScreen** (one scrolling form, sections "Family" and "Administrator"): setup code (hint: "Shown in the server log"),
   family name, time zone (searchable picker, default = device zone), currency (text field, default by locale, suggestions
   RUB/EUR/USD), language (Русский/English), your name, login, password + repeat. Inline validation with contract validators;
   server field errors mapped to fields.
4. **LoginScreen**: login, password, "Log in"; "Log in with QR code" (scanner → login code), "I have an invitation"
   (scanner or paste code). Errors: `INVALID_CREDENTIALS`, `RATE_LIMITED`.
5. **InviteAcceptScreen**: preview (family name, inviter) then name, login, password + repeat → "Join".
6. **MainScaffold** + section placeholders + sync indicator + status sheet + rejection snackbar, as decided.
7. **SettingsScreen**:
   - Profile: display name (edits own person via `PersonsWriter` — works offline), login (read-only), change password,
     "Log in on another device" → shows a QR code for a self login code (qrose) with a 5-minute countdown.
   - Appearance: theme (System/Light/Dark), language (System/English/Русский).
   - Devices: sessions list (device, platform, last seen, "this device"), revoke with confirmation.
   - About: app version, server version, server address; "Open-source licenses" placeholder text with AGPL notice.
   - Log out; Delete account (confirmation dialog with password if the user has one; `LAST_ADMIN` explained).
8. **Android shell** (`client/android`): `FamilyHubApplication` (Koin start with Android platform module), `MainActivity`
   (`AppCompatActivity`, edge-to-edge, `setContent { App() }`, deep link forwarding, `foreground` toggling of the sync engine
   via lifecycle), manifest (INTERNET, CAMERA, deep links, locale config), app icon placeholder (simple vector: house shape
   in primary color), app name "FamilyHub".
9. **Tests**:
   - ViewModel unit tests (jvmTest) with fake `SessionManager`/API: server URL normalization; setup validation and server
     field-error mapping; login error states; invite flow; settings actions.
   - Compose UI tests on JVM (`runComposeUiTest`) for ServerScreen, LoginScreen and the bottom bar filtering by permissions.
10. **Visual check on the emulator** (if the AVD from M0-01 exists): start the dev server and DB, start the emulator
    headless, `./gradlew :client:android:installDebug`, walk through: server `http://10.0.2.2:8080` → setup → main → settings;
    take screenshots with `adb exec-out screencap -p > <scratch>/NN-name.png` and inspect them; fix obvious layout problems
    (clipped text in Russian, overlapping, unreadable contrast). Attach the list of checked screens to the handoff.

## Acceptance

- `./gradlew check :client:android:assembleDebug` passes; APK at `client/android/build/outputs/apk/debug/`.
- No hardcoded user-visible strings in Kotlin code (grep for quotes in `ui/` composables is clean except resource keys).
- Emulator walkthrough done, or explicitly reported as skipped with the reason.

## Docs to update

- Foundation spec: mark UI parts of FND-001, FND-010, FND-011, FND-013, FND-014, FND-028, FND-043, FND-060…062 as `done`.
- AGENTS.md "Commands": build/install APK, run emulator headless.
- [conventions](../../process/conventions.md): add a "Client UI" section (ViewModel/UiState pattern, routes, theme tokens, strings).
- STATUS.md; plan status.

## Stop and report if

- CameraX + ZXing cannot be made to work without proprietary dependencies.
- Per-app language switching cannot work with Compose resources — report options instead of hacking.

## Handoff

Russian summary for the customer with 3–6 screenshots (paths): what can be tried in the app now (connect, create family,
log in, settings), how it was checked, known gaps; next plan M0-08.
