package com.marukitano.caminoguard;

import org.maplibre.android.geometry.LatLng;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;


/**
 * Stage-only resolver for real Muschel -> Muschel alternatives.
 *
 * The primary StageTopology owns the shells. Official CaminoVariantPath runs
 * are branches inside the physical GPS network between those shells.
 *
 * Automatic physical joins are deliberately strict: <= 25 m, same route
 * group, and only between already-existing official track geometries.
 * No long connector geometry is invented.
 */
final class CaminoStagePathResolver {

    private static final double JOIN_TOLERANCE_M =
            25.0;

    private static final double SHELL_ON_VARIANT_TOLERANCE_M =
            10.0;

    private static final double EVENT_EPSILON_M =
            0.25;

    private static final int MAX_DEPTH =
            24;

    private static final int MAX_RESULTS_PER_STAGE =
            64;


    private final Map<CaminoRoute, RouteState> states =
            new IdentityHashMap<>();


    void rebuild(
            List<CaminoRoute> routes,
            CaminoStageTopology topology
    ) {
        states.clear();

        for (CaminoRoute route
                : routes) {

            RouteState state =
                    new RouteState(
                            route
                    );

            state.indexTracks();

            states.put(
                    route,
                    state
            );
        }

        /*
         * Use the already-established StageTopology to learn which physical
         * endpoint of every prepared primary track is FROM and which is TO.
         * This is important because CaminoRepository may reverse geometry
         * without swapping fromKey/toKey.
         */
        if (topology != null) {
            for (CaminoStageTopology.StageNode node
                    : topology.nodes()) {

                for (CaminoStageTopology.StageEdge edge
                        : node.outgoing()) {

                    RouteState state =
                            states.get(
                                    edge.route
                            );

                    if (state == null) {
                        continue;
                    }

                    state.registerPrimaryStage(
                            edge.primaryTrack,
                            edge.primaryTrackIndex,
                            node.placeKey,
                            edge.toPlaceKey,
                            node.point
                    );
                }
            }
        }

        for (RouteState state
                : states.values()) {

            state.finishBuild();
        }
    }


    List<CaminoResolvedStagePath> findAlternatives(
            CaminoRoute route,
            int primaryTrackIndex,
            String startPlaceKey
    ) {
        RouteState state =
                states.get(
                        route
                );

        if (state == null
                || primaryTrackIndex < 0
                || primaryTrackIndex
                >= route.tracks.size()) {

            return Collections.emptyList();
        }

        RouteTrack primary =
                route.tracks.get(
                        primaryTrackIndex
                );

        PrimaryStageInfo stage =
                state.primaryStages.get(
                        primary
                );

        if (stage == null) {
            return Collections.emptyList();
        }

        Cursor start =
                Cursor.primary(
                        primary,
                        primaryTrackIndex,
                        stage.start,
                        stage.direction
                );

        List<CompletedPath> completed =
                new ArrayList<>();

        walk(
                state,
                start,
                new ArrayList<>(),
                new LinkedHashSet<>(),
                completed,
                0
        );

        List<CaminoResolvedStagePath> result =
                new ArrayList<>();

        Set<String> seen =
                new LinkedHashSet<>();

        for (CompletedPath item
                : completed) {

            /*
             * The ordinary primary choice is already inserted by
             * CaminoController. Only paths that actually used at least one
             * official variant belong here.
             */
            if (item.usedVariantIds.isEmpty()
                    || item.shellHit == null
                    || !meaningful(
                    item.destinationPlaceKey
            )) {

                continue;
            }

            String id =
                    signature(
                            route,
                            startPlaceKey,
                            item
                    );

            if (!seen.add(
                    id
            )) {
                continue;
            }

            result.add(
                    new CaminoResolvedStagePath(
                            id,
                            route,
                            startPlaceKey,
                            item.destinationPlaceKey,
                            stage.start.toHit(
                                    primaryTrackIndex
                            ),
                            item.shellHit,
                            item.legs,
                            item.usedVariantIds
                    )
            );
        }

        result.sort(
                Comparator
                        .comparing(
                                (CaminoResolvedStagePath path) ->
                                        path.destinationPlaceKey
                        )
                        .thenComparing(
                                path ->
                                        path.id
                        )
        );

        return result;
    }


