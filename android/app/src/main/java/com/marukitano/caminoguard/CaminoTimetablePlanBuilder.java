package com.marukitano.caminoguard;

import org.maplibre.android.geometry.LatLng;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Adapter from the canonical selected MeasurementPath to pure timetable input.
 *
 * Output is only CaminoTimetableStopPlan, so Android UI and a future Pebble
 * bridge can consume the same route/ETA data.
 */
final class CaminoTimetablePlanBuilder {

    interface TimeEstimator {
        double estimateSeconds(
                MeasurementPath path
        );
    }

    private static final double DISTANCE_EPSILON_M =
            0.05;

    private final TimeEstimator timeEstimator;


    CaminoTimetablePlanBuilder(
            WalkingPerformanceModel performanceModel
    ) {
        this(
                path -> {
                    if (performanceModel == null) {
                        return Double.NaN;
                    }

                    WalkingTimeEstimate estimate =
                            performanceModel.estimate(
                                    path
                            );

                    return estimate == null
                            ? Double.NaN
                            : estimate.durationSeconds;
                }
        );
    }


    CaminoTimetablePlanBuilder(
            TimeEstimator timeEstimator
    ) {
        if (timeEstimator == null) {
            throw new IllegalArgumentException(
                    "Timetable time estimator must not be null."
            );
        }

        this.timeEstimator =
                timeEstimator;
    }


    List<CaminoTimetableStopPlan> build(
            MeasurementPath path
    ) {
        if (path == null
                || path.timetableStops.isEmpty()
                || !Double.isFinite(
                path.distanceM
        )
                || path.distanceM < 0.0) {

            return Collections.emptyList();
        }

        List<DisplayStop> displayStops =
                collectDisplayStops(
                        path
                );

        if (displayStops.isEmpty()) {
            return Collections.emptyList();
        }

        List<CaminoTimetableStopPlan> result =
                new ArrayList<>();

        double previousElapsedSeconds =
                0.0;

        for (DisplayStop stop
                : displayStops) {

            double elapsedSeconds;

            if (stop.chainageM
                    <= DISTANCE_EPSILON_M) {

                elapsedSeconds =
                        0.0;

            } else {
                MeasurementPath prefix =
                        prefixPath(
                                path,
                                stop.chainageM
                        );

                elapsedSeconds =
                        timeEstimator.estimateSeconds(
                                prefix
                        );

                if (!Double.isFinite(
                        elapsedSeconds
                )
                        || elapsedSeconds < 0.0) {

                    /*
                     * Better no Fahrplan than a fabricated ETA.
                     */
                    return Collections.emptyList();
                }
            }

            elapsedSeconds =
                    Math.max(
                            previousElapsedSeconds,
                            elapsedSeconds
                    );

            result.add(
                    new CaminoTimetableStopPlan(
                            stop.name,
                            stop.chainageM,
                            elapsedSeconds
                    )
            );

            previousElapsedSeconds =
                    elapsedSeconds;
        }

        return Collections.unmodifiableList(
                result
        );
    }


    private List<DisplayStop> collectDisplayStops(
            MeasurementPath path
    ) {
        List<DisplayStop> result =
                new ArrayList<>();

        int lastIndex =
                path.timetableStops.size() - 1;

        for (int index = 0;
                index < path.timetableStops.size();
                index++) {

            CaminoTimetablePathStop source =
                    path.timetableStops.get(
                            index
                    );

            if (source == null
                    || !Double.isFinite(
                    source.chainageM
            )) {

                continue;
            }

            boolean endpoint =
                    index == 0
                            || index == lastIndex;

            boolean village =
                    isVillagePlaceKey(
                            source.placeKey
                    );

            if (!endpoint
                    && !village) {

                continue;
            }

            String name;

            if (village) {
                name =
                        displayName(
                                source.placeKey
                        );

            } else {
                name =
                        index == 0
                                ? "Start"
                                : "Ziel";
            }

            double chainageM =
                    Math.max(
                            0.0,
                            Math.min(
                                    path.distanceM,
                                    source.chainageM
                            )
                    );

            if (!result.isEmpty()) {
                DisplayStop previous =
                        result.get(
                                result.size() - 1
                        );

                if (Math.abs(
                        previous.chainageM
                                - chainageM
                ) <= 0.5
                        && previous.name.equals(
                        name
                )) {

                    continue;
                }
            }

            result.add(
                    new DisplayStop(
                            name,
                            chainageM
                    )
            );
        }

        return result;
    }


    static boolean isVillagePlaceKey(
            String placeKey
    ) {
        if (placeKey == null) {
            return false;
        }

        String value =
                placeKey.trim();

        if (value.isEmpty()) {
            return false;
        }

        String lower =
                value.toLowerCase(
                        Locale.ROOT
                );

        return !lower.startsWith(
                "@"
        )
                && !lower.startsWith(
                "fork_"
        );
    }


