package com.marukitano.caminoguard;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;

import org.maplibre.android.MapLibre;
import org.maplibre.android.maps.MapView;


public final class MainActivity extends Activity {

    private MapView mapView;
    private GpsGyroOrientationController orientationController;
    private CaminoController caminoController;
    private MapCoordinator mapCoordinator;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        CaminoConfig.initialize(this);

        MapLibre.getInstance(this);
        setContentView(R.layout.activity_main);

        mapView = findViewById(R.id.map_view);

        org.maplibre.android.geometry.LatLng startupPosition =
                MapCoordinator.startupPosition();

        orientationController =
                new GpsGyroOrientationController(this);

        caminoController =
                new CaminoController(
                        this,
                        mapView,
                        startupPosition
                );

        caminoController.configureLivePositionMode(startupPosition);
        caminoController.setLiveNavigationController(orientationController);

        mapCoordinator =
                new MapCoordinator(
                        this,
                        mapView,
                        findViewById(R.id.map_setup_panel),
                        findViewById(R.id.map_setup_status),
                        findViewById(R.id.map_setup_progress),
                        orientationController,
                        caminoController
                );

        mapCoordinator.start();

        showLibreLinkUpSetupIfNeeded();
    }


    /*
     * Minimal v0.1 setup only.
     *
     * Credentials are stored in Camino Guard's private app storage and are
     * never written to camino-config.json or to the repository.
     */
    private void showLibreLinkUpSetupIfNeeded() {
        LibreLinkUpStore store =
                new LibreLinkUpStore(
                        this
                );

        if (store.hasCredentials()) {
            return;
        }

        int padding =
                Math.round(
                        20.0f
                                * getResources()
                                .getDisplayMetrics()
                                .density
                );

        LinearLayout form =
                new LinearLayout(
                        this
                );

        form.setOrientation(
                LinearLayout.VERTICAL
        );

        form.setPadding(
                padding,
                padding / 2,
                padding,
                0
        );


        EditText email =
                new EditText(
                        this
                );

        email.setHint(
                "LibreLinkUp E-Mail"
        );

        email.setSingleLine(
                true
        );

        email.setInputType(
                InputType.TYPE_CLASS_TEXT
                        | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        );


        EditText password =
                new EditText(
                        this
                );

        password.setHint(
                "LibreLinkUp Passwort"
        );

        password.setSingleLine(
                true
        );

        password.setInputType(
                InputType.TYPE_CLASS_TEXT
                        | InputType.TYPE_TEXT_VARIATION_PASSWORD
        );


        form.addView(
                email
        );

        form.addView(
                password
        );


        AlertDialog dialog =
                new AlertDialog.Builder(
                        this
                )
                        .setTitle(
                                "LibreLinkUp"
                        )
                        .setMessage(
                                "Camino Guard verwendet den Europe-Server. "
                                        + "Die Zugangsdaten bleiben lokal "
                                        + "im privaten App-Speicher."
                        )
                        .setView(
                                form
                        )
                        .setPositiveButton(
                                "Speichern",
                                null
                        )
                        .setNegativeButton(
                                "Später",
                                null
                        )
                        .create();


        dialog.setOnShowListener(
                ignored -> {
                    dialog.getButton(
                            AlertDialog.BUTTON_POSITIVE
                    ).setOnClickListener(
                            view -> {
                                String emailText =
                                        email.getText()
                                                .toString()
                                                .trim();

                                String passwordText =
                                        password.getText()
                                                .toString();

                                if (emailText.isEmpty()) {
                                    email.setError(
                                            "E-Mail fehlt"
                                    );
                                    return;
                                }

                                if (passwordText.isEmpty()) {
                                    password.setError(
                                            "Passwort fehlt"
                                    );
                                    return;
                                }

                                store.saveCredentials(
                                        emailText,
                                        passwordText
                                );

                                CaminoTrackingService
                                        .requestLibreRefresh(
                                                this
                                        );

                                dialog.dismiss();
                            }
                    );
                }
        );

        dialog.show();
    }



    @Override
    protected void onStart() {
        super.onStart();
        mapView.onStart();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mapView.onResume();
        orientationController.start();
        caminoController.startLivePosition();
    }

    @Override
    protected void onPause() {
        caminoController.stopLivePosition();
        orientationController.stop();
        mapView.onPause();
        super.onPause();
    }

    @Override
    protected void onStop() {
        mapView.onStop();
        super.onStop();
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        orientationController.onLocationPermissionResult(
                requestCode, permissions, grantResults
        );
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        mapView.onLowMemory();
    }

    @Override
    protected void onDestroy() {
        mapCoordinator.destroy();
        mapView.onDestroy();
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        mapView.onSaveInstanceState(outState);
    }

}
