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



    @Test
    public void heightProfileRequiresTwoFiniteElevationsForSegmentInterpolation() {
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

        /*
         * Normal projection preserves its historical one-endpoint fallback.
         */
        MeasurementPathProjection.Result normal =
                MeasurementPathProjection.projectWithin(
                        path,
                        new LatLng(
                                47.0005,
                                8.0
                        ),
                        100.0
                );

        assertNotNull(
                normal
        );

        assertEquals(
                500.0,
                normal.chainageM,
                1.0
        );

        /*
         * Height-profile projection preserves the older controller behaviour:
         * no interpolated segment without two finite elevations. It therefore
         * falls back to the finite endpoint.
         */
        MeasurementPathProjection.Result profile =
                MeasurementPathProjection.projectHeightProfileWithin(
                        path,
                        new LatLng(
                                47.0005,
                                8.0
                        ),
                        100.0
                );

        assertNotNull(
                profile
        );

        assertEquals(
                0.0,
                profile.chainageM,
                0.001
        );

        assertEquals(
                123.0,
                profile.elevationM,
                0.001
        );
    }


    @Test
    public void heightProfileDoesNotCrossBreakBeforeGap() {
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
                MeasurementPathProjection.projectHeightProfileWithin(
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
    public void lockedProjectionMatchesBothExistingProjectionModes() {
        MeasurementPath path =
                simplePath(
                        200.0,
                        1200.0,
                        1000.0
                );

        LatLng position =
                new LatLng(
                        47.0005,
                        8.0
                );

        MeasurementPathProjection.Result route =
                MeasurementPathProjection.projectWithin(
                        path,
                        position,
                        20.0
                );

        MeasurementPathProjection.Result height =
                MeasurementPathProjection.projectHeightProfileWithin(
                        path,
                        position,
                        20.0
                );

        MeasurementPathProjection.LockedResult combined =
                MeasurementPathProjection.projectLockedWithin(
                        path,
                        position,
                        20.0
                );

        assertSameProjection(
                route,
                combined.route
        );

        assertSameProjection(
                height,
                combined.heightProfile
        );
    }


    @Test
    public void lockedProjectionPreservesHeightEndpointFallback() {
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

        LatLng position =
                new LatLng(
                        47.0005,
                        8.0
                );

        MeasurementPathProjection.LockedResult combined =
                MeasurementPathProjection.projectLockedWithin(
                        path,
                        position,
                        100.0
                );

        assertNotNull(
                combined.route
        );

        assertNotNull(
                combined.heightProfile
        );

        assertEquals(
                500.0,
                combined.route.chainageM,
                1.0
        );

        assertEquals(
                0.0,
                combined.heightProfile.chainageM,
                0.001
        );

        assertEquals(
                123.0,
                combined.heightProfile.elevationM,
                0.001
        );
    }


    @Test
    public void lockedProjectionPreservesBreakBeforeGap() {
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

        MeasurementPathProjection.LockedResult combined =
                MeasurementPathProjection.projectLockedWithin(
                        path,
                        new LatLng(
                                47.0005,
                                8.0
                        ),
                        20.0
                );

        assertNull(
                combined.route
        );

        assertNull(
                combined.heightProfile
        );
    }


    private void assertSameProjection(
            MeasurementPathProjection.Result expected,
            MeasurementPathProjection.Result actual
    ) {
        if (expected == null) {
            assertNull(
                    actual
            );
            return;
        }

        assertNotNull(
                actual
        );

        assertEquals(
                expected.offsetM,
                actual.offsetM,
                0.001
        );

        assertEquals(
                expected.chainageM,
                actual.chainageM,
                0.001
        );

        assertEquals(
                Double.doubleToLongBits(
                        expected.elevationM
                ),
                Double.doubleToLongBits(
                        actual.elevationM
                )
        );

        assertEquals(
                Double.doubleToLongBits(
                        expected.fraction
                ),
                Double.doubleToLongBits(
                        actual.fraction
                )
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