    static String displayName(
            String placeKey
    ) {
        if (placeKey == null) {
            return "";
        }

        String trimmed =
                placeKey.trim();

        if (looksLikeDisplayLabel(
                trimmed
        )) {

            return trimmed.replaceAll(
                    "\\s+",
                    " "
            );
        }

        String normalized =
                trimmed.replace(
                                '_',
                                ' '
                        )
                        .replace(
                                '-',
                                ' '
                        )
                        .replaceAll(
                                "\\s+",
                                " "
                        );

        if (normalized.isEmpty()) {
            return "";
        }

        String[] words =
                normalized.split(
                        " "
                );

        StringBuilder result =
                new StringBuilder();

        for (int index = 0;
                index < words.length;
                index++) {

            if (index > 0) {
                result.append(
                        ' '
                );
            }

            String lower =
                    words[index]
                            .toLowerCase(
                                    Locale.ROOT
                            );

            if (index > 0
                    && isLowercaseConnector(
                    lower
            )) {

                result.append(
                        lower
                );

                continue;
            }

            int firstCodePoint =
                    lower.codePointAt(
                            0
                    );

            result.appendCodePoint(
                    Character.toUpperCase(
                            firstCodePoint
                    )
            );

            result.append(
                    lower.substring(
                            Character.charCount(
                                    firstCodePoint
                            )
                    )
            );
        }

        return result.toString();
    }


    private static boolean looksLikeDisplayLabel(
            String value
    ) {
        for (int index = 0;
                index < value.length();
                index++) {

            char c =
                    value.charAt(
                            index
                    );

            if (Character.isWhitespace(
                    c
            )
                    || Character.isUpperCase(
                    c
            )) {

                return true;
            }
        }

        return false;
    }


    private static boolean isLowercaseConnector(
            String value
    ) {
        return "de".equals(
                value
        )
                || "del".equals(
                value
        )
                || "la".equals(
                value
        )
                || "las".equals(
                value
        )
                || "los".equals(
                value
        )
                || "el".equals(
                value
        )
                || "y".equals(
                value
        );
    }


    private MeasurementPath prefixPath(
            MeasurementPath source,
            double targetDistanceM
    ) {
        targetDistanceM =
                Math.max(
                        0.0,
                        Math.min(
                                source.distanceM,
                                targetDistanceM
                        )
                );

        if (targetDistanceM
                >= source.distanceM
                - DISTANCE_EPSILON_M) {

            return source;
        }

        MeasurementPath result =
                new MeasurementPath();

        result.distanceM =
                targetDistanceM;

        result.startRoute =
                source.startRoute;

        result.endRoute =
                source.endRoute;

        ProfilePoint previousSource =
                null;

        for (ProfilePoint current
                : source.profilePoints) {

            if (current == null
                    || !Double.isFinite(
                    current.distanceM
            )) {

                previousSource =
                        current;

                continue;
            }

            if (current.distanceM
                    < targetDistanceM
                    - DISTANCE_EPSILON_M) {

                result.profilePoints.add(
                        current
                );

                previousSource =
                        current;

                continue;
            }

            if (Math.abs(
                    current.distanceM
                            - targetDistanceM
            ) <= DISTANCE_EPSILON_M) {

                result.profilePoints.add(
                        current
                );

                break;
            }

            if (previousSource != null
                    && Double.isFinite(
                    previousSource.distanceM
            )
                    && current.distanceM
                    > previousSource.distanceM
                    && !current.breakBefore
                    && previousSource.point != null
                    && current.point != null
                    && Double.isFinite(
                    previousSource.elevationM
            )
                    && Double.isFinite(
                    current.elevationM
            )) {

                double fraction =
                        (
                                targetDistanceM
                                        - previousSource.distanceM
                        )
                                / (
                                current.distanceM
                                        - previousSource.distanceM
                        );

                fraction =
                        Math.max(
                                0.0,
                                Math.min(
                                        1.0,
                                        fraction
                                )
                        );

                LatLng point =
                        new LatLng(
                                previousSource.point.getLatitude()
                                        + fraction
                                        * (
                                        current.point.getLatitude()
                                                - previousSource.point.getLatitude()
                                ),
                                previousSource.point.getLongitude()
                                        + fraction
                                        * (
                                        current.point.getLongitude()
                                                - previousSource.point.getLongitude()
                                )
                        );

                double elevationM =
                        previousSource.elevationM
                                + fraction
                                * (
                                current.elevationM
                                        - previousSource.elevationM
                        );

                result.profilePoints.add(
                        new ProfilePoint(
                                point,
                                targetDistanceM,
                                elevationM,
                                false
                        )
                );
            }

            break;
        }

        /*
         * estimate() requires at least two profile points before applying its
         * normal flat fallback to unprofiled distance.
         */
        if (result.profilePoints.size() == 1) {
            ProfilePoint first =
                    result.profilePoints.get(
                            0
                    );

            if (targetDistanceM
                    > first.distanceM
                    + DISTANCE_EPSILON_M) {

                result.profilePoints.add(
                        new ProfilePoint(
                                first.point,
                                targetDistanceM,
                                first.elevationM,
                                true
                        )
                );
            }
        }

        return result;
    }


    private static final class DisplayStop {

        final String name;
        final double chainageM;


        DisplayStop(
                String name,
                double chainageM
        ) {
            this.name =
                    name;

            this.chainageM =
                    chainageM;
        }
    }
}
