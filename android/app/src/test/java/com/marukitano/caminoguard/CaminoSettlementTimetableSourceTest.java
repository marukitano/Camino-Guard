package com.marukitano.caminoguard;

import org.junit.Test;
import org.maplibre.android.geometry.LatLng;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public final class CaminoSettlementTimetableSourceTest {

    @Test
    public void projectsExistingTrackMarkerToTimetableChainage() {
        ProfilePoint first =
                new ProfilePoint(
                        new LatLng(
                                37.0000,
                                -2.0000
                        ),
                        0.0,
                        100.0,
                        false
                );

        ProfilePoint second =
                new ProfilePoint(
                        new LatLng(
                                37.0000,
                                -1.9900
                        ),
                        1000.0,
                        100.0,
                        false
                );

        CaminoSettlementTimetableSource.Projection projection =
                CaminoSettlementTimetableSource.projectOntoProfile(
                        new LatLng(
                                37.0000,
                                -1.9950
                        ),
                        Arrays.asList(
                                first,
                                second
                        )
                );

        assertNotNull(
                projection
        );

        assertEquals(
                500.0,
                projection.chainageM,
                1.0
        );

        assertTrue(
                projection.offsetM < 1.0
        );
    }


    @Test
    public void endpointKeyMatchesCanonicalGeoJsonName() {
        assertEquals(
                CaminoSettlementTimetableSource.canonicalNameKey(
                        "almeria"
                ),
                CaminoSettlementTimetableSource.canonicalNameKey(
                        "Almería"
                )
        );

        assertEquals(
                CaminoSettlementTimetableSource.canonicalNameKey(
                        "santa_cruz_de_marchena"
                ),
                CaminoSettlementTimetableSource.canonicalNameKey(
                        "Santa Cruz de Marchena"
                )
        );
    }
}
