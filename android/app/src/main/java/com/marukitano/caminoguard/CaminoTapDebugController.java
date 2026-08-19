package com.marukitano.caminoguard;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.PointF;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
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
 * Temporary all-Camino interaction harness.
 *
 * Normal Camino geometry remains in the map style and therefore stays green.
 * This controller only adds:
 *
 *  - draggable fake current position
 *  - projected start point + straight off-route connector
 *  - one or two draggable Camino selection points
 *  - red overlay only on the measured Camino section
 *  - route-aware distance/name display
 */
public final class CaminoTapDebugController {

    private static final String ROUTE_ASSET =
            "camino/debug-all-primary-caminos.json";

    private static final String SELECTED_ROUTE_SOURCE =
            "camino-debug-selected-route-source";
    private static final String SELECTED_ROUTE_LAYER =
            "camino-debug-selected-route";

    private static final String CONNECTOR_SOURCE =
            "camino-debug-start-connector-source";
    private static final String CONNECTOR_LAYER =
            "camino-debug-start-connector";

    private static final String DUMMY_SOURCE =
            "camino-debug-dummy-position-source";
    private static final String DUMMY_LAYER =
            "camino-debug-dummy-position";

    private static final String START_SNAP_SOURCE =
            "camino-debug-start-snap-source";
    private static final String START_SNAP_LAYER =
            "camino-debug-start-snap";

    private static final String SELECTED_SOURCE =
            "camino-debug-selected-position-source";
    private static final String SELECTED_LAYER =
            "camino-debug-selected-position";

    private static final double EARTH_RADIUS_M = 6371008.8;

    private static final int DRAG_NONE = 0;
    private static final int DRAG_DUMMY = 1;
    private static final int DRAG_POINT_1 = 2;
    private static final int DRAG_POINT_2 = 3;

    private final Activity activity;
    private final MapView mapView;
    private final List<CaminoRoute> routes =
            new ArrayList<>();

    private MapLibreMap map;

    private GeoJsonSource selectedRouteSource;
    private GeoJsonSource connectorSource;
    private GeoJsonSource dummySource;
    private GeoJsonSource startSnapSource;
    private GeoJsonSource selectedSource;

    private TextView distanceView;

    private LatLng dummyPosition;

    private CaminoRoute selectedRoute;
    private ProjectionHit selectedHit;
    private ProjectionHit secondTapHit;

    private int dragTarget = DRAG_NONE;

    public CaminoTapDebugController(
            Activity activity,
            MapView mapView,
            LatLng initialPosition
    ) {
        this.activity = activity;
        this.mapView = mapView;
        this.dummyPosition = initialPosition;
    }

