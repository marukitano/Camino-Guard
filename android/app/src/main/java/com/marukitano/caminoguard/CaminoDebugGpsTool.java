package com.marukitano.caminoguard;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.PointF;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.MapView;

/**
 * Debug-only GPS simulator UI.
 *
 * It owns UI/timing only. CaminoController remains the owner of the canonical
 * selected MeasurementPath, snapping and position state.
 */
final class CaminoDebugGpsTool {

    interface Host {
        boolean debugPositionActive();
        void activateDebugPositionAtStart();
        void deactivateDebugPosition();
        void placeDebugPosition(LatLng mapPosition);
        double currentDebugChainageM();
        double debugRouteDistanceM();
        void setDebugChainageM(double chainageM);
    }

    /*
     * The existing CaminoInfoController stack is:
     *   48 x 144 dp
     *   bottom margin 18 dp
     *
     * Put this button six dp directly above that stack.
     */
    private static final int CONTROL_LEFT_DP =
            2;

    private static final int CONTROL_BOTTOM_DP =
            18
                    + 144
                    + 6;

    private static final int BUTTON_SIZE_DP =
            46;

    private static final int PANEL_LEFT_DP =
            CONTROL_LEFT_DP
                    + BUTTON_SIZE_DP
                    + 6;

    private static final int PANEL_WIDTH_DP =
            238;

    private static final int PANEL_HEIGHT_DP =
            46;

    private static final long AUTO_TICK_MS =
            50L;

    /*
     * Debug simulation speeds. The high end is intentional:
     * 500 km/h advances one kilometre in about 7.2 seconds, making the
     * timetable's one-kilometre village-retention rule practical to test.
     */
    private static final double[] SPEEDS_KMH = {
            1.0,
            2.0,
            3.0,
            4.0,
            5.0,
            7.5,
            10.0,
            15.0,
            20.0,
            30.0,
            50.0,
            75.0,
            100.0,
            150.0,
            200.0,
            300.0,
            500.0
    };

    private static final int DEFAULT_SPEED_INDEX =
            4;

    private final Activity activity;
    private final MapView mapView;
    private final Host host;
    private final boolean enabled;

    private MapLibreMap map;

    private TextView gpsButton;
    private LinearLayout speedPanel;
    private TextView playButton;
    private SeekBar speedSlider;
    private TextView speedLabel;

    private boolean routeAvailable;
    private boolean active;
    private boolean autoRunning;

    private boolean buttonDragMoved;
    private float buttonDownRawX;
    private float buttonDownRawY;

    private int speedIndex =
            DEFAULT_SPEED_INDEX;

    private long lastAutoTickMs;

    private final Runnable autoTick =
            new Runnable() {
                @Override
                public void run() {
                    tickAutoWalk();
                }
            };


    CaminoDebugGpsTool(
            Activity activity,
            MapView mapView,
            Host host
    ) {
        this.activity =
                activity;

        this.mapView =
                mapView;

        this.host =
                host;

        this.enabled =
                (activity.getApplicationInfo().flags
                        & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
                        && CaminoConfig.get()
                        .booleanValue(
                                "debug.draggableGpsEnabled"
                        );
    }


    void attachMap(
            MapLibreMap map
    ) {
        this.map =
                map;
    }


    void ensureView() {
        if (gpsButton != null) {
            return;
        }

        ViewGroup parent =
                (ViewGroup)
                        mapView.getParent();

        gpsButton =
                new TextView(
                        activity
                );

        gpsButton.setText(
                "GPS"
        );

        gpsButton.setTextColor(
                Color.rgb(
                        245,
                        201,
                        142
                )
        );

        gpsButton.setTextSize(
                11.0f
        );

        gpsButton.setGravity(
                Gravity.CENTER
        );

        gpsButton.setTypeface(
                android.graphics.Typeface.DEFAULT,
                android.graphics.Typeface.BOLD
        );

        gpsButton.setVisibility(
                View.GONE
        );

        gpsButton.setOnTouchListener(
                this::handleGpsButtonTouch
        );

        FrameLayout.LayoutParams buttonParams =
                new FrameLayout.LayoutParams(
                        dp(
                                BUTTON_SIZE_DP
                        ),
                        dp(
                                BUTTON_SIZE_DP
                        ),
                        Gravity.START
                                | Gravity.BOTTOM
                );

        buttonParams.leftMargin =
                dp(
                        CONTROL_LEFT_DP
                );

        buttonParams.bottomMargin =
                dp(
                        CONTROL_BOTTOM_DP
                );

        parent.addView(
                gpsButton,
                buttonParams
        );

        gpsButton.setElevation(
                dp(
                        1020
                )
        );

        speedPanel =
                new LinearLayout(
                        activity
                );

        speedPanel.setOrientation(
                LinearLayout.HORIZONTAL
        );

        speedPanel.setGravity(
                Gravity.CENTER_VERTICAL
        );

        speedPanel.setPadding(
                dp(
                        5
                ),
                0,
                dp(
                        6
                ),
                0
        );

        speedPanel.setVisibility(
                View.GONE
        );

        playButton =
                new TextView(
                        activity
                );

        playButton.setGravity(
                Gravity.CENTER
        );

        playButton.setTextSize(
                19.0f
        );

        playButton.setTextColor(
                Color.WHITE
        );

        playButton.setText(
                "▶"
        );

        playButton.setOnClickListener(
                ignored -> toggleAutoWalk()
        );

        LinearLayout.LayoutParams playParams =
                new LinearLayout.LayoutParams(
                        dp(
                                38
                        ),
                        LinearLayout.LayoutParams.MATCH_PARENT
                );

        speedPanel.addView(
                playButton,
                playParams
        );

        speedSlider =
                new SeekBar(
                        activity
                );

        speedSlider.setMax(
                SPEEDS_KMH.length
                        - 1
        );

        speedSlider.setProgress(
                speedIndex
        );

        speedSlider.setOnSeekBarChangeListener(
                new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(
                            SeekBar seekBar,
                            int progress,
                            boolean fromUser
                    ) {
                        speedIndex =
                                Math.max(
                                        0,
                                        Math.min(
                                                SPEEDS_KMH.length - 1,
                                                progress
                                        )
                                );

                        updateSpeedLabel();
                    }

                    @Override
                    public void onStartTrackingTouch(
                            SeekBar seekBar
                    ) {
                        // Nothing.
                    }

                    @Override
                    public void onStopTrackingTouch(
                            SeekBar seekBar
                    ) {
                        // Nothing.
                    }
                }
        );

        LinearLayout.LayoutParams sliderParams =
                new LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        1.0f
                );

