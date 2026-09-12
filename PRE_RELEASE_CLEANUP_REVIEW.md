# Camino Guard – Pre-Release Cleanup Review

**Review-Basis:** `main` @ `82b5ddcaa8660450670ed442150ed584566185e6`  
**Datum:** 2026-09-12  
**Scope:** Android + Pebble; Wartbarkeit, Reduktion, Zuverlässigkeit, Validität, Testbarkeit.  
**Absicht:** Noch **keine** Funktionsänderungen. Erst dokumentieren, dann in kleinen, testbaren Commits aufräumen.

## Kurzurteil

Camino Guard ist inzwischen nah genug an einer finalen Version, dass ich **keinen Architektur-Umbau** empfehlen würde. Die Grundidee ist gut: Android besitzt Route/ETA/Zustand, die Pebble ist primär Präsentations-Endpunkt, und die kanonischen Routendaten haben einen klaren Besitzer.

Vor einem „final“-Tag würde ich aber einen gezielten Cleanup machen. Dabei sollte „kurzer Code“ **nicht** „wenige Zeilen“ bedeuten. Ziel ist:

- weniger Zustände,
- weniger doppelte Implementierungen,
- weniger tote Pfade,
- weniger implizite Lifecycle-Annahmen,
- weniger Kommentare, die nur den Code nacherzählen,
- dafür klar lesbare Kontrollflüsse und harte Tests an den Übergängen.

Mein Ziel wäre nicht, die App möglichst klein aussehen zu lassen, sondern die Zahl der Dinge zu reduzieren, die falsch werden können.

---

# P0 – vor einer finalen Version prüfen/fixen

## 1. Fresh-install Location-Permission-Flow kann GPS nicht aktivieren

**Dateien:**
- `android/app/src/main/java/com/marukitano/caminoguard/MainActivity.java`
- `android/app/src/main/java/com/marukitano/caminoguard/GpsGyroOrientationController.java`
- `android/app/src/main/java/com/marukitano/caminoguard/CaminoTrackingService.java`

### Problem

`MainActivity.onResume()` ruft zuerst `CaminoTrackingService.setAppForeground(..., true)` auf. Ohne Location-Permission kehrt diese Methode sofort zurück. Danach fordert `GpsGyroOrientationController.start()` die Berechtigung an.

Nach erfolgreicher Berechtigung ruft `onLocationPermissionResult()` aktuell nur:

```java
CaminoTrackingService.addListener(this);
CaminoTrackingService.start(activity);
```

`start()` setzt aber `appForegroundRequested` nicht auf `true`. Ohne eingelockte Route kann `CaminoTrackingService.updateGpsRegistration()` deshalb entscheiden, dass GNSS nicht benötigt wird.

### Empfehlung

Nach erfolgreicher Berechtigung den **Foreground-Zustand** setzen, nicht nur den Service starten. Idealerweise gibt es danach nur noch einen eindeutigen Einstiegspfad.

Beispielrichtung:

```java
CaminoTrackingService.addListener(this);
CaminoTrackingService.setAppForeground(activity, true);
```

Danach prüfen, ob `CaminoTrackingService.start()` als separater öffentlicher Pfad überhaupt noch gebraucht wird.

### Test

Fresh install / App-Daten löschen:

1. App starten.
2. Location erlauben.
3. Keine Route locken.
4. Position muss ohne Pause/Resume und ohne Neustart erscheinen.

**Priorität: sehr hoch.** Kleiner Fix, hoher Nutzen.

---

## 2. `pendingPerformanceEvents` kann bei Init-Fehler unbegrenzt wachsen

**Datei:** `CaminoTrackingService.java`

Aktuell:

```java
private final List<PerformanceEvent> pendingPerformanceEvents =
        new ArrayList<>();
```

Solange `backgroundWalkingPerformanceModel == null` ist, werden Events angehängt. Die Initialisierung des Modells versucht es dreimal. Wenn alle drei Versuche scheitern, bleibt das Modell für diese Service-Lebensdauer `null`; weitere Walking-Events können dann unbegrenzt gesammelt werden.

Auf einem langen Wandertag ist das ein unnötiges RAM-Risiko.

### Empfehlung

Eine der beiden einfachen Varianten:

1. **Bevorzugt:** bounded `ArrayDeque`, z. B. nur die letzten N Events behalten.
2. Nach endgültigem Init-Fehler `performanceUnavailable=true`, Queue leeren und neue Performance-Events verwerfen.

