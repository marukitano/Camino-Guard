package com.marukitano.caminoguard;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.maplibre.android.MapLibre;
import org.maplibre.android.camera.CameraPosition;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.maps.MapView;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.Style;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();

    private MapView mapView;
    private View setupPanel;
    private TextView setupStatus;
    private ProgressBar setupProgress;
    private GpsGyroOrientationController orientationController;
    private CaminoController caminoController;
    private CaminoMapRenderer caminoMapRenderer;
    private OfflineMapRepository offlineMapRepository;
    private MapStyleProvider mapStyleProvider;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        CaminoConfig.initialize(this);

        MapLibre.getInstance(this);
        setContentView(R.layout.activity_main);

        mapView = findViewById(R.id.map_view);
        setupPanel = findViewById(R.id.map_setup_panel);
        setupStatus = findViewById(R.id.map_setup_status);
        setupProgress = findViewById(R.id.map_setup_progress);
        LatLng startupPosition =
                new LatLng(
                        CaminoConfig.get().doubleValue("startup.latitude"),
                        CaminoConfig.get().doubleValue("startup.longitude")
                );

        orientationController =
                new GpsGyroOrientationController(this);

        caminoController =
                new CaminoController(
                        this,
                        mapView,
                        startupPosition
                );

        caminoMapRenderer =
                new CaminoMapRenderer(this);

        offlineMapRepository =
                new OfflineMapRepository(
                        this,
                        new OfflineMapRepository.ProgressListener() {
                            @Override
                            public void onPreparing() {
                                runOnUiThread(() -> {
                                    setupPanel.setVisibility(View.VISIBLE);
                                    setupProgress.setVisibility(View.VISIBLE);
                                    setupProgress.setIndeterminate(false);
                                    setupProgress.setProgress(0);
                                    setupStatus.setText(
                                            R.string.offline_map_preparing
                                    );
                                });
                            }

                            @Override
                            public void onProgress(
                                    int progress,
                                    int percent
                            ) {
                                runOnUiThread(() -> {
                                    setupProgress.setProgress(progress);
                                    setupStatus.setText(
                                            getString(
                                                    R.string.offline_map_progress,
                                                    percent
                                            )
                                    );
                                });
                            }
                        }
                );

        mapStyleProvider =
                new MapStyleProvider(this);

        orientationController.setExternalNavigationManaged(true);
        caminoController.configureLivePositionMode(startupPosition);
        caminoController.setLiveNavigationController(orientationController);

        mapView.getMapAsync(map -> {
            // Allow natural two-finger zoom + rotate at the same time.
            // MapLibre can otherwise disable rotation when scale wins the
            // gesture race first, which makes rotation feel intermittent.
            map.getUiSettings().setRotateGesturesEnabled(true);
            map.getUiSettings().setDisableRotateWhenScaling(false);
            map.getUiSettings().setIncreaseScaleThresholdWhenRotating(false);
orientationController.attachMap(map);
            caminoController.attachMap(map);

            /*
             * Wait until MapView has its real pixel size. Then calculate the
             * zoom at which the Iberian bounds just fit this phone display.
             */
            mapView.post(() -> configureStartupCamera(map));

            ioExecutor.execute(() -> prepareOfflineMap(map));
        });
    }

    private void configureStartupCamera(MapLibreMap map) {
        map.setMinZoomPreference(0.0);
        map.setCameraPosition(
                new CameraPosition.Builder()
                        .target(
                                new LatLng(
                                        CaminoConfig.get().doubleValue("startup.latitude"),
                                        CaminoConfig.get().doubleValue("startup.longitude")
                                )
                        )
                        .zoom(CaminoConfig.get().doubleValue("startup.zoom"))
                        .bearing(CaminoConfig.get().doubleValue("startup.bearing"))
                        .tilt(CaminoConfig.get().doubleValue("startup.tilt"))
                        .build()
        );
    }


    private void prepareOfflineMap(MapLibreMap map) {
        try {
            OfflineMapRepository.InstalledMaps installedMaps =
                    offlineMapRepository.ensureInstalled();

            String finalStyleJson =
                    mapStyleProvider.buildStyle(
                            installedMaps
                    );

            runOnUiThread(
                    () -> {
                        setupStatus.setText(
                                R.string.offline_map_loading
                        );

                        setupProgress.setIndeterminate(
                                true
                        );

                        map.setStyle(
                                new Style.Builder()
                                        .fromJson(
                                                finalStyleJson
                                        ),
                                style -> {
                                    setupPanel.setVisibility(
                                            View.GONE
                                    );

                                    caminoMapRenderer.onStyleLoaded(
                                            style
                                    );

                                    orientationController.onStyleLoaded(
                                            style
                                    );

                                    caminoController.onStyleLoaded(
                                            style
                                    );
                                }
                        );
                    }
            );

        } catch (Exception error) {
            runOnUiThread(
                    () -> {
                        setupProgress.setVisibility(
                                View.GONE
                        );

                        setupStatus.setText(
                                getString(
                                        R.string.offline_map_error,
                                        error.getMessage() == null
                                                ? error.getClass()
                                                .getSimpleName()
                                                : error.getMessage()
                                )
                        );
                    }
            );
        }
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
        ioExecutor.shutdownNow();
        mapView.onDestroy();
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        mapView.onSaveInstanceState(outState);
    }

}