    private void walk(
            RouteState state,
            Cursor cursor,
            List<CaminoResolvedStageLeg> prefix,
            LinkedHashSet<String> usedVariantIds,
            List<CompletedPath> completed,
            int depth
    ) {
        if (cursor == null
                || cursor.track == null
                || depth > MAX_DEPTH
                || completed.size()
                >= MAX_RESULTS_PER_STAGE) {

            return;
        }

        double physicalEndChainage =
                cursor.direction > 0
                        ? cursor.trackLengthM
                        : 0.0;

        ShellEvent shell =
                nearestShellAhead(
                        state,
                        cursor
                );

        double terminalChainage =
                shell == null
                        ? physicalEndChainage
                        : shell.trackProjection.chainageM;

        List<BranchEvent> branches =
                branchesAhead(
                        state,
                        cursor,
                        terminalChainage
                );

        /*
         * Each branch encountered before the next shell becomes a separate
         * route possibility. The recursive traversal can encounter further
         * branches on the variant or after it rejoins another official track.
         */
        for (BranchEvent branch
                : branches) {

            if (completed.size()
                    >= MAX_RESULTS_PER_STAGE) {
                break;
            }

            String branchId =
                    branch.attachment.path.id;

            if (usedVariantIds.contains(
                    branchId
            )) {
                continue;
            }

            List<CaminoResolvedStageLeg> branchPrefix =
                    copyWithSlice(
                            prefix,
                            cursor,
                            branch.target
                    );

            LinkedHashSet<String> branchUsed =
                    new LinkedHashSet<>(
                            usedVariantIds
                    );

            branchUsed.add(
                    branchId
            );

            Cursor variantStart =
                    variantPartStartCursor(
                            state,
                            branch.attachment.path,
                            0
                    );

            if (variantStart == null) {
                continue;
            }

            walk(
                    state,
                    variantStart,
                    branchPrefix,
                    branchUsed,
                    completed,
                    depth + 1
            );
        }

        List<CaminoResolvedStageLeg> straight =
                copyWithSliceToChainage(
                        prefix,
                        cursor,
                        terminalChainage
                );

        if (shell != null) {
            completed.add(
                    new CompletedPath(
                            straight,
                            shell.placeKey,
                            shell.shellHit,
                            usedVariantIds
                    )
            );

            return;
        }

        if (cursor.primaryTrackIndex >= 0) {
            continueAfterPrimaryBoundary(
                    state,
                    cursor,
                    straight,
                    usedVariantIds,
                    completed,
                    depth
            );

        } else {
            continueAfterVariantBoundary(
                    state,
                    cursor,
                    straight,
                    usedVariantIds,
                    completed,
                    depth
            );
        }
    }


    private void continueAfterPrimaryBoundary(
            RouteState state,
            Cursor cursor,
            List<CaminoResolvedStageLeg> prefix,
            LinkedHashSet<String> usedVariantIds,
            List<CompletedPath> completed,
            int depth
    ) {
        /*
         * A registered primary StageEdge has a real shell at its logical end,
         * so nearestShellAhead() normally stops there. This fallback is only
         * for primary technical pieces that have no registered shell edge.
         */
        int nextIndex =
                cursor.direction > 0
                        ? cursor.primaryTrackIndex + 1
                        : cursor.primaryTrackIndex - 1;

        if (nextIndex < 0
                || nextIndex
                >= state.route.tracks.size()) {

            return;
        }

        RouteTrack next =
                state.route.tracks.get(
                        nextIndex
                );

        if (next.points.size() < 2) {
            return;
        }

        LatLng currentEnd =
                cursor.direction > 0
                        ? cursor.track.points.get(
                        cursor.track.points.size() - 1
                )
                        : cursor.track.points.get(
                        0
                );

        PrimaryStageInfo nextStage =
                state.primaryStages.get(
                        next
                );

        int nextDirection =
                nextStage == null
                        ? cursor.direction
                        : nextStage.direction;

        TrackProjection nextStart =
                nextDirection > 0
                        ? endpointProjection(
                        next,
                        true
                )
                        : endpointProjection(
                        next,
                        false
                );

        if (nextStart == null
                || GeoMath.distanceMeters(
                currentEnd,
                nextStart.point
        ) > JOIN_TOLERANCE_M) {

            return;
        }

        walk(
                state,
                Cursor.primary(
                        next,
                        nextIndex,
                        nextStart,
                        nextDirection
                ),
                prefix,
                usedVariantIds,
                completed,
                depth + 1
        );
    }


