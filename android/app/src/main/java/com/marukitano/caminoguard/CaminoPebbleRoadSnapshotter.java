package com.marukitano.caminoguard;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.location.Location;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;
import org.maplibre.android.MapLibre;
import org.maplibre.android.geometry.LatLngBounds;
import org.maplibre.android.maps.Style;
import org.maplibre.android.snapshotter.MapSnapshotter;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Produces a tiny roads-only snapshot from the same offline PMTiles repository
 * used by Camino Guard's Android map.
 *
 * The Pebble does not need terrain, labels or land-use. It only needs enough
 * nearby street/path geometry to answer "which way gets me back?". Rendering
 * at 100x114 gives a compact one-bit mask that the watch can scale exactly 2x
 * to its 200x228 screen while keeping a simple one-pixel-per-metre overlay.
 */
final class CaminoPebbleRoadSnapshotter
        implements AutoCloseable {

    static final int MASK_WIDTH = 100;
    static final int MASK_HEIGHT = 114;

    private static final double HALF_WIDTH_M = 100.0;
    private static final double HALF_HEIGHT_M = 114.0;
    private static final float CACHE_REUSE_DISTANCE_M = 20.0f;
    private static final int MASK_BYTES =
            (MASK_WIDTH * MASK_HEIGHT + 7) / 8;

    interface Callback {
        void onReady(Result result);
    }

    static final class Result {
        final Location center;
        final byte[] roadMask;

        Result(
                Location center,
                byte[] roadMask
        ) {
            this.center =
                    center == null
                            ? null
                            : new Location(center);

            this.roadMask =
                    roadMask == null
                            ? null
                            : roadMask.clone();
        }

        boolean hasRoadMask() {
            return roadMask != null
                    && roadMask.length == MASK_BYTES;
        }
    }

    private static final class Request {
        final Location center;
        final Callback callback;

        Request(
                Location center,
                Callback callback
        ) {
            this.center =
                    new Location(center);
            this.callback =
                    callback;
        }
    }

    private final Context context;
    private final Handler mainHandler =
            new Handler(Looper.getMainLooper());
    private final ExecutorService ioExecutor =
            Executors.newSingleThreadExecutor();

    private boolean closed;
    private boolean preparingStyle;
    private boolean snapshotRunning;
    private String roadStyleJson;
    private Request pendingRequest;
    private Result cachedResult;
    private MapSnapshotter activeSnapshotter;

    CaminoPebbleRoadSnapshotter(
            Context context
    ) {
        this.context =
                context.getApplicationContext();
    }

    void request(
            Location center,
            Callback callback
    ) {
        if (center == null
                || callback == null) {
            return;
        }

        Result cached;
        boolean prepare = false;
        boolean start = false;

        synchronized (this) {
            if (closed) {
                return;
            }

            cached =
                    reusableCachedResult(center);

            if (cached != null) {
                mainHandler.post(
                        () -> callback.onReady(cached)
                );
                return;
            }

            /*
             * Only the newest requested centre matters. A fast walker can
             * easily move again while MapLibre is rendering the previous
             * 200x228 m neighbourhood.
             */
            pendingRequest =
                    new Request(
                            center,
                            callback
                    );

            if (roadStyleJson == null
                    && !preparingStyle) {

                preparingStyle =
                        true;
                prepare =
                        true;

            } else if (roadStyleJson != null
                    && !snapshotRunning) {

                start =
                        true;
            }
        }

        if (prepare) {
            ioExecutor.execute(
                    this::prepareStyle
            );
        } else if (start) {
            mainHandler.post(
                    this::startPendingSnapshotOnMain
            );
        }
    }

    private Result reusableCachedResult(
            Location requestedCenter
    ) {
        if (cachedResult == null
                || cachedResult.center == null
                || !cachedResult.hasRoadMask()) {

            return null;
        }

        float distance =
                cachedResult.center.distanceTo(
                        requestedCenter
                );

        if (!Float.isFinite(distance)
                || distance > CACHE_REUSE_DISTANCE_M) {

            return null;
        }

        return cachedResult;
    }

    private void prepareStyle() {
        String style =
                null;

        try {
            OfflineMapRepository.InstalledMaps maps =
                    new OfflineMapRepository(
                            context,
                            null
                    ).ensureInstalled();

            style =
                    buildRoadStyle(
                            maps
                    );

        } catch (Exception ignored) {
            /*
             * Route and breadcrumb remain useful even if the tiny road
             * background cannot be prepared. A later request may retry.
             */
        }

        boolean shouldStart;

        synchronized (this) {
            preparingStyle =
                    false;

            if (closed) {
                return;
            }

            roadStyleJson =
                    style;

            shouldStart =
                    pendingRequest != null;
        }

        if (style == null) {
            finishWithoutRoads();
            return;
        }

        if (shouldStart) {
            mainHandler.post(
                    this::startPendingSnapshotOnMain
            );
        }
    }

    private void startPendingSnapshotOnMain() {
        Request request;
        String style;

        synchronized (this) {
            if (closed
                    || snapshotRunning
                    || pendingRequest == null
                    || roadStyleJson == null) {

                return;
            }

            request =
                    pendingRequest;
            pendingRequest =
                    null;

            style =
                    roadStyleJson;

            snapshotRunning =
                    true;
        }

        try {
            MapLibre.getInstance(
                    context
            );

            MapSnapshotter.Options options =
                    new MapSnapshotter.Options(
                            MASK_WIDTH,
                            MASK_HEIGHT
                    )
                            .withPixelRatio(1.0f)
                            .withAttribution(false)
                            .withLogo(false)
                            .withRegion(
                                    boundsAround(
                                            request.center
                                    )
                            )
                            .withStyleBuilder(
                                    new Style.Builder()
                                            .fromJson(style)
                            );

            MapSnapshotter snapshotter =
                    new MapSnapshotter(
                            context,
                            options
                    );

            synchronized (this) {
                if (closed) {
                    snapshotRunning =
                            false;
                    snapshotter.cancel();
                    return;
                }

                activeSnapshotter =
                        snapshotter;
            }

            snapshotter.start(
                    snapshot -> finishSnapshot(
                            request,
                            roadMask(
                                    snapshot.getBitmap()
                            )
                    ),
                    error -> finishSnapshot(
                            request,
                            null
                    )
            );

        } catch (RuntimeException error) {
            finishSnapshot(
                    request,
                    null
            );
        }
    }

    private void finishWithoutRoads() {
        Request request;

        synchronized (this) {
            if (closed) {
                return;
            }

            request =
                    pendingRequest;
            pendingRequest =
                    null;
        }

        if (request != null) {
            mainHandler.post(
                    () -> request.callback.onReady(
                            new Result(
                                    request.center,
                                    null
                            )
                    )
            );
        }
    }

    private void finishSnapshot(
            Request request,
            byte[] roadMask
    ) {
        Request next;
        String style;
        Result result =
                new Result(
                        request.center,
                        roadMask
                );

        synchronized (this) {
            if (closed) {
                return;
            }

            snapshotRunning =
                    false;
            activeSnapshotter =
                    null;

            if (result.hasRoadMask()) {
                cachedResult =
                        result;
            }

            next =
                    pendingRequest;
            style =
                    roadStyleJson;
        }

        request.callback.onReady(
                result
        );

        if (next != null
                && style != null) {
            mainHandler.post(
                    this::startPendingSnapshotOnMain
            );
        }
    }

    private static byte[] roadMask(
            Bitmap bitmap
    ) {
        if (bitmap == null
                || bitmap.getWidth() != MASK_WIDTH
                || bitmap.getHeight() != MASK_HEIGHT) {

            return null;
        }

        byte[] mask =
                new byte[MASK_BYTES];

        int bitIndex =
                0;

        for (int y = 0;
                y < MASK_HEIGHT;
                y++) {

            for (int x = 0;
                    x < MASK_WIDTH;
                    x++) {

                int pixel =
                        bitmap.getPixel(
                                x,
                                y
                        );

                int luminance =
                        Color.red(pixel)
                                + Color.green(pixel)
                                + Color.blue(pixel);

                if (Color.alpha(pixel) > 0
                        && luminance >= 72) {

                    mask[bitIndex >> 3] |=
                            (byte) (1 << (bitIndex & 7));
                }

                bitIndex++;
            }
        }

        return mask;
    }

    private static LatLngBounds boundsAround(
            Location center
    ) {
        double latitude =
                center.getLatitude();

        double latitudeDelta =
                HALF_HEIGHT_M
                        / 110_540.0;

        double cosine =
                Math.cos(
                        Math.toRadians(
                                latitude
                        )
                );

        double safeCosine =
                Math.max(
                        0.01,
                        Math.abs(cosine)
                );

        double longitudeDelta =
                HALF_WIDTH_M
                        / (111_320.0 * safeCosine);

        return LatLngBounds.from(
                latitude + latitudeDelta,
                center.getLongitude() + longitudeDelta,
                latitude - latitudeDelta,
                center.getLongitude() - longitudeDelta
        );
    }

    private static String buildRoadStyle(
            OfflineMapRepository.InstalledMaps maps
    ) {
        String iberiaUrl =
                JSONObject.quote(
                        pmTilesUrl(
                                maps.iberiaMap
                        )
                );

        String schaffhausenUrl =
                JSONObject.quote(
                        pmTilesUrl(
                                maps.schaffhausenMap
                        )
                );

        return "{"
                + "\"version\":8,"
                + "\"sources\":{"
                + "\"iberia\":{\"type\":\"vector\",\"url\":"
                + iberiaUrl
                + "},"
                + "\"schaffhausen\":{\"type\":\"vector\",\"url\":"
                + schaffhausenUrl
                + "}},"
                + "\"layers\":["
                + "{\"id\":\"background\",\"type\":\"background\","
                + "\"paint\":{\"background-color\":\"#000000\"}},"
                + roadLayer(
                        "roads-iberia",
                        "iberia"
                )
                + ","
                + roadLayer(
                        "roads-schaffhausen",
                        "schaffhausen"
                )
                + "]} ";
    }

    private static String roadLayer(
            String id,
            String source
    ) {
        return "{\"id\":"
                + JSONObject.quote(id)
                + ",\"type\":\"line\",\"source\":"
                + JSONObject.quote(source)
                + ",\"source-layer\":\"roads\","
                + "\"filter\":[\"in\",\"kind\","
                + "\"path\",\"other\",\"minor_road\","
                + "\"medium_road\",\"major_road\",\"highway\"],"
                + "\"paint\":{\"line-color\":\"#FFFFFF\","
                + "\"line-width\":1.15,\"line-opacity\":1.0},"
                + "\"layout\":{\"line-cap\":\"round\","
                + "\"line-join\":\"round\"}}";
    }

    private static String pmTilesUrl(
            java.io.File file
    ) {
        return "pmtiles://"
                + Uri.fromFile(file);
    }

    @Override
    public void close() {
        MapSnapshotter snapshotter;

        synchronized (this) {
            if (closed) {
                return;
            }

            closed =
                    true;
            pendingRequest =
                    null;
            cachedResult =
                    null;

            snapshotter =
                    activeSnapshotter;
            activeSnapshotter =
                    null;
        }

        ioExecutor.shutdownNow();

        if (snapshotter != null) {
            mainHandler.post(
                    snapshotter::cancel
            );
        }
    }
}
