package com.marukitano.caminoguard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;
import org.maplibre.android.geometry.LatLng;


public final class MeasurementPathProjectionTest {

    @Test
    public void midpointKeepsChainageElevationAndFraction() {
        MeasurementPath path =
                simplePath(
                        200.0,
                        1200.0,
                        1000.0
                );

        MeasurementPathProjection.Result result =
                MeasurementPathProjection.projectWithin(
                        path,
                        new LatLng(
                                47.0005,
                                8.0
                        ),
                        20.0
                );

        assertNotNull(
                result
        );

        /*
         * profile chainage deliberately remains 200..1200.
         * This proves existing Pebble/study semantics are untouched.
         */
        assertEquals(
                700.0,
                result.chainageM,
                0.5
        );

        assertEquals(
                150.0,
                result.elevationM,
                0.1
        );

        /*
         * Android UI progress is normalized independently of the profile's
         * absolute starting distance.
         */
        assertEquals(
                0.5,
                result.fraction,
                0.001
        );
    }


    @Test
    public void positionOutsideCorridorIsRejected() {
        MeasurementPath path =
                simplePath(
                        0.0,
                        1000.0,
                        1000.0
                );

        MeasurementPathProjection.Result result =
                MeasurementPathProjection.projectWithin(
                        path,
                        new LatLng(
                                47.0005,
                                8.001
                        ),
                        20.0
                );

        assertNull(
                result
        );
    }


    @Test
    public void breakBeforeNeverCreatesInventedProgressAcrossGap() {
        MeasurementPath path =
                new MeasurementPath();

        path.distanceM =
                1000.0;

        path.profilePoints.add(
                new ProfilePoint(
                        new LatLng(
                                47.0,
                                8.0
                        ),
                        0.0,
                        100.0,
                        false
                )
        );

        path.profilePoints.add(
                new ProfilePoint(
                        new LatLng(
                                47.001,
                                8.0
                        ),
                        1000.0,
                        200.0,
                        true
                )
        );

        assertNull(
                MeasurementPathProjection.projectWithin(
                        path,
                        new LatLng(
                                47.0005,
                                8.0
                        ),
                        20.0
                )
        );
    }


    @Test
    public void singleFiniteEndpointElevationKeepsHistoricalFallback() {
        MeasurementPath path =
                new MeasurementPath();

        path.distanceM =
                1000.0;

        path.profilePoints.add(
                new ProfilePoint(
                        new LatLng(
                                47.0,
                                8.0
                        ),
                        0.0,
                        123.0,
                        false
                )
        );

        path.profilePoints.add(
                new ProfilePoint(
                        new LatLng(
                                47.001,
                                8.0
                        ),
                        1000.0,
                        Double.NaN,
                        false
                )
        );

        MeasurementPathProjection.Result result =
                MeasurementPathProjection.projectWithin(
                        path,
                        new LatLng(
                                47.0005,
                                8.0
                        ),
                        20.0
                );

        assertNotNull(
                result
        );

        assertEquals(
                123.0,
                result.elevationM,
                0.001
        );
    }


    private MeasurementPath simplePath(
            double firstDistanceM,
            double lastDistanceM,
            double routeDistanceM
    ) {
        MeasurementPath path =
                new MeasurementPath();

        path.distanceM =
                routeDistanceM;

        path.profilePoints.add(
                new ProfilePoint(
                        new LatLng(
                                47.0,
                                8.0
                        ),
                        firstDistanceM,
                        100.0,
                        false
                )
        );

        path.profilePoints.add(
                new ProfilePoint(
                        new LatLng(
                                47.001,
                                8.0
                        ),
                        lastDistanceM,
                        200.0,
                        false
                )
        );

        return path;
    }
}
