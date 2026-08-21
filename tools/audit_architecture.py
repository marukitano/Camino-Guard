#!/usr/bin/env python3
from pathlib import Path
import json
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "android/app/src/main/java/com/marukitano/caminoguard"
ASSETS = ROOT / "android/app/src/main/assets"
CAMINO = ASSETS / "camino"
errors = []


def check(condition, message):
    if not condition:
        errors.append(message)


config_path = ASSETS / "config/camino-config.json"
canonical = CAMINO / "camino-global.json"
style_path = ASSETS / "styles/camino-basic.json"
main_path = JAVA / "MainActivity.java"
controller_path = JAVA / "CaminoController.java"
repository_path = JAVA / "CaminoRepository.java"
network_path = JAVA / "CaminoNetwork.java"
measurement_path = JAVA / "MeasurementEngine.java"
offline_map_path = JAVA / "OfflineMapRepository.java"
map_style_path = JAVA / "MapStyleProvider.java"
navigation_path = JAVA / "NavigationController.java"

check(config_path.is_file(), "missing camino-config.json")
check(canonical.is_file(), "missing camino-global.json")
check(controller_path.is_file(), "missing CaminoController.java")
check(repository_path.is_file(), "missing CaminoRepository.java")
check(network_path.is_file(), "missing CaminoNetwork.java")
check(measurement_path.is_file(), "missing MeasurementEngine.java")
check(offline_map_path.is_file(), "missing OfflineMapRepository.java")
check(map_style_path.is_file(), "missing MapStyleProvider.java")
check(navigation_path.is_file(), "missing NavigationController.java")
check(not (JAVA / "CaminoTapDebugController.java").exists(),
      "old CaminoTapDebugController.java still exists")

for name in (
    "tracks-global.geojson",
    "tracks-global-debug.geojson",
    "debug-schaffhausen-tracks.geojson",
):
    check(not (CAMINO / name).exists(),
          f"obsolete duplicate runtime dataset remains: {name}")

if config_path.is_file():
    config = json.loads(config_path.read_text())
    check(config.get("data", {}).get("caminoAsset")
          == "camino/camino-global.json",
          "config does not point to canonical Camino asset")

if style_path.is_file():
    style = json.loads(style_path.read_text())
    source = style.get("sources", {}).get("camino-tracks", {})
    data = source.get("data")
    check(isinstance(data, dict)
          and data.get("type") == "FeatureCollection",
          "camino-tracks source is not runtime/in-memory GeoJSON")

if main_path.is_file():
    main = main_path.read_text()
    check("DEBUG_SCHAFFHAUSEN_MAP" not in main,
          "Schaffhausen behavior switch remains")
    check("DEBUG_CAMINO_TAP_ALMERIA" not in main,
          "Almeria behavior switch remains")
    check("useSchaffhausenDebugMap" not in main,
          "regional behavior method remains")
    check("CaminoMapRenderer" in main,
          "CaminoMapRenderer is not wired")

if controller_path.is_file():
    controller = controller_path.read_text()
    check("CaminoTapDebugController" not in controller,
          "old controller name remains")
    check("camino/camino-global.json" not in controller,
          "controller hardcodes Camino path instead of config")
    check(not re.search(r"//\s*CAMINO_[A-Z0-9_]+", controller),
          "patch-version comments remain")

if repository_path.is_file():
    repository = repository_path.read_text()
    check("new JSONObject(" in repository,
          "CaminoRepository does not own JSON parsing")
    check("prepareRouteGeometry(" in repository,
          "CaminoRepository does not own static route geometry preparation")

if controller_path.is_file():
    controller = controller_path.read_text()
    check("new JSONObject(" not in controller,
          "CaminoController still parses Camino JSON")
    check("private static final class CaminoRoute" not in controller,
          "CaminoController still owns CaminoRoute domain model")
    check("private static final class RouteTrack" not in controller,
          "CaminoController still owns RouteTrack domain model")
    check("CaminoRepository" in controller,
          "CaminoController is not wired to CaminoRepository")

if network_path.is_file():
    network = network_path.read_text()
    check("PriorityQueue<NodeDistance>" in network,
          "CaminoNetwork does not own Dijkstra")
    check("void rebuild(" in network,
          "CaminoNetwork does not own graph construction")

