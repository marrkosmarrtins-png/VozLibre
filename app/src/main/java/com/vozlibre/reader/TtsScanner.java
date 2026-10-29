package com.vozlibre.reader;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Enumera TODOS los motores de texto a voz instalados en el teléfono:
 * los del sistema y los que el usuario haya instalado aparte
 * (RHVoice, eSpeak, Acapela, vocalices locales, etc.).
 *
 * No depende de que ningún motor llegue a inicializarse: primero usa
 * getEngines() de una instancia temporal (funciona sin init) y, si
 * algunosAndroid oculta paquetes (visibility filtering de Android 11+),
 * repite la búsqueda con PackageManager, que con <queries> en el
 * AndroidManifest sí ve todos los motores.
 */
public final class TtsScanner {

    /** Un motor encontrado: etiqueta visible, paquete y si es el del sistema. */
    public static class Info {
        public String label;
        public String pkg;
        public boolean isDefault;
    }

    private TtsScanner() { }

    public static List<Info> list(Context ctx) {
        Context app = ctx.getApplicationContext();
        String systemDefault = null;
        try {
            systemDefault = Settings.Secure.getString(
                    app.getContentResolver(), Settings.Secure.TTS_DEFAULT_SYNTH);
        } catch (Exception ignored) { }

        Set<String> seen = new HashSet<>();
        List<Info> out = new ArrayList<>();

        // 1) getEngines() con una instancia temporal (no requiere init).
        try {
            TextToSpeech probe = new TextToSpeech(app, null);
            try {
                List<TextToSpeech.EngineInfo> engines = probe.getEngines();
                if (engines != null) {
                    for (TextToSpeech.EngineInfo e : engines) {
                        if (e == null || e.name == null || !seen.add(e.name)) continue;
                        Info i = new Info();
                        i.pkg = e.name;
                        i.label = (e.label != null && !e.label.trim().isEmpty())
                                ? e.label : e.name;
                        i.isDefault = e.name.equals(systemDefault);
                        out.add(i);
                    }
                }
            } finally {
                try { probe.shutdown(); } catch (Exception ignored) { }
            }
        } catch (Exception ignored) { }

        // 2) Respaldo con PackageManager + <queries>: completo incluso si 1) falló.
        try {
            PackageManager pm = app.getPackageManager();
            Intent intent = new Intent("android.intent.action.TTS_SERVICE");
            List<ResolveInfo> ris = pm.queryIntentServices(intent, 0);
            if (ris != null) {
                for (ResolveInfo ri : ris) {
                    if (ri == null || ri.serviceInfo == null) continue;
                    String pkg = ri.serviceInfo.packageName;
                    if (pkg == null || !seen.add(pkg)) continue;
                    Info i = new Info();
                    i.pkg = pkg;
                    CharSequence lbl = ri.loadLabel(pm);
                    i.label = (lbl != null && lbl.toString().trim().length() > 0)
                            ? lbl.toString() : pkg;
                    i.isDefault = pkg.equals(systemDefault);
                    out.add(i);
                }
            }
        } catch (Exception ignored) { }

        // Marcar el motor predeterminado y ordenar: primero el del sistema,
        // después alfabético.
        for (Info i : out) {
            if (i.isDefault) {
                i.label = i.label + " " + app.getString(R.string.engine_system_default_suffix);
            }
        }
        Collections.sort(out, (a, b) -> {
            if (a.isDefault != b.isDefault) return a.isDefault ? -1 : 1;
            return a.label.toLowerCase(Locale.getDefault())
                    .compareTo(b.label.toLowerCase(Locale.getDefault()));
        });
        return out;
    }

    /** Primer motor disponible distinto del dado (para reintentos de voz). */
    public static String firstEngineOtherThan(Context ctx, String excludedPkg) {
        List<Info> all = list(ctx);
        for (Info i : all) {
            if (i.pkg != null && !i.pkg.equals(excludedPkg)) return i.pkg;
        }
        return null;
    }
}
