package com.marukitano.caminoguard;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Iterator;

/** Applies optional MapLibre layer overrides from camino-config.json. */
final class MapStyleConfig {
    private MapStyleConfig() {
    }

    static String apply(String styleJson) throws Exception {
        JSONObject style = new JSONObject(styleJson);
        JSONObject overrides = CaminoConfig.get().object(
                "mapStyle.layerOverrides"
        );
        JSONArray layers = style.getJSONArray("layers");
        Iterator<String> ids = overrides.keys();

        while (ids.hasNext()) {
            String id = ids.next();
            JSONObject layer = findLayer(layers, id);
            if (layer == null) {
                throw new IllegalArgumentException(
                        "Config override references unknown map layer: " + id
                );
            }
            merge(layer, overrides.getJSONObject(id));
        }

        return style.toString();
    }

    private static JSONObject findLayer(JSONArray layers, String id) {
        for (int index = 0; index < layers.length(); index++) {
            JSONObject layer = layers.optJSONObject(index);
            if (layer != null && id.equals(layer.optString("id"))) {
                return layer;
            }
        }
        return null;
    }

    private static void merge(JSONObject target, JSONObject override)
            throws Exception {
        Iterator<String> keys = override.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object value = override.get(key);
            if (value instanceof JSONObject
                    && target.opt(key) instanceof JSONObject) {
                merge(target.getJSONObject(key), (JSONObject) value);
            } else {
                target.put(key, value);
            }
        }
    }
}
