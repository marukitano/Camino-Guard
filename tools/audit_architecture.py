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
renderer_path = JAVA / "CaminoMapRenderer.java"
geomath_path = JAVA / "GeoMath.java"
network_path = JAVA / "CaminoNetwork.java"
measurement_path = JAVA / "MeasurementEngine.java"
offline_map_path = JAVA / "OfflineMapRepository.java"
map_style_path = JAVA / "MapStyleProvider.java"
navigation_path = JAVA / "NavigationController.java"
live_navigation_camera_path = JAVA / "LiveNavigationCameraController.java"
projection_path = JAVA / "CaminoProjectionEngine.java"
info_presenter_path = JAVA / "CaminoInfoPresenter.java"
info_controller_path = JAVA / "CaminoInfoController.java"
map_coordinator_path = JAVA / "MapCoordinator.java"
interaction_renderer_path = JAVA / "CaminoInteractionRenderer.java"
travel_stats_path = JAVA / "TravelStatsController.java"
drag_path = JAVA / "CaminoDragController.java"
selection_path = JAVA / "CaminoSelectionController.java"
info_panel_path = JAVA / "CaminoInfoPanel.java"
info_chevron_path = JAVA / "CaminoChevronView.java"
info_navigation_button_path = JAVA / "CaminoNavigationButton.java"
height_profile_view_path = JAVA / "CaminoHeightProfileView.java"
height_profile_model_path = JAVA / "CaminoHeightProfileModel.java"
height_profile_controller_path = JAVA / "CaminoHeightProfileController.java"
tracking_service_path = JAVA / "CaminoTrackingService.java"
direction_tracker_path = JAVA / "CaminoDirectionTracker.java"

dead_code_audit_path = ROOT / "tools/audit_dead_code.py"
check(dead_code_audit_path.is_file(), "missing dead-code audit tool")

check(config_path.is_file(), "missing camino-config.json")
check(canonical.is_file(), "missing camino-global.json")
check(controller_path.is_file(), "missing CaminoController.java")
check(repository_path.is_file(), "missing CaminoRepository.java")
check(renderer_path.is_file(), "missing CaminoMapRenderer.java")
check(geomath_path.is_file(), "missing GeoMath.java")
check(network_path.is_file(), "missing CaminoNetwork.java")
check(measurement_path.is_file(), "missing MeasurementEngine.java")
check(offline_map_path.is_file(), "missing OfflineMapRepository.java")
check(map_style_path.is_file(), "missing MapStyleProvider.java")
check(navigation_path.is_file(), "missing NavigationController.java")
check(live_navigation_camera_path.is_file(), "missing LiveNavigationCameraController.java")
check(projection_path.is_file(), "missing CaminoProjectionEngine.java")
check(info_presenter_path.is_file(), "missing CaminoInfoPresenter.java")
check(info_controller_path.is_file(), "missing CaminoInfoController.java")
check(map_coordinator_path.is_file(), "missing MapCoordinator.java")
check(interaction_renderer_path.is_file(), "missing CaminoInteractionRenderer.java")
check(travel_stats_path.is_file(), "missing TravelStatsController.java")
check(drag_path.is_file(), "missing CaminoDragController.java")
check(selection_path.is_file(), "missing CaminoSelectionController.java")
check(info_panel_path.is_file(), "missing CaminoInfoPanel.java")
check(info_chevron_path.is_file(), "missing CaminoChevronView.java")
check(info_navigation_button_path.is_file(), "missing CaminoNavigationButton.java")
check(height_profile_view_path.is_file(), "missing CaminoHeightProfileView.java")
check(height_profile_model_path.is_file(), "missing CaminoHeightProfileModel.java")
check(height_profile_controller_path.is_file(), "missing CaminoHeightProfileController.java")
check(tracking_service_path.is_file(), "missing CaminoTrackingService.java")
check(direction_tracker_path.is_file(), "missing CaminoDirectionTracker.java")
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

