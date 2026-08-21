package com.marukitano.caminoguard;

import android.os.SystemClock;

import android.Manifest;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;

import org.maplibre.android.camera.*;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.maps.*;
import org.maplibre.android.style.layers.*;
import org.maplibre.android.style.sources.GeoJsonSource;
import org.maplibre.geojson.*;

import java.util.*;

public final class GpsGyroOrientationController implements CaminoTrackingService.Listener {
    // CAMINO_ORGANIC_SINGLE_RECENTER_V23
    // CAMINO_CINEMATIC_RECENTER_V22
    // CAMINO_CENTERING_DENSITY_AND_EASE_RESUME_V21
    // CAMINO_SMOOTH_GPS_CAMERA_V19
    // CAMINO_SMOOTH_GPS_CAMERA_V19_COMPILE_FIX_A
    // CAMINO_PROVEN_GPS_MINIMAL_EXTERNAL_FOLLOW_V18
    public static final int LOCATION_PERMISSION_REQUEST=4207;

    private static final String POS_SRC="camino-user-location";
    private static final String DOT="camino-user-location-dot";
    private static final String ARROW="camino-user-direction";
    private static final String ARROW_IMG="camino-user-direction-arrow";
    private static final String ARROW_IMG_STATIONARY="camino-user-direction-arrow-stationary";
    private static final String TRACK_SRC="camino-debug-gps-track";
    private static final String TRACK="camino-debug-gps-track-line";
    private final Activity activity;
    private MapLibreMap map;
    private GeoJsonSource posSource, trackSource;
    private SymbolLayer arrowLayer;
    private CaminoTrackingService.Snapshot state;
    private boolean externalNavigationFollowEnabled;
    private boolean externalNavigationSuspended;
    private boolean externalNavigationReturnAnimating;
    private ValueAnimator externalNavigationReturnAnimator;
    private static final int EXTERNAL_NAVIGATION_RETURN_MS = 1650;
    private Double smoothedExternalCameraBearingDeg;
    private long smoothedExternalCameraBearingTimeMs;
    private static final double EXTERNAL_CAMERA_BEARING_TAU_MS = 2200.0;
    private static final double EXTERNAL_CAMERA_BEARING_DEADBAND_DEG = 1.25;
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

    private final List<TimedPoint> playbackPoints=new ArrayList<>();
    private ValueAnimator playbackAnimator;

    private boolean previousStationary;
    private Double departureHeadingDeg;
    private boolean started, asked;

    public GpsGyroOrientationController(Activity a){
        activity=a;
    }
    public void attachMap(MapLibreMap m){
        map=m;

        map.addOnCameraMoveListener(this::updateArrow);
    }

