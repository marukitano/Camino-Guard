package com.marukitano.caminoguard;

import android.hardware.SensorEvent;
import android.hardware.SensorManager;
import android.location.Location;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Owns walking-course and continuous GPS-anchored gyro-heading state.
 *
 * GPS acceptance, Android service lifecycle and motion-state classification
 * remain in CaminoTrackingService.
 */
final class CaminoDirectionTracker {

    private static final int MAX_COURSE_HISTORY = 80;

    private final float trackPointSpacingM;
    private final float courseBaselineM;

    private final Deque<Location> courseHistory =
            new ArrayDeque<>();

    private Float gpsCourseDeg;
    private Float phoneHeadingDeg;
    private Float rawCameraYawDeg;

    /*
     * Relative handset yaw versus walking direction.
     *
     * Gyro motion adds to this offset continuously. A real GPS course change
     * subtracts from it, so turning a corner while holding the phone normally
     * does not get counted twice.
     */
    private float phoneOffsetDeg;
    private Float lastAppliedRawYawDeg;

    CaminoDirectionTracker(
            float trackPointSpacingM,
            float courseBaselineM
    ) {
        this.trackPointSpacingM = trackPointSpacingM;
        this.courseBaselineM = courseBaselineM;
    }

    void acceptMovingLocation(Location location) {
        appendCoursePoint(location);

        Float course =
                calculateCourseOverLastDistance(
                        courseBaselineM
                );

        if (course == null) {
            return;
        }

        float normalizedCourse =
                GeoMath.normalizeDegrees(
                        course
                );

        if (gpsCourseDeg != null) {
            /*
             * A change in GPS course is a change in WALKING direction, not an
             * extra handset rotation. Remove it from the accumulated phone
             * offset. This is what lets gyro augmentation remain active while
             * walking without double-counting bends in the path.
             */
            float courseDelta =
                    GeoMath.shortestAngleDegrees(
                            gpsCourseDeg,
                            normalizedCourse
                    );

            phoneOffsetDeg =
                    signedDegrees(
                            phoneOffsetDeg
                                    - courseDelta
                    );
        }

        gpsCourseDeg =
                normalizedCourse;

        /*
         * Until the first GPS walking course exists, the current handset
         * orientation is implicitly the zero-offset / normal orientation.
         */
        if (lastAppliedRawYawDeg == null
                && rawCameraYawDeg != null) {

            lastAppliedRawYawDeg =
                    rawCameraYawDeg;
        }

        updateHeadingFromCurrentOffset();
    }

    void enterMoving(Location acceptedLocation) {
        courseHistory.clear();

        if (acceptedLocation != null) {
            courseHistory.addLast(
                    new Location(acceptedLocation)
            );
        }
    }

    void enterStationary() {
        /*
         * Do NOT recalibrate here.
         *
         * The same relative gyro offset continues through moving and
         * stationary states, so stopping no longer changes how the arrow
         * interprets handset rotation.
         */
        updateHeadingFromCurrentOffset();
    }

    void updateRawCameraYaw(SensorEvent event) {
        float[] rotation = new float[9];

        SensorManager.getRotationMatrixFromVector(
                rotation,
                event.values
        );

        float worldX = rotation[1];
        float worldY = rotation[4];

        if (Math.hypot(worldX, worldY) < 0.18) {
            return;
        }

        rawCameraYawDeg =
                GeoMath.normalizeDegrees(
                        (float) Math.toDegrees(
                                Math.atan2(
                                        worldX,
                                        worldY
                                )
                        )
                );
    }

    void updateGyroAugmentedHeading() {
        if (rawCameraYawDeg == null) {
            return;
        }

        if (gpsCourseDeg == null) {
            /*
             * No world reference yet. Remember the current sensor yaw so the
             * first valid GPS course starts with offset zero.
             */
            lastAppliedRawYawDeg =
                    rawCameraYawDeg;

            return;
        }

        if (lastAppliedRawYawDeg == null) {
            lastAppliedRawYawDeg =
                    rawCameraYawDeg;

            updateHeadingFromCurrentOffset();
            return;
        }

        float gyroDelta =
                GeoMath.shortestAngleDegrees(
                        lastAppliedRawYawDeg,
                        rawCameraYawDeg
                );

        lastAppliedRawYawDeg =
                rawCameraYawDeg;

        phoneOffsetDeg =
                signedDegrees(
                        phoneOffsetDeg
                                + gyroDelta
                );

        updateHeadingFromCurrentOffset();
    }

    private void updateHeadingFromCurrentOffset() {
        if (gpsCourseDeg == null) {
            return;
        }

        phoneHeadingDeg =
                GeoMath.normalizeDegrees(
                        gpsCourseDeg
                                + phoneOffsetDeg
                );
    }

    private float signedDegrees(
            float degrees
    ) {
        float normalized =
                GeoMath.normalizeDegrees(
                        degrees
                );

        return normalized > 180.0f
                ? normalized - 360.0f
                : normalized;
    }

    Float courseDeg() {
        return gpsCourseDeg;
    }

    Float phoneHeadingDeg() {
        return phoneHeadingDeg;
    }

    private void appendCoursePoint(Location location) {
        if (!courseHistory.isEmpty()) {
            Location newest = courseHistory.peekLast();

            if (newest.distanceTo(location)
                    < trackPointSpacingM) {
                return;
            }
        }

        courseHistory.addLast(
                new Location(location)
        );

        while (courseHistory.size() > MAX_COURSE_HISTORY) {
            courseHistory.removeFirst();
        }
    }

    private Float calculateCourseOverLastDistance(
            float targetDistanceM
    ) {
        if (courseHistory.size() < 2) {
            return null;
        }

        List<Location> points =
                new ArrayList<>(courseHistory);

        int newestIndex = points.size() - 1;
        Location newest = points.get(newestIndex);
        float walkedBackM = 0.0f;

        for (int index = newestIndex;
                index > 0;
                index--) {

            Location newer = points.get(index);
            Location older = points.get(index - 1);

            walkedBackM += older.distanceTo(newer);

            if (walkedBackM >= targetDistanceM) {
                return GeoMath.normalizeDegrees(
                        older.bearingTo(newest)
                );
            }
        }

        return null;
    }


}
