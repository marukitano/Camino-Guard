package com.marukitano.caminoguard;

import android.Manifest;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;

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
        implements CaminoTrackingService.Listener {

    public static final int LOCATION_PERMISSION_REQUEST =
            4207;

    private static final int MAX_PLAYBACK_POINTS =
            3;

    private static final String POS_SRC="camino-user-location";
    private static final String DOT="camino-user-location-dot";
    private static final String ARROW="camino-user-direction";
    private static final String ARROW_IMG="camino-user-direction-arrow";
    private static final String ARROW_IMG_STATIONARY="camino-user-direction-arrow-stationary";
    private static final String TRACK_SRC="camino-debug-gps-track";
    private static final String TRACK="camino-debug-gps-track-line";
    private final Activity activity;
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

    private boolean previousStationary;
    private Double departureHeadingDeg;
    private boolean started;
    private boolean asked;

    public GpsGyroOrientationController(
            Activity activity
    ) {
        this.activity =
                activity;

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

    public void stop() {
        if (!started) {
            return;
        }

        started =
                false;

        /*
         * The process-wide tracking service may still have other listeners.
         * Stopping this controller therefore only detaches its own listener.
         */
        CaminoTrackingService.removeListener(
                this
        );
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

        liveNavigationCameraController.setCourseDeg(
                s.courseDeg);
        if(trackSource!=null && s.track!=renderedTrack){
            renderedTrack=s.track;

            if(s.track.size()>=2){
                List<Point> pts=new ArrayList<>();
                for(android.location.Location l:s.track)
                    pts.add(Point.fromLngLat(l.getLongitude(),l.getLatitude()));
                trackSource.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(pts)));
            }
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
                    gyroOffset=GeoMath.shortestAngleDegrees(
                            s.courseDeg,
                            s.phoneHeadingDeg);
                }

                departureHeadingDeg=
                        GeoMath.normalizeDegrees(base+gyroOffset);

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
            double gyroOffset=GeoMath.shortestAngleDegrees(
                    state.courseDeg,
                    state.phoneHeadingDeg);

            worldHeading=GeoMath.normalizeDegrees(
                    baseCourse+gyroOffset);

            image=ARROW_IMG_STATIONARY;
        }

        float screenAngle=(float)GeoMath.normalizeDegrees(
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

            updateArrow();

            liveNavigationCameraController.onPose(
                    newest);

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
                pos);

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
