package com.marukitano.caminoguard;

import android.app.Activity;
import android.graphics.PointF;

import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.MapView;
import org.maplibre.android.maps.Style;
import org.maplibre.geojson.Feature;
import org.maplibre.geojson.Point;

import java.util.ArrayList;
import java.util.List;

/**
 * Global Camino interaction controller.
 *
 * Selection, measurement, routing, height profile and live navigation
 * use the same canonical Camino dataset everywhere.
 */
public final class CaminoController {

    private final Activity activity;
    private final MapView mapView;
    private final CaminoRepository caminoRepository;
    private final List<CaminoRoute> routes =
            new ArrayList<>();
    private final CaminoNetwork caminoNetwork;
    private final MeasurementEngine measurementEngine;
    private final NavigationController navigationController;
    private final CaminoProjectionEngine projectionEngine;
    private final CaminoInfoPresenter infoPresenter;
    private final CaminoInfoController infoController;
    private final CaminoInteractionRenderer interactionRenderer;
    private final CaminoHeightProfileController heightProfileController;
    private final TravelStatsController travelStatsController;
    private final WalkingPerformanceModel walkingPerformanceModel;
    private final CaminoDragController dragController;
    private final CaminoSelectionController selectionController;
    private final CaminoSelectionStatsOverlay selectionStatsOverlay;

    private MapLibreMap map;

    private LatLng dummyPosition;

    /*
     * One active position source:
     *   false -> draggable planning/debug marker
     *   true  -> accepted real GPS position from CaminoTrackingService
     */
    private boolean livePositionMode;
    private Float liveCourseDeg;
    private long lastLiveFixStamp = Long.MIN_VALUE;
    private boolean livePositionListenerRegistered;

    private final CaminoTrackingService.Listener livePositionListener =
            this::handleLiveTrackingState;

    private MeasurementPath currentMeasurementPath;

    /*
     * Visual state only. The actual stage selection lives in the ordinary
     * two-point CaminoSelectionController state.
     */
    private LatLng selectedStagePoint;
    private String selectedStageHighlightColor;

