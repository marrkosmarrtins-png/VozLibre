package com.vozlibre.reader;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.util.SparseIntArray;

/**
 * Sonidos PROPIOS de VozLibre (earcons): pequeños tonos incluidos en la
 * propia aplicación para indicar inicio, parada, avance, retroceso,
 * alertas, aciertos y errores. VozLibre no depende de los sonidos de
 * ningún otro lector de pantalla ni del sistema: los trae consigo.
 */
public final class Earcons {

    public static final int START = 0;    // lectura iniciada
    public static final int STOP = 1;     // lectura detenida / fin
    public static final int NEXT = 2;     // siguiente elemento
    public static final int PREV = 3;     // elemento anterior
    public static final int ALERT = 4;    // diálogo o aviso importante
    public static final int SUCCESS = 5;  // algo se activó bien
    public static final int ERROR = 6;    // fallo (sin voz, sin red…)

    private static final int[] RES = {
            R.raw.earcon_start, R.raw.earcon_stop, R.raw.earcon_next,
            R.raw.earcon_prev, R.raw.earcon_alert, R.raw.earcon_success,
            R.raw.earcon_error
    };

    private static volatile Earcons sInstance;

    private final SoundPool pool;
    private final SparseIntArray ids = new SparseIntArray();

    private Earcons(Context appContext) {
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        pool = new SoundPool.Builder()
                .setMaxStreams(3)
                .setAudioAttributes(attrs)
                .build();
        for (int i = 0; i < RES.length; i++) {
            int id = 0;
            try {
                id = pool.load(appContext, RES[i], 1);
            } catch (Exception ignored) { }
            ids.put(i, id);
        }
    }

    public static Earcons get(Context ctx) {
        if (sInstance == null) {
            synchronized (Earcons.class) {
                if (sInstance == null) {
                    sInstance = new Earcons(ctx.getApplicationContext());
                }
            }
        }
        return sInstance;
    }

    /** Reproduce un earcon. volume = 0..1 sobre el flujo de sonificación. */
    public void play(int which, float volume) {
        if (volume <= 0f) return;
        int id = ids.get(which, 0);
        if (id == 0) return;
        try {
            pool.play(id, volume, volume, 1, 0, 1f);
        } catch (Exception ignored) { }
    }
}
