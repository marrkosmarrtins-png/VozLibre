package com.vozlibre.reader;

import android.content.Context;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;

/**
 * Vibración de respuesta al anunciar elementos (como TalkBack).
 *
 * La intensidad la elige el usuario (0 .. 100 %) y se traduce en la duración
 * del pulso: es la forma fiable de variar la «fuerza» en todos los Android
 * (los patrones con amplitud solo existen en los 8.0+ y no en todos los
 * motores). Todo protegido con try/catch: vibrar es un extra, nunca un
 * requisito.
 */
public final class FeedbackHelper {

    private final Vibrator vib;
    private volatile boolean released = false;

    public FeedbackHelper(Context ctx) {
        Vibrator v = null;
        try {
            if (ctx != null) {
                v = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
            }
        } catch (Exception ignored) { }
        vib = v;
    }

    /** Vibra un pulso proporcional a la fuerza elegida (0..100). */
    public void vibrate(int strengthPercent) {
        if (released || vib == null) return;
        try {
            int strength = Math.max(0, Math.min(100, strengthPercent));
            if (strength <= 0) return;
            // 0 % -> 25 ms (un toque casi imperceptible) .. 100 % -> 105 ms.
            long ms = 25L + (long) (strength / 100.0 * 80.0);
            if (Build.VERSION.SDK_INT >= 26) {
                vib.vibrate(VibrationEffect.createOneShot(ms,
                        VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vib.vibrate(ms);
            }
        } catch (Exception ignored) { }
    }

    /** Corta cualquier vibración en curso y marca el helper como libre. */
    public void release() {
        released = true;
        if (vib != null) {
            try { vib.cancel(); } catch (Exception ignored) { }
        }
    }
}
