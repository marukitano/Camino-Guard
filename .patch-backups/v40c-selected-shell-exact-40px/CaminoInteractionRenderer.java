package com.marukitano.caminoguard;

import android.graphics.Color;

import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.maps.Style;
import org.maplibre.android.style.expressions.Expression;
import org.maplibre.android.style.layers.CircleLayer;
import org.maplibre.android.style.layers.LineLayer;
import org.maplibre.android.style.layers.SymbolLayer;
import org.maplibre.android.style.layers.Property;
import org.maplibre.android.style.layers.PropertyFactory;
import org.maplibre.android.style.sources.GeoJsonSource;
import org.maplibre.geojson.Feature;
import org.maplibre.geojson.FeatureCollection;
import org.maplibre.geojson.LineString;
import org.maplibre.geojson.Point;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders only the interactive Camino overlays.
 *
 * CaminoMapRenderer owns the static complete Camino network. CaminoController
 * still owns selection/measurement decisions and MeasurementEngine calls.
 */
final class CaminoInteractionRenderer {

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

    private static final String SELECTED_STAGE_SOURCE =
            "camino-selected-stage-source";

    private static final String SELECTED_STAGE_LAYER =
            "camino-selected-stage-halo";

    private static final String SELECTED_STAGE_SHELL_LAYER =
            "camino-selected-stage-shell-selected";

    private static final String ROUTE_GAP_SOURCE =
            "camino-route-gap-source";
    private static final String ROUTE_GAP_LAYER =
            "camino-route-gap";

    private GeoJsonSource selectedRouteSource;
    private GeoJsonSource connectorSource;
    private GeoJsonSource dummySource;
    private GeoJsonSource startSnapSource;
    private GeoJsonSource selectedSource;
    private GeoJsonSource selectedStageSource;
    private GeoJsonSource routeGapSource;

    void onStyleLoaded(
            Style style,
            LatLng dummyPosition,
            boolean livePositionMode
    ) {
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
                                dummyPosition.getLongitude(),
                                dummyPosition.getLatitude()
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

        selectedStageSource =
                new GeoJsonSource(
                        SELECTED_STAGE_SOURCE,
                        emptyFeatures()
                );

        style.addSource(
                selectedStageSource
        );

        /*
         * The normal stage shell is 30 px. For an active day-stage selection
         * draw the same shell at 40 px on the same point:
         *
         *     40 / 30 = 1.333333...
         *
         * The existing selection ring is created immediately afterwards and
         * therefore remains visible on top. The original 30 px stage marker
         * may still be rendered by CaminoMapRenderer; because both icons are
         * identical and perfectly centred it simply sits inside this larger
         * selected copy.
         */
        SymbolLayer selectedStageShell =
                new SymbolLayer(
                        SELECTED_STAGE_SHELL_LAYER,
                        SELECTED_STAGE_SOURCE
                );

        selectedStageShell.setProperties(
                PropertyFactory.iconImage(
                        "camino-stage-shell"
                ),
                PropertyFactory.iconSize(
                        40.0f / 30.0f
                ),
                PropertyFactory.iconAllowOverlap(
                        true
                ),
                PropertyFactory.iconIgnorePlacement(
                        true
                )
        );

        style.addLayer(
                selectedStageShell
        );

        CircleLayer selectedStage =
                new CircleLayer(
                        SELECTED_STAGE_LAYER,
                        SELECTED_STAGE_SOURCE
                );

        selectedStage.setProperties(
                PropertyFactory.circleRadius(
                        18.5f
                ),
                PropertyFactory.circleColor(
                        Color.TRANSPARENT
                ),
                PropertyFactory.circleStrokeColor(
                        Expression.get(
                                "highlight_color"
                        )
                ),
                PropertyFactory.circleStrokeWidth(
                        3.5f
                ),
                PropertyFactory.circleOpacity(
                        1.0f
                )
        );

        /*
         * Transparent centre: the shell remains completely visible. Only a
         * strong route-coloured frame is drawn around the tapped start shell.
         */
        style.addLayer(
                selectedStage
        );
    }

    boolean isMeasurementRouteReady() {
        return selectedRouteSource != null
                && routeGapSource != null;
    }

    void updateDummyPosition(
            LatLng dummyPosition
    ) {
        if (dummySource == null) {
            return;
        }

        dummySource.setGeoJson(
                Point.fromLngLat(
                        dummyPosition.getLongitude(),
                        dummyPosition.getLatitude()
                )
        );
    }

    void updateStartProjection(
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
                        startHit.point.getLongitude(),
                        startHit.point.getLatitude()
                )
        );
    }

    void updateConnector(
            LatLng dummyPosition,
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
                        LineString.fromLngLats(
                                points
                        )
                );

        feature.addStringProperty(
                "highlight_color",
                startRouteHit.route.highlightColor
        );

        connectorSource.setGeoJson(
                feature
        );
    }

    void hideStartProjectionAndConnector() {
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

    void updateSelectedPositions(
            ProjectionHit selectedHit,
            ProjectionHit secondTapHit
    ) {
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
                                selectedHit.point.getLongitude(),
                                selectedHit.point.getLatitude()
                        )
                )
        );

        if (secondTapHit != null) {
            points.add(
                    Feature.fromGeometry(
                            Point.fromLngLat(
                                    secondTapHit.point.getLongitude(),
                                    secondTapHit.point.getLatitude()
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

    void updateSelectedStage(
            LatLng stagePoint,
            String highlightColor
    ) {
        if (selectedStageSource == null) {
            return;
        }

        if (stagePoint == null
                || highlightColor == null) {

            selectedStageSource.setGeoJson(
                    emptyFeatures()
            );

            return;
        }

        Feature feature =
                Feature.fromGeometry(
                        Point.fromLngLat(
                                stagePoint.getLongitude(),
                                stagePoint.getLatitude()
                        )
                );

        feature.addStringProperty(
                "highlight_color",
                highlightColor
        );

        selectedStageSource.setGeoJson(
                feature
        );
    }


    void renderMeasurementPath(
            MeasurementPath path
    ) {
        if (!isMeasurementRouteReady()) {
            return;
        }

        if (path == null) {
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
                        path.routeFeatures
                )
        );

        routeGapSource.setGeoJson(
                FeatureCollection.fromFeatures(
                        path.gapFeatures
                )
        );
    }

    private static FeatureCollection emptyFeatures() {
        return FeatureCollection.fromFeatures(
                new ArrayList<>()
        );
    }
}
