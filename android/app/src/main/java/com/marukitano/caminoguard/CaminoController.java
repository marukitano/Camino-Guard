package com.marukitano.caminoguard;

import android.app.Activity;
import android.graphics.Color;
import android.os.SystemClock;
import android.text.format.DateFormat;
import android.graphics.PointF;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.maplibre.android.camera.CameraPosition;
import org.maplibre.android.camera.CameraUpdateFactory;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.MapView;
import org.maplibre.android.maps.Style;
import org.maplibre.android.style.layers.CircleLayer;
import org.maplibre.android.style.layers.LineLayer;
import org.maplibre.android.style.layers.Property;
import org.maplibre.android.style.layers.PropertyFactory;
import org.maplibre.android.style.expressions.Expression;
import org.maplibre.android.style.sources.GeoJsonSource;
import org.maplibre.geojson.Feature;
import org.maplibre.geojson.FeatureCollection;
import org.maplibre.geojson.LineString;
import org.maplibre.geojson.Point;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Global Camino interaction controller.
 *
 * Selection, measurement, routing, height profile and live navigation
 * use the same canonical Camino dataset everywhere.
 */
public final class CaminoController {

    private static final String SELECTED_ROUTE_SOURCE =
            "camino-selected-route-source";
    private static final String SELECTED_ROUTE_LAYER =
            "camino-selected-route";
    private static final String SELECTED_ROUTE_HALO_LAYER =
            "camino-selected-route-halo";

    private static final String CONNECTOR_SOURCE =
            "camino-start-connector-source";
    private static final String CONNECTOR_LAYER =
            "camino-start-connector";

    private static final String DUMMY_SOURCE =
            "camino-dummy-position-source";
    private static final String DUMMY_LAYER =
            "camino-dummy-position";

    private static final String START_SNAP_SOURCE =
            "camino-start-snap-source";
    private static final String START_SNAP_LAYER =
            "camino-start-snap";

    private static final String SELECTED_SOURCE =
            "camino-selected-position-source";
    private static final String SELECTED_LAYER =
            "camino-selected-position";

    private static final String ROUTE_GAP_SOURCE =
            "camino-route-gap-source";
    private static final String ROUTE_GAP_LAYER =
            "camino-route-gap";

    /*
     * Cross-group semantic place matches may still have offset official
     * geometry. Keep the same conservative 5 km guard used by the processed
     * place identity and count the actual straight gap in the measurement.
     */
    private static final double MAX_SEMANTIC_TRANSFER_GAP_M =
            CaminoConfig.get().doubleValue(
                    "measurement.maxSemanticTransferGapMeters"
            );

    private static final double EARTH_RADIUS_M = 6371008.8;

    /*
     * CAMINO_HEIGHT_PROFILE_V1
     *
     * Height samples are retained much more sparsely than the raw CNIG survey
     * geometry. The source geometry still drives route distance exactly.
     */
    private static final long HEIGHT_PROFILE_REFRESH_DELAY_MS =
            CaminoConfig.get().longValue(
                    "measurement.heightProfileRefreshDelayMs"
            );
    private static final double SPEED_SAMPLE_MIN_MOVE_M =
            CaminoConfig.get().doubleValue(
                    "measurement.speedSampleMinMoveMeters"
            );
    private static final double SPEED_SAMPLE_MAX_JUMP_M =
            CaminoConfig.get().doubleValue(
                    "measurement.speedSampleMaxJumpMeters"
            );

    private static final long NAVIGATION_RECENTER_DELAY_MS =
            CaminoConfig.get().longValue(
                    "navigation.recenterDelayMs"
            );
    private static final double NAVIGATION_VERTICAL_WINDOW_M =
            CaminoConfig.get().doubleValue(
                    "navigation.verticalWindowMeters"
            );
    private static final double NAVIGATION_CAMERA_LEAD_M =
            CaminoConfig.get().doubleValue(
                    "navigation.cameraLeadMeters"
            );

    private static final int DRAG_NONE = 0;
    private static final int DRAG_DUMMY = 1;
    private static final int DRAG_POINT_1 = 2;
    private static final int DRAG_POINT_2 = 3;

    private final Activity activity;
    private final MapView mapView;
    private final CaminoRepository caminoRepository;
    private final List<CaminoRoute> routes =
            new ArrayList<>();
    private final CaminoNetwork caminoNetwork;
    private final MeasurementEngine measurementEngine;
    /*
     * Transitional read-only alias used by Measurement code.
     * CaminoNetwork owns and rebuilds this list.
     */
    private final List<NetworkTrack> networkTracks;

    private MapLibreMap map;

    private GeoJsonSource selectedRouteSource;
    private GeoJsonSource connectorSource;
    private GeoJsonSource dummySource;
    private GeoJsonSource startSnapSource;
    private GeoJsonSource selectedSource;
    private GeoJsonSource routeGapSource;

    private TextView distanceView;
    private CaminoHeightProfileView heightProfileView;
    private CaminoInfoPanel infoPanel;

    private boolean navigationFollowEnabled;
    private boolean navigationFollowSuspended;
    private int navigationResumeGeneration;


    private String infoTitleText = "";
    private String summaryLeftText = "";
    private String summaryRightText = "";
    private String heightStatsText = "";
    private String speedStatsText = "";

    private long travelSessionStartElapsedMs = -1L;
    private long travelMovingElapsedMs = 0L;
    private double travelDistanceM = 0.0;
    private LatLng lastTravelSamplePosition;
    private long lastTravelSampleElapsedMs = -1L;
    private boolean heightProfileRefreshScheduled;

    private final Runnable heightProfileRefreshRunnable =
            () -> {
                heightProfileRefreshScheduled =
                        false;
                refreshHeightProfile();
            };

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
    private GpsGyroOrientationController liveNavigationController;

    private final CaminoTrackingService.Listener livePositionListener =
            this::handleLiveTrackingState;

    private CaminoRoute selectedRoute;
    private ProjectionHit selectedHit;
    private CaminoRoute secondSelectedRoute;
    private ProjectionHit secondTapHit;

    private MeasurementPath currentMeasurementPath;

    private int dragTarget = DRAG_NONE;

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

        this.networkTracks =
                caminoNetwork.tracks();

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
        this.navigationFollowEnabled = true;
        this.navigationFollowSuspended = false;
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

        if (liveNavigationController != null) {
            liveNavigationController
                    .setExternalNavigationFollowEnabled(
                            navigationFollowEnabled
                    );
        }
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
        liveNavigationController =
                controller;