    public void onStyleLoaded(Style style){
        posSource=new GeoJsonSource(POS_SRC); style.addSource(posSource);
        trackSource=new GeoJsonSource(TRACK_SRC); style.addSource(trackSource);

        LineLayer line=new LineLayer(TRACK,TRACK_SRC);
        line.setProperties(
                PropertyFactory.lineColor(Color.parseColor("#D04432")),
                PropertyFactory.lineWidth(4f),
                PropertyFactory.lineOpacity(.88f),
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

    public void setExternalNavigationFollowEnabled(boolean enabled){
        externalNavigationFollowEnabled=enabled;
        externalNavigationSuspended=false;
        externalNavigationReturnAnimating=false;
        if(externalNavigationReturnAnimator!=null){
            externalNavigationReturnAnimator.cancel();
            externalNavigationReturnAnimator=null;
        }

        if(enabled && map!=null){
            smoothedExternalCameraBearingDeg=
                    norm(map.getCameraPosition().bearing);
            smoothedExternalCameraBearingTimeMs=
                    SystemClock.elapsedRealtime();
        }

        if(!enabled)
            return;

        renderExternalCameraFromLastPose();
    }

    public void setExternalNavigationSuspended(boolean suspended){
        if(!externalNavigationFollowEnabled)
            return;

        externalNavigationSuspended=suspended;

        if(suspended
                && externalNavigationReturnAnimator!=null){
            externalNavigationReturnAnimator.cancel();
            externalNavigationReturnAnimator=null;
            externalNavigationReturnAnimating=false;
        }

        if(suspended)
            return;

        if(map!=null){
            smoothedExternalCameraBearingDeg=
                    norm(map.getCameraPosition().bearing);
            smoothedExternalCameraBearingTimeMs=
                    SystemClock.elapsedRealtime();
        }

        easeExternalCameraFromLastPose();
    }

    public void start(){
        if(started)return; started=true;
        if(activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){
            if(!asked){
                asked=true;
                activity.requestPermissions(new String[]{
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION},LOCATION_PERMISSION_REQUEST);
            }
            return;
        }
        CaminoTrackingService.addListener(this);
        CaminoTrackingService.start(activity);
    }

    public void stop(){
        if(!started)return;
        started=false;
        CaminoTrackingService.removeListener(this); // service keeps GPS+gyro alive
    }

    public void onLocationPermissionResult(int requestCode,String[] p,int[] g){
        if(requestCode==LOCATION_PERMISSION_REQUEST
                && activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED){
            CaminoTrackingService.addListener(this);
            CaminoTrackingService.start(activity);
        }
    }

    @Override public void onTrackingStateChanged(CaminoTrackingService.Snapshot s){
        state=s; activity.runOnUiThread(()->render(s));
    }

    private void render(CaminoTrackingService.Snapshot s){
        if(s==null)return;
        if(trackSource!=null && s.track.size()>=2){
            List<Point> pts=new ArrayList<>();
            for(android.location.Location l:s.track)
                pts.add(Point.fromLngLat(l.getLongitude(),l.getLatitude()));
            trackSource.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(pts)));
        }
        if(previousStationary && !s.stationary){
            Double base=
                    displayedBearing!=null
                            ? displayedBearing
                            : s.courseDeg!=null
                                    ? (double)s.courseDeg
                                    : null;

            if(base!=null){
                double gyroOffset=0.0;

                if(s.courseDeg!=null
                        && s.phoneHeadingDeg!=null){
                    gyroOffset=shortestAngle(
                            s.courseDeg,
                            s.phoneHeadingDeg);
                }

                departureHeadingDeg=
                        norm(base+gyroOffset);

                displayedBearing=
                        departureHeadingDeg;
            }
        }

        previousStationary=s.stationary;

        updateArrow();

        if(s.location!=null && s.location.getTime()!=lastFollowLocationTime){
            lastFollowLocationTime=s.location.getTime();
            animateToSnapshot(s);
        }
    }



