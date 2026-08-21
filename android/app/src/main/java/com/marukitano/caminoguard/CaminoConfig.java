package com.marukitano.caminoguard;

import android.content.Context;
import android.graphics.Color;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/** Immutable user-editable application configuration. */
public final class CaminoConfig {
    private static final String ASSET = "config/camino-config.json";
    private static volatile CaminoConfig instance;
    private final JSONObject root;

    private CaminoConfig(JSONObject root) {
        this.root = root;
    }

    public static synchronized void initialize(Context context) {
        if (instance != null) {
            return;
        }

        try (InputStream input = context.getAssets().open(ASSET);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            instance = new CaminoConfig(
                    new JSONObject(output.toString("UTF-8"))
            );
        } catch (Exception error) {
            throw new IllegalStateException("Cannot load " + ASSET, error);
        }
    }

    public static CaminoConfig get() {
        CaminoConfig value = instance;
        if (value == null) {
            throw new IllegalStateException(
                    "CaminoConfig.initialize(context) must run before use"
            );
        }
        return value;
    }

    public String string(String path) {
        Object value = required(path);
        if (!(value instanceof String)) {
            throw wrongType(path, "string", value);
        }
        return (String) value;
    }

    public double doubleValue(String path) {
        Object value = required(path);
        if (!(value instanceof Number)) {
            throw wrongType(path, "number", value);
        }
        return ((Number) value).doubleValue();
    }

    public float floatValue(String path) {
        return (float) doubleValue(path);
    }

    public long longValue(String path) {
        Object value = required(path);
        if (!(value instanceof Number)) {
            throw wrongType(path, "number", value);
        }
        return ((Number) value).longValue();
    }

    public boolean booleanValue(String path) {
        Object value = required(path);
        if (!(value instanceof Boolean)) {
            throw wrongType(path, "boolean", value);
        }
        return (Boolean) value;
    }

    public int color(String path) {
        return Color.parseColor(string(path));
    }

    JSONObject object(String path) {
        Object value = required(path);
        if (!(value instanceof JSONObject)) {
            throw wrongType(path, "object", value);
        }
        return (JSONObject) value;
    }

    private Object required(String path) {
        String[] parts = path.split("\\.");
        Object cursor = root;
        for (String part : parts) {
            if (!(cursor instanceof JSONObject)) {
                throw new IllegalArgumentException(
                        "Config path is not an object: " + path
                );
            }
            JSONObject object = (JSONObject) cursor;
            if (!object.has(part)) {
                throw new IllegalArgumentException(
                        "Missing config value: " + path
                );
            }
            cursor = object.opt(part);
        }
        return cursor;
    }

    private IllegalArgumentException wrongType(
            String path,
            String expected,
            Object actual
    ) {
        return new IllegalArgumentException(
                "Config value " + path + " must be " + expected
                        + ", got "
                        + (actual == null ? "null" : actual.getClass().getSimpleName())
        );
    }
}
