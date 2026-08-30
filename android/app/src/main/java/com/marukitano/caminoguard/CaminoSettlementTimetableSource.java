package com.marukitano.caminoguard;

import android.content.Context;
import android.util.JsonReader;

import org.maplibre.android.geometry.LatLng;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Reads the SAME precomputed settlement marker source that is drawn on the map.
 *
 * Important: this class does not perform a second village detection. The
 * offline generator already decided which settlements belong to each Camino
 * section and wrote their snapped marker points to camino-settlements.geojson.
 * Here we only project those existing marker points onto the currently locked
 * MeasurementPath to obtain timetable chainage.
 */
final class CaminoSettlementTimetableSource {

    private static final String ASSET_PATH =
            "camino/camino-settlements.geojson";

    /*
     * The GeoJSON marker is already on the route. A small tolerance only
     * absorbs floating point / independently simplified geometry differences;
     * it must not turn this back into a generic "nearby village" search.
     */
    private static final double MAX_MARKER_PATH_OFFSET_M =
            12.0;

    private static final double DUPLICATE_CHAINAGE_M =
            25.0;

    private final Context context;

    private List<SettlementMarker> markers;

    private MeasurementPath cachedInputPath;
    private MeasurementPath cachedTimetablePath;


    CaminoSettlementTimetableSource(
            Context context
    ) {
        this.context =
                context.getApplicationContext();
    }


    MeasurementPath withSettlementStops(
            MeasurementPath path
    ) {
        if (path == null) {
            return null;
        }

        if (path == cachedInputPath
                && cachedTimetablePath != null) {

            return cachedTimetablePath;
        }

        MeasurementPath result =
                timetableCopy(
                        path
                );

        List<LocatedSettlement> located =
                locateSettlements(
                        path
                );

        result.timetableStops.addAll(
                CaminoTimetablePathStops.mergeSettlements(
                        path.distanceM,
                        path.timetableStops,
                        supplementalStops(
                                located
                        )
                )
        );

        cachedInputPath =
                path;

        cachedTimetablePath =
                result;

        return result;
    }


    private MeasurementPath timetableCopy(
            MeasurementPath source
    ) {
        MeasurementPath result =
                new MeasurementPath();

        result.distanceM =
                source.distanceM;

        result.startRoute =
                source.startRoute;

        result.endRoute =
                source.endRoute;

        result.profilePoints.addAll(
                source.profilePoints
        );

        return result;
    }


    private List<LocatedSettlement> locateSettlements(
            MeasurementPath path
    ) {
        if (path.profilePoints == null
                || path.profilePoints.size() < 2
                || !Double.isFinite(
                path.distanceM
        )
                || path.distanceM < 0.0) {

            return Collections.emptyList();
        }

        String routeGroupId =
                lockedRouteGroupId(
                        path
                );

        List<LocatedSettlement> result =
                new ArrayList<>();

        for (SettlementMarker marker
                : markers()) {

            if (marker == null
                    || marker.point == null
                    || !meaningful(
                    marker.name
            )) {

                continue;
            }

            if (routeGroupId != null
                    && meaningful(
                    marker.routeGroupId
            )
                    && !routeGroupId.equals(
                    marker.routeGroupId
            )) {

                continue;
            }

            Projection projection =
                    projectOntoProfile(
                            marker.point,
                            path.profilePoints
                    );

            if (projection == null
                    || !Double.isFinite(
                    projection.offsetM
            )
                    || projection.offsetM
                    > MAX_MARKER_PATH_OFFSET_M
                    || !Double.isFinite(
                    projection.chainageM
            )
                    || projection.chainageM < -0.5
                    || projection.chainageM
                    > path.distanceM + 0.5) {

                continue;
            }

            result.add(
                    new LocatedSettlement(
                            marker.name.trim(),
                            clamp(
                                    projection.chainageM,
                                    0.0,
                                    path.distanceM
                            ),
                            projection.offsetM
                    )
            );
        }

        result.sort(
                Comparator.comparingDouble(
                        stop ->
                                stop.chainageM
                )
        );

        return deduplicate(
                result
        );
    }


    private List<LocatedSettlement> deduplicate(
            List<LocatedSettlement> input
    ) {
        if (input.isEmpty()) {
            return input;
        }

        List<LocatedSettlement> result =
                new ArrayList<>();

        for (LocatedSettlement candidate
                : input) {

            int duplicateIndex =
                    -1;

            String candidateKey =
                    canonicalNameKey(
                            candidate.name
                    );

            for (int index =
                    result.size() - 1;
                    index >= 0;
                    index--) {

                LocatedSettlement previous =
                        result.get(
                                index
                        );

                if (candidate.chainageM
                        - previous.chainageM
                        > DUPLICATE_CHAINAGE_M) {

                    break;
                }

                if (candidateKey.equals(
                        canonicalNameKey(
                                previous.name
                        )
                )) {

                    duplicateIndex =
                            index;

                    break;
                }
            }

            if (duplicateIndex < 0) {
                result.add(
                        candidate
                );

                continue;
            }

            LocatedSettlement previous =
                    result.get(
                            duplicateIndex
                    );

            if (candidate.offsetM
                    < previous.offsetM) {

                result.set(
                        duplicateIndex,
                        candidate
                );
            }
        }

        result.sort(
                Comparator.comparingDouble(
                        stop ->
                                stop.chainageM
                )
        );

        return result;
    }


