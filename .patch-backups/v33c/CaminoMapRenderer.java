package com.marukitano.caminoguard;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.maplibre.android.geometry.VisibleRegion;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.Style;
import org.maplibre.android.style.layers.SymbolLayer;
import org.maplibre.android.style.layers.PropertyFactory;
import org.maplibre.android.style.sources.GeoJsonSource;
import org.maplibre.geojson.Feature;
import org.maplibre.geojson.FeatureCollection;
import org.maplibre.geojson.LineString;
import org.maplibre.geojson.Point;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Static renderer for the already-parsed canonical Camino domain model.
 *
 * CaminoRepository is the single owner of asset parsing. This class only
 * converts RouteTrack geometry into the GeoJSON required by MapLibre.
 */
final class CaminoMapRenderer {

    private static final String SOURCE =
            "camino-tracks";

    private static final String STAGE_SOURCE =
            "camino-stage-points";

    private static final String STAGE_LAYER =
            "camino-stage-circles";

    private static final String STAGE_IMAGE =
            "camino-stage-shell";

    /*
     * Stage markers belong directly above the visible Camino route, but below
     * the later political/world overview fills. That way they are hidden by
     * exactly the same overview curtain as the Camino itself.
     */
    private static final String SETTLEMENT_LAYER =
            "camino-settlement-points";

    /*
     * The compass control is 40 x 40 dp. A MapLibre circle radius of 20 gives
     * the stage marker the same visual diameter and leaves enough room for a
     * stage number later.
     */
    /*
     * The far-zoom minimum circle diameter is 4.4:
     *   Camino casing 2.2 * 2.
     *
     * The normal maximum should only be three times that minimum:
     *   4.4 * 3 = 13.2 diameter -> 6.6 radius.
     *
     * The existing dynamic Camino-width floor still wins at very close zooms,
     * so a marker can never become thinner than twice the visible Camino.
     */
    private static final float STAGE_MARKER_RADIUS =
            6.6f;

    /*
     * Official CNIG stage ES10a:01a Almeria -> Rioja is about 15.1 km long.
     *
     * A roughly 30-km visible screen height comfortably contains that stage.
     * At this scale and every closer scale the marker keeps its full 40-unit
     * diameter. Further out it shrinks proportionally with the visible map:
     *
     *   30 km viewport -> radius 20
     *   60 km viewport -> radius 10
     *  120 km viewport -> radius 5
     *  240 km viewport -> radius 2.5
     */
    private static final double STAGE_MARKER_FULL_SIZE_VIEWPORT_M =
            30_000.0;

    private final Context context;

    private SymbolLayer stageLayer;

    private float stageIconPixelSize =
            Float.NaN;

    private float lastStageMarkerRadius =
            Float.NaN;

    CaminoMapRenderer(
            Context context
    ) {
        this.context =
                context.getApplicationContext();
    }

    void onStyleLoaded(
            Style style,
            List<CaminoRoute> routes,
            MapLibreMap map
    ) {
        GeoJsonSource source =
                style.getSourceAs(
                        SOURCE
                );

        if (source == null) {
            throw new IllegalStateException(
                    "Map style is missing source "
                            + SOURCE
            );
        }

        source.setGeoJson(
                buildFeatures(
                        routes
                )
        );

        FeatureCollection stageFeatures =
                buildStageFeatures(
                        routes
                );

        GeoJsonSource stageSource =
                style.getSourceAs(
                        STAGE_SOURCE
                );

        if (stageSource == null) {
            stageSource =
                    new GeoJsonSource(
                            STAGE_SOURCE,
                            stageFeatures
                    );

            style.addSource(
                    stageSource
            );
        } else {
            stageSource.setGeoJson(
                    stageFeatures
            );
        }

        Bitmap stageIcon =
                BitmapFactory.decodeResource(
                        context.getResources(),
                        R.drawable.camino_stage_shell
                );

        if (stageIcon == null
                || stageIcon.getWidth() <= 0
                || stageIcon.getHeight() <= 0) {
            throw new IllegalStateException(
                    "Could not decode camino_stage_shell"
            );
        }

        stageIconPixelSize =
                Math.max(
                        stageIcon.getWidth(),
                        stageIcon.getHeight()
                );

        style.addImage(
                STAGE_IMAGE,
                stageIcon
        );

        if (style.getLayer(
                STAGE_LAYER
        ) == null) {

            stageLayer =
                    new SymbolLayer(
                            STAGE_LAYER,
                            STAGE_SOURCE
                    );

            stageLayer.setProperties(
                    PropertyFactory.iconImage(
                            STAGE_IMAGE
                    ),
                    PropertyFactory.iconAllowOverlap(
                            true
                    ),
                    PropertyFactory.iconIgnorePlacement(
                            true
                    ),
                    PropertyFactory.iconSize(
                            iconSizeForRadius(
                                    STAGE_MARKER_RADIUS
                            )
                    )
            );

            /*
             * Added after the Camino track layers so the stage discs remain
             * readable on top of the route. GPS/navigation layers are attached
             * later and therefore remain above these markers.
             */
            style.addLayerAbove(
                    stageLayer,
                    SETTLEMENT_LAYER
            );

        } else {
            stageLayer =
                    (SymbolLayer)
                            style.getLayer(
                                    STAGE_LAYER
                            );
        }

        lastStageMarkerRadius =
                Float.NaN;

        updateStageMarkerScale(
                map
        );
    }