    private void continueAfterVariantBoundary(
            RouteState state,
            Cursor cursor,
            List<CaminoResolvedStageLeg> prefix,
            LinkedHashSet<String> usedVariantIds,
            List<CompletedPath> completed,
            int depth
    ) {
        VariantOwner owner =
                state.variantOwnerByTrack.get(
                        cursor.track
                );

        if (owner == null) {
            return;
        }

        CaminoVariantPath path =
                owner.path;

        int nextPartIndex =
                owner.partIndex + 1;

        if (nextPartIndex
                < path.parts.size()) {

            CaminoVariantPathPart currentPart =
                    path.parts.get(
                            owner.partIndex
                    );

            CaminoVariantPathPart nextPart =
                    path.parts.get(
                            nextPartIndex
                    );

            /*
             * v53 already established the source-truth semantic chain.
             * For selectable routing we additionally require the two actual
             * GPS geometries to touch within the strict snap tolerance.
             */
            if (GeoMath.distanceMeters(
                    currentPart.endPoint(),
                    nextPart.startPoint()
            ) > JOIN_TOLERANCE_M) {

                return;
            }

            Cursor next =
                    variantPartStartCursor(
                            state,
                            path,
                            nextPartIndex
                    );

            walk(
                    state,
                    next,
                    prefix,
                    usedVariantIds,
                    completed,
                    depth + 1
            );

            return;
        }

        PathAttachment attachment =
                state.attachments.get(
                        path
                );

        if (attachment == null
                || !attachment.valid()) {

            return;
        }

        TrackProjection target =
                attachment.endTarget;

        int primaryIndex =
                state.primaryIndex(
                        target.track
                );

        if (primaryIndex >= 0) {
            PrimaryStageInfo targetStage =
                    state.primaryStages.get(
                            target.track
                    );

            int direction =
                    targetStage == null
                            ? 1
                            : targetStage.direction;

            walk(
                    state,
                    Cursor.primary(
                            target.track,
                            primaryIndex,
                            target,
                            direction
                    ),
                    prefix,
                    usedVariantIds,
                    completed,
                    depth + 1
            );

            return;
        }

        VariantOwner targetOwner =
                state.variantOwnerByTrack.get(
                        target.track
                );

        if (targetOwner == null
                || usedVariantIds.contains(
                targetOwner.path.id
        )) {

            return;
        }

        LinkedHashSet<String> continuedUsed =
                new LinkedHashSet<>(
                        usedVariantIds
                );

        /*
         * This is not an invented branch choice: the first variant physically
         * ended on an already-existing second variant corridor, so following
         * that corridor is the only proven continuation.
         */
        continuedUsed.add(
                targetOwner.path.id
        );

        int direction =
                targetOwner.part.reversed
                        ? -1
                        : 1;

        walk(
                state,
                Cursor.variant(
                        target.track,
                        target,
                        direction
                ),
                prefix,
                continuedUsed,
                completed,
                depth + 1
        );
    }


    private Cursor variantPartStartCursor(
            RouteState state,
            CaminoVariantPath path,
            int partIndex
    ) {
        if (path == null
                || partIndex < 0
                || partIndex
                >= path.parts.size()) {

            return null;
        }

        CaminoVariantPathPart part =
                path.parts.get(
                        partIndex
                );

        if (!state.variantOwnerByTrack.containsKey(
                part.track
        )) {

            return null;
        }

        boolean firstEndpoint =
                !part.reversed;

        TrackProjection start =
                endpointProjection(
                        part.track,
                        firstEndpoint
                );

        if (start == null) {
            return null;
        }

        return Cursor.variant(
                part.track,
                start,
                part.reversed
                        ? -1
                        : 1
        );
    }


    private List<BranchEvent> branchesAhead(
            RouteState state,
            Cursor cursor,
            double terminalChainage
    ) {
        List<BranchEvent> all =
                state.branchStartsByTrack.get(
                        cursor.track
                );

        if (all == null
                || all.isEmpty()) {

            return Collections.emptyList();
        }

        double available =
                cursor.direction > 0
                        ? terminalChainage
                        - cursor.position.chainageM
                        : cursor.position.chainageM
                        - terminalChainage;

        if (available < -EVENT_EPSILON_M) {
            return Collections.emptyList();
        }

        List<BranchEvent> result =
                new ArrayList<>();

        for (BranchEvent event
                : all) {

            double ahead =
                    cursor.direction > 0
                            ? event.target.chainageM
                            - cursor.position.chainageM
                            : cursor.position.chainageM
                            - event.target.chainageM;

            if (ahead < -EVENT_EPSILON_M
                    || ahead
                    > available + EVENT_EPSILON_M) {

                continue;
            }

            result.add(
                    event
            );
        }

        result.sort(
                Comparator.comparingDouble(
                        event ->
                                Math.abs(
                                        event.target.chainageM
                                                - cursor.position.chainageM
                                )
                )
        );

        return result;
    }


