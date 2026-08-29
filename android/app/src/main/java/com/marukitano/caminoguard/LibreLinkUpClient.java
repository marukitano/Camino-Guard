package com.marukitano.caminoguard;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;


/**
 * Headless LibreLinkUp background client.
 *
 * Recovery behaviour intentionally mirrors the proven OpenLibreLinkUp logic:
 *
 * - Europe server only.
 * - No overlapping fetches.
 * - Existing session is tried first.
 * - Failed fetch -> clear session -> login -> retry fetch immediately.
 * - Failed recovery -> clear session -> retry after one minute.
 * - A successful HTTP response with a >=15 minute old reading is considered
 *   stale. Once per stale episode the session is cleared and polling retries
 *   every minute.
 * - A fresh reading restores the normal five minute polling interval.
 *
 * Authentication state only lives in RAM. If Android recreates the foreground
 * service, a completely fresh LibreLinkUp login is performed automatically.
 */
final class LibreLinkUpClient
        implements AutoCloseable {

    interface Listener {

        void onGlucoseUpdated(
                String displayText,
                long readingTimeMs
        );
    }


    private static final String TAG =
            "LibreLinkUp";

    /*
     * User-confirmed working endpoint.
     * Do not auto-switch to DE/US/etc.
     */
    private static final String BASE_URL =
            "https://api-eu.libreview.io";

    /*
     * Same client identity as the working OpenLibreLinkUp implementation.
     */
    private static final String PRODUCT =
            "llu.ios";

    private static final String VERSION =
            "4.16.0";

    private static final String USER_AGENT =
            "Mozilla/5.0 (iPhone; CPU OS 17_4_1 like Mac OS X) "
                    + "AppleWebKit/536.26 (KHTML, like Gecko) "
                    + "Version/17.4.1 Mobile/10A5355d Safari/8536.25";


    private static final long NORMAL_POLL_MS =
            5L * 60L * 1000L;

    private static final long RETRY_POLL_MS =
            1L * 60L * 1000L;

    private static final long STALE_REAUTH_MS =
            15L * 60L * 1000L;

    private static final int HTTP_TIMEOUT_MS =
            30_000;


    private final LibreLinkUpStore store;
    private final Listener listener;

    private final ScheduledExecutorService executor =
            Executors.newSingleThreadScheduledExecutor();

    private ScheduledFuture<?> scheduledTask;
    private ScheduledFuture<?> ageRefreshTask;

    /*
     * Last valid Libre measurement in RAM.
     * Network polling remains at five minutes; this is only used
     * to refresh the displayed age once per minute.
     */
    private volatile Reading lastReading;

    private boolean closed;
    private boolean fetchInProgress;
    private boolean runAgainAfterCurrent;
    private boolean staleSessionResetDone;


    /*
     * Session state is deliberately NOT persistent.
     */
    private String authToken;
    private String accountId;
    private String patientId;

    private String credentialFingerprint;


    LibreLinkUpClient(
            Context context,
            Listener listener
    ) {
        this.store =
                new LibreLinkUpStore(
                        context
                );

        this.listener =
                listener;
    }


    synchronized void start() {
        if (closed) {
            return;
        }

        Log.d(
                TAG,
                "Background LibreLinkUp client started"
        );

        scheduleInLocked(
                0L
        );

        /*
         * Libre itself is still polled only every five minutes.
         * This timer only updates the displayed measurement age.
         */
        ageRefreshTask =
                executor.scheduleAtFixedRate(
                        this::publishCurrentReadingAge,
                        1L,
                        1L,
                        TimeUnit.MINUTES
                );
    }


    synchronized void requestNow() {
        if (closed) {
            return;
        }

        if (fetchInProgress) {
            runAgainAfterCurrent =
                    true;

            return;
        }

        scheduleInLocked(
                0L
        );
    }


    private synchronized void scheduleInLocked(
            long delayMs
    ) {
        if (closed) {
            return;
        }

        if (scheduledTask != null) {
            scheduledTask.cancel(
                    false
            );

            scheduledTask =
                    null;
        }

        scheduledTask =
                executor.schedule(
                        this::runCycle,
                        Math.max(
                                0L,
                                delayMs
                        ),
                        TimeUnit.MILLISECONDS
                );
    }


    private void runCycle() {
        synchronized (this) {
            if (closed) {
                return;
            }

            scheduledTask =
                    null;

            if (fetchInProgress) {
                runAgainAfterCurrent =
                        true;

                return;
            }

            fetchInProgress =
                    true;
        }


        long nextDelayMs =
                NORMAL_POLL_MS;

        try {
            nextDelayMs =
                    performFetchCycle();

        } catch (Throwable error) {
            /*
             * Nothing in LibreLinkUp may terminate the foreground GPS
             * service, even malformed JSON or an unexpected runtime error.
             */
            Log.w(
                    TAG,
                    "Unexpected LibreLinkUp cycle failure",
                    error
            );

            resetSession(
                    "unexpected cycle failure"
            );

            nextDelayMs =
                    RETRY_POLL_MS;

        } finally {
            synchronized (this) {
                fetchInProgress =
                        false;

                if (closed) {
                    return;
                }

                if (runAgainAfterCurrent) {
                    runAgainAfterCurrent =
                            false;

                    scheduleInLocked(
                            0L
                    );

                } else {
                    scheduleInLocked(
                            nextDelayMs
                    );
                }
            }
        }
    }


    private long performFetchCycle() {
        LibreLinkUpStore.Credentials credentials =
                store.credentials();

        if (credentials == null) {
            resetSession(
                    "no credentials configured"
            );

            Log.d(
                    TAG,
                    "No LibreLinkUp credentials configured"
            );

            return NORMAL_POLL_MS;
        }


        String fingerprint =
                sha256Hex(
                        credentials.email
                                + "\n"
                                + credentials.password
                );

        if (!fingerprint.equals(
                credentialFingerprint
        )) {

            credentialFingerprint =
                    fingerprint;

            staleSessionResetDone =
                    false;

            resetSession(
                    "credentials initialized/changed"
            );
        }


        Reading reading;

        try {
            if (authToken != null) {
                try {
                    reading =
                            fetchLatestReading();

                } catch (Exception firstError) {
                    Log.d(
                            TAG,
                            "Fetch failed, re-authenticating: "
                                    + firstError.getMessage()
                    );

                    resetSession(
                            "fetch failed"
                    );

                    login(
                            credentials
                    );

                    reading =
                            fetchLatestReading();
                }

            } else {
                login(
                        credentials
                );

                reading =
                        fetchLatestReading();
            }

        } catch (Exception error) {
            Log.w(
                    TAG,
                    "Login/fetch failed after recovery attempt: "
                            + error.getMessage()
            );

            resetSession(
                    "request failed"
            );

            Log.d(
                    TAG,
                    "Recovery retry in 1 minute"
            );

            return RETRY_POLL_MS;
        }


        if (reading == null) {
            Log.w(
                    TAG,
                    "No glucose reading received"
            );

            return RETRY_POLL_MS;
        }


        lastReading =
                reading;

        long nowMs =
                System.currentTimeMillis();

        long ageMs =
                Math.max(
                        0L,
                        nowMs - reading.timestampMs
                );

        long ageMinutes =
                Math.round(
                        ageMs / 60_000.0
                );

        String displayText =
                formatGlucoseDisplay(
                        reading.mgdl,
                        reading.timestampMs,
                        nowMs
                );

        /*
         * Preserve the raw value and original Libre timestamp so the
         * displayed age can also be reconstructed after a service restart.
         */
        store.saveReading(
                reading.mgdl,
                reading.timestampMs,
                displayText
        );

        publishReading(
                reading
        );

        Log.d(
                TAG,
                "Glucose received: "
                        + displayText
                        + ", age="
                        + ageMinutes
                        + " min"
        );


        /*
         * Exact recovery principle from OpenLibreLinkUp's reliable patch:
         * an HTTP 200 with an old reading is NOT considered healthy.
         */
        if (ageMs >= STALE_REAUTH_MS) {
            Log.w(
                    TAG,
                    "Newest LibreLinkUp reading is stale ("
                            + ageMinutes
                            + " min old)"
            );

            if (!staleSessionResetDone) {
                resetSession(
                        "stale LibreLinkUp reading"
                );

                staleSessionResetDone =
                        true;
            }

            Log.d(
                    TAG,
                    "Stale recovery retry in 1 minute"
            );

            return RETRY_POLL_MS;
        }


        staleSessionResetDone =
                false;

        Log.d(
                TAG,
                "Next normal LibreLinkUp poll in 5 minutes"
        );

        return NORMAL_POLL_MS;
    }


    private void login(
            LibreLinkUpStore.Credentials credentials
    ) throws IOException, JSONException {

        Log.d(
                TAG,
                "Logging in to LibreLinkUp Europe"
        );

        JSONObject body =
                new JSONObject();

        body.put(
                "email",
                credentials.email
        );

        body.put(
                "password",
                credentials.password
        );


        JSONObject response =
                request(
                        "POST",
                        "/llu/auth/login",
                        body,
                        false
                );


        if (response.optInt(
                "status",
                -1
        ) != 0) {

            throw new IOException(
                    "LibreLinkUp login rejected"
            );
        }


        JSONObject data =
                response.optJSONObject(
                        "data"
                );

        if (data == null) {
            throw new IOException(
                    "LibreLinkUp login returned no data"
            );
        }


        /*
         * Europe-only by design. We explicitly do not switch to another
         * regional server behind the user's back.
         */
        if (data.optBoolean(
                "redirect",
                false
        )) {

            throw new IOException(
                    "LibreLinkUp requested region redirect instead of Europe"
            );
        }


        JSONObject authTicket =
                data.optJSONObject(
                        "authTicket"
                );

        String token =
                authTicket == null
                        ? null
                        : authTicket.optString(
                                "token",
                                null
                        );

        JSONObject user =
                data.optJSONObject(
                        "user"
                );

        String userId =
                user == null
                        ? null
                        : user.optString(
                                "id",
                                null
                        );


        if (token == null
                || token.trim().isEmpty()) {

            throw new IOException(
                    "LibreLinkUp returned no auth token"
            );
        }

        if (userId == null
                || userId.trim().isEmpty()) {

            throw new IOException(
                    "LibreLinkUp returned no user ID"
            );
        }


        authToken =
                token.trim();

        accountId =
                sha256Hex(
                        userId
                );

        patientId =
                null;


        Log.d(
                TAG,
                "LibreLinkUp login successful"
        );
    }


    private Reading fetchLatestReading()
            throws IOException, JSONException {

        if (authToken == null) {
            throw new IOException(
                    "Not logged in"
            );
        }


        if (patientId == null) {
            fetchConnection();
        }


        JSONObject response =
                request(
                        "GET",
                        "/llu/connections/"
                                + patientId
                                + "/graph",
                        null,
                        true
                );


        if (response.optInt(
                "status",
                -1
        ) != 0) {

            throw new IOException(
                    "Invalid LibreLinkUp graph response"
            );
        }


        JSONObject data =
                response.optJSONObject(
                        "data"
                );

        if (data == null) {
            throw new IOException(
                    "LibreLinkUp graph returned no data"
            );
        }


        Reading newest =
                null;


        JSONArray graph =
                data.optJSONArray(
                        "graphData"
                );

        if (graph != null) {
            for (int index = 0;
                    index < graph.length();
                    index++) {

                JSONObject item =
                        graph.optJSONObject(
                                index
                        );

                Reading candidate =
                        readingFromJson(
                                item
                        );

                newest =
                        newest(
                                newest,
                                candidate
                        );
            }
        }


        JSONObject connection =
                data.optJSONObject(
                        "connection"
                );

        if (connection != null) {
            JSONObject current =
                    connection.optJSONObject(
                            "glucoseMeasurement"
                    );

            if (current == null) {
                current =
                        connection.optJSONObject(
                                "glucoseItem"
                        );
            }

            newest =
                    newest(
                            newest,
                            readingFromJson(
                                    current
                            )
                    );
        }


        if (newest == null) {
            throw new IOException(
                    "LibreLinkUp graph contains no usable glucose reading"
            );
        }


        return newest;
    }


    private void fetchConnection()
            throws IOException, JSONException {

        JSONObject response =
                request(
                        "GET",
                        "/llu/connections",
                        null,
                        true
                );


        if (response.optInt(
                "status",
                -1
        ) != 0) {

            throw new IOException(
                    "LibreLinkUp connections request failed"
            );
        }


        JSONArray data =
                response.optJSONArray(
                        "data"
                );

        if (data == null
                || data.length() == 0) {

            throw new IOException(
                    "No LibreLinkUp connection found"
            );
        }


        JSONObject connection =
                data.optJSONObject(
                        0
                );

        String id =
                connection == null
                        ? null
                        : connection.optString(
                                "patientId",
                                null
                        );

        if (id == null
                || id.trim().isEmpty()) {

            throw new IOException(
                    "LibreLinkUp returned no patient ID"
            );
        }


        patientId =
                id.trim();
    }


    private JSONObject request(
            String method,
            String path,
            JSONObject body,
            boolean includeAuth
    ) throws IOException, JSONException {

        HttpURLConnection connection =
                null;

        try {
            URL url =
                    new URL(
                            BASE_URL
                                    + path
                    );

            connection =
                    (HttpURLConnection)
                            url.openConnection();

            connection.setRequestMethod(
                    method
            );

            connection.setConnectTimeout(
                    HTTP_TIMEOUT_MS
            );

            connection.setReadTimeout(
                    HTTP_TIMEOUT_MS
            );

            connection.setRequestProperty(
                    "Content-Type",
                    "application/json;charset=UTF-8"
            );

            connection.setRequestProperty(
                    "Accept",
                    "application/json"
            );

            connection.setRequestProperty(
                    "User-Agent",
                    USER_AGENT
            );

            connection.setRequestProperty(
                    "product",
                    PRODUCT
            );

            connection.setRequestProperty(
                    "version",
                    VERSION
            );

            connection.setRequestProperty(
                    "account-id",
                    accountId == null
                            ? ""
                            : accountId
            );


            if (includeAuth
                    && authToken != null) {

                connection.setRequestProperty(
                        "Authorization",
                        "Bearer "
                                + authToken
                );
            }


            if (body != null) {
                byte[] bytes =
                        body.toString()
                                .getBytes(
                                        StandardCharsets.UTF_8
                                );

                connection.setDoOutput(
                        true
                );

                try (OutputStream output =
                             connection.getOutputStream()) {

                    output.write(
                            bytes
                    );
                }
            }


            int status =
                    connection.getResponseCode();

            InputStream input =
                    status >= 200
                            && status < 300
                            ? connection.getInputStream()
                            : connection.getErrorStream();

            String responseText =
                    readAll(
                            input
                    );


            if (status < 200
                    || status >= 300) {

                throw new IOException(
                        "HTTP "
                                + status
                );
            }


            if (responseText == null
                    || responseText.trim().isEmpty()) {

                throw new IOException(
                        "Empty LibreLinkUp response"
                );
            }


            return new JSONObject(
                    responseText
            );

        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }


    private String readAll(
            InputStream input
    ) throws IOException {

        if (input == null) {
            return "";
        }

        StringBuilder result =
                new StringBuilder();

        try (BufferedReader reader =
                     new BufferedReader(
                             new InputStreamReader(
                                     input,
                                     StandardCharsets.UTF_8
                             )
                     )) {

            String line;

            while ((line =
                    reader.readLine()) != null) {

                result.append(
                        line
                );
            }
        }

        return result.toString();
    }


    private Reading readingFromJson(
            JSONObject json
    ) {
        if (json == null) {
            return null;
        }


        double value;

        if (json.has(
                "ValueInMgPerDl"
        )) {

            value =
                    json.optDouble(
                            "ValueInMgPerDl",
                            Double.NaN
                    );

        } else {
            value =
                    json.optDouble(
                            "Value",
                            Double.NaN
                    );
        }


        if (!Double.isFinite(
                value
        )) {

            return null;
        }


        long timestampMs;

        if (json.has(
                "FactoryTimestamp"
        )) {

            timestampMs =
                    parseTimestamp(
                            json.opt(
                                    "FactoryTimestamp"
                            ),
                            true
                    );

        } else {
            timestampMs =
                    parseTimestamp(
                            json.opt(
                                    "Timestamp"
                            ),
                            false
                    );
        }


        if (timestampMs <= 0L) {
            return null;
        }


        return new Reading(
                (int) Math.round(
                        value
                ),
                timestampMs
        );
    }


    private long parseTimestamp(
            Object raw,
            boolean factoryUtc
    ) {
        if (raw == null
                || raw == JSONObject.NULL) {

            return 0L;
        }


        if (raw instanceof Number) {
            long value =
                    ((Number) raw)
                            .longValue();

            return value
                    < 100_000_000_000L
                    ? value * 1000L
                    : value;
        }


        String text =
                String.valueOf(
                        raw
                ).trim();

        if (text.isEmpty()) {
            return 0L;
        }


        if (text.matches(
                "^\\\\d+$"
        )) {

            try {
                long value =
                        Long.parseLong(
                                text
                        );

                return value
                        < 100_000_000_000L
                        ? value * 1000L
                        : value;

            } catch (NumberFormatException ignored) {
            }
        }


        /*
         * Explicit timezone first.
         */
        try {
            return Instant.parse(
                    text
            ).toEpochMilli();

        } catch (DateTimeParseException ignored) {
        }


        try {
            return OffsetDateTime.parse(
                    text
            ).toInstant()
                    .toEpochMilli();

        } catch (DateTimeParseException ignored) {
        }


        /*
         * ISO timestamp without zone.
         *
         * OpenLibreLinkUp deliberately interprets FactoryTimestamp as UTC.
         */
        try {
            LocalDateTime value =
                    LocalDateTime.parse(
                            text,
                            DateTimeFormatter.ISO_LOCAL_DATE_TIME
                    );

            return factoryUtc
                    ? value.toInstant(
                            ZoneOffset.UTC
                    ).toEpochMilli()
                    : value.atZone(
                            ZoneId.systemDefault()
                    ).toInstant()
                            .toEpochMilli();

        } catch (DateTimeParseException ignored) {
        }


        DateTimeFormatter[] formats =
                new DateTimeFormatter[]{
                        DateTimeFormatter.ofPattern(
                                "M/d/uuuu h:mm:ss a",
                                Locale.US
                        ),
                        DateTimeFormatter.ofPattern(
                                "M/d/uuuu H:mm:ss",
                                Locale.US
                        )
                };


        for (DateTimeFormatter format
                : formats) {

            try {
                LocalDateTime value =
                        LocalDateTime.parse(
                                text,
                                format
                        );

                return factoryUtc
                        ? value.toInstant(
                                ZoneOffset.UTC
                        ).toEpochMilli()
                        : value.atZone(
                                ZoneId.systemDefault()
                        ).toInstant()
                                .toEpochMilli();

            } catch (DateTimeParseException ignored) {
            }
        }


        return 0L;
    }


    private Reading newest(
            Reading first,
            Reading second
    ) {
        if (first == null) {
            return second;
        }

        if (second == null) {
            return first;
        }

        return second.timestampMs
                > first.timestampMs
                ? second
                : first;
    }


    static String formatGlucoseDisplay(
            int mgdl,
            long readingTimeMs,
            long nowMs
    ) {
        String value;

        if (mgdl < 40) {
            value =
                    "LOW";

        } else if (mgdl > 400) {
            value =
                    "HIGH";

        } else {
            value =
                    String.format(
                            Locale.US,
                            "%.1f mmol/L",
                            mgdl / 18.0182
                    );
        }

        long ageMs =
                Math.max(
                        0L,
                        nowMs - readingTimeMs
                );

        /*
         * Fresh Libre readings do not need an age indicator.
         * Show it only once the value is more than five full minutes old.
         */
        long ageMinutes =
                ageMs / 60_000L;

        if (ageMinutes <= 5L) {
            return value;
        }

        return value
                + " "
                + ageMinutes
                + " min";
    }


    private void publishCurrentReadingAge() {
        Reading reading =
                lastReading;

        if (reading == null) {
            return;
        }

        publishReading(
                reading
        );
    }


    private void publishReading(
            Reading reading
    ) {
        if (reading == null
                || listener == null) {
            return;
        }

        String displayText =
                formatGlucoseDisplay(
                        reading.mgdl,
                        reading.timestampMs,
                        System.currentTimeMillis()
                );

        try {
            listener.onGlucoseUpdated(
                    displayText,
                    reading.timestampMs
            );

        } catch (RuntimeException error) {
            Log.w(
                    TAG,
                    "Libre listener failed",
                    error
            );
        }
    }


    private void resetSession(
            String reason
    ) {
        authToken =
                null;

        accountId =
                null;

        patientId =
                null;

        if (reason != null
                && !reason.isEmpty()) {

            Log.d(
                    TAG,
                    "LibreLinkUp session reset: "
                            + reason
            );
        }
    }


    private String sha256Hex(
            String value
    ) {
        try {
            MessageDigest digest =
                    MessageDigest.getInstance(
                            "SHA-256"
                    );

            byte[] bytes =
                    digest.digest(
                            value.getBytes(
                                    StandardCharsets.UTF_8
                            )
                    );

            StringBuilder result =
                    new StringBuilder(
                            bytes.length * 2
                    );

            for (byte current
                    : bytes) {

                result.append(
                        String.format(
                                Locale.US,
                                "%02x",
                                current & 0xff
                        )
                );
            }

            return result.toString();

        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(
                    "SHA-256 unavailable",
                    error
            );
        }
    }


    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }

        closed =
                true;

        if (scheduledTask != null) {
            scheduledTask.cancel(
                    false
            );

            scheduledTask =
                    null;
        }

        if (ageRefreshTask != null) {
            ageRefreshTask.cancel(
                    false
            );

            ageRefreshTask =
                    null;
        }

        executor.shutdownNow();

        resetSession(
                "client closed"
        );
    }


    private static final class Reading {

        final int mgdl;
        final long timestampMs;


        Reading(
                int mgdl,
                long timestampMs
        ) {
            this.mgdl =
                    mgdl;

            this.timestampMs =
                    timestampMs;
        }
    }
}
