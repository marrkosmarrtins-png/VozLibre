package com.vozlibre.reader;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;

/**
 * Sonidos propios de VozLibre (earcons): el mismo lenguaje sonoro de
 * TalkBack, pero con sonidos propios del proyecto (dominio público).
 *
 * Los 11 archivos .wav viven en res/raw y se cargan una sola vez en un
 * SoundPool singleton. El volumen lo decide quien llama (0.0 .. 1.0) para
 * respetar el ajuste «volumen de los sonidos» del usuario.
 *
 * Nada aquí puede tirar abajo el lector: todo va protegido con try/catch
 * (un sonido es un extra, la voz es lo imprescindible).
 */
public final class Earcons {

    // Identificadores de los 11 sonidos (orden = orden de carga en res/raw).
    public static final int FOCUS = 0;    // elemento bajo el dedo / enfocado
    public static final int CLICK = 1;    // activación (doble toque)
    public static final int NEXT = 2;     // elemento siguiente
    public static final int PREV = 3;     // elemento anterior
    public static final int SCROLL = 4;   // desplazamiento de pantalla
    public static final int WINDOW = 5;   // pantalla / ventana nueva
    public static final int START = 6;    // comienza la lectura continua
    public static final int STOP = 7;      // se detiene / se apaga
    public static final int SUCCESS = 8;  // acción completada con éxito
    public static final int ERROR = 9;    // fallo / no hay contenido
    public static final int ALERT = 10;   // aviso que pide atención

    private static final int[] RAW = {
            R.raw.earcon_focus,
            R.raw.earcon_click,
            R.raw.earcon_next,
            R.raw.earcon_prev,
            R.raw.earcon_scroll,
            R.raw.earcon_window,
            R.raw.earcon_start,
            R.raw.earcon_stop,
            R.raw.earcon_success,
            R.raw.earcon_error,
            R.raw.earcon_alert,
    };

    private static volatile Earcons instance;

    private final SoundPool pool;
    private final int[] ids = new int[RAW.length];
    private volatile boolean released = false;

    private Earcons(Context ctx) {
        SoundPool p;
        try {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            p = new SoundPool.Builder()
                    .setMaxStreams(2)
                    .setAudioAttributes(attrs)
                    .build();
        } catch (Exception e) {
            p = null;
        }
        pool = p;
        if (pool != null) {
            for (int i = 0; i < RAW.length; i++) {
                try {
                    ids[i] = pool.load(ctx, RAW[i], 1);
                } catch (Exception e) {
                    ids[i] = 0;
                }
            }
        }
    }

    /** Instancia única (se crea con el contexto de la aplicación). */
    public static Earcons get(Context ctx) {
        if (instance == null) {
            synchronized (Earcons.class) {
                if (instance == null) {
                    instance = new Earcons(
                            ctx != null ? ctx.getApplicationContext() : null);
                }
            }
        }
        return instance;
    }

    /**
     * Reproduce un sonido propio con el volumen indicado (0.0 .. 1.0).
     * Silencioso si algo falla: el fallo de un sonido JAMÁS rompe la voz.
     */
    public void play(int which, float volume) {
        if (released || pool == null) return;
        if (which < 0 || which >= ids.length) return;
        try {
            if (ids[which] != 0) {
                float v = Math.max(0f, Math.min(1f, volume));
                pool.play(ids[which], v, v, 1, 0, 1f);
            }
        } catch (Exception ignored) { }
    }

    /** Libera el SoundPool (al apagar el lector). */
    public void release() {
        released = true;
        if (pool != null) {
            try { pool.release(); } catch (Exception ignored) { }
        }
    }
}
