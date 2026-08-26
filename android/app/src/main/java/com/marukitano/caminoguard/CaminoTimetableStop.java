package com.marukitano.caminoguard;

/**
 * Platform-neutral timetable stop ready for presentation on Android or Pebble.
 */
final class CaminoTimetableStop {

    final String name;
    final double chainageM;
    final int arrivalMinutesOfDay;

    CaminoTimetableStop(
            String name,
            double chainageM,
            int arrivalMinutesOfDay
    ) {
        this.name =
                name;

        this.chainageM =
                chainageM;

        this.arrivalMinutesOfDay =
                normalizeMinutesOfDay(
                        arrivalMinutesOfDay
                );
    }

    private static int normalizeMinutesOfDay(
            int minutes
    ) {
        int normalized =
                minutes
                        % (
                        24
                                * 60
                );

        if (normalized < 0) {
            normalized +=
                    24
                            * 60;
        }

        return normalized;
    }
}
