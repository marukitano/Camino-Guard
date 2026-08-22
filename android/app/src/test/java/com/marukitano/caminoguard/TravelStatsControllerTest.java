package com.marukitano.caminoguard;

import static org.junit.Assert.assertEquals;
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
import java.util.Collections;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class TravelStatsControllerTest {

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
    public void firstSampleStartsSessionWithoutInventingMovement() {
        Fixture fixture =
                new Fixture();

        fixture.clock.set(
                1_000L
        );

        fixture.controller.noteSample(
                point(
                        47.0,
                        8.0
                )
        );

        assertTrue(
                fixture.stats.get()
                        .contains(
                                "Ø Moving   —"
                        )
        );

        assertTrue(
                fixture.stats.get()
                        .contains(
                                "Ø Gesamt   —"
                        )
        );
    }

    @Test
    public void validMovementContributesToMovingAndTotalSpeed() {
        Fixture fixture =
                new Fixture();

        fixture.clock.set(
                1_000L
        );

        fixture.controller.noteSample(
                point(
                        47.0,
                        8.0
                )
        );

        fixture.clock.set(
                11_000L
        );

        fixture.controller.noteSample(
                point(
                        47.000089932,
                        8.0
                )
        );

        assertTrue(
                fixture.stats.get()
                        .contains(
                                "Ø Moving   3,6 km/h"
                        )
        );

        assertTrue(
                fixture.stats.get()
                        .contains(
                                "Ø Gesamt   3,6 km/h"
                        )
        );
    }

    @Test
    public void subThresholdMovementDoesNotCountAsTravelDistance() {
        Fixture fixture =
                new Fixture();

        fixture.clock.set(
                1_000L
        );

        fixture.controller.noteSample(
                point(
                        47.0,
                        8.0
                )
        );

        fixture.clock.set(
                11_000L
        );

        fixture.controller.noteSample(
                point(
                        47.0000089932,
                        8.0
                )
        );

        fixture.clock.set(
                21_000L
        );

        fixture.controller.noteSample(
                point(
                        47.0000989252,
                        8.0
                )
        );

        assertTrue(
                fixture.stats.get()
                        .contains(
                                "Ø Moving   3,6 km/h"
                        )
        );

        assertTrue(
                fixture.stats.get()
                        .contains(
                                "Ø Gesamt   1,8 km/h"
                        )
        );
    }

    @Test
    public void implausibleJumpIsIgnored() {
        Fixture fixture =
                new Fixture();

        fixture.clock.set(
                1_000L
        );

        fixture.controller.noteSample(
                point(
                        47.0,
                        8.0
                )
        );

        fixture.clock.set(
                11_000L
        );

        fixture.controller.noteSample(
                point(
                        47.03,
                        8.0
                )
        );

        assertTrue(
                fixture.stats.get()
                        .contains(
                                "Ø Moving   —"
                        )
        );

        assertTrue(
                fixture.stats.get()
                        .contains(
                                "Ø Gesamt   —"
                        )
        );
    }

    @Test
    public void nullSampleDoesNotPublishAnything() {
        Fixture fixture =
                new Fixture();

        fixture.controller.noteSample(
                null
        );

        assertEquals(
                null,
                fixture.stats.get()
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
        final AtomicLong clock =
                new AtomicLong();

        final AtomicReference<String> stats =
                new AtomicReference<>();

        final TravelStatsController controller =
                new TravelStatsController(
                        Collections.emptyList(),
                        null,
                        null,
                        () -> null,
                        () -> null,
                        stats::set,
                        clock::get
                );
    }
}
