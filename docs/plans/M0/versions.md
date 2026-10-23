# Versions and toolchain (verified 2026-10-22)

Single source of truth for M0. Verified against Maven Central / Google Maven metadata, GitHub releases, Docker Hub,
the Gradle plugin portal and official docs on 2026-10-22. M0-01 copies the catalog below into `gradle/libs.versions.toml`;
after that the catalog file is authoritative.

**Upgrade rule:** do not raise Kotlin, AGP, Gradle or Compose Multiplatform during M0. The combination below is the
newest one inside Kotlin's officially tested range (kotlinlang.org KMP compatibility guide, updated 2026-10-08:
Kotlin 2.4.20–2.4.21 tested with Gradle ≤ 9.7.0 and AGP ≤ 9.3.x). AGP 9.4.x and Gradle 9.8.x exist but are outside it.

## Toolchain

| Item | Version | Notes |
| --- | --- | --- |
| JDK | 21 (Homebrew `openjdk@21`) | Runs Gradle; compiles everything. Server bytecode 21; KMP/Android bytecode 17 |
| Gradle (wrapper) | 9.7.0 | Needs JVM 17–27 |
| Kotlin | 2.4.21 | |
| Android Gradle Plugin | 9.3.3 | Latest patch of 9.3 (needs Gradle ≥ 9.5) |
| Compose Multiplatform | 1.12.2 | Based on Jetpack Compose 1.12.1 |
| Android compileSdk / targetSdk | 37 | Android 17. If AGP warns that 37 is untested, add `android.suppressUnsupportedCompileSdk=37` to `gradle.properties` |
| Android minSdk | 26 | ≥ 24 required by WorkManager 2.12; ≥ 23 by CameraX/SQLDelight |
| Android build-tools | 37.0.0 | |
| Android SDK packages | `platform-tools`, `platforms;android-37.0`, `build-tools;37.0.0` | Emulator image: `system-images;android-37.0;default;arm64-v8a` (AOSP, **no Google services** — matches ADR-0006) |

## Version catalog (`gradle/libs.versions.toml`)

