package com.marukitano.caminoguard;

import android.content.Context;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import io.rebble.pebblekit2.client.java.DefaultJavaPebbleSender;
import io.rebble.pebblekit2.client.java.JavaPebbleSender;
import io.rebble.pebblekit2.common.model.PebbleDictionaryItem;


/**
 * Android -> Camino Guard Pebble bridge.
 *
 * The watch is a presentation endpoint. Navigation and LibreLinkUp logic stay
 * on Android.
 */
final class CaminoPebbleBridge
        implements AutoCloseable {

    private static final String TAG =
            "CaminoPebble";

    private static final UUID APP_UUID =
            UUID.fromString(
                    "5d2f1422-7b95-4951-b7ce-d122783a58d4"
            );

    /*
     * Must match pebble/package.json.
     */
    private static final int KEY_GLUCOSE =
            0;

    private static final int KEY_NEXT_DISTANCE =
            1;

    private static final int KEY_NEXT_TIME =
            2;

    private static final int KEY_CURRENT_SPEED =
            3;

    private static final int KEY_ALARM_ACTIVE =
            4;

    private static final int KEY_ROUTE_VALID =
            5;

    private static final int KEY_NEXT_NAME =
            6;


    private final JavaPebbleSender sender;


    CaminoPebbleBridge(
            Context context
    ) {
        sender =
                new DefaultJavaPebbleSender(
                        context.getApplicationContext()
                );
    }


    synchronized void sendGlucose(
            String glucoseText
    ) {
        if (glucoseText == null
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

        try {
            sender.sendDataToPebble(
                    APP_UUID,
                    dictionary,
                    result -> {
                        if (result == null) {
                            Log.d(
                                    TAG,
                                    "Pebble currently unreachable"
                            );

                        } else {
                            Log.d(
                                    TAG,
                                    "Glucose sent to Pebble: "
                                            + glucoseText
                            );
                        }
                    }
            );

        } catch (RuntimeException error) {
            /*
             * Pebble communication is never allowed to affect GPS or Libre.
             */
            Log.w(
                    TAG,
                    "Could not send glucose to Pebble",
                    error
            );
        }
    }


    synchronized void sendRouteState(
            String nextName,
            String nextDistance,
            String nextTime,
            String currentSpeed,
            boolean alarmActive,
            boolean routeValid
    ) {
        Map<Integer, PebbleDictionaryItem> dictionary =
                new HashMap<>();

        dictionary.put(
                KEY_NEXT_NAME,
                new PebbleDictionaryItem.Text(
                        safeText(
                                nextName
                        )
                )
        );

        dictionary.put(
                KEY_NEXT_DISTANCE,
                new PebbleDictionaryItem.Text(
                        safeText(
                                nextDistance
                        )
                )
        );

        dictionary.put(
                KEY_NEXT_TIME,
                new PebbleDictionaryItem.Text(
                        safeText(
                                nextTime
                        )
                )
        );

        dictionary.put(
                KEY_CURRENT_SPEED,
                new PebbleDictionaryItem.Text(
                        safeText(
                                currentSpeed
                        )
                )
        );

        /*
         * The current Pebble C receiver reads these values as strings.
         */
        dictionary.put(
                KEY_ALARM_ACTIVE,
                new PebbleDictionaryItem.Text(
                        alarmActive
                                ? "1"
                                : "0"
                )
        );

        dictionary.put(
                KEY_ROUTE_VALID,
                new PebbleDictionaryItem.Text(
                        routeValid
                                ? "1"
                                : "0"
                )
        );

        try {
            sender.sendDataToPebble(
                    APP_UUID,
                    dictionary,
                    result -> {
                        if (result == null) {
                            Log.d(
                                    TAG,
                                    "Pebble currently unreachable"
                            );
                        }
                    }
            );

        } catch (RuntimeException error) {
            /*
             * Pebble transport is presentation only.
             * Never let it affect GPS/navigation.
             */
            Log.w(
                    TAG,
                    "Could not send route state to Pebble",
                    error
            );
        }
    }


    private String safeText(
            String value
    ) {
        if (value == null
                || value.trim()
                .isEmpty()) {

            return "--";
        }

        return value.trim();
    }


    @Override
    public synchronized void close() {
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
