package com.marukitano.caminoguard;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.maps.MapView;

import java.util.List;
import java.util.Locale;

/**
 * Vertical statistics for an explicit selected Camino segment.
 *
 * Planning / unlocked:
 *   - complete selected route
 *   - selectable start time
 *   - no guessed pause time (Pause = 0)
 *
 * Navigation / locked:
 *   - current position -> selected goal
 *   - remaining distance / elevation / walking time
 *   - actually accumulated stationary time only
 *   - arrival = current wall clock + remaining walking time
 */
final class CaminoSelectionStatsOverlay {

    private static final int RIGHT_MARGIN_PX = 3;
    private static final int CENTER_GAP_DP = 34;

    /*
     * Live remaining-distance/ETA is valid only while the physical position is
     * genuinely near the selected route.
     */
    private static final double MAX_LIVE_ROUTE_OFFSET_M =
            200.0;

    private static final long CLOCK_TICK_MS = 30_000L;

    private final Activity activity;
    private final MapView mapView;
    private final WalkingPerformanceModel performanceModel;

    private VerticalStatsTextView upperView;
    private VerticalStatsTextView lowerView;
    private StartTimeScrollView startTimeView;

    private boolean locked;

    private MeasurementPath lastPath;
    private LatLng lastPosition;

    /*
     * Also gates real pause accumulation. A stationary phone in Switzerland
     * while inspecting a Spanish route is not a pause on that hike.
     */
    private boolean currentPositionOnSelectedRoute;

    private int plannedStartMinutesOfDay =
            -1;

    private long accumulatedPauseMs;
    private long activePauseStartedWallMs =
            -1L;

    private final Runnable clockTick =
            new Runnable() {
                @Override
                public void run() {
                    if (!locked
                            || upperView == null
                            || upperView.getVisibility()
                            != View.VISIBLE) {

                        return;
                    }

                    renderLastState();

                    mapView.postDelayed(
                            this,
                            CLOCK_TICK_MS
                    );
                }
            };

    CaminoSelectionStatsOverlay(
            Activity activity,
            MapView mapView,
            WalkingPerformanceModel performanceModel
    ) {
        this.activity =
                activity;

        this.mapView =
                mapView;

        this.performanceModel =
                performanceModel;
    }

    void ensureView() {
        if (upperView != null
                && lowerView != null
                && startTimeView != null) {

            return;
        }

        ViewGroup parent =
                (ViewGroup)
                        mapView.getParent();

        upperView =
                createStatsView();

        lowerView =
                createStatsView();

        startTimeView =
                new StartTimeScrollView(
                        activity
                );

        startTimeView.setVisibility(
                View.GONE
        );

        FrameLayout.LayoutParams startTimeParams =
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.END
                                | Gravity.BOTTOM
                );

        startTimeParams.rightMargin =
                RIGHT_MARGIN_PX;

