package com.marukitano.caminoguard;

/**
 * Owns the wall-clock anchor used by locked-navigation timetable ETAs.
 *
 * Live distance and speed may update much more frequently. Arrival-time
 * calibration deliberately follows a slower, motion-dependent cadence:
 *
 * MOVING     -> every 15 minutes
 * STATIONARY -> every 1 minute
 *
 * A motion-state transition or an explicit force refresh recalibrates
 * immediately.
 */
final class TimetableEtaClock {

    private static final long MOVING_REFRESH_MS =
            15L * 60L * 1000L;

    private static final long STATIONARY_REFRESH_MS =
            60L * 1000L;

    private boolean initialized;

    private boolean lastStationary;

    private long lastRefreshElapsedMs =
            Long.MIN_VALUE;

    private int startMinutes;

    private long revision;


    long revision() {
        return revision;
    }


    int startMinutes(
            long nowElapsedMs,
            int wallClockMinutes,
            double elapsedSecondsAtCurrent,
            boolean stationary,
            boolean forceRefresh
    ) {
        if (!Double.isFinite(
                elapsedSecondsAtCurrent
        )) {
            throw new IllegalArgumentException(
                    "elapsedSecondsAtCurrent must be finite"
            );
        }

        boolean motionChanged =
                initialized
                        && stationary
                        != lastStationary;

        long refreshIntervalMs =
                stationary
                        ? STATIONARY_REFRESH_MS
                        : MOVING_REFRESH_MS;

        boolean elapsedClockReset =
                initialized
                        && nowElapsedMs
                        < lastRefreshElapsedMs;

        boolean due =
                !initialized
                        || forceRefresh
                        || motionChanged
                        || elapsedClockReset
                        || nowElapsedMs
                        - lastRefreshElapsedMs
                        >= refreshIntervalMs;

        if (due) {
            startMinutes =
                    wallClockMinutes
                            - (int) Math.round(
                            elapsedSecondsAtCurrent
                                    / 60.0
                    );

            initialized =
                    true;

            lastStationary =
                    stationary;

            lastRefreshElapsedMs =
                    nowElapsedMs;

            revision++;
        }

        return startMinutes;
    }


    void reset() {
        initialized =
                false;

        lastStationary =
                false;

        lastRefreshElapsedMs =
                Long.MIN_VALUE;

        startMinutes =
                0;

        revision =
                0L;
    }
}
