package com.marukitano.caminoguard;

import org.maplibre.android.geometry.LatLng;
import org.maplibre.geojson.Feature;
import org.maplibre.geojson.LineString;
import org.maplibre.geojson.Point;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Owns route measurement and height-profile geometry.
 *
 * The algorithms in this class were moved from CaminoController without
 * redesigning their behavior. It consumes CaminoRepository domain objects and
 * CaminoNetwork shortest paths, and returns a MeasurementPath for rendering/UI.
 *
 * No Activity, View, MapLibre map state, GPS, camera or tracking state lives here.
 */
final class MeasurementEngine {

    private final CaminoNetwork network;

    private final double heightProfileSampleSpacingM;

    MeasurementEngine(
            CaminoNetwork network
    ) {
        this.network =
                network;

        this.heightProfileSampleSpacingM =
                CaminoConfig.get()
                        .doubleValue(
                                "measurement.heightProfileSampleSpacingMeters"
                        );
    }

    private List<Feature> buildRoutePieces(
            CaminoRoute route,
            ProjectionHit start,
            ProjectionHit end
    ) {
        List<Feature> features =
                new ArrayList<>();

        if (start.trackIndex
                == end.trackIndex) {

            addTrackSlice(
                    features,
                    route.tracks.get(
                            start.trackIndex
                    ),
                    start,
                    end
            );

            return features;
        }

        /*
         * Track order is authoritative when crossing section boundaries.
         * Chainage excludes physical gaps, so the end of one track and the
         * start of the next can legitimately have identical chainage values.
         */
        boolean forward =
                start.trackIndex
                        < end.trackIndex;

        if (forward) {
            for (int trackIndex =
                    start.trackIndex;
                    trackIndex
                            <= end.trackIndex;
                    trackIndex++) {

                RouteTrack track =
                        route.tracks.get(
                                trackIndex
                        );

                ProjectionHit from =
                        trackIndex
                                == start.trackIndex
                                ? start
                                : trackStartHit(
                                        route,
                                        trackIndex
                                );

                ProjectionHit to =
                        trackIndex
                                == end.trackIndex
                                ? end
                                : trackEndHit(
                                        route,
                                        trackIndex
                                );

                addTrackSlice(
                        features,
                        track,
                        from,
                        to
                );
            }

        } else {
            for (int trackIndex =
                    start.trackIndex;
                    trackIndex
                            >= end.trackIndex;
                    trackIndex--) {

                RouteTrack track =
                        route.tracks.get(
                                trackIndex
                        );

                ProjectionHit from =
                        trackIndex
                                == start.trackIndex
                                ? start
                                : trackEndHit(
                                        route,
                                        trackIndex
                                );

                ProjectionHit to =
                        trackIndex
                                == end.trackIndex
                                ? end
                                : trackStartHit(
                                        route,
                                        trackIndex
                                );

                addTrackSlice(
                        features,
                        track,
                        from,
                        to
                );
            }
        }

        return features;
    }

    private void addTrackSlice(
            List<Feature> output,
            RouteTrack track,
            ProjectionHit from,
            ProjectionHit to
    ) {
        List<LatLng> slice =
                sliceTrack(
                        track,
                        from,
                        to
                );

        if (slice.size() < 2) {
            return;
        }

        final double minRenderSpacingM = 12.0;

        List<Point> points =
                new ArrayList<>();

        LatLng lastRendered = null;

        for (int index = 0; index < slice.size(); index++) {
            LatLng point = slice.get(index);

            boolean endpoint =
                    index == 0
                            || index == slice.size() - 1;

            if (!endpoint
                    && lastRendered != null
                    && GeoMath.distanceMeters(
                            lastRendered,
                            point
                    ) < minRenderSpacingM) {
                continue;
            }

            points.add(
                    Point.fromLngLat(
                            point.getLongitude(),
                            point.getLatitude()
                    )
            );

            lastRendered = point;
        }

        if (points.size() < 2) {
            return;
        }

        Feature feature =
                Feature.fromGeometry(
                        LineString.fromLngLats(points)
                );

        feature.addStringProperty(
                "color",
                track.color
        );

        feature.addStringProperty(
                "highlight_color",
                track.highlightColor
        );

        output.add(feature);
    }

    private List<LatLng> sliceTrack(
            RouteTrack track,
            ProjectionHit from,
            ProjectionHit to
    ) {
        List<LatLng> result =
                new ArrayList<>();

        result.add(
                from.point
        );

        if (from.segmentIndex
                < to.segmentIndex
                || (
                from.segmentIndex
                        == to.segmentIndex
                        && from.t
                        <= to.t
        )) {

            for (int vertexIndex =
                    from.segmentIndex + 1;
                    vertexIndex
                            <= to.segmentIndex;
                    vertexIndex++) {

                result.add(
                        track.points.get(
                                vertexIndex
                        )
                );
            }

        } else {

            for (int vertexIndex =
                    from.segmentIndex;
                    vertexIndex
                            > to.segmentIndex;
                    vertexIndex--) {

                result.add(
                        track.points.get(
                                vertexIndex
                        )
                );
            }
        }

        if (GeoMath.distanceMeters(
                result.get(
                        result.size() - 1
                ),
                to.point
        ) > 0.05) {

            result.add(
                    to.point
            );
        }

        return result;
    }

    private ProjectionHit trackStartHit(
            CaminoRoute route,
            int trackIndex
    ) {
        RouteTrack track =
                route.tracks.get(
                        trackIndex
                );

        return new ProjectionHit(
                track.points.get(
                        0
                ),
                track.baseChainageM,
                0.0,
                trackIndex,
                0,
                0.0
        );
    }

    private ProjectionHit trackEndHit(
            CaminoRoute route,
            int trackIndex
    ) {
        RouteTrack track =
                route.tracks.get(
                        trackIndex
                );

        int lastSegment =
                track.points.size()
                        - 2;

        return new ProjectionHit(
                track.points.get(
                        track.points.size()
                                - 1
                ),
                track.baseChainageM
                        + track.lengthM,
                0.0,
                trackIndex,
                lastSegment,
                1.0
        );
    }


    MeasurementPath buildResolvedStagePath(
            CaminoResolvedStagePath path
    ) {
        if (path == null
                || path.route == null
                || path.legs.isEmpty()) {

            return null;
        }

        MeasurementPath result =
                new MeasurementPath();

        LatLng previousEnd =
                null;

        double distanceM =
                0.0;

        for (CaminoResolvedStageLeg leg
                : path.legs) {

            if (leg == null
                    || leg.track == null
                    || leg.from == null
                    || leg.to == null
                    || leg.track.points.size() < 2) {

                continue;
            }

            /*
             * Resolver joins are snaps between already-existing official GPS
             * geometries (max 25 m). Do not draw or measure an invented
             * connector. Any non-zero snap simply starts a new profile fragment.
             */
            if (previousEnd != null
                    && GeoMath.distanceMeters(
                    previousEnd,
                    leg.from.point
            ) > 0.05) {

                result.profileLastGeometryPoint =
                        null;

                result.profileNeedsBreak =
                        true;
            }

            addTrackSlice(
                    result.routeFeatures,
                    leg.track,
                    leg.from,
                    leg.to
            );

            appendTrackProfileSlice(
                    result,
                    leg.track,
                    leg.from,
                    leg.to
            );

            List<LatLng> geometry =
                    sliceTrack(
                            leg.track,
                            leg.from,
                            leg.to
                    );

            for (int index = 0;
                    index < geometry.size() - 1;
                    index++) {

                distanceM +=
                        GeoMath.distanceMeters(
                                geometry.get(
                                        index
                                ),
                                geometry.get(
                                        index + 1
                                )
                        );
            }

            previousEnd =
                    leg.to.point;
        }

        if (result.routeFeatures.isEmpty()) {
            return null;
        }

        result.distanceM =
                distanceM;

        result.startRoute =
                path.route;

        result.endRoute =
                path.route;

        return result;
    }


