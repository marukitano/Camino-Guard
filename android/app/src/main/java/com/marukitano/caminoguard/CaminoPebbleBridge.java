package com.marukitano.caminoguard;

import android.content.Context;
import android.location.Location;
import android.util.Log;

import java.io.ByteArrayOutputStream;
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

    private static final int ROAD_CHUNK_BYTES =
            160;

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
    private static final int KEY_ROUTE_PROGRESS_PERCENT = 23;
    private static final int KEY_SHOW_MAP_ONCE = 24;
    private static final int KEY_MAP_ROADS_GENERATION = 25;
    private static final int KEY_MAP_ROADS_CHUNK_INDEX = 26;
    private static final int KEY_MAP_ROADS_CHUNK_COUNT = 27;
    private static final int KEY_MAP_ROADS_CHUNK_DATA = 28;
    private static final int KEY_STOP_IS_GOAL = 29;

    private final JavaPebbleSender sender;
    private final CaminoPebbleWeatherClient weatherClient;
    private final CaminoPebbleRoadSnapshotter roadSnapshotter;
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

        roadSnapshotter =
                new CaminoPebbleRoadSnapshotter(
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

    void requestRoadSnapshot(
            Location location,
            CaminoPebbleRoadSnapshotter.Callback callback
    ) {
        roadSnapshotter.request(
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
            Integer routeProgressPercent,
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

        putOptionalInt32(
                dictionary,
                KEY_ROUTE_PROGRESS_PERCENT,
                routeProgressPercent
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
            Integer temperatureCurrentTenths,
            boolean isGoal,
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

        putOptionalInt32(
                dictionary,
                KEY_TEMP_CURRENT_TENTHS,
                temperatureCurrentTenths
        );

        putOptionalInt32(
                dictionary,
                KEY_STOP_IS_GOAL,
                isGoal ? 1 : 0
        );

        sendDictionary(
                dictionary,
                "timetable stop",
                onResult
        );
    }

    synchronized void sendShowMapOnce() {
        if (!CaminoPebbleSession.isWatchOpen()) {
            return;
        }

        Map<Integer, PebbleDictionaryItem> dictionary =
                new HashMap<>();

        dictionary.put(
                KEY_SHOW_MAP_ONCE,
                new PebbleDictionaryItem.Int32(
                        1
                )
        );

        sendDictionary(
                dictionary,
                "off-route map switch",
                null
        );
    }

    synchronized void sendMiniMap(
            byte[] payload,
            Consumer<Boolean> onResult
    ) {
        sendMiniMap(
                payload,
                0,
                null,
                onResult
        );
    }

    synchronized void sendMiniMap(
            byte[] payload,
            int roadGeneration,
            byte[] roadMask,
            Consumer<Boolean> onResult
    ) {
        if (payload == null
                || payload.length == 0) {

            payload =
                    new byte[]{2, 0, 0, 0, 0};
        }

        byte[] roadData =
                roadMask == null
                        ? null
                        : encodeRoadMask(
                                roadMask
                        );

        Map<Integer, PebbleDictionaryItem> dictionary =
                new HashMap<>();

        dictionary.put(
                KEY_MAP_VECTOR,
                new PebbleDictionaryItem.Bytes(
                        payload
                )
        );

        dictionary.put(
                KEY_MAP_ROADS_GENERATION,
                new PebbleDictionaryItem.Int32(
                        Math.max(
                                0,
                                roadGeneration
                        )
                )
        );

        sendDictionary(
                dictionary,
                "mini map vector",
                delivered -> {
                    if (!delivered) {
                        if (onResult != null) {
                            onResult.accept(
                                    false
                            );
                        }
                        return;
                    }

                    if (roadData == null
                            || roadData.length == 0
                            || roadGeneration <= 0) {

                        if (onResult != null) {
                            onResult.accept(
                                    true
                            );
                        }
                        return;
                    }

                    int chunkCount =
                            (roadData.length
                                    + ROAD_CHUNK_BYTES
                                    - 1)
                                    / ROAD_CHUNK_BYTES;

                    sendRoadChunk(
                            roadData,
                            roadGeneration,
                            0,
                            chunkCount,
                            onResult
                    );
                }
        );
    }

    /**
     * Sparse-mask codec with bounded overhead.
     *
     * control 0x00..0x7f: copy control+1 following literal bytes
     * control 0x80..0xff: emit control&0x7f + 1 zero bytes
     *
     * Zero runs shorter than three bytes stay inside literal blocks. That is
     * important for diagonal roads: it keeps the absolute worst case to one
     * control byte per 128 raw bytes instead of exploding on alternating
     * zero/non-zero bytes.
     */
    private static byte[] encodeRoadMask(
            byte[] raw
    ) {
        if (raw == null
                || raw.length == 0) {

            return null;
        }

        ByteArrayOutputStream output =
                new ByteArrayOutputStream(
                        raw.length
                );

        int index = 0;

        while (index < raw.length) {
            int zeroRun =
                    zeroRunLength(
                            raw,
                            index
                    );

            if (zeroRun >= 3) {
                output.write(
                        0x80
                                | (zeroRun - 1)
                );

                index +=
                        zeroRun;

                continue;
            }

            int start =
                    index;

            int literalRun =
                    0;

            while (index < raw.length
                    && literalRun < 128) {

                zeroRun =
                        zeroRunLength(
                                raw,
                                index
                        );

                if (zeroRun >= 3) {
                    break;
                }

                index++;
                literalRun++;
            }

            if (literalRun <= 0) {
                /* Defensive fallback; the outer zero-run branch normally owns this. */
                literalRun =
                        1;
                index++;
            }

            output.write(
                    literalRun - 1
            );

            output.write(
                    raw,
                    start,
                    literalRun
            );
        }

        return output.toByteArray();
    }

    private static int zeroRunLength(
            byte[] raw,
            int start
    ) {
        if (raw == null
                || start < 0
                || start >= raw.length
                || raw[start] != 0) {

            return 0;
        }

        int run = 1;

        while (start + run < raw.length
                && raw[start + run] == 0
                && run < 128) {

            run++;
        }

        return run;
    }

    private void sendRoadChunk(
            byte[] roadData,
            int roadGeneration,
            int chunkIndex,
            int chunkCount,
            Consumer<Boolean> onResult
    ) {
        int offset =
                chunkIndex
                        * ROAD_CHUNK_BYTES;

        int length =
                Math.min(
                        ROAD_CHUNK_BYTES,
                        roadData.length
                                - offset
                );

        if (length <= 0
                || chunkIndex >= chunkCount) {

            if (onResult != null) {
                onResult.accept(
                        true
                );
            }
            return;
        }

        byte[] chunk =
                new byte[length];

        System.arraycopy(
                roadData,
                offset,
                chunk,
                0,
                length
        );

        Map<Integer, PebbleDictionaryItem> dictionary =
                new HashMap<>();

        dictionary.put(
                KEY_MAP_ROADS_GENERATION,
                new PebbleDictionaryItem.Int32(
                        roadGeneration
                )
        );

        dictionary.put(
                KEY_MAP_ROADS_CHUNK_INDEX,
                new PebbleDictionaryItem.Int32(
                        chunkIndex
                )
        );

        dictionary.put(
                KEY_MAP_ROADS_CHUNK_COUNT,
                new PebbleDictionaryItem.Int32(
                        chunkCount
                )
        );

        dictionary.put(
                KEY_MAP_ROADS_CHUNK_DATA,
                new PebbleDictionaryItem.Bytes(
                        chunk
                )
        );

        sendDictionary(
                dictionary,
                "mini map roads "
                        + (chunkIndex + 1)
                        + "/"
                        + chunkCount,
                delivered -> {
                    if (!delivered) {
                        if (onResult != null) {
                            onResult.accept(
                                    false
                            );
                        }
                        return;
                    }

                    int nextIndex =
                            chunkIndex + 1;

                    if (nextIndex >= chunkCount) {
                        if (onResult != null) {
                            onResult.accept(
                                    true
                            );
                        }
                        return;
                    }

                    sendRoadChunk(
                            roadData,
                            roadGeneration,
                            nextIndex,
                            chunkCount,
                            onResult
                    );
                }
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
        roadSnapshotter.close();
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
