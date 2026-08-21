package com.marukitano.caminoguard;

import android.content.Context;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

final class MapStyleProvider {

    private static final String STYLE_ASSET =
            "styles/camino-basic.json";
    private static final String STYLE_PM_TILES_TOKEN =
            "__PMTILES_URL__";
    private static final String STYLE_SCHAFFHAUSEN_PM_TILES_TOKEN =
            "__SCHAFFHAUSEN_PMTILES_URL__";
    private static final String STYLE_SCHAFFHAUSEN_CONTOURS_TOKEN =
            "__SCHAFFHAUSEN_CONTOURS_URL__";
    private static final String STYLE_WORLD_PM_TILES_TOKEN =
            "__WORLD_PM_TILES_URL__";
    private static final String STYLE_CONTOURS_TOKEN =
            "__CONTOURS_URL__";

    private final Context context;

    MapStyleProvider(
            Context context
    ) {
        this.context =
                context.getApplicationContext();
    }

    String buildStyle(
            OfflineMapRepository.InstalledMaps maps
    ) throws Exception {

        String styleJson =
                readAssetText(
                        STYLE_ASSET
                );

        requireToken(
                styleJson,
                STYLE_PM_TILES_TOKEN,
                "Offline style is missing PMTiles URL token."
        );

        requireToken(
                styleJson,
                STYLE_CONTOURS_TOKEN,
                "Offline style is missing contour URL token."
        );

        requireToken(
                styleJson,
                STYLE_SCHAFFHAUSEN_PM_TILES_TOKEN,
                "Offline style is missing Schaffhausen PMTiles URL token."
        );

        requireToken(
                styleJson,
                STYLE_SCHAFFHAUSEN_CONTOURS_TOKEN,
                "Offline style is missing Schaffhausen contour URL token."
        );

        requireToken(
                styleJson,
                STYLE_WORLD_PM_TILES_TOKEN,
                "Offline style is missing MapLibre World PMTiles URL token."
        );

        styleJson =
                styleJson
                        .replace(
                                STYLE_PM_TILES_TOKEN,
                                pmTilesUrl(maps.iberiaMap)
                        )
                        .replace(
                                STYLE_SCHAFFHAUSEN_PM_TILES_TOKEN,
                                pmTilesUrl(maps.schaffhausenMap)
                        )
                        .replace(
                                STYLE_SCHAFFHAUSEN_CONTOURS_TOKEN,
                                pmTilesUrl(maps.schaffhausenContours)
                        )
                        .replace(
                                STYLE_WORLD_PM_TILES_TOKEN,
                                pmTilesUrl(maps.worldMap)
                        )
                        .replace(
                                STYLE_CONTOURS_TOKEN,
                                pmTilesUrl(maps.contours)
                        );

        return MapStyleConfig.apply(
                styleJson
        );
    }

    private static String pmTilesUrl(
            java.io.File file
    ) {
        return "pmtiles://"
                + Uri.fromFile(file);
    }

    private static void requireToken(
            String styleJson,
            String token,
            String errorMessage
    ) throws IOException {

        if (!styleJson.contains(token)) {
            throw new IOException(errorMessage);
        }
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
}
