package com.marukitano.caminoguard;

import org.maplibre.android.geometry.LatLng;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


/**
 * One complete official alternative run.
 *
 * CNIG section ids are authoritative. The numeric part is the stage/family
 * number. Within one family, non-primary tracks are chained only when their
 * official endpoints actually connect.
 *
 * Example:
 *   ES19a:06a              primary
 *   ES19a:06b + ES19a:06c one alternative run
 *
 * No primary-route projection, merge inference or place-specific exception is
 * involved here.
 */
final class CaminoVariantPath {

    final String id;
    final CaminoRoute route;
    final int sectionNumber;
    final int runIndex;
    final List<CaminoVariantPathPart> parts;

    CaminoVariantPath(
            String id,
            CaminoRoute route,
            int sectionNumber,
            int runIndex,
            List<CaminoVariantPathPart> parts
    ) {
        this.id =
                id;

        this.route =
                route;

        this.sectionNumber =
                sectionNumber;

        this.runIndex =
                runIndex;

        this.parts =
                Collections.unmodifiableList(
                        new ArrayList<>(
                                parts
                        )
                );
    }


    LatLng startPoint() {
        return parts.get(
                0
        ).startPoint();
    }


    LatLng endPoint() {
        return parts.get(
                parts.size() - 1
        ).endPoint();
    }


    String trackIdsLabel() {
        StringBuilder result =
                new StringBuilder();

        for (CaminoVariantPathPart part
                : parts) {

            if (result.length() > 0) {
                result.append(
                        "+"
                );
            }

            result.append(
                    route.id
            );

            result.append(
                    ":"
            );

            result.append(
                    part.track.sectionId
            );
        }

        return result.toString();
    }
}


final class CaminoVariantPathPart {

    final RouteTrack track;
    final boolean reversed;

    CaminoVariantPathPart(
            RouteTrack track,
            boolean reversed
    ) {
        this.track =
                track;

        this.reversed =
                reversed;
    }


    LatLng startPoint() {
        return reversed
                ? track.points.get(
                track.points.size() - 1
        )
                : track.points.get(
                0
        );
    }


    LatLng endPoint() {
        return reversed
                ? track.points.get(
                0
        )
                : track.points.get(
                track.points.size() - 1
        );
    }


    String startKey() {
        return reversed
                ? track.toKey
                : track.fromKey;
    }


    String endKey() {
        return reversed
                ? track.fromKey
                : track.toKey;
    }


    boolean startPseudo() {
        return reversed
                ? track.pseudoTo
                : track.pseudoFrom;
    }


    boolean endPseudo() {
        return reversed
                ? track.pseudoFrom
                : track.pseudoTo;
    }
}


/**
 * Builds alternative runs directly from the official track numbering.
 *
 * Rule:
 * - primary tracks are already route.tracks and stay untouched
 * - every other track belongs to the family of its numeric section number
 * - inside that family, tracks are chained when official endpoints match
 * - geometric fallback is intentionally small and is only used when source
 *   labels are pseudo/unequal
 */
final class CaminoVariantPathBuilder {

    private static final double SAME_KEY_MAX_DISTANCE_M =
            2500.0;

    private static final double GEOMETRY_JOIN_DISTANCE_M =
            500.0;


    static void rebuild(
            CaminoRoute route
    ) {
        route.variantPaths.clear();

        Map<Integer, List<RouteTrack>> families =
                new LinkedHashMap<>();

        for (RouteTrack track
                : route.renderTracks) {

            if (route.tracks.contains(
                    track
            )) {
                continue;
            }

            families.computeIfAbsent(
                    track.order,
                    ignored ->
                            new ArrayList<>()
            ).add(
                    track
            );
        }

        List<Integer> familyNumbers =
                new ArrayList<>(
                        families.keySet()
                );

        Collections.sort(
                familyNumbers
        );

        for (Integer familyNumber
                : familyNumbers) {

            List<RouteTrack> remaining =
                    new ArrayList<>(
                            families.get(
                                    familyNumber
                            )
                    );

            remaining.sort(
                    Comparator.comparing(
                            track ->
                                    track.sectionId
                    )
            );

            int runIndex =
                    1;

            while (!remaining.isEmpty()) {
                RouteTrack seed =
                        remaining.remove(
                                0
                        );

                List<CaminoVariantPathPart> parts =
                        new ArrayList<>();

                parts.add(
                        new CaminoVariantPathPart(
                                seed,
                                false
                        )
                );

                growForward(
                        parts,
                        remaining
                );

                growBackward(
                        parts,
                        remaining
                );

                String id =
                        buildId(
                                route,
                                familyNumber,
                                runIndex,
                                parts
                        );

                route.variantPaths.add(
                        new CaminoVariantPath(
                                id,
                                route,
                                familyNumber,
                                runIndex,
                                parts
                        )
                );

                runIndex++;
            }
        }

        route.variantPaths.sort(
                Comparator
                        .comparingInt(
                                path ->
                                        path.sectionNumber
                        )
                        .thenComparingInt(
                                path ->
                                        path.runIndex
                        )
        );
    }


    private static void growForward(
            List<CaminoVariantPathPart> parts,
            List<RouteTrack> remaining
    ) {
        while (!remaining.isEmpty()) {
            CaminoVariantPathPart tail =
                    parts.get(
                            parts.size() - 1
                    );

            Endpoint wanted =
                    endpointAtEnd(
                            tail
                    );

            Connection best =
                    bestForwardConnection(
                            wanted,
                            remaining
                    );

            if (best == null) {
                return;
            }

            remaining.remove(
                    best.track
            );

            parts.add(
                    new CaminoVariantPathPart(
                            best.track,
                            best.reversed
                    )
            );
        }
    }


