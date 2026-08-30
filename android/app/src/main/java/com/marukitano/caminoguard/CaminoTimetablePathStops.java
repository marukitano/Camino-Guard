package com.marukitano.caminoguard;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Owns timetable-stop semantics.
 *
 * Route stops are authoritative for route order and chainage.
 * Settlement markers may add missing places or improve a matching label,
 * but they never rebuild or replace the selected route.
 */
final class CaminoTimetablePathStops {

    private static final double SAME_CHAINAGE_M =
            0.5;

    private static final double SAME_PLACE_DISTANCE_M =
            25.0;

    private static final double NAMED_ENDPOINT_SEARCH_M =
            2500.0;

    private static final double SYNTHETIC_ENDPOINT_SEARCH_M =
            40.0;


    private CaminoTimetablePathStops() {
    }


    static List<CaminoTimetablePathStop> normalizeRouteStops(
            double distanceM,
            List<CaminoTimetablePathStop> candidates
    ) {
        double routeEndM =
                validRouteDistance(
                        distanceM
                );

        List<CaminoTimetablePathStop> sorted =
                new ArrayList<>();

        if (candidates != null) {
            for (CaminoTimetablePathStop stop
                    : candidates) {

                if (stop == null
                        || !meaningful(
                        stop.placeKey
                )
                        || !Double.isFinite(
                        stop.chainageM
                )
                        || stop.chainageM < -SAME_CHAINAGE_M
                        || stop.chainageM
                        > routeEndM + SAME_CHAINAGE_M) {

                    continue;
                }

                sorted.add(
                        new CaminoTimetablePathStop(
                                stop.placeKey.trim(),
                                clamp(
                                        stop.chainageM,
                                        0.0,
                                        routeEndM
                                )
                        )
                );
            }
        }

        sorted.sort(
                Comparator.comparingDouble(
                        stop -> stop.chainageM
                )
        );

        List<CaminoTimetablePathStop> result =
                new ArrayList<>();

        for (CaminoTimetablePathStop candidate
                : sorted) {

            appendRouteStop(
                    result,
                    candidate
            );
        }

        /*
         * Start and goal are route semantics, not settlement semantics.
         * A route with no intermediate places is therefore still valid.
         */
        if (result.isEmpty()
                || result.get(
                0
        ).chainageM > SAME_CHAINAGE_M) {

            result.add(
                    0,
                    new CaminoTimetablePathStop(
                            "@start",
                            0.0
                    )
            );
        }

        CaminoTimetablePathStop last =
                result.get(
                        result.size() - 1
                );

        if (result.size() == 1
                || routeEndM
                - last.chainageM
                > SAME_CHAINAGE_M) {

            result.add(
                    new CaminoTimetablePathStop(
                            "@goal",
                            routeEndM
                    )
            );
        }

        return Collections.unmodifiableList(
                result
        );
    }


