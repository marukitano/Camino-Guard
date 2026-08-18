package com.marukitano.caminoguard;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Camino Guard tracking service.
 *
 * Direction logic:
 *
 * MOVING:
 *   - GPS track is authoritative.
 *   - map course + arrow heading come from the last ~15 m of accepted path.
 *   - gyro rotation is ignored.
 *
 * STATIONARY:
 *   - GPS position/course are frozen.
 *   - gyro may rotate the arrow relative to the last walking course.
 *
 * No magnetometer is used.
 */
public final class CaminoTrackingService extends Service
        implements LocationListener, SensorEventListener {

    public interface Listener {
        void onTrackingStateChanged(Snapshot snapshot);
    }

    public static final class Snapshot {
        public final Location location;
        public final List<Location> track;
        public final Float courseDeg;
        public final Float phoneHeadingDeg;

        Snapshot(
                Location location,
                List<Location> track,
                Float courseDeg,
                Float phoneHeadingDeg
        ) {
            this.location =
                    location == null
                            ? null
                            : new Location(location);

            this.track = new ArrayList<>();
            for (Location point : track) {
                this.track.add(new Location(point));
            }

            this.courseDeg = courseDeg;
            this.phoneHeadingDeg = phoneHeadingDeg;
        }
    }

    private enum MotionState {
        UNKNOWN,
        MOVING,
        STATIONARY
    }

    private static final String CHANNEL = "camino_tracking";
    private static final int NOTIFICATION = 3601;

    private static final float MAX_GPS_ACCURACY_M = 25.0f;
    private static final float TRACK_POINT_SPACING_M = 1.5f;

    /*
     * Fixed spatial direction window.
     * The heading is the continuation of roughly the last 15 m walked.
     */
    private static final float COURSE_BASELINE_M = 15.0f;

    /*
     * Linear-acceleration state machine.
     *
     * Walking gives repeated impulses; standing still gives only sensor noise.
     * A single phone movement must not immediately count as walking.
     */
    private static final float MOTION_ACCEL_THRESHOLD = 0.45f;
    private static final long MOTION_START_CONFIRM_MS = 350L;
    private static final long MOTION_PULSE_GAP_MS = 550L;
    private static final long STATIONARY_AFTER_QUIET_MS = 2500L;

    /*
     * GPS speed is only a backup movement signal.
     * 0.55 m/s is about 2 km/h. Require two consecutive good-speed fixes.
     */
    private static final float GPS_MOVING_SPEED_MPS = 0.55f;
    private static final int GPS_MOVING_FIXES_REQUIRED = 2;
    private static final float GPS_ESCAPE_DISTANCE_M = 25.0f;

    private static final CopyOnWriteArrayList<Listener> LISTENERS =
            new CopyOnWriteArrayList<>();

    private static volatile Snapshot latestSnapshot =
            new Snapshot(
                    null,
                    new ArrayList<>(),
                    null,
                    null
            );

    private LocationManager locationManager;
    private SensorManager sensorManager;
    private Sensor gameRotationVector;
    private Sensor linearAcceleration;

    private final List<Location> track = new ArrayList<>();
    private final Deque<Location> courseHistory = new ArrayDeque<>();

    private Location acceptedLocation;
    private Location lastTrackLocation;

    private Float gpsCourseDeg;
    private Float phoneHeadingDeg;

    private Float rawCameraYawDeg;
    private Float stationaryRefYawDeg;
    private Float stationaryRefHeadingDeg;

    private MotionState motionState = MotionState.UNKNOWN;

    private long motionObservationStartedMs;
    private long motionBurstStartedMs = -1L;
    private long lastStrongMotionMs = -1L;

    private int consecutiveGpsMovingFixes;

    private long lastSensorPublishMs;

    public static void addListener(Listener listener) {
        if (listener == null) {
            return;
        }

        LISTENERS.addIfAbsent(listener);
        listener.onTrackingStateChanged(latestSnapshot);
    }

    public static void removeListener(Listener listener) {
        LISTENERS.remove(listener);
    }

    public static Snapshot snapshot() {
        return latestSnapshot;
    }

    public static void start(Activity activity) {
        if (activity.checkSelfPermission(
                Manifest.permission.ACCESS_FINE_LOCATION
        ) != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        Intent intent =
                new Intent(
                        activity,
                        CaminoTrackingService.class
                );

        if (Build.VERSION.SDK_INT
                >= Build.VERSION_CODES.O) {
            activity.startForegroundService(intent);
        } else {
            activity.startService(intent);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();

        motionObservationStartedMs =
                SystemClock.elapsedRealtime();

        createNotificationChannel();

        Notification notification =
                new Notification.Builder(this, CHANNEL)
                        .setContentTitle("Camino Guard")
                        .setContentText("GPS-Track läuft")
                        .setSmallIcon(
                                android.R.drawable.ic_menu_mylocation
                        )
                        .setOngoing(true)
                        .build();

        if (Build.VERSION.SDK_INT
                >= Build.VERSION_CODES.Q) {
            startForeground(
                    NOTIFICATION,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            );
        } else {
            startForeground(
                    NOTIFICATION,
                    notification
            );
        }

        locationManager =
                (LocationManager)
                        getSystemService(
                                Context.LOCATION_SERVICE
                        );

        sensorManager =
                (SensorManager)
                        getSystemService(
                                Context.SENSOR_SERVICE
                        );

        gameRotationVector =
                sensorManager.getDefaultSensor(
                        Sensor.TYPE_GAME_ROTATION_VECTOR
                );

        linearAcceleration =
                sensorManager.getDefaultSensor(
                        Sensor.TYPE_LINEAR_ACCELERATION
                );

        if (gameRotationVector != null) {
            sensorManager.registerListener(
                    this,
                    gameRotationVector,
                    SensorManager.SENSOR_DELAY_GAME
            );
        }

        if (linearAcceleration != null) {
            sensorManager.registerListener(
                    this,
                    linearAcceleration,
                    SensorManager.SENSOR_DELAY_GAME
            );
        }

        if (checkSelfPermission(
                Manifest.permission.ACCESS_FINE_LOCATION
        ) != PackageManager.PERMISSION_GRANTED) {
            stopSelf();
            return;
        }

        try {
            locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1000L,
                    1.0f,
                    this
            );
        } catch (SecurityException error) {
            stopSelf();
        }
    }

    @Override
    public int onStartCommand(
            Intent intent,
            int flags,
            int startId
    ) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }

        if (locationManager != null) {
            try {
                locationManager.removeUpdates(this);
            } catch (SecurityException ignored) {
            }
        }

        super.onDestroy();
    }

    @Override
    public void onLocationChanged(Location location) {
        if (!isGoodGpsFix(location)) {
            return;
        }

        /*
         * First trustworthy fix is always shown, even before the movement
         * classifier has decided MOVING/STATIONARY.
         */
        if (acceptedLocation == null) {
            acceptedLocation =
                    new Location(location);

            track.add(
                    new Location(location)
            );

            lastTrackLocation =
                    new Location(location);

            publish();
            return;
        }

        updateGpsMovementEvidence(location);

        if (motionState != MotionState.MOVING) {
            /*
             * This is the actual jitter filter:
             * no map point, no red-track point and no course update while
             * stationary/unknown.
             */
            return;
        }

        acceptMovingLocation(location);
        publish();
    }

    private void updateGpsMovementEvidence(
            Location location
    ) {
        boolean fastEnough =
                location.hasSpeed()
                        && location.getSpeed()
                                >= GPS_MOVING_SPEED_MPS;

        if (fastEnough) {
            consecutiveGpsMovingFixes++;
        } else {
            consecutiveGpsMovingFixes = 0;
        }

        if (motionState != MotionState.MOVING
                && consecutiveGpsMovingFixes
                        >= GPS_MOVING_FIXES_REQUIRED) {
            enterMoving();
            return;
        }

        /*
         * Last-resort escape hatch if a phone exposes a poor acceleration
         * sensor. Ordinary stationary GPS jitter should not reach 25 m.
         */
        if (motionState != MotionState.MOVING
                && acceptedLocation.distanceTo(location)
                        >= GPS_ESCAPE_DISTANCE_M) {
            enterMoving();
        }
    }

    private void acceptMovingLocation(
            Location location
    ) {
        acceptedLocation =
                new Location(location);

        if (lastTrackLocation == null
                || lastTrackLocation.distanceTo(location)
                        >= TRACK_POINT_SPACING_M) {
            track.add(
                    new Location(location)
            );

            lastTrackLocation =
                    new Location(location);
        }

        appendCoursePoint(location);

        Float course =
                calculateCourseOverLastDistance(
                        COURSE_BASELINE_M
                );

        if (course != null) {
            gpsCourseDeg = course;

            /*
             * While moving there is deliberately ZERO gyro contribution.
             * Arrow and map use the same 15 m walking course.
             */
            phoneHeadingDeg = gpsCourseDeg;
        }
    }

    private void appendCoursePoint(Location location) {
        if (!courseHistory.isEmpty()) {
            Location newest =
                    courseHistory.peekLast();

            if (newest.distanceTo(location)
                    < TRACK_POINT_SPACING_M) {
                return;
            }
        }

        courseHistory.addLast(
                new Location(location)
        );

        /*
         * Bounded history. At walking GPS rates this is far more than the
         * 15 m required while keeping memory predictable.
         */
        while (courseHistory.size() > 80) {
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

        int newestIndex =
                points.size() - 1;

        Location newest =
                points.get(newestIndex);

        float walkedBackM = 0.0f;

        for (int index = newestIndex;
                index > 0;
                index--) {
            Location newer =
                    points.get(index);

            Location older =
                    points.get(index - 1);

            walkedBackM +=
                    older.distanceTo(newer);

            if (walkedBackM
                    >= targetDistanceM) {
                return normalizeDegrees(
                        older.bearingTo(newest)
                );
            }
        }

        return null;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType()
                == Sensor.TYPE_LINEAR_ACCELERATION) {
            handleLinearAcceleration(event);
            return;
        }

        if (event.sensor.getType()
                != Sensor.TYPE_GAME_ROTATION_VECTOR) {
            return;
        }

        updateRawCameraYaw(event);

        if (motionState == MotionState.STATIONARY) {
            updateStationaryArrow();
        } else if (motionState == MotionState.MOVING) {
            /*
             * Explicitly ignore phone rotation while walking.
             */
            if (gpsCourseDeg != null) {
                phoneHeadingDeg =
                        gpsCourseDeg;
            }
        }

        long now =
                SystemClock.elapsedRealtime();

        if (now - lastSensorPublishMs
                >= 50L) {
            lastSensorPublishMs = now;
            publish();
        }
    }

    private void handleLinearAcceleration(
            SensorEvent event
    ) {
        float x = event.values[0];
        float y = event.values[1];
        float z = event.values[2];

        float magnitude =
                (float) Math.sqrt(
                        x * x
                                + y * y
                                + z * z
                );

        long now =
                SystemClock.elapsedRealtime();

        if (magnitude
                >= MOTION_ACCEL_THRESHOLD) {
            if (motionBurstStartedMs < 0L
                    || lastStrongMotionMs < 0L
                    || now - lastStrongMotionMs
                            > MOTION_PULSE_GAP_MS) {
                motionBurstStartedMs = now;
            }

            lastStrongMotionMs = now;

            if (motionState
                    != MotionState.MOVING
                    && now - motionBurstStartedMs
                            >= MOTION_START_CONFIRM_MS) {
                enterMoving();
            }

            return;
        }

        /*
         * No meaningful translation acceleration.
         * After 2.5 seconds of quiet, standing still is authoritative.
         *
         * Crucially, GPS jitter cannot keep us in MOVING state.
         */
        long quietSince =
                lastStrongMotionMs >= 0L
                        ? lastStrongMotionMs
                        : motionObservationStartedMs;

        if (now - quietSince
                >= STATIONARY_AFTER_QUIET_MS) {
            enterStationary();
        }
    }

    private void enterMoving() {
        if (motionState == MotionState.MOVING) {
            return;
        }

        motionState = MotionState.MOVING;

        consecutiveGpsMovingFixes = 0;

        stationaryRefYawDeg = null;
        stationaryRefHeadingDeg = null;

        /*
         * A new walking episode gets a fresh 15 m window.
         * Seed it from the exact frozen stop point. After roughly 15 m in the
         * new direction the new course becomes authoritative.
         */
        courseHistory.clear();

        if (acceptedLocation != null) {
            courseHistory.addLast(
                    new Location(acceptedLocation)
            );
        }

        if (gpsCourseDeg != null) {
            phoneHeadingDeg =
                    gpsCourseDeg;
        }

        publish();
    }

    private void enterStationary() {
        if (motionState == MotionState.STATIONARY) {
            return;
        }

        motionState = MotionState.STATIONARY;

        consecutiveGpsMovingFixes = 0;
        motionBurstStartedMs = -1L;

        /*
         * Establish a relative gyro zero exactly when the phone/person becomes
         * stationary. The last GPS walking course remains the absolute base.
         */
        stationaryRefYawDeg =
                rawCameraYawDeg;

        if (gpsCourseDeg != null) {
            stationaryRefHeadingDeg =
                    gpsCourseDeg;

            phoneHeadingDeg =
                    gpsCourseDeg;
        } else {
            stationaryRefHeadingDeg =
                    phoneHeadingDeg;
        }

        publish();
    }

    private void updateRawCameraYaw(
            SensorEvent event
    ) {
        float[] rotation =
                new float[9];

        SensorManager.getRotationMatrixFromVector(
                rotation,
                event.values
        );

        /*
         * Physical device +Y = camera/top edge.
         * No landscape/display remapping.
         */
        float worldX =
                rotation[1];

        float worldY =
                rotation[4];

        if (Math.hypot(worldX, worldY)
                < 0.18) {
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

    private void updateStationaryArrow() {
        if (rawCameraYawDeg == null) {
            return;
        }

        if (stationaryRefYawDeg == null) {
            stationaryRefYawDeg =
                    rawCameraYawDeg;

            if (gpsCourseDeg != null) {
                stationaryRefHeadingDeg =
                        gpsCourseDeg;
            } else {
                stationaryRefHeadingDeg =
                        phoneHeadingDeg;
            }
        }

        if (stationaryRefHeadingDeg
                == null) {
            return;
        }

        phoneHeadingDeg =
                normalizeDegrees(
                        stationaryRefHeadingDeg
                                + shortestAngleDegrees(
                                        stationaryRefYawDeg,
                                        rawCameraYawDeg
                                )
                );
    }

    private boolean isGoodGpsFix(
            Location location
    ) {
        return !location.hasAccuracy()
                || location.getAccuracy()
                        <= MAX_GPS_ACCURACY_M;
    }

    private void publish() {
        Snapshot snapshot =
                new Snapshot(
                        acceptedLocation,
                        track,
                        gpsCourseDeg,
                        phoneHeadingDeg
                );

        latestSnapshot = snapshot;

        for (Listener listener : LISTENERS) {
            listener.onTrackingStateChanged(
                    snapshot
            );
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT
                < Build.VERSION_CODES.O) {
            return;
        }

        NotificationChannel channel =
                new NotificationChannel(
                        CHANNEL,
                        "Camino tracking",
                        NotificationManager.IMPORTANCE_LOW
                );

        channel.setDescription(
                "Camino Guard GPS tracking"
        );

        NotificationManager manager =
                (NotificationManager)
                        getSystemService(
                                Context.NOTIFICATION_SERVICE
                        );

        manager.createNotificationChannel(
                channel
        );
    }

    private static float normalizeDegrees(
            float value
    ) {
        float normalized =
                value % 360.0f;

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

    @Override
    public void onAccuracyChanged(
            Sensor sensor,
            int accuracy
    ) {
    }

    @Override
    public void onProviderEnabled(
            String provider
    ) {
    }

    @Override
    public void onProviderDisabled(
            String provider
    ) {
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onStatusChanged(
            String provider,
            int status,
            Bundle extras
    ) {
    }
}
