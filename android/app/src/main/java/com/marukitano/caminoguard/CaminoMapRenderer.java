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

    static final String STAGE_LAYER =
            "camino-stage-circles";

    private static final String STAGE_IMAGE =
            "camino-stage-shell";

    static final String STAGE_SELECTED_IMAGE =
            "camino-stage-shell-selected-40";

    private static final float STAGE_ICON_FIXED_DIAMETER_PX =
            30.0f;

    /*
     * Normal stage shells are useful only once the map is close enough to
     * inspect individual day stages. Keeping hundreds of overlapping symbols
     * alive at country scale causes unnecessary MapLibre layout/render work.
     *
     * Use the real visible screen diagonal instead of a fixed zoom level so
     * the threshold remains meaningful across devices, latitude and tilt.
     */
    private static final double STAGE_MARKER_MAX_VIEWPORT_DIAGONAL_M =
            200_000.0;

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
private final Context context;

    private SymbolLayer stageLayer;

    /*
     * The camera listeners survive a style reload. Keep only one listener set
     * per MapLibreMap; stageLayer itself may be replaced by a later style.
     */
    private MapLibreMap stageVisibilityMap;

    private boolean stageMarkersVisible;

    /*
     * Logical MapLibre image size in style pixels, not raw bitmap pixels.
     * MapLibre derives sprite pixelRatio from Bitmap.getDensity().
     */
    private float stageIconStyleSize =
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
            CaminoStageTopology stageTopology,
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
                        stageTopology
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

        /*
         * drawable-nodpi prevents Android from resizing the PNG, but it also
         * leaves the decoded Bitmap without useful density metadata.
         *
         * MapLibre Android derives sprite pixelRatio from Bitmap.getDensity().
         * Give the bitmap the real display density so 256 physical pixels map
         * to the same logical/style pixel system as CircleLayer.
         */
        int densityDpi =
                context.getResources()
                        .getDisplayMetrics()
                        .densityDpi;

        float density =
                context.getResources()
                        .getDisplayMetrics()
                        .density;

        stageIcon.setDensity(
                densityDpi
        );

        stageIconStyleSize =
                Math.max(
                        stageIcon.getWidth(),
                        stageIcon.getHeight()
                )
                        / density;

        style.addImage(
                STAGE_IMAGE,
                stageIcon
        );

        /*
         * The normal shell is rendered at exactly 30 logical/style pixels via
         * fixedStageIconSize(). The selected shell must be exactly 40 logical
         * pixels, not 1.333 times the raw source bitmap.
         *
         * Build a second bitmap whose logical size is 40 px at the current
         * display density, then MapLibre can render it with iconSize(1.0).
         */
        float selectedScale =
                (
                        40.0f
                                * density
                )
                        / Math.max(
                        stageIcon.getWidth(),
                        stageIcon.getHeight()
                );

        int selectedWidth =
                Math.max(
                        1,
                        Math.round(
                                stageIcon.getWidth()
                                        * selectedScale
                        )
                );

        int selectedHeight =
                Math.max(
                        1,
                        Math.round(
                                stageIcon.getHeight()
                                        * selectedScale
                        )
                );

        Bitmap selectedStageIcon =
                Bitmap.createScaledBitmap(
                        stageIcon,
                        selectedWidth,
                        selectedHeight,
                        true
                );

        selectedStageIcon.setDensity(
                densityDpi
        );

        style.addImage(
                STAGE_SELECTED_IMAGE,
                selectedStageIcon
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
                    PropertyFactory.visibility(
                            "none"
                    ),
                    PropertyFactory.iconAllowOverlap(
                            true
                    ),
                    PropertyFactory.iconIgnorePlacement(
                            true
                    ),
                    PropertyFactory.iconSize(
                            fixedStageIconSize()
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

        installStageVisibilityGate(
                map
        );


}


    /*
     * Keep the expensive all-stage shell layer completely disabled while the
     * visible map diagonal is larger than about 200 km.
     *
     * Zooming OUT:
     *   hide immediately while the camera is moving, as soon as the threshold
     *   is crossed.
     *
     * Zooming IN:
     *   while hidden, do no per-frame distance work at all. Re-enable only on
     *   CameraIdle once the final viewport is <= 200 km.
     *
     * This asymmetry avoids bringing hundreds of overlapping symbols back into
     * MapLibre in the middle of a pinch gesture.
     */
    private void installStageVisibilityGate(
            MapLibreMap map
    ) {
        setStageMarkersVisible(
                false
        );

        /*
         * Style loading can happen while the restored camera is already at a
         * close scale. Evaluate it once immediately instead of waiting for the
         * user's first gesture.
         */
        updateStageVisibilityAfterCameraIdle(
                map
        );

        if (stageVisibilityMap == map) {
            return;
        }

        stageVisibilityMap =
                map;

        map.addOnCameraMoveListener(
                () -> {
                    if (!stageMarkersVisible) {
                        return;
                    }

                    double diagonalM =
                            visibleViewportDiagonalMeters(
                                    map
                            );

                    if (!Double.isFinite(
                            diagonalM
                    )
                            || diagonalM
                            > STAGE_MARKER_MAX_VIEWPORT_DIAGONAL_M) {

                        setStageMarkersVisible(
                                false
                        );
                    }
                }
        );

        map.addOnCameraIdleListener(
                () -> updateStageVisibilityAfterCameraIdle(
                        map
                )
        );
    }


    private void updateStageVisibilityAfterCameraIdle(
            MapLibreMap map
    ) {
        double diagonalM =
                visibleViewportDiagonalMeters(
                        map
                );

        boolean shouldBeVisible =
                Double.isFinite(
                        diagonalM
                )
                        && diagonalM
                        <= STAGE_MARKER_MAX_VIEWPORT_DIAGONAL_M;

        if (stageMarkersVisible != shouldBeVisible) {
            setStageMarkersVisible(
                    shouldBeVisible
            );
        }
    }


    private void setStageMarkersVisible(
            boolean visible
    ) {
        stageMarkersVisible =
                visible;

        if (stageLayer == null) {
            return;
        }

        stageLayer.setProperties(
                PropertyFactory.visibility(
                        visible
                                ? "visible"
                                : "none"
                )
        );
    }


    private double visibleViewportDiagonalMeters(
            MapLibreMap map
    ) {
        try {
            VisibleRegion region =
                    map.getProjection()
                            .getVisibleRegion();

            if (region == null
                    || region.farLeft == null
                    || region.farRight == null
                    || region.nearLeft == null
                    || region.nearRight == null) {

                return Double.POSITIVE_INFINITY;
            }

            /*
             * Rotation and tilt can make the two geographic screen diagonals
             * differ. Use the longer one so "200 km diagonal" is conservative.
             */
            double diagonal1M =
                    GeoMath.distanceMeters(
                            region.farLeft,
                            region.nearRight
                    );

            double diagonal2M =
                    GeoMath.distanceMeters(
                            region.farRight,
                            region.nearLeft
                    );

            return Math.max(
                    diagonal1M,
                    diagonal2M
            );

        } catch (RuntimeException error) {
            /*
             * During transient projection/style states, fail closed: keeping
             * the mass marker layer hidden is both safer and faster.
             */
            return Double.POSITIVE_INFINITY;
        }
    }


    private float fixedStageIconSize() {
        if (!Float.isFinite(
                stageIconStyleSize
        )
                || stageIconStyleSize <= 0.0f) {
            return 1.0f;
        }

        return STAGE_ICON_FIXED_DIAMETER_PX
                / stageIconStyleSize;
    }

    private FeatureCollection buildStageFeatures(
            CaminoStageTopology stageTopology
    ) {
        List<Feature> features =
                new ArrayList<>();

        /*
         * Renderer no longer invents or deduplicates routing identity.
         * CaminoStageTopology already owns exactly one logical StageNode per
         * placeKey and retains every Camino membership behind that node.
         */
        for (CaminoStageTopology.StageNode node
                : stageTopology.nodes()) {

            if (node == null
                    || node.point == null
                    || node.placeKey == null
                    || node.placeKey.isEmpty()) {

                continue;
            }

            Feature feature =
                    Feature.fromGeometry(
                            Point.fromLngLat(
                                    node.point.getLongitude(),
                                    node.point.getLatitude()
                            )
                    );

            feature.addStringProperty(
                    "place_key",
                    node.placeKey
            );

            feature.addStringProperty(
                    "marker_color",
                    node.markerColor
            );

            features.add(
                    feature
            );
        }

        return FeatureCollection.fromFeatures(
                features
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
