package com.marukitano.caminoguard;

import org.maplibre.android.camera.CameraPosition;
import org.maplibre.android.camera.CameraUpdateFactory;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.MapView;

final class NavigationController {

    private static final double FOLLOW_ZOOM =
            16.5;

    private static final long ROTATION_RESET_COOLDOWN_MS =
            10_000L;

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
                Mode mode,
                boolean suspended
        );
    }


    interface RotationResetHaloListener {
        void onRotationResetHaloChanged(
                boolean visible
        );
    }

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

    private RotationResetHaloListener rotationResetHaloListener;
    private boolean directionReady;
    private boolean rotationResetHalo;

    private final Runnable restoreRotationResetHalo =
            () -> {
                if (!directionReady) {
                    return;
                }

                rotationResetHalo =
                        true;

                publishRotationResetHalo();
            };

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

            publishNavigationMode();
            syncExternalFollow();
        }
    }

    void setExternalController(
            GpsGyroOrientationController controller
    ) {
        externalController = controller;

        if (externalController != null) {
            externalController
                    .setForegroundDirectionReadyListener(
                            this::onForegroundDirectionReady
                    );
        }

        syncExternalFollow();
    }

    void setRotationResetHaloListener(
            RotationResetHaloListener listener
    ) {
        rotationResetHaloListener =
                listener;

        publishRotationResetHalo();
    }


    private void onForegroundDirectionReady() {
        directionReady =
                true;

        rotationResetHalo =
                true;

        mapView.removeCallbacks(
                restoreRotationResetHalo
        );

        publishRotationResetHalo();
    }


    private boolean rotationResetHaloVisible() {
        return rotationResetHalo
                && !followSuspended;
    }


    private void publishRotationResetHalo() {
        if (rotationResetHaloListener != null) {
            rotationResetHaloListener
                    .onRotationResetHaloChanged(
                            rotationResetHaloVisible()
                    );
        }
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

        externalController
                .setExternalNavigationSuspended(
                        followSuspended
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

        /*
         * When the yellow halo is visible this click is exclusively a
         * foreground-rotation reset. Navigation mode remains untouched.
         */
        if (rotationResetHaloVisible()
                && liveMode
                && externalController != null) {

            externalController
                    .resetForegroundRotation();

            rotationResetHalo =
                    false;

            publishRotationResetHalo();

            mapView.removeCallbacks(
                    restoreRotationResetHalo
            );

            mapView.postDelayed(
                    restoreRotationResetHalo,
                    ROTATION_RESET_COOLDOWN_MS
            );

            return;
        }

        /*
         * A manually moved follow map is PARKED, not in another navigation
         * mode. Tapping the reticle resumes exactly the previous mode.
         */
        if (followEnabled
                && followSuspended) {

            followSuspended =
                    false;

            publishNavigationMode();
            publishRotationResetHalo();

            if (liveMode
                    && externalController != null) {

                externalController
                        .setExternalNavigationSuspended(
                                false
                        );

                return;
            }

            followNow(
                    true
            );

            return;
        }

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

            /*
             * The external controller owns continuous live-camera updates.
             * The button press itself must still use NavigationController's
             * current position to restore centre and walking zoom immediately.
             */
            externalController
                    .setExternalNavigationMode(
                            currentMode()
                    );
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

        followSuspended =
                true;

        publishNavigationMode();
        publishRotationResetHalo();

        if (liveMode
                && externalController != null) {

            externalController
                    .setExternalNavigationSuspended(
                            true
                    );
        }

        /*
         * Deliberately NO delayed resume:
         * the user may inspect or zoom another part of the map for as long as
         * desired. The reticle button is the explicit way back.
         */
    }

    void handleCameraIdle() {
        /*
         * Intentionally empty.
         *
         * Follow suspension is sticky until the user taps the reticle.
         * Keeping this policy here preserves NavigationController ownership of
         * gesture/follow state rather than leaking it into the map UI.
         */
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

        /*
         * Follow pivots around the physical position itself.
         * This keeps the user arrow fixed while COURSE_UP rotates the map.
         */
        LatLng cameraTarget =
                position;

        double zoom =
                FOLLOW_ZOOM;

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




    private void publishNavigationMode() {
        if (navigationModeListener != null) {
            navigationModeListener
                    .onNavigationModeChanged(
                            currentMode(),
                            followSuspended
                    );
        }
    }
}
