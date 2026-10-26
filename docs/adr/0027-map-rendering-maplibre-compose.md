---
status: accepted
date: 2026-10-25
---
# ADR-0027: The map is drawn with maplibre-compose behind an expect composable

## Context

ADR-0006 chose MapLibre with OpenStreetMap vector tiles (OpenFreeMap by default). The client is Compose Multiplatform,
Android first, web/iOS/desktop later (M7+). Options in October 2026: `org.maplibre.compose:maplibre-compose` 0.20.0
(BSD-3-Clause; Android and iOS Beta, desktop and web Alpha; breaking changes in minor releases; desktop needs Java 25)
and MapLibre Native Android 13.7.1 (BSD-2-Clause, mature, Android only, wrapped in `AndroidView`). Shared UI tests run
on the JVM target with JDK 21.

## Decision

1. Use `maplibre-compose` 0.20.0 (pinned) with the OpenGL Android runtime
   (`maplibre-compose-runtime-opengl-android`, `runtimeOnly` in `client/android`). Never add the `location-runtime-gms`
   or `-hms` artifacts.
2. All map code sits behind `expect fun FamilyMapView(state: MapViewState, …)` in `client/shared`; the dependency is in
   `androidMain` only. The `jvmMain` actual is a simple test stand-in, so JVM tests and the JDK 21 toolchain stay as
   they are.
3. Pins, places, accuracy circles, tracks and stops are GeoJSON layers drawn from our own state; the library's
   own location indicator is not used.
4. Style URLs (light and dark) come from the server (`GET /location/config`); the map shows the style's attribution.
5. Upgrades of the library are deliberate: one plan, check the release notes, re-run the map walkthrough.

## Consequences

- One map API for later platforms; the seam keeps breaking changes inside two files.
- The APK grows by the native MapLibre libraries (≈ 10–15 MB across ABIs).
- If OpenGL rendering fails on a device class, the Vulkan runtime is a one-line switch.

## Alternatives considered

- **MapLibre Native Android in `AndroidView`** — mature, but a second implementation would be needed for iOS and web.
- **osmdroid** — raster tiles only, no vector styles, Android only.
