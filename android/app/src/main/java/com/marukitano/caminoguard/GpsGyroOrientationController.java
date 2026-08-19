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
import android.view.View;
import android.widget.Button;

import org.maplibre.android.camera.*;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.maps.*;
import org.maplibre.android.style.layers.*;
import org.maplibre.android.style.sources.GeoJsonSource;
import org.maplibre.geojson.*;

import java.util.*;

public final class GpsGyroOrientationController implements CaminoTrackingService.Listener {
    public static final int LOCATION_PERMISSION_REQUEST=4207;

    private static final String POS_SRC="camino-user-location";
    private static final String ARROW="camino-user-direction";
    private static final String ARROW_IMG="camino-user-direction-arrow";
    private static final String ARROW_IMG_STATIONARY="camino-user-direction-arrow-stationary";

    private final Activity activity;
    private final Button recenterButton;
    private final Button rotateButton;
    private MapLibreMap map;
    private GeoJsonSource posSource;
    private SymbolLayer arrowLayer;
    private CaminoTrackingService.Snapshot state;
    private Double navigationZoom;
    private boolean followMode;
    private long lastFollowLocationTime = Long.MIN_VALUE;
    private LatLng displayedPosition;
    private Double displayedBearing;

    private static final class TimedPoint {
        final LatLng point;
        final long timeMs;

        TimedPoint(LatLng point,long timeMs){
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
        recenterButton=a.findViewById(R.id.map_recenter_button);
        rotateButton=a.findViewById(R.id.map_rotate_button);
        recenterButton.setOnClickListener(v->recenter());
        rotateButton.setOnClickListener(v->followAtCurrentZoom());
    }
    public void attachMap(MapLibreMap m){
        map=m;

        map.addOnCameraMoveStartedListener(reason -> {
            if(reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE){
                leaveFollowMode();
            }
        });

        map.addOnCameraMoveListener(this::updateArrow);
    }

    public void onStyleLoaded(Style style){
        posSource=new GeoJsonSource(POS_SRC);
        style.addSource(posSource);

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

    public void start(){
        if(started)return; started=true;
        enterFollowMode();
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

    private void enterFollowMode(){
        followMode=true;
        navigationZoom=null;
        lastFollowLocationTime=Long.MIN_VALUE;
        recenterButton.setVisibility(View.GONE);
        rotateButton.setVisibility(View.GONE);

        if(state!=null && state.location!=null){
            follow(state);
            lastFollowLocationTime=state.location.getTime();
        }
    }

    private void leaveFollowMode(){
        if(!followMode)return;
        followMode=false;
        recenterButton.setVisibility(View.VISIBLE);
        rotateButton.setVisibility(View.VISIBLE);
    }

    private void recenter(){
        enterFollowMode();
    }

    private void followAtCurrentZoom(){
        if(map==null)return;

        /*
         * Restore centering + course-up rotation but keep the zoom level
         * currently chosen by the user.
         */
        navigationZoom=map.getCameraPosition().zoom;
        followMode=true;
        lastFollowLocationTime=Long.MIN_VALUE;

        recenterButton.setVisibility(View.GONE);
        rotateButton.setVisibility(View.GONE);

        if(state!=null && state.location!=null){
            follow(state);
            lastFollowLocationTime=state.location.getTime();
        }
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

        playbackPoints.add(
                new TimedPoint(
                        newest,
                        timeMs));

        /* Only the newest three fixes are used by the active algorithm. */
        while(playbackPoints.size()>3)
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

        double h00=2*t3-3*t2+1;
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

        if(followMode){
            double mapBearing=
                    state!=null && state.courseDeg!=null
                            ? state.courseDeg
                            : splineBearing;

            if(navigationZoom==null)
                navigationZoom=map.getCameraPosition().zoom;

            LatLng cameraTarget=dest(
                    pos.getLatitude(),
                    pos.getLongitude(),
                    mapBearing,
                    500.0);

            CameraPosition camera=
                    new CameraPosition.Builder()
                            .target(cameraTarget)
                            .zoom(navigationZoom)
                            .bearing(mapBearing)
                            .tilt(0)
                            .build();

            map.moveCamera(
                    CameraUpdateFactory.newCameraPosition(
                            camera));
        }

        updateArrow();
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


    private void follow(CaminoTrackingService.Snapshot s){
        if(map==null || s.location==null)return;

        if(s.courseDeg==null){
            if(navigationZoom==null){
                CameraPosition c=map.getCameraPosition();
                CameraPosition n=new CameraPosition.Builder(c)
                        .target(new LatLng(s.location.getLatitude(),s.location.getLongitude()))
                        .zoom(Math.max(c.zoom,15.0)).build();
                map.moveCamera(
                        CameraUpdateFactory.newCameraPosition(n)
                );
            }
            return;
        }

        double course=s.courseDeg;
        if(navigationZoom==null){
            CameraPosition fit=fitNavigation(s.location,course);
            navigationZoom=fit!=null?fit.zoom:15.0;
        }

        LatLng target=dest(
                s.location.getLatitude(),s.location.getLongitude(),course,500.0);

        CameraPosition c=new CameraPosition.Builder()
                .target(target).zoom(navigationZoom).bearing(course).tilt(0).build();
        displayedPosition=
                new LatLng(
                        s.location.getLatitude(),
                        s.location.getLongitude()
                );
        displayedBearing=course;

        map.moveCamera(
                CameraUpdateFactory.newCameraPosition(c)
        );
    }

    private CameraPosition fitNavigation(android.location.Location o,double course){
        // 2 km ahead, 1 km behind, +/-1 km sideways.
        List<Point> ring=new ArrayList<>();
        LatLng a=offset(o,course,2000,-1000), b=offset(o,course,2000,1000);
        LatLng c=offset(o,course,-1000,1000), d=offset(o,course,-1000,-1000);
        for(LatLng p:new LatLng[]{a,b,c,d,a})
            ring.add(Point.fromLngLat(p.getLongitude(),p.getLatitude()));
        int pad=Math.round(24*activity.getResources().getDisplayMetrics().density);
        return map.getCameraForGeometry(LineString.fromLngLats(ring),
                new int[]{pad,pad,pad,pad},course,0);
    }

    private static LatLng offset(android.location.Location o,double f,double forward,double right){
        LatLng p=dest(o.getLatitude(),o.getLongitude(),
                forward>=0?f:norm(f+180),Math.abs(forward));
        return dest(p.getLatitude(),p.getLongitude(),
                right>=0?norm(f+90):norm(f-90),Math.abs(right));
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
