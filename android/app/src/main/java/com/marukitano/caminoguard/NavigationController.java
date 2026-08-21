package com.marukitano.caminoguard;

import org.maplibre.android.camera.CameraPosition;
import org.maplibre.android.camera.CameraUpdateFactory;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.MapView;

final class NavigationController {

    interface PositionProvider {
        LatLng currentPosition();
    }

    interface BearingProvider {
        double currentBearingDegrees();
    }

    interface FollowStateListener {
        void onFollowStateChanged(
                boolean enabled
        );
    }

    private static final double EARTH_RADIUS_M =
            6371008.8;

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
    private final FollowStateListener followStateListener;

    private MapLibreMap map;
    private GpsGyroOrientationController externalController;

    private boolean liveMode;
    private boolean followEnabled;
    private boolean followSuspended;
    private int resumeGeneration;

    NavigationController(
            MapView mapView,
            PositionProvider positionProvider,
            BearingProvider bearingProvider,
            FollowStateListener followStateListener
    ) {
        this.mapView = mapView;
        this.positionProvider = positionProvider;
        this.bearingProvider = bearingProvider;
        this.followStateListener = followStateListener;
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
            followEnabled = true;
            followSuspended = false;
            resumeGeneration++;

            publishFollowState();
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
                .setExternalNavigationFollowEnabled(
                        followEnabled
                );
    }

    boolean isFollowEnabled() {
        return followEnabled;
    }

    void toggleFollow() {
        resumeGeneration++;

        followEnabled =
                !followEnabled;

        followSuspended = false;

        publishFollowState();

        if (liveMode
                && externalController != null) {

            externalController
                    .setExternalNavigationFollowEnabled(
                            followEnabled
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

        double bearing =
                bearingProvider.currentBearingDegrees();

        LatLng cameraTarget =
                destination(
                        position,
                        bearing,
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
                        .bearing(bearing)
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

    private static LatLng destination(
            LatLng from,
            double bearingDegrees,
            double meters
    ) {
        double angularDistance =
                meters / EARTH_RADIUS_M;

        double bearing =
                Math.toRadians(
                        bearingDegrees
                );

        double lat1 =
                Math.toRadians(
                        from.getLatitude()
                );

        double lon1 =
                Math.toRadians(
                        from.getLongitude()
                );

        double lat2 =
                Math.asin(
                        Math.sin(lat1)
                                * Math.cos(angularDistance)
                                + Math.cos(lat1)
                                * Math.sin(angularDistance)
                                * Math.cos(bearing)
                );

        double lon2 =
                lon1
                        + Math.atan2(
                        Math.sin(bearing)
                                * Math.sin(angularDistance)
                                * Math.cos(lat1),
                        Math.cos(angularDistance)
                                - Math.sin(lat1)
                                * Math.sin(lat2)
                );

        return new LatLng(
                Math.toDegrees(lat2),
                Math.toDegrees(lon2)
        );
    }

    private void publishFollowState() {
        if (followStateListener != null) {
            followStateListener
                    .onFollowStateChanged(
                            followEnabled
                    );
        }
    }
}