    private List<CaminoTimetablePathStops.SupplementalStop> supplementalStops(
            List<LocatedSettlement> located
    ) {
        List<CaminoTimetablePathStops.SupplementalStop> result =
                new ArrayList<>();

        if (located == null) {
            return result;
        }

        for (LocatedSettlement settlement
                : located) {

            if (settlement == null
                    || !meaningful(
                    settlement.name
            )
                    || !Double.isFinite(
                    settlement.chainageM
            )) {

                continue;
            }

            result.add(
                    new CaminoTimetablePathStops.SupplementalStop(
                            settlement.name,
                            settlement.chainageM
                    )
            );
        }

        return result;
    }


    private String lockedRouteGroupId(
            MeasurementPath path
    ) {
        if (path == null
                || path.startRoute == null
                || path.endRoute == null
                || !meaningful(
                path.startRoute.id
        )
                || !meaningful(
                path.endRoute.id
        )
                || !path.startRoute.id.equals(
                path.endRoute.id
        )) {

            return null;
        }

        return path.startRoute.id;
    }


    private List<SettlementMarker> markers() {
        if (markers != null) {
            return markers;
        }

        List<SettlementMarker> loaded =
                new ArrayList<>();

        try (InputStream input =
                     context.getAssets()
                             .open(
                                     ASSET_PATH
                             );
             InputStreamReader streamReader =
                     new InputStreamReader(
                             input,
                             StandardCharsets.UTF_8
                     );
             JsonReader reader =
                     new JsonReader(
                             streamReader
                     )) {

            readFeatureCollection(
                    reader,
                    loaded
            );

        } catch (IOException
                | RuntimeException ignored) {

            /*
             * Fail closed: the old start/goal timetable remains available.
             * A malformed optional settlement index must never break routing.
             */
            loaded.clear();
        }

        markers =
                Collections.unmodifiableList(
                        loaded
                );

        return markers;
    }


    private void readFeatureCollection(
            JsonReader reader,
            List<SettlementMarker> output
    ) throws IOException {
        reader.beginObject();

        while (reader.hasNext()) {
            String key =
                    reader.nextName();

            if ("features".equals(
                    key
            )) {

                reader.beginArray();

                while (reader.hasNext()) {
                    SettlementMarker marker =
                            readFeature(
                                    reader
                            );

                    if (marker != null) {
                        output.add(
                                marker
                        );
                    }
                }

                reader.endArray();

            } else {
                reader.skipValue();
            }
        }

        reader.endObject();
    }


    private SettlementMarker readFeature(
            JsonReader reader
    ) throws IOException {
        String name =
                null;

        String routeGroupId =
                null;

        double latitude =
                Double.NaN;

        double longitude =
                Double.NaN;

        reader.beginObject();

        while (reader.hasNext()) {
            String key =
                    reader.nextName();

            if ("properties".equals(
                    key
            )) {

                String[] properties =
                        readProperties(
                                reader
                        );

                name =
                        properties[0];

                routeGroupId =
                        properties[1];

            } else if ("geometry".equals(
                    key
            )) {

                double[] coordinates =
                        readPointGeometry(
                                reader
                        );

                longitude =
                        coordinates[0];

                latitude =
                        coordinates[1];

            } else {
                reader.skipValue();
            }
        }

        reader.endObject();

        if (!meaningful(
                name
        )
                || !Double.isFinite(
                latitude
        )
                || !Double.isFinite(
                longitude
        )) {

            return null;
        }

        return new SettlementMarker(
                name,
                routeGroupId,
                new LatLng(
                        latitude,
                        longitude
                )
        );
    }


    private String[] readProperties(
            JsonReader reader
    ) throws IOException {
        String name =
                null;

        String routeGroupId =
                null;

        reader.beginObject();

        while (reader.hasNext()) {
            String key =
                    reader.nextName();

            if ("name".equals(
                    key
            )) {

                name =
                        nextNullableString(
                                reader
                        );

            } else if ("route_group_id".equals(
                    key
            )) {

                routeGroupId =
                        nextNullableString(
                                reader
                        );

            } else {
                reader.skipValue();
            }
        }

        reader.endObject();

        return new String[]{
                name,
                routeGroupId
        };
    }


    private double[] readPointGeometry(
            JsonReader reader
    ) throws IOException {
        double longitude =
                Double.NaN;

        double latitude =
                Double.NaN;

        reader.beginObject();

        while (reader.hasNext()) {
            String key =
                    reader.nextName();

            if ("coordinates".equals(
                    key
            )) {

                reader.beginArray();

                if (reader.hasNext()) {
                    longitude =
                            reader.nextDouble();
                }

                if (reader.hasNext()) {
                    latitude =
                            reader.nextDouble();
                }

                while (reader.hasNext()) {
                    reader.skipValue();
                }

                reader.endArray();

            } else {
                reader.skipValue();
            }
        }

        reader.endObject();

        return new double[]{
                longitude,
                latitude
        };
    }


