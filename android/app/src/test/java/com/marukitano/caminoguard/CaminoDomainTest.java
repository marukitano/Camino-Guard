package com.marukitano.caminoguard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.BeforeClass;
import org.junit.Test;
import org.maplibre.android.geometry.LatLng;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

public final class CaminoDomainTest {

    private static final double EARTH_RADIUS_M =
            6371008.8;

    private static final Path ASSETS =
            Path.of(
                    "src",
                    "main",
                    "assets"
            );

    @BeforeClass
    public static void initializeConfig()
            throws Exception {

        String configText =
                readUtf8(
                        ASSETS.resolve(
                                "config/camino-config.json"
                        )
                );

        JSONObject root =
                new JSONObject(
                        configText
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
    public void canonicalAssetKeepsDataContract()
            throws Exception {

        String assetPath =
                CaminoConfig.get()
                        .string(
                                "data.caminoAsset"
                        );

        Path asset =
                ASSETS.resolve(
                        assetPath
                );

        assertTrue(
                "configured Camino asset must exist: " + asset,
                Files.isRegularFile(
                        asset
                )
        );

        JSONObject root =
                new JSONObject(
                        readUtf8(
                                asset
                        )
                );

        JSONArray routes =
                root.getJSONArray(
                        "routes"
                );

        assertTrue(
                "canonical Camino asset must contain routes",
                routes.length() > 0
        );

        for (int routeIndex = 0;
                routeIndex < routes.length();
                routeIndex++) {

            JSONObject route =
                    routes.getJSONObject(
                            routeIndex
                    );

            assertFalse(
                    "route_group_id must not be blank",
                    route.getString(
                            "route_group_id"
                    ).trim().isEmpty()
            );

            assertFalse(
                    "route name must not be blank",
                    route.getString(
                            "name"
                    ).trim().isEmpty()
            );

            JSONArray tracks =
                    route.getJSONArray(
                            "tracks"
                    );

            assertTrue(
                    "route must contain tracks",
                    tracks.length() > 0
            );

            for (int trackIndex = 0;
                    trackIndex < tracks.length();
                    trackIndex++) {

                JSONObject track =
                        tracks.getJSONObject(
                                trackIndex
                        );

                assertFalse(
                        "section_id must not be blank",
                        track.getString(
                                "section_id"
                        ).trim().isEmpty()
                );

                JSONArray coordinates =
                        track.getJSONArray(
                                "coordinates"
                        );

                assertTrue(
                        "track must contain at least two coordinates",
                        coordinates.length() >= 2
                );

                for (int pointIndex = 0;
                        pointIndex < coordinates.length();
                        pointIndex++) {

                    JSONArray coordinate =
                            coordinates.getJSONArray(
                                    pointIndex
                            );

                    assertTrue(
                            "coordinate must contain latitude and longitude",
                            coordinate.length() >= 2
                    );

                    double latitude =
                            coordinate.getDouble(
                                    0
                            );

                    double longitude =
                            coordinate.getDouble(
                                    1
                            );

                    assertTrue(
                            "latitude must be finite",
                            Double.isFinite(
                                    latitude
                            )
                    );

                    assertTrue(
                            "longitude must be finite",
                            Double.isFinite(
                                    longitude
                            )
                    );

                    assertTrue(
                            "latitude must be valid",
                            latitude >= -90.0
                                    && latitude <= 90.0
                    );

                    assertTrue(
                            "longitude must be valid",
                            longitude >= -180.0
                                    && longitude <= 180.0
                    );
                }
            }
        }
    }

    @Test
    public void colorsKeepGlobalNormalizationAndDarkeningContract() {
        String configuredDefault =
                CaminoConfig.get()
                        .string(
                                "routes.defaultColor"
                        );

        assertEquals(
                "#AABBCC",
                CaminoColors.normalize(
                        "  #AABBCC  "
                )
        );

        assertEquals(
                configuredDefault,
                CaminoColors.normalize(
                        "not-a-color"
                )
        );

        assertEquals(
                "#BFBFBF",
                CaminoColors.darken(
                        "#ffffff",
                        0.25f
                )
        );

        assertEquals(
                "#000000",
                CaminoColors.darken(
                        "#123456",
                        1.0f
                )
        );

        assertEquals(
                "#112233",
                CaminoColors.darken(
                        "#AA112233",
                        0.0f
                )
        );
    }

    @Test
    public void haversineDistanceKeepsRadiusUnitsAndSymmetry() {
        LatLng origin =
                new LatLng(
                        0.0,
                        0.0
                );

        LatLng northOneDegree =
                new LatLng(
                        1.0,
                        0.0
                );

        assertEquals(
                0.0,
                GeoMath.distanceMeters(
                        origin,
                        origin
                ),
                1e-9
        );

        double expectedOneDegree =
                Math.PI
                        * EARTH_RADIUS_M
                        / 180.0;

        double forward =
                GeoMath.distanceMeters(
                        origin,
                        northOneDegree
                );

        double backward =
                GeoMath.distanceMeters(
                        northOneDegree,
                        origin
                );

        assertEquals(
                expectedOneDegree,
                forward,
                0.01
        );

        assertEquals(
                forward,
                backward,
                1e-9
        );
    }

    @Test
    public void projectionFindsNearestPointOnSingleTrack() {
        CaminoRoute route =
                singleTrackRoute();

        CaminoNetwork network =
                new CaminoNetwork();

        network.rebuild(
                Arrays.asList(
                        route
                )
        );

        CaminoProjectionEngine engine =
                new CaminoProjectionEngine(
                        network
                );

        RouteHit result =
                engine.findNearestRouteHit(
                        new LatLng(
                                0.001,
                                0.005
                        )
                );

        assertNotNull(
                result
        );

        assertSame(
                route,
                result.route
        );

        assertEquals(
                0,
                result.hit.trackIndex
        );

        assertEquals(
                0,
                result.hit.segmentIndex
        );

        assertEquals(
                Math.PI
                        * EARTH_RADIUS_M
                        / 180000.0,
                result.hit.distanceFromQueryM,
                0.05
        );
    }

    @Test
    public void networkFindPathUsesTrackWeightAndHandlesBounds() {
        CaminoRoute route =
                singleTrackRoute();

        RouteTrack track =
                route.tracks.get(
                        0
                );

        CaminoNetwork network =
                new CaminoNetwork();

        network.rebuild(
                Arrays.asList(
                        route
                )
        );

        NetworkPath path =
                network.findPath(
                        0,
                        1
                );

        assertNotNull(
                path
        );

        assertEquals(
                track.lengthM,
                path.distanceM,
                1e-9
        );

        assertEquals(
                1,
                path.steps.size()
        );

        NetworkPath sameNode =
                network.findPath(
                        0,
                        0
                );

        assertNotNull(
                sameNode
        );

        assertEquals(
                0.0,
                sameNode.distanceM,
                0.0
        );

        assertTrue(
                sameNode.steps.isEmpty()
        );

        assertNull(
                network.findPath(
                        -1,
                        1
                )
        );

        assertNull(
                network.findPath(
                        0,
                        2
                )
        );
    }

    private static String readUtf8(
            Path path
    ) throws Exception {

        return new String(
                Files.readAllBytes(
                        path
                ),
                StandardCharsets.UTF_8
        );
    }

    private static CaminoRoute singleTrackRoute() {
        CaminoRoute route =
                new CaminoRoute(
                        "test-route",
                        "Test Route",
                        "#336699"
                );

        List<LatLng> points =
                Arrays.asList(
                        new LatLng(
                                0.0,
                                0.0
                        ),
                        new LatLng(
                                0.0,
                                0.01
                        )
                );

        List<Double> elevations =
                Arrays.asList(
                        100.0,
                        110.0
                );

        RouteTrack track =
                new RouteTrack(
                        "01a",
                        1,
                        points,
                        elevations,
                        "#336699",
                        "#24476B",
                        "",
                        "",
                        false,
                        false
                );

        track.baseChainageM =
                0.0;

        track.lengthM =
                GeoMath.distanceMeters(
                        points.get(
                                0
                        ),
                        points.get(
                                1
                        )
                );

        route.tracks.add(
                track
        );

        return route;
    }
}
