package com.marukitano.caminoguard;

import org.maplibre.android.geometry.LatLng;

/**
 * Pure Camino route projection / nearest-hit engine.
 *
 * No map UI, touch state, GPS, camera or renderer state lives here.
 */
final class CaminoProjectionEngine {

    private static final double EARTH_RADIUS_M =
            6371008.8;

    private final CaminoNetwork network;

    CaminoProjectionEngine(
            CaminoNetwork network
    ) {
        this.network =
                network;
    }

    RouteHit findNearestRouteHit(
            LatLng query
    ) {
        if (network.tracks().isEmpty()) {
            return null;
        }

        /*
         * First choose the track whose precomputed bounding circle has the
         * smallest possible distance to the query. Project that one exactly,
         * then only inspect tracks whose lower bound can still beat the best
         * exact result. In normal use this turns a scan of all Camino points
         * into a scan of a few hundred cheap bounds plus one/few local tracks.
         */
        NetworkTrack seed =
                null;

        double seedLowerBoundM =
                Double.POSITIVE_INFINITY;

        for (NetworkTrack reference
                : network.tracks()) {

            double lowerBoundM =
                    trackLowerBoundDistanceMeters(
                            reference.track,
                            query
                    );

            if (lowerBoundM
                    < seedLowerBoundM) {
                seedLowerBoundM =
                        lowerBoundM;
                seed =
                        reference;
            }
        }

        if (seed == null) {
            return null;
        }

        ProjectionHit seedHit =
                projectToTrack(
                        seed.route,
                        seed.trackIndex,
                        query
                );

        if (seedHit == null) {
            return null;
        }

        RouteHit best =
                new RouteHit(
                        seed.route,
                        seedHit
                );

        for (NetworkTrack reference
                : network.tracks()) {

            if (reference == seed) {
                continue;
            }

            double lowerBoundM =
                    trackLowerBoundDistanceMeters(
                            reference.track,
                            query
                    );

            if (lowerBoundM
                    > best.hit.distanceFromQueryM) {
                continue;
            }

            ProjectionHit hit =
                    projectToTrack(
                            reference.route,
                            reference.trackIndex,
                            query
                    );

            if (hit != null
                    && hit.distanceFromQueryM
                    < best.hit.distanceFromQueryM) {

                best =
                        new RouteHit(
                                reference.route,
                                hit
                        );
            }
        }

        return best;
    }

    ProjectionHit projectToRoute(
            CaminoRoute route,
            LatLng query
    ) {
        if (route == null) {
            return null;
        }

        ProjectionHit best =
                null;

        for (int trackIndex = 0;
                trackIndex < route.tracks.size();
                trackIndex++) {

            RouteTrack track =
                    route.tracks.get(
                            trackIndex
                    );

            double lowerBoundM =
                    trackLowerBoundDistanceMeters(
                            track,
                            query
                    );

            if (best != null
                    && lowerBoundM
                    > best.distanceFromQueryM) {
                continue;
            }

            ProjectionHit hit =
                    projectToTrack(
                            route,
                            trackIndex,
                            query
                    );

            if (hit != null
                    && (best == null
                    || hit.distanceFromQueryM
                    < best.distanceFromQueryM)) {
                best =
                        hit;
            }
        }

        return best;
    }

    private ProjectionHit projectToTrack(
            CaminoRoute route,
            int trackIndex,
            LatLng query
    ) {
        if (route == null
                || trackIndex < 0
                || trackIndex >= route.tracks.size()) {
            return null;
        }

        RouteTrack track =
                route.tracks.get(
                        trackIndex
                );

        ProjectionHit best =
                null;

        double alongTrackM =
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

            ProjectionHit hit =
                    projectToSegment(
                            query,
                            a,
                            b,
                            track.baseChainageM
                                    + alongTrackM,
                            trackIndex,
                            segmentIndex
                    );

            if (best == null
                    || hit.distanceFromQueryM
                    < best.distanceFromQueryM) {
                best =
                        hit;
            }

            alongTrackM +=
                    CaminoRepository.distanceMeters(
                            a,
                            b
                    );
        }

        return best;
    }

    /**
     * Conservative lower bound based on a precomputed bounding circle.
     * The small safety padding deliberately makes the bound slightly looser;
     * false positives cost only a local track projection, while false negatives
     * could choose the wrong Camino and are therefore avoided.
     */
    private double trackLowerBoundDistanceMeters(
            RouteTrack track,
            LatLng query
    ) {
        double centerDistanceM =
                CaminoRepository.distanceMeters(
                        query,
                        track.boundsCenter
                );

        return Math.max(
                0.0,
                centerDistanceM
                        - track.boundsRadiusM
                        - 250.0
        );
    }

    private ProjectionHit projectToSegment(
            LatLng query,
            LatLng a,
            LatLng b,
            double chainageAtA,
            int trackIndex,
            int segmentIndex
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
                        * EARTH_RADIUS_M
                        * cosLat;

        double ay =
                Math.toRadians(
                        a.getLatitude()
                                - query.getLatitude()
                )
                        * EARTH_RADIUS_M;

        double bx =
                Math.toRadians(
                        b.getLongitude()
                                - query.getLongitude()
                )
                        * EARTH_RADIUS_M
                        * cosLat;

        double by =
                Math.toRadians(
                        b.getLatitude()
                                - query.getLatitude()
                )
                        * EARTH_RADIUS_M;

        double vx =
                bx - ax;

        double vy =
                by - ay;

        double lengthSq =
                vx * vx
                        + vy * vy;

        double t =
                0.0;

        if (lengthSq
                > 1e-9) {

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

        LatLng projected =
                interpolate(
                        a,
                        b,
                        t
                );

        double segmentLength =
                CaminoRepository.distanceMeters(
                        a,
                        b
                );

        return new ProjectionHit(
                projected,
                chainageAtA
                        + t
                        * segmentLength,
                Math.hypot(
                        px,
                        py
                ),
                trackIndex,
                segmentIndex,
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
}
