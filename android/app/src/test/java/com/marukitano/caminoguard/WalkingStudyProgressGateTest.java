package com.marukitano.caminoguard;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class WalkingStudyProgressGateTest {

    @Test
    public void coherentForwardWalkingIsAccepted() {
        assertTrue(
                WalkingStudyProgressGate.accepts(
                        61.0,
                        59.0,
                        57.0
                )
        );
    }


    @Test
    public void coherentReverseWalkingIsAccepted() {
        /*
         * Walking a Camino backwards is still real walking.
         */
        assertTrue(
                WalkingStudyProgressGate.accepts(
                        42.0,
                        41.0,
                        39.0
                )
        );
    }


    @Test
    public void stationaryGpsCloudIsRejected() {
        assertFalse(
                WalkingStudyProgressGate.accepts(
                        18.0,
                        7.0,
                        4.0
                )
        );
    }


    @Test
    public void forwardBackwardJitterIsRejected() {
        assertFalse(
                WalkingStudyProgressGate.accepts(
                        31.0,
                        29.0,
                        6.0
                )
        );
    }


    @Test
    public void projectionJumpIsRejected() {
        assertFalse(
                WalkingStudyProgressGate.accepts(
                        20.0,
                        105.0,
                        103.0
                )
        );
    }


    @Test
    public void twelveMetresNetProgressIsRequired() {
        assertFalse(
                WalkingStudyProgressGate.accepts(
                        13.0,
                        12.5,
                        11.9
                )
        );

        assertTrue(
                WalkingStudyProgressGate.accepts(
                        13.0,
                        12.5,
                        12.0
                )
        );
    }


    @Test
    public void nonFiniteInputIsRejected() {
        assertFalse(
                WalkingStudyProgressGate.accepts(
                        Double.NaN,
                        20.0,
                        20.0
                )
        );
    }
}
