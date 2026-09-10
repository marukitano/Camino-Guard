package com.marukitano.caminoguard;

import android.content.Context;
import android.location.Location;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import io.rebble.pebblekit2.client.java.DefaultJavaPebbleSender;
import io.rebble.pebblekit2.client.java.JavaPebbleSender;
import io.rebble.pebblekit2.common.model.PebbleDictionaryItem;
import io.rebble.pebblekit2.common.model.TransmissionResult;
import io.rebble.pebblekit2.common.model.WatchIdentifier;

/**
 * Android -> Camino Guard Pebble bridge.
 *
 * The watchapp is a presentation endpoint. Navigation, route progress and ETA
 * stay Android-owned. The bridge only serializes already-calculated values.
 */
final class CaminoPebbleBridge
        implements AutoCloseable {

    private static final String TAG =
            "CaminoPebble";

    private static final UUID APP_UUID =
            UUID.fromString(
                    "5d2f1422-7b95-4951-b7ce-d122783a58d4"
            );

    /* Must match pebble/package.json. */
    private static final int KEY_GLUCOSE = 0;
    private static final int KEY_CURRENT_SPEED = 3;
    private static final int KEY_TEMP_CURRENT_TENTHS = 8;
    private static final int KEY_TEMP_MIN_TENTHS = 9;
    private static final int KEY_TEMP_MAX_TENTHS = 10;
    private static final int KEY_SUNRISE_MINUTES = 11;
    private static final int KEY_SUNSET_MINUTES = 12;
    private static final int KEY_ELEVATION_CURRENT = 13;
    private static final int KEY_ELEVATION_MIN = 14;
    private static final int KEY_ELEVATION_MAX = 15;
    private static final int KEY_STOP_NAME = 16;
    private static final int KEY_STOP_TIME = 17;
    private static final int KEY_STOP_DISTANCE = 18;
    private static final int KEY_STOP_PERCENT = 19;
    private static final int KEY_MAP_VECTOR = 20;

    private final JavaPebbleSender sender;
    private final CaminoPebbleWeatherClient weatherClient;
    private final LibreLinkUpStore libreStore;

    CaminoPebbleBridge(
            Context context
    ) {
        Context appContext =
                context.getApplicationContext();

        sender =
                new DefaultJavaPebbleSender(
                        appContext
                );

        weatherClient =
                new CaminoPebbleWeatherClient(
                        appContext
                );

        libreStore =
                new LibreLinkUpStore(
                        appContext
                );
    }

    void requestWeather(
            Location location,
            Consumer<CaminoPebbleWeatherClient.Snapshot> callback
    ) {
        weatherClient.request(
                location,
                callback
        );
    }

    synchronized void sendCachedGlucose() {
        Integer mgdl =
                libreStore.lastGlucoseMgdl();

        long readingTimeMs =
                libreStore.lastReadingTimeMs();

        if (mgdl != null
                && readingTimeMs > 0L) {

            sendGlucose(
                    LibreLinkUpClient.formatGlucoseDisplay(
                            mgdl,
                            readingTimeMs,
                            System.currentTimeMillis()
                    )
            );

            return;
        }

        String cachedText =
                libreStore.lastGlucoseText();

        if (cachedText != null
                && !cachedText.trim().isEmpty()) {

            sendGlucose(
                    cachedText
            );
        }
    }

    synchronized void sendGlucose(
            String glucoseText
    ) {
        if (!CaminoPebbleSession.isWatchOpen()
                || glucoseText == null
                || glucoseText.trim().isEmpty()) {

            return;
        }

        Map<Integer, PebbleDictionaryItem> dictionary =
                new HashMap<>();

        dictionary.put(
                KEY_GLUCOSE,
                new PebbleDictionaryItem.Text(
                        glucoseText.trim()
                )
        );

        sendDictionary(
                dictionary,
                "glucose",
                null
        );
    }

    synchronized void sendDashboardState(
            String currentSpeed,
            Integer temperatureCurrentTenths,
            Integer temperatureMinTenths,
            Integer temperatureMaxTenths,
            Integer sunriseMinutes,
            Integer sunsetMinutes,
            Integer elevationCurrentM,
            Integer elevationMinM,
            Integer elevationMaxM,
            Consumer<Boolean> onResult
    ) {
        Map<Integer, PebbleDictionaryItem> dictionary =
                new HashMap<>();

        putOptionalText(
                dictionary,
                KEY_CURRENT_SPEED,
                currentSpeed
        );

        putOptionalInt32(
                dictionary,
                KEY_TEMP_CURRENT_TENTHS,
                temperatureCurrentTenths
        );

        putOptionalInt32(
                dictionary,
                KEY_TEMP_MIN_TENTHS,
                temperatureMinTenths
        );

        putOptionalInt32(
                dictionary,
                KEY_TEMP_MAX_TENTHS,
                temperatureMaxTenths
        );

        putOptionalInt32(
                dictionary,
                KEY_SUNRISE_MINUTES,
                sunriseMinutes
        );

        putOptionalInt32(
                dictionary,
                KEY_SUNSET_MINUTES,
                sunsetMinutes
        );

        putOptionalInt32(
                dictionary,
                KEY_ELEVATION_CURRENT,
                elevationCurrentM
        );

        putOptionalInt32(
                dictionary,
                KEY_ELEVATION_MIN,
                elevationMinM
        );

        putOptionalInt32(
                dictionary,
                KEY_ELEVATION_MAX,
                elevationMaxM
        );

        sendDictionary(
                dictionary,
                "dashboard",
                onResult
        );
    }

    synchronized void sendTimetableStop(
            String name,
            String arrivalTime,
            String remainingDistance,
            Integer routePercent,
            Consumer<Boolean> onResult
    ) {
        Map<Integer, PebbleDictionaryItem> dictionary =
                new HashMap<>();

        putOptionalText(
                dictionary,
                KEY_STOP_NAME,
                name
        );

        putOptionalText(
                dictionary,
                KEY_STOP_TIME,
                arrivalTime
        );

        putOptionalText(
                dictionary,
                KEY_STOP_DISTANCE,
                remainingDistance
        );

        putOptionalInt32(
                dictionary,
                KEY_STOP_PERCENT,
                routePercent
        );

        sendDictionary(
                dictionary,
                "timetable stop",
                onResult
        );
    }

    synchronized void sendMiniMap(
            byte[] payload,
            Consumer<Boolean> onResult
    ) {
        if (payload == null
                || payload.length == 0) {

            payload =
                    new byte[]{1, 0, 0};
        }

        Map<Integer, PebbleDictionaryItem> dictionary =
                new HashMap<>();

        dictionary.put(
                KEY_MAP_VECTOR,
                new PebbleDictionaryItem.Bytes(
                        payload
                )
        );

        sendDictionary(
                dictionary,
                "mini map",
                onResult
        );
    }

    private void sendDictionary(
            Map<Integer, PebbleDictionaryItem> dictionary,
            String label,
            Consumer<Boolean> onResult
    ) {
        if (dictionary == null
                || dictionary.isEmpty()) {

            if (onResult != null) {
                onResult.accept(
                        true
                );
            }

            return;
        }

        try {
            sender.sendDataToPebble(
                    APP_UUID,
                    dictionary,
                    result -> {
                        boolean delivered =
                                transmissionSucceeded(
                                        result
                                );

                        if (!delivered) {
                            Log.d(
                                    TAG,
                                    label
                                            + " not delivered to Pebble: "
                                            + result
                            );
                        }

                        if (onResult != null) {
                            onResult.accept(
                                    delivered
                            );
                        }
                    }
            );

        } catch (RuntimeException error) {
            Log.w(
                    TAG,
                    "Could not send "
                            + label
                            + " to Pebble",
                    error
            );

            if (onResult != null) {
                onResult.accept(
                        false
                );
            }
        }
    }

    static boolean transmissionSucceeded(
            Map<WatchIdentifier, TransmissionResult> result
    ) {
        if (result == null
                || result.isEmpty()) {

            return false;
        }

        for (TransmissionResult transmission :
                result.values()) {

            if (!(transmission
                    instanceof TransmissionResult.Success)) {

                return false;
            }
        }

        return true;
    }

    private void putOptionalText(
            Map<Integer, PebbleDictionaryItem> dictionary,
            int key,
            String value
    ) {
        if (value == null) {
            return;
        }

        dictionary.put(
                key,
                new PebbleDictionaryItem.Text(
                        safeText(
                                value
                        )
                )
        );
    }

    private void putOptionalInt32(
            Map<Integer, PebbleDictionaryItem> dictionary,
            int key,
            Integer value
    ) {
        if (value == null) {
            return;
        }

        dictionary.put(
                key,
                new PebbleDictionaryItem.Int32(
                        value
                )
        );
    }

    private String safeText(
            String value
    ) {
        if (value == null
                || value.trim().isEmpty()) {

            return "--";
        }

        return value.trim();
    }

    @Override
    public synchronized void close() {
        weatherClient.close();

        try {
            sender.close();

        } catch (Exception error) {
            Log.w(
                    TAG,
                    "Could not close Pebble sender",
                    error
            );
        }
    }
}