    private static void growBackward(
            List<CaminoVariantPathPart> parts,
            List<RouteTrack> remaining
    ) {
        while (!remaining.isEmpty()) {
            CaminoVariantPathPart head =
                    parts.get(
                            0
                    );

            Endpoint wanted =
                    endpointAtStart(
                            head
                    );

            Connection best =
                    bestBackwardConnection(
                            wanted,
                            remaining
                    );

            if (best == null) {
                return;
            }

            remaining.remove(
                    best.track
            );

            parts.add(
                    0,
                    new CaminoVariantPathPart(
                            best.track,
                            best.reversed
                    )
            );
        }
    }


    private static Connection bestForwardConnection(
            Endpoint wanted,
            List<RouteTrack> remaining
    ) {
        Connection best =
                null;

        for (RouteTrack candidate
                : remaining) {

            Endpoint normalStart =
                    rawStart(
                            candidate
                    );

            double normalScore =
                    connectionScore(
                            wanted,
                            normalStart
                    );

            if (Double.isFinite(
                    normalScore
            )
                    && (
                    best == null
                            || normalScore
                            < best.score
            )) {

                best =
                        new Connection(
                                candidate,
                                false,
                                normalScore
                        );
            }

            Endpoint reversedStart =
                    rawEnd(
                            candidate
                    );

            double reversedScore =
                    connectionScore(
                            wanted,
                            reversedStart
                    );

            if (Double.isFinite(
                    reversedScore
            )
                    && (
                    best == null
                            || reversedScore
                            < best.score
            )) {

                best =
                        new Connection(
                                candidate,
                                true,
                                reversedScore
                        );
            }
        }

        return best;
    }


    private static Connection bestBackwardConnection(
            Endpoint wanted,
            List<RouteTrack> remaining
    ) {
        Connection best =
                null;

        for (RouteTrack candidate
                : remaining) {

            Endpoint normalEnd =
                    rawEnd(
                            candidate
                    );

            double normalScore =
                    connectionScore(
                            normalEnd,
                            wanted
                    );

            if (Double.isFinite(
                    normalScore
            )
                    && (
                    best == null
                            || normalScore
                            < best.score
            )) {

                best =
                        new Connection(
                                candidate,
                                false,
                                normalScore
                        );
            }

            Endpoint reversedEnd =
                    rawStart(
                            candidate
                    );

            double reversedScore =
                    connectionScore(
                            reversedEnd,
                            wanted
                    );

            if (Double.isFinite(
                    reversedScore
            )
                    && (
                    best == null
                            || reversedScore
                            < best.score
            )) {

                best =
                        new Connection(
                                candidate,
                                true,
                                reversedScore
                        );
            }
        }

        return best;
    }


    private static double connectionScore(
            Endpoint left,
            Endpoint right
    ) {
        double distanceM =
                GeoMath.distanceMeters(
                        left.point,
                        right.point
                );

        boolean exactSemantic =
                !left.pseudo
                        && !right.pseudo
                        && left.key != null
                        && right.key != null
                        && left.key.equals(
                        right.key
                );

        if (exactSemantic
                && distanceM
                <= SAME_KEY_MAX_DISTANCE_M) {

            return distanceM;
        }

        if (distanceM
                <= GEOMETRY_JOIN_DISTANCE_M) {

            return 100_000.0
                    + distanceM;
        }

        return Double.POSITIVE_INFINITY;
    }


    private static Endpoint endpointAtStart(
            CaminoVariantPathPart part
    ) {
        return new Endpoint(
                part.startPoint(),
                part.startKey(),
                part.startPseudo()
        );
    }


    private static Endpoint endpointAtEnd(
            CaminoVariantPathPart part
    ) {
        return new Endpoint(
                part.endPoint(),
                part.endKey(),
                part.endPseudo()
        );
    }


    private static Endpoint rawStart(
            RouteTrack track
    ) {
        return new Endpoint(
                track.points.get(
                        0
                ),
                track.fromKey,
                track.pseudoFrom
        );
    }


    private static Endpoint rawEnd(
            RouteTrack track
    ) {
        return new Endpoint(
                track.points.get(
                        track.points.size() - 1
                ),
                track.toKey,
                track.pseudoTo
        );
    }


    private static String buildId(
            CaminoRoute route,
            int familyNumber,
            int runIndex,
            List<CaminoVariantPathPart> parts
    ) {
        StringBuilder id =
                new StringBuilder();

        id.append(
                route.id
        );

        id.append(
                ":"
        );

        if (familyNumber < 10) {
            id.append(
                    "0"
            );
        }

        id.append(
                familyNumber
        );

        id.append(
                ":alt"
        );

        id.append(
                runIndex
        );

        id.append(
                ":"
        );

        boolean first =
                true;

        for (CaminoVariantPathPart part
                : parts) {

            if (!first) {
                id.append(
                        "+"
                );
            }

            first =
                    false;

            id.append(
                    part.track.sectionId
            );
        }

        return id.toString();
    }


    private static final class Endpoint {

        final LatLng point;
        final String key;
        final boolean pseudo;

        Endpoint(
                LatLng point,
                String key,
                boolean pseudo
        ) {
            this.point =
                    point;

            this.key =
                    key;

            this.pseudo =
                    pseudo;
        }
    }


    private static final class Connection {

        final RouteTrack track;
        final boolean reversed;
        final double score;

        Connection(
                RouteTrack track,
                boolean reversed,
                double score
        ) {
            this.track =
                    track;

            this.reversed =
                    reversed;

            this.score =
                    score;
        }
    }
}
