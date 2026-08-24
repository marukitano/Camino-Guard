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
 * Source track ids and source endpoint semantics are authoritative. A run may
 * contain one track or continue across several official section numbers. Track
 * proximity never invents membership; geometry is used only to orient tracks
 * that the source semantics have already connected.
 *
 * Example:
 *   ES19a:06a              primary
 *   ES19a:06b + ES19a:06c one alternative run
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

    /*
     * Geometry is NOT allowed to invent topology here.
     *
     * A connection exists only when the source semantics say so:
     *   left.toKey == right.fromKey
     * and neither endpoint is a pseudo label such as "variante".
     *
     * Distance is used only as a sanity check against equal place names that
     * are geographically unrelated, and later to orient geometry that is
     * already known to belong to the same source-defined run.
     */
    private static final double SAME_PLACE_SANITY_DISTANCE_M =
            2500.0;


    static void rebuild(
            CaminoRoute route
    ) {
        route.variantPaths.clear();

        List<RouteTrack> variants =
                new ArrayList<>();

        for (RouteTrack track
                : route.renderTracks) {

            if (!route.tracks.contains(
                    track
            )) {
                variants.add(
                        track
                );
            }
        }

        variants.sort(
                Comparator
                        .comparingInt(
                                (RouteTrack track) ->
                                        track.order
                        )
                        .thenComparing(
                                (RouteTrack track) ->
                                        track.sectionId
                        )
        );

        Map<RouteTrack, List<RouteTrack>> successors =
                new LinkedHashMap<>();

        Map<RouteTrack, List<RouteTrack>> predecessors =
                new LinkedHashMap<>();

        for (RouteTrack track
                : variants) {

            successors.put(
                    track,
                    new ArrayList<>()
            );

            predecessors.put(
                    track,
                    new ArrayList<>()
            );
        }

        for (RouteTrack left
                : variants) {

            for (RouteTrack right
                    : variants) {

                if (left == right
                        || !sourceCanFollow(
                        left,
                        right
                )) {
                    continue;
                }

                successors.get(
                        left
                ).add(
                        right
                );

                predecessors.get(
                        right
                ).add(
                        left
                );
            }
        }

        for (List<RouteTrack> list
                : successors.values()) {

            list.sort(
                    CaminoVariantPathBuilder::compareSourceOrder
            );
        }

        for (List<RouteTrack> list
                : predecessors.values()) {

            list.sort(
                    CaminoVariantPathBuilder::compareSourceOrder
            );
        }

        Map<RouteTrack, Boolean> used =
                new LinkedHashMap<>();

        for (RouteTrack track
                : variants) {

            used.put(
                    track,
                    false
            );
        }

        List<List<RouteTrack>> chains =
                new ArrayList<>();

        /*
         * Start only where there is no unique, reciprocal predecessor.
         * At a real branch/merge we deliberately split the run instead of
         * guessing which source track "probably" belongs to which path.
         */
        for (RouteTrack seed
                : variants) {

            if (Boolean.TRUE.equals(
                    used.get(
                            seed
                    )
            )
                    || hasUnambiguousPredecessor(
                    seed,
                    predecessors,
                    successors
            )) {
                continue;
            }

            chains.add(
                    collectUnambiguousChain(
                            seed,
                            successors,
                            predecessors,
                            used
                    )
            );
        }

        /*
         * Increasing source order makes cycles very unlikely, but never drop a
         * source track if malformed data leaves one outside the pass above.
         */
        for (RouteTrack seed
                : variants) {

            if (Boolean.TRUE.equals(
                    used.get(
                            seed
                    )
            )) {
                continue;
            }

            chains.add(
                    collectUnambiguousChain(
                            seed,
                            successors,
                            predecessors,
                            used
                    )
            );
        }

        chains.sort(
                (left, right) -> {
                    int orderCompare =
                            compareSourceOrder(
                                    left.get(
                                            0
                                    ),
                                    right.get(
                                            0
                                    )
                            );

                    if (orderCompare != 0) {
                        return orderCompare;
                    }

                    return Integer.compare(
                            left.size(),
                            right.size()
                    );
                }
        );

        int runIndex =
                1;

        for (List<RouteTrack> chain
                : chains) {

            if (chain.isEmpty()) {
                continue;
            }

            List<CaminoVariantPathPart> parts =
                    normalizeVariantGeometry(
                            orientKnownChain(
                                    route,
                                    chain
                            )
                    );

            int sectionNumber =
                    chain.get(
                            0
                    ).order;

            route.variantPaths.add(
                    new CaminoVariantPath(
                            buildId(
                                    route,
                                    runIndex,
                                    parts
                            ),
                            route,
                            sectionNumber,
                            runIndex,
                            parts
                    )
            );

            runIndex++;
        }
    }


    private static List<RouteTrack> collectUnambiguousChain(
            RouteTrack seed,
            Map<RouteTrack, List<RouteTrack>> successors,
            Map<RouteTrack, List<RouteTrack>> predecessors,
            Map<RouteTrack, Boolean> used
    ) {
        List<RouteTrack> chain =
                new ArrayList<>();

        RouteTrack current =
                seed;

        while (current != null
                && !Boolean.TRUE.equals(
                used.get(
                        current
                )
        )) {

            chain.add(
                    current
            );

            used.put(
                    current,
                    true
            );

            current =
                    unambiguousSuccessor(
                            current,
                            successors,
                            predecessors
                    );
        }

        return chain;
    }


    private static boolean hasUnambiguousPredecessor(
            RouteTrack track,
            Map<RouteTrack, List<RouteTrack>> predecessors,
            Map<RouteTrack, List<RouteTrack>> successors
    ) {
        List<RouteTrack> incoming =
                predecessors.get(
                        track
                );

        if (incoming == null
                || incoming.size() != 1) {
            return false;
        }

        RouteTrack predecessor =
                incoming.get(
                        0
                );

        List<RouteTrack> outgoing =
                successors.get(
                        predecessor
                );

        return outgoing != null
                && outgoing.size() == 1;
    }


    private static RouteTrack unambiguousSuccessor(
            RouteTrack track,
            Map<RouteTrack, List<RouteTrack>> successors,
            Map<RouteTrack, List<RouteTrack>> predecessors
    ) {
        List<RouteTrack> outgoing =
                successors.get(
                        track
                );

        if (outgoing == null
                || outgoing.size() != 1) {
            return null;
        }

        RouteTrack candidate =
                outgoing.get(
                        0
                );

        List<RouteTrack> incoming =
                predecessors.get(
                        candidate
                );

        if (incoming == null
                || incoming.size() != 1) {
            return null;
        }

        return candidate;
    }


    private static boolean sourceCanFollow(
            RouteTrack left,
            RouteTrack right
    ) {
        if (left.pseudoTo
                || right.pseudoFrom
                || left.toKey == null
                || right.fromKey == null
                || !left.toKey.equals(
                right.fromKey
        )) {
            return false;
        }

        /*
         * Source numbering is directional. Never connect backwards in the
         * official sequence. Same-number pieces such as 06b -> 06c are valid.
         */
        if (right.order
                < left.order) {
            return false;
        }

        if (right.order
                == left.order
                && right.sectionId.compareToIgnoreCase(
                left.sectionId
        ) <= 0) {
            return false;
        }

        /*
         * This is only a homonym guard. Unequal semantic keys NEVER connect,
         * regardless of geometric distance.
         */
        return nearestEndpointDistance(
                left,
                right
        ) <= SAME_PLACE_SANITY_DISTANCE_M;
    }


    private static double nearestEndpointDistance(
            RouteTrack left,
            RouteTrack right
    ) {
        LatLng leftFirst =
                left.points.get(
                        0
                );

        LatLng leftLast =
                left.points.get(
                        left.points.size() - 1
                );

        LatLng rightFirst =
                right.points.get(
                        0
                );

        LatLng rightLast =
                right.points.get(
                        right.points.size() - 1
                );

        return Math.min(
                Math.min(
                        GeoMath.distanceMeters(
                                leftFirst,
                                rightFirst
                        ),
                        GeoMath.distanceMeters(
                                leftFirst,
                                rightLast
                        )
                ),
                Math.min(
                        GeoMath.distanceMeters(
                                leftLast,
                                rightFirst
                        ),
                        GeoMath.distanceMeters(
                                leftLast,
                                rightLast
                        )
                )
        );
    }


    /**
     * The source semantics already fixed membership and order. Geometry is used
     * here only to decide whether each KML polyline must be read forwards or
     * backwards so measurement/ascent/descent follow the source-defined run.
     */
    private static List<CaminoVariantPathPart> orientKnownChain(
            CaminoRoute route,
            List<RouteTrack> chain
    ) {
        int count =
                chain.size();

        if (count == 1) {
            RouteTrack track =
                    chain.get(
                            0
                    );

            double normal =
                    endpointAnchorCost(
                            route,
                            track.fromKey,
                            startPoint(
                                    track,
                                    false
                            )
                    )
                            + endpointAnchorCost(
                            route,
                            track.toKey,
                            endPoint(
                                    track,
                                    false
                            )
                    );

            double reversed =
                    endpointAnchorCost(
                            route,
                            track.fromKey,
                            startPoint(
                                    track,
                                    true
                            )
                    )
                            + endpointAnchorCost(
                            route,
                            track.toKey,
                            endPoint(
                                    track,
                                    true
                            )
                    );

            boolean reverseTrack;

            if (Math.abs(
                    normal - reversed
            ) > 1.0) {

                reverseTrack =
                        reversed < normal;

            } else {
                PrimaryProgress first =
                        nearestPrimaryProgress(
                                route,
                                startPoint(
                                        track,
                                        false
                                )
                        );

                PrimaryProgress last =
                        nearestPrimaryProgress(
                                route,
                                endPoint(
                                        track,
                                        false
                                )
                        );

                if (first != null
                        && last != null
                        && first.distanceM
                        <= PRIMARY_ORIENTATION_MAX_DISTANCE_M
                        && last.distanceM
                        <= PRIMARY_ORIENTATION_MAX_DISTANCE_M
                        && Math.abs(
                        first.progressM
                                - last.progressM
                ) > PRIMARY_ORIENTATION_MIN_PROGRESS_M) {

                    reverseTrack =
                            first.progressM
                                    > last.progressM;

                } else {
                    reverseTrack =
                            reversed < normal;
                }
            }

            List<CaminoVariantPathPart> single =
                    new ArrayList<>();

            single.add(
                    new CaminoVariantPathPart(
                            track,
                            reverseTrack
                    )
            );

            return single;
        }

        double[][] cost =
                new double[count][2];

        int[][] previousState =
                new int[count][2];

        RouteTrack first =
                chain.get(
                        0
                );

        for (int state = 0;
                state < 2;
                state++) {

            boolean reversed =
                    state == 1;

            cost[0][state] =
                    endpointAnchorCost(
                            route,
                            first.fromKey,
                            startPoint(
                                    first,
                                    reversed
                            )
                    );

            previousState[0][state] =
                    -1;
        }

        for (int index = 1;
                index < count;
                index++) {

            RouteTrack current =
                    chain.get(
                            index
                    );

            RouteTrack previous =
                    chain.get(
                            index - 1
                    );

            for (int state = 0;
                    state < 2;
                    state++) {

                boolean currentReversed =
                        state == 1;

                double best =
                        Double.POSITIVE_INFINITY;

                int bestPreviousState =
                        0;

                for (int priorState = 0;
                        priorState < 2;
                        priorState++) {

                    boolean previousReversed =
                            priorState == 1;

                    double candidate =
                            cost[index - 1][priorState]
                                    + GeoMath.distanceMeters(
                                    endPoint(
                                            previous,
                                            previousReversed
                                    ),
                                    startPoint(
                                            current,
                                            currentReversed
                                    )
                            );

                    if (candidate < best) {
                        best =
                                candidate;

                        bestPreviousState =
                                priorState;
                    }
                }

                cost[index][state] =
                        best;

                previousState[index][state] =
                        bestPreviousState;
            }
        }

        RouteTrack last =
                chain.get(
                        count - 1
                );

        double normalFinal =
                cost[count - 1][0]
                        + endpointAnchorCost(
                        route,
                        last.toKey,
                        endPoint(
                                last,
                                false
                        )
                );

        double reversedFinal =
                cost[count - 1][1]
                        + endpointAnchorCost(
                        route,
                        last.toKey,
                        endPoint(
                                last,
                                true
                        )
                );

        int state =
                reversedFinal < normalFinal
                        ? 1
                        : 0;

        boolean[] reversed =
                new boolean[count];

        for (int index = count - 1;
                index >= 0;
                index--) {

            reversed[index] =
                    state == 1;

            state =
                    previousState[index][state];
        }

        List<CaminoVariantPathPart> parts =
                new ArrayList<>();

        for (int index = 0;
                index < count;
                index++) {

            parts.add(
                    new CaminoVariantPathPart(
                            chain.get(
                                    index
                            ),
                            reversed[index]
                    )
            );
        }

        return parts;
    }


    /*
     * Tie-breaker only: this NEVER invents topology. The primary geometry has
     * already been oriented by CaminoRepository, so its accumulated chainage
     * gives a stable forward direction along the Camino.
     */
    private static final double PRIMARY_ORIENTATION_MAX_DISTANCE_M =
            250.0;

    private static final double PRIMARY_ORIENTATION_MIN_PROGRESS_M =
            50.0;


    private static PrimaryProgress nearestPrimaryProgress(
            CaminoRoute route,
            LatLng point
    ) {
        PrimaryProgress best =
                null;

        for (RouteTrack primary
                : route.tracks) {

            if (primary.points.size() < 2) {
                continue;
            }

            double chainageAtA =
                    0.0;

            for (int segmentIndex = 0;
                    segmentIndex
                    < primary.points.size() - 1;
                    segmentIndex++) {

                LatLng a =
                        primary.points.get(
                                segmentIndex
                        );

                LatLng b =
                        primary.points.get(
                                segmentIndex + 1
                        );

                SegmentProgress hit =
                        projectToSegment(
                                point,
                                a,
                                b
                        );

                double segmentLength =
                        GeoMath.distanceMeters(
                                a,
                                b
                        );

                if (best == null
                        || hit.distanceM
                        < best.distanceM) {

                    best =
                            new PrimaryProgress(
                                    hit.distanceM,
                                    primary.baseChainageM
                                            + chainageAtA
                                            + hit.t
                                            * segmentLength
                            );
                }

                chainageAtA +=
                        segmentLength;
            }
        }

        return best;
    }


    private static SegmentProgress projectToSegment(
            LatLng query,
            LatLng a,
            LatLng b
    ) {
        double referenceLatitude =
                Math.toRadians(
                        (
                                query.getLatitude()
                                        + a.getLatitude()
                                        + b.getLatitude()
                        ) / 3.0
                );

        double cosLatitude =
                Math.max(
                        0.20,
                        Math.cos(
                                referenceLatitude
                        )
                );

        double ax =
                Math.toRadians(
                        a.getLongitude()
                                - query.getLongitude()
                )
                        * GeoMath.EARTH_RADIUS_M
                        * cosLatitude;

        double ay =
                Math.toRadians(
                        a.getLatitude()
                                - query.getLatitude()
                )
                        * GeoMath.EARTH_RADIUS_M;

        double bx =
                Math.toRadians(
                        b.getLongitude()
                                - query.getLongitude()
                )
                        * GeoMath.EARTH_RADIUS_M
                        * cosLatitude;

        double by =
                Math.toRadians(
                        b.getLatitude()
                                - query.getLatitude()
                )
                        * GeoMath.EARTH_RADIUS_M;

        double vx =
                bx - ax;

        double vy =
                by - ay;

        double denominator =
                vx * vx
                        + vy * vy;

        double t =
                0.0;

        if (denominator > 1e-9) {
            t =
                    -(ax * vx
                            + ay * vy)
                            / denominator;

            t =
                    Math.max(
                            0.0,
                            Math.min(
                                    1.0,
                                    t
                            )
                    );
        }

        double px =
                ax + t * vx;

        double py =
                ay + t * vy;

        return new SegmentProgress(
                Math.hypot(
                        px,
                        py
                ),
                t
        );
    }


    /*
     * Once direction is known, normalize the mutable RouteTrack geometry
     * itself. This makes resolver, measurement and temporary raw endpoint debug
     * labels all see the same forward direction. fromKey/toKey stay unchanged,
     * exactly like primary geometry orientation in CaminoRepository.
     */
    private static List<CaminoVariantPathPart> normalizeVariantGeometry(
            List<CaminoVariantPathPart> oriented
    ) {
        List<CaminoVariantPathPart> result =
                new ArrayList<>();

        for (CaminoVariantPathPart part
                : oriented) {

            if (part.reversed) {
                Collections.reverse(
                        part.track.points
                );

                Collections.reverse(
                        part.track.elevations
                );
            }

            result.add(
                    new CaminoVariantPathPart(
                            part.track,
                            false
                    )
            );
        }

        return result;
    }


    private static final class PrimaryProgress {

        final double distanceM;
        final double progressM;


        PrimaryProgress(
                double distanceM,
                double progressM
        ) {
            this.distanceM =
                    distanceM;

            this.progressM =
                    progressM;
        }
    }


    private static final class SegmentProgress {

        final double distanceM;
        final double t;


        SegmentProgress(
                double distanceM,
                double t
        ) {
            this.distanceM =
                    distanceM;

            this.t =
                    t;
        }
    }


    private static double endpointAnchorCost(
            CaminoRoute route,
            String semanticKey,
            LatLng point
    ) {
        if (semanticKey == null) {
            return 0.0;
        }

        double best =
                Double.POSITIVE_INFINITY;

        for (RouteTrack primary
                : route.tracks) {

            if (!semanticKey.equals(
                    primary.fromKey
            )
                    && !semanticKey.equals(
                    primary.toKey
            )) {
                continue;
            }

            best =
                    Math.min(
                            best,
                            GeoMath.distanceMeters(
                                    point,
                                    primary.points.get(
                                            0
                                    )
                            )
                    );

            best =
                    Math.min(
                            best,
                            GeoMath.distanceMeters(
                                    point,
                                    primary.points.get(
                                            primary.points.size() - 1
                                    )
                            )
                    );
        }

        return Double.isFinite(
                best
        )
                ? best
                : 0.0;
    }


    private static LatLng startPoint(
            RouteTrack track,
            boolean reversed
    ) {
        return reversed
                ? track.points.get(
                track.points.size() - 1
        )
                : track.points.get(
                0
        );
    }


    private static LatLng endPoint(
            RouteTrack track,
            boolean reversed
    ) {
        return reversed
                ? track.points.get(
                0
        )
                : track.points.get(
                track.points.size() - 1
        );
    }


    private static int compareSourceOrder(
            RouteTrack left,
            RouteTrack right
    ) {
        int byNumber =
                Integer.compare(
                        left.order,
                        right.order
                );

        if (byNumber != 0) {
            return byNumber;
        }

        return left.sectionId.compareToIgnoreCase(
                right.sectionId
        );
    }


    private static String buildId(
            CaminoRoute route,
            int runIndex,
            List<CaminoVariantPathPart> parts
    ) {
        StringBuilder id =
                new StringBuilder();

        id.append(
                route.id
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
}
