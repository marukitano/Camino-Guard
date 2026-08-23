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

        addRawTrackDebugIcons(
                style,
                stageFeatures,
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


        /*
         * TEMPORARY DEBUG:
         * Show the raw canonical route_group_id + section_id for all original
         * track endpoints located at each currently rendered shell.
         *
         * No topology/outgoing-choice logic participates in these labels.
         */
        if (style.getLayer(
                "camino-stage-raw-track-labels"
        ) == null) {

            SymbolLayer rawTrackLabelLayer =
                    new SymbolLayer(
                            "camino-stage-raw-track-labels",
                            STAGE_SOURCE
                    );

            rawTrackLabelLayer.setProperties(
                    PropertyFactory.iconImage(
                            "{raw_track_icon}"
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
                                    34.0f
                            }
                    )
            );

            rawTrackLabelLayer.setMinZoom(
                    8.5f
            );

            style.addLayerAbove(
                    rawTrackLabelLayer,
                    STAGE_LAYER
            );
        }

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
