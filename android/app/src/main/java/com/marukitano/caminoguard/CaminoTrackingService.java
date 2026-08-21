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
        public final boolean stationary;
        public final String motionState;
        public final boolean gpsHasSpeed;
        public final float gpsSpeedMps;
        public final float gpsAccuracyM;
        public final float accelRms;
        public final long stationaryCandidateMs;

        Snapshot(
                Location location,
                List<Location> track,
                Float courseDeg,
                Float phoneHeadingDeg,
                boolean stationary,
                String motionState,
                boolean gpsHasSpeed,
                float gpsSpeedMps,
                float gpsAccuracyM,
                float accelRms,
                long stationaryCandidateMs
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
            this.stationary = stationary;
            this.motionState = motionState;
            this.gpsHasSpeed = gpsHasSpeed;
            this.gpsSpeedMps = gpsSpeedMps;
            this.gpsAccuracyM = gpsAccuracyM;
            this.accelRms = accelRms;
            this.stationaryCandidateMs = stationaryCandidateMs;
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
    private static final float COURSE_BASELINE_M = 10.0f;

    private static final float STATIONARY_RMS_THRESHOLD = 1.50f;
    private static final float MOVING_RMS_THRESHOLD = 1.80f;
    private static final long MOTION_STATE_CONFIRM_MS = 1500L;
    private static final CopyOnWriteArrayList<Listener> LISTENERS =
            new CopyOnWriteArrayList<>();

    private static volatile Snapshot latestSnapshot =
            new Snapshot(
                    null,
                    new ArrayList<>(),
                    null,
                    null,
                    false,
                    "UNKNOWN",
                    false,
                    0.0f,
                    -1.0f,
                    0.0f,
                    0L
            );

    private LocationManager locationManager;
    private SensorManager sensorManager;
    private Sensor gameRotationVector;
    private Sensor linearAcceleration;

    private final List<Location> track = new ArrayList<>();
    private final Deque<Location> courseHistory = new ArrayDeque<>();

    private Location acceptedLocation;
    private boolean latestGpsHasSpeed;
    private float latestGpsSpeedMps;
    private float latestGpsAccuracyM = -1.0f;
    private Location lastTrackLocation;

    private Float gpsCourseDeg;
    private Float phoneHeadingDeg;

    private Float rawCameraYawDeg;
    private Float gyroReferenceYawDeg;

    private MotionState motionState = MotionState.UNKNOWN;

    private long lastSensorPublishMs;
    private float stationaryRmsSq;
    private long stationaryCandidateSinceMs = -1L;
    private long movingCandidateSinceMs = -1L;

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
         * The first trustworthy fix gives us an initial map position.
         * After that, GPS is gated completely by the accelerometer state.
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

        /*
         * STATIONARY / UNKNOWN:
         * ignore this GPS packet completely.
         *
         * MOVING:
         * process the fix normally.
         *
         * GPS speed/distance no longer participates in motion-state
         * classification.
         */
        if (motionState != MotionState.MOVING) {
            return;
        }

        acceptMovingLocation(location);
        publish();
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
        }

        if (rawCameraYawDeg != null) {
            gyroReferenceYawDeg = rawCameraYawDeg;
        }

        if (gpsCourseDeg != null) {
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
            updateGyroAugmentedHeading();

            long now = SystemClock.elapsedRealtime();

            if (now - lastSensorPublishMs >= 50L) {
                lastSensorPublishMs = now;
                publish();
            }
        }
    }

    private void handleLinearAcceleration(
            SensorEvent event
    ) {
        float x = event.values[0];
        float y = event.values[1];
        float z = event.values[2];

        float magnitudeSq = x * x + y * y + z * z;

        /*
         * Thresholds measured during real use on this phone:
         *
         *   RMS < 1.50  -> standing / only moving phone in hand
         *   RMS > 1.80  -> walking
         *
         * The 1.50..1.80 gap is deliberate hysteresis.
         * Both transitions require 1.5 seconds beyond the threshold.
         */
        final float alpha = 0.04f;

        stationaryRmsSq =
                stationaryRmsSq == 0.0f
                        ? magnitudeSq
                        : (1.0f - alpha) * stationaryRmsSq
                                + alpha * magnitudeSq;

        float rms =
                (float) Math.sqrt(stationaryRmsSq);

        long now =
                SystemClock.elapsedRealtime();

        if (rms < STATIONARY_RMS_THRESHOLD) {
            movingCandidateSinceMs = -1L;

            if (stationaryCandidateSinceMs < 0L) {
                stationaryCandidateSinceMs = now;
            }

            if (motionState != MotionState.STATIONARY
                    && now - stationaryCandidateSinceMs
                            >= MOTION_STATE_CONFIRM_MS) {
                enterStationary();
            }

            return;
        }

        if (rms > MOVING_RMS_THRESHOLD) {
            stationaryCandidateSinceMs = -1L;

            if (movingCandidateSinceMs < 0L) {
                movingCandidateSinceMs = now;
            }

            if (motionState != MotionState.MOVING
                    && now - movingCandidateSinceMs
                            >= MOTION_STATE_CONFIRM_MS) {
                enterMoving();
            }

            return;
        }

        /*
         * Dead band 1.50..1.80:
         * keep the current state, but require a fresh confirmation once a
         * threshold is crossed again.
         */
        stationaryCandidateSinceMs = -1L;
        movingCandidateSinceMs = -1L;
    }

    private void enterMoving() {
        if (motionState == MotionState.MOVING) {
            return;
        }

        motionState = MotionState.MOVING;

        stationaryCandidateSinceMs = -1L;
        courseHistory.clear();

        if (acceptedLocation != null) {
            courseHistory.addLast(
                    new Location(acceptedLocation)
            );
        }

        publish();
    }

    private void enterStationary() {
        if (motionState == MotionState.STATIONARY) {
            return;
        }

        motionState = MotionState.STATIONARY;

        gyroReferenceYawDeg = rawCameraYawDeg;

        if (gpsCourseDeg != null) {
            phoneHeadingDeg = gpsCourseDeg;
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

    private void updateGyroAugmentedHeading() {
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



    private boolean isGoodGpsFix(
            Location location
    ) {
        return !location.hasAccuracy()
                || location.getAccuracy()
                        <= MAX_GPS_ACCURACY_M;
    }

    private void publish() {
        long stationaryCandidateMs =
                stationaryCandidateSinceMs < 0L
                        ? 0L
                        : Math.max(
                                0L,
                                SystemClock.elapsedRealtime()
                                        - stationaryCandidateSinceMs
                        );

        Snapshot snapshot =
                new Snapshot(
                        acceptedLocation,
                        track,
                        gpsCourseDeg,
                        phoneHeadingDeg,
                        motionState == MotionState.STATIONARY,
                        motionState.name(),
                        latestGpsHasSpeed,
                        latestGpsSpeedMps,
                        latestGpsAccuracyM,
                        (float) Math.sqrt(stationaryRmsSq),
                        stationaryCandidateMs
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
