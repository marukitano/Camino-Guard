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
     * A stationary confirmation may START only at or below this speed. This
     * protects very slow real walking from being classified as a pause.
     */
    private static final float STATIONARY_START_MAX_SPEED_MPS =
            0.20f;

    /*
     * 0.35 m/s = 1.26 km/h.
     *
     * Once a genuine low-speed sample has started a stationary candidate,
     * ordinary GNSS jitter is allowed to rise into this band without throwing
     * away the complete five-second confirmation. Field testing showed that a
     * seated phone can otherwise hover around 0.7..1.0 km/h for minutes.
     */
    private static final float STATIONARY_HOLD_MAX_SPEED_MPS =
            0.35f;

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
                <= STATIONARY_START_MAX_SPEED_MPS) {

            movingCandidateSinceMs =
                    -1L;

            if (stationaryCandidateSinceMs < 0L) {
                stationaryCandidateSinceMs =
                        nowMs;
            }

            confirmStationaryIfReady(
                    nowMs
            );

            return state;
        }

        /*
         * Do not START a pause from this band, but do let an already-started
         * low-speed candidate survive typical stationary GNSS noise.
         */
        if (speedMps
                <= STATIONARY_HOLD_MAX_SPEED_MPS
                && stationaryCandidateSinceMs >= 0L) {

            movingCandidateSinceMs =
                    -1L;

            confirmStationaryIfReady(
                    nowMs
            );

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
         * Neutral band 0.35..0.45 m/s: preserve the established state, but a
         * value this high is enough to invalidate a pending pause candidate.
         */
        resetCandidates();

        return state;
    }


    void reset() {
        state =
                State.UNKNOWN;

        resetCandidates();
    }


    private void confirmStationaryIfReady(
            long nowMs
    ) {
        if (state != State.STATIONARY
                && stationaryCandidateSinceMs >= 0L
                && nowMs
                - stationaryCandidateSinceMs
                >= STATIONARY_CONFIRM_MS) {

            state =
                    State.STATIONARY;
        }
    }


    private void resetCandidates() {
        stationaryCandidateSinceMs =
                -1L;

        movingCandidateSinceMs =
                -1L;
    }
}