    private void updateArrow(){
        if(map==null || arrowLayer==null || state==null)
            return;

        Double baseCourse=displayedBearing;

        if(baseCourse==null && state.courseDeg!=null)
            baseCourse=(double)state.courseDeg;

        if(baseCourse==null){
            arrowLayer.setProperties(
                    PropertyFactory.iconImage(ARROW_IMG),
                    PropertyFactory.iconOpacity(0f));
            return;
        }

        double worldHeading=baseCourse;
        String image=ARROW_IMG;

        if(state.stationary
                && state.courseDeg!=null
                && state.phoneHeadingDeg!=null){
            double gyroOffset=shortestAngle(
                    state.courseDeg,
                    state.phoneHeadingDeg);

            worldHeading=norm(
                    baseCourse+gyroOffset);

            image=ARROW_IMG_STATIONARY;
        }

        float screenAngle=(float)norm(
                worldHeading-map.getCameraPosition().bearing);

        arrowLayer.setProperties(
                PropertyFactory.iconImage(image),
                PropertyFactory.iconRotate(screenAngle),
                PropertyFactory.iconOpacity(1f));
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

        while(playbackPoints.size()>120)
            playbackPoints.remove(0);

        if(displayedPosition==null || previous==null){
            displayedPosition=newest;

            if(s.courseDeg!=null)
                displayedBearing=(double)s.courseDeg;

            posSource.setGeoJson(
                    Feature.fromGeometry(
                            Point.fromLngLat(
                                    newest.getLongitude(),
                                    newest.getLatitude())));

            updateArrow();

            if(externalNavigationFollowEnabled
                    && !externalNavigationSuspended){
                renderExternalCamera(
                        newest,
                        displayedBearing!=null
                                ? displayedBearing
                                : 0.0);
            }

            return;
        }

        LatLng start=displayedPosition;
        LatLng end=newest;

        double directBearing=
                bearingDegrees(
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
                    bearingDegrees(
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
        return norm(
                from
                        +shortestAngle(
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
            return bearingDegrees(a,b);

        return norm(
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

        if(externalNavigationFollowEnabled
                && !externalNavigationSuspended){
            renderExternalCamera(
                    pos,
                    splineBearing);
        }

        updateArrow();
    }







    private void easeExternalCameraFromLastPose(){
        org.maplibre.android.maps.MapView mv=mapView();

        if(map==null
                || mv==null
                || mv.getHeight()<=0
                || displayedPosition==null
                || externalNavigationSuspended
                || !externalNavigationFollowEnabled)
            return;

        if(externalNavigationReturnAnimator!=null){
            externalNavigationReturnAnimator.cancel();
            externalNavigationReturnAnimator=null;
        }

        final CameraPosition startCamera=
                map.getCameraPosition();

        final double finalZoom=
                startCamera.zoom;

        final double bearing=
                norm(
                        startCamera.bearing);

        smoothedExternalCameraBearingDeg=
                bearing;

        smoothedExternalCameraBearingTimeMs=
                SystemClock.elapsedRealtime();

        double metersPerPixel=
                map.getProjection()
                        .getMetersPerPixelAtLatitude(
                                displayedPosition.getLatitude());

        double pixelRatio=
                Math.max(
                        1.0,
                        activity.getResources()
                                .getDisplayMetrics()
                                .density);

        double logicalMapHeightPx=
                mv.getHeight()
                        /pixelRatio;

        double leadMeters=
                Double.isFinite(metersPerPixel)
                        && metersPerPixel>0.0
                        ? metersPerPixel
                                *logicalMapHeightPx
                                /6.0
                        : 0.0;

        final LatLng finalTarget=
                dest(
                        displayedPosition.getLatitude(),
                        displayedPosition.getLongitude(),
                        bearing,
                        leadMeters);

        final LatLng startTarget=
                startCamera.target;

        double dLat=
                Math.toRadians(
                        finalTarget.getLatitude()
                                -startTarget.getLatitude());

        double dLon=
                Math.toRadians(
                        finalTarget.getLongitude()
                                -startTarget.getLongitude());

        double lat1=
                Math.toRadians(
                        startTarget.getLatitude());

        double lat2=
                Math.toRadians(
                        finalTarget.getLatitude());

        double hav=
                Math.sin(dLat/2.0)
                        *Math.sin(dLat/2.0)
                        +Math.cos(lat1)
                        *Math.cos(lat2)
                        *Math.sin(dLon/2.0)
                        *Math.sin(dLon/2.0);

        double distanceMeters=
                6371000.0
                        *2.0
                        *Math.atan2(
                                Math.sqrt(hav),
                                Math.sqrt(
                                        Math.max(
                                                0.0,
                                                1.0-hav)));

        double visibleHalfHeightMeters=
                Math.max(
                        1.0,
                        metersPerPixel
                                *logicalMapHeightPx
                                /2.0);

        double distanceRatio=
                distanceMeters
                        /visibleHalfHeightMeters;

        final double zoomOutLevels=
                distanceRatio<=0.75
                        ? 0.50
                        : Math.min(
                                2.50,
                                Math.max(
                                        0.65,
                                        Math.log(
                                                1.0+distanceRatio)
                                                /Math.log(2.0)
                                )
                        );

        final double longitudeDelta=
                ((finalTarget.getLongitude()
                        -startTarget.getLongitude()
                        +540.0)
                        %360.0)
                        -180.0;

        externalNavigationReturnAnimating=true;

        ValueAnimator animator=
                ValueAnimator.ofFloat(
                        0f,
                        1f);

        externalNavigationReturnAnimator=
                animator;

        animator.setDuration(
                EXTERNAL_NAVIGATION_RETURN_MS);

        animator.setInterpolator(
                new android.view.animation.LinearInterpolator());

        animator.addUpdateListener(
                valueAnimator -> {
                    if(externalNavigationSuspended
                            || !externalNavigationFollowEnabled)
                        return;

                    double t=
                            (float)valueAnimator
                                    .getAnimatedValue();

                    double s=
                            t*t
                                    *(3.0-2.0*t);

                    double lat=
                            startTarget.getLatitude()
                                    +(finalTarget.getLatitude()
                                            -startTarget.getLatitude())
                                            *s;

                    double lon=
                            startTarget.getLongitude()
                                    +longitudeDelta*s;

                    if(lon>180.0)
                        lon-=360.0;

                    if(lon<-180.0)
                        lon+=360.0;

                    double zoomPulse=
                            Math.sin(
                                    Math.PI*s);

                    double zoom=
                            finalZoom
                                    -zoomOutLevels
                                            *zoomPulse;

                    CameraPosition camera=
                            new CameraPosition.Builder(
                                    map.getCameraPosition())
                                    .target(
                                            new LatLng(
                                                    lat,
                                                    lon))
                                    .zoom(zoom)
                                    .bearing(bearing)
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
                new android.animation.AnimatorListenerAdapter(){
                    private boolean cancelled;

                    @Override
                    public void onAnimationCancel(
                            android.animation.Animator animation
                    ){
                        cancelled=true;
                        externalNavigationReturnAnimating=false;

                        if(externalNavigationReturnAnimator
                                ==animation){
                            externalNavigationReturnAnimator=null;
                        }
                    }

                    @Override
                    public void onAnimationEnd(
                            android.animation.Animator animation
                    ){
                        externalNavigationReturnAnimating=false;

                        if(externalNavigationReturnAnimator
                                ==animation){
                            externalNavigationReturnAnimator=null;
                        }

                        if(cancelled
                                || externalNavigationSuspended
                                || !externalNavigationFollowEnabled)
                            return;

                        renderExternalCameraFromLastPose();
                    }
                });

        animator.start();
    }

    private void renderExternalCameraFromLastPose(){
        if(displayedPosition==null)
            return;

        double bearing=
                displayedBearing!=null
                        ? displayedBearing
                        : state!=null && state.courseDeg!=null
                                ? state.courseDeg
                                : map!=null
                                        ? map.getCameraPosition().bearing
                                        : 0.0;

        renderExternalCamera(
                displayedPosition,
                bearing);
    }

    private void renderExternalCamera(
            LatLng pos,
            double ignoredSplineBearing
    ){
        org.maplibre.android.maps.MapView mv=mapView();

        if(map==null
                || mv==null
                || mv.getHeight()<=0
                || externalNavigationSuspended
                || externalNavigationReturnAnimating
                || !externalNavigationFollowEnabled)
            return;

        /*
         * Follow NEVER owns zoom.
         */
        double zoom=
                map.getCameraPosition().zoom;

        /*
         * Rotate the whole map ONLY from the GPS walking course.
         * Phone/gyro orientation may still animate the arrow while stationary,
         * but it can never bounce the map.
         */
        Double desiredCourse=
                state!=null
                        && state.courseDeg!=null
                        ? state.courseDeg.doubleValue()
                        : null;

        double cameraBearing=
                desiredCourse!=null
                        ? smoothExternalCameraBearing(desiredCourse)
                        : smoothedExternalCameraBearingDeg!=null
                                ? smoothedExternalCameraBearingDeg
                                : norm(map.getCameraPosition().bearing);

        double metersPerPixel=
                map.getProjection()
                        .getMetersPerPixelAtLatitude(
                                pos.getLatitude());

        /*
         * IMPORTANT:
         * Patch 024 once wrote top-padding H/3 into the MapLibre camera.
         * Explicit zero padding below prevents that old/saved padding from
         * combining with this H/6 lead.
         *
         * Zero padding + target H/6 ahead => walker at 2H/3:
         * one third behind, two thirds ahead.
         */
        double pixelRatio=
                Math.max(
                        1.0,
                        activity.getResources()
                                .getDisplayMetrics()
                                .density);

        double logicalMapHeightPx=
                mv.getHeight()
                        /pixelRatio;

        double leadMeters=
                Double.isFinite(metersPerPixel)
                        && metersPerPixel>0.0
                        ? metersPerPixel
                                *logicalMapHeightPx
                                /6.0
                        : 0.0;

        LatLng target=
                dest(
                        pos.getLatitude(),
                        pos.getLongitude(),
                        cameraBearing,
                        leadMeters);

        CameraPosition camera=
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
                CameraUpdateFactory.newCameraPosition(
                        camera));
    }

    private double smoothExternalCameraBearing(
            double targetBearing
    ){
        double target=
                norm(targetBearing);

        long now=
                SystemClock.elapsedRealtime();

        if(smoothedExternalCameraBearingDeg==null){
            smoothedExternalCameraBearingDeg=
                    map!=null
                            ? norm(map.getCameraPosition().bearing)
                            : target;
            smoothedExternalCameraBearingTimeMs=now;
            return smoothedExternalCameraBearingDeg;
        }

        long dtMs=
                Math.max(
                        0L,
                        Math.min(
                                100L,
                                now-smoothedExternalCameraBearingTimeMs));

        smoothedExternalCameraBearingTimeMs=now;

        double current=
                smoothedExternalCameraBearingDeg;

        double delta=
                ((target-current+540.0)%360.0)-180.0;

        /*
         * Suppress small course noise completely.
         */
        if(Math.abs(delta)
                <= EXTERNAL_CAMERA_BEARING_DEADBAND_DEG){
            return current;
        }

        double alpha=
                1.0-Math.exp(
                        -dtMs
                                /EXTERNAL_CAMERA_BEARING_TAU_MS);

        smoothedExternalCameraBearingDeg=
                norm(
                        current
                                +delta*alpha);

        return smoothedExternalCameraBearingDeg;
    }

    private org.maplibre.android.maps.MapView mapView(){
        return activity.findViewById(
                R.id.map_view);
    }

    private static double bearingDegrees(LatLng a,LatLng b){
        double p1=Math.toRadians(a.getLatitude());
        double p2=Math.toRadians(b.getLatitude());
        double dl=Math.toRadians(
                b.getLongitude()-a.getLongitude());

        double y=Math.sin(dl)*Math.cos(p2);
        double x=Math.cos(p1)*Math.sin(p2)
                -Math.sin(p1)*Math.cos(p2)*Math.cos(dl);

        return norm(Math.toDegrees(Math.atan2(y,x)));
    }

    private static double distanceMeters(LatLng a,LatLng b){
        double R=6371008.8;
        double p1=Math.toRadians(a.getLatitude());
        double p2=Math.toRadians(b.getLatitude());
        double dp=p2-p1;
        double dl=Math.toRadians(
                b.getLongitude()-a.getLongitude());

        double h=Math.sin(dp/2)*Math.sin(dp/2)
                +Math.cos(p1)*Math.cos(p2)
                *Math.sin(dl/2)*Math.sin(dl/2);

        return 2*R*Math.asin(Math.min(1.0,Math.sqrt(h)));
    }

    private static LatLng dest(double lat,double lon,double bearing,double meters){
        double R=6371008.8, p1=Math.toRadians(lat), l1=Math.toRadians(lon);
        double b=Math.toRadians(bearing), d=meters/R;
        double p2=Math.asin(Math.sin(p1)*Math.cos(d)+Math.cos(p1)*Math.sin(d)*Math.cos(b));
        double l2=l1+Math.atan2(Math.sin(b)*Math.sin(d)*Math.cos(p1),
                Math.cos(d)-Math.sin(p1)*Math.sin(p2));
        return new LatLng(Math.toDegrees(p2),Math.toDegrees(l2));
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

    private static double shortestAngle(double from,double to){
        double d=norm(to-from);
        return d>180.0?d-360.0:d;
    }

    private static double norm(double v){v%=360;return v<0?v+360:v;}
}
