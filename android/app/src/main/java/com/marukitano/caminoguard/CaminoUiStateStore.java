package com.marukitano.caminoguard;

import android.content.Context;
import android.content.SharedPreferences;

import org.maplibre.android.camera.CameraPosition;
import org.maplibre.android.geometry.LatLng;

/**
 * Small persistent store for user-facing map/navigation state.
 *
 * App updates installed with adb install -r keep this state. Uninstalling the
 * app or clearing its data intentionally removes it.
 */
final class CaminoUiStateStore {

    private static final String PREFS_NAME =
            "camino_guard_ui_state";

    private static final String PREF_FOLLOW_ENABLED =
            "navigation_follow_enabled";

    private static final String PREF_CAMERA_VALID =
            "camera_valid";

    private static final String PREF_CAMERA_LAT =
            "camera_lat";

    private static final String PREF_CAMERA_LON =
            "camera_lon";

    private static final String PREF_CAMERA_ZOOM =
            "camera_zoom";

    private static final String PREF_CAMERA_BEARING =
            "camera_bearing";

    private static final String PREF_CAMERA_TILT =
            "camera_tilt";

    private final SharedPreferences preferences;

    CaminoUiStateStore(
            Context context
    ) {
        preferences =
                context
                        .getApplicationContext()
                        .getSharedPreferences(
                                PREFS_NAME,
                                Context.MODE_PRIVATE
                        );
    }

    boolean restoreFollowEnabled(
            boolean fallback
    ) {
        return preferences.getBoolean(
                PREF_FOLLOW_ENABLED,
                fallback
        );
    }

    void saveFollowEnabled(
            boolean enabled
    ) {
        preferences
                .edit()
                .putBoolean(
                        PREF_FOLLOW_ENABLED,
                        enabled
                )
                .apply();
    }

    CameraPosition restoreCameraPosition(
            CameraPosition fallback
    ) {
        if (fallback == null
                || !preferences.getBoolean(
                PREF_CAMERA_VALID,
                false
        )) {
            return fallback;
        }

        double latitude =
                getDouble(
                        PREF_CAMERA_LAT,
                        Double.NaN
                );

        double longitude =
                getDouble(
                        PREF_CAMERA_LON,
                        Double.NaN
                );

        double zoom =
                getDouble(
                        PREF_CAMERA_ZOOM,
                        Double.NaN
                );

        double bearing =
                getDouble(
                        PREF_CAMERA_BEARING,
                        Double.NaN
                );

        double tilt =
                getDouble(
                        PREF_CAMERA_TILT,
                        Double.NaN
                );

        if (!Double.isFinite(latitude)
                || latitude < -90.0
                || latitude > 90.0
                || !Double.isFinite(longitude)
                || longitude < -180.0
                || longitude > 180.0
                || !Double.isFinite(zoom)
                || !Double.isFinite(bearing)
                || !Double.isFinite(tilt)) {
            return fallback;
        }

        return new CameraPosition.Builder(
                fallback
        )
                .target(
                        new LatLng(
                                latitude,
                                longitude
                        )
                )
                .zoom(
                        zoom
                )
                .bearing(
                        bearing
                )
                .tilt(
                        tilt
                )
                .build();
    }

    void saveCameraPosition(
            CameraPosition camera
    ) {
        if (camera == null
                || camera.target == null
                || !Double.isFinite(
                camera.target.getLatitude()
        )
                || !Double.isFinite(
                camera.target.getLongitude()
        )
                || !Double.isFinite(
                camera.zoom
        )
                || !Double.isFinite(
                camera.bearing
        )
                || !Double.isFinite(
                camera.tilt
        )) {
            return;
        }

        preferences
                .edit()
                .putLong(
                        PREF_CAMERA_LAT,
                        Double.doubleToRawLongBits(
                                camera.target.getLatitude()
                        )
                )
                .putLong(
                        PREF_CAMERA_LON,
                        Double.doubleToRawLongBits(
                                camera.target.getLongitude()
                        )
                )
                .putLong(
                        PREF_CAMERA_ZOOM,
                        Double.doubleToRawLongBits(
                                camera.zoom
                        )
                )
                .putLong(
                        PREF_CAMERA_BEARING,
                        Double.doubleToRawLongBits(
                                camera.bearing
                        )
                )
                .putLong(
                        PREF_CAMERA_TILT,
                        Double.doubleToRawLongBits(
                                camera.tilt
                        )
                )
                .putBoolean(
                        PREF_CAMERA_VALID,
                        true
                )
                .apply();
    }

    private double getDouble(
            String key,
            double fallback
    ) {
        if (!preferences.contains(
                key
        )) {
            return fallback;
        }

        return Double.longBitsToDouble(
                preferences.getLong(
                        key,
                        Double.doubleToRawLongBits(
                                fallback
                        )
                )
        );
    }
}