```toml
[versions]
kotlin = "2.4.21"
agp = "9.3.3"
compose-multiplatform = "1.12.2"
compose-material3 = "1.12.0-alpha03"
compose-material-icons = "1.7.3"
jb-navigation = "2.10.0-rc01"
jb-lifecycle = "2.11.0"
kotlinx-coroutines = "1.11.0"
kotlinx-serialization = "1.12.0"
kotlinx-datetime = "0.8.0"
ktor = "3.6.0"
exposed = "1.6.0"
postgresql = "42.7.14"
hikaricp = "7.1.0"
flyway = "13.11.1"
logback = "1.6.5"
kotlin-logging = "8.0.4"
bouncycastle = "1.86"
junit = "6.1.3"
testcontainers = "2.0.5"
sqldelight = "2.4.1"
koin = "4.2.2"
androidx-activity = "1.13.0"
androidx-appcompat = "1.8.0"
androidx-datastore = "1.3.0"
androidx-camerax = "1.6.2"
androidx-work = "2.12.0"
zxing = "3.5.4"
qrose = "1.3.1"
unifiedpush = "3.3.5"
webpush = "1.3.0"
spotless = "8.10.4"
ktlint = "1.8.0"
foojay = "1.0.0"

[libraries]
# Kotlin / kotlinx
kotlin-test = { module = "org.jetbrains.kotlin:kotlin-test", version.ref = "kotlin" }
kotlin-test-junit5 = { module = "org.jetbrains.kotlin:kotlin-test-junit5", version.ref = "kotlin" }
kotlinx-coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "kotlinx-coroutines" }
kotlinx-coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "kotlinx-coroutines" }
kotlinx-serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "kotlinx-serialization" }
kotlinx-datetime = { module = "org.jetbrains.kotlinx:kotlinx-datetime", version.ref = "kotlinx-datetime" }

# Ktor server
ktor-server-core = { module = "io.ktor:ktor-server-core", version.ref = "ktor" }
ktor-server-netty = { module = "io.ktor:ktor-server-netty", version.ref = "ktor" }
ktor-server-cio = { module = "io.ktor:ktor-server-cio", version.ref = "ktor" }
ktor-server-content-negotiation = { module = "io.ktor:ktor-server-content-negotiation", version.ref = "ktor" }
ktor-server-status-pages = { module = "io.ktor:ktor-server-status-pages", version.ref = "ktor" }
ktor-server-call-logging = { module = "io.ktor:ktor-server-call-logging", version.ref = "ktor" }
ktor-server-call-id = { module = "io.ktor:ktor-server-call-id", version.ref = "ktor" }
ktor-server-forwarded-header = { module = "io.ktor:ktor-server-forwarded-header", version.ref = "ktor" }
ktor-server-websockets = { module = "io.ktor:ktor-server-websockets", version.ref = "ktor" }
ktor-server-test-host = { module = "io.ktor:ktor-server-test-host", version.ref = "ktor" }
ktor-serialization-kotlinx-json = { module = "io.ktor:ktor-serialization-kotlinx-json", version.ref = "ktor" }

# Ktor client
ktor-client-core = { module = "io.ktor:ktor-client-core", version.ref = "ktor" }
ktor-client-okhttp = { module = "io.ktor:ktor-client-okhttp", version.ref = "ktor" }
ktor-client-cio = { module = "io.ktor:ktor-client-cio", version.ref = "ktor" }
ktor-client-content-negotiation = { module = "io.ktor:ktor-client-content-negotiation", version.ref = "ktor" }
ktor-client-websockets = { module = "io.ktor:ktor-client-websockets", version.ref = "ktor" }
ktor-client-mock = { module = "io.ktor:ktor-client-mock", version.ref = "ktor" }

# Server persistence, security, logging
exposed-core = { module = "org.jetbrains.exposed:exposed-core", version.ref = "exposed" }
exposed-jdbc = { module = "org.jetbrains.exposed:exposed-jdbc", version.ref = "exposed" }
exposed-java-time = { module = "org.jetbrains.exposed:exposed-java-time", version.ref = "exposed" }
postgresql = { module = "org.postgresql:postgresql", version.ref = "postgresql" }
hikaricp = { module = "com.zaxxer:HikariCP", version.ref = "hikaricp" }
flyway-core = { module = "org.flywaydb:flyway-core", version.ref = "flyway" }
flyway-database-postgresql = { module = "org.flywaydb:flyway-database-postgresql", version.ref = "flyway" }
bouncycastle-bcprov = { module = "org.bouncycastle:bcprov-jdk18on", version.ref = "bouncycastle" }
webpush = { module = "com.interaso:webpush", version.ref = "webpush" }
logback-classic = { module = "ch.qos.logback:logback-classic", version.ref = "logback" }
kotlin-logging = { module = "io.github.oshai:kotlin-logging-jvm", version.ref = "kotlin-logging" }

# Testing (JVM)
junit-bom = { module = "org.junit:junit-bom", version.ref = "junit" }
junit-jupiter = { module = "org.junit.jupiter:junit-jupiter" }
junit-platform-launcher = { module = "org.junit.platform:junit-platform-launcher" }
testcontainers-postgresql = { module = "org.testcontainers:testcontainers-postgresql", version.ref = "testcontainers" }

# Compose Multiplatform (direct coordinates; the plugin's compose.* aliases are deprecated)
compose-runtime = { module = "org.jetbrains.compose.runtime:runtime", version.ref = "compose-multiplatform" }
compose-foundation = { module = "org.jetbrains.compose.foundation:foundation", version.ref = "compose-multiplatform" }
compose-ui = { module = "org.jetbrains.compose.ui:ui", version.ref = "compose-multiplatform" }
compose-ui-tooling = { module = "org.jetbrains.compose.ui:ui-tooling", version.ref = "compose-multiplatform" }
compose-ui-tooling-preview = { module = "org.jetbrains.compose.ui:ui-tooling-preview", version.ref = "compose-multiplatform" }
compose-ui-test = { module = "org.jetbrains.compose.ui:ui-test", version.ref = "compose-multiplatform" }
compose-components-resources = { module = "org.jetbrains.compose.components:components-resources", version.ref = "compose-multiplatform" }
compose-material3 = { module = "org.jetbrains.compose.material3:material3", version.ref = "compose-material3" }
compose-material-icons-core = { module = "org.jetbrains.compose.material:material-icons-core", version.ref = "compose-material-icons" }
compose-desktop-macos-arm64 = { module = "org.jetbrains.compose.desktop:desktop-jvm-macos-arm64", version.ref = "compose-multiplatform" }
compose-desktop-linux-x64 = { module = "org.jetbrains.compose.desktop:desktop-jvm-linux-x64", version.ref = "compose-multiplatform" }
jb-navigation-compose = { module = "org.jetbrains.androidx.navigation:navigation-compose", version.ref = "jb-navigation" }
jb-lifecycle-viewmodel-compose = { module = "org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose", version.ref = "jb-lifecycle" }
jb-lifecycle-runtime-compose = { module = "org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose", version.ref = "jb-lifecycle" }

# Client data / DI
sqldelight-android-driver = { module = "app.cash.sqldelight:android-driver", version.ref = "sqldelight" }
sqldelight-sqlite-driver = { module = "app.cash.sqldelight:sqlite-driver", version.ref = "sqldelight" }
sqldelight-coroutines = { module = "app.cash.sqldelight:coroutines-extensions", version.ref = "sqldelight" }
koin-core = { module = "io.insert-koin:koin-core", version.ref = "koin" }
koin-compose = { module = "io.insert-koin:koin-compose", version.ref = "koin" }
koin-compose-viewmodel = { module = "io.insert-koin:koin-compose-viewmodel", version.ref = "koin" }
koin-android = { module = "io.insert-koin:koin-android", version.ref = "koin" }
androidx-datastore-preferences-core = { module = "androidx.datastore:datastore-preferences-core", version.ref = "androidx-datastore" }
androidx-datastore-core = { module = "androidx.datastore:datastore-core", version.ref = "androidx-datastore" }
qrose = { module = "io.github.alexzhirkevich:qrose", version.ref = "qrose" }

# Android only
androidx-activity-compose = { module = "androidx.activity:activity-compose", version.ref = "androidx-activity" }
androidx-appcompat = { module = "androidx.appcompat:appcompat", version.ref = "androidx-appcompat" }
androidx-camera-camera2 = { module = "androidx.camera:camera-camera2", version.ref = "androidx-camerax" }
androidx-camera-lifecycle = { module = "androidx.camera:camera-lifecycle", version.ref = "androidx-camerax" }
androidx-camera-view = { module = "androidx.camera:camera-view", version.ref = "androidx-camerax" }
androidx-work-runtime = { module = "androidx.work:work-runtime", version.ref = "androidx-work" }
zxing-core = { module = "com.google.zxing:core", version.ref = "zxing" }
unifiedpush-connector = { module = "org.unifiedpush.android:connector", version.ref = "unifiedpush" }

[plugins]
kotlin-multiplatform = { id = "org.jetbrains.kotlin.multiplatform", version.ref = "kotlin" }
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
compose-compiler = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
compose-multiplatform = { id = "org.jetbrains.compose", version.ref = "compose-multiplatform" }
android-application = { id = "com.android.application", version.ref = "agp" }
android-kmp-library = { id = "com.android.kotlin.multiplatform.library", version.ref = "agp" }
sqldelight = { id = "app.cash.sqldelight", version.ref = "sqldelight" }
spotless = { id = "com.diffplug.spotless", version.ref = "spotless" }
```

