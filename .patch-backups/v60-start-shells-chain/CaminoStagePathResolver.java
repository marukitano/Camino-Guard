package com.marukitano.caminoguard;

import org.maplibre.android.geometry.LatLng;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;


/**
 * Resolves the interactive day-stage graph.
 *
 * One selectable section ALWAYS ends at the next shell.
 *
 * A shell exists at:
 * - every established primary stage endpoint, and
 * - every proven physical fork where an official alternative starts.
 *
 * A pure merge does not create a shell. After a merge traversal simply keeps
 * following the physically attached official track until the next shell.
 *
 * Physical automatic joins are deliberately strict: <= 25 m, same Camino
 * route group, and only between already-existing official GPS geometries.
 */
final class CaminoStagePathResolver {

    private static final double JOIN_TOLERANCE_M =
            25.0;

    private static final double SHELL_ON_VARIANT_TOLERANCE_M =
            10.0;

    private static final double EXISTING_SHELL_REUSE_M =
            10.0;

    private static final double EVENT_EPSILON_M =
            0.25;

    private static final int MAX_TRACE_DEPTH =
            32;


    private final Map<CaminoRoute, RouteState> states =
            new IdentityHashMap<>();

    private final Map<String, List<DecisionPoint>> decisionsByKey =
            new LinkedHashMap<>();

    private final List<DecisionPoint> allDecisions =
            new ArrayList<>();

    private final Map<PathAttachment, DecisionPoint> decisionByAttachment =
            new IdentityHashMap<>();


    void rebuild(
            List<CaminoRoute> routes,
            CaminoStageTopology topology
    ) {
        states.clear();
        decisionsByKey.clear();
        allDecisions.clear();
        decisionByAttachment.clear();

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
         * Learn the physical forward direction of every already-established
         * primary stage from StageTopology. CaminoRepository can reverse a
         * primary geometry without swapping fromKey/toKey.
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

        /*
         * Only after all strict physical variant attachments are known do we
         * create additional shells at actual forks.
         */
        if (topology != null) {
            installDecisionShells(
                    topology
            );
        }
    }


    /**
     * Choices from an established primary shell.
     *
     * If a fork is further down the primary stage, this returns ONE path only:
     * shell -> fork shell. The alternatives are deliberately not exposed until
     * the user taps that fork shell.
     *
     * If the established shell itself is already a fork, all choices from that
     * fork are returned immediately.
     */
    boolean isDecisionShell(
            String placeKey,
            LatLng point
    ) {
        if (!meaningful(
                placeKey
        )
                || point == null) {

            return false;
        }

        List<DecisionPoint> candidates =
                decisionsByKey.get(
                        placeKey
                );

        if (candidates == null
                || candidates.isEmpty()) {

            return false;
        }

        for (DecisionPoint candidate
                : candidates) {

            if (GeoMath.distanceMeters(
                    point,
                    candidate.point
            ) <= JOIN_TOLERANCE_M * 2.0) {

                return true;
            }
        }

        return false;
    }


    CaminoResolvedStagePath append(
            CaminoResolvedStagePath first,
            CaminoResolvedStagePath second
    ) {
        if (first == null
                || second == null
                || first.route == null
                || first.route != second.route
                || first.endHit == null
                || second.startHit == null
                || first.legs.isEmpty()
                || second.legs.isEmpty()) {

            return null;
        }

        if (GeoMath.distanceMeters(
                first.endHit.point,
                second.startHit.point
        ) > JOIN_TOLERANCE_M * 2.0) {

            return null;
        }

        List<CaminoResolvedStageLeg> legs =
                new ArrayList<>(
                        first.legs
                );

        legs.addAll(
                second.legs
        );

        LinkedHashSet<String> variants =
                new LinkedHashSet<>(
                        first.variantPathIds
                );

        variants.addAll(
                second.variantPathIds
        );

        return new CaminoResolvedStagePath(
                first.id
                        + "++"
                        + second.id,
                first.route,
                first.startPlaceKey,
                second.destinationPlaceKey,
                first.startHit,
                second.endHit,
                legs,
                variants
        );
    }


