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
import android.speech.tts.TextToSpeech;
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
 * Pantalla principal de VozLibre: descripción, estado del lector, ajustes de
 * voz (motor, ritmo, tono), vibración y sonido (fuerza y volumen),
 * exploración táctil, diálogo "Acerca de" y enlace al código fuente.
 */
public class MainActivity extends Activity {

    private static final int REQ_NOTIFS = 41;

    private Spinner spnEngine;
    private SeekBar seekRate, seekPitch, seekDelay, seekVib, seekSound;
    private Switch swVibrate, swSound, swTouch;
    private TextView lblRate, lblPitch, lblDelay, lblVib, lblSound, txtStatus;

    /** Lista de motores TTS instalados: etiqueta + paquete. */
    private final List<String> engineLabels = new ArrayList<>();
    private final List<String> enginePackages = new ArrayList<>();
    private String chosenEngine = "";   // "" = motor del sistema
    private TextToSpeech probeTts;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        txtStatus = findViewById(R.id.txtStatus);
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
        Button btnDemo = findViewById(R.id.btnDemo);
        Button btnApply = findViewById(R.id.btnApply);
        Button btnAbout = findViewById(R.id.btnAbout);
        Button btnSource = findViewById(R.id.btnSource);

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

        btnAbout.setOnClickListener(v -> showAboutDialog());
        btnSource.setOnClickListener(v -> openSourceCode());

        // Permiso de notificaciones en Android 13+ (la notificación es el
        // mando a distancia del lector).
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"},
                    REQ_NOTIFS);
        }

        loadEngineList();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    @Override
    protected void onDestroy() {
        if (probeTts != null) {
            try { probeTts.shutdown(); } catch (Exception ignored) { }
            probeTts = null;
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------------ estado

    private void refreshStatus() {
        boolean enabled = isServiceEnabled();
        txtStatus.setText(enabled ? R.string.status_active : R.string.status_inactive);
        // Colores en crudo: NUNCA getResource/colorRes (evita el crash antiguo).
        txtStatus.setTextColor(enabled ? 0xFF15803D : 0xFFB91C1C);
    }

    private boolean isServiceEnabled() {
        if (ReaderService.instance != null) return true;
        AccessibilityManager am =
                (AccessibilityManager) getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (am == null) return false;
        List<AccessibilityServiceInfo> list =
                am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_SPOKEN);
        if (list == null) return false;
        for (AccessibilityServiceInfo info : list) {
            String id = info.getId();
            if (id != null && id.startsWith(getPackageName())) return true;
        }
        return false;
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

    /** Lista los motores TTS instalados; el motor del sistema va primero. */
    private void loadEngineList() {
        engineLabels.clear();
        enginePackages.clear();
        engineLabels.add(getString(R.string.engine_system));
        enginePackages.add("");

        probeTts = new TextToSpeech(getApplicationContext(), status -> {
            if (status != TextToSpeech.SUCCESS || probeTts == null) return;
            String systemEngine = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.TTS_DEFAULT_SYNTH);
            List<TextToSpeech.EngineInfo> engines = probeTts.getEngines();
            List<String> labels = new ArrayList<>();
            List<String> pkgs = new ArrayList<>();
            if (engines != null) {
                for (TextToSpeech.EngineInfo e : engines) {
                    String label = e.label != null && !e.label.trim().isEmpty()
                            ? e.label : e.name;
                    if (e.name.equals(systemEngine)) {
                        label += " " + getString(R.string.engine_system_default_suffix);
                    }
                    labels.add(label);
                    pkgs.add(e.name);
                }
            }
            final List<String> fLabels = labels, fPkgs = pkgs;
            runOnUiThread(() -> {
                engineLabels.addAll(fLabels);
                enginePackages.addAll(fPkgs);
                ArrayAdapter<String> ad = new ArrayAdapter<>(MainActivity.this,
                        android.R.layout.simple_spinner_item, engineLabels);
                ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                spnEngine.setAdapter(ad);
                // Seleccionar el motor guardado (o el del sistema)
                int sel = Math.max(0, enginePackages.indexOf(chosenEngine));
                spnEngine.setSelection(sel);
            });
        });

        spnEngine.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                if (pos >= 0 && pos < enginePackages.size()) {
                    chosenEngine = enginePackages.get(pos);
                }
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) { }
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