The foojay plugin is applied in `settings.gradle.kts` by literal version (`1.0.0`); the catalog entry documents it.

## Build notes

- **AGP 9 + KMP layout:** `com.android.application` must not be applied in a KMP module, and `androidTarget {}` is a
  configuration error since Kotlin 2.3. Shared KMP modules use `com.android.kotlin.multiplatform.library` with a
  `kotlin { android { … } }` block (not the deprecated `androidLibrary {}`); the app is a separate module with
  `com.android.application` and **no** `kotlin-android` plugin (AGP 9 compiles Kotlin itself).
- **Android KMP library plugin limits:** no build types/flavors, no BuildConfig, Android resources and host tests off by
  default — enable with `androidResources { enable = true }` (needed for Compose resources) and `withHostTest { }`.
- **Compose dependencies:** use the direct coordinates above; Material3 and navigation versions are the ones JetBrains
  pairs with CMP 1.12.2 (alpha/RC). Material icons are frozen at 1.7.3 (still compatible), but new UI does not use
  them: icons are Material Symbols Outlined (weight 250) vectors generated by `tools/design-assets/` (ADR-0016, M0-07).
  Debug-only tooling in a KMP Android library: `dependencies { androidRuntimeClasspath(libs.compose.ui.tooling) }`.
- **JVM Compose UI tests** (jvmTest): `libs.compose.ui.test` plus the desktop runtime for the host OS
  (`compose-desktop-macos-arm64` locally, `compose-desktop-linux-x64` in CI — select by `os.name`/`os.arch` in the build
  script). Tests use `runComposeUiTest` (opt-in `ExperimentalTestApi`).
