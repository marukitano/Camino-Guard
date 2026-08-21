package com.marukitano.caminoguard;

import org.maplibre.android.geometry.LatLng;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Owns the Camino graph and shortest-path algorithm.
 *
 * Input is the parsed global domain model from CaminoRepository.
 * This class has no Android UI, MapLibre rendering, GPS or HUD responsibility.
 */
final class CaminoNetwork {

    private final List<NetworkTrack> tracks =
            new ArrayList<>();

    private final List<List<GraphEdge>> graph =
            new ArrayList<>();

    List<NetworkTrack> tracks() {
        return tracks;
    }

    void rebuild(
            List<CaminoRoute> routes
    ) {
        tracks.clear();
        graph.clear();

        for (CaminoRoute route
                : routes) {

            for (int trackIndex = 0;
                    trackIndex < route.tracks.size();
                    trackIndex++) {

                RouteTrack track =
                        route.tracks.get(
                                trackIndex
                        );

                track.networkIndex =
                        tracks.size();

                tracks.add(
                        new NetworkTrack(
                                route,
                                track,
                                trackIndex
                        )
                );
            }
        }

        int nodeCount =
                tracks.size()
                        * 2;

        for (int node = 0;
                node < nodeCount;
                node++) {

            graph.add(
                    new ArrayList<>()
            );
        }

        /* Every official primary track is a traversable graph edge. */
        for (NetworkTrack reference
                : tracks) {

            int startNode =
                    reference.track.networkIndex
                            * 2;

            int endNode =
                    startNode + 1;

            addUndirectedEdge(
                    startNode,
                    endNode,
                    reference.track.lengthM,
                    GraphEdge.TYPE_TRACK
            );
        }

        /*
         * Preserve the established section ordering inside each route.
         * A physical gap remains a real weighted edge and therefore contributes
         * to route distance exactly as before this extraction.
         */
        for (CaminoRoute route
                : routes) {

            for (int trackIndex = 0;
                    trackIndex
                            < route.tracks.size() - 1;
                    trackIndex++) {

                RouteTrack first =
                        route.tracks.get(
                                trackIndex
                        );

                RouteTrack second =
                        route.tracks.get(
                                trackIndex + 1
                        );

                int firstEndNode =
                        first.networkIndex
                                * 2 + 1;

                int secondStartNode =
                        second.networkIndex
                                * 2;

                addUndirectedEdge(
                        firstEndNode,
                        secondStartNode,
                        gapBetweenTracks(
                                route,
                                trackIndex,
                                trackIndex + 1
                        ),
                        GraphEdge.TYPE_GAP
                );
            }
        }

        /*
         * Cross-Camino transitions are semantic: endpoint place keys must
         * match. The configured geometry guard prevents malformed long jumps.
         */
        for (int firstNode = 0;
                firstNode < nodeCount;
                firstNode++) {

            String firstKey =
                    endpointPlaceKey(
                            firstNode
                    );

            if (firstKey == null) {
                continue;
            }

            for (int secondNode = firstNode + 1;
                    secondNode < nodeCount;
                    secondNode++) {

                if (firstNode / 2
                        == secondNode / 2) {
                    continue;
                }

                String secondKey =
                        endpointPlaceKey(
                                secondNode
                        );

                if (!firstKey.equals(
                        secondKey
                )) {
                    continue;
                }

                double gapM =
                        CaminoRepository.distanceMeters(
                                endpointPoint(
                                        firstNode
                                ),
                                endpointPoint(
                                        secondNode
                                )
                        );

                if (gapM
                        > CaminoConfig.get()
                        .doubleValue(
                                "measurement.maxSemanticTransferGapMeters"
                        )) {
                    continue;
                }

                addUndirectedEdge(
                        firstNode,
                        secondNode,
                        gapM,
                        GraphEdge.TYPE_GAP
                );
            }
        }
    }

