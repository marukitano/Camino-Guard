package com.marukitano.caminoguard;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class CaminoTimetablePlanBuilderTest {

    @Test
    public void build_keepsVillagesAndDropsSyntheticJunctions() {
        MeasurementPath path =
                new MeasurementPath();

        path.distanceM =
                15_000.0;

        path.timetableStops.add(
                new CaminoTimetablePathStop(
                        "rioja",
                        0.0
                )
        );

        path.timetableStops.add(
                new CaminoTimetablePathStop(
                        "@branch:MOZ:1",
                        4_000.0
                )
        );

        path.timetableStops.add(
                new CaminoTimetablePathStop(
                        "santa_cruz_de_marchena",
                        8_000.0
                )
        );

        path.timetableStops.add(
                new CaminoTimetablePathStop(
                        "@merge:MOZ:1",
                        10_000.0
                )
        );

        path.timetableStops.add(
                new CaminoTimetablePathStop(
                        "alboloduy",
                        15_000.0
                )
        );

        CaminoTimetablePlanBuilder builder =
                new CaminoTimetablePlanBuilder(
                        prefix ->
                                prefix.distanceM
                );

        List<CaminoTimetableStopPlan> stops =
                builder.build(
                        path
                );

        assertEquals(
                3,
                stops.size()
        );

        assertEquals(
                "Rioja",
                stops.get(
                        0
                ).name
        );

        assertEquals(
                "Santa Cruz de Marchena",
                stops.get(
                        1
                ).name
        );

        assertEquals(
                "Alboloduy",
                stops.get(
                        2
                ).name
        );

        assertEquals(
                8_000.0,
                stops.get(
                        1
                ).chainageM,
                0.001
        );

        assertEquals(
                8_000.0,
                stops.get(
                        1
                ).elapsedSecondsFromStart,
                0.001
        );
    }


    @Test
    public void syntheticEndpointsBecomeStartAndGoal() {
        MeasurementPath path =
                new MeasurementPath();

        path.distanceM =
                5_000.0;

        path.timetableStops.add(
                new CaminoTimetablePathStop(
                        "@branch:test:1",
                        0.0
                )
        );

        path.timetableStops.add(
                new CaminoTimetablePathStop(
                        "@merge:test:1",
                        5_000.0
                )
        );

        CaminoTimetablePlanBuilder builder =
                new CaminoTimetablePlanBuilder(
                        prefix ->
                                prefix.distanceM
                );

        List<CaminoTimetableStopPlan> stops =
                builder.build(
                        path
                );

        assertEquals(
                2,
                stops.size()
        );

        assertEquals(
                "Start",
                stops.get(
                        0
                ).name
        );

        assertEquals(
                "Ziel",
                stops.get(
                        1
                ).name
        );
    }


    @Test
    public void villageKeyRecognitionIsConservative() {
        assertTrue(
                CaminoTimetablePlanBuilder.isVillagePlaceKey(
                        "rioja"
                )
        );

        assertFalse(
                CaminoTimetablePlanBuilder.isVillagePlaceKey(
                        "@branch:MOZ:1"
                )
        );

        assertFalse(
                CaminoTimetablePlanBuilder.isVillagePlaceKey(
                        "fork_04b"
                )
        );
    }


    @Test
    public void displayNameKeepsSpanishConnectorsLowercase() {
        assertEquals(
                "Santa Cruz de Marchena",
                CaminoTimetablePlanBuilder.displayName(
                        "santa_cruz_de_marchena"
                )
        );

        assertEquals(
                "Castro del Río",
                CaminoTimetablePlanBuilder.displayName(
                        "castro_del_río"
                )
        );
    }
}