    MeasurementPath buildStageVariantMeasurementPath(
            CaminoRoute route,
            ProjectionHit stageStartHit,
            ProjectionHit branchStartHit,
            RouteTrack variantTrack,
            boolean variantStartIsFirst,
            ProjectionHit mergeHit,
            ProjectionHit stageEndHit
    ) {
        if (route == null
                || stageStartHit == null
                || branchStartHit == null
                || variantTrack == null
                || mergeHit == null
                || stageEndHit == null
                || variantTrack.points.size() < 2) {

            return null;
        }

        ProjectionHit variantStart =
                standaloneTrackEndpointHit(
                        variantTrack,
                        variantStartIsFirst
                );

        ProjectionHit variantEnd =
                standaloneTrackEndpointHit(
                        variantTrack,
                        !variantStartIsFirst
                );

        MeasurementPath result =
                new MeasurementPath();

        /*
         * Part 0:
         * Follow the primary Camino from the tapped stage shell to the actual
         * branch point. This is required for variants such as Castro del Rio
         * 14b whose official geometry begins about 700 m after the shell.
         */
        result.routeFeatures.addAll(
                buildRoutePieces(
                        route,
                        stageStartHit,
                        branchStartHit
                )
        );

        result.gapFeatures.addAll(
                buildRouteGapPieces(
                        route,
                        stageStartHit,
                        branchStartHit
                )
        );

        appendRouteProfilePieces(
                result,
                route,
                stageStartHit,
                branchStartHit
        );

        double prefixDistanceM =
                routeDistanceWithGaps(
                        route,
                        stageStartHit,
                        branchStartHit
                );

        double branchGapM =
                GeoMath.distanceMeters(
                        branchStartHit.point,
                        variantStart.point
                );

        if (branchGapM > 25.0) {
            addGapFeature(
                    result.gapFeatures,
                    branchStartHit.point,
                    variantStart.point,
                    route.highlightColor
            );

            appendGapProfile(
                    result,
                    branchStartHit.point,
                    elevationAtHit(
                            route.tracks.get(
                                    branchStartHit.trackIndex
                            ),
                            branchStartHit
                    ),
                    variantStart.point,
                    elevationAtHit(
                            variantTrack,
                            variantStart
                    )
            );
        }

        /*
         * Part 1: traverse the complete official alternative geometry.
         */
        addTrackSlice(
                result.routeFeatures,
                variantTrack,
                variantStart,
                variantEnd
        );

        appendTrackProfileSlice(
                result,
                variantTrack,
                variantStart,
                variantEnd
        );

        double variantDistanceM =
                trackGeometryLength(
                        variantTrack
                );

        double mergeGapM =
                GeoMath.distanceMeters(
                        variantEnd.point,
                        mergeHit.point
                );

        if (mergeGapM > 25.0) {
            addGapFeature(
                    result.gapFeatures,
                    variantEnd.point,
                    mergeHit.point,
                    route.highlightColor
            );

            appendGapProfile(
                    result,
                    variantEnd.point,
                    elevationAtHit(
                            variantTrack,
                            variantEnd
                    ),
                    mergeHit.point,
                    elevationAtHit(
                            route.tracks.get(
                                    mergeHit.trackIndex
                            ),
                            mergeHit
                    )
            );
        }

        /*
         * Part 2:
         * Continue on the primary Camino after the rejoin point.
         */
        result.routeFeatures.addAll(
                buildRoutePieces(
                        route,
                        mergeHit,
                        stageEndHit
                )
        );

        result.gapFeatures.addAll(
                buildRouteGapPieces(
                        route,
                        mergeHit,
                        stageEndHit
                )
        );

        appendRouteProfilePieces(
                result,
                route,
                mergeHit,
                stageEndHit
        );

        double suffixDistanceM =
                routeDistanceWithGaps(
                        route,
                        mergeHit,
                        stageEndHit
                );

        result.distanceM =
                prefixDistanceM
                        + branchGapM
                        + variantDistanceM
                        + mergeGapM
                        + suffixDistanceM;

        result.startRoute =
                route;

        result.endRoute =
                route;

        return result;
    }


    private ProjectionHit standaloneTrackEndpointHit(
            RouteTrack track,
            boolean firstEndpoint
    ) {
        int lastSegment =
                track.points.size()
                        - 2;

        if (firstEndpoint) {
            return new ProjectionHit(
                    track.points.get(
                            0
                    ),
                    0.0,
                    0.0,
                    -1,
                    0,
                    0.0
            );
        }

        return new ProjectionHit(
                track.points.get(
                        track.points.size() - 1
                ),
                trackGeometryLength(
                        track
                ),
                0.0,
                -1,
                lastSegment,
                1.0
        );
    }


    private double trackGeometryLength(
            RouteTrack track
    ) {
        double distanceM =
                0.0;

        for (int index = 0;
                index < track.points.size() - 1;
                index++) {

            distanceM +=
                    GeoMath.distanceMeters(
                            track.points.get(
                                    index
                            ),
                            track.points.get(
                                    index + 1
                            )
                    );
        }

        return distanceM;
    }


