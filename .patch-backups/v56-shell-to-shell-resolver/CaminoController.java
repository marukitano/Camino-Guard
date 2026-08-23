package com.marukitano.caminoguard;

import android.app.Activity;
import android.graphics.PointF;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.MapView;
import org.maplibre.android.maps.Style;
import org.maplibre.geojson.Feature;
import org.maplibre.geojson.Point;

import java.util.ArrayList;
import java.util.Comparator;
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
    private String selectedStagePlaceKey;
    private int selectedStageChoiceIndex;
    private StageRouteSelection selectedStageSelection;

    private CaminoVariantPath selectedVariantPath;

    /*
     * One visible shell can represent several logical primary Camino edges.
     * These temporary constraints let the established variant resolver run
     * once for each topology edge without duplicating its Castro/Abla logic.
     */
    private final CaminoStageTopology stageTopology =
            new CaminoStageTopology();

    private CaminoRoute stageRouteConstraint;
    private int stagePrimaryTrackConstraint =
            -1;

    private StageTapTarget pendingStageTouch;
    private float stageTouchDownX;
    private float stageTouchDownY;
    private boolean stageTouchLongPressTriggered;
    private Runnable pendingStageLongPress;

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
                        handleMapTouch(
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
        if (selectedStagePoint != null
                || selectedVariantPath != null) {
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


    CaminoStageTopology stageTopologyForRendering() {
        return stageTopology;
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

        stageTopology.rebuild(
                routes
        );

        caminoNetwork.rebuild(
                routes
        );
    }

    private void refresh() {
        interactionRenderer.updateDummyPosition(
                dummyPosition
        );
        if (selectedStagePoint != null
                || selectedVariantPath != null) {
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

        if (selectedVariantPath != null) {
            interactionRenderer.hideStartProjectionAndConnector();

        } else if (selectionController.secondTapHit()
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
                != null
                || selectedVariantPath != null) {

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

    private boolean handleMapTouch(
            MotionEvent event
    ) {
        if (map == null) {
            return false;
        }

        if (selectedStagePoint == null) {
            return dragController.handleTouch(
                    event
            );
        }

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pendingStageTouch =
                        findStageTapTarget(
                                event.getX(),
                                event.getY()
                        );

                stageTouchDownX = event.getX();
                stageTouchDownY = event.getY();
                stageTouchLongPressTriggered = false;

                if (pendingStageTouch == null) {
                    return false;
                }

                armStageLongPressIfDraggable();
                return true;

            case MotionEvent.ACTION_MOVE:
                if (pendingStageTouch == null) {
                    return false;
                }

                if (stageTouchLongPressTriggered) {
                    dragController.handleTouch(event);
                    return true;
                }

                float dx = event.getX() - stageTouchDownX;
                float dy = event.getY() - stageTouchDownY;
                float slop = stageDp(12);

                if (dx * dx + dy * dy > slop * slop) {
                    cancelPendingStageLongPress();
                    pendingStageTouch = null;
                }

                return true;

            case MotionEvent.ACTION_UP:
                if (pendingStageTouch == null) {
                    return false;
                }

                cancelPendingStageLongPress();

                if (stageTouchLongPressTriggered) {
                    dragController.handleTouch(event);
                    clearPendingStageTouch();
                    return true;
                }

                StageTapTarget target = pendingStageTouch;
                clearPendingStageTouch();

                handleStagePlaceTap(
                        target.placeKey,
                        target.point
                );

                return true;

            case MotionEvent.ACTION_CANCEL:
                cancelPendingStageLongPress();

                if (stageTouchLongPressTriggered) {
                    dragController.handleTouch(event);
                }

                clearPendingStageTouch();
                return true;

            default:
                return pendingStageTouch != null;
        }
    }


    private void armStageLongPressIfDraggable() {
        cancelPendingStageLongPress();

        if (pendingStageTouch == null
                || !stageTargetRepresentsSelectedEndpoint(
                pendingStageTouch
        )) {
            return;
        }

        pendingStageLongPress =
                () -> {
                    if (pendingStageTouch == null
                            || stageTouchLongPressTriggered) {
                        return;
                    }

                    stageTouchLongPressTriggered = true;

                    /*
                     * Explicitly leave shell mode without rebuilding the route:
                     * selected 40 px shell disappears, ordinary 30 px shell
                     * remains and the already existing blue points become visible.
                     */
                    clearSelectedStageVisual();

                    interactionRenderer.updateSelectedStage(
                            null,
                            null
                    );

                    interactionRenderer.updateSelectedPositions(
                            selectionController.selectedHit(),
                            selectionController.secondTapHit()
                    );

                    dragController.beginDragAt(
                            stageTouchDownX,
                            stageTouchDownY
                    );
                };

        mapView.postDelayed(
                pendingStageLongPress,
                ViewConfiguration.getLongPressTimeout()
        );
    }


    private void cancelPendingStageLongPress() {
        if (pendingStageLongPress == null) {
            return;
        }

        mapView.removeCallbacks(pendingStageLongPress);
        pendingStageLongPress = null;
    }


    private void clearPendingStageTouch() {
        cancelPendingStageLongPress();
        pendingStageTouch = null;
        stageTouchLongPressTriggered = false;
    }


    private boolean stageTargetRepresentsSelectedEndpoint(
            StageTapTarget target
    ) {
        if (target == null || map == null) {
            return false;
        }

        PointF stageScreen =
                map.getProjection()
                        .toScreenLocation(target.point);

        float maxDistance = stageDp(30);
        float maxDistanceSq = maxDistance * maxDistance;

        ProjectionHit first = selectionController.selectedHit();

        if (first != null
                && screenDistanceSq(
                stageScreen,
                first.point
        ) <= maxDistanceSq) {
            return true;
        }

        ProjectionHit second = selectionController.secondTapHit();

        return second != null
                && screenDistanceSq(
                stageScreen,
                second.point
        ) <= maxDistanceSq;
    }


    private float screenDistanceSq(
            PointF screen,
            LatLng point
    ) {
        PointF other =
                map.getProjection()
                        .toScreenLocation(point);

        float dx = screen.x - other.x;
        float dy = screen.y - other.y;

        return dx * dx + dy * dy;
    }


    private boolean handleStageTap(
            LatLng tap
    ) {
        if (map == null || routes.isEmpty()) {
            return false;
        }

        if (handleVariantTap(
                tap
        )) {
            return true;
        }

        PointF screenPoint =
                map.getProjection()
                        .toScreenLocation(tap);

        StageTapTarget target =
                findStageTapTarget(
                        screenPoint.x,
                        screenPoint.y
                );

        if (target == null) {
            return false;
        }

        return handleStagePlaceTap(
                target.placeKey,
                target.point
        );
    }


    private boolean handleVariantTap(
            LatLng tap
    ) {
        if (map == null) {
            return false;
        }

        PointF screenPoint =
                map.getProjection()
                        .toScreenLocation(
                                tap
                        );

        List<VariantTapTarget> targets =
                findVariantTapTargets(
                        screenPoint.x,
                        screenPoint.y
                );

        if (targets.isEmpty()) {
            return false;
        }

        int choiceIndex =
                0;

        if (selectedVariantPath != null) {
            for (int index = 0;
                    index < targets.size();
                    index++) {

                if (selectedVariantPath.id.equals(
                        targets.get(
                                index
                        ).pathId
                )) {

                    choiceIndex =
                            (
                                    index + 1
                            )
                                    % targets.size();

                    break;
                }
            }
        }

        VariantTapTarget target =
                targets.get(
                        choiceIndex
                );

        CaminoVariantPath path =
                findVariantPath(
                        target.pathId
                );

        if (path == null) {
            return true;
        }

        clearSelectedStageVisual();

        selectionController.clearSelectionWithoutRefresh();

        selectedVariantPath =
                path;

        refresh();

        return true;
    }


    private List<VariantTapTarget> findVariantTapTargets(
            float screenX,
            float screenY
    ) {
        List<VariantTapTarget> result =
                new ArrayList<>();

        if (map == null) {
            return result;
        }

        float radius =
                stageDp(
                        28
                );

        RectF hitBox =
                new RectF(
                        screenX - radius,
                        screenY - radius,
                        screenX + radius,
                        screenY + radius
                );

        List<Feature> features =
                map.queryRenderedFeatures(
                        hitBox,
                        CaminoMapRenderer.VARIANT_LAYER
                );

        if (features == null
                || features.isEmpty()) {

            return result;
        }

        for (Feature feature
                : features) {

            if (feature == null
                    || !feature.hasProperty(
                    "variant_path_id"
            )
                    || !(feature.geometry()
                    instanceof Point)) {

                continue;
            }

            Point geometry =
                    (Point)
                            feature.geometry();

            LatLng point =
                    new LatLng(
                            geometry.latitude(),
                            geometry.longitude()
                    );

            PointF markerScreen =
                    map.getProjection()
                            .toScreenLocation(
                                    point
                            );

            float dx =
                    screenX
                            - markerScreen.x;

            float dy =
                    screenY
                            - markerScreen.y;

            float distanceSq =
                    dx * dx
                            + dy * dy;

            if (distanceSq
                    > radius * radius) {

                continue;
            }

            String pathId =
                    feature.getStringProperty(
                            "variant_path_id"
                    );

            boolean alreadyPresent =
                    false;

            for (VariantTapTarget existing
                    : result) {

                if (existing.pathId.equals(
                        pathId
                )) {

                    alreadyPresent =
                            true;

                    break;
                }
            }

            if (!alreadyPresent) {
                result.add(
                        new VariantTapTarget(
                                pathId,
                                point,
                                distanceSq
                        )
                );
            }
        }

        result.sort(
                Comparator
                        .comparingDouble(
                                (VariantTapTarget target) ->
                                        target.distanceSq
                        )
                        .thenComparing(
                                (VariantTapTarget target) ->
                                        target.pathId
                        )
        );

        return result;
    }


    private CaminoVariantPath findVariantPath(
            String pathId
    ) {
        if (pathId == null) {
            return null;
        }

        for (CaminoRoute route
                : routes) {

            for (CaminoVariantPath path
                    : route.variantPaths) {

                if (pathId.equals(
                        path.id
                )) {

                    return path;
                }
            }
        }

        return null;
    }


    private StageTapTarget findStageTapTarget(
            float screenX,
            float screenY
    ) {
        if (map == null) {
            return null;
        }

        /* 56 dp invisible collider around the visible shell. */
        float radius = stageDp(28);

        RectF hitBox =
                new RectF(
                        screenX - radius,
                        screenY - radius,
                        screenX + radius,
                        screenY + radius
                );

        List<Feature> stageFeatures =
                map.queryRenderedFeatures(
                        hitBox,
                        CaminoMapRenderer.STAGE_LAYER
                );

        if (stageFeatures == null || stageFeatures.isEmpty()) {
            return null;
        }

        StageTapTarget best = null;
        float bestDistanceSq = Float.POSITIVE_INFINITY;

        for (Feature feature : stageFeatures) {
            if (feature == null
                    || !feature.hasProperty("place_key")
                    || !(feature.geometry() instanceof Point)) {
                continue;
            }

            Point geometry = (Point) feature.geometry();

            LatLng point =
                    new LatLng(
                            geometry.latitude(),
                            geometry.longitude()
                    );

            PointF shellScreen =
                    map.getProjection()
                            .toScreenLocation(point);

            float dx = screenX - shellScreen.x;
            float dy = screenY - shellScreen.y;
            float distanceSq = dx * dx + dy * dy;

            if (distanceSq > radius * radius
                    || distanceSq >= bestDistanceSq) {
                continue;
            }

            bestDistanceSq = distanceSq;
            best =
                    new StageTapTarget(
                            feature.getStringProperty("place_key"),
                            point
                    );
        }

        return best;
    }


    private float stageDp(
            int value
    ) {
        return value
                * activity
                .getResources()
                .getDisplayMetrics()
                .density;
    }


    private boolean handleStagePlaceTap(
            String placeKey,
            LatLng stagePoint
    ) {
        selectedVariantPath =
                null;


        if (selectedStageSelection != null
                && selectedStagePlaceKey != null
                && !placeKey.equals(selectedStagePlaceKey)
                && placeKey.equals(
                selectedStageSelection.destinationPlaceKey
        )) {

            List<StageRouteSelection> extensionChoices =
                    findOutgoingStages(
                            placeKey,
                            stagePoint
                    );

            if (!extensionChoices.isEmpty()) {
                StageRouteSelection nextStage =
                        extensionChoices.get(0);

                if (nextStage.route
                        == selectedStageSelection.route
                        && compareRoutePosition(
                        nextStage.endHit,
                        selectedStageSelection.endHit
                ) > 0) {

                    selectedStageSelection =
                            selectedStageSelection.withExtendedEnd(
                                    nextStage.endHit,
                                    nextStage.destinationPlaceKey
                            );

                    selectionController.selectStage(
                            selectedStageSelection.route,
                            selectedStageSelection.startHit,
                            selectedStageSelection.endHit
                    );

                    return true;
                }
            }
        }

        List<StageRouteSelection> choices =
                findOutgoingStages(
                        placeKey,
                        stagePoint
                );

        if (choices.isEmpty()) {
            return true;
        }

        final boolean sameStage =
                placeKey.equals(
                        selectedStagePlaceKey
                );

        int choiceIndex =
                sameStage
                        ? (
                        selectedStageChoiceIndex + 1
                ) % choices.size()
                        : 0;

        StageRouteSelection stageSelection =
                choices.get(
                        choiceIndex
                );

        selectedStagePoint = stagePoint;
        selectedStageHighlightColor = stageSelection.route.highlightColor;
        selectedStagePlaceKey = placeKey;
        selectedStageChoiceIndex = choiceIndex;
        selectedStageSelection = stageSelection;

        selectionController.selectStage(
                stageSelection.route,
                stageSelection.startHit,
                stageSelection.endHit
        );

        return true;
    }


    private List<StageRouteSelection> findOutgoingStages(
            String placeKey,
            LatLng stagePoint
    ) {
        List<StageRouteSelection> result =
                new ArrayList<>();

        CaminoStageTopology.StageNode node =
                stageTopology.node(
                        placeKey,
                        stagePoint
                );

        if (node == null) {
            return result;
        }

        /*
         * One rendered StageNode may own several outgoing primary StageEdges.
         * Expand the already-established primary+variant resolver once for
         * every edge. This preserves all existing variant semantics while
         * making junctions between Caminos complete.
         */
        for (CaminoStageTopology.StageEdge edge
                : node.outgoing()) {

            stageRouteConstraint =
                    edge.route;

            stagePrimaryTrackConstraint =
                    edge.primaryTrackIndex;

            try {
                result.addAll(
                        findOutgoingStagesForSinglePrimary(
                                placeKey,
                                stagePoint
                        )
                );

            } finally {
                stageRouteConstraint =
                        null;

                stagePrimaryTrackConstraint =
                        -1;
            }
        }

        return result;
    }


    private List<StageRouteSelection> findOutgoingStagesForSinglePrimary(
            String placeKey,
            LatLng stagePoint
    ) {
        List<StageRouteSelection> result =
                new ArrayList<>();

        StageRouteSelection primary =
                findPrimaryOutgoingStage(
                        placeKey,
                        stagePoint
                );

        if (primary != null) {
            result.add(
                    primary
            );
        }

        return result;
    }


    private int compareRoutePosition(
            ProjectionHit left,
            ProjectionHit right
    ) {
        if (left.trackIndex
                != right.trackIndex) {

            return Integer.compare(
                    left.trackIndex,
                    right.trackIndex
            );
        }

        if (left.segmentIndex
                != right.segmentIndex) {

            return Integer.compare(
                    left.segmentIndex,
                    right.segmentIndex
            );
        }

        return Double.compare(
                left.t,
                right.t
        );
    }


    private StageRouteSelection findPrimaryOutgoingStage(
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

            if (stageRouteConstraint != null
                    && route
                    != stageRouteConstraint) {

                continue;
            }

            for (int trackIndex = 0;
                    trackIndex < route.tracks.size();
                    trackIndex++) {

                if (stagePrimaryTrackConstraint >= 0
                        && trackIndex
                        != stagePrimaryTrackConstraint) {

                    continue;
                }

                RouteTrack track =
                        route.tracks.get(
                                trackIndex
                        );

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
                 * Primary geometry can be reversed internally without swapping
                 * fromKey/toKey. The shell coordinate establishes the semantic
                 * stage start.
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
                                endHit,
                                trackIndex,
                                null,
                                true,
                                null,
                                null,
                                track.toKey
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

        selectedStagePlaceKey =
                null;

        selectedStageChoiceIndex =
                0;

        selectedStageSelection =
                null;

        selectedVariantPath =
                null;
    }


    private static final class VariantTapTarget {

        final String pathId;
        final LatLng point;
        final float distanceSq;

        VariantTapTarget(
                String pathId,
                LatLng point,
                float distanceSq
        ) {
            this.pathId =
                    pathId;

            this.point =
                    point;

            this.distanceSq =
                    distanceSq;
        }
    }


    private static final class StageTapTarget {

        final String placeKey;
        final LatLng point;

        StageTapTarget(
                String placeKey,
                LatLng point
        ) {
            this.placeKey = placeKey;
            this.point = point;
        }
    }


    private static final class StageRouteSelection {

        final CaminoRoute route;
        final ProjectionHit startHit;
        final ProjectionHit endHit;
        final int primaryTrackIndex;
        final RouteTrack variantTrack;
        final boolean variantStartIsFirst;
        final ProjectionHit variantEntryHit;
        final ProjectionHit mergeHit;
        final String destinationPlaceKey;

        StageRouteSelection(
                CaminoRoute route,
                ProjectionHit startHit,
                ProjectionHit endHit,
                int primaryTrackIndex,
                RouteTrack variantTrack,
                boolean variantStartIsFirst,
                ProjectionHit variantEntryHit,
                ProjectionHit mergeHit,
                String destinationPlaceKey
        ) {
            this.route = route;
            this.startHit = startHit;
            this.endHit = endHit;
            this.primaryTrackIndex = primaryTrackIndex;
            this.variantTrack = variantTrack;
            this.variantStartIsFirst = variantStartIsFirst;
            this.variantEntryHit = variantEntryHit;
            this.mergeHit = mergeHit;
            this.destinationPlaceKey = destinationPlaceKey;
        }

        boolean usesVariant() {
            return variantTrack != null
                    && variantEntryHit != null
                    && mergeHit != null;
        }

        StageRouteSelection withExtendedEnd(
                ProjectionHit extendedEndHit,
                String extendedDestinationPlaceKey
        ) {
            return new StageRouteSelection(
                    route,
                    startHit,
                    extendedEndHit,
                    primaryTrackIndex,
                    variantTrack,
                    variantStartIsFirst,
                    variantEntryHit,
                    mergeHit,
                    extendedDestinationPlaceKey
            );
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

        if (selectedVariantPath != null) {
            currentMeasurementPath =
                    measurementEngine.buildOfficialVariantPath(
                            selectedVariantPath
                    );

            interactionRenderer.renderMeasurementPath(
                    currentMeasurementPath
            );

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

        if (selectedStageSelection != null
                && selectedStageSelection.usesVariant()
                && selectionController.secondTapHit()
                != null) {

            currentMeasurementPath =
                    measurementEngine.buildStageVariantMeasurementPath(
                            selectedStageSelection.route,
                            selectedStageSelection.startHit,
                            selectedStageSelection.variantEntryHit,
                            selectedStageSelection.variantTrack,
                            selectedStageSelection.variantStartIsFirst,
                            selectedStageSelection.mergeHit,
                            selectedStageSelection.endHit
                    );

        } else {
            currentMeasurementPath =
                    measurementEngine.buildMeasurementPath(
                            routeStart,
                            routeEnd
                    );
        }

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