        speedPanel.addView(
                speedSlider,
                sliderParams
        );

        speedLabel =
                new TextView(
                        activity
                );

        speedLabel.setGravity(
                Gravity.CENTER
        );

        speedLabel.setTextColor(
                Color.WHITE
        );

        speedLabel.setTextSize(
                11.0f
        );

        speedLabel.setTypeface(
                android.graphics.Typeface.MONOSPACE,
                android.graphics.Typeface.BOLD
        );

        LinearLayout.LayoutParams speedParams =
                new LinearLayout.LayoutParams(
                        dp(
                                70
                        ),
                        LinearLayout.LayoutParams.MATCH_PARENT
                );

        speedPanel.addView(
                speedLabel,
                speedParams
        );

        updateSpeedLabel();

        FrameLayout.LayoutParams panelParams =
                new FrameLayout.LayoutParams(
                        dp(
                                PANEL_WIDTH_DP
                        ),
                        dp(
                                PANEL_HEIGHT_DP
                        ),
                        Gravity.START
                                | Gravity.BOTTOM
                );

        panelParams.leftMargin =
                dp(
                        PANEL_LEFT_DP
                );

        panelParams.bottomMargin =
                dp(
                        CONTROL_BOTTOM_DP
                );

        parent.addView(
                speedPanel,
                panelParams
        );

        speedPanel.setElevation(
                dp(
                        1015
                )
        );

