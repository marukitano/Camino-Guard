package com.marukitano.caminoguard;

import android.location.Location;
import android.os.SystemClock;

import java.util.Calendar;
import java.util.Locale;


/**
 * Pebble presentation adapter for the explicitly locked MeasurementPath.
 *
 * Route geometry, progress and timetable remain Android-owned.
 * No global nearest-Camino lookup and no orientation sensor is used here.
 */
final class CaminoPebbleRoutePublisher {

    private static final long SEND_INTERVAL_MS =
            5_000L;

    /*
     * Presentation only.
     *
     * CaminoTrackingService supplies the already-built authoritative
     * CaminoTimetableState. Pebble never runs timetable/ETA route math here.
     */
    private final CaminoPebbleBridge bridge;

    private int pathVersion =
            Integer.MIN_VALUE;

    private double lastGoodChainageM =
            Double.NaN;

    private boolean sentAnyState;
    private boolean lastAlarmActive;
    private boolean lastRouteValid;

    /*
     * Motion transitions bypass the ordinary five-second telemetry throttle.
     * This guarantees immediate 0.0 km/h / ETA presentation on pause and an
     * immediate refresh when walking resumes.
     */
    private boolean hasMotionState;
    private boolean lastStationary;

    private long lastEvaluationElapsedMs =
            Long.MIN_VALUE;

    private String lastSentNextName;
    private String lastSentNextDistance;
    private String lastSentNextTime;
    private String lastSentSpeed;


    CaminoPebbleRoutePublisher(
            CaminoPebbleBridge bridge
    ) {
        if (bridge == null) {
            throw new IllegalArgumentException(
                    "bridge must not be null"
            );
        }

        this.bridge =
                bridge;
    }


    synchronized void onGpsFix(
            Location location,
            LockedMeasurementPathStore.Snapshot locked,
            boolean onRoute,
            CaminoTimetableState timetableState,
            boolean stationary
    ) {
        if (locked == null
                || locked.path == null) {

            pathVersion =
                    Integer.MIN_VALUE;

            hasMotionState =
                    false;

            /*
             * The watch clears route + speed when ROUTE_VALID becomes false.
             */
            sendIfChanged(
                    "--",
                    "--",
                    "--",
                    "--",
                    false,
                    false
            );

            return;
        }

        /*
         * NO_ROUTE is independent of GPS availability. A locked route still
         * needs a physical position before ON_ROUTE/OFF_ROUTE can be shown.
         */
        if (location == null) {
            return;
        }

        boolean pathChanged =
                pathVersion
                        != locked.version;

        if (pathChanged) {
            pathVersion =
                    locked.version;
        }

        boolean alarmActive =
                !onRoute;

        boolean routeStateChanged =
                !sentAnyState
                        || pathChanged
                        || alarmActive
                        != lastAlarmActive
                        || !lastRouteValid;

        boolean motionChanged =
                !hasMotionState
                        || stationary
                        != lastStationary;

        long nowElapsed =
                SystemClock.elapsedRealtime();

        /*
         * OFF ROUTE remains a transition, not a telemetry stream.
         * Motion changes while the alarm is visible need no extra packet.
         */
        if (alarmActive) {
            if (routeStateChanged) {
                sendIfChanged(
                        "--",
                        "--",
                        "--",
                        "--",
                        true,
                        true
                );
            }

            hasMotionState =
                    true;

            lastStationary =
                    stationary;

            return;
        }

        boolean immediateEvaluation =
                routeStateChanged
                        || motionChanged;

        if (!immediateEvaluation
                && lastEvaluationElapsedMs
                != Long.MIN_VALUE
                && nowElapsed
                - lastEvaluationElapsedMs
                < SEND_INTERVAL_MS) {

            return;
        }

        lastEvaluationElapsedMs =
                nowElapsed;

        String nextName =
                "--";

        String nextDistance =
                "--";

        String nextTime =
                "--";

        Values values =
                valuesFromState(
                        timetableState
                );

        if (values != null) {
            nextName =
                    values.name;

            nextDistance =
                    values.distance;

            nextTime =
                    values.time;
        }

        sendIfChanged(
                nextName,
                nextDistance,
                nextTime,
                formatSpeed(
                        location,
                        stationary
                ),
                false,
                true
        );

        hasMotionState =
                true;

        lastStationary =
                stationary;
    }


