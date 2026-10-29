package com.vozlibre.reader;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Sistema de actualizaciones de VozLibre.
 *
 * 1. Comprueba un pequeño archivo update.json publicado junto al código
 *    en el repositorio (rama principal). Si hay una versión más nueva:
 * 2. Muestra una notificación «VozLibre tiene una versión nueva, descárguela».
 * 3. Al entrar en el lector, muestra un diálogo con el nombre de la versión,
 *    el tamaño de la descarga y la descripción (novedades y correcciones),
 *    con los botones «Más tarde» y «Descargar».
 * 4. Descarga el APK a una carpeta propia del teléfono (updates) y lo
 *    ofrece al instalador del sistema. Si la instalación falla, el archivo
 *    queda guardado en esa carpeta para instalarlo a mano.
 *
 * Sin librerías externas: solo HttpURLConnection y org.json del sistema.
 */
public final class UpdateManager {

    /** Ubicación pública del manifiesto de actualizaciones. */
    public static final String MANIFEST_URL =
            "https://raw.githubusercontent.com/marrkosmarrtins-png/VozLibre/main/update.json";

    private static final String PREFS = "vozlibre_updates";
    private static final String KEY_LAST_CHECK = "last_check_ms";
    private static final String KEY_NOTIFIED_CODE = "notified_version_code";
    private static final String CHANNEL_UPDATES = "vozlibre_updates";
    private static final int NOTIF_UPDATE_ID = 100;
    /** Comprobación automática cada 12 horas. */
    private static final long AUTO_CHECK_MS = 12L * 60L * 60L * 1000L;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private UpdateManager() { }

    /** Datos de la versión nueva publicada en update.json. */
    public static class Info {
        public int versionCode;
        public String versionName = "";
        public long sizeBytes;
        public String apkUrl = "";
        public String notes = "";
    }

    public interface CheckCallback {
        /** info == null significa «no hay nada más nuevo». */
        void onResult(Info info);
        void onError(String message);
    }

    // -------------------------------------------------------------- comprobación

    public static boolean shouldAutoCheck(Context ctx) {
        SharedPreferences p = prefs(ctx);
        return System.currentTimeMillis() - p.getLong(KEY_LAST_CHECK, 0L) > AUTO_CHECK_MS;
    }

