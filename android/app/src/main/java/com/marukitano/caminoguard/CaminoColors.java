package com.marukitano.caminoguard;

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

        return candidate;
    }

    static String darken(String value) {
        return darken(
                value,
                CaminoConfig.get().floatValue("routes.casingDarken")
        );
    }

    static String darken(String value, float amount) {
        String normalized =
                normalize(
                        value
                );

        int rgbOffset =
                normalized.length() == 9
                        ? 3
                        : 1;

        int red =
                Integer.parseInt(
                        normalized.substring(
                                rgbOffset,
                                rgbOffset + 2
                        ),
                        16
                );

        int green =
                Integer.parseInt(
                        normalized.substring(
                                rgbOffset + 2,
                                rgbOffset + 4
                        ),
                        16
                );

        int blue =
                Integer.parseInt(
                        normalized.substring(
                                rgbOffset + 4,
                                rgbOffset + 6
                        ),
                        16
                );

        float keep =
                Math.max(
                        0.0f,
                        Math.min(
                                1.0f,
                                1.0f - amount
                        )
                );

        red =
                Math.round(
                        red * keep
                );

        green =
                Math.round(
                        green * keep
                );

        blue =
                Math.round(
                        blue * keep
                );

        return String.format(
                Locale.ROOT,
                "#%02X%02X%02X",
                red,
                green,
                blue
        );
    }
}