    List<CaminoResolvedStagePath> findChoices(
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

        DecisionPoint atStart =
                decisionAt(
                        state,
                        primary,
                        stage.start,
                        startPlaceKey
                );

        if (atStart != null) {
            return choicesFromDecision(
                    atStart,
                    primary
            );
        }

        Cursor start =
                Cursor.primary(
                        primary,
                        primaryTrackIndex,
                        stage.start,
                        stage.direction
                );

        CompletedPath completed =
                traceUntilNextShell(
                        state,
                        start,
                        new ArrayList<>(),
                        new LinkedHashSet<>(),
                        null,
                        0
                );

        if (completed == null) {
            return Collections.emptyList();
        }

        CaminoResolvedStagePath path =
                resolved(
                        route,
                        startPlaceKey,
                        stage.start.toHit(
                                primaryTrackIndex
                        ),
                        completed
                );

        if (path == null) {
            return Collections.emptyList();
        }

        return Collections.singletonList(
                path
        );
    }


    /**
     * Choices from a synthetic fork shell. This is used when the shell has no
     * primary StageTopology outgoing edge because it lies in the middle of an
     * official track.
     */
    List<CaminoResolvedStagePath> findDecisionChoices(
            String placeKey,
            LatLng point
    ) {
        List<DecisionPoint> candidates =
                decisionsByKey.get(
                        placeKey
                );

        if (candidates == null
                || candidates.isEmpty()
                || point == null) {

            return Collections.emptyList();
        }

        DecisionPoint best =
                null;

        double bestDistanceM =
                Double.POSITIVE_INFINITY;

        for (DecisionPoint candidate
                : candidates) {

            double distanceM =
                    GeoMath.distanceMeters(
                            point,
                            candidate.point
                    );

            if (distanceM
                    < bestDistanceM) {

                best =
                        candidate;

                bestDistanceM =
                        distanceM;
            }
        }

        if (best == null
                || bestDistanceM
                > JOIN_TOLERANCE_M * 2.0) {

            return Collections.emptyList();
        }

        return choicesFromDecision(
                best,
                null
        );
    }


    private List<CaminoResolvedStagePath> choicesFromDecision(
            DecisionPoint decision,
            RouteTrack preferredStraightTrack
    ) {
        List<CaminoResolvedStagePath> result =
                new ArrayList<>();

        Set<String> seen =
                new LinkedHashSet<>();

        /*
         * Straight continuation(s). Normally there is exactly one. At an
         * existing primary shell shared by multiple Camino edges the caller
         * passes preferredStraightTrack so each topology edge contributes only
         * its own corridor.
         */
        Set<RouteTrack> straightTracks =
                Collections.newSetFromMap(
                        new IdentityHashMap<>()
                );

        for (BranchEvent event
                : decision.events) {

            if (preferredStraightTrack != null
                    && event.target.track
                    != preferredStraightTrack) {

                continue;
            }

            if (!straightTracks.add(
                    event.target.track
            )) {
                continue;
            }

            Cursor straight =
                    cursorAtAttachment(
                            decision.state,
                            event.target
                    );

            if (straight == null) {
                continue;
            }

            CompletedPath completed =
                    traceUntilNextShell(
                            decision.state,
                            straight,
                            new ArrayList<>(),
                            new LinkedHashSet<>(),
                            decision.placeKey,
                            0
                    );

            CaminoResolvedStagePath path =
                    resolved(
                            decision.state.route,
                            decision.placeKey,
                            event.target.toHit(
                                    straight.primaryTrackIndex
                            ),
                            completed
                    );

            if (path != null
                    && seen.add(
                    path.id
            )) {

                result.add(
                        path
                );
            }
        }

        /*
         * Alternative continuations. The branch choice starts here; no piece
         * before this fork is included in the highlighted selection.
         */
        for (BranchEvent event
                : decision.events) {

            if (preferredStraightTrack != null
                    && event.target.track
                    != preferredStraightTrack) {

                continue;
            }

            CaminoVariantPath variant =
                    event.attachment.path;

            Cursor variantStart =
                    variantPartStartCursor(
                            decision.state,
                            variant,
                            0
                    );

            if (variantStart == null) {
                continue;
            }

            LinkedHashSet<String> used =
                    new LinkedHashSet<>();

            used.add(
                    variant.id
            );

            CompletedPath completed =
                    traceUntilNextShell(
                            decision.state,
                            variantStart,
                            new ArrayList<>(),
                            used,
                            decision.placeKey,
                            0
                    );

            CaminoResolvedStagePath path =
                    resolved(
                            decision.state.route,
                            decision.placeKey,
                            event.target.toHit(
                                    decision.state.primaryIndex(
                                            event.target.track
                                    )
                            ),
                            completed
                    );

            if (path != null
                    && seen.add(
                    path.id
            )) {

                result.add(
                        path
                );
            }
        }

        return result;
    }


