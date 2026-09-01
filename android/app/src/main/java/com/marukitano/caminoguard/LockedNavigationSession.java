package com.marukitano.caminoguard;

import org.maplibre.android.geometry.LatLng;

/**
 * Runtime state of one explicitly locked navigation session.
 *
 * Owns selected-path projection reuse, OFF ROUTE state and the last
 * trustworthy route progress.
 *
 * UI, vibration and persistence remain controller responsibilities.
 */
final class LockedNavigationSession {

    private final double maxOffsetM;

    private boolean offRoute;

    private double lastGoodChainageM =
            Double.NaN;

    /*
     * One combined route/height projection for one exact path + position.
     */
    private boolean projectionCacheValid;

    private MeasurementPath projectionCachePath;

    private double projectionCacheLatitude =
            Double.NaN;

    private double projectionCacheLongitude =
            Double.NaN;

    private MeasurementPathProjection.LockedResult
            projectionCacheResult;


    LockedNavigationSession(
            double maxOffsetM
    ) {
        if (!Double.isFinite(
                maxOffsetM
        )
                || maxOffsetM < 0.0) {

            throw new IllegalArgumentException(
                    "maxOffsetM must be finite and >= 0"
            );
        }

        this.maxOffsetM =
                maxOffsetM;
    }


    MeasurementPathProjection.LockedResult projectionFor(
            MeasurementPath path,
            LatLng position
    ) {
        if (path == null
                || position == null) {

            return null;
        }

        double latitude =
                position.getLatitude();

        double longitude =
                position.getLongitude();

        if (projectionCacheValid
                && projectionCachePath == path
                && Double.doubleToLongBits(
                        projectionCacheLatitude
                )
                == Double.doubleToLongBits(
                        latitude
                )
                && Double.doubleToLongBits(
                        projectionCacheLongitude
                )
                == Double.doubleToLongBits(
                        longitude
                )) {

            return projectionCacheResult;
        }

        projectionCacheResult =
                MeasurementPathProjection.projectLockedWithin(
                        path,
                        position,
                        maxOffsetM
                );

        /*
         * A freshly locked route may start while the walker is still at a
         * hotel or another point outside the route corridor.
         *
         * Presentation must switch out of planning mode immediately and use
         * the selected route start as the ETA anchor instead of leaving the
         * old planned start time on screen. This fallback is deliberately NOT
         * a trustworthy GPS projection: its offset is greater than maxOffsetM,
         * so updateRouteState() continues to classify the physical position as
         * OFF ROUTE. Once one real on-route projection has been accepted,
         * later off-route periods keep the last trustworthy progress instead
         * of jumping back to the route start.
         */
        if (projectionCacheResult != null
                && projectionCacheResult.route == null
                && !Double.isFinite(
                        lastGoodChainageM
                )) {

            MeasurementPathProjection.Result routeStart =
                    routeStartPresentationProjection(
                            path
                    );

            if (routeStart != null) {
                projectionCacheResult =
                        new MeasurementPathProjection.LockedResult(
                                routeStart,
                                projectionCacheResult.heightProfile
                        );
            }
        }

        projectionCachePath =
                path;

        projectionCacheLatitude =
                latitude;

        projectionCacheLongitude =
                longitude;

        projectionCacheValid =
                true;

        return projectionCacheResult;
    }


    /*
     * Returns true only for the transition:
     *
     * ON ROUTE -> OFF ROUTE
     */
    boolean updateRouteState(
            MeasurementPath path,
            MeasurementPathProjection.Result projection
    ) {
        double chainageM =
                isTrustworthyRouteProjection(
                        projection
                )
                        ? routeChainageM(
                        path,
                        projection
                )
                        : Double.NaN;

        if (Double.isFinite(
                chainageM
        )) {
            lastGoodChainageM =
                    chainageM;

            offRoute =
                    false;

            return false;
        }

        boolean enteredOffRoute =
                !offRoute;

        offRoute =
                true;

        return enteredOffRoute;
    }


    void clearOffRoute() {
        offRoute =
                false;
    }


    boolean isOffRoute() {
        return offRoute;
    }


    double currentChainageM(
            MeasurementPath path,
            MeasurementPathProjection.Result projection,
            boolean locked
    ) {
        return currentChainageM(
                routeChainageM(
                        path,
                        projection
                ),
                locked
        );
    }


    /*
     * Also used by the development-only draggable GPS position.
     */
    double currentChainageM(
            double chainageM,
            boolean locked
    ) {
        if (Double.isFinite(
                chainageM
        )) {
            if (locked) {
                lastGoodChainageM =
                        chainageM;
            }

            return chainageM;
        }

        /*
         * Temporary OFF ROUTE must never become a false jump to km 0.
         */
        if (locked
                && Double.isFinite(
                        lastGoodChainageM
                )) {

            return lastGoodChainageM;
        }

        return 0.0;
    }


    void reset() {
        offRoute =
                false;

        lastGoodChainageM =
                Double.NaN;

        projectionCacheValid =
                false;

        projectionCachePath =
                null;

        projectionCacheLatitude =
                Double.NaN;

        projectionCacheLongitude =
                Double.NaN;

        projectionCacheResult =
                null;
    }


    private boolean isTrustworthyRouteProjection(
            MeasurementPathProjection.Result projection
    ) {
        return projection != null
                && Double.isFinite(
                        projection.offsetM
                )
                && projection.offsetM
                <= maxOffsetM;
    }


    private MeasurementPathProjection.Result
    routeStartPresentationProjection(
            MeasurementPath path
    ) {
        if (path == null
                || !Double.isFinite(
                        path.distanceM
                )
                || path.distanceM < 0.0
                || path.profilePoints == null
                || path.profilePoints.isEmpty()) {

            return null;
        }

        double profileStartDistanceM =
                0.0;

        double startElevationM =
                Double.NaN;

        ProfilePoint first =
                path.profilePoints.get(
                        0
                );

        if (first != null) {
            if (Double.isFinite(
                    first.distanceM
            )) {
                profileStartDistanceM =
                        first.distanceM;
            }

            if (Double.isFinite(
                    first.elevationM
            )) {
                startElevationM =
                        first.elevationM;
            }
        }

        if (!Double.isFinite(
                startElevationM
        )) {
            for (ProfilePoint point
                    : path.profilePoints) {

                if (point != null
                        && Double.isFinite(
                                point.elevationM
                        )) {

                    startElevationM =
                            point.elevationM;
                    break;
                }
            }
        }

        return new MeasurementPathProjection.Result(
                maxOffsetM + 1.0,
                profileStartDistanceM,
                startElevationM,
                0.0
        );
    }


    private static double routeChainageM(
            MeasurementPath path,
            MeasurementPathProjection.Result projection
    ) {
        if (path == null
                || projection == null
                || !Double.isFinite(
                        path.distanceM
                )
                || path.distanceM < 0.0
                || !Double.isFinite(
                        projection.fraction
                )) {

            return Double.NaN;
        }

        /*
         * Preserve the existing Android semantics exactly:
         * normalized fraction * MeasurementPath.distanceM.
         *
         * projection.chainageM can use an absolute profile-distance origin
         * that is not zero and therefore is deliberately not used here.
         */
        return Math.max(
                0.0,
                Math.min(
                        path.distanceM,
                        path.distanceM
                                * projection.fraction
                )
        );
    }
}
