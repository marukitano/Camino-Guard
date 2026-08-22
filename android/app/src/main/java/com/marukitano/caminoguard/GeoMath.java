package com.marukitano.caminoguard;

import org.maplibre.android.geometry.LatLng;

/**
 * Shared geographic math used by route, navigation and GPS components.
 *
 * Keep all spherical Earth calculations on the same radius and normalization
 * contract so callers cannot silently drift apart numerically.
 */
final class GeoMath {

    static final double EARTH_RADIUS_M =
            6371008.8;

    private GeoMath() {
    }

    static double distanceMeters(
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

        double dLat =
                lat2 - lat1;

        double dLon =
                Math.toRadians(
                        to.getLongitude()
                                - from.getLongitude()
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

    static double bearingDegrees(
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
                )
                        * Math.cos(
                        lat2
                );

        double x =
                Math.cos(
                        lat1
                )
                        * Math.sin(
                        lat2
                )
                        - Math.sin(
                        lat1
                )
                        * Math.cos(
                        lat2
                )
                        * Math.cos(
                        deltaLon
                );

        return normalizeDegrees(
                Math.toDegrees(
                        Math.atan2(
                                y,
                                x
                        )
                )
        );
    }

    static LatLng destination(
            LatLng from,
            double bearingDegrees,
            double meters
    ) {
        return destination(
                from.getLatitude(),
                from.getLongitude(),
                bearingDegrees,
                meters
        );
    }

    static LatLng destination(
            double latitude,
            double longitude,
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
                        latitude
                );

        double lon1 =
                Math.toRadians(
                        longitude
                );

        double lat2 =
                Math.asin(
                        Math.sin(
                                lat1
                        )
                                * Math.cos(
                                angularDistance
                        )
                                + Math.cos(
                                lat1
                        )
                                * Math.sin(
                                angularDistance
                        )
                                * Math.cos(
                                bearing
                        )
                );

        double lon2 =
                lon1
                        + Math.atan2(
                        Math.sin(
                                bearing
                        )
                                * Math.sin(
                                angularDistance
                        )
                                * Math.cos(
                                lat1
                        ),
                        Math.cos(
                                angularDistance
                        )
                                - Math.sin(
                                lat1
                        )
                                * Math.sin(
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

    static double normalizeDegrees(
            double value
    ) {
        value %=
                360.0;

        return value < 0.0
                ? value + 360.0
                : value;
    }

    static float normalizeDegrees(
            float value
    ) {
        value %=
                360.0f;

        return value < 0.0f
                ? value + 360.0f
                : value;
    }

    static double shortestAngleDegrees(
            double from,
            double to
    ) {
        double delta =
                normalizeDegrees(
                        to - from
                );

        return delta > 180.0
                ? delta - 360.0
                : delta;
    }

    static float shortestAngleDegrees(
            float from,
            float to
    ) {
        float delta =
                normalizeDegrees(
                        to - from
                );

        return delta > 180.0f
                ? delta - 360.0f
                : delta;
    }
}