    void updateStageMarkerScale(
            MapLibreMap map
    ) {
        if (stageLayer == null
                || map == null) {
            return;
        }

        VisibleRegion visibleRegion =
                map.getProjection()
                        .getVisibleRegion();

        if (visibleRegion == null) {
            return;
        }

        /*
         * Use the physical screen-height scale instead of MapLibre's abstract
         * zoom number. This keeps the behaviour stable across devices and map
         * orientation.
         */
        double leftHeightM =
                GeoMath.distanceMeters(
                        visibleRegion.farLeft,
                        visibleRegion.nearLeft
                );

        double rightHeightM =
                GeoMath.distanceMeters(
                        visibleRegion.farRight,
                        visibleRegion.nearRight
                );

        double viewportHeightM =
                Math.max(
                        leftHeightM,
                        rightHeightM
                );

        if (!Double.isFinite(
                viewportHeightM
        )
                || viewportHeightM
                <= 0.0) {
            return;
        }

        double scale =
                Math.min(
                        1.0,
                        STAGE_MARKER_FULL_SIZE_VIEWPORT_M
                                / viewportHeightM
                );

        float proportionalRadius =
                (float)
                        (
                                STAGE_MARKER_RADIUS
                                        * scale
                        );

        /*
         * Never let a stage circle become visually thinner than the Camino
         * itself.
         *
         * The visible Camino width is defined by the outer casing layer.
         * Requiring:
         *
         *     circle diameter >= 2 * Camino width
         *
         * means:
         *
         *     circle radius >= Camino casing width
         *
         * Keep this interpolation in sync with camino-route-casing in
         * styles/camino-basic.json.
         */
        float minimumRadius =
                caminoCasingWidthAtZoom(
                        map.getCameraPosition()
                                .zoom
                );

        float radius =
                Math.max(
                        proportionalRadius,
                        minimumRadius
                );

        /*
         * Avoid needless JNI/style updates for sub-pixel changes while the
         * camera is moving.
         */
        if (Float.isFinite(
                lastStageMarkerRadius
        )
                && Math.abs(
                radius
                        - lastStageMarkerRadius
        ) < 0.05f) {
            return;
        }

        lastStageMarkerRadius =
                radius;

        stageLayer.setProperties(
                PropertyFactory.iconSize(
                        iconSizeForRadius(
                                radius
                        )
                )
        );
    }

    private float iconSizeForRadius(
            float radius
    ) {
        if (!Float.isFinite(
                stageIconPixelSize
        )
                || stageIconPixelSize <= 0.0f) {
            return 1.0f;
        }

        /*
         * The old CircleLayer used "radius" as half the visual marker
         * diameter. SymbolLayer iconSize is a scale factor relative to the
         * registered bitmap width. Converting here preserves the exact visual
         * size behaviour we already tuned for the stage circles.
         */
        return (
                radius
                        * 2.0f
        )
                / stageIconPixelSize;
    }

    private float caminoCasingWidthAtZoom(
            double zoom
    ) {
        /*
         * Exact line-width stops from the MapLibre layer
         * "camino-route-casing":
         *
         *   z4  -> 2.2
         *   z8  -> 3.0
         *   z13 -> 5.0
         *   z15 -> 7.0
         *
         * MapLibre uses linear interpolation between these stops and clamps
         * outside the range, so do the same here.
         */
        if (!Double.isFinite(
                zoom
        )
                || zoom <= 4.0) {
            return 2.2f;
        }

        if (zoom < 8.0) {
            return lerp(
                    2.2f,
                    3.0f,
                    (float)
                            (
                                    (zoom - 4.0)
                                            / 4.0
                            )
            );
        }

        if (zoom < 13.0) {
            return lerp(
                    3.0f,
                    5.0f,
                    (float)
                            (
                                    (zoom - 8.0)
                                            / 5.0
                            )
            );
        }

        if (zoom < 15.0) {
            return lerp(
                    5.0f,
                    7.0f,
                    (float)
                            (
                                    (zoom - 13.0)
                                            / 2.0
                            )
            );
        }

        return 7.0f;
    }

