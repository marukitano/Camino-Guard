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
    private static final String DOT="camino-user-location-dot";
    private static final String ARROW="camino-user-direction";
    private static final String ARROW_IMG="camino-user-direction-arrow";
    private static final String ARROW_IMG_STATIONARY="camino-user-direction-arrow-stationary";
    private static final String TRACK_SRC="camino-debug-gps-track";
    private static final String TRACK="camino-debug-gps-track-line";

    private final Activity activity;
    private final Button recenterButton;
    private MapLibreMap map;
    private GeoJsonSource posSource, trackSource;
    private SymbolLayer arrowLayer;
    private CaminoTrackingService.Snapshot state;
    private Double navigationZoom;
    private boolean followMode;
    private long lastFollowLocationTime = Long.MIN_VALUE;
    private ValueAnimator navigationAnimator;
    private LatLng displayedPosition;
    private Double displayedBearing;
    private boolean started, asked;

    public GpsGyroOrientationController(Activity a){
        activity=a;
        recenterButton=a.findViewById(R.id.map_recenter_button);
        recenterButton.setOnClickListener(v->recenter());
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
        if(trackSource!=null && s.track.size()>=2){
            List<Point> pts=new ArrayList<>();
            for(android.location.Location l:s.track)
                pts.add(Point.fromLngLat(l.getLongitude(),l.getLatitude()));
            trackSource.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(pts)));
        }
        updateArrow();

        if(s.location!=null && s.location.getTime()!=lastFollowLocationTime){
            lastFollowLocationTime=s.location.getTime();
            animateToSnapshot(s);
        }
    }

    private void updateArrow(){
        if(arrowLayer==null || state==null || state.phoneHeadingDeg==null || map==null){
            if(arrowLayer!=null)
                arrowLayer.setProperties(PropertyFactory.iconOpacity(0f));
            return;
        }

        if(state.stationary){
            float screenAngle=(float)norm(
                    state.phoneHeadingDeg - map.getCameraPosition().bearing);

            arrowLayer.setProperties(
                    PropertyFactory.iconImage(ARROW_IMG_STATIONARY),
                    PropertyFactory.iconRotate(screenAngle),
                    PropertyFactory.iconOpacity(1f));
        } else {
            arrowLayer.setProperties(
                    PropertyFactory.iconImage(ARROW_IMG),
                    PropertyFactory.iconRotate(0f),
                    PropertyFactory.iconOpacity(1f));
        }
    }

    private void enterFollowMode(){
        followMode=true;
        navigationZoom=null;
        lastFollowLocationTime=Long.MIN_VALUE;
        recenterButton.setVisibility(View.GONE);

        if(state!=null && state.location!=null){
            follow(state);
            lastFollowLocationTime=state.location.getTime();
        }
    }

    private void leaveFollowMode(){
        if(!followMode)return;
        followMode=false;
        recenterButton.setVisibility(View.VISIBLE);
    }

    private void recenter(){
        enterFollowMode();
    }

    private void animateToSnapshot(CaminoTrackingService.Snapshot s){
        if(map==null || posSource==null || s.location==null)return;

        LatLng targetPosition=new LatLng(
                s.location.getLatitude(),
                s.location.getLongitude());

        if(displayedPosition==null){
            displayedPosition=targetPosition;
            posSource.setGeoJson(Feature.fromGeometry(Point.fromLngLat(
                    targetPosition.getLongitude(),
                    targetPosition.getLatitude())));

            if(s.courseDeg!=null)
                displayedBearing=(double)s.courseDeg;

            if(followMode)
                follow(s);

            return;
        }

        LatLng startPosition=displayedPosition;
        double startBearing=
                displayedBearing!=null
                        ? displayedBearing
                        : map.getCameraPosition().bearing;
        double targetBearing=
                s.courseDeg!=null
                        ? s.courseDeg
                        : startBearing;

        if(navigationAnimator!=null)
            navigationAnimator.cancel();

        navigationAnimator=ValueAnimator.ofFloat(0f,1f);
        navigationAnimator.setDuration(950L);

        navigationAnimator.addUpdateListener(animation -> {
            float t=(float)animation.getAnimatedValue();

            double lat=startPosition.getLatitude()
                    +(targetPosition.getLatitude()-startPosition.getLatitude())*t;
            double lon=startPosition.getLongitude()
                    +(targetPosition.getLongitude()-startPosition.getLongitude())*t;

            double bearing=norm(
                    startBearing
                            + shortestAngle(startBearing,targetBearing)*t
            );

            displayedPosition=new LatLng(lat,lon);
            displayedBearing=bearing;

            posSource.setGeoJson(Feature.fromGeometry(
                    Point.fromLngLat(lon,lat)));

            if(followMode && s.courseDeg!=null){
                if(navigationZoom==null){
                    CameraPosition fit=fitNavigation(s.location,s.courseDeg);
                    navigationZoom=fit!=null?fit.zoom:15.0;
                }

                LatLng cameraTarget=dest(lat,lon,bearing,500.0);

                CameraPosition camera=new CameraPosition.Builder()
                        .target(cameraTarget)
                        .zoom(navigationZoom)
                        .bearing(bearing)
                        .tilt(0)
                        .build();

                /*
                 * ValueAnimator is the tween. moveCamera simply renders each
                 * interpolated frame; no nested MapLibre animation is started.
                 */
                map.moveCamera(
                        CameraUpdateFactory.newCameraPosition(camera)
                );
            }

            updateArrow();
        });

        navigationAnimator.start();
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
