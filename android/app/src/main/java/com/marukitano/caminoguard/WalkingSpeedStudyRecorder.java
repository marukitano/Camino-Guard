package com.marukitano.caminoguard;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;
import android.util.Log;

import org.maplibre.android.geometry.LatLng;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;


/**
 * Four-week raw walking-speed study.
 *
 * HARD ELIGIBILITY:
 *
 *   1. GPS says MOVING.
 *   2. A MeasurementPath is explicitly LOCKED.
 *   3. Both positions are <= configured off-route threshold from THAT path.
 *
 * Stationary, unlocked and off-route intervals never enter a sample.
 */
final class WalkingSpeedStudyRecorder {

    private static final String TAG =
            "WalkingSpeedStudy";

    private static final String PREFS =
            "walking-speed-study";

    private static final String KEY_START_MS =
            "start_ms";

    private static final String FILE_NAME =
            "walking-speed-study.csv";

    private static final long DAY_MS =
            24L * 60L * 60L * 1000L;

    private static final long STUDY_DURATION_MS =
            28L * DAY_MS;

    /*
     * Same plausibility window as WalkingPerformanceModel.
     */
    private static final long TARGET_SAMPLE_MOVING_MS =
            60_000L;

    private static final long MIN_SAMPLE_MOVING_MS =
            20_000L;

    private static final double MIN_SAMPLE_DISTANCE_M =
            12.0;

    private static final double MIN_VALID_SPEED_KMH =
            0.5;

    private static final double MAX_VALID_SPEED_KMH =
            9.0;

    private static final double MAX_ABS_GRADE_PCT =
            35.0;

    private static final double MAX_GPS_SEGMENT_M =
            250.0;

    /*
     * Navigation may still use the normal application GPS tolerance.
     * Long-term personal walking data is deliberately more conservative.
     */
    private static final float MAX_STUDY_GPS_ACCURACY_M =
            12.0f;


    private final Context context;

    private final SharedPreferences preferences;

    private final File csvFile;


    private int pathVersion =
            Integer.MIN_VALUE;

    private LatLng anchorPosition;

    private double anchorElevationM =
            Double.NaN;

    private double anchorChainageM =
            Double.NaN;

    private long anchorElapsedMs =
            -1L;

    private long anchorWallMs =
            -1L;


    private long bucketMovingMs;

    private double bucketDistanceM;

    /*
     * Plausibility signals only. CSV schema and recorded GPS distance remain
     * unchanged.
     */
    private double bucketRouteTravelM;

    private double bucketStartChainageM =
            Double.NaN;

    private double bucketEndChainageM =
            Double.NaN;

    private long bucketStartedWallMs;

    private double bucketStartElevationM =
            Double.NaN;

    private double bucketEndElevationM =
            Double.NaN;

    private double bucketStartLat =
            Double.NaN;

    private double bucketStartLon =
            Double.NaN;

    private double bucketEndLat =
            Double.NaN;

    private double bucketEndLon =
            Double.NaN;


    private final SimpleDateFormat localTime =
            new SimpleDateFormat(
                    "yyyy-MM-dd'T'HH:mm:ssZ",
                    Locale.US
            );


    WalkingSpeedStudyRecorder(
            Context context
    ) {
        this.context =
                context.getApplicationContext();

        preferences =
                this.context.getSharedPreferences(
                        PREFS,
                        Context.MODE_PRIVATE
                );

        csvFile =
                new File(
                        this.context.getFilesDir(),
                        FILE_NAME
                );
    }