    static List<CaminoTimetablePathStop> mergeSettlements(
            double distanceM,
            List<CaminoTimetablePathStop> routeStops,
            List<SupplementalStop> settlements
    ) {
        double routeEndM =
                validRouteDistance(
                        distanceM
                );

        List<CaminoTimetablePathStop> result =
                new ArrayList<>(
                        normalizeRouteStops(
                                routeEndM,
                                routeStops
                        )
                );

        List<SupplementalStop> remaining =
                new ArrayList<>();

        if (settlements != null) {
            for (SupplementalStop stop
                    : settlements) {

                if (stop == null
                        || !meaningful(
                        stop.name
                )
                        || !Double.isFinite(
                        stop.chainageM
                )
                        || stop.chainageM < -SAME_CHAINAGE_M
                        || stop.chainageM
                        > routeEndM + SAME_CHAINAGE_M) {

                    continue;
                }

                remaining.add(
                        new SupplementalStop(
                                stop.name.trim(),
                                clamp(
                                        stop.chainageM,
                                        0.0,
                                        routeEndM
                                )
                        )
                );
            }
        }

        remaining.sort(
                Comparator.comparingDouble(
                        stop -> stop.chainageM
                )
        );

        /*
         * First enrich the already-selected route stops.
         *
         * Chainage NEVER comes from the settlement marker here. The selected
         * route owns the physical stop position.
         */
        int lastRouteIndex =
                result.size() - 1;

        for (int index = 0;
                index < result.size();
                index++) {

            CaminoTimetablePathStop routeStop =
                    result.get(
                            index
                    );

            boolean endpoint =
                    index == 0
                            || index == lastRouteIndex;

            SupplementalStop settlement =
                    consumeBestMatchingSettlement(
                            remaining,
                            routeStop,
                            endpoint
                    );

            if (settlement == null) {
                continue;
            }

            String label =
                    preferredLabel(
                            routeStop.placeKey,
                            settlement.name
                    );

            result.set(
                    index,
                    new CaminoTimetablePathStop(
                            label,
                            routeStop.chainageM
                    )
            );
        }

        /*
         * Only settlements which are genuinely additional route places remain.
         */
        for (SupplementalStop settlement
                : remaining) {

            mergeSupplementalStop(
                    result,
                    settlement
            );
        }

        result.sort(
                Comparator.comparingDouble(
                        stop -> stop.chainageM
                )
        );

        return Collections.unmodifiableList(
                result
        );
    }


    private static void appendRouteStop(
            List<CaminoTimetablePathStop> output,
            CaminoTimetablePathStop candidate
    ) {
        if (output.isEmpty()) {
            output.add(
                    candidate
            );
            return;
        }

        int lastIndex =
                output.size() - 1;

        CaminoTimetablePathStop previous =
                output.get(
                        lastIndex
                );

        if (Math.abs(
                previous.chainageM
                        - candidate.chainageM
        ) > SAME_CHAINAGE_M) {

            output.add(
                    candidate
            );
            return;
        }

        /*
         * Different resolver aliases can describe the same physical shell.
         * Prefer a real place key over an internal fork/merge key.
         */
        String preferred =
                preferredRouteKey(
                        previous.placeKey,
                        candidate.placeKey
                );

        output.set(
                lastIndex,
                new CaminoTimetablePathStop(
                        preferred,
                        previous.chainageM
                )
        );
    }


    private static SupplementalStop consumeBestMatchingSettlement(
            List<SupplementalStop> settlements,
            CaminoTimetablePathStop routeStop,
            boolean endpoint
    ) {
        if (settlements.isEmpty()
                || routeStop == null) {

            return null;
        }

        boolean named =
                isNamedPlaceKey(
                        routeStop.placeKey
                );

        String routeName =
                canonicalNameKey(
                        routeStop.placeKey
                );

        double limitM;

        if (endpoint) {
            limitM =
                    named
                            ? NAMED_ENDPOINT_SEARCH_M
                            : SYNTHETIC_ENDPOINT_SEARCH_M;
        } else {
            limitM =
                    SAME_PLACE_DISTANCE_M;
        }

        int bestIndex =
                -1;

        double bestDistanceM =
                Double.POSITIVE_INFINITY;

        for (int index = 0;
                index < settlements.size();
                index++) {

            SupplementalStop candidate =
                    settlements.get(
                            index
                    );

            double distanceM =
                    Math.abs(
                            candidate.chainageM
                                    - routeStop.chainageM
                    );

            boolean samePhysicalChainage =
                    distanceM
                            <= SAME_CHAINAGE_M;

            if (!samePhysicalChainage
                    && distanceM > limitM) {

                continue;
            }

            if (named
                    && !routeName.equals(
                    canonicalNameKey(
                            candidate.name
                    )
            )
                    && !samePhysicalChainage) {

                continue;
            }

            if (distanceM
                    < bestDistanceM) {

                bestIndex =
                        index;

                bestDistanceM =
                        distanceM;
            }
        }

        if (bestIndex < 0) {
            return null;
        }

        return settlements.remove(
                bestIndex
        );
    }


