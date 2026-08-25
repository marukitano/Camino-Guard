package com.marukitano.caminoguard;

import android.app.Activity;
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
                new TextView(
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
         * Keep the selected-route values stacked vertically, but align the
         * complete stats block to the physical right edge.
         */
        view.setGravity(
                Gravity.END
                        | Gravity.CENTER_VERTICAL
        );

        view.setTextAlignment(
                View.TEXT_ALIGNMENT_VIEW_END
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
                                | Gravity.TOP
                );

        params.rightMargin =
                dp(
                        8
                );

        params.topMargin =
                dp(
                        78
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

        String text =
                formatDistance(
                        path.distanceM
                )
                        + "\n"
                        + "↑ "
                        + formatMeters(
                        elevationStats.ascentM
                )
                        + "\n"
                        + "↓ "
                        + formatMeters(
                        elevationStats.descentM
                )
                        + "\n"
                        + "Δ "
                        + formatSignedMeters(
                        elevationStats.deltaM
                )
                        + "\n"
                        + "≈ "
                        + formatDurationSeconds(
                        estimate.durationSeconds
                )
                        + "\n"
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