- **Time:** use `kotlin.time.Instant` and `kotlin.time.Clock` (stable). kotlinx-datetime 0.8.0 has no
  `kotlinx.datetime.Instant`/`Clock`; import `kotlin.time.Instant` explicitly. kotlinx-datetime is for `LocalDate`,
  `TimeZone`, etc.
- **UUIDv7:** `kotlin.uuid.Uuid.generateV7()` (available in the pinned Kotlin 2.4.21) still requires `@OptIn(ExperimentalUuidApi::class)`.
  Wrap it once in `contract/` (`Ids.newId(): String`) so the opt-in lives in one place.
- **JUnit:** JUnit 6 via BOM; Gradle 9 needs `testRuntimeOnly(libs.junit.platform.launcher)`; `kotlin-test-junit5`
  works with JUnit 6 when the BOM is applied (`testImplementation(platform(libs.junit.bom))`).
- **ktlint 1.8.0** embeds the Kotlin 2.2.21 parser: avoid syntax newer than Kotlin 2.2 in source (e.g. new 2.3/2.4
  language features); if a file fails to parse, report it rather than disabling ktlint globally.

## Server notes

- **Exposed 1.x packages:** `org.jetbrains.exposed.v1.core.*` (tables, conditions), `org.jetbrains.exposed.v1.jdbc.*`
  (`Database`, `selectAll`, `insert`, …), `org.jetbrains.exposed.v1.javatime.*` (`timestamp`). Transactions in coroutines:
  `org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction(db = db) { … }` wrapped in `withContext(Dispatchers.IO)`.
  `newSuspendedTransaction` is deprecated. The block's receiver type is whatever `suspendTransaction` declares in 1.6
  (`JdbcTransaction` expected) — use the declared type, the plans' snippets are illustrative.
  ```kotlin
  suspend fun <T> dbQuery(block: suspend JdbcTransaction.() -> T): T =
      withContext(Dispatchers.IO) { suspendTransaction(db = database) { block() } }
  ```
- **Flyway 13** needs `flyway-database-postgresql` next to `flyway-core`.
- **Testcontainers 2.x** renamed artifacts (`testcontainers-postgresql`); use
  `org.testcontainers.postgresql.PostgreSQLContainer("postgres:18-alpine")` (non-generic). No JUnit extension needed —
  the harness uses a lazily started singleton container.
