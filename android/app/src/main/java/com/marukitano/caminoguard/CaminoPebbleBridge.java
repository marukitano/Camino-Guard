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
