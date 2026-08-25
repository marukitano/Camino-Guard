package com.marukitano.caminoguard;

import android.app.Activity;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.maplibre.android.camera.CameraPosition;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.MapView;
import org.maplibre.android.maps.Style;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Owns MapLibre startup and offline-style orchestration.
 *
 * Android lifecycle remains in MainActivity. GPS/gyro, Camino interaction,
 * rendering algorithms and domain logic remain in their dedicated components.
 */
final class MapCoordinator {

    private final Activity activity;
    private final MapView mapView;
    private final View setupPanel;
    private final TextView setupStatus;
    private final ProgressBar setupProgress;
    private final GpsGyroOrientationController orientationController;
    private final CaminoController caminoController;

    private final ExecutorService ioExecutor =
            Executors.newSingleThreadExecutor();

    private final CaminoMapRenderer caminoMapRenderer;
    private final OfflineMapRepository offlineMapRepository;
    private final MapStyleProvider mapStyleProvider;
    private final CaminoUiStateStore uiStateStore;

    MapCoordinator(
            Activity activity,
            MapView mapView,
            View setupPanel,
            TextView setupStatus,
            ProgressBar setupProgress,
            GpsGyroOrientationController orientationController,
            CaminoController caminoController
    ) {
        this.activity = activity;
        this.mapView = mapView;
        this.setupPanel = setupPanel;
        this.setupStatus = setupStatus;
        this.setupProgress = setupProgress;
        this.orientationController = orientationController;
        this.caminoController = caminoController;

        caminoMapRenderer =
                new CaminoMapRenderer(
                        activity
                );

        offlineMapRepository =
                new OfflineMapRepository(
                        activity,
                        new OfflineMapRepository.ProgressListener() {
                            @Override
                            public void onPreparing() {
                                activity.runOnUiThread(
                                        () -> {
                                            setupPanel.setVisibility(
                                                    View.VISIBLE
                                            );
                                            setupProgress.setVisibility(
                                                    View.VISIBLE
                                            );
                                            setupProgress.setIndeterminate(
                                                    false
                                            );
                                            setupProgress.setProgress(
                                                    0
                                            );
                                            setupStatus.setText(
                                                    R.string.offline_map_preparing
                                            );
                                        }
                                );
                            }

                            @Override
                            public void onProgress(
                                    int progress,
                                    int percent
                            ) {
                                activity.runOnUiThread(
                                        () -> {
                                            setupProgress.setProgress(
                                                    progress
                                            );
                                            setupStatus.setText(
                                                    activity.getString(
                                                            R.string.offline_map_progress,
                                                            percent
                                                    )
                                            );
                                        }
                                );
                            }
                        }
                );

        mapStyleProvider =
                new MapStyleProvider(
                        activity
                );

        uiStateStore =
                new CaminoUiStateStore(
                        activity
                );
    }

    static LatLng startupPosition() {
        return new LatLng(
                CaminoConfig.get().doubleValue(
                        "startup.latitude"
                ),
                CaminoConfig.get().doubleValue(
                        "startup.longitude"
                )
        );
    }

    void start() {
        mapView.getMapAsync(
                map -> {
                    map.getUiSettings()
                            .setRotateGesturesEnabled(
                                    true
                            );

                    /*
                     * Source attribution remains embedded in the style, but the
                     * long always-visible MapLibre attribution row is replaced
                     * by Camino Guard's compact info button.
                     */
                    map.getUiSettings()
                            .setAttributionEnabled(
                                    false
                            );

                    map.getUiSettings()
                            .setLogoEnabled(
                                    false
                            );

                    map.getUiSettings()
                            .setDisableRotateWhenScaling(
                                    false
                            );

                    map.getUiSettings()
                            .setIncreaseScaleThresholdWhenRotating(
                                    false
                            );

                    orientationController.attachMap(
                            map
                    );

                    caminoController.attachMap(
                            map
                    );

                    /*
                     * IMPORTANT startup ordering:
                     *
                     * MapLibre may emit an initial CameraIdle for its temporary
                     * default/world camera. Do NOT listen for persistence yet,
                     * otherwise that transient camera overwrites the user's
                     * saved position before restoreCameraPosition() can read it.
                     *
                     * 1. restore the startup camera
                     * 2. one UI turn later enable persistence
                     */
                    mapView.post(
                            () -> {
                                configureStartupCamera(
                                        map
                                );

                                mapView.post(
                                        () -> map.addOnCameraIdleListener(
                                                () -> uiStateStore
                                                        .saveCameraPosition(
                                                                map.getCameraPosition()
                                                        )
                                        )
                                );
                            }
                    );

                    ioExecutor.execute(
                            () -> prepareOfflineMap(
                                    map
                            )
                    );
                }
        );
    }

    void destroy() {
        ioExecutor.shutdownNow();
    }


    private void configureStartupCamera(
            MapLibreMap map
    ) {
        map.setMinZoomPreference(
                0.0
        );

        CameraPosition fallback =
                new CameraPosition.Builder()
                        .target(
                                startupPosition()
                        )
                        .zoom(
                                CaminoConfig.get().doubleValue(
                                        "startup.zoom"
                                )
                        )
                        .bearing(
                                CaminoConfig.get().doubleValue(
                                        "startup.bearing"
                                )
                        )
                        .tilt(
                                CaminoConfig.get().doubleValue(
                                        "startup.tilt"
                                )
                        )
                        .build();

        /*
         * First launch uses CaminoConfig. Every later launch restores the
         * exact last camera: center, zoom, rotation and tilt.
         */
        map.setCameraPosition(
                uiStateStore.restoreCameraPosition(
                        fallback
                )
        );
    }

    private void prepareOfflineMap(
            MapLibreMap map
    ) {
        try {
            OfflineMapRepository.InstalledMaps installedMaps =
                    offlineMapRepository.ensureInstalled();

            String finalStyleJson =
                    mapStyleProvider.buildStyle(
                            installedMaps
                    );

            activity.runOnUiThread(
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

                                    caminoController.onStyleLoaded(
                                            style
                                    );

                                    caminoMapRenderer.onStyleLoaded(
                                            style,
                                            caminoController.routesForRendering(),
                                            caminoController.stageTopologyForRendering(),
                                            map
                                    );

                                    orientationController.onStyleLoaded(
                                            style
                                    );

                                }
                        );
                    }
            );

        } catch (Exception error) {
            activity.runOnUiThread(
                    () -> {
                        setupProgress.setVisibility(
                                View.GONE
                        );

                        setupStatus.setText(
                                activity.getString(
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
}