        if (livePositionMode
                && liveNavigationController != null) {
            liveNavigationController
                    .setExternalNavigationFollowEnabled(
                            navigationFollowEnabled
                    );
        }
    }

    public void attachMap(
            MapLibreMap map
    ) {
        this.map = map;

        map.addOnMapClickListener(
                this::handleMapTap
        );

        /*
         * Keep the profile alive while the map is panned, zoomed or rotated.
         * Camera-move updates are deliberately throttled; CameraIdle performs
         * one exact final refresh.
         */
        map.addOnCameraMoveListener(
                this::scheduleHeightProfileRefresh
        );

        map.addOnCameraIdleListener(
                this::handleHeightProfileCameraIdle
        );

        map.addOnCameraMoveStartedListener(
                this::handleNavigationCameraMoveStarted
        );

        mapView.setOnTouchListener(
                (view, event) ->
                        handleTouch(event)
        );
    }

    private boolean handleMapTap(
            LatLng point
    ) {
        if (dragTarget != DRAG_NONE
                || routes.isEmpty()) {
            return false;
        }

        /*
         * Tap 1:
         * select whichever Camino is actually under the finger.
         */
        if (selectedHit == null) {
            RouteHit routeHit =
                    findNearestRouteHit(
                            point
                    );

            if (routeHit == null
                    || !isTapCloseEnough(
                            point,
                            routeHit.hit.point
                    )) {
                clearSelection();
                return false;
            }

            selectedRoute =
                    routeHit.route;
            selectedHit =
                    routeHit.hit;
            secondSelectedRoute =
                    null;
            secondTapHit =
                    null;

            refresh();
            return true;
        }

        /*
         * Tap 2:
         * may select another Camino. The measurement engine finds the
         * semantic Camino connection between the two selected tracks.
         */
        if (secondTapHit == null) {
            RouteHit routeHit =
                    findNearestRouteHit(
                            point
                    );

            if (routeHit == null
                    || !isTapCloseEnough(
                            point,
                            routeHit.hit.point
                    )) {
                clearSelection();
                return false;
            }

            secondSelectedRoute =
                    routeHit.route;
            secondTapHit =
                    routeHit.hit;

            refresh();
            return true;
        }

        /*
         * Tap 3:
         * discard the old two-point measurement and immediately start a new
         * measurement. The third tap may therefore select another Camino.
         */
        RouteHit routeHit =
                findNearestRouteHit(
                        point
                );

        if (routeHit == null
                || !isTapCloseEnough(
                        point,
                        routeHit.hit.point
                )) {
            clearSelection();
            return false;
        }

        selectedRoute =
                routeHit.route;
        selectedHit =
                routeHit.hit;
        secondSelectedRoute =
                null;
        secondTapHit =
                null;

        refresh();
        return true;
    }

    private boolean handleTouch(
            MotionEvent event
    ) {
        if (map == null) {
            return false;
        }

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragTarget =
                        findDragTarget(
                                event.getX(),
                                event.getY()
                        );

                return dragTarget
                        != DRAG_NONE;

            case MotionEvent.ACTION_MOVE:
                if (dragTarget
                        == DRAG_NONE) {
                    return false;
                }

                /*
                 * CAMINO_DRAG_PERFORMANCE_V3
                 *
                 * Dragging must stay cheap. Move only the interactive marker
                 * (and the start connector for the fake GPS position). The
                 * complete network route / GeoJSON overlay is rebuilt once on
                 * ACTION_UP instead of once for every touch event.
                 */
                moveDragTarget(
                        event.getX(),
                        event.getY(),
                        true
                );

                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (dragTarget
                        == DRAG_NONE) {
                    return false;
                }

                moveDragTarget(
                        event.getX(),
                        event.getY(),
                        false
                );

                dragTarget =
                        DRAG_NONE;

                return true;

            default:
                return dragTarget
                        != DRAG_NONE;
        }
    }

    private int findDragTarget(
            float x,
            float y
    ) {
        final float grabRadius =
                dp(34);

        final float maxDistanceSq =
                grabRadius
                        * grabRadius;

        int bestTarget =
                DRAG_NONE;

        float bestDistanceSq =
                Float.MAX_VALUE;

        if (secondTapHit != null) {
            float distanceSq =
                    screenDistanceSq(
                            x,
                            y,
                            secondTapHit.point
                    );

            if (distanceSq
                    <= maxDistanceSq
                    && distanceSq
                    < bestDistanceSq) {
                bestTarget =
                        DRAG_POINT_2;

                bestDistanceSq =
                        distanceSq;
            }
        }

        if (selectedHit != null) {
            float distanceSq =
                    screenDistanceSq(
                            x,
                            y,
                            selectedHit.point
                    );

            if (distanceSq
                    <= maxDistanceSq
                    && distanceSq
                    < bestDistanceSq) {
                bestTarget =
                        DRAG_POINT_1;

                bestDistanceSq =
                        distanceSq;
            }
        }

        if (!livePositionMode) {
            float dummyDistanceSq =
                    screenDistanceSq(
                            x,
                            y,
                            dummyPosition
                    );

            if (dummyDistanceSq
                    <= maxDistanceSq
                    && dummyDistanceSq
                    < bestDistanceSq) {
                bestTarget =
                        DRAG_DUMMY;
            }

        }

        return bestTarget;
    }

    private float screenDistanceSq(
            float x,
            float y,
            LatLng point
    ) {
        PointF screen =
                map.getProjection()
                        .toScreenLocation(
                                point
                        );

        float dx =
                x - screen.x;

        float dy =
                y - screen.y;

        return dx * dx
                + dy * dy;
    }

    private void moveDragTarget(
            float x,
            float y,
            boolean previewOnly
    ) {
        LatLng fingerPosition =
                map.getProjection()
                        .fromScreenLocation(
                                new PointF(
                                        x,
                                        y
                                )
                        );

        if (dragTarget
                == DRAG_DUMMY) {
            dummyPosition =
                    fingerPosition;

            if (previewOnly) {
                refreshDragPreview();
            } else {
                refresh();

                noteTravelSample(
                        dummyPosition
                );

                if (navigationFollowEnabled
                        && !navigationFollowSuspended) {
                    applyNavigationFollow(
                            true
                    );
                }
            }
            return;
        }

        CaminoRoute dragRoute =
                dragTarget == DRAG_POINT_2
                        ? secondSelectedRoute
                        : selectedRoute;

        if (dragRoute == null) {
            return;
        }

        /*
         * projectToRoute() is now track-bounds pruned, so snapping a dragged
         * measurement point no longer scans every segment of the whole Camino.
         */
        ProjectionHit snapped =
                projectToRoute(
                        dragRoute,
                        fingerPosition
                );

        if (snapped == null) {
            return;
        }

        if (dragTarget
                == DRAG_POINT_1) {
            selectedHit =
                    snapped;

        } else if (dragTarget
                == DRAG_POINT_2) {
            secondTapHit =
                    snapped;
        }

        if (previewOnly) {
            refreshDragPreview();
        } else {
            refresh();
        }
    }

    /**
     * Cheap UI-only refresh used while a finger is moving.
     *
     * The expensive measured route and network path intentionally remain at
     * their previous geometry until the finger is released. This keeps the
     * marker attached to the finger instead of blocking the Android UI thread
     * for seconds while serialising a country-scale GeoJSON overlay.
     */
    private void refreshDragPreview() {
        updateDummySource();
        updateSelectedSource();

        if (dragTarget
                != DRAG_DUMMY
                || secondTapHit != null) {
            return;
        }

        RouteHit startRouteHit =
                findNearestRouteHit(
                        dummyPosition
                );

        ProjectionHit startHit =
                startRouteHit == null
                        ? null
                        : startRouteHit.hit;

        updateStartProjection(
                startHit
        );

        updateConnector(
                startRouteHit
        );

        /*
         * Before a destination point exists the label does not depend on a
         * network path, so it is safe and cheap to keep it live while dragging.
         */
        if (selectedHit == null) {
            updateDistanceLabel(
                    startRouteHit
            );
        }
    }

    public void onStyleLoaded(
            Style style
    ) {
        ensureDistanceView();
        ensureHeightProfileView();

        selectedRouteSource =
                new GeoJsonSource(
                        SELECTED_ROUTE_SOURCE,
                        emptyFeatures()
                );

        style.addSource(
                selectedRouteSource
        );

        LineLayer selectedRouteHaloLayer =
                new LineLayer(
                        SELECTED_ROUTE_HALO_LAYER,
                        SELECTED_ROUTE_SOURCE
                );

        selectedRouteHaloLayer.setProperties(
                PropertyFactory.lineColor(
                        Expression.get(
                                "highlight_color"
                        )
                ),
                PropertyFactory.lineWidth(
                        CaminoConfig.get().floatValue(
                                "routes.selection.haloWidth"
                        )
                ),
                PropertyFactory.lineOpacity(
                        0.96f
                ),
                PropertyFactory.lineCap(
                        Property.LINE_CAP_ROUND
                ),
                PropertyFactory.lineJoin(
                        Property.LINE_JOIN_ROUND
                )
        );

        style.addLayer(
                selectedRouteHaloLayer
        );

        LineLayer selectedRouteLayer =
                new LineLayer(
                        SELECTED_ROUTE_LAYER,
                        SELECTED_ROUTE_SOURCE
                );

        selectedRouteLayer.setProperties(
                PropertyFactory.lineColor(
                        Expression.get(
                                "color"
                        )
                ),
                PropertyFactory.lineWidth(
                        CaminoConfig.get().floatValue(
                                "routes.selection.lineWidth"
                        )
                ),
                PropertyFactory.lineOpacity(
                        1.0f
                ),
                PropertyFactory.lineCap(
                        Property.LINE_CAP_ROUND
                ),
                PropertyFactory.lineJoin(
                        Property.LINE_JOIN_ROUND
                )
        );

        style.addLayer(
                selectedRouteLayer
        );

        connectorSource =
                new GeoJsonSource(
                        CONNECTOR_SOURCE,
                        emptyFeatures()
                );

        style.addSource(
                connectorSource
        );

        LineLayer connector =
                new LineLayer(
                        CONNECTOR_LAYER,
                        CONNECTOR_SOURCE
                );

        connector.setProperties(
                PropertyFactory.lineColor(
                        Expression.get(
                                "highlight_color"
                        )
                ),
                PropertyFactory.lineWidth(
                        2.5f
                ),
                PropertyFactory.lineOpacity(
                        0.90f
                ),
                PropertyFactory.lineCap(
                        Property.LINE_CAP_ROUND
                )
        );

        style.addLayer(
                connector
        );

        routeGapSource =
                new GeoJsonSource(
                        ROUTE_GAP_SOURCE,
                        emptyFeatures()
                );

        style.addSource(
                routeGapSource
        );

        LineLayer routeGapLayer =
                new LineLayer(
                        ROUTE_GAP_LAYER,
                        ROUTE_GAP_SOURCE
                );

        routeGapLayer.setProperties(
                PropertyFactory.lineColor(
                        Expression.get(
                                "highlight_color"
                        )
                ),
                PropertyFactory.lineWidth(
                        2.5f
                ),
                PropertyFactory.lineOpacity(
                        0.90f
                ),
                PropertyFactory.lineCap(
                        Property.LINE_CAP_ROUND
                )
        );

        style.addLayer(
                routeGapLayer
        );

        dummySource =
                new GeoJsonSource(
                        DUMMY_SOURCE,
                        Point.fromLngLat(
                                dummyPosition
                                        .getLongitude(),
                                dummyPosition
                                        .getLatitude()
                        )
                );

        style.addSource(
                dummySource
        );

        CircleLayer dummy =
                new CircleLayer(
                        DUMMY_LAYER,
                        DUMMY_SOURCE
                );

        dummy.setProperties(
                PropertyFactory.circleOpacity(
                        livePositionMode ? 0.0f : 1.0f
                ),
                PropertyFactory.circleRadius(
                        10.0f
                ),
                PropertyFactory.circleColor(
                        Color.parseColor(
                                "#F5C98E"
                        )
                ),
                PropertyFactory.circleStrokeColor(
                        Color.parseColor(
                                "#3D332C"
                        )
                ),
                PropertyFactory.circleStrokeWidth(
                        3.0f
                )
        );

        style.addLayer(
                dummy
        );

        startSnapSource =
                new GeoJsonSource(
                        START_SNAP_SOURCE,
                        emptyFeatures()
                );

        style.addSource(
                startSnapSource
        );

        CircleLayer startSnap =
                new CircleLayer(
                        START_SNAP_LAYER,
                        START_SNAP_SOURCE
                );

        startSnap.setProperties(
                PropertyFactory.circleRadius(
                        5.5f
                ),
                PropertyFactory.circleColor(
                        Color.parseColor(
                                "#FFF0C8"
                        )
                ),
                PropertyFactory.circleStrokeColor(
                        Color.parseColor(
                                "#3D332C"
                        )
                ),
                PropertyFactory.circleStrokeWidth(
                        2.0f
                )
        );

        style.addLayer(
                startSnap
        );

        selectedSource =
                new GeoJsonSource(
                        SELECTED_SOURCE,
                        emptyFeatures()
                );

        style.addSource(
                selectedSource
        );

        CircleLayer selected =
                new CircleLayer(
                        SELECTED_LAYER,
                        SELECTED_SOURCE
                );

        selected.setProperties(
                PropertyFactory.circleRadius(
                        7.0f
                ),
                PropertyFactory.circleColor(
                        Color.parseColor(
                                "#4A90E2"
                        )
                ),
                PropertyFactory.circleStrokeColor(
                        Color.parseColor(
                                "#3D332C"
                        )
                ),
                PropertyFactory.circleStrokeWidth(
                        2.5f
                )
        );

        style.addLayer(
                selected
        );

        try {
            loadRoutes();
            refresh();

        } catch (Exception error) {
            setLabel(
                    "Camino Fehler: "
                            + (error.getMessage()
                            == null
                            ? error.getClass()
                            .getSimpleName()
                            : error.getMessage())
            );
        }
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
        updateDummySource();
        updateSelectedSource();

        /*
         * CAMINO_NETWORK_MEASUREMENT_V2
         *
         * The fake position is always projected onto the nearest Camino,
         * even before a measurement point exists. This is the same behavior
         * the real GPS position will need later.
         */
        RouteHit startRouteHit =
                routes.isEmpty()
                        ? null
                        : findNearestRouteHit(
                                dummyPosition
                        );

        ProjectionHit startHit =
                startRouteHit == null
                        ? null
                        : startRouteHit.hit;

        if (secondTapHit
                == null) {
            updateStartProjection(
                    startHit
            );

            updateConnector(
                startRouteHit
        );

        } else {
            hideStartProjectionAndConnector();
        }

        updateSelectedRoute(
                startRouteHit
        );

        updateDistanceLabel(
                startRouteHit
        );

        refreshHeightProfile();
    }

    private RouteHit findNearestRouteHit(
            LatLng query
    ) {
        if (networkTracks.isEmpty()) {
            return null;
        }

        /*
         * First choose the track whose precomputed bounding circle has the
         * smallest possible distance to the query. Project that one exactly,
         * then only inspect tracks whose lower bound can still beat the best
         * exact result. In normal use this turns a scan of all Camino points
         * into a scan of a few hundred cheap bounds plus one/few local tracks.
         */
        NetworkTrack seed =
                null;

        double seedLowerBoundM =
                Double.POSITIVE_INFINITY;

        for (NetworkTrack reference
                : networkTracks) {

            double lowerBoundM =
                    trackLowerBoundDistanceMeters(
                            reference.track,
                            query
                    );

            if (lowerBoundM
                    < seedLowerBoundM) {
                seedLowerBoundM =
                        lowerBoundM;
                seed =
                        reference;
            }
        }

        if (seed == null) {
            return null;
        }

        ProjectionHit seedHit =
                projectToTrack(
                        seed.route,
                        seed.trackIndex,
                        query
                );

        if (seedHit == null) {
            return null;
        }

        RouteHit best =
                new RouteHit(
                        seed.route,
                        seedHit
                );

        for (NetworkTrack reference
                : networkTracks) {

            if (reference == seed) {
                continue;
            }

            double lowerBoundM =
                    trackLowerBoundDistanceMeters(
                            reference.track,
                            query
                    );

            if (lowerBoundM
                    > best.hit.distanceFromQueryM) {
                continue;
            }

            ProjectionHit hit =
                    projectToTrack(
                            reference.route,
                            reference.trackIndex,
                            query
                    );

            if (hit != null
                    && hit.distanceFromQueryM
                    < best.hit.distanceFromQueryM) {

                best =
                        new RouteHit(
                                reference.route,
                                hit
                        );
            }
        }

        return best;
    }

    private ProjectionHit projectToRoute(
            CaminoRoute route,
            LatLng query
    ) {
        if (route == null) {
            return null;
        }

        ProjectionHit best =
                null;

        for (int trackIndex = 0;
                trackIndex < route.tracks.size();
                trackIndex++) {

            RouteTrack track =
                    route.tracks.get(
                            trackIndex
                    );

            double lowerBoundM =
                    trackLowerBoundDistanceMeters(
                            track,
                            query
                    );

            if (best != null
                    && lowerBoundM
                    > best.distanceFromQueryM) {
                continue;
            }

            ProjectionHit hit =
                    projectToTrack(
                            route,
                            trackIndex,
                            query
                    );

            if (hit != null
                    && (best == null
                    || hit.distanceFromQueryM
                    < best.distanceFromQueryM)) {
                best =
                        hit;
            }
        }

        return best;
    }

    private ProjectionHit projectToTrack(
            CaminoRoute route,
            int trackIndex,
            LatLng query
    ) {
        if (route == null
                || trackIndex < 0
                || trackIndex >= route.tracks.size()) {
            return null;
        }

        RouteTrack track =
                route.tracks.get(
                        trackIndex
                );

        ProjectionHit best =
                null;

        double alongTrackM =
                0.0;

        for (int segmentIndex = 0;
                segmentIndex
                        < track.points.size() - 1;
                segmentIndex++) {

            LatLng a =
                    track.points.get(
                            segmentIndex
                    );

            LatLng b =
                    track.points.get(
                            segmentIndex + 1
                    );

            ProjectionHit hit =
                    projectToSegment(
                            query,
                            a,
                            b,
                            track.baseChainageM
                                    + alongTrackM,
                            trackIndex,
                            segmentIndex
                    );

            if (best == null
                    || hit.distanceFromQueryM
                    < best.distanceFromQueryM) {
                best =
                        hit;
            }

            alongTrackM +=
                    distanceMeters(
                            a,
                            b
                    );
        }

        return best;
    }

    /**
     * Conservative lower bound based on a precomputed bounding circle.
     * The small safety padding deliberately makes the bound slightly looser;
     * false positives cost only a local track projection, while false negatives
     * could choose the wrong Camino and are therefore avoided.
     */
    private double trackLowerBoundDistanceMeters(
            RouteTrack track,
            LatLng query
    ) {
        double centerDistanceM =
                distanceMeters(
                        query,
                        track.boundsCenter
                );

        return Math.max(
                0.0,
                centerDistanceM
                        - track.boundsRadiusM
                        - 250.0
        );
    }

    private ProjectionHit projectToSegment(
            LatLng query,
            LatLng a,
            LatLng b,
            double chainageAtA,
            int trackIndex,
            int segmentIndex
    ) {
        double refLatRad =
                Math.toRadians(
                        (
                                query.getLatitude()
                                        + a.getLatitude()
                                        + b.getLatitude()
                        ) / 3.0
                );

        double cosLat =
                Math.max(
                        0.20,
                        Math.cos(
                                refLatRad
                        )
                );

        double ax =
                Math.toRadians(
                        a.getLongitude()
                                - query.getLongitude()
                )
                        * EARTH_RADIUS_M
                        * cosLat;

        double ay =
                Math.toRadians(
                        a.getLatitude()
                                - query.getLatitude()
                )
                        * EARTH_RADIUS_M;

        double bx =
                Math.toRadians(
                        b.getLongitude()
                                - query.getLongitude()
                )
                        * EARTH_RADIUS_M
                        * cosLat;

        double by =
                Math.toRadians(
                        b.getLatitude()
                                - query.getLatitude()
                )
                        * EARTH_RADIUS_M;

        double vx =
                bx - ax;

        double vy =
                by - ay;

        double lengthSq =
                vx * vx
                        + vy * vy;

        double t =
                0.0;

        if (lengthSq
                > 1e-9) {

            t =
                    -(ax * vx
                            + ay * vy)
                            / lengthSq;

            t =
                    Math.max(
                            0.0,
                            Math.min(
                                    1.0,
                                    t
                            )
                    );
        }

        double px =
                ax + t * vx;

        double py =
                ay + t * vy;

        LatLng projected =
                interpolate(
                        a,
                        b,
                        t
                );

        double segmentLength =
                distanceMeters(
                        a,
                        b
                );

        return new ProjectionHit(
                projected,
                chainageAtA
                        + t
                        * segmentLength,
                Math.hypot(
                        px,
                        py
                ),
                trackIndex,
                segmentIndex,
                t
        );
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

    private void updateDummySource() {
        if (dummySource == null) {
            return;
        }

        dummySource.setGeoJson(
                Point.fromLngLat(
                        dummyPosition
                                .getLongitude(),
                        dummyPosition
                                .getLatitude()
                )
        );
    }

    private void updateStartProjection(
            ProjectionHit startHit
    ) {
        if (startSnapSource == null) {
            return;
        }

        if (startHit == null) {
            startSnapSource.setGeoJson(
                    emptyFeatures()
            );
            return;
        }

        startSnapSource.setGeoJson(
                Point.fromLngLat(
                        startHit.point
                                .getLongitude(),
                        startHit.point
                                .getLatitude()
                )
        );
    }

    private void updateConnector(
            RouteHit startRouteHit
    ) {
        if (connectorSource == null) {
            return;
        }

        ProjectionHit startHit =
                startRouteHit == null
                        ? null
                        : startRouteHit.hit;

        if (startHit == null
                || startHit.distanceFromQueryM < 3.0) {
            connectorSource.setGeoJson(
                    emptyFeatures()
            );
            return;
        }

        List<Point> points =
                new ArrayList<>();

        points.add(
                Point.fromLngLat(
                        dummyPosition.getLongitude(),
                        dummyPosition.getLatitude()
                )
        );

        points.add(
                Point.fromLngLat(
                        startHit.point.getLongitude(),
                        startHit.point.getLatitude()
                )
        );

        Feature feature =
                Feature.fromGeometry(
                        LineString.fromLngLats(points)
                );

        feature.addStringProperty(
                "highlight_color",
                startRouteHit.route.highlightColor
        );

        connectorSource.setGeoJson(feature);
    }

    private void hideStartProjectionAndConnector() {
        if (startSnapSource != null) {
            startSnapSource.setGeoJson(
                    emptyFeatures()
            );
        }

        if (connectorSource != null) {
            connectorSource.setGeoJson(
                    emptyFeatures()
            );
        }
    }

    private void updateSelectedSource() {
        if (selectedSource == null) {
            return;
        }

        if (selectedHit == null) {
            selectedSource.setGeoJson(
                    emptyFeatures()
            );

            return;
        }

        List<Feature> points =
                new ArrayList<>();

        points.add(
                Feature.fromGeometry(
                        Point.fromLngLat(
                                selectedHit.point
                                        .getLongitude(),
                                selectedHit.point
                                        .getLatitude()
                        )
                )
        );

        if (secondTapHit != null) {
            points.add(
                    Feature.fromGeometry(
                            Point.fromLngLat(
                                    secondTapHit.point
                                            .getLongitude(),
                                    secondTapHit.point
                                            .getLatitude()
                            )
                    )
            );
        }

        selectedSource.setGeoJson(
                FeatureCollection.fromFeatures(
                        points
                )
        );
    }

    private void updateSelectedRoute(
            RouteHit startRouteHit
    ) {
        currentMeasurementPath =
                null;

        if (selectedRouteSource == null
                || routeGapSource == null) {
            return;
        }

        if (selectedRoute == null
                || selectedHit == null) {

            selectedRouteSource.setGeoJson(
                    emptyFeatures()
            );

            routeGapSource.setGeoJson(
                    emptyFeatures()
            );

            return;
        }

        RouteHit routeStart;
        RouteHit routeEnd;

        if (secondTapHit != null) {
            if (secondSelectedRoute == null) {
                selectedRouteSource.setGeoJson(
                        emptyFeatures()
                );

                routeGapSource.setGeoJson(
                        emptyFeatures()
                );

                return;
            }

            routeStart =
                    new RouteHit(
                            selectedRoute,
                            selectedHit
                    );

            routeEnd =
                    new RouteHit(
                            secondSelectedRoute,
                            secondTapHit
                    );

        } else {
            if (startRouteHit == null) {
                selectedRouteSource.setGeoJson(
                        emptyFeatures()
                );

                routeGapSource.setGeoJson(
                        emptyFeatures()
                );

                return;
            }

            routeStart =
                    startRouteHit;

            routeEnd =
                    new RouteHit(
                            selectedRoute,
                            selectedHit
                    );
        }

        currentMeasurementPath =
                measurementEngine.buildMeasurementPath(
                        routeStart,
                        routeEnd
                );

        if (currentMeasurementPath == null) {
            selectedRouteSource.setGeoJson(
                    emptyFeatures()
            );

            routeGapSource.setGeoJson(
                    emptyFeatures()
            );

            return;
        }

        selectedRouteSource.setGeoJson(
                FeatureCollection.fromFeatures(
                        currentMeasurementPath.routeFeatures
                )
        );

        routeGapSource.setGeoJson(
                FeatureCollection.fromFeatures(
                        currentMeasurementPath.gapFeatures
                )
        );
    }

    private void updateDistanceLabel(
            RouteHit startRouteHit
    ) {
        if (routes.isEmpty()) {
            setInfoTitle(
                    ""
            );

            setSummaryTexts(
                    "Lade Caminos …",
                    ""
            );

            return;
        }

        if (selectedRoute == null
                || selectedHit == null) {

            if (startRouteHit == null) {
                setInfoTitle(
                        ""
                );

                setSummaryTexts(
                        "Kein Camino gefunden",
                        ""
                );

                return;
            }

            setInfoTitle(
                    startRouteHit.route.name
            );

            double offRouteM =
                    startRouteHit.hit.distanceFromQueryM;

            setSummaryTexts(
                    offRouteM < 3.0
                            ? "Auf dem Camino"
                            : formatDistance(
                                    offRouteM
                            )
                            + " bis Camino",
                    ""
            );

            return;
        }

        RouteHit measurementStart;
        RouteHit measurementEnd;

        if (secondTapHit != null) {
            if (secondSelectedRoute == null) {
                setInfoTitle(
                        selectedRoute.name
                );

                setSummaryTexts(
                        "Zweiter Camino fehlt",
                        ""
                );

                return;
            }

            measurementStart =
                    new RouteHit(
                            selectedRoute,
                            selectedHit
                    );

            measurementEnd =
                    new RouteHit(
                            secondSelectedRoute,
                            secondTapHit
                    );

        } else {
            if (startRouteHit == null) {
                setInfoTitle(
                        selectedRoute.name
                );

                setSummaryTexts(
                        "Startpunkt konnte nicht projiziert werden",
                        ""
                );

                return;
            }

            measurementStart =
                    startRouteHit;

            measurementEnd =
                    new RouteHit(
                            selectedRoute,
                            selectedHit
                    );
        }

        setInfoTitle(
                measurementRouteLabel(
                        measurementStart,
                        measurementEnd
                )
        );

        if (currentMeasurementPath == null) {
            setSummaryTexts(
                    "Keine Camino-Verbindung",
                    ""
            );

            return;
        }

        String leftText =
                "";

        if (secondTapHit == null) {
            leftText =
                    startRouteHit.hit.distanceFromQueryM < 3.0
                            ? "Auf dem Camino"
                            : formatDistance(
                                    startRouteHit.hit.distanceFromQueryM
                            )
                            + " bis Camino";
        }

        String rightText =
                formatDistance(
                        currentMeasurementPath.distanceM
                )
                        + " Etappenlänge";

        setSummaryTexts(
                leftText,
                rightText
        );
    }
    private String measurementRouteLabel(
            RouteHit start,
            RouteHit end
    ) {
        if (start.route
                == end.route) {
            return end.route.name;
        }

        return start.route.name
                + " → "
                + end.route.name;
    }

    private String formatDistance(
            double distanceM
    ) {
        if (distanceM
                >= 1000.0) {

            return String.format(
                    Locale.GERMANY,
                    "%.2fkm",
                    distanceM
                            / 1000.0
            );
        }

        return String.format(
                Locale.GERMANY,
                "%.0fm",
                distanceM
        );
    }

    private void clearSelection() {
        selectedRoute =
                null;

        selectedHit =
                null;

        secondSelectedRoute =
                null;

        secondTapHit =
                null;

        refresh();
    }

    /*
     * CAMINO_EDGE_PROJECTED_HEIGHT_PROFILE_V2
     *
     * Full-height narrow overlay glued to the right map edge. It has no
     * independent chart Y-axis: route points keep their current screen-Y
     * position; only elevation becomes horizontal displacement.
     */
    private void ensureHeightProfileView() {
        if (heightProfileView != null) {
            return;
        }

        heightProfileView =
                new CaminoHeightProfileView(
                        activity
                );

        heightProfileView.setVisibility(
                android.view.View.GONE
        );

        ViewGroup parent =
                (ViewGroup)
                        mapView.getParent();

        /*
         * Full-screen transparent overlay: the profile still occupies only the
         * rightmost 126 dp when drawn, but the cursor guide can now extend all
         * the way back to the corresponding Camino point.
         *
         * onTouchEvent() returns false outside the profile strip, so normal map
         * gestures keep working everywhere else.
         */
        FrameLayout.LayoutParams params =
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        Gravity.TOP
                                | Gravity.START
                );

        parent.addView(
                heightProfileView,
                params
        );

        heightProfileView.setElevation(
                dp(2)
        );
    }

    private void scheduleHeightProfileRefresh() {
        updateInfoCompass();

        if (heightProfileRefreshScheduled) {
            return;
        }

        heightProfileRefreshScheduled =
                true;

        mapView.postDelayed(
                heightProfileRefreshRunnable,
                HEIGHT_PROFILE_REFRESH_DELAY_MS
        );
    }

    private void handleHeightProfileCameraIdle() {
        updateInfoCompass();

        mapView.removeCallbacks(
                heightProfileRefreshRunnable
        );

        heightProfileRefreshScheduled =
                false;

        refreshHeightProfile();

        if (!livePositionMode
                || !navigationFollowEnabled
                || !navigationFollowSuspended
                || liveNavigationController == null) {

            return;
        }

        /*
         * Gesture is finished. Only NOW start the 20-second no-input timer.
         */
        final int generation =
                ++navigationResumeGeneration;

        mapView.postDelayed(
                () -> {
                    if (!livePositionMode
                            || !navigationFollowEnabled
                            || !navigationFollowSuspended
                            || generation
                            != navigationResumeGeneration
                            || liveNavigationController == null) {

                        return;
                    }

                    navigationFollowSuspended =
                            false;

                    liveNavigationController
                            .setExternalNavigationSuspended(
                                    false
                            );
                },
                NAVIGATION_RECENTER_DELAY_MS
        );
    }

    private void refreshHeightProfile() {
        if (heightProfileView == null
                || map == null
                || currentMeasurementPath == null
                || currentMeasurementPath.profilePoints.size()
                < 2
                || mapView.getWidth() <= 0
                || mapView.getHeight() <= 0) {

            if (heightProfileView != null) {
                heightProfileView.clearProfile();
            }

            setHeightStats(
                    ""
            );

            return;
        }

        float width =
                mapView.getWidth();

        float height =
                mapView.getHeight();

        List<CaminoHeightProfileView.Sample> visible =
                new ArrayList<>();

        int previousVisibleIndex =
                -2;

        double previousElevation =
                Double.NaN;

        double minElevation =
                Double.POSITIVE_INFINITY;

        double maxElevation =
                Double.NEGATIVE_INFINITY;

        double netElevationChange =
                0.0;

        double accumulatedAscent =
                0.0;

        double accumulatedDescent =
                0.0;

        for (int index = 0;
                index
                        < currentMeasurementPath.profilePoints.size();
                index++) {

            ProfilePoint point =
                    currentMeasurementPath.profilePoints.get(
                            index
                    );

            if (!Double.isFinite(
                    point.elevationM
            )) {
                continue;
            }

            PointF screen =
                    map.getProjection()
                            .toScreenLocation(
                                    point.point
                            );

            if (!Float.isFinite(
                    screen.x
            ) || !Float.isFinite(
                    screen.y
            ) || screen.x < 0.0f
                    || screen.x > width
                    || screen.y < 0.0f
                    || screen.y > height) {

                continue;
            }

            boolean breakBefore =
                    point.breakBefore
                            || previousVisibleIndex
                            != index - 1;

            visible.add(
                    new CaminoHeightProfileView.Sample(
                            screen.x / width,
                            screen.y / height,
                            point.elevationM,
                            breakBefore
                    )
            );

            minElevation =
                    Math.min(
                            minElevation,
                            point.elevationM
                    );

            maxElevation =
                    Math.max(
                            maxElevation,
                            point.elevationM
                    );

            if (!breakBefore
                    && Double.isFinite(
                    previousElevation
            )) {

                double delta =
                        point.elevationM
                                - previousElevation;

                netElevationChange +=
                        delta;

                if (delta
                        > 0.0) {

                    accumulatedAscent +=
                            delta;

                } else if (delta
                        < 0.0) {

                    accumulatedDescent +=
                            -delta;
                }
            }

            previousElevation =
                    point.elevationM;

            previousVisibleIndex =
                    index;
        }

        if (visible.size()
                < 2
                || !Double.isFinite(
                minElevation
        )
                || !Double.isFinite(
                maxElevation
        )) {

            heightProfileView.clearProfile();

            setHeightStats(
                    ""
            );

            return;
        }

        heightProfileView.setSamples(
                visible
        );

        /*
         * CAMINO_PROFILE_LABELS_STATS_V4
         *
         * One metric per line keeps the bottom information panel readable.
         * Sigma-down is a positive magnitude: total descended vertical metres
         * over the currently visible route fragments.
         */
        setHeightStats(
                String.format(
                        Locale.GERMANY,
                        "Altₘᵢₙ   %.0f m\n"
                                + "Altₘₐₓ   %.0f m\n"
                                + "AltΔ     %+.0f m\n"
                                + "AltΣ↑    %.0f m\n"
                                + "AltΣ↓    %.0f m",
                        minElevation,
                        maxElevation,
                        netElevationChange,
                        accumulatedAscent,
                        accumulatedDescent
                )
        );
    }

    private void ensureDistanceView() {
        if (distanceView != null) {
            return;
        }

        infoPanel =
                new CaminoInfoPanel(
                        activity
                );

        distanceView =
                infoPanel.getTextView();

        infoPanel.setNavigationAction(
                this::toggleNavigationFollow
        );

        infoPanel.setNavigationFollowEnabled(
                navigationFollowEnabled
        );

        if (map != null) {
            infoPanel.setCompassDrawable(
                    map.getUiSettings()
                            .getCompassImage()
            );

            map.getUiSettings()
                    .setCompassEnabled(
                            false
                    );

            updateInfoCompass();
        }

        ViewGroup parent =
                (ViewGroup)
                        mapView.getParent();

        FrameLayout.LayoutParams params =
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.BOTTOM
                                | Gravity.CENTER_HORIZONTAL
                );

        params.bottomMargin =
                dp(18);

        parent.addView(
                infoPanel,
                params
        );

        /*
         * The HUD is intentionally the highest Android View in this overlay
         * stack. The full-screen profile may draw under it, never touch over it.
         */
        infoPanel.setElevation(
                dp(1000)
        );

        infoPanel.bringToFront();

        updateDistancePanelText();
    }

    private void toggleNavigationFollow() {
        navigationResumeGeneration++;

        navigationFollowEnabled =
                !navigationFollowEnabled;

        navigationFollowSuspended =
                false;

        if (infoPanel != null) {
            infoPanel.setNavigationFollowEnabled(
                    navigationFollowEnabled
            );
        }

        if (livePositionMode
                && liveNavigationController != null) {

            liveNavigationController
                    .setExternalNavigationFollowEnabled(
                            navigationFollowEnabled
                    );

            return;
        }

        if (navigationFollowEnabled) {
            applyNavigationFollow(
                    true
            );
        }
    }

    private void handleNavigationCameraMoveStarted(
            int reason
    ) {
        if (!navigationFollowEnabled
                || reason
                != MapLibreMap.OnCameraMoveStartedListener
                .REASON_API_GESTURE) {

            return;
        }

        navigationFollowSuspended =
                true;

        /*
         * Kill any older pending resume as soon as a new user gesture begins.
         */
        navigationResumeGeneration++;

        if (livePositionMode
                && liveNavigationController != null) {

            /*
             * From this instant the GPS controller is forbidden to touch the
             * camera. Pan/zoom/rotate is pure MapLibre manual interaction.
             */
            liveNavigationController
                    .setExternalNavigationSuspended(
                            true
                    );

            /*
             * No timer here. The 20 seconds begin only after CameraIdle,
             * i.e. after the user has actually stopped interacting.
             */
            return;
        }

        /*
         * Preserve historical planning/debug behavior outside live GPS mode.
         */
        final int generation =
                navigationResumeGeneration;

        mapView.postDelayed(
                () -> {
                    if (!navigationFollowEnabled
                            || generation
                            != navigationResumeGeneration) {

                        return;
                    }

                    navigationFollowSuspended =
                            false;

                    applyNavigationFollow(
                            true
                    );
                },
                NAVIGATION_RECENTER_DELAY_MS
        );
    }

    private void applyNavigationFollow(
            boolean animated
    ) {
        if (map == null
                || dummyPosition == null
                || mapView.getHeight() <= 0) {

            return;
        }

        double bearing =
                navigationBearingAtPosition();

        /*
         * 1.0 km ahead + 0.5 km behind = 1.5 km vertical ground window.
         * A camera centre 250 m ahead of the user places the user at roughly
         * two thirds of the screen height:
         *
         *   top ----- 1000 m ----- user ----- 500 m ----- bottom
         */
        LatLng cameraTarget =
                navigationDestination(
                        dummyPosition,
                        bearing,
                        NAVIGATION_CAMERA_LEAD_M
                );

        double zoom =
                navigationZoomForVerticalMeters(
                        dummyPosition.getLatitude(),
                        NAVIGATION_VERTICAL_WINDOW_M
                );

        CameraPosition position =
                new CameraPosition.Builder(
                        map.getCameraPosition()
                )
                        .target(
                                cameraTarget
                        )
                        .zoom(
                                zoom
                        )
                        .bearing(
                                bearing
                        )
                        .tilt(
                                0.0
                        )
                        .build();

        if (animated) {
            map.easeCamera(
                    CameraUpdateFactory.newCameraPosition(
                            position
                    ),
                    550
            );

        } else {
            map.setCameraPosition(
                    position
            );
        }
    }

    private double navigationZoomForVerticalMeters(
            double latitude,
            double verticalMeters
    ) {
        double currentZoom =
                map.getCameraPosition()
                        .zoom;

        double currentMetersPerPixel =
                map.getProjection()
                        .getMetersPerPixelAtLatitude(
                                latitude
                        );

        double desiredMetersPerPixel =
                verticalMeters
                        / Math.max(
                        1.0,
                        mapView.getHeight()
                );

        if (!Double.isFinite(
                currentMetersPerPixel
        )
                || currentMetersPerPixel <= 0.0
                || !Double.isFinite(
                desiredMetersPerPixel
        )
                || desiredMetersPerPixel <= 0.0) {

            return currentZoom;
        }

        double zoomDelta =
                Math.log(
                        currentMetersPerPixel
                                / desiredMetersPerPixel
                ) / Math.log(
                        2.0
                );

        return currentZoom
                + zoomDelta;
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
                        : findNearestRouteHit(
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

        return navigationBearingDegrees(
                track.points.get(
                        segment
                ),
                track.points.get(
                        segment + 1
                )
        );
    }

    private static double navigationBearingDegrees(
            LatLng from,
            LatLng to
    ) {
        double lat1 =
                Math.toRadians(
                        from.getLatitude()
                );

        double lat2 =
                Math.toRadians(
                        to.getLatitude()
                );

        double deltaLon =
                Math.toRadians(
                        to.getLongitude()
                                - from.getLongitude()
                );

        double y =
                Math.sin(
                        deltaLon
                ) * Math.cos(
                        lat2
                );

        double x =
                Math.cos(
                        lat1
                ) * Math.sin(
                        lat2
                )
                        - Math.sin(
                        lat1
                ) * Math.cos(
                        lat2
                ) * Math.cos(
                        deltaLon
                );

        double bearing =
                Math.toDegrees(
                        Math.atan2(
                                y,
                                x
                        )
                );

        bearing %=
                360.0;

        return bearing < 0.0
                ? bearing + 360.0
                : bearing;
    }

    private static LatLng navigationDestination(
            LatLng from,
            double bearingDegrees,
            double meters
    ) {
        double angularDistance =
                meters
                        / EARTH_RADIUS_M;

        double bearing =
                Math.toRadians(
                        bearingDegrees
                );

        double lat1 =
                Math.toRadians(
                        from.getLatitude()
                );

        double lon1 =
                Math.toRadians(
                        from.getLongitude()
                );

        double lat2 =
                Math.asin(
                        Math.sin(
                                lat1
                        ) * Math.cos(
                                angularDistance
                        )
                                + Math.cos(
                                lat1
                        ) * Math.sin(
                                angularDistance
                        ) * Math.cos(
                                bearing
                        )
                );

        double lon2 =
                lon1
                        + Math.atan2(
                        Math.sin(
                                bearing
                        ) * Math.sin(
                                angularDistance
                        ) * Math.cos(
                                lat1
                        ),
                        Math.cos(
                                angularDistance
                        )
                                - Math.sin(
                                lat1
                        ) * Math.sin(
                                lat2
                        )
                );

        return new LatLng(
                Math.toDegrees(
                        lat2
                ),
                Math.toDegrees(
                        lon2
                )
        );
    }

    private void noteTravelSample(
            LatLng position
    ) {
        if (position == null) {
            return;
        }

        long now =
                SystemClock.elapsedRealtime();

        if (travelSessionStartElapsedMs < 0L) {
            travelSessionStartElapsedMs =
                    now;

            lastTravelSampleElapsedMs =
                    now;

            lastTravelSamplePosition =
                    new LatLng(
                            position.getLatitude(),
                            position.getLongitude()
                    );

            setSpeedStats(
                    buildSpeedStatsText()
            );

            return;
        }

        if (lastTravelSamplePosition == null) {
            lastTravelSamplePosition =
                    new LatLng(
                            position.getLatitude(),
                            position.getLongitude()
                    );

            lastTravelSampleElapsedMs =
                    now;

            setSpeedStats(
                    buildSpeedStatsText()
            );

            return;
        }

        long deltaMs =
                now
                        - lastTravelSampleElapsedMs;

        if (deltaMs <= 0L
                || deltaMs
                > 15L * 60L * 1000L) {

            lastTravelSamplePosition =
                    new LatLng(
                            position.getLatitude(),
                            position.getLongitude()
                    );

            lastTravelSampleElapsedMs =
                    now;

            setSpeedStats(
                    buildSpeedStatsText()
            );

            return;
        }

        double segmentM =
                travelDistanceBetween(
                        lastTravelSamplePosition,
                        position
                );

        if (segmentM >= SPEED_SAMPLE_MIN_MOVE_M
                && segmentM <= SPEED_SAMPLE_MAX_JUMP_M) {

            travelDistanceM +=
                    segmentM;

            travelMovingElapsedMs +=
                    deltaMs;
        }

        lastTravelSamplePosition =
                new LatLng(
                        position.getLatitude(),
                        position.getLongitude()
                );

        lastTravelSampleElapsedMs =
                now;

        setSpeedStats(
                buildSpeedStatsText()
        );
    }

    private String buildSpeedStatsText() {
        long totalElapsedMs =
                travelSessionStartElapsedMs < 0L
                        ? 0L
                        : SystemClock.elapsedRealtime()
                        - travelSessionStartElapsedMs;

        double movingKmh =
                travelMovingElapsedMs <= 0L
                        ? Double.NaN
                        : travelDistanceM
                        / (
                        travelMovingElapsedMs
                                / 1000.0
                )
                        * 3.6;

        double totalKmh =
                totalElapsedMs <= 0L
                        ? Double.NaN
                        : travelDistanceM
                        / (
                        totalElapsedMs
                                / 1000.0
                )
                        * 3.6;

        double realWorldSpeedMps =
                !Double.isNaN(
                        totalKmh
                )
                        && totalKmh >= 0.4
                        ? totalKmh / 3.6
                        : (
                        !Double.isNaN(
                                movingKmh
                        )
                                && movingKmh >= 0.4
                                ? movingKmh / 3.6
                                : Double.NaN
                );

        String stageEta =
                "—";

        if (currentMeasurementPath != null
                && !Double.isNaN(
                        realWorldSpeedMps
                )
                && realWorldSpeedMps > 0.0) {

            long etaWallClockMs =
                    System.currentTimeMillis()
                            + (long) (
                            currentMeasurementPath.distanceM
                                    / realWorldSpeedMps
                                    * 1000.0
                    );

            stageEta =
                    DateFormat.format(
                            "HH:mm",
                            etaWallClockMs
                    ).toString();
        }

        double[] village =
                nextVillageMetrics();

        String villageDistance =
                "—";

        String villageTime =
                "—";

        String villageAscent =
                "—";

        if (village != null) {
            villageDistance =
                    formatDistance(
                            village[0]
                    );

            villageAscent =
                    String.format(
                            Locale.GERMANY,
                            "%.0f Hm",
                            village[1]
                    );

            if (!Double.isNaN(
                    realWorldSpeedMps
            )
                    && realWorldSpeedMps > 0.0) {

                villageTime =
                        formatDuration(
                                village[0]
                                        / realWorldSpeedMps
                        );
            }
        }

        return String.format(
                Locale.GERMANY,
                "Ø Moving   %s\n"
                        + "Ø Gesamt   %s\n"
                        + "Ankunft    %s\n"
                        + "bis Dorf   %s\n"
                        + "Dorf Zeit  %s\n"
                        + "Dorf ↑     %s",
                formatSpeedKmh(
                        movingKmh
                ),
                formatSpeedKmh(
                        totalKmh
                ),
                stageEta,
                villageDistance,
                villageTime,
                villageAscent
        );
    }

    private String formatSpeedKmh(
            double kmh
    ) {
        if (Double.isNaN(
                kmh
        )
                || kmh <= 0.0) {

            return "—";
        }

        return String.format(
                Locale.GERMANY,
                "%.1f km/h",
                kmh
        );
    }

    private String formatDuration(
            double seconds
    ) {
        if (!Double.isFinite(
                seconds
        )
                || seconds < 0.0) {

            return "—";
        }

        long minutes =
                Math.max(
                        0L,
                        Math.round(
                                seconds / 60.0
                        )
                );

        if (minutes < 60L) {
            return minutes
                    + " min";
        }

        long hours =
                minutes / 60L;

        long restMinutes =
                minutes % 60L;

        return String.format(
                Locale.GERMANY,
                "%d h %02d min",
                hours,
                restMinutes
        );
    }

    private double[] nextVillageMetrics() {
        if (routes.isEmpty()
                || dummyPosition == null) {

            return null;
        }

        RouteHit start =
                findNearestRouteHit(
                        dummyPosition
                );

        if (start == null
                || start.hit.trackIndex < 0
                || start.hit.trackIndex
                >= start.route.tracks.size()) {

            return null;
        }

        CaminoRoute route =
                start.route;

        int trackIndex =
                start.hit.trackIndex;

        RouteTrack firstTrack =
                route.tracks.get(
                        trackIndex
                );

        double alongTrackM =
                start.hit.chainageM
                        - firstTrack.baseChainageM;

        alongTrackM =
                Math.max(
                        0.0,
                        Math.min(
                                firstTrack.lengthM,
                                alongTrackM
                        )
                );

        double distanceM =
                firstTrack.lengthM
                        - alongTrackM;

        double ascentM =
                positiveAscentFromHitToTrackEnd(
                        firstTrack,
                        start.hit
                );

        if (isVillageEndpoint(
                firstTrack
        )) {
            return new double[]{
                    distanceM,
                    ascentM
            };
        }

        for (int index =
                trackIndex + 1;
                index < route.tracks.size();
                index++) {

            RouteTrack previous =
                    route.tracks.get(
                            index - 1
                    );

            RouteTrack current =
                    route.tracks.get(
                            index
                    );

            distanceM +=
                    measurementEngine.gapBetweenTracks(
                            route,
                            index - 1,
                            index
                    );

            distanceM +=
                    current.lengthM;

            /*
             * Gaps have real horizontal distance but no invented terrain.
             * Ascent resumes only on the next official CNIG geometry.
             */
            ascentM +=
                    positiveAscentWholeTrack(
                            current
                    );

            if (isVillageEndpoint(
                    current
            )) {
                return new double[]{
                        distanceM,
                        ascentM
                };
            }
        }

        return null;
    }

    private boolean isVillageEndpoint(
            RouteTrack track
    ) {
        return track != null
                && !track.pseudoTo
                && track.toKey != null
                && !track.toKey.isEmpty();
    }

    private double positiveAscentFromHitToTrackEnd(
            RouteTrack track,
            ProjectionHit hit
    ) {
        if (track == null
                || hit == null
                || track.elevations.isEmpty()) {

            return 0.0;
        }

        double previous =
                measurementEngine.elevationAtHit(
                        track,
                        hit
                );

        double ascentM =
                0.0;

        int firstVertex =
                Math.max(
                        0,
                        Math.min(
                                track.elevations.size(),
                                hit.segmentIndex + 1
                        )
                );

        for (int index =
                firstVertex;
                index < track.elevations.size();
                index++) {

            double elevation =
                    track.elevations.get(
                            index
                    );

            if (Double.isFinite(
                    previous
            )
                    && Double.isFinite(
                    elevation
            )) {

                double delta =
                        elevation
                                - previous;

                if (delta > 0.0) {
                    ascentM +=
                            delta;
                }
            }

            if (Double.isFinite(
                    elevation
            )) {
                previous =
                        elevation;
            }
        }

        return ascentM;
    }

    private double positiveAscentWholeTrack(
            RouteTrack track
    ) {
        if (track == null
                || track.elevations.size() < 2) {

            return 0.0;
        }

        double ascentM =
                0.0;

        double previous =
                track.elevations.get(
                        0
                );

        for (int index = 1;
                index < track.elevations.size();
                index++) {

            double elevation =
                    track.elevations.get(
                            index
                    );

            if (Double.isFinite(
                    previous
            )
                    && Double.isFinite(
                    elevation
            )) {

                double delta =
                        elevation
                                - previous;

                if (delta > 0.0) {
                    ascentM +=
                            delta;
                }
            }

            if (Double.isFinite(
                    elevation
            )) {
                previous =
                        elevation;
            }
        }

        return ascentM;
    }

    private static double travelDistanceBetween(
            LatLng from,
            LatLng to
    ) {
        double lat1 =
                Math.toRadians(
                        from.getLatitude()
                );

        double lon1 =
                Math.toRadians(
                        from.getLongitude()
                );

        double lat2 =
                Math.toRadians(
                        to.getLatitude()
                );

        double lon2 =
                Math.toRadians(
                        to.getLongitude()
                );

        double dLat =
                lat2
                        - lat1;

        double dLon =
                lon2
                        - lon1;

        double a =
                Math.sin(
                        dLat / 2.0
                )
                        * Math.sin(
                        dLat / 2.0
                )
                        + Math.cos(
                        lat1
                )
                        * Math.cos(
                        lat2
                )
                        * Math.sin(
                        dLon / 2.0
                )
                        * Math.sin(
                        dLon / 2.0
                );

        double c =
                2.0
                        * Math.atan2(
                        Math.sqrt(
                                a
                        ),
                        Math.sqrt(
                                1.0 - a
                        )
                );

        return EARTH_RADIUS_M
                * c;
    }

    private void updateInfoCompass() {
        if (infoPanel == null
                || map == null) {
            return;
        }

        infoPanel.setBearing(
                map.getCameraPosition()
                        .bearing
        );
    }

    private void setInfoTitle(
            String text
    ) {
        infoTitleText =
                text == null
                        ? ""
                        : text;

        updateDistancePanelText();
    }

    private void setLabel(
            String text
    ) {
        setSummaryTexts(
                text,
                ""
        );
    }

    private void setSummaryTexts(
            String left,
            String right
    ) {
        summaryLeftText =
                left == null
                        ? ""
                        : left;

        summaryRightText =
                right == null
                        ? ""
                        : right;

        updateDistancePanelText();
    }

    private void setHeightStats(
            String text
    ) {
        heightStatsText =
                text == null
                        ? ""
                        : text;

        updateDistancePanelText();
    }

    private void setSpeedStats(
            String text
    ) {
        speedStatsText =
                text == null
                        ? ""
                        : text;

        updateDistancePanelText();
    }

    private void updateDistancePanelText() {
        if (infoPanel == null) {
            return;
        }

        infoPanel.setTitle(
                infoTitleText
        );

        infoPanel.setSummaryTexts(
                summaryLeftText,
                summaryRightText
        );

        infoPanel.setStatsTexts(
                heightStatsText,
                speedStatsText
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

    private static FeatureCollection emptyFeatures() {
        return FeatureCollection.fromFeatures(
                new ArrayList<>()
        );
    }

    private static LatLng interpolate(
            LatLng a,
            LatLng b,
            double t
    ) {
        return new LatLng(
                a.getLatitude()
                        + t
                        * (
                        b.getLatitude()
                                - a.getLatitude()
                ),
                a.getLongitude()
                        + t
                        * (
                        b.getLongitude()
                                - a.getLongitude()
                )
        );
    }


    private static double distanceMeters(
            LatLng a,
            LatLng b
    ) {
        double lat1 =
                Math.toRadians(
                        a.getLatitude()
                );

        double lat2 =
                Math.toRadians(
                        b.getLatitude()
                );

        double dLat =
                lat2 - lat1;

        double dLon =
                Math.toRadians(
                        b.getLongitude()
                                - a.getLongitude()
                );

        double h =
                Math.sin(
                        dLat / 2.0
                )
                        * Math.sin(
                        dLat / 2.0
                )
                        + Math.cos(
                        lat1
                )
                        * Math.cos(
                        lat2
                )
                        * Math.sin(
                        dLon / 2.0
                )
                        * Math.sin(
                        dLon / 2.0
                );

        return 2.0
                * EARTH_RADIUS_M
                * Math.asin(
                Math.min(
                        1.0,
                        Math.sqrt(
                                h
                        )
                )
        );
    }

}