    MeasurementPath buildMeasurementPath(
            RouteHit start,
            RouteHit end
    ) {
        if (start == null
                || end == null) {
            return null;
        }

        RouteTrack startTrack =
                trackForManualHit(
                        start.route,
                        start.hit
                );

        RouteTrack endTrack =
                trackForManualHit(
                        end.route,
                        end.hit
                );

        if (startTrack == null
                || endTrack == null) {

            return null;
        }

        /*
         * Same named Camino:
         *
         * Primary -> primary preserves the old established linear behavior.
         * As soon as one point lies on an official variant, use a route-local
         * graph containing primary + variant geometry.
         */
        if (start.route
                == end.route) {

            boolean startPrimary =
                    start.route.tracks.contains(
                            startTrack
                    );

            boolean endPrimary =
                    end.route.tracks.contains(
                            endTrack
                    );

            if (!startPrimary
                    || !endPrimary) {

                return buildManualVariantAwareMeasurement(
                        start.route,
                        start.hit,
                        end.hit
                );
            }

            MeasurementPath result =
                    new MeasurementPath();

            result.routeFeatures.addAll(
                    buildRoutePieces(
                            start.route,
                            start.hit,
                            end.hit
                    )
            );

            result.gapFeatures.addAll(
                    buildRouteGapPieces(
                            start.route,
                            start.hit,
                            end.hit
                    )
            );

            appendRouteProfilePieces(
                    result,
                    start.route,
                    start.hit,
                    end.hit
            );

            result.distanceM =
                    routeDistanceWithGaps(
                            start.route,
                            start.hit,
                            end.hit
                    );

            result.startRoute =
                    start.route;
            result.endRoute =
                    end.route;

            return result;
        }

        /*
         * The global cross-Camino graph intentionally remains primary-only.
         * Do not silently invent cross-route variant transfers here.
         */
        if (!start.route.tracks.contains(
                startTrack
        )
                || !end.route.tracks.contains(
                endTrack
        )) {

            return null;
        }

        NetworkCandidate best =
                null;

        for (int startSide = 0;
                startSide <= 1;
                startSide++) {

            int startNode =
                    networkNodeForHit(
                            start,
                            startSide
                    );

            double startPartialM =
                    distanceFromHitToTrackEndpoint(
                            start,
                            startSide
                    );

            for (int endSide = 0;
                    endSide <= 1;
                    endSide++) {

                int endNode =
                        networkNodeForHit(
                                end,
                                endSide
                        );

                double endPartialM =
                        distanceFromHitToTrackEndpoint(
                                end,
                                endSide
                        );

                NetworkPath networkPath =
                        network.findPath(
                                startNode,
                                endNode
                        );

                if (networkPath == null) {
                    continue;
                }

                double totalM =
                        startPartialM
                                + networkPath.distanceM
                                + endPartialM;

                if (best == null
                        || totalM
                        < best.totalM) {

                    best =
                            new NetworkCandidate(
                                    startSide,
                                    endSide,
                                    totalM,
                                    networkPath
                            );
                }
            }
        }

        if (best == null) {
            return null;
        }

        MeasurementPath result =
                new MeasurementPath();

        result.distanceM =
                best.totalM;
        result.startRoute =
                start.route;
        result.endRoute =
                end.route;

        addPartialTrack(
                result.routeFeatures,
                start,
                best.startSide,
                true
        );

        for (NetworkStep step
                : best.networkPath.steps) {

            if (step.type
                    == GraphEdge.TYPE_TRACK) {
                addFullTrackStep(
                        result.routeFeatures,
                        step
                );

            } else {
                addGapFeature(
                        result.gapFeatures,
                        endpointPoint(
                                step.fromNode
                        ),
                        endpointPoint(
                                step.toNode
                        ),
                        endpointHighlightColor(
                                step.fromNode
                        )
                );
            }
        }

        addPartialTrack(
                result.routeFeatures,
                end,
                best.endSide,
                false
        );

        appendCrossRouteProfile(
                result,
                start,
                end,
                best
        );

        return result;
    }

    private static final double MANUAL_VARIANT_JOIN_TOLERANCE_M =
            25.0;

    private static final double MANUAL_NODE_EPSILON_M =
            0.05;


    private RouteTrack trackForManualHit(
            CaminoRoute route,
            ProjectionHit hit
    ) {
        if (route == null
                || hit == null) {

            return null;
        }

        if (hit.track != null
                && route.renderTracks.contains(
                hit.track
        )) {

            return hit.track;
        }

        if (hit.trackIndex >= 0
                && hit.trackIndex
                < route.tracks.size()) {

            return route.tracks.get(
                    hit.trackIndex
            );
        }

        return null;
    }


