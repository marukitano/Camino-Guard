package com.marukitano.caminoguard;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * Persistent raw walking-performance history.
 *
 * One row is approximately one minute of actual MOVING time. Raw rows are
 * deliberately retained so future statistics/export can compare days and
 * weeks without having lost the original data.
 */
final class WalkingPerformanceStore
        extends SQLiteOpenHelper {

    private static final String DATABASE_NAME =
            "walking-performance.db";

    private static final int DATABASE_VERSION =
            2;

    private static final String TABLE =
            "walking_minute_sample";

    WalkingPerformanceStore(
            Context context
    ) {
        super(
                context.getApplicationContext(),
                DATABASE_NAME,
                null,
                DATABASE_VERSION
        );
    }

    @Override
    public void onCreate(
            SQLiteDatabase db
    ) {
        db.execSQL(
                "CREATE TABLE " + TABLE + " ("
                        + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                        + "started_at_ms INTEGER NOT NULL,"
                        + "ended_at_ms INTEGER NOT NULL,"
                        + "moving_ms INTEGER NOT NULL,"
                        + "distance_m REAL NOT NULL,"
                        + "grade_pct REAL NOT NULL,"
                        + "speed_kmh REAL NOT NULL,"
                        + "start_lat REAL,"
                        + "start_lon REAL,"
                        + "end_lat REAL,"
                        + "end_lon REAL,"
                        + "route_group_id TEXT,"
                        + "section_id TEXT"
                        + ")"
        );

        db.execSQL(
                "CREATE INDEX idx_walking_sample_time "
                        + "ON " + TABLE
                        + "(ended_at_ms)"
        );

        db.execSQL(
                "CREATE INDEX idx_walking_sample_grade "
                        + "ON " + TABLE
                        + "(grade_pct)"
        );

        createPauseTable(
                db
        );
    }

    @Override
    public void onUpgrade(
            SQLiteDatabase db,
            int oldVersion,
            int newVersion
    ) {
        /*
         * Never drop historical walking or pause data.
         */
        if (oldVersion < 2) {
            createPauseTable(
                    db
            );
        }
    }

    private void createPauseTable(
            SQLiteDatabase db
    ) {
        db.execSQL(
                "CREATE TABLE IF NOT EXISTS walking_pause ("
                        + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                        + "started_at_ms INTEGER NOT NULL,"
                        + "ended_at_ms INTEGER,"
                        + "duration_ms INTEGER NOT NULL DEFAULT 0,"
                        + "start_lat REAL NOT NULL,"
                        + "start_lon REAL NOT NULL,"
                        + "end_lat REAL,"
                        + "end_lon REAL,"
                        + "route_group_id TEXT,"
                        + "section_id TEXT"
                        + ")"
        );

        db.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_walking_pause_time "
                        + "ON walking_pause(started_at_ms)"
        );
    }

    void insert(
            WalkingMinuteSample sample
    ) {
        ContentValues values =
                new ContentValues();

        values.put(
                "started_at_ms",
                sample.startedAtMs
        );

        values.put(
                "ended_at_ms",
                sample.endedAtMs
        );

        values.put(
                "moving_ms",
                sample.movingMs
        );

        values.put(
                "distance_m",
                sample.distanceM
        );

        values.put(
                "grade_pct",
                sample.gradePct
        );

        values.put(
                "speed_kmh",
                sample.speedKmh
        );

        values.put(
                "start_lat",
                sample.startLat
        );

        values.put(
                "start_lon",
                sample.startLon
        );

        values.put(
                "end_lat",
                sample.endLat
        );

        values.put(
                "end_lon",
                sample.endLon
        );

        values.put(
                "route_group_id",
                sample.routeGroupId
        );

        values.put(
                "section_id",
                sample.sectionId
        );

        getWritableDatabase().insertOrThrow(
                TABLE,
                null,
                values
        );
    }

    long beginPause(
            WalkingPause pause
    ) {
        ContentValues values =
                new ContentValues();

        values.put(
                "started_at_ms",
                pause.startedAtMs
        );

        values.put(
                "duration_ms",
                0L
        );

        values.put(
                "start_lat",
                pause.startLat
        );

        values.put(
                "start_lon",
                pause.startLon
        );

        values.put(
                "route_group_id",
                pause.routeGroupId
        );

        values.put(
                "section_id",
                pause.sectionId
        );

        return getWritableDatabase().insertOrThrow(
                "walking_pause",
                null,
                values
        );
    }

    void endPause(
            long pauseId,
            long endedAtMs,
            long durationMs,
            double endLat,
            double endLon
    ) {
        if (pauseId < 0L) {
            return;
        }

        ContentValues values =
                new ContentValues();

        values.put(
                "ended_at_ms",
                endedAtMs
        );

        values.put(
                "duration_ms",
                Math.max(
                        0L,
                        durationMs
                )
        );

        values.put(
                "end_lat",
                endLat
        );

        values.put(
                "end_lon",
                endLon
        );

        getWritableDatabase().update(
                "walking_pause",
                values,
                "id = ?",
                new String[]{
                        Long.toString(
                                pauseId
                        )
                }
        );
    }

    WalkingPauseSummary pauseSummarySince(
            long minimumStartedAtMs
    ) {
        int count =
                0;

        long durationMs =
                0L;

        try (Cursor cursor =
                     getReadableDatabase().rawQuery(
                             "SELECT COUNT(*), "
                                     + "COALESCE(SUM(duration_ms), 0) "
                                     + "FROM walking_pause "
                                     + "WHERE started_at_ms >= ? "
                                     + "AND ended_at_ms IS NOT NULL",
                             new String[]{
                                     Long.toString(
                                             minimumStartedAtMs
                                     )
                             }
                     )) {

            if (cursor.moveToFirst()) {
                count =
                        cursor.getInt(
                                0
                        );

                durationMs =
                        cursor.getLong(
                                1
                        );
            }
        }

        return new WalkingPauseSummary(
                count,
                durationMs
        );
    }

    List<WalkingMinuteSample> loadSince(
            long minimumEndedAtMs
    ) {
        List<WalkingMinuteSample> result =
                new ArrayList<>();

        try (Cursor cursor =
                     getReadableDatabase().query(
                             TABLE,
                             new String[]{
                                     "started_at_ms",
                                     "ended_at_ms",
                                     "moving_ms",
                                     "distance_m",
                                     "grade_pct",
                                     "speed_kmh",
                                     "start_lat",
                                     "start_lon",
                                     "end_lat",
                                     "end_lon",
                                     "route_group_id",
                                     "section_id"
                             },
                             "ended_at_ms >= ?",
                             new String[]{
                                     Long.toString(
                                             minimumEndedAtMs
                                     )
                             },
                             null,
                             null,
                             "ended_at_ms ASC"
                     )) {

            int startedAtIndex =
                    cursor.getColumnIndexOrThrow(
                            "started_at_ms"
                    );

            int endedAtIndex =
                    cursor.getColumnIndexOrThrow(
                            "ended_at_ms"
                    );

            int movingIndex =
                    cursor.getColumnIndexOrThrow(
                            "moving_ms"
                    );

            int distanceIndex =
                    cursor.getColumnIndexOrThrow(
                            "distance_m"
                    );

            int gradeIndex =
                    cursor.getColumnIndexOrThrow(
                            "grade_pct"
                    );

            int speedIndex =
                    cursor.getColumnIndexOrThrow(
                            "speed_kmh"
                    );

            int startLatIndex =
                    cursor.getColumnIndexOrThrow(
                            "start_lat"
                    );

            int startLonIndex =
                    cursor.getColumnIndexOrThrow(
                            "start_lon"
                    );

            int endLatIndex =
                    cursor.getColumnIndexOrThrow(
                            "end_lat"
                    );

            int endLonIndex =
                    cursor.getColumnIndexOrThrow(
                            "end_lon"
                    );

            int routeIndex =
                    cursor.getColumnIndexOrThrow(
                            "route_group_id"
                    );

            int sectionIndex =
                    cursor.getColumnIndexOrThrow(
                            "section_id"
                    );

            while (cursor.moveToNext()) {
                result.add(
                        new WalkingMinuteSample(
                                cursor.getLong(
                                        startedAtIndex
                                ),
                                cursor.getLong(
                                        endedAtIndex
                                ),
                                cursor.getLong(
                                        movingIndex
                                ),
                                cursor.getDouble(
                                        distanceIndex
                                ),
                                cursor.getDouble(
                                        gradeIndex
                                ),
                                cursor.getDouble(
                                        speedIndex
                                ),
                                cursor.getDouble(
                                        startLatIndex
                                ),
                                cursor.getDouble(
                                        startLonIndex
                                ),
                                cursor.getDouble(
                                        endLatIndex
                                ),
                                cursor.getDouble(
                                        endLonIndex
                                ),
                                cursor.isNull(
                                        routeIndex
                                )
                                        ? null
                                        : cursor.getString(
                                                routeIndex
                                        ),
                                cursor.isNull(
                                        sectionIndex
                                )
                                        ? null
                                        : cursor.getString(
                                                sectionIndex
                                        )
                        )
                );
            }
        }

        return result;
    }
}


