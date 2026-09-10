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
 * Produces a roads-only snapshot from the same offline PMTiles repository used
 * by Camino Guard's Android map.
 *
 * Screen 3 itself is only 200 x 228 px, but a heading-up map needs geometry
 * beyond those screen edges: the corners exposed by rotation would otherwise
 * be empty. Android therefore renders a 360 x 360 m square around the user at
 * 4x supersampling, then reduces it to a compact 256 x 256 one-bit road mask.
 * The Pebble samples that overscanned mask into its fixed 200 x 228 frame.
 * This keeps roads available at every heading without allocating a giant
 * colour bitmap on the watch.
 */
final class CaminoPebbleRoadSnapshotter
        implements AutoCloseable {

    static final int MASK_WIDTH = 256;
    static final int MASK_HEIGHT = 256;

    private static final int SUPERSAMPLE = 4;
    private static final int RENDER_WIDTH =
            MASK_WIDTH * SUPERSAMPLE;
    private static final int RENDER_HEIGHT =
            MASK_HEIGHT * SUPERSAMPLE;

    private static final double HALF_WIDTH_M = 180.0;
    private static final double HALF_HEIGHT_M = 180.0;
    private static final float CACHE_REUSE_DISTANCE_M = 20.0f;
    private static final int MASK_BYTES =
            (MASK_WIDTH * MASK_HEIGHT + 7) / 8;

    /*
     * A high-res road pixel is considered covered well before full white so
     * MapLibre's antialiased edge contributes to the final shape. Two covered
     * samples in a 4x4 block are enough to preserve a soft diagonal edge.
     */
    private static final int HIGH_RES_LUMINANCE_MIN = 72;
    private static final int MIN_COVERED_SUBPIXELS = 2;

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
             * move again while MapLibre is rendering the previous window.
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
             * Route and breadcrumb remain useful even if the road background
             * cannot be prepared. A later request may retry.
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
                            RENDER_WIDTH,
                            RENDER_HEIGHT
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
                || bitmap.getWidth() != RENDER_WIDTH
                || bitmap.getHeight() != RENDER_HEIGHT) {

            return null;
        }

        boolean[] occupied =
                new boolean[MASK_WIDTH * MASK_HEIGHT];

        for (int y = 0;
                y < MASK_HEIGHT;
                y++) {

            int sourceY =
                    y * SUPERSAMPLE;

            for (int x = 0;
                    x < MASK_WIDTH;
                    x++) {

                int sourceX =
                        x * SUPERSAMPLE;

                int covered = 0;

                for (int subY = 0;
                        subY < SUPERSAMPLE;
                        subY++) {

                    for (int subX = 0;
                            subX < SUPERSAMPLE;
                            subX++) {

                        int pixel =
                                bitmap.getPixel(
                                        sourceX + subX,
                                        sourceY + subY
                                );

                        if (Color.alpha(pixel) <= 0) {
                            continue;
                        }

                        int luminance =
                                Color.red(pixel)
                                        + Color.green(pixel)
                                        + Color.blue(pixel);

                        if (luminance >= HIGH_RES_LUMINANCE_MIN) {
                            covered++;
                        }
                    }
                }

                occupied[y * MASK_WIDTH + x] =
                        covered >= MIN_COVERED_SUBPIXELS;
            }
        }

        bridgeSinglePixelGaps(
                occupied
        );

        byte[] mask =
                new byte[MASK_BYTES];

        for (int bitIndex = 0;
                bitIndex < occupied.length;
                bitIndex++) {

            if (occupied[bitIndex]) {
                mask[bitIndex >> 3] |=
                        (byte) (1 << (bitIndex & 7));
            }
        }

        return mask;
    }

    private static void bridgeSinglePixelGaps(
            boolean[] occupied
    ) {
        if (occupied == null
                || occupied.length
                != MASK_WIDTH * MASK_HEIGHT) {

            return;
        }

        boolean[] source =
                occupied.clone();

        for (int y = 1;
                y < MASK_HEIGHT - 1;
                y++) {

            for (int x = 1;
                    x < MASK_WIDTH - 1;
                    x++) {

                int index =
                        y * MASK_WIDTH + x;

                if (source[index]) {
                    continue;
                }

                boolean horizontal =
                        source[index - 1]
                                && source[index + 1];

                boolean vertical =
                        source[index - MASK_WIDTH]
                                && source[index + MASK_WIDTH];

                boolean diagonalDown =
                        source[index - MASK_WIDTH - 1]
                                && source[index + MASK_WIDTH + 1];

                boolean diagonalUp =
                        source[index - MASK_WIDTH + 1]
                                && source[index + MASK_WIDTH - 1];

                if (horizontal
                        || vertical
                        || diagonalDown
                        || diagonalUp) {

                    occupied[index] =
                            true;
                }
            }
        }
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
                /* 8 high-res pixels become roughly a 2 px road before watch-side rounding. */
                + "\"line-width\":8.0,\"line-opacity\":1.0},"
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