    /**
     * Manual two-point measurement for one Camino when at least one endpoint
     * lies on an official variant.
     *
     * This graph is deliberately separate from CaminoNetwork:
     * - primary-only global behavior stays unchanged
     * - all official renderTracks are traversable
     * - variant paths attach only at proven <=25 m physical junctions
     * - primary section gaps keep their old measured behavior
     */
    private MeasurementPath buildManualVariantAwareMeasurement(
            CaminoRoute route,
            ProjectionHit start,
            ProjectionHit end
    ) {
        RouteTrack startTrack =
                trackForManualHit(
                        route,
                        start
                );

        RouteTrack endTrack =
                trackForManualHit(
                        route,
                        end
                );

        if (route == null
                || startTrack == null
                || endTrack == null) {

            return null;
        }

        /*
         * Two points on exactly the same geometry should simply use that
         * geometry instead of finding a shorter detour through the network.
         */
        if (startTrack == endTrack) {
            ProjectionHit localStart =
                    manualHitAtChainage(
                            route,
                            startTrack,
                            localChainageM(
                                    startTrack,
                                    start
                            )
                    );

            ProjectionHit localEnd =
                    manualHitAtChainage(
                            route,
                            endTrack,
                            localChainageM(
                                    endTrack,
                                    end
                            )
                    );

            if (localStart == null
                    || localEnd == null) {

                return null;
            }

            MeasurementPath direct =
                    new MeasurementPath();

            addTrackSlice(
                    direct.routeFeatures,
                    startTrack,
                    localStart,
                    localEnd
            );

            appendTrackProfileSlice(
                    direct,
                    startTrack,
                    localStart,
                    localEnd
            );

            direct.distanceM =
                    Math.abs(
                            localChainageM(
                                    startTrack,
                                    start
                            )
                                    - localChainageM(
                                    endTrack,
                                    end
                            )
                    );

            direct.startRoute =
                    route;

            direct.endRoute =
                    route;

            return direct.routeFeatures.isEmpty()
                    ? null
                    : direct;
        }

        Map<RouteTrack, List<ManualNode>> nodesByTrack =
                new IdentityHashMap<>();

        for (RouteTrack track
                : route.renderTracks) {

            if (track == null
                    || track.points.size() < 2) {

                continue;
            }

            manualNode(
                    nodesByTrack,
                    track,
                    0.0
            );

            manualNode(
                    nodesByTrack,
                    track,
                    trackGeometryLength(
                            track
                    )
            );
        }

        ManualNode startNode =
                manualNode(
                        nodesByTrack,
                        startTrack,
                        localChainageM(
                                startTrack,
                                start
                        )
                );

        ManualNode endNode =
                manualNode(
                        nodesByTrack,
                        endTrack,
                        localChainageM(
                                endTrack,
                                end
                        )
                );

        if (startNode == null
                || endNode == null) {

            return null;
        }

        List<ManualConnection> connections =
                new ArrayList<>();

        /*
         * Preserve the old ordered primary-track connection behavior.
         * A real source gap contributes to distance and is rendered as a gap.
         */
        for (int index = 0;
                index < route.tracks.size() - 1;
                index++) {

            RouteTrack left =
                    route.tracks.get(
                            index
                    );

            RouteTrack right =
                    route.tracks.get(
                            index + 1
                    );

            ManualNode from =
                    manualNode(
                            nodesByTrack,
                            left,
                            trackGeometryLength(
                                    left
                            )
                    );

            ManualNode to =
                    manualNode(
                            nodesByTrack,
                            right,
                            0.0
                    );

            if (from != null
                    && to != null) {

                connections.add(
                        new ManualConnection(
                                from,
                                to,
                                GeoMath.distanceMeters(
                                        from.point,
                                        to.point
                                ),
                                true
                        )
                );
            }
        }

        /*
         * Variant topology comes only from official CaminoVariantPath objects.
         * Geometry may attach a path only when BOTH outside endpoints are
         * physically proven within the same <=25 m tolerance as stage routing.
         */
        for (CaminoVariantPath path
                : route.variantPaths) {

            if (path == null
                    || path.parts.isEmpty()) {

                continue;
            }

            Set<RouteTrack> ownTracks =
                    Collections.newSetFromMap(
                            new IdentityHashMap<>()
                    );

            for (CaminoVariantPathPart part
                    : path.parts) {

                ownTracks.add(
                        part.track
                );
            }

            ManualProjection startAttachment =
                    nearestManualAttachment(
                            route,
                            path.startPoint(),
                            ownTracks,
                            true
                    );

            ManualProjection endAttachment =
                    nearestManualAttachment(
                            route,
                            path.endPoint(),
                            ownTracks,
                            false
                    );

            boolean valid =
                    startAttachment != null
                            && endAttachment != null
                            && startAttachment.distanceM
                            <= MANUAL_VARIANT_JOIN_TOLERANCE_M
                            && endAttachment.distanceM
                            <= MANUAL_VARIANT_JOIN_TOLERANCE_M;

            if (valid) {
                CaminoVariantPathPart firstPart =
                        path.parts.get(
                                0
                        );

                CaminoVariantPathPart lastPart =
                        path.parts.get(
                                path.parts.size() - 1
                        );

                ManualProjection variantStart =
                        manualProject(
                                firstPart.track,
                                path.startPoint()
                        );

                ManualProjection variantEnd =
                        manualProject(
                                lastPart.track,
                                path.endPoint()
                        );

                if (variantStart != null
                        && variantEnd != null) {

                    ManualNode variantStartNode =
                            manualNode(
                                    nodesByTrack,
                                    firstPart.track,
                                    variantStart.chainageM
                            );

                    ManualNode startTargetNode =
                            manualNode(
                                    nodesByTrack,
                                    startAttachment.track,
                                    startAttachment.chainageM
                            );

                    ManualNode variantEndNode =
                            manualNode(
                                    nodesByTrack,
                                    lastPart.track,
                                    variantEnd.chainageM
                            );

                    ManualNode endTargetNode =
                            manualNode(
                                    nodesByTrack,
                                    endAttachment.track,
                                    endAttachment.chainageM
                            );

                    if (variantStartNode != null
                            && startTargetNode != null) {

                        connections.add(
                                new ManualConnection(
                                        variantStartNode,
                                        startTargetNode,
                                        0.0,
                                        false
                                )
                        );
                    }

                    if (variantEndNode != null
                            && endTargetNode != null) {

                        connections.add(
                                new ManualConnection(
                                        variantEndNode,
                                        endTargetNode,
                                        0.0,
                                        false
                                )
                        );
                    }
                }
            }

            /*
             * Source-defined pieces in one official variant run may cross
             * section numbers. Join them only when their real endpoint geometry
             * is within the strict topology tolerance.
             */
            for (int partIndex = 0;
                    partIndex < path.parts.size() - 1;
                    partIndex++) {

                CaminoVariantPathPart left =
                        path.parts.get(
                                partIndex
                        );

                CaminoVariantPathPart right =
                        path.parts.get(
                                partIndex + 1
                        );

                if (GeoMath.distanceMeters(
                        left.endPoint(),
                        right.startPoint()
                ) > MANUAL_VARIANT_JOIN_TOLERANCE_M) {

                    continue;
                }

                ManualProjection leftEnd =
                        manualProject(
                                left.track,
                                left.endPoint()
                        );

                ManualProjection rightStart =
                        manualProject(
                                right.track,
                                right.startPoint()
                        );

                if (leftEnd == null
                        || rightStart == null) {

                    continue;
                }

                ManualNode leftNode =
                        manualNode(
                                nodesByTrack,
                                left.track,
                                leftEnd.chainageM
                        );

                ManualNode rightNode =
                        manualNode(
                                nodesByTrack,
                                right.track,
                                rightStart.chainageM
                        );

                if (leftNode != null
                        && rightNode != null) {

                    connections.add(
                            new ManualConnection(
                                    leftNode,
                                    rightNode,
                                    0.0,
                                    false
                            )
                    );
                }
            }
        }

        List<ManualNode> allNodes =
                new ArrayList<>();

        for (Map.Entry<RouteTrack, List<ManualNode>> entry
                : nodesByTrack.entrySet()) {

            List<ManualNode> nodes =
                    entry.getValue();

            nodes.sort(
                    Comparator.comparingDouble(
                            node ->
                                    node.chainageM
                    )
            );

            for (ManualNode node
                    : nodes) {

                node.id =
                        allNodes.size();

                allNodes.add(
                        node
                );
            }
        }

        List<List<ManualEdge>> graph =
                new ArrayList<>();

        for (int index = 0;
                index < allNodes.size();
                index++) {

            graph.add(
                    new ArrayList<>()
            );
        }

        /*
         * Split every track at all branch/merge/user nodes.
         */
        for (Map.Entry<RouteTrack, List<ManualNode>> entry
                : nodesByTrack.entrySet()) {

            List<ManualNode> nodes =
                    entry.getValue();

            for (int index = 0;
                    index < nodes.size() - 1;
                    index++) {

                addManualTrackEdge(
                        graph,
                        nodes.get(
                                index
                        ),
                        nodes.get(
                                index + 1
                        )
                );
            }
        }

        for (ManualConnection connection
                : connections) {

            addManualConnectionEdge(
                    graph,
                    connection
            );
        }

        ManualPath manualPath =
                findManualPath(
                        graph,
                        startNode.id,
                        endNode.id
                );

        if (manualPath == null) {
            return null;
        }

        MeasurementPath result =
                new MeasurementPath();

        LatLng previousEnd =
                null;

        for (ManualEdge edge
                : manualPath.edges) {

            if (edge.track != null) {
                ProjectionHit from =
                        manualHitAtChainage(
                                route,
                                edge.track,
                                edge.fromChainageM
                        );

                ProjectionHit to =
                        manualHitAtChainage(
                                route,
                                edge.track,
                                edge.toChainageM
                        );

                if (from == null
                        || to == null) {

                    continue;
                }

                if (previousEnd != null
                        && GeoMath.distanceMeters(
                        previousEnd,
                        from.point
                ) > MANUAL_NODE_EPSILON_M) {

                    result.profileLastGeometryPoint =
                            null;

                    result.profileNeedsBreak =
                            true;
                }

                addTrackSlice(
                        result.routeFeatures,
                        edge.track,
                        from,
                        to
                );

                appendTrackProfileSlice(
                        result,
                        edge.track,
                        from,
                        to
                );

                result.distanceM +=
                        edge.distanceM;

                previousEnd =
                        to.point;

                continue;
            }

            /*
             * Primary stage gaps remain real measured gaps.
             */
            if (edge.measuredGap) {
                result.distanceM +=
                        edge.distanceM;

                if (edge.distanceM
                        > MANUAL_NODE_EPSILON_M) {

                    addGapFeature(
                            result.gapFeatures,
                            edge.from.point,
                            edge.toNode.point,
                            route.highlightColor
                    );

                    ProjectionHit fromHit =
                            manualHitAtChainage(
                                    route,
                                    edge.from.track,
                                    edge.from.chainageM
                            );

                    ProjectionHit toHit =
                            manualHitAtChainage(
                                    route,
                                    edge.toNode.track,
                                    edge.toNode.chainageM
                            );

                    appendGapProfile(
                            result,
                            edge.from.point,
                            fromHit == null
                                    ? Double.NaN
                                    : elevationAtHit(
                                            edge.from.track,
                                            fromHit
                                    ),
                            edge.toNode.point,
                            toHit == null
                                    ? Double.NaN
                                    : elevationAtHit(
                                            edge.toNode.track,
                                            toHit
                                    )
                    );
                }

            } else if (GeoMath.distanceMeters(
                    edge.from.point,
                    edge.toNode.point
            ) > MANUAL_NODE_EPSILON_M) {

                /*
                 * Strict topology snap (<=25 m): like resolved stage routing,
                 * do not draw or measure an invented connector.
                 */
                result.profileLastGeometryPoint =
                        null;

                result.profileNeedsBreak =
                        true;
            }

            previousEnd =
                    edge.toNode.point;
        }

        if (result.routeFeatures.isEmpty()) {
            return null;
        }

        result.startRoute =
                route;

        result.endRoute =
                route;

        return result;
    }


