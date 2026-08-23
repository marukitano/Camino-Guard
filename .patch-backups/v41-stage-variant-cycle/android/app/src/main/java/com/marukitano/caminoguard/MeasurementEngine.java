package com.marukitano.caminoguard;

import org.maplibre.android.geometry.LatLng;
import org.maplibre.geojson.Feature;
import org.maplibre.geojson.LineString;
import org.maplibre.geojson.Point;

import java.util.ArrayList;
import java.util.List;

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

    MeasurementPath buildMeasurementPath(
            RouteHit start,
            RouteHit end
    ) {
        if (start == null
                || end == null) {
            return null;
        }

        /*
         * Same named Camino: preserve the current simple behavior and stay on
         * this Camino instead of looking for a possibly shorter detour through
         * another route group.
         */
        if (start.route
                == end.route) {
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

    ProjectionHit(
            LatLng point,
            double chainageM,
            double distanceFromQueryM,
            int trackIndex,
            int segmentIndex,
            double t
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
