package com.marukitano.caminoguard;

import android.app.Activity;
import android.graphics.PointF;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.MapView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Owns the projected Camino height-profile overlay and its refresh lifecycle.
 *
 * Measurement geometry remains owned by MeasurementEngine. Drawing/touch
 * behavior remains owned by CaminoHeightProfileView.
 */
final class CaminoHeightProfileController {

    private static final long REFRESH_DELAY_MS =
            CaminoConfig.get().longValue(
                    "measurement.heightProfileRefreshDelayMs"
            );

    private final Activity activity;
    private final MapView mapView;
    private final CaminoInfoPresenter infoPresenter;
    private final Supplier<MeasurementPath> measurementPathSupplier;

    private MapLibreMap map;
    private CaminoHeightProfileView view;
    private boolean refreshScheduled;

    private final Runnable refreshRunnable =
            () -> {
                refreshScheduled = false;
                refresh();
            };

    CaminoHeightProfileController(
            Activity activity,
            MapView mapView,
            CaminoInfoPresenter infoPresenter,
            Supplier<MeasurementPath> measurementPathSupplier
    ) {
        this.activity = activity;
        this.mapView = mapView;
        this.infoPresenter = infoPresenter;
        this.measurementPathSupplier = measurementPathSupplier;
    }

    void attachMap(
            MapLibreMap map
    ) {
        this.map = map;
    }

    void ensureView() {
        if (view != null) {
            return;
        }

        view =
                new CaminoHeightProfileView(
                        activity
                );

        view.setVisibility(
                android.view.View.GONE
        );

        ViewGroup parent =
                (ViewGroup)
                        mapView.getParent();

        /*
         * Full-screen transparent overlay: the profile still occupies only the
         * rightmost 126 dp when drawn, but the cursor guide can extend all the
         * way back to the corresponding Camino point.
         *
         * onTouchEvent() returns false outside the profile strip, so normal map
         * gestures keep working everywhere else.
         */
        FrameLayout.LayoutParams params =
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        Gravity.TOP
                                | Gravity.START
                );

        parent.addView(
                view,
                params
        );

        view.setElevation(
                dp(2)
        );
    }

    void scheduleRefresh() {
        if (refreshScheduled) {
            return;
        }

        refreshScheduled = true;

        mapView.postDelayed(
                refreshRunnable,
                REFRESH_DELAY_MS
        );
    }

    void handleCameraIdle() {
        mapView.removeCallbacks(
                refreshRunnable
        );

        refreshScheduled = false;

        refresh();
    }

    void refresh() {
        MeasurementPath measurementPath =
                measurementPathSupplier.get();

        if (view == null
                || map == null
                || measurementPath == null
                || measurementPath.profilePoints.size()
                < 2
                || mapView.getWidth() <= 0
                || mapView.getHeight() <= 0) {

            if (view != null) {
                view.clearProfile();
            }

            infoPresenter.setHeightStats(
                    ""
            );

            return;
        }

        float width =
                mapView.getWidth();

        float height =
                mapView.getHeight();

        List<CaminoHeightProfileView.Sample> visible =
                new ArrayList<>();

        int previousVisibleIndex =
                -2;

        double previousElevation =
                Double.NaN;

        double minElevation =
                Double.POSITIVE_INFINITY;

        double maxElevation =
                Double.NEGATIVE_INFINITY;

        double netElevationChange =
                0.0;

        double accumulatedAscent =
                0.0;

        double accumulatedDescent =
                0.0;

        for (int index = 0;
                index
                        < measurementPath.profilePoints.size();
                index++) {

            ProfilePoint point =
                    measurementPath.profilePoints.get(
                            index
                    );

            if (!Double.isFinite(
                    point.elevationM
            )) {
                continue;
            }

            PointF screen =
                    map.getProjection()
                            .toScreenLocation(
                                    point.point
                            );

            if (!Float.isFinite(
                    screen.x
            ) || !Float.isFinite(
                    screen.y
            ) || screen.x < 0.0f
                    || screen.x > width
                    || screen.y < 0.0f
                    || screen.y > height) {

                continue;
            }

            boolean breakBefore =
                    point.breakBefore
                            || previousVisibleIndex
                            != index - 1;

            visible.add(
                    new CaminoHeightProfileView.Sample(
                            screen.x / width,
                            screen.y / height,
                            point.elevationM,
                            breakBefore
                    )
            );

            minElevation =
                    Math.min(
                            minElevation,
                            point.elevationM
                    );

            maxElevation =
                    Math.max(
                            maxElevation,
                            point.elevationM
                    );

            if (!breakBefore
                    && Double.isFinite(
                    previousElevation
            )) {

                double delta =
                        point.elevationM
                                - previousElevation;

                netElevationChange +=
                        delta;

                if (delta
                        > 0.0) {

                    accumulatedAscent +=
                            delta;

                } else if (delta
                        < 0.0) {

                    accumulatedDescent +=
                            -delta;
                }
            }

            previousElevation =
                    point.elevationM;

            previousVisibleIndex =
                    index;
        }

        if (visible.size()
                < 2
                || !Double.isFinite(
                minElevation
        )
                || !Double.isFinite(
                maxElevation
        )) {

            view.clearProfile();

            infoPresenter.setHeightStats(
                    ""
            );

            return;
        }

        view.setSamples(
                visible
        );

        /*
         * One metric per line keeps the bottom information panel readable.
         * Sigma-down is a positive magnitude: total descended vertical metres
         * over the currently visible route fragments.
         */
        infoPresenter.setHeightStats(
                String.format(
                        Locale.GERMANY,
                        "Altₘᵢₙ   %.0f m\n"
                                + "Altₘₐₓ   %.0f m\n"
                                + "AltΔ     %+.0f m\n"
                                + "AltΣ↑    %.0f m\n"
                                + "AltΣ↓    %.0f m",
                        minElevation,
                        maxElevation,
                        netElevationChange,
                        accumulatedAscent,
                        accumulatedDescent
                )
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
}
