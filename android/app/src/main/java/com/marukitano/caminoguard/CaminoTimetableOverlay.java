package com.marukitano.caminoguard;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import org.maplibre.android.maps.MapView;

import java.util.List;

/**
 * Android-only presentation adapter for the platform-neutral Camino timetable.
 *
 * The timetable calculation remains in CaminoTimetablePlanBuilder /
 * CaminoTimetableEngine. This class only owns Android view lifecycle and the
 * left-edge reveal control.
 *
 * Pebble must consume CaminoTimetableState directly, never this class.
 */
final class CaminoTimetableOverlay {

    private static final long ANIMATION_MS =
            180L;

    private static final float PANEL_LEFT_DP =
            58.0f;

    private final Activity activity;
    private final MapView mapView;
    private final CaminoTimetablePlanBuilder planBuilder;
    private final CaminoTimetableEngine engine =
            new CaminoTimetableEngine();
    private final CaminoSettlementTimetableSource settlementSource;

    private CaminoTimetableView timetableView;
    private TimetableToggleView toggleView;

    private boolean open;

    /*
     * v112 intentionally renders the complete locked plan from its beginning.
     * Live route progress / GPS -> selected-path chainage is connected in the
     * next phase without changing the timetable model or Android drawing API.
     */
    private double currentChainageM =
            0.0;

    /*
     * OFF ROUTE contract:
     *
     * Route progress freezes at the last trustworthy selected-path position.
     * The timetable itself stays visible. Wall-clock based arrival times keep
     * advancing while the walker is away from the route.
     */
    private List<CaminoTimetableStopPlan> lastValidPlans;

    private double lastValidChainageM =
            Double.NaN;

    private double lastValidElapsedSeconds =
            Double.NaN;



    CaminoTimetableOverlay(
            Activity activity,
            MapView mapView,
            WalkingPerformanceModel performanceModel
    ) {
        this.activity =
                activity;

        this.mapView =
                mapView;

        this.planBuilder =
                new CaminoTimetablePlanBuilder(
                        performanceModel
                );

        this.settlementSource =
                new CaminoSettlementTimetableSource(
                        activity
                );
    }


    void ensureView() {
        if (timetableView != null
                && toggleView != null) {

            return;
        }

        ViewGroup parent =
                (ViewGroup)
                        mapView.getParent();

        timetableView =
                new CaminoTimetableView(
                        activity
                );

        timetableView.setVisibility(
                View.GONE
        );

        FrameLayout.LayoutParams timetableParams =
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        Gravity.START
                                | Gravity.TOP
                );

        parent.addView(
                timetableView,
                timetableParams
        );

        timetableView.setElevation(
                dp(
                        880.0f
                )
        );

        toggleView =
                new TimetableToggleView(
                        activity
                );

        toggleView.setVisibility(
                View.GONE
        );

        toggleView.setOnClickListener(
                ignored -> toggle()
        );

        FrameLayout.LayoutParams toggleParams =
                new FrameLayout.LayoutParams(
                        dpInt(
                                34.0f
                        ),
                        dpInt(
                                50.0f
                        ),
                        Gravity.START
                                | Gravity.CENTER_VERTICAL
                );

        toggleParams.leftMargin =
                dpInt(
                        1.0f
                );

        parent.addView(
                toggleView,
                toggleParams
        );

        toggleView.setElevation(
                dp(
                        920.0f
                )
        );

