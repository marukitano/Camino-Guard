package com.marukitano.caminoguard;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.os.SystemClock;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Locale;

final class OfflineMapRepository {

    private static final String SCHAFFHAUSEN_MAP_ASSET =
            "maps/debug-schaffhausen.pmtiles";
    private static final String SCHAFFHAUSEN_MAP_METADATA_ASSET =
            "maps/debug-schaffhausen.metadata.json";
    private static final String SCHAFFHAUSEN_LOCAL_MAP_FILENAME =
            "debug-schaffhausen.pmtiles";
    private static final String PREF_INSTALLED_SCHAFFHAUSEN_SHA256 =
            "installed_debug_schaffhausen_sha256";

    private static final String SCHAFFHAUSEN_CONTOUR_ASSET =
            "maps/debug-schaffhausen-contours.pmtiles";
    private static final String SCHAFFHAUSEN_CONTOUR_METADATA_ASSET =
            "maps/debug-schaffhausen-contours.metadata.json";
    private static final String SCHAFFHAUSEN_LOCAL_CONTOUR_FILENAME =
            "debug-schaffhausen-contours.pmtiles";
    private static final String PREF_INSTALLED_SCHAFFHAUSEN_CONTOUR_SHA256 =
            "installed_debug_schaffhausen_contour_sha256";

    private static final String WORLD_MAP_ASSET =
            "maps/world-maplibre.pmtiles";
    private static final String WORLD_MAP_METADATA_ASSET =
            "maps/world-maplibre.metadata.json";
    private static final String WORLD_LOCAL_MAP_FILENAME =
            "world-maplibre.pmtiles";
    private static final String PREF_INSTALLED_WORLD_SHA256 =
            "installed_world_maplibre_sha256";

    private static final String MAP_ASSET =
            "maps/iberia.pmtiles";
    private static final String MAP_METADATA_ASSET =
            "maps/iberia.metadata.json";

    private static final String CONTOUR_ASSET =
            "maps/contours.pmtiles";
    private static final String CONTOUR_METADATA_ASSET =
            "maps/contours.metadata.json";

    private static final String LOCAL_MAP_DIRECTORY =
            "maps";
    private static final String LOCAL_MAP_FILENAME =
            "iberia.pmtiles";
    private static final String LOCAL_CONTOUR_FILENAME =
            "contours.pmtiles";

    private static final String PREFS_NAME =
            "offline_map";
    private static final String PREF_INSTALLED_SHA256 =
            "installed_sha256";
    private static final String PREF_INSTALLED_CONTOUR_SHA256 =
            "installed_contour_sha256";

    private static final long PROGRESS_UPDATE_INTERVAL_MS =
            150L;
    private static final int COPY_BUFFER_BYTES =
            4 * 1024 * 1024;

    interface ProgressListener {
        void onPreparing();

        void onProgress(
                int progress,
                int percent
        );
    }

    static final class InstalledMaps {
        final File iberiaMap;
        final File schaffhausenMap;
        final File schaffhausenContours;
        final File worldMap;
        final File contours;

        InstalledMaps(
                File iberiaMap,
                File schaffhausenMap,
                File schaffhausenContours,
                File worldMap,
                File contours
        ) {
            this.iberiaMap = iberiaMap;
            this.schaffhausenMap = schaffhausenMap;
            this.schaffhausenContours = schaffhausenContours;
            this.worldMap = worldMap;
            this.contours = contours;
        }
    }

    private final Context context;
    private final ProgressListener progressListener;

    OfflineMapRepository(
            Context context,
            ProgressListener progressListener
    ) {
        this.context = context.getApplicationContext();
        this.progressListener = progressListener;
    }

    InstalledMaps ensureInstalled()
            throws Exception {

        File localIberiaMap =
                ensureMapInstalled(
                        readMapMetadata(MAP_METADATA_ASSET),
                        MAP_ASSET,
                        LOCAL_MAP_FILENAME,
                        PREF_INSTALLED_SHA256
                );

        File localSchaffhausenMap =
                ensureMapInstalled(
                        readMapMetadata(SCHAFFHAUSEN_MAP_METADATA_ASSET),
                        SCHAFFHAUSEN_MAP_ASSET,
                        SCHAFFHAUSEN_LOCAL_MAP_FILENAME,
                        PREF_INSTALLED_SCHAFFHAUSEN_SHA256
                );

        File localSchaffhausenContours =
                ensureMapInstalled(
                        readMapMetadata(SCHAFFHAUSEN_CONTOUR_METADATA_ASSET),
                        SCHAFFHAUSEN_CONTOUR_ASSET,
                        SCHAFFHAUSEN_LOCAL_CONTOUR_FILENAME,
                        PREF_INSTALLED_SCHAFFHAUSEN_CONTOUR_SHA256
                );

        File localWorldMap =
                ensureMapInstalled(
                        readMapMetadata(WORLD_MAP_METADATA_ASSET),
                        WORLD_MAP_ASSET,
                        WORLD_LOCAL_MAP_FILENAME,
                        PREF_INSTALLED_WORLD_SHA256
                );

        File localContours =
                ensureMapInstalled(
                        readMapMetadata(CONTOUR_METADATA_ASSET),
                        CONTOUR_ASSET,
                        LOCAL_CONTOUR_FILENAME,
                        PREF_INSTALLED_CONTOUR_SHA256
                );

        return new InstalledMaps(
                localIberiaMap,
                localSchaffhausenMap,
                localSchaffhausenContours,
                localWorldMap,
                localContours
        );
    }

