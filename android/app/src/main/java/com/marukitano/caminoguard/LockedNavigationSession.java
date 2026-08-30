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
                routeChainageM(
                        path,
                        projection
                );

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
