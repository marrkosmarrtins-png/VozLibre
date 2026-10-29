package com.vozlibre.reader;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.accessibility.AccessibilityManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pantalla principal de VozLibre: descripción, estado del lector y de la
 * exploración táctil, ajustes de voz (motor —TODOS los instalados—, ritmo,
 * tono), vibración y sonido, búsqueda de actualizaciones, diálogo «Acerca
 * de» y enlace al código fuente.
 */
public class MainActivity extends Activity {

    private static final int REQ_NOTIFS = 41;

    private Spinner spnEngine;
    private SeekBar seekRate, seekPitch, seekDelay, seekVib, seekSound;
    private Switch swVibrate, swSound, swTouch;
    private TextView lblRate, lblPitch, lblDelay, lblVib, lblSound, txtStatus,
            txtTouch, txtEngines;
    private Button btnTouchFix;

    /** Lista de motores TTS instalados: etiqueta + paquete. */
    private final List<String> engineLabels = new ArrayList<>();
    private final List<String> enginePackages = new ArrayList<>();
    private String chosenEngine = "";   // "" = motor del sistema
    private boolean updateDialogShown = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        txtStatus = findViewById(R.id.txtStatus);
        txtTouch = findViewById(R.id.txtTouch);
        txtEngines = findViewById(R.id.txtEngines);
        spnEngine = findViewById(R.id.spnEngine);
        seekRate = findViewById(R.id.seekRate);
        seekPitch = findViewById(R.id.seekPitch);
        seekDelay = findViewById(R.id.seekDelay);
        seekVib = findViewById(R.id.seekVib);
        seekSound = findViewById(R.id.seekSound);
        swVibrate = findViewById(R.id.swVibrate);
        swSound = findViewById(R.id.swSound);
        swTouch = findViewById(R.id.swTouch);
        lblRate = findViewById(R.id.lblRate);
        lblPitch = findViewById(R.id.lblPitch);
        lblDelay = findViewById(R.id.lblDelay);
        lblVib = findViewById(R.id.lblVib);
        lblSound = findViewById(R.id.lblSound);
        Button btnActivate = findViewById(R.id.btnActivate);
        btnTouchFix = findViewById(R.id.btnTouchFix);
        Button btnDemo = findViewById(R.id.btnDemo);
        Button btnApply = findViewById(R.id.btnApply);
        Button btnAbout = findViewById(R.id.btnAbout);
        Button btnSource = findViewById(R.id.btnSource);
        Button btnRescan = findViewById(R.id.btnRescanEngines);
        Button btnUpdates = findViewById(R.id.btnCheckUpdates);

        // Valores guardados
        SharedPreferences p = Prefs.get(this);
        seekRate.setProgress(Math.round((p.getFloat(Prefs.KEY_RATE, 1.0f) - 0.5f) * 100f));
        seekPitch.setProgress(Math.round((p.getFloat(Prefs.KEY_PITCH, 1.0f) - 0.5f) * 100f));
        seekDelay.setProgress((p.getInt(Prefs.KEY_DELAY, 900) - 300) / 100);
        seekVib.setProgress(p.getInt(Prefs.KEY_VIB_STRENGTH, 60));
        seekSound.setProgress(p.getInt(Prefs.KEY_SOUND_VOL, 70));
        swVibrate.setChecked(p.getBoolean(Prefs.KEY_VIB_ON, true));
        swSound.setChecked(p.getBoolean(Prefs.KEY_SOUND_ON, true));
        swTouch.setChecked(p.getBoolean(Prefs.KEY_TOUCH, true));
        chosenEngine = p.getString(Prefs.KEY_ENGINE, "");

