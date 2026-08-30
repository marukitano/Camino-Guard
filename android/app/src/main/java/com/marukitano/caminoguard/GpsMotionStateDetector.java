package com.marukitano.caminoguard;

/**
 * Low-frequency GPS-only motion classifier.
 *
 * GNSS-reported speed is deliberately preferred over point-to-point GPS
 * displacement because a stationary position can wander several metres while
 * Doppler-derived speed remains close to zero.
 *
 * No accelerometer, gyro or magnetometer participates here.
 */
final class GpsMotionStateDetector {

    enum State {
        UNKNOWN,
        MOVING,
        STATIONARY
    }

    /*
     * 0.20 m/s = 0.72 km/h.
     *
     * A speed must stay below this boundary for the complete confirmation
     * period before a real walking interval becomes a pause.
     */
    private static final float STATIONARY_MAX_SPEED_MPS =
            0.20f;

    /*
     * 0.45 m/s = 1.62 km/h.
     *
     * The gap between stationary and moving thresholds prevents ordinary GNSS
     * speed noise from rapidly toggling the state.
     */
    private static final float MOVING_MIN_SPEED_MPS =
            0.45f;

    private static final long STATIONARY_CONFIRM_MS =
            5_000L;

    private static final long MOVING_CONFIRM_MS =
            2_000L;

    private State state =
            State.UNKNOWN;

    private long stationaryCandidateSinceMs =
            -1L;

    private long movingCandidateSinceMs =
            -1L;


    State state() {
        return state;
    }


    State updateSpeed(
            float speedMps,
            long nowMs
    ) {
        if (Float.isNaN(speedMps)
                || Float.isInfinite(speedMps)
                || speedMps < 0.0f) {

            resetCandidates();
            return state;
        }

        if (speedMps
                <= STATIONARY_MAX_SPEED_MPS) {

            movingCandidateSinceMs =
                    -1L;

            if (stationaryCandidateSinceMs < 0L) {
                stationaryCandidateSinceMs =
                        nowMs;
            }

            if (state != State.STATIONARY
                    && nowMs
                    - stationaryCandidateSinceMs
                    >= STATIONARY_CONFIRM_MS) {

                state =
                        State.STATIONARY;
            }

            return state;
        }

        if (speedMps
                >= MOVING_MIN_SPEED_MPS) {

            stationaryCandidateSinceMs =
                    -1L;

            if (movingCandidateSinceMs < 0L) {
                movingCandidateSinceMs =
                        nowMs;
            }

            if (state != State.MOVING
                    && nowMs
                    - movingCandidateSinceMs
                    >= MOVING_CONFIRM_MS) {

                state =
                        State.MOVING;
            }

            return state;
        }

        /*
         * Hysteresis band 0.20..0.45 m/s:
         * preserve the already established state but require a fresh complete
         * confirmation after leaving the band.
         */
        resetCandidates();

        return state;
    }


    void reset() {
        state =
                State.UNKNOWN;

        resetCandidates();
    }


    private void resetCandidates() {
        stationaryCandidateSinceMs =
                -1L;

        movingCandidateSinceMs =
                -1L;
    }
}
