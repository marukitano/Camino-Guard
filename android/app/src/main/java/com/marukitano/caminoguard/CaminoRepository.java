package com.marukitano.caminoguard;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;
import org.maplibre.android.geometry.LatLng;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Single owner of persistent Camino route data.
 *
 * Responsibilities:
 * - read the canonical Camino JSON asset
 * - validate/parse routes and tracks
 * - apply the global colour contract
 * - orient track geometry consistently
 * - calculate static route chainage/length metadata
 *
 * It does NOT know about MapLibre layers, touch input, GPS, HUD or navigation.
 */
final class CaminoRepository {

    /*
     * Canonical Camino application data is immutable after repository loading.
     *
     * MainActivity and CaminoTrackingService live in the same Android process
     * and need the same route graph. Parsing the large canonical JSON twice
     * only duplicates CPU, temporary JSON memory and the complete geometry
     * object graph.
     *
     * Publish the graph only after loadUncached() finished all orientation,
     * static chainage and variant-path preparation.
     */
    private static final Object SHARED_ROUTES_LOCK =
            new Object();

    private static volatile List<CaminoRoute>
            sharedRoutes;

    private final Context context;

    CaminoRepository(
            Context context
    ) {
        this.context =
                context.getApplicationContext();
    }

    List<CaminoRoute> load()
            throws Exception {

        List<CaminoRoute> cached =
                sharedRoutes;

        if (cached != null) {
            return cached;
        }

        synchronized (SHARED_ROUTES_LOCK) {
            cached =
                    sharedRoutes;

            if (cached != null) {
                return cached;
            }

            List<CaminoRoute> loaded =
                    loadUncached();

            /*
             * Callers may build their own top-level lists, but the canonical
             * parsed route objects themselves are intentionally shared.
             */
            cached =
                    Collections.unmodifiableList(
                            new ArrayList<>(
                                    loaded
                            )
                    );

            /*
             * volatile publication also makes the completely initialized
             * object graph visible to the other process thread.
             */
            sharedRoutes =
                    cached;

            return cached;
        }
    }


    private List<CaminoRoute> loadUncached()
            throws Exception {

        JSONObject root =
                new JSONObject(
                        readAssetText(
                                CaminoConfig.get()
                                        .string(
                                                "data.caminoAsset"
                                        )
                        )
                );

        JSONArray routesJson =
                root.getJSONArray(
                        "routes"
                );

        List<CaminoRoute> routes =
                new ArrayList<>();

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
                            ),
                            routeJson.optString(
                                    "color",
                                    CaminoConfig.get()
                                            .string(
                                                    "routes.defaultColor"
                                            )
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

                String fromKey =
                        trackJson.optString(
                                "from_key",
                                ""
                        );

                String toKey =
                        trackJson.optString(
                                "to_key",
                                ""
                        );

                boolean pseudoFrom =
                        trackJson.optBoolean(
                                "pseudo_from",
                                false
                        );

                boolean pseudoTo =
                        trackJson.optBoolean(
                                "pseudo_to",
                                false
                        );

                JSONArray coordinates =
                        trackJson.getJSONArray(
                                "coordinates"
                        );

                List<LatLng> points =
                        new ArrayList<>();

                List<Double> elevations =
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

                    elevations.add(
                            coordinate.optDouble(
                                    2,
                                    Double.NaN
                            )
                    );
                }

                if (points.size() < 2) {
                    continue;
                }

                String trackColor =
                        CaminoColors.normalize(
                                trackJson.optString(
                                        "color",
                                        route.color
                                )
                        );

                RouteTrack parsedTrack =
                        new RouteTrack(
                                sectionId,
                                sectionNumber(
                                        sectionId
                                ),
                                points,
                                elevations,
                                trackColor,
                                CaminoColors.darken(
                                        trackColor
                                ),
                                fromKey,
                                toKey,
                                pseudoFrom,
                                pseudoTo
                        );

                /*
                 * All official CNIG tracks are renderable. Only the established
                 * primary "a" tracks participate in the current linear routing
                 * model. Variant branches stay out of route.tracks until the
                 * graph is made fully branch-aware.
                 */
                route.renderTracks.add(
                        parsedTrack
                );

                if (trackJson.optBoolean(
                        "routing_primary",
                        true
                )) {
                    route.tracks.add(
                            parsedTrack
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

            CaminoVariantPathBuilder.rebuild(
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
                    "keine Camino-Routen im kanonischen Datensatz"
            );
        }

        return routes;
    }