final class WalkingPause {

    final long startedAtMs;

    final double startLat;
    final double startLon;

    final String routeGroupId;
    final String sectionId;

    WalkingPause(
            long startedAtMs,
            double startLat,
            double startLon,
            String routeGroupId,
            String sectionId
    ) {
        this.startedAtMs =
                startedAtMs;

        this.startLat =
                startLat;

        this.startLon =
                startLon;

        this.routeGroupId =
                routeGroupId;

        this.sectionId =
                sectionId;
    }
}


final class WalkingPauseSummary {

    final int count;
    final long durationMs;

    WalkingPauseSummary(
            int count,
            long durationMs
    ) {
        this.count =
                count;

        this.durationMs =
                durationMs;
    }
}


final class WalkingMinuteSample {

    final long startedAtMs;
    final long endedAtMs;
    final long movingMs;

    final double distanceM;
    final double gradePct;
    final double speedKmh;

    final double startLat;
    final double startLon;
    final double endLat;
    final double endLon;

    final String routeGroupId;
    final String sectionId;

    WalkingMinuteSample(
            long startedAtMs,
            long endedAtMs,
            long movingMs,
            double distanceM,
            double gradePct,
            double speedKmh,
            double startLat,
            double startLon,
            double endLat,
            double endLon,
            String routeGroupId,
            String sectionId
    ) {
        this.startedAtMs =
                startedAtMs;

        this.endedAtMs =
                endedAtMs;

        this.movingMs =
                movingMs;

        this.distanceM =
                distanceM;

        this.gradePct =
                gradePct;

        this.speedKmh =
                speedKmh;

        this.startLat =
                startLat;

        this.startLon =
                startLon;

        this.endLat =
                endLat;

        this.endLon =
                endLon;

        this.routeGroupId =
                routeGroupId;

        this.sectionId =
                sectionId;
    }
}