    public CaminoController(
            Activity activity,
            MapView mapView,
            LatLng initialPosition
    ) {
        this.activity = activity;
        this.mapView = mapView;
        this.caminoRepository =
                new CaminoRepository(
                        activity
                );

        this.caminoNetwork =
                new CaminoNetwork();

        this.measurementEngine =
                new MeasurementEngine(
                        caminoNetwork
                );

        this.infoPresenter =
                new CaminoInfoPresenter();

        this.infoController =
                new CaminoInfoController(
                        activity,
                        mapView,
                        infoPresenter
                );

        this.interactionRenderer =
                new CaminoInteractionRenderer();

        this.navigationController =
                new NavigationController(
                        mapView,
                        () -> dummyPosition,
                        this::navigationBearingAtPosition,
                        infoController::setNavigationFollowEnabled
                );

        infoController.setNavigationAction(
                navigationController::toggleFollow
        );

        infoController.setNavigationFollowEnabled(
                navigationController.isFollowEnabled()
        );

        this.projectionEngine =
                new CaminoProjectionEngine(
                        caminoNetwork
                );

        this.walkingPerformanceModel =
                new WalkingPerformanceModel(
                        activity,
                        projectionEngine,
                        measurementEngine
                );

        this.heightProfileController =
                new CaminoHeightProfileController(
                        activity,
                        mapView,
                        infoPresenter,
                        routes
                );

        this.travelStatsController =
                new TravelStatsController(
                        routes,
                        projectionEngine,
                        measurementEngine,
                        () -> dummyPosition,
                        () -> currentMeasurementPath,
                        infoPresenter::setSpeedStats
                );

        this.selectionController =
                new CaminoSelectionController(
                        routes,
                        projectionEngine,
                        this::isTapCloseEnough,
                        this::refresh
                );

        this.selectionStatsOverlay =
                new CaminoSelectionStatsOverlay(
                        activity,
                        mapView,
                        walkingPerformanceModel
                );

        this.dragController =
                new CaminoDragController(
                        activity,
                        projectionEngine,
                        new CaminoDragController.Host() {
                            @Override
                            public boolean isLivePositionMode() {
                                return livePositionMode;
                            }

                            @Override
                            public LatLng dummyPosition() {
                                return dummyPosition;
                            }

                            @Override
                            public void setDummyPosition(
                                    LatLng position
                            ) {
                                dummyPosition =
                                        position;
                            }

                            @Override
                            public CaminoRoute selectedRoute() {
                                return selectionController.selectedRoute();
                            }

                            @Override
                            public ProjectionHit selectedHit() {
                                return selectionController.selectedHit();
                            }

                            @Override
                            public void setSelectedHit(
                                    ProjectionHit hit
                            ) {
                                clearSelectedStageVisual();

                                selectionController.setSelectedHit(
                                        hit
                                );
                            }

                            @Override
                            public CaminoRoute secondSelectedRoute() {
                                return selectionController.secondSelectedRoute();
                            }

                            @Override
                            public ProjectionHit secondTapHit() {
                                return selectionController.secondTapHit();
                            }

                            @Override
                            public void setSecondTapHit(
                                    ProjectionHit hit
                            ) {
                                clearSelectedStageVisual();

                                selectionController.setSecondTapHit(
                                        hit
                                );
                            }

                            @Override
                            public void refresh() {
                                CaminoController.this.refresh();
                            }

                            @Override
                            public void refreshDragPreview(
                                    boolean draggingDummy
                            ) {
                                CaminoController.this.refreshDragPreview(
                                        draggingDummy
                                );
                            }

                            @Override
                            public void noteTravelSample(
                                    LatLng position
                            ) {
                                travelStatsController.noteSample(
                                        position
                                );
                            }

                            @Override
                            public void followIfActive() {
                                navigationController.followIfActive(
                                        true
                                );
                            }
                        }
                );

        this.dummyPosition = initialPosition;
    }


    public void configureLivePositionMode(
            LatLng initialPosition
    ) {
        if (initialPosition == null) {
            throw new IllegalArgumentException(
                    "Live-position mode requires an initial position"
            );
        }

        /*
         * Live GPS changes ONLY the position source.
         * It does NOT switch Camino datasets.
         *
         * Spain/Portugal and Schaffhausen are loaded by the same
         * CaminoRepository and therefore run through this same code path.
         */
        this.livePositionMode = true;
        this.dummyPosition = initialPosition;
        navigationController.configureLiveMode(
                true
        );
    }

    public void startLivePosition() {
        if (!livePositionMode
                || livePositionListenerRegistered) {
            return;
        }

        livePositionListenerRegistered = true;
        CaminoTrackingService.addListener(
                livePositionListener
        );

        navigationController.syncExternalFollow();
    }

    public void stopLivePosition() {
        if (!livePositionListenerRegistered) {
            return;
        }

        livePositionListenerRegistered = false;
        CaminoTrackingService.removeListener(
                livePositionListener
        );
    }

