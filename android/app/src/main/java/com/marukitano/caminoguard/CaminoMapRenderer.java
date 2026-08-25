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

    static final String STAGE_LAYER =
            "camino-stage-circles";

    private static final String STAGE_IMAGE =
            "camino-stage-shell";

    static final String STAGE_SELECTED_IMAGE =
            "camino-stage-shell-selected-40";

    private static final String VARIANT_SOURCE =
            "camino-variant-points";

    static final String VARIANT_LAYER =
            "camino-variant-snails";

    private static final String VARIANT_IMAGE =
            "camino-variant-snail";

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
     * v60: coloured v50 network-debug circles/labels are disabled.
     */
    private static final boolean NETWORK_DEBUG_ENABLED =
            false;

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

        if (NETWORK_DEBUG_ENABLED) {
            addRawTrackDebugIcons(
                    style,
                    stageFeatures,
                    routes
            );
        }

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


        /*
         * TEMPORARY DEBUG:
         * Show the raw canonical route_group_id + section_id for all original
         * track endpoints located at each currently rendered shell.
         *
         * No topology/outgoing-choice logic participates in these labels.
         */
        FeatureCollection variantFeatures =
                buildVariantFeatures(
                        routes
                );

        GeoJsonSource variantSource =
                style.getSourceAs(
                        VARIANT_SOURCE
                );

        if (variantSource == null) {
            variantSource =
                    new GeoJsonSource(
                            VARIANT_SOURCE,
                            variantFeatures
                    );

            style.addSource(
                    variantSource
            );

        } else {
            variantSource.setGeoJson(
                    variantFeatures
            );
        }

        Bitmap variantIcon =
                createVariantSnailIcon(
                        stageIcon.getWidth(),
                        stageIcon.getHeight(),
                        densityDpi
                );

        style.addImage(
                VARIANT_IMAGE,
                variantIcon
        );

        if (style.getLayer(
                VARIANT_LAYER
        ) == null) {

            SymbolLayer variantLayer =
                    new SymbolLayer(
                            VARIANT_LAYER,
                            VARIANT_SOURCE
                    );

            variantLayer.setProperties(
                    PropertyFactory.iconImage(
                            VARIANT_IMAGE
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
                    ),
                    PropertyFactory.iconOffset(
                            new Float[]{
                                    18.0f,
                                    0.0f
                            }
                    )
            );

            style.addLayerAbove(
                    variantLayer,
                    STAGE_LAYER
            );
        }

        if (NETWORK_DEBUG_ENABLED) {
            installNetworkDebugView(
                    style,
                    routes
            );
        }

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


    private void addRawTrackDebugIcons(
            Style style,
            FeatureCollection stageFeatures,
            List<CaminoRoute> routes
    ) {
        if (stageFeatures == null
                || stageFeatures.features() == null) {

            return;
        }

        int imageIndex =
                0;

        for (Feature feature
                : stageFeatures.features()) {

            if (feature == null
                    || !(feature.geometry() instanceof Point)) {

                continue;
            }

            Point shell =
                    (Point)
                            feature.geometry();

            List<String> labels =
                    rawTrackLabelsAt(
                            shell,
                            routes
                    );

            String imageId =
                    "camino-stage-debug-track-"
                            + imageIndex++;

            Bitmap bitmap =
                    createRawTrackDebugBitmap(
                            labels
                    );

            style.addImage(
                    imageId,
                    bitmap
            );

            feature.addStringProperty(
                    "raw_track_icon",
                    imageId
            );
        }
    }


    private List<String> rawTrackLabelsAt(
            Point shell,
            List<CaminoRoute> routes
    ) {
        org.maplibre.android.geometry.LatLng shellPoint =
                new org.maplibre.android.geometry.LatLng(
                        shell.latitude(),
                        shell.longitude()
                );

        java.util.LinkedHashSet<String> labels =
                new java.util.LinkedHashSet<>();

        for (CaminoRoute route
                : routes) {

            for (RouteTrack track
                    : route.renderTracks) {

                if (track == null
                        || track.points.size() < 2) {

                    continue;
                }

                org.maplibre.android.geometry.LatLng first =
                        track.points.get(
                                0
                        );

                org.maplibre.android.geometry.LatLng last =
                        track.points.get(
                                track.points.size() - 1
                        );

                double firstDistanceM =
                        GeoMath.distanceMeters(
                                shellPoint,
                                first
                        );

                double lastDistanceM =
                        GeoMath.distanceMeters(
                                shellPoint,
                                last
                        );

                /*
                 * Debug only: deliberately generous. We want to SEE the raw
                 * numbering around the shell, not make a routing decision.
                 */
                if (firstDistanceM <= 500.0) {
                    labels.add(
                            "P1 "
                                    + route.id
                                    + ":"
                                    + track.sectionId
                                    + "  "
                                    + shortPlace(
                                    track.fromKey
                            )
                                    + " -> "
                                    + shortPlace(
                                    track.toKey
                            )
                                    + (
                                    route.tracks.contains(
                                            track
                                    )
                                            ? "  [PRIMARY]"
                                            : "  [VAR]"
                            )
                    );
                }

                if (lastDistanceM <= 500.0) {
                    labels.add(
                            "P2 "
                                    + route.id
                                    + ":"
                                    + track.sectionId
                                    + "  "
                                    + shortPlace(
                                    track.fromKey
                            )
                                    + " -> "
                                    + shortPlace(
                                    track.toKey
                            )
                                    + (
                                    route.tracks.contains(
                                            track
                                    )
                                            ? "  [PRIMARY]"
                                            : "  [VAR]"
                            )
                    );
                }
            }
        }

        if (labels.isEmpty()) {
            labels.add(
                    "(kein Track-Endpunkt <=500m)"
            );
        }

        return new ArrayList<>(
                labels
        );
    }


    private String shortPlace(
            String value
    ) {
        if (value == null
                || value.isEmpty()) {

            return "?";
        }

        if (value.length() <= 18) {
            return value;
        }

        return value.substring(
                0,
                17
        )
                + "…";
    }


    private Bitmap createRawTrackDebugBitmap(
            List<String> lines
    ) {
        float density =
                context.getResources()
                        .getDisplayMetrics()
                        .density;

        int densityDpi =
                context.getResources()
                        .getDisplayMetrics()
                        .densityDpi;

        android.graphics.Paint paint =
                new android.graphics.Paint(
                        android.graphics.Paint.ANTI_ALIAS_FLAG
                );

        paint.setColor(
                android.graphics.Color.BLACK
        );

        paint.setTextSize(
                10.0f
                        * density
        );

        paint.setTypeface(
                android.graphics.Typeface.create(
                        android.graphics.Typeface.MONOSPACE,
                        android.graphics.Typeface.BOLD
                )
        );

        float padding =
                5.0f
                        * density;

        float lineGap =
                3.0f
                        * density;

        android.graphics.Paint.FontMetrics metrics =
                paint.getFontMetrics();

        float lineHeight =
                metrics.descent
                        - metrics.ascent;

        float widest =
                1.0f;

        for (String line
                : lines) {

            widest =
                    Math.max(
                            widest,
                            paint.measureText(
                                    line
                            )
                    );
        }

        int width =
                Math.max(
                        1,
                        Math.round(
                                widest
                                        + padding
                                        * 2.0f
                        )
                );

        int height =
                Math.max(
                        1,
                        Math.round(
                                padding
                                        * 2.0f
                                        + lines.size()
                                        * lineHeight
                                        + Math.max(
                                        0,
                                        lines.size() - 1
                                )
                                        * lineGap
                        )
                );

        Bitmap bitmap =
                Bitmap.createBitmap(
                        width,
                        height,
                        Bitmap.Config.ARGB_8888
                );

        bitmap.setDensity(
                densityDpi
        );

        android.graphics.Canvas canvas =
                new android.graphics.Canvas(
                        bitmap
                );

        android.graphics.Paint background =
                new android.graphics.Paint(
                        android.graphics.Paint.ANTI_ALIAS_FLAG
                );

        background.setColor(
                android.graphics.Color.argb(
                        225,
                        255,
                        255,
                        255
                )
        );

        float radius =
                4.0f
                        * density;

        canvas.drawRoundRect(
                0.0f,
                0.0f,
                width,
                height,
                radius,
                radius,
                background
        );

        android.graphics.Paint border =
                new android.graphics.Paint(
                        android.graphics.Paint.ANTI_ALIAS_FLAG
                );

        border.setStyle(
                android.graphics.Paint.Style.STROKE
        );

        border.setStrokeWidth(
                Math.max(
                        1.0f,
                        density
                )
        );

        border.setColor(
                android.graphics.Color.argb(
                        180,
                        0,
                        0,
                        0
                )
        );

        canvas.drawRoundRect(
                0.5f,
                0.5f,
                width - 0.5f,
                height - 0.5f,
                radius,
                radius,
                border
        );

        float baseline =
                padding
                        - metrics.ascent;

        for (String line
                : lines) {

            canvas.drawText(
                    line,
                    padding,
                    baseline,
                    paint
            );

            baseline +=
                    lineHeight
                            + lineGap;
        }

        return bitmap;
    }


    private FeatureCollection buildVariantFeatures(
            List<CaminoRoute> routes
    ) {
        List<Feature> features =
                new ArrayList<>();

        for (CaminoRoute route
                : routes) {

            for (CaminoVariantPath path
                    : route.variantPaths) {

                if (path.parts.isEmpty()) {
                    continue;
                }

                addVariantEndpointFeature(
                        features,
                        path,
                        path.startPoint(),
                        "start"
                );

                if (GeoMath.distanceMeters(
                        path.startPoint(),
                        path.endPoint()
                ) > 1.0) {

                    addVariantEndpointFeature(
                            features,
                            path,
                            path.endPoint(),
                            "end"
                    );
                }
            }
        }

        return FeatureCollection.fromFeatures(
                features
        );
    }


    private void addVariantEndpointFeature(
            List<Feature> features,
            CaminoVariantPath path,
            org.maplibre.android.geometry.LatLng point,
            String endpointRole
    ) {
        Feature feature =
                Feature.fromGeometry(
                        Point.fromLngLat(
                                point.getLongitude(),
                                point.getLatitude()
                        )
                );

        feature.addStringProperty(
                "variant_path_id",
                path.id
        );

        feature.addStringProperty(
                "endpoint_role",
                endpointRole
        );

        feature.addStringProperty(
                "track_ids",
                path.trackIdsLabel()
        );

        features.add(
                feature
        );
    }


    private Bitmap createVariantSnailIcon(
            int width,
            int height,
            int densityDpi
    ) {
        Bitmap bitmap =
                Bitmap.createBitmap(
                        Math.max(
                                1,
                                width
                        ),
                        Math.max(
                                1,
                                height
                        ),
                        Bitmap.Config.ARGB_8888
                );

        bitmap.setDensity(
                densityDpi
        );

        android.graphics.Canvas canvas =
                new android.graphics.Canvas(
                        bitmap
                );

        float cx =
                width / 2.0f;

        float cy =
                height / 2.0f;

        float radius =
                Math.min(
                        width,
                        height
                ) * 0.46f;

        int blue =
                android.graphics.Color.rgb(
                        35,
                        93,
                        154
                );

        int cream =
                android.graphics.Color.rgb(
                        255,
                        246,
                        205
                );

        android.graphics.Paint fill =
                new android.graphics.Paint(
                        android.graphics.Paint.ANTI_ALIAS_FLAG
                );

        fill.setStyle(
                android.graphics.Paint.Style.FILL
        );

        fill.setColor(
                blue
        );

        canvas.drawCircle(
                cx,
                cy,
                radius,
                fill
        );

        fill.setColor(
                cream
        );

        canvas.drawCircle(
                cx,
                cy,
                radius * 0.78f,
                fill
        );

        android.graphics.Paint spiral =
                new android.graphics.Paint(
                        android.graphics.Paint.ANTI_ALIAS_FLAG
                );

        spiral.setColor(
                blue
        );

        spiral.setStyle(
                android.graphics.Paint.Style.STROKE
        );

        spiral.setStrokeCap(
                android.graphics.Paint.Cap.ROUND
        );

        spiral.setStrokeJoin(
                android.graphics.Paint.Join.ROUND
        );

        spiral.setStrokeWidth(
                radius * 0.13f
        );

        android.graphics.Path spiralPath =
                new android.graphics.Path();

        float lastX =
                cx;

        float lastY =
                cy;

        float beforeX =
                cx;

        float beforeY =
                cy;

        final int samples =
                72;

        for (int index = 0;
                index <= samples;
                index++) {

            float t =
                    index
                            / (float)
                            samples;

            double angle =
                    -Math.PI * 0.20
                            + t
                            * Math.PI
                            * 3.55;

            float r =
                    radius
                            * (
                            0.08f
                                    + 0.56f
                                    * t
                    );

            float x =
                    cx
                            + r
                            * (float)
                            Math.cos(
                                    angle
                            );

            float y =
                    cy
                            + r
                            * (float)
                            Math.sin(
                                    angle
                            );

            if (index == 0) {
                spiralPath.moveTo(
                        x,
                        y
                );

            } else {
                spiralPath.lineTo(
                        x,
                        y
                );
            }

            beforeX =
                    lastX;

            beforeY =
                    lastY;

            lastX =
                    x;

            lastY =
                    y;
        }

        canvas.drawPath(
                spiralPath,
                spiral
        );

        float dx =
                lastX - beforeX;

        float dy =
                lastY - beforeY;

        float length =
                (float)
                        Math.hypot(
                                dx,
                                dy
                        );

        if (length > 0.001f) {
            dx /=
                    length;

            dy /=
                    length;

            float arrow =
                    radius * 0.25f;

            float wing =
                    radius * 0.14f;

            float baseX =
                    lastX
                            - dx
                            * arrow;

            float baseY =
                    lastY
                            - dy
                            * arrow;

            float px =
                    -dy;

            float py =
                    dx;

            android.graphics.Path head =
                    new android.graphics.Path();

            head.moveTo(
                    lastX,
                    lastY
            );

            head.lineTo(
                    baseX
                            + px
                            * wing,
                    baseY
                            + py
                            * wing
            );

            head.moveTo(
                    lastX,
                    lastY
            );

            head.lineTo(
                    baseX
                            - px
                            * wing,
                    baseY
                            - py
                            * wing
            );

            canvas.drawPath(
                    head,
                    spiral
            );
        }

        return bitmap;
    }



    /*
     * ---------------------------------------------------------------------
     * TEMPORARY NETWORK INSPECTION VIEW
     * ---------------------------------------------------------------------
     *
     * The shell resource remains untouched. These layers sit above it and are
     * only here to make the official CNIG track structure human-readable.
     *
     * - every original renderTrack endpoint becomes a coloured node
     * - node colours are split into equal "pizza slices" for all Caminos there
     * - node labels show incomingRouteSection - route - outgoingRouteSection
     * - every track id is repeated approximately every 5 km along the geometry
     *
     * No routing/topology decision is derived from these debug objects.
     */

    // v50a-short-node-labels
    private static final String DEBUG_NODE_SOURCE =
            "camino-debug-track-nodes";

    private static final String DEBUG_NODE_LAYER =
            "camino-debug-track-node-circles";

    private static final String DEBUG_NODE_LABEL_LAYER =
            "camino-debug-track-node-labels";

    private static final String DEBUG_TRACK_LABEL_SOURCE =
            "camino-debug-track-label-points";

    private static final String DEBUG_TRACK_LABEL_LAYER =
            "camino-debug-track-labels-v50";

    private static final double DEBUG_NODE_CLUSTER_M =
            120.0;

    private static final double DEBUG_TRACK_LABEL_SPACING_M =
            5_000.0;

    private static final float DEBUG_NODE_DIAMETER_DP =
            34.0f;


    private void installNetworkDebugView(
            Style style,
            List<CaminoRoute> routes
    ) {
        List<DebugTrackNode> nodes =
                buildDebugTrackNodes(
                        routes
                );

        FeatureCollection nodeFeatures =
                buildDebugNodeFeaturesAndImages(
                        style,
                        nodes
                );

        GeoJsonSource nodeSource =
                style.getSourceAs(
                        DEBUG_NODE_SOURCE
                );

        if (nodeSource == null) {
            nodeSource =
                    new GeoJsonSource(
                            DEBUG_NODE_SOURCE,
                            nodeFeatures
                    );

            style.addSource(
                    nodeSource
            );

        } else {
            nodeSource.setGeoJson(
                    nodeFeatures
            );
        }

        FeatureCollection trackLabelFeatures =
                buildTrackLabelFeaturesAndImages(
                        style,
                        routes
                );

        GeoJsonSource trackLabelSource =
                style.getSourceAs(
                        DEBUG_TRACK_LABEL_SOURCE
                );

        if (trackLabelSource == null) {
            trackLabelSource =
                    new GeoJsonSource(
                            DEBUG_TRACK_LABEL_SOURCE,
                            trackLabelFeatures
                    );

            style.addSource(
                    trackLabelSource
            );

        } else {
            trackLabelSource.setGeoJson(
                    trackLabelFeatures
            );
        }

        if (style.getLayer(
                DEBUG_TRACK_LABEL_LAYER
        ) == null) {

            SymbolLayer trackLabelLayer =
                    new SymbolLayer(
                            DEBUG_TRACK_LABEL_LAYER,
                            DEBUG_TRACK_LABEL_SOURCE
                    );

            trackLabelLayer.setProperties(
                    PropertyFactory.iconImage(
                            "{debug_track_label_icon}"
                    ),
                    PropertyFactory.iconAllowOverlap(
                            false
                    ),
                    PropertyFactory.iconIgnorePlacement(
                            false
                    )
            );

            trackLabelLayer.setMinZoom(
                    8.0f
            );

            style.addLayerAbove(
                    trackLabelLayer,
                    STAGE_LAYER
            );
        }

        if (style.getLayer(
                DEBUG_NODE_LAYER
        ) == null) {

            SymbolLayer nodeLayer =
                    new SymbolLayer(
                            DEBUG_NODE_LAYER,
                            DEBUG_NODE_SOURCE
                    );

            nodeLayer.setProperties(
                    PropertyFactory.iconImage(
                            "{debug_node_icon}"
                    ),
                    PropertyFactory.iconAllowOverlap(
                            true
                    ),
                    PropertyFactory.iconIgnorePlacement(
                            true
                    )
            );

            /*
             * v49's snail stays visible on top where it exists.
             * Everywhere else this opaque 34dp debug circle covers the normal
             * 30px shell without deleting or modifying the shell asset.
             */
            if (style.getLayer(
                    VARIANT_LAYER
            ) != null) {

                style.addLayerBelow(
                        nodeLayer,
                        VARIANT_LAYER
                );

            } else {
                style.addLayerAbove(
                        nodeLayer,
                        STAGE_LAYER
                );
            }
        }

        if (style.getLayer(
                DEBUG_NODE_LABEL_LAYER
        ) == null) {

            SymbolLayer nodeLabelLayer =
                    new SymbolLayer(
                            DEBUG_NODE_LABEL_LAYER,
                            DEBUG_NODE_SOURCE
                    );

            nodeLabelLayer.setProperties(
                    PropertyFactory.iconImage(
                            "{debug_node_label_icon}"
                    ),
                    PropertyFactory.iconAllowOverlap(
                            true
                    ),
                    PropertyFactory.iconIgnorePlacement(
                            true
                    ),
                    PropertyFactory.iconOffset(
                            new Float[]{
                                    0.0f,
                                    30.0f
                            }
                    )
            );

            nodeLabelLayer.setMinZoom(
                    8.5f
            );

            style.addLayerAbove(
                    nodeLabelLayer,
                    DEBUG_NODE_LAYER
            );
        }
    }


    private List<DebugTrackNode> buildDebugTrackNodes(
            List<CaminoRoute> routes
    ) {
        List<DebugTrackNode> nodes =
                new ArrayList<>();

        for (CaminoRoute route
                : routes) {

            for (RouteTrack track
                    : route.renderTracks) {

                if (track == null
                        || track.points.size() < 2) {

                    continue;
                }

                addDebugEndpoint(
                        nodes,
                        route,
                        track,
                        track.points.get(
                                0
                        ),
                        true
                );

                addDebugEndpoint(
                        nodes,
                        route,
                        track,
                        track.points.get(
                                track.points.size() - 1
                        ),
                        false
                );
            }
        }

        return nodes;
    }


    private void addDebugEndpoint(
            List<DebugTrackNode> nodes,
            CaminoRoute route,
            RouteTrack track,
            org.maplibre.android.geometry.LatLng point,
            boolean firstEndpoint
    ) {
        DebugTrackNode best =
                null;

        double bestDistanceM =
                Double.POSITIVE_INFINITY;

        for (DebugTrackNode candidate
                : nodes) {

            double distanceM =
                    GeoMath.distanceMeters(
                            point,
                            candidate.point
                    );

            if (distanceM <= DEBUG_NODE_CLUSTER_M
                    && distanceM < bestDistanceM) {

                best =
                        candidate;

                bestDistanceM =
                        distanceM;
            }
        }

        if (best == null) {
            best =
                    new DebugTrackNode(
                            point
                    );

            nodes.add(
                    best
            );
        }

        best.addEndpoint(
                route,
                track,
                firstEndpoint
        );
    }


    private FeatureCollection buildDebugNodeFeaturesAndImages(
            Style style,
            List<DebugTrackNode> nodes
    ) {
        List<Feature> features =
                new ArrayList<>();

        int index =
                0;

        for (DebugTrackNode node
                : nodes) {

            String nodeIconId =
                    "camino-debug-node-"
                            + index;

            String labelIconId =
                    "camino-debug-node-label-"
                            + index;

            style.addImage(
                    nodeIconId,
                    createDebugNodeBitmap(
                            node
                    )
            );

            style.addImage(
                    labelIconId,
                    createTextBadgeBitmap(
                            node.labelLines(),
                            "#333333"
                    )
            );

            Feature feature =
                    Feature.fromGeometry(
                            Point.fromLngLat(
                                    node.point.getLongitude(),
                                    node.point.getLatitude()
                            )
                    );

            feature.addStringProperty(
                    "debug_node_icon",
                    nodeIconId
            );

            feature.addStringProperty(
                    "debug_node_label_icon",
                    labelIconId
            );

            features.add(
                    feature
            );

            index++;
        }

        return FeatureCollection.fromFeatures(
                features
        );
    }


    private FeatureCollection buildTrackLabelFeaturesAndImages(
            Style style,
            List<CaminoRoute> routes
    ) {
        List<Feature> features =
                new ArrayList<>();

        int imageIndex =
                0;

        for (CaminoRoute route
                : routes) {

            for (RouteTrack track
                    : route.renderTracks) {

                if (track == null
                        || track.points.size() < 2) {

                    continue;
                }

                String label =
                        route.id
                                + ":"
                                + track.sectionId;

                String imageId =
                        "camino-debug-track-label-"
                                + imageIndex++;

                style.addImage(
                        imageId,
                        createTextBadgeBitmap(
                                java.util.Collections.singletonList(
                                        label
                                ),
                                track.color
                        )
                );

                List<org.maplibre.android.geometry.LatLng> labelPoints =
                        equallySpacedTrackLabelPoints(
                                track
                        );

                for (org.maplibre.android.geometry.LatLng point
                        : labelPoints) {

                    Feature feature =
                            Feature.fromGeometry(
                                    Point.fromLngLat(
                                            point.getLongitude(),
                                            point.getLatitude()
                                    )
                            );

                    feature.addStringProperty(
                            "debug_track_label_icon",
                            imageId
                    );

                    features.add(
                            feature
                    );
                }
            }
        }

        return FeatureCollection.fromFeatures(
                features
        );
    }


    private List<org.maplibre.android.geometry.LatLng>
    equallySpacedTrackLabelPoints(
            RouteTrack track
    ) {
        List<org.maplibre.android.geometry.LatLng> result =
                new ArrayList<>();

        double totalM =
                debugTrackLength(
                        track
                );

        if (totalM <= 0.0) {
            return result;
        }

        if (totalM < DEBUG_TRACK_LABEL_SPACING_M) {
            org.maplibre.android.geometry.LatLng midpoint =
                    pointAtTrackDistance(
                            track,
                            totalM / 2.0
                    );

            if (midpoint != null) {
                result.add(
                        midpoint
                );
            }

            return result;
        }

        double distanceM =
                DEBUG_TRACK_LABEL_SPACING_M
                        / 2.0;

        while (distanceM < totalM) {
            org.maplibre.android.geometry.LatLng point =
                    pointAtTrackDistance(
                            track,
                            distanceM
                    );

            if (point != null) {
                result.add(
                        point
                );
            }

            distanceM +=
                    DEBUG_TRACK_LABEL_SPACING_M;
        }

        return result;
    }


    private double debugTrackLength(
            RouteTrack track
    ) {
        double totalM =
                0.0;

        for (int index = 0;
                index < track.points.size() - 1;
                index++) {

            totalM +=
                    GeoMath.distanceMeters(
                            track.points.get(
                                    index
                            ),
                            track.points.get(
                                    index + 1
                            )
                    );
        }

        return totalM;
    }


    private org.maplibre.android.geometry.LatLng pointAtTrackDistance(
            RouteTrack track,
            double targetM
    ) {
        double traversedM =
                0.0;

        for (int index = 0;
                index < track.points.size() - 1;
                index++) {

            org.maplibre.android.geometry.LatLng a =
                    track.points.get(
                            index
                    );

            org.maplibre.android.geometry.LatLng b =
                    track.points.get(
                            index + 1
                    );

            double segmentM =
                    GeoMath.distanceMeters(
                            a,
                            b
                    );

            if (segmentM <= 0.0) {
                continue;
            }

            if (traversedM + segmentM >= targetM) {
                double t =
                        (
                                targetM - traversedM
                        )
                                / segmentM;

                t =
                        Math.max(
                                0.0,
                                Math.min(
                                        1.0,
                                        t
                                )
                        );

                return new org.maplibre.android.geometry.LatLng(
                        a.getLatitude()
                                + (
                                b.getLatitude()
                                        - a.getLatitude()
                        )
                                * t,
                        a.getLongitude()
                                + (
                                b.getLongitude()
                                        - a.getLongitude()
                        )
                                * t
                );
            }

            traversedM +=
                    segmentM;
        }

        return track.points.get(
                track.points.size() - 1
        );
    }


    private Bitmap createDebugNodeBitmap(
            DebugTrackNode node
    ) {
        float density =
                context.getResources()
                        .getDisplayMetrics()
                        .density;

        int densityDpi =
                context.getResources()
                        .getDisplayMetrics()
                        .densityDpi;

        int size =
                Math.max(
                        1,
                        Math.round(
                                DEBUG_NODE_DIAMETER_DP
                                        * density
                        )
                );

        Bitmap bitmap =
                Bitmap.createBitmap(
                        size,
                        size,
                        Bitmap.Config.ARGB_8888
                );

        bitmap.setDensity(
                densityDpi
        );

        android.graphics.Canvas canvas =
                new android.graphics.Canvas(
                        bitmap
                );

        java.util.List<String> colours =
                node.routeColours();

        if (colours.isEmpty()) {
            colours =
                    java.util.Collections.singletonList(
                            "#2360A0"
                    );
        }

        float inset =
                Math.max(
                        1.0f,
                        density
                );

        android.graphics.RectF oval =
                new android.graphics.RectF(
                        inset,
                        inset,
                        size - inset,
                        size - inset
                );

        android.graphics.Paint paint =
                new android.graphics.Paint(
                        android.graphics.Paint.ANTI_ALIAS_FLAG
                );

        paint.setStyle(
                android.graphics.Paint.Style.FILL
        );

        float sweep =
                360.0f
                        / colours.size();

        float startAngle =
                -90.0f;

        for (String colour
                : colours) {

            paint.setColor(
                    debugParseColor(
                            colour
                    )
            );

            canvas.drawArc(
                    oval,
                    startAngle,
                    sweep,
                    true,
                    paint
            );

            startAngle +=
                    sweep;
        }

        android.graphics.Paint border =
                new android.graphics.Paint(
                        android.graphics.Paint.ANTI_ALIAS_FLAG
                );

        border.setStyle(
                android.graphics.Paint.Style.STROKE
        );

        border.setStrokeWidth(
                Math.max(
                        1.0f,
                        1.4f * density
                )
        );

        border.setColor(
                android.graphics.Color.WHITE
        );

        canvas.drawCircle(
                size / 2.0f,
                size / 2.0f,
                size / 2.0f
                        - inset,
                border
        );

        if (colours.size() > 1) {
            border.setStrokeWidth(
                    Math.max(
                            1.0f,
                            density
                    )
            );

            for (int index = 0;
                    index < colours.size();
                    index++) {

                double angle =
                        Math.toRadians(
                                -90.0
                                        + index
                                        * (
                                        360.0
                                                / colours.size()
                                )
                        );

                float cx =
                        size / 2.0f;

                float cy =
                        size / 2.0f;

                float radius =
                        size / 2.0f
                                - inset;

                canvas.drawLine(
                        cx,
                        cy,
                        cx
                                + radius
                                * (float)
                                Math.cos(
                                        angle
                                ),
                        cy
                                + radius
                                * (float)
                                Math.sin(
                                        angle
                                ),
                        border
                );
            }
        }

        return bitmap;
    }


    private Bitmap createTextBadgeBitmap(
            List<String> lines,
            String borderColour
    ) {
        if (lines == null
                || lines.isEmpty()) {

            lines =
                    java.util.Collections.singletonList(
                            "?"
                    );
        }

        float density =
                context.getResources()
                        .getDisplayMetrics()
                        .density;

        int densityDpi =
                context.getResources()
                        .getDisplayMetrics()
                        .densityDpi;

        android.graphics.Paint textPaint =
                new android.graphics.Paint(
                        android.graphics.Paint.ANTI_ALIAS_FLAG
                );

        textPaint.setColor(
                android.graphics.Color.BLACK
        );

        textPaint.setTextSize(
                10.0f
                        * density
        );

        textPaint.setTypeface(
                android.graphics.Typeface.create(
                        android.graphics.Typeface.MONOSPACE,
                        android.graphics.Typeface.BOLD
                )
        );

        android.graphics.Paint.FontMetrics metrics =
                textPaint.getFontMetrics();

        float lineHeight =
                metrics.descent
                        - metrics.ascent;

        float paddingX =
                5.0f
                        * density;

        float paddingY =
                3.0f
                        * density;

        float lineGap =
                1.5f
                        * density;

        float width =
                1.0f;

        for (String line
                : lines) {

            width =
                    Math.max(
                            width,
                            textPaint.measureText(
                                    line
                            )
                    );
        }

        int bitmapWidth =
                Math.max(
                        1,
                        Math.round(
                                width
                                        + paddingX
                                        * 2.0f
                        )
                );

        int bitmapHeight =
                Math.max(
                        1,
                        Math.round(
                                paddingY
                                        * 2.0f
                                        + lines.size()
                                        * lineHeight
                                        + Math.max(
                                        0,
                                        lines.size() - 1
                                )
                                        * lineGap
                        )
                );

        Bitmap bitmap =
                Bitmap.createBitmap(
                        bitmapWidth,
                        bitmapHeight,
                        Bitmap.Config.ARGB_8888
                );

        bitmap.setDensity(
                densityDpi
        );

        android.graphics.Canvas canvas =
                new android.graphics.Canvas(
                        bitmap
                );

        android.graphics.Paint background =
                new android.graphics.Paint(
                        android.graphics.Paint.ANTI_ALIAS_FLAG
                );

        background.setColor(
                android.graphics.Color.argb(
                        225,
                        255,
                        255,
                        255
                )
        );

        float radius =
                4.0f
                        * density;

        canvas.drawRoundRect(
                0.0f,
                0.0f,
                bitmapWidth,
                bitmapHeight,
                radius,
                radius,
                background
        );

        android.graphics.Paint border =
                new android.graphics.Paint(
                        android.graphics.Paint.ANTI_ALIAS_FLAG
                );

        border.setStyle(
                android.graphics.Paint.Style.STROKE
        );

        border.setStrokeWidth(
                Math.max(
                        1.0f,
                        1.2f * density
                )
        );

        border.setColor(
                debugParseColor(
                        borderColour
                )
        );

        canvas.drawRoundRect(
                border.getStrokeWidth() / 2.0f,
                border.getStrokeWidth() / 2.0f,
                bitmapWidth
                        - border.getStrokeWidth()
                        / 2.0f,
                bitmapHeight
                        - border.getStrokeWidth()
                        / 2.0f,
                radius,
                radius,
                border
        );

        float baseline =
                paddingY
                        - metrics.ascent;

        for (String line
                : lines) {

            canvas.drawText(
                    line,
                    paddingX,
                    baseline,
                    textPaint
            );

            baseline +=
                    lineHeight
                            + lineGap;
        }

        return bitmap;
    }


    private int debugParseColor(
            String value
    ) {
        try {
            return android.graphics.Color.parseColor(
                    value
            );

        } catch (RuntimeException error) {
            return android.graphics.Color.rgb(
                    35,
                    96,
                    160
            );
        }
    }


    private static final class DebugTrackNode {

        final org.maplibre.android.geometry.LatLng point;

        final java.util.LinkedHashMap<String, String> routeColours =
                new java.util.LinkedHashMap<>();

        final java.util.LinkedHashMap<String, java.util.LinkedHashSet<String>>
                incomingByRoute =
                new java.util.LinkedHashMap<>();

        final java.util.LinkedHashMap<String, java.util.LinkedHashSet<String>>
                outgoingByRoute =
                new java.util.LinkedHashMap<>();

        DebugTrackNode(
                org.maplibre.android.geometry.LatLng point
        ) {
            this.point =
                    point;
        }

        void addEndpoint(
                CaminoRoute route,
                RouteTrack track,
                boolean firstEndpoint
        ) {
            routeColours.put(
                    route.id,
                    track.color
            );

            java.util.LinkedHashMap<String, java.util.LinkedHashSet<String>>
                    target =
                    firstEndpoint
                            ? outgoingByRoute
                            : incomingByRoute;

            target.computeIfAbsent(
                    route.id,
                    ignored ->
                            new java.util.LinkedHashSet<>()
            ).add(
                    track.sectionId
            );
        }

        List<String> routeColours() {
            return new ArrayList<>(
                    routeColours.values()
            );
        }

        List<String> labelLines() {
            java.util.LinkedHashSet<String> routeIds =
                    new java.util.LinkedHashSet<>();

            routeIds.addAll(
                    incomingByRoute.keySet()
            );

            routeIds.addAll(
                    outgoingByRoute.keySet()
            );

            List<String> result =
                    new ArrayList<>();

            for (String routeId
                    : routeIds) {

                String incoming =
                        joinSections(
                                incomingByRoute.get(
                                        routeId
                                ),
                                "START"
                        );

                String outgoing =
                        joinSections(
                                outgoingByRoute.get(
                                        routeId
                                ),
                                "END"
                        );

                result.add(
                        incoming
                                + " - "
                                + outgoing
                );
            }

            if (result.isEmpty()) {
                result.add(
                        "?"
                );
            }

            return result;
        }

        private String joinSections(
                java.util.LinkedHashSet<String> sections,
                String fallback
        ) {
            if (sections == null
                    || sections.isEmpty()) {

                return fallback;
            }

            StringBuilder result =
                    new StringBuilder();

            for (String section
                    : sections) {

                if (result.length() > 0) {
                    result.append(
                            "/"
                    );
                }

                result.append(
                        section
                );
            }

            return result.toString();
        }
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