    public void attachMap(
            MapLibreMap map
    ) {
        this.map = map;

        map.addOnMapClickListener(
                this::handleMapTap
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
            secondTapHit = null;

            refresh();
            return true;
        }

        /*
         * Tap 2:
         * stay on the already selected Camino.
         */
        if (secondTapHit == null) {
            ProjectionHit hit =
                    projectToRoute(
                            selectedRoute,
                            point
                    );

            if (hit == null
                    || !isTapCloseEnough(
                            point,
                            hit.point
                    )) {
                clearSelection();
                return false;
            }

            secondTapHit = hit;

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
        secondTapHit = null;

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

                moveDragTarget(
                        event.getX(),
                        event.getY()
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
                        event.getY()
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
            float y
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

            refresh();
            return;
        }

        if (selectedRoute == null) {
            return;
        }

        ProjectionHit snapped =
                projectToRoute(
                        selectedRoute,
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

        refresh();
    }

    public void onStyleLoaded(
            Style style
    ) {
        ensureDistanceView();

        selectedRouteSource =
                new GeoJsonSource(
                        SELECTED_ROUTE_SOURCE,
                        emptyFeatures()
                );

        style.addSource(
                selectedRouteSource
        );

        LineLayer selectedRouteLayer =
                new LineLayer(
                        SELECTED_ROUTE_LAYER,
                        SELECTED_ROUTE_SOURCE
                );

        selectedRouteLayer.setProperties(
                PropertyFactory.lineColor(
                        Color.parseColor(
                                "#D04432"
                        )
                ),
                PropertyFactory.lineWidth(
                        6.0f
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
                        Color.parseColor(
                                "#D04432"
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
        JSONObject root =
                new JSONObject(
                        readAssetText(
                                ROUTE_ASSET
                        )
                );

        JSONArray routesJson =
                root.getJSONArray(
                        "routes"
                );

        routes.clear();

        for (int routeIndex = 0;
                routeIndex < routesJson.length();
                routeIndex++) {

            JSONObject routeJson =
                    routesJson.getJSONObject(
                            routeIndex
                    );

            CaminoRoute route =
                    new CaminoRoute(
                            routeJson.getString(
                                    "route_group_id"
                            ),
                            routeJson.getString(
                                    "name"
                            )
                    );

            JSONArray tracksJson =
                    routeJson.getJSONArray(
                            "tracks"
                    );

            for (int trackIndex = 0;
                    trackIndex < tracksJson.length();
                    trackIndex++) {

                JSONObject trackJson =
                        tracksJson.getJSONObject(
                                trackIndex
                        );

                String sectionId =
                        trackJson.getString(
                                "section_id"
                        );

                JSONArray coordinates =
                        trackJson.getJSONArray(
                                "coordinates"
                        );

                List<LatLng> points =
                        new ArrayList<>();

                for (int pointIndex = 0;
                        pointIndex < coordinates.length();
                        pointIndex++) {

                    JSONArray coordinate =
                            coordinates.getJSONArray(
                                    pointIndex
                            );

                    points.add(
                            new LatLng(
                                    coordinate.getDouble(
                                            0
                                    ),
                                    coordinate.getDouble(
                                            1
                                    )
                            )
                    );
                }

                if (points.size()
                        >= 2) {
                    route.tracks.add(
                            new RouteTrack(
                                    sectionId,
                                    sectionNumber(
                                            sectionId
                                    ),
                                    points
                            )
                    );
                }
            }

            route.tracks.sort(
                    Comparator.comparingInt(
                            track ->
                                    track.order
                    )
            );

            prepareRouteGeometry(
                    route
            );

            if (!route.tracks.isEmpty()) {
                routes.add(
                        route
                );
            }
        }

        if (routes.isEmpty()) {
            throw new IllegalStateException(
                    "keine Camino-Routen im Debug-Asset"
            );
        }
    }

    private void prepareRouteGeometry(
            CaminoRoute route
    ) {
        LatLng previousEnd =
                null;

        double chainage =
                0.0;

        for (RouteTrack track
                : route.tracks) {

            if (previousEnd
                    != null) {
                LatLng first =
                        track.points.get(
                                0
                        );

                LatLng last =
                        track.points.get(
                                track.points.size()
                                        - 1
                        );

                if (distanceMeters(
                        previousEnd,
                        last
                ) < distanceMeters(
                        previousEnd,
                        first
                )) {
                    Collections.reverse(
                            track.points
                    );
                }
            }

            track.baseChainageM =
                    chainage;

            track.lengthM =
                    polylineLength(
                            track.points
                    );

            chainage +=
                    track.lengthM;

            previousEnd =
                    track.points.get(
                            track.points.size()
                                    - 1
                    );
        }
    }

    private String readAssetText(
            String assetName
    ) throws Exception {
        try (InputStream input =
                     activity.getAssets()
                             .open(
                                     assetName
                             );
             ByteArrayOutputStream output =
                     new ByteArrayOutputStream()) {

            byte[] buffer =
                    new byte[
                            16 * 1024
                            ];

            int count;

            while ((count =
                    input.read(
                            buffer
                    )) != -1) {

                output.write(
                        buffer,
                        0,
                        count
                );
            }

            return output.toString(
                    "UTF-8"
            );
        }
    }

    private void refresh() {
        updateDummySource();
        updateSelectedSource();

        ProjectionHit startHit =
                selectedRoute
                        == null
                        ? null
                        : projectToRoute(
                                selectedRoute,
                                dummyPosition
                        );

        if (selectedRoute
                != null
                && secondTapHit
                == null) {

            updateStartProjection(
                    startHit
            );

            updateConnector(
                    startHit
            );

        } else {
            hideStartProjectionAndConnector();
        }

        updateSelectedRoute(
                startHit
        );

        updateDistanceLabel(
                startHit
        );
    }

    private RouteHit findNearestRouteHit(
            LatLng query
    ) {
        RouteHit best =
                null;

        for (CaminoRoute route
                : routes) {

            ProjectionHit hit =
                    projectToRoute(
                            route,
                            query
                    );

            if (hit == null) {
                continue;
            }

            if (best == null
                    || hit.distanceFromQueryM
                    < best.hit.distanceFromQueryM) {

                best =
                        new RouteHit(
                                route,
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

            double alongTrackM =
                    0.0;

            for (int segmentIndex = 0;
                    segmentIndex
                            < track.points.size()
                            - 1;
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
        }

        return best;
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
        if (startSnapSource == null
                || startHit == null) {
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
            ProjectionHit startHit
    ) {
        if (connectorSource == null) {
            return;
        }

        if (startHit == null
                || startHit.distanceFromQueryM
                < 3.0) {

            connectorSource.setGeoJson(
                    emptyFeatures()
            );

            return;
        }

        List<Point> points =
                new ArrayList<>();

        points.add(
                Point.fromLngLat(
                        dummyPosition
                                .getLongitude(),
                        dummyPosition
                                .getLatitude()
                )
        );

        points.add(
                Point.fromLngLat(
                        startHit.point
                                .getLongitude(),
                        startHit.point
                                .getLatitude()
                )
        );

        connectorSource.setGeoJson(
                Feature.fromGeometry(
                        LineString.fromLngLats(
                                points
                        )
                )
        );
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
            ProjectionHit startHit
    ) {
        if (selectedRouteSource == null) {
            return;
        }

        if (selectedRoute == null
                || selectedHit == null) {

            selectedRouteSource.setGeoJson(
                    emptyFeatures()
            );

            return;
        }

        ProjectionHit routeStart;
        ProjectionHit routeEnd;

        if (secondTapHit != null) {
            routeStart =
                    selectedHit;

            routeEnd =
                    secondTapHit;

        } else {
            if (startHit == null) {
                selectedRouteSource.setGeoJson(
                        emptyFeatures()
                );

                return;
            }

            routeStart =
                    startHit;

            routeEnd =
                    selectedHit;
        }

        List<Feature> pieces =
                buildRoutePieces(
                        selectedRoute,
                        routeStart,
                        routeEnd
                );

        selectedRouteSource.setGeoJson(
                FeatureCollection.fromFeatures(
                        pieces
                )
        );
    }

    private List<Feature> buildRoutePieces(
            CaminoRoute route,
            ProjectionHit start,
            ProjectionHit end
    ) {
        List<Feature> features =
                new ArrayList<>();

        if (start.trackIndex
                == end.trackIndex) {

            addTrackSlice(
                    features,
                    route.tracks.get(
                            start.trackIndex
                    ),
                    start,
                    end
            );

            return features;
        }

        boolean forward =
                start.chainageM
                        <= end.chainageM;

        if (forward) {
            for (int trackIndex =
                    start.trackIndex;
                    trackIndex
                            <= end.trackIndex;
                    trackIndex++) {

                RouteTrack track =
                        route.tracks.get(
                                trackIndex
                        );

                ProjectionHit from =
                        trackIndex
                                == start.trackIndex
                                ? start
                                : trackStartHit(
                                        route,
                                        trackIndex
                                );

                ProjectionHit to =
                        trackIndex
                                == end.trackIndex
                                ? end
                                : trackEndHit(
                                        route,
                                        trackIndex
                                );

                addTrackSlice(
                        features,
                        track,
                        from,
                        to
                );
            }

        } else {
            for (int trackIndex =
                    start.trackIndex;
                    trackIndex
                            >= end.trackIndex;
                    trackIndex--) {

                RouteTrack track =
                        route.tracks.get(
                                trackIndex
                        );

                ProjectionHit from =
                        trackIndex
                                == start.trackIndex
                                ? start
                                : trackEndHit(
                                        route,
                                        trackIndex
                                );

                ProjectionHit to =
                        trackIndex
                                == end.trackIndex
                                ? end
                                : trackStartHit(
                                        route,
                                        trackIndex
                                );

                addTrackSlice(
                        features,
                        track,
                        from,
                        to
                );
            }
        }

        return features;
    }

    private void addTrackSlice(
            List<Feature> output,
            RouteTrack track,
            ProjectionHit from,
            ProjectionHit to
    ) {
        List<LatLng> slice =
                sliceTrack(
                        track,
                        from,
                        to
                );

        if (slice.size()
                < 2) {
            return;
        }

        List<Point> points =
                new ArrayList<>();

        for (LatLng point
                : slice) {

            points.add(
                    Point.fromLngLat(
                            point.getLongitude(),
                            point.getLatitude()
                    )
            );
        }

        output.add(
                Feature.fromGeometry(
                        LineString.fromLngLats(
                                points
                        )
                )
        );
    }

    private List<LatLng> sliceTrack(
            RouteTrack track,
            ProjectionHit from,
            ProjectionHit to
    ) {
        List<LatLng> result =
                new ArrayList<>();

        result.add(
                from.point
        );

        if (from.segmentIndex
                < to.segmentIndex
                || (
                from.segmentIndex
                        == to.segmentIndex
                        && from.t
                        <= to.t
        )) {

            for (int vertexIndex =
                    from.segmentIndex + 1;
                    vertexIndex
                            <= to.segmentIndex;
                    vertexIndex++) {

                result.add(
                        track.points.get(
                                vertexIndex
                        )
                );
            }

        } else {

            for (int vertexIndex =
                    from.segmentIndex;
                    vertexIndex
                            > to.segmentIndex;
                    vertexIndex--) {

                result.add(
                        track.points.get(
                                vertexIndex
                        )
                );
            }
        }

        if (distanceMeters(
                result.get(
                        result.size() - 1
                ),
                to.point
        ) > 0.05) {

            result.add(
                    to.point
            );
        }

        return result;
    }

    private ProjectionHit trackStartHit(
            CaminoRoute route,
            int trackIndex
    ) {
        RouteTrack track =
                route.tracks.get(
                        trackIndex
                );

        return new ProjectionHit(
                track.points.get(
                        0
                ),
                track.baseChainageM,
                0.0,
                trackIndex,
                0,
                0.0
        );
    }

    private ProjectionHit trackEndHit(
            CaminoRoute route,
            int trackIndex
    ) {
        RouteTrack track =
                route.tracks.get(
                        trackIndex
                );

        int lastSegment =
                track.points.size()
                        - 2;

        return new ProjectionHit(
                track.points.get(
                        track.points.size()
                                - 1
                ),
                track.baseChainageM
                        + track.lengthM,
                0.0,
                trackIndex,
                lastSegment,
                1.0
        );
    }

    private void updateDistanceLabel(
            ProjectionHit startHit
    ) {
        if (routes.isEmpty()) {
            setLabel(
                    "Lade Caminos …"
            );

            return;
        }

        if (selectedRoute == null
                || selectedHit == null) {

            setLabel(
                    "Camino antippen"
            );

            return;
        }

        if (secondTapHit != null) {
            double distanceM =
                    Math.abs(
                            secondTapHit.chainageM
                                    - selectedHit.chainageM
                    );

            setLabel(
                    formatDistance(
                            distanceM
                    )
                            + " "
                            + selectedRoute.name
            );

            return;
        }

        if (startHit == null) {
            setLabel(
                    "Startpunkt konnte nicht projiziert werden"
            );

            return;
        }

        double distanceM =
                Math.abs(
                        selectedHit.chainageM
                                - startHit.chainageM
                );

        String text =
                formatDistance(
                        distanceM
                )
                        + " "
                        + selectedRoute.name;

        if (startHit.distanceFromQueryM
                >= 3.0) {

            text +=
                    "\n"
                            + formatDistance(
                            startHit.distanceFromQueryM
                    )
                            + " Start";
        }

        setLabel(
                text
        );
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

        secondTapHit =
                null;

        refresh();
    }

    private void ensureDistanceView() {
        if (distanceView != null) {
            return;
        }

        distanceView =
                new TextView(
                        activity
                );

        distanceView.setTextColor(
                Color.WHITE
        );

        distanceView.setTextSize(
                15.0f
        );

        /*
         * Left-align the text inside the panel so the numeric values on
         * consecutive lines share exactly the same starting x-position:
         *
         *   430m  Camino ...
         *   173m  Start
         */
        distanceView.setGravity(
                Gravity.START
                        | Gravity.CENTER_VERTICAL
        );

        distanceView.setPadding(
                dp(14),
                dp(9),
                dp(14),
                dp(9)
        );

        GradientDrawable background =
                new GradientDrawable();

        background.setColor(
                Color.parseColor(
                        "#E63D332C"
                )
        );

        background.setCornerRadius(
                dp(18)
        );

        distanceView.setBackground(
                background
        );

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
                distanceView,
                params
        );
    }

    private void setLabel(
            String text
    ) {
        if (distanceView != null) {
            distanceView.setText(
                    text
            );
        }
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

    private static int sectionNumber(
            String sectionId
    ) {
        try {
            return Integer.parseInt(
                    sectionId.substring(
                            0,
                            sectionId.length()
                                    - 1
                    )
            );

        } catch (NumberFormatException error) {
            return Integer.MAX_VALUE;
        }
    }

    private static double polylineLength(
            List<LatLng> points
    ) {
        double total =
                0.0;

        for (int index = 0;
                index < points.size()
                        - 1;
                index++) {

            total +=
                    distanceMeters(
                            points.get(
                                    index
                            ),
                            points.get(
                                    index + 1
                            )
                    );
        }

        return total;
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

    private static final class CaminoRoute {
        final String id;
        final String name;
        final List<RouteTrack> tracks =
                new ArrayList<>();

        CaminoRoute(
                String id,
                String name
        ) {
            this.id = id;
            this.name = name;
        }
    }

    private static final class RouteTrack {
        final String sectionId;
        final int order;
        final List<LatLng> points;

        double baseChainageM;
        double lengthM;

        RouteTrack(
                String sectionId,
                int order,
                List<LatLng> points
        ) {
            this.sectionId =
                    sectionId;

            this.order =
                    order;

            this.points =
                    points;
        }
    }

    private static final class ProjectionHit {
        final LatLng point;
        final double chainageM;
        final double distanceFromQueryM;
        final int trackIndex;
        final int segmentIndex;
        final double t;

        ProjectionHit(
                LatLng point,
                double chainageM,
                double distanceFromQueryM,
                int trackIndex,
                int segmentIndex,
                double t
        ) {
            this.point =
                    point;

            this.chainageM =
                    chainageM;

            this.distanceFromQueryM =
                    distanceFromQueryM;

            this.trackIndex =
                    trackIndex;

            this.segmentIndex =
                    segmentIndex;

            this.t =
                    t;
        }
    }

    private static final class RouteHit {
        final CaminoRoute route;
        final ProjectionHit hit;

        RouteHit(
                CaminoRoute route,
                ProjectionHit hit
        ) {
            this.route =
                    route;

            this.hit =
                    hit;
        }
    }
}