if renderer_path.is_file():
    renderer = renderer_path.read_text()
    check("new JSONObject(" not in renderer,
          "CaminoMapRenderer still parses canonical JSON")
    check("readAssetText(" not in renderer,
          "CaminoMapRenderer still reads canonical asset text")
    check("data.caminoAsset" not in renderer,
          "CaminoMapRenderer still owns canonical asset lookup")
    check("List<CaminoRoute>" in renderer
          and "RouteTrack" in renderer,
          "CaminoMapRenderer does not render parsed domain objects")

if geomath_path.is_file():
    geomath = geomath_path.read_text()
    for required_method in (
        "static double distanceMeters(",
        "static double bearingDegrees(",
        "static LatLng destination(",
        "static double normalizeDegrees(",
        "static double shortestAngleDegrees(",
    ):
        check(required_method in geomath,
              "GeoMath missing shared method: " + required_method)

    for java_path in JAVA.glob("*.java"):
        if java_path == geomath_path:
            continue

        java_text = java_path.read_text()

        check("6371008.8" not in java_text,
              f"duplicate Earth radius literal remains in {java_path.name}")
        check("CaminoRepository.distanceMeters(" not in java_text,
              f"old repository distance helper remains in {java_path.name}")

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

if navigation_path.is_file():
    navigation = navigation_path.read_text()
    check("void cycleMode()" in navigation
          and "Mode currentMode()" in navigation
          and "NORTH_UP" in navigation
          and "COURSE_UP" in navigation,
          "NavigationController does not own three-state navigation mode")
    check("void handleCameraMoveStarted(" in navigation,
          "NavigationController does not own gesture suspension")
    check("void handleCameraIdle()" in navigation
          and "Follow suspension is sticky" in navigation,
          "NavigationController does not own sticky follow suspension")
    check("CameraUpdateFactory.newCameraPosition(" in navigation,
          "NavigationController does not own camera follow mechanics")
    check("GpsGyroOrientationController" in navigation,
          "NavigationController is not wired to external GPS orientation controller")

if live_navigation_camera_path.is_file():
    live_navigation_camera = live_navigation_camera_path.read_text()
    check("GeoMath.distanceMeters(" in live_navigation_camera,
          "LiveNavigationCameraController is not using shared GeoMath distance")
    check("final LatLng finalTarget =" in live_navigation_camera
          and "lastPose;" in live_navigation_camera
          and "LatLng target =" in live_navigation_camera
          and "pos;" in live_navigation_camera,
          "LiveNavigationCameraController does not keep follow pivot on GPS position")
    check("RETURN_MS = 1650" in live_navigation_camera,
          "LiveNavigationCameraController lost proven 1650 ms return timing")
    check("BEARING_TAU_MS = 2200.0" in live_navigation_camera,
          "LiveNavigationCameraController lost proven bearing smoothing")
    check("BEARING_DEADBAND_DEG = 1.25" in live_navigation_camera,
          "LiveNavigationCameraController lost proven bearing deadband")
    check("direction arrow is the rotation pivot" in live_navigation_camera,
          "LiveNavigationCameraController lost position-centred rotation pivot")
    check("FOLLOW_ZOOM =" in live_navigation_camera
          and "16.5;" in live_navigation_camera,
          "LiveNavigationCameraController lost tuned z16.5 follow zoom")
    check("GPS walking course" in live_navigation_camera,
          "LiveNavigationCameraController no longer documents course-only rotation")

gps_orientation_path = JAVA / "GpsGyroOrientationController.java"
if gps_orientation_path.is_file():
    gps_orientation = gps_orientation_path.read_text()
    check("import org.maplibre.android.maps.*;" not in gps_orientation,
          "GpsGyroOrientationController still has MapLibre wildcard imports")
    check("import org.maplibre.geojson.*;" not in gps_orientation,
          "GpsGyroOrientationController still has GeoJSON wildcard imports")
    check("MAX_PLAYBACK_POINTS =" in gps_orientation
          and "            3;" in gps_orientation,
          "GpsGyroOrientationController playback history is not capped at 3")
    check("GeoMath.bearingDegrees(" in gps_orientation,
          "GpsGyroOrientationController is not using shared GeoMath bearing")
    check("GeoMath.shortestAngleDegrees(" in gps_orientation,
          "GpsGyroOrientationController is not using shared GeoMath angles")
    check("LiveNavigationCameraController" in gps_orientation,
          "GpsGyroOrientationController is not wired to live camera controller")
    check("private void renderExternalCamera(" not in gps_orientation,
          "GpsGyroOrientationController still owns live camera rendering")
    check("externalNavigationReturnAnimator" not in gps_orientation,
          "GpsGyroOrientationController still owns live camera return animation")

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