    private ShellEvent nearestShellAhead(
            RouteState state,
            Cursor cursor
    ) {
        ShellEvent best =
                null;

        double bestAhead =
                Double.POSITIVE_INFINITY;

        PrimaryStageInfo primaryStage =
                state.primaryStages.get(
                        cursor.track
                );

        if (primaryStage != null
                && cursor.primaryTrackIndex >= 0
                && cursor.direction
                == primaryStage.direction) {

            double ahead =
                    cursor.direction > 0
                            ? primaryStage.end.chainageM
                            - cursor.position.chainageM
                            : cursor.position.chainageM
                            - primaryStage.end.chainageM;

            if (ahead >= -EVENT_EPSILON_M) {
                best =
                        new ShellEvent(
                                primaryStage.destinationPlaceKey,
                                primaryStage.end,
                                primaryStage.end.toHit(
                                        primaryStage.primaryTrackIndex
                                )
                        );

                bestAhead =
                        Math.max(
                                0.0,
                                ahead
                        );
            }
        }

        /*
         * A variant can physically pass through a primary shell before its KML
         * file ends. That is still a stage boundary and must stop traversal.
         */
        List<ShellEvent> events =
                state.shellsOnTrack.get(
                        cursor.track
                );

        if (events != null) {
            for (ShellEvent event
                    : events) {

                double ahead =
                        cursor.direction > 0
                                ? event.trackProjection.chainageM
                                - cursor.position.chainageM
                                : cursor.position.chainageM
                                - event.trackProjection.chainageM;

                /*
                 * Ignore a shell exactly under the current variant cursor.
                 * That is commonly the shell we just departed from when a
                 * variant starts directly at a stage point.
                 */
                if (ahead <= EVENT_EPSILON_M
                        || ahead >= bestAhead) {

                    continue;
                }

                best =
                        event;

                bestAhead =
                        Math.max(
                                0.0,
                                ahead
                        );
            }
        }

        return best;
    }


    private List<CaminoResolvedStageLeg> copyWithSlice(
            List<CaminoResolvedStageLeg> prefix,
            Cursor cursor,
            TrackProjection to
    ) {
        return copyWithSliceToChainage(
                prefix,
                cursor,
                to.chainageM
        );
    }


    private List<CaminoResolvedStageLeg> copyWithSliceToChainage(
            List<CaminoResolvedStageLeg> prefix,
            Cursor cursor,
            double toChainageM
    ) {
        List<CaminoResolvedStageLeg> result =
                new ArrayList<>(
                        prefix
                );

        TrackProjection to =
                projectionAtChainage(
                        cursor.track,
                        toChainageM
                );

        if (to == null
                || Math.abs(
                to.chainageM
                        - cursor.position.chainageM
        ) < 0.05) {

            return result;
        }

        result.add(
                new CaminoResolvedStageLeg(
                        cursor.track,
                        cursor.position.toHit(
                                cursor.primaryTrackIndex
                        ),
                        to.toHit(
                                cursor.primaryTrackIndex
                        )
                )
        );

        return result;
    }


    private String signature(
            CaminoRoute route,
            String startPlaceKey,
            CompletedPath item
    ) {
        StringBuilder result =
                new StringBuilder();

        result.append(
                route.id
        );

        result.append(
                ":"
        );

        result.append(
                startPlaceKey == null
                        ? "?"
                        : startPlaceKey
        );

        result.append(
                "->"
        );

        result.append(
                item.destinationPlaceKey
        );

        result.append(
                "|"
        );

        boolean first =
                true;

        for (String id
                : item.usedVariantIds) {

            if (!first) {
                result.append(
                        "+"
                );
            }

            result.append(
                    id
            );

            first =
                    false;
        }

        return result.toString();
    }


    private static boolean meaningful(
            String value
    ) {
        return value != null
                && !value.isEmpty();
    }


    private static TrackProjection endpointProjection(
            RouteTrack track,
            boolean first
    ) {
        if (track == null
                || track.points.size() < 2) {

            return null;
        }

        if (first) {
            return new TrackProjection(
                    track,
                    track.points.get(
                            0
                    ),
                    0.0,
                    0.0,
                    0,
                    0.0
            );
        }

        return new TrackProjection(
                track,
                track.points.get(
                        track.points.size() - 1
                ),
                0.0,
                trackLength(
                        track
                ),
                track.points.size() - 2,
                1.0
        );
    }


    private static TrackProjection project(
            RouteTrack track,
            LatLng query
    ) {
        if (track == null
                || query == null
                || track.points.size() < 2) {

            return null;
        }

        TrackProjection best =
                null;

        double chainageAtA =
                0.0;

        for (int segmentIndex = 0;
                segmentIndex
                < track.points.size() - 1;
                segmentIndex++) {

            LatLng a =
                    track.points.get(
                            segmentIndex
                    );

            LatLng b =
                    track.points.get(
                            segmentIndex + 1
                    );

            SegmentProjection segment =
                    projectToSegment(
                            query,
                            a,
                            b
                    );

            if (best == null
                    || segment.distanceM
                    < best.distanceM) {

                double segmentLength =
                        GeoMath.distanceMeters(
                                a,
                                b
                        );

                best =
                        new TrackProjection(
                                track,
                                segment.point,
                                segment.distanceM,
                                chainageAtA
                                        + segment.t
                                        * segmentLength,
                                segmentIndex,
                                segment.t
                        );
            }

            chainageAtA +=
                    GeoMath.distanceMeters(
                            a,
                            b
                    );
        }

        return best;
    }


