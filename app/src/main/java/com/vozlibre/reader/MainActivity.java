package com.vozlibre.reader;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.SystemClock;
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

import java.io.File;
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
    private TextView lblRate, lblPitch, lblDelay, lblVib, lblSound, txtStatus, txtTouch;

    /** Lista de motores TTS instalados: etiqueta + paquete. */
    private final List<String> engineLabels = new ArrayList<>();
    private final List<String> enginePackages = new ArrayList<>();
    private String chosenEngine = "";   // "" = motor del sistema

    // ---- actualizaciones ----
    private static final long CHECK_INTERVAL_MS = 10 * 60 * 1000; // 10 min entre consultas
    private static long lastCheckAt = 0;
    private DownloadManager downloadManager;
    private long pendingDlId = -1;
    private Uri pendingInstallUri = null;

    /** Aviso del sistema: la descarga del APK terminó. */
    private final BroadcastReceiver downloadDoneReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
            if (id == -1 || id != pendingDlId) return;
            checkDownloadResult(id);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        txtStatus = findViewById(R.id.txtStatus);
        txtTouch = findViewById(R.id.txtTouch);
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
        Button btnTouchFix = findViewById(R.id.btnTouchFix);

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
        btnTouchFix.setOnClickListener(v -> requestTouchGrant());

        // Permiso de notificaciones en Android 13+ (la notificación es el
        // mando a distancia del lector).
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"},
                    REQ_NOTIFS);
        }

        loadEngineList();

        // Descargas de actualizaciones: el sistema avisa al terminar.
        downloadManager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
        try {
            registerReceiver(downloadDoneReceiver,
                    new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
        resumeUpdateFlow();
    }

    @Override
    protected void onDestroy() {
        try {
            unregisterReceiver(downloadDoneReceiver);
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------------ estado

    private void refreshStatus() {
        boolean enabled = isServiceEnabled();
        txtStatus.setText(enabled ? R.string.status_active : R.string.status_inactive);
        // Colores en crudo: NUNCA getResource/colorRes (evita el crash antiguo).
        txtStatus.setTextColor(enabled ? 0xFF15803D : 0xFFB91C1C);
        if (txtTouch != null) {
            AccessibilityManager am = (AccessibilityManager)
                    getSystemService(Context.ACCESSIBILITY_SERVICE);
            boolean te = am != null && am.isTouchExplorationEnabled();
            txtTouch.setText(te ? R.string.status_touch_on : R.string.status_touch_off);
            txtTouch.setTextColor(te ? 0xFF15803D : 0xFFB45309);
        }
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

    /**
     * Lista TODOS los motores TTS instalados (del sistema e instalados por
     * el usuario). getEngines() consulta el gestor de paquetes: NO necesita
     * que el motor se inicialice, así que se llama en el acto. Así la lista
     * sale completa aunque el motor actual esté fallando.
     */
    private void loadEngineList() {
        engineLabels.clear();
        enginePackages.clear();
        engineLabels.add(getString(R.string.engine_system));
        enginePackages.add("");

        TextToSpeech probe = null;
        try {
            probe = new TextToSpeech(getApplicationContext(), null);
            String systemEngine = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.TTS_DEFAULT_SYNTH);
            List<TextToSpeech.EngineInfo> engines = probe.getEngines();
            if (engines != null) {
                for (TextToSpeech.EngineInfo e : engines) {
                    if (e == null || e.name == null) continue;
                    String label = e.label != null && !e.label.trim().isEmpty()
                            ? e.label : e.name;
                    if (e.name.equals(systemEngine)) {
                        label += " " + getString(R.string.engine_system_default_suffix);
                    }
                    engineLabels.add(label);
                    enginePackages.add(e.name);
                }
            }
        } catch (Exception ignored) {
            // Si ni eso funciona, al menos queda «Motor del sistema».
        } finally {
            if (probe != null) {
                try { probe.shutdown(); } catch (Exception ignored) { }
            }
        }

        ArrayAdapter<String> ad = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, engineLabels);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spnEngine.setAdapter(ad);
        // Seleccionar el motor guardado (o el del sistema)
        int sel = Math.max(0, enginePackages.indexOf(chosenEngine));
        spnEngine.setSelection(sel);

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

    // ----------------------------------------------------------------- exploración táctil

    /**
     * Pide al sistema el permiso de exploración táctil (el aviso
     * «¿Usar VozLibre con la exploración táctil?»). Sin ese permiso, tocar
     * un elemento lo PULSA en vez de anunciarlo y los deslizamientos no
     * navegan: es lo que pasa si el aviso se rechazó o no llegó a verse.
     */
    private void requestTouchGrant() {
        ReaderService svc = ReaderService.instance;
        if (svc == null) {
            Toast.makeText(this, R.string.touch_needs_service, Toast.LENGTH_LONG).show();
            try {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            } catch (Exception ignored) { }
            return;
        }
        svc.applySettings();
        svc.requestTouchExplorationGrant();
        Toast.makeText(this, R.string.touch_requested, Toast.LENGTH_LONG).show();
    }

    // ----------------------------------------------------------------- actualizaciones

    private int installedVersionCode() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Flujo de actualización al entrar a la app:
     * 1) si Android ya nos dio permiso de instalación, continúa donde quedó;
     * 2) revisa si hay descarga terminada de una vez anterior (la app pudo
     *    cerrarse mientras bajaba);
     * 3) consulta updates.json y muestra el diálogo si hay versión nueva.
     */
    private void resumeUpdateFlow() {
        // 1) el usuario volvió de conceder el permiso de instalación
        if (pendingInstallUri != null
                && (Build.VERSION.SDK_INT < 26
                    || getPackageManager().canRequestPackageInstalls())) {
            Uri uri = pendingInstallUri;
            pendingInstallUri = null;
            promptInstall(uri);
        }

        // 2) descargas quedadas de sesiones anteriores
        long saved = Prefs.get(this).getLong(Prefs.KEY_PENDING_DL, -1);
        if (saved >= 0 && downloadManager != null) {
            Cursor c = null;
            try {
                c = downloadManager.query(new DownloadManager.Query().setFilterById(saved));
                if (c != null && c.moveToFirst()) {
                    int status = c.getInt(
                            c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        Prefs.get(this).edit().putLong(Prefs.KEY_PENDING_DL, -1).apply();
                        pendingDlId = -1;
                        Uri uri = downloadManager.getUriForDownloadedFile(saved);
                        if (uri != null) promptInstall(uri);
                    } else if (status == DownloadManager.STATUS_FAILED) {
                        Prefs.get(this).edit().putLong(Prefs.KEY_PENDING_DL, -1).apply();
                        pendingDlId = -1;
                        Toast.makeText(this, R.string.upd_download_failed,
                                Toast.LENGTH_LONG).show();
                    }
                    // en curso: se espera el aviso del sistema
                }
            } catch (Exception ignored) {
            } finally {
                if (c != null) c.close();
            }
        }

        // 3) ¿hay versión nueva? (aviso desde la notificación = consulta forzada)
        boolean forced = getIntent() != null
                && getIntent().getBooleanExtra("show_update", false);
        long now = SystemClock.elapsedRealtime();
        if (forced || now - lastCheckAt > CHECK_INTERVAL_MS) {
            lastCheckAt = now;
            UpdateChecker.checkAsync(this, info -> {
                if (isFinishing() || isDestroyed()) return;
                if (info != null && info.versionCode > installedVersionCode()) {
                    showUpdateDialog(info);
                }
            });
        }
    }

    /** Diálogo «Actualización disponible»: nombre, tamaño y novedades. */
    private void showUpdateDialog(UpdateChecker.UpdateInfo info) {
        String sizeTxt = info.sizeBytes > 0
                ? String.format(Locale.getDefault(), "%.1f MB", info.sizeBytes / 1048576f)
                : "—";
        StringBuilder msg = new StringBuilder();
        msg.append("Versión ").append(info.versionName)
           .append(" (código ").append(info.versionCode).append(")\n");
        msg.append("Tamaño: ").append(sizeTxt).append("\n\n");
        if (info.description != null && !info.description.trim().isEmpty()) {
            msg.append(info.description.trim());
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.upd_dialog_title)
                .setMessage(msg.toString())
                .setPositiveButton(R.string.btn_download,
                        (d, w) -> startDownload(info))
                .setNegativeButton(R.string.btn_later, null)
                .show();
    }

    /** Descarga el APK con DownloadManager a Descargas/VozLibre/. */
    private void startDownload(UpdateChecker.UpdateInfo info) {
        if (downloadManager == null) {
            Toast.makeText(this, R.string.upd_download_failed, Toast.LENGTH_LONG).show();
            return;
        }
        try {
            String fileName = "VozLibre-" + info.versionName
                    + "-code" + info.versionCode + ".apk";
            // la carpeta debe existir antes de encolar
            File dstDir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), "VozLibre");
            dstDir.mkdirs();

            DownloadManager.Request req =
                    new DownloadManager.Request(Uri.parse(info.apkUrl));
            req.setTitle(getString(R.string.app_name) + " " + info.versionName);
            req.setDescription(getString(R.string.upd_dm_desc));
            req.setNotificationVisibility(DownloadManager.Request
                    .VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setMimeType("application/vnd.android.package-archive");
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,
                    "VozLibre/" + fileName);

            pendingDlId = downloadManager.enqueue(req);
            Prefs.get(this).edit().putLong(Prefs.KEY_PENDING_DL, pendingDlId).apply();
            Toast.makeText(this, R.string.upd_downloading, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, R.string.upd_download_failed, Toast.LENGTH_LONG).show();
        }
    }

    /** Al terminar la descarga: ofrecer la instalación. */
    private void checkDownloadResult(long id) {
        int status = -1;
        Cursor c = null;
        try {
            c = downloadManager.query(new DownloadManager.Query().setFilterById(id));
            if (c != null && c.moveToFirst()) {
                status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        Prefs.get(this).edit().putLong(Prefs.KEY_PENDING_DL, -1).apply();
        pendingDlId = -1;

        if (status == DownloadManager.STATUS_SUCCESSFUL) {
            Uri uri = downloadManager.getUriForDownloadedFile(id);
            if (uri != null) {
                promptInstall(uri);
            } else {
                Toast.makeText(this, R.string.upd_download_failed,
                        Toast.LENGTH_LONG).show();
            }
        } else if (status == DownloadManager.STATUS_FAILED) {
            Toast.makeText(this, R.string.upd_download_failed, Toast.LENGTH_LONG).show();
        }
    }

    /** Confirma la instalación y pide el permiso de apps desconocidas si falta. */
    private void promptInstall(Uri apkUri) {
        if (Build.VERSION.SDK_INT >= 26
                && !getPackageManager().canRequestPackageInstalls()) {
            pendingInstallUri = apkUri;
            Toast.makeText(this, R.string.upd_need_install_perm, Toast.LENGTH_LONG).show();
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                try {
                    startActivity(new Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES));
                } catch (Exception ignored) { }
            }
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.upd_dialog_title)
                .setMessage(R.string.upd_installed_prompt)
                .setPositiveButton(R.string.btn_install, (d, w) -> doInstall(apkUri))
                .setNegativeButton(R.string.btn_later, null)
                .show();
    }

    private void doInstall(Uri apkUri) {
        try {
            Intent i = new Intent(Intent.ACTION_INSTALL_PACKAGE);
            i.setDataAndType(apkUri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, R.string.upd_open_error, Toast.LENGTH_LONG).show();
        }
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
