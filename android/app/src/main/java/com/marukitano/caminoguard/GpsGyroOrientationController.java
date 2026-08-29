package com.marukitano.caminoguard;

import android.Manifest;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.SystemClock;

import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.Style;
import org.maplibre.android.style.layers.CircleLayer;
import org.maplibre.android.style.layers.LineLayer;
import org.maplibre.android.style.layers.Property;
import org.maplibre.android.style.layers.PropertyFactory;
import org.maplibre.android.style.layers.SymbolLayer;
import org.maplibre.android.style.sources.GeoJsonSource;
import org.maplibre.geojson.Feature;
import org.maplibre.geojson.LineString;
import org.maplibre.geojson.Point;

import java.util.ArrayList;
import java.util.List;

public final class GpsGyroOrientationController
        implements CaminoTrackingService.Listener,
        SensorEventListener {

    public static final int LOCATION_PERMISSION_REQUEST =
            4207;

    private static final int MAX_PLAYBACK_POINTS =
            3;

    private static final float FOREGROUND_DIRECTION_WARMUP_M =
            20.0f;

    private static final String POS_SRC="camino-user-location";
    private static final String DOT="camino-user-location-dot";
    private static final String ARROW="camino-user-direction";
    private static final String ARROW_IMG="camino-user-direction-arrow";
    private static final String ARROW_IMG_STATIONARY="camino-user-direction-arrow-stationary";
    private static final String TRACK_SRC="camino-debug-gps-track";
    private static final String TRACK="camino-debug-gps-track-line";
    private final Activity activity;

    private final SensorManager sensorManager;
    private final Sensor gameRotationVector;

    private boolean gyroRegistered;
    private Float lastRawGyroYawDeg;
    private float gyroOffsetDeg;
    private Float gyroCourseAnchorDeg;
    private long lastGyroRenderMs = Long.MIN_VALUE;

    /*
     * A newly created foreground controller must earn a fresh GPS direction.
     * Do NOT reset these fields from onPause/onResume: screen-off only resets
     * the gyro, not the already-established GPS walking direction.
     */
    private android.location.Location foregroundDirectionStartLocation;
    private boolean foregroundDirectionReady;
    private Runnable foregroundDirectionReadyListener;

    private MapLibreMap map;
    private GeoJsonSource posSource;
    private GeoJsonSource trackSource;
    private SymbolLayer arrowLayer;
    private CaminoTrackingService.Snapshot state;
    private List<android.location.Location> renderedTrack;
    private final LiveNavigationCameraController liveNavigationCameraController;
    private long lastFollowLocationTime = Long.MIN_VALUE;
    private LatLng displayedPosition;
    private Double displayedBearing;

    private static final class TimedPoint {
        final LatLng point;
        final long timeMs;

        TimedPoint(
                LatLng point,
                long timeMs
        ){
            this.point=point;
            this.timeMs=timeMs;
        }
    }

    private final List<TimedPoint> playbackPoints =
            new ArrayList<>();

    private ValueAnimator playbackAnimator;

    private Double departureHeadingDeg;
    private boolean started;
    private boolean asked;

    public GpsGyroOrientationController(
            Activity activity
    ) {
        this.activity =
                activity;

        sensorManager =
                (SensorManager)
                        activity.getSystemService(
                                Context.SENSOR_SERVICE
                        );

        gameRotationVector =
                sensorManager == null
                        ? null
                        : sensorManager.getDefaultSensor(
                                Sensor.TYPE_GAME_ROTATION_VECTOR
                        );

        liveNavigationCameraController =
                new LiveNavigationCameraController(
                        activity
                );
    }

    public void attachMap(
            MapLibreMap map
    ) {
        this.map =
                map;

        liveNavigationCameraController.attachMap(
                map
        );

        map.addOnCameraMoveListener(
                this::updateArrow
        );
    }

    public void onStyleLoaded(Style style){
        posSource=new GeoJsonSource(POS_SRC); style.addSource(posSource);
        trackSource=new GeoJsonSource(TRACK_SRC); style.addSource(trackSource);
        renderedTrack=null;

        LineLayer line=new LineLayer(TRACK,TRACK_SRC);
        line.setProperties(
                PropertyFactory.lineColor(Color.parseColor("#D04432")),
                PropertyFactory.lineWidth(4f),

                /*
                 * Keep the proven GPS/track pipeline intact.
                 * The former debug track is simply invisible.
                 */
                PropertyFactory.lineOpacity(0f),

                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND));
        style.addLayer(line);

        CircleLayer dot=new CircleLayer(DOT,POS_SRC);
        dot.setProperties(
                PropertyFactory.circleRadius(7f),
                PropertyFactory.circleColor(Color.parseColor("#F5C98E")),
                PropertyFactory.circleStrokeColor(Color.parseColor("#3D332C")),
                PropertyFactory.circleStrokeWidth(2.2f));
        style.addLayer(dot);

        style.addImage(
                ARROW_IMG,
                arrowBitmap(Color.parseColor("#F5C98E"))
        );
        style.addImage(
                ARROW_IMG_STATIONARY,
                arrowBitmap(Color.parseColor("#4A90E2"))
        );
        arrowLayer=new SymbolLayer(ARROW,POS_SRC);
        arrowLayer.setProperties(
                PropertyFactory.iconImage(ARROW_IMG),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
                PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_VIEWPORT),
                PropertyFactory.iconOpacity(0f));
        style.addLayer(arrowLayer);

        render(state!=null?state:CaminoTrackingService.snapshot());
    }

    void setForegroundDirectionReadyListener(
            Runnable listener
    ) {
        foregroundDirectionReadyListener =
                listener;

        if (foregroundDirectionReady
                && listener != null) {

            listener.run();
        }
    }


    public void setExternalNavigationMode(
            NavigationController.Mode mode
    ) {
        liveNavigationCameraController
                .setNavigationMode(
                        mode
                );
    }

    public void setExternalNavigationSuspended(boolean suspended){
        liveNavigationCameraController.setSuspended(
                suspended);
    }

    public void start() {
        if (started) {
            return;
        }

        started =
                true;

        /*
         * A new foreground session always starts exactly on the current
         * GPS walking tangent. The first gyro sample becomes zero degrees.
         */
        resetForegroundSession();
        registerForegroundGyro();

        if (activity.checkSelfPermission(
                Manifest.permission.ACCESS_FINE_LOCATION
        ) != PackageManager.PERMISSION_GRANTED) {

            if (!asked) {
                asked =
                        true;

                activity.requestPermissions(
                        new String[]{
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION
                        },
                        LOCATION_PERMISSION_REQUEST
                );
            }

            return;
        }

        CaminoTrackingService.addListener(
                this
        );

        CaminoTrackingService.start(
                activity
        );
    }

    public void stop() {
        if (!started) {
            return;
        }

        started =
                false;

        unregisterForegroundGyro();

        if (playbackAnimator != null) {
            playbackAnimator.cancel();
            playbackAnimator = null;
        }

        resetGyroOffset();

        CaminoTrackingService.removeListener(
                this
        );
    }

    public void onLocationPermissionResult(int requestCode,String[] p,int[] g){
        if (requestCode == LOCATION_PERMISSION_REQUEST
                && started
                && activity.checkSelfPermission(
                Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED) {

            CaminoTrackingService.addListener(
                    this
            );

            CaminoTrackingService.start(
                    activity
            );
        }
    }

    @Override public void onTrackingStateChanged(CaminoTrackingService.Snapshot s){
        state=s; activity.runOnUiThread(()->render(s));
    }

    private void render(CaminoTrackingService.Snapshot s){
        if(s==null)return;

        if(trackSource!=null && s.track!=renderedTrack){
            renderedTrack=s.track;

            if(s.track.size()>=2){
                List<Point> pts=new ArrayList<>();

                for(android.location.Location l:s.track)
                    pts.add(
                            Point.fromLngLat(
                                    l.getLongitude(),
                                    l.getLatitude()
                            )
                    );

                trackSource.setGeoJson(
                        Feature.fromGeometry(
                                LineString.fromLngLats(
                                        pts
                                )
                        )
                );
            }
        }

        updateForegroundDirectionReadiness(
                s
        );

        if (foregroundDirectionReady) {
            compensateGyroForGpsCourse(
                    s.courseDeg
            );
        }

        /*
         * Map rotation is GPS-only, but even the map must not use a stale
         * course from before this foreground session has established movement.
         */
        liveNavigationCameraController.setCourseDeg(
                foregroundDirectionReady
                        ? s.courseDeg
                        : null
        );
        updateArrow();

        if(s.location!=null && s.location.getTime()!=lastFollowLocationTime){
            lastFollowLocationTime=s.location.getTime();
            animateToSnapshot(s);
        }
    }

    private void updateArrow() {
        if (map == null
                || arrowLayer == null
                || state == null) {

            return;
        }

        /*
         * During the first 20 m of a fresh foreground controller we show only
         * the existing location dot. A direction arrow would imply knowledge
         * that we deliberately do not trust yet.
         */
        if (!foregroundDirectionReady) {
            arrowLayer.setProperties(
                    PropertyFactory.iconImage(
                            ARROW_IMG
                    ),
                    PropertyFactory.iconOpacity(
                            0f
                    )
            );

            return;
        }

        Double baseCourse =
                null;

        if (state.courseDeg != null) {
            baseCourse =
                    Double.valueOf(
                            state.courseDeg
                    );

        } else if (displayedBearing != null) {
            baseCourse =
                    displayedBearing;
        }

        if (baseCourse == null) {
            arrowLayer.setProperties(
                    PropertyFactory.iconImage(
                            ARROW_IMG
                    ),
                    PropertyFactory.iconOpacity(
                            0f
                    )
            );

            return;
        }

        /*
         * GPS tangent is the walking-direction base.
         * Only the visible arrow receives the relative foreground gyro offset.
         */
        double worldHeading =
                augmentedHeading(
                        baseCourse
                );

        float screenAngle =
                (float) GeoMath.normalizeDegrees(
                        worldHeading
                                - map.getCameraPosition().bearing
                );

        arrowLayer.setProperties(
                PropertyFactory.iconImage(
                        ARROW_IMG
                ),
                PropertyFactory.iconRotate(
                        screenAngle
                ),
                PropertyFactory.iconOpacity(
                        1f
                )
        );
    }

    private void animateToSnapshot(CaminoTrackingService.Snapshot s){
        if(map==null || posSource==null || s.location==null)
            return;

        LatLng newest=new LatLng(
                s.location.getLatitude(),
                s.location.getLongitude());

        long timeMs=
                s.location.getElapsedRealtimeNanos()>0
                        ? s.location.getElapsedRealtimeNanos()/1_000_000L
                        : android.os.SystemClock.elapsedRealtime();

        TimedPoint previous=null;

        if(!playbackPoints.isEmpty()){
            previous=
                    playbackPoints.get(
                            playbackPoints.size()-1);

            if(timeMs<=previous.timeMs)
                return;
        }

        TimedPoint newestTimed=
                new TimedPoint(
                        newest,
                        timeMs);

        playbackPoints.add(newestTimed);

        while (playbackPoints.size()
                > MAX_PLAYBACK_POINTS) {

            playbackPoints.remove(
                    0
            );
        }

        if(displayedPosition==null || previous==null){
            displayedPosition=newest;

            if(s.courseDeg!=null)
                displayedBearing=(double)s.courseDeg;

            posSource.setGeoJson(
                    Feature.fromGeometry(
                            Point.fromLngLat(
                                    newest.getLongitude(),
                                    newest.getLatitude())));

            liveNavigationCameraController.onPose(
                    newest
            );

            updateArrow();

            return;
        }

        LatLng start=displayedPosition;
        LatLng end=newest;

        double directBearing=
                GeoMath.bearingDegrees(
                        start,
                        end);

        double startBearing=
                departureHeadingDeg!=null
                        ? departureHeadingDeg
                        : displayedBearing!=null
                                ? displayedBearing
                                : directBearing;

        double endBearing=directBearing;

        if(playbackPoints.size()>=3){
            TimedPoint older=
                    playbackPoints.get(
                            playbackPoints.size()-3);

            double previousBearing=
                    GeoMath.bearingDegrees(
                            older.point,
                            previous.point);

            endBearing=blendHeading(
                    previousBearing,
                    directBearing,
                    0.72);
        }

        long fixIntervalMs=
                Math.max(
                        1L,
                        timeMs-previous.timeMs);

        long durationMs=
                Math.max(
                        420L,
                        Math.min(
                                900L,
                                Math.round(
                                        fixIntervalMs*0.82)));

        if(playbackAnimator!=null)
            playbackAnimator.cancel();

        final double sb=startBearing;
        final double eb=endBearing;

        playbackAnimator=
                ValueAnimator.ofFloat(0f,1f);

        playbackAnimator.setDuration(
                durationMs);

        playbackAnimator.setInterpolator(
                new android.view.animation.LinearInterpolator());

        playbackAnimator.addUpdateListener(animation -> {
            float t=
                    (float)animation
                            .getAnimatedValue();

            LatLng pos=headingHermite(
                    start,
                    end,
                    sb,
                    eb,
                    t);

            double bearing=
                    headingHermiteBearing(
                            start,
                            end,
                            sb,
                            eb,
                            t);

            renderSplinePose(
                    pos,
                    bearing);
        });

        playbackAnimator.addListener(
                new android.animation.AnimatorListenerAdapter(){
                    @Override
                    public void onAnimationEnd(
                            android.animation.Animator animation
                    ){
                        departureHeadingDeg=null;

                        renderSplinePose(
                                end,
                                eb);
                    }
                });

        playbackAnimator.start();
    }


    private static double blendHeading(
            double from,
            double to,
            double amount
    ){
        return GeoMath.normalizeDegrees(
                from
                        +GeoMath.shortestAngleDegrees(
                                from,
                                to)*amount);
    }

    private static LatLng headingHermite(
            LatLng a,
            LatLng b,
            double startBearingDeg,
            double endBearingDeg,
            float t
    ){
        double midLat=Math.toRadians(
                (a.getLatitude()
                        +b.getLatitude())*0.5);

        double lonScale=
                Math.max(
                        0.20,
                        Math.cos(midLat));

        double deltaNorth=
                b.getLatitude()
                        -a.getLatitude();

        double deltaEast=
                (b.getLongitude()
                        -a.getLongitude())
                        *lonScale;

        double segmentLen=
                Math.hypot(
                        deltaNorth,
                        deltaEast);

        double tangentLen=
                segmentLen*0.55;

        double sRad=
                Math.toRadians(
                        startBearingDeg);

        double eRad=
                Math.toRadians(
                        endBearingDeg);

        double m0North=
                Math.cos(sRad)
                        *tangentLen;

        double m0East=
                Math.sin(sRad)
                        *tangentLen;

        double m1North=
                Math.cos(eRad)
                        *tangentLen;

        double m1East=
                Math.sin(eRad)
                        *tangentLen;

        double t2=t*t;
        double t3=t2*t;

        double h10=t3-2*t2+t;
        double h01=-2*t3+3*t2;
        double h11=t3-t2;

        double north=
                h10*m0North
                        +h01*deltaNorth
                        +h11*m1North;

        double east=
                h10*m0East
                        +h01*deltaEast
                        +h11*m1East;

        return new LatLng(
                a.getLatitude()+north,
                a.getLongitude()
                        +east/lonScale);
    }

    private static double headingHermiteBearing(
            LatLng a,
            LatLng b,
            double startBearingDeg,
            double endBearingDeg,
            float t
    ){
        double midLat=Math.toRadians(
                (a.getLatitude()
                        +b.getLatitude())*0.5);

        double lonScale=
                Math.max(
                        0.20,
                        Math.cos(midLat));

        double deltaNorth=
                b.getLatitude()
                        -a.getLatitude();

        double deltaEast=
                (b.getLongitude()
                        -a.getLongitude())
                        *lonScale;

        double segmentLen=
                Math.hypot(
                        deltaNorth,
                        deltaEast);

        double tangentLen=
                segmentLen*0.55;

        double sRad=
                Math.toRadians(
                        startBearingDeg);

        double eRad=
                Math.toRadians(
                        endBearingDeg);

        double m0North=
                Math.cos(sRad)
                        *tangentLen;

        double m0East=
                Math.sin(sRad)
                        *tangentLen;

        double m1North=
                Math.cos(eRad)
                        *tangentLen;

        double m1East=
                Math.sin(eRad)
                        *tangentLen;

        double t2=t*t;

        double dh10=3*t2-4*t+1;
        double dh01=-6*t2+6*t;
        double dh11=3*t2-2*t;

        double north=
                dh10*m0North
                        +dh01*deltaNorth
                        +dh11*m1North;

        double east=
                dh10*m0East
                        +dh01*deltaEast
                        +dh11*m1East;

        if(Math.hypot(east,north)<1e-12)
            return GeoMath.bearingDegrees(a,b);

        return GeoMath.normalizeDegrees(
                Math.toDegrees(
                        Math.atan2(
                                east,
                                north)));
    }

    private void renderSplinePose(
            LatLng pos,
            double splineBearing
    ){
        displayedPosition=pos;
        displayedBearing=splineBearing;

        posSource.setGeoJson(
                Feature.fromGeometry(
                        Point.fromLngLat(
                                pos.getLongitude(),
                                pos.getLatitude())));

        liveNavigationCameraController.onPose(
                pos
        );

        updateArrow();
    }

    private void registerForegroundGyro() {
        if (gyroRegistered
                || sensorManager == null
                || gameRotationVector == null) {

            return;
        }

        gyroRegistered =
                sensorManager.registerListener(
                        this,
                        gameRotationVector,
                        SensorManager.SENSOR_DELAY_UI
                );
    }


    private void unregisterForegroundGyro() {
        if (!gyroRegistered
                || sensorManager == null) {

            return;
        }

        sensorManager.unregisterListener(
                this,
                gameRotationVector
        );

        gyroRegistered =
                false;
    }


    private void resetForegroundSession() {
        resetGyroOffset();

        playbackPoints.clear();

        displayedPosition =
                null;

        displayedBearing =
                null;

        departureHeadingDeg =
                null;

        lastFollowLocationTime =
                Long.MIN_VALUE;
    }


    private void resetGyroOffset() {
        lastRawGyroYawDeg =
                null;

        gyroOffsetDeg =
                0.0f;

        gyroCourseAnchorDeg =
                null;

        lastGyroRenderMs =
                Long.MIN_VALUE;
    }


    @Override
    public void onSensorChanged(
            SensorEvent event
    ) {
        if (!started
                || event == null
                || event.sensor == null
                || event.sensor.getType()
                != Sensor.TYPE_GAME_ROTATION_VECTOR) {

            return;
        }

        Float rawYaw =
                rawCameraYawDegrees(
                        event
                );

        if (rawYaw == null) {
            return;
        }

        /*
         * First foreground sensor sample defines zero. Therefore reopening
         * Camino Guard always starts again from the pure GPS tangent.
         */
        if (lastRawGyroYawDeg == null) {
            lastRawGyroYawDeg =
                    rawYaw;

            gyroOffsetDeg =
                    0.0f;

            applyForegroundOrientation();
            return;
        }

        float delta =
                GeoMath.shortestAngleDegrees(
                        lastRawGyroYawDeg,
                        rawYaw
                );

        lastRawGyroYawDeg =
                rawYaw;

        gyroOffsetDeg =
                signedDegrees(
                        gyroOffsetDeg
                                + delta
                );

        /*
         * SENSOR_DELAY_UI plus a 50 ms render throttle gives responsive map
         * rotation without driving MapLibre at raw sensor frequency.
         */
        long now =
                SystemClock.elapsedRealtime();

        if (lastGyroRenderMs != Long.MIN_VALUE
                && now - lastGyroRenderMs < 50L) {

            return;
        }

        lastGyroRenderMs =
                now;

        applyForegroundOrientation();
    }


    @Override
    public void onAccuracyChanged(
            Sensor sensor,
            int accuracy
    ) {
        // Rotation-vector accuracy changes need no special handling.
    }


    private Float rawCameraYawDegrees(
            SensorEvent event
    ) {
        float[] rotation =
                new float[9];

        SensorManager.getRotationMatrixFromVector(
                rotation,
                event.values
        );

        float worldX =
                rotation[1];

        float worldY =
                rotation[4];

        if (Math.hypot(
                worldX,
                worldY
        ) < 0.18) {

            return null;
        }

        return GeoMath.normalizeDegrees(
                (float) Math.toDegrees(
                        Math.atan2(
                                worldX,
                                worldY
                        )
                )
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


    private void updateForegroundDirectionReadiness(
            CaminoTrackingService.Snapshot snapshot
    ) {
        if (foregroundDirectionReady
                || snapshot == null
                || snapshot.location == null) {

            return;
        }

        if (foregroundDirectionStartLocation == null) {
            foregroundDirectionStartLocation =
                    new android.location.Location(
                            snapshot.location
                    );

            return;
        }

        if (snapshot.courseDeg == null) {
            return;
        }

        float distanceM =
                foregroundDirectionStartLocation.distanceTo(
                        snapshot.location
                );

        if (distanceM < FOREGROUND_DIRECTION_WARMUP_M) {
            return;
        }

        /*
         * We now trust the GPS walking tangent.
         *
         * Throw away every handset rotation collected while direction was
         * unknown. The arrow appears exactly on the GPS tangent.
         */
        foregroundDirectionReady =
                true;

        resetGyroOffset();

        gyroCourseAnchorDeg =
                GeoMath.normalizeDegrees(
                        snapshot.courseDeg
                );

        if (foregroundDirectionReadyListener != null) {
            foregroundDirectionReadyListener.run();
        }
    }


    void resetForegroundRotation() {
        /*
         * Used by the navigation/compass button and by no GPS state.
         * GPS course and track remain untouched; only handset rotation is
         * zeroed.
         */
        resetGyroOffset();

        if (foregroundDirectionReady
                && state != null
                && state.courseDeg != null) {

            gyroCourseAnchorDeg =
                    GeoMath.normalizeDegrees(
                            state.courseDeg
                    );
        }

        updateArrow();
    }


    private void compensateGyroForGpsCourse(
            Float courseDeg
    ) {
        if (courseDeg == null) {
            return;
        }

        float normalizedCourse =
                GeoMath.normalizeDegrees(
                        courseDeg
                );

        if (gyroCourseAnchorDeg == null) {
            gyroCourseAnchorDeg =
                    normalizedCourse;

            return;
        }

        float courseDelta =
                GeoMath.shortestAngleDegrees(
                        gyroCourseAnchorDeg,
                        normalizedCourse
                );

        gyroCourseAnchorDeg =
                normalizedCourse;

        gyroOffsetDeg =
                signedDegrees(
                        gyroOffsetDeg
                                - courseDelta
                );
    }


    private double augmentedHeading(
            double baseCourse
    ) {
        return GeoMath.normalizeDegrees(
                baseCourse
                        + gyroOffsetDeg
        );
    }


    private Float augmentedCourse(
            Float baseCourse
    ) {
        if (baseCourse == null) {
            return null;
        }

        return (float) augmentedHeading(
                baseCourse.doubleValue()
        );
    }


    private Float augmentedCourse(
            Double baseCourse
    ) {
        if (baseCourse == null) {
            return null;
        }

        return (float) augmentedHeading(
                baseCourse
        );
    }


    private Float augmentedCourse(
            double baseCourse
    ) {
        return (float) augmentedHeading(
                baseCourse
        );
    }


    private void applyForegroundOrientation() {
        /*
         * Foreground gyro affects ONLY the direction arrow.
         * Map rotation remains GPS-course-only.
         */
        updateArrow();
    }


    private Bitmap arrowBitmap(int fillColor){
        int size=Math.max(64,Math.round(40*activity.getResources().getDisplayMetrics().density));
        Bitmap bm=Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888);
        Canvas c=new Canvas(bm); float x=size/2f;
        Path p=new Path(); p.moveTo(x,size*.06f); p.lineTo(size*.78f,size*.72f);
        p.lineTo(x,size*.60f); p.lineTo(size*.22f,size*.72f); p.close();
        Paint out=new Paint(Paint.ANTI_ALIAS_FLAG); out.setStyle(Paint.Style.STROKE);
        out.setStrokeJoin(Paint.Join.ROUND); out.setStrokeWidth(Math.max(4,size*.075f));
        out.setColor(Color.parseColor("#3D332C"));
        Paint fill=new Paint(Paint.ANTI_ALIAS_FLAG); fill.setStyle(Paint.Style.FILL);
        fill.setColor(fillColor);
        c.drawPath(p,out); c.drawPath(p,fill); return bm;
    }


}