        toggleView.bringToFront();
    }


    void update(
            MeasurementPath path,
            boolean locked,
            boolean offRoute,
            double currentChainageM
    ) {
        ensureView();

        if (!locked
                || path == null) {

            this.currentChainageM =
                    0.0;

            clearFrozenState();
            clearAndHide();
            return;
        }

        /*
         * OFF ROUTE:
         *
         * Do NOT recalculate route progress from the off-route GPS position
         * and, most importantly, do NOT clear/hide the timetable.
         *
         * Keep the last valid route chainage and ETA model. buildState()
         * uses the current wall clock on every GPS refresh, therefore time
         * continues normally while distance/progress stay frozen.
         */
        if (offRoute) {
            if (!hasFrozenState()) {
                clearAndHide();
                return;
            }

            this.currentChainageM =
                    lastValidChainageM;

            CaminoTimetableState state =
                    buildState(
                            lastValidPlans,
                            lastValidElapsedSeconds,
                            lastValidChainageM
                    );

            if (state != null
                    && state.visibleStops.size() >= 2) {

                showState(
                        state
                );
            }

            return;
        }

        this.currentChainageM =
                Double.isFinite(
                        currentChainageM
                )
                        ? Math.max(
                        0.0,
                        currentChainageM
                )
                        : 0.0;

        MeasurementPath timetablePath =
                settlementSource.withSettlementStops(
                        path
                );

        List<CaminoTimetableStopPlan> plans =
                planBuilder.build(
                        timetablePath
                );

        if (plans.size() < 2) {
            clearAndHide();
            return;
        }

        double elapsedSecondsAtCurrent =
                planBuilder.elapsedSecondsAtChainage(
                        timetablePath,
                        this.currentChainageM
                );

        if (!Double.isFinite(
                elapsedSecondsAtCurrent
        )) {

            clearAndHide();
            return;
        }

        CaminoTimetableState state =
                buildState(
                        plans,
                        elapsedSecondsAtCurrent,
                        this.currentChainageM
                );

        if (state == null
                || state.visibleStops.size() < 2) {

            clearAndHide();
            return;
        }

        /*
         * Only an ON-ROUTE projection may advance this snapshot.
         * OFF ROUTE reuses it unchanged.
         */
        lastValidPlans =
                plans;

        lastValidChainageM =
                this.currentChainageM;

        lastValidElapsedSeconds =
                elapsedSecondsAtCurrent;

        showState(
                state
        );
    }


    private CaminoTimetableState buildState(
            List<CaminoTimetableStopPlan> plans,
            double elapsedSecondsAtCurrent,
            double chainageM
    ) {
        if (plans == null
                || plans.size() < 2
                || !Double.isFinite(
                elapsedSecondsAtCurrent
        )
                || !Double.isFinite(
                chainageM
        )) {

            return null;
        }

        /*
         * Rebase the route-start clock on every refresh.
         *
         * With frozen chainage this means:
         *
         * distance/progress = frozen
         * wall clock        = continues
         * ETA               = moves later with real time
         */
        int startMinutes =
                currentClockMinutes()
                        - (int)
                        Math.round(
                                elapsedSecondsAtCurrent
                                        / 60.0
                        );

        return engine.build(
                plans,
                startMinutes,
                chainageM
        );
    }


    private void showState(
            CaminoTimetableState state
    ) {
        timetableView.setState(
                state
        );

        toggleView.setVisibility(
                View.VISIBLE
        );

        toggleView.setOpen(
                open
        );

        /*
         * update() must never accidentally close an already-open timetable.
         */
        if (open) {
            timetableView.setVisibility(
                    View.VISIBLE
            );

            timetableView.setAlpha(
                    1.0f
            );

            timetableView.setTranslationX(
                    dp(
                            PANEL_LEFT_DP
                    )
            );
        }

        toggleView.bringToFront();
    }


    private boolean hasFrozenState() {
        return lastValidPlans != null
                && lastValidPlans.size() >= 2
                && Double.isFinite(
                lastValidChainageM
        )
                && Double.isFinite(
                lastValidElapsedSeconds
        );
    }


    private void clearFrozenState() {
        lastValidPlans =
                null;

        lastValidChainageM =
                Double.NaN;

        lastValidElapsedSeconds =
                Double.NaN;
    }


    private int currentClockMinutes() {
        java.util.Calendar now =
                java.util.Calendar.getInstance();

        return now.get(
                java.util.Calendar.HOUR_OF_DAY
        )
                * 60
                + now.get(
                java.util.Calendar.MINUTE
        );
    }


    private void toggle() {
        if (toggleView == null
                || toggleView.getVisibility()
                != View.VISIBLE
                || timetableView == null
                || !timetableView.hasState()) {

            return;
        }

        open =
                !open;

        toggleView.setOpen(
                open
        );

        if (open) {
            showAnimated();

        } else {
            hideAnimated();
        }
    }


    private void showAnimated() {
        timetableView.animate()
                .cancel();

        timetableView.setVisibility(
                View.VISIBLE
        );

        timetableView.setAlpha(
                1.0f
        );

        mapView.post(
                () -> {
                    if (!open
                            || timetableView == null) {

                        return;
                    }

                    float width =
                            timetableView.panelWidthPx();

                    timetableView.setTranslationX(
                            -width
                    );

                    timetableView.animate()
                            .translationX(
                                    dp(
                                            PANEL_LEFT_DP
                                    )
                            )
                            .setDuration(
                                    ANIMATION_MS
                            )
                            .start();
                }
        );
    }


    private void hideAnimated() {
        timetableView.animate()
                .cancel();

        float width =
                timetableView.panelWidthPx();

        timetableView.animate()
                .translationX(
                        -width
                )
                .setDuration(
                        ANIMATION_MS
                )
                .withEndAction(
                        () -> {
                            if (!open
                                    && timetableView != null) {

                                timetableView.setVisibility(
                                        View.GONE
                                );

                                timetableView.setTranslationX(
                                        0.0f
                                );
                            }
                        }
                )
                .start();
    }


    private void clearAndHide() {
        open =
                false;

        currentChainageM =
                0.0;

        if (timetableView != null) {
            timetableView.animate()
                    .cancel();

            timetableView.clearState();

            timetableView.setTranslationX(
                    0.0f
            );

            timetableView.setVisibility(
                    View.GONE
            );
        }

        if (toggleView != null) {
            toggleView.setOpen(
                    false
            );

            toggleView.setVisibility(
                    View.GONE
            );
        }
    }


    private int dpInt(
            float value
    ) {
        return Math.round(
                dp(
                        value
                )
        );
    }


    private float dp(
            float value
    ) {
        return value
                * activity.getResources()
                .getDisplayMetrics()
                .density;
    }


    /**
     * Exact left-edge visual counterpart of the existing right-edge
     * CaminoHeightProfileView control:
     *
     * 34x50 dp, 1 dp edge margin, graphite rounded background,
     * warm 2.2 dp rounded chevron.
     */
    private static final class TimetableToggleView
            extends View {

        private final Paint backgroundPaint =
                new Paint(
                        Paint.ANTI_ALIAS_FLAG
                );

        private final Paint chevronPaint =
                new Paint(
                        Paint.ANTI_ALIAS_FLAG
                );

        private boolean open;


        TimetableToggleView(
                android.content.Context context
        ) {
            super(
                    context
            );

            setClickable(
                    true
            );

            backgroundPaint.setColor(
                    Color.argb(
                            225,
                            92,
                            92,
                            92
                    )
            );

            backgroundPaint.setStyle(
                    Paint.Style.FILL
            );

            chevronPaint.setColor(
                    Color.rgb(
                            255,
                            240,
                            200
                    )
            );

            chevronPaint.setStyle(
                    Paint.Style.STROKE
            );

            chevronPaint.setStrokeWidth(
                    dp(
                            2.2f
                    )
            );

            chevronPaint.setStrokeCap(
                    Paint.Cap.ROUND
            );

            chevronPaint.setStrokeJoin(
                    Paint.Join.ROUND
            );
        }


        void setOpen(
                boolean open
        ) {
            if (this.open
                    == open) {

                return;
            }

            this.open =
                    open;

            invalidate();
        }


        @Override
        protected void onDraw(
                Canvas canvas
        ) {
            super.onDraw(
                    canvas
            );

            float radius =
                    dp(
                            10.0f
                    );

            canvas.drawRoundRect(
                    0.0f,
                    0.0f,
                    getWidth(),
                    getHeight(),
                    radius,
                    radius,
                    backgroundPaint
            );

            float cx =
                    getWidth()
                            / 2.0f;

            float cy =
                    getHeight()
                            / 2.0f;

            float halfHeight =
                    dp(
                            9.0f
                    );

            float depth =
                    dp(
                            5.0f
                    );

            Path path =
                    new Path();

            if (!open) {
                /*
                 * Closed on the LEFT edge -> point into the screen.
                 */
                path.moveTo(
                        cx - depth,
                        cy - halfHeight
                );

                path.lineTo(
                        cx + depth,
                        cy
                );

                path.lineTo(
                        cx - depth,
                        cy + halfHeight
                );

            } else {
                /*
                 * Open -> point back toward the LEFT edge to close.
                 */
                path.moveTo(
                        cx + depth,
                        cy - halfHeight
                );

                path.lineTo(
                        cx - depth,
                        cy
                );

                path.lineTo(
                        cx + depth,
                        cy + halfHeight
                );
            }

            canvas.drawPath(
                    path,
                    chevronPaint
            );
        }


        private float dp(
                float value
        ) {
            return value
                    * getResources()
                    .getDisplayMetrics()
                    .density;
        }
    }
}
