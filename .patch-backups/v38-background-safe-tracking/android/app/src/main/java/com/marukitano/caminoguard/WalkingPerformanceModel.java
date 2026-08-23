package com.marukitano.caminoguard;

import android.content.Context;

import org.maplibre.android.geometry.LatLng;

import java.util.Arrays;
import java.util.List;

/**
 * Learns the user's personal walking speed as a function of slope.
 */
final class WalkingPerformanceModel {

    private static final long TARGET_BUCKET_MOVING_MS =
            60_000L;

    private static final long MIN_PARTIAL_BUCKET_MOVING_MS =
            20_000L;

    private static final double MIN_BUCKET_DISTANCE_M =
            12.0;

    private static final double MAX_PROJECTION_OFFSET_M =
            60.0;

    private static final double MIN_VALID_SPEED_KMH =
            0.5;

    private static final double MAX_VALID_SPEED_KMH =
            9.0;

    private static final double MAX_ABS_GRADE_PCT =
            35.0;

    private static final long MODEL_HISTORY_MS =
            90L
                    * 24L
                    * 60L
                    * 60L
                    * 1000L;

    /*
     * Old observations remain stored, but their ETA influence decays with a
     * two-week half-life so improving fitness shows up quickly.
     */
    private static final double RECENCY_HALF_LIFE_DAYS =
            14.0;

    private static final double FULL_CONFIDENCE_MINUTES =
            15.0;

    /*
     * Grade buckets:
     * <=-12, -12..-8, -8..-4, -4..-1, -1..+1,
     * +1..+4, +4..+8, +8..+12, >=+12.
     */
    private static final double[] GRADE_EDGES =
            new double[]{
                    -12.0,
                    -8.0,
                    -4.0,
                    -1.0,
                    1.0,
                    4.0,
                    8.0,
                    12.0
            };

    private static final double ETA_GRADE_WINDOW_M =
            100.0;

    private final CaminoProjectionEngine projectionEngine;
    private final MeasurementEngine measurementEngine;
    private final WalkingPerformanceStore store;

    private final double[] weightedSpeedSum =
            new double[GRADE_EDGES.length + 1];

    private final double[] effectiveMinutes =
            new double[GRADE_EDGES.length + 1];

    private LatLng lastPosition;
    private RouteHit lastRouteHit;
    private long lastElapsedMs =
            -1L;

    private long bucketMovingMs;
    private double bucketDistanceM;

    private long bucketStartedAtWallMs;

    private double bucketStartLat;
    private double bucketStartLon;
    private double bucketEndLat;
    private double bucketEndLon;

    private double bucketStartElevationM =
            Double.NaN;

    private double bucketEndElevationM =
            Double.NaN;

    private String bucketRouteGroupId;
    private String bucketSectionId;

    /*
     * A pause is inserted into SQLite immediately when STATIONARY begins.
     * It is closed by the first subsequent MOVING GPS sample.
     */
    private long activePauseId =
            -1L;

    private long activePauseStartedAtWallMs =
            -1L;

    WalkingPerformanceModel(
            Context context,
            CaminoProjectionEngine projectionEngine,
            MeasurementEngine measurementEngine
    ) {
        this.projectionEngine =
                projectionEngine;

        this.measurementEngine =
                measurementEngine;

        this.store =
                new WalkingPerformanceStore(
                        context
                );

        loadRecentHistory();
    }

