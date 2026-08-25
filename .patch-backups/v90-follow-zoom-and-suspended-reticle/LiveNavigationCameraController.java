package com.marukitano.caminoguard;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.os.SystemClock;

import org.maplibre.android.camera.CameraPosition;
import org.maplibre.android.camera.CameraUpdateFactory;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.maps.MapLibreMap;

final class LiveNavigationCameraController {

    private static final int RETURN_MS = 1650;
    private static final double BEARING_TAU_MS = 2200.0;
    private static final double BEARING_DEADBAND_DEG = 1.25;

    private final Activity activity;

    private MapLibreMap map;
    private LatLng lastPose;
    private Float courseDeg;

    private boolean followEnabled;
    private boolean northUp;
    private boolean suspended;
    private boolean returnAnimating;
    private ValueAnimator returnAnimator;

    private Double smoothedCameraBearingDeg;
    private long smoothedCameraBearingTimeMs;

    LiveNavigationCameraController(Activity activity) {
        this.activity = activity;
    }

    void attachMap(MapLibreMap map) {
        this.map = map;
    }

    void setCourseDeg(Float courseDeg) {
        this.courseDeg = courseDeg;
    }

    void onPose(LatLng pos) {
        lastPose = pos;

        if (followEnabled && !suspended) {
            renderCamera(pos);
        }
    }

    void setNavigationMode(
            NavigationController.Mode mode
    ) {
        if (mode == null) {
            mode =
                    NavigationController.Mode.MANUAL;
        }

        boolean enabled =
                mode
                        != NavigationController.Mode.MANUAL;

        northUp =
                mode
                        == NavigationController.Mode.NORTH_UP;

        followEnabled =
                enabled;

        suspended = false;
        returnAnimating = false;

        if (returnAnimator != null) {
            returnAnimator.cancel();
            returnAnimator = null;
        }

        if (enabled && map != null) {
            smoothedCameraBearingDeg =
                    GeoMath.normalizeDegrees(
                            map.getCameraPosition().bearing
                    );

            smoothedCameraBearingTimeMs =
                    SystemClock.elapsedRealtime();
        }

        if (!enabled) {
            return;
        }

        renderFromLastPose();
    }

    void setSuspended(boolean suspended) {
        if (!followEnabled) {
            return;
        }

        this.suspended = suspended;

        if (suspended && returnAnimator != null) {
            returnAnimator.cancel();
            returnAnimator = null;
            returnAnimating = false;
        }

        if (suspended) {
            return;
        }

        if (map != null) {
            smoothedCameraBearingDeg =
                    GeoMath.normalizeDegrees(map.getCameraPosition().bearing);
            smoothedCameraBearingTimeMs =
                    SystemClock.elapsedRealtime();
        }

        easeFromLastPose();
    }

    private void easeFromLastPose() {
        org.maplibre.android.maps.MapView mv = mapView();

        if (map == null
                || mv == null
                || mv.getHeight() <= 0
                || lastPose == null
                || suspended
                || !followEnabled) {
            return;
        }

        if (returnAnimator != null) {
            returnAnimator.cancel();
            returnAnimator = null;
        }

        final CameraPosition startCamera =
                map.getCameraPosition();

        final double finalZoom =
                startCamera.zoom;

        final double cameraBearing =
                northUp
                        ? 0.0
                        : GeoMath.normalizeDegrees(
                                startCamera.bearing
                        );

        smoothedCameraBearingDeg =
                cameraBearing;
        smoothedCameraBearingTimeMs =
                SystemClock.elapsedRealtime();

        double metersPerPixel =
                map.getProjection()
                        .getMetersPerPixelAtLatitude(
                                lastPose.getLatitude());

        double pixelRatio =
                Math.max(
                        1.0,
                        activity.getResources()
                                .getDisplayMetrics()
                                .density);

        double logicalMapHeightPx =
                mv.getHeight() / pixelRatio;

        /*
         * The GPS arrow is the camera pivot.
         * Rotating COURSE_UP must rotate around the walker, not around an
         * artificial look-ahead point near the screen centre.
         */
        final LatLng finalTarget =
                lastPose;

        final LatLng startTarget =
                startCamera.target;

        double distanceMeters =
                GeoMath.distanceMeters(
                        startTarget,
                        finalTarget
                );

        double visibleHalfHeightMeters =
                Math.max(
                        1.0,
                        metersPerPixel
                                * logicalMapHeightPx
                                / 2.0);

        double distanceRatio =
                distanceMeters
                        / visibleHalfHeightMeters;

        final double zoomOutLevels =
                distanceRatio <= 0.75
                        ? 0.50
                        : Math.min(
                                2.50,
                                Math.max(
                                        0.65,
                                        Math.log(1.0 + distanceRatio)
                                                / Math.log(2.0)
                                )
                        );

        final double longitudeDelta =
                ((finalTarget.getLongitude()
                        - startTarget.getLongitude()
                        + 540.0)
                        % 360.0)
                        - 180.0;

        returnAnimating = true;

        ValueAnimator animator =
                ValueAnimator.ofFloat(
                        0f,
                        1f);

        returnAnimator =
                animator;

        animator.setDuration(
                RETURN_MS);

        animator.setInterpolator(
                new android.view.animation.LinearInterpolator());

        animator.addUpdateListener(
                valueAnimator -> {
                    if (suspended || !followEnabled) {
                        return;
                    }

                    double t =
                            (float) valueAnimator.getAnimatedValue();

                    double s =
                            t * t * (3.0 - 2.0 * t);

                    double lat =
                            startTarget.getLatitude()
                                    + (finalTarget.getLatitude()
                                    - startTarget.getLatitude()) * s;

                    double lon =
                            startTarget.getLongitude()
                                    + longitudeDelta * s;

                    if (lon > 180.0) {
                        lon -= 360.0;
                    }

                    if (lon < -180.0) {
                        lon += 360.0;
                    }

                    double zoomPulse =
                            Math.sin(Math.PI * s);

                    double zoom =
                            finalZoom
                                    - zoomOutLevels * zoomPulse;

                    CameraPosition camera =
                            new CameraPosition.Builder(
                                    map.getCameraPosition())
                                    .target(
                                            new LatLng(
                                                    lat,
                                                    lon))
                                    .zoom(zoom)
                                    .bearing(cameraBearing)
                                    .tilt(0.0)
                                    .padding(
                                            0.0,
                                            0.0,
                                            0.0,
                                            0.0)
                                    .build();

                    map.moveCamera(
                            CameraUpdateFactory
                                    .newCameraPosition(
                                            camera));
                });

        animator.addListener(
                new android.animation.AnimatorListenerAdapter() {
                    private boolean cancelled;

                    @Override
                    public void onAnimationCancel(
                            android.animation.Animator animation
                    ) {
                        cancelled = true;
                        returnAnimating = false;

                        if (returnAnimator == animation) {
                            returnAnimator = null;
                        }
                    }

                    @Override
                    public void onAnimationEnd(
                            android.animation.Animator animation
                    ) {
                        returnAnimating = false;

                        if (returnAnimator == animation) {
                            returnAnimator = null;
                        }

                        if (cancelled
                                || suspended
                                || !followEnabled) {
                            return;
                        }

                        renderFromLastPose();
                    }
                });

        animator.start();
    }

