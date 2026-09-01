package com.marukitano.caminoguard;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class GpsMotionStateDetectorTest {

    @Test
    public void stationaryRequiresFiveSecondConfirmation() {
        GpsMotionStateDetector detector =
                new GpsMotionStateDetector();

        assertEquals(
                GpsMotionStateDetector.State.UNKNOWN,
                detector.updateSpeed(
                        0.0f,
                        1_000L
                )
        );

        assertEquals(
                GpsMotionStateDetector.State.UNKNOWN,
                detector.updateSpeed(
                        0.10f,
                        5_999L
                )
        );

        assertEquals(
                GpsMotionStateDetector.State.STATIONARY,
                detector.updateSpeed(
                        0.10f,
                        6_000L
                )
        );
    }


    @Test
    public void movingRequiresTwoContinuousSeconds() {
        GpsMotionStateDetector detector =
                new GpsMotionStateDetector();

        assertEquals(
                GpsMotionStateDetector.State.UNKNOWN,
                detector.updateSpeed(
                        1.20f,
                        10_000L
                )
        );

        assertEquals(
                GpsMotionStateDetector.State.UNKNOWN,
                detector.updateSpeed(
                        1.10f,
                        11_999L
                )
        );

        assertEquals(
                GpsMotionStateDetector.State.MOVING,
                detector.updateSpeed(
                        1.10f,
                        12_000L
                )
        );
    }


    @Test
    public void hysteresisDoesNotTurnMovingIntoStationary() {
        GpsMotionStateDetector detector =
                new GpsMotionStateDetector();

        detector.updateSpeed(
                1.0f,
                1_000L
        );

        detector.updateSpeed(
                1.0f,
                3_000L
        );

        assertEquals(
                GpsMotionStateDetector.State.MOVING,
                detector.state()
        );

        assertEquals(
                GpsMotionStateDetector.State.MOVING,
                detector.updateSpeed(
                        0.30f,
                        20_000L
                )
        );
    }


    @Test
    public void lowSpeedGpsNoiseDoesNotBlockStationaryConfirmation() {
        GpsMotionStateDetector detector =
                new GpsMotionStateDetector();

        detector.updateSpeed(
                1.0f,
                1_000L
        );

        detector.updateSpeed(
                1.0f,
                3_000L
        );

        assertEquals(
                GpsMotionStateDetector.State.MOVING,
                detector.updateSpeed(
                        0.18f,
                        10_000L
                )
        );

        assertEquals(
                GpsMotionStateDetector.State.MOVING,
                detector.updateSpeed(
                        0.24f,
                        12_000L
                )
        );

        assertEquals(
                GpsMotionStateDetector.State.MOVING,
                detector.updateSpeed(
                        0.30f,
                        14_000L
                )
        );

        assertEquals(
                GpsMotionStateDetector.State.STATIONARY,
                detector.updateSpeed(
                        0.28f,
                        15_000L
                )
        );
    }


    @Test
    public void realMovementBreaksPendingStationaryConfirmation() {
        GpsMotionStateDetector detector =
                new GpsMotionStateDetector();

        detector.updateSpeed(
                1.0f,
                1_000L
        );

        detector.updateSpeed(
                1.0f,
                3_000L
        );

        detector.updateSpeed(
                0.18f,
                10_000L
        );

        detector.updateSpeed(
                0.28f,
                12_000L
        );

        assertEquals(
                GpsMotionStateDetector.State.MOVING,
                detector.updateSpeed(
                        0.50f,
                        13_000L
                )
        );

        assertEquals(
                GpsMotionStateDetector.State.MOVING,
                detector.updateSpeed(
                        0.18f,
                        15_000L
                )
        );

        assertEquals(
                GpsMotionStateDetector.State.STATIONARY,
                detector.updateSpeed(
                        0.18f,
                        20_000L
                )
        );
    }


    @Test
    public void invalidSpeedNeverInventsTransition() {
        GpsMotionStateDetector detector =
                new GpsMotionStateDetector();

        detector.updateSpeed(
                1.0f,
                1_000L
        );

        detector.updateSpeed(
                1.0f,
                3_000L
        );

        assertEquals(
                GpsMotionStateDetector.State.MOVING,
                detector.updateSpeed(
                        Float.NaN,
                        30_000L
                )
        );
    }


    @Test
    public void resetRequiresFreshClassification() {
        GpsMotionStateDetector detector =
                new GpsMotionStateDetector();

        detector.updateSpeed(
                0.0f,
                1_000L
        );

        detector.updateSpeed(
                0.0f,
                6_000L
        );

        detector.reset();

        assertEquals(
                GpsMotionStateDetector.State.UNKNOWN,
                detector.state()
        );
    }
}