    /**
     * Linear traversal only. Forks are STOP events, not recursive choices.
     * Merges remain transparent and traversal continues through them.
     */
    private CompletedPath traceUntilNextShell(
            RouteState state,
            Cursor cursor,
            List<CaminoResolvedStageLeg> prefix,
            LinkedHashSet<String> usedVariantIds,
            String ignoredDecisionKey,
            int depth
    ) {
        if (cursor == null
                || cursor.track == null
                || depth > MAX_TRACE_DEPTH) {

            return null;
        }

        double physicalEndChainage =
                cursor.direction > 0
                        ? cursor.trackLengthM
                        : 0.0;

        ShellEvent stageShell =
                nearestEstablishedShellAhead(
                        state,
                        cursor,
                        ignoredDecisionKey
                );

        DecisionHit decision =
                nearestDecisionAhead(
                        state,
                        cursor,
                        ignoredDecisionKey
                );

        double stageAhead =
                stageShell == null
                        ? Double.POSITIVE_INFINITY
                        : forwardDistance(
                                cursor,
                                stageShell.trackProjection.chainageM
                        );

        double decisionAhead =
                decision == null
                        ? Double.POSITIVE_INFINITY
                        : decision.aheadM;

        /*
         * A fork shell wins when it lies before the established stage shell.
         * If both are the same physical shell, both keys are already unified
         * by installDecisionShells(), so either result means the same stop.
         */
        if (decision != null
                && decisionAhead
                <= stageAhead + EVENT_EPSILON_M) {

            List<CaminoResolvedStageLeg> legs =
                    copyWithSlice(
                            prefix,
                            cursor,
                            decision.projection
                    );

            ProjectionHit endHit =
                    decision.projection.toHit(
                            cursor.primaryTrackIndex
                    );

            return new CompletedPath(
                    legs,
                    decision.decision.placeKey,
                    endHit,
                    usedVariantIds
            );
        }

        if (stageShell != null) {
            List<CaminoResolvedStageLeg> legs =
                    copyWithSlice(
                            prefix,
                            cursor,
                            stageShell.trackProjection
                    );

            return new CompletedPath(
                    legs,
                    stageShell.placeKey,
                    stageShell.shellHit,
                    usedVariantIds
            );
        }

        List<CaminoResolvedStageLeg> advanced =
                copyWithSliceToChainage(
                        prefix,
                        cursor,
                        physicalEndChainage
                );

        /*
         * No shell before this technical track boundary. Continue through the
         * proven physical connection. Pure merges therefore stay invisible.
         */
        if (cursor.primaryTrackIndex >= 0) {
            Cursor next =
                    nextPrimaryCursor(
                            state,
                            cursor
                    );

            if (next == null) {
                return null;
            }

            return traceUntilNextShell(
                    state,
                    next,
                    advanced,
                    usedVariantIds,
                    ignoredDecisionKey,
                    depth + 1
            );
        }

        VariantContinuation continuation =
                nextVariantContinuation(
                        state,
                        cursor,
                        usedVariantIds
                );

        if (continuation == null) {
            return null;
        }

        return traceUntilNextShell(
                state,
                continuation.cursor,
                advanced,
                continuation.usedVariantIds,
                ignoredDecisionKey,
                depth + 1
        );
    }


