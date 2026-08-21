package com.marukitano.caminoguard;

import android.os.SystemClock;
import android.text.format.DateFormat;

import org.maplibre.android.geometry.LatLng;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Owns travel-session statistics and next-village ETA metrics.
 *
 * It receives accepted position samples from CaminoController but owns no GPS,
 * tracking, MapLibre, touch or navigation-camera behavior.
 */
final class TravelStatsController {

    interface PositionProvider {
        LatLng currentPosition();
    }

    interface MeasurementPathProvider {
        MeasurementPath currentMeasurementPath();
    }

    private static final double EARTH_RADIUS_M =
            6371008.8;

    private static final double SPEED_SAMPLE_MIN_MOVE_M =
            CaminoConfig.get().doubleValue(
                    "measurement.speedSampleMinMoveMeters"
            );

    private static final double SPEED_SAMPLE_MAX_JUMP_M =
            CaminoConfig.get().doubleValue(
                    "measurement.speedSampleMaxJumpMeters"
            );

    private final List<CaminoRoute> routes;
    private final CaminoProjectionEngine projectionEngine;
    private final MeasurementEngine measurementEngine;
    private final PositionProvider positionProvider;
    private final MeasurementPathProvider measurementPathProvider;
    private final Consumer<String> statsSink;

    private long travelSessionStartElapsedMs =
            -1L;

    private long travelMovingElapsedMs =
            0L;

    private double travelDistanceM =
            0.0;

    private LatLng lastTravelSamplePosition;

    private long lastTravelSampleElapsedMs =
            -1L;

    TravelStatsController(
            List<CaminoRoute> routes,
            CaminoProjectionEngine projectionEngine,
            MeasurementEngine measurementEngine,
            PositionProvider positionProvider,
            MeasurementPathProvider measurementPathProvider,
            Consumer<String> statsSink
    ) {
        this.routes =
                routes;

        this.projectionEngine =
                projectionEngine;

        this.measurementEngine =
                measurementEngine;

        this.positionProvider =
                positionProvider;

        this.measurementPathProvider =
                measurementPathProvider;

        this.statsSink =
                statsSink;
    }

    void noteSample(
            LatLng position
    ) {
        if (position == null) {
            return;
        }

        long now =
                SystemClock.elapsedRealtime();

        if (travelSessionStartElapsedMs < 0L) {
            travelSessionStartElapsedMs =
                    now;

            lastTravelSampleElapsedMs =
                    now;

            lastTravelSamplePosition =
                    copyPosition(
                            position
                    );

            publishStats();
            return;
        }

        if (lastTravelSamplePosition == null) {
            lastTravelSamplePosition =
                    copyPosition(
                            position
                    );

            lastTravelSampleElapsedMs =
                    now;

            publishStats();
            return;
        }

        long deltaMs =
                now
                        - lastTravelSampleElapsedMs;

        if (deltaMs <= 0L
                || deltaMs
                > 15L * 60L * 1000L) {

            lastTravelSamplePosition =
                    copyPosition(
                            position
                    );

            lastTravelSampleElapsedMs =
                    now;

            publishStats();
            return;
        }

        double segmentM =
                travelDistanceBetween(
                        lastTravelSamplePosition,
                        position
                );

        if (segmentM >= SPEED_SAMPLE_MIN_MOVE_M
                && segmentM <= SPEED_SAMPLE_MAX_JUMP_M) {

            travelDistanceM +=
                    segmentM;

            travelMovingElapsedMs +=
                    deltaMs;
        }

        lastTravelSamplePosition =
                copyPosition(
                        position
                );

        lastTravelSampleElapsedMs =
                now;

        publishStats();
    }

    private void publishStats() {
        statsSink.accept(
                buildSpeedStatsText()
        );
    }