    private void handleLiveTrackingState(
            CaminoTrackingService.Snapshot snapshot
    ) {
        if (!livePositionMode
                || snapshot == null
                || snapshot.location == null) {
            return;
        }

        /*
         * Motion-state publications can arrive without a new GPS timestamp.
         * Handle STATIONARY before the duplicate-fix early return so pauses are
         * excluded from learned walking speed.
         */
        if (snapshot.stationary) {
            travelStatsController.noteStationary(
                    new LatLng(
                            snapshot.location.getLatitude(),
                            snapshot.location.getLongitude()
                    )
            );
        }

        long stamp =
                snapshot.location.getElapsedRealtimeNanos() > 0L
                        ? snapshot.location.getElapsedRealtimeNanos()
                        : snapshot.location.getTime() * 1_000_000L;

        /*
         * Stationary gyro snapshots arrive much faster than GPS. Do not rebuild
         * route/profile on every gyro publication.
         */
        if (stamp == lastLiveFixStamp) {
            liveCourseDeg = snapshot.courseDeg;
            return;
        }

        lastLiveFixStamp = stamp;

        LatLng position =
                new LatLng(
                        snapshot.location.getLatitude(),
                        snapshot.location.getLongitude()
                );

        Float course =
                snapshot.courseDeg;

        activity.runOnUiThread(
                () -> {
                    if (!livePositionMode) {
                        return;
                    }

                    dummyPosition = position;
                    liveCourseDeg = course;

                    /*
                     * This handler only reaches here for a new accepted GPS fix.
                     * Gyro-only snapshots were rejected by lastLiveFixStamp above.
                     */
                    travelStatsController.noteSample(
                            position
                    );

                    if (map == null
                            || routes.isEmpty()) {
                        return;
                    }

                    refresh();

                }
        );
    }


    public void setLiveNavigationController(
            GpsGyroOrientationController controller
    ) {
        navigationController.setExternalController(
                controller
        );
    }

    public void attachMap(
            MapLibreMap map
    ) {
        this.map = map;
        infoController.attachMap(
                map
        );
        heightProfileController.attachMap(
                map
        );
        navigationController.attachMap(
                map
        );
        dragController.attachMap(
                map
        );

        map.addOnMapClickListener(
                point -> {
                    if (dragController.isDragging()) {
                        return false;
                    }

                    /*
                     * Shells sit directly on the Camino, so test them before
                     * the generic route tap. A shell tap consumes one tap and
                     * creates the full two-point day-stage selection.
                     */
                    if (handleStageTap(
                            point
                    )) {
                        return true;
                    }

                    clearSelectedStageVisual();

                    return selectionController.handleMapTap(
                            point,
                            false
                    );
                }
        );

        /*
         * Keep the profile alive while the map is panned, zoomed or rotated.
         * Camera-move updates are deliberately throttled; CameraIdle performs
         * one exact final refresh.
         */
        map.addOnCameraMoveListener(
                () -> {
                    infoController.updateCompass();
                    heightProfileController.scheduleRefresh();
                }
        );

        map.addOnCameraIdleListener(
                () -> {
                    infoController.updateCompass();
                    heightProfileController.handleCameraIdle();
                    navigationController.handleCameraIdle();
                }
        );

        map.addOnCameraMoveStartedListener(
                navigationController::handleCameraMoveStarted
        );

        mapView.setOnTouchListener(
                (view, event) ->
                        dragController.handleTouch(
                                event
                        )
        );
    }


    /**
     * Cheap UI-only refresh used while a finger is moving.
     *
     * The expensive measured route and network path intentionally remain at
     * their previous geometry until the finger is released. This keeps the
     * marker attached to the finger instead of blocking the Android UI thread
     * for seconds while serialising a country-scale GeoJSON overlay.
     */
    private void refreshDragPreview(
            boolean draggingDummy
    ) {
        interactionRenderer.updateDummyPosition(
                dummyPosition
        );
        if (selectedStagePoint != null) {
            /*
             * Do not paint the normal blue tap dots over the shell artwork.
             * The route endpoints still exist in selectionController; this is
             * only a rendering choice.
             */
            interactionRenderer.updateSelectedPositions(
                    null,
                    null
            );

        } else {
            interactionRenderer.updateSelectedPositions(
                    selectionController.selectedHit(),
                    selectionController.secondTapHit()
            );
        }

        interactionRenderer.updateSelectedStage(
                selectedStagePoint,
                selectedStageHighlightColor
        );

        if (!draggingDummy
                || selectionController.secondTapHit() != null) {
            return;
        }

        RouteHit startRouteHit =
                projectionEngine.findNearestRouteHit(
                        dummyPosition
                );

        ProjectionHit startHit =
                startRouteHit == null
                        ? null
                        : startRouteHit.hit;

        interactionRenderer.updateStartProjection(
                startHit
        );

        interactionRenderer.updateConnector(
                dummyPosition,
                startRouteHit
        );

        /*
         * Before a destination point exists the label does not depend on a
         * network path, so it is safe and cheap to keep it live while dragging.
         */
        if (selectionController.selectedHit() == null) {
            infoController.updateMeasurementSummary(
                    routes,
                    selectionController,
                    startRouteHit,
                    currentMeasurementPath
            );
        }
    }