    private static void markChecked(Context ctx) {
        prefs(ctx).edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static void checkAsync(final Context ctx, final CheckCallback cb) {
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            Info info = null;
            String error = null;
            try {
                Info fetched = fetchManifest();
                if (fetched != null && fetched.versionCode > installedVersionCode(app)) {
                    info = fetched;
                }
            } catch (Exception e) {
                error = e.getMessage();
            }
            markChecked(app);
            final Info fInfo = info;
            final String fError = error;
            MAIN.post(() -> {
                if (cb == null) return;
                if (fError != null) cb.onError(fError);
                else cb.onResult(fInfo);
            });
        }).start();
    }

    private static Info fetchManifest() throws Exception {
        String body = httpGetString(MANIFEST_URL);
        if (body == null || body.trim().isEmpty()) throw new Exception("manifest vacío");
        JSONObject o = new JSONObject(body);
        Info info = new Info();
        info.versionCode = o.optInt("versionCode", 0);
        info.versionName = o.optString("versionName", "");
        info.sizeBytes = o.optLong("sizeBytes", 0L);
        info.apkUrl = o.optString("apkUrl", "");
        info.notes = o.optString("notes", "");
        if (info.versionCode <= 0 || info.apkUrl.isEmpty()) {
            throw new Exception("manifest incompleto");
        }
        return info;
    }

    private static int installedVersionCode(Context ctx) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(
                    ctx.getPackageName(), 0);
            return pi.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    // -------------------------------------------------------------- notificación

    /** Notifica «hay una versión nueva» una sola vez por versión. */
    public static void notifyUpdateAvailable(Context ctx, Info info) {
        if (info == null) return;
        final Context app = ctx.getApplicationContext();
        SharedPreferences p = prefs(app);
        if (p.getInt(KEY_NOTIFIED_CODE, 0) >= info.versionCode) return;

        NotificationManager nm =
                (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_UPDATES,
                    app.getString(R.string.update_channel),
                    NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription(app.getString(R.string.update_channel));
            nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(app, MainActivity.class);
        open.putExtra("show_update", true);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pOpen = PendingIntent.getActivity(app, 21, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(app, CHANNEL_UPDATES)
                : new Notification.Builder(app);
        b.setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(app.getString(R.string.notif_update_title))
                .setContentText(app.getString(R.string.notif_update_text,
                        info.versionName))
                .setContentIntent(pOpen)
                .setAutoCancel(true);
        nm.notify(NOTIF_UPDATE_ID, b.build());
        p.edit().putInt(KEY_NOTIFIED_CODE, info.versionCode).apply();
    }

    // -------------------------------------------------------------- diálogo

    /** Diálogo de actualización: versión, tamaño, novedades; Más tarde / Descargar. */
    public static void showUpdateDialog(final Activity act, final Info info) {
        if (act == null || act.isFinishing() || info == null) return;
        Earcons.get(act).play(Earcons.ALERT, 0.9f);
        String size = formatSize(act, info.sizeBytes);
        String msg = act.getString(R.string.dlg_update_size, size)
                + "\n\n" + act.getString(R.string.dlg_update_notes_label) + "\n"
                + (info.notes.isEmpty() ? "· Mejoras y correcciones generales." : info.notes);
        new AlertDialog.Builder(act)
                .setTitle(act.getString(R.string.dlg_update_title, info.versionName))
                .setMessage(msg)
                .setPositiveButton(R.string.btn_download,
                        (d, w) -> downloadAndInstall(act, info))
                .setNegativeButton(R.string.btn_later, null)
                .show();
    }

    private static String formatSize(Context ctx, long bytes) {
        if (bytes <= 0) return "";
        double mb = bytes / (1024.0 * 1024.0);
        return String.format(java.util.Locale.getDefault(), "%.1f MB", mb);
    }

    // -------------------------------------------------------------- descarga

    public static File updatesDir(Context ctx) {
        Context app = ctx.getApplicationContext();
        File base = app.getExternalFilesDir(null);
        File dir = new File(base != null ? base : app.getFilesDir(), "updates");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    public static void downloadAndInstall(final Activity act, final Info info) {
        Toast.makeText(act, R.string.downloading, Toast.LENGTH_SHORT).show();
        final File dir = updatesDir(act);
        final File out = new File(dir, "VozLibre-" + info.versionName + ".apk");
        final File tmp = new File(dir, out.getName() + ".part");
        new Thread(() -> {
            try {
                httpToFile(info.apkUrl, tmp);
                if (tmp.length() <= 0) throw new Exception("archivo vacío");
                if (out.exists()) out.delete();
                if (!tmp.renameTo(out)) throw new Exception("no se pudo guardar");
                MAIN.post(() -> {
                    Earcons.get(act).play(Earcons.SUCCESS, 0.9f);
                    new AlertDialog.Builder(act)
                            .setTitle(R.string.dlg_download_done_title)
                            .setMessage(act.getString(R.string.download_saved,
                                    out.getAbsolutePath()))
                            .setPositiveButton(R.string.btn_install_now,
                                    (d, w) -> installApk(act, out))
                            .setNegativeButton(R.string.dialog_close, null)
                            .show();
                });
            } catch (Exception e) {
                try { tmp.delete(); } catch (Exception ignored) { }
                MAIN.post(() -> {
                    Earcons.get(act).play(Earcons.ERROR, 0.9f);
                    Toast.makeText(act, R.string.download_error,
                            Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    // -------------------------------------------------------------- instalación

    /** Pide al sistema la instalación del APK descargado. */
    public static void installApk(Activity act, File apk) {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                if (!act.getPackageManager().canRequestPackageInstalls()) {
                    try {
                        act.startActivity(new Intent(
                                android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:" + act.getPackageName())));
                    } catch (Exception e) {
                        act.startActivity(new Intent(
                                android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES));
                    }
                    Toast.makeText(act, R.string.install_blocked,
                            Toast.LENGTH_LONG).show();
                    return;
                }
            }
            Uri uri = Uri.parse("content://com.vozlibre.reader.apk/updates/"
                    + apk.getName());
            Intent i = new Intent(Intent.ACTION_INSTALL_PACKAGE);
            i.setData(uri);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            i.putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true);
            i.putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, act.getPackageName());
            act.startActivity(i);
        } catch (Exception e) {
            Toast.makeText(act, act.getString(R.string.download_saved,
                    apk.getAbsolutePath()), Toast.LENGTH_LONG).show();
        }
    }

    // -------------------------------------------------------------- HTTP mínimo

    private static String httpGetString(String url) throws Exception {
        HttpURLConnection c = null;
        try {
            c = open(url);
            if (c.getResponseCode() != 200) {
                throw new Exception("HTTP " + c.getResponseCode());
            }
            InputStream in = c.getInputStream();
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static void httpToFile(String url, File out) throws Exception {
        HttpURLConnection c = null;
        FileOutputStream fos = null;
        try {
            c = open(url);
            if (c.getResponseCode() != 200) {
                throw new Exception("HTTP " + c.getResponseCode());
            }
            InputStream in = c.getInputStream();
            fos = new FileOutputStream(out);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
            fos.flush();
            in.close();
        } finally {
            if (fos != null) try { fos.close(); } catch (Exception ignored) { }
            if (c != null) c.disconnect();
        }
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "VozLibre-Updater");
        c.setRequestMethod("GET");
        return c;
    }
}
