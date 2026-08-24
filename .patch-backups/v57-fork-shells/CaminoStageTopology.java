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

            for (int trackIndex = 0;
                    trackIndex < route.tracks.size();
                    trackIndex++) {

                RouteTrack track =
                        route.tracks.get(
                                trackIndex
                        );

                if (track.points.size()
                        < 2) {
                    continue;
                }

                StageNode fromNode =
                        null;

                StageNode toNode =
                        null;

                if (!track.pseudoFrom
                        && meaningful(
                        track.fromKey
                )) {

                    fromNode =
                            getOrCreateNode(
                                    track.fromKey,
                                    track.points.get(
                                            0
                                    ),
                                    track.color
                            );
                }

                if (!track.pseudoTo
                        && meaningful(
                        track.toKey
                )) {

                    toNode =
                            getOrCreateNode(
                                    track.toKey,
                                    track.points.get(
                                            track.points.size()
                                                    - 1
                                    ),
                                    track.color
                            );
                }

                if (fromNode == null
                        || toNode == null) {

                    continue;
                }

                StageEdge edge =
                        new StageEdge(
                                route,
                                trackIndex,
                                track,
                                track.fromKey,
                                track.toKey
                        );

                fromNode.outgoing.add(
                        edge
                );

                toNode.incoming.add(
                        edge
                );
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