    void noteMovingSample(
            LatLng position,
            long elapsedMs
    ) {
        if (position == null
                || projectionEngine == null
                || measurementEngine == null) {

            return;
        }

        finishActivePause(
                position
        );

        RouteHit currentRouteHit =
                projectionEngine.findNearestRouteHit(
                        position
                );

        if (lastPosition == null
                || lastElapsedMs < 0L
                || lastRouteHit == null) {

            setAnchor(
                    position,
                    currentRouteHit,
                    elapsedMs
            );

            return;
        }

        long deltaMs =
                elapsedMs
                        - lastElapsedMs;

        if (deltaMs <= 0L
                || deltaMs > 2L * 60L * 1000L) {

            flushPartialBucket();
            resetBucket();

            setAnchor(
                    position,
                    currentRouteHit,
                    elapsedMs
            );

            return;
        }

        double segmentM =
                GeoMath.distanceMeters(
                        lastPosition,
                        position
                );

        if (!Double.isFinite(
                segmentM
        )
                || segmentM <= 0.0
                || segmentM > 250.0) {

            setAnchor(
                    position,
                    currentRouteHit,
                    elapsedMs
            );

            return;
        }

        if (!usableRouteHit(
                lastRouteHit
        )
                || !usableRouteHit(
                currentRouteHit
        )) {

            flushPartialBucket();
            resetBucket();

            setAnchor(
                    position,
                    currentRouteHit,
                    elapsedMs
            );

            return;
        }

        if (bucketMovingMs > 0L
                && bucketRouteGroupId != null
                && !bucketRouteGroupId.equals(
                currentRouteHit.route.id
        )) {

            flushPartialBucket();
            resetBucket();
        }

        double startElevationM =
                elevationAt(
                        lastRouteHit
                );

        double endElevationM =
                elevationAt(
                        currentRouteHit
                );

        if (!Double.isFinite(
                startElevationM
        )
                || !Double.isFinite(
                endElevationM
        )) {

            flushPartialBucket();
            resetBucket();

            setAnchor(
                    position,
                    currentRouteHit,
                    elapsedMs
            );

            return;
        }

        if (bucketMovingMs == 0L) {
            bucketStartedAtWallMs =
                    System.currentTimeMillis()
                            - deltaMs;

            bucketStartLat =
                    lastPosition.getLatitude();

            bucketStartLon =
                    lastPosition.getLongitude();

            bucketStartElevationM =
                    startElevationM;

            bucketRouteGroupId =
                    lastRouteHit.route.id;

            bucketSectionId =
                    sectionId(
                            lastRouteHit
                    );
        }

        bucketMovingMs +=
                deltaMs;

        bucketDistanceM +=
                segmentM;

        bucketEndLat =
                position.getLatitude();

        bucketEndLon =
                position.getLongitude();

        bucketEndElevationM =
                endElevationM;

        if (bucketMovingMs
                >= TARGET_BUCKET_MOVING_MS) {

            flushBucket();
            resetBucket();
        }

        setAnchor(
                position,
                currentRouteHit,
                elapsedMs
        );
    }

    void noteStationary(
            LatLng position
    ) {
        flushPartialBucket();
        resetBucket();

        LatLng pausePosition =
                position != null
                        ? position
                        : lastPosition;

        if (activePauseId < 0L
                && pausePosition != null) {

            RouteHit routeHit =
                    projectionEngine.findNearestRouteHit(
                            pausePosition
                    );

            String routeGroupId =
                    usableRouteHit(
                            routeHit
                    )
                            ? routeHit.route.id
                            : null;

            String sectionId =
                    usableRouteHit(
                            routeHit
                    )
                            ? sectionId(
                                    routeHit
                            )
                            : null;

            activePauseStartedAtWallMs =
                    System.currentTimeMillis();

            activePauseId =
                    store.beginPause(
                            new WalkingPause(
                                    activePauseStartedAtWallMs,
                                    pausePosition.getLatitude(),
                                    pausePosition.getLongitude(),
                                    routeGroupId,
                                    sectionId
                            )
                    );
        }

        lastPosition =
                null;

        lastRouteHit =
                null;

        lastElapsedMs =
                -1L;
    }

    private void finishActivePause(
            LatLng resumePosition
    ) {
        if (activePauseId < 0L
                || activePauseStartedAtWallMs < 0L
                || resumePosition == null) {

            return;
        }

        long endedAtWallMs =
                System.currentTimeMillis();

        store.endPause(
                activePauseId,
                endedAtWallMs,
                endedAtWallMs
                        - activePauseStartedAtWallMs,
                resumePosition.getLatitude(),
                resumePosition.getLongitude()
        );

        activePauseId =
                -1L;

        activePauseStartedAtWallMs =
                -1L;
    }

