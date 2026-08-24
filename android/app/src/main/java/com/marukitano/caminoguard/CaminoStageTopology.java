package com.marukitano.caminoguard;

import org.maplibre.android.geometry.LatLng;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


/**
 * Primary-stage topology only.
 *
 * Equal semantic names are kept as separate spatial nodes when their
 * coordinates are far apart. Alternative routes do not live here anymore;
 * they have explicit CaminoVariantPath objects and snail markers.
 */
final class CaminoStageTopology {

    private static final double SAME_PLACE_CLUSTER_M =
            1500.0;


    static final class StageNode {

        final String placeKey;
        final LatLng point;
        final String markerColor;

        private final List<StageEdge> outgoing =
                new ArrayList<>();

        private final List<StageEdge> incoming =
                new ArrayList<>();

        StageNode(
                String placeKey,
                LatLng point,
                String markerColor
        ) {
            this.placeKey =
                    placeKey;

            this.point =
                    point;

            this.markerColor =
                    markerColor;
        }

        List<StageEdge> outgoing() {
            return Collections.unmodifiableList(
                    outgoing
            );
        }

        List<StageEdge> incoming() {
            return Collections.unmodifiableList(
                    incoming
            );
        }
    }


    static final class StageEdge {

        final CaminoRoute route;
        final int primaryTrackIndex;
        final RouteTrack primaryTrack;

        final String fromPlaceKey;
        final String toPlaceKey;

        StageEdge(
                CaminoRoute route,
                int primaryTrackIndex,
                RouteTrack primaryTrack,
                String fromPlaceKey,
                String toPlaceKey
        ) {
            this.route =
                    route;

            this.primaryTrackIndex =
                    primaryTrackIndex;

            this.primaryTrack =
                    primaryTrack;

            this.fromPlaceKey =
                    fromPlaceKey;

            this.toPlaceKey =
                    toPlaceKey;
        }
    }


    private final Map<String, List<StageNode>> nodesByPlaceKey =
            new LinkedHashMap<>();

    private final List<StageNode> allNodes =
            new ArrayList<>();


    void rebuild(
            List<CaminoRoute> routes
    ) {
        nodesByPlaceKey.clear();
        allNodes.clear();

        for (CaminoRoute route
                : routes) {

            /*
             * v60 topology contract:
             * ONE shell per track START. Track ends never create a shell.
             */
            List<StageNode> routeStartNodes =
                    new ArrayList<>();

            List<StageNode> starts =
                    new ArrayList<>();

            for (int trackIndex = 0;
                    trackIndex < route.tracks.size();
                    trackIndex++) {

                RouteTrack track =
                        route.tracks.get(
                                trackIndex
                        );

                if (track.points.size() < 2) {
                    starts.add(
                            null
                    );

                    continue;
                }

                String startKey =
                        !track.pseudoFrom
                                && meaningful(
                                track.fromKey
                        )
                                ? track.fromKey
                                : "@trackstart:"
                                + route.id
                                + ":"
                                + track.sectionId;

                StageNode startNode =
                        getOrCreateRouteStartNode(
                                routeStartNodes,
                                startKey,
                                track.points.get(
                                        0
                                ),
                                track.color
                        );

                starts.add(
                        startNode
                );
            }

            for (int trackIndex = 0;
                    trackIndex < route.tracks.size();
                    trackIndex++) {

                StageNode fromNode =
                        starts.get(
                                trackIndex
                        );

                if (fromNode == null) {
                    continue;
                }

                RouteTrack track =
                        route.tracks.get(
                                trackIndex
                        );

                StageNode nextNode =
                        trackIndex + 1
                        < starts.size()
                                ? starts.get(
                                trackIndex + 1
                        )
                                : null;

                String destinationKey;

                if (nextNode != null) {
                    destinationKey =
                            nextNode.placeKey;

                } else if (!track.pseudoTo
                        && meaningful(
                        track.toKey
                )) {

                    destinationKey =
                            track.toKey;

                } else {
                    destinationKey =
                            "@trackend:"
                                    + route.id
                                    + ":"
                                    + track.sectionId;
                }

                StageEdge edge =
                        new StageEdge(
                                route,
                                trackIndex,
                                track,
                                fromNode.placeKey,
                                destinationKey
                        );

                fromNode.outgoing.add(
                        edge
                );

                if (nextNode != null) {
                    nextNode.incoming.add(
                            edge
                    );
                }
            }
        }
    }


