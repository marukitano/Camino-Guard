package com.marukitano.caminoguard;

import org.junit.Test;
import org.maplibre.android.geometry.LatLng;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public final class CaminoTimetableVillageSourceTest {

    @Test
    public void canonicalTrackEndpointsExposeSameVillageKeysAsRouteData() {
        RouteTrack track =
                track(
                        "rioja",
                        "santa_cruz_de_marchena",
                        false,
                        false
                );

        ProjectionHit start =
                new ProjectionHit(
                        track.points.get(
                                0
                        ),
                        0.0,
                        0.0,
                        0,
                        0,
                        0.0
                );

        ProjectionHit end =
                new ProjectionHit(
                        track.points.get(
                                1
                        ),
                        1000.0,
                        0.0,
                        0,
                        0,
                        1.0
                );

        assertEquals(
                "rioja",
                MeasurementEngine.timetablePlaceKeyAtTrackEndpoint(
                        track,
                        start
                )
        );

        assertEquals(
                "santa_cruz_de_marchena",
                MeasurementEngine.timetablePlaceKeyAtTrackEndpoint(
                        track,
                        end
                )
        );
    }


    @Test
    public void pseudoEndpointsAreNotTimetableVillages() {
        RouteTrack track =
                track(
                        "rioja",
                        "junction",
                        false,
                        true
                );

        ProjectionHit end =
                new ProjectionHit(
                        track.points.get(
                                1
                        ),
                        1000.0,
                        0.0,
                        0,
                        0,
                        1.0
                );

        assertNull(
                MeasurementEngine.timetablePlaceKeyAtTrackEndpoint(
                        track,
                        end
                )
        );
    }


    @Test
    public void interiorPointIsNotInventedAsVillage() {
        RouteTrack track =
                track(
                        "rioja",
                        "santa_cruz_de_marchena",
                        false,
                        false
                );

        ProjectionHit middle =
                new ProjectionHit(
                        new LatLng(
                                37.005,
                                -2.005
                        ),
                        500.0,
                        0.0,
                        0,
                        0,
                        0.5
                );

        assertNull(
                MeasurementEngine.timetablePlaceKeyAtTrackEndpoint(
                        track,
                        middle
                )
        );
    }


    private RouteTrack track(
            String fromKey,
            String toKey,
            boolean pseudoFrom,
            boolean pseudoTo
    ) {
        return new RouteTrack(
                "01a",
                1,
                Arrays.asList(
                        new LatLng(
                                37.000,
                                -2.000
                        ),
                        new LatLng(
                                37.010,
                                -2.010
                        )
                ),
                Arrays.asList(
                        100.0,
                        110.0
                ),
                "#000000",
                "#000000",
                fromKey,
                toKey,
                pseudoFrom,
                pseudoTo
        );
    }
}