    private Values valuesFromState(
            CaminoTimetableState state
    ) {
        if (state == null
                || !state.hasNextStop()) {

            return null;
        }

        CaminoTimetableStop next =
                state.nextStop;

        double remainingDistanceM =
                Math.max(
                        0.0,
                        next.chainageM
                                - state.currentChainageM
                );

        int remainingMinutes =
                forwardMinutes(
                        currentClockMinutes(),
                        next.arrivalMinutesOfDay
                );

        return new Values(
                next.name,
                formatDistance(
                        remainingDistanceM
                ),
                formatDuration(
                        remainingMinutes
                )
        );
    }


    private void sendIfChanged(
            String nextName,
            String nextDistance,
            String nextTime,
            String speed,
            boolean alarmActive,
            boolean routeValid
    ) {
        if (sentAnyState
                && alarmActive == lastAlarmActive
                && routeValid == lastRouteValid
                && sameText(
                        nextName,
                        lastSentNextName
                )
                && sameText(
                        nextDistance,
                        lastSentNextDistance
                )
                && sameText(
                        nextTime,
                        lastSentNextTime
                )
                && sameText(
                        speed,
                        lastSentSpeed
                )) {

            return;
        }

        boolean firstSend =
                !sentAnyState;

        String nextNameDelta =
                firstSend
                        || !sameText(
                                nextName,
                                lastSentNextName
                        )
                        ? nextName
                        : null;

        String nextDistanceDelta =
                firstSend
                        || !sameText(
                                nextDistance,
                                lastSentNextDistance
                        )
                        ? nextDistance
                        : null;

        String nextTimeDelta =
                firstSend
                        || !sameText(
                                nextTime,
                                lastSentNextTime
                        )
                        ? nextTime
                        : null;

        String speedDelta =
                firstSend
                        || !sameText(
                                speed,
                                lastSentSpeed
                        )
                        ? speed
                        : null;

        Boolean alarmDelta =
                firstSend
                        || alarmActive
                        != lastAlarmActive
                        ? Boolean.valueOf(
                                alarmActive
                        )
                        : null;

        Boolean routeValidDelta =
                firstSend
                        || routeValid
                        != lastRouteValid
                        ? Boolean.valueOf(
                                routeValid
                        )
                        : null;

        bridge.sendRouteState(
                nextNameDelta,
                nextDistanceDelta,
                nextTimeDelta,
                speedDelta,
                alarmDelta,
                routeValidDelta
        );

        sentAnyState =
                true;

        lastAlarmActive =
                alarmActive;

        lastRouteValid =
                routeValid;

        lastSentNextName =
                nextName;

        lastSentNextDistance =
                nextDistance;

        lastSentNextTime =
                nextTime;

        lastSentSpeed =
                speed;
    }


    private static boolean sameText(
            String first,
            String second
    ) {
        return first == null
                ? second == null
                : first.equals(
                        second
                );
    }


    private static int currentClockMinutes() {
        Calendar now =
                Calendar.getInstance();

        return now.get(
                Calendar.HOUR_OF_DAY
        )
                * 60
                + now.get(
                Calendar.MINUTE
        );
    }


    private static int forwardMinutes(
            int from,
            int to
    ) {
        int value =
                (to - from)
                        % (24 * 60);

        if (value < 0) {
            value +=
                    24 * 60;
        }

        return value;
    }


    private static String formatDistance(
            double distanceM
    ) {
        if (distanceM < 1000.0) {
            return String.format(
                    Locale.US,
                    "%.0f m",
                    distanceM
            );
        }

        return String.format(
                Locale.US,
                "%.1f km",
                distanceM / 1000.0
        );
    }


    private static String formatDuration(
            int minutes
    ) {
        int safe =
                Math.max(
                        0,
                        minutes
                );

        if (safe < 60) {
            return safe + " min";
        }

        int hours =
                safe / 60;

        int remainder =
                safe % 60;

        return remainder == 0
                ? hours + " h"
                : hours + " h " + remainder + " min";
    }


    private static String formatSpeed(
            Location location,
            boolean stationary
    ) {
        if (stationary) {
            return "0.0 km/h";
        }

        if (location == null
                || !location.hasSpeed()
                || !Float.isFinite(
                location.getSpeed()
        )
                || location.getSpeed() < 0.0f) {

            return "--";
        }

        return String.format(
                Locale.US,
                "%.1f km/h",
                location.getSpeed() * 3.6f
        );
    }


    private static final class Values {

        final String name;
        final String distance;
        final String time;


        Values(
                String name,
                String distance,
                String time
        ) {
            this.name =
                    name;

            this.distance =
                    distance;

            this.time =
                    time;
        }
    }
}
