package com.marukitano.caminoguard;

import android.graphics.Color;

import java.util.Locale;

/** One colour implementation for base routes and selected-route overlays. */
final class CaminoColors {
    private CaminoColors() {
    }

    static String normalize(String value) {
        String fallback = CaminoConfig.get().string("routes.defaultColor");
        if (value == null) {
            return fallback;
        }
        String candidate = value.trim();
        if (!candidate.matches("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?")) {
            return fallback;
        }
        try {
            Color.parseColor(candidate);
            return candidate;
        } catch (IllegalArgumentException error) {
            return fallback;
        }
    }

    static String darken(String value) {
        return darken(
                value,
                CaminoConfig.get().floatValue("routes.casingDarken")
        );
    }

    static String darken(String value, float amount) {
        int color = Color.parseColor(normalize(value));
        float keep = Math.max(0.0f, Math.min(1.0f, 1.0f - amount));
        int red = Math.round(Color.red(color) * keep);
        int green = Math.round(Color.green(color) * keep);
        int blue = Math.round(Color.blue(color) * keep);
        return String.format(Locale.ROOT, "#%02X%02X%02X", red, green, blue);
    }
}
