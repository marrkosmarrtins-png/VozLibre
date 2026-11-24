package com.vozlibre.reader;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Detector de motores de texto a voz (TTS) instalados.
 *
 * No depende de que ningún motor inicialice: pregunta directamente al
 * gestor de paquetes qué servicios responden a la acción estándar
 * «android.intent.action.TTS_SERVICE». Así VozLibre puede mostrar TODOS los
 * motores (los del sistema y los instalados por el usuario: RHVoice,
 * eSpeak, Acapela…) y puede reintentar la voz con otro motor si el elegido
 * no arranca.
 */
public final class TtsScanner {

    /** Un motor encontrado: paquete + etiqueta legible. */
    public static final class Info {
        public final String pkg;
        public final String label;
        public final boolean isDefault;

        Info(String pkg, String label, boolean isDefault) {
            this.pkg = pkg;
            this.label = label;
            this.isDefault = isDefault;
        }
    }

    private TtsScanner() { }

    /**
     * Lista todos los motores TTS del teléfono, ordenados alfabéticamente
     * por su nombre legible. Nunca lanza excepciones: como mucho devuelve
     * una lista vacía (teléfono sin motores).
     */
    public static List<Info> list(Context ctx) {
        List<Info> out = new ArrayList<>();
        if (ctx == null) return out;
        try {
            Intent probe = new Intent(TextToSpeech.Engine
                    .INTENT_ACTION_TTS_SERVICE);
            List<ResolveInfo> ris = ctx.getPackageManager()
                    .queryIntentServices(probe, 0);
            if (ris == null) return out;
            String defPkg = null;
            try {
                defPkg = Settings.Secure.getString(ctx.getContentResolver(),
                        Settings.Secure.TTS_DEFAULT_SYNTH);
            } catch (Exception ignored) { }
            for (ResolveInfo ri : ris) {
                try {
                    ServiceInfo si = ri.serviceInfo;
                    if (si == null) continue;
                    String pkg = si.packageName;
                    if (pkg == null || pkg.trim().isEmpty()) continue;
                    CharSequence cs = si.loadLabel(ctx.getPackageManager());
                    String label = cs != null && !cs.toString().trim().isEmpty()
                            ? cs.toString() : pkg;
                    out.add(new Info(pkg, label, pkg.equals(defPkg)));
                } catch (Exception ignored) { }
            }
            Collections.sort(out, new Comparator<Info>() {
                @Override
                public int compare(Info a, Info b) {
                    return String.valueOf(a.label)
                            .compareToIgnoreCase(String.valueOf(b.label));
                }
            });
        } catch (Exception ignored) { }
        return out;
    }
}