    private String nextNullableString(
            JsonReader reader
    ) throws IOException {
        if (reader.peek()
                == android.util.JsonToken.NULL) {

            reader.nextNull();
            return null;
        }

        return reader.nextString();
    }


    static Projection projectOntoProfile(
            LatLng marker,
            List<ProfilePoint> profile
    ) {
        if (marker == null
                || profile == null
                || profile.isEmpty()) {

            return null;
        }

        Projection best =
                null;

        for (ProfilePoint point
                : profile) {

            if (point == null
                    || point.point == null
                    || !Double.isFinite(
                    point.distanceM
            )) {

                continue;
            }

            double offsetM =
                    GeoMath.distanceMeters(
                            marker,
                            point.point
                    );

            if (best == null
                    || offsetM < best.offsetM) {

                best =
                        new Projection(
                                point.distanceM,
                                offsetM
                        );
            }
        }

        for (int index = 1;
                index < profile.size();
                index++) {

            ProfilePoint first =
                    profile.get(
                            index - 1
                    );

            ProfilePoint second =
                    profile.get(
                            index
                    );

            if (first == null
                    || second == null
                    || first.point == null
                    || second.point == null
                    || second.breakBefore
                    || !Double.isFinite(
                    first.distanceM
            )
                    || !Double.isFinite(
                    second.distanceM
            )
                    || second.distanceM
                    < first.distanceM) {

                continue;
            }

            Projection candidate =
                    projectOntoSegment(
                            marker,
                            first,
                            second
                    );

            if (candidate != null
                    && (best == null
                    || candidate.offsetM
                    < best.offsetM)) {

                best =
                        candidate;
            }
        }

        return best;
    }


    private static Projection projectOntoSegment(
            LatLng marker,
            ProfilePoint first,
            ProfilePoint second
    ) {
        double referenceLatitudeRad =
                Math.toRadians(
                        marker.getLatitude()
                );

        double latitudeScaleM =
                111_132.0;

        double longitudeScaleM =
                111_320.0
                        * Math.cos(
                        referenceLatitudeRad
                );

        double ax =
                (first.point.getLongitude()
                        - marker.getLongitude())
                        * longitudeScaleM;

        double ay =
                (first.point.getLatitude()
                        - marker.getLatitude())
                        * latitudeScaleM;

        double bx =
                (second.point.getLongitude()
                        - marker.getLongitude())
                        * longitudeScaleM;

        double by =
                (second.point.getLatitude()
                        - marker.getLatitude())
                        * latitudeScaleM;

        double dx =
                bx - ax;

        double dy =
                by - ay;

        double lengthSquared =
                dx * dx
                        + dy * dy;

        double fraction =
                lengthSquared <= 1.0e-9
                        ? 0.0
                        : -(
                        ax * dx
                                + ay * dy
                )
                        / lengthSquared;

        fraction =
                clamp(
                        fraction,
                        0.0,
                        1.0
                );

        double nearestX =
                ax
                        + fraction * dx;

        double nearestY =
                ay
                        + fraction * dy;

        double offsetM =
                Math.hypot(
                        nearestX,
                        nearestY
                );

        double chainageM =
                first.distanceM
                        + fraction
                        * (
                        second.distanceM
                                - first.distanceM
                );

        return new Projection(
                chainageM,
                offsetM
        );
    }


    static String canonicalNameKey(
            String value
    ) {
        return CaminoTimetablePathStops.canonicalNameKey(
                value
        );
    }


    private static boolean meaningful(
            String value
    ) {
        return value != null
                && !value.trim()
                .isEmpty();
    }


    private static double clamp(
            double value,
            double minimum,
            double maximum
    ) {
        return Math.max(
                minimum,
                Math.min(
                        maximum,
                        value
                )
        );
    }


    static final class Projection {

        final double chainageM;
        final double offsetM;


        Projection(
                double chainageM,
                double offsetM
        ) {
            this.chainageM =
                    chainageM;

            this.offsetM =
                    offsetM;
        }
    }


    private static final class SettlementMarker {

        final String name;
        final String routeGroupId;
        final LatLng point;


        SettlementMarker(
                String name,
                String routeGroupId,
                LatLng point
        ) {
            this.name =
                    name;

            this.routeGroupId =
                    routeGroupId;

            this.point =
                    point;
        }
    }


    private static final class LocatedSettlement {

        final String name;
        final double chainageM;
        final double offsetM;


        LocatedSettlement(
                String name,
                double chainageM,
                double offsetM
        ) {
            this.name =
                    name;

            this.chainageM =
                    chainageM;

            this.offsetM =
                    offsetM;
        }
    }
}
