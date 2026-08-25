package com.marukitano.caminoguard;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.util.Linkify;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.MapView;

import java.util.List;
import java.util.Locale;

final class CaminoInfoController {

    private final Activity activity;
    private final MapView mapView;
    private final CaminoInfoPresenter presenter;

    private MapLibreMap map;
    private CaminoInfoPanel panel;

    private Runnable navigationAction;
    private NavigationController.Mode navigationMode =
            NavigationController.Mode.MANUAL;

    private Runnable selectionLockAction;
    private boolean selectionLocked;
    private boolean selectionLockAvailable;

    CaminoInfoController(
            Activity activity,
            MapView mapView,
            CaminoInfoPresenter presenter
    ) {
        this.activity = activity;
        this.mapView = mapView;
        this.presenter = presenter;
    }

    void attachMap(
            MapLibreMap map
    ) {
        this.map = map;

        if (panel != null) {
            configureCompass();
        }
    }

    void setNavigationAction(
            Runnable action
    ) {
        navigationAction = action;

        if (panel != null) {
            panel.setNavigationAction(
                    action
            );
        }
    }

    void setNavigationMode(
            NavigationController.Mode mode
    ) {
        navigationMode =
                mode == null
                        ? NavigationController.Mode.MANUAL
                        : mode;

        if (panel != null) {
            panel.setNavigationMode(
                    navigationMode
            );
        }
    }

    void setSelectionLockAction(
            Runnable action
    ) {
        selectionLockAction =
                action;

        if (panel != null) {
            panel.setSelectionLockAction(
                    action
            );
        }
    }

    void setSelectionLocked(
            boolean locked
    ) {
        selectionLocked =
                locked;

        if (panel != null) {
            panel.setSelectionLocked(
                    locked
            );
        }
    }

    void setSelectionLockAvailable(
            boolean available
    ) {
        selectionLockAvailable =
                available;

        if (panel != null) {
            panel.setSelectionLockAvailable(
                    available
            );
        }
    }

    void ensureView() {
        if (panel != null) {
            return;
        }

        panel =
                new CaminoInfoPanel(
                        activity
                );

        presenter.attach(
                panel
        );

        if (navigationAction != null) {
            panel.setNavigationAction(
                    navigationAction
            );
        }

        panel.setNavigationMode(
                navigationMode
        );

        panel.setSelectionLockAction(
                selectionLockAction
        );

        panel.setSelectionLocked(
                selectionLocked
        );

        panel.setSelectionLockAvailable(
                selectionLockAvailable
        );

        panel.setAttributionAction(
                this::showAttributionDialog
        );

        if (map != null) {
            configureCompass();
        }

        ViewGroup parent =
                (ViewGroup)
                        mapView.getParent();

        /*
         * The former bottom info card is currently disabled. Its remaining
         * navigation-mode + lock + attribution controls live as a compact vertical
         * stack at the left screen edge.
         */
        FrameLayout.LayoutParams params =
                new FrameLayout.LayoutParams(
                        dp(
                                48
                        ),
                        dp(
                                176
                        ),
                        Gravity.START
                                | Gravity.BOTTOM
                );

        params.leftMargin =
                dp(
                        2
                );

        /*
         * Anchor the three controls at the bottom-left and let the stack grow
         * upward: info, lock, then the single navigation-mode button.
         */
        params.bottomMargin =
                dp(
                        18
                );

        parent.addView(
                panel,
                params
        );

        panel.setElevation(
                dp(
                        1000
                )
        );

        panel.bringToFront();

        presenter.refresh();
    }

    void updateCompass() {
        if (panel == null
                || map == null) {
            return;
        }

        org.maplibre.android.camera.CameraPosition camera =
                map.getCameraPosition();

        panel.setBearing(
                camera.bearing
        );

        panel.setZoomLevel(
                camera.zoom
        );
    }