    private Cursor nextPrimaryCursor(
            RouteState state,
            Cursor cursor
    ) {
        int nextIndex =
                cursor.direction > 0
                        ? cursor.primaryTrackIndex + 1
                        : cursor.primaryTrackIndex - 1;

        if (nextIndex < 0
                || nextIndex
                >= state.route.tracks.size()) {

            return null;
        }

        RouteTrack next =
                state.route.tracks.get(
                        nextIndex
                );

        if (next.points.size() < 2) {
            return null;
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

            return null;
        }

        return Cursor.primary(
                next,
                nextIndex,
                nextStart,
                nextDirection
        );
    }


    private VariantContinuation nextVariantContinuation(
            RouteState state,
            Cursor cursor,
            LinkedHashSet<String> usedVariantIds
    ) {
        VariantOwner owner =
                state.variantOwnerByTrack.get(
                        cursor.track
                );

        if (owner == null) {
            return null;
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

            if (GeoMath.distanceMeters(
                    currentPart.endPoint(),
                    nextPart.startPoint()
            ) > JOIN_TOLERANCE_M) {

                return null;
            }

            Cursor next =
                    variantPartStartCursor(
                            state,
                            path,
                            nextPartIndex
                    );

            return next == null
                    ? null
                    : new VariantContinuation(
                            next,
                            usedVariantIds
                    );
        }

        PathAttachment attachment =
                state.attachments.get(
                        path
                );

        if (attachment == null
                || !attachment.valid()) {

            return null;
        }

        TrackProjection target =
                attachment.endTarget;

        Cursor next =
                cursorAtAttachment(
                        state,
                        target
                );

        if (next == null) {
            return null;
        }

        LinkedHashSet<String> continuedUsed =
                new LinkedHashSet<>(
                        usedVariantIds
                );

        VariantOwner targetOwner =
                state.variantOwnerByTrack.get(
                        target.track
                );

        if (targetOwner != null
                && !continuedUsed.contains(
                targetOwner.path.id
        )) {

            /*
             * A variant ending on another official variant is a proven
             * continuation, not a new choice. The next fork on that corridor
             * will still stop traversal normally.
             */
            continuedUsed.add(
                    targetOwner.path.id
            );
        }

        return new VariantContinuation(
                next,
                continuedUsed
        );
    }


