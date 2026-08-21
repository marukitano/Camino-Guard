package com.marukitano.caminoguard;

import android.app.Activity;
import android.graphics.Color;
import android.os.SystemClock;
import android.text.format.DateFormat;
import android.graphics.PointF;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
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

    private static final double EARTH_RADIUS_M =
            6371008.8;

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
    private final TravelStatsController travelStatsController;
    private final CaminoDragController dragController;
    private final CaminoSelectionController selectionController;

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

    private final CaminoTrackingService.Listener livePositionListener =
            this::handleLiveTrackingState;

    private MeasurementPath currentMeasurementPath;

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

        this.navigationController =
                new NavigationController(
                        mapView,
                        () -> dummyPosition,
                        this::navigationBearingAtPosition,
                        enabled -> {
                            if (infoPanel != null) {
                                infoPanel.setNavigationFollowEnabled(
                                        enabled
                                );
                            }
                        }
                );

        this.projectionEngine =
                new CaminoProjectionEngine(
                        caminoNetwork
                );

        this.infoPresenter =
                new CaminoInfoPresenter();

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
        navigationController.setExternalController(
                controller
        );
    }

    public void attachMap(
            MapLibreMap map
    ) {
        this.map = map;
        navigationController.attachMap(
                map
        );
        dragController.attachMap(
                map
        );

        map.addOnMapClickListener(
                point -> selectionController.handleMapTap(
                        point,
                        dragController.isDragging()
                )
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
        updateDummySource();
        updateSelectedSource();

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
        if (selectionController.selectedHit() == null) {
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
                        : projectionEngine.findNearestRouteHit(
                                dummyPosition
                        );

        ProjectionHit startHit =
                startRouteHit == null
                        ? null
                        : startRouteHit.hit;

        if (selectionController.secondTapHit()
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

        if (selectionController.selectedHit() == null) {
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
                                selectionController.selectedHit().point
                                        .getLongitude(),
                                selectionController.selectedHit().point
                                        .getLatitude()
                        )
                )
        );

        if (selectionController.secondTapHit() != null) {
            points.add(
                    Feature.fromGeometry(
                            Point.fromLngLat(
                                    selectionController.secondTapHit().point
                                            .getLongitude(),
                                    selectionController.secondTapHit().point
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

        if (selectionController.selectedRoute() == null
                || selectionController.selectedHit() == null) {

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

        if (selectionController.secondTapHit() != null) {
            if (selectionController.secondSelectedRoute() == null) {
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
                            selectionController.selectedRoute(),
                            selectionController.selectedHit()
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
            infoPresenter.setInfoTitle(
                    ""
            );

            infoPresenter.setSummaryTexts(
                    "Lade Caminos …",
                    ""
            );

            return;
        }

        if (selectionController.selectedRoute() == null
                || selectionController.selectedHit() == null) {

            if (startRouteHit == null) {
                infoPresenter.setInfoTitle(
                        ""
                );

                infoPresenter.setSummaryTexts(
                        "Kein Camino gefunden",
                        ""
                );

                return;
            }

            infoPresenter.setInfoTitle(
                    startRouteHit.route.name
            );

            double offRouteM =
                    startRouteHit.hit.distanceFromQueryM;

            infoPresenter.setSummaryTexts(
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

        if (selectionController.secondTapHit() != null) {
            if (selectionController.secondSelectedRoute() == null) {
                infoPresenter.setInfoTitle(
                        selectionController.selectedRoute().name
                );

                infoPresenter.setSummaryTexts(
                        "Zweiter Camino fehlt",
                        ""
                );

                return;
            }

            measurementStart =
                    new RouteHit(
                            selectionController.selectedRoute(),
                            selectionController.selectedHit()
                    );

            measurementEnd =
                    new RouteHit(
                            selectionController.secondSelectedRoute(),
                            selectionController.secondTapHit()
                    );

        } else {
            if (startRouteHit == null) {
                infoPresenter.setInfoTitle(
                        selectionController.selectedRoute().name
                );

                infoPresenter.setSummaryTexts(
                        "Startpunkt konnte nicht projiziert werden",
                        ""
                );

                return;
            }

            measurementStart =
                    startRouteHit;

            measurementEnd =
                    new RouteHit(
                            selectionController.selectedRoute(),
                            selectionController.selectedHit()
                    );
        }

        infoPresenter.setInfoTitle(
                measurementRouteLabel(
                        measurementStart,
                        measurementEnd
                )
        );

        if (currentMeasurementPath == null) {
            infoPresenter.setSummaryTexts(
                    "Keine Camino-Verbindung",
                    ""
            );

            return;
        }

        String leftText =
                "";

        if (selectionController.secondTapHit() == null) {
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

        infoPresenter.setSummaryTexts(
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

        navigationController.handleCameraIdle();
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

            infoPresenter.setHeightStats(
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

            infoPresenter.setHeightStats(
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
        infoPresenter.setHeightStats(
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

        infoPresenter.attach(
                infoPanel
        );

        infoPanel.setNavigationAction(
                navigationController::toggleFollow
        );

        infoPanel.setNavigationFollowEnabled(
                navigationController.isFollowEnabled()
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

        infoPresenter.refresh();
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
