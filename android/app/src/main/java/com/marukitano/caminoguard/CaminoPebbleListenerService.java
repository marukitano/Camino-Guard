package com.marukitano.caminoguard;

import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import io.rebble.pebblekit2.client.java.BaseJavaPebbleListenerService;
import io.rebble.pebblekit2.common.model.PebbleDictionaryItem;
import io.rebble.pebblekit2.common.model.ReceiveResult;

/**
 * Pebble -> Android control channel for the Camino Guard watchapp.
 *
 * It intentionally never starts CaminoTrackingService. Page gestures are
 * delivered only to a publisher that is already alive, so opening the watchapp
 * cannot resurrect GPS after the user closed the Android task.
 */
public final class CaminoPebbleListenerService
        extends BaseJavaPebbleListenerService {

    private static final UUID APP_UUID =
            UUID.fromString(
                    "5d2f1422-7b95-4951-b7ce-d122783a58d4"
            );

    private static final int KEY_WATCH_PAGE =
            21;

    private static final int KEY_WATCH_STOP_DELTA =
            22;

    @Override
    public void onMessageReceived(
            UUID watchappUUID,
            Map<Integer, ? extends PebbleDictionaryItem> data,
            String watch,
            Consumer<ReceiveResult> responder
    ) {
        if (APP_UUID.equals(
                watchappUUID
        )) {
            Integer page =
                    int32Value(
                            data.get(
                                    KEY_WATCH_PAGE
                            )
                    );

            if (page != null) {
                CaminoPebbleSession.pageChanged(
                        page
                );
            }

            Integer stopDelta =
                    int32Value(
                            data.get(
                                    KEY_WATCH_STOP_DELTA
                            )
                    );

            if (stopDelta != null) {
                CaminoPebbleSession.stopDelta(
                        stopDelta
                );
            }
        }

        responder.accept(
                ReceiveResult.Ack.INSTANCE
        );
    }

    @Override
    protected void onAppOpened(
            UUID watchappUUID,
            String watch
    ) {
        if (APP_UUID.equals(
                watchappUUID
        )) {
            CaminoPebbleSession.watchOpened();
        }
    }

    @Override
    protected void onAppClosed(
            UUID watchappUUID,
            String watch
    ) {
        if (APP_UUID.equals(
                watchappUUID
        )) {
            CaminoPebbleSession.watchClosed();
        }
    }

    private static Integer int32Value(
            PebbleDictionaryItem item
    ) {
        if (!(item
                instanceof PebbleDictionaryItem.Int32)) {

            return null;
        }

        return ((PebbleDictionaryItem.Int32) item)
                .getValue();
    }
}
