package com.marukitano.caminoguard;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Persistent diagnostic trace for the foreground GPS/gyro heading pipeline.
 *
 * This class is deliberately observation-only. It must never influence
 * navigation, GPS course, gyro state, rendering or sensor lifecycle.
 *
 * The current and previous files are kept in app-internal storage so a rare
 * heading failure can be recovered later without ADB having been connected
 * when it occurred.
 */
final class CaminoHeadingTrace {

    private static final String TAG =
            "CaminoHeading";

    private static final String FILE_NAME =
            "camino-heading.log";

    private static final String PREVIOUS_FILE_NAME =
            "camino-heading-prev.log";

    private static final long MAX_FILE_BYTES =
            8L * 1024L * 1024L;

    private static final Object LOCK =
            new Object();

    private CaminoHeadingTrace() {
    }

    static void d(
            Context context,
            String message
    ) {
        Log.d(
                TAG,
                message
        );

        if (context == null
                || message == null) {

            return;
        }

        Context app =
                context.getApplicationContext();

        long wallTimeMs =
                System.currentTimeMillis();

        long elapsedMs =
                SystemClock.elapsedRealtime();

        String timestamp =
                new SimpleDateFormat(
                        "yyyy-MM-dd HH:mm:ss.SSS",
                        Locale.US
                ).format(
                        new Date(
                                wallTimeMs
                        )
                );

        String line =
                timestamp
                        + " elapsed="
                        + elapsedMs
                        + " "
                        + message
                        + "\n";

        byte[] bytes =
                line.getBytes(
                        StandardCharsets.UTF_8
                );

        synchronized (LOCK) {
            File current =
                    new File(
                            app.getFilesDir(),
                            FILE_NAME
                    );

            File previous =
                    new File(
                            app.getFilesDir(),
                            PREVIOUS_FILE_NAME
                    );

            try {
                if (current.exists()
                        && current.length()
                        + bytes.length
                        > MAX_FILE_BYTES) {

                    if (previous.exists()
                            && !previous.delete()) {

                        Log.w(
                                TAG,
                                "Could not remove previous heading trace"
                        );
                    }

                    if (!current.renameTo(
                            previous
                    )) {
                        /*
                         * Rotation failure must never affect navigation.
                         * Truncate only the diagnostic file and continue.
                         */
                        try (FileOutputStream ignored =
                                     new FileOutputStream(
                                             current,
                                             false
                                     )) {
                            // truncate
                        }
                    }
                }

                try (FileOutputStream output =
                             new FileOutputStream(
                                     current,
                                     true
                             )) {

                    output.write(
                            bytes
                    );
                }

            } catch (IOException
                    | RuntimeException error) {

                Log.w(
                        TAG,
                        "Could not persist heading trace",
                        error
                );
            }
        }
    }
}
