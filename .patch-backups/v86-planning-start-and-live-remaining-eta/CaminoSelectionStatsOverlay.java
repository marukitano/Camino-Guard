package com.marukitano.caminoguard;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.maplibre.android.maps.MapView;

import java.util.List;
import java.util.Locale;

/**
 * Vertical route statistics shown for an explicit selected Camino segment.
 *
 * v84 splits the information around the centre-right profile toggle:
 *
 *   ABOVE toggle: walking time + arrival time
 *   BELOW toggle: distance + elevation values
 */
final class CaminoSelectionStatsOverlay {

    private static final int RIGHT_MARGIN_PX = 3;
    private static final int CENTER_GAP_DP = 34;

    private final Activity activity;
    private final MapView mapView;
    private final WalkingPerformanceModel performanceModel;

    private VerticalStatsTextView upperView;
    private VerticalStatsTextView lowerView;

    private boolean locked;

    CaminoSelectionStatsOverlay(
            Activity activity,
            MapView mapView,
            WalkingPerformanceModel performanceModel
    ) {
        this.activity = activity;
        this.mapView = mapView;
        this.performanceModel =
                performanceModel;
    }

    void ensureView() {
        if (upperView != null
                && lowerView != null) {
            return;
        }

        ViewGroup parent =
                (ViewGroup)
                        mapView.getParent();

        upperView =
                createStatsView();

        lowerView =
                createStatsView();

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
                upperView,
                upperParams
        );

        parent.addView(
                lowerView,
                lowerParams
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

        /*
         * No internal padding. The 3 px LayoutParams margin is therefore the
         * real visible right-edge spacing instead of being hidden behind the
         * legacy 7-10 dp TextView padding.
         */
        result.setPadding(
                0,
                0,
                0,
                0
        );

        result.setVisibility(
                View.GONE
        );

        result.setClickable(
                false
        );

        return result;
    }

    private void updateVerticalPlacement() {
        if (upperView == null
                || lowerView == null
                || mapView.getHeight() <= 0) {
            return;
        }

        ViewGroup.LayoutParams rawUpper =
                upperView.getLayoutParams();

        ViewGroup.LayoutParams rawLower =
                lowerView.getLayoutParams();

        if (!(rawUpper instanceof FrameLayout.LayoutParams)
                || !(rawLower instanceof FrameLayout.LayoutParams)) {
            return;
        }

        FrameLayout.LayoutParams upperParams =
                (FrameLayout.LayoutParams)
                        rawUpper;

        FrameLayout.LayoutParams lowerParams =
                (FrameLayout.LayoutParams)
                        rawLower;

        int centre =
                mapView.getHeight()
                        / 2;

        int gap =
                dp(
                        CENTER_GAP_DP
                );

        upperParams.rightMargin =
                RIGHT_MARGIN_PX;

        upperParams.bottomMargin =
                mapView.getHeight()
                        - (
                        centre
                                - gap
                );

        lowerParams.rightMargin =
                RIGHT_MARGIN_PX;

        lowerParams.topMargin =
                centre
                        + gap;

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
        this.locked =
                locked;

        int color =
                statsTextColor();

        if (upperView != null) {
            upperView.setTextColor(
                    color
            );
            upperView.invalidate();
        }

        if (lowerView != null) {
            lowerView.setTextColor(
                    color
            );
            lowerView.invalidate();
        }
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
            MeasurementPath path
    ) {
        ensureView();

        if (path == null
                || path.profilePoints.isEmpty()
                || !Double.isFinite(
                path.distanceM
        )
                || path.distanceM <= 0.0) {

            hide();
            return;
        }

        ElevationStats elevationStats =
                calculateElevationStats(
                        path.profilePoints
                );

        if (elevationStats == null) {
            hide();
            return;
        }

        WalkingTimeEstimate estimate =
                performanceModel == null
                        ? null
                        : performanceModel.estimate(
                                path
                        );

        if (estimate == null) {
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
                        + "Ankunft "
                        + DateFormat.format(
                        "HH:mm",
                        arrivalWallClockMs
                ).toString();

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

        upperView.setText(
                upperText
        );

        lowerView.setText(
                lowerText
        );

        updateVerticalPlacement();

        upperView.setVisibility(
                View.VISIBLE
        );

        lowerView.setVisibility(
                View.VISIBLE
        );
    }

    void hide() {
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
    }

    private ElevationStats calculateElevationStats(
            List<ProfilePoint> points
    ) {
        ProfilePoint first =
                firstFiniteElevationPoint(
                        points
                );

        ProfilePoint last =
                lastFiniteElevationPoint(
                        points
                );

        if (first == null
                || last == null) {
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

            if (!Double.isFinite(
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
                last.elevationM
                        - first.elevationM
        );
    }

    private ProfilePoint firstFiniteElevationPoint(
            List<ProfilePoint> points
    ) {
        for (ProfilePoint point
                : points) {

            if (Double.isFinite(
                    point.elevationM
            )) {
                return point;
            }
        }

        return null;
    }

    private ProfilePoint lastFiniteElevationPoint(
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

            if (Double.isFinite(
                    point.elevationM
            )) {
                return point;
            }
        }

        return null;
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
        if (!Double.isFinite(
                seconds
        )) {
            return "–";
        }

        int totalMinutes =
                Math.max(
                        0,
                        (int)
                                Math.round(
                                        seconds
                                                / 60.0
                                )
                );

        totalMinutes =
                ((totalMinutes + 2)
                        / 5)
                        * 5;

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
