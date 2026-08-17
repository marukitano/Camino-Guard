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

    private static final String MAP_ASSET = "maps/iberia.pmtiles";
    private static final String MAP_METADATA_ASSET = "maps/iberia.metadata.json";
    private static final String CONTOUR_ASSET = "maps/contours.pmtiles";
    private static final String CONTOUR_METADATA_ASSET = "maps/contours.metadata.json";
    private static final String TERRAIN_ASSET = "maps/terrain.pmtiles";
    private static final String TERRAIN_METADATA_ASSET = "maps/terrain.metadata.json";
    private static final String STYLE_ASSET = "styles/camino-basic.json";
    private static final String STYLE_PM_TILES_TOKEN = "__PMTILES_URL__";
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        MapLibre.getInstance(this);
        setContentView(R.layout.activity_main);

        mapView = findViewById(R.id.map_view);
        setupPanel = findViewById(R.id.map_setup_panel);
        setupStatus = findViewById(R.id.map_setup_status);
        setupProgress = findViewById(R.id.map_setup_progress);

        mapView.getMapAsync(map -> {
            // Allow natural two-finger zoom + rotate at the same time.
            // MapLibre can otherwise disable rotation when scale wins the
            // gesture race first, which makes rotation feel intermittent.
            map.getUiSettings().setRotateGesturesEnabled(true);
            map.getUiSettings().setDisableRotateWhenScaling(false);
            map.getUiSettings().setIncreaseScaleThresholdWhenRotating(false);
            map.setLatLngBoundsForCameraTarget(IBERIA_CAMERA_BOUNDS);

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

        CameraPosition overview =
                map.getCameraForLatLngBounds(
                        IBERIA_CAMERA_BOUNDS,
                        edgePadding
                );

        if (overview != null) {
            /*
             * Users may zoom in as far as they like, but cannot zoom farther
             * out than the overview that fits Iberia on this display.
             */
            map.setMinZoomPreference(overview.zoom);
            map.setCameraPosition(overview);
        }
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    private void prepareOfflineMap(MapLibreMap map) {
        try {
            MapMetadata mapMetadata =
                    readMapMetadata(MAP_METADATA_ASSET);
            File localMap =
                    ensureMapInstalled(
                            mapMetadata,
                            MAP_ASSET,
                            LOCAL_MAP_FILENAME,
                            PREF_INSTALLED_SHA256
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
            String pmTilesUrl = "pmtiles://" + Uri.fromFile(localMap);
            String contoursUrl = "pmtiles://" + Uri.fromFile(localContours);

            if (!styleJson.contains(STYLE_PM_TILES_TOKEN)) {
                throw new IOException("Offline style is missing PMTiles URL token.");
            }
            if (!styleJson.contains(STYLE_CONTOURS_TOKEN)) {
                throw new IOException("Offline style is missing contour URL token.");
            }

            styleJson = styleJson
                    .replace(STYLE_PM_TILES_TOKEN, pmTilesUrl)
                    .replace(STYLE_CONTOURS_TOKEN, contoursUrl);

            String finalStyleJson = styleJson;
            runOnUiThread(() -> {
                setupStatus.setText(R.string.offline_map_loading);
                setupProgress.setIndeterminate(true);
                map.setStyle(
                        new Style.Builder().fromJson(finalStyleJson),
                        style -> setupPanel.setVisibility(View.GONE)
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
    }

    @Override
    protected void onPause() {
        mapView.onPause();
        super.onPause();
    }

    @Override
    protected void onStop() {
        mapView.onStop();
        super.onStop();
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
