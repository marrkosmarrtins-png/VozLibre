package com.vozlibre.reader;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.util.SparseIntArray;

/**
 * Sonidos PROPIOS de VozLibre (earcons): UN SONIDO PARA CADA ACCIÓN del
 * lector, sintetizados e incluidos en la propia aplicación (dominio
 * público, sin licencias de terceros): foco, clic, ventana/diálogo, scroll,
 * inicio, parada, siguiente, anterior, alerta, acierto y error.
 *
 * DOS CORRECCIONES IMPORTANTES (por qué antes no se oía nada):
 * 1. FLUJO DE AUDIO: antes se usaba USAGE_ASSISTANCE_SONIFICATION, que
 *    Android dirige al flujo del SISTEMA (STREAM_SYSTEM), un volumen que
 *    en muchos teléfonos está bajo o silenciado y NO es el que sube el
 *    usuario. Ahora los sonidos van por USAGE_MEDIA (STREAM_MUSIC), el
 *    mismo flujo por donde habla la voz: si se oye la voz, se oyen los
 *    sonidos (igual que hace TalkBack con sus earcons).
 * 2. CARGA ASÍNCRONA: SoundPool carga los archivos en segundo plano; si
 *    se pedía un sonido antes de que terminara de cargar, se perdía en
 *    silencio. Ahora los sonidos pedidos antes de la carga quedan
 *    pendientes y se reproducen en cuanto el archivo está listo.
 */
public final class Earcons {

    public static final int START = 0;    // lectura continua iniciada
    public static final int STOP = 1;     // lectura detenida / fin
    public static final int NEXT = 2;     // siguiente elemento
    public static final int PREV = 3;     // elemento anterior
    public static final int ALERT = 4;    // diálogo o aviso importante
    public static final int SUCCESS = 5;  // algo se activó bien
    public static final int ERROR = 6;    // fallo (sin voz, sin red…)
    public static final int FOCUS = 7;    // elemento tocado / con foco
    public static final int CLICK = 8;    // activación (doble toque, clic)
    public static final int WINDOW = 9;   // pantalla o diálogo nuevo
    public static final int SCROLL = 10;  // la lista se desplazó

    private static final int[] RES = {
            R.raw.earcon_start, R.raw.earcon_stop, R.raw.earcon_next,
            R.raw.earcon_prev, R.raw.earcon_alert, R.raw.earcon_success,
            R.raw.earcon_error, R.raw.earcon_focus, R.raw.earcon_click,
            R.raw.earcon_window, R.raw.earcon_scroll
    };

    private static volatile Earcons sInstance;

    private final SoundPool pool;
    private final SparseIntArray ids = new SparseIntArray();
    private final boolean[] loaded = new boolean[RES.length];
    private final boolean[] pending = new boolean[RES.length];
    private volatile float lastVolume = 0.8f;

    private Earcons(Context appContext) {
        // STREAM_MUSIC: el mismo flujo de la voz sintética. Si el usuario
        // oye la voz, oirá también los sonidos, al mismo volumen.
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        pool = new SoundPool.Builder()
                .setMaxStreams(6)
                .setAudioAttributes(attrs)
                .build();
        pool.setOnLoadCompleteListener((sp, sampleId, status) -> {
            // status: 0 = cargado bien (SoundPool no publica una constante).
            if (status != 0) return;
            for (int i = 0; i < RES.length; i++) {
                if (ids.get(i, -1) == sampleId) {
                    loaded[i] = true;
                    // ¿Alguien pidió este sonido antes de que cargara?
                    if (pending[i]) {
                        pending[i] = false;
                        try {
                            sp.play(sampleId, lastVolume, lastVolume, 1, 0, 1f);
                        } catch (Exception ignored) { }
                    }
                    break;
                }
            }
        });
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

    /** Reproduce un earcon. volume = 0..1 sobre el flujo de medios. */
    public void play(int which, float volume) {
        if (volume <= 0f) return;
        lastVolume = volume;
        if (which < 0 || which >= RES.length) return;
        if (!loaded[which]) {
            // Aún cargando: reproducir en cuanto esté listo (sin perderlo).
            pending[which] = true;
            return;
        }
        int id = ids.get(which, 0);
        if (id == 0) return;
        try {
            pool.play(id, volume, volume, 1, 0, 1f);
        } catch (Exception ignored) { }
    }
}