    synchronized void noteGpsFix(
            Location location,
            LockedMeasurementPathStore.Snapshot locked,
            MeasurementPathProjection.Result projection
    ) {
        if (location == null) {
            breakSampleChain(
                    true
            );
            return;
        }

        /*
         * HARD MOVING GATE.
         *
         * No accelerometer is introduced. Android's GPS speed estimate is used.
         * If GPS does not provide a speed, the fix is NOT accepted for the
         * four-week experiment.
         */
        if (!location.hasSpeed()
                || !Float.isFinite(
                        location.getSpeed()
                )
                || location.getSpeed()
                        * 3.6f
                        < MIN_VALID_SPEED_KMH) {

            noteNotMoving();
            return;
        }

        /*
         * HARD STUDY-ACCURACY GATE.
         *
         * Four-week training data fails closed unless Android reports a
         * substantially better fix than the general navigation limit.
         */
        if (!location.hasAccuracy()
                || !Float.isFinite(
                        location.getAccuracy()
                )
                || location.getAccuracy()
                > MAX_STUDY_GPS_ACCURACY_M) {

            breakSampleChain(
                    true
            );

            return;
        }

        /*
         * HARD LOCKED GATE.
         *
         * The service supplied the authoritative persisted lock snapshot for
         * this exact GPS fix.
         */
        if (locked == null
                || locked.path == null) {

            breakSampleChain(
                    true
            );
            return;
        }

        LatLng position =
                new LatLng(
                        location.getLatitude(),
                        location.getLongitude()
                );

        /*
         * HARD ON-ROAD GATE.
         *
         * >20 m therefore never contributes distance, time, grade or speed.
         */
        if (projection == null) {
            breakSampleChain(
                    true
            );
            return;
        }

        /*
         * Study samples need both real route elevation and trustworthy
         * selected-path chainage.
         */
        if (!Double.isFinite(
                projection.elevationM
        )
                || !Double.isFinite(
                projection.chainageM
        )) {

            breakSampleChain(
                    true
            );

            return;
        }

        /*
         * The locked route changed. Never bridge two tours with one sample.
         */
        if (pathVersion != Integer.MIN_VALUE
                && pathVersion != locked.version) {

            breakSampleChain(
                    true
            );
        }

        pathVersion =
                locked.version;

        long elapsedMs =
                location.getElapsedRealtimeNanos()
                        > 0L
                        ? location.getElapsedRealtimeNanos()
                        / 1_000_000L
                        : android.os.SystemClock.elapsedRealtime();

        long wallMs =
                location.getTime() > 0L
                        ? location.getTime()
                        : System.currentTimeMillis();

        long studyStartMs =
                ensureStudyStart(
                        wallMs
                );

        if (wallMs < studyStartMs
                || wallMs >= studyStartMs
                + STUDY_DURATION_MS) {

            breakSampleChain(
                    false
            );
            return;
        }

        if (anchorPosition == null
                || anchorElapsedMs < 0L) {

            setAnchor(
                    position,
                    projection.elevationM,
                    projection.chainageM,
                    elapsedMs,
                    wallMs
            );

            return;
        }

        long deltaMs =
                elapsedMs
                        - anchorElapsedMs;

        if (deltaMs <= 0L
                || deltaMs > 2L
                * 60L
                * 1000L) {

            breakSampleChain(
                    true
            );

            setAnchor(
                    position,
                    projection.elevationM,
                    projection.chainageM,
                    elapsedMs,
                    wallMs
            );

            return;
        }

        double segmentM =
                GeoMath.distanceMeters(
                        anchorPosition,
                        position
                );

        if (!Double.isFinite(
                segmentM
        )
                || segmentM <= 0.0
                || segmentM > MAX_GPS_SEGMENT_M) {

            breakSampleChain(
                    true
            );

            setAnchor(
                    position,
                    projection.elevationM,
                    projection.chainageM,
                    elapsedMs,
                    wallMs
            );

            return;
        }

        double routeSegmentM =
                Math.abs(
                        projection.chainageM
                                - anchorChainageM
                );

        if (!Double.isFinite(
                routeSegmentM
        )) {
            breakSampleChain(
                    true
            );

            setAnchor(
                    position,
                    projection.elevationM,
                    projection.chainageM,
                    elapsedMs,
                    wallMs
            );

            return;
        }

        if (bucketMovingMs == 0L) {
            bucketStartedWallMs =
                    anchorWallMs;

            bucketStartElevationM =
                    anchorElevationM;

            bucketStartChainageM =
                    anchorChainageM;

            bucketStartLat =
                    anchorPosition.getLatitude();

            bucketStartLon =
                    anchorPosition.getLongitude();
        }

        bucketMovingMs +=
                deltaMs;

        bucketDistanceM +=
                segmentM;

        bucketRouteTravelM +=
                routeSegmentM;

        bucketEndChainageM =
                projection.chainageM;

        bucketEndElevationM =
                projection.elevationM;

        bucketEndLat =
                position.getLatitude();

        bucketEndLon =
                position.getLongitude();

        if (bucketMovingMs
                >= TARGET_SAMPLE_MOVING_MS) {

            flushBucket();
            resetBucket();
        }

        setAnchor(
                position,
                projection.elevationM,
                projection.chainageM,
                elapsedMs,
                wallMs
        );
    }