    public void onStyleLoaded(
            Style style
    ) {
        infoController.ensureView();
        heightProfileController.ensureView();
        selectionStatsOverlay.ensureView();

        interactionRenderer.onStyleLoaded(
                style,
                dummyPosition,
                livePositionMode
        );

        try {
            loadRoutes();
            refresh();

        } catch (Exception error) {
            infoPresenter.setLabel(
                    "Camino Fehler: "
                            + (error.getMessage()
                            == null
                            ? error.getClass()
                            .getSimpleName()
                            : error.getMessage())
            );
        }
    }

    List<CaminoRoute> routesForRendering() {
        return routes;
    }


    private void loadRoutes()
            throws Exception {

        routes.clear();
        routes.addAll(
                caminoRepository.load()
        );

        if (routes.isEmpty()) {
            throw new IllegalStateException(
                    "keine Camino-Routen im kanonischen Datensatz"
            );
        }

        caminoNetwork.rebuild(
                routes
        );
    }

    private void refresh() {
        interactionRenderer.updateDummyPosition(
                dummyPosition
        );
        if (selectedStagePoint != null) {
            /*
             * Do not paint the normal blue tap dots over the shell artwork.
             * The route endpoints still exist in selectionController; this is
             * only a rendering choice.
             */
            interactionRenderer.updateSelectedPositions(
                    null,
                    null
            );

        } else {
            interactionRenderer.updateSelectedPositions(
                    selectionController.selectedHit(),
                    selectionController.secondTapHit()
            );
        }

        interactionRenderer.updateSelectedStage(
                selectedStagePoint,
                selectedStageHighlightColor
        );

        /*
         * The fake position is always projected onto the nearest Camino,
         * even before a measurement point exists. This is the same behavior
         * the real GPS position will need later.
         */
        RouteHit startRouteHit =
                routes.isEmpty()
                        ? null
                        : projectionEngine.findNearestRouteHit(
                                dummyPosition
                        );

        ProjectionHit startHit =
                startRouteHit == null
                        ? null
                        : startRouteHit.hit;

        if (selectionController.secondTapHit()
                == null) {
            interactionRenderer.updateStartProjection(
                    startHit
            );

            interactionRenderer.updateConnector(
                    dummyPosition,
                    startRouteHit
            );

        } else {
            interactionRenderer.hideStartProjectionAndConnector();
        }

        updateSelectedRoute(
                startRouteHit
        );

        /*
         * The compact stats card belongs only to an explicit two-point
         * selection. A one-point measurement from the current GPS/dummy
         * position intentionally stays card-free.
         */
        if (selectionController.secondTapHit()
                != null) {

            selectionStatsOverlay.update(
                    currentMeasurementPath
            );

        } else {
            selectionStatsOverlay.hide();
        }

        infoController.updateMeasurementSummary(
                routes,
                selectionController,
                startRouteHit,
                currentMeasurementPath
        );

        heightProfileController.refresh();
    }

