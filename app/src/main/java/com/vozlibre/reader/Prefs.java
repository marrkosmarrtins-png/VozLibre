package com.vozlibre.reader;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Acceso centralizado a los ajustes guardados de VozLibre.
 * Se guardan en SharedPreferences para que sobrevivan al reinicio.
 */
public final class Prefs {

    private static final String FILE = "vozlibre";

    public static final String KEY_RATE = "rate";             // float 0.5 .. 2.0
    public static final String KEY_PITCH = "pitch";           // float 0.5 .. 1.5
    public static final String KEY_DELAY = "scroll_delay";    // int ms 300 .. 2000
    public static final String KEY_TOUCH = "touch_explore";   // boolean
    public static final String KEY_ENGINE = "tts_engine";     // String ("" = motor del sistema)
    public static final String KEY_VIB_ON = "vib_on";         // boolean
    public static final String KEY_VIB_STRENGTH = "vib_strength"; // int 0..100 (porcentaje)
    public static final String KEY_SOUND_ON = "sound_on";     // boolean
    public static final String KEY_SOUND_VOL = "sound_volume"; // int 0..100
    public static final String KEY_PENDING_DL = "pending_download_id";  // long (-1 = nada)
    public static final String KEY_LAST_NOTIFIED_UPDATE = "last_notified_update"; // int code

    private Prefs() { }

    public static SharedPreferences get(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static void load(SharedPreferences p, Holder h) {
        h.rate = p.getFloat(KEY_RATE, 1.0f);
        h.pitch = p.getFloat(KEY_PITCH, 1.0f);
        h.scrollDelayMs = p.getInt(KEY_DELAY, 900);
        h.touchExplore = p.getBoolean(KEY_TOUCH, true);
        h.engine = p.getString(KEY_ENGINE, "");
        h.vibOn = p.getBoolean(KEY_VIB_ON, true);
        h.vibStrength = p.getInt(KEY_VIB_STRENGTH, 60);
        h.soundOn = p.getBoolean(KEY_SOUND_ON, true);
        h.soundVolume = p.getInt(KEY_SOUND_VOL, 70);
    }

    /** Contenedor simple de los valores actuales. */
    public static class Holder {
        public float rate = 1.0f;
        public float pitch = 1.0f;
        public int scrollDelayMs = 900;
        public boolean touchExplore = true;
        public String engine = "";
        public boolean vibOn = true;
        public int vibStrength = 60;
        public boolean soundOn = true;
        public int soundVolume = 70;
    }
}