- **Logging:** logback 1.6 (no Janino `<if>`); JSON via the built-in `ch.qos.logback.classic.encoder.JsonEncoder`.
- **Argon2id (Bouncy Castle 1.86):** `Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)` (the no-arg builder is Argon2i),
  `Argon2BytesGenerator().init(params); generateBytes(chars, out)`. BC does not format PHC strings — do it yourself.
- **Ktor 3.6.0** runs on JDK 21. GraalVM native image works only with the CIO engine (manual setup) — not used in M0.

## Docker images

| Image | Tag | Notes |
| --- | --- | --- |
| PostgreSQL | `postgres:18-alpine` | Multi-arch. **Data volume path is `/var/lib/postgresql`** in PG 18 images (was `/var/lib/postgresql/data`) |
| Server base | `eclipse-temurin:21-jre-noble` | Multi-arch; pin `-noble` (plain `21-jre` moved to a newer Ubuntu) |
| Caddy | `caddy:2.11.7-alpine` | Multi-arch |
| ntfy | `binwiederhier/ntfy:v2.32.0` | Multi-arch; `command: serve` |

## GitHub Actions

`actions/checkout@v7`, `actions/setup-java@v6` (Temurin 21), `gradle/actions/setup-gradle@v6`,
`docker/setup-qemu-action@v4`, `docker/setup-buildx-action@v4`, `docker/build-push-action@v7`, `docker/login-action@v4`.
`ubuntu-latest` (24.04) already has the Android SDK (`ANDROID_HOME`, build-tools 37.0.0, android-37.x platforms) —
`android-actions/setup-android` is not needed; its default Java is 17, so always use setup-java 21.

## UnifiedPush

- Android connector `org.unifiedpush.android:connector:3.3.5` (Apache-2.0). Spec AND_3.1.0.
- Client API: `UnifiedPush.tryUseCurrentOrDefaultDistributor(context) { ok -> }`, then `UnifiedPush.register(context)`;
  subclass `PushService` with `onNewEndpoint(endpoint: PushEndpoint, instance)`, `onMessage(message: PushMessage, instance)`,
  `onRegistrationFailed(reason, instance)`, `onUnregistered(instance)`; manifest action
  `org.unifiedpush.android.connector.PUSH_EVENT`. `PushEndpoint` has `url` and `pubKeySet(pubKey, auth)`; the connector
  generates keys and decrypts messages itself.
- **Encryption is mandatory:** messages must be RFC 8291 `aes128gcm` encrypted; ≤ 4096 bytes encrypted (≤ 3993 cleartext).
  VAPID is optional for ntfy (required only by FCM-based distributors).
- Server request: `POST <endpoint>`, `Content-Encoding: aes128gcm`, `Content-Type: application/octet-stream`, `TTL`,
  optional `Urgency`; 2xx = success; **404/410 = delete the registration**.
- Server library: `com.interaso:webpush:1.3.0` (MIT, Kotlin, no dependencies, JDK HttpClient). Always sends VAPID;
  keys can be generated and loaded through its API (store them in the database, not in files).
- SSRF guard recommended: reject endpoints resolving to non-global IPs unless allow-listed.
- **ntfy as distributor:** the phone runs the ntfy app pointed at the family's ntfy server (persistent connection, no
  Firebase for self-hosted servers). UnifiedPush topics start with `up`. Minimal server config: `base-url`, `listen-http`,
  `behind-proxy: true`, `cache-file`, `auth-file`, `auth-default-access: "deny-all"` plus
  `ntfy access '*' 'up*' read-write`. `upstream-base-url` is iOS-only — not needed.
- **Do not use** `embedded-fcm-distributor` — it relies on Google Play Services (ADR-0006).

## Licenses

All listed dependencies are Apache-2.0 or MIT (QRose, ktlint, webpush: MIT; Bouncy Castle: MIT-style) — compatible with
AGPL-3.0 (ADR-0003). Re-check when adding anything new.