    private boolean handleStageTap(
            LatLng tap
    ) {
        if (map == null
                || routes.isEmpty()) {

            return false;
        }

        PointF screenPoint =
                map.getProjection()
                        .toScreenLocation(
                                tap
                        );

        List<Feature> stageFeatures =
                map.queryRenderedFeatures(
                        screenPoint,
                        CaminoMapRenderer.STAGE_LAYER
                );

        if (stageFeatures == null
                || stageFeatures.isEmpty()) {

            return false;
        }

        /*
         * The stage source is a Point source carrying its semantic place_key.
         * Normally there is exactly one rendered feature under the shell.
         */
        for (Feature feature
                : stageFeatures) {

            if (feature == null
                    || !feature.hasProperty(
                    "place_key"
            )
                    || !(feature.geometry()
                    instanceof Point)) {

                continue;
            }

            String placeKey =
                    feature.getStringProperty(
                            "place_key"
                    );

            Point geometry =
                    (Point)
                            feature.geometry();

            LatLng stagePoint =
                    new LatLng(
                            geometry.latitude(),
                            geometry.longitude()
                    );

            StageRouteSelection stageSelection =
                    findOutgoingStage(
                            placeKey,
                            stagePoint
                    );

            /*
             * A real shell was tapped. Consume the tap even at a final route
             * endpoint where no outgoing official stage exists, otherwise the
             * same touch would unexpectedly become a normal Camino tap.
             */
            if (stageSelection == null) {
                return true;
            }

            selectedStagePoint =
                    stagePoint;

            selectedStageHighlightColor =
                    stageSelection.route.highlightColor;

            selectionController.selectStage(
                    stageSelection.route,
                    stageSelection.startHit,
                    stageSelection.endHit
            );

            return true;
        }

        return false;
    }


    private StageRouteSelection findOutgoingStage(
            String placeKey,
            LatLng stagePoint
    ) {
        if (placeKey == null
                || placeKey.isEmpty()
                || stagePoint == null) {

            return null;
        }

        StageRouteSelection best =
                null;

        double bestDistanceM =
                Double.POSITIVE_INFINITY;

        for (CaminoRoute route
                : routes) {

            for (int trackIndex = 0;
                    trackIndex < route.tracks.size();
                    trackIndex++) {

                RouteTrack track =
                        route.tracks.get(
                                trackIndex
                        );

                /*
                 * route.tracks contains only routing_primary tracks. Therefore
                 * a shell selects the official primary day stage, never a b/c/d
                 * rendering variant.
                 */
                if (track.pseudoFrom
                        || track.pseudoTo
                        || track.fromKey == null
                        || track.toKey == null
                        || !placeKey.equals(
                        track.fromKey
                )
                        || track.points.size() < 2) {

                    continue;
                }

                LatLng first =
                        track.points.get(
                                0
                        );

                LatLng last =
                        track.points.get(
                                track.points.size() - 1
                        );

                double firstDistanceM =
                        GeoMath.distanceMeters(
                                stagePoint,
                                first
                        );

                double lastDistanceM =
                        GeoMath.distanceMeters(
                                stagePoint,
                                last
                        );

                /*
                 * CaminoRepository is allowed to reverse geometry for route
                 * continuity without swapping fromKey/toKey. The actual shell
                 * coordinate therefore decides which physical track endpoint
                 * is the semantic FROM endpoint.
                 */
                boolean startIsFirst =
                        firstDistanceM
                                <= lastDistanceM;

                double startDistanceM =
                        Math.min(
                                firstDistanceM,
                                lastDistanceM
                        );

                if (startDistanceM
                        >= bestDistanceM) {

                    continue;
                }

                ProjectionHit startHit =
                        projectionEngine.projectToTrackEndpoint(
                                route,
                                trackIndex,
                                startIsFirst
                        );

                ProjectionHit endHit =
                        projectionEngine.projectToTrackEndpoint(
                                route,
                                trackIndex,
                                !startIsFirst
                        );

                if (startHit == null
                        || endHit == null) {

                    continue;
                }

                bestDistanceM =
                        startDistanceM;

                best =
                        new StageRouteSelection(
                                route,
                                startHit,
                                endHit
                        );
            }
        }

        return best;
    }


