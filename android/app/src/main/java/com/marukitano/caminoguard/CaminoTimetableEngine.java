package com.marukitano.caminoguard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Pure-Java Camino timetable core.
 *
 * Responsibilities:
 * - convert elapsed route time into wall-clock arrival times,
 * - keep route stops in canonical walking order,
 * - keep a passed stop visible for the first kilometre,
 * - afterwards replace the bottom village row by one compact
 *   "noch X km" distance row,
 * - show only a compact preview window:
 *     current/passed bottom row,
 *     the next two villages,
 *     and the destination,
 * - indicate whether intermediate villages are collapsed before the goal.
 *
 * It deliberately owns no Android UI, MapLibre, GPS, Pebble transport,
 * MeasurementEngine or controller behavior.
 */
final class CaminoTimetableEngine {

    static final double PASSED_STOP_KEEP_DISTANCE_M =
            1000.0;

    CaminoTimetableState build(
            List<CaminoTimetableStopPlan> stopPlans,
            int startMinutesOfDay,
            double currentChainageM
    ) {
        if (stopPlans == null
                || stopPlans.isEmpty()) {

            return new CaminoTimetableState(
                    Collections.emptyList(),
                    null,
                    Double.NaN,
                    false,
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
                            startMinutesOfDay
                    )
            );
        }

        int lastIndex =
                stopPlans.size()
                        - 1;

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

        if (passedIndex == lastIndex) {
            keepPassedStop =
                    true;
        }

        boolean showDistanceToNext =
                nextIndex >= 0
                        && passedIndex >= 0
                        && !keepPassedStop;

        int firstVisibleIndex;

        if (keepPassedStop) {
            firstVisibleIndex =
                    passedIndex;

        } else if (nextIndex >= 0) {
            firstVisibleIndex =
                    nextIndex;

        } else {
            firstVisibleIndex =
                    lastIndex;
        }

        firstVisibleIndex =
                Math.max(
                        0,
                        firstVisibleIndex
                );

        List<Integer> visibleIndices =
                buildVisibleIndices(
                        lastIndex,
                        firstVisibleIndex,
                        nextIndex,
                        showDistanceToNext
                );

        List<CaminoTimetableStop> visibleStops =
                new ArrayList<>(
                        visibleIndices.size()
                );

        for (Integer index
                : visibleIndices) {

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

        boolean hasHiddenStopsBeforeGoal =
                hasHiddenStopsBeforeGoal(
                        visibleIndices,
                        lastIndex,
                        showDistanceToNext
                );

        return new CaminoTimetableState(
                visibleStops,
                nextStop,
                distanceToNextM,
                showDistanceToNext,
                hasHiddenStopsBeforeGoal
        );
    }

    private List<Integer> buildVisibleIndices(
            int lastIndex,
            int firstVisibleIndex,
            int nextIndex,
            boolean showDistanceToNext
    ) {
        List<Integer> result =
                new ArrayList<>(
                        4
                );

        if (showDistanceToNext) {
            if (nextIndex < 0) {
                result.add(
                        lastIndex
                );
                return result;
            }

            addUnique(
                    result,
                    nextIndex
            );

            if (nextIndex + 1 < lastIndex) {
                addUnique(
                        result,
                        nextIndex + 1
                );
            }

            addUnique(
                    result,
                    lastIndex
            );

            return result;
        }

        addUnique(
                result,
                firstVisibleIndex
        );

        if (firstVisibleIndex + 1 <= lastIndex) {
            addUnique(
                    result,
                    firstVisibleIndex + 1
            );
        }

        if (firstVisibleIndex + 2 < lastIndex) {
            addUnique(
                    result,
                    firstVisibleIndex + 2
            );
        }

        addUnique(
                result,
                lastIndex
        );

        return result;
    }

    private boolean hasHiddenStopsBeforeGoal(
            List<Integer> visibleIndices,
            int lastIndex,
            boolean showDistanceToNext
    ) {
        if (visibleIndices.isEmpty()) {
            return false;
        }

        int lastShownNonGoalIndex =
                -1;

        if (showDistanceToNext) {
            if (visibleIndices.size() >= 2) {
                int candidate =
                        visibleIndices.get(
                                visibleIndices.size()
                                        - 2
                        );

                if (candidate < lastIndex) {
                    lastShownNonGoalIndex =
                            candidate;
                }
            }

        } else {
            for (int i =
                    visibleIndices.size() - 1;
                    i >= 0;
                    i--) {

                int candidate =
                        visibleIndices.get(
                                i
                        );

                if (candidate < lastIndex) {
                    lastShownNonGoalIndex =
                            candidate;
                    break;
                }
            }
        }

        return lastShownNonGoalIndex >= 0
                && lastIndex > lastShownNonGoalIndex + 1;
    }

    private void addUnique(
            List<Integer> target,
            int index
    ) {
        if (target.isEmpty()
                || target.get(
                target.size()
                        - 1
        ) != index) {

            target.add(
                    index
            );
        }
    }

    private CaminoTimetableStop toStop(
            CaminoTimetableStopPlan plan,
            int startMinutesOfDay
    ) {
        long elapsedMinutes =
                Math.round(
                        plan.elapsedSecondsFromStart
                                / 60.0
                );

        long arrival =
                startMinutesOfDay
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

            if (stop.chainageM < lastChainageM) {
                throw new IllegalArgumentException(
                        "Timetable stops must be ordered by chainage."
                );
            }

            if (stop.elapsedSecondsFromStart < lastElapsedSeconds) {
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
