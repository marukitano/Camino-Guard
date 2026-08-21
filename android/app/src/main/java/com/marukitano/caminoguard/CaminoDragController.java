package com.marukitano.caminoguard;

import android.app.Activity;
import android.graphics.PointF;
import android.view.MotionEvent;

import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.maps.MapLibreMap;

/**
 * Owns Camino drag gesture state and drag/snapping mechanics.
 *
 * Selection semantics remain in CaminoController. This class only moves the
 * already-selected interactive targets and delegates refresh side effects.
 */
final class CaminoDragController {

    interface Host {
        boolean isLivePositionMode();
        LatLng dummyPosition();
        void setDummyPosition(LatLng position);

        CaminoRoute selectedRoute();
        ProjectionHit selectedHit();
        void setSelectedHit(ProjectionHit hit);

        CaminoRoute secondSelectedRoute();
        ProjectionHit secondTapHit();
        void setSecondTapHit(ProjectionHit hit);

        void refresh();
        void refreshDragPreview(boolean draggingDummy);
        void noteTravelSample(LatLng position);
        void followIfActive();
    }

    private static final int DRAG_NONE = 0;
    private static final int DRAG_DUMMY = 1;
    private static final int DRAG_POINT_1 = 2;
    private static final int DRAG_POINT_2 = 3;

    private final Activity activity;
    private final CaminoProjectionEngine projectionEngine;
    private final Host host;

    private MapLibreMap map;
    private int dragTarget = DRAG_NONE;

    CaminoDragController(
            Activity activity,
            CaminoProjectionEngine projectionEngine,
            Host host
    ) {
        this.activity =
                activity;

        this.projectionEngine =
                projectionEngine;

        this.host =
                host;
    }

    void attachMap(
            MapLibreMap map
    ) {
        this.map =
                map;
    }

    boolean isDragging() {
        return dragTarget
                != DRAG_NONE;
    }

    boolean handleTouch(
            MotionEvent event
    ) {
        if (map == null) {
            return false;
        }

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragTarget =
                        findDragTarget(
                                event.getX(),
                                event.getY()
                        );

                return dragTarget
                        != DRAG_NONE;

            case MotionEvent.ACTION_MOVE:
                if (dragTarget
                        == DRAG_NONE) {
                    return false;
                }

                /*
                 * Dragging stays cheap: only interactive marker/connector
                 * previews move until release. Full route refresh happens once.
                 */
                moveDragTarget(
                        event.getX(),
                        event.getY(),
                        true
                );

                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (dragTarget
                        == DRAG_NONE) {
                    return false;
                }

                moveDragTarget(
                        event.getX(),
                        event.getY(),
                        false
                );

                dragTarget =
                        DRAG_NONE;

                return true;

            default:
                return dragTarget
                        != DRAG_NONE;
        }
    }

    private int findDragTarget(
            float x,
            float y
    ) {
        final float grabRadius =
                dp(34);

        final float maxDistanceSq =
                grabRadius
                        * grabRadius;

        int bestTarget =
                DRAG_NONE;

        float bestDistanceSq =
                Float.MAX_VALUE;

        ProjectionHit secondTapHit =
                host.secondTapHit();

        if (secondTapHit != null) {
            float distanceSq =
                    screenDistanceSq(
                            x,
                            y,
                            secondTapHit.point
                    );

            if (distanceSq
                    <= maxDistanceSq
                    && distanceSq
                    < bestDistanceSq) {
                bestTarget =
                        DRAG_POINT_2;

                bestDistanceSq =
                        distanceSq;
            }
        }

        ProjectionHit selectedHit =
                host.selectedHit();

        if (selectedHit != null) {
            float distanceSq =
                    screenDistanceSq(
                            x,
                            y,
                            selectedHit.point
                    );

            if (distanceSq
                    <= maxDistanceSq
                    && distanceSq
                    < bestDistanceSq) {
                bestTarget =
                        DRAG_POINT_1;

                bestDistanceSq =
                        distanceSq;
            }
        }

        if (!host.isLivePositionMode()) {
            float dummyDistanceSq =
                    screenDistanceSq(
                            x,
                            y,
                            host.dummyPosition()
                    );

            if (dummyDistanceSq
                    <= maxDistanceSq
                    && dummyDistanceSq
                    < bestDistanceSq) {
                bestTarget =
                        DRAG_DUMMY;
            }
        }

        return bestTarget;
    }

    private float screenDistanceSq(
            float x,
            float y,
            LatLng point
    ) {
        PointF screen =
                map.getProjection()
                        .toScreenLocation(
                                point
                        );

        float dx =
                x - screen.x;

        float dy =
                y - screen.y;

        return dx * dx
                + dy * dy;
    }

    private void moveDragTarget(
            float x,
            float y,
            boolean previewOnly
    ) {
        LatLng fingerPosition =
                map.getProjection()
                        .fromScreenLocation(
                                new PointF(
                                        x,
                                        y
                                )
                        );

        if (dragTarget
                == DRAG_DUMMY) {

            host.setDummyPosition(
                    fingerPosition
            );

            if (previewOnly) {
                host.refreshDragPreview(
                        true
                );

            } else {
                host.refresh();

                host.noteTravelSample(
                        fingerPosition
                );

                host.followIfActive();
            }

            return;
        }

        CaminoRoute dragRoute =
                dragTarget == DRAG_POINT_2
                        ? host.secondSelectedRoute()
                        : host.selectedRoute();

        if (dragRoute == null) {
            return;
        }

        ProjectionHit snapped =
                projectionEngine.projectToRoute(
                        dragRoute,
                        fingerPosition
                );

        if (snapped == null) {
            return;
        }

        if (dragTarget
                == DRAG_POINT_1) {

            host.setSelectedHit(
                    snapped
            );

        } else if (dragTarget
                == DRAG_POINT_2) {

            host.setSecondTapHit(
                    snapped
            );
        }

        if (previewOnly) {
            host.refreshDragPreview(
                    false
            );

        } else {
            host.refresh();
        }
    }

    private int dp(
            int value
    ) {
        return Math.round(
                value
                        * activity
                        .getResources()
                        .getDisplayMetrics()
                        .density
        );
    }
}
