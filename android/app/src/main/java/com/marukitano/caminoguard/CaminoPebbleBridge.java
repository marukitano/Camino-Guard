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

    private static final int KEY_FLAT_SPEED =
            7;


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
            String flatSpeed,
            Boolean alarmActive,
            Boolean routeValid
    ) {
        Map<Integer, PebbleDictionaryItem> dictionary =
                new HashMap<>();

        /*
         * Pebble's receiver treats a missing tuple as "keep the previous
         * value". Therefore a null argument means this key did not change and
         * does not need to consume AppMessage bandwidth.
         */
        putOptionalText(
                dictionary,
                KEY_NEXT_NAME,
                nextName
        );

        putOptionalText(
                dictionary,
                KEY_NEXT_DISTANCE,
                nextDistance
        );

        putOptionalText(
                dictionary,
                KEY_NEXT_TIME,
                nextTime
        );

        putOptionalText(
                dictionary,
                KEY_CURRENT_SPEED,
                currentSpeed
        );

        putOptionalText(
                dictionary,
                KEY_FLAT_SPEED,
                flatSpeed
        );

        /*
         * The current Pebble C receiver reads these values as strings.
         */
        putOptionalText(
                dictionary,
                KEY_ALARM_ACTIVE,
                alarmActive == null
                        ? null
                        : alarmActive
                        ? "1"
                        : "0"
        );

        putOptionalText(
                dictionary,
                KEY_ROUTE_VALID,
                routeValid == null
                        ? null
                        : routeValid
                        ? "1"
                        : "0"
        );

        if (dictionary.isEmpty()) {
            return;
        }

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
