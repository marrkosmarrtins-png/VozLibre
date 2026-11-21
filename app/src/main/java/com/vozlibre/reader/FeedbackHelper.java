package com.vozlibre.reader;

import android.content.Context;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

/**
 * Respuesta táctil y sonora del lector: vibración con fuerza ajustable
 * (amplitud 1–255) y "clic" de navegación con volumen ajustable.
 */
class FeedbackHelper {

    private final Context appContext;
    private ToneGenerator tone;
    private int lastVolume = -1;

    FeedbackHelper(Context appContext) {
        this.appContext = appContext;
    }

    /** Vibración corta. strength = 0..100 % (se traduce a amplitud 1..255). */
    void vibrate(int strength) {
        if (strength <= 0) return;
        try {
            Vibrator v = obtainVibrator();
            if (v == null || !v.hasVibrator()) return;
            int amplitude = Math.max(1, Math.min(255, strength * 255 / 100));
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(VibrationEffect.createOneShot(45, amplitude));
            } else {
                // API 24/25: solo duración fija (la amplitud no existe aún).
                v.vibrate(45);
            }
        } catch (Exception ignored) {
        }
    }

    /** Clic sonoro de navegación. volume = 0..100 (sobre el flujo MÚSICA). */
    void click(int volume) {
        if (volume <= 0) return;
        try {
            if (tone == null || lastVolume != volume) {
                if (tone != null) tone.release();
                tone = new ToneGenerator(AudioManager.STREAM_MUSIC,
                        Math.max(1, Math.min(100, volume)));
                lastVolume = volume;
            }
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, 120);
        } catch (Exception ignored) {
            // Algunos dispositivos se quedan sin recursos de tono; no es crítico.
        }
    }

    void release() {
        if (tone != null) {
            try { tone.release(); } catch (Exception ignored) { }
            tone = null;
            lastVolume = -1;
        }
    }

    private Vibrator obtainVibrator() {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                VibratorManager mgr = (VibratorManager)
                        appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                return mgr != null ? mgr.getDefaultVibrator() : null;
            }
            @SuppressWarnings("deprecation")
            Vibrator v = (Vibrator) appContext.getSystemService(Context.VIBRATOR_SERVICE);
            return v;
        } catch (Exception e) {
            return null;
        }
    }
}