    synchronized void noteNotMoving() {
        /*
         * Valid moving time collected before a stop may still be useful,
         * but the stationary interval itself can never enter the sample.
         */
        flushPartialBucket();

        resetBucket();
        resetAnchor();
    }


    synchronized void close() {
        flushPartialBucket();

        resetBucket();
        resetAnchor();
    }


    private void breakSampleChain(
            boolean keepValidPartial
    ) {
        if (keepValidPartial) {
            flushPartialBucket();
        }

        resetBucket();
        resetAnchor();
    }


    private void flushPartialBucket() {
        if (bucketMovingMs
                >= MIN_SAMPLE_MOVING_MS) {

            flushBucket();
        }
    }


    private void flushBucket() {
        if (bucketMovingMs
                < MIN_SAMPLE_MOVING_MS
                || bucketDistanceM
                < MIN_SAMPLE_DISTANCE_M
                || !Double.isFinite(
                        bucketStartElevationM
                )
                || !Double.isFinite(
                        bucketEndElevationM
                )) {

            return;
        }

        double routeNetProgressM =
                Math.abs(
                        bucketEndChainageM
                                - bucketStartChainageM
                );

        if (!WalkingStudyProgressGate.accepts(
                bucketDistanceM,
                bucketRouteTravelM,
                routeNetProgressM
        )) {

            return;
        }

        double gradePct =
                (
                        bucketEndElevationM
                                - bucketStartElevationM
                )
                        / bucketDistanceM
                        * 100.0;

        double speedKmh =
                bucketDistanceM
                        / (
                        bucketMovingMs
                                / 3_600_000.0
                )
                        / 1000.0;

        if (!Double.isFinite(
                gradePct
        )
                || Math.abs(
                        gradePct
                )
                > MAX_ABS_GRADE_PCT
                || !Double.isFinite(
                        speedKmh
                )
                || speedKmh
                < MIN_VALID_SPEED_KMH
                || speedKmh
                > MAX_VALID_SPEED_KMH) {

            return;
        }

        long endedWallMs =
                bucketStartedWallMs
                        + bucketMovingMs;

        appendCsv(
                bucketStartedWallMs,
                endedWallMs,
                bucketMovingMs,
                bucketDistanceM,
                gradePct,
                speedKmh
        );
    }