    WalkingTimeEstimate estimate(
            MeasurementPath path
    ) {
        if (path == null
                || path.profilePoints.size() < 2
                || !Double.isFinite(
                path.distanceM
        )
                || path.distanceM <= 0.0) {

            return null;
        }

        double seconds =
                0.0;

        List<ProfilePoint> points =
                path.profilePoints;

        ProfilePoint previous =
                points.get(
                        0
                );

        double accountedDistanceM =
                0.0;

        double chunkDistanceM =
                0.0;

        double chunkElevationDeltaM =
                0.0;

        for (int index = 1;
                index < points.size();
                index++) {

            ProfilePoint current =
                    points.get(
                            index
                    );

            double segmentDistanceM =
                    current.distanceM
                            - previous.distanceM;

            if (!Double.isFinite(
                    segmentDistanceM
            )
                    || segmentDistanceM <= 0.0) {

                previous =
                        current;

                continue;
            }

            accountedDistanceM +=
                    segmentDistanceM;

            if (current.breakBefore
                    || !Double.isFinite(
                    current.elevationM
            )
                    || !Double.isFinite(
                    previous.elevationM
            )) {

                if (chunkDistanceM > 0.0) {
                    seconds +=
                            secondsForChunk(
                                    chunkDistanceM,
                                    chunkElevationDeltaM
                            );

                    chunkDistanceM =
                            0.0;

                    chunkElevationDeltaM =
                            0.0;
                }

                seconds +=
                        secondsForGrade(
                                segmentDistanceM,
                                0.0
                        );

                previous =
                        current;

                continue;
            }

            chunkDistanceM +=
                    segmentDistanceM;

            chunkElevationDeltaM +=
                    current.elevationM
                            - previous.elevationM;

            if (chunkDistanceM
                    >= ETA_GRADE_WINDOW_M) {

                seconds +=
                        secondsForChunk(
                                chunkDistanceM,
                                chunkElevationDeltaM
                        );

                chunkDistanceM =
                        0.0;

                chunkElevationDeltaM =
                        0.0;
            }

            previous =
                    current;
        }

        if (chunkDistanceM > 0.0) {
            seconds +=
                    secondsForChunk(
                            chunkDistanceM,
                            chunkElevationDeltaM
                    );
        }

        double unprofiledDistanceM =
                path.distanceM
                        - accountedDistanceM;

        if (unprofiledDistanceM > 0.5) {
            seconds +=
                    secondsForGrade(
                            unprofiledDistanceM,
                            0.0
                    );
        }

        if (!Double.isFinite(
                seconds
        )
                || seconds <= 0.0) {

            return null;
        }

        return new WalkingTimeEstimate(
                seconds
        );
    }

    private double secondsForChunk(
            double distanceM,
            double elevationDeltaM
    ) {
        double gradePct =
                distanceM <= 0.0
                        ? 0.0
                        : elevationDeltaM
                        / distanceM
                        * 100.0;

        return secondsForGrade(
                distanceM,
                gradePct
        );
    }

    private double secondsForGrade(
            double distanceM,
            double gradePct
    ) {
        double kmh =
                predictedSpeedKmh(
                        gradePct
                );

        double metersPerSecond =
                kmh
                        / 3.6;

        return distanceM
                / Math.max(
                0.1,
                metersPerSecond
        );
    }

    double predictedSpeedKmh(
            double gradePct
    ) {
        int bucket =
                bucketForGrade(
                        gradePct
                );

        double fallback =
                fallbackSpeedKmh(
                        gradePct
                );

        double minutes =
                effectiveMinutes[
                        bucket
                        ];

        if (minutes <= 0.0) {
            return fallback;
        }

        double personal =
                weightedSpeedSum[
                        bucket
                        ]
                        / minutes;

        if (!Double.isFinite(
                personal
        )
                || personal <= 0.0) {

            return fallback;
        }

        double confidence =
                Math.min(
                        1.0,
                        minutes
                                / FULL_CONFIDENCE_MINUTES
                );

        return fallback
                * (
                1.0
                        - confidence
        )
                + personal
                * confidence;
    }

    private double fallbackSpeedKmh(
            double gradePct
    ) {
        /*
         * Same conservative physical idea as the previous ETA:
         * flat 4.5 km/h, +1 h / 600 vertical m uphill,
         * +1 h / 1000 vertical m downhill.
         */
        double gradeFraction =
                Math.abs(
                        gradePct
                )
                        / 100.0;

        double hoursPerMeter =
                1.0
                        / 4_500.0;

        if (gradePct > 0.0) {
            hoursPerMeter +=
                    gradeFraction
                            / 600.0;

        } else if (gradePct < 0.0) {
            hoursPerMeter +=
                    gradeFraction
                            / 1_000.0;
        }

        double kmh =
                1.0
                        / hoursPerMeter
                        / 1000.0;

        return Math.max(
                1.0,
                Math.min(
                        6.0,
                        kmh
                )
        );
    }

    private void loadRecentHistory() {
        Arrays.fill(
                weightedSpeedSum,
                0.0
        );

        Arrays.fill(
                effectiveMinutes,
                0.0
        );

        long now =
                System.currentTimeMillis();

        List<WalkingMinuteSample> samples =
                store.loadSince(
                        now
                                - MODEL_HISTORY_MS
                );

        for (WalkingMinuteSample sample
                : samples) {

            addToModel(
                    sample,
                    now
            );
        }
    }

