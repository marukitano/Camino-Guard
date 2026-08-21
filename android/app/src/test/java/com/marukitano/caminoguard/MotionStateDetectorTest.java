package com.marukitano.caminoguard;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class MotionStateDetectorTest {

    @Test
    public void stationaryRequiresFullConfirmationWindow() {
        MotionStateDetector detector =
                new MotionStateDetector();

        assertEquals(
                MotionStateDetector.State.UNKNOWN,
                detector.updateMagnitudeSquared(
                        1.0f,
                        1000L
                )
        );

        assertEquals(
                MotionStateDetector.State.UNKNOWN,
                detector.updateMagnitudeSquared(
                        1.0f,
                        2499L
                )
        );

        assertEquals(
                MotionStateDetector.State.STATIONARY,
                detector.updateMagnitudeSquared(
                        1.0f,
                        2500L
                )
        );
    }

    @Test
    public void movingRequiresFullConfirmationWindow() {
        MotionStateDetector detector =
                new MotionStateDetector();

        assertEquals(
                MotionStateDetector.State.UNKNOWN,
                detector.updateMagnitudeSquared(
                        4.0f,
                        5000L
                )
        );

        assertEquals(
                MotionStateDetector.State.UNKNOWN,
                detector.updateMagnitudeSquared(
                        4.0f,
                        6499L
                )
        );

        assertEquals(
                MotionStateDetector.State.MOVING,
                detector.updateMagnitudeSquared(
                        4.0f,
                        6500L
                )
        );
    }

    @Test
    public void deadBandResetsPendingConfirmation() {
        MotionStateDetector detector =
                new MotionStateDetector();

        detector.updateMagnitudeSquared(
                1.0f,
                1000L
        );

        /*
         * From rmsSquared=1.0, one 49.0 sample yields
         * 0.96*1 + 0.04*49 = 2.92, sqrt ~= 1.709:
         * inside the 1.50..1.80 dead band.
         */
        detector.updateMagnitudeSquared(
                49.0f,
                2000L
        );

        /*
         * The EWMA does not drop below 1.50 immediately after the
         * dead-band sample. Seven zero samples move rmsSquared from
         * 2.92 to about 2.19, so a fresh stationary candidate starts
         * at 2500 ms.
         */
        for (int index = 0; index < 7; index++) {
            assertEquals(
                    MotionStateDetector.State.UNKNOWN,
                    detector.updateMagnitudeSquared(
                            0.0f,
                            2500L
                    )
            );
        }

        assertEquals(
                MotionStateDetector.State.UNKNOWN,
                detector.updateMagnitudeSquared(
                        0.0f,
                        3999L
                )
        );

        assertEquals(
                MotionStateDetector.State.STATIONARY,
                detector.updateMagnitudeSquared(
                        0.0f,
                        4000L
                )
        );
    }

    @Test
    public void currentStatePersistsInsideDeadBand() {
        MotionStateDetector detector =
                new MotionStateDetector();

        detector.updateMagnitudeSquared(
                1.0f,
                1000L
        );

        detector.updateMagnitudeSquared(
                1.0f,
                2500L
        );

        assertEquals(
                MotionStateDetector.State.STATIONARY,
                detector.state()
        );

        assertEquals(
                MotionStateDetector.State.STATIONARY,
                detector.updateMagnitudeSquared(
                        49.0f,
                        2600L
                )
        );
    }
}