    private static void mergeSupplementalStop(
            List<CaminoTimetablePathStop> output,
            SupplementalStop settlement
    ) {
        String settlementName =
                canonicalNameKey(
                        settlement.name
                );

        for (int index = 0;
                index < output.size();
                index++) {

            CaminoTimetablePathStop existing =
                    output.get(
                            index
                    );

            double distanceM =
                    Math.abs(
                            existing.chainageM
                                    - settlement.chainageM
                    );

            boolean sameChainage =
                    distanceM
                            <= SAME_CHAINAGE_M;

            boolean sameName =
                    settlementName.equals(
                            canonicalNameKey(
                                    existing.placeKey
                            )
                    );

            boolean synthetic =
                    !isNamedPlaceKey(
                            existing.placeKey
                    );

            if (!sameChainage
                    && !(distanceM <= SAME_PLACE_DISTANCE_M
                    && (
                    sameName
                            || synthetic
            ))) {

                continue;
            }

            /*
             * Route chainage stays authoritative.
             *
             * A settlement may replace an internal synthetic label or provide
             * the human-readable spelling of the same named place.
             */
            if (synthetic
                    || sameName) {

                output.set(
                        index,
                        new CaminoTimetablePathStop(
                                settlement.name,
                                existing.chainageM
                        )
                );
            }

            return;
        }

        output.add(
                new CaminoTimetablePathStop(
                        settlement.name,
                        settlement.chainageM
                )
        );
    }


    private static String preferredLabel(
            String routeKey,
            String settlementName
    ) {
        if (!isNamedPlaceKey(
                routeKey
        )) {

            return settlementName;
        }

        if (canonicalNameKey(
                routeKey
        ).equals(
                canonicalNameKey(
                        settlementName
                )
        )) {

            return settlementName;
        }

        /*
         * Same exact physical stop but different real names:
         * route semantics win; suppress the duplicate settlement row.
         */
        return routeKey;
    }


    private static String preferredRouteKey(
            String first,
            String second
    ) {
        boolean firstNamed =
                isNamedPlaceKey(
                        first
                );

        boolean secondNamed =
                isNamedPlaceKey(
                        second
                );

        if (!firstNamed
                && secondNamed) {

            return second;
        }

        if (meaningful(
                first
        )) {

            return first;
        }

        return meaningful(
                second
        )
                ? second
                : "@route";
    }


    static boolean isNamedPlaceKey(
            String value
    ) {
        if (!meaningful(
                value
        )) {

            return false;
        }

        String lower =
                value.trim()
                        .toLowerCase(
                                Locale.ROOT
                        );

        return !lower.startsWith(
                "@"
        )
                && !lower.startsWith(
                "fork_"
        );
    }


    static String canonicalNameKey(
            String value
    ) {
        if (value == null) {
            return "";
        }

        String normalized =
                Normalizer.normalize(
                        value,
                        Normalizer.Form.NFD
                )
                        .replaceAll(
                                "\\p{M}+",
                                ""
                        )
                        .toLowerCase(
                                Locale.ROOT
                        );

        StringBuilder result =
                new StringBuilder();

        for (int index = 0;
                index < normalized.length();
                index++) {

            char c =
                    normalized.charAt(
                            index
                    );

            if (Character.isLetterOrDigit(
                    c
            )) {

                result.append(
                        c
                );
            }
        }

        return result.toString();
    }


    private static boolean meaningful(
            String value
    ) {
        return value != null
                && !value.trim()
                .isEmpty();
    }


    private static double validRouteDistance(
            double value
    ) {
        return Double.isFinite(
                value
        )
                && value >= 0.0
                ? value
                : 0.0;
    }


    private static double clamp(
            double value,
            double minimum,
            double maximum
    ) {
        return Math.max(
                minimum,
                Math.min(
                        maximum,
                        value
                )
        );
    }


    static final class SupplementalStop {

        final String name;
        final double chainageM;


        SupplementalStop(
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
