package com.marukitano.caminoguard;

/**
 * Pure motion-state detector for the tracking service.
 *
 * Thresholds and timing are production tuning values.
 */
final class MotionStateDetector {

    enum State {
        UNKNOWN,
        MOVING,
        STATIONARY
    }

    private static final float STATIONARY_RMS_THRESHOLD = 1.50f;
    private static final float MOVING_RMS_THRESHOLD = 1.80f;
    private static final long STATE_CONFIRM_MS = 1500L;
    private static final float RMS_ALPHA = 0.04f;

    private State state = State.UNKNOWN;
    private float rmsSquared;
    private long stationaryCandidateSinceMs = -1L;
    private long movingCandidateSinceMs = -1L;

    State state() {
        return state;
    }

    State updateMagnitudeSquared(
            float magnitudeSquared,
            long nowMs
    ) {
        rmsSquared =
                rmsSquared == 0.0f
                        ? magnitudeSquared
                        : (1.0f - RMS_ALPHA) * rmsSquared
                                + RMS_ALPHA * magnitudeSquared;

        float rms =
                (float) Math.sqrt(rmsSquared);

        if (rms < STATIONARY_RMS_THRESHOLD) {
            movingCandidateSinceMs = -1L;

            if (stationaryCandidateSinceMs < 0L) {
                stationaryCandidateSinceMs = nowMs;
            }

            if (state != State.STATIONARY
                    && nowMs - stationaryCandidateSinceMs
                            >= STATE_CONFIRM_MS) {
                state = State.STATIONARY;
            }

            return state;
        }

        if (rms > MOVING_RMS_THRESHOLD) {
            stationaryCandidateSinceMs = -1L;

            if (movingCandidateSinceMs < 0L) {
                movingCandidateSinceMs = nowMs;
            }

            if (state != State.MOVING
                    && nowMs - movingCandidateSinceMs
                            >= STATE_CONFIRM_MS) {
                state = State.MOVING;
            }

            return state;
        }

        /*
         * Dead band 1.50..1.80:
         * keep the current state, but require a fresh confirmation once a
         * threshold is crossed again.
         */
        stationaryCandidateSinceMs = -1L;
        movingCandidateSinceMs = -1L;

        return state;
    }
}
