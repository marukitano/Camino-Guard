package com.marukitano.caminoguard;

import org.maplibre.android.camera.CameraPosition;
import org.maplibre.android.camera.CameraUpdateFactory;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.MapView;

final class NavigationController {

    enum Mode {
        MANUAL,
        NORTH_UP,
        COURSE_UP
    }

    interface PositionProvider {
        LatLng currentPosition();
    }

    interface BearingProvider {
        double currentBearingDegrees();
    }

    interface NavigationModeListener {
        void onNavigationModeChanged(
                Mode mode
        );
    }

    private final long recenterDelayMs =
            CaminoConfig.get().longValue(
                    "navigation.recenterDelayMs"
            );

    private final double verticalWindowM =
            CaminoConfig.get().doubleValue(
                    "navigation.verticalWindowMeters"
            );

    private final double cameraLeadM =
            CaminoConfig.get().doubleValue(
                    "navigation.cameraLeadMeters"
            );

    private final MapView mapView;
    private final PositionProvider positionProvider;
    private final BearingProvider bearingProvider;
    private final NavigationModeListener navigationModeListener;
    private final CaminoUiStateStore uiStateStore;

    private MapLibreMap map;
    private GpsGyroOrientationController externalController;

    private boolean liveMode;
    private boolean followEnabled;
    private boolean northUpFollow;
    private boolean followSuspended;
    private int resumeGeneration;

    NavigationController(
            MapView mapView,
            PositionProvider positionProvider,
            BearingProvider bearingProvider,
            NavigationModeListener navigationModeListener
    ) {
        this.mapView = mapView;
        this.positionProvider = positionProvider;
        this.bearingProvider = bearingProvider;
        this.navigationModeListener =
                navigationModeListener;

        uiStateStore =
                new CaminoUiStateStore(
                        mapView.getContext()
                );
    }

    void attachMap(
            MapLibreMap map
    ) {
        this.map = map;
    }

    void configureLiveMode(
            boolean enabled
    ) {
        liveMode = enabled;

        if (enabled) {
            /*
             * First installation defaults to centered/follow mode, matching
             * the old behaviour. Afterwards restore the user's last explicit
             * navigation mode.
             */
            followEnabled =
                    uiStateStore.restoreFollowEnabled(
                            true
                    );

            /*
             * Existing installs keep the old boolean preference. A stored
             * follow=true maps to the previous behaviour: course-up follow.
             */
            northUpFollow =
                    false;

            followSuspended = false;
            resumeGeneration++;

            publishNavigationMode();
            syncExternalFollow();
        }
    }

    void setExternalController(
            GpsGyroOrientationController controller
    ) {
        externalController = controller;
        syncExternalFollow();
    }

    void syncExternalFollow() {
        if (!liveMode
                || externalController == null) {
            return;
        }

        externalController
                .setExternalNavigationMode(
                        currentMode()
                );
    }

    boolean isFollowEnabled() {
        return followEnabled;
    }

    Mode currentMode() {
        if (!followEnabled) {
            return Mode.MANUAL;
        }

        return northUpFollow
                ? Mode.NORTH_UP
                : Mode.COURSE_UP;
    }

    void cycleMode() {
        resumeGeneration++;

        /*
         * The button shows the current state, and tapping advances:
         * MANUAL -> NORTH_UP -> COURSE_UP -> MANUAL.
         */
        if (!followEnabled) {
            followEnabled =
                    true;

            northUpFollow =
                    true;

        } else if (northUpFollow) {
            northUpFollow =
                    false;

        } else {
            followEnabled =
                    false;

            northUpFollow =
                    false;
        }

        followSuspended =
                false;

        /*
         * Keep the existing persisted manual/follow preference compatible.
         * Both NORTH_UP and COURSE_UP are follow=true.
         */
        uiStateStore.saveFollowEnabled(
                followEnabled
        );

        publishNavigationMode();

        if (liveMode
                && externalController != null) {

            externalController
                    .setExternalNavigationMode(
                            currentMode()
                    );

            return;
        }

        if (followEnabled) {
            followNow(
                    true
            );
        }
    }

    void handleCameraMoveStarted(
            int reason
    ) {
        if (!followEnabled
                || reason
                != MapLibreMap.OnCameraMoveStartedListener
                .REASON_API_GESTURE) {
            return;
        }

        followSuspended = true;
        resumeGeneration++;

        if (liveMode
                && externalController != null) {

            externalController
                    .setExternalNavigationSuspended(
                            true
                    );

            return;
        }

        final int generation =
                resumeGeneration;

        mapView.postDelayed(
                () -> {
                    if (!followEnabled
                            || generation
                            != resumeGeneration) {
                        return;
                    }

                    followSuspended = false;

                    followNow(
                            true
                    );
                },
                recenterDelayMs
        );
    }

    void handleCameraIdle() {
        if (!liveMode
                || !followEnabled
                || !followSuspended
                || externalController == null) {
            return;
        }

        final int generation =
                ++resumeGeneration;

        mapView.postDelayed(
                () -> {
                    if (!liveMode
                            || !followEnabled
                            || !followSuspended
                            || generation
                            != resumeGeneration
                            || externalController == null) {
                        return;
                    }

                    followSuspended = false;

                    externalController
                            .setExternalNavigationSuspended(
                                    false
                            );
                },
                recenterDelayMs
        );
    }

    void followIfActive(
            boolean animated
    ) {
        if (!followEnabled
                || followSuspended) {
            return;
        }

        followNow(
                animated
        );
    }

    private void followNow(
            boolean animated
    ) {
        LatLng position =
                positionProvider.currentPosition();

        if (map == null
                || position == null
                || mapView.getHeight() <= 0) {
            return;
        }

        double travelBearing =
                bearingProvider.currentBearingDegrees();

        double cameraBearing =
                northUpFollow
                        ? 0.0
                        : travelBearing;

        LatLng cameraTarget =
                GeoMath.destination(
                        position,
                        travelBearing,
                        cameraLeadM
                );

        double zoom =
                zoomForVerticalMeters(
                        position.getLatitude(),
                        verticalWindowM
                );

        CameraPosition cameraPosition =
                new CameraPosition.Builder(
                        map.getCameraPosition()
                )
                        .target(cameraTarget)
                        .zoom(zoom)
                        .bearing(cameraBearing)
                        .tilt(0.0)
                        .build();

        if (animated) {
            map.easeCamera(
                    CameraUpdateFactory.newCameraPosition(
                            cameraPosition
                    ),
                    550
            );
        } else {
            map.setCameraPosition(
                    cameraPosition
            );
        }
    }

    private double zoomForVerticalMeters(
            double latitude,
            double verticalMeters
    ) {
        double currentZoom =
                map.getCameraPosition().zoom;

        double currentMetersPerPixel =
                map.getProjection()
                        .getMetersPerPixelAtLatitude(
                                latitude
                        );

        double desiredMetersPerPixel =
                verticalMeters
                        / Math.max(
                                1.0,
                                mapView.getHeight()
                        );

        if (!Double.isFinite(currentMetersPerPixel)
                || currentMetersPerPixel <= 0.0
                || !Double.isFinite(desiredMetersPerPixel)
                || desiredMetersPerPixel <= 0.0) {

            return currentZoom;
        }

        double zoomDelta =
                Math.log(
                        currentMetersPerPixel
                                / desiredMetersPerPixel
                ) / Math.log(2.0);

        return currentZoom
                + zoomDelta;
    }


    private void publishNavigationMode() {
        if (navigationModeListener != null) {
            navigationModeListener
                    .onNavigationModeChanged(
                            currentMode()
                    );
        }
    }
}