if projection_path.is_file():
    projection = projection_path.read_text()
    check("RouteHit findNearestRouteHit(" in projection,
          "CaminoProjectionEngine does not own nearest-route lookup")
    check("ProjectionHit projectToRoute(" in projection,
          "CaminoProjectionEngine does not own route projection")
    check("projectToSegment(" in projection,
          "CaminoProjectionEngine does not own segment projection")
    check("network.tracks()" in projection,
          "CaminoProjectionEngine does not use canonical CaminoNetwork track index")

if controller_path.is_file():
    controller = controller_path.read_text()
    check("private RouteHit findNearestRouteHit(" not in controller,
          "CaminoController still owns nearest-route lookup")
    check("private ProjectionHit projectToRoute(" not in controller,
          "CaminoController still owns route projection")
    check("private ProjectionHit projectToSegment(" not in controller,
          "CaminoController still owns segment projection")
    check("CaminoProjectionEngine" in controller,
          "CaminoController is not wired to CaminoProjectionEngine")

if info_presenter_path.is_file():
    info_presenter = info_presenter_path.read_text()
    check("void setInfoTitle(" in info_presenter,
          "CaminoInfoPresenter does not own info title state")
    check("void setSummaryTexts(" in info_presenter,
          "CaminoInfoPresenter does not own summary state")
    check("void setHeightStats(" in info_presenter,
          "CaminoInfoPresenter does not own height stats state")
    check("void setSpeedStats(" in info_presenter,
          "CaminoInfoPresenter does not own speed stats state")
    check("infoPanel.setStatsTexts(" in info_presenter,
          "CaminoInfoPresenter does not render panel stats")

if info_controller_path.is_file():
    info_controller = info_controller_path.read_text()
    check("new CaminoInfoPanel(" in info_controller,
          "CaminoInfoController does not own info-panel lifecycle")
    check("void updateMeasurementSummary(" in info_controller,
          "CaminoInfoController does not own measurement summary presentation")
    check("void updateCompass()" in info_controller,
          "CaminoInfoController does not own HUD compass updates")
    check("void setNavigationMode(" in info_controller,
          "CaminoInfoController does not own navigation-button UI state")
    check("new MeasurementEngine(" not in info_controller,
          "CaminoInfoController incorrectly constructs measurement engine")
    check("CaminoTrackingService" not in info_controller,
          "CaminoInfoController incorrectly owns GPS tracking")

if controller_path.is_file():
    controller = controller_path.read_text()
    check("infoTitleText" not in controller,
          "CaminoController still owns info title state")
    check("summaryLeftText" not in controller,
          "CaminoController still owns info summary state")
    check("heightStatsText" not in controller,
          "CaminoController still owns height stats presentation state")
    check("speedStatsText" not in controller,
          "CaminoController still owns speed stats presentation state")
    check("CaminoInfoPresenter" in controller,
          "CaminoController is not wired to CaminoInfoPresenter")
    check("CaminoInfoController" in controller,
          "CaminoController is not wired to CaminoInfoController")
    check("private void updateDistanceLabel(" not in controller,
          "CaminoController still owns measurement summary presentation")
    check("private void ensureDistanceView()" not in controller,
          "CaminoController still owns info-panel lifecycle")
    check("private void updateInfoCompass()" not in controller,
          "CaminoController still owns HUD compass updates")
    check("CaminoInfoPanel" not in controller,
          "CaminoController still directly owns CaminoInfoPanel")