    private void clearSelectedStageVisual() {
        selectedStagePoint =
                null;

        selectedStageHighlightColor =
                null;
    }


    private static final class StageRouteSelection {

        final CaminoRoute route;
        final ProjectionHit startHit;
        final ProjectionHit endHit;

        StageRouteSelection(
                CaminoRoute route,
                ProjectionHit startHit,
                ProjectionHit endHit
        ) {
            this.route =
                    route;

            this.startHit =
                    startHit;

            this.endHit =
                    endHit;
        }
    }


    private boolean isTapCloseEnough(
            LatLng tap,
            LatLng projected
    ) {
        if (map == null) {
            return false;
        }

        PointF a =
                map.getProjection()
                        .toScreenLocation(
                                tap
                        );

        PointF b =
                map.getProjection()
                        .toScreenLocation(
                                projected
                        );

        double dx =
                a.x - b.x;

        double dy =
                a.y - b.y;

        return Math.hypot(
                dx,
                dy
        ) <= dp(28);
    }

    private void updateSelectedRoute(
            RouteHit startRouteHit
    ) {
        currentMeasurementPath =
                null;

        if (!interactionRenderer.isMeasurementRouteReady()) {
            return;
        }

        if (selectionController.selectedRoute() == null
                || selectionController.selectedHit() == null) {

            interactionRenderer.renderMeasurementPath(
                    null
            );

            return;
        }

        RouteHit routeStart;
        RouteHit routeEnd;

        if (selectionController.secondTapHit() != null) {
            if (selectionController.secondSelectedRoute() == null) {
                interactionRenderer.renderMeasurementPath(
                        null
                );

                return;
            }

            routeStart =
                    new RouteHit(
                            selectionController.selectedRoute(),
                            selectionController.selectedHit()
                    );

            routeEnd =
                    new RouteHit(
                            selectionController.secondSelectedRoute(),
                            selectionController.secondTapHit()
                    );

        } else {
            if (startRouteHit == null) {
                interactionRenderer.renderMeasurementPath(
                        null
                );

                return;
            }

            routeStart =
                    startRouteHit;

            routeEnd =
                    new RouteHit(
                            selectionController.selectedRoute(),
                            selectionController.selectedHit()
                    );
        }

        currentMeasurementPath =
                measurementEngine.buildMeasurementPath(
                        routeStart,
                        routeEnd
                );

        interactionRenderer.renderMeasurementPath(
                currentMeasurementPath
        );
    }


    private double navigationBearingAtPosition() {
        if (livePositionMode
                && liveCourseDeg != null
                && Float.isFinite(
                liveCourseDeg
        )) {
            double bearing =
                    liveCourseDeg % 360.0;

            return bearing < 0.0
                    ? bearing + 360.0
                    : bearing;
        }


        RouteHit routeHit =
                routes.isEmpty()
                        ? null
                        : projectionEngine.findNearestRouteHit(
                                dummyPosition
                        );

        if (routeHit == null
                || routeHit.hit.trackIndex < 0
                || routeHit.hit.trackIndex
                >= routeHit.route.tracks.size()) {

            return map.getCameraPosition()
                    .bearing;
        }

        RouteTrack track =
                routeHit.route.tracks.get(
                        routeHit.hit.trackIndex
                );

        if (track.points.size()
                < 2) {

            return map.getCameraPosition()
                    .bearing;
        }

        int segment =
                Math.max(
                        0,
                        Math.min(
                                track.points.size() - 2,
                                routeHit.hit.segmentIndex
                        )
                );

        return GeoMath.bearingDegrees(
                track.points.get(
                        segment
                ),
                track.points.get(
                        segment + 1
                )
        );
    }

    private int dp(
            int value
    ) {
        return Math.round(
                value
                        * activity
                        .getResources()
                        .getDisplayMetrics()
                        .density
        );
    }

}