    private void renderFromLastPose() {
        if (lastPose == null) {
            return;
        }

        renderCamera(lastPose);
    }

    private void renderCamera(LatLng pos) {
        org.maplibre.android.maps.MapView mv = mapView();

        if (map == null
                || mv == null
                || mv.getHeight() <= 0
                || suspended
                || returnAnimating
                || !followEnabled) {
            return;
        }

        /*
         * Follow NEVER owns zoom.
         */
        double zoom =
                map.getCameraPosition().zoom;

        /*
         * Rotate the whole map ONLY from the GPS walking course.
         * Phone/gyro orientation may still animate the arrow while stationary,
         * but it can never bounce the map.
         */
        Double desiredCourse =
                courseDeg != null
                        ? courseDeg.doubleValue()
                        : null;

        double cameraBearing =
                northUp
                        ? 0.0
                        : desiredCourse != null
                                ? smoothCameraBearing(
                                        desiredCourse
                                )
                                : smoothedCameraBearingDeg != null
                                        ? smoothedCameraBearingDeg
                                        : GeoMath.normalizeDegrees(
                                                map.getCameraPosition().bearing
                                        );

        /*
         * Zero padding means the camera target is the physical screen centre.
         * Because target == GPS pose, the direction arrow is the rotation pivot.
         */
        /*
         * Keep the current GPS pose exactly on the camera target. This is what
         * makes COURSE_UP rotate around the visible direction arrow.
         *
         * Zoom remains user-owned for now; v89 only displays it for tuning.
         */
        LatLng target =
                pos;

        CameraPosition camera =
                new CameraPosition.Builder(
                        map.getCameraPosition())
                        .target(target)
                        .zoom(zoom)
                        .bearing(cameraBearing)
                        .tilt(0.0)
                        .padding(
                                0.0,
                                0.0,
                                0.0,
                                0.0)
                        .build();

        map.moveCamera(
                CameraUpdateFactory
                        .newCameraPosition(
                                camera));
    }

    private double smoothCameraBearing(double targetBearing) {
        double target =
                GeoMath.normalizeDegrees(targetBearing);

        long now =
                SystemClock.elapsedRealtime();

        if (smoothedCameraBearingDeg == null) {
            smoothedCameraBearingDeg =
                    map != null
                            ? GeoMath.normalizeDegrees(map.getCameraPosition().bearing)
                            : target;
            smoothedCameraBearingTimeMs =
                    now;
            return smoothedCameraBearingDeg;
        }

        long dtMs =
                Math.max(
                        0L,
                        Math.min(
                                100L,
                                now - smoothedCameraBearingTimeMs));

        smoothedCameraBearingTimeMs =
                now;

        double current =
                smoothedCameraBearingDeg;

        double delta =
                ((target - current + 540.0) % 360.0) - 180.0;

        /*
         * Suppress small course noise completely.
         */
        if (Math.abs(delta) <= BEARING_DEADBAND_DEG) {
            return current;
        }

        double alpha =
                1.0
                        - Math.exp(
                                -dtMs / BEARING_TAU_MS);

        smoothedCameraBearingDeg =
                GeoMath.normalizeDegrees(
                        current + delta * alpha);

        return smoothedCameraBearingDeg;
    }

    private org.maplibre.android.maps.MapView mapView() {
        return activity.findViewById(
                R.id.map_view);
    }


}
