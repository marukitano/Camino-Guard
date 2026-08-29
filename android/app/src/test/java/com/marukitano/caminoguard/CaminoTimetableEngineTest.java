package com.marukitano.caminoguard;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public final class CaminoTimetableEngineTest {

    private final CaminoTimetableEngine engine =
            new CaminoTimetableEngine();

    @Test
    public void atStart_keepsStartStopAndHidesDistanceRow() {
        CaminoTimetableState state =
                engine.build(
                        sampleStops(),
                        8 * 60,
                        0.0
                );

        assertEquals(
                3,
                state.visibleStops.size()
        );

        assertEquals(
                "Rioja",
                state.visibleStops.get(
                        0
                ).name
        );

        assertFalse(
                state.showDistanceToNext
        );

        assertNotNull(
                state.nextStop
        );

        assertEquals(
                "Santa Cruz",
                state.nextStop.name
        );
    }

    @Test
    public void oneKilometreAfterStart_hidesStartAndShowsOnlyDistanceToNext() {
        CaminoTimetableState state =
                engine.build(
                        sampleStops(),
                        8 * 60,
                        1000.0
                );

        assertEquals(
                2,
                state.visibleStops.size()
        );

        assertEquals(
                "Santa Cruz",
                state.visibleStops.get(
                        0
                ).name
        );

        assertTrue(
                state.showDistanceToNext
        );

        assertEquals(
                1000.0,
                state.currentChainageM,
                0.001
        );

        assertEquals(
                "Santa Cruz",
                state.nextStop.name
        );
    }

    @Test
    public void recentlyPassedVillage_staysVisibleForFirstKilometre() {
        CaminoTimetableState state =
                engine.build(
                        sampleStops(),
                        8 * 60,
                        8500.0
                );

        assertEquals(
                "Santa Cruz",
                state.visibleStops.get(
                        0
                ).name
        );

        assertFalse(
                state.showDistanceToNext
        );

        assertEquals(
                "Alboloduy",
                state.nextStop.name
        );
    }

    @Test
    public void kilometreAfterVillage_dropsVillageAndShowsRemainingDistance() {
        CaminoTimetableState state =
                engine.build(
                        sampleStops(),
                        8 * 60,
                        9000.0
                );

        assertEquals(
                1,
                state.visibleStops.size()
        );

        assertEquals(
                "Alboloduy",
                state.visibleStops.get(
                        0
                ).name
        );

        assertTrue(
                state.showDistanceToNext
        );

        assertEquals(
                9000.0,
                state.currentChainageM,
                0.001
        );
    }

    @Test
    public void arrivalTimesComeFromPlannedElapsedTime() {
        CaminoTimetableState state =
                engine.build(
                        sampleStops(),
                        8 * 60,
                        0.0
                );

        assertEquals(
                8 * 60,
                state.visibleStops.get(
                        0
                ).arrivalMinutesOfDay
        );

        assertEquals(
                10 * 60,
                state.visibleStops.get(
                        1
                ).arrivalMinutesOfDay
        );

        assertEquals(
                12 * 60,
                state.visibleStops.get(
                        2
                ).arrivalMinutesOfDay
        );
    }

    @Test
    public void arrivalTimeWrapsAcrossMidnight() {
        List<CaminoTimetableStopPlan> stops =
                Arrays.asList(
                        new CaminoTimetableStopPlan(
                                "Start",
                                0.0,
                                0.0
                        ),
                        new CaminoTimetableStopPlan(
                                "Night village",
                                5000.0,
                                90.0 * 60.0
                        )
                );

        CaminoTimetableState state =
                engine.build(
                        stops,
                        23 * 60 + 30,
                        0.0
                );

        assertEquals(
                60,
                state.visibleStops.get(
                        1
                ).arrivalMinutesOfDay
        );
    }

    @Test
    public void atDestination_keepsDestinationAndHasNoNextDistance() {
        CaminoTimetableState state =
                engine.build(
                        sampleStops(),
                        8 * 60,
                        15000.0
                );

        assertEquals(
                1,
                state.visibleStops.size()
        );

        assertEquals(
                "Alboloduy",
                state.visibleStops.get(
                        0
                ).name
        );

        assertFalse(
                state.hasNextStop()
        );

        assertFalse(
                state.showDistanceToNext
        );

        assertEquals(
                15000.0,
                state.currentChainageM,
                0.001
        );
    }

    @Test
    public void compactWindow_showsOnlyCurrentTwoNextAndGoal() {
        CaminoTimetableState state =
                engine.build(
                        longerStops(),
                        8 * 60,
                        0.0
                );

        assertEquals(
                4,
                state.visibleStops.size()
        );

        assertEquals(
                "Rioja",
                state.visibleStops.get(
                        0
                ).name
        );

        assertEquals(
                "Santa Cruz",
                state.visibleStops.get(
                        1
                ).name
        );

        assertEquals(
                "Bentarique",
                state.visibleStops.get(
                        2
                ).name
        );

        assertEquals(
                "Alboloduy",
                state.visibleStops.get(
                        3
                ).name
        );

        assertTrue(
                state.hasHiddenStopsBeforeGoal
        );
    }

    @Test
    public void afterBottomDrops_windowPromotesNextVillageAndKeepsGoal() {
        CaminoTimetableState state =
                engine.build(
                        longerStops(),
                        8 * 60,
                        1000.0
                );

        assertTrue(
                state.showDistanceToNext
        );

        assertEquals(
                3,
                state.visibleStops.size()
        );

        assertEquals(
                "Santa Cruz",
                state.visibleStops.get(
                        0
                ).name
        );

        assertEquals(
                "Bentarique",
                state.visibleStops.get(
                        1
                ).name
        );

        assertEquals(
                "Alboloduy",
                state.visibleStops.get(
                        2
                ).name
        );

        assertTrue(
                state.hasHiddenStopsBeforeGoal
        );
    }

    @Test
    public void whenNoIntermediateVillagesAreHidden_goalConnectionStaysSolid() {
        List<CaminoTimetableStopPlan> stops =
                Arrays.asList(
                        new CaminoTimetableStopPlan(
                                "Rioja",
                                0.0,
                                0.0
                        ),
                        new CaminoTimetableStopPlan(
                                "Santa Cruz",
                                8000.0,
                                2.0 * 60.0 * 60.0
                        ),
                        new CaminoTimetableStopPlan(
                                "Bentarique",
                                12000.0,
                                3.0 * 60.0 * 60.0
                        ),
                        new CaminoTimetableStopPlan(
                                "Alboloduy",
                                15000.0,
                                4.0 * 60.0 * 60.0
                        )
                );

        CaminoTimetableState state =
                engine.build(
                        stops,
                        8 * 60,
                        1000.0
                );

        assertFalse(
                state.hasHiddenStopsBeforeGoal
        );

        assertEquals(
                3,
                state.visibleStops.size()
        );
    }

    private List<CaminoTimetableStopPlan> sampleStops() {
        return Arrays.asList(
                new CaminoTimetableStopPlan(
                        "Rioja",
                        0.0,
                        0.0
                ),
                new CaminoTimetableStopPlan(
                        "Santa Cruz",
                        8000.0,
                        2.0 * 60.0 * 60.0
                ),
                new CaminoTimetableStopPlan(
                        "Alboloduy",
                        15000.0,
                        4.0 * 60.0 * 60.0
                )
        );
    }

    private List<CaminoTimetableStopPlan> longerStops() {
        return Arrays.asList(
                new CaminoTimetableStopPlan(
                        "Rioja",
                        0.0,
                        0.0
                ),
                new CaminoTimetableStopPlan(
                        "Santa Cruz",
                        8000.0,
                        2.0 * 60.0 * 60.0
                ),
                new CaminoTimetableStopPlan(
                        "Bentarique",
                        11000.0,
                        2.8 * 60.0 * 60.0
                ),
                new CaminoTimetableStopPlan(
                        "Nacimiento",
                        18000.0,
                        4.0 * 60.0 * 60.0
                ),
                new CaminoTimetableStopPlan(
                        "Alboloduy",
                        24000.0,
                        5.2 * 60.0 * 60.0
                )
        );
    }
}
