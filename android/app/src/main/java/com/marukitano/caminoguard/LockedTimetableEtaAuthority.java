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
                )
        );
    }


    LockedTimetableEtaAuthority(
            PlanSource planSource
    ) {
        if (planSource == null) {
            throw new IllegalArgumentException(
                    "planSource must not be null"
            );
        }

        this.planSource =
                planSource;
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
        }

        double chainageM =
                lastGoodChainageM;

        /*
         * No trustworthy route position yet.
         *
         * Never invent km 0 merely to produce an ETA.
         */
        if (!Double.isFinite(
                chainageM
        )) {
            etaWasOnRoute =
                    onRoute;

            return latestState;
        }

        /*
         * OFF ROUTE freezes the complete authoritative timetable state.
         * Re-entry is detected through etaWasOnRoute and refreshes immediately.
         *
         * If no state exists yet, continue once so an initial lock can still
         * establish an ETA when a trustworthy chainage is already known.
         */
        if (!onRoute
                && latestState != null) {

            etaWasOnRoute =
                    false;

            return latestState;
        }

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
                        stationary,
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


    synchronized void reset() {
        pathVersion =
                Integer.MIN_VALUE;

        lastGoodChainageM =
                Double.NaN;

        etaWasOnRoute =
                false;

        etaPlans =
                null;

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
