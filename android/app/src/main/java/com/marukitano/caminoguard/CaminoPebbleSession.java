package com.marukitano.caminoguard;

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

    interface Listener {
        void onPebbleOpened();
        void onPebbleClosed();
        void onPebblePageChanged(int page);
        void onPebbleStopDelta(int delta);
    }

    private static WeakReference<Listener> listenerRef =
            new WeakReference<>(null);

    private static boolean watchOpen;
    private static int page;

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
            listener.onPebblePageChanged(
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

        Listener listener =
                listenerRef.get();

        if (listener != null) {
            listener.onPebblePageChanged(
                    page
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
