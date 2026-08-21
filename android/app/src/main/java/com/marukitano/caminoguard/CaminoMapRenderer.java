package com.marukitano.caminoguard;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;
import org.maplibre.android.maps.Style;
import org.maplibre.android.style.sources.GeoJsonSource;
import org.maplibre.geojson.Feature;
import org.maplibre.geojson.FeatureCollection;
import org.maplibre.geojson.LineString;
import org.maplibre.geojson.Point;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Static Camino map renderer.
 *
 * This renderer and CaminoController read the SAME canonical camino-global.json.
 * No parallel tracks-global GeoJSON exists at runtime.
 */
final class CaminoMapRenderer {
    private static final String SOURCE = "camino-tracks";
    private final Context context;

    CaminoMapRenderer(Context context) {
        this.context = context.getApplicationContext();
    }

    void onStyleLoaded(Style style) {
        GeoJsonSource source = style.getSourceAs(SOURCE);
        if (source == null) {
            throw new IllegalStateException(
                    "Map style is missing source " + SOURCE
            );
        }
        try {
            source.setGeoJson(loadFeatures());
        } catch (Exception error) {
            throw new IllegalStateException(
                    "Cannot render canonical Camino data",
                    error
            );
        }
    }

    private FeatureCollection loadFeatures() throws Exception {
        JSONObject root = new JSONObject(
                readAssetText(
                        CaminoConfig.get().string("data.caminoAsset")
                )
        );
        JSONArray routes = root.getJSONArray("routes");
        List<Feature> features = new ArrayList<>();

        for (int routeIndex = 0; routeIndex < routes.length(); routeIndex++) {
            JSONObject route = routes.getJSONObject(routeIndex);
            String routeColor = CaminoColors.normalize(
                    route.optString(
                            "color",
                            CaminoConfig.get().string("routes.defaultColor")
                    )
            );
            JSONArray tracks = route.getJSONArray("tracks");

            for (int trackIndex = 0; trackIndex < tracks.length(); trackIndex++) {
                JSONObject track = tracks.getJSONObject(trackIndex);
                JSONArray coordinates = track.getJSONArray("coordinates");
                if (coordinates.length() < 2) {
                    continue;
                }

                String trackColor = CaminoColors.normalize(
                        track.optString("color", routeColor)
                );
                List<Point> points = new ArrayList<>();

                for (int pointIndex = 0; pointIndex < coordinates.length(); pointIndex++) {
                    JSONArray coordinate = coordinates.getJSONArray(pointIndex);
                    points.add(
                            Point.fromLngLat(
                                    coordinate.getDouble(1),
                                    coordinate.getDouble(0)
                            )
                    );
                }

                Feature feature = Feature.fromGeometry(
                        LineString.fromLngLats(points)
                );
                feature.addStringProperty("routeColor", trackColor);
                feature.addStringProperty(
                        "casingColor",
                        CaminoColors.darken(trackColor)
                );
                feature.addStringProperty(
                        "route_group_id",
                        route.optString("route_group_id", "")
                );
                feature.addStringProperty(
                        "section_id",
                        track.optString("section_id", "")
                );
                features.add(feature);
            }
        }

        return FeatureCollection.fromFeatures(features);
    }

    private String readAssetText(String assetName) throws Exception {
        try (InputStream input = context.getAssets().open(assetName);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            return output.toString("UTF-8");
        }
    }
}
