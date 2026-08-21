package com.marukitano.caminoguard;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.json.JSONObject;
import org.maplibre.android.MapLibre;
import org.maplibre.android.camera.CameraPosition;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.geometry.LatLngBounds;
import org.maplibre.android.maps.MapView;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.Style;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {

    // CAMINO_CLEAN_CLIPPED_SCHAFFHAUSEN_V30

    // CAMINO_ORIGINAL_MAPLIBRE_WORLD_V25

    // CAMINO_DUAL_REGIONAL_MAP_SOURCES_V20

    // CAMINO_WORLD_CAMERA_UNLOCK_V19

    // CAMINO_TUX_LOOP_NAV_BRIDGE_V15

    // CAMINO_SCHAFFHAUSEN_REAL_GPS_INTEGRATION_V14

    // TEMPORARY: local walking test for GPS + gyro in Schaffhausen.
    private static final boolean DEBUG_SCHAFFHAUSEN_MAP = true;

    /* TEMPORARY: interactive Camino distance test in Almeria. */
    private static final boolean DEBUG_CAMINO_TAP_ALMERIA = false;
    private static final LatLng DEBUG_ALMERIA_POSITION =
            new LatLng(36.83838096, -2.46707205);
    private static final LatLng DEBUG_SCHAFFHAUSEN_POSITION =
            new LatLng(47.69811, 8.63268);
    private static final String DEBUG_SCHAFFHAUSEN_TRACKS_ASSET_URL =
            "asset://camino/debug-schaffhausen-tracks.geojson";
    private static final String DEBUG_MAP_ASSET = "maps/debug-schaffhausen.pmtiles";
    private static final String DEBUG_MAP_METADATA_ASSET = "maps/debug-schaffhausen.metadata.json";
    private static final String DEBUG_LOCAL_MAP_FILENAME = "debug-schaffhausen.pmtiles";
    private static final String PREF_INSTALLED_DEBUG_SHA256 = "installed_debug_schaffhausen_sha256";
    private static final String DEBUG_CONTOUR_ASSET =
            "maps/debug-schaffhausen-contours.pmtiles";
    private static final String DEBUG_CONTOUR_METADATA_ASSET =
            "maps/debug-schaffhausen-contours.metadata.json";
    private static final String DEBUG_LOCAL_CONTOUR_FILENAME =
            "debug-schaffhausen-contours.pmtiles";
    private static final String PREF_INSTALLED_DEBUG_CONTOUR_SHA256 =
            "installed_debug_schaffhausen_contour_sha256";
    private static final String WORLD_MAP_ASSET =
            "maps/world-maplibre.pmtiles";
    private static final String WORLD_MAP_METADATA_ASSET =
            "maps/world-maplibre.metadata.json";
    private static final String WORLD_LOCAL_MAP_FILENAME =
            "world-maplibre.pmtiles";
    private static final String PREF_INSTALLED_WORLD_SHA256 =
            "installed_world_maplibre_sha256";
    private static final LatLngBounds SCHAFFHAUSEN_DEBUG_BOUNDS =
            new LatLngBounds.Builder()
                    .include(new LatLng(47.80, 8.45))
                    .include(new LatLng(47.60, 8.82))
                    .build();

    private static final String MAP_ASSET = "maps/iberia.pmtiles";
    private static final String MAP_METADATA_ASSET = "maps/iberia.metadata.json";
    private static final String CONTOUR_ASSET = "maps/contours.pmtiles";
    private static final String CONTOUR_METADATA_ASSET = "maps/contours.metadata.json";
    private static final String TERRAIN_ASSET = "maps/terrain.pmtiles";
    private static final String TERRAIN_METADATA_ASSET = "maps/terrain.metadata.json";
    private static final String STYLE_ASSET = "styles/camino-basic.json";
    private static final String STYLE_PM_TILES_TOKEN = "__PMTILES_URL__";
    private static final String STYLE_SCHAFFHAUSEN_PM_TILES_TOKEN =
            "__SCHAFFHAUSEN_PMTILES_URL__";
    private static final String STYLE_SCHAFFHAUSEN_CONTOURS_TOKEN =
            "__SCHAFFHAUSEN_CONTOURS_URL__";
    private static final String STYLE_WORLD_PM_TILES_TOKEN =
            "__WORLD_PM_TILES_URL__";
    private static final String STYLE_CONTOURS_TOKEN = "__CONTOURS_URL__";
    private static final String STYLE_TERRAIN_TOKEN = "__TERRAIN_URL__";
    private static final String LOCAL_MAP_DIRECTORY = "maps";
    private static final String LOCAL_MAP_FILENAME = "iberia.pmtiles";
    private static final String LOCAL_CONTOUR_FILENAME = "contours.pmtiles";
    private static final String LOCAL_TERRAIN_FILENAME = "terrain.pmtiles";
    private static final String PREFS_NAME = "offline_map";
    private static final String PREF_INSTALLED_SHA256 = "installed_sha256";
    private static final String PREF_INSTALLED_CONTOUR_SHA256 =
            "installed_contour_sha256";
    private static final long PROGRESS_UPDATE_INTERVAL_MS = 150L;
    private static final int COPY_BUFFER_BYTES = 4 * 1024 * 1024;
    private static final int OVERVIEW_PADDING_DP = 18;

    /*
     * Camino Guard is an Iberian Camino app, not a world map.
     * These camera bounds contain mainland Portugal, mainland Spain and
     * Saint-Jean-Pied-de-Port, while preventing irrelevant world panning.
     */
    private static final LatLngBounds IBERIA_CAMERA_BOUNDS =
            new LatLngBounds.Builder()
                    .include(new LatLng(43.90, -10.10))
                    .include(new LatLng(35.70, 3.50))
                    .build();

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();

    private MapView mapView;
    private View setupPanel;
    private TextView setupStatus;
    private ProgressBar setupProgress;
    private GpsGyroOrientationController orientationController;
    private CaminoTapDebugController caminoTapDebugController;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        MapLibre.getInstance(this);
        setContentView(R.layout.activity_main);

        mapView = findViewById(R.id.map_view);
        setupPanel = findViewById(R.id.map_setup_panel);
        setupStatus = findViewById(R.id.map_setup_status);
        setupProgress = findViewById(R.id.map_setup_progress);
        orientationController = new GpsGyroOrientationController(this);
        caminoTapDebugController = new CaminoTapDebugController(
                this, mapView, DEBUG_ALMERIA_POSITION);

        if (useSchaffhausenDebugMap()) {
            orientationController.setExternalNavigationManaged(
                    true
            );

            caminoTapDebugController.configureLivePositionMode(
                    DEBUG_SCHAFFHAUSEN_POSITION
            );

            caminoTapDebugController.setLiveNavigationController(
                    orientationController
            );
        }

        mapView.getMapAsync(map -> {
            // Allow natural two-finger zoom + rotate at the same time.
            // MapLibre can otherwise disable rotation when scale wins the
            // gesture race first, which makes rotation feel intermittent.
            map.getUiSettings().setRotateGesturesEnabled(true);
            map.getUiSettings().setDisableRotateWhenScaling(false);
            map.getUiSettings().setIncreaseScaleThresholdWhenRotating(false);
if (DEBUG_CAMINO_TAP_ALMERIA) {
                caminoTapDebugController.attachMap(map);
            } else {
                orientationController.attachMap(map);

                if (useSchaffhausenDebugMap()) {
                    caminoTapDebugController.attachMap(map);
                }
            }

            /*
             * Wait until MapView has its real pixel size. Then calculate the
             * zoom at which the Iberian bounds just fit this phone display.
             */
            mapView.post(() -> configureIberiaOverview(map));

            ioExecutor.execute(() -> prepareOfflineMap(map));
        });
    }

    private void configureIberiaOverview(MapLibreMap map) {
        int padding = dpToPx(OVERVIEW_PADDING_DP);
        int[] edgePadding = {padding, padding, padding, padding};

        LatLngBounds overviewBounds = useSchaffhausenDebugMap()
                ? SCHAFFHAUSEN_DEBUG_BOUNDS
                : IBERIA_CAMERA_BOUNDS;

        CameraPosition overview =
                map.getCameraForLatLngBounds(
                        overviewBounds,
                        edgePadding
                );

        if (overview != null) {
            /*
             * Users may zoom in as far as they like, but cannot zoom farther
             * out than the overview that fits Iberia on this display.
             */
            /*
             * Camera freedom is global. Offline map coverage is a rendering
             * concern, not a navigation restriction.
             */
            map.setMinZoomPreference(0.0);

            if (DEBUG_CAMINO_TAP_ALMERIA) {
                map.setCameraPosition(
                        new CameraPosition.Builder()
                                .target(DEBUG_ALMERIA_POSITION)
                                .zoom(14.8)
                                .bearing(0.0)
                                .tilt(0.0)
                                .build()
                );
            } else {
                map.setCameraPosition(overview);
            }
        }
    }

    private static boolean useSchaffhausenDebugMap() {
        return DEBUG_SCHAFFHAUSEN_MAP && !DEBUG_CAMINO_TAP_ALMERIA;
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    private void prepareOfflineMap(MapLibreMap map) {
        try {
            /*
             * V20: regional map sources are additive, never exclusive.
             *
             * Iberia remains the normal protomaps source.
             * Schaffhausen is installed as a second PMTiles source.
             */
            MapMetadata iberiaMapMetadata =
                    readMapMetadata(
                            MAP_METADATA_ASSET
                    );

            File localIberiaMap =
                    ensureMapInstalled(
                            iberiaMapMetadata,
                            MAP_ASSET,
                            LOCAL_MAP_FILENAME,
                            PREF_INSTALLED_SHA256
                    );

            MapMetadata schaffhausenMapMetadata =
                    readMapMetadata(
                            DEBUG_MAP_METADATA_ASSET
                    );

            File localSchaffhausenMap =
                    ensureMapInstalled(
                            schaffhausenMapMetadata,
                            DEBUG_MAP_ASSET,
                            DEBUG_LOCAL_MAP_FILENAME,
                            PREF_INSTALLED_DEBUG_SHA256
                    );

            MapMetadata schaffhausenContourMetadata =
                    readMapMetadata(
                            DEBUG_CONTOUR_METADATA_ASSET
                    );

            File localSchaffhausenContours =
                    ensureMapInstalled(
                            schaffhausenContourMetadata,
                            DEBUG_CONTOUR_ASSET,
                            DEBUG_LOCAL_CONTOUR_FILENAME,
                            PREF_INSTALLED_DEBUG_CONTOUR_SHA256
                    );

            MapMetadata worldMapMetadata =
                    readMapMetadata(
                            WORLD_MAP_METADATA_ASSET
                    );

            File localWorldMap =
                    ensureMapInstalled(
                            worldMapMetadata,
                            WORLD_MAP_ASSET,
                            WORLD_LOCAL_MAP_FILENAME,
                            PREF_INSTALLED_WORLD_SHA256
                    );

            MapMetadata contourMetadata =
                    readMapMetadata(CONTOUR_METADATA_ASSET);
            File localContours =
                    ensureMapInstalled(
                            contourMetadata,
                            CONTOUR_ASSET,
                            LOCAL_CONTOUR_FILENAME,
                            PREF_INSTALLED_CONTOUR_SHA256
                    );

            String styleJson = readAssetText(STYLE_ASSET);
            String pmTilesUrl =
                    "pmtiles://" + Uri.fromFile(localIberiaMap);
            String schaffhausenPmTilesUrl =
                    "pmtiles://" + Uri.fromFile(localSchaffhausenMap);
            String schaffhausenContoursUrl =
                    "pmtiles://" + Uri.fromFile(localSchaffhausenContours);
            String worldPmTilesUrl =
                    "pmtiles://" + Uri.fromFile(localWorldMap);
            String contoursUrl =
                    "pmtiles://" + Uri.fromFile(localContours);

            if (!styleJson.contains(STYLE_PM_TILES_TOKEN)) {
                throw new IOException("Offline style is missing PMTiles URL token.");
            }
            if (!styleJson.contains(STYLE_CONTOURS_TOKEN)) {
                throw new IOException("Offline style is missing contour URL token.");
            }
            if (!styleJson.contains(
                    STYLE_SCHAFFHAUSEN_PM_TILES_TOKEN
            )) {
                throw new IOException(
                        "Offline style is missing Schaffhausen PMTiles URL token."
                );
            }
            if (!styleJson.contains(
                    STYLE_SCHAFFHAUSEN_CONTOURS_TOKEN
            )) {
                throw new IOException(
                        "Offline style is missing Schaffhausen contour URL token."
                );
            }
            if (!styleJson.contains(
                    STYLE_WORLD_PM_TILES_TOKEN
            )) {
                throw new IOException(
                        "Offline style is missing MapLibre World PMTiles URL token."
                );
            }

            styleJson = styleJson
                    .replace(
                            STYLE_PM_TILES_TOKEN,
                            pmTilesUrl
                    )
                    .replace(
                            STYLE_SCHAFFHAUSEN_PM_TILES_TOKEN,
                            schaffhausenPmTilesUrl
                    )
                    .replace(
                            STYLE_SCHAFFHAUSEN_CONTOURS_TOKEN,
                            schaffhausenContoursUrl
                    )
                    .replace(
                            STYLE_WORLD_PM_TILES_TOKEN,
                            worldPmTilesUrl
                    )
                    .replace(
                            STYLE_CONTOURS_TOKEN,
                            contoursUrl
                    );

            /*
             * Camino geometry is global. Spain/Portugal and Schaffhausen/Tux
             * are already merged into the single camino-tracks source.
             */


            String finalStyleJson = styleJson;
            runOnUiThread(() -> {
                setupStatus.setText(R.string.offline_map_loading);
                setupProgress.setIndeterminate(true);
                map.setStyle(
                        new Style.Builder().fromJson(finalStyleJson),
                        style -> {
                            setupPanel.setVisibility(View.GONE);

                            if (DEBUG_CAMINO_TAP_ALMERIA) {
                                caminoTapDebugController.onStyleLoaded(style);
                            } else {
                                orientationController.onStyleLoaded(style);

                                if (useSchaffhausenDebugMap()) {
                                    caminoTapDebugController.onStyleLoaded(style);
                                }
                            }
                        }
                );
            });
        } catch (Exception error) {
            runOnUiThread(() -> {
                setupProgress.setVisibility(View.GONE);
                setupStatus.setText(
                        getString(
                                R.string.offline_map_error,
                                error.getMessage() == null
                                        ? error.getClass().getSimpleName()
                                        : error.getMessage()
                        )
                );
            });
        }
    }

    private MapMetadata readMapMetadata(String metadataAsset) throws Exception {
        JSONObject json = new JSONObject(readAssetText(metadataAsset));
        long sizeBytes = json.getLong("size_bytes");
        String sha256 = json.getString("sha256").toLowerCase(Locale.ROOT);

        if (sizeBytes <= 0L) {
            throw new IOException("Invalid offline-map size in metadata.");
        }
        if (!sha256.matches("[0-9a-f]{64}")) {
            throw new IOException("Invalid offline-map SHA-256 in metadata.");
        }

        return new MapMetadata(sizeBytes, sha256);
    }

    private File ensureMapInstalled(
            MapMetadata metadata,
            String assetName,
            String localFilename,
            String preferenceKey
    ) throws Exception {
        File mapDirectory = new File(getFilesDir(), LOCAL_MAP_DIRECTORY);
        if (!mapDirectory.isDirectory() && !mapDirectory.mkdirs()) {
            throw new IOException("Could not create private map directory.");
        }

        File destination = new File(mapDirectory, localFilename);
        SharedPreferences preferences =
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        String installedSha =
                preferences.getString(preferenceKey, "");

        if (destination.isFile()
                && destination.length() == metadata.sizeBytes
                && metadata.sha256.equals(installedSha)) {
            return destination;
        }

        long packagedLength = packagedAssetLength(assetName);
        if (packagedLength != metadata.sizeBytes) {
            throw new IOException(
                    "Bundled PMTiles size does not match metadata ("
                            + packagedLength + " vs " + metadata.sizeBytes + ")."
            );
        }

        File temporary = new File(mapDirectory, localFilename + ".part");
        if (temporary.exists() && !temporary.delete()) {
            throw new IOException("Could not remove stale partial map.");
        }

        runOnUiThread(() -> {
            setupPanel.setVisibility(View.VISIBLE);
            setupProgress.setVisibility(View.VISIBLE);
            setupProgress.setIndeterminate(false);
            setupProgress.setProgress(0);
            setupStatus.setText(R.string.offline_map_preparing);
        });

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[COPY_BUFFER_BYTES];
        long copied = 0L;
        long lastUiUpdate = 0L;

        try (InputStream input =
                     getAssets().open(assetName, AssetManager.ACCESS_STREAMING);
             FileOutputStream output = new FileOutputStream(temporary)) {

            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
                digest.update(buffer, 0, count);
                copied += count;

                long now = SystemClock.elapsedRealtime();
                if (now - lastUiUpdate >= PROGRESS_UPDATE_INTERVAL_MS) {
                    publishCopyProgress(copied, metadata.sizeBytes);
                    lastUiUpdate = now;
                }
            }
            output.getFD().sync();
        } catch (Exception error) {
            temporary.delete();
            throw error;
        }

        publishCopyProgress(copied, metadata.sizeBytes);

        if (copied != metadata.sizeBytes) {
            temporary.delete();
            throw new IOException(
                    "Offline-map copy is incomplete ("
                            + copied + " of " + metadata.sizeBytes + " bytes)."
            );
        }

        String copiedSha = hex(digest.digest());
        if (!metadata.sha256.equals(copiedSha)) {
            temporary.delete();
            throw new IOException("Offline-map copy failed SHA-256 verification.");
        }

        if (destination.exists() && !destination.delete()) {
            temporary.delete();
            throw new IOException("Could not replace old offline map.");
        }
        if (!temporary.renameTo(destination)) {
            temporary.delete();
            throw new IOException("Could not activate copied offline map.");
        }

        preferences.edit()
                .putString(preferenceKey, metadata.sha256)
                .apply();

        return destination;
    }

    private long packagedAssetLength(String assetName) throws IOException {
        try (AssetFileDescriptor descriptor = getAssets().openFd(assetName)) {
            return descriptor.getLength();
        }
    }

    private void publishCopyProgress(long copied, long total) {
        int progress = total > 0L
                ? (int) Math.min(1000L, copied * 1000L / total)
                : 0;
        int percent = progress / 10;

        runOnUiThread(() -> {
            setupProgress.setProgress(progress);
            setupStatus.setText(
                    getString(R.string.offline_map_progress, percent)
            );
        });
    }

    private String readAssetText(String assetName) throws IOException {
        try (InputStream input = getAssets().open(assetName);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            return output.toString("UTF-8");
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return builder.toString();
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
        if (!DEBUG_CAMINO_TAP_ALMERIA) {
            orientationController.start();
            if (useSchaffhausenDebugMap()) {
                caminoTapDebugController.startLivePosition();
            }
        }
    }

    @Override
    protected void onPause() {
        if (!DEBUG_CAMINO_TAP_ALMERIA) {
            if (useSchaffhausenDebugMap()) {
                caminoTapDebugController.stopLivePosition();
            }
            orientationController.stop();
        }
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
        if (!DEBUG_CAMINO_TAP_ALMERIA) {
            orientationController.onLocationPermissionResult(
                    requestCode, permissions, grantResults);
        }
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

    private static final class MapMetadata {
        private final long sizeBytes;
        private final String sha256;

        private MapMetadata(long sizeBytes, String sha256) {
            this.sizeBytes = sizeBytes;
            this.sha256 = sha256;
        }
    }
}
