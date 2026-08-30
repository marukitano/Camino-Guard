package com.marukitano.caminoguard;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.maplibre.android.geometry.LatLng;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;


/**
 * Persistent runtime snapshot of the currently LOCKED MeasurementPath.
 *
 * This is navigation state, not another Camino data source.
 * Canonical Camino geometry still comes exclusively from camino-global.json.
 */
final class LockedMeasurementPathStore {

    private static final String TAG =
            "LockedMeasurementPath";

    private static final String PREFS =
            "locked-measurement-path";

    private static final String KEY_ACTIVE =
            "active";

    private static final String KEY_VERSION =
            "version";

    private static final String FILE_NAME =
            "locked-measurement-path.bin";

    private static final String TEMP_FILE_NAME =
            "locked-measurement-path.tmp";

    private static final int MAGIC =
            0x43474D50; // CGMP

    private static final int FORMAT_VERSION =
            2;

    private static final int MIN_SUPPORTED_FORMAT_VERSION =
            1;


    static final class Snapshot {

        final int version;
        final MeasurementPath path;

        Snapshot(
                int version,
                MeasurementPath path
        ) {
            this.version =
                    version;

            this.path =
                    path;
        }
    }


    private final Context context;
    private final SharedPreferences preferences;

    private int cachedVersion =
            Integer.MIN_VALUE;

    private MeasurementPath cachedPath;


    LockedMeasurementPathStore(
            Context context
    ) {
        this.context =
                context.getApplicationContext();

        preferences =
                this.context.getSharedPreferences(
                        PREFS,
                        Context.MODE_PRIVATE
                );
    }


    static boolean hasActivePath(
            Context context
    ) {
        Context appContext =
                context.getApplicationContext();

        SharedPreferences prefs =
                appContext.getSharedPreferences(
                        PREFS,
                        Context.MODE_PRIVATE
                );

        File file =
                new File(
                        appContext.getFilesDir(),
                        FILE_NAME
                );

        return prefs.getBoolean(
                KEY_ACTIVE,
                false
        )
                && file.isFile()
                && hasCurrentFormat(
                        file
                );
    }


    static void save(
            Context context,
            MeasurementPath path
    ) {
        if (context == null
                || path == null
                || path.profilePoints == null
                || path.profilePoints.size() < 2
                || !Double.isFinite(
                        path.distanceM
                )
                || path.distanceM <= 0.0) {

            return;
        }

        Context appContext =
                context.getApplicationContext();

        /*
         * Android's visible timetable enriches the selected MeasurementPath
         * with the precomputed settlement markers before building its plan.
         *
         * Persist that same finalized stop list so background consumers,
         * especially Pebble, cannot see a different timetable from Android.
         *
         * Geometry remains the original locked MeasurementPath. Only timetable
         * stop metadata comes from the enriched copy.
         */
        MeasurementPath timetablePath =
                new CaminoSettlementTimetableSource(
                        appContext
                ).withSettlementStops(
                        path
                );

        java.util.List<CaminoTimetablePathStop> persistedTimetableStops =
                timetablePath != null
                        && timetablePath.timetableStops != null
                        ? timetablePath.timetableStops
                        : CaminoTimetablePathStops.normalizeRouteStops(
                                path.distanceM,
                                path.timetableStops
                        );

        File target =
                new File(
                        appContext.getFilesDir(),
                        FILE_NAME
                );

        File temporary =
                new File(
                        appContext.getFilesDir(),
                        TEMP_FILE_NAME
                );

        try (
                FileOutputStream fileOutput =
                        new FileOutputStream(
                                temporary,
                                false
                        );

                BufferedOutputStream buffered =
                        new BufferedOutputStream(
                                fileOutput
                        );

                DataOutputStream output =
                        new DataOutputStream(
                                buffered
                        )
        ) {
            output.writeInt(
                    MAGIC
            );

            output.writeInt(
                    FORMAT_VERSION
            );

            output.writeDouble(
                    path.distanceM
            );

            output.writeInt(
                    path.profilePoints.size()
            );

            for (ProfilePoint point
                    : path.profilePoints) {

                if (point == null
                        || point.point == null
                        || !Double.isFinite(
                                point.point.getLatitude()
                        )
                        || !Double.isFinite(
                                point.point.getLongitude()
                        )
                        || !Double.isFinite(
                                point.distanceM
                        )) {

                    throw new IllegalStateException(
                            "invalid profile point"
                    );
                }

                output.writeDouble(
                        point.point.getLatitude()
                );

                output.writeDouble(
                        point.point.getLongitude()
                );

                output.writeDouble(
                        point.distanceM
                );

                output.writeDouble(
                        point.elevationM
                );

                output.writeBoolean(
                        point.breakBefore
                );
            }

            output.writeInt(
                    persistedTimetableStops.size()
            );

            for (CaminoTimetablePathStop stop
                    : persistedTimetableStops) {

                if (stop == null
                        || stop.placeKey == null
                        || stop.placeKey.trim().isEmpty()
                        || !Double.isFinite(
                        stop.chainageM
                )
                        || stop.chainageM < 0.0
                        || stop.chainageM
                        > path.distanceM + 0.5) {

                    throw new IllegalStateException(
                            "invalid timetable stop"
                    );
                }

                output.writeUTF(
                        stop.placeKey
                );

                output.writeDouble(
                        stop.chainageM
                );
            }

            output.flush();

        } catch (Exception error) {
            temporary.delete();

            Log.e(
                    TAG,
                    "Could not save locked path",
                    error
            );

            return;
        }

        if (target.exists()
                && !target.delete()) {

            temporary.delete();

            Log.e(
                    TAG,
                    "Could not replace locked path"
            );

            return;
        }

        if (!temporary.renameTo(
                target
        )) {
            temporary.delete();

            Log.e(
                    TAG,
                    "Could not activate locked path"
            );

            return;
        }

        SharedPreferences prefs =
                appContext.getSharedPreferences(
                        PREFS,
                        Context.MODE_PRIVATE
                );

        int nextVersion =
                prefs.getInt(
                        KEY_VERSION,
                        0
                )
                        + 1;

        prefs.edit()
                .putBoolean(
                        KEY_ACTIVE,
                        true
                )
                .putInt(
                        KEY_VERSION,
                        nextVersion
                )
                .apply();
    }


