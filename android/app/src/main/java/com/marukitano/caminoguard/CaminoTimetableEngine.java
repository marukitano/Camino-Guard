package com.marukitano.caminoguard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Pure-Java Camino timetable core.
 *
 * Responsibilities:
 * - convert planned elapsed time into wall-clock arrival times,
 * - keep route stops in canonical walking order,
 * - hide a passed stop once the walker is 1 km beyond it,
 * - expose only the remaining distance to the next stop.
 *
 * It deliberately owns no Android UI, MapLibre, GPS, Pebble transport,
 * MeasurementEngine or controller behavior.
 */
final class CaminoTimetableEngine {

    static final double PASSED_STOP_KEEP_DISTANCE_M =
            1000.0;

    CaminoTimetableState build(
            List<CaminoTimetableStopPlan> stopPlans,
            int plannedStartMinutesOfDay,
            double currentChainageM
    ) {
        if (stopPlans == null
                || stopPlans.isEmpty()) {

            return new CaminoTimetableState(
                    Collections.emptyList(),
                    null,
                    Double.NaN,
                    false
            );
        }

        validateCanonicalOrder(
                stopPlans
        );

        double routeEndChainageM =
                stopPlans.get(
                        stopPlans.size()
                                - 1
                ).chainageM;

        double progressM =
                clamp(
                        currentChainageM,
                        0.0,
                        routeEndChainageM
                );

        List<CaminoTimetableStop> allStops =
                new ArrayList<>(
                        stopPlans.size()
                );

        for (CaminoTimetableStopPlan plan
                : stopPlans) {

            allStops.add(
                    toStop(
                            plan,
                            plannedStartMinutesOfDay
                    )
            );
        }

        int passedIndex =
                lastStopAtOrBehind(
                        stopPlans,
                        progressM
                );

        int nextIndex =
                firstStopAhead(
                        stopPlans,
                        progressM
                );

        boolean keepPassedStop =
                passedIndex >= 0
                        && progressM
                        - stopPlans.get(
                        passedIndex
                ).chainageM
                        < PASSED_STOP_KEEP_DISTANCE_M;

        if (passedIndex
                == stopPlans.size() - 1) {

            keepPassedStop =
                    true;
        }

        int firstVisibleIndex;

        if (keepPassedStop) {
            firstVisibleIndex =
                    passedIndex;

        } else if (nextIndex >= 0) {
            firstVisibleIndex =
                    nextIndex;

        } else {
            firstVisibleIndex =
                    stopPlans.size() - 1;
        }

        firstVisibleIndex =
                Math.max(
                        0,
                        firstVisibleIndex
                );

        List<CaminoTimetableStop> visibleStops =
                new ArrayList<>();

        for (int index =
                firstVisibleIndex;
                index < allStops.size();
                index++) {

            visibleStops.add(
                    allStops.get(
                            index
                    )
            );
        }

        CaminoTimetableStop nextStop =
                nextIndex >= 0
                        ? allStops.get(
                        nextIndex
                )
                        : null;

        double distanceToNextM =
                nextIndex >= 0
                        ? Math.max(
                        0.0,
                        stopPlans.get(
                                nextIndex
                        ).chainageM
                                - progressM
                )
                        : Double.NaN;

        boolean showDistanceToNext =
                nextStop != null
                        && passedIndex >= 0
                        && !keepPassedStop;

        return new CaminoTimetableState(
                visibleStops,
                nextStop,
                distanceToNextM,
                showDistanceToNext
        );
    }

    private CaminoTimetableStop toStop(
            CaminoTimetableStopPlan plan,
            int plannedStartMinutesOfDay
    ) {
        long elapsedMinutes =
                Math.round(
                        plan.elapsedSecondsFromStart
                                / 60.0
                );

        long arrival =
                plannedStartMinutesOfDay
                        + elapsedMinutes;

        return new CaminoTimetableStop(
                plan.name,
                plan.chainageM,
                normalizeMinutesOfDay(
                        arrival
                )
        );
    }

    private int lastStopAtOrBehind(
            List<CaminoTimetableStopPlan> stops,
            double progressM
    ) {
        int result =
                -1;

        for (int index =
                0;
                index < stops.size();
                index++) {

            if (stops.get(
                    index
            ).chainageM
                    <= progressM) {

                result =
                        index;

            } else {
                break;
            }
        }

        return result;
    }

    private int firstStopAhead(
            List<CaminoTimetableStopPlan> stops,
            double progressM
    ) {
        for (int index =
                0;
                index < stops.size();
                index++) {

            if (stops.get(
                    index
            ).chainageM
                    > progressM) {

                return index;
            }
        }

        return -1;
    }

    private void validateCanonicalOrder(
            List<CaminoTimetableStopPlan> stops
    ) {
        double lastChainageM =
                -1.0;

        double lastElapsedSeconds =
                -1.0;

        for (CaminoTimetableStopPlan stop
                : stops) {

            if (stop == null) {
                throw new IllegalArgumentException(
                        "Timetable stop must not be null."
                );
            }

            if (stop.chainageM
                    < lastChainageM) {

                throw new IllegalArgumentException(
                        "Timetable stops must be ordered by chainage."
                );
            }

            if (stop.elapsedSecondsFromStart
                    < lastElapsedSeconds) {

                throw new IllegalArgumentException(
                        "Timetable stop elapsed times must be monotonic."
                );
            }

            lastChainageM =
                    stop.chainageM;

            lastElapsedSeconds =
                    stop.elapsedSecondsFromStart;
        }
    }

    private double clamp(
            double value,
            double min,
            double max
    ) {
        if (!Double.isFinite(
                value
        )) {

            return min;
        }

        return Math.max(
                min,
                Math.min(
                        max,
                        value
                )
        );
    }

    private int normalizeMinutesOfDay(
            long minutes
    ) {
        long normalized =
                minutes
                        % (
                        24L
                                * 60L
                );

        if (normalized < 0L) {
            normalized +=
                    24L
                            * 60L;
        }

        return (
                int
                ) normalized;
    }
}
