package com.marukitano.caminoguard;

import android.app.Activity;
import android.os.Bundle;

import org.maplibre.android.MapLibre;
import org.maplibre.android.maps.MapView;


public final class MainActivity extends Activity {

    private MapView mapView;
    private GpsGyroOrientationController orientationController;
    private CaminoController caminoController;
    private MapCoordinator mapCoordinator;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        CaminoConfig.initialize(this);

        MapLibre.getInstance(this);
        setContentView(R.layout.activity_main);

        mapView = findViewById(R.id.map_view);

        org.maplibre.android.geometry.LatLng startupPosition =
                MapCoordinator.startupPosition();

        orientationController =
                new GpsGyroOrientationController(this);

        caminoController =
                new CaminoController(
                        this,
                        mapView,
                        startupPosition
                );

        caminoController.configureLivePositionMode(startupPosition);
        caminoController.setLiveNavigationController(orientationController);

        mapCoordinator =
                new MapCoordinator(
                        this,
                        mapView,
                        findViewById(R.id.map_setup_panel),
                        findViewById(R.id.map_setup_status),
                        findViewById(R.id.map_setup_progress),
                        orientationController,
                        caminoController
                );

        mapCoordinator.start();
    }



    @Override
    protected void onStart() {
        super.onStart();
        mapView.onStart();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mapView.onResume();
        orientationController.start();
        caminoController.startLivePosition();
    }

    @Override
    protected void onPause() {
        caminoController.stopLivePosition();
        orientationController.stop();
        mapView.onPause();
        super.onPause();
    }

    @Override
    protected void onStop() {
        mapView.onStop();
        super.onStop();
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        orientationController.onLocationPermissionResult(
                requestCode, permissions, grantResults
        );
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        mapView.onLowMemory();
    }

    @Override
    protected void onDestroy() {
        mapCoordinator.destroy();
        mapView.onDestroy();
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        mapView.onSaveInstanceState(outState);
    }

}