    void updateMeasurementSummary(
            List<CaminoRoute> routes,
            CaminoSelectionController selectionController,
            RouteHit startRouteHit,
            MeasurementPath currentMeasurementPath
    ) {
        if (routes.isEmpty()) {
            presenter.setInfoTitle(
                    ""
            );

            presenter.setSummaryTexts(
                    "Lade Caminos …",
                    ""
            );

            return;
        }

        if (selectionController.selectedRoute() == null
                || selectionController.selectedHit() == null) {

            if (startRouteHit == null) {
                presenter.setInfoTitle(
                        ""
                );

                presenter.setSummaryTexts(
                        "Kein Camino gefunden",
                        ""
                );

                return;
            }

            presenter.setInfoTitle(
                    startRouteHit.route.name
            );

            double offRouteM =
                    startRouteHit.hit.distanceFromQueryM;

            presenter.setSummaryTexts(
                    offRouteM < 3.0
                            ? "Auf dem Camino"
                            : formatDistance(
                                    offRouteM
                            )
                            + " bis Camino",
                    ""
            );

            return;
        }

        RouteHit measurementStart;
        RouteHit measurementEnd;

        if (selectionController.secondTapHit() != null) {
            if (selectionController.secondSelectedRoute() == null) {
                presenter.setInfoTitle(
                        selectionController.selectedRoute().name
                );

                presenter.setSummaryTexts(
                        "Zweiter Camino fehlt",
                        ""
                );

                return;
            }

            measurementStart =
                    new RouteHit(
                            selectionController.selectedRoute(),
                            selectionController.selectedHit()
                    );

            measurementEnd =
                    new RouteHit(
                            selectionController.secondSelectedRoute(),
                            selectionController.secondTapHit()
                    );

        } else {
            if (startRouteHit == null) {
                presenter.setInfoTitle(
                        selectionController.selectedRoute().name
                );

                presenter.setSummaryTexts(
                        "Startpunkt konnte nicht projiziert werden",
                        ""
                );

                return;
            }

            measurementStart =
                    startRouteHit;

            measurementEnd =
                    new RouteHit(
                            selectionController.selectedRoute(),
                            selectionController.selectedHit()
                    );
        }

        presenter.setInfoTitle(
                measurementRouteLabel(
                        measurementStart,
                        measurementEnd
                )
        );

        if (currentMeasurementPath == null) {
            presenter.setSummaryTexts(
                    "Keine Camino-Verbindung",
                    ""
            );

            return;
        }

        String leftText =
                "";

        if (selectionController.secondTapHit() == null) {
            leftText =
                    startRouteHit.hit.distanceFromQueryM < 3.0
                            ? "Auf dem Camino"
                            : formatDistance(
                                    startRouteHit.hit.distanceFromQueryM
                            )
                            + " bis Camino";
        }

        String rightText =
                formatDistance(
                        currentMeasurementPath.distanceM
                )
                        + " Etappenlänge";

        presenter.setSummaryTexts(
                leftText,
                rightText
        );
    }

