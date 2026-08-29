package com.marukitano.caminoguard;

import android.content.Context;
import android.content.SharedPreferences;


/**
 * Private local state for LibreLinkUp.
 *
 * This is runtime/account state and intentionally does not belong in
 * camino-config.json.
 */
final class LibreLinkUpStore {

    static final class Credentials {

        final String email;
        final String password;


        Credentials(
                String email,
                String password
        ) {
            this.email =
                    email;

            this.password =
                    password;
        }
    }


    private static final String PREFS =
            "libre_link_up";

    private static final String KEY_EMAIL =
            "email";

    private static final String KEY_PASSWORD =
            "password";

    private static final String KEY_LAST_GLUCOSE_TEXT =
            "last_glucose_text";

    private static final String KEY_LAST_GLUCOSE_MGDL =
            "last_glucose_mgdl";

    private static final String KEY_LAST_READING_TIME_MS =
            "last_reading_time_ms";


    private final SharedPreferences preferences;


    LibreLinkUpStore(
            Context context
    ) {
        preferences =
                context.getApplicationContext()
                        .getSharedPreferences(
                                PREFS,
                                Context.MODE_PRIVATE
                        );
    }


    boolean hasCredentials() {
        return credentials()
                != null;
    }


    Credentials credentials() {
        String email =
                preferences.getString(
                        KEY_EMAIL,
                        ""
                );

        String password =
                preferences.getString(
                        KEY_PASSWORD,
                        ""
                );

        if (email == null
                || email.trim().isEmpty()
                || password == null
                || password.isEmpty()) {

            return null;
        }

        return new Credentials(
                email.trim(),
                password
        );
    }


    void saveCredentials(
            String email,
            String password
    ) {
        String cleanEmail =
                email == null
                        ? ""
                        : email.trim();

        String cleanPassword =
                password == null
                        ? ""
                        : password;

        preferences.edit()
                .putString(
                        KEY_EMAIL,
                        cleanEmail
                )
                .putString(
                        KEY_PASSWORD,
                        cleanPassword
                )
                .apply();
    }


    void saveReading(
            int mgdl,
            long readingTimeMs,
            String displayText
    ) {
        preferences.edit()
                .putInt(
                        KEY_LAST_GLUCOSE_MGDL,
                        mgdl
                )
                .putLong(
                        KEY_LAST_READING_TIME_MS,
                        readingTimeMs
                )
                .putString(
                        KEY_LAST_GLUCOSE_TEXT,
                        displayText
                )
                .apply();
    }


    String lastGlucoseText() {
        return preferences.getString(
                KEY_LAST_GLUCOSE_TEXT,
                null
        );
    }


    Integer lastGlucoseMgdl() {
        if (!preferences.contains(
                KEY_LAST_GLUCOSE_MGDL
        )) {
            return null;
        }

        return preferences.getInt(
                KEY_LAST_GLUCOSE_MGDL,
                0
        );
    }


    long lastReadingTimeMs() {
        return preferences.getLong(
                KEY_LAST_READING_TIME_MS,
                0L
        );
    }
}