    private static TrackProjection projectionAtChainage(
            RouteTrack track,
            double requestedChainageM
    ) {
        if (track == null
                || track.points.size() < 2) {

            return null;
        }

        double total =
                trackLength(
                        track
                );

        double wanted =
                Math.max(
                        0.0,
                        Math.min(
                                total,
                                requestedChainageM
                        )
                );

        double chainage =
                0.0;

        for (int segmentIndex = 0;
                segmentIndex
                < track.points.size() - 1;
                segmentIndex++) {

            LatLng a =
                    track.points.get(
                            segmentIndex
                    );

            LatLng b =
                    track.points.get(
                            segmentIndex + 1
                    );

            double segmentLength =
                    GeoMath.distanceMeters(
                            a,
                            b
                    );

            if (chainage + segmentLength
                    >= wanted
                    || segmentIndex
                    == track.points.size() - 2) {

                double t =
                        segmentLength <= 1e-9
                                ? 0.0
                                : (
                                wanted - chainage
                        ) / segmentLength;

                t =
                        Math.max(
                                0.0,
                                Math.min(
                                        1.0,
                                        t
                                )
                        );

                return new TrackProjection(
                        track,
                        interpolate(
                                a,
                                b,
                                t
                        ),
                        0.0,
                        wanted,
                        segmentIndex,
                        t
                );
            }

            chainage +=
                    segmentLength;
        }

        return endpointProjection(
                track,
                false
        );
    }