    private float lerp(
            float start,
            float end,
            float factor
    ) {
        factor =
                Math.max(
                        0.0f,
                        Math.min(
                                1.0f,
                                factor
                        )
                );

        return start
                + (
                end
                        - start
        )
                * factor;
    }


    private FeatureCollection buildStageFeatures(
            List<CaminoRoute> routes
    ) {
        /*
         * One semantic place key -> exactly one rendered marker.
         *
         * A place can be the end of one stage and the start of the next, or
         * can occur in several Camino route groups. LinkedHashMap guarantees
         * that those repetitions never create two circles next to each other.
         */
        Map<String, Point> pointsByPlaceKey =
                new LinkedHashMap<>();

        Map<String, String> colorsByPlaceKey =
                new LinkedHashMap<>();

        for (CaminoRoute route
                : routes) {

            for (RouteTrack track
                    : route.renderTracks) {

                if (track.points.size()
                        < 2) {
                    continue;
                }

                addStageEndpoint(
                        pointsByPlaceKey,
                        colorsByPlaceKey,
                        track.fromKey,
                        track.pseudoFrom,
                        track.points.get(
                                0
                        ),
                        track.color
                );

                addStageEndpoint(
                        pointsByPlaceKey,
                        colorsByPlaceKey,
                        track.toKey,
                        track.pseudoTo,
                        track.points.get(
                                track.points.size()
                                        - 1
                        ),
                        track.color
                );
            }
        }

        List<Feature> features =
                new ArrayList<>(
                        pointsByPlaceKey.size()
                );

        for (Map.Entry<String, Point> entry
                : pointsByPlaceKey.entrySet()) {

            Feature feature =
                    Feature.fromGeometry(
                            entry.getValue()
                    );

            /*
             * Keep the semantic key in the GeoJSON. Later we can add a
             * SymbolLayer with a stage number without rebuilding marker
             * identity or deduplication.
             */
            feature.addStringProperty(
                    "place_key",
                    entry.getKey()
            );

            feature.addStringProperty(
                    "marker_color",
                    colorsByPlaceKey.get(
                            entry.getKey()
                    )
            );

            features.add(
                    feature
            );
        }

        return FeatureCollection.fromFeatures(
                features
        );
    }

    private void addStageEndpoint(
            Map<String, Point> pointsByPlaceKey,
            Map<String, String> colorsByPlaceKey,
            String placeKey,
            boolean pseudo,
            org.maplibre.android.geometry.LatLng point,
            String markerColor
    ) {
        if (pseudo
                || placeKey == null
                || placeKey.isEmpty()
                || point == null
                || pointsByPlaceKey.containsKey(
                placeKey
        )) {
            return;
        }

        pointsByPlaceKey.put(
                placeKey,
                Point.fromLngLat(
                        point.getLongitude(),
                        point.getLatitude()
                )
        );

        colorsByPlaceKey.put(
                placeKey,
                markerColor
        );
    }


    private FeatureCollection buildFeatures(
            List<CaminoRoute> routes
    ) {
        List<Feature> features =
                new ArrayList<>();

        for (CaminoRoute route
                : routes) {

            for (RouteTrack track
                    : route.renderTracks) {

                if (track.points.size() < 2) {
                    continue;
                }

                List<Point> points =
                        new ArrayList<>(
                                track.points.size()
                        );

                for (org.maplibre.android.geometry.LatLng point
                        : track.points) {

                    points.add(
                            Point.fromLngLat(
                                    point.getLongitude(),
                                    point.getLatitude()
                            )
                    );
                }

                Feature feature =
                        Feature.fromGeometry(
                                LineString.fromLngLats(
                                        points
                                )
                        );

                feature.addStringProperty(
                        "routeColor",
                        track.color
                );

                feature.addStringProperty(
                        "casingColor",
                        track.highlightColor
                );

                feature.addStringProperty(
                        "route_group_id",
                        route.id
                );

                feature.addStringProperty(
                        "section_id",
                        track.sectionId
                );

                features.add(
                        feature
                );
            }
        }

        return FeatureCollection.fromFeatures(
                features
        );
    }
}