Keine komplizierte Recovery-Maschine bauen. Die Navigation darf von diesem Lernmodell nicht abhängig werden.

### Test

Init-Fehler künstlich erzwingen und mehrere tausend GPS-Events einspeisen. Queue-Größe muss hart begrenzt bleiben.

**Priorität: sehr hoch.** Echter Langzeit-/Fehlerfall.

---

## 3. MapLibre-`MapView`-Lifecycle vollständig machen

**Datei:** `MainActivity.java`

`MapView` bekommt aktuell `onStart`, `onResume`, `onPause`, `onStop`, `onLowMemory`, `onSaveInstanceState` und `onDestroy`, aber im geprüften Stand keinen Aufruf von:

```java
mapView.onCreate(savedInstanceState);
```

Die MapLibre-`MapView`-API dokumentiert `onCreate(Bundle)` als Parent-Lifecycle-Aufruf.

### Empfehlung

Nach `findViewById(R.id.map_view)`:

```java
mapView.onCreate(savedInstanceState);
```

Danach Rotation/Activity-Recreation und Style-Recreation erneut testen.

**Priorität: hoch.** Die App funktioniert derzeit, aber ein unvollständiger Lifecycle ist genau die Art Fehler, die erst nach Recreation/Memory Pressure sichtbar wird.

---

## 4. Pebble-Session beim Service-Ende deterministisch lösen

**Dateien:**
- `CaminoTrackingService.java`
- `CaminoPebbleSession.java`
- `CaminoPebbleRoutePublisher.java`

`CaminoPebbleSession` hält den Listener zwar nur als `WeakReference`, aber `CaminoTrackingService.onDestroy()` setzt den Session-Listener nicht explizit auf `null`.

Damit hängt korrekte Trennung teilweise vom GC ab. In einem kurzen Zeitfenster kann ein alter Publisher noch angesprochen werden, obwohl Bridge/Sender bereits geschlossen werden.

### Empfehlung

Beim Teardown explizit:

```java
CaminoPebbleSession.setListener(null);
```

Noch sauberer: `CaminoPebbleRoutePublisher implements AutoCloseable` und der Publisher besitzt selbst An-/Abmeldung.

**Priorität: hoch.** Lifecycle sollte deterministisch sein, nicht GC-basiert.

---

## 5. Reproduzierbaren Build erzwingen: JDK 21 + CI

`build.gradle.kts` setzt Java Source/Target 17. PebbleKit2 `1.3.0` benötigt im Build-Setup aber eine Java-21-fähige Runtime. Dieser Unterschied hat bereits einen Build auf JDK 17 scheitern lassen.

Zusätzlich hat der aktuelle HEAD keine CI-Statuschecks und es gibt derzeit keinen `.github/workflows`-Ordner.

### Empfehlung

- Gradle/CI-Runtime explizit auf **JDK 21** pinnen.
- Source/Target 17 kann bleiben, wenn gewünscht.
- Minimal-CI auf jedem Push/PR:

```bash
cd android
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Zusätzlich mindestens:

```bash
git diff --check
```

Pebble-Build ebenfalls automatisieren, sobald ein stabiler SDK-Setup im Runner feststeht.

**Priorität: hoch.** Ein reproduzierbarer Build ist Teil der Validität.

---

# P1 – viel Code entfernen, ohne Verhalten zu ändern

## 6. `MeasurementPathProjection` stark vereinfachen

**Datei:** `MeasurementPathProjection.java`

Hier liegt aktuell die beste Möglichkeit, Code **wirklich** kürzer und zugleich sicherer zu machen.

Es existieren drei eng verwandte Projektionen:

- `projectLockedWithin(...)`
- `projectWithin(...)`
- `projectHeightProfileWithin(...)`

`projectLockedWithin(...)` berechnet Route und Height-Profile bereits in einem gemeinsamen Segmentlauf. Die beiden anderen Methoden enthalten große Teile derselben Geometrie noch einmal.

Das vorhandene `MeasurementPathProjectionTest` enthält bereits den wichtigen Regressionstest `lockedProjectionMatchesBothExistingProjectionModes()`.

### Empfehlung

Eine Implementierung als Wahrheit behalten:

```java
static Result projectWithin(...) {
    return projectLockedWithin(...).route;
}

