package com.marukitano.caminoguard;

/**
 * Platform-neutral input stop for the Camino timetable.
 *
 * chainageM is the cumulative distance from the selected route start.
 * elapsedSecondsFromStart is the planned walking time from the selected
 * route start to this stop. Android will later derive both values from the
 * canonical selected MeasurementPath. The timetable core itself therefore
 * has no Android, MapLibre, GPS or drawing dependency.
 */
final class CaminoTimetableStopPlan {

    final String name;
    final double chainageM;
    final double elapsedSecondsFromStart;

    CaminoTimetableStopPlan(
            String name,
            double chainageM,
            double elapsedSecondsFromStart
    ) {
        if (name == null
                || name.trim().isEmpty()) {

            throw new IllegalArgumentException(
                    "Timetable stop name must not be empty."
            );
        }

        if (!Double.isFinite(
                chainageM
        )
                || chainageM < 0.0) {

            throw new IllegalArgumentException(
                    "Timetable stop chainage must be finite and >= 0."
            );
        }

        if (!Double.isFinite(
                elapsedSecondsFromStart
        )
                || elapsedSecondsFromStart < 0.0) {

            throw new IllegalArgumentException(
                    "Timetable stop elapsed time must be finite and >= 0."
            );
        }

        this.name =
                name.trim();

        this.chainageM =
                chainageM;

        this.elapsedSecondsFromStart =
                elapsedSecondsFromStart;
    }
}