    private void prepareRouteGeometry(
            CaminoRoute route
    ) {
        if (route.tracks.size()
                >= 2) {

            RouteTrack firstTrack =
                    route.tracks.get(
                            0
                    );

            RouteTrack secondTrack =
                    route.tracks.get(
                            1
                    );

            LatLng firstStart =
                    firstTrack.points.get(
                            0
                    );

            LatLng firstEnd =
                    firstTrack.points.get(
                            firstTrack.points.size()
                                    - 1
                    );

            LatLng secondStart =
                    secondTrack.points.get(
                            0
                    );

            LatLng secondEnd =
                    secondTrack.points.get(
                            secondTrack.points.size()
                                    - 1
                    );

            double startToSecond =
                    Math.min(
                            GeoMath.distanceMeters(
                                    firstStart,
                                    secondStart
                            ),
                            GeoMath.distanceMeters(
                                    firstStart,
                                    secondEnd
                            )
                    );

            double endToSecond =
                    Math.min(
                            GeoMath.distanceMeters(
                                    firstEnd,
                                    secondStart
                            ),
                            GeoMath.distanceMeters(
                                    firstEnd,
                                    secondEnd
                            )
                    );

            if (startToSecond
                    < endToSecond) {

                Collections.reverse(
                        firstTrack.points
                );

                Collections.reverse(
                        firstTrack.elevations
                );
            }
        }

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

                if (GeoMath.distanceMeters(
                        previousEnd,
                        last
                ) < GeoMath.distanceMeters(
                        previousEnd,
                        first
                )) {

                    Collections.reverse(
                            track.points
                    );

                    Collections.reverse(
                            track.elevations
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
                     context.getAssets()
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

        } catch (RuntimeException error) {
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
                    GeoMath.distanceMeters(
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

    static String emptyToNull(
            String value
    ) {
        if (value == null) {
            return null;
        }

        String trimmed =
                value.trim();

        return trimmed.isEmpty()
                ? null
                : trimmed;
    }
}


final class CaminoRoute {

    final String id;
    final String name;
    final String color;
    final String highlightColor;
    /*
     * Primary tracks remain the established routing/measurement sequence.
     * renderTracks contains those same objects plus official CNIG variants.
     */
    final List<RouteTrack> tracks =
            new ArrayList<>();

    final List<RouteTrack> renderTracks =
            new ArrayList<>();

    final List<CaminoVariantPath> variantPaths =
            new ArrayList<>();

    CaminoRoute(
            String id,
            String name,
            String color
    ) {
        this.id =
                id;

        this.name =
                name;

        this.color =
                CaminoColors.normalize(
                        color
                );

        this.highlightColor =
                CaminoColors.darken(
                        this.color
                );
    }
}


final class RouteTrack {

    final String sectionId;
    final int order;
    final List<LatLng> points;
    final List<Double> elevations;
    final String color;
    final String highlightColor;
    final String fromKey;
    final String toKey;
    final boolean pseudoFrom;
    final boolean pseudoTo;

    final LatLng boundsCenter;
    final double boundsRadiusM;

    double baseChainageM;
    double lengthM;

    RouteTrack(
            String sectionId,
            int order,
            List<LatLng> points,
            List<Double> elevations,
            String color,
            String highlightColor,
            String fromKey,
            String toKey,
            boolean pseudoFrom,
            boolean pseudoTo
    ) {
        this.sectionId =
                sectionId;

        this.order =
                order;

        this.points =
                points;

        this.elevations =
                elevations;

        this.color =
                color;

        this.highlightColor =
                highlightColor;

        this.fromKey =
                CaminoRepository.emptyToNull(
                        fromKey
                );

        this.toKey =
                CaminoRepository.emptyToNull(
                        toKey
                );

        this.pseudoFrom =
                pseudoFrom;

        this.pseudoTo =
                pseudoTo;

        double minLat =
                Double.POSITIVE_INFINITY;

        double maxLat =
                Double.NEGATIVE_INFINITY;

        double minLon =
                Double.POSITIVE_INFINITY;

        double maxLon =
                Double.NEGATIVE_INFINITY;

        for (LatLng point
                : points) {

            minLat =
                    Math.min(
                            minLat,
                            point.getLatitude()
                    );

            maxLat =
                    Math.max(
                            maxLat,
                            point.getLatitude()
                    );

            minLon =
                    Math.min(
                            minLon,
                            point.getLongitude()
                    );

            maxLon =
                    Math.max(
                            maxLon,
                            point.getLongitude()
                    );
        }

        this.boundsCenter =
                new LatLng(
                        (minLat + maxLat)
                                / 2.0,
                        (minLon + maxLon)
                                / 2.0
                );

        double radiusM =
                0.0;

        for (LatLng point
                : points) {

            radiusM =
                    Math.max(
                            radiusM,
                            GeoMath.distanceMeters(
                                    boundsCenter,
                                    point
                            )
                    );
        }

        this.boundsRadiusM =
                radiusM;
    }
}