if controller_path.is_file():
    controller = controller_path.read_text()
    check("PriorityQueue<" not in controller,
          "CaminoController still owns Dijkstra")
    check("private void buildNetworkGraph()" not in controller,
          "CaminoController still owns graph construction")
    check("CaminoNetwork" in controller,
          "CaminoController is not wired to CaminoNetwork")

if measurement_path.is_file():
    measurement = measurement_path.read_text()
    check("MeasurementPath buildMeasurementPath(" in measurement,
          "MeasurementEngine does not own measurement path construction")
    check("appendTrackProfileSlice(" in measurement,
          "MeasurementEngine does not own height-profile geometry")
    check("network.findPath(" in measurement,
          "MeasurementEngine does not consume CaminoNetwork")

if controller_path.is_file():
    controller = controller_path.read_text()
    check("private MeasurementPath buildMeasurementPath(" not in controller,
          "CaminoController still owns measurement path construction")
    check("private List<Feature> buildRoutePieces(" not in controller,
          "CaminoController still owns measurement rendering geometry")
    check("private void appendTrackProfileSlice(" not in controller,
          "CaminoController still owns profile geometry")
    check("MeasurementEngine" in controller,
          "CaminoController is not wired to MeasurementEngine")

if offline_map_path.is_file():
    offline_map = offline_map_path.read_text()
    check("MessageDigest.getInstance(" in offline_map,
          "OfflineMapRepository does not own PMTiles integrity verification")
    check("ensureMapInstalled(" in offline_map,
          "OfflineMapRepository does not own PMTiles installation")

if map_style_path.is_file():
    map_style = map_style_path.read_text()
    check("__PMTILES_URL__" in map_style,
          "MapStyleProvider does not own style token replacement")
    check("MapStyleConfig.apply(" in map_style,
          "MapStyleProvider does not apply global map style config")

main_activity_path = JAVA / "MainActivity.java"
if main_activity_path.is_file():
    main_activity = main_activity_path.read_text()
    check("ensureMapInstalled(" not in main_activity,
          "MainActivity still owns PMTiles installation")
    check("MessageDigest" not in main_activity,
          "MainActivity still owns PMTiles integrity verification")
    check("__PMTILES_URL__" not in main_activity,
          "MainActivity still owns style token replacement")
    check("OfflineMapRepository" in main_activity,
          "MainActivity is not wired to OfflineMapRepository")
    check("MapStyleProvider" in main_activity,
          "MainActivity is not wired to MapStyleProvider")

if navigation_path.is_file():
    navigation = navigation_path.read_text()
    check("void toggleFollow()" in navigation,
          "NavigationController does not own follow state")
    check("void handleCameraMoveStarted(" in navigation,
          "NavigationController does not own gesture suspension")
    check("void handleCameraIdle()" in navigation,
          "NavigationController does not own delayed resume")
    check("CameraUpdateFactory.newCameraPosition(" in navigation,
          "NavigationController does not own camera follow mechanics")
    check("GpsGyroOrientationController" in navigation,
          "NavigationController is not wired to external GPS orientation controller")

if controller_path.is_file():
    controller = controller_path.read_text()
    check("navigationFollowEnabled" not in controller,
          "CaminoController still owns navigation follow state")
    check("private void applyNavigationFollow(" not in controller,
          "CaminoController still owns navigation camera mechanics")
    check("private void handleNavigationCameraMoveStarted(" not in controller,
          "CaminoController still owns navigation gesture suspension")
    check("NavigationController" in controller,
          "CaminoController is not wired to NavigationController")
    check("private double navigationBearingAtPosition()" in controller,
          "route-aware navigation bearing left CaminoController unexpectedly")

if canonical.is_file():
    root = json.loads(canonical.read_text())
    check(isinstance(root.get("routes"), list) and root["routes"],
          "canonical Camino dataset has no routes")

if errors:
    print("ARCHITECTURE AUDIT FAILED")
    for error in errors:
        print("  - " + error)
    sys.exit(1)

print("ARCHITECTURE AUDIT OK")
print("  one canonical Camino dataset")
print("  one global controller")
print("  one Camino repository / domain owner")
print("  one Camino graph / shortest-path engine")
print("  one measurement / height-profile engine")
print("  one offline-map repository")
print("  one runtime map-style provider")
print("  one navigation follow / camera controller")
print("  one runtime Camino map renderer")
print("  one immutable config file")
print("  no regional Camino behavior switch")