    private String buildSpeedStatsText() {
        long totalElapsedMs =
                travelSessionStartElapsedMs < 0L
                        ? 0L
                        : SystemClock.elapsedRealtime()
                        - travelSessionStartElapsedMs;

        double movingKmh =
                travelMovingElapsedMs <= 0L
                        ? Double.NaN
                        : travelDistanceM
                        / (
                        travelMovingElapsedMs
                                / 1000.0
                )
                        * 3.6;

        double totalKmh =
                totalElapsedMs <= 0L
                        ? Double.NaN
                        : travelDistanceM
                        / (
                        totalElapsedMs
                                / 1000.0
                )
                        * 3.6;

        double realWorldSpeedMps =
                !Double.isNaN(
                        totalKmh
                )
                        && totalKmh >= 0.4
                        ? totalKmh / 3.6
                        : (
                        !Double.isNaN(
                                movingKmh
                        )
                                && movingKmh >= 0.4
                                ? movingKmh / 3.6
                                : Double.NaN
                );

        String stageEta =
                "—";

        MeasurementPath currentMeasurementPath =
                measurementPathProvider.currentMeasurementPath();

        if (currentMeasurementPath != null
                && !Double.isNaN(
                        realWorldSpeedMps
                )
                && realWorldSpeedMps > 0.0) {

            long etaWallClockMs =
                    System.currentTimeMillis()
                            + (long) (
                            currentMeasurementPath.distanceM
                                    / realWorldSpeedMps
                                    * 1000.0
                    );

            stageEta =
                    DateFormat.format(
                            "HH:mm",
                            etaWallClockMs
                    ).toString();
        }

        double[] village =
                nextVillageMetrics();

        String villageDistance =
                "—";

        String villageTime =
                "—";

        String villageAscent =
                "—";

        if (village != null) {
            villageDistance =
                    formatDistance(
                            village[0]
                    );

            villageAscent =
                    String.format(
                            Locale.GERMANY,
                            "%.0f Hm",
                            village[1]
                    );

            if (!Double.isNaN(
                    realWorldSpeedMps
            )
                    && realWorldSpeedMps > 0.0) {

                villageTime =
                        formatDuration(
                                village[0]
                                        / realWorldSpeedMps
                        );
            }
        }

        return String.format(
                Locale.GERMANY,
                "Ø Moving   %s\n"
                        + "Ø Gesamt   %s\n"
                        + "Ankunft    %s\n"
                        + "bis Dorf   %s\n"
                        + "Dorf Zeit  %s\n"
                        + "Dorf ↑     %s",
                formatSpeedKmh(
                        movingKmh
                ),
                formatSpeedKmh(
                        totalKmh
                ),
                stageEta,
                villageDistance,
                villageTime,
                villageAscent
        );
    }

    private String formatSpeedKmh(
            double kmh
    ) {
        if (Double.isNaN(
                kmh
        )
                || kmh <= 0.0) {

            return "—";
        }

        return String.format(
                Locale.GERMANY,
                "%.1f km/h",
                kmh
        );
    }

    private String formatDuration(
            double seconds
    ) {
        if (!Double.isFinite(
                seconds
        )
                || seconds < 0.0) {

            return "—";
        }

        long minutes =
                Math.max(
                        0L,
                        Math.round(
                                seconds / 60.0
                        )
                );

        if (minutes < 60L) {
            return minutes
                    + " min";
        }

        long hours =
                minutes / 60L;

        long restMinutes =
                minutes % 60L;

        return String.format(
                Locale.GERMANY,
                "%d h %02d min",
                hours,
                restMinutes
        );
    }

    private String formatDistance(
            double distanceM
    ) {
        if (distanceM
                >= 1000.0) {

            return String.format(
                    Locale.GERMANY,
                    "%.2fkm",
                    distanceM
                            / 1000.0
            );
        }

        return String.format(
                Locale.GERMANY,
                "%.0fm",
                distanceM
        );
    }

