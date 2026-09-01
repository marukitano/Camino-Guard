package com.marukitano.caminoguard;

import java.util.List;

/**
 * Single ETA authority for real locked navigation.
 *
 * It owns:
 * - the motion-aware ETA clock
 * - the frozen walking-performance stop plan
 * - the last trustworthy locked-route chainage
 * - the immutable timetable state consumed by Android and Pebble
 *
 * Consumers must not independently recalculate real-navigation ETA.
 */
final class LockedTimetableEtaAuthority {

    interface PlanSource {

        List<CaminoTimetableStopPlan> build(
                MeasurementPath path
        );

        double elapsedSecondsAtChainage(
                MeasurementPath path,
                double chainageM
        );
    }


    private final PlanSource planSource;

    private final WalkingPerformanceModel performanceModel;

    private double etaFlatSpeedKmh =
            Double.NaN;

    private final CaminoTimetableEngine engine =
            new CaminoTimetableEngine();

    private final TimetableEtaClock etaClock =
            new TimetableEtaClock();

    private int pathVersion =
            Integer.MIN_VALUE;

    private double lastGoodChainageM =
            Double.NaN;

    private boolean etaWasOnRoute;

    private List<CaminoTimetableStopPlan> etaPlans;

    private int etaStartMinutes;

    private CaminoTimetableState latestState;


    LockedTimetableEtaAuthority(
            WalkingPerformanceModel performanceModel
    ) {
        this(
                createPlanSource(
                        performanceModel
                ),
                performanceModel
        );
    }


    LockedTimetableEtaAuthority(
            PlanSource planSource
    ) {
        this(
                planSource,
                null
        );
    }


    private LockedTimetableEtaAuthority(
            PlanSource planSource,
            WalkingPerformanceModel performanceModel
    ) {
        if (planSource == null) {
            throw new IllegalArgumentException(
                    "planSource must not be null"
            );
        }

        this.planSource =
                planSource;

        this.performanceModel =
                performanceModel;
    }


    synchronized CaminoTimetableState update(
            int newPathVersion,
            MeasurementPath path,
            double projectedChainageM,
            boolean onRoute,
            boolean stationary,
            long nowElapsedMs,
            int wallClockMinutes
    ) {
        if (path == null) {
            reset();
            return null;
        }

        boolean pathChanged =
                pathVersion
                        != newPathVersion;

        if (pathChanged) {
            reset();

            pathVersion =
                    newPathVersion;
        }

        if (Double.isFinite(
                projectedChainageM
        )) {
            lastGoodChainageM =
                    clampChainage(
                            path,
                            projectedChainageM
                    );

        } else if (pathChanged) {
            /*
             * A freshly locked route may begin while the walker is still at a
             * hotel or another off-route location. ETA presentation should
             * nevertheless switch to live mode immediately: use the selected
             * route start as the initial chainage and the current wall clock as
             * its time anchor. The physical onRoute flag remains false, so this
             * does not pretend that the GPS position is already on the Camino.
             */
            lastGoodChainageM =
                    0.0;
        }

        double chainageM =
                lastGoodChainageM;

        if (!Double.isFinite(
                chainageM
        )) {
            etaWasOnRoute =
                    onRoute;

            return latestState;
        }

        /*
         * OFF ROUTE keeps the last trustworthy route chainage frozen, but
         * wall-clock delay must continue to move the ETA forward.
         *
         * Treat OFF ROUTE like a stationary route position for ETA cadence:
         * the same chainage is rebuilt once per minute until re-entry.
         */
        boolean etaStationary =
                stationary
                        || !onRoute;

        double elapsedSecondsAtCurrent =
                planSource.elapsedSecondsAtChainage(
                        path,
                        chainageM
                );

        if (!Double.isFinite(
                elapsedSecondsAtCurrent
        )) {
            etaWasOnRoute =
                    onRoute;

            return latestState;
        }

        boolean forceEtaRefresh =
                pathChanged
                        || (
                        onRoute
                                && !etaWasOnRoute
                );

        long revisionBefore =
                etaClock.revision();

        etaStartMinutes =
                etaClock.startMinutes(
                        nowElapsedMs,
                        wallClockMinutes,
                        elapsedSecondsAtCurrent,
                        etaStationary,
                        forceEtaRefresh
                );

        boolean etaRefreshed =
                etaClock.revision()
                        != revisionBefore;

        /*
         * WalkingPerformanceModel may learn continuously.
         *
         * The plan itself changes only when the ETA cadence allows it, so one
         * real-navigation ETA snapshot cannot drift differently on Android and
         * Pebble.
         */
        if (etaPlans == null
                || etaRefreshed) {

            etaPlans =
                    planSource.build(
                            path
                    );

            /*
             * Snapshot the personal near-flat reference speed from exactly the same
             * WalkingPerformanceModel state that produced this ETA plan.
             */
            etaFlatSpeedKmh =
                    performanceModel == null
                            ? Double.NaN
                            : performanceModel.referenceSpeedKmh();
        }

        if (etaPlans == null
                || etaPlans.size() < 2) {

            latestState =
                    null;

            etaWasOnRoute =
                    onRoute;

            return null;
        }

        latestState =
                engine.build(
                        etaPlans,
                        etaStartMinutes,
                        chainageM
                );

        etaWasOnRoute =
                onRoute;

        return latestState;
    }


    synchronized CaminoTimetableState latestState() {
        return latestState;
    }


    synchronized double latestFlatSpeedKmh() {
        return etaFlatSpeedKmh;
    }


    synchronized void reset() {
        pathVersion =
                Integer.MIN_VALUE;

        lastGoodChainageM =
                Double.NaN;

        etaWasOnRoute =
                false;

        etaPlans =
                null;

        etaFlatSpeedKmh =
                Double.NaN;

        etaStartMinutes =
                0;

        latestState =
                null;

        etaClock.reset();
    }


    static double routeChainageM(
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
         * Timetable stop chainage is relative to the selected route start.
         * Therefore use normalized MeasurementPath progress, not the raw
         * profile-distance origin stored in projection.chainageM.
         */
        return clampChainage(
                path,
                path.distanceM
                        * projection.fraction
        );
    }


    private static double clampChainage(
            MeasurementPath path,
            double chainageM
    ) {
        if (path != null
                && Double.isFinite(
                        path.distanceM
                )
                && path.distanceM >= 0.0) {

            return Math.max(
                    0.0,
                    Math.min(
                            path.distanceM,
                            chainageM
                    )
            );
        }

        return Math.max(
                0.0,
                chainageM
        );
    }


    private static PlanSource createPlanSource(
            WalkingPerformanceModel performanceModel
    ) {
        CaminoTimetablePlanBuilder builder =
                new CaminoTimetablePlanBuilder(
                        performanceModel
                );

        return new PlanSource() {

            @Override
            public List<CaminoTimetableStopPlan> build(
                    MeasurementPath path
            ) {
                return builder.build(
                        path
                );
            }


            @Override
            public double elapsedSecondsAtChainage(
                    MeasurementPath path,
                    double chainageM
            ) {
                return builder.elapsedSecondsAtChainage(
                        path,
                        chainageM
                );
            }
        };
    }
}
