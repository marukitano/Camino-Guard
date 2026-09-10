package com.marukitano.caminoguard;

import android.location.Location;
import android.os.SystemClock;

import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * Pebble presentation adapter for the explicitly locked MeasurementPath.
 *
 * Android remains the single authority for route geometry, progress and ETA.
 * The Pebble watchapp only requests one of three presentation pages:
 * dashboard, timetable stop browser, or a tiny local route map.
 */
final class CaminoPebbleRoutePublisher
        implements CaminoPebbleSession.Listener {

    private static final long SEND_INTERVAL_MS =
            5_000L;

    private static final long TRACE_MAX_AGE_MS =
            5L * 60L * 1000L;

    private static final float TRACE_SPACING_M =
            4.0f;

    private static final int TRACE_MAX_POINTS =
            64;

    private static final double MAP_CAPTURE_M =
            120.0;

    private static final int MAP_MAX_ROUTE_POINTS =
            48;

    private static final int MAP_MAX_TRAIL_POINTS =
            30;

    /* Integer sentinel used to explicitly clear a metric on the watch. */
    private static final int UNKNOWN_METRIC =
            Integer.MIN_VALUE;

    private final CaminoPebbleBridge bridge;

    private int pathVersion =
            Integer.MIN_VALUE;

    private long lastEvaluationElapsedMs =
            Long.MIN_VALUE;

    private boolean hasMotionState;
    private boolean lastStationary;

    private Location latestLocation;
    private LockedMeasurementPathStore.Snapshot latestLocked;
    private CaminoTimetableState latestTimetableState;
    private double latestFlatSpeedKmh =
            Double.NaN;
    private boolean latestStationary;

    private int visiblePage;
    private int selectedStopIndex =
            -1;

    private boolean sentAnyDashboard;
    private boolean forceDashboardFull =
            true;

    private String lastSentSpeed;
    private Integer lastSentTemperatureCurrent;
    private Integer lastSentTemperatureMin;
    private Integer lastSentTemperatureMax;
    private Integer lastSentSunrise;
    private Integer lastSentSunset;
    private Integer lastSentElevationCurrent;
    private Integer lastSentElevationMin;
    private Integer lastSentElevationMax;

    private CaminoPebbleWeatherClient.Snapshot weather;

    private int elevationScalePathVersion =
            Integer.MIN_VALUE;
    private Integer routeElevationMinM;
    private Integer routeElevationMaxM;

    private boolean forceStopSend =
            true;

    private boolean forceMapSend =
            true;

    private final Deque<Location> recentTrail =
            new ArrayDeque<>();

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

        visiblePage =
                CaminoPebbleSession.page();

        CaminoPebbleSession.setListener(
                this
        );
    }

    synchronized void onGpsFix(
            Location location,
            LockedMeasurementPathStore.Snapshot locked,
            boolean onRoute,
            CaminoTimetableState timetableState,
            double flatSpeedKmh,
            boolean stationary
    ) {
        latestLocation =
                location == null
                        ? null
                        : new Location(
                                location
                        );

        latestLocked =
                locked;

        latestTimetableState =
                timetableState;

        latestFlatSpeedKmh =
                flatSpeedKmh;

        latestStationary =
                stationary;

        if (location != null
                && !stationary) {

            rememberTrailPoint(
                    location
            );
        }

        int newPathVersion =
                locked == null
                        || locked.path == null
                        ? Integer.MIN_VALUE
                        : locked.version;

        boolean pathChanged =
                pathVersion
                        != newPathVersion;

        if (pathChanged) {
            pathVersion =
                    newPathVersion;

            selectedStopIndex =
                    -1;

            forceDashboardFull =
                    true;

            forceStopSend =
                    true;

            forceMapSend =
                    true;

            refreshElevationScale(
                    locked
            );
        }

        boolean motionChanged =
                !hasMotionState
                        || stationary
                        != lastStationary;

        hasMotionState =
                true;

        lastStationary =
                stationary;

        if (!CaminoPebbleSession.isWatchOpen()) {
            return;
        }

        long nowElapsed =
                SystemClock.elapsedRealtime();

        boolean immediate =
                pathChanged
                        || motionChanged
                        || forceDashboardFull
                        || forceStopSend
                        || forceMapSend;

        if (!immediate
                && lastEvaluationElapsedMs
                != Long.MIN_VALUE
                && nowElapsed
                - lastEvaluationElapsedMs
                < SEND_INTERVAL_MS) {

            return;
        }

        lastEvaluationElapsedMs =
                nowElapsed;

        sendCurrentPage(
                false
        );
    }

    @Override
    public synchronized void onPebbleOpened() {
        forceDashboardFull =
                true;

        forceStopSend =
                true;

        forceMapSend =
                true;

        lastEvaluationElapsedMs =
                Long.MIN_VALUE;

        bridge.sendCachedGlucose();

        sendCurrentPage(
                true
        );
    }

    @Override
    public synchronized void onPebbleClosed() {
        /* No Android service lifecycle change belongs to the watchapp. */
    }

    @Override
    public synchronized void onPebblePageChanged(
            int page
    ) {
        visiblePage =
                Math.max(
                        0,
                        Math.min(
                                2,
                                page
                        )
                );

        lastEvaluationElapsedMs =
                Long.MIN_VALUE;

        if (visiblePage == 0) {
            forceDashboardFull =
                    true;

        } else if (visiblePage == 1) {
            selectedStopIndex =
                    findNextStopIndex(
                            latestTimetableState
                    );

            forceStopSend =
                    true;

        } else {
            forceMapSend =
                    true;
        }

        if (CaminoPebbleSession.isWatchOpen()) {
            sendCurrentPage(
                    true
            );
        }
    }

    @Override
    public synchronized void onPebbleStopDelta(
            int delta
    ) {
        if (visiblePage != 1) {
            return;
        }

        List<CaminoTimetableStop> stops =
                allStops(
                        latestTimetableState
                );

        if (stops.isEmpty()) {
            selectedStopIndex =
                    -1;

        } else {
            if (selectedStopIndex < 0
                    || selectedStopIndex
                    >= stops.size()) {

                selectedStopIndex =
                        findNextStopIndex(
                                latestTimetableState
                        );
            }

            selectedStopIndex =
                    Math.max(
                            0,
                            Math.min(
                                    stops.size() - 1,
                                    selectedStopIndex
                                            + (delta < 0 ? -1 : 1)
                            )
                    );
        }

        forceStopSend =
                true;

        sendTimetableStop();
    }

    private void sendCurrentPage(
            boolean force
    ) {
        if (!CaminoPebbleSession.isWatchOpen()) {
            return;
        }

        if (visiblePage == 0) {
            sendDashboard(
                    force
            );

        } else if (visiblePage == 1) {
            sendTimetableStop();

        } else {
            sendMiniMap();
        }
    }

    private void sendDashboard(
            boolean force
    ) {
        Location location =
                latestLocation;

        if (location != null) {
            bridge.requestWeather(
                    location,
                    this::onWeatherSnapshot
            );
        }

        ElevationValues elevation =
                elevationValues();

        int temperatureCurrent =
                weather == null
                        ? UNKNOWN_METRIC
                        : weather.currentTenthsC;

        int temperatureMin =
                weather == null
                        ? UNKNOWN_METRIC
                        : weather.minTenthsC;

        int temperatureMax =
                weather == null
                        ? UNKNOWN_METRIC
                        : weather.maxTenthsC;

        int sunrise =
                weather == null
                        ? UNKNOWN_METRIC
                        : weather.sunriseMinutes;

        int sunset =
                weather == null
                        ? UNKNOWN_METRIC
                        : weather.sunsetMinutes;

        int elevationCurrent =
                elevation == null
                        ? UNKNOWN_METRIC
                        : elevation.currentM;

        int elevationMin =
                elevation == null
                        ? UNKNOWN_METRIC
                        : elevation.minM;

        int elevationMax =
                elevation == null
                        ? UNKNOWN_METRIC
                        : elevation.maxM;

        String speed =
                formatSpeed(
                        location,
                        latestStationary
                );

        boolean fullSend =
                force
                        || forceDashboardFull
                        || !sentAnyDashboard;

        if (!fullSend
                && sameText(
                        speed,
                        lastSentSpeed
                )
                && sameInt(
                        temperatureCurrent,
                        lastSentTemperatureCurrent
                )
                && sameInt(
                        temperatureMin,
                        lastSentTemperatureMin
                )
                && sameInt(
                        temperatureMax,
                        lastSentTemperatureMax
                )
                && sameInt(
                        sunrise,
                        lastSentSunrise
                )
                && sameInt(
                        sunset,
                        lastSentSunset
                )
                && sameInt(
                        elevationCurrent,
                        lastSentElevationCurrent
                )
                && sameInt(
                        elevationMin,
                        lastSentElevationMin
                )
                && sameInt(
                        elevationMax,
                        lastSentElevationMax
                )) {

            return;
        }

        String speedDelta =
                fullSend
                        || !sameText(
                                speed,
                                lastSentSpeed
                        )
                        ? speed
                        : null;

        Integer temperatureCurrentDelta =
                intDelta(
                        fullSend,
                        temperatureCurrent,
                        lastSentTemperatureCurrent
                );

        Integer temperatureMinDelta =
                intDelta(
                        fullSend,
                        temperatureMin,
                        lastSentTemperatureMin
                );

        Integer temperatureMaxDelta =
                intDelta(
                        fullSend,
                        temperatureMax,
                        lastSentTemperatureMax
                );

        Integer sunriseDelta =
                intDelta(
                        fullSend,
                        sunrise,
                        lastSentSunrise
                );

        Integer sunsetDelta =
                intDelta(
                        fullSend,
                        sunset,
                        lastSentSunset
                );

        Integer elevationCurrentDelta =
                intDelta(
                        fullSend,
                        elevationCurrent,
                        lastSentElevationCurrent
                );

        Integer elevationMinDelta =
                intDelta(
                        fullSend,
                        elevationMin,
                        lastSentElevationMin
                );

        Integer elevationMaxDelta =
                intDelta(
                        fullSend,
                        elevationMax,
                        lastSentElevationMax
                );

        forceDashboardFull =
                false;

        bridge.sendDashboardState(
                speedDelta,
                temperatureCurrentDelta,
                temperatureMinDelta,
                temperatureMaxDelta,
                sunriseDelta,
                sunsetDelta,
                elevationCurrentDelta,
                elevationMinDelta,
                elevationMaxDelta,
                delivered -> {
                    if (delivered) {
                        return;
                    }

                    synchronized (CaminoPebbleRoutePublisher.this) {
                        forceDashboardFull =
                                true;
                    }
                }
        );

        sentAnyDashboard =
                true;

        lastSentSpeed =
                speed;

        lastSentTemperatureCurrent =
                temperatureCurrent;

        lastSentTemperatureMin =
                temperatureMin;

        lastSentTemperatureMax =
                temperatureMax;

        lastSentSunrise =
                sunrise;

        lastSentSunset =
                sunset;

        lastSentElevationCurrent =
                elevationCurrent;

        lastSentElevationMin =
                elevationMin;

        lastSentElevationMax =
                elevationMax;
    }

    private synchronized void onWeatherSnapshot(
            CaminoPebbleWeatherClient.Snapshot snapshot
    ) {
        if (snapshot == null
                || sameWeather(
                        weather,
                        snapshot
                )) {

            return;
        }

        weather =
                snapshot;

        forceDashboardFull =
                true;

        if (CaminoPebbleSession.isWatchOpen()
                && visiblePage == 0) {

            sendDashboard(
                    true
            );
        }
    }

    private void sendTimetableStop() {
        List<CaminoTimetableStop> stops =
                allStops(
                        latestTimetableState
                );

        if (stops.isEmpty()) {
            selectedStopIndex =
                    -1;

            bridge.sendTimetableStop(
                    "--",
                    "--",
                    "--",
                    -1,
                    delivered -> {
                        synchronized (CaminoPebbleRoutePublisher.this) {
                            forceStopSend =
                                    !delivered;
                        }
                    }
            );

            return;
        }

        if (selectedStopIndex < 0
                || selectedStopIndex
                >= stops.size()) {

            selectedStopIndex =
                    findNextStopIndex(
                            latestTimetableState
                    );
        }

        selectedStopIndex =
                Math.max(
                        0,
                        Math.min(
                                stops.size() - 1,
                                selectedStopIndex
                        )
                );

        CaminoTimetableStop stop =
                stops.get(
                        selectedStopIndex
                );

        double currentChainageM =
                latestTimetableState == null
                        || !Double.isFinite(
                                latestTimetableState.currentChainageM
                        )
                        ? 0.0
                        : latestTimetableState.currentChainageM;

        double routeDistanceM =
                latestLocked == null
                        || latestLocked.path == null
                        ? Double.NaN
                        : latestLocked.path.distanceM;

        int percent =
                !Double.isFinite(routeDistanceM)
                        || routeDistanceM <= 0.0
                        ? -1
                        : (int)
                        Math.round(
                                100.0
                                        * stop.chainageM
                                        / routeDistanceM
                        );

        percent =
                percent < 0
                        ? percent
                        : Math.max(
                                0,
                                Math.min(
                                        100,
                                        percent
                                )
                        );

        forceStopSend =
                false;

        bridge.sendTimetableStop(
                stop.name,
                formatArrivalTime(
                        stop.arrivalMinutesOfDay
                ),
                formatDistance(
                        Math.abs(
                                stop.chainageM
                                        - currentChainageM
                        )
                ),
                percent,
                delivered -> {
                    if (delivered) {
                        return;
                    }

                    synchronized (CaminoPebbleRoutePublisher.this) {
                        forceStopSend =
                                true;
                    }
                }
        );
    }

    private void sendMiniMap() {
        byte[] payload =
                buildMiniMapPayload();

        forceMapSend =
                false;

        bridge.sendMiniMap(
                payload,
                delivered -> {
                    if (delivered) {
                        return;
                    }

                    synchronized (CaminoPebbleRoutePublisher.this) {
                        forceMapSend =
                                true;
                    }
                }
        );
    }

    private byte[] buildMiniMapPayload() {
        Location center =
                latestLocation;

        if (center == null) {
            return new byte[]{1, 0, 0};
        }

        ByteArrayOutputStream route =
                new ByteArrayOutputStream();

        int routePairs =
                0;

        MeasurementPath path =
                latestLocked == null
                        ? null
                        : latestLocked.path;

        if (path != null
                && path.profilePoints != null) {

            boolean previousIncluded =
                    false;

            for (int index = 0;
                    index < path.profilePoints.size()
                            && routePairs < MAP_MAX_ROUTE_POINTS;
                    index++) {

                ProfilePoint point =
                        path.profilePoints.get(
                                index
                        );

                if (point == null
                        || point.point == null) {

                    previousIncluded =
                            false;
                    continue;
                }

                LocalPoint local =
                        localPoint(
                                center,
                                point.point.getLatitude(),
                                point.point.getLongitude()
                        );

                boolean included =
                        Math.abs(
                                local.eastM
                        ) <= MAP_CAPTURE_M
                                && Math.abs(
                                local.northM
                        ) <= MAP_CAPTURE_M;

                if (!included) {
                    previousIncluded =
                            false;
                    continue;
                }

                if ((point.breakBefore
                        || !previousIncluded)
                        && routePairs > 0
                        && routePairs
                        < MAP_MAX_ROUTE_POINTS) {

                    writeMapPair(
                            route,
                            -128,
                            -128
                    );

                    routePairs++;
                }

                if (routePairs
                        >= MAP_MAX_ROUTE_POINTS) {
                    break;
                }

                writeMapPair(
                        route,
                        encodeMeters(
                                local.eastM
                        ),
                        encodeMeters(
                                local.northM
                        )
                );

                routePairs++;
                previousIncluded =
                        true;
            }
        }

        ByteArrayOutputStream trail =
                new ByteArrayOutputStream();

        List<Location> trailPoints =
                new ArrayList<>(
                        recentTrail
                );

        int start =
                Math.max(
                        0,
                        trailPoints.size()
                                - MAP_MAX_TRAIL_POINTS
                );

        int trailPairs =
                0;

        for (int index = start;
                index < trailPoints.size()
                        && trailPairs < MAP_MAX_TRAIL_POINTS;
                index++) {

            Location point =
                    trailPoints.get(
                            index
                    );

            LocalPoint local =
                    localPoint(
                            center,
                            point.getLatitude(),
                            point.getLongitude()
                    );

            if (Math.abs(
                    local.eastM
            ) > MAP_CAPTURE_M
                    || Math.abs(
                    local.northM
            ) > MAP_CAPTURE_M) {

                continue;
            }

            writeMapPair(
                    trail,
                    encodeMeters(
                            local.eastM
                    ),
                    encodeMeters(
                            local.northM
                    )
            );

            trailPairs++;
        }

        ByteArrayOutputStream payload =
                new ByteArrayOutputStream(
                        3
                                + route.size()
                                + trail.size()
                );

        payload.write(
                1
        );

        payload.write(
                routePairs
        );

        payload.write(
                trailPairs
        );

        byte[] routeBytes =
                route.toByteArray();

        payload.write(
                routeBytes,
                0,
                routeBytes.length
        );

        byte[] trailBytes =
                trail.toByteArray();

        payload.write(
                trailBytes,
                0,
                trailBytes.length
        );

        return payload.toByteArray();
    }

    private void rememberTrailPoint(
            Location location
    ) {
        long nowElapsed =
                SystemClock.elapsedRealtime();

        while (!recentTrail.isEmpty()) {
            Location oldest =
                    recentTrail.peekFirst();

            if (oldest == null
                    || nowElapsed
                    - locationElapsedMs(
                            oldest
                    ) <= TRACE_MAX_AGE_MS) {

                break;
            }

            recentTrail.removeFirst();
        }

        Location newest =
                recentTrail.peekLast();

        if (newest != null
                && newest.distanceTo(
                        location
                ) < TRACE_SPACING_M) {

            return;
        }

        recentTrail.addLast(
                new Location(
                        location
                )
        );

        while (recentTrail.size()
                > TRACE_MAX_POINTS) {

            recentTrail.removeFirst();
        }
    }

    private ElevationValues elevationValues() {
        if (latestLocked == null
                || latestLocked.path == null
                || routeElevationMinM == null
                || routeElevationMaxM == null
                || latestTimetableState == null
                || !Double.isFinite(
                        latestTimetableState.currentChainageM
                )) {

            return null;
        }

        ProfilePoint nearest =
                null;

        double nearestDistance =
                Double.POSITIVE_INFINITY;

        for (ProfilePoint point :
                latestLocked.path.profilePoints) {

            if (point == null
                    || !Double.isFinite(
                            point.distanceM
                    )
                    || !Double.isFinite(
                            point.elevationM
                    )) {

                continue;
            }

            double distance =
                    Math.abs(
                            point.distanceM
                                    - latestTimetableState.currentChainageM
                    );

            if (distance < nearestDistance) {
                nearestDistance =
                        distance;

                nearest =
                        point;
            }
        }

        if (nearest == null) {
            return null;
        }

        return new ElevationValues(
                (int)
                        Math.round(
                                nearest.elevationM
                        ),
                routeElevationMinM,
                routeElevationMaxM
        );
    }

    private void refreshElevationScale(
            LockedMeasurementPathStore.Snapshot locked
    ) {
        elevationScalePathVersion =
                locked == null
                        ? Integer.MIN_VALUE
                        : locked.version;

        routeElevationMinM =
                null;

        routeElevationMaxM =
                null;

        if (locked == null
                || locked.path == null
                || locked.path.profilePoints == null) {

            return;
        }

        double min =
                Double.POSITIVE_INFINITY;

        double max =
                Double.NEGATIVE_INFINITY;

        for (ProfilePoint point :
                locked.path.profilePoints) {

            if (point == null
                    || !Double.isFinite(
                            point.elevationM
                    )) {

                continue;
            }

            min =
                    Math.min(
                            min,
                            point.elevationM
                    );

            max =
                    Math.max(
                            max,
                            point.elevationM
                    );
        }

        if (!Double.isFinite(min)
                || !Double.isFinite(max)) {

            return;
        }

        routeElevationMinM =
                (int)
                        Math.round(
                                min
                        );

        routeElevationMaxM =
                (int)
                        Math.round(
                                max
                        );
    }

    private static List<CaminoTimetableStop> allStops(
            CaminoTimetableState state
    ) {
        if (state == null
                || state.allStops == null) {

            return java.util.Collections.emptyList();
        }

        return state.allStops;
    }

    private static int findNextStopIndex(
            CaminoTimetableState state
    ) {
        List<CaminoTimetableStop> stops =
                allStops(
                        state
                );

        if (stops.isEmpty()) {
            return -1;
        }

        double current =
                state == null
                        || !Double.isFinite(
                                state.currentChainageM
                        )
                        ? 0.0
                        : state.currentChainageM;

        for (int index = 0;
                index < stops.size();
                index++) {

            if (stops.get(
                    index
            ).chainageM
                    > current + 0.5) {

                return index;
            }
        }

        return stops.size()
                - 1;
    }

    private static LocalPoint localPoint(
            Location center,
            double latitude,
            double longitude
    ) {
        double latitudeRadians =
                Math.toRadians(
                        center.getLatitude()
                );

        double eastM =
                (longitude
                        - center.getLongitude())
                        * 111_320.0
                        * Math.cos(
                                latitudeRadians
                        );

        double northM =
                (latitude
                        - center.getLatitude())
                        * 110_540.0;

        return new LocalPoint(
                eastM,
                northM
        );
    }

    private static int encodeMeters(
            double meters
    ) {
        return (int)
                Math.round(
                        Math.max(
                                -120.0,
                                Math.min(
                                        120.0,
                                        meters
                                )
                        )
                );
    }

    private static void writeMapPair(
            ByteArrayOutputStream output,
            int eastM,
            int northM
    ) {
        output.write(
                eastM & 0xff
        );

        output.write(
                northM & 0xff
        );
    }

    private static long locationElapsedMs(
            Location location
    ) {
        if (location != null
                && location.getElapsedRealtimeNanos()
                > 0L) {

            return location.getElapsedRealtimeNanos()
                    / 1_000_000L;
        }

        return SystemClock.elapsedRealtime();
    }

    private static boolean sameWeather(
            CaminoPebbleWeatherClient.Snapshot first,
            CaminoPebbleWeatherClient.Snapshot second
    ) {
        if (first == null
                || second == null) {

            return first == second;
        }

        return first.currentTenthsC
                == second.currentTenthsC
                && first.minTenthsC
                == second.minTenthsC
                && first.maxTenthsC
                == second.maxTenthsC
                && first.sunriseMinutes
                == second.sunriseMinutes
                && first.sunsetMinutes
                == second.sunsetMinutes;
    }

    private static Integer intDelta(
            boolean fullSend,
            int value,
            Integer previous
    ) {
        return fullSend
                || !sameInt(
                        value,
                        previous
                )
                ? value
                : null;
    }

    private static boolean sameInt(
            int value,
            Integer previous
    ) {
        return previous != null
                && previous
                == value;
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

    private static String formatDistance(
            double distanceM
    ) {
        if (!Double.isFinite(distanceM)) {
            return "--";
        }

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

    private static String formatArrivalTime(
            int minutesOfDay
    ) {
        int normalized =
                minutesOfDay
                        % (24 * 60);

        if (normalized < 0) {
            normalized +=
                    24 * 60;
        }

        return String.format(
                Locale.US,
                "%02d:%02d",
                normalized / 60,
                normalized % 60
        );
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

    private static final class ElevationValues {
        final int currentM;
        final int minM;
        final int maxM;

        ElevationValues(
                int currentM,
                int minM,
                int maxM
        ) {
            this.currentM = currentM;
            this.minM = minM;
            this.maxM = maxM;
        }
    }

    private static final class LocalPoint {
        final double eastM;
        final double northM;

        LocalPoint(
                double eastM,
                double northM
        ) {
            this.eastM = eastM;
            this.northM = northM;
        }
    }
}
