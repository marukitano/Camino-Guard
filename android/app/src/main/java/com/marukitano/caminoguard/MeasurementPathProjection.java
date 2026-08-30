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

        /*
         * Normalized progress across the MeasurementPath profile.
         *
         * chainageM deliberately keeps its historical profile-distance
         * semantics for study and low-level projection consumers. fraction is
         * the 0..1 value used for route-relative UI/timetable progress.
         */
        final double fraction;

        Result(
                double offsetM,
                double chainageM,
                double elevationM,
                double fraction
        ) {
            this.offsetM =
                    offsetM;

            this.chainageM =
                    chainageM;

            this.elevationM =
                    elevationM;

            this.fraction =
                    fraction;
        }
    }


    static final class LockedResult {

        final Result route;
        final Result heightProfile;


        LockedResult(
                Result route,
                Result heightProfile
        ) {
            this.route =
                    route;

            this.heightProfile =
                    heightProfile;
        }
    }


    private MeasurementPathProjection() {
    }


    static LockedResult projectLockedWithin(
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

            return new LockedResult(
                    null,
                    null
            );
        }

        List<ProfilePoint> points =
                path.profilePoints;

        double routeBestOffsetM =
                Double.POSITIVE_INFINITY;

        double routeBestDistanceM =
                Double.NaN;

        double routeBestElevationM =
                Double.NaN;

        double heightBestOffsetM =
                Double.POSITIVE_INFINITY;

        double heightBestDistanceM =
                Double.NaN;

        double heightBestElevationM =
                Double.NaN;

        ProfilePoint first =
                points.get(
                        0
                );

        ProfilePoint last =
                points.get(
                        points.size() - 1
                );

        boolean heightProfileGeometryValid =
                first != null
                        && last != null
                        && Double.isFinite(
                                first.distanceM
                        )
                        && Double.isFinite(
                                last.distanceM
                        )
                        && Double.isFinite(
                                last.distanceM
                                        - first.distanceM
                        )
                        && last.distanceM
                        - first.distanceM
                        > 0.01;

        /*
         * Exactly one segment-geometry calculation per usable segment.
         *
         * Normal route projection accepts the segment regardless of elevation.
         * Height-profile projection additionally requires two finite endpoint
         * elevations, preserving its historical behaviour.
         */
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

            SegmentProjection segment =
                    projectToSegment(
                            position,
                            a.point,
                            b.point
                    );

            if (segment == null) {
                continue;
            }

            if (segment.offsetM
                    < routeBestOffsetM) {

                routeBestOffsetM =
                        segment.offsetM;

                routeBestDistanceM =
                        a.distanceM
                                + (
                                b.distanceM
                                        - a.distanceM
                        )
                                * segment.t;

                if (Double.isFinite(
                        a.elevationM
                )
                        && Double.isFinite(
                                b.elevationM
                        )) {

                    routeBestElevationM =
                            a.elevationM
                                    + (
                                    b.elevationM
                                            - a.elevationM
                            )
                                    * segment.t;

                } else if (Double.isFinite(
                        a.elevationM
                )) {

                    routeBestElevationM =
                            a.elevationM;

                } else if (Double.isFinite(
                        b.elevationM
                )) {

                    routeBestElevationM =
                            b.elevationM;

                } else {
                    routeBestElevationM =
                            Double.NaN;
                }
            }

            if (heightProfileGeometryValid
                    && Double.isFinite(
                            a.elevationM
                    )
                    && Double.isFinite(
                            b.elevationM
                    )
                    && segment.offsetM
                    < heightBestOffsetM) {

                heightBestOffsetM =
                        segment.offsetM;

                heightBestDistanceM =
                        a.distanceM
                                + segment.t
                                * (
                                b.distanceM
                                        - a.distanceM
                        );

                heightBestElevationM =
                        a.elevationM
                                + segment.t
                                * (
                                b.elevationM
                                        - a.elevationM
                        );
            }
        }

        Result routeResult =
                null;

        if (Double.isFinite(
                routeBestOffsetM
        )
                && Double.isFinite(
                        routeBestDistanceM
                )
                && routeBestOffsetM
                <= maxOffsetM) {

            routeResult =
                    new Result(
                            routeBestOffsetM,
                            routeBestDistanceM,
                            routeBestElevationM,
                            normalizedFraction(
                                    points,
                                    routeBestDistanceM
                            )
                    );
        }

        /*
         * Historical height-profile fallback:
         *
         * Only if NO finite-elevation segment was usable at all, find the
         * nearest individual finite profile point.
         */
        if (heightProfileGeometryValid
                && !Double.isFinite(
                        heightBestDistanceM
                )) {

            for (ProfilePoint point : points) {

                if (point == null
                        || point.point == null
                        || !Double.isFinite(
                                point.distanceM
                        )
                        || !Double.isFinite(
                                point.elevationM
                        )) {

                    continue;
                }

                double offsetM =
                        GeoMath.distanceMeters(
                                position,
                                point.point
                        );

                if (!Double.isFinite(
                        offsetM
                )
                        || offsetM
                        >= heightBestOffsetM) {

                    continue;
                }

                heightBestOffsetM =
                        offsetM;

                heightBestDistanceM =
                        point.distanceM;

                heightBestElevationM =
                        point.elevationM;
            }
        }

        Result heightResult =
                null;

        double heightFraction =
                normalizedFraction(
                        points,
                        heightBestDistanceM
                );

        if (heightProfileGeometryValid
                && Double.isFinite(
                        heightBestOffsetM
                )
                && Double.isFinite(
                        heightBestDistanceM
                )
                && Double.isFinite(
                        heightBestElevationM
                )
                && Double.isFinite(
                        heightFraction
                )
                && heightBestOffsetM
                <= maxOffsetM) {

            heightResult =
                    new Result(
                            heightBestOffsetM,
                            heightBestDistanceM,
                            heightBestElevationM,
                            heightFraction
                    );
        }

        return new LockedResult(
                routeResult,
                heightResult
        );
    }


    private static double normalizedFraction(
            List<ProfilePoint> points,
            double distanceM
    ) {
        if (points == null
                || points.size() < 2
                || !Double.isFinite(
                        distanceM
                )) {

            return Double.NaN;
        }

        ProfilePoint first =
                points.get(
                        0
                );

        ProfilePoint last =
                points.get(
                        points.size() - 1
                );

        if (first == null
                || last == null
                || !Double.isFinite(
                        first.distanceM
                )
                || !Double.isFinite(
                        last.distanceM
                )) {

            return Double.NaN;
        }

        double spanM =
                last.distanceM
                        - first.distanceM;

        if (!Double.isFinite(
                spanM
        )
                || spanM <= 0.01) {

            return Double.NaN;
        }

        double fraction =
                (
                        distanceM
                                - first.distanceM
                )
                        / spanM;

        return Math.max(
                0.0,
                Math.min(
                        1.0,
                        fraction
                )
        );
    }


    static Result projectHeightProfileWithin(
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

        ProfilePoint first =
                points.get(
                        0
                );

        ProfilePoint last =
                points.get(
                        points.size() - 1
                );

        if (first == null
                || last == null
                || !Double.isFinite(
                        first.distanceM
                )
                || !Double.isFinite(
                        last.distanceM
                )) {

            return null;
        }

        double spanM =
                last.distanceM
                        - first.distanceM;

        if (!Double.isFinite(
                spanM
        )
                || spanM <= 0.01) {

            return null;
        }

        double bestOffsetM =
                Double.POSITIVE_INFINITY;

        double bestDistanceM =
                Double.NaN;

        double bestElevationM =
                Double.NaN;

        /*
         * Preserve the old height-profile rule:
         * interpolate only across a segment whose BOTH endpoint elevations
         * are finite.
         */
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
                    )
                    || !Double.isFinite(
                            a.elevationM
                    )
                    || !Double.isFinite(
                            b.elevationM
                    )) {

                continue;
            }

            SegmentProjection segment =
                    projectToSegment(
                            position,
                            a.point,
                            b.point
                    );

            if (segment == null
                    || segment.offsetM
                    >= bestOffsetM) {

                continue;
            }

            bestOffsetM =
                    segment.offsetM;

            bestDistanceM =
                    a.distanceM
                            + segment.t
                            * (
                            b.distanceM
                                    - a.distanceM
                    );

            bestElevationM =
                    a.elevationM
                            + segment.t
                            * (
                            b.elevationM
                                    - a.elevationM
                    );
        }

        /*
         * Historical fallback: only when NO finite-elevation segment anywhere
         * was usable, snap to the nearest individual finite profile point.
         */
        if (!Double.isFinite(
                bestDistanceM
        )) {

            for (ProfilePoint point
                    : points) {

                if (point == null
                        || point.point == null
                        || !Double.isFinite(
                                point.distanceM
                        )
                        || !Double.isFinite(
                                point.elevationM
                        )) {

                    continue;
                }

                double offsetM =
                        GeoMath.distanceMeters(
                                position,
                                point.point
                        );

                if (!Double.isFinite(
                        offsetM
                )
                        || offsetM
                        >= bestOffsetM) {

                    continue;
                }

                bestOffsetM =
                        offsetM;

                bestDistanceM =
                        point.distanceM;

                bestElevationM =
                        point.elevationM;
            }
        }

        if (!Double.isFinite(
                bestDistanceM
        )
                || !Double.isFinite(
                        bestElevationM
                )
                || !Double.isFinite(
                        bestOffsetM
                )
                || bestOffsetM > maxOffsetM) {

            return null;
        }

        double fraction =
                (
                        bestDistanceM
                                - first.distanceM
                )
                        / spanM;

        fraction =
                Math.max(
                        0.0,
                        Math.min(
                                1.0,
                                fraction
                        )
                );

        return new Result(
                bestOffsetM,
                bestDistanceM,
                bestElevationM,
                fraction
        );
    }


    private static SegmentProjection projectToSegment(
            LatLng query,
            LatLng a,
            LatLng b
    ) {
        if (query == null
                || a == null
                || b == null) {

            return null;
        }

        double refLatRad =
                Math.toRadians(
                        (
                                a.getLatitude()
                                        + b.getLatitude()
                                        + query.getLatitude()
                        )
                                / 3.0
                );

        double lonScale =
                Math.cos(
                        refLatRad
                );

        double ax =
                a.getLongitude()
                        * lonScale;

        double ay =
                a.getLatitude();

        double bx =
                b.getLongitude()
                        * lonScale;

        double by =
                b.getLatitude();

        double px =
                query.getLongitude()
                        * lonScale;

        double py =
                query.getLatitude();

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

        double offsetM =
                GeoMath.distanceMeters(
                        query,
                        projected
                );

        if (!Double.isFinite(
                offsetM
        )) {

            return null;
        }

        return new SegmentProjection(
                t,
                offsetM
        );
    }


    private static final class SegmentProjection {

        final double t;
        final double offsetM;


        SegmentProjection(
                double t,
                double offsetM
        ) {
            this.t =
                    t;

            this.offsetM =
                    offsetM;
        }
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

            SegmentProjection segment =
                    projectToSegment(
                            position,
                            a.point,
                            b.point
                    );

            if (segment == null) {
                continue;
            }

            double t =
                    segment.t;

            double offsetM =
                    segment.offsetM;

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

        double fraction =
                Double.NaN;

        ProfilePoint first =
                points.get(
                        0
                );

        ProfilePoint last =
                points.get(
                        points.size() - 1
                );

        if (first != null
                && last != null
                && Double.isFinite(
                        first.distanceM
                )
                && Double.isFinite(
                        last.distanceM
                )) {

            double spanM =
                    last.distanceM
                            - first.distanceM;

            if (Double.isFinite(
                    spanM
            )
                    && spanM > 0.01) {

                fraction =
                        (
                                bestDistanceM
                                        - first.distanceM
                        )
                                / spanM;

                fraction =
                        Math.max(
                                0.0,
                                Math.min(
                                        1.0,
                                        fraction
                                )
                        );
            }
        }

        return new Result(
                bestOffsetM,
                bestDistanceM,
                bestElevationM,
                fraction
        );
    }
}
