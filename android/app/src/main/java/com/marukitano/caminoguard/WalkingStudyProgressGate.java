package com.marukitano.caminoguard;

/**
 * Conservative plausibility gate for the four-week walking study.
 *
 * GPS movement alone is not enough. A usable sample must also make coherent
 * progress along the explicitly locked MeasurementPath.
 */
final class WalkingStudyProgressGate {

    /*
     * A stationary GPS cloud must not become a walking sample merely because
     * individual fixes accumulated distance.
     */
    private static final double MIN_NET_ROUTE_PROGRESS_M =
            12.0;

    /*
     * Net progress must account for at least 75% of all absolute chainage
     * movement. Repeated forward/backward projection jitter therefore fails.
     */
    private static final double MIN_DIRECTIONALITY_RATIO =
            0.75;

    /*
     * GPS distance and travel along the locked route should describe the same
     * physical movement.
     */
    private static final double MIN_ROUTE_TO_GPS_RATIO =
            0.70;

    private static final double MAX_ROUTE_TO_GPS_RATIO =
            1.35;


    private WalkingStudyProgressGate() {
    }


    static boolean accepts(
            double gpsDistanceM,
            double routeTravelM,
            double routeNetProgressM
    ) {
        if (!Double.isFinite(
                gpsDistanceM
        )
                || !Double.isFinite(
                routeTravelM
        )
                || !Double.isFinite(
                routeNetProgressM
        )
                || gpsDistanceM <= 0.0
                || routeTravelM <= 0.0
                || routeNetProgressM
                < MIN_NET_ROUTE_PROGRESS_M
                || routeNetProgressM
                > routeTravelM + 0.01) {

            return false;
        }

        double directionality =
                routeNetProgressM
                        / routeTravelM;

        if (directionality
                < MIN_DIRECTIONALITY_RATIO) {

            return false;
        }

        double routeToGps =
                routeTravelM
                        / gpsDistanceM;

        return routeToGps
                >= MIN_ROUTE_TO_GPS_RATIO
                && routeToGps
                <= MAX_ROUTE_TO_GPS_RATIO;
    }
}
