package com.marukitano.caminoguard;

import org.maplibre.android.geometry.LatLng;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Logical stage topology derived from the canonical PRIMARY Camino tracks.
 *
 * One placeKey becomes one visible StageNode. A StageNode may belong to
 * several Caminos and therefore owns several incoming/outgoing StageEdges.
 *
 * Rendering deduplication is deliberately NOT allowed to destroy this routing
 * identity.
 */
final class CaminoStageTopology {

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


    private final Map<String, StageNode> nodesByPlaceKey =
            new LinkedHashMap<>();


    void rebuild(
            List<CaminoRoute> routes
    ) {
        nodesByPlaceKey.clear();

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
            String placeKey
    ) {
        if (!meaningful(
                placeKey
        )) {
            return null;
        }

        return nodesByPlaceKey.get(
                placeKey
        );
    }


    Collection<StageNode> nodes() {
        return Collections.unmodifiableCollection(
                nodesByPlaceKey.values()
        );
    }


    private StageNode getOrCreateNode(
            String placeKey,
            LatLng point,
            String markerColor
    ) {
        StageNode existing =
                nodesByPlaceKey.get(
                        placeKey
                );

        if (existing != null) {
            return existing;
        }

        StageNode created =
                new StageNode(
                        placeKey,
                        point,
                        markerColor
                );

        nodesByPlaceKey.put(
                placeKey,
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