    private ManualProjection nearestManualAttachment(
            CaminoRoute route,
            LatLng point,
            Set<RouteTrack> excluded,
            boolean preferPrimary
    ) {
        if (route == null
                || point == null) {

            return null;
        }

        if (preferPrimary) {
            ManualProjection bestPrimary =
                    null;

            for (RouteTrack primary
                    : route.tracks) {

                if (excluded.contains(
                        primary
                )) {

                    continue;
                }

                ManualProjection hit =
                        manualProject(
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
                    <= MANUAL_VARIANT_JOIN_TOLERANCE_M) {

                return bestPrimary;
            }
        }

        ManualProjection best =
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

            ManualProjection hit =
                    manualProject(
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

        return best != null
                && best.distanceM
                <= MANUAL_VARIANT_JOIN_TOLERANCE_M
                ? best
                : null;
    }


    private ManualProjection manualProject(
            RouteTrack track,
            LatLng query
    ) {
        if (track == null
                || query == null
                || track.points.size() < 2) {

            return null;
        }

        ManualProjection best =
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

            ManualSegmentProjection segment =
                    manualProjectToSegment(
                            query,
                            a,
                            b
                    );

            double segmentLength =
                    GeoMath.distanceMeters(
                            a,
                            b
                    );

            if (best == null
                    || segment.distanceM
                    < best.distanceM) {

                best =
                        new ManualProjection(
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
                    segmentLength;
        }

        return best;
    }


    private ManualSegmentProjection manualProjectToSegment(
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

        LatLng point =
                new LatLng(
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

        return new ManualSegmentProjection(
                point,
                Math.hypot(
                        px,
                        py
                ),
                t
        );
    }


    private double localChainageM(
            RouteTrack track,
            ProjectionHit hit
    ) {
        if (track == null
                || hit == null
                || track.points.size() < 2) {

            return 0.0;
        }

        int segment =
                Math.max(
                        0,
                        Math.min(
                                track.points.size() - 2,
                                hit.segmentIndex
                        )
                );

        double chainage =
                0.0;

        for (int index = 0;
                index < segment;
                index++) {

            chainage +=
                    GeoMath.distanceMeters(
                            track.points.get(
                                    index
                            ),
                            track.points.get(
                                    index + 1
                            )
                    );
        }

        double segmentLength =
                GeoMath.distanceMeters(
                        track.points.get(
                                segment
                        ),
                        track.points.get(
                                segment + 1
                        )
                );

        chainage +=
                Math.max(
                        0.0,
                        Math.min(
                                1.0,
                                hit.t
                        )
                )
                        * segmentLength;

        return chainage;
    }


    private ManualNode manualNode(
            Map<RouteTrack, List<ManualNode>> nodesByTrack,
            RouteTrack track,
            double requestedChainageM
    ) {
        if (track == null
                || track.points.size() < 2) {

            return null;
        }

        double length =
                trackGeometryLength(
                        track
                );

        double chainage =
                Math.max(
                        0.0,
                        Math.min(
                                length,
                                requestedChainageM
                        )
                );

        List<ManualNode> nodes =
                nodesByTrack.computeIfAbsent(
                        track,
                        ignored ->
                                new ArrayList<>()
                );

        for (ManualNode node
                : nodes) {

            if (Math.abs(
                    node.chainageM
                            - chainage
            ) <= MANUAL_NODE_EPSILON_M) {

                return node;
            }
        }

        ProjectionHit hit =
                manualHitAtChainage(
                        null,
                        track,
                        chainage
                );

        if (hit == null) {
            return null;
        }

        ManualNode created =
                new ManualNode(
                        track,
                        chainage,
                        hit.point
                );

        nodes.add(
                created
        );

        return created;
    }


    private ProjectionHit manualHitAtChainage(
            CaminoRoute route,
            RouteTrack track,
            double requestedChainageM
    ) {
        if (track == null
                || track.points.size() < 2) {

            return null;
        }

        double total =
                trackGeometryLength(
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

                LatLng point =
                        new LatLng(
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

                int primaryIndex =
                        route == null
                                ? -1
                                : route.tracks.indexOf(
                                        track
                                );

                double publicChainage =
                        primaryIndex >= 0
                                ? track.baseChainageM
                                + wanted
                                : wanted;

                return new ProjectionHit(
                        point,
                        publicChainage,
                        0.0,
                        primaryIndex,
                        segmentIndex,
                        t,
                        track
                );
            }

            chainage +=
                    segmentLength;
        }

        return null;
    }


    private void addManualTrackEdge(
            List<List<ManualEdge>> graph,
            ManualNode left,
            ManualNode right
    ) {
        if (left == null
                || right == null) {

            return;
        }

        double distance =
                Math.abs(
                        right.chainageM
                                - left.chainageM
                );

        graph.get(
                left.id
        ).add(
                new ManualEdge(
                        right.id,
                        distance,
                        left.track,
                        left.chainageM,
                        right.chainageM,
                        left,
                        right,
                        false
                )
        );

        graph.get(
                right.id
        ).add(
                new ManualEdge(
                        left.id,
                        distance,
                        right.track,
                        right.chainageM,
                        left.chainageM,
                        right,
                        left,
                        false
                )
        );
    }


    private void addManualConnectionEdge(
            List<List<ManualEdge>> graph,
            ManualConnection connection
    ) {
        if (connection == null
                || connection.from == null
                || connection.to == null) {

            return;
        }

        graph.get(
                connection.from.id
        ).add(
                new ManualEdge(
                        connection.to.id,
                        connection.distanceM,
                        null,
                        0.0,
                        0.0,
                        connection.from,
                        connection.to,
                        connection.measuredGap
                )
        );

        graph.get(
                connection.to.id
        ).add(
                new ManualEdge(
                        connection.from.id,
                        connection.distanceM,
                        null,
                        0.0,
                        0.0,
                        connection.to,
                        connection.from,
                        connection.measuredGap
                )
        );
    }


    private ManualPath findManualPath(
            List<List<ManualEdge>> graph,
            int startNode,
            int endNode
    ) {
        if (startNode < 0
                || endNode < 0
                || startNode >= graph.size()
                || endNode >= graph.size()) {

            return null;
        }

        if (startNode == endNode) {
            return new ManualPath(
                    new ArrayList<>()
            );
        }

        double[] distance =
                new double[
                        graph.size()
                        ];

        int[] previousNode =
                new int[
                        graph.size()
                        ];

        ManualEdge[] previousEdge =
                new ManualEdge[
                        graph.size()
                        ];

        for (int index = 0;
                index < graph.size();
                index++) {

            distance[index] =
                    Double.POSITIVE_INFINITY;

            previousNode[index] =
                    -1;
        }

        PriorityQueue<ManualQueueItem> queue =
                new PriorityQueue<>(
                        Comparator.comparingDouble(
                                item ->
                                        item.distanceM
                        )
                );

        distance[startNode] =
                0.0;

        queue.add(
                new ManualQueueItem(
                        startNode,
                        0.0
                )
        );

        while (!queue.isEmpty()) {
            ManualQueueItem current =
                    queue.poll();

            if (current.distanceM
                    != distance[current.node]) {

                continue;
            }

            if (current.node
                    == endNode) {

                break;
            }

            for (ManualEdge edge
                    : graph.get(
                            current.node
                    )) {

                double candidate =
                        current.distanceM
                                + edge.distanceM;

                if (candidate
                        >= distance[edge.to]) {

                    continue;
                }

                distance[edge.to] =
                        candidate;

                previousNode[edge.to] =
                        current.node;

                previousEdge[edge.to] =
                        edge;

                queue.add(
                        new ManualQueueItem(
                                edge.to,
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

        List<ManualEdge> reversed =
                new ArrayList<>();

        int node =
                endNode;

        while (node
                != startNode) {

            ManualEdge edge =
                    previousEdge[node];

            int previous =
                    previousNode[node];

            if (edge == null
                    || previous < 0) {

                return null;
            }

            reversed.add(
                    edge
            );

            node =
                    previous;
        }

        Collections.reverse(
                reversed
        );

        return new ManualPath(
                reversed
        );
    }


    private void appendCrossRouteProfile(
            MeasurementPath result,
            RouteHit start,
            RouteHit end,
            NetworkCandidate best
    ) {
        appendPartialTrackProfile(
                result,
                start,
                best.startSide,
                true
        );

        for (NetworkStep step
                : best.networkPath.steps) {

            if (step.type
                    == GraphEdge.TYPE_TRACK) {

                appendFullTrackProfile(
                        result,
                        step
                );

            } else {
                appendGapProfile(
                        result,
                        endpointPoint(
                                step.fromNode
                        ),
                        endpointElevation(
                                step.fromNode
                        ),
                        endpointPoint(
                                step.toNode
                        ),
                        endpointElevation(
                                step.toNode
                        )
                );
            }
        }

        appendPartialTrackProfile(
                result,
                end,
                best.endSide,
                false
        );
    }

    private void appendRouteProfilePieces(
            MeasurementPath result,
            CaminoRoute route,
            ProjectionHit start,
            ProjectionHit end
    ) {
        if (start.trackIndex
                == end.trackIndex) {

            appendTrackProfileSlice(
                    result,
                    route.tracks.get(
                            start.trackIndex
                    ),
                    start,
                    end
            );

            return;
        }

        /*
         * Track order is authoritative when crossing section boundaries.
         * Chainage excludes physical gaps, so the end of one track and the
         * start of the next can legitimately have identical chainage values.
         */
        boolean forward =
                start.trackIndex
                        < end.trackIndex;

        if (forward) {
            for (int trackIndex =
                    start.trackIndex;
                    trackIndex
                            <= end.trackIndex;
                    trackIndex++) {

                RouteTrack track =
                        route.tracks.get(
                                trackIndex
                        );

                ProjectionHit from =
                        trackIndex
                                == start.trackIndex
                                ? start
                                : trackStartHit(
                                        route,
                                        trackIndex
                                );

                ProjectionHit to =
                        trackIndex
                                == end.trackIndex
                                ? end
                                : trackEndHit(
                                        route,
                                        trackIndex
                                );

                appendTrackProfileSlice(
                        result,
                        track,
                        from,
                        to
                );

                if (trackIndex
                        < end.trackIndex) {

                    RouteTrack next =
                            route.tracks.get(
                                    trackIndex + 1
                            );

                    appendGapProfile(
                            result,
                            track.points.get(
                                    track.points.size()
                                            - 1
                            ),
                            track.elevations.get(
                                    track.elevations.size()
                                            - 1
                            ),
                            next.points.get(
                                    0
                            ),
                            next.elevations.get(
                                    0
                            )
                    );
                }
            }

        } else {
            for (int trackIndex =
                    start.trackIndex;
                    trackIndex
                            >= end.trackIndex;
                    trackIndex--) {

                RouteTrack track =
                        route.tracks.get(
                                trackIndex
                        );

                ProjectionHit from =
                        trackIndex
                                == start.trackIndex
                                ? start
                                : trackEndHit(
                                        route,
                                        trackIndex
                                );

                ProjectionHit to =
                        trackIndex
                                == end.trackIndex
                                ? end
                                : trackStartHit(
                                        route,
                                        trackIndex
                                );

                appendTrackProfileSlice(
                        result,
                        track,
                        from,
                        to
                );

                if (trackIndex
                        > end.trackIndex) {

                    RouteTrack previous =
                            route.tracks.get(
                                    trackIndex - 1
                            );

                    appendGapProfile(
                            result,
                            track.points.get(
                                    0
                            ),
                            track.elevations.get(
                                    0
                            ),
                            previous.points.get(
                                    previous.points.size()
                                            - 1
                            ),
                            previous.elevations.get(
                                    previous.elevations.size()
                                            - 1
                            )
                    );
                }
            }
        }
    }

    private void appendPartialTrackProfile(
            MeasurementPath result,
            RouteHit routeHit,
            int endpointSide,
            boolean measurementStartsHere
    ) {
        ProjectionHit endpoint =
                endpointSide == 0
                        ? trackStartHit(
                                routeHit.route,
                                routeHit.hit.trackIndex
                        )
                        : trackEndHit(
                                routeHit.route,
                                routeHit.hit.trackIndex
                        );

        ProjectionHit from =
                measurementStartsHere
                        ? routeHit.hit
                        : endpoint;

        ProjectionHit to =
                measurementStartsHere
                        ? endpoint
                        : routeHit.hit;

        appendTrackProfileSlice(
                result,
                routeHit.route.tracks.get(
                        routeHit.hit.trackIndex
                ),
                from,
                to
        );
    }

    private void appendFullTrackProfile(
            MeasurementPath result,
            NetworkStep step
    ) {
        NetworkTrack reference =
                network.tracks().get(
                        step.fromNode / 2
                );

        int fromSide =
                step.fromNode % 2;

        int toSide =
                step.toNode % 2;

        ProjectionHit from =
                fromSide == 0
                        ? trackStartHit(
                                reference.route,
                                reference.trackIndex
                        )
                        : trackEndHit(
                                reference.route,
                                reference.trackIndex
                        );

        ProjectionHit to =
                toSide == 0
                        ? trackStartHit(
                                reference.route,
                                reference.trackIndex
                        )
                        : trackEndHit(
                                reference.route,
                                reference.trackIndex
                        );

        appendTrackProfileSlice(
                result,
                reference.track,
                from,
                to
        );
    }

    private void appendGapProfile(
            MeasurementPath result,
            LatLng from,
            double fromElevationM,
            LatLng to,
            double toElevationM
    ) {
        appendProfileGeometryPoint(
                result,
                from,
                fromElevationM,
                false,
                true
        );

        /*
         * No invented terrain through an off-geometry Camino gap: horizontal
         * distance remains real, but the next official elevation starts a new
         * profile fragment.
         */
        appendProfileGeometryPoint(
                result,
                to,
                toElevationM,
                true,
                true
        );
    }

    private void appendTrackProfileSlice(
            MeasurementPath result,
            RouteTrack track,
            ProjectionHit from,
            ProjectionHit to
    ) {
        appendProfileGeometryPoint(
                result,
                from.point,
                elevationAtHit(
                        track,
                        from
                ),
                false,
                true
        );

        boolean forward =
                from.segmentIndex
                        < to.segmentIndex
                        || (
                        from.segmentIndex
                                == to.segmentIndex
                                && from.t
                                <= to.t
                );

        if (forward) {
            for (int vertexIndex =
                    from.segmentIndex + 1;
                    vertexIndex
                            <= to.segmentIndex;
                    vertexIndex++) {

                appendProfileGeometryPoint(
                        result,
                        track.points.get(
                                vertexIndex
                        ),
                        track.elevations.get(
                                vertexIndex
                        ),
                        false,
                        false
                );
            }

        } else {
            for (int vertexIndex =
                    from.segmentIndex;
                    vertexIndex
                            > to.segmentIndex;
                    vertexIndex--) {

                appendProfileGeometryPoint(
                        result,
                        track.points.get(
                                vertexIndex
                        ),
                        track.elevations.get(
                                vertexIndex
                        ),
                        false,
                        false
                );
            }
        }

        appendProfileGeometryPoint(
                result,
                to.point,
                elevationAtHit(
                        track,
                        to
                ),
                false,
                true
        );
    }

    private void appendProfileGeometryPoint(
            MeasurementPath result,
            LatLng point,
            double elevationM,
            boolean breakBefore,
            boolean forceEmit
    ) {
        if (point == null) {
            return;
        }

        if (result.profileLastGeometryPoint
                != null) {

            result.profileCursorM +=
                    GeoMath.distanceMeters(
                            result.profileLastGeometryPoint,
                            point
                    );
        }

        result.profileLastGeometryPoint =
                point;

        if (!Double.isFinite(
                elevationM
        )) {
            result.profileNeedsBreak =
                    true;
            return;
        }

        boolean effectiveBreak =
                breakBefore
                        || result.profileNeedsBreak;

        boolean shouldEmit =
                forceEmit
                        || effectiveBreak
                        || result.profilePoints.isEmpty()
                        || result.profileCursorM
                        - result.profileLastEmittedDistanceM
                        >= heightProfileSampleSpacingM;

        if (!shouldEmit) {
            return;
        }

        if (!result.profilePoints.isEmpty()) {
            ProfilePoint previous =
                    result.profilePoints.get(
                            result.profilePoints.size()
                                    - 1
                    );

            if (Math.abs(
                    previous.distanceM
                            - result.profileCursorM
            ) < 0.01
                    && GeoMath.distanceMeters(
                    previous.point,
                    point
            ) < 0.05) {

                result.profileNeedsBreak =
                        false;
                return;
            }
        }

        result.profilePoints.add(
                new ProfilePoint(
                        point,
                        result.profileCursorM,
                        elevationM,
                        effectiveBreak
                )
        );

        result.profileLastEmittedDistanceM =
                result.profileCursorM;

        result.profileNeedsBreak =
                false;
    }

    double elevationAtHit(
            RouteTrack track,
            ProjectionHit hit
    ) {
        if (track.elevations.isEmpty()) {
            return Double.NaN;
        }

        int firstIndex =
                Math.max(
                        0,
                        Math.min(
                                track.elevations.size() - 1,
                                hit.segmentIndex
                        )
                );

        int secondIndex =
                Math.max(
                        0,
                        Math.min(
                                track.elevations.size() - 1,
                                firstIndex + 1
                        )
                );

        double first =
                track.elevations.get(
                        firstIndex
                );

        double second =
                track.elevations.get(
                        secondIndex
                );

        if (Double.isFinite(
                first
        ) && Double.isFinite(
                second
        )) {
            return first
                    + hit.t
                    * (
                    second
                            - first
            );
        }

        if (Double.isFinite(
                first
        )) {
            return first;
        }

        return second;
    }

    private double endpointElevation(
            int node
    ) {
        NetworkTrack reference =
                network.tracks().get(
                        node / 2
                );

        if (reference.track.elevations.isEmpty()) {
            return Double.NaN;
        }

        if (node % 2 == 0) {
            return reference.track.elevations.get(
                    0
            );
        }

        return reference.track.elevations.get(
                reference.track.elevations.size()
                        - 1
        );
    }

    private double routeDistanceWithGaps(
            CaminoRoute route,
            ProjectionHit start,
            ProjectionHit end
    ) {
        double distanceM =
                Math.abs(
                        end.chainageM
                                - start.chainageM
                );

        int firstTrack =
                Math.min(
                        start.trackIndex,
                        end.trackIndex
                );

        int lastTrack =
                Math.max(
                        start.trackIndex,
                        end.trackIndex
                );

        for (int trackIndex = firstTrack;
                trackIndex < lastTrack;
                trackIndex++) {

            distanceM +=
                    gapBetweenTracks(
                            route,
                            trackIndex,
                            trackIndex + 1
                    );
        }

        return distanceM;
    }

    private List<Feature> buildRouteGapPieces(
            CaminoRoute route,
            ProjectionHit start,
            ProjectionHit end
    ) {
        List<Feature> features =
                new ArrayList<>();

        int firstTrack =
                Math.min(
                        start.trackIndex,
                        end.trackIndex
                );

        int lastTrack =
                Math.max(
                        start.trackIndex,
                        end.trackIndex
                );

        for (int trackIndex = firstTrack;
                trackIndex < lastTrack;
                trackIndex++) {

            RouteTrack first =
                    route.tracks.get(
                            trackIndex
                    );

            RouteTrack second =
                    route.tracks.get(
                            trackIndex + 1
                    );

            addGapFeature(
                    features,
                    first.points.get(
                            first.points.size()
                                    - 1
                    ),
                    second.points.get(
                            0
                    ),
                    route.highlightColor
            );
        }

        return features;
    }

    double gapBetweenTracks(
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

        return GeoMath.distanceMeters(
                first.points.get(
                        first.points.size()
                                - 1
                ),
                second.points.get(
                        0
                )
        );
    }

    private void addGapFeature(
            List<Feature> output,
            LatLng from,
            LatLng to,
            String highlightColor
    ) {
        if (from == null
                || to == null
                || GeoMath.distanceMeters(from, to) < 0.05) {
            return;
        }

        List<Point> points =
                new ArrayList<>();

        points.add(
                Point.fromLngLat(
                        from.getLongitude(),
                        from.getLatitude()
                )
        );

        points.add(
                Point.fromLngLat(
                        to.getLongitude(),
                        to.getLatitude()
                )
        );

        Feature feature =
                Feature.fromGeometry(
                        LineString.fromLngLats(points)
                );

        feature.addStringProperty(
                "highlight_color",
                highlightColor
        );

        output.add(feature);
    }

    private int networkNodeForHit(
            RouteHit routeHit,
            int side
    ) {
        RouteTrack track =
                routeHit.route.tracks.get(
                        routeHit.hit.trackIndex
                );

        return track.networkIndex
                * 2
                + side;
    }

    private double distanceFromHitToTrackEndpoint(
            RouteHit routeHit,
            int side
    ) {
        RouteTrack track =
                routeHit.route.tracks.get(
                        routeHit.hit.trackIndex
                );

        double alongTrackM =
                routeHit.hit.chainageM
                        - track.baseChainageM;

        alongTrackM =
                Math.max(
                        0.0,
                        Math.min(
                                track.lengthM,
                                alongTrackM
                        )
                );

        return side == 0
                ? alongTrackM
                : track.lengthM
                - alongTrackM;
    }

    private void addPartialTrack(
            List<Feature> output,
            RouteHit routeHit,
            int endpointSide,
            boolean measurementStartsHere
    ) {
        ProjectionHit endpoint =
                endpointSide == 0
                        ? trackStartHit(
                                routeHit.route,
                                routeHit.hit.trackIndex
                        )
                        : trackEndHit(
                                routeHit.route,
                                routeHit.hit.trackIndex
                        );

        ProjectionHit from =
                measurementStartsHere
                        ? routeHit.hit
                        : endpoint;

        ProjectionHit to =
                measurementStartsHere
                        ? endpoint
                        : routeHit.hit;

        addTrackSlice(
                output,
                routeHit.route.tracks.get(
                        routeHit.hit.trackIndex
                ),
                from,
                to
        );
    }

    private void addFullTrackStep(
            List<Feature> output,
            NetworkStep step
    ) {
        NetworkTrack reference =
                network.tracks().get(
                        step.fromNode / 2
                );

        int fromSide =
                step.fromNode % 2;

        int toSide =
                step.toNode % 2;

        ProjectionHit from =
                fromSide == 0
                        ? trackStartHit(
                                reference.route,
                                reference.trackIndex
                        )
                        : trackEndHit(
                                reference.route,
                                reference.trackIndex
                        );

        ProjectionHit to =
                toSide == 0
                        ? trackStartHit(
                                reference.route,
                                reference.trackIndex
                        )
                        : trackEndHit(
                                reference.route,
                                reference.trackIndex
                        );

        addTrackSlice(
                output,
                reference.track,
                from,
                to
        );
    }

    private String endpointHighlightColor(
            int node
    ) {
        return network.endpointHighlightColor(
                node
        );
    }

    private LatLng endpointPoint(
            int node
    ) {
        return network.endpointPoint(
                node
        );
    }


    private static final class ManualProjection {

        final RouteTrack track;
        final LatLng point;
        final double distanceM;
        final double chainageM;
        final int segmentIndex;
        final double t;

        ManualProjection(
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
    }


    private static final class ManualSegmentProjection {

        final LatLng point;
        final double distanceM;
        final double t;

        ManualSegmentProjection(
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


    private static final class ManualNode {

        final RouteTrack track;
        final double chainageM;
        final LatLng point;
        int id =
                -1;

        ManualNode(
                RouteTrack track,
                double chainageM,
                LatLng point
        ) {
            this.track =
                    track;

            this.chainageM =
                    chainageM;

            this.point =
                    point;
        }
    }


    private static final class ManualConnection {

        final ManualNode from;
        final ManualNode to;
        final double distanceM;
        final boolean measuredGap;

        ManualConnection(
                ManualNode from,
                ManualNode to,
                double distanceM,
                boolean measuredGap
        ) {
            this.from =
                    from;

            this.to =
                    to;

            this.distanceM =
                    distanceM;

            this.measuredGap =
                    measuredGap;
        }
    }


    private static final class ManualEdge {

        final int to;
        final double distanceM;
        final RouteTrack track;
        final double fromChainageM;
        final double toChainageM;
        final ManualNode from;
        final ManualNode toNode;
        final boolean measuredGap;

        ManualEdge(
                int to,
                double distanceM,
                RouteTrack track,
                double fromChainageM,
                double toChainageM,
                ManualNode from,
                ManualNode toNode,
                boolean measuredGap
        ) {
            this.to =
                    to;

            this.distanceM =
                    distanceM;

            this.track =
                    track;

            this.fromChainageM =
                    fromChainageM;

            this.toChainageM =
                    toChainageM;

            this.from =
                    from;

            this.toNode =
                    toNode;

            this.measuredGap =
                    measuredGap;
        }
    }


    private static final class ManualQueueItem {

        final int node;
        final double distanceM;

        ManualQueueItem(
                int node,
                double distanceM
        ) {
            this.node =
                    node;

            this.distanceM =
                    distanceM;
        }
    }


    private static final class ManualPath {

        final List<ManualEdge> edges;

        ManualPath(
                List<ManualEdge> edges
        ) {
            this.edges =
                    edges;
        }
    }


    private static final class NetworkCandidate {
        final int startSide;
        final int endSide;
        final double totalM;
        final NetworkPath networkPath;

        NetworkCandidate(
                int startSide,
                int endSide,
                double totalM,
                NetworkPath networkPath
        ) {
            this.startSide =
                    startSide;
            this.endSide =
                    endSide;
            this.totalM =
                    totalM;
            this.networkPath =
                    networkPath;
        }
    }
}


final class ProfilePoint {

    final LatLng point;
    final double distanceM;
    final double elevationM;
    final boolean breakBefore;

    ProfilePoint(
            LatLng point,
            double distanceM,
            double elevationM,
            boolean breakBefore
    ) {
        this.point =
                point;
        this.distanceM =
                distanceM;
        this.elevationM =
                elevationM;
        this.breakBefore =
                breakBefore;
    }
}


final class MeasurementPath {

    final List<Feature> routeFeatures =
            new ArrayList<>();

    final List<Feature> gapFeatures =
            new ArrayList<>();

    final List<ProfilePoint> profilePoints =
            new ArrayList<>();

    double distanceM;
    CaminoRoute startRoute;
    CaminoRoute endRoute;

    double profileCursorM;

    double profileLastEmittedDistanceM =
            Double.NEGATIVE_INFINITY;

    LatLng profileLastGeometryPoint;
    boolean profileNeedsBreak;
}


final class ProjectionHit {

    final LatLng point;
    final double chainageM;
    final double distanceFromQueryM;
    final int trackIndex;
    final int segmentIndex;
    final double t;

    /*
     * Exact geometry owner for manual selectable hits.
     *
     * Legacy primary-only hits may leave this null and keep using trackIndex.
     * Variant hits use trackIndex == -1 and carry the real RouteTrack here.
     */
    final RouteTrack track;

    ProjectionHit(
            LatLng point,
            double chainageM,
            double distanceFromQueryM,
            int trackIndex,
            int segmentIndex,
            double t
    ) {
        this(
                point,
                chainageM,
                distanceFromQueryM,
                trackIndex,
                segmentIndex,
                t,
                null
        );
    }

    ProjectionHit(
            LatLng point,
            double chainageM,
            double distanceFromQueryM,
            int trackIndex,
            int segmentIndex,
            double t,
            RouteTrack track
    ) {
        this.point =
                point;

        this.chainageM =
                chainageM;

        this.distanceFromQueryM =
                distanceFromQueryM;

        this.trackIndex =
                trackIndex;

        this.segmentIndex =
                segmentIndex;

        this.t =
                t;

        this.track =
                track;
    }
}


final class RouteHit {

    final CaminoRoute route;
    final ProjectionHit hit;

    RouteHit(
            CaminoRoute route,
            ProjectionHit hit
    ) {
        this.route =
                route;

        this.hit =
                hit;
    }
}
