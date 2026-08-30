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
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

import org.maplibre.android.geometry.LatLng;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Camino Guard background GPS tracking service.
 *
 * Background tracking is deliberately GPS-only. High-frequency handset
 * orientation sensors belong to the foreground Activity and are disabled
 * whenever Camino Guard is paused or the screen is off.
 */
public final class CaminoTrackingService extends Service
        implements LocationListener {

    public interface Listener {
        void onTrackingStateChanged(Snapshot snapshot);
    }

    public static final class Snapshot {
        public final Location location;
        public final Float courseDeg;
        public final Float phoneHeadingDeg;
        public final boolean stationary;

        Snapshot(
                Location location,
                Float courseDeg,
                Float phoneHeadingDeg,
                boolean stationary
        ) {
            this.location =
                    location == null
                            ? null
                            : new Location(location);

            this.courseDeg = courseDeg;
            this.phoneHeadingDeg = phoneHeadingDeg;
            this.stationary = stationary;
        }
    }

    private static final String CHANNEL = "camino_tracking";
    private static final int NOTIFICATION = 3601;

    private static final String ACTION_REFRESH_LIBRE =
            "com.marukitano.caminoguard.REFRESH_LIBRE";

    private static final String ACTION_SET_APP_FOREGROUND =
            "com.marukitano.caminoguard.SET_APP_FOREGROUND";

    private static final String ACTION_LOCKED_ROUTE_CHANGED =
            "com.marukitano.caminoguard.LOCKED_ROUTE_CHANGED";

    private static final String EXTRA_APP_FOREGROUND =
            "app_foreground";

    private static final float MAX_GPS_ACCURACY_M = 25.0f;
    private static final float TRACK_POINT_SPACING_M = 1.5f;

    /*
     * Stationary position stays frozen, but timetable ETA must receive one
     * wall-clock tick per minute while a pause continues.
     */
    private static final long STATIONARY_PUBLISH_INTERVAL_MS =
            60_000L;

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
                    null,
                    null,
                    false
            );

    private LocationManager locationManager;

    /*
     * GPS is required while the Activity is visible OR while an explicit
     * locked route exists.
     *
     * LibreLinkUp/Pebble glucose keep using this foreground service even when
     * this flag is false and GPS itself is switched off.
     */
    private boolean appForegroundRequested;

    private boolean gpsUpdatesRegistered;

    private Location acceptedLocation;

    /*
     * Background motion state is GPS-only.
     *
     * lastMotionFix exists only as a fallback when a GPS provider does not
     * expose Doppler speed for a fix.
     */
    private final GpsMotionStateDetector gpsMotionStateDetector =
            new GpsMotionStateDetector();

    private Location lastMotionFix;

    private long lastStationaryPublishElapsedMs =
            Long.MIN_VALUE;

    private final CaminoDirectionTracker directionTracker =
            new CaminoDirectionTracker(
                    TRACK_POINT_SPACING_M,
                    COURSE_BASELINE_M
            );

    private final ExecutorService performanceExecutor =
            Executors.newSingleThreadExecutor();

    private final Object performanceLock =
            new Object();

    private final List<PerformanceEvent> pendingPerformanceEvents =
            new ArrayList<>();

    private volatile WalkingPerformanceModel
            backgroundWalkingPerformanceModel;

    /*
     * LibreLinkUp lives in the same foreground-service lifecycle as GPS.
     * It therefore keeps running when MainActivity is paused or the screen
     * is off. It has its own low-frequency scheduler and does not depend on
     * GPS fixes arriving.
     */
    private volatile LibreLinkUpClient libreLinkUpClient;

    /*
     * Presentation bridge only. A Pebble failure must never affect GPS,
     * routing, off-route detection or performance recording.
     */
    private volatile CaminoPebbleBridge pebbleBridge;

    private volatile CaminoPebbleRoutePublisher
            pebbleRoutePublisher;

    /*
     * Independent four-week raw study.
     *
     * It never feeds ETA/prediction. It only writes accepted
     * MOVING + LOCKED + ON-ROAD samples to its CSV.
     */
    private volatile WalkingSpeedStudyRecorder
            walkingSpeedStudyRecorder;

    /*
     * Pebble route presentation and the four-week study consume the same
     * persisted locked MeasurementPath.
     *
     * Keep one store and one projection in the service so one physical GPS
     * fix cannot scan the same selected route independently in both consumers.
     */
    private final Object lockedPathProjectionLock =
            new Object();

    private volatile LockedMeasurementPathStore
            serviceLockedPathStore;

    private volatile double serviceMaxRouteOffsetM =
            Double.NaN;

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

    public static void setAppForeground(
            Activity activity,
            boolean foreground
    ) {
        if (activity == null
                || activity.checkSelfPermission(
                Manifest.permission.ACCESS_FINE_LOCATION
        ) != PackageManager.PERMISSION_GRANTED) {

            return;
        }

        Intent intent =
                new Intent(
                        activity,
                        CaminoTrackingService.class
                );

        intent.setAction(
                ACTION_SET_APP_FOREGROUND
        );

        intent.putExtra(
                EXTRA_APP_FOREGROUND,
                foreground
        );

        if (Build.VERSION.SDK_INT
                >= Build.VERSION_CODES.O) {

            activity.startForegroundService(
                    intent
            );

        } else {
            activity.startService(
                    intent
            );
        }
    }


    public static void requestLibreRefresh(
            Activity activity
    ) {
        if (activity == null
                || activity.checkSelfPermission(
                Manifest.permission.ACCESS_FINE_LOCATION
        ) != PackageManager.PERMISSION_GRANTED) {

            return;
        }

        Intent intent =
                new Intent(
                        activity,
                        CaminoTrackingService.class
                );

        intent.setAction(
                ACTION_REFRESH_LIBRE
        );

        if (Build.VERSION.SDK_INT
                >= Build.VERSION_CODES.O) {

            activity.startForegroundService(
                    intent
            );

        } else {
            activity.startService(
                    intent
            );
        }
    }


    public static void notifyLockedRouteChanged(
            Activity activity
    ) {
        if (activity == null
                || activity.checkSelfPermission(
                        Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED) {

            return;
        }

        Intent intent =
                new Intent(
                        activity,
                        CaminoTrackingService.class
                );

        intent.setAction(
                ACTION_LOCKED_ROUTE_CHANGED
        );

        if (Build.VERSION.SDK_INT
                >= Build.VERSION_CODES.O) {

            activity.startForegroundService(
                    intent
            );

        } else {
            activity.startService(
                    intent
            );
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

        if (checkSelfPermission(
                Manifest.permission.ACCESS_FINE_LOCATION
        ) != PackageManager.PERMISSION_GRANTED) {
            stopSelf();
            return;
        }

        /*
         * Do not start GNSS merely because the foreground service exists.
         *
         * The service also owns LibreLinkUp/Pebble glucose and may therefore
         * intentionally stay alive while no location consumer exists.
         */
        updateGpsRegistration();

        performanceExecutor.execute(
                this::initializeWalkingPerformanceRecorder
        );

        /*
         * LibreLinkUp is deliberately started from the foreground service,
         * not from MainActivity.
         *
         * Display off / Home button / another foreground app therefore do
         * not stop glucose polling.
         */
        try {
            pebbleBridge =
                    new CaminoPebbleBridge(
                            getApplicationContext()
                    );

            ensurePebbleRoutePublisher(
                    backgroundWalkingPerformanceModel
            );

            LibreLinkUpStore libreStore =
                    new LibreLinkUpStore(
                            getApplicationContext()
                    );

            Integer cachedMgdl =
                    libreStore.lastGlucoseMgdl();

            long cachedReadingTimeMs =
                    libreStore.lastReadingTimeMs();

            if (cachedMgdl != null
                    && cachedReadingTimeMs > 0L) {

                pebbleBridge.sendGlucose(
                        LibreLinkUpClient
                                .formatGlucoseDisplay(
                                        cachedMgdl,
                                        cachedReadingTimeMs,
                                        System.currentTimeMillis()
                                )
                );
            }

            libreLinkUpClient =
                    new LibreLinkUpClient(
                            getApplicationContext(),
                            (text, readingTimeMs) -> {
                                CaminoPebbleBridge bridge =
                                        pebbleBridge;

                                if (bridge != null) {
                                    bridge.sendGlucose(
                                            text
                                    );
                                }
                            }
                    );

            libreLinkUpClient.start();

        } catch (RuntimeException error) {
            /*
             * Pebble/Libre are auxiliary outputs. They must never be able to
             * take the GPS foreground service down.
             */
            Log.w(
                    "LibreLinkUp",
                    "Could not initialize LibreLinkUp/Pebble bridge",
                    error
            );
        }
    }

    @Override
    public int onStartCommand(
            Intent intent,
            int flags,
            int startId
    ) {
        if (intent != null) {
            if (ACTION_SET_APP_FOREGROUND.equals(
                    intent.getAction()
            )) {

                appForegroundRequested =
                        intent.getBooleanExtra(
                                EXTRA_APP_FOREGROUND,
                                false
                        );

            } else if (ACTION_LOCKED_ROUTE_CHANGED.equals(
                    intent.getAction()
            )) {

                publishCurrentLockedRouteState();

            } else if (ACTION_REFRESH_LIBRE.equals(
                    intent.getAction()
            )) {

                LibreLinkUpClient client =
                        libreLinkUpClient;

                if (client != null) {
                    client.requestNow();
                }
            }
        }

        /*
         * The persisted locked path is authoritative for background GPS.
         * This also makes START_STICKY recreation safe:
         *
         * locked   -> GNSS resumes
         * unlocked -> service may live for Libre, but GNSS stays off
         */
        updateGpsRegistration();

        /*
         * Preserve the existing service behaviour: Android may recreate this
         * foreground service after reclaiming the process.
         *
         * A recreated Libre client intentionally starts without an auth
         * token and therefore performs a clean LibreLinkUp login.
         */
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        if (locationManager != null
                && gpsUpdatesRegistered) {

            try {
                locationManager.removeUpdates(
                        this
                );

            } catch (SecurityException ignored) {
            }
        }

        gpsUpdatesRegistered =
                false;

        WalkingSpeedStudyRecorder studyRecorder =
                walkingSpeedStudyRecorder;

        if (studyRecorder != null) {
            studyRecorder.close();
        }

        walkingSpeedStudyRecorder =
                null;

        LibreLinkUpClient libreClient =
                libreLinkUpClient;

        libreLinkUpClient =
                null;

        if (libreClient != null) {
            libreClient.close();
        }

        pebbleRoutePublisher =
                null;

        CaminoPebbleBridge bridge =
                pebbleBridge;

        pebbleBridge =
                null;

        if (bridge != null) {
            bridge.close();
        }

        performanceExecutor.shutdownNow();

        super.onDestroy();
    }

    private synchronized void updateGpsRegistration() {
        if (locationManager == null) {
            return;
        }

        boolean locked =
                LockedMeasurementPathStore.hasActivePath(
                        this
                );

        boolean required =
                appForegroundRequested
                        || locked;

        if (required == gpsUpdatesRegistered) {
            return;
        }

        if (required) {
            if (checkSelfPermission(
                    Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED) {

                return;
            }

            try {
                locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        1000L,
                        1.0f,
                        this
                );

                gpsUpdatesRegistered =
                        true;

                Log.i(
                        "CaminoTrackingService",
                        "GPS enabled foreground="
                                + appForegroundRequested
                                + " locked="
                                + locked
                );

            } catch (SecurityException error) {
                Log.w(
                        "CaminoTrackingService",
                        "Could not enable GPS",
                        error
                );
            }

            return;
        }

        try {
            locationManager.removeUpdates(
                    this
            );

        } catch (SecurityException ignored) {
        }

        gpsUpdatesRegistered =
                false;

        gpsMotionStateDetector.reset();

        lastMotionFix =
                null;

        lastStationaryPublishElapsedMs =
                Long.MIN_VALUE;

        /*
         * Direction history from before a GPS-off interval must not be used as
         * the spatial baseline for movement after resume.
         *
         * This clears only the service-side GPS point history; foreground gyro
         * state remains owned exclusively by GpsGyroOrientationController.
         */
        directionTracker.enterMoving(
                null
        );

        Log.i(
                "CaminoTrackingService",
                "GPS disabled foreground=false locked=false"
        );
    }


    @Override
    public void onLocationChanged(Location location) {
        if (!isGoodGpsFix(location)) {
            return;
        }

        boolean firstMotionFix =
                lastMotionFix == null;

        float motionSpeedMps =
                motionSpeedMps(
                        location,
                        lastMotionFix
                );

        long motionElapsedMs =
                locationElapsedMs(
                        location
                );

        GpsMotionStateDetector.State previousMotionState =
                gpsMotionStateDetector.state();

        GpsMotionStateDetector.State motionState =
                gpsMotionStateDetector.updateSpeed(
                        motionSpeedMps,
                        motionElapsedMs
                );

        lastMotionFix =
                new Location(
                        location
                );

        LockedMeasurementPathStore.Snapshot locked =
                currentServiceLockedPath();

        /*
         * The first trustworthy GPS packet after startup/resume is always
         * accepted as a position anchor. It is not yet labelled as MOVING and
         * therefore cannot enter the walking-study/performance pipeline.
         */
        if (firstMotionFix) {
            acceptedLocation =
                    new Location(
                            location
                    );

            directionTracker.enterMoving(
                    location
            );

            publish(
                    locked,
                    projectServiceLockedPath(
                            locked,
                            location
                    )
            );

            return;
        }

        if (motionState
                == GpsMotionStateDetector.State.STATIONARY) {

            /*
             * Freeze GPS position/course while standing. Raw stationary GNSS
             * jitter must not move the map, route progress or off-route state.
             */
            boolean enteredStationary =
                    previousMotionState
                            != GpsMotionStateDetector.State.STATIONARY;

            if (enteredStationary) {
                recordPerformanceStationary(
                        acceptedLocation
                );

                directionTracker.enterStationary();
            }

            /*
             * The frozen Location intentionally keeps its original timestamp.
             * Publishing it once per minute gives timetable presentation a
             * wall-clock tick without accepting stationary GPS jitter.
             */
            boolean stationaryPublishDue =
                    enteredStationary
                            || lastStationaryPublishElapsedMs
                            == Long.MIN_VALUE
                            || motionElapsedMs
                            - lastStationaryPublishElapsedMs
                            >= STATIONARY_PUBLISH_INTERVAL_MS;

            if (stationaryPublishDue) {
                lastStationaryPublishElapsedMs =
                        motionElapsedMs;

                publish(
                        locked,
                        projectServiceLockedPath(
                                locked,
                                acceptedLocation
                        )
                );
            }

            return;
        }

        /*
         * UNKNOWN is deliberately not MOVING. This preserves the hard
         * MOVING-only gate for the four-week study and learned performance.
         */
        if (motionState
                != GpsMotionStateDetector.State.MOVING) {

            return;
        }

        lastStationaryPublishElapsedMs =
                Long.MIN_VALUE;

        if (previousMotionState
                != GpsMotionStateDetector.State.MOVING) {

            /*
             * Start a fresh spatial direction baseline after a pause.
             */
            directionTracker.enterMoving(
                    acceptedLocation
            );
        }

        MeasurementPathProjection.Result lockedProjection =
                projectServiceLockedPath(
                        locked,
                        location
                );

        acceptMovingLocation(
                location,
                locked,
                lockedProjection
        );

        publish(
                locked,
                lockedProjection
        );
    }

    private void acceptMovingLocation(
            Location location,
            LockedMeasurementPathStore.Snapshot locked,
            MeasurementPathProjection.Result lockedProjection
    ) {
        acceptedLocation =
                new Location(location);

        recordPerformanceMoving(
                location
        );

        WalkingSpeedStudyRecorder studyRecorder =
                walkingSpeedStudyRecorder;

        if (studyRecorder != null) {
            studyRecorder.noteGpsFix(
                    location,
                    locked,
                    lockedProjection
            );
        }

        directionTracker.acceptMovingLocation(
                location
        );
    }

    private float motionSpeedMps(
            Location location,
            Location previous
    ) {
        if (location == null) {
            return Float.NaN;
        }

        if (location.hasSpeed()
                && !Float.isNaN(
                        location.getSpeed()
                )
                && !Float.isInfinite(
                        location.getSpeed()
                )
                && location.getSpeed() >= 0.0f) {

            return location.getSpeed();
        }

        if (previous == null) {
            return Float.NaN;
        }

        long currentElapsedMs =
                locationElapsedMs(
                        location
                );

        long previousElapsedMs =
                locationElapsedMs(
                        previous
                );

        long deltaMs =
                currentElapsedMs
                        - previousElapsedMs;

        if (deltaMs <= 0L) {
            return Float.NaN;
        }

        float distanceM =
                previous.distanceTo(
                        location
                );

        if (Float.isNaN(distanceM)
                || Float.isInfinite(distanceM)
                || distanceM < 0.0f) {

            return Float.NaN;
        }

        return distanceM
                / (
                deltaMs
                        / 1000.0f
        );
    }


    private long locationElapsedMs(
            Location location
    ) {
        if (location != null
                && location.getElapsedRealtimeNanos()
                > 0L) {

            return location.getElapsedRealtimeNanos()
                    / 1_000_000L;
        }

        return SystemClock.elapsedRealtime();
    }


    private boolean isGoodGpsFix(
            Location location
    ) {
        return !location.hasAccuracy()
                || location.getAccuracy()
                        <= MAX_GPS_ACCURACY_M;
    }

    private void publish(
            LockedMeasurementPathStore.Snapshot locked,
            MeasurementPathProjection.Result lockedProjection
    ) {
        Snapshot snapshot =
                new Snapshot(
                        acceptedLocation,
                        directionTracker.courseDeg(),
                        null,
                        gpsMotionStateDetector.state()
                                == GpsMotionStateDetector.State.STATIONARY
                );

        latestSnapshot = snapshot;

        for (Listener listener : LISTENERS) {
            listener.onTrackingStateChanged(
                    snapshot
            );
        }

        CaminoPebbleRoutePublisher publisher =
                pebbleRoutePublisher;

        if (publisher != null
                && acceptedLocation != null) {

            publisher.onGpsFix(
                    acceptedLocation,
                    locked,
                    lockedProjection,
                    snapshot.stationary
            );
        }
    }

    private void publishCurrentLockedRouteState() {
        CaminoPebbleRoutePublisher publisher =
                pebbleRoutePublisher;

        if (publisher == null) {
            return;
        }

        LockedMeasurementPathStore.Snapshot locked =
                currentServiceLockedPath();

        MeasurementPathProjection.Result lockedProjection =
                projectServiceLockedPath(
                        locked,
                        acceptedLocation
                );

        publisher.onGpsFix(
                acceptedLocation,
                locked,
                lockedProjection,
                gpsMotionStateDetector.state()
                        == GpsMotionStateDetector.State.STATIONARY
        );
    }


    private LockedMeasurementPathStore.Snapshot
            currentServiceLockedPath() {

        LockedMeasurementPathStore store =
                serviceLockedPathStore;

        if (store == null) {
            return null;
        }

        synchronized (lockedPathProjectionLock) {
            return store.currentLockedPath();
        }
    }


    private MeasurementPathProjection.Result
            projectServiceLockedPath(
                    LockedMeasurementPathStore.Snapshot locked,
                    Location location
            ) {

        if (locked == null
                || locked.path == null
                || location == null
                || !Double.isFinite(
                        serviceMaxRouteOffsetM
                )
                || serviceMaxRouteOffsetM < 0.0) {

            return null;
        }

        LatLng position =
                new LatLng(
                        location.getLatitude(),
                        location.getLongitude()
                );

        return MeasurementPathProjection.projectWithin(
                locked.path,
                position,
                serviceMaxRouteOffsetM
        );
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

                serviceMaxRouteOffsetM =
                        CaminoConfig.get()
                                .doubleValue(
                                        "navigation.offRouteThresholdMeters"
                                );

                serviceLockedPathStore =
                        new LockedMeasurementPathStore(
                                getApplicationContext()
                        );

                walkingSpeedStudyRecorder =
                        new WalkingSpeedStudyRecorder(
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

        ensurePebbleRoutePublisher(
                model
        );
    }


    private synchronized void ensurePebbleRoutePublisher(
            WalkingPerformanceModel model
    ) {
        if (pebbleRoutePublisher != null
                || model == null
                || pebbleBridge == null) {

            return;
        }

        try {
            pebbleRoutePublisher =
                    new CaminoPebbleRoutePublisher(
                            getApplicationContext(),
                            model,
                            pebbleBridge
                    );

            Log.i(
                    "CaminoPebble",
                    "Pebble route publisher ready"
            );

            LockedMeasurementPathStore.Snapshot locked =
                    currentServiceLockedPath();

            MeasurementPathProjection.Result lockedProjection =
                    projectServiceLockedPath(
                            locked,
                            acceptedLocation
                    );

            pebbleRoutePublisher.onGpsFix(
                    acceptedLocation,
                    locked,
                    lockedProjection,
                    gpsMotionStateDetector.state()
                            == GpsMotionStateDetector.State.STATIONARY
            );

        } catch (RuntimeException error) {
            Log.w(
                    "CaminoPebble",
                    "Could not initialize route publisher",
                    error
            );
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
        WalkingSpeedStudyRecorder studyRecorder =
                walkingSpeedStudyRecorder;

        if (studyRecorder != null) {
            studyRecorder.noteNotMoving();
        }

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
