package com.marukitano.caminoguard;

import android.content.Context;
import android.location.Location;
import android.os.SystemClock;

import org.maplibre.android.geometry.LatLng;

import java.util.Calendar;
import java.util.List;
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

    private final CaminoTimetablePlanBuilder planBuilder;

    private final CaminoTimetableEngine timetableEngine =
            new CaminoTimetableEngine();

    private final CaminoPebbleBridge bridge;

    private int pathVersion =
            Integer.MIN_VALUE;

    private double lastGoodChainageM =
            Double.NaN;

    private boolean sentAnyState;
    private boolean lastAlarmActive;
    private boolean lastRouteValid;

    private long lastEvaluationElapsedMs =
            Long.MIN_VALUE;

    private String lastSentNextName;
    private String lastSentNextDistance;
    private String lastSentNextTime;
    private String lastSentSpeed;


    CaminoPebbleRoutePublisher(
            Context context,
            WalkingPerformanceModel performanceModel,
            CaminoPebbleBridge bridge
    ) {
        planBuilder =
                new CaminoTimetablePlanBuilder(
                        performanceModel
                );

        this.bridge =
                bridge;
    }


    synchronized void onGpsFix(
            Location location,
            LockedMeasurementPathStore.Snapshot locked,
            MeasurementPathProjection.Result projection
    ) {
        if (locked == null
                || locked.path == null) {

            pathVersion =
                    Integer.MIN_VALUE;

            lastGoodChainageM =
                    Double.NaN;

            /*
             * The watch clears route + speed when ROUTE_VALID becomes false.
             * Send that transition once; repeated unlocked GPS fixes carry no
             * new visible information.
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
         * NO_ROUTE is independent of GPS availability. A real locked route,
         * however, still requires a physical position before ON_ROUTE or
         * OFF_ROUTE can be determined.
         */
        if (location == null) {
            return;
        }

        boolean pathChanged =
                pathVersion != locked.version;

        if (pathChanged) {
            pathVersion =
                    locked.version;

            lastGoodChainageM =
                    Double.NaN;
        }

        MeasurementPath path =
                locked.path;

        boolean alarmActive =
                projection == null;

        if (projection != null) {
            lastGoodChainageM =
                    projection.chainageM;
        }

        boolean stateChanged =
                !sentAnyState
                        || pathChanged
                        || alarmActive != lastAlarmActive
                        || !lastRouteValid;

        long nowElapsed =
                SystemClock.elapsedRealtime();

        /*
         * OFF ROUTE is a transition, not a five-second telemetry stream.
         * The watch hides route values while the alarm is active.
         */
        if (alarmActive) {
            if (stateChanged) {
                sendIfChanged(
                        "--",
                        "--",
                        "--",
                        "--",
                        true,
                        true
                );
            }

            return;
        }

        if (!stateChanged
                && lastEvaluationElapsedMs != Long.MIN_VALUE
                && nowElapsed - lastEvaluationElapsedMs
                < SEND_INTERVAL_MS) {

            return;
        }

        lastEvaluationElapsedMs =
                nowElapsed;

        /*
         * During OFF ROUTE the selected-path chainage freezes at the last
         * trustworthy projection. The watch hides route values while alarm is
         * active, but retaining them makes re-entry deterministic.
         */
        double chainageM =
                projection != null
                        ? projection.chainageM
                        : lastGoodChainageM;

        String nextName =
                "--";

        String nextDistance =
                "--";

        String nextTime =
                "--";

        if (Double.isFinite(
                chainageM
        )
                && path.timetableStops != null
                && path.timetableStops.size() >= 2) {

            Values values =
                    buildValues(
                            path,
                            chainageM
                    );

            if (values != null) {
                nextName =
                        values.name;

                nextDistance =
                        values.distance;

                nextTime =
                        values.time;
            }
        }

        sendIfChanged(
                nextName,
                nextDistance,
                nextTime,
                formatSpeed(
                        location
                ),
                false,
                true
        );
    }


    private Values buildValues(
            MeasurementPath path,
            double chainageM
    ) {
        List<CaminoTimetableStopPlan> plans =
                planBuilder.build(
                        path
                );

        if (plans.size() < 2) {
            return null;
        }

        double elapsedSecondsAtCurrent =
                planBuilder.elapsedSecondsAtChainage(
                        path,
                        chainageM
                );

        if (!Double.isFinite(
                elapsedSecondsAtCurrent
        )) {

            return null;
        }

        int nowMinutes =
                currentClockMinutes();

        int startMinutes =
                nowMinutes
                        - (int) Math.round(
                        elapsedSecondsAtCurrent
                                / 60.0
                );

        CaminoTimetableState state =
                timetableEngine.build(
                        plans,
                        startMinutes,
                        chainageM
                );

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
                        nowMinutes,
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
            Location location
    ) {
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
