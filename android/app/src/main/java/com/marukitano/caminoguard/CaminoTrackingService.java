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
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import org.maplibre.android.geometry.LatLng;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Camino Guard tracking service.
 *
 * Direction logic:
 *
 * MOVING:
 *   - GPS track/course are authoritative for walking direction.
 *   - relative handset yaw from TYPE_GAME_ROTATION_VECTOR is added continuously
 *     to that walking course for the visible direction arrow.
 *
 * STATIONARY:
 *   - GPS position/course are frozen.
 *   - the exact same relative handset-yaw logic continues.
 *
 * The map's COURSE_UP rotation remains GPS-course-only.
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

        Snapshot(
                Location location,
                List<Location> track,
                Float courseDeg,
                Float phoneHeadingDeg,
                boolean stationary
        ) {
            this.location =
                    location == null
                            ? null
                            : new Location(location);

            this.track = track;

            this.courseDeg = courseDeg;
            this.phoneHeadingDeg = phoneHeadingDeg;
            this.stationary = stationary;
        }
    }

    private static final String CHANNEL = "camino_tracking";
    private static final int NOTIFICATION = 3601;

    private static final float MAX_GPS_ACCURACY_M = 25.0f;
    private static final float TRACK_POINT_SPACING_M = 1.5f;

    /*
     * Fixed spatial direction window.
     * The heading is the continuation of roughly the last 10 m walked.
     */
    private static final float COURSE_BASELINE_M = 10.0f;

    private static final CopyOnWriteArrayList<Listener> LISTENERS =
            new CopyOnWriteArrayList<>();

    private static volatile Snapshot latestSnapshot =
            new Snapshot(
                    null,
                    Collections.emptyList(),
                    null,
                    null,
                    false
            );

    private LocationManager locationManager;
    private SensorManager sensorManager;
    private Sensor gameRotationVector;
    private Sensor linearAcceleration;

    private final List<Location> track = new ArrayList<>();

    /*
     * Read-only deep copy exposed through Snapshot.
     * It is rebuilt only when the GPS track itself grows. High-frequency gyro
     * publications reuse the same immutable list instance.
     */
    private List<Location> publishedTrack =
            Collections.emptyList();
    private boolean publishedTrackDirty;

    private Location acceptedLocation;
    private Location lastTrackLocation;

    private final MotionStateDetector motionStateDetector =
            new MotionStateDetector();

    private final CaminoDirectionTracker directionTracker =
            new CaminoDirectionTracker(
                    TRACK_POINT_SPACING_M,
                    COURSE_BASELINE_M
            );

    private long lastSensorPublishMs;

    /*
     * The foreground service owns recording. Activity listeners are allowed to
     * disappear on screen-off/onPause without stopping statistics.
     */
    private PowerManager.WakeLock trackingWakeLock;

    private final ExecutorService performanceExecutor =
            Executors.newSingleThreadExecutor();

    private final Object performanceLock =
            new Object();

    private final List<PerformanceEvent> pendingPerformanceEvents =
            new ArrayList<>();

    private volatile WalkingPerformanceModel
            backgroundWalkingPerformanceModel;

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

        acquireTrackingWakeLock();

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
            return;
        }

        performanceExecutor.execute(
                this::initializeWalkingPerformanceRecorder
        );
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

        performanceExecutor.shutdownNow();

        releaseTrackingWakeLock();

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
            publishedTrackDirty = true;

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
        if (motionStateDetector.state() != MotionStateDetector.State.MOVING) {
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

        recordPerformanceMoving(
                location
        );

        if (lastTrackLocation == null
                || lastTrackLocation.distanceTo(location)
                        >= TRACK_POINT_SPACING_M) {
            track.add(
                    new Location(location)
            );
            publishedTrackDirty = true;

            lastTrackLocation =
                    new Location(location);
        }

        directionTracker.acceptMovingLocation(
                location
        );
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

        directionTracker.updateRawCameraYaw(
                event
        );

        /*
         * Handset rotation augments the GPS walking course in BOTH movement
         * states. This is relative gyro orientation only; it does not introduce
         * magnetic north and it never rotates the COURSE_UP map itself.
         */
        directionTracker.updateGyroAugmentedHeading();

        long now =
                SystemClock.elapsedRealtime();

        if (now - lastSensorPublishMs >= 50L) {
            lastSensorPublishMs =
                    now;

            publish();
        }
    }

    private void handleLinearAcceleration(
            SensorEvent event
    ) {
        float x = event.values[0];
        float y = event.values[1];
        float z = event.values[2];

        float magnitudeSq = x * x + y * y + z * z;

        MotionStateDetector.State previous =
                motionStateDetector.state();

        MotionStateDetector.State current =
                motionStateDetector.updateMagnitudeSquared(
                        magnitudeSq,
                        SystemClock.elapsedRealtime()
                );

        if (current == previous) {
            return;
        }

        if (current == MotionStateDetector.State.STATIONARY) {
            enterStationary();
            return;
        }

        if (current == MotionStateDetector.State.MOVING) {
            enterMoving();
        }
    }

    private void enterMoving() {
        directionTracker.enterMoving(
                acceptedLocation
        );

        publish();
    }

    private void enterStationary() {
        directionTracker.enterStationary();

        recordPerformanceStationary(
                acceptedLocation
        );

        publish();
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
                        snapshotTrack(),
                        directionTracker.courseDeg(),
                        directionTracker.phoneHeadingDeg(),
                        motionStateDetector.state()
                                == MotionStateDetector.State.STATIONARY
                );

        latestSnapshot = snapshot;

        for (Listener listener : LISTENERS) {
            listener.onTrackingStateChanged(
                    snapshot
            );
        }
    }

    private List<Location> snapshotTrack() {
        if (!publishedTrackDirty) {
            return publishedTrack;
        }

        List<Location> copy =
                new ArrayList<>(
                        track.size()
                );

        for (Location point : track) {
            copy.add(
                    new Location(point)
            );
        }

        publishedTrack =
                Collections.unmodifiableList(
                        copy
                );

        publishedTrackDirty = false;

        return publishedTrack;
    }

    private void acquireTrackingWakeLock() {
        PowerManager powerManager =
                (PowerManager)
                        getSystemService(
                                Context.POWER_SERVICE
                        );

        if (powerManager == null) {
            return;
        }

        trackingWakeLock =
                powerManager.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        "CaminoGuard:background-tracking"
                );

        trackingWakeLock.setReferenceCounted(
                false
        );

        trackingWakeLock.acquire();
    }

    private void releaseTrackingWakeLock() {
        if (trackingWakeLock != null
                && trackingWakeLock.isHeld()) {

            trackingWakeLock.release();
        }
    }

    private void initializeWalkingPerformanceRecorder() {
        Exception lastError =
                null;

        for (int attempt = 1;
                attempt <= 3;
                attempt++) {

            if (Thread.currentThread()
                    .isInterrupted()) {

                return;
            }

            try {
                CaminoConfig.initialize(
                        getApplicationContext()
                );

                List<CaminoRoute> routes =
                        new CaminoRepository(
                                getApplicationContext()
                        ).load();

                CaminoNetwork network =
                        new CaminoNetwork();

                network.rebuild(
                        routes
                );

                MeasurementEngine measurementEngine =
                        new MeasurementEngine(
                                network
                        );

                CaminoProjectionEngine projectionEngine =
                        new CaminoProjectionEngine(
                                network
                        );

                WalkingPerformanceModel model =
                        new WalkingPerformanceModel(
                                getApplicationContext(),
                                projectionEngine,
                                measurementEngine
                        );

                activateWalkingPerformanceRecorder(
                        model
                );

                Log.i(
                        "CaminoTrackingService",
                        "Background walking recorder ready"
                );

                return;

            } catch (Exception error) {
                lastError =
                        error;

                Log.e(
                        "CaminoTrackingService",
                        "Background recorder init attempt "
                                + attempt
                                + " failed",
                        error
                );

                if (attempt < 3) {
                    try {
                        Thread.sleep(
                                2_000L
                        );
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread()
                                .interrupt();

                        return;
                    }
                }
            }
        }

        if (lastError != null) {
            Log.e(
                    "CaminoTrackingService",
                    "Background walking recorder unavailable",
                    lastError
            );
        }
    }

    private void activateWalkingPerformanceRecorder(
            WalkingPerformanceModel model
    ) {
        synchronized (performanceLock) {
            for (PerformanceEvent event
                    : pendingPerformanceEvents) {

                applyPerformanceEvent(
                        model,
                        event
                );
            }

            pendingPerformanceEvents.clear();

            backgroundWalkingPerformanceModel =
                    model;
        }
    }

    private void recordPerformanceMoving(
            Location location
    ) {
        if (location == null) {
            return;
        }

        long elapsedMs =
                location.getElapsedRealtimeNanos()
                        > 0L
                        ? location.getElapsedRealtimeNanos()
                        / 1_000_000L
                        : SystemClock.elapsedRealtime();

        queueOrApplyPerformanceEvent(
                new PerformanceEvent(
                        location,
                        elapsedMs,
                        false
                )
        );
    }

    private void recordPerformanceStationary(
            Location location
    ) {
        if (location == null) {
            return;
        }

        queueOrApplyPerformanceEvent(
                new PerformanceEvent(
                        location,
                        SystemClock.elapsedRealtime(),
                        true
                )
        );
    }

    private void queueOrApplyPerformanceEvent(
            PerformanceEvent event
    ) {
        synchronized (performanceLock) {
            WalkingPerformanceModel model =
                    backgroundWalkingPerformanceModel;

            if (model == null) {
                pendingPerformanceEvents.add(
                        event
                );

                return;
            }

            applyPerformanceEvent(
                    model,
                    event
            );
        }
    }

    private void applyPerformanceEvent(
            WalkingPerformanceModel model,
            PerformanceEvent event
    ) {
        LatLng position =
                new LatLng(
                        event.location.getLatitude(),
                        event.location.getLongitude()
                );

        if (event.stationary) {
            model.noteStationary(
                    position
            );

        } else {
            model.noteMovingSample(
                    position,
                    event.elapsedMs
            );
        }
    }

    private static final class PerformanceEvent {

        final Location location;
        final long elapsedMs;
        final boolean stationary;

        PerformanceEvent(
                Location location,
                long elapsedMs,
                boolean stationary
        ) {
            this.location =
                    new Location(
                            location
                    );

            this.elapsedMs =
                    elapsedMs;

            this.stationary =
                    stationary;
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