        updateVisualState();
    }


    void update(
            boolean routeAvailable,
            boolean active
    ) {
        ensureView();

        this.routeAvailable =
                enabled
                        && routeAvailable;

        this.active =
                active;

        if (!routeAvailable
                || !active) {

            stopAutoWalk();
        }

        updateVisualState();
    }


    void pauseAuto() {
        stopAutoWalk();
    }


    private boolean handleGpsButtonTouch(
            View ignored,
            MotionEvent event
    ) {
        if (!routeAvailable) {
            return false;
        }

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                buttonDragMoved =
                        false;

                buttonDownRawX =
                        event.getRawX();

                buttonDownRawY =
                        event.getRawY();

                return true;

            case MotionEvent.ACTION_MOVE:
                float dx =
                        event.getRawX()
                                - buttonDownRawX;

                float dy =
                        event.getRawY()
                                - buttonDownRawY;

                float slop =
                        ViewConfiguration.get(
                                activity
                        )
                                .getScaledTouchSlop();

                if (!buttonDragMoved
                        && dx * dx + dy * dy
                        > slop * slop) {

                    buttonDragMoved =
                            true;
                }

                if (buttonDragMoved) {
                    placeFromRawScreen(
                            event.getRawX(),
                            event.getRawY()
                    );
                }

                return true;

            case MotionEvent.ACTION_UP:
                if (buttonDragMoved) {
                    placeFromRawScreen(
                            event.getRawX(),
                            event.getRawY()
                    );

                } else if (active) {
                    host.deactivateDebugPosition();

                } else {
                    host.activateDebugPositionAtStart();
                }

                active =
                        host.debugPositionActive();

                updateVisualState();

                return true;

            case MotionEvent.ACTION_CANCEL:
                buttonDragMoved =
                        false;

                return true;

            default:
                return true;
        }
    }


    private void placeFromRawScreen(
            float rawX,
            float rawY
    ) {
        if (map == null
                || !routeAvailable) {

            return;
        }

        int[] mapLocation =
                new int[2];

        mapView.getLocationOnScreen(
                mapLocation
        );

        PointF screen =
                new PointF(
                        rawX
                                - mapLocation[0],
                        rawY
                                - mapLocation[1]
                );

        LatLng mapPosition =
                map.getProjection()
                        .fromScreenLocation(
                                screen
                        );

        host.placeDebugPosition(
                mapPosition
        );

        active =
                host.debugPositionActive();

        updateVisualState();
    }


    private void toggleAutoWalk() {
        if (!routeAvailable
                || !active) {

            return;
        }

        if (autoRunning) {
            stopAutoWalk();

        } else {
            startAutoWalk();
        }
    }


    private void startAutoWalk() {
        if (autoRunning
                || !routeAvailable
                || !active) {

            return;
        }

        autoRunning =
                true;

        lastAutoTickMs =
                SystemClock.elapsedRealtime();

        updateVisualState();

        mapView.removeCallbacks(
                autoTick
        );

        mapView.postDelayed(
                autoTick,
                AUTO_TICK_MS
        );
    }


    private void stopAutoWalk() {
        autoRunning =
                false;

        mapView.removeCallbacks(
                autoTick
        );

        updatePlayButton();
    }


    private void tickAutoWalk() {
        if (!autoRunning
                || !routeAvailable
                || !active) {

            stopAutoWalk();
            return;
        }

        long now =
                SystemClock.elapsedRealtime();

        double elapsedSeconds =
                Math.max(
                        0.0,
                        Math.min(
                                0.5,
                                (
                                        now
                                                - lastAutoTickMs
                                )
                                        / 1000.0
                        )
                );

        lastAutoTickMs =
                now;

        double currentM =
                host.currentDebugChainageM();

        double routeEndM =
                host.debugRouteDistanceM();

        if (!Double.isFinite(
                currentM
        )
                || !Double.isFinite(
                routeEndM
        )
                || routeEndM <= 0.0) {

            stopAutoWalk();
            return;
        }

        double speedMps =
                SPEEDS_KMH[
                        speedIndex
                ]
                        / 3.6;

        double nextM =
                Math.min(
                        routeEndM,
                        currentM
                                + speedMps
                                * elapsedSeconds
                );

        host.setDebugChainageM(
                nextM
        );

        active =
                host.debugPositionActive();

        if (!active
                || nextM >= routeEndM
                - 0.01) {

            stopAutoWalk();
            return;
        }

        mapView.postDelayed(
                autoTick,
                AUTO_TICK_MS
        );
    }


    private void updateVisualState() {
        if (gpsButton == null
                || speedPanel == null) {

            return;
        }

        gpsButton.setVisibility(
                routeAvailable
                        ? View.VISIBLE
                        : View.GONE
        );

        speedPanel.setVisibility(
                routeAvailable
                        && active
                        ? View.VISIBLE
                        : View.GONE
        );

        gpsButton.setBackground(
                buttonBackground(
                        active
                )
        );

        speedPanel.setBackground(
                panelBackground()
        );

        updatePlayButton();
        updateSpeedLabel();

        gpsButton.bringToFront();

        if (speedPanel.getVisibility()
                == View.VISIBLE) {

            speedPanel.bringToFront();
            gpsButton.bringToFront();
        }
    }


    private void updatePlayButton() {
        if (playButton == null) {
            return;
        }

        playButton.setText(
                autoRunning
                        ? "Ⅱ"
                        : "▶"
        );
    }


    private void updateSpeedLabel() {
        if (speedLabel == null) {
            return;
        }

        double speed =
                SPEEDS_KMH[
                        speedIndex
                ];

        if (speed < 10.0
                && speed
                != Math.rint(
                        speed
                )) {

            speedLabel.setText(
                    String.format(
                            java.util.Locale.GERMANY,
                            "%.1f km/h",
                            speed
                    )
            );

        } else {
            speedLabel.setText(
                    String.format(
                            java.util.Locale.GERMANY,
                            "%.0f km/h",
                            speed
                    )
            );
        }
    }


    private GradientDrawable buttonBackground(
            boolean active
    ) {
        GradientDrawable background =
                panelBackground();

        background.setStroke(
                dp(
                        active
                                ? 2
                                : 1
                ),
                active
                        ? Color.rgb(
                        245,
                        201,
                        142
                )
                        : Color.argb(
                        95,
                        255,
                        255,
                        255
                )
        );

        return background;
    }


    private GradientDrawable panelBackground() {
        GradientDrawable background =
                new GradientDrawable();

        background.setColor(
                Color.argb(
                        225,
                        92,
                        92,
                        92
                )
        );

        background.setCornerRadius(
                dp(
                        12
                )
        );

        background.setStroke(
                dp(
                        1
                ),
                Color.argb(
                        95,
                        255,
                        255,
                        255
                )
        );

        return background;
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
