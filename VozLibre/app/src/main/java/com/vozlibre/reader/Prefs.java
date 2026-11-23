package com.vozlibre.reader;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Acceso centralizado a los ajustes guardados de VozLibre.
 * Se guardan en SharedPreferences para que sobrevivan al reinicio.
 */
public final class Prefs {

    private static final String FILE = "vozlibre";

    public static final String KEY_RATE = "rate";             // float 0.5 .. 5.0
    public static final String KEY_PITCH = "pitch";           // float 0.5 .. 1.5
    public static final String KEY_DELAY = "scroll_delay";    // int ms 300 .. 2000
    public static final String KEY_TOUCH = "touch_explore";   // boolean
    public static final String KEY_ENGINE = "tts_engine";     // String ("" = motor del sistema)
    public static final String KEY_VOICE_LOCALE = "voice_locale"; // String ("es_ES", "" = automático)
    public static final String KEY_VOICE_NAME = "voice_name";    // String ("" = voz automática)
    public static final String KEY_VIB_ON = "vib_on";         // boolean
    public static final String KEY_VIB_STRENGTH = "vib_strength"; // int 0..100 (porcentaje)
    public static final String KEY_SOUND_ON = "sound_on";     // boolean
    public static final String KEY_SOUND_VOL = "sound_volume"; // int 0..100
    public static final String KEY_NOTIF_ON = "notif_on";     // boolean: decir notificaciones (1.0.8)
    public static final String KEY_ECHO_ON = "echo_on";       // boolean: eco de teclas al escribir (1.0.8)

    private Prefs() { }

    public static SharedPreferences get(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** Velocidad EXTREMA por defecto: el usuario pidió un lector ultra rápido. */
    public static final float DEFAULT_RATE = 2.5f;
    /** Pausa mínima entre páginas: navegación lo más rápida posible. */
    public static final int DEFAULT_SCROLL_DELAY = 250;

    public static void load(SharedPreferences p, Holder h) {
        h.rate = p.getFloat(KEY_RATE, DEFAULT_RATE);
        h.pitch = p.getFloat(KEY_PITCH, 1.0f);
        h.scrollDelayMs = p.getInt(KEY_DELAY, DEFAULT_SCROLL_DELAY);
        h.touchExplore = p.getBoolean(KEY_TOUCH, true);
        h.engine = p.getString(KEY_ENGINE, "");
        h.voiceLocale = p.getString(KEY_VOICE_LOCALE, "");
        h.voiceName = p.getString(KEY_VOICE_NAME, "");
        h.vibOn = p.getBoolean(KEY_VIB_ON, true);
        h.vibStrength = p.getInt(KEY_VIB_STRENGTH, 60);
        h.soundOn = p.getBoolean(KEY_SOUND_ON, true);
        h.soundVolume = p.getInt(KEY_SOUND_VOL, 70);
        h.notifOn = p.getBoolean(KEY_NOTIF_ON, true);
        h.echoOn = p.getBoolean(KEY_ECHO_ON, true);
    }

    /** Contenedor simple de los valores actuales. */
    public static class Holder {
        public float rate = DEFAULT_RATE;
        public float pitch = 1.0f;
        public int scrollDelayMs = DEFAULT_SCROLL_DELAY;
        public boolean touchExplore = true;
        public String engine = "";
        public String voiceLocale = "";
        public String voiceName = "";
        public boolean vibOn = true;
        public int vibStrength = 60;
        public boolean soundOn = true;
        public int soundVolume = 70;
        /** Avisos (1.0.8): decir las notificaciones al llegar. */
        public boolean notifOn = true;
        /** Avisos (1.0.8): eco de teclas al escribir (nunca contraseñas). */
        public boolean echoOn = true;
    }
}
