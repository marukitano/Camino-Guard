package com.marukitano.caminoguard;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
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
 * Small map overlay shown only for a manually selected Camino segment
 * (two explicit Camino tap points).
 *
 * It consumes the already calculated MeasurementPath. No routing or projection
 * work is duplicated here.
 */
final class CaminoSelectionStatsOverlay {

    private final Activity activity;
    private final MapView mapView;
    private final WalkingPerformanceModel performanceModel;

    private TextView view;

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
        if (view != null) {
            return;
        }

        view =
                new VerticalStatsTextView(
                        activity
                );

        view.setTextColor(
                Color.WHITE
        );

        view.setTextSize(
                13.5f
        );

        view.setTypeface(
                Typeface.create(
                        Typeface.MONOSPACE,
                        Typeface.NORMAL
                )
        );

        /*
         * The stats strip itself is vertical. The custom TextView rotates the
         * actual glyphs clockwise, so the values read from top to bottom along
         * the physical right screen edge instead of being ordinary horizontal
         * rows that merely happen to be right-aligned.
         */
        view.setGravity(
                Gravity.CENTER
        );

        view.setTextAlignment(
                View.TEXT_ALIGNMENT_CENTER
        );

        view.setPadding(
                dp(9),
                dp(7),
                dp(10),
                dp(7)
        );

        view.setLineSpacing(
                0.0f,
                1.08f
        );

        view.setBackground(
                buildBackground()
        );

        view.setVisibility(
                View.GONE
        );

        view.setClickable(
                false
        );

        ViewGroup parent =
                (ViewGroup)
                        mapView.getParent();

        FrameLayout.LayoutParams params =
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.END
                                | Gravity.CENTER_VERTICAL
                );

        params.rightMargin =
                dp(
                        8
                );

        parent.addView(
                view,
                params
        );

        view.setElevation(
                dp(
                        900
                )
        );

        view.bringToFront();
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

        /*
         * One physical text line is rotated by VerticalStatsTextView. Keeping
         * embedded newlines here would create several vertical columns instead
         * of one clean strip along the screen edge.
         */
        String text =
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
                )
                        + "  ·  "
                        + "≈ "
                        + formatDurationSeconds(
                        estimate.durationSeconds
                )
                        + "  ·  "
                        + "Ankunft "
                        + DateFormat.format(
                        "HH:mm",
                        arrivalWallClockMs
                ).toString();

        view.setText(
                text
        );

        view.setVisibility(
                View.VISIBLE
        );
    }

    void hide() {
        if (view == null) {
            return;
        }

        view.setText(
                ""
        );

        view.setVisibility(
                View.GONE
        );
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

    private GradientDrawable buildBackground() {
        GradientDrawable background =
                new GradientDrawable();

        background.setColor(
                Color.argb(
                        150,
                        24,
                        27,
                        30
                )
        );

        background.setCornerRadius(
                dp(
                        9
                )
        );

        background.setStroke(
                dp(
                        1
                ),
                Color.argb(
                        85,
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

    /*
     * TextView whose measured dimensions are physically vertical while the
     * actual text itself remains a normal readable line rotated clockwise.
     * Because the dimensions are swapped in onMeasure(), FrameLayout can place
     * this as a true narrow vertical strip at Gravity.END without translation
     * hacks.
     */
    /*
     * True vertical stats strip.
     *
     * v76 rotated TextView's own internal layout. Android then relaid out the
     * TextView with the already-swapped dimensions, which could leave a
     * perfectly visible vertical background but clip the actual glyphs.
     *
     * v77 therefore lets TextView keep only its text/font state and draws the
     * one-line value string directly with its Paint. Measurement is performed
     * explicitly before the 90-degree rotation.
     */
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

            int horizontalWidth =
                    Math.max(
                            1,
                            Math.round(
                                    paint.measureText(
                                            text
                                    )
                            )
                                    + getPaddingLeft()
                                    + getPaddingRight()
                    );

            int horizontalHeight =
                    Math.max(
                            1,
                            Math.round(
                                    metrics.descent
                                            - metrics.ascent
                            )
                                    + getPaddingTop()
                                    + getPaddingBottom()
                    );

            /*
             * After clockwise rotation:
             *   physical width  = normal text height
             *   physical height = normal text width
             */
            int desiredWidth =
                    horizontalHeight;

            int desiredHeight =
                    horizontalWidth;

            setMeasuredDimension(
                    resolveSize(
                            desiredWidth,
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

            android.graphics.Paint.FontMetrics metrics =
                    paint.getFontMetrics();

            int saveCount =
                    canvas.save();

            canvas.translate(
                    getWidth(),
                    0.0f
            );

            canvas.rotate(
                    90.0f
            );

            float baseline =
                    getPaddingTop()
                            - metrics.ascent;

            canvas.drawText(
                    text,
                    getPaddingLeft(),
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