static Result projectHeightProfileWithin(...) {
    return projectLockedWithin(...).heightProfile;
}
```

Oder die gemeinsame Scan-Logik in genau **einen** privaten Helper ziehen.

Zusätzlich sind in `projectWithin(...)` aktuell lokale Werte `userLat` / `userLon` vorhanden, die nicht verwendet werden.

### Nutzen

- potentiell mehrere hundert Zeilen weniger,
- ein Algorithmus statt drei fast gleicher Algorithmen,
- weniger Gefahr, dass später nur eine Variante gefixt wird,
- bestehende Tests eignen sich bereits als Sicherheitsnetz.

**Priorität: hoch für Cleanup, niedriges Verhaltensrisiko bei grünem Testlauf.**

---

## 7. Sicher bestätigten toten Pebble-Code entfernen

**Dateien:**
- `pebble/src/c/main.c`
- `pebble/package.json`

### 7.1 Alter Progress-Icon-Pfad

Die Fortschrittszeile wird inzwischen direkt durch `progress_row(...)` gezeichnet. Trotzdem existieren noch:

- `DASH_ICON_PROGRESS`
- `draw_progress_icon(...)`
- der generische Fallback in `draw_dashboard_icon(...)`

Entfernen. `draw_dashboard_icon` sollte nur die tatsächlich existierenden Icon-Typen behandeln.

### 7.2 Alte Marker-`GPath`s

Noch vorhanden:

- `s_marker_outline_path`
- `s_marker_fill_path`
- `s_marker_outline_points`
- `s_marker_fill_points`
- `s_marker_outline_info`
- `s_marker_fill_info`

Sie werden erzeugt und zerstört, der aktuelle Map-Marker wird aber als weißer/roter Kreis durch `draw_position_marker(...)` gezeichnet.

Diese GPaths können weg.

### 7.3 Nicht mehr geladene Ressourcen

`package.json` enthält noch:

- `FONT_MEGAFONT_12`
- `ICON_SHELL`

Der aktuelle C-Code lädt nur `FONT_MEGAFONT_14`, `FONT_MEGAFONT_18`, `ICON_HEART`, `ICON_BLOOD`, `ICON_SHOE`.

Wenn kein weiterer Consumer vorhanden ist: aus `package.json` entfernen.

### 7.4 Alte Message Keys

`package.json` enthält noch historische Keys wie:

- `NEXT_DISTANCE`
- `NEXT_TIME`
- `ALARM_ACTIVE`
- `ROUTE_VALID`
- `NEXT_NAME`
- `FLAT_SPEED`

Sie gehören nicht mehr zum aktuell geprüften Android↔Pebble-Pfad. Vor Löschung einmal mit `rg` bestätigen und anschließend entfernen.

### 7.5 Generated-header-Kompatibilität

Am Anfang von `main.c`:

```c
#ifndef MESSAGE_KEY_STOP_IS_GOAL
#define MESSAGE_KEY_STOP_IS_GOAL 29
#endif
```

Vor einer finalen Version würde ich keine alten generierten Header mehr unterstützen. Sauberer Build soll Voraussetzung sein. Shim entfernen.

---

## 8. Protokollnamen an ihre heutige Bedeutung anpassen

Die Semantik hat sich weiterentwickelt, die Namen teilweise nicht.

### Beispiele

`STOP_IS_GOAL` ist heute ein Bitfeld:

- bit 0 = goal
- bit 1 = start
- bit 2 = passed

Ein Name wie `STOP_FLAGS` wäre korrekt. Die **Nummer 29 kann gleich bleiben**, damit kein Protokollbruch nötig ist.

`CaminoPebbleBridge.sendTimetableStop(... String arrivalTime ...)` transportiert inzwischen keine Ankunftsuhrzeit mehr, sondern Restdauer `H:MM`.

Parameter in `remainingDuration` umbenennen.

`STOP_TIME` kann zunächst als Wire-Key bleiben, wenn wir den Protokollumfang klein halten wollen.

**Regel:** interne Namen sollen die aktuelle Wahrheit ausdrücken. Historische Bedeutung gehört in Git, nicht in Variablennamen.

---

## 9. ETA-„onRoute“-Benennung an die neue Alternative-Route-Semantik anpassen

**Dateien:**
- `CaminoTrackingService.java`
- `LockedTimetableEtaAuthority.java`
- `LockedTimetableEtaAuthorityTest.java`

Produktion projiziert für Timetable/ETA bewusst auf den **nächsten Punkt der eingelockten Camino-Route**, auch wenn der Wanderer physisch außerhalb des OFF-ROUTE-Schwellwerts läuft.

In `CaminoTrackingService` ist das inzwischen als `hasRouteProjection` erkennbar. `LockedTimetableEtaAuthority` nennt denselben booleschen Eingang aber weiterhin `onRoute`, und Tests sprechen noch von „off route freezes progress“.

Das ist gefährlich, weil ein späterer Reviewer `onRoute` leicht wieder mit dem Warnschwellwert verbindet und damit die gewünschte Alternative-Route-Funktion kaputtmacht.

### Empfehlung

- Authority-Parameter in `hasRouteProjection` / `progressAvailable` umbenennen.
- Kommentare entsprechend kürzen.
- Testnamen aktualisieren.
- Zusätzlich einen Test auf höherer Ebene hinzufügen:
  - GPS physisch weit neben Route,
  - nearest locked-route projection existiert,
  - Timetable-Progress folgt dieser Projektion,
  - OFF-ROUTE-Warnung bleibt trotzdem aktiv.

**Kein Verhaltenswechsel**, nur Semantik unmissverständlich machen.

---

## 10. Doppelte Pebble-Callbacks beim Attach vermeiden

`CaminoPebbleSession.setListener(listener)` ruft bei bereits geöffneter Watch-App nacheinander:

```java
listener.onPebbleOpened();
listener.onPebblePageChanged(page);
```

Beide Pfade können sofort Daten senden. Der Publisher liest beim Konstruktor außerdem bereits `CaminoPebbleSession.page()`.

### Empfehlung

Ein eindeutiger Attach-Pfad. Zum Beispiel nur `onPebbleOpened()` und dort den aktuellen Page-State verwenden, oder ein einzelner `onPebbleAttached(page)`-Callback.

Weniger doppelte Sends, weniger Flags, leichter testbar.

---

## 11. Alte Motion-/Heading-API-Kandidaten mit `rg` verifizieren und löschen

Vor dem Entfernen einmal lokal bestätigen:

```bash
cd ~/git/Camino-Guard
rg '\bMotionStateDetector\b' android/app/src/main/java android/app/src/test
rg '\bphoneHeadingDeg\b' android/app/src/main/java android/app/src/test
rg 'augmentedCourse\(' android/app/src/main/java
```

Kandidaten:

- `MotionStateDetector.java` sieht wie der Vorgänger von `GpsMotionStateDetector` aus.
- `CaminoTrackingService.Snapshot.phoneHeadingDeg` wird im aktuellen Publish-Pfad immer als `null` erzeugt; der Foreground-Gyro lebt inzwischen im `GpsGyroOrientationController`.
- mehrere `augmentedCourse(...)`-Overloads sehen nach Altbestand aus; aktuelle Darstellung benutzt direkt `augmentedHeading(...)`.

Nur löschen, wenn `rg` keine echten Consumer zeigt.

---

# P1 – Kommentare und Diagnostik

## 12. Kommentare deutlich reduzieren – aber die richtigen behalten

`ARCHITECTURE.md` formuliert die richtige Regel bereits: Kommentare sollen **warum / Constraints** erklären.

### Behalten

Kommentare zu:

- Wire-Protokollen und Bitfeldern,
- Road-RLE-Format,
- Map-Payload-Versionen,
- Koordinatentransformationen,
- bewusst getrenntem OFF-ROUTE-Schwellwert vs. nearest-route projection,
- ETA-Cadence,
- ungewöhnlichen Android/MapLibre-Lifecycle-Workarounds,
- bewusstem Verhalten bei GNSS-Jitter / Stationary.

### Löschen oder stark kürzen

Kommentare, die nur sagen, was die nächsten zwei Zeilen offensichtlich tun.

Beispiel für einen stale Kommentar in `main.c`:

> „Use the font's actual line height to identify a one-line layout“

Die Implementierung erkennt die Einzeiligkeit tatsächlich über die gemessene Breite. Kommentar entfernen oder korrekt auf einen Satz kürzen.

### Keine Patch-Historie im Code

„old“, „previous patch“, „kept for compatibility“ usw. nur behalten, wenn es eine echte externe Kompatibilitätsanforderung gibt. Sonst ist Git die Historie.

---

## 13. Pebble-C nicht weiter minifizieren

`main.c` enthält inzwischen viele komplette State-Machine-Funktionen als extrem lange Einzeiler.

Das ist **nicht** guter kurzer Code. Es spart Zeilen, aber versteckt:

- Rücksprünge,
- Timer-Lifecycle,
- Bounds-Checks,
- Zustandswechsel,
- Fehlerpfade.

Gerade bei `touch_*`, Scroll-Animation, AppMessage und Road-Chunks erhöht das Review-Risiko.

### Empfehlung

- ein Statement pro Zeile,
- kleine Early Returns,
- keine künstlichen Leerzeilenorgien,
- Funktionen möglichst < ca. 30–50 logische Zeilen,
- Kommentare nur bei nicht offensichtlichen Invarianten.

**Kurzer Code = wenige Konzepte und wenig Duplikation, nicht wenige Newlines.**

---

## 14. Permanente Heading-Diagnostik vor Release abschalten oder gaten

`CaminoHeadingTrace` kann zwei interne Dateien von jeweils bis zu etwa 8 MB halten. Außerdem werden GPS-/Heading-Diagnosen während des Betriebs persistent geschrieben.

Das war für das Debugging sinnvoll. Für eine finale Version sollte es entweder:

- per Build-/Config-Flag deaktivierbar sein,
- nur bei einem expliziten Diagnosemodus schreiben,
- oder vollständig entfernt werden, wenn die Richtung über mehrere Feldtests stabil ist.

Wenn `CaminoHeadingTrace` entfällt, können auch die nur dafür existierenden `lastTrace*`-Felder aus dem Orientation-Controller verschwinden.

Zusätzlich sollte Release-Logging keine Messwerte unnötig ausgeben. Beispielsweise loggt `LibreLinkUpClient` derzeit den formatierten Glukosewert. Für Release besser nur Status/Fehler loggen, nicht den Messwert selbst.

---

# P2 – Robustheit / Security / Semantik

## 15. Libre-Zugangsdaten + Android-Backup bewusst entscheiden

`LibreLinkUpStore` speichert E-Mail und Passwort in normalen privaten `SharedPreferences`. Gleichzeitig steht im Manifest:

```xml
android:allowBackup="true"
```

Für eine private App gibt es zwei vernünftige Richtungen:

- **minimal:** Backup abschalten (`allowBackup=false`), wenn App-Backup nicht benötigt wird;
- oder Backup-Regeln definieren, die Credentials ausschließen.

Keystore-Verschlüsselung ist möglich, erhöht aber Code und Komplexität. Für dieses Projekt würde ich zuerst die einfachste klare Policy wählen.

---

## 16. Service-Kommentare und Permission-Invariante vereinheitlichen

Kommentare im Tracking-Service sagen sinngemäß, dass Libre/Pebble weiterlaufen können, während GPS aus ist. Gleichzeitig verweigern mehrere öffentliche Service-Einstiege ihren Dienst komplett, wenn Fine Location fehlt, und `onCreate()` stoppt den Service ohne Location-Permission.

Das ist kein zwingender Bug, aber zwei verschiedene mentale Modelle.

Vor Release eine Regel festlegen:

- **Variante A:** Location-Permission ist Voraussetzung für den gesamten Service. Dann Kommentare vereinfachen.
- **Variante B:** Service ist unabhängig; nur GNSS-Registrierung braucht Location. Dann Permission-Checks auf GNSS beschränken.

Ich würde keine Mischform behalten.

---

## 17. Statisches `latestSnapshot` bewusst behandeln

`CaminoTrackingService.latestSnapshot` ist statisch und wird beim Service-Teardown nicht zurückgesetzt.

Das kann gewollt sein (letzte bekannte Position sofort anzeigen), kann aber bei Service-Recreation auch veralteten Zustand präsentieren.

Empfehlung: Entscheidung explizit machen.

- Falls Last-known-state gewünscht: Alter/Timestamp als Stale-Kriterium verwenden.
- Falls nicht: in `onDestroy()` zurücksetzen.

Dazu ein Test für Service-Recreation.

---

## 18. Restdauer nicht langfristig aus modulo-24h-Ankunft rekonstruieren

Die Timetable-Engine speichert Ankunft als `arrivalMinutesOfDay` (0..1439). Die Pebble-Restdauer wird anschließend berechnet als:

```text
arrivalMinutesOfDay - currentMinutesOfDay
```

und bei negativem Ergebnis werden 24 h addiert.

Für normale Tagesetappen ist das okay. Es verliert aber die Tagesinformation und kann bei >24-h-Zuständen oder ungewöhnlichen Clock-Sprüngen falsch wrappen.

Langfristig sauberer: Restdauer bzw. absoluten ETA-Offset direkt im Timetable-State transportieren.

Mindestens Tests für:

- 23:50 → Ziel 00:20,
- Clock-/Timezone-Änderung,
- unerwartet lange ETA.

Kein Release-Blocker für typische Camino-Tagesetappen.

---

## 19. Unbekannter Progress sollte keinen semantischen blauen Fortschritt vortäuschen

Im aktuellen Pebble-`progress_row()` bekommt auch `UNKNOWN_METRIC` wegen der Mindestbreite einen kleinen blauen Balken und den Text `--`.

Technisch stabil, visuell aber semantisch uneindeutig.

Empfehlung: bei unbekanntem Wert entweder nur `--` ohne blauen Fortschritt oder einen bewusst neutralen Placeholder darstellen.

---

# Tests: was schon gut ist

Die vorhandenen Android-Tests sind für die Kernlogik überraschend ordentlich. Besonders wertvoll sind Tests für:

- Timetable-Engine,
- Timetable-Plan/Stops,
- GPS-Motion-State,
- Locked Navigation,
- ETA Authority,
- Measurement Engine,
- MeasurementPathProjection.

Das ist wichtig, weil es uns erlaubt, jetzt **Code zu löschen**, statt nur neue Abstraktionen hinzuzufügen.

Die größte Lücke liegt nicht in der Mathematik, sondern dort, wo die letzten echten Feldbugs aufgetreten sind:

- Activity/Service-Lifecycle,
- Permission-Übergänge,
- MapLibre-Style-Recreation,
- Pebble-Session/Transport,
- Fehler-Recovery über Stunden.

---

# Neue Tests, die ich vor „final“ ergänzen würde

## Android

1. **Fresh permission grant**
   - kein Lock,
   - Permission erst während Laufzeit,
   - GPS wird danach sofort registriert.

2. **Bounded performance queue**
   - Init des Performance-Modells schlägt dauerhaft fehl,
   - tausende Events,
   - Speicherstruktur bleibt begrenzt.

3. **Nearest locked-route projection trotz physischem OFF-ROUTE**
   - Timetable-Progress folgt nearest projection,
   - Warnstatus bleibt threshold-basiert.

4. **Style recreation marker seed**
   - neuer GeoJsonSource,
   - identischer GPS-Timestamp,
   - Position wird trotzdem sofort neu gesetzt.

5. **Pebble session lifecycle**
   - attach bei bereits offener Watch,
   - keine doppelten Full Sends,
   - close/service destroy → kein Callback auf alten Publisher.

6. **Timetable duration midnight**
   - z. B. 23:50 → 00:20 = 30 min.

7. **Road codec golden test**
   - Android RLE-Encoder mit festen Vektoren,
   - dieselben Vektoren auf Pebble-Seite dekodierbar.

8. **Protocol consistency**
   - Message-Key-Namen/Nummern aus `pebble/package.json` gegen Android-Konstanten prüfen.

## Pebble

Mindestens Build-Gate plus kleine pure C-Tests/Hosttests, wenn ohne großen SDK-Aufwand möglich, für:

- `H:MM`-Parser,
- Road-RLE-Decoder,
- Boundary-Stop-Bounce-Zustände,
- Map-Payload-Längen/Bereiche.

Wenn Hosttests unverhältnismäßig teuer werden, lieber wenige gute Protokoll-Golden-Tests auf Android + echter Pebble-Build als ein großes Testframework nur für die Uhr.

---

# Pre-Release-Feldtest

Nach jedem Cleanup unverändert durchlaufen:

- Fresh install → Location erlauben → Position erscheint sofort.
- App 2+ Stunden laufen/background → öffnen → Punkt/Pfeil sofort sichtbar.
- Style/Map neu laden → Marker bleibt sichtbar.
- Route locken, absichtlich Alternativweg gehen → Progress/ETA folgt nearest locked Camino; OFF-ROUTE-Warnung bleibt getrennt.
- 5+ Minuten stehen → Karte/Progress friert sinnvoll, ETA läuft weiter.
- Watch öffnen/schließen/neu öffnen.
- Timetable bis Start und Ziel browsen → Bounce korrekt.
- Zielpunkt erst bei erreichtem Ziel gefüllt.
- Handy/Pebble-Verbindung kurz trennen und wieder verbinden.
- Wetter/Libre ohne Netz → Navigation darf nicht beeinflusst werden.
- Android-App aus Recent Tasks entfernen → erwartetes Service-Verhalten prüfen.

---

# Empfohlene Cleanup-Reihenfolge

## Commit 1 – Safety net

- JDK 21 pinnen.
- Android CI: unit tests + lint + assemble.
- `git diff --check`.
- 2–3 gezielte Regressionstests für Permission, Projection-Semantik und Queue-Bound.

## Commit 2 – echte Reliability-Fixes

- Permission-Flow.
- bounded Performance-Queue.
- MapView `onCreate`.
- Pebble listener teardown.

Danach Feldtest.

## Commit 3 – tote Teile löschen

- Pebble Progress-Icon-Altlast.
- alte Marker-GPaths.
- ungenutzte Pebble-Ressourcen.
- alte Message Keys.
- bestätigte tote Android-Klassen/Felder/Overloads.

Danach vollständiger Build/Test.

## Commit 4 – Duplikation reduzieren

- `MeasurementPathProjection` auf eine Kernimplementierung reduzieren.
- vorhandene Projection-Tests unverändert/grün halten.

Danach Feldtest für Route/Alternative-Route/Höhenprofil.

## Commit 5 – Namen + Kommentare + Diagnostik

- `STOP_FLAGS` / `remainingDuration` / `hasRouteProjection`.
- stale Kommentare löschen.
- HeadingTrace gaten/entfernen.
- C-Code lesbar formatieren, **nicht minifizieren**.

---

# Was ich ausdrücklich nicht machen würde

- Keine neue Framework-/Dependency-Schicht nur fürs Cleanup.
- Keine komplette Kotlin-Migration.
- Keine Dependency-Injection-Library.
- Kein MVVM-/Clean-Architecture-Umbau nur wegen Lehrbuchästhetik.
- Keine funktionierende Map-Geometrie „schöner“ refactoren, bevor Tests sie schützen.
- Keine großen Dateien blind in zehn Klassen zerlegen. Erst tote Logik und Duplikation entfernen.

Große Dateien, die sich **danach** noch für einen zweiten Review lohnen:

- `MeasurementEngine.java`
- `CaminoStagePathResolver.java`
- `CaminoController.java`
- `CaminoHeightProfileView.java`
- `CaminoHeightProfileController.java`
- `CaminoTrackingService.java`
- `CaminoPebbleRoutePublisher.java`
- `GpsGyroOrientationController.java`

Dateigröße allein ist aber kein Fehler. Eine Aufteilung ist nur sinnvoll, wenn eine eigenständige Verantwortung herausfällt.

---

# Definition of Done für „final“

Ich würde Camino Guard als cleanup-fertig ansehen, wenn:

- P0-Punkte behoben oder bewusst verworfen und dokumentiert sind,
- Android Unit Tests + Lint + Assemble reproduzierbar mit JDK 21 laufen,
- Pebble aus clean build baut,
- keine bestätigten toten Ressourcen/Message-Keys/Renderer mehr vorhanden sind,
- Projection-Code nur eine algorithmische Wahrheit besitzt,
- Service/Watch-Lifecycle deterministisch auf- und abgebaut wird,
- Kommentare überwiegend Invarianten und Gründe erklären,
- die Feldtest-Liste mindestens einmal nach dem letzten strukturellen Cleanup bestanden wurde.

## Schluss

Der Code braucht jetzt **kein neues Feature-Fundament**, sondern einen kontrollierten Reduktionspass. Die größten Gewinne kommen aus wenigen Stellen: Permission/Lifecycle härten, die Performance-Queue begrenzen, Projection-Duplikation entfernen, Pebble-Altlasten löschen und CI festziehen.

Wenn diese Punkte erledigt sind und die nächsten Feldtests sauber laufen, würde ich nicht weiter „aufräumen um des Aufräumens willen“. Ab dann ist Stabilität wertvoller als weitere Schönheit.