package com.marukitano.caminoguard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable platform-neutral timetable snapshot.
 *
 * Android renders the compact railway-style preview from visibleStops. Pebble
 * can browse allStops without re-running ETA or route math, so both displays
 * remain backed by the same authoritative timetable calculation.
 */
final class CaminoTimetableState {

    final List<CaminoTimetableStop> visibleStops;
    final List<CaminoTimetableStop> allStops;
    final CaminoTimetableStop nextStop;
    final double currentChainageM;
    final boolean showDistanceToNext;
    final boolean hasHiddenStopsBeforeGoal;

    CaminoTimetableState(
            List<CaminoTimetableStop> visibleStops,
            CaminoTimetableStop nextStop,
            double currentChainageM,
            boolean showDistanceToNext,
            boolean hasHiddenStopsBeforeGoal
    ) {
        this(
                visibleStops,
                visibleStops,
                nextStop,
                currentChainageM,
                showDistanceToNext,
                hasHiddenStopsBeforeGoal
        );
    }

    CaminoTimetableState(
            List<CaminoTimetableStop> visibleStops,
            List<CaminoTimetableStop> allStops,
            CaminoTimetableStop nextStop,
            double currentChainageM,
            boolean showDistanceToNext,
            boolean hasHiddenStopsBeforeGoal
    ) {
        this.visibleStops =
                immutableCopy(
                        visibleStops
                );

        this.allStops =
                immutableCopy(
                        allStops
                );

        this.nextStop =
                nextStop;

        this.currentChainageM =
                currentChainageM;

        this.showDistanceToNext =
                showDistanceToNext;

        this.hasHiddenStopsBeforeGoal =
                hasHiddenStopsBeforeGoal;
    }

    boolean hasNextStop() {
        return nextStop != null;
    }

    private static List<CaminoTimetableStop> immutableCopy(
            List<CaminoTimetableStop> source
    ) {
        if (source == null
                || source.isEmpty()) {

            return Collections.emptyList();
        }

        return Collections.unmodifiableList(
                new ArrayList<>(
                        source
                )
        );
    }
}
