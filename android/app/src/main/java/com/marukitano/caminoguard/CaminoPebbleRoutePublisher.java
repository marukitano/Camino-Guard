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
 * The watchapp only requests one of three presentation pages: dashboard,
 * timetable stop browser, or a small north-up local route map.
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

    /* Screen 3 is 200 x 228 px and deliberately uses 1 metre per pixel. */
    private static final double MAP_HALF_WIDTH_M =
            100.0;

    private static final double MAP_HALF_HEIGHT_M =
            114.0;

    /* -128 is reserved as the polyline break marker in the watch payload. */
    private static final double MAP_VECTOR_LIMIT_M =
            127.0;

    private static final int MAP_MAX_ROUTE_POINTS =
            48;

    private static final int MAP_MAX_TRAIL_POINTS =
            30;

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
    private boolean latestStationary;

    private int visiblePage;
    private int selectedStopIndex =
            -1;
    private boolean selectedStopFollowsNext =
            true;

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
    private Integer lastSentRouteProgressPercent;

    private CaminoPebbleWeatherClient.Snapshot weather;

    private Integer routeElevationMinM;
    private Integer routeElevationMaxM;

    private boolean forceStopSend =
            true;

    private boolean forceMapSend =
            true;

    private boolean mapRequestInFlight;
    private CaminoPebbleRoadSnapshotter.Result latestRoadResult;
    private int roadGeneration;
    private int lastSentRoadGeneration =
            Integer.MIN_VALUE;

    /* Independent edge detector for the watch's one-shot off-route jump. */
    private int offRouteWatchPathVersion =
            Integer.MIN_VALUE;
    private boolean offRouteWatchStateInitialized;
    private boolean lastWatchOnRoute;

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
                        : new Location(location);

        latestLocked =
                locked;

        latestTimetableState =
                timetableState;

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

            selectedStopFollowsNext =
                    true;

            forceDashboardFull =
                    true;

            forceStopSend =
                    true;

            forceMapSend =
                    true;

            latestRoadResult =
                    null;

            roadGeneration =
                    0;

            lastSentRoadGeneration =
                    Integer.MIN_VALUE;

            refreshElevationScale(
                    locked
            );
        }

        if (visiblePage == 1
                && selectedStopFollowsNext) {

            int nextStopIndex =
                    findNextStopIndex(
                            latestTimetableState
                    );

            if (selectedStopIndex
                    != nextStopIndex) {

                selectedStopIndex =
                        nextStopIndex;

                forceStopSend =
                        true;
            }
        }

        updateOffRouteWatchState(
                locked,
                onRoute
        );

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

        lastSentRoadGeneration =
                Integer.MIN_VALUE;

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

            selectedStopFollowsNext =
                    true;

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

        selectedStopFollowsNext =
                false;

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

            /*
             * Pebble's upper button and a downward QuickSwipe send -1.
             * Those gestures browse forward to the following route stop.
             */
            selectedStopIndex =
                    Math.max(
                            0,
                            Math.min(
                                    stops.size() - 1,
                                    selectedStopIndex
                                            + (delta < 0 ? 1 : -1)
                            )
                    );
        }

        forceStopSend =
                true;

        sendTimetableStop();
    }

    private void updateOffRouteWatchState(
            LockedMeasurementPathStore.Snapshot locked,
            boolean onRoute
    ) {
        int version =
                locked == null
                        || locked.path == null
                        ? Integer.MIN_VALUE
                        : locked.version;

        if (version == Integer.MIN_VALUE) {
            offRouteWatchPathVersion =
                    Integer.MIN_VALUE;

            offRouteWatchStateInitialized =
                    false;

            return;
        }

        if (version != offRouteWatchPathVersion) {
            offRouteWatchPathVersion =
                    version;

            offRouteWatchStateInitialized =
                    false;
        }

        /* First state is a baseline. Never alarm merely because tracking began. */
        if (!offRouteWatchStateInitialized) {
            lastWatchOnRoute =
                    onRoute;

            offRouteWatchStateInitialized =
                    true;

            return;
        }

        boolean leftRoute =
                lastWatchOnRoute
                        && !onRoute;

        lastWatchOnRoute =
                onRoute;

        if (leftRoute
                && CaminoPebbleSession.isWatchOpen()) {

            forceMapSend =
                    true;

            /*
             * The watch handles vibration + one automatic page change. No
             * repeated command is sent while the user remains off-route, so
             * manual swiping stays completely free afterwards.
             */
            bridge.sendShowMapOnce();
        }
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

        int routeProgressPercent =
                routeProgressPercent();

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
                && sameText(speed, lastSentSpeed)
                && sameInt(temperatureCurrent, lastSentTemperatureCurrent)
                && sameInt(temperatureMin, lastSentTemperatureMin)
                && sameInt(temperatureMax, lastSentTemperatureMax)
                && sameInt(sunrise, lastSentSunrise)
                && sameInt(sunset, lastSentSunset)
                && sameInt(elevationCurrent, lastSentElevationCurrent)
                && sameInt(elevationMin, lastSentElevationMin)
                && sameInt(elevationMax, lastSentElevationMax)
                && sameInt(routeProgressPercent, lastSentRouteProgressPercent)) {

            return;
        }

        String speedDelta =
                fullSend
                        || !sameText(speed, lastSentSpeed)
                        ? speed
                        : null;

        Integer temperatureCurrentDelta =
                intDelta(fullSend, temperatureCurrent, lastSentTemperatureCurrent);

        Integer temperatureMinDelta =
                intDelta(fullSend, temperatureMin, lastSentTemperatureMin);

        Integer temperatureMaxDelta =
                intDelta(fullSend, temperatureMax, lastSentTemperatureMax);

        Integer sunriseDelta =
                intDelta(fullSend, sunrise, lastSentSunrise);

        Integer sunsetDelta =
                intDelta(fullSend, sunset, lastSentSunset);

        Integer elevationCurrentDelta =
                intDelta(fullSend, elevationCurrent, lastSentElevationCurrent);

        Integer elevationMinDelta =
                intDelta(fullSend, elevationMin, lastSentElevationMin);

        Integer elevationMaxDelta =
                intDelta(fullSend, elevationMax, lastSentElevationMax);

        Integer routeProgressDelta =
                intDelta(fullSend, routeProgressPercent, lastSentRouteProgressPercent);

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
                routeProgressDelta,
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

        lastSentRouteProgressPercent =
                routeProgressPercent;
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

        forceStopSend =
                true;

        if (CaminoPebbleSession.isWatchOpen()) {
            if (visiblePage == 0) {
                sendDashboard(
                        true
                );

            } else if (visiblePage == 1) {
                sendTimetableStop();
            }
        }
    }

    private void sendTimetableStop() {
        Location location =
                latestLocation;

        if (location != null) {
            bridge.requestWeather(
                    location,
                    this::onWeatherSnapshot
            );
        }

        int temperatureCurrent =
                weather == null
                        ? UNKNOWN_METRIC
                        : weather.currentTenthsC;

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
                    temperatureCurrent,
                    false,
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

        int routeProgressPercent =
                routeProgressPercent();

        int percent =
                routeProgressPercent
                        == UNKNOWN_METRIC
                        ? -1
                        : routeProgressPercent;

        /*
         * The existing Pebble endpoint flag is true for both ends of the
         * timetable. The watch distinguishes START vs GOAL from the browsing
         * direction and keeps that endpoint identity for later refreshes.
         */
        boolean isEndpoint =
                selectedStopIndex == 0
                        || selectedStopIndex
                        == stops.size() - 1;

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
                temperatureCurrent,
                isEndpoint,
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
        if (mapRequestInFlight) {
            return;
        }

        Location center =
                latestLocation;

        if (center == null) {
            forceMapSend =
                    false;

            bridge.sendMiniMap(
                    new byte[]{2, 0, 0, 0, 0},
                    0,
                    null,
                    delivered -> {
                        synchronized (CaminoPebbleRoutePublisher.this) {
                            forceMapSend =
                                    !delivered;
                        }
                    }
            );

            return;
        }

        mapRequestInFlight =
                true;

        forceMapSend =
                false;

        bridge.requestRoadSnapshot(
                new Location(center),
                this::onRoadSnapshot
        );
    }

    private synchronized void onRoadSnapshot(
            CaminoPebbleRoadSnapshotter.Result result
    ) {
        mapRequestInFlight =
                false;

        if (!CaminoPebbleSession.isWatchOpen()
                || visiblePage != 2) {

            forceMapSend =
                    true;

            return;
        }

        CaminoPebbleRoadSnapshotter.Result usable =
                result != null
                        && result.hasRoadMask()
                        && result.center != null
                        ? result
                        : null;

        if (usable != null) {
            boolean newRoadCenter =
                    latestRoadResult == null
                            || latestRoadResult.center == null
                            || latestRoadResult.center.distanceTo(
                                    usable.center
                            ) > 1.0f;

            latestRoadResult =
                    usable;

            if (newRoadCenter
                    || roadGeneration <= 0) {

                roadGeneration =
                        roadGeneration == Integer.MAX_VALUE
                                ? 1
                                : roadGeneration + 1;
            }
        }

        Location mapCenter =
                usable != null
                        ? usable.center
                        : latestLocation;

        if (mapCenter == null) {
            forceMapSend =
                    true;
            return;
        }

        int generation =
                usable == null
                        ? 0
                        : roadGeneration;

        byte[] roadMask =
                usable != null
                        && generation
                        != lastSentRoadGeneration
                        ? usable.roadMask
                        : null;

        boolean sendingRoadMask =
                roadMask != null;

        byte[] payload =
                buildMiniMapPayload(
                        mapCenter
                );

        bridge.sendMiniMap(
                payload,
                generation,
                roadMask,
                delivered -> {
                    synchronized (CaminoPebbleRoutePublisher.this) {
                        if (delivered
                                && sendingRoadMask) {

                            lastSentRoadGeneration =
                                    generation;
                        }

                        forceMapSend =
                                !delivered;
                    }
                }
        );
    }

    private byte[] buildMiniMapPayload(
            Location center
    ) {
        if (center == null) {
            return new byte[]{2, 0, 0, 0, 0};
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

            ProfilePoint previous =
                    null;

            boolean runOpen =
                    false;

            int lastEast =
                    Integer.MIN_VALUE;

            int lastNorth =
                    Integer.MIN_VALUE;

            routeLoop:
            for (ProfilePoint point : path.profilePoints) {
                if (point == null
                        || point.point == null) {

                    previous =
                            null;

                    runOpen =
                            false;
                    continue;
                }

                if (previous != null
                        && !point.breakBefore) {

                    LocalPoint from =
                            localPoint(
                                    center,
                                    previous.point.getLatitude(),
                                    previous.point.getLongitude()
                            );

                    LocalPoint to =
                            localPoint(
                                    center,
                                    point.point.getLatitude(),
                                    point.point.getLongitude()
                            );

                    LocalPoint[] clipped =
                            clipSegmentToMap(
                                    from,
                                    to
                            );

                    if (clipped == null) {
                        runOpen =
                                false;

                    } else {
                        int firstEast =
                                encodeMeters(
                                        clipped[0].eastM
                                );

                        int firstNorth =
                                encodeMeters(
                                        clipped[0].northM
                                );

                        int secondEast =
                                encodeMeters(
                                        clipped[1].eastM
                                );

                        int secondNorth =
                                encodeMeters(
                                        clipped[1].northM
                                );

                        if (!runOpen) {
                            if (routePairs > 0) {
                                if (routePairs + 2
                                        > MAP_MAX_ROUTE_POINTS) {

                                    break routeLoop;
                                }

                                writeMapPair(
                                        route,
                                        -128,
                                        -128
                                );

                                routePairs++;
                            }

                            if (routePairs
                                    >= MAP_MAX_ROUTE_POINTS) {
                                break routeLoop;
                            }

                            writeMapPair(
                                    route,
                                    firstEast,
                                    firstNorth
                            );

                            routePairs++;
                            lastEast =
                                    firstEast;
                            lastNorth =
                                    firstNorth;
                            runOpen =
                                    true;

                        } else if ((firstEast != lastEast
                                || firstNorth != lastNorth)
                                && routePairs
                                < MAP_MAX_ROUTE_POINTS) {

                            writeMapPair(
                                    route,
                                    firstEast,
                                    firstNorth
                            );

                            routePairs++;
                            lastEast =
                                    firstEast;
                            lastNorth =
                                    firstNorth;
                        }

                        if ((secondEast != lastEast
                                || secondNorth != lastNorth)) {

                            if (routePairs
                                    >= MAP_MAX_ROUTE_POINTS) {
                                break routeLoop;
                            }

                            writeMapPair(
                                    route,
                                    secondEast,
                                    secondNorth
                            );

                            routePairs++;
                            lastEast =
                                    secondEast;
                            lastNorth =
                                    secondNorth;
                        }
                    }
                } else {
                    runOpen =
                            false;
                }

                previous =
                        point;
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

            if (Math.abs(local.eastM)
                    > MAP_HALF_WIDTH_M
                    || Math.abs(local.northM)
                    > MAP_HALF_HEIGHT_M) {

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

        LocalPoint marker =
                latestLocation == null
                        ? new LocalPoint(0.0, 0.0)
                        : localPoint(
                                center,
                                latestLocation.getLatitude(),
                                latestLocation.getLongitude()
                        );

        ByteArrayOutputStream payload =
                new ByteArrayOutputStream(
                        5
                                + route.size()
                                + trail.size()
                );

        payload.write(
                2
        );

        payload.write(
                routePairs
        );

        payload.write(
                trailPairs
        );

        payload.write(
                encodeMeters(marker.eastM)
                        & 0xff
        );

        payload.write(
                encodeMeters(marker.northM)
                        & 0xff
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

    /*
     * Clip each real route segment to the signed-byte map square instead of
     * testing only its endpoints. This keeps the Camino visible even when two
     * sparse profile vertices both lie outside the 200 x 228 m screen while
     * their segment passes straight through it.
     */
    private static LocalPoint[] clipSegmentToMap(
            LocalPoint from,
            LocalPoint to
    ) {
        if (from == null
                || to == null) {
            return null;
        }

        double x0 = from.eastM;
        double y0 = from.northM;
        double dx = to.eastM - x0;
        double dy = to.northM - y0;
        double t0 = 0.0;
        double t1 = 1.0;

        double[] p =
                {-dx, dx, -dy, dy};

        double[] q =
                {
                        x0 + MAP_VECTOR_LIMIT_M,
                        MAP_VECTOR_LIMIT_M - x0,
                        y0 + MAP_VECTOR_LIMIT_M,
                        MAP_VECTOR_LIMIT_M - y0
                };

        for (int index = 0;
                index < p.length;
                index++) {

            double pi = p[index];
            double qi = q[index];

            if (Math.abs(pi) < 1.0e-9) {
                if (qi < 0.0) {
                    return null;
                }
                continue;
            }

            double ratio = qi / pi;

            if (pi < 0.0) {
                if (ratio > t1) {
                    return null;
                }
                t0 = Math.max(t0, ratio);

            } else {
                if (ratio < t0) {
                    return null;
                }
                t1 = Math.min(t1, ratio);
            }
        }

        return new LocalPoint[]{
                new LocalPoint(
                        x0 + dx * t0,
                        y0 + dy * t0
                ),
                new LocalPoint(
                        x0 + dx * t1,
                        y0 + dy * t1
                )
        };
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
                    - locationElapsedMs(oldest)
                    <= TRACE_MAX_AGE_MS) {

                break;
            }

            recentTrail.removeFirst();
        }

        Location newest =
                recentTrail.peekLast();

        if (newest != null
                && newest.distanceTo(location)
                < TRACE_SPACING_M) {

            return;
        }

        recentTrail.addLast(
                new Location(location)
        );

        while (recentTrail.size()
                > TRACE_MAX_POINTS) {

            recentTrail.removeFirst();
        }
    }

    private int routeProgressPercent() {
        if (latestLocked == null
                || latestLocked.path == null
                || !Double.isFinite(
                        latestLocked.path.distanceM
                )
                || latestLocked.path.distanceM <= 0.0
                || latestTimetableState == null
                || !Double.isFinite(
                        latestTimetableState.currentChainageM
                )) {

            return UNKNOWN_METRIC;
        }

        int percent =
                (int) Math.round(
                        100.0
                                * latestTimetableState.currentChainageM
                                / latestLocked.path.distanceM
                );

        return Math.max(
                0,
                Math.min(
                        100,
                        percent
                )
        );
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
                (int) Math.round(
                        nearest.elevationM
                ),
                routeElevationMinM,
                routeElevationMaxM
        );
    }

    private void refreshElevationScale(
            LockedMeasurementPathStore.Snapshot locked
    ) {
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
                (int) Math.round(min);

        routeElevationMaxM =
                (int) Math.round(max);
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

            if (stops.get(index).chainageM
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
        return (int) Math.round(
                Math.max(
                        -127.0,
                        Math.min(
                                127.0,
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

        return String.format(
                Locale.US,
                "%.1f km",
                Math.max(
                        0.0,
                        distanceM
                ) / 1000.0
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
