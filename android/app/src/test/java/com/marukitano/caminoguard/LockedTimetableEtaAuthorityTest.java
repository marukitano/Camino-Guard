package com.marukitano.caminoguard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;


public final class LockedTimetableEtaAuthorityTest {

    @Test
    public void firstUpdateCreatesAuthoritativeState() {
        FakePlanSource source =
                new FakePlanSource();

        LockedTimetableEtaAuthority authority =
                new LockedTimetableEtaAuthority(
                        source
                );

        MeasurementPath path =
                path();

        CaminoTimetableState state =
                authority.update(
                        1,
                        path,
                        100.0,
                        true,
                        false,
                        1_000L,
                        600
                );

        assertNotNull(
                state
        );

        assertNotNull(
                state.nextStop
        );

        assertEquals(
                100.0,
                state.currentChainageM,
                0.001
        );

        assertEquals(
                654,
                state.nextStop.arrivalMinutesOfDay
        );

        assertEquals(
                1,
                source.buildCount
        );
    }


    @Test
    public void movingUpdatesProgressWithoutRebuildingEtaForFifteenMinutes() {
        FakePlanSource source =
                new FakePlanSource();

        LockedTimetableEtaAuthority authority =
                new LockedTimetableEtaAuthority(
                        source
                );

        MeasurementPath path =
                path();

        CaminoTimetableState first =
                authority.update(
                        1,
                        path,
                        100.0,
                        true,
                        false,
                        1_000L,
                        600
                );

        CaminoTimetableState fiveMinutesLater =
                authority.update(
                        1,
                        path,
                        200.0,
                        true,
                        false,
                        1_000L + 5L * 60L * 1000L,
                        605
                );

        assertEquals(
                200.0,
                fiveMinutesLater.currentChainageM,
                0.001
        );

        assertEquals(
                first.nextStop.arrivalMinutesOfDay,
                fiveMinutesLater.nextStop.arrivalMinutesOfDay
        );

        assertEquals(
                1,
                source.buildCount
        );

        CaminoTimetableState fifteenMinutesLater =
                authority.update(
                        1,
                        path,
                        300.0,
                        true,
                        false,
                        1_000L + 15L * 60L * 1000L,
                        615
                );

        assertEquals(
                300.0,
                fifteenMinutesLater.currentChainageM,
                0.001
        );

        assertEquals(
                657,
                fifteenMinutesLater.nextStop.arrivalMinutesOfDay
        );

        assertEquals(
                2,
                source.buildCount
        );
    }


    @Test
    public void stationaryTransitionAndEachMinutePushArrivalLater() {
        FakePlanSource source =
                new FakePlanSource();

        LockedTimetableEtaAuthority authority =
                new LockedTimetableEtaAuthority(
                        source
                );

        MeasurementPath path =
                path();

        CaminoTimetableState moving =
                authority.update(
                        1,
                        path,
                        100.0,
                        true,
                        false,
                        1_000L,
                        600
                );

        assertEquals(
                654,
                moving.nextStop.arrivalMinutesOfDay
        );

        CaminoTimetableState enteredStationary =
                authority.update(
                        1,
                        path,
                        100.0,
                        true,
                        true,
                        2_000L,
                        601
                );

        assertEquals(
                655,
                enteredStationary.nextStop.arrivalMinutesOfDay
        );

        CaminoTimetableState oneMinuteLater =
                authority.update(
                        1,
                        path,
                        100.0,
                        true,
                        true,
                        62_000L,
                        602
                );

        assertEquals(
                656,
                oneMinuteLater.nextStop.arrivalMinutesOfDay
        );

        assertEquals(
                3,
                source.buildCount
        );
    }


    @Test
    public void offRouteFreezesExactStateUntilReentry() {
        FakePlanSource source =
                new FakePlanSource();

        LockedTimetableEtaAuthority authority =
                new LockedTimetableEtaAuthority(
                        source
                );

        MeasurementPath path =
                path();

        CaminoTimetableState onRoute =
                authority.update(
                        1,
                        path,
                        200.0,
                        true,
                        false,
                        1_000L,
                        600
                );

        CaminoTimetableState offRoute =
                authority.update(
                        1,
                        path,
                        Double.NaN,
                        false,
                        false,
                        10L * 60L * 1000L,
                        610
                );

        assertSame(
                onRoute,
                offRoute
        );

        assertEquals(
                200.0,
                offRoute.currentChainageM,
                0.001
        );

        assertEquals(
                1,
                source.buildCount
        );

        CaminoTimetableState reentered =
                authority.update(
                        1,
                        path,
                        250.0,
                        true,
                        false,
                        10L * 60L * 1000L + 1_000L,
                        611
                );

        assertEquals(
                250.0,
                reentered.currentChainageM,
                0.001
        );

        /*
         * Re-entry forces a fresh ETA immediately instead of waiting for the
         * normal 15-minute moving interval.
         */
        assertEquals(
                2,
                source.buildCount
        );
    }


    @Test
    public void newPathVersionCannotReuseOldProgress() {
        FakePlanSource source =
                new FakePlanSource();

        LockedTimetableEtaAuthority authority =
                new LockedTimetableEtaAuthority(
                        source
                );

        MeasurementPath path =
                path();

        assertNotNull(
                authority.update(
                        1,
                        path,
                        400.0,
                        true,
                        false,
                        1_000L,
                        600
                )
        );

        assertNull(
                authority.update(
                        2,
                        path,
                        Double.NaN,
                        false,
                        false,
                        2_000L,
                        601
                )
        );
    }


    @Test
    public void timetableChainageUsesNormalizedRouteProgress() {
        MeasurementPath path =
                path();

        MeasurementPathProjection.Result projection =
                new MeasurementPathProjection.Result(
                        3.0,
                        700.0,
                        120.0,
                        0.5
                );

        /*
         * Raw profile chainage is 700 m, but timetable progress is
         * 50% of the 1000 m selected MeasurementPath -> 500 m.
         */
        assertEquals(
                500.0,
                LockedTimetableEtaAuthority.routeChainageM(
                        path,
                        projection
                ),
                0.001
        );
    }


    @Test
    public void resetRemovesAuthoritativeState() {
        LockedTimetableEtaAuthority authority =
                new LockedTimetableEtaAuthority(
                        new FakePlanSource()
                );

        authority.update(
                1,
                path(),
                100.0,
                true,
                false,
                1_000L,
                600
        );

        assertNotNull(
                authority.latestState()
        );

        authority.reset();

        assertNull(
                authority.latestState()
        );
    }


    private static MeasurementPath path() {
        MeasurementPath path =
                new MeasurementPath();

        path.distanceM =
                1000.0;

        return path;
    }


    private static final class FakePlanSource
            implements LockedTimetableEtaAuthority.PlanSource {

        int buildCount;


        @Override
        public List<CaminoTimetableStopPlan> build(
                MeasurementPath path
        ) {
            buildCount++;

            return Arrays.asList(
                    new CaminoTimetableStopPlan(
                            "Start",
                            0.0,
                            0.0
                    ),
                    new CaminoTimetableStopPlan(
                            "Ziel",
                            1000.0,
                            3600.0
                    )
            );
        }


        @Override
        public double elapsedSecondsAtChainage(
                MeasurementPath path,
                double chainageM
        ) {
            /*
             * 1000 m = 60 min in this deterministic test model.
             */
            return chainageM
                    * 3.6;
        }
    }
}