        updateLabels();
        SeekBar.OnSeekBarChangeListener relabel = new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                updateLabels();
            }
            @Override
            public void onStartTrackingTouch(SeekBar s) { }
            @Override
            public void onStopTrackingTouch(SeekBar s) { }
        };
        seekRate.setOnSeekBarChangeListener(relabel);
        seekPitch.setOnSeekBarChangeListener(relabel);
        seekDelay.setOnSeekBarChangeListener(relabel);
        seekVib.setOnSeekBarChangeListener(relabel);
        seekSound.setOnSeekBarChangeListener(relabel);

        btnActivate.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        // Reparación de la exploración táctil: si el usuario rechazó el
        // diálogo del sistema, hay que volver a pedirlo (y, si el sistema no
        // lo vuelve a mostrar, indicar cómo desactivar/reactivar el lector).
        btnTouchFix.setOnClickListener(v -> fixTouchExploration());

        btnApply.setOnClickListener(v -> applyChanges());

        btnDemo.setOnClickListener(v -> {
            ReaderService svc = ReaderService.instance;
            if (svc != null) {
                svc.applySettings();
                svc.testVoice();
            } else {
                Toast.makeText(this, R.string.demo_needs_service, Toast.LENGTH_LONG).show();
            }
        });

        btnAbout.setOnClickListener(v -> {
            Earcons.get(this).play(Earcons.ALERT, 0.8f);
            showAboutDialog();
        });
        btnSource.setOnClickListener(v -> openSourceCode());
        btnRescan.setOnClickListener(v -> {
            Toast.makeText(this, R.string.engine_scanning, Toast.LENGTH_SHORT).show();
            loadEngineList();
        });
        btnUpdates.setOnClickListener(v -> checkUpdates(true));

        spnEngine.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view,
                                       int pos, long id) {
                if (pos >= 0 && pos < enginePackages.size()) {
                    chosenEngine = enginePackages.get(pos);
                }
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) { }
        });

        // Permiso de notificaciones en Android 13+ (notificación-mando y
        // avisos de nuevas versiones).
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                   != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"},
                    REQ_NOTIFS);
        }

        loadEngineList();

        // Si se abrió desde la notificación de actualización, mostrar el diálogo.
        if (getIntent() != null
                && getIntent().getBooleanExtra("show_update", false)) {
            getIntent().removeExtra("show_update");
            checkUpdates(true);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
        // Al entrar al lector: comprobar versión nueva y avisar.
        checkUpdates(false);
    }

    // ------------------------------------------------------------------ estado

    private void refreshStatus() {
        boolean enabled = isServiceEnabled();
        txtStatus.setText(enabled ? R.string.status_active : R.string.status_inactive);
        // Colores en crudo: NUNCA getResource/colorRes (evita el crash antiguo).
        txtStatus.setTextColor(enabled ? 0xFF15803D : 0xFFB91C1C);

        AccessibilityManager am =
                (AccessibilityManager) getSystemService(Context.ACCESSIBILITY_SERVICE);
        boolean te = am != null && am.isTouchExplorationEnabled();
        txtTouch.setText(te ? R.string.status_touch_on : R.string.status_touch_off);
        txtTouch.setTextColor(te ? 0xFF15803D : 0xFFB45309);
        btnTouchFix.setVisibility(te ? View.GONE : View.VISIBLE);
    }

    private boolean isServiceEnabled() {
        if (ReaderService.instance != null) return true;
        AccessibilityManager am =
                (AccessibilityManager) getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (am == null) return false;
        List<AccessibilityServiceInfo> list =
                am.getEnabledAccessibilityServiceList(
                        AccessibilityServiceInfo.FEEDBACK_SPOKEN);
        if (list == null) return false;
        for (AccessibilityServiceInfo info : list) {
            String id = info.getId();
            if (id != null && id.startsWith(getPackageName())) return true;
        }
        return false;
    }

    /** Reintenta el permiso de exploración táctil y guía con instrucciones. */
    private void fixTouchExploration() {
        ReaderService svc = ReaderService.instance;
        if (svc != null) {
            svc.requestTouchExplorationGrant();
        } else {
            Toast.makeText(this, R.string.touch_needs_service,
                    Toast.LENGTH_LONG).show();
        }
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------------ etiquetas

    private void updateLabels() {
        float rate = 0.5f + seekRate.getProgress() / 100f;      // 0.5 .. 2.0
        float pitch = 0.5f + seekPitch.getProgress() / 100f;    // 0.5 .. 1.5
        int delay = 300 + seekDelay.getProgress() * 100;        // 300 .. 2000 ms
        lblRate.setText(String.format(Locale.getDefault(),
                "%s: %.1fx", getString(R.string.lbl_rate), rate));
        lblPitch.setText(String.format(Locale.getDefault(),
                "%s: %.1fx", getString(R.string.lbl_pitch), pitch));
        lblDelay.setText(String.format(Locale.getDefault(),
                "%s: %d ms", getString(R.string.lbl_delay), delay));
        lblVib.setText(String.format(Locale.getDefault(),
                "%s: %d %%", getString(R.string.lbl_vib), seekVib.getProgress()));
        lblSound.setText(String.format(Locale.getDefault(),
                "%s: %d %%", getString(R.string.lbl_sound), seekSound.getProgress()));
    }

    // ------------------------------------------------------------------ motores de voz

    /**
     * Lista TODOS los motores TTS instalados (del sistema y añadidos por el
     * usuario) con TtsScanner, sin depender de que ningún motor inicialice.
     */
    private void loadEngineList() {
        txtEngines.setText(R.string.engine_scanning);
        final Context app = getApplicationContext();
        new Thread(() -> {
            final List<TtsScanner.Info> found = TtsScanner.list(app);
            runOnUiThread(() -> {
                engineLabels.clear();
                enginePackages.clear();
                engineLabels.add(getString(R.string.engine_system));
                enginePackages.add("");
                for (TtsScanner.Info e : found) {
                    engineLabels.add(e.label);
                    enginePackages.add(e.pkg);
                }
                ArrayAdapter<String> ad = new ArrayAdapter<>(MainActivity.this,
                        android.R.layout.simple_spinner_item, engineLabels);
                ad.setDropDownViewResource(
                        android.R.layout.simple_spinner_dropdown_item);
                spnEngine.setAdapter(ad);
                int sel = Math.max(0, enginePackages.indexOf(chosenEngine));
                spnEngine.setSelection(Math.min(sel, enginePackages.size() - 1));
                txtEngines.setText(getString(R.string.engines_found,
                        enginePackages.size() - 1));
            });
        }).start();
    }

    // ------------------------------------------------------------------ actualizaciones

    /**
     * Comprueba si hay versión nueva. Con manual=true informa siempre
     * (diálogo, o avisos de «no hay» / «sin conexión»); automática solo
     * abre el diálogo cuando de verdad hay una versión más nueva.
     */
    private void checkUpdates(boolean manual) {
        if (manual) {
            Toast.makeText(this, R.string.update_checking, Toast.LENGTH_SHORT).show();
        }
        UpdateManager.checkAsync(this, new UpdateManager.CheckCallback() {
            @Override
            public void onResult(UpdateManager.Info info) {
                if (info == null) {
                    if (manual) {
                        Toast.makeText(MainActivity.this, R.string.update_none,
                                Toast.LENGTH_SHORT).show();
                    }
                    return;
                }
                UpdateManager.notifyUpdateAvailable(MainActivity.this, info);
                if (!updateDialogShown || manual) {
                    updateDialogShown = true;
                    UpdateManager.showUpdateDialog(MainActivity.this, info);
                }
            }

            @Override
            public void onError(String message) {
                if (manual) {
                    Toast.makeText(MainActivity.this, R.string.update_error,
                            Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    // ------------------------------------------------------------------ aplicar cambios

    private void applyChanges() {
        float rate = 0.5f + seekRate.getProgress() / 100f;
        float pitch = 0.5f + seekPitch.getProgress() / 100f;
        int delay = 300 + seekDelay.getProgress() * 100;

        Prefs.get(this).edit()
                .putFloat(Prefs.KEY_RATE, rate)
                .putFloat(Prefs.KEY_PITCH, pitch)
                .putInt(Prefs.KEY_DELAY, delay)
                .putBoolean(Prefs.KEY_TOUCH, swTouch.isChecked())
                .putString(Prefs.KEY_ENGINE, chosenEngine)
                .putBoolean(Prefs.KEY_VIB_ON, swVibrate.isChecked())
                .putInt(Prefs.KEY_VIB_STRENGTH, seekVib.getProgress())
                .putBoolean(Prefs.KEY_SOUND_ON, swSound.isChecked())
                .putInt(Prefs.KEY_SOUND_VOL, seekSound.getProgress())
                .apply();

        ReaderService svc = ReaderService.instance;
        if (svc != null) svc.applySettings();

        Toast.makeText(this, R.string.applied_ok, Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------ acerca de / código fuente

    private void showAboutDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.about_title)
                .setMessage(R.string.about_message)
                .setPositiveButton(R.string.btn_source, (d, w) -> openSourceCode())
                .setNegativeButton(R.string.dialog_close, null)
                .show();
    }

    private void openSourceCode() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse(getString(R.string.repo_url))));
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.repo_url), Toast.LENGTH_LONG).show();
        }
    }
}
