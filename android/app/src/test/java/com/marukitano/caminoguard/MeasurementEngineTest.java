package com.marukitano.caminoguard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.BeforeClass;
import org.junit.Test;
import org.maplibre.android.geometry.LatLng;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class MeasurementEngineTest {

    private static final Path CONFIG =
            Path.of(
                    "src",
                    "main",
                    "assets",
                    "config",
                    "camino-config.json"
            );

    @BeforeClass
    public static void initializeConfig()
            throws Exception {

        JSONObject root =
                new JSONObject(
                        new String(
                                Files.readAllBytes(
                                        CONFIG
                                ),
                                StandardCharsets.UTF_8
                        )
                );

        Constructor<CaminoConfig> constructor =
                CaminoConfig.class.getDeclaredConstructor(
                        JSONObject.class
                );

        constructor.setAccessible(
                true
        );

        CaminoConfig config =
                constructor.newInstance(
                        root
                );

        Field instance =
                CaminoConfig.class.getDeclaredField(
                        "instance"
                );

        instance.setAccessible(
                true
        );

        instance.set(
                null,
                config
        );
    }

    @Test
    public void nullEndpointsDoNotProduceMeasurement() {
        Fixture fixture =
                singleTrackFixture();

        assertNull(
                fixture.engine.buildMeasurementPath(
                        null,
                        fixture.end
                )
        );

        assertNull(
                fixture.engine.buildMeasurementPath(
                        fixture.start,
                        null
                )
        );
    }

    @Test
    public void sameTrackUsesPartialDistanceAndInterpolatedElevation() {
        CaminoRoute route =
                new CaminoRoute(
                        "test",
                        "Test Camino",
                        "#6a994e"
                );

        LatLng a =
                point(
                        47.0,
                        8.0
                );

        LatLng b =
                point(
                        47.001,
                        8.0
                );

        RouteTrack track =
                track(
                        "01a",
                        0,
                        Arrays.asList(
                                a,
                                b
                        ),
                        Arrays.asList(
                                100.0,
                                200.0
                        ),
                        route
                );

        prepareRoute(
                route,
                Collections.singletonList(
                        track
                )
        );

        CaminoNetwork network =
                networkFor(
                        route
                );

        MeasurementEngine engine =
                new MeasurementEngine(
                        network
                );

        ProjectionHit startHit =
                hitOnSingleSegment(
                        track,
                        0,
                        0.25
                );

        ProjectionHit endHit =
                hitOnSingleSegment(
                        track,
                        0,
                        0.75
                );

        MeasurementPath result =
                engine.buildMeasurementPath(
                        new RouteHit(
                                route,
                                startHit
                        ),
                        new RouteHit(
                                route,
                                endHit
                        )
                );

        assertNotNull(
                result
        );

        assertEquals(
                track.lengthM * 0.50,
                result.distanceM,
                0.05
        );

        assertEquals(
                125.0,
                engine.elevationAtHit(
                        track,
                        startHit
                ),
                0.001
        );

        assertEquals(
                175.0,
                engine.elevationAtHit(
                        track,
                        endHit
                ),
                0.001
        );

        assertEquals(
                2,
                result.profilePoints.size()
        );

        assertEquals(
                125.0,
                result.profilePoints.get(
                        0
                ).elevationM,
                0.001
        );

        assertEquals(
                175.0,
                result.profilePoints.get(
                        1
                ).elevationM,
                0.001
        );
    }

    @Test
    public void sameRouteGapContributesRealDistanceAndBreaksProfile() {
        GapFixture fixture =
                gapFixture();

        MeasurementPath result =
                fixture.engine.buildMeasurementPath(
                        fixture.forwardStart,
                        fixture.forwardEnd
                );

        assertNotNull(
                result
        );

        assertEquals(
                fixture.expectedGapM,
                result.distanceM,
                0.05
        );

        assertEquals(
                1,
                result.gapFeatures.size()
        );

        assertEquals(
                2,
                result.profilePoints.size()
        );

        assertTrue(
                result.profilePoints.get(
                        1
                ).breakBefore
        );

        assertEquals(
                fixture.expectedGapM,
                result.profilePoints.get(
                        1
                ).distanceM,
                0.05
        );
    }

    @Test
    public void reverseGapMeasurementKeepsSameDistance() {
        GapFixture fixture =
                gapFixture();

        MeasurementPath forward =
                fixture.engine.buildMeasurementPath(
                        fixture.forwardStart,
                        fixture.forwardEnd
                );

        MeasurementPath reverse =
                fixture.engine.buildMeasurementPath(
                        fixture.reverseStart,
                        fixture.reverseEnd
                );

        assertNotNull(
                forward
        );

        assertNotNull(
                reverse
        );

        assertEquals(
                forward.distanceM,
                reverse.distanceM,
                0.05
        );

        assertEquals(
                1,
                reverse.gapFeatures.size()
        );

        assertTrue(
                reverse.profilePoints.size()
                        >= 2
        );

        assertEquals(
                0.0,
                reverse.profilePoints.get(
                        0
                ).distanceM,
                0.05
        );

        assertEquals(
                fixture.expectedGapM,
                reverse.profilePoints.get(
                        reverse.profilePoints.size() - 1
                ).distanceM,
                0.05
        );

        assertTrue(
                hasProfileBreakNear(
                        reverse,
                        fixture.expectedGapM
                )
        );
    }

    private static boolean hasProfileBreakNear(
            MeasurementPath path,
            double distanceM
    ) {
        for (ProfilePoint point
                : path.profilePoints) {

            if (point.breakBefore
                    && Math.abs(
                    point.distanceM
                            - distanceM
            ) <= 0.05) {

                return true;
            }
        }

        return false;
    }

    private static Fixture singleTrackFixture() {
        CaminoRoute route =
                new CaminoRoute(
                        "single",
                        "Single",
                        "#6a994e"
                );

        RouteTrack track =
                track(
                        "01a",
                        0,
                        Arrays.asList(
                                point(
                                        47.0,
                                        8.0
                                ),
                                point(
                                        47.001,
                                        8.0
                                )
                        ),
                        Arrays.asList(
                                100.0,
                                200.0
                        ),
                        route
                );

        prepareRoute(
                route,
                Collections.singletonList(
                        track
                )
        );

        CaminoNetwork network =
                networkFor(
                        route
                );

        MeasurementEngine engine =
                new MeasurementEngine(
                        network
                );

        return new Fixture(
                engine,
                new RouteHit(
                        route,
                        hitOnSingleSegment(
                                track,
                                0,
                                0.25
                        )
                ),
                new RouteHit(
                        route,
                        hitOnSingleSegment(
                                track,
                                0,
                                0.75
                        )
                )
        );
    }

    private static GapFixture gapFixture() {
        CaminoRoute route =
                new CaminoRoute(
                        "gap",
                        "Gap Camino",
                        "#6a994e"
                );

        RouteTrack first =
                track(
                        "01a",
                        0,
                        Arrays.asList(
                                point(
                                        47.0,
                                        8.0
                                ),
                                point(
                                        47.0001,
                                        8.0
                                )
                        ),
                        Arrays.asList(
                                100.0,
                                110.0
                        ),
                        route
                );

        RouteTrack second =
                track(
                        "02a",
                        1,
                        Arrays.asList(
                                point(
                                        47.0010,
                                        8.0
                                ),
                                point(
                                        47.0011,
                                        8.0
                                )
                        ),
                        Arrays.asList(
                                120.0,
                                130.0
                        ),
                        route
                );

        prepareRoute(
                route,
                Arrays.asList(
                        first,
                        second
                )
        );

        CaminoNetwork network =
                networkFor(
                        route
                );

        MeasurementEngine engine =
                new MeasurementEngine(
                        network
                );

        ProjectionHit firstEnd =
                hitOnSingleSegment(
                        first,
                        0,
                        1.0
                );

        ProjectionHit secondStart =
                hitOnSingleSegment(
                        second,
                        1,
                        0.0
                );

        double expectedGapM =
                CaminoRepository.distanceMeters(
                        first.points.get(
                                first.points.size() - 1
                        ),
                        second.points.get(
                                0
                        )
                );

        return new GapFixture(
                engine,
                new RouteHit(
                        route,
                        firstEnd
                ),
                new RouteHit(
                        route,
                        secondStart
                ),
                new RouteHit(
                        route,
                        secondStart
                ),
                new RouteHit(
                        route,
                        firstEnd
                ),
                expectedGapM
        );
    }

    private static CaminoNetwork networkFor(
            CaminoRoute route
    ) {
        CaminoNetwork network =
                new CaminoNetwork();

        network.rebuild(
                Collections.singletonList(
                        route
                )
        );

        return network;
    }

    private static void prepareRoute(
            CaminoRoute route,
            List<RouteTrack> tracks
    ) {
        route.tracks.addAll(
                tracks
        );

        double chainageM =
                0.0;

        for (RouteTrack track
                : route.tracks) {

            track.baseChainageM =
                    chainageM;

            track.lengthM =
                    polylineLength(
                            track.points
                    );

            chainageM +=
                    track.lengthM;
        }
    }

    private static double polylineLength(
            List<LatLng> points
    ) {
        double distanceM =
                0.0;

        for (int index = 0;
                index < points.size() - 1;
                index++) {

            distanceM +=
                    CaminoRepository.distanceMeters(
                            points.get(
                                    index
                            ),
                            points.get(
                                    index + 1
                            )
                    );
        }

        return distanceM;
    }

    private static ProjectionHit hitOnSingleSegment(
            RouteTrack track,
            int trackIndex,
            double t
    ) {
        LatLng from =
                track.points.get(
                        0
                );

        LatLng to =
                track.points.get(
                        1
                );

        LatLng point =
                new LatLng(
                        from.getLatitude()
                                + t
                                * (
                                to.getLatitude()
                                        - from.getLatitude()
                        ),
                        from.getLongitude()
                                + t
                                * (
                                to.getLongitude()
                                        - from.getLongitude()
                        )
                );

        return new ProjectionHit(
                point,
                track.baseChainageM
                        + t
                        * track.lengthM,
                0.0,
                trackIndex,
                0,
                t
        );
    }

    private static RouteTrack track(
            String sectionId,
            int order,
            List<LatLng> points,
            List<Double> elevations,
            CaminoRoute route
    ) {
        return new RouteTrack(
                sectionId,
                order,
                new ArrayList<>(
                        points
                ),
                new ArrayList<>(
                        elevations
                ),
                route.color,
                route.highlightColor,
                null,
                null,
                false,
                false
        );
    }

    private static LatLng point(
            double latitude,
            double longitude
    ) {
        return new LatLng(
                latitude,
                longitude
        );
    }

    private static final class Fixture {
        final MeasurementEngine engine;
        final RouteHit start;
        final RouteHit end;

        Fixture(
                MeasurementEngine engine,
                RouteHit start,
                RouteHit end
        ) {
            this.engine =
                    engine;
            this.start =
                    start;
            this.end =
                    end;
        }
    }

    private static final class GapFixture {
        final MeasurementEngine engine;
        final RouteHit forwardStart;
        final RouteHit forwardEnd;
        final RouteHit reverseStart;
        final RouteHit reverseEnd;
        final double expectedGapM;

        GapFixture(
                MeasurementEngine engine,
                RouteHit forwardStart,
                RouteHit forwardEnd,
                RouteHit reverseStart,
                RouteHit reverseEnd,
                double expectedGapM
        ) {
            this.engine =
                    engine;
            this.forwardStart =
                    forwardStart;
            this.forwardEnd =
                    forwardEnd;
            this.reverseStart =
                    reverseStart;
            this.reverseEnd =
                    reverseEnd;
            this.expectedGapM =
                    expectedGapM;
        }
    }
}
