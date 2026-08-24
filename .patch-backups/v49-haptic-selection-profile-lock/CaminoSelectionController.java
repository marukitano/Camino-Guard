package com.marukitano.caminoguard;

import org.maplibre.android.geometry.LatLng;

import java.util.List;

/**
 * Owns Camino tap-selection state and Tap 1/2/3 semantics.
 *
 * It has no MapLibre map/view state. Screen-distance acceptance is supplied by
 * CaminoController because that calculation depends on the live map projection.
 */
final class CaminoSelectionController {

    interface TapAcceptance {
        boolean isTapCloseEnough(
                LatLng tap,
                LatLng projected
        );
    }

    interface RefreshAction {
        void refresh();
    }

    private final List<CaminoRoute> routes;
    private final CaminoProjectionEngine projectionEngine;
    private final TapAcceptance tapAcceptance;
    private final RefreshAction refreshAction;

    private CaminoRoute selectedRoute;
    private ProjectionHit selectedHit;
    private CaminoRoute secondSelectedRoute;
    private ProjectionHit secondTapHit;

    CaminoSelectionController(
            List<CaminoRoute> routes,
            CaminoProjectionEngine projectionEngine,
            TapAcceptance tapAcceptance,
            RefreshAction refreshAction
    ) {
        this.routes = routes;
        this.projectionEngine = projectionEngine;
        this.tapAcceptance = tapAcceptance;
        this.refreshAction = refreshAction;
    }

    boolean handleMapTap(
            LatLng point,
            boolean dragActive
    ) {
        if (dragActive || routes.isEmpty()) {
            return false;
        }

        // Tap 1: choose whichever Camino is under the finger.
        if (selectedHit == null) {
            RouteHit routeHit = acceptedRouteHit(point);

            if (routeHit == null) {
                clearSelection();
                return false;
            }

            selectedRoute = routeHit.route;
            selectedHit = routeHit.hit;
            secondSelectedRoute = null;
            secondTapHit = null;

            refreshAction.refresh();
            return true;
        }

        // Tap 2: add a destination, possibly on another Camino.
        if (secondTapHit == null) {
            RouteHit routeHit = acceptedRouteHit(point);

            if (routeHit == null) {
                clearSelection();
                return false;
            }

            secondSelectedRoute = routeHit.route;
            secondTapHit = routeHit.hit;

            refreshAction.refresh();
            return true;
        }

        // Tap 3: discard old measurement and start a new one immediately.
        RouteHit routeHit = acceptedRouteHit(point);

        if (routeHit == null) {
            clearSelection();
            return false;
        }

        selectedRoute = routeHit.route;
        selectedHit = routeHit.hit;
        secondSelectedRoute = null;
        secondTapHit = null;

        refreshAction.refresh();
        return true;
    }

    void selectStage(
            CaminoRoute route,
            ProjectionHit startHit,
            ProjectionHit endHit
    ) {
        if (route == null
                || startHit == null
                || endHit == null) {

            return;
        }

        /*
         * A shell tap is deliberately represented as the same two-point
         * selection as two manual Camino taps. Measurement, route halo,
         * elevation profile and ETA therefore stay on the single established
         * code path.
         */
        selectedRoute =
                route;

        selectedHit =
                startHit;

        secondSelectedRoute =
                route;

        secondTapHit =
                endHit;

        refreshAction.refresh();
    }


    CaminoRoute selectedRoute() {
        return selectedRoute;
    }

    ProjectionHit selectedHit() {
        return selectedHit;
    }

    void setSelectedHit(ProjectionHit hit) {
        selectedHit = hit;
    }

    CaminoRoute secondSelectedRoute() {
        return secondSelectedRoute;
    }

    ProjectionHit secondTapHit() {
        return secondTapHit;
    }

    void setSecondTapHit(ProjectionHit hit) {
        secondTapHit = hit;
    }

    private RouteHit acceptedRouteHit(LatLng point) {
        RouteHit routeHit =
                projectionEngine.findNearestSelectableRouteHit(
                        routes,
                        point
                );

        if (routeHit == null
                || !tapAcceptance.isTapCloseEnough(
                        point,
                        routeHit.hit.point
                )) {
            return null;
        }

        return routeHit;
    }

    void clearSelectionWithoutRefresh() {
        selectedRoute =
                null;

        selectedHit =
                null;

        secondSelectedRoute =
                null;

        secondTapHit =
                null;
    }


    private void clearSelection() {
        selectedRoute = null;
        selectedHit = null;
        secondSelectedRoute = null;
        secondTapHit = null;

        refreshAction.refresh();
    }
}
