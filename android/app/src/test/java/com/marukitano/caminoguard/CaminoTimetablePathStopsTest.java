package com.marukitano.caminoguard;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;

public final class CaminoTimetablePathStopsTest {

    @Test
    public void routeShellsRemainAuthoritativeWithoutSettlements() {
        List<CaminoTimetablePathStop> result =
                CaminoTimetablePathStops.mergeSettlements(
                        3000.0,
                        Arrays.asList(
                                new CaminoTimetablePathStop(
                                        "beringen_start",
                                        0.0
                                ),
                                new CaminoTimetablePathStop(
                                        "beringen_mitte",
                                        1500.0
                                ),
                                new CaminoTimetablePathStop(
                                        "beringen_ziel",
                                        3000.0
                                )
                        ),
                        Collections.emptyList()
                );

        assertEquals(
                3,
                result.size()
        );

        assertEquals(
                "beringen_mitte",
                result.get(
                        1
                ).placeKey
        );

        assertEquals(
                1500.0,
                result.get(
                        1
                ).chainageM,
                0.001
        );
    }


    @Test
    public void matchingSettlementImprovesNameButKeepsRouteChainage() {
        List<CaminoTimetablePathStop> result =
                CaminoTimetablePathStops.mergeSettlements(
                        3000.0,
                        Arrays.asList(
                                new CaminoTimetablePathStop(
                                        "@start",
                                        0.0
                                ),
                                new CaminoTimetablePathStop(
                                        "santa_cruz_de_marchena",
                                        1000.0
                                ),
                                new CaminoTimetablePathStop(
                                        "@goal",
                                        3000.0
                                )
                        ),
                        Arrays.asList(
                                new CaminoTimetablePathStops.SupplementalStop(
                                        "Santa Cruz de Marchena",
                                        1012.0
                                ),
                                new CaminoTimetablePathStops.SupplementalStop(
                                        "Nueva Villa",
                                        2000.0
                                )
                        )
                );

        assertEquals(
                4,
                result.size()
        );

        assertEquals(
                "Santa Cruz de Marchena",
                result.get(
                        1
                ).placeKey
        );

        /*
         * Marker was at 1012 m; selected route stop stays at exactly 1000 m.
         */
        assertEquals(
                1000.0,
                result.get(
                        1
                ).chainageM,
                0.001
        );

        assertEquals(
                "Nueva Villa",
                result.get(
                        2
                ).placeKey
        );
    }


    @Test
    public void syntheticShellAndSettlementBecomeOneStop() {
        List<CaminoTimetablePathStop> result =
                CaminoTimetablePathStops.mergeSettlements(
                        2000.0,
                        Arrays.asList(
                                new CaminoTimetablePathStop(
                                        "@start",
                                        0.0
                                ),
                                new CaminoTimetablePathStop(
                                        "@branch:test:1",
                                        1000.0
                                ),
                                new CaminoTimetablePathStop(
                                        "@goal",
                                        2000.0
                                )
                        ),
                        Collections.singletonList(
                                new CaminoTimetablePathStops.SupplementalStop(
                                        "Beringen",
                                        1005.0
                                )
                        )
                );

        assertEquals(
                3,
                result.size()
        );

        assertEquals(
                "Beringen",
                result.get(
                        1
                ).placeKey
        );

        assertEquals(
                1000.0,
                result.get(
                        1
                ).chainageM,
                0.001
        );
    }


    @Test
    public void startGoalOnlyIsAValidRoute() {
        List<CaminoTimetablePathStop> result =
                CaminoTimetablePathStops.normalizeRouteStops(
                        5000.0,
                        Collections.emptyList()
                );

        assertEquals(
                2,
                result.size()
        );

        assertEquals(
                "@start",
                result.get(
                        0
                ).placeKey
        );

        assertEquals(
                "@goal",
                result.get(
                        1
                ).placeKey
        );
    }
}
