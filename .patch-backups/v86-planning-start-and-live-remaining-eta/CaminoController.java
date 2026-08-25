package com.marukitano.caminoguard;

import android.app.Activity;
import android.graphics.PointF;
import android.graphics.RectF;
import android.view.HapticFeedbackConstants;
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
    private final CaminoUiStateStore uiStateStore;

    private boolean selectionLocked;

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

    /*
     * Extension carousel: preserve the already-selected prefix while rotating
     * alternatives at the current destination/fork shell.
     */
    private StageRouteSelection stageExtensionBaseSelection;
    private String stageExtensionPlaceKey;
    private int stageExtensionChoiceIndex =
            -1;

    private CaminoVariantPath selectedVariantPath;

    /*
     * One visible shell can represent several logical primary Camino edges.
     * These temporary constraints let the established variant resolver run
     * once for each topology edge without duplicating its Castro/Abla logic.
     */
    private final CaminoStageTopology stageTopology =
            new CaminoStageTopology();

    private final CaminoStagePathResolver stagePathResolver =
            new CaminoStagePathResolver();

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

        this.uiStateStore =
                new CaminoUiStateStore(
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

        infoController.setSelectionLockAction(
                this::toggleSelectionLock
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
                     * A locked selection is immutable. Ordinary map gestures
                     * still work because this only consumes the final map click,
                     * not pan/zoom touch movement.
                     */
                    if (selectionLocked) {
                        return true;
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

        stagePathResolver.rebuild(
                routes,
                stageTopology
        );

        caminoNetwork.rebuild(
                routes
        );

        restoreLockedSelectionIfPresent();
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

        boolean marked =
                hasMarkedSelection();

        heightProfileController.setMeasurementPath(
                marked
                        ? currentMeasurementPath
                        : null
        );

        heightProfileController.setLockedSelectionPosition(
                selectionLocked && marked
                        ? dummyPosition
                        : null,
                selectionLocked && marked
        );

        infoController.setSelectionLockAvailable(
                marked
        );

        infoController.setSelectionLocked(
                selectionLocked
        );

        selectionStatsOverlay.setLocked(
                selectionLocked
        );

        heightProfileController.refresh();
    }

    private boolean handleMapTouch(
            MotionEvent event
    ) {
        if (map == null) {
            return false;
        }

        if (selectionLocked) {
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
                     * Tactile confirmation exactly when shell mode turns into
                     * editable blue-point mode.
                     */
                    mapView.performHapticFeedback(
                            HapticFeedbackConstants.LONG_PRESS
                    );

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

        /*
         * The offset snail collider overlaps nearby shell colliders. A shell is
         * the stage selector, so it must get first refusal. Snails remain
         * tappable where no shell is under the tap.
         */
        PointF screenPoint =
                map.getProjection()
                        .toScreenLocation(tap);

        StageTapTarget target =
                findStageTapTarget(
                        screenPoint.x,
                        screenPoint.y
                );

        if (target != null) {
            return handleStagePlaceTap(
                    target.placeKey,
                    target.point
            );
        }

        return handleVariantTap(
                tap
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

        /*
         * Repeated tap on the same fork while its extension carousel is active.
         */
        if (stageExtensionBaseSelection != null
                && stageExtensionPlaceKey != null
                && stageExtensionPlaceKey.equals(
                placeKey
        )) {

            List<StageRouteSelection> extensionChoices =
                    findOutgoingStages(
                            placeKey,
                            stagePoint
                    );

            if (!extensionChoices.isEmpty()) {
                stageExtensionChoiceIndex =
                        (
                                stageExtensionChoiceIndex + 1
                        )
                                % extensionChoices.size();

                StageRouteSelection combined =
                        appendStageSelection(
                                stageExtensionBaseSelection,
                                extensionChoices.get(
                                        stageExtensionChoiceIndex
                                )
                        );

                if (combined != null) {
                    applyExtendedStageSelection(
                            combined
                    );

                    return true;
                }
            }

            clearStageExtensionCarousel();
        }

        /*
         * Tap the CURRENT destination shell:
         * append the next section. At a fork, choice 0 is appended first and
         * repeated taps on the same fork rotate all alternatives while the
         * complete prefix stays selected.
         */
        if (selectedStageSelection != null
                && selectedStagePlaceKey != null
                && !placeKey.equals(
                selectedStagePlaceKey
        )
                && placeKey.equals(
                selectedStageSelection.destinationPlaceKey
        )) {

            List<StageRouteSelection> extensionChoices =
                    findOutgoingStages(
                            placeKey,
                            stagePoint
                    );

            if (!extensionChoices.isEmpty()) {
                StageRouteSelection base =
                        selectedStageSelection;

                if (extensionChoices.size() > 1) {
                    stageExtensionBaseSelection =
                            base;

                    stageExtensionPlaceKey =
                            placeKey;

                    stageExtensionChoiceIndex =
                            0;

                } else {
                    clearStageExtensionCarousel();
                }

                StageRouteSelection combined =
                        appendStageSelection(
                                base,
                                extensionChoices.get(
                                        0
                                )
                        );

                if (combined != null) {
                    applyExtendedStageSelection(
                            combined
                    );

                    return true;
                }

                clearStageExtensionCarousel();
            }
        }

        clearStageExtensionCarousel();

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

        selectedStagePoint =
                stagePoint;

        selectedStageHighlightColor =
                stageSelection.route.highlightColor;

        selectedStagePlaceKey =
                placeKey;

        selectedStageChoiceIndex =
                choiceIndex;

        selectedStageSelection =
                stageSelection;

        selectionController.selectStage(
                stageSelection.route,
                stageSelection.startHit,
                stageSelection.endHit
        );

        return true;
    }


    private StageRouteSelection appendStageSelection(
            StageRouteSelection prefix,
            StageRouteSelection next
    ) {
        if (prefix == null
                || next == null
                || prefix.route
                != next.route) {

            return null;
        }

        if (prefix.resolvedPath != null
                && next.resolvedPath != null) {

            CaminoResolvedStagePath combined =
                    stagePathResolver.append(
                            prefix.resolvedPath,
                            next.resolvedPath
                    );

            if (combined == null) {
                return null;
            }

            return new StageRouteSelection(
                    combined.route,
                    combined.startHit,
                    combined.endHit,
                    prefix.primaryTrackIndex,
                    null,
                    true,
                    null,
                    null,
                    combined.destinationPlaceKey,
                    combined
            );
        }

        if (compareRoutePosition(
                next.endHit,
                prefix.endHit
        ) <= 0) {

            return null;
        }

        return prefix.withExtendedEnd(
                next.endHit,
                next.destinationPlaceKey
        );
    }


    private void applyExtendedStageSelection(
            StageRouteSelection combined
    ) {
        selectedStageSelection =
                combined;

        selectionController.selectStage(
                combined.route,
                combined.startHit,
                combined.endHit
        );
    }


    private void clearStageExtensionCarousel() {
        stageExtensionBaseSelection =
                null;

        stageExtensionPlaceKey =
                null;

        stageExtensionChoiceIndex =
                -1;
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

        for (CaminoStageTopology.StageEdge edge
                : node.outgoing()) {

            appendResolvedStageChoices(
                    result,
                    stagePathResolver.findChoices(
                            edge.route,
                            edge.primaryTrackIndex,
                            placeKey
                    )
            );
        }

        if (node.outgoing().isEmpty()) {
            appendResolvedStageChoices(
                    result,
                    stagePathResolver.findDecisionChoices(
                            placeKey,
                            stagePoint
                    )
            );
        }

        return result;
    }


    private void appendResolvedStageChoices(
            List<StageRouteSelection> output,
            List<CaminoResolvedStagePath> resolvedPaths
    ) {
        if (resolvedPaths == null
                || resolvedPaths.isEmpty()) {

            return;
        }

        java.util.LinkedHashSet<String> existingIds =
                new java.util.LinkedHashSet<>();

        for (StageRouteSelection existing
                : output) {

            if (existing.resolvedPath != null) {
                existingIds.add(
                        existing.resolvedPath.id
                );
            }
        }

        for (CaminoResolvedStagePath resolved
                : resolvedPaths) {

            if (resolved == null
                    || !existingIds.add(
                    resolved.id
            )) {

                continue;
            }

            output.add(
                    new StageRouteSelection(
                            resolved.route,
                            resolved.startHit,
                            resolved.endHit,
                            resolved.startHit.trackIndex,
                            null,
                            true,
                            null,
                            null,
                            resolved.destinationPlaceKey,
                            resolved
                    )
            );
        }
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


    private boolean hasMarkedSelection() {
        return selectedVariantPath != null
                || selectionController.secondTapHit() != null;
    }


    private void toggleSelectionLock() {
        if (selectionLocked) {
            selectionLocked =
                    false;

            selectionStatsOverlay.setLocked(
                    false
            );

            uiStateStore.clearLockedSelection();

            infoController.setSelectionLocked(
                    false
            );

            heightProfileController.setLockedSelectionPosition(
                    null,
                    false
            );

            heightProfileController.refresh();

            return;
        }

        if (!hasMarkedSelection()
                || currentMeasurementPath == null) {

            return;
        }

        selectionLocked =
                true;

        selectionStatsOverlay.setLocked(
                true
        );

        persistLockedSelection();

        infoController.setSelectionLocked(
                true
        );

        heightProfileController.setLockedSelectionPosition(
                dummyPosition,
                true
        );

        heightProfileController.refresh();
    }


    private void persistLockedSelection() {
        String startRouteId =
                null;

        LatLng startPoint =
                null;

        String endRouteId =
                null;

        LatLng endPoint =
                null;

        if (selectionController.selectedRoute() != null
                && selectionController.selectedHit() != null) {

            startRouteId =
                    selectionController.selectedRoute().id;

            startPoint =
                    selectionController.selectedHit().point;
        }

        if (selectionController.secondSelectedRoute() != null
                && selectionController.secondTapHit() != null) {

            endRouteId =
                    selectionController.secondSelectedRoute().id;

            endPoint =
                    selectionController.secondTapHit().point;
        }

        String variantPathId =
                selectedVariantPath == null
                        ? null
                        : selectedVariantPath.id;

        String resolvedPathId =
                selectedStageSelection == null
                        || selectedStageSelection.resolvedPath == null
                        ? null
                        : selectedStageSelection.resolvedPath.id;

        uiStateStore.saveLockedSelection(
                startRouteId,
                startPoint,
                endRouteId,
                endPoint,
                variantPathId,
                selectedStagePlaceKey,
                selectedStagePoint,
                resolvedPathId
        );
    }


    private void restoreLockedSelectionIfPresent() {
        CaminoUiStateStore.LockedSelectionState saved =
                uiStateStore.restoreLockedSelection();

        if (saved == null) {
            selectionLocked =
                    false;

            return;
        }

        if (saved.variantPathId != null) {
            CaminoVariantPath variant =
                    findVariantPath(
                            saved.variantPathId
                    );

            if (variant != null) {
                selectionController.clearSelectionWithoutRefresh();

                selectedVariantPath =
                        variant;

                selectionLocked =
                        true;

                return;
            }
        }

        if (saved.stagePlaceKey != null
                && saved.stagePoint != null
                && saved.resolvedPathId != null) {

            List<StageRouteSelection> choices =
                    findOutgoingStages(
                            saved.stagePlaceKey,
                            saved.stagePoint
                    );

            for (int index = 0;
                    index < choices.size();
                    index++) {

                StageRouteSelection choice =
                        choices.get(
                                index
                        );

                if (choice.resolvedPath == null
                        || !saved.resolvedPathId.equals(
                        choice.resolvedPath.id
                )) {

                    continue;
                }

                selectedVariantPath =
                        null;

                selectedStagePoint =
                        saved.stagePoint;

                selectedStageHighlightColor =
                        choice.route.highlightColor;

                selectedStagePlaceKey =
                        saved.stagePlaceKey;

                selectedStageChoiceIndex =
                        index;

                selectedStageSelection =
                        choice;

                selectionController.restoreSelection(
                        choice.route,
                        choice.startHit,
                        choice.route,
                        choice.endHit
                );

                selectionLocked =
                        true;

                return;
            }
        }

        if (saved.startRouteId == null
                || saved.startPoint == null
                || saved.endRouteId == null
                || saved.endPoint == null) {

            uiStateStore.clearLockedSelection();

            selectionLocked =
                    false;

            return;
        }

        CaminoRoute startRoute =
                findRouteById(
                        saved.startRouteId
                );

        CaminoRoute endRoute =
                findRouteById(
                        saved.endRouteId
                );

        if (startRoute == null
                || endRoute == null) {

            uiStateStore.clearLockedSelection();

            selectionLocked =
                    false;

            return;
        }

        ProjectionHit startHit =
                projectionEngine.projectToSelectableRoute(
                        startRoute,
                        saved.startPoint
                );

        ProjectionHit endHit =
                projectionEngine.projectToSelectableRoute(
                        endRoute,
                        saved.endPoint
                );

        if (startHit == null
                || endHit == null
                || startHit.distanceFromQueryM > 50.0
                || endHit.distanceFromQueryM > 50.0) {

            uiStateStore.clearLockedSelection();

            selectionLocked =
                    false;

            return;
        }

        clearSelectedStageVisual();

        selectionController.restoreSelection(
                startRoute,
                startHit,
                endRoute,
                endHit
        );

        selectionLocked =
                true;
    }


    private CaminoRoute findRouteById(
            String routeId
    ) {
        if (routeId == null) {
            return null;
        }

        for (CaminoRoute route
                : routes) {

            if (routeId.equals(
                    route.id
            )) {

                return route;
            }
        }

        return null;
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
        clearStageExtensionCarousel();
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
        final CaminoResolvedStagePath resolvedPath;

        StageRouteSelection(
                CaminoRoute route,
                ProjectionHit startHit,
                ProjectionHit endHit,
                int primaryTrackIndex,
                RouteTrack variantTrack,
                boolean variantStartIsFirst,
                ProjectionHit variantEntryHit,
                ProjectionHit mergeHit,
                String destinationPlaceKey,
                CaminoResolvedStagePath resolvedPath
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
            this.resolvedPath = resolvedPath;
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
                    extendedDestinationPlaceKey,
                    resolvedPath
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
                && selectedStageSelection.resolvedPath != null
                && selectionController.secondTapHit()
                != null) {

            currentMeasurementPath =
                    measurementEngine.buildResolvedStagePath(
                            selectedStageSelection.resolvedPath
                    );

        } else if (selectedStageSelection != null
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
