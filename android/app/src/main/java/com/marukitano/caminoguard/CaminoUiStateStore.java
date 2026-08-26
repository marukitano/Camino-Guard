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

    /*
     * v20 could save MapLibre's temporary world camera before startup restore.
     * Version 2 deliberately invalidates those old camera values once.
     */
    private static final String PREF_CAMERA_STATE_VERSION =
            "camera_state_version";

    private static final int CURRENT_CAMERA_STATE_VERSION =
            2;

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

    private static final String PREF_PLANNED_START_MINUTES =
            "planning_start_minutes";

    private static final String PREF_SELECTION_LOCKED =
            "selection_locked";

    private static final String PREF_SELECTION_START_ROUTE =
            "selection_start_route";

    private static final String PREF_SELECTION_START_LAT =
            "selection_start_lat";

    private static final String PREF_SELECTION_START_LON =
            "selection_start_lon";

    private static final String PREF_SELECTION_END_ROUTE =
            "selection_end_route";

    private static final String PREF_SELECTION_END_LAT =
            "selection_end_lat";

    private static final String PREF_SELECTION_END_LON =
            "selection_end_lon";

    private static final String PREF_SELECTION_STAGE_PLACE =
            "selection_stage_place";

    private static final String PREF_SELECTION_STAGE_LAT =
            "selection_stage_lat";

    private static final String PREF_SELECTION_STAGE_LON =
            "selection_stage_lon";

    private static final String PREF_SELECTION_RESOLVED_PATH =
            "selection_resolved_path";

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
        )
                || preferences.getInt(
                PREF_CAMERA_STATE_VERSION,
                0
        ) != CURRENT_CAMERA_STATE_VERSION) {

            /*
             * Old v20 camera data is intentionally ignored. The navigation
             * follow/manual preference uses another key and remains intact.
             */
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
                .putInt(
                        PREF_CAMERA_STATE_VERSION,
                        CURRENT_CAMERA_STATE_VERSION
                )
                .putBoolean(
                        PREF_CAMERA_VALID,
                        true
                )
                .apply();
    }

    int restorePlannedStartMinutes(
            int fallback
    ) {
        int value =
                preferences.getInt(
                        PREF_PLANNED_START_MINUTES,
                        fallback
                );

        if (value < 0
                || value >= 24 * 60) {

            return fallback;
        }

        return value;
    }


    void savePlannedStartMinutes(
            int minutes
    ) {
        int normalized =
                minutes
                        % (
                        24
                                * 60
                );

        if (normalized < 0) {
            normalized +=
                    24
                            * 60;
        }

        preferences
                .edit()
                .putInt(
                        PREF_PLANNED_START_MINUTES,
                        normalized
                )
                .apply();
    }


    void saveLockedSelection(
            String startRouteId,
            LatLng startPoint,
            String endRouteId,
            LatLng endPoint,
            String stagePlaceKey,
            LatLng stagePoint,
            String resolvedPathId
    ) {
        SharedPreferences.Editor editor =
                preferences
                        .edit()
                        .putBoolean(
                                PREF_SELECTION_LOCKED,
                                true
                        );

        putNullableString(
                editor,
                PREF_SELECTION_START_ROUTE,
                startRouteId
        );

        putNullablePoint(
                editor,
                PREF_SELECTION_START_LAT,
                PREF_SELECTION_START_LON,
                startPoint
        );

        putNullableString(
                editor,
                PREF_SELECTION_END_ROUTE,
                endRouteId
        );

        putNullablePoint(
                editor,
                PREF_SELECTION_END_LAT,
                PREF_SELECTION_END_LON,
                endPoint
        );

        putNullableString(
                editor,
                PREF_SELECTION_STAGE_PLACE,
                stagePlaceKey
        );

        putNullablePoint(
                editor,
                PREF_SELECTION_STAGE_LAT,
                PREF_SELECTION_STAGE_LON,
                stagePoint
        );

        putNullableString(
                editor,
                PREF_SELECTION_RESOLVED_PATH,
                resolvedPathId
        );

        editor.apply();
    }


    LockedSelectionState restoreLockedSelection() {
        if (!preferences.getBoolean(
                PREF_SELECTION_LOCKED,
                false
        )) {

            return null;
        }

        return new LockedSelectionState(
                preferences.getString(
                        PREF_SELECTION_START_ROUTE,
                        null
                ),
                restorePoint(
                        PREF_SELECTION_START_LAT,
                        PREF_SELECTION_START_LON
                ),
                preferences.getString(
                        PREF_SELECTION_END_ROUTE,
                        null
                ),
                restorePoint(
                        PREF_SELECTION_END_LAT,
                        PREF_SELECTION_END_LON
                ),
                preferences.getString(
                        PREF_SELECTION_STAGE_PLACE,
                        null
                ),
                restorePoint(
                        PREF_SELECTION_STAGE_LAT,
                        PREF_SELECTION_STAGE_LON
                ),
                preferences.getString(
                        PREF_SELECTION_RESOLVED_PATH,
                        null
                )
        );
    }


    void clearLockedSelection() {
        preferences
                .edit()
                .remove(
                        PREF_SELECTION_LOCKED
                )
                .remove(
                        PREF_SELECTION_START_ROUTE
                )
                .remove(
                        PREF_SELECTION_START_LAT
                )
                .remove(
                        PREF_SELECTION_START_LON
                )
                .remove(
                        PREF_SELECTION_END_ROUTE
                )
                .remove(
                        PREF_SELECTION_END_LAT
                )
                .remove(
                        PREF_SELECTION_END_LON
                )
                .remove(
                        PREF_SELECTION_STAGE_PLACE
                )
                .remove(
                        PREF_SELECTION_STAGE_LAT
                )
                .remove(
                        PREF_SELECTION_STAGE_LON
                )
                .remove(
                        PREF_SELECTION_RESOLVED_PATH
                )
                .apply();
    }


    private void putNullableString(
            SharedPreferences.Editor editor,
            String key,
            String value
    ) {
        if (value == null
                || value.trim().isEmpty()) {

            editor.remove(
                    key
            );

        } else {
            editor.putString(
                    key,
                    value
            );
        }
    }


    private void putNullablePoint(
            SharedPreferences.Editor editor,
            String latKey,
            String lonKey,
            LatLng point
    ) {
        if (point == null
                || !Double.isFinite(
                point.getLatitude()
        )
                || !Double.isFinite(
                point.getLongitude()
        )) {

            editor.remove(
                    latKey
            );

            editor.remove(
                    lonKey
            );

            return;
        }

        editor.putLong(
                latKey,
                Double.doubleToRawLongBits(
                        point.getLatitude()
                )
        );

        editor.putLong(
                lonKey,
                Double.doubleToRawLongBits(
                        point.getLongitude()
                )
        );
    }


    private LatLng restorePoint(
            String latKey,
            String lonKey
    ) {
        double latitude =
                getDouble(
                        latKey,
                        Double.NaN
                );

        double longitude =
                getDouble(
                        lonKey,
                        Double.NaN
                );

        if (!Double.isFinite(
                latitude
        )
                || latitude < -90.0
                || latitude > 90.0
                || !Double.isFinite(
                longitude
        )
                || longitude < -180.0
                || longitude > 180.0) {

            return null;
        }

        return new LatLng(
                latitude,
                longitude
        );
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

    static final class LockedSelectionState {

        final String startRouteId;
        final LatLng startPoint;
        final String endRouteId;
        final LatLng endPoint;
        final String stagePlaceKey;
        final LatLng stagePoint;
        final String resolvedPathId;

        LockedSelectionState(
                String startRouteId,
                LatLng startPoint,
                String endRouteId,
                LatLng endPoint,
                String stagePlaceKey,
                LatLng stagePoint,
                String resolvedPathId
        ) {
            this.startRouteId =
                    startRouteId;

            this.startPoint =
                    startPoint;

            this.endRouteId =
                    endRouteId;

            this.endPoint =
                    endPoint;

            this.stagePlaceKey =
                    stagePlaceKey;

            this.stagePoint =
                    stagePoint;

            this.resolvedPathId =
                    resolvedPathId;
        }
    }

}
