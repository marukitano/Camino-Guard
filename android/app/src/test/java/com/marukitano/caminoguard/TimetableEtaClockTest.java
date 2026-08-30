package com.marukitano.caminoguard;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class TimetableEtaClockTest {

    @Test
    public void firstEvaluationCalibratesImmediately() {
        TimetableEtaClock clock =
                new TimetableEtaClock();

        assertEquals(
                590,
                clock.startMinutes(
                        1_000L,
                        600,
                        600.0,
                        false,
                        false
                )
        );
    }


    @Test
    public void movingKeepsEtaAnchorForFifteenMinutes() {
        TimetableEtaClock clock =
                new TimetableEtaClock();

        assertEquals(
                590,
                clock.startMinutes(
                        1_000L,
                        600,
                        600.0,
                        false,
                        false
                )
        );

        /*
         * If recalibrated here the result would be 593.
         * MOVING must retain the original 590 anchor.
         */
        assertEquals(
                590,
                clock.startMinutes(
                        1_000L + 5L * 60L * 1000L,
                        605,
                        720.0,
                        false,
                        false
                )
        );

        /*
         * At 15 minutes the calibration becomes due.
         * New result: 615 - 15 = 600.
         */
        assertEquals(
                600,
                clock.startMinutes(
                        1_000L + 15L * 60L * 1000L,
                        615,
                        900.0,
                        false,
                        false
                )
        );
    }


    @Test
    public void enteringStationaryRecalibratesImmediately() {
        TimetableEtaClock clock =
                new TimetableEtaClock();

        clock.startMinutes(
                1_000L,
                600,
                600.0,
                false,
                false
        );

        assertEquals(
                591,
                clock.startMinutes(
                        2_000L,
                        601,
                        600.0,
                        true,
                        false
                )
        );
    }


    @Test
    public void stationaryRecalibratesEveryMinute() {
        TimetableEtaClock clock =
                new TimetableEtaClock();

        assertEquals(
                590,
                clock.startMinutes(
                        1_000L,
                        600,
                        600.0,
                        true,
                        false
                )
        );

        /*
         * Not yet one minute: keep old anchor.
         */
        assertEquals(
                590,
                clock.startMinutes(
                        60_999L,
                        601,
                        600.0,
                        true,
                        false
                )
        );

        /*
         * Full minute reached: paused arrival moves one minute later.
         */
        assertEquals(
                591,
                clock.startMinutes(
                        61_000L,
                        601,
                        600.0,
                        true,
                        false
                )
        );
    }


    @Test
    public void leavingStationaryRecalibratesImmediately() {
        TimetableEtaClock clock =
                new TimetableEtaClock();

        clock.startMinutes(
                1_000L,
                600,
                600.0,
                true,
                false
        );

        assertEquals(
                592,
                clock.startMinutes(
                        2_000L,
                        602,
                        600.0,
                        false,
                        false
                )
        );
    }


    @Test
    public void forceRefreshIgnoresNormalCadence() {
        TimetableEtaClock clock =
                new TimetableEtaClock();

        clock.startMinutes(
                1_000L,
                600,
                600.0,
                false,
                false
        );

        assertEquals(
                595,
                clock.startMinutes(
                        2_000L,
                        605,
                        600.0,
                        false,
                        true
                )
        );
    }


    @Test
    public void resetRequiresFreshCalibration() {
        TimetableEtaClock clock =
                new TimetableEtaClock();

        clock.startMinutes(
                1_000L,
                600,
                600.0,
                false,
                false
        );

        clock.reset();

        assertEquals(
                610,
                clock.startMinutes(
                        2_000L,
                        620,
                        600.0,
                        false,
                        false
                )
        );
    }


    @Test
    public void revisionChangesOnlyWhenEtaIsRecalibrated() {
        TimetableEtaClock clock =
                new TimetableEtaClock();

        assertEquals(
                0L,
                clock.revision()
        );

        clock.startMinutes(
                1_000L,
                600,
                600.0,
                false,
                false
        );

        assertEquals(
                1L,
                clock.revision()
        );

        clock.startMinutes(
                1_000L + 5L * 60L * 1000L,
                605,
                720.0,
                false,
                false
        );

        assertEquals(
                1L,
                clock.revision()
        );

        clock.startMinutes(
                1_000L + 15L * 60L * 1000L,
                615,
                900.0,
                false,
                false
        );

        assertEquals(
                2L,
                clock.revision()
        );
    }


    @Test(expected = IllegalArgumentException.class)
    public void nonFiniteElapsedTimeIsRejected() {
        TimetableEtaClock clock =
                new TimetableEtaClock();

        clock.startMinutes(
                1_000L,
                600,
                Double.NaN,
                false,
                false
        );
    }
}
