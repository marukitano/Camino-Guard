package com.marukitano.caminoguard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.maplibre.android.geometry.LatLng;


public final class LockedNavigationSessionTest {

    @Test
    public void identicalPathAndPositionReuseProjection() {
        MeasurementPath path =
                simplePath();

        LockedNavigationSession session =
                new LockedNavigationSession(
                        20.0
                );

        LatLng position =
                new LatLng(
                        47.0005,
                        8.0
                );

        MeasurementPathProjection.LockedResult first =
                session.projectionFor(
                        path,
                        position
                );

        MeasurementPathProjection.LockedResult second =
                session.projectionFor(
                        path,
                        position
                );

        assertSame(
                first,
                second
        );
    }


    @Test
    public void androidProgressUsesNormalizedMeasurementPathDistance() {
        MeasurementPath path =
                simplePath();

        LockedNavigationSession session =
                new LockedNavigationSession(
                        20.0
                );

        MeasurementPathProjection.LockedResult projection =
                session.projectionFor(
                        path,
                        new LatLng(
                                47.0005,
                                8.0
                        )
                );

        /*
         * Profile distance is 200..1200.
         * Projection chainage is therefore about 700 m.
         *
         * Existing Android semantics use fraction 0.5 of
         * MeasurementPath.distanceM=1000 -> 500 m.
         */
        assertEquals(
                500.0,
                session.currentChainageM(
                        path,
                        projection.route,
                        true
                ),
                1.0
        );
    }


    @Test
    public void offRouteTransitionFiresOnlyOnceAndFreezesProgress() {
        MeasurementPath path =
                simplePath();

        LockedNavigationSession session =
                new LockedNavigationSession(
                        20.0
                );

        MeasurementPathProjection.LockedResult valid =
                session.projectionFor(
                        path,
                        new LatLng(
                                47.0005,
                                8.0
                        )
                );

        assertFalse(
                session.updateRouteState(
                        path,
                        valid.route
                )
        );

        assertFalse(
                session.isOffRoute()
        );

        assertTrue(
                session.updateRouteState(
                        path,
                        null
                )
        );

        assertTrue(
                session.isOffRoute()
        );

        assertFalse(
                session.updateRouteState(
                        path,
                        null
                )
        );

        assertEquals(
                500.0,
                session.currentChainageM(
                        path,
                        null,
                        true
                ),
                1.0
        );
    }


    @Test
    public void clearOffRouteDoesNotForgetLastGoodProgress() {
        MeasurementPath path =
                simplePath();

        LockedNavigationSession session =
                new LockedNavigationSession(
                        20.0
                );

        MeasurementPathProjection.LockedResult valid =
                session.projectionFor(
                        path,
                        new LatLng(
                                47.0005,
                                8.0
                        )
                );

        session.updateRouteState(
                path,
                valid.route
        );

        session.updateRouteState(
                path,
                null
        );

        session.clearOffRoute();

        assertFalse(
                session.isOffRoute()
        );

        assertEquals(
                500.0,
                session.currentChainageM(
                        path,
                        null,
                        true
                ),
                1.0
        );
    }


    @Test
    public void resetClearsOffRouteProgressAndProjectionSession() {
        MeasurementPath path =
                simplePath();

        LockedNavigationSession session =
                new LockedNavigationSession(
                        20.0
                );

        MeasurementPathProjection.LockedResult first =
                session.projectionFor(
                        path,
                        new LatLng(
                                47.0005,
                                8.0
                        )
                );

        session.updateRouteState(
                path,
                first.route
        );

        session.updateRouteState(
                path,
                null
        );

        session.reset();

        assertFalse(
                session.isOffRoute()
        );

        assertEquals(
                0.0,
                session.currentChainageM(
                        path,
                        null,
                        true
                ),
                0.001
        );
    }


    @Test
    public void debugChainageUsesSameFreezeState() {
        LockedNavigationSession session =
                new LockedNavigationSession(
                        20.0
                );

        assertEquals(
                321.0,
                session.currentChainageM(
                        321.0,
                        true
                ),
                0.001
        );

        assertEquals(
                321.0,
                session.currentChainageM(
                        Double.NaN,
                        true
                ),
                0.001
        );
    }


    private static MeasurementPath simplePath() {
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
                        200.0,
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
                        1200.0,
                        200.0,
                        false
                )
        );

        return path;
    }
}