    private void addToModel(
            WalkingMinuteSample sample,
            long nowMs
    ) {
        if (!validSample(
                sample
        )) {
            return;
        }

        double ageDays =
                Math.max(
                        0.0,
                        (
                                nowMs
                                        - sample.endedAtMs
                        )
                                / (
                                24.0
                                        * 60.0
                                        * 60.0
                                        * 1000.0
                        )
                );

        double recencyWeight =
                Math.pow(
                        0.5,
                        ageDays
                                / RECENCY_HALF_LIFE_DAYS
                );

        double movingMinutes =
                sample.movingMs
                        / 60_000.0;

        double weight =
                movingMinutes
                        * recencyWeight;

        int bucket =
                bucketForGrade(
                        sample.gradePct
                );

        effectiveMinutes[
                bucket
                ] +=
                weight;

        weightedSpeedSum[
                bucket
                ] +=
                sample.speedKmh
                        * weight;
    }

    private boolean validSample(
            WalkingMinuteSample sample
    ) {
        return sample != null
                && sample.movingMs
                >= MIN_PARTIAL_BUCKET_MOVING_MS
                && sample.distanceM
                >= MIN_BUCKET_DISTANCE_M
                && Double.isFinite(
                sample.gradePct
        )
                && Math.abs(
                sample.gradePct
        )
                <= MAX_ABS_GRADE_PCT
                && Double.isFinite(
                sample.speedKmh
        )
                && sample.speedKmh
                >= MIN_VALID_SPEED_KMH
                && sample.speedKmh
                <= MAX_VALID_SPEED_KMH;
    }

    private void flushPartialBucket() {
        if (bucketMovingMs
                >= MIN_PARTIAL_BUCKET_MOVING_MS) {

            flushBucket();
        }
    }

    private void flushBucket() {
        if (bucketMovingMs
                < MIN_PARTIAL_BUCKET_MOVING_MS
                || bucketDistanceM
                < MIN_BUCKET_DISTANCE_M
                || !Double.isFinite(
                bucketStartElevationM
        )
                || !Double.isFinite(
                bucketEndElevationM
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

        long endedAtMs =
                System.currentTimeMillis();

        WalkingMinuteSample sample =
                new WalkingMinuteSample(
                        bucketStartedAtWallMs,
                        endedAtMs,
                        bucketMovingMs,
                        bucketDistanceM,
                        gradePct,
                        speedKmh,
                        bucketStartLat,
                        bucketStartLon,
                        bucketEndLat,
                        bucketEndLon,
                        bucketRouteGroupId,
                        bucketSectionId
                );

        if (!validSample(
                sample
        )) {
            return;
        }

        store.insert(
                sample
        );

        addToModel(
                sample,
                endedAtMs
        );
    }

    private void resetBucket() {
        bucketMovingMs =
                0L;

        bucketDistanceM =
                0.0;

        bucketStartedAtWallMs =
                0L;

        bucketStartElevationM =
                Double.NaN;

        bucketEndElevationM =
                Double.NaN;

        bucketRouteGroupId =
                null;

        bucketSectionId =
                null;
    }

    private void setAnchor(
            LatLng position,
            RouteHit routeHit,
            long elapsedMs
    ) {
        lastPosition =
                new LatLng(
                        position.getLatitude(),
                        position.getLongitude()
                );

        lastRouteHit =
                routeHit;

        lastElapsedMs =
                elapsedMs;
    }

    private boolean usableRouteHit(
            RouteHit hit
    ) {
        return hit != null
                && hit.route != null
                && hit.hit != null
                && hit.hit.distanceFromQueryM
                <= MAX_PROJECTION_OFFSET_M
                && hit.hit.trackIndex >= 0
                && hit.hit.trackIndex
                < hit.route.tracks.size();
    }

    private double elevationAt(
            RouteHit hit
    ) {
        if (!usableRouteHit(
                hit
        )) {
            return Double.NaN;
        }

        RouteTrack track =
                hit.route.tracks.get(
                        hit.hit.trackIndex
                );

        return measurementEngine.elevationAtHit(
                track,
                hit.hit
        );
    }

    private String sectionId(
            RouteHit hit
    ) {
        if (!usableRouteHit(
                hit
        )) {
            return null;
        }

        return hit.route.tracks.get(
                hit.hit.trackIndex
        ).sectionId;
    }

    private int bucketForGrade(
            double gradePct
    ) {
        for (int index = 0;
                index < GRADE_EDGES.length;
                index++) {

            if (gradePct
                    < GRADE_EDGES[
                    index
                    ]) {

                return index;
            }
        }

        return GRADE_EDGES.length;
    }
}


final class WalkingTimeEstimate {

    final double durationSeconds;

    WalkingTimeEstimate(
            double durationSeconds
    ) {
        this.durationSeconds =
                durationSeconds;
    }
}