if map_coordinator_path.is_file():
    coordinator = map_coordinator_path.read_text()
    check("mapView.getMapAsync(" in coordinator,
          "MapCoordinator does not own MapLibre startup")
    check("offlineMapRepository.ensureInstalled()" in coordinator,
          "MapCoordinator does not own offline-map orchestration")
    check("mapStyleProvider.buildStyle(" in coordinator,
          "MapCoordinator does not own style orchestration")
    check("map.setStyle(" in coordinator,
          "MapCoordinator does not own runtime style application")
    check("caminoMapRenderer.onStyleLoaded(" in coordinator,
          "MapCoordinator does not coordinate Camino renderer style load")

main_path = JAVA / "MainActivity.java"
if main_path.is_file():
    main = main_path.read_text()
    check("MapCoordinator" in main,
          "MainActivity is not wired to MapCoordinator")
    check("getMapAsync(" not in main,
          "MainActivity still owns MapLibre startup")
    check("OfflineMapRepository" not in main,
          "MainActivity still owns offline-map repository")
    check("MapStyleProvider" not in main,
          "MainActivity still owns map-style provider")
    check("CaminoMapRenderer" not in main,
          "MainActivity still owns Camino renderer")

if interaction_renderer_path.is_file():
    interaction_renderer = interaction_renderer_path.read_text()
    check("void onStyleLoaded(" in interaction_renderer,
          "CaminoInteractionRenderer does not own interaction overlay style setup")
    check("void updateDummyPosition(" in interaction_renderer,
          "CaminoInteractionRenderer does not own dummy marker rendering")
    check("void updateSelectedPositions(" in interaction_renderer,
          "CaminoInteractionRenderer does not own selected-point rendering")
    check("void renderMeasurementPath(" in interaction_renderer,
          "CaminoInteractionRenderer does not own measurement overlay rendering")
    check("new MeasurementEngine(" not in interaction_renderer,
          "CaminoInteractionRenderer incorrectly constructs MeasurementEngine")
    check("buildMeasurementPath(" not in interaction_renderer,
          "CaminoInteractionRenderer incorrectly owns measurement calculation")

if controller_path.is_file():
    controller = controller_path.read_text()
    check("CaminoInteractionRenderer" in controller,
          "CaminoController is not wired to CaminoInteractionRenderer")
    check("private GeoJsonSource selectedRouteSource" not in controller,
          "CaminoController still owns interaction overlay sources")
    check("private void updateDummySource()" not in controller,
          "CaminoController still owns dummy marker rendering")
    check("private void updateSelectedSource()" not in controller,
          "CaminoController still owns selected-point rendering")
    check("measurementEngine.buildMeasurementPath(" in controller,
          "CaminoController unexpectedly lost measurement decision ownership")

if travel_stats_path.is_file():
    travel_stats = travel_stats_path.read_text()
    check("void noteSample(" in travel_stats,
          "TravelStatsController does not own travel sampling")
    check("buildSpeedStatsText()" in travel_stats,
          "TravelStatsController does not own speed/ETA formatting")
    check("nextVillageMetrics()" in travel_stats,
          "TravelStatsController does not own next-village metrics")
    check("positiveAscentFromHitToTrackEnd(" in travel_stats,
          "TravelStatsController does not own village ascent calculation")
    check("CaminoTrackingService" not in travel_stats,
          "TravelStatsController incorrectly owns GPS tracking")
    check("MapLibreMap" not in travel_stats,
          "TravelStatsController incorrectly owns map state")

if controller_path.is_file():
    controller = controller_path.read_text()
    check("travelSessionStartElapsedMs" not in controller,
          "CaminoController still owns travel session timing")
    check("travelMovingElapsedMs" not in controller,
          "CaminoController still owns moving-time statistics")
    check("private void noteTravelSample(" not in controller,
          "CaminoController still owns travel sampling")
    check("private String buildSpeedStatsText()" not in controller,
          "CaminoController still owns travel stats formatting")
    check("private double[] nextVillageMetrics()" not in controller,
          "CaminoController still owns next-village metrics")
    check("TravelStatsController" in controller,
          "CaminoController is not wired to TravelStatsController")