    private MapMetadata readMapMetadata(
            String metadataAsset
    ) throws Exception {

        JSONObject json =
                new JSONObject(
                        readAssetText(metadataAsset)
                );

        long sizeBytes =
                json.getLong("size_bytes");

        String sha256 =
                json.getString("sha256")
                        .toLowerCase(Locale.ROOT);

        if (sizeBytes <= 0L) {
            throw new IOException(
                    "Invalid offline-map size in metadata."
            );
        }

        if (!sha256.matches("[0-9a-f]{64}")) {
            throw new IOException(
                    "Invalid offline-map SHA-256 in metadata."
            );
        }

        return new MapMetadata(sizeBytes, sha256);
    }

    private File ensureMapInstalled(
            MapMetadata metadata,
            String assetName,
            String localFilename,
            String preferenceKey
    ) throws Exception {

        File mapDirectory =
                new File(
                        context.getFilesDir(),
                        LOCAL_MAP_DIRECTORY
                );

        if (!mapDirectory.isDirectory()
                && !mapDirectory.mkdirs()) {
            throw new IOException(
                    "Could not create private map directory."
            );
        }

        File destination =
                new File(
                        mapDirectory,
                        localFilename
                );

        SharedPreferences preferences =
                context.getSharedPreferences(
                        PREFS_NAME,
                        Context.MODE_PRIVATE
                );

        String installedSha =
                preferences.getString(
                        preferenceKey,
                        ""
                );

        if (destination.isFile()
                && destination.length() == metadata.sizeBytes
                && metadata.sha256.equals(installedSha)) {
            return destination;
        }

        long packagedLength =
                packagedAssetLength(assetName);

        if (packagedLength != metadata.sizeBytes) {
            throw new IOException(
                    "Bundled PMTiles size does not match metadata ("
                            + packagedLength + " vs "
                            + metadata.sizeBytes + ")."
            );
        }

        File temporary =
                new File(
                        mapDirectory,
                        localFilename + ".part"
                );

        if (temporary.exists()
                && !temporary.delete()) {
            throw new IOException(
                    "Could not remove stale partial map."
            );
        }

        if (progressListener != null) {
            progressListener.onPreparing();
        }

        MessageDigest digest =
                MessageDigest.getInstance("SHA-256");

        byte[] buffer =
                new byte[COPY_BUFFER_BYTES];

        long copied = 0L;
        long lastUiUpdate = 0L;

        try (InputStream input =
                     context.getAssets().open(
                             assetName,
                             AssetManager.ACCESS_STREAMING
                     );
             FileOutputStream output =
                     new FileOutputStream(temporary)) {

            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
                digest.update(buffer, 0, count);
                copied += count;

                long now =
                        SystemClock.elapsedRealtime();

                if (now - lastUiUpdate
                        >= PROGRESS_UPDATE_INTERVAL_MS) {
                    publishCopyProgress(
                            copied,
                            metadata.sizeBytes
                    );
                    lastUiUpdate = now;
                }
            }

            output.getFD().sync();

        } catch (Exception error) {
            temporary.delete();
            throw error;
        }

        publishCopyProgress(
                copied,
                metadata.sizeBytes
        );

        if (copied != metadata.sizeBytes) {
            temporary.delete();
            throw new IOException(
                    "Offline-map copy is incomplete ("
                            + copied + " of "
                            + metadata.sizeBytes + " bytes)."
            );
        }

        String copiedSha =
                hex(digest.digest());

        if (!metadata.sha256.equals(copiedSha)) {
            temporary.delete();
            throw new IOException(
                    "Offline-map SHA-256 verification failed."
            );
        }

        if (destination.exists()
                && !destination.delete()) {
            temporary.delete();
            throw new IOException(
                    "Could not replace old offline map."
            );
        }

        if (!temporary.renameTo(destination)) {
            temporary.delete();
            throw new IOException(
                    "Could not install offline map."
            );
        }

        preferences.edit()
                .putString(
                        preferenceKey,
                        metadata.sha256
                )
                .apply();

        return destination;
    }

    private long packagedAssetLength(
            String assetName
    ) throws IOException {

        try (AssetFileDescriptor descriptor =
                     context.getAssets().openFd(assetName)) {
            return descriptor.getLength();
        }
    }

    private void publishCopyProgress(
            long copied,
            long total
    ) {
        if (progressListener == null) {
            return;
        }

        int progress =
                total > 0L
                        ? (int) Math.min(
                                1000L,
                                copied * 1000L / total
                        )
                        : 0;

        int percent =
                progress / 10;

        progressListener.onProgress(
                progress,
                percent
        );
    }

    private String readAssetText(
            String assetName
    ) throws IOException {

        try (InputStream input =
                     context.getAssets().open(assetName);
             ByteArrayOutputStream output =
                     new ByteArrayOutputStream()) {

            byte[] buffer =
                    new byte[16 * 1024];

            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }

            return output.toString("UTF-8");
        }
    }

    private static String hex(
            byte[] bytes
    ) {
        StringBuilder builder =
                new StringBuilder(bytes.length * 2);

        for (byte value : bytes) {
            builder.append(
                    String.format(
                            Locale.ROOT,
                            "%02x",
                            value & 0xff
                    )
            );
        }

        return builder.toString();
    }

    private static final class MapMetadata {
        final long sizeBytes;
        final String sha256;

        MapMetadata(
                long sizeBytes,
                String sha256
        ) {
            this.sizeBytes = sizeBytes;
            this.sha256 = sha256;
        }
    }
}