    private Cursor cursorAtAttachment(
            RouteState state,
            TrackProjection projection
    ) {
        if (projection == null
                || projection.track == null) {

            return null;
        }

        int primaryIndex =
                state.primaryIndex(
                        projection.track
                );

        if (primaryIndex >= 0) {
            PrimaryStageInfo stage =
                    state.primaryStages.get(
                            projection.track
                    );

            int direction =
                    stage == null
                            ? 1
                            : stage.direction;

            return Cursor.primary(
                    projection.track,
                    primaryIndex,
                    projection,
                    direction
            );
        }

        VariantOwner owner =
                state.variantOwnerByTrack.get(
                        projection.track
                );

        if (owner == null) {
            return null;
        }

        return Cursor.variant(
                projection.track,
                projection,
                owner.part.reversed
                        ? -1
                        : 1
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

        TrackProjection start =
                endpointProjection(
                        part.track,
                        !part.reversed
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


    private DecisionPoint decisionAt(
            RouteState state,
            RouteTrack track,
            TrackProjection projection,
            String placeKey
    ) {
        List<BranchEvent> events =
                state.branchStartsByTrack.get(
                        track
                );

        if (events == null) {
            return null;
        }

        for (BranchEvent event
                : events) {

            DecisionPoint decision =
                    decisionByAttachment.get(
                            event.attachment
                    );

            if (decision == null
                    || !decision.placeKey.equals(
                    placeKey
            )) {

                continue;
            }

            if (Math.abs(
                    event.target.chainageM
                            - projection.chainageM
            ) <= JOIN_TOLERANCE_M) {

                return decision;
            }
        }

        return null;
    }


    private DecisionHit nearestDecisionAhead(
            RouteState state,
            Cursor cursor,
            String ignoredDecisionKey
    ) {
        List<BranchEvent> events =
                state.branchStartsByTrack.get(
                        cursor.track
                );

        if (events == null
                || events.isEmpty()) {

            return null;
        }

        DecisionHit best =
                null;

        for (BranchEvent event
                : events) {

            DecisionPoint decision =
                    decisionByAttachment.get(
                            event.attachment
                    );

            if (decision == null) {
                continue;
            }

            double ahead =
                    forwardDistance(
                            cursor,
                            event.target.chainageM
                    );

            if (ahead < -EVENT_EPSILON_M) {
                continue;
            }

            if (ignoredDecisionKey != null
                    && ignoredDecisionKey.equals(
                    decision.placeKey
            )
                    && ahead
                    <= JOIN_TOLERANCE_M) {

                continue;
            }

            if (best == null
                    || ahead
                    < best.aheadM) {

                best =
                        new DecisionHit(
                                decision,
                                event.target,
                                Math.max(
                                        0.0,
                                        ahead
                                )
                        );
            }
        }

        return best;
    }


    private ShellEvent nearestEstablishedShellAhead(
            RouteState state,
            Cursor cursor,
            String ignoredPlaceKey
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
                    forwardDistance(
                            cursor,
                            primaryStage.end.chainageM
                    );

            if (ahead
                    >= -EVENT_EPSILON_M) {

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
         * A variant may physically pass an established primary shell before
         * its technical KML endpoint. That shell still ends the section.
         */
        List<ShellEvent> events =
                state.shellsOnTrack.get(
                        cursor.track
                );

        if (events != null) {
            for (ShellEvent event
                    : events) {

                double ahead =
                        forwardDistance(
                                cursor,
                                event.trackProjection.chainageM
                        );

                if (ahead < -EVENT_EPSILON_M
                        || ahead
                        >= bestAhead) {

                    continue;
                }

                if (ignoredPlaceKey != null
                        && ignoredPlaceKey.equals(
                        event.placeKey
                )
                        && ahead
                        <= JOIN_TOLERANCE_M) {

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


    private double forwardDistance(
            Cursor cursor,
            double chainageM
    ) {
        return cursor.direction > 0
                ? chainageM
                - cursor.position.chainageM
                : cursor.position.chainageM
                - chainageM;
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


    private CaminoResolvedStagePath resolved(
            CaminoRoute route,
            String startPlaceKey,
            ProjectionHit startHit,
            CompletedPath completed
    ) {
        if (route == null
                || startHit == null
                || completed == null
                || completed.shellHit == null
                || !meaningful(
                completed.destinationPlaceKey
        )
                || completed.legs.isEmpty()) {

            return null;
        }

        String id =
                signature(
                        route,
                        startPlaceKey,
                        completed
                );

        return new CaminoResolvedStagePath(
                id,
                route,
                startPlaceKey,
                completed.destinationPlaceKey,
                startHit,
                completed.shellHit,
                completed.legs,
                completed.usedVariantIds
        );
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

        if (item.usedVariantIds.isEmpty()) {
            result.append(
                    "straight"
            );

        } else {
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
        }

        return result.toString();
    }


    private void installDecisionShells(
            CaminoStageTopology topology
    ) {
        int syntheticIndex =
                1;

        for (RouteState state
                : states.values()) {

            for (List<BranchEvent> events
                    : state.branchStartsByTrack.values()) {

                for (BranchEvent event
                        : events) {

                    if (!event.attachment.valid()) {
                        continue;
                    }

                    DecisionPoint existing =
                            existingDecisionNear(
                                    state,
                                    event
                            );

                    if (existing == null) {
                        CaminoStageTopology.StageNode established =
                                topology.nearestPrimaryNode(
                                        state.route,
                                        event.target.point,
                                        EXISTING_SHELL_REUSE_M
                                );

                        String placeKey;
                        LatLng point;

                        if (established != null) {
                            placeKey =
                                    established.placeKey;

                            point =
                                    established.point;

                        } else {
                            placeKey =
                                    "@branch:"
                                            + state.route.id
                                            + ":"
                                            + syntheticIndex++;

                            point =
                                    event.target.point;

                            topology.addDecisionNode(
                                    placeKey,
                                    point,
                                    state.route.color
                            );
                        }

                        existing =
                                new DecisionPoint(
                                        state,
                                        placeKey,
                                        point
                                );

                        allDecisions.add(
                                existing
                        );

                        decisionsByKey
                                .computeIfAbsent(
                                        placeKey,
                                        ignored ->
                                                new ArrayList<>()
                                )
                                .add(
                                        existing
                                );
                    }

                    existing.events.add(
                            event
                    );

                    decisionByAttachment.put(
                            event.attachment,
                            existing
                    );
                }
            }
        }
    }


    private DecisionPoint existingDecisionNear(
            RouteState state,
            BranchEvent event
    ) {
        for (DecisionPoint decision
                : allDecisions) {

            if (decision.state != state) {
                continue;
            }

            for (BranchEvent existing
                    : decision.events) {

                if (existing.target.track
                        == event.target.track
                        && Math.abs(
                        existing.target.chainageM
                                - event.target.chainageM
                ) <= JOIN_TOLERANCE_M) {

                    return decision;
                }
            }

            if (GeoMath.distanceMeters(
                    decision.point,
                    event.target.point
            ) <= 5.0) {

                return decision;
            }
        }

        return null;
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

            buildEstablishedShellEventsOnVariants();
        }


        private PathAttachment buildAttachment(
                CaminoVariantPath path
        ) {
            TrackProjection start =
                    nearestBranchStartTrack(
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


        private TrackProjection nearestBranchStartTrack(
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

            TrackProjection bestPrimary =
                    null;

            for (RouteTrack primary
                    : route.tracks) {

                if (excluded.contains(
                        primary
                )) {
                    continue;
                }

                TrackProjection hit =
                        project(
                                primary,
                                point
                        );

                if (hit != null
                        && (
                        bestPrimary == null
                                || hit.distanceM
                                < bestPrimary.distanceM
                )) {

                    bestPrimary =
                            hit;
                }
            }

            if (bestPrimary != null
                    && bestPrimary.distanceM
                    <= JOIN_TOLERANCE_M) {

                return bestPrimary;
            }

            return nearestOtherTrack(
                    point,
                    ownPath
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


        private void buildEstablishedShellEventsOnVariants() {
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


    private static final class DecisionPoint {

        final RouteState state;
        final String placeKey;
        final LatLng point;
        final List<BranchEvent> events =
                new ArrayList<>();


        DecisionPoint(
                RouteState state,
                String placeKey,
                LatLng point
        ) {
            this.state =
                    state;

            this.placeKey =
                    placeKey;

            this.point =
                    point;
        }
    }


    private static final class DecisionHit {

        final DecisionPoint decision;
        final TrackProjection projection;
        final double aheadM;


        DecisionHit(
                DecisionPoint decision,
                TrackProjection projection,
                double aheadM
        ) {
            this.decision =
                    decision;

            this.projection =
                    projection;

            this.aheadM =
                    aheadM;
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


    private static final class VariantContinuation {

        final Cursor cursor;
        final LinkedHashSet<String> usedVariantIds;


        VariantContinuation(
                Cursor cursor,
                LinkedHashSet<String> usedVariantIds
        ) {
            this.cursor =
                    cursor;

            this.usedVariantIds =
                    usedVariantIds;
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