    NetworkPath findPath(
            int startNode,
            int endNode
    ) {
        if (startNode == endNode) {
            return new NetworkPath(
                    0.0,
                    new ArrayList<>()
            );
        }

        int nodeCount =
                graph.size();

        if (startNode < 0
                || endNode < 0
                || startNode >= nodeCount
                || endNode >= nodeCount) {
            return null;
        }

        double[] distance =
                new double[nodeCount];

        int[] previous =
                new int[nodeCount];

        int[] previousType =
                new int[nodeCount];

        double[] previousDistance =
                new double[nodeCount];

        for (int node = 0;
                node < nodeCount;
                node++) {

            distance[node] =
                    Double.POSITIVE_INFINITY;

            previous[node] =
                    -1;
        }

        PriorityQueue<NodeDistance> queue =
                new PriorityQueue<>(
                        Comparator.comparingDouble(
                                item ->
                                        item.distanceM
                        )
                );

        distance[startNode] =
                0.0;

        queue.add(
                new NodeDistance(
                        startNode,
                        0.0
                )
        );

        while (!queue.isEmpty()) {
            NodeDistance current =
                    queue.poll();

            if (current.distanceM
                    != distance[current.node]) {
                continue;
            }

            if (current.node
                    == endNode) {
                break;
            }

            for (GraphEdge edge
                    : graph.get(
                            current.node
                    )) {

                double candidate =
                        current.distanceM
                                + edge.distanceM;

                if (candidate
                        >= distance[edge.toNode]) {
                    continue;
                }

                distance[edge.toNode] =
                        candidate;

                previous[edge.toNode] =
                        current.node;

                previousType[edge.toNode] =
                        edge.type;

                previousDistance[edge.toNode] =
                        edge.distanceM;

                queue.add(
                        new NodeDistance(
                                edge.toNode,
                                candidate
                        )
                );
            }
        }

        if (!Double.isFinite(
                distance[endNode]
        )) {
            return null;
        }

        List<NetworkStep> reversed =
                new ArrayList<>();

        int currentNode =
                endNode;

        while (currentNode
                != startNode) {

            int previousNode =
                    previous[currentNode];

            if (previousNode < 0) {
                return null;
            }

            reversed.add(
                    new NetworkStep(
                            previousNode,
                            currentNode,
                            previousDistance[currentNode],
                            previousType[currentNode]
                    )
            );

            currentNode =
                    previousNode;
        }

        Collections.reverse(
                reversed
        );

        return new NetworkPath(
                distance[endNode],
                reversed
        );
    }

    LatLng endpointPoint(
            int node
    ) {
        NetworkTrack reference =
                tracks.get(
                        node / 2
                );

        if (node % 2 == 0) {
            return reference.track.points.get(
                    0
            );
        }

        return reference.track.points.get(
                reference.track.points.size()
                        - 1
        );
    }

    String endpointHighlightColor(
            int node
    ) {
        NetworkTrack reference =
                tracks.get(
                        node / 2
                );

        return reference.route.highlightColor;
    }

    private String endpointPlaceKey(
            int node
    ) {
        NetworkTrack reference =
                tracks.get(
                        node / 2
                );

        if (node % 2 == 0) {
            return reference.track.pseudoFrom
                    ? null
                    : reference.track.fromKey;
        }

        return reference.track.pseudoTo
                ? null
                : reference.track.toKey;
    }

    private double gapBetweenTracks(
            CaminoRoute route,
            int firstTrackIndex,
            int secondTrackIndex
    ) {
        RouteTrack first =
                route.tracks.get(
                        firstTrackIndex
                );

        RouteTrack second =
                route.tracks.get(
                        secondTrackIndex
                );

        LatLng from =
                first.points.get(
                        first.points.size()
                                - 1
                );

        LatLng to =
                second.points.get(
                        0
                );

        return CaminoRepository.distanceMeters(
                from,
                to
        );
    }

    private void addUndirectedEdge(
            int firstNode,
            int secondNode,
            double distanceM,
            int type
    ) {
        graph.get(
                firstNode
        ).add(
                new GraphEdge(
                        secondNode,
                        distanceM,
                        type
                )
        );

        graph.get(
                secondNode
        ).add(
                new GraphEdge(
                        firstNode,
                        distanceM,
                        type
                )
        );
    }

    private static final class NodeDistance {

        final int node;
        final double distanceM;

        NodeDistance(
                int node,
                double distanceM
        ) {
            this.node =
                    node;

            this.distanceM =
                    distanceM;
        }
    }
}


final class NetworkTrack {

    final CaminoRoute route;
    final RouteTrack track;
    final int trackIndex;

    NetworkTrack(
            CaminoRoute route,
            RouteTrack track,
            int trackIndex
    ) {
        this.route =
                route;

        this.track =
                track;

        this.trackIndex =
                trackIndex;
    }
}


final class NetworkPath {

    final double distanceM;
    final List<NetworkStep> steps;

    NetworkPath(
            double distanceM,
            List<NetworkStep> steps
    ) {
        this.distanceM =
                distanceM;

        this.steps =
                steps;
    }
}


final class NetworkStep {

    final int fromNode;
    final int toNode;
    final double distanceM;
    final int type;

    NetworkStep(
            int fromNode,
            int toNode,
            double distanceM,
            int type
    ) {
        this.fromNode =
                fromNode;

        this.toNode =
                toNode;

        this.distanceM =
                distanceM;

        this.type =
                type;
    }
}


final class GraphEdge {

    static final int TYPE_TRACK = 1;
    static final int TYPE_GAP = 2;

    final int toNode;
    final double distanceM;
    final int type;

    GraphEdge(
            int toNode,
            double distanceM,
            int type
    ) {
        this.toNode =
                toNode;

        this.distanceM =
                distanceM;

        this.type =
                type;
    }
}
