package com.marukitano.caminoguard;

import android.hardware.SensorEvent;
import android.hardware.SensorManager;
import android.location.Location;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Owns walking-course and stationary gyro-heading state.
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
    private Float gyroReferenceYawDeg;

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

        if (course != null) {
            gpsCourseDeg = course;
        }

        if (rawCameraYawDeg != null) {
            gyroReferenceYawDeg = rawCameraYawDeg;
        }

        if (gpsCourseDeg != null) {
            phoneHeadingDeg = gpsCourseDeg;
        }
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
        gyroReferenceYawDeg = rawCameraYawDeg;

        if (gpsCourseDeg != null) {
            phoneHeadingDeg = gpsCourseDeg;
        }
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
                normalizeDegrees(
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

        if (gyroReferenceYawDeg == null) {
            gyroReferenceYawDeg = rawCameraYawDeg;
        }

        Float baseHeading =
                gpsCourseDeg != null
                        ? gpsCourseDeg
                        : phoneHeadingDeg;

        if (baseHeading == null) {
            return;
        }

        float offset =
                shortestAngleDegrees(
                        gyroReferenceYawDeg,
                        rawCameraYawDeg
                );

        phoneHeadingDeg =
                normalizeDegrees(
                        baseHeading + offset
                );
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
                return normalizeDegrees(
                        older.bearingTo(newest)
                );
            }
        }

        return null;
    }

    private static float normalizeDegrees(float value) {
        float normalized = value % 360.0f;

        if (normalized < 0.0f) {
            normalized += 360.0f;
        }

        return normalized;
    }

    private static float shortestAngleDegrees(
            float from,
            float to
    ) {
        float delta =
                normalizeDegrees(
                        to - from
                );

        if (delta > 180.0f) {
            delta -= 360.0f;
        }

        return delta;
    }
}