    private void showAttributionDialog() {
        final Dialog dialog =
                new Dialog(
                        activity
                );

        LinearLayout card =
                new LinearLayout(
                        activity
                );

        card.setOrientation(
                LinearLayout.VERTICAL
        );

        card.setPadding(
                dp(
                        20
                ),
                dp(
                        17
                ),
                dp(
                        20
                ),
                dp(
                        15
                )
        );

        GradientDrawable background =
                new GradientDrawable();

        background.setColor(
                Color.argb(
                        238,
                        35,
                        39,
                        43
                )
        );

        background.setCornerRadius(
                dp(
                        22
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

        card.setBackground(
                background
        );

        TextView title =
                new TextView(
                        activity
                );

        title.setText(
                "Kartendaten & Lizenzen"
        );

        title.setTextColor(
                Color.WHITE
        );

        title.setTextSize(
                18.0f
        );

        title.setGravity(
                Gravity.START
        );

        title.setTypeface(
                android.graphics.Typeface.DEFAULT,
                android.graphics.Typeface.BOLD
        );

        card.addView(
                title,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                )
        );

        TextView body =
                new TextView(
                        activity
                );

        body.setText(
                "© OpenStreetMap-Mitwirkende\n"
                        + "Basiskarte: Protomaps\n"
                        + "Terrain/Höhendaten: Mapterhorn open-data sources\n"
                        + "Weltübersicht: MapLibre Demo Tiles / Natural Earth\n"
                        + "Camino-Routen: CNIG / FEAACS, CC BY 4.0\n\n"
                        + "Camino Guard © Maru\n"
                        + "Lizenz: GNU GPLv3\n"
                        + "GitHub: https://github.com/marukitano/Camino-Guard"
        );

        body.setTextColor(
                Color.WHITE
        );

        body.setTextSize(
                14.0f
        );

        body.setLineSpacing(
                0.0f,
                1.12f
        );

        body.setAutoLinkMask(
                Linkify.WEB_URLS
        );

        body.setLinkTextColor(
                Color.rgb(
                        150,
                        205,
                        255
                )
        );

        LinearLayout.LayoutParams bodyParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                );

        bodyParams.topMargin =
                dp(
                        12
                );

        card.addView(
                body,
                bodyParams
        );

        TextView close =
                new TextView(
                        activity
                );

        close.setText(
                "OK"
        );

        close.setTextColor(
                Color.WHITE
        );

        close.setTextSize(
                14.0f
        );

        close.setTypeface(
                android.graphics.Typeface.DEFAULT,
                android.graphics.Typeface.BOLD
        );

        close.setGravity(
                Gravity.CENTER
        );

        close.setPadding(
                dp(
                        14
                ),
                dp(
                        9
                ),
                dp(
                        14
                ),
                dp(
                        9
                )
        );

        LinearLayout.LayoutParams closeParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                );

        closeParams.gravity =
                Gravity.END;

        closeParams.topMargin =
                dp(
                        8
                );

        card.addView(
                close,
                closeParams
        );

        close.setOnClickListener(
                view ->
                        dialog.dismiss()
        );

        dialog.setContentView(
                card
        );

        android.view.Window window =
                dialog.getWindow();

        if (window != null) {
            window.setBackgroundDrawable(
                    new ColorDrawable(
                            Color.TRANSPARENT
                    )
            );

            window.setDimAmount(
                    0.28f
            );

            window.addFlags(
                    android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND
            );
        }

        dialog.setCanceledOnTouchOutside(
                true
        );

        dialog.show();

        window =
                dialog.getWindow();

        if (window != null) {
            android.view.WindowManager.LayoutParams attributes =
                    window.getAttributes();

            attributes.width =
                    Math.min(
                            dp(
                                    340
                            ),
                            activity
                                    .getResources()
                                    .getDisplayMetrics()
                                    .widthPixels
                                    - dp(
                                    28
                            )
                    );

            attributes.height =
                    android.view.WindowManager.LayoutParams.WRAP_CONTENT;

            window.setAttributes(
                    attributes
            );
        }
    }

    private void configureCompass() {
        panel.setCompassDrawable(
                map.getUiSettings()
                        .getCompassImage()
        );

        map.getUiSettings()
                .setCompassEnabled(
                        false
                );

        map.getUiSettings()
                .setAttributionEnabled(
                        false
                );

        map.getUiSettings()
                .setLogoEnabled(
                        false
                );

        updateCompass();
    }

    private String measurementRouteLabel(
            RouteHit start,
            RouteHit end
    ) {
        if (start.route
                == end.route) {
            return end.route.name;
        }

        return start.route.name
                + " → "
                + end.route.name;
    }

    private String formatDistance(
            double distanceM
    ) {
        if (distanceM
                >= 1000.0) {

            return String.format(
                    Locale.GERMANY,
                    "%.2fkm",
                    distanceM
                            / 1000.0
            );
        }

        return String.format(
                Locale.GERMANY,
                "%.0fm",
                distanceM
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
