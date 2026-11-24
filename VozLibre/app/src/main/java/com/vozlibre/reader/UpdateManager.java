package com.vozlibre.reader;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Comprobación, aviso e instalación de actualizaciones de VozLibre.
 *
 *  1. checkAsync(): descarga update.json del repositorio (el código y el
 *     APK viven en GitHub: nada pasa por servidores privados) y lo compara
 *     con la versión instalada.
 *  2. notifyUpdateAvailable(): notificación propia con el número de versión.
 *  3. showUpdateDialog(): diálogo con las novedades; «Descargar» baja el APK
 *     y «Instalar ahora» lo entrega al instalador del sistema via
 *     ApkProvider (el usuario SIEMPRE confirma la instalación).
 *
 * Toda la red va en hilos aparte: la pantalla y la voz nunca se bloquean.
 */
public final class UpdateManager {

    private static final String CHANNEL_ID = "vozlibre_updates";
    private static final int NOTIF_ID = 9;
    private static final String PREFS = "vozlibre";
    private static final String KEY_SKIPPED = "update_skipped_code";

    /** Datos de una versión nueva encontrada. */
    public static final class Info {
        public final String versionName;
        public final int versionCode;
        public final long sizeBytes;
        public final String notes;
        public final String apkUrl;

        Info(String versionName, int versionCode, long sizeBytes,
             String notes, String apkUrl) {
            this.versionName = versionName;
            this.versionCode = versionCode;
            this.sizeBytes = sizeBytes;
            this.notes = notes;
            this.apkUrl = apkUrl;
        }
    }

    /** Respuesta de la comprobación. */
    public interface CheckCallback {
        void onResult(Info info);      // null = no hay versión más nueva
        void onError(String message);
    }

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean checking = new AtomicBoolean(false);

    private UpdateManager() { }

    // ------------------------------------------------------------------ comprobar