if drag_path.is_file():
    drag = drag_path.read_text()
    check("boolean handleTouch(" in drag,
          "CaminoDragController does not own touch drag handling")
    check("findDragTarget(" in drag,
          "CaminoDragController does not own drag-target selection")
    check("screenDistanceSq(" in drag,
          "CaminoDragController does not own screen hit-distance")
    check(
        "projectionEngine.projectToRoute(" in drag
        or "projectionEngine.projectToSelectableRoute(" in drag,
        "CaminoDragController does not own drag snapping"
    )
    check("CaminoTrackingService" not in drag,
          "CaminoDragController incorrectly owns GPS tracking")
    check("NavigationController" not in drag,
          "CaminoDragController directly owns navigation controller")

if controller_path.is_file():
    controller = controller_path.read_text()
    check("private boolean handleTouch(" not in controller,
          "CaminoController still owns touch drag handling")
    check("private int findDragTarget(" not in controller,
          "CaminoController still owns drag-target selection")
    check("private void moveDragTarget(" not in controller,
          "CaminoController still owns drag movement")
    check("dragTarget" not in controller,
          "CaminoController still owns drag state")
    check("CaminoDragController" in controller,
          "CaminoController is not wired to CaminoDragController")

if selection_path.is_file():
    selection = selection_path.read_text()
    check("boolean handleMapTap(" in selection,
          "CaminoSelectionController does not own tap selection")
    check("Tap 1:" in selection and "Tap 2:" in selection and "Tap 3:" in selection,
          "CaminoSelectionController does not own Tap 1/2/3 semantics")
    check("private CaminoRoute selectedRoute;" in selection,
          "CaminoSelectionController does not own primary selection state")
    check("private ProjectionHit secondTapHit;" in selection,
          "CaminoSelectionController does not own second-point selection state")
    check("MapLibreMap" not in selection,
          "CaminoSelectionController incorrectly owns map state")
    check("MotionEvent" not in selection,
          "CaminoSelectionController incorrectly owns drag mechanics")

if controller_path.is_file():
    controller = controller_path.read_text()
    check("private boolean handleMapTap(" not in controller,
          "CaminoController still owns tap-selection semantics")
    check("private CaminoRoute selectedRoute;" not in controller,
          "CaminoController still owns primary selection state")
    check("private ProjectionHit secondTapHit;" not in controller,
          "CaminoController still owns second-point selection state")
    check("CaminoSelectionController" in controller,
          "CaminoController is not wired to CaminoSelectionController")

if info_panel_path.is_file():
    info_panel = info_panel_path.read_text()
    check("CaminoChevronView" in info_panel,
          "CaminoInfoPanel is not wired to CaminoChevronView")
    check("CaminoNavigationButton" in info_panel,
          "CaminoInfoPanel is not wired to CaminoNavigationButton")
    check("private static final class ChevronView" not in info_panel,
          "CaminoInfoPanel still owns chevron drawing implementation")
    check("private static final class NavigationButton" not in info_panel,
          "CaminoInfoPanel still owns navigation-button drawing implementation")

if info_chevron_path.is_file():
    info_chevron = info_chevron_path.read_text()
    check("protected void onDraw(" in info_chevron,
          "CaminoChevronView does not own chevron drawing")

if info_navigation_button_path.is_file():
    info_navigation_button = info_navigation_button_path.read_text()
    check("void setMode(" in info_navigation_button
          and "NavigationController.Mode.MANUAL" in info_navigation_button
          and "NavigationController.Mode.NORTH_UP" in info_navigation_button
          and "drawRecenterReticle(" in info_navigation_button,
          "CaminoNavigationButton does not own navigation-mode/reticle visual state")
    check("protected void onDraw(" in info_navigation_button,
          "CaminoNavigationButton does not own navigation control drawing")

if height_profile_model_path.is_file():
    height_profile_model = height_profile_model_path.read_text()
    check("reduceSamples(" in height_profile_model,
          "CaminoHeightProfileModel does not own sample reduction")
    check("findNearestSample(" in height_profile_model,
          "CaminoHeightProfileModel does not own nearest-sample lookup")
    check("import android.graphics.Canvas" not in height_profile_model
          and "android.graphics.Canvas" not in height_profile_model,
          "CaminoHeightProfileModel incorrectly owns Canvas drawing")
    check("MotionEvent" not in height_profile_model,
          "CaminoHeightProfileModel incorrectly owns touch events")
    check("ValueAnimator" not in height_profile_model,
          "CaminoHeightProfileModel incorrectly owns reveal animation")

