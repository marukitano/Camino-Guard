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

## Bundled offline basemap

`tools/fetch_offline_basemap.py` creates the real Camino Guard basemap as a
PMTiles asset for the Android application. The region is deliberately limited
to the Geofabrik Spain and Portugal country polygons plus a small corridor
around the first Camino Frances stage from Saint-Jean-Pied-de-Port to
Roncesvalles. It does not include the French Camino route groups.

The tool downloads only the required tile ranges from the current Protomaps
daily planet build and writes:

```text
android/app/src/main/assets/maps/iberia.pmtiles
android/app/src/main/assets/maps/iberia.metadata.json
```

The large generated files are ignored by Git. They are nevertheless ordinary
Android assets and will be included in an APK built on a machine where they are
present.

Default detail is zoom 0 through 15:

```bash
python3 tools/fetch_offline_basemap.py
```

Use `--force` to replace an existing local basemap.

Map data attribution: Protomaps / OpenStreetMap contributors.
