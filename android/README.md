# Camino Guard Android

Native Android companion for Camino Guard.

The first milestone is intentionally dependency-free: prove the local Android toolchain, Gradle build, APK installation and app launch before adding MapLibre, offline map data, GPS, route matching or Pebble communication.

## Toolchain

- JDK 17
- Android SDK Platform 36
- Android Build Tools 36.0.0
- Android Gradle Plugin 9.3.0
- Gradle 9.5.0

## Build

```bash
./gradlew assembleDebug
```

APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Install over USB

```bash
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## MapLibre rendering test

The next milestone adds MapLibre Native Android as the map renderer.

For this test only, the app loads the public MapLibre demo style over HTTPS:

```text
https://demotiles.maplibre.org/style.json
```

This is deliberately an online rendering test. Camino Guard's actual basemap
will be replaced by local offline map data in the next map-data milestone.
No Camino route geometry is bundled into the Android app yet.