    private static SegmentProjection projectToSegment(
            LatLng query,
            LatLng a,
            LatLng b
    ) {
        double refLatRad =
                Math.toRadians(
                        (
                                query.getLatitude()
                                        + a.getLatitude()
                                        + b.getLatitude()
                        ) / 3.0
                );

        double cosLat =
                Math.max(
                        0.20,
                        Math.cos(
                                refLatRad
                        )
                );

        double ax =
                Math.toRadians(
                        a.getLongitude()
                                - query.getLongitude()
                )
                        * GeoMath.EARTH_RADIUS_M
                        * cosLat;

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
                        * cosLat;

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

        double lengthSq =
                vx * vx
                        + vy * vy;

        double t =
                0.0;

        if (lengthSq > 1e-9) {
            t =
                    -(ax * vx
                            + ay * vy)
                            / lengthSq;

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

        return new SegmentProjection(
                interpolate(
                        a,
                        b,
                        t
                ),
                Math.hypot(
                        px,
                        py
                ),
                t
        );
    }


    private static LatLng interpolate(
            LatLng a,
            LatLng b,
            double t
    ) {
        return new LatLng(
                a.getLatitude()
                        + t
                        * (
                        b.getLatitude()
                                - a.getLatitude()
                ),
                a.getLongitude()
                        + t
                        * (
                        b.getLongitude()
                                - a.getLongitude()
                )
        );
    }


    private static double trackLength(
            RouteTrack track
    ) {
        double result =
                0.0;

        for (int index = 0;
                index < track.points.size() - 1;
                index++) {

            result +=
                    GeoMath.distanceMeters(
                            track.points.get(
                                    index
                            ),
                            track.points.get(
                                    index + 1
                            )
                    );
        }

        return result;
    }


    private static final class RouteState {

        final CaminoRoute route;

        final Map<RouteTrack, Integer> primaryIndexByTrack =
                new IdentityHashMap<>();

        final Map<RouteTrack, PrimaryStageInfo> primaryStages =
                new IdentityHashMap<>();

        final Map<RouteTrack, VariantOwner> variantOwnerByTrack =
                new IdentityHashMap<>();

        final Map<CaminoVariantPath, PathAttachment> attachments =
                new IdentityHashMap<>();

        final Map<RouteTrack, List<BranchEvent>> branchStartsByTrack =
                new IdentityHashMap<>();

        final Map<RouteTrack, List<ShellEvent>> shellsOnTrack =
                new IdentityHashMap<>();


        RouteState(
                CaminoRoute route
        ) {
            this.route =
                    route;
        }


        void indexTracks() {
            for (int index = 0;
                    index < route.tracks.size();
                    index++) {

                primaryIndexByTrack.put(
                        route.tracks.get(
                                index
                        ),
                        index
                );
            }

            for (CaminoVariantPath path
                    : route.variantPaths) {

                for (int partIndex = 0;
                        partIndex < path.parts.size();
                        partIndex++) {

                    CaminoVariantPathPart part =
                            path.parts.get(
                                    partIndex
                            );

                    variantOwnerByTrack.put(
                            part.track,
                            new VariantOwner(
                                    path,
                                    part,
                                    partIndex
                            )
                    );
                }
            }
        }


        void registerPrimaryStage(
                RouteTrack track,
                int primaryTrackIndex,
                String startPlaceKey,
                String destinationPlaceKey,
                LatLng shellPoint
        ) {
            if (track == null
                    || shellPoint == null
                    || track.points.size() < 2) {

                return;
            }

            TrackProjection first =
                    endpointProjection(
                            track,
                            true
                    );

            TrackProjection last =
                    endpointProjection(
                            track,
                            false
                    );

            if (first == null
                    || last == null) {

                return;
            }

            boolean startIsFirst =
                    GeoMath.distanceMeters(
                            shellPoint,
                            first.point
                    )
                            <= GeoMath.distanceMeters(
                            shellPoint,
                            last.point
                    );

            TrackProjection start =
                    startIsFirst
                            ? first
                            : last;

            TrackProjection end =
                    startIsFirst
                            ? last
                            : first;

            primaryStages.put(
                    track,
                    new PrimaryStageInfo(
                            track,
                            primaryTrackIndex,
                            startPlaceKey,
                            destinationPlaceKey,
                            start,
                            end,
                            startIsFirst
                                    ? 1
                                    : -1
                    )
            );
        }


        void finishBuild() {
            for (CaminoVariantPath path
                    : route.variantPaths) {

                PathAttachment attachment =
                        buildAttachment(
                                path
                        );

                attachments.put(
                        path,
                        attachment
                );

                if (attachment.valid()) {
                    branchStartsByTrack
                            .computeIfAbsent(
                                    attachment.startTarget.track,
                                    ignored ->
                                            new ArrayList<>()
                            )
                            .add(
                                    new BranchEvent(
                                            attachment,
                                            attachment.startTarget
                                    )
                            );
                }
            }

            for (List<BranchEvent> events
                    : branchStartsByTrack.values()) {

                events.sort(
                        Comparator.comparingDouble(
                                event ->
                                        event.target.chainageM
                        )
                );
            }

            buildShellEventsOnVariants();
        }


        private PathAttachment buildAttachment(
                CaminoVariantPath path
        ) {
            TrackProjection start =
                    nearestOtherTrack(
                            path.startPoint(),
                            path
                    );

            TrackProjection end =
                    nearestOtherTrack(
                            path.endPoint(),
                            path
                    );

            return new PathAttachment(
                    path,
                    start,
                    end
            );
        }


        private TrackProjection nearestOtherTrack(
                LatLng point,
                CaminoVariantPath ownPath
        ) {
            Set<RouteTrack> excluded =
                    Collections.newSetFromMap(
                            new IdentityHashMap<>()
                    );

            for (CaminoVariantPathPart part
                    : ownPath.parts) {

                excluded.add(
                        part.track
                );
            }

            TrackProjection best =
                    null;

            for (RouteTrack candidate
                    : route.renderTracks) {

                if (excluded.contains(
                        candidate
                )) {
                    continue;
                }

                double lowerBound =
                        Math.max(
                                0.0,
                                GeoMath.distanceMeters(
                                        point,
                                        candidate.boundsCenter
                                )
                                        - candidate.boundsRadiusM
                                        - 50.0
                        );

                if (best != null
                        && lowerBound
                        > best.distanceM) {

                    continue;
                }

                TrackProjection hit =
                        project(
                                candidate,
                                point
                        );

                if (hit != null
                        && (
                        best == null
                                || hit.distanceM
                                < best.distanceM
                )) {

                    best =
                            hit;
                }
            }

            return best;
        }


        private void buildShellEventsOnVariants() {
            List<ShellAnchor> anchors =
                    new ArrayList<>();

            Set<String> seen =
                    new LinkedHashSet<>();

            for (PrimaryStageInfo stage
                    : primaryStages.values()) {

                addShellAnchor(
                        anchors,
                        seen,
                        stage.startPlaceKey,
                        stage.start,
                        stage.primaryTrackIndex
                );

                addShellAnchor(
                        anchors,
                        seen,
                        stage.destinationPlaceKey,
                        stage.end,
                        stage.primaryTrackIndex
                );
            }

            for (RouteTrack variantTrack
                    : variantOwnerByTrack.keySet()) {

                List<ShellEvent> events =
                        new ArrayList<>();

                for (ShellAnchor anchor
                        : anchors) {

                    double lowerBound =
                            Math.max(
                                    0.0,
                                    GeoMath.distanceMeters(
                                            anchor.point,
                                            variantTrack.boundsCenter
                                    )
                                            - variantTrack.boundsRadiusM
                                            - 25.0
                            );

                    if (lowerBound
                            > SHELL_ON_VARIANT_TOLERANCE_M) {

                        continue;
                    }

                    TrackProjection hit =
                            project(
                                    variantTrack,
                                    anchor.point
                            );

                    if (hit == null
                            || hit.distanceM
                            > SHELL_ON_VARIANT_TOLERANCE_M) {

                        continue;
                    }

                    events.add(
                            new ShellEvent(
                                    anchor.placeKey,
                                    hit,
                                    anchor.shellHit
                            )
                    );
                }

                events.sort(
                        Comparator.comparingDouble(
                                event ->
                                        event.trackProjection.chainageM
                        )
                );

                if (!events.isEmpty()) {
                    shellsOnTrack.put(
                            variantTrack,
                            events
                    );
                }
            }
        }


        private void addShellAnchor(
                List<ShellAnchor> anchors,
                Set<String> seen,
                String placeKey,
                TrackProjection projection,
                int primaryTrackIndex
        ) {
            if (!meaningful(
                    placeKey
            )
                    || projection == null) {

                return;
            }

            String signature =
                    placeKey
                            + "@"
                            + Math.round(
                            projection.point.getLatitude()
                                    * 100000.0
                    )
                            + ":"
                            + Math.round(
                            projection.point.getLongitude()
                                    * 100000.0
                    );

            if (!seen.add(
                    signature
            )) {
                return;
            }

            anchors.add(
                    new ShellAnchor(
                            placeKey,
                            projection.point,
                            projection.toHit(
                                    primaryTrackIndex
                            )
                    )
            );
        }


        int primaryIndex(
                RouteTrack track
        ) {
            Integer value =
                    primaryIndexByTrack.get(
                            track
                    );

            return value == null
                    ? -1
                    : value;
        }
    }


    private static final class PrimaryStageInfo {

        final RouteTrack track;
        final int primaryTrackIndex;
        final String startPlaceKey;
        final String destinationPlaceKey;
        final TrackProjection start;
        final TrackProjection end;
        final int direction;


        PrimaryStageInfo(
                RouteTrack track,
                int primaryTrackIndex,
                String startPlaceKey,
                String destinationPlaceKey,
                TrackProjection start,
                TrackProjection end,
                int direction
        ) {
            this.track =
                    track;

            this.primaryTrackIndex =
                    primaryTrackIndex;

            this.startPlaceKey =
                    startPlaceKey;

            this.destinationPlaceKey =
                    destinationPlaceKey;

            this.start =
                    start;

            this.end =
                    end;

            this.direction =
                    direction;
        }
    }


    private static final class Cursor {

        final RouteTrack track;
        final int primaryTrackIndex;
        final TrackProjection position;
        final int direction;
        final double trackLengthM;


        private Cursor(
                RouteTrack track,
                int primaryTrackIndex,
                TrackProjection position,
                int direction
        ) {
            this.track =
                    track;

            this.primaryTrackIndex =
                    primaryTrackIndex;

            this.position =
                    position;

            this.direction =
                    direction;

            this.trackLengthM =
                    trackLength(
                            track
                    );
        }


        static Cursor primary(
                RouteTrack track,
                int primaryTrackIndex,
                TrackProjection position,
                int direction
        ) {
            return new Cursor(
                    track,
                    primaryTrackIndex,
                    position,
                    direction
            );
        }


        static Cursor variant(
                RouteTrack track,
                TrackProjection position,
                int direction
        ) {
            return new Cursor(
                    track,
                    -1,
                    position,
                    direction
            );
        }
    }


    private static final class TrackProjection {

        final RouteTrack track;
        final LatLng point;
        final double distanceM;
        final double chainageM;
        final int segmentIndex;
        final double t;


        TrackProjection(
                RouteTrack track,
                LatLng point,
                double distanceM,
                double chainageM,
                int segmentIndex,
                double t
        ) {
            this.track =
                    track;

            this.point =
                    point;

            this.distanceM =
                    distanceM;

            this.chainageM =
                    chainageM;

            this.segmentIndex =
                    segmentIndex;

            this.t =
                    t;
        }


        ProjectionHit toHit(
                int primaryTrackIndex
        ) {
            double absoluteChainage =
                    primaryTrackIndex >= 0
                            ? track.baseChainageM
                            + chainageM
                            : chainageM;

            return new ProjectionHit(
                    point,
                    absoluteChainage,
                    distanceM,
                    primaryTrackIndex,
                    segmentIndex,
                    t
            );
        }
    }


    private static final class SegmentProjection {

        final LatLng point;
        final double distanceM;
        final double t;


        SegmentProjection(
                LatLng point,
                double distanceM,
                double t
        ) {
            this.point =
                    point;

            this.distanceM =
                    distanceM;

            this.t =
                    t;
        }
    }


    private static final class VariantOwner {

        final CaminoVariantPath path;
        final CaminoVariantPathPart part;
        final int partIndex;


        VariantOwner(
                CaminoVariantPath path,
                CaminoVariantPathPart part,
                int partIndex
        ) {
            this.path =
                    path;

            this.part =
                    part;

            this.partIndex =
                    partIndex;
        }
    }


    private static final class PathAttachment {

        final CaminoVariantPath path;
        final TrackProjection startTarget;
        final TrackProjection endTarget;


        PathAttachment(
                CaminoVariantPath path,
                TrackProjection startTarget,
                TrackProjection endTarget
        ) {
            this.path =
                    path;

            this.startTarget =
                    startTarget;

            this.endTarget =
                    endTarget;
        }


        boolean valid() {
            return startTarget != null
                    && endTarget != null
                    && startTarget.distanceM
                    <= JOIN_TOLERANCE_M
                    && endTarget.distanceM
                    <= JOIN_TOLERANCE_M;
        }
    }


    private static final class BranchEvent {

        final PathAttachment attachment;
        final TrackProjection target;


        BranchEvent(
                PathAttachment attachment,
                TrackProjection target
        ) {
            this.attachment =
                    attachment;

            this.target =
                    target;
        }
    }


    private static final class ShellAnchor {

        final String placeKey;
        final LatLng point;
        final ProjectionHit shellHit;


        ShellAnchor(
                String placeKey,
                LatLng point,
                ProjectionHit shellHit
        ) {
            this.placeKey =
                    placeKey;

            this.point =
                    point;

            this.shellHit =
                    shellHit;
        }
    }


    private static final class ShellEvent {

        final String placeKey;
        final TrackProjection trackProjection;
        final ProjectionHit shellHit;


        ShellEvent(
                String placeKey,
                TrackProjection trackProjection,
                ProjectionHit shellHit
        ) {
            this.placeKey =
                    placeKey;

            this.trackProjection =
                    trackProjection;

            this.shellHit =
                    shellHit;
        }
    }


    private static final class CompletedPath {

        final List<CaminoResolvedStageLeg> legs;
        final String destinationPlaceKey;
        final ProjectionHit shellHit;
        final LinkedHashSet<String> usedVariantIds;


        CompletedPath(
                List<CaminoResolvedStageLeg> legs,
                String destinationPlaceKey,
                ProjectionHit shellHit,
                Set<String> usedVariantIds
        ) {
            this.legs =
                    Collections.unmodifiableList(
                            new ArrayList<>(
                                    legs
                            )
                    );

            this.destinationPlaceKey =
                    destinationPlaceKey;

            this.shellHit =
                    shellHit;

            this.usedVariantIds =
                    new LinkedHashSet<>(
                            usedVariantIds
                    );
        }
    }
}


final class CaminoResolvedStagePath {