    StageNode node(
            String placeKey,
            LatLng point
    ) {
        if (!meaningful(
                placeKey
        )
                || point == null) {

            return null;
        }

        List<StageNode> candidates =
                nodesByPlaceKey.get(
                        placeKey
                );

        if (candidates == null
                || candidates.isEmpty()) {

            return null;
        }

        StageNode best =
                null;

        double bestDistanceM =
                Double.POSITIVE_INFINITY;

        for (StageNode candidate
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

        return best;
    }


    Collection<StageNode> nodes() {
        return Collections.unmodifiableList(
                allNodes
        );
    }



    StageNode addDecisionNode(
            String placeKey,
            LatLng point,
            String markerColor
    ) {
        if (!meaningful(
                placeKey
        )
                || point == null) {

            return null;
        }

        return getOrCreateNode(
                placeKey,
                point,
                markerColor
        );
    }


    StageNode nearestPrimaryNode(
            CaminoRoute route,
            LatLng point,
            double maxDistanceM
    ) {
        if (route == null
                || point == null
                || maxDistanceM < 0.0) {

            return null;
        }

        StageNode best =
                null;

        double bestDistanceM =
                maxDistanceM;

        for (StageNode candidate
                : allNodes) {

            boolean belongsToRoute =
                    false;

            for (StageEdge edge
                    : candidate.outgoing) {

                if (edge.route == route) {
                    belongsToRoute =
                            true;
                    break;
                }
            }

            if (!belongsToRoute) {
                for (StageEdge edge
                        : candidate.incoming) {

                    if (edge.route == route) {
                        belongsToRoute =
                                true;
                        break;
                    }
                }
            }

            if (!belongsToRoute) {
                continue;
            }

            double distanceM =
                    GeoMath.distanceMeters(
                            point,
                            candidate.point
                    );

            if (distanceM
                    <= bestDistanceM) {

                best =
                        candidate;

                bestDistanceM =
                        distanceM;
            }
        }

        return best;
    }


    private static final double START_NODE_MERGE_M =
            10.0;


    private StageNode getOrCreateRouteStartNode(
            List<StageNode> routeStartNodes,
            String placeKey,
            LatLng point,
            String markerColor
    ) {
        for (StageNode candidate
                : routeStartNodes) {

            if (GeoMath.distanceMeters(
                    candidate.point,
                    point
            ) <= START_NODE_MERGE_M) {

                addPlaceKeyAlias(
                        placeKey,
                        candidate
                );

                return candidate;
            }
        }

        StageNode created =
                getOrCreateNode(
                        placeKey,
                        point,
                        markerColor
                );

        if (!routeStartNodes.contains(
                created
        )) {

            routeStartNodes.add(
                    created
            );
        }

        addPlaceKeyAlias(
                placeKey,
                created
        );

        return created;
    }


    private void addPlaceKeyAlias(
            String placeKey,
            StageNode node
    ) {
        if (!meaningful(
                placeKey
        )
                || node == null) {

            return;
        }

        List<StageNode> aliases =
                nodesByPlaceKey.computeIfAbsent(
                        placeKey,
                        ignored ->
                                new ArrayList<>()
                );

        if (!aliases.contains(
                node
        )) {

            aliases.add(
                    node
            );
        }
    }


    private StageNode getOrCreateNode(
            String placeKey,
            LatLng point,
            String markerColor
    ) {
        List<StageNode> candidates =
                nodesByPlaceKey.computeIfAbsent(
                        placeKey,
                        ignored ->
                                new ArrayList<>()
                );

        for (StageNode candidate
                : candidates) {

            if (GeoMath.distanceMeters(
                    candidate.point,
                    point
            ) <= SAME_PLACE_CLUSTER_M) {

                return candidate;
            }
        }

        StageNode created =
                new StageNode(
                        placeKey,
                        point,
                        markerColor
                );

        candidates.add(
                created
        );

        allNodes.add(
                created
        );

        return created;
    }


    private static boolean meaningful(
            String value
    ) {
        return value != null
                && !value.isEmpty();
    }
}