    static void clear(
            Context context
    ) {
        if (context == null) {
            return;
        }

        Context appContext =
                context.getApplicationContext();

        File target =
                new File(
                        appContext.getFilesDir(),
                        FILE_NAME
                );

        File temporary =
                new File(
                        appContext.getFilesDir(),
                        TEMP_FILE_NAME
                );

        target.delete();
        temporary.delete();

        SharedPreferences prefs =
                appContext.getSharedPreferences(
                        PREFS,
                        Context.MODE_PRIVATE
                );

        int nextVersion =
                prefs.getInt(
                        KEY_VERSION,
                        0
                )
                        + 1;

        prefs.edit()
                .putBoolean(
                        KEY_ACTIVE,
                        false
                )
                .putInt(
                        KEY_VERSION,
                        nextVersion
                )
                .apply();
    }


    Snapshot currentLockedPath() {
        /*
         * Hard gate number one:
         * no persisted UI lock = absolutely no study path.
         */
        CaminoUiStateStore.LockedSelectionState locked =
                new CaminoUiStateStore(
                        context
                ).restoreLockedSelection();

        if (locked == null) {
            cachedPath =
                    null;

            return null;
        }

        if (!preferences.getBoolean(
                KEY_ACTIVE,
                false
        )) {
            cachedPath =
                    null;

            return null;
        }

        int version =
                preferences.getInt(
                        KEY_VERSION,
                        0
                );

        if (cachedPath != null
                && cachedVersion == version) {

            return new Snapshot(
                    version,
                    cachedPath
            );
        }

        File file =
                new File(
                        context.getFilesDir(),
                        FILE_NAME
                );

        MeasurementPath loaded =
                read(
                        file
                );

        if (loaded == null) {
            cachedPath =
                    null;
            return null;
        }

        cachedVersion =
                version;

        cachedPath =
                loaded;

        return new Snapshot(
                version,
                loaded
        );
    }


    private static boolean hasCurrentFormat(
            File file
    ) {
        if (file == null
                || !file.isFile()) {

            return false;
        }

        try (
                FileInputStream fileInput =
                        new FileInputStream(
                                file
                        );

                BufferedInputStream buffered =
                        new BufferedInputStream(
                                fileInput
                        );

                DataInputStream input =
                        new DataInputStream(
                                buffered
                        )
        ) {
            return input.readInt() == MAGIC
                    && input.readInt()
                    == FORMAT_VERSION;

        } catch (Exception ignored) {
            return false;
        }
    }


    private MeasurementPath read(
            File file
    ) {
        if (file == null
                || !file.isFile()) {

            return null;
        }

        try (
                FileInputStream fileInput =
                        new FileInputStream(
                                file
                        );

                BufferedInputStream buffered =
                        new BufferedInputStream(
                                fileInput
                        );

                DataInputStream input =
                        new DataInputStream(
                                buffered
                        )
        ) {
            if (input.readInt()
                    != MAGIC) {

                return null;
            }

            int formatVersion =
                    input.readInt();

            if (formatVersion
                    < MIN_SUPPORTED_FORMAT_VERSION
                    || formatVersion
                    > FORMAT_VERSION) {

                return null;
            }

            double distanceM =
                    input.readDouble();

            int count =
                    input.readInt();

            if (!Double.isFinite(
                    distanceM
            )
                    || distanceM <= 0.0
                    || count < 2
                    || count > 200_000) {

                return null;
            }

            MeasurementPath path =
                    new MeasurementPath();

            path.distanceM =
                    distanceM;

            for (int index = 0;
                    index < count;
                    index++) {

                double latitude =
                        input.readDouble();

                double longitude =
                        input.readDouble();

                double chainageM =
                        input.readDouble();

                double elevationM =
                        input.readDouble();

                boolean breakBefore =
                        input.readBoolean();

                if (!Double.isFinite(
                        latitude
                )
                        || latitude < -90.0
                        || latitude > 90.0
                        || !Double.isFinite(
                        longitude
                )
                        || longitude < -180.0
                        || longitude > 180.0
                        || !Double.isFinite(
                        chainageM
                )) {

                    return null;
                }

                path.profilePoints.add(
                        new ProfilePoint(
                                new LatLng(
                                        latitude,
                                        longitude
                                ),
                                chainageM,
                                elevationM,
                                breakBefore
                        )
                );
            }

            if (formatVersion >= 2) {
                int stopCount =
                        input.readInt();

                if (stopCount < 0
                        || stopCount > 10_000) {

                    return null;
                }

                for (int index = 0;
                        index < stopCount;
                        index++) {

                    String placeKey =
                            input.readUTF();

                    double chainageM =
                            input.readDouble();

                    if (placeKey == null
                            || placeKey.trim().isEmpty()
                            || !Double.isFinite(
                            chainageM
                    )
                            || chainageM < 0.0
                            || chainageM
                            > distanceM + 0.5) {

                        return null;
                    }

                    path.timetableStops.add(
                            new CaminoTimetablePathStop(
                                    placeKey,
                                    chainageM
                            )
                    );
                }
            }

            return path;

        } catch (Exception error) {
            Log.e(
                    TAG,
                    "Could not read locked path",
                    error
            );

            return null;
        }
    }
}
