package com.marukitano.caminoguard;

import org.maplibre.android.geometry.LatLng;

import java.util.List;


/**
 * Headless projection onto an already resolved MeasurementPath.
 *
 * This is deliberately selected-path geometry. It does NOT ask the global
 * CaminoProjectionEngine for the nearest Camino.
 */
final class MeasurementPathProjection {

    static final class Result {

        final double offsetM;
        final double chainageM;
        final double elevationM;

        Result(
                double offsetM,
                double chainageM,
                double elevationM
        ) {
            this.offsetM =
                    offsetM;

            this.chainageM =
                    chainageM;

            this.elevationM =
                    elevationM;
        }
    }


    private MeasurementPathProjection() {
    }


    static Result projectWithin(
            MeasurementPath path,
            LatLng position,
            double maxOffsetM
    ) {
        if (path == null
                || position == null
                || path.profilePoints == null
                || path.profilePoints.size() < 2
                || !Double.isFinite(
                        maxOffsetM
                )
                || maxOffsetM < 0.0) {

            return null;
        }

        List<ProfilePoint> points =
                path.profilePoints;

        double bestOffsetM =
                Double.POSITIVE_INFINITY;

        double bestDistanceM =
                Double.NaN;

        double bestElevationM =
                Double.NaN;

        double userLat =
                position.getLatitude();

        double userLon =
                position.getLongitude();

        for (int index = 1;
                index < points.size();
                index++) {

            ProfilePoint a =
                    points.get(
                            index - 1
                    );

            ProfilePoint b =
                    points.get(
                            index
                    );

            if (a == null
                    || b == null
                    || a.point == null
                    || b.point == null
                    || b.breakBefore
                    || !Double.isFinite(
                            a.distanceM
                    )
                    || !Double.isFinite(
                            b.distanceM
                    )) {

                continue;
            }

            double refLatRad =
                    Math.toRadians(
                            (
                                    a.point.getLatitude()
                                            + b.point.getLatitude()
                                            + userLat
                            )
                                    / 3.0
                    );

            double lonScale =
                    Math.cos(
                            refLatRad
                    );

            double ax =
                    a.point.getLongitude()
                            * lonScale;

            double ay =
                    a.point.getLatitude();

            double bx =
                    b.point.getLongitude()
                            * lonScale;

            double by =
                    b.point.getLatitude();

            double px =
                    userLon
                            * lonScale;

            double py =
                    userLat;

            double dx =
                    bx - ax;

            double dy =
                    by - ay;

            double lengthSquared =
                    dx * dx
                            + dy * dy;

            double t =
                    lengthSquared <= 1e-15
                            ? 0.0
                            : (
                            (
                                    px - ax
                            )
                                    * dx
                                    + (
                                    py - ay
                            )
                                    * dy
                    )
                            / lengthSquared;

            t =
                    Math.max(
                            0.0,
                            Math.min(
                                    1.0,
                                    t
                            )
                    );

            LatLng projected =
                    new LatLng(
                            a.point.getLatitude()
                                    + (
                                    b.point.getLatitude()
                                            - a.point.getLatitude()
                            )
                                    * t,
                            a.point.getLongitude()
                                    + (
                                    b.point.getLongitude()
                                            - a.point.getLongitude()
                            )
                                    * t
                    );

            double offsetM =
                    GeoMath.distanceMeters(
                            position,
                            projected
                    );

            if (!Double.isFinite(
                    offsetM
            )
                    || offsetM >= bestOffsetM) {

                continue;
            }

            bestOffsetM =
                    offsetM;

            bestDistanceM =
                    a.distanceM
                            + (
                            b.distanceM
                                    - a.distanceM
                    )
                            * t;

            if (Double.isFinite(
                    a.elevationM
            )
                    && Double.isFinite(
                    b.elevationM
            )) {

                bestElevationM =
                        a.elevationM
                                + (
                                b.elevationM
                                        - a.elevationM
                        )
                                * t;

            } else if (Double.isFinite(
                    a.elevationM
            )) {

                bestElevationM =
                        a.elevationM;

            } else if (Double.isFinite(
                    b.elevationM
            )) {

                bestElevationM =
                        b.elevationM;

            } else {
                bestElevationM =
                        Double.NaN;
            }
        }

        if (!Double.isFinite(
                bestOffsetM
        )
                || !Double.isFinite(
                        bestDistanceM
                )
                || bestOffsetM > maxOffsetM) {

            return null;
        }

        return new Result(
                bestOffsetM,
                bestDistanceM,
                bestElevationM
        );
    }
}
