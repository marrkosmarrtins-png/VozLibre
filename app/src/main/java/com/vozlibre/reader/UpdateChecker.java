package com.vozlibre.reader;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Verificador de actualizaciones de VozLibre.
 *
 * Lee un pequeño JSON público publicado en el repositorio del proyecto
 * (raw.githubusercontent.com/.../updates.json) que describe la versión más
 * reciente: número de versión, nombre, tamaño del APK, URL de descarga y
 * una descripción en español con las novedades y los arreglos.
 *
 * No envía NINGÚN dato: solo hace una lectura anónima del JSON. Sin
 * librerías externas: HttpURLConnection + org.json (incluidos en Android).
 */
public final class UpdateChecker {

    /** Datos de la actualización publicada en updates.json. */
    public static class UpdateInfo {
        public int versionCode = -1;
        public String versionName = "";
        public long sizeBytes = -1;
        public String apkUrl = "";
        public String description = "";
    }

    public interface Callback {
        /** update == null si no hay datos o falló la conexión (silencioso). */
        void onUpdateInfo(UpdateInfo update);
    }

    private static final int TIMEOUT_MS = 10000;

    private UpdateChecker() { }

    /** Consulta updates.json en segundo plano y responde en el hilo principal. */
    public static void checkAsync(Context ctx, Callback cb) {
        final String url = ctx.getString(R.string.updates_url);
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            UpdateInfo info = fetch(url);
            main.post(() -> {
                try {
                    cb.onUpdateInfo(info);
                } catch (Exception ignored) {
                }
            });
        }, "vozlibre-update-check").start();
    }

    private static UpdateInfo fetch(String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", "VozLibre");
            int code = conn.getResponseCode();
            if (code != 200) return null;
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            JSONObject o = new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
            UpdateInfo i = new UpdateInfo();
            i.versionCode = o.optInt("versionCode", -1);
            i.versionName = o.optString("versionName", "");
            i.sizeBytes = o.optLong("sizeBytes", -1);
            i.apkUrl = o.optString("apkUrl", "");
            i.description = o.optString("description", "");
            if (i.versionCode <= 0 || i.apkUrl.trim().isEmpty()) return null;
            return i;
        } catch (Exception e) {
            return null; // sin internet o JSON inválido: silencio, no molesta
        } finally {
            if (conn != null) {
                try { conn.disconnect(); } catch (Exception ignored) { }
            }
        }
    }
}