    final String id;
    final CaminoRoute route;
    final String startPlaceKey;
    final String destinationPlaceKey;
    final ProjectionHit startHit;
    final ProjectionHit endHit;
    final List<CaminoResolvedStageLeg> legs;
    final List<String> variantPathIds;


    CaminoResolvedStagePath(
            String id,
            CaminoRoute route,
            String startPlaceKey,
            String destinationPlaceKey,
            ProjectionHit startHit,
            ProjectionHit endHit,
            List<CaminoResolvedStageLeg> legs,
            Set<String> variantPathIds
    ) {
        this.id =
                id;

        this.route =
                route;

        this.startPlaceKey =
                startPlaceKey;

        this.destinationPlaceKey =
                destinationPlaceKey;

        this.startHit =
                startHit;

        this.endHit =
                endHit;

        this.legs =
                Collections.unmodifiableList(
                        new ArrayList<>(
                                legs
                        )
                );

        this.variantPathIds =
                Collections.unmodifiableList(
                        new ArrayList<>(
                                variantPathIds
                        )
                );
    }
}


final class CaminoResolvedStageLeg {

    final RouteTrack track;
    final ProjectionHit from;
    final ProjectionHit to;


    CaminoResolvedStageLeg(
            RouteTrack track,
            ProjectionHit from,
            ProjectionHit to
    ) {
        this.track =
                track;

        this.from =
                from;

        this.to =
                to;
    }
}
