package com.marukitano.caminoguard;

import android.os.Handler;
import android.os.Looper;

import java.lang.ref.WeakReference;

/**
 * Process-local bridge between PebbleKit's bound listener service and the
 * active tracking-service presentation adapter.
 *
 * Opening the watchapp must never start Android GPS by itself. Therefore the
 * Pebble listener only talks to an already-existing publisher through this
 * weak reference. If CaminoTrackingService is not alive, there is simply no
 * Android-backed data source to wake up.
 */
final class CaminoPebbleSession {

    private static final long MAP_PAGE_SETTLE_MS =
            450L;

    interface Listener {
        void onPebbleOpened();
        void onPebbleClosed();
        void onPebblePageChanged(int page);
        void onPebbleStopDelta(int delta);
    }

    private static WeakReference<Listener> listenerRef =
            new WeakReference<>(null);

    private static final Handler mainHandler =
            new Handler(Looper.getMainLooper());

    private static boolean watchOpen;
    private static int page;
    private static int pageDispatchGeneration;

    private CaminoPebbleSession() {
    }

    static synchronized void setListener(
            Listener listener
    ) {
        listenerRef =
                new WeakReference<>(
                        listener
                );

        if (listener != null
                && watchOpen) {

            listener.onPebbleOpened();
            dispatchPageChangedLocked(
                    page
            );
        }
    }

    static synchronized boolean isWatchOpen() {
        return watchOpen;
    }

    static synchronized int page() {
        return page;
    }

    static synchronized void watchOpened() {
        watchOpen =
                true;

        page =
                0;

        pageDispatchGeneration++;

        Listener listener =
                listenerRef.get();

        if (listener != null) {
            listener.onPebbleOpened();
            listener.onPebblePageChanged(
                    page
            );
        }
    }

    static synchronized void watchClosed() {
        watchOpen =
                false;

        pageDispatchGeneration++;

        Listener listener =
                listenerRef.get();

        if (listener != null) {
            listener.onPebbleClosed();
        }
    }

    static synchronized void pageChanged(
            int requestedPage
    ) {
        page =
                Math.max(
                        0,
                        Math.min(
                                2,
                                requestedPage
                        )
                );

        int requestedGeneration =
                ++pageDispatchGeneration;

        if (page != 2) {
            dispatchPageChangedLocked(
                    page
            );
            return;
        }

        /*
         * Screen 3 can require several KB of compressed road geometry. The
         * watch sends its target page when the Nasu-style snap starts, not
         * after the slide has finished. Starting that transfer immediately
         * competes with the 16 ms animation loop and can make a valid swipe
         * look as if the large map is pushing itself on-screen in slow motion.
         * Give the page transition a short head start; a newer page command
         * invalidates this delayed dispatch automatically.
         */
        mainHandler.postDelayed(
                () -> dispatchSettledMapPage(
                        requestedGeneration
                ),
                MAP_PAGE_SETTLE_MS
        );
    }

    private static synchronized void dispatchSettledMapPage(
            int requestedGeneration
    ) {
        if (!watchOpen
                || page != 2
                || requestedGeneration
                != pageDispatchGeneration) {

            return;
        }

        dispatchPageChangedLocked(
                2
        );
    }

    private static void dispatchPageChangedLocked(
            int changedPage
    ) {
        Listener listener =
                listenerRef.get();

        if (listener != null) {
            listener.onPebblePageChanged(
                    changedPage
            );
        }
    }

    static synchronized void stopDelta(
            int delta
    ) {
        if (delta == 0) {
            return;
        }

        Listener listener =
                listenerRef.get();

        if (listener != null) {
            listener.onPebbleStopDelta(
                    delta < 0
                            ? -1
                            : 1
            );
        }
    }
}