    private void appendCsv(
            long startedAtMs,
            long endedAtMs,
            long movingMs,
            double distanceM,
            double gradePct,
            double speedKmh
    ) {
        long studyStartMs =
                preferences.getLong(
                        KEY_START_MS,
                        -1L
                );

        if (studyStartMs <= 0L
                || endedAtMs < studyStartMs
                || endedAtMs >= studyStartMs
                + STUDY_DURATION_MS) {

            return;
        }

        int studyDay =
                (int) (
                        (
                                endedAtMs
                                        - studyStartMs
                        )
                                / DAY_MS
                )
                        + 1;

        int grade1Pct =
                (int) Math.round(
                        gradePct
                );

        boolean writeHeader =
                !csvFile.exists()
                        || csvFile.length()
                        == 0L;

        try (
                FileOutputStream output =
                        new FileOutputStream(
                                csvFile,
                                true
                        );

                OutputStreamWriter streamWriter =
                        new OutputStreamWriter(
                                output,
                                "UTF-8"
                        );

                BufferedWriter writer =
                        new BufferedWriter(
                                streamWriter
                        )
        ) {
            if (writeHeader) {
                writer.write(
                        "study_start_ms,"
                                + "study_day,"
                                + "sample_started_at_ms,"
                                + "sample_ended_at_ms,"
                                + "sample_ended_local,"
                                + "moving_ms,"
                                + "distance_m,"
                                + "grade_pct,"
                                + "grade_1pct,"
                                + "speed_kmh,"
                                + "start_lat,"
                                + "start_lon,"
                                + "end_lat,"
                                + "end_lon,"
                                + "locked_path_version"
                );

                writer.newLine();
            }

            writer.write(
                    String.format(
                            Locale.US,
                            "%d,%d,%d,%d,%s,%d,"
                                    + "%.3f,%.3f,%d,%.4f,"
                                    + "%.8f,%.8f,%.8f,%.8f,%d",
                            studyStartMs,
                            studyDay,
                            startedAtMs,
                            endedAtMs,
                            csv(
                                    localTime.format(
                                            new Date(
                                                    endedAtMs
                                            )
                                    )
                            ),
                            movingMs,
                            distanceM,
                            gradePct,
                            grade1Pct,
                            speedKmh,
                            bucketStartLat,
                            bucketStartLon,
                            bucketEndLat,
                            bucketEndLon,
                            pathVersion
                    )
            );

            writer.newLine();

        } catch (Exception error) {
            /*
             * Study logging must never disturb navigation.
             */
            Log.e(
                    TAG,
                    "Could not write study sample",
                    error
            );
        }
    }


    private long ensureStudyStart(
            long wallMs
    ) {
        long value =
                preferences.getLong(
                        KEY_START_MS,
                        -1L
                );

        if (value > 0L) {
            return value;
        }

        /*
         * The 28 days start with the first actually eligible:
         *
         * MOVING + LOCKED + ON-ROAD
         *
         * fix, not merely when the application was installed.
         */
        preferences.edit()
                .putLong(
                        KEY_START_MS,
                        wallMs
                )
                .commit();

        return wallMs;
    }


    private void setAnchor(
            LatLng position,
            double elevationM,
            double chainageM,
            long elapsedMs,
            long wallMs
    ) {
        anchorPosition =
                new LatLng(
                        position.getLatitude(),
                        position.getLongitude()
                );

        anchorElevationM =
                elevationM;

        anchorChainageM =
                chainageM;

        anchorElapsedMs =
                elapsedMs;

        anchorWallMs =
                wallMs;
    }


    private void resetAnchor() {
        anchorPosition =
                null;

        anchorElevationM =
                Double.NaN;

        anchorChainageM =
                Double.NaN;

        anchorElapsedMs =
                -1L;

        anchorWallMs =
                -1L;
    }


    private void resetBucket() {
        bucketMovingMs =
                0L;

        bucketDistanceM =
                0.0;

        bucketRouteTravelM =
                0.0;

        bucketStartChainageM =
                Double.NaN;

        bucketEndChainageM =
                Double.NaN;

        bucketStartedWallMs =
                0L;

        bucketStartElevationM =
                Double.NaN;

        bucketEndElevationM =
                Double.NaN;

        bucketStartLat =
                Double.NaN;

        bucketStartLon =
                Double.NaN;

        bucketEndLat =
                Double.NaN;

        bucketEndLon =
                Double.NaN;
    }


    private String csv(
            String value
    ) {
        if (value == null) {
            return "\"\"";
        }

        return "\""
                + value.replace(
                        "\"",
                        "\"\""
                )
                + "\"";
    }
}