        FrameLayout.LayoutParams upperParams =
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.END
                                | Gravity.BOTTOM
                );

        upperParams.rightMargin =
                RIGHT_MARGIN_PX;

        FrameLayout.LayoutParams lowerParams =
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.END
                                | Gravity.TOP
                );

        lowerParams.rightMargin =
                RIGHT_MARGIN_PX;

        parent.addView(
                startTimeView,
                startTimeParams
        );

        parent.addView(
                upperView,
                upperParams
        );

        parent.addView(
                lowerView,
                lowerParams
        );

        startTimeView.setElevation(
                dp(
                        900
                )
        );

        upperView.setElevation(
                dp(
                        900
                )
        );

        lowerView.setElevation(
                dp(
                        900
                )
        );

        startTimeView.bringToFront();
        upperView.bringToFront();
        lowerView.bringToFront();

        mapView.post(
                this::updateVerticalPlacement
        );
    }

    private VerticalStatsTextView createStatsView() {
        VerticalStatsTextView result =
                new VerticalStatsTextView(
                        activity
                );

        result.setTextColor(
                statsTextColor()
        );

        result.setTextSize(
                13.5f
        );

        result.setTypeface(
                Typeface.create(
                        Typeface.MONOSPACE,
                        Typeface.NORMAL
                )
        );

        result.setGravity(
                Gravity.CENTER
        );

        result.setPadding(
                0,
                0,
                0,
                0
        );

        result.setVisibility(
                View.GONE
        );

        return result;
    }

    private void updateVerticalPlacement() {
        if (upperView == null
                || lowerView == null
                || startTimeView == null
                || mapView.getHeight() <= 0) {

            return;
        }

        ViewGroup.LayoutParams rawUpper =
                upperView.getLayoutParams();

        ViewGroup.LayoutParams rawLower =
                lowerView.getLayoutParams();

        ViewGroup.LayoutParams rawStartTime =
                startTimeView.getLayoutParams();

        if (!(rawUpper
                instanceof FrameLayout.LayoutParams)
                || !(rawLower
                instanceof FrameLayout.LayoutParams)
                || !(rawStartTime
                instanceof FrameLayout.LayoutParams)) {

            return;
        }

        FrameLayout.LayoutParams upperParams =
                (FrameLayout.LayoutParams)
                        rawUpper;

        FrameLayout.LayoutParams lowerParams =
                (FrameLayout.LayoutParams)
                        rawLower;

        FrameLayout.LayoutParams startTimeParams =
                (FrameLayout.LayoutParams)
                        rawStartTime;

        int centre =
                mapView.getHeight()
                        / 2;

        int gap =
                dp(
                        CENTER_GAP_DP
                );

        int baseBottomMargin =
                mapView.getHeight()
                        - (
                        centre
                                - gap
                );

        startTimeParams.rightMargin =
                RIGHT_MARGIN_PX;

        startTimeParams.bottomMargin =
                baseBottomMargin;

        upperParams.rightMargin =
                RIGHT_MARGIN_PX;

        upperParams.bottomMargin =
                baseBottomMargin
                        + (
                        startTimeView.getVisibility()
                                == View.VISIBLE
                                ? startTimeView.getMeasuredHeight()
                                        + dp(
                                        5
                                )
                                : 0
                );

        lowerParams.rightMargin =
                RIGHT_MARGIN_PX;

        lowerParams.topMargin =
                centre
                        + gap;

        startTimeView.setLayoutParams(
                startTimeParams
        );

        upperView.setLayoutParams(
                upperParams
        );

        lowerView.setLayoutParams(
                lowerParams
        );
    }

    void setLocked(
            boolean locked
    ) {
        boolean changed =
                this.locked
                        != locked;

        this.locked =
                locked;

        if (changed) {
            accumulatedPauseMs =
                    0L;

            activePauseStartedWallMs =
                    -1L;

            currentPositionOnSelectedRoute =
                    false;
        }

        int color =
                statsTextColor();

        if (upperView != null) {
            upperView.setTextColor(
                    color
            );

            upperView.setClickable(
                    !locked
            );

            upperView.invalidate();
        }

        if (lowerView != null) {
            lowerView.setTextColor(
                    color
            );

            lowerView.invalidate();
        }

        if (startTimeView != null) {
            startTimeView.setEnabled(
                    !locked
            );

            startTimeView.setMainColor(
                    color
            );
        }

        mapView.removeCallbacks(
                clockTick
        );

        if (locked
                && upperView != null
                && upperView.getVisibility()
                == View.VISIBLE) {

            mapView.postDelayed(
                    clockTick,
                    CLOCK_TICK_MS
            );
        }

        renderLastState();
    }

    void noteMotionState(
            boolean stationary
    ) {
        if (!locked) {
            return;
        }

        if (!currentPositionOnSelectedRoute) {
            /*
             * We are not currently on the selected route. Do not start or carry
             * a hiking pause across an off-route / remote-planning interval.
             */
            activePauseStartedWallMs =
                    -1L;

            return;
        }

        long now =
                System.currentTimeMillis();

        if (stationary) {
            if (activePauseStartedWallMs < 0L) {
                activePauseStartedWallMs =
                        now;
            }

        } else if (activePauseStartedWallMs >= 0L) {
            accumulatedPauseMs +=
                    Math.max(
                            0L,
                            now
                                    - activePauseStartedWallMs
                    );

            activePauseStartedWallMs =
                    -1L;
        }

        renderLastState();
    }

    private long currentPauseMs() {
        long result =
                accumulatedPauseMs;

        if (locked
                && activePauseStartedWallMs >= 0L) {

            result +=
                    Math.max(
                            0L,
                            System.currentTimeMillis()
                                    - activePauseStartedWallMs
                    );
        }

        return result;
    }

    private int statsTextColor() {
        if (locked) {
            return Color.rgb(
                    245,
                    245,
                    245
            );
        }

        return Color.rgb(
                35,
                39,
                43
        );
    }

    void update(
            MeasurementPath path,
            LatLng currentPosition
    ) {
        ensureView();

        lastPath =
                path;

        lastPosition =
                currentPosition;

        if (path == null
                || path.profilePoints == null
                || path.profilePoints.isEmpty()
                || !Double.isFinite(
                path.distanceM
        )
                || path.distanceM <= 0.0) {

            plannedStartMinutesOfDay =
                    -1;

            hide();
            return;
        }

        if (!locked
                && plannedStartMinutesOfDay < 0) {

            plannedStartMinutesOfDay =
                    nextHalfHour(
                            currentMinutesOfDay()
                    );
        }

        renderLastState();
    }

    private void renderLastState() {
        if (upperView == null
                || lowerView == null
                || lastPath == null
                || lastPath.profilePoints == null
                || lastPath.profilePoints.isEmpty()) {

            return;
        }

        if (locked) {
            renderNavigation(
                    lastPath,
                    lastPosition
            );

        } else {
            renderPlanning(
                    lastPath
            );
        }
    }

    private void renderPlanning(
            MeasurementPath path
    ) {
        ElevationStats elevationStats =
                calculateElevationStats(
                        path.profilePoints
                );

        WalkingTimeEstimate estimate =
                performanceModel == null
                        ? null
                        : performanceModel.estimate(
                                path
                        );

        if (elevationStats == null
                || estimate == null) {

            hide();
            return;
        }

        if (plannedStartMinutesOfDay < 0) {
            plannedStartMinutesOfDay =
                    nextHalfHour(
                            currentMinutesOfDay()
                    );
        }

        int walkingMinutes =
                roundedDurationMinutes(
                        estimate.durationSeconds
                );

        int arrivalMinutes =
                normalizeMinutesOfDay(
                        plannedStartMinutesOfDay
                                + walkingMinutes
                );

        startTimeView.setMinutes(
                plannedStartMinutesOfDay
        );

        String upperText =
                "≈ "
                        + formatDurationSeconds(
                        estimate.durationSeconds
                )
                        + "  ·  "
                        + "Pause 0 min"
                        + "  ·  "
                        + "Ankunft "
                        + formatClockMinutes(
                        arrivalMinutes
                );

        String lowerText =
                formatDistance(
                        path.distanceM
                )
                        + "  ·  "
                        + "↑ "
                        + formatMeters(
                        elevationStats.ascentM
                )
                        + "  ·  "
                        + "↓ "
                        + formatMeters(
                        elevationStats.descentM
                )
                        + "  ·  "
                        + "Δ "
                        + formatSignedMeters(
                        elevationStats.deltaM
                );

        showTexts(
                upperText,
                lowerText
        );
    }

    private void renderNavigation(
            MeasurementPath path,
            LatLng position
    ) {
        RouteProgress progress =
                locateProgress(
                        path.profilePoints,
                        position
                );

        currentPositionOnSelectedRoute =
                progress != null;

        if (progress == null) {
            progress =
                    new RouteProgress(
                            0.0,
                            firstFiniteElevation(
                                    path.profilePoints
                            )
                    );
        }

        double remainingDistanceM =
                Math.max(
                        0.0,
                        path.distanceM
                                * (
                                1.0
                                        - progress.fraction
                        )
                );

        ElevationStats remainingElevation =
                calculateRemainingElevationStats(
                        path.profilePoints,
                        progress
                );

        WalkingTimeEstimate estimate =
                performanceModel == null
                        ? null
                        : performanceModel.estimateRemaining(
                                path,
                                progress.fraction
                        );

        if (remainingElevation == null
                || estimate == null) {

            hide();
            return;
        }

        long arrivalWallClockMs =
                System.currentTimeMillis()
                        + (long) (
                        estimate.durationSeconds
                                * 1000.0
                );

        String upperText =
                "≈ "
                        + formatDurationSeconds(
                        estimate.durationSeconds
                )
                        + "  ·  "
                        + "Pause "
                        + formatPauseMs(
                        currentPauseMs()
                )
                        + "  ·  "
                        + "Ankunft "
                        + DateFormat.format(
                        "HH:mm",
                        arrivalWallClockMs
                ).toString();

        String lowerText =
                formatDistance(
                        remainingDistanceM
                )
                        + "  ·  "
                        + "↑ "
                        + formatMeters(
                        remainingElevation.ascentM
                )
                        + "  ·  "
                        + "↓ "
                        + formatMeters(
                        remainingElevation.descentM
                )
                        + "  ·  "
                        + "Δ "
                        + formatSignedMeters(
                        remainingElevation.deltaM
                );

        showTexts(
                upperText,
                lowerText
        );
    }

    private void showTexts(
            String upperText,
            String lowerText
    ) {
        upperView.setText(
                upperText
        );

        lowerView.setText(
                lowerText
        );

        if (!locked
                && plannedStartMinutesOfDay >= 0) {

            startTimeView.setMinutes(
                    plannedStartMinutesOfDay
            );

            startTimeView.setVisibility(
                    View.VISIBLE
            );

        } else {
            startTimeView.setVisibility(
                    View.GONE
            );
        }

        updateVerticalPlacement();

        upperView.setVisibility(
                View.VISIBLE
        );

        lowerView.setVisibility(
                View.VISIBLE
        );

        mapView.post(
                this::updateVerticalPlacement
        );

        mapView.removeCallbacks(
                clockTick
        );

        if (locked) {
            mapView.postDelayed(
                    clockTick,
                    CLOCK_TICK_MS
            );
        }
    }

    private RouteProgress locateProgress(
            List<ProfilePoint> points,
            LatLng position
    ) {
        if (position == null
                || points == null
                || points.size() < 2) {

            return null;
        }

        ProfilePoint first =
                points.get(
                        0
                );

        ProfilePoint last =
                points.get(
                        points.size() - 1
                );

        if (first == null
                || last == null
                || !Double.isFinite(
                first.distanceM
        )
                || !Double.isFinite(
                last.distanceM
        )) {

            return null;
        }

        double spanM =
                last.distanceM
                        - first.distanceM;

        if (!Double.isFinite(
                spanM
        )
                || spanM <= 0.01) {

            return null;
        }

        double bestOffsetM =
                Double.POSITIVE_INFINITY;

        double bestDistanceM =
                first.distanceM;

        double bestElevationM =
                first.elevationM;

        double userLat =
                position.getLatitude();

        double userLon =
                position.getLongitude();

        for (int index = 1;
                index < points.size();
                index++) {

            ProfilePoint a =
                    points.get(
                            index - 1
                    );

            ProfilePoint b =
                    points.get(
                            index
                    );

            if (a == null
                    || b == null
                    || a.point == null
                    || b.point == null
                    || b.breakBefore
                    || !Double.isFinite(
                    a.distanceM
            )
                    || !Double.isFinite(
                    b.distanceM
            )) {

                continue;
            }

            double refLatRad =
                    Math.toRadians(
                            (
                                    a.point.getLatitude()
                                            + b.point.getLatitude()
                                            + userLat
                            )
                                    / 3.0
                    );

            double lonScale =
                    Math.cos(
                            refLatRad
                    );

            double ax =
                    a.point.getLongitude()
                            * lonScale;

            double ay =
                    a.point.getLatitude();

            double bx =
                    b.point.getLongitude()
                            * lonScale;

            double by =
                    b.point.getLatitude();

            double px =
                    userLon
                            * lonScale;

            double py =
                    userLat;

            double dx =
                    bx
                            - ax;

            double dy =
                    by
                            - ay;

            double lengthSquared =
                    dx
                            * dx
                            + dy
                            * dy;

            double t =
                    lengthSquared <= 1e-15
                            ? 0.0
                            : (
                            (
                                    px
                                            - ax
                            )
                                    * dx
                                    + (
                                    py
                                            - ay
                            )
                                    * dy
                    )
                            / lengthSquared;

            t =
                    Math.max(
                            0.0,
                            Math.min(
                                    1.0,
                                    t
                            )
                    );

            double projectedLat =
                    a.point.getLatitude()
                            + (
                            b.point.getLatitude()
                                    - a.point.getLatitude()
                    )
                            * t;

            double projectedLon =
                    a.point.getLongitude()
                            + (
                            b.point.getLongitude()
                                    - a.point.getLongitude()
                    )
                            * t;

            double offsetM =
                    GeoMath.distanceMeters(
                            position,
                            new LatLng(
                                    projectedLat,
                                    projectedLon
                            )
                    );

            if (!Double.isFinite(
                    offsetM
            )
                    || offsetM >= bestOffsetM) {

                continue;
            }

            bestOffsetM =
                    offsetM;

            bestDistanceM =
                    a.distanceM
                            + (
                            b.distanceM
                                    - a.distanceM
                    )
                            * t;

            if (Double.isFinite(
                    a.elevationM
            )
                    && Double.isFinite(
                    b.elevationM
            )) {

                bestElevationM =
                        a.elevationM
                                + (
                                b.elevationM
                                        - a.elevationM
                        )
                                * t;

            } else if (Double.isFinite(
                    a.elevationM
            )) {

                bestElevationM =
                        a.elevationM;

            } else if (Double.isFinite(
                    b.elevationM
            )) {

                bestElevationM =
                        b.elevationM;
            }
        }

        if (!Double.isFinite(
                bestOffsetM
        )
                || bestOffsetM
                > MAX_LIVE_ROUTE_OFFSET_M) {

            /*
             * Never turn an arbitrary nearest point into fake live progress.
             */
            return null;
        }

        double fraction =
                (
                        bestDistanceM
                                - first.distanceM
                )
                        / spanM;

        fraction =
                Math.max(
                        0.0,
                        Math.min(
                                1.0,
                                fraction
                        )
                );

        return new RouteProgress(
                fraction,
                bestElevationM
        );
    }

    private ElevationStats calculateRemainingElevationStats(
            List<ProfilePoint> points,
            RouteProgress progress
    ) {
        if (points == null
                || points.size() < 2
                || progress == null) {

            return null;
        }

        ProfilePoint first =
                points.get(
                        0
                );

        ProfilePoint last =
                points.get(
                        points.size() - 1
                );

        if (first == null
                || last == null
                || !Double.isFinite(
                first.distanceM
        )
                || !Double.isFinite(
                last.distanceM
        )) {

            return null;
        }

        double startDistanceM =
                first.distanceM
                        + (
                        last.distanceM
                                - first.distanceM
                )
                        * progress.fraction;

        double ascentM =
                0.0;

        double descentM =
                0.0;

        double previousElevationM =
                progress.elevationM;

        boolean havePrevious =
                Double.isFinite(
                        previousElevationM
                );

        for (ProfilePoint point
                : points) {

            if (point == null
                    || !Double.isFinite(
                    point.distanceM
            )
                    || point.distanceM
                    <= startDistanceM) {

                continue;
            }

            if (point.breakBefore
                    || !Double.isFinite(
                    point.elevationM
            )) {

                havePrevious =
                        false;

                continue;
            }

            if (havePrevious) {
                double deltaM =
                        point.elevationM
                                - previousElevationM;

                if (deltaM > 0.0) {
                    ascentM +=
                            deltaM;

                } else {
                    descentM +=
                            -deltaM;
                }
            }

            previousElevationM =
                    point.elevationM;

            havePrevious =
                    true;
        }

        double finalElevationM =
                lastFiniteElevation(
                        points
                );

        double deltaM =
                Double.isFinite(
                        finalElevationM
                )
                        && Double.isFinite(
                        progress.elevationM
                )
                        ? finalElevationM
                        - progress.elevationM
                        : 0.0;

        return new ElevationStats(
                ascentM,
                descentM,
                deltaM
        );
    }

    private ElevationStats calculateElevationStats(
            List<ProfilePoint> points
    ) {
        double firstElevationM =
                firstFiniteElevation(
                        points
                );

        double lastElevationM =
                lastFiniteElevation(
                        points
                );

        if (!Double.isFinite(
                firstElevationM
        )
                || !Double.isFinite(
                lastElevationM
        )) {

            return null;
        }

        double ascentM =
                0.0;

        double descentM =
                0.0;

        ProfilePoint previous =
                null;

        for (ProfilePoint point
                : points) {

            if (point == null
                    || !Double.isFinite(
                    point.elevationM
            )) {

                previous =
                        null;

                continue;
            }

            if (previous != null
                    && !point.breakBefore) {

                double deltaM =
                        point.elevationM
                                - previous.elevationM;

                if (deltaM > 0.0) {
                    ascentM +=
                            deltaM;

                } else {
                    descentM +=
                            -deltaM;
                }
            }

            previous =
                    point;
        }

        return new ElevationStats(
                ascentM,
                descentM,
                lastElevationM
                        - firstElevationM
        );
    }

    private double firstFiniteElevation(
            List<ProfilePoint> points
    ) {
        for (ProfilePoint point
                : points) {

            if (point != null
                    && Double.isFinite(
                    point.elevationM
            )) {

                return point.elevationM;
            }
        }

        return Double.NaN;
    }

    private double lastFiniteElevation(
            List<ProfilePoint> points
    ) {
        for (int index =
                points.size() - 1;
                index >= 0;
                index--) {

            ProfilePoint point =
                    points.get(
                            index
                    );

            if (point != null
                    && Double.isFinite(
                    point.elevationM
            )) {

                return point.elevationM;
            }
        }

        return Double.NaN;
    }

    private int nextHalfHour(
            int minutes
    ) {
        int normalized =
                normalizeMinutesOfDay(
                        minutes
                );

        return normalizeMinutesOfDay(
                (
                        (
                                normalized
                                        + 29
                        )
                                / 30
                )
                        * 30
        );
    }

    private int currentMinutesOfDay() {
        java.util.Calendar calendar =
                java.util.Calendar.getInstance();

        return calendar.get(
                java.util.Calendar.HOUR_OF_DAY
        )
                * 60
                + calendar.get(
                java.util.Calendar.MINUTE
        );
    }

    private int normalizeMinutesOfDay(
            int minutes
    ) {
        int result =
                minutes
                        % (
                        24
                                * 60
                );

        if (result < 0) {
            result +=
                    24
                            * 60;
        }

        return result;
    }

    private String formatClockMinutes(
            int minutes
    ) {
        int normalized =
                normalizeMinutesOfDay(
                        minutes
                );

        return String.format(
                Locale.GERMANY,
                "%02d:%02d",
                normalized / 60,
                normalized % 60
        );
    }

    private int roundedDurationMinutes(
            double seconds
    ) {
        if (!Double.isFinite(
                seconds
        )
                || seconds <= 0.0) {

            return 0;
        }

        int minutes =
                Math.max(
                        0,
                        (int)
                                Math.round(
                                        seconds
                                                / 60.0
                                )
                );

        return (
                (
                        minutes
                                + 2
                )
                        / 5
        )
                * 5;
    }

    private String formatDistance(
            double distanceM
    ) {
        if (distanceM < 1000.0) {
            return String.format(
                    Locale.GERMANY,
                    "%.0f m",
                    distanceM
            );
        }

        return String.format(
                Locale.GERMANY,
                "%.1f km",
                distanceM
                        / 1000.0
        );
    }

    private String formatMeters(
            double meters
    ) {
        return String.format(
                Locale.GERMANY,
                "%.0f m",
                meters
        );
    }

    private String formatSignedMeters(
            double meters
    ) {
        return String.format(
                Locale.GERMANY,
                "%+.0f m",
                meters
        );
    }

    private String formatDurationSeconds(
            double seconds
    ) {
        int totalMinutes =
                roundedDurationMinutes(
                        seconds
                );

        int wholeHours =
                totalMinutes
                        / 60;

        int minutes =
                totalMinutes
                        % 60;

        if (wholeHours == 0) {
            return String.format(
                    Locale.GERMANY,
                    "%d min",
                    minutes
            );
        }

        if (minutes == 0) {
            return String.format(
                    Locale.GERMANY,
                    "%d h",
                    wholeHours
            );
        }

        return String.format(
                Locale.GERMANY,
                "%d h %02d min",
                wholeHours,
                minutes
        );
    }

    private String formatPauseMs(
            long pauseMs
    ) {
        long totalMinutes =
                Math.max(
                        0L,
                        Math.round(
                                pauseMs
                                        / 60_000.0
                        )
                );

        long hours =
                totalMinutes
                        / 60L;

        long minutes =
                totalMinutes
                        % 60L;

        if (hours == 0L) {
            return String.format(
                    Locale.GERMANY,
                    "%d min",
                    minutes
            );
        }

        if (minutes == 0L) {
            return String.format(
                    Locale.GERMANY,
                    "%d h",
                    hours
            );
        }

        return String.format(
                Locale.GERMANY,
                "%d h %02d min",
                hours,
                minutes
        );
    }

    void hide() {
        mapView.removeCallbacks(
                clockTick
        );

        lastPath =
                null;

        lastPosition =
                null;

        plannedStartMinutesOfDay =
                -1;

        if (upperView != null) {
            upperView.setText(
                    ""
            );

            upperView.setVisibility(
                    View.GONE
            );
        }

        if (lowerView != null) {
            lowerView.setText(
                    ""
            );

            lowerView.setVisibility(
                    View.GONE
            );
        }

        if (startTimeView != null) {
            startTimeView.setVisibility(
                    View.GONE
            );
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

    private final class StartTimeScrollView
            extends View {

        private static final int STEP_MINUTES = 30;

        private final android.graphics.Paint mainPaint =
                new android.graphics.Paint(
                        android.graphics.Paint.ANTI_ALIAS_FLAG
                );

        private final android.graphics.Paint previewPaint =
                new android.graphics.Paint(
                        android.graphics.Paint.ANTI_ALIAS_FLAG
                );

        private int minutes;
        private float lastTouchY;
        private float dragRemainderPx;

        StartTimeScrollView(
                Context context
        ) {
            super(
                    context
            );

            float textSizePx =
                    13.5f
                            * getResources()
                            .getDisplayMetrics()
                            .scaledDensity;

            mainPaint.setTextSize(
                    textSizePx
            );

            mainPaint.setTypeface(
                    Typeface.create(
                            Typeface.MONOSPACE,
                            Typeface.NORMAL
                    )
            );

            mainPaint.setColor(
                    statsTextColor()
            );

            previewPaint.setTextSize(
                    textSizePx
            );

            previewPaint.setTypeface(
                    Typeface.create(
                            Typeface.MONOSPACE,
                            Typeface.NORMAL
                    )
            );

            previewPaint.setColor(
                    Color.rgb(
                            118,
                            123,
                            128
                    )
            );

            setClickable(
                    true
            );
        }

        void setMainColor(
                int color
        ) {
            mainPaint.setColor(
                    color
            );
            invalidate();
        }

        void setMinutes(
                int value
        ) {
            int normalized =
                    normalizeMinutesOfDay(
                            value
                    );

            if (minutes == normalized) {
                return;
            }

            minutes =
                    normalized;

            requestLayout();
            invalidate();
        }

        private String currentLabel() {
            return "Start "
                    + formatClockMinutes(
                    minutes
            );
        }

        private String previousLabel() {
            return formatClockMinutes(
                    minutes
                            - STEP_MINUTES
            );
        }

        @Override
        protected void onMeasure(
                int widthMeasureSpec,
                int heightMeasureSpec
        ) {
            android.graphics.Paint.FontMetrics metrics =
                    mainPaint.getFontMetrics();

            int textHeight =
                    Math.max(
                            1,
                            Math.round(
                                    metrics.descent
                                            - metrics.ascent
                            )
                    );

            int touchWidth =
                    Math.max(
                            textHeight,
                            dp(
                                    28
                            )
                    );

            int currentWidth =
                    Math.max(
                            1,
                            Math.round(
                                    mainPaint.measureText(
                                            currentLabel()
                                    )
                            )
                    );

            int previewWidth =
                    Math.max(
                            1,
                            Math.round(
                                    previewPaint.measureText(
                                            previousLabel()
                                    )
                            )
                    );

            int desiredHeight =
                    currentWidth
                            + dp(
                            5
                    )
                            + previewWidth;

            setMeasuredDimension(
                    resolveSize(
                            touchWidth,
                            widthMeasureSpec
                    ),
                    resolveSize(
                            desiredHeight,
                            heightMeasureSpec
                    )
            );
        }

        @Override
        protected void onDraw(
                Canvas canvas
        ) {
            super.onDraw(
                    canvas
            );

            String current =
                    currentLabel();

            String previous =
                    previousLabel();

            android.graphics.Paint.FontMetrics metrics =
                    mainPaint.getFontMetrics();

            float textHeight =
                    metrics.descent
                            - metrics.ascent;

            float baseline =
                    (
                            getWidth()
                                    - textHeight
                    )
                            / 2.0f
                            - metrics.ascent;

            int save =
                    canvas.save();

            canvas.translate(
                    0.0f,
                    getHeight()
            );

            canvas.rotate(
                    -90.0f
            );

            canvas.drawText(
                    current,
                    0.0f,
                    baseline,
                    mainPaint
            );

            float previewX =
                    mainPaint.measureText(
                            current
                    )
                            + dp(
                            5
                    );

            canvas.drawText(
                    previous,
                    previewX,
                    baseline,
                    previewPaint
            );

            canvas.restoreToCount(
                    save
            );
        }

        @Override
        public boolean onTouchEvent(
                MotionEvent event
        ) {
            if (!isEnabled()
                    || locked
                    || lastPath == null) {

                return false;
            }

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    lastTouchY =
                            event.getY();

                    dragRemainderPx =
                            0.0f;

                    getParent()
                            .requestDisallowInterceptTouchEvent(
                                    true
                            );

                    return true;

                case MotionEvent.ACTION_MOVE:
                    float currentY =
                            event.getY();

                    float delta =
                            lastTouchY
                                    - currentY;

                    lastTouchY =
                            currentY;

                    dragRemainderPx +=
                            delta;

                    float threshold =
                            dp(
                                    22
                            );

                    boolean changed =
                            false;

                    while (dragRemainderPx >= threshold) {
                        plannedStartMinutesOfDay =
                                normalizeMinutesOfDay(
                                        plannedStartMinutesOfDay
                                                + STEP_MINUTES
                                );

                        dragRemainderPx -=
                                threshold;

                        changed =
                                true;
                    }

                    while (dragRemainderPx <= -threshold) {
                        plannedStartMinutesOfDay =
                                normalizeMinutesOfDay(
                                        plannedStartMinutesOfDay
                                                - STEP_MINUTES
                                );

                        dragRemainderPx +=
                                threshold;

                        changed =
                                true;
                    }

                    if (changed) {
                        renderLastState();
                    }

                    return true;

                case MotionEvent.ACTION_UP:
                    getParent()
                            .requestDisallowInterceptTouchEvent(
                                    false
                            );

                    performClick();
                    return true;

                case MotionEvent.ACTION_CANCEL:
                    getParent()
                            .requestDisallowInterceptTouchEvent(
                                    false
                            );

                    return true;

                default:
                    return true;
            }
        }

        @Override
        public boolean performClick() {
            super.performClick();
            return true;
        }
    }


    private static final class VerticalStatsTextView
            extends TextView {

        VerticalStatsTextView(
                Context context
        ) {
            super(
                    context
            );

            setSingleLine(
                    true
            );
        }

        @Override
        protected void onMeasure(
                int widthMeasureSpec,
                int heightMeasureSpec
        ) {
            CharSequence value =
                    getText();

            String text =
                    value == null
                            ? ""
                            : value.toString();

            android.graphics.Paint paint =
                    getPaint();

            android.graphics.Paint.FontMetrics metrics =
                    paint.getFontMetrics();

            int normalWidth =
                    Math.max(
                            1,
                            Math.round(
                                    paint.measureText(
                                            text
                                    )
                            )
                    );

            int normalHeight =
                    Math.max(
                            1,
                            Math.round(
                                    metrics.descent
                                            - metrics.ascent
                            )
                    );

            setMeasuredDimension(
                    resolveSize(
                            normalHeight,
                            widthMeasureSpec
                    ),
                    resolveSize(
                            normalWidth,
                            heightMeasureSpec
                    )
            );
        }

        @Override
        protected void onDraw(
                Canvas canvas
        ) {
            CharSequence value =
                    getText();

            if (value == null
                    || value.length() == 0) {

                return;
            }

            String text =
                    value.toString();

            android.graphics.Paint paint =
                    getPaint();

            paint.setColor(
                    getCurrentTextColor()
            );

            android.graphics.Paint.FontMetrics metrics =
                    paint.getFontMetrics();

            int saveCount =
                    canvas.save();

            canvas.translate(
                    0.0f,
                    getHeight()
            );

            canvas.rotate(
                    -90.0f
            );

            float baseline =
                    -metrics.ascent;

            canvas.drawText(
                    text,
                    0.0f,
                    baseline,
                    paint
            );

            canvas.restoreToCount(
                    saveCount
            );
        }
    }

    private static final class RouteProgress {

        final double fraction;
        final double elevationM;

        RouteProgress(
                double fraction,
                double elevationM
        ) {
            this.fraction =
                    fraction;

            this.elevationM =
                    elevationM;
        }
    }

    private static final class ElevationStats {

        final double ascentM;
        final double descentM;
        final double deltaM;

        ElevationStats(
                double ascentM,
                double descentM,
                double deltaM
        ) {
            this.ascentM =
                    ascentM;

            this.descentM =
                    descentM;

            this.deltaM =
                    deltaM;
        }
    }
}
