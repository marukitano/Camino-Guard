package com.marukitano.caminoguard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable platform-neutral timetable snapshot.
 *
 * Android can render this as the railway-style vertical line.
 * A Pebble bridge can serialize the same values without re-running route math.
 */
final class CaminoTimetableState {

    final List<CaminoTimetableStop> visibleStops;
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
        this.visibleStops =
                Collections.unmodifiableList(
                        new ArrayList<>(
                                visibleStops
                        )
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
}