if height_profile_view_path.is_file():
    height_profile_view = height_profile_view_path.read_text()
    check("CaminoHeightProfileModel" in height_profile_view,
          "CaminoHeightProfileView is not wired to CaminoHeightProfileModel")
    check("private List<Sample> reduceSamples(" not in height_profile_view,
          "CaminoHeightProfileView still owns sample reduction")
    check("private int findNearestSample(" not in height_profile_view,
          "CaminoHeightProfileView still owns nearest-sample lookup")
    check("protected void onDraw(" in height_profile_view,
          "CaminoHeightProfileView no longer owns Canvas rendering")
    check("public boolean onTouchEvent(" in height_profile_view,
          "CaminoHeightProfileView no longer owns UI touch behavior")

if height_profile_controller_path.is_file():
    height_profile_controller = height_profile_controller_path.read_text()
    check("CaminoHeightProfileView" in height_profile_controller,
          "CaminoHeightProfileController does not own height-profile view lifecycle")
    check("void scheduleRefresh()" in height_profile_controller,
          "CaminoHeightProfileController does not own throttled refresh scheduling")
    check("void handleCameraIdle()" in height_profile_controller,
          "CaminoHeightProfileController does not own final camera-idle refresh")
    check("void refresh()" in height_profile_controller,
          "CaminoHeightProfileController does not own projected profile refresh")
    check("new MeasurementEngine(" not in height_profile_controller,
          "CaminoHeightProfileController incorrectly constructs measurement engine")
    check("NavigationController" not in height_profile_controller,
          "CaminoHeightProfileController incorrectly owns navigation policy")

if controller_path.is_file():
    controller = controller_path.read_text()
    check("CaminoHeightProfileController" in controller,
          "CaminoController is not wired to CaminoHeightProfileController")
    check("private void refreshHeightProfile()" not in controller,
          "CaminoController still owns height-profile refresh implementation")
    check("private void ensureHeightProfileView()" not in controller,
          "CaminoController still owns height-profile view lifecycle")
    check("heightProfileRefreshScheduled" not in controller,
          "CaminoController still owns height-profile scheduling state")

overlay_dir = ASSETS / "map-overlays"
style_path = ASSETS / "styles/camino-basic.json"

check(
    (overlay_dir / "schaffhausen-munot-mask.geojson").is_file(),
    "missing current Schaffhausen Munot round mask",
)
check(
    not (overlay_dir / "debug-schaffhausen-south-curtain.geojson").exists(),
    "current Munot mask still has obsolete south-curtain filename",
)
check(
    not (overlay_dir / "debug-schaffhausen-transition-mask.geojson").exists(),
    "obsolete Schaffhausen transition-mask asset still present",
)

if style_path.is_file():
    style_text = style_path.read_text()
    check(
        '"schaffhausen-munot-mask": {' in style_text,
        "style missing Schaffhausen Munot mask source",
    )
    check(
        '"source": "schaffhausen-munot-mask"' in style_text,
        "style missing Schaffhausen Munot mask layer binding",
    )
    check(
        '"id": "schaffhausen-munot-mask-fill"' in style_text,
        "style missing Schaffhausen Munot mask fill layer",
    )
    check(
        "schaffhausen-south-curtain" not in style_text,
        "obsolete south-curtain naming remains in style",
    )

test_file = ROOT / "android/app/src/test/java/com/marukitano/caminoguard/CaminoDomainTest.java"
app_gradle = ROOT / "android/app/build.gradle.kts"

check(
    test_file.is_file(),
    "missing P0 Camino domain tests",
)

if test_file.is_file():
    test_text = test_file.read_text()
    for required_test in (
        "canonicalAssetKeepsDataContract",
        "colorsKeepGlobalNormalizationAndDarkeningContract",
        "haversineDistanceKeepsRadiusUnitsAndSymmetry",
        "projectionFindsNearestPointOnSingleTrack",
        "networkFindPathUsesTrackWeightAndHandlesBounds",
    ):
        check(
            required_test in test_text,
            "missing P0 domain test: " + required_test,
        )

