package com.marukitano.caminoguard;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Very small weather client used only for the Pebble dashboard scale.
 *
 * The watch needs four values: current temperature, today's minimum/maximum,
 * sunrise and sunset. They are cached locally and refreshed at low frequency;
 * weather failure is presentation-only and can never affect navigation/GPS.
 */
final class CaminoPebbleWeatherClient
        implements AutoCloseable {

    static final class Snapshot {
        final int currentTenthsC;
        final int minTenthsC;
        final int maxTenthsC;
        final int sunriseMinutes;
        final int sunsetMinutes;

        Snapshot(
                int currentTenthsC,
                int minTenthsC,
                int maxTenthsC,
                int sunriseMinutes,
                int sunsetMinutes
        ) {
            this.currentTenthsC = currentTenthsC;
            this.minTenthsC = minTenthsC;
            this.maxTenthsC = maxTenthsC;
            this.sunriseMinutes = sunriseMinutes;
            this.sunsetMinutes = sunsetMinutes;
        }
    }

    private static final String TAG =
            "CaminoPebbleWeather";

    private static final String PREFS =
            "camino-pebble-weather";

    private static final long FETCH_INTERVAL_MS =
            30L * 60L * 1000L;

    private static final float REFETCH_DISTANCE_M =
            20_000.0f;

    private final SharedPreferences preferences;
    private final ExecutorService executor =
            Executors.newSingleThreadExecutor();

    private boolean fetchInFlight;

    CaminoPebbleWeatherClient(
            Context context
    ) {
        preferences =
                context.getApplicationContext()
                        .getSharedPreferences(
                                PREFS,
                                Context.MODE_PRIVATE
                        );
    }

    void request(
            Location location,
            Consumer<Snapshot> callback
    ) {
        if (location == null
                || callback == null) {

            return;
        }

        Snapshot cached =
                cachedSnapshot();

        if (cached != null) {
            callback.accept(
                    cached
            );
        }

        synchronized (this) {
            if (fetchInFlight
                    || cacheIsFreshFor(
                            location
                    )) {

                return;
            }

            fetchInFlight =
                    true;
        }

        Location requestLocation =
                new Location(
                        location
                );

        executor.execute(
                () -> {
                    Snapshot fresh =
                            null;

                    try {
                        fresh =
                                fetch(
                                        requestLocation
                                );

                        save(
                                requestLocation,
                                fresh
                        );

                    } catch (Exception error) {
                        Log.d(
                                TAG,
                                "Weather refresh failed",
                                error
                        );

                    } finally {
                        synchronized (CaminoPebbleWeatherClient.this) {
                            fetchInFlight =
                                    false;
                        }
                    }

                    if (fresh != null) {
                        callback.accept(
                                fresh
                        );
                    }
                }
        );
    }

    private boolean cacheIsFreshFor(
            Location location
    ) {
        if (!preferences.getBoolean(
                "valid",
                false
        )) {

            return false;
        }

        long fetchedAtMs =
                preferences.getLong(
                        "fetched_at_ms",
                        0L
                );

        if (fetchedAtMs <= 0L
                || System.currentTimeMillis()
                - fetchedAtMs
                >= FETCH_INTERVAL_MS) {

            return false;
        }

        double latitude =
                Double.longBitsToDouble(
                        preferences.getLong(
                                "latitude_bits",
                                Double.doubleToRawLongBits(
                                        Double.NaN
                                )
                        )
                );

        double longitude =
                Double.longBitsToDouble(
                        preferences.getLong(
                                "longitude_bits",
                                Double.doubleToRawLongBits(
                                        Double.NaN
                                )
                        )
                );

        if (!Double.isFinite(latitude)
                || !Double.isFinite(longitude)) {

            return false;
        }

        float[] distance =
                new float[1];

        Location.distanceBetween(
                latitude,
                longitude,
                location.getLatitude(),
                location.getLongitude(),
                distance
        );

        return distance[0]
                < REFETCH_DISTANCE_M;
    }

    private Snapshot cachedSnapshot() {
        if (!preferences.getBoolean(
                "valid",
                false
        )) {

            return null;
        }

        return new Snapshot(
                preferences.getInt(
                        "current_tenths_c",
                        0
                ),
                preferences.getInt(
                        "min_tenths_c",
                        0
                ),
                preferences.getInt(
                        "max_tenths_c",
                        0
                ),
                preferences.getInt(
                        "sunrise_minutes",
                        -1
                ),
                preferences.getInt(
                        "sunset_minutes",
                        -1
                )
        );
    }

    private Snapshot fetch(
            Location location
    ) throws Exception {
        String endpoint =
                String.format(
                        Locale.US,
                        "https://api.open-meteo.com/v1/forecast"
                                + "?latitude=%.6f&longitude=%.6f"
                                + "&current=temperature_2m"
                                + "&daily=temperature_2m_min,temperature_2m_max,sunrise,sunset"
                                + "&timezone=auto&forecast_days=1",
                        location.getLatitude(),
                        location.getLongitude()
                );

        HttpURLConnection connection =
                (HttpURLConnection)
                        new URL(
                                endpoint
                        ).openConnection();

        connection.setConnectTimeout(
                8_000
        );

        connection.setReadTimeout(
                8_000
        );

        connection.setRequestProperty(
                "Accept",
                "application/json"
        );

        try {
            int status =
                    connection.getResponseCode();

            if (status < 200
                    || status >= 300) {

                throw new IllegalStateException(
                        "Weather HTTP "
                                + status
                );
            }

            StringBuilder body =
                    new StringBuilder();

            try (
                    BufferedReader reader =
                            new BufferedReader(
                                    new InputStreamReader(
                                            connection.getInputStream(),
                                            StandardCharsets.UTF_8
                                    )
                            )
            ) {
                String line;

                while ((line = reader.readLine())
                        != null) {
                    body.append(
                            line
                    );
                }
            }

            JSONObject root =
                    new JSONObject(
                            body.toString()
                    );

            JSONObject current =
                    root.getJSONObject(
                            "current"
                    );

            JSONObject daily =
                    root.getJSONObject(
                            "daily"
                    );

            int currentTenths =
                    toTenths(
                            current.getDouble(
                                    "temperature_2m"
                            )
                    );

            int minTenths =
                    toTenths(
                            firstDouble(
                                    daily,
                                    "temperature_2m_min"
                            )
                    );

            int maxTenths =
                    toTenths(
                            firstDouble(
                                    daily,
                                    "temperature_2m_max"
                            )
                    );

            int sunrise =
                    minutesOfDay(
                            firstString(
                                    daily,
                                    "sunrise"
                            )
                    );

            int sunset =
                    minutesOfDay(
                            firstString(
                                    daily,
                                    "sunset"
                            )
                    );

            if (maxTenths < minTenths
                    || sunrise < 0
                    || sunset <= sunrise) {

                throw new IllegalStateException(
                        "Invalid weather scale"
                );
            }

            return new Snapshot(
                    currentTenths,
                    minTenths,
                    maxTenths,
                    sunrise,
                    sunset
            );

        } finally {
            connection.disconnect();
        }
    }

    private void save(
            Location location,
            Snapshot snapshot
    ) {
        preferences.edit()
                .putBoolean(
                        "valid",
                        true
                )
                .putLong(
                        "fetched_at_ms",
                        System.currentTimeMillis()
                )
                .putLong(
                        "latitude_bits",
                        Double.doubleToRawLongBits(
                                location.getLatitude()
                        )
                )
                .putLong(
                        "longitude_bits",
                        Double.doubleToRawLongBits(
                                location.getLongitude()
                        )
                )
                .putInt(
                        "current_tenths_c",
                        snapshot.currentTenthsC
                )
                .putInt(
                        "min_tenths_c",
                        snapshot.minTenthsC
                )
                .putInt(
                        "max_tenths_c",
                        snapshot.maxTenthsC
                )
                .putInt(
                        "sunrise_minutes",
                        snapshot.sunriseMinutes
                )
                .putInt(
                        "sunset_minutes",
                        snapshot.sunsetMinutes
                )
                .apply();
    }

    private static double firstDouble(
            JSONObject object,
            String name
    ) throws Exception {
        JSONArray values =
                object.getJSONArray(
                        name
                );

        return values.getDouble(
                0
        );
    }

    private static String firstString(
            JSONObject object,
            String name
    ) throws Exception {
        JSONArray values =
                object.getJSONArray(
                        name
                );

        return values.getString(
                0
        );
    }

    private static int toTenths(
            double value
    ) {
        if (!Double.isFinite(
                value
        )) {

            throw new IllegalArgumentException(
                    "non-finite temperature"
            );
        }

        return (int)
                Math.round(
                        value * 10.0
                );
    }

    static int minutesOfDay(
            String isoLocalDateTime
    ) {
        if (isoLocalDateTime == null) {
            return -1;
        }

        int separator =
                isoLocalDateTime.indexOf(
                        'T'
                );

        if (separator < 0
                || separator + 6
                > isoLocalDateTime.length()) {

            return -1;
        }

        try {
            int hour =
                    Integer.parseInt(
                            isoLocalDateTime.substring(
                                    separator + 1,
                                    separator + 3
                            )
                    );

            int minute =
                    Integer.parseInt(
                            isoLocalDateTime.substring(
                                    separator + 4,
                                    separator + 6
                            )
                    );

            if (hour < 0
                    || hour > 23
                    || minute < 0
                    || minute > 59) {

                return -1;
            }

            return hour * 60
                    + minute;

        } catch (RuntimeException ignored) {
            return -1;
        }
    }

    @Override
    public synchronized void close() {
        executor.shutdownNow();
    }
}
