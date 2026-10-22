---
id: M0-01
status: ready
depends_on: []
requirements: []
---
# M0-01: Dev environment and Gradle monorepo skeleton

## Goal

Prepare the developer machine (macOS, Apple Silicon) and create the Gradle monorepo with four modules that build and test:
`contract` (KMP), `server` (Ktor), `client:shared` (KMP + Compose Multiplatform), `client:android` (Android app).
Formatting/linting, CI workflow and an optional headless Android emulator for visual checks.
No product features.

## Read first

- [versions.md](versions.md) — **binding** toolchain, catalog and build notes
- [ADR-0004](../../adr/0004-kotlin-full-stack.md), [ADR-0006](../../adr/0006-no-proprietary-dependencies.md),
  [architecture overview § repository layout](../../architecture/overview.md)
- [conventions](../../process/conventions.md)

## Scope

- Install: JDK 21, Gradle (only to create the wrapper), Android command-line tools + SDK packages, optional emulator.
- Gradle wrapper 9.7.0, `settings.gradle.kts`, root `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`.
- Modules with minimal code and one test each.
- Spotless + ktlint; `.github/workflows/ci.yml`.
- Docs: `docs/process/dev-setup.md`, AGENTS.md "Commands".

Out of scope: database, real endpoints (M0-02), SQLDelight (M0-06), UI beyond a single placeholder screen.

## Decisions (do not revisit)

- Versions exactly as in [versions.md](versions.md); copy its TOML block verbatim into `gradle/libs.versions.toml`.
- Module paths: `:contract`, `:server`, `:client:shared`, `:client:android` (folders `contract/`, `server/`,
  `client/shared/`, `client/android/`). Typesafe project accessors on (`projects.contract`, `projects.client.shared`).
- Packages / namespaces: `app.familyhub.contract`, `app.familyhub.server`, `app.familyhub.client`
  (+ Compose resources class package `app.familyhub.client.resources`), Android `applicationId = "app.familyhub.android"`
  with debug suffix `.debug`. (The working name will be renamed before M6 — keep names centralized.)
- JDK toolchain 21 everywhere (`kotlin { jvmToolchain(21) }`). Bytecode: server 21; KMP `jvm()` and Android targets 17
  (`compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }`).
- KMP Android targets use `com.android.kotlin.multiplatform.library` with `kotlin { android { … } }`. The app module uses
  `com.android.application` + `org.jetbrains.kotlin.plugin.compose`, **no** `kotlin-android` plugin.
- `contract` targets: `jvm()` + `android`. `client:shared` targets: `android` + `jvm()` (JVM for tests and future desktop).
- Server: `kotlin-jvm` + `application` plugins (`installDist`), **not** the Ktor Gradle plugin.
- Formatting: Spotless with ktlint 1.8.0, `ktlint_official` code style, max line length 140, Composable functions may be
  PascalCase. `spotlessCheck` is part of `check`. Agents run `./gradlew spotlessApply` before `check`.
- Gradle properties: configuration cache, build cache and parallel builds **on**. If configuration cache breaks a plugin,
  turn it off and report.
- Nothing that requires `sudo`. If a step needs it, stop and ask the customer.

## Steps

1. **JDK 21**: `brew install openjdk@21`. Set for Gradle without touching shell profiles: append (create if missing)
   `org.gradle.java.home=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home` to `~/.gradle/gradle.properties`
   (keep existing lines). For commands in this session export
   `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home` and prepend `$JAVA_HOME/bin` to `PATH`.
   Verify `java -version` shows 21.
2. **Gradle wrapper**: `brew install gradle`, then in the repo root `gradle wrapper --gradle-version 9.7.0 --distribution-type bin`.
   From now on use only `./gradlew`.
3. **Android SDK**: `brew install --cask android-commandlinetools`; `export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools`;
   `yes | sdkmanager --licenses`; `sdkmanager "platform-tools" "platforms;android-37.0" "build-tools;37.0.0"`.
   Create `local.properties` (gitignored) with `sdk.dir=/opt/homebrew/share/android-commandlinetools`.
4. **Emulator (recommended, ~3 GB)**: `sdkmanager "emulator" "system-images;android-37.0;default;arm64-v8a"`
   (AOSP image without Google services; if unavailable, pick the newest `default` arm64 image from `sdkmanager --list`).
   `avdmanager create avd -n familyhub -k "<that image>" -d pixel_7`. Headless start:
   `$ANDROID_HOME/emulator/emulator -avd familyhub -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect &`,
   wait with `adb wait-for-device` and `adb shell getprop sys.boot_completed` = 1. Stop: `adb emu kill`.
   If the emulator cannot run, continue and report.
5. **Settings and root build**:
   - `settings.gradle.kts`: `pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }`,
     `plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }`,
     `dependencyResolutionManagement { repositories { google(); mavenCentral() } }`,
     `enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")`, `rootProject.name = "familyhub"`, includes.
   - `gradle.properties`: `org.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8`, `org.gradle.caching=true`,
     `org.gradle.configuration-cache=true`, `org.gradle.parallel=true`, `kotlin.code.style=official`, `android.useAndroidX=true`,
     `version=0.1.0` (project version for all modules; the server reports it as `serverVersion`).
   - Root `build.gradle.kts`: all catalog plugins with `apply false`; Spotless applied to the root project with
     `kotlin { target("**/*.kt"); targetExclude("**/build/**"); ktlint(libs.versions.ktlint.get()) }` and
     `kotlinGradle { target("**/*.kts"); targetExclude("**/build/**"); ktlint(libs.versions.ktlint.get()) }`.
   - `.editorconfig`: add a `[*.{kt,kts}]` section: `ktlint_code_style = ktlint_official`, `max_line_length = 140`,
     `ktlint_function_naming_ignore_when_annotated_with = Composable`.