    /**
     * Comprueba en segundo plano si hay una versión más nueva. Termina
     * llamando al callback SIEMPRE en el hilo principal. Nunca lanza.
     */
    public static void checkAsync(final Context ctx, final CheckCallback cb) {
        if (ctx == null || cb == null) return;
        if (!checking.compareAndSet(false, true)) return; // ya hay una en marcha
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            Info info = null;
            String error = null;
            HttpURLConnection c = null;
            try {
                String spec = app.getString(R.string.updates_url);
                c = (HttpURLConnection) new URL(spec).openConnection();
                c.setConnectTimeout(9000);
                c.setReadTimeout(12000);
                c.setRequestProperty("Cache-Control", "no-cache");
                int code = c.getResponseCode();
                if (code != HttpURLConnection.HTTP_OK) {
                    throw new IllegalStateException("HTTP " + code);
                }
                InputStream in = c.getInputStream();
                java.util.Scanner sc = new java.util.Scanner(in, "UTF-8")
                        .useDelimiter("\\A");
                String body = sc.hasNext() ? sc.next() : "";
                sc.close();
                JSONObject o = new JSONObject(body);
                int remoteCode = o.optInt("versionCode", 0);
                String vname = o.optString("versionName", "");
                String apk = o.optString("apkUrl", "");
                long size = o.optLong("sizeBytes", 0L);
                String notes = o.optString("notes", "");
                int installed = installedVersionCode(app);
                if (remoteCode > installed && !vname.isEmpty() && !apk.isEmpty()) {
                    info = new Info(vname, remoteCode, size, notes, apk);
                } else {
                    info = null;
                }
            } catch (Exception e) {
                error = String.valueOf(e.getMessage());
            } finally {
                if (c != null) {
                    try { c.disconnect(); } catch (Exception ignored) { }
                }
            }
            final Info fInfo = info;
            final String fError = error;
            checking.set(false);
            main.post(() -> {
                if (fError != null) cb.onError(fError);
                else cb.onResult(fInfo);
            });
        }, "vozlibre-update-check").start();
    }

    /** versionCode de la app instalada (0 si no se puede leer). */
    public static int installedVersionCode(Context ctx) {
        try {
            PackageInfo pi = ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0);
            return pi.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ avisar

    /** Notificación propia: «Nueva versión de VozLibre disponible». */
    public static void notifyUpdateAvailable(Context ctx, Info info) {
        if (ctx == null || info == null) return;
        try {
            final Context app = ctx.getApplicationContext();
            NotificationManager nm = (NotificationManager)
                    app.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                        app.getString(R.string.update_channel),
                        NotificationManager.IMPORTANCE_DEFAULT);
                nm.createNotificationChannel(ch);
            }
            Intent open = new Intent(app, MainActivity.class);
            open.putExtra("show_update", true);
            open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            android.app.PendingIntent pi = android.app.PendingIntent
                    .getActivity(app, 31, open,
                            android.app.PendingIntent.FLAG_UPDATE_CURRENT
                                    | android.app.PendingIntent.FLAG_IMMUTABLE);
            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(app, CHANNEL_ID)
                    : new Notification.Builder(app);
            b.setSmallIcon(R.drawable.ic_launcher)
                    .setContentTitle(app.getString(R.string.notif_update_title))
                    .setContentText(app.getString(R.string.notif_update_text,
                            info.versionName))
                    .setContentIntent(pi)
                    .setAutoCancel(true);
            nm.notify(NOTIF_ID, b.build());
        } catch (Exception ignored) { }
    }

    /** Diálogo con las novedades y el botón de descarga. */
    public static void showUpdateDialog(final Activity act, final Info info) {
        if (act == null || act.isFinishing() || info == null) return;
        try {
            DialogInterface.OnClickListener later = (d, w) ->
                    markSkipped(act, info.versionCode);
            String size = info.sizeBytes > 0
                    ? humanBytes(info.sizeBytes) : "—";
            StringBuilder msg = new StringBuilder();
            msg.append(act.getString(R.string.dlg_update_size, size));
            if (info.notes != null && !info.notes.trim().isEmpty()) {
                msg.append("\n\n").append(act.getString(
                        R.string.dlg_update_notes_label))
                        .append("\n").append(info.notes);
            }
            new AlertDialog.Builder(act)
                    .setTitle(act.getString(R.string.dlg_update_title,
                            info.versionName))
                    .setMessage(msg)
                    .setPositiveButton(R.string.btn_download,
                            (d, w) -> downloadAndInstall(act, info))
                    .setNeutralButton(R.string.btn_later, later)
                    .show();
        } catch (Exception ignored) { }
    }

    /** Recuerda que el usuario pospuso ESTA versión (no volver a molestar). */
    private static void markSkipped(Context ctx, int versionCode) {
        try {
            SharedPreferences p = ctx.getApplicationContext()
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            p.edit().putInt(KEY_SKIPPED, versionCode).apply();
        } catch (Exception ignored) { }
    }

    private static boolean wasSkipped(Context ctx, int versionCode) {
        try {
            SharedPreferences p = ctx.getApplicationContext()
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            return p.getInt(KEY_SKIPPED, -1) == versionCode;
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ descargar e instalar

    /**
     * Descarga el APK (diálogo de progreso no bloqueante: Toast + hilos) y
     * al terminar ofrece instalarlo. Todo en segundo plano; la pantalla
     * sigue viva y la voz sigue hablando.
     */
    private static void downloadAndInstall(final Activity act, final Info info) {
        final Context app = act.getApplicationContext();
        Toast.makeText(app, R.string.downloading, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            File out = null;
            HttpURLConnection c = null;
            String error = null;
            try {
                URL u = new URL(info.apkUrl);
                c = (HttpURLConnection) u.openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(60000);
                if (c.getResponseCode() != HttpURLConnection.HTTP_OK) {
                    throw new IllegalStateException(
                            "HTTP " + c.getResponseCode());
                }
                File dir = ApkProvider.sharedDir(app);
                // Limpiar descargas anteriores (solo la última importa).
                File[] old = dir.listFiles();
                if (old != null) {
                    for (File f : old) {
                        try { f.delete(); } catch (Exception ignored) { }
                    }
                }
                out = new File(dir, "vozlibre-update.apk");
                InputStream in = c.getInputStream();
                FileOutputStream fo = new FileOutputStream(out);
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) {
                    fo.write(buf, 0, n);
                }
                fo.flush();
                fo.close();
                in.close();
            } catch (Exception e) {
                error = e.getMessage();
                if (out != null) {
                    try { out.delete(); } catch (Exception ignored) { }
                    out = null;
                }
            } finally {
                if (c != null) {
                    try { c.disconnect(); } catch (Exception ignored) { }
                }
            }
            final File apk = out;
            final String fError = error;
            main.post(() -> {
                if (act.isFinishing()) return;
                if (apk == null) {
                    Toast.makeText(act, R.string.download_error,
                            Toast.LENGTH_LONG).show();
                    return;
                }
                offerInstall(act, apk);
            });
        }, "vozlibre-download").start();
    }

    /** Descarga completa: diálogo «Instalar ahora». */
    private static void offerInstall(final Activity act, final File apk) {
        try {
            new AlertDialog.Builder(act)
                    .setTitle(R.string.dlg_download_done_title)
                    .setMessage(act.getString(R.string.download_saved,
                            apk.getAbsolutePath()))
                    .setPositiveButton(R.string.btn_install_now,
                            (d, w) -> installApk(act, apk))
                    .setNegativeButton(R.string.btn_later, null)
                    .show();
        } catch (Exception ignored) { }
    }

    /** Entrega el APK al instalador del sistema (con confirmación del usuario). */
    private static void installApk(Activity act, File apk) {
        try {
            Uri uri = ApkProvider.uriFor(apk);
            Intent i = new Intent(Intent.ACTION_INSTALL_PACKAGE);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            i.putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true);
            i.putExtra(Intent.EXTRA_RETURN_RESULT, true);
            act.startActivity(i);
        } catch (Exception e) {
            // Falta el permiso «Instalar apps desconocidas» u otro problema.
            Toast.makeText(act, R.string.install_blocked,
                    Toast.LENGTH_LONG).show();
        }
    }

    /** 286836 -> «280,4 KB» para el diálogo. */
    public static String humanBytes(long bytes) {
        if (bytes <= 0) return "0 B";
        double kb = bytes / 1024.0;
        if (kb < 1000) {
            return String.format(java.util.Locale.getDefault(),
                    "%.1f KB", kb);
        }
        return String.format(java.util.Locale.getDefault(),
                "%.1f MB", kb / 1024.0);
    }
}