if app_gradle.is_file():
    gradle_text = app_gradle.read_text()
    check(
        'testImplementation("junit:junit:4.13.2")' in gradle_text,
        "missing JUnit unit-test dependency",
    )
    check(
        'testImplementation("org.json:json:20250517")' in gradle_text,
        "missing pure-JVM JSON test dependency",
    )
    check(
        "org.robolectric" not in gradle_text,
        "P0 domain tests should not depend on Robolectric",
    )

if canonical.is_file():
    root = json.loads(canonical.read_text())
    check(isinstance(root.get("routes"), list) and root["routes"],
          "canonical Camino dataset has no routes")

if direction_tracker_path.is_file():
    direction_tracker = direction_tracker_path.read_text()
    check("GeoMath.normalizeDegrees(" in direction_tracker,
          "CaminoDirectionTracker is not using shared GeoMath normalization")
    check("GeoMath.shortestAngleDegrees(" in direction_tracker,
          "CaminoDirectionTracker is not using shared GeoMath angles")
    check("void acceptMovingLocation(" in direction_tracker,
          "CaminoDirectionTracker does not own moving GPS course updates")
    check("void updateRawCameraYaw(" in direction_tracker,
          "CaminoDirectionTracker does not own raw gyro yaw")
    check("void updateGyroAugmentedHeading()" in direction_tracker,
          "CaminoDirectionTracker does not own stationary gyro heading")
    check("calculateCourseOverLastDistance(" in direction_tracker,
          "CaminoDirectionTracker does not own spatial course calculation")

if tracking_service_path.is_file():
    tracking_service = tracking_service_path.read_text()
    check("CaminoDirectionTracker" in tracking_service,
          "CaminoTrackingService is not wired to CaminoDirectionTracker")
    check("private Float gpsCourseDeg;" not in tracking_service,
          "CaminoTrackingService still owns GPS course state")
    check("private Float phoneHeadingDeg;" not in tracking_service,
          "CaminoTrackingService still owns phone heading state")
    check("private Float rawCameraYawDeg;" not in tracking_service,
          "CaminoTrackingService still owns raw gyro yaw state")
    check("private Float gyroReferenceYawDeg;" not in tracking_service,
          "CaminoTrackingService still owns gyro reference state")
    check("private Float calculateCourseOverLastDistance(" not in tracking_service,
          "CaminoTrackingService still owns course calculation")

if errors:
    print("ARCHITECTURE AUDIT FAILED")
    for error in errors:
        print("  - " + error)
    sys.exit(1)

print("ARCHITECTURE AUDIT OK")
print("  one canonical Camino dataset")
print("  one global controller")
print("  one Camino repository / canonical parser")
print("  one runtime Camino renderer over parsed domain objects")
print("  one shared geographic math owner")
print("  one Camino graph / shortest-path engine")
print("  one measurement / height-profile engine")
print("  one offline-map repository")
print("  one runtime map-style provider")
print("  one navigation follow policy controller")
print("  one proven live-GPS navigation camera executor")
print("  one Camino projection / nearest-hit engine")
print("  one Camino info-panel presenter")
print("  one Camino info / HUD orchestration controller")
print("  one MapLibre startup / style coordinator")
print("  one travel statistics / village ETA controller")
print("  one Camino drag interaction controller")
print("  one Camino tap-selection controller")
print("  separated Camino info-panel drawing controls")
print("  separated Camino height-profile sample model")
print("  one Camino height-profile projection / refresh controller")
print("  one runtime Camino map renderer")
print("  one interactive Camino overlay renderer")
print("  one Camino walking-course / gyro direction tracker")
print("  one immutable config file")
print("  current Schaffhausen Munot round mask preserved")
print("  obsolete Schaffhausen transition mask removed")
print("  P0 Camino pure-JVM domain tests present")
print("  conservative dead-code audit tool present")
print("  no regional Camino behavior switch")