    private double[] nextVillageMetrics() {
        LatLng currentPosition =
                positionProvider.currentPosition();

        if (routes.isEmpty()
                || currentPosition == null) {

            return null;
        }

        RouteHit start =
                projectionEngine.findNearestRouteHit(
                        currentPosition
                );

        if (start == null
                || start.hit.trackIndex < 0
                || start.hit.trackIndex
                >= start.route.tracks.size()) {

            return null;
        }

        CaminoRoute route =
                start.route;

        int trackIndex =
                start.hit.trackIndex;

        RouteTrack firstTrack =
                route.tracks.get(
                        trackIndex
                );

        double alongTrackM =
                start.hit.chainageM
                        - firstTrack.baseChainageM;

        alongTrackM =
                Math.max(
                        0.0,
                        Math.min(
                                firstTrack.lengthM,
                                alongTrackM
                        )
                );

        double distanceM =
                firstTrack.lengthM
                        - alongTrackM;

        double ascentM =
                positiveAscentFromHitToTrackEnd(
                        firstTrack,
                        start.hit
                );

        if (isVillageEndpoint(
                firstTrack
        )) {
            return new double[]{
                    distanceM,
                    ascentM
            };
        }

        for (int index =
                trackIndex + 1;
                index < route.tracks.size();
                index++) {

            RouteTrack current =
                    route.tracks.get(
                            index
                    );

            distanceM +=
                    measurementEngine.gapBetweenTracks(
                            route,
                            index - 1,
                            index
                    );

            distanceM +=
                    current.lengthM;

            /*
             * Gaps have real horizontal distance but no invented terrain.
             * Ascent resumes only on the next official geometry.
             */
            ascentM +=
                    positiveAscentWholeTrack(
                            current
                    );

            if (isVillageEndpoint(
                    current
            )) {
                return new double[]{
                        distanceM,
                        ascentM
                };
            }
        }

        return null;
    }

    private boolean isVillageEndpoint(
            RouteTrack track
    ) {
        return track != null
                && !track.pseudoTo
                && track.toKey != null
                && !track.toKey.isEmpty();
    }

    private double positiveAscentFromHitToTrackEnd(
            RouteTrack track,
            ProjectionHit hit
    ) {
        if (track == null
                || hit == null
                || track.elevations.isEmpty()) {

            return 0.0;
        }

        double previous =
                measurementEngine.elevationAtHit(
                        track,
                        hit
                );

        double ascentM =
                0.0;

        int firstVertex =
                Math.max(
                        0,
                        Math.min(
                                track.elevations.size(),
                                hit.segmentIndex + 1
                        )
                );

        for (int index =
                firstVertex;
                index < track.elevations.size();
                index++) {

            double elevation =
                    track.elevations.get(
                            index
                    );

            if (Double.isFinite(
                    previous
            )
                    && Double.isFinite(
                    elevation
            )) {

                double delta =
                        elevation
                                - previous;

                if (delta > 0.0) {
                    ascentM +=
                            delta;
                }
            }

            if (Double.isFinite(
                    elevation
            )) {
                previous =
                        elevation;
            }
        }

        return ascentM;
    }

    private double positiveAscentWholeTrack(
            RouteTrack track
    ) {
        if (track == null
                || track.elevations.size() < 2) {

            return 0.0;
        }

        double ascentM =
                0.0;

        double previous =
                track.elevations.get(
                        0
                );

        for (int index = 1;
                index < track.elevations.size();
                index++) {

            double elevation =
                    track.elevations.get(
                            index
                    );

            if (Double.isFinite(
                    previous
            )
                    && Double.isFinite(
                    elevation
            )) {

                double delta =
                        elevation
                                - previous;

                if (delta > 0.0) {
                    ascentM +=
                            delta;
                }
            }

            if (Double.isFinite(
                    elevation
            )) {
                previous =
                        elevation;
            }
        }

        return ascentM;
    }

    private static LatLng copyPosition(
            LatLng position
    ) {
        return new LatLng(
                position.getLatitude(),
                position.getLongitude()
        );
    }

    private static double travelDistanceBetween(
            LatLng from,
            LatLng to
    ) {
        double lat1 =
                Math.toRadians(
                        from.getLatitude()
                );

        double lon1 =
                Math.toRadians(
                        from.getLongitude()
                );

        double lat2 =
                Math.toRadians(
                        to.getLatitude()
                );

        double lon2 =
                Math.toRadians(
                        to.getLongitude()
                );

        double dLat =
                lat2
                        - lat1;

        double dLon =
                lon2
                        - lon1;

        double a =
                Math.sin(
                        dLat / 2.0
                )
                        * Math.sin(
                        dLat / 2.0
                )
                        + Math.cos(
                        lat1
                )
                        * Math.cos(
                        lat2
                )
                        * Math.sin(
                        dLon / 2.0
                )
                        * Math.sin(
                        dLon / 2.0
                );

        double c =
                2.0
                        * Math.atan2(
                        Math.sqrt(
                                a
                        ),
                        Math.sqrt(
                                1.0 - a
                        )
                );

        return EARTH_RADIUS_M
                * c;
    }
}