6. **`contract`**: plugins `kotlin-multiplatform`, `android-kmp-library`, `kotlin-serialization`. Targets per Decisions,
   `android { namespace = "app.familyhub.contract"; compileSdk = 37; minSdk = 26 }`. Dependencies: commonMain
   `kotlinx-serialization-json`, `kotlinx-datetime`; commonTest `kotlin-test`; jvmTest `kotlin-test-junit5`,
   `platform(junit-bom)`, runtimeOnly `junit-platform-launcher`; `tasks.withType<Test>().configureEach { useJUnitPlatform() }`.
   Code: `app/familyhub/contract/Ids.kt` — `object Ids { fun newId(): String }` using `Uuid.generateV7()` with the opt-in.
   Test: generated id parses as UUID, version nibble = 7, two ids generated in sequence sort ascending as strings.
7. **`server`**: plugins `kotlin-jvm`, `kotlin-serialization`, `application`; `application { mainClass.set("app.familyhub.server.ApplicationKt") }`.
   Dependencies: `projects.contract`, ktor server core/netty/content-negotiation, `ktor-serialization-kotlinx-json`,
   `logback-classic`; tests: `kotlin-test-junit5`, `platform(junit-bom)`, `ktor-server-test-host`, `junit-platform-launcher`.
   Code: `main()` starting Netty on port 8080 with `GET /health/live` → `{"status":"ok"}` (a `fun Application.module()` used by
   tests). Test with `testApplication { application { module() } }`.
8. **`client:shared`**: plugins `kotlin-multiplatform`, `android-kmp-library`, `compose-multiplatform`, `compose-compiler`,
   `kotlin-serialization`. `android { namespace = "app.familyhub.client"; compileSdk = 37; minSdk = 26;
   androidResources { enable = true }; withHostTest { } }`; `jvm()`. commonMain: compose runtime/foundation/ui/material3/
   components-resources/ui-tooling-preview, `projects.contract`. jvmTest: `kotlin-test-junit5`, `platform(junit-bom)`,
   `junit-platform-launcher` (runtime), `compose-ui-test`, desktop runtime for the host OS (see versions.md build notes).
   `compose.resources { packageOfResClass = "app.familyhub.client.resources"; publicResClass = false }`.
   Code: `@Composable fun App()` → `MaterialTheme { Surface { Text(stringResource(Res.string.app_name)) } }`;
   `composeResources/values/strings.xml` (`app_name` = "FamilyHub") and `values-ru/strings.xml` (same value).
   JVM UI test (`runComposeUiTest`) asserting the text "FamilyHub" is displayed.
9. **`client:android`**: plugins `android-application`, `compose-compiler`. `android { namespace = "app.familyhub.android";
   compileSdk = 37; defaultConfig { applicationId = "app.familyhub.android"; minSdk = 26; targetSdk = 37; versionCode = 1;
   versionName = "0.1.0" }; buildTypes { debug { applicationIdSuffix = ".debug" } }; compileOptions { 17 } }`,
   `kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }`. Dependencies: `projects.client.shared`,
   `androidx-activity-compose`. `MainActivity : ComponentActivity` with `enableEdgeToEdge()` and `setContent { App() }`;
   manifest with label "FamilyHub" and the launcher activity; placeholder adaptive icon (vector). If the build says Compose
   is not enabled, add `buildFeatures { compose = true }`.
10. **Run**: `./gradlew spotlessApply check :client:android:assembleDebug`. If the emulator runs:
    `./gradlew :client:android:installDebug`, `adb shell am start -n app.familyhub.android.debug/app.familyhub.android.MainActivity`,
    `adb exec-out screencap -p > <scratchpad>/m0-01.png`, inspect that "FamilyHub" is visible.
11. **CI** `.github/workflows/ci.yml`: on `push` to `main` and `pull_request`; job `build` on `ubuntu-latest`:
    checkout v7, setup-java v6 (temurin 21), setup-gradle v6, `./gradlew check :client:android:assembleDebug --stacktrace`,
    upload the debug APK as an artifact. (The repo has no remote yet; just keep the file valid.)
12. **Docs**: write `docs/process/dev-setup.md` (what was installed and how, env vars, `~/.gradle/gradle.properties` line,
    emulator commands, troubleshooting you hit). Fill AGENTS.md "Commands": format, check, run server, build APK, emulator
    start/stop, screenshot.

## Acceptance

- `./gradlew check :client:android:assembleDebug` passes from a clean checkout (`git clean -xfd` excluding `local.properties` is not required).
- `./gradlew :server:run` → `curl localhost:8080/health/live` returns `{"status":"ok"}`.
- APK exists; emulator screenshot shows "FamilyHub" (or the emulator step is reported as skipped with the reason).
- `git status` shows no build outputs or `local.properties`; the wrapper jar is included.

## Docs to update

- `docs/process/dev-setup.md` (new), AGENTS.md "Commands", [conventions](../../process/conventions.md) "General"
  (formatting command, code style), STATUS.md, plan status in [plans README](../README.md).

## Stop and report if

- Any step needs `sudo` or an interactive login.
- The pinned Kotlin/AGP/CMP/Gradle combination fails to configure and the fix would require changing versions —
  report the exact error; do not upgrade or downgrade on your own.
- ktlint cannot parse valid Kotlin code.

## Handoff

Russian summary for the customer: the development environment is ready and the empty project builds (server, app,
shared code), what was installed on the Mac and why (JDK, Android SDK, emulator), how it was checked; next plan M0-02.
