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
