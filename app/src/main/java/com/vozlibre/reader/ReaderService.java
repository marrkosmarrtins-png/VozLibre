package com.vozlibre.reader;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * VozLibre: lector de pantalla libre (GPLv3) e INDEPENDIENTE.
 *
 *  1. Funciona solo: no necesita TalkBack ni ningún otro lector de pantalla.
 *     Pide por sí mismo la exploración táctil al sistema, guía al usuario
 *     con su propia voz para aceptar el diálogo del sistema y confirma con
 *     un sonido propio cuando la navegación táctil queda activa.
 *  2. Lectura continua: recorre en voz alta todos los elementos visibles y,
 *     al llegar al final, desplaza el scroll y sigue leyendo.
 *  3. Barrido bidireccional: deslizar a la derecha va al siguiente elemento,
 *     deslizar a la izquierda al anterior, con marco verde.
 *  4. Exploración táctil PROPIA (arquitectura TalkBack): el servicio recibe
 *     los toques crudos y hace SU PRPIA búsqueda del elemento bajo el dedo
 *     (hit-test), así sigue el dedo en TODA la pantalla, incluso donde las
 *     vistas no mandan eventos hover. Doble toque = activar; doble toque y
 *     mantener = pulsación larga. No depende de otro lector.
 *  5. Sigue el FOCO: cuando una app mueve el foco (abre un diálogo, salta a
 *     un campo, muestra el menú de la pulsación larga), el lector salta a
 *     ese elemento y anuncia los elementos del nuevo menú/diálogo.
 *  6. Barrido que DESPLAZA LAS LISTAS: al llegar al último elemento visible
 *     desplaza el contenedor (RecyclerView/ListView) y sigue con los nuevos.
 *  7. Sonidos propios (earcons CC0 de Kenney) para CADA acción: foco, clic,
 *     ventana/diálogo, scroll, inicio, parada, siguiente, anterior, alerta,
 *     acierto y error. No usa los de otro lector.
 *  8. Motor de voz a elección: lista TODOS los motores instalados, TODAS
 *     sus voces, permite elegir IDIOMA y VOZ concreta, y si el elegido
 *     falla reintenta con otro disponible. Velocidad EXTREMA (2.5x, 5x).
 *  9. DESLIZADORES ajustables (como TalkBack): con un deslizador enfocado,
 *     deslizar hacia arriba o abajo lo ajusta y anuncia su valor.
 * 10. Toda la pantalla responde: apps, TECLADO, barra de estado (hora y
 *     batería), barra de navegación y zonas vacías: nunca silencio.
 * 11. Solo lee y actúa dentro de la app que está leyendo: si cambia de
 *     aplicación se detiene limpiamente (no se queda «pegado» en Chrome).
 * 12. Notificación de control con botones, incluido «Desactivar lector».
 * 13. Busca actualizaciones cada 12 h y avisa con notificación propia.
 * 14. AUTOENFOQUE de pantalla nueva (arquitectura TalkBack): al abrirse una
 *     app, un diálogo o un menú, el foco salta AL PRIMER ELEMENTO de la
 *     pantalla nueva: nunca se queda en los botones de la pantalla
 *     anterior. Doble toque en CUALQUIER PARTE activa el elemento
 *     enfocado. SCROLL con DOS DEDOS arriba y abajo. Tocar la pantalla
 *     DETIENE la lectura continua.
 */
public class ReaderService extends AccessibilityService {

    public static final String ACTION_READ_ALL = "com.vozlibre.reader.READ_ALL";
    public static final String ACTION_SWEEP_NEXT = "com.vozlibre.reader.SWEEP_NEXT";
    public static final String ACTION_STOP = "com.vozlibre.reader.STOP";
    public static final String ACTION_DISABLE = "com.vozlibre.reader.DISABLE";

    /** Instancia activa del servicio; null cuando el servicio está desactivado. */
    public static volatile ReaderService instance;

    private static final int MODE_IDLE = 0;
    private static final int MODE_READING = 1;
    private static final int MODE_SWEEP = 2;

    private static final int MAX_NODES = 600;
    private static final int MAX_SPOKEN_MEMORY = 300;
    private static final String CHANNEL_ID = "vozlibre_control";
    private static final int NOTIF_ID = 1;
    private static final int NOTIF_ID_NO_TTS = 2;
    private static final long UPDATE_CHECK_MS = 12L * 60L * 60L * 1000L;

    /** Bucle de lectura/barrido: lo detiene stopAllInternal(). */
    private final Handler handler = new Handler(Looper.getMainLooper());
    /** Mantenimiento (permisos, recordatorios, actualizaciones): NO lo detiene la parada. */
    private final Handler systemHandler = new Handler(Looper.getMainLooper());

    private TextToSpeech tts;
    private volatile boolean ttsReady = false;
    private String activeEnginePackage = null;   // motor realmente en uso
    private volatile int mode = MODE_IDLE;

    private List<AccessibilityNodeInfo> nodes = new ArrayList<>();
    private int index = 0;
    private String readingPackage = null;
    private String lastSpoken = "";

    /** Memoria de textos ya leídos (LRU) para no repetir tras el scroll. */
    private final LinkedHashMap<String, Boolean> spokenTexts =
            new LinkedHashMap<String, Boolean>(128, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > MAX_SPOKEN_MEMORY;
                }
            };

    private final Prefs.Holder settings = new Prefs.Holder();
    private HighlightOverlay overlay;
    private FeedbackHelper feedback;
    private Earcons earcons;

    // ------- independencia de la exploración táctil -------
    private boolean gestureConfirmed = false;   // ya nos llegan gestos nuestros
    private boolean teOnAnnounced = false;      // ya avisamos «exploración táctil activada»
    private boolean welcomeSpoken = false;
    private int teRemindCount = 0;
    private AlertDialog teDialog;               // diálogo propio de arranque (estilo TalkBack)
    private boolean teDialogShown = false;      // ya lo mostramos en esta conexión
    private AccessibilityManager.TouchExplorationStateChangeListener teListener;

    // ------- motor de voz: reintentos -------
    private int ttsInitAttempts = 0;
    private boolean engineFallbackAnnounced = false;

    // ------- explorador táctil propio (arquitectura TalkBack) -------
    private static final long DOUBLE_TAP_MS = 350L;
    private static final long TAP_MAX_MS = 220L;
    private static final long LONG_PRESS_MS = 480L;
    private static final int TAP_SLOP = 30;
    private long downMs = 0;
    private long lastTapUpMs = 0;
    private float lastTapX = 0, lastTapY = 0;
    private float downX = 0, downY = 0;
    private boolean movedFar = false;
    private boolean longPressSent = false;
    private boolean secondTapDown = false;
    private long rawTouchSeenMs = 0;
    private long suppressFocusEchoUntil = 0;
    private String lastAnnouncedKey = "";
    private long lastAnnouncedKeyMs = 0;
    private AccessibilityNodeInfo touchedNode = null;

    // ------- autoenfoque de pantalla nueva (arquitectura TalkBack) -------
    private static final long WINDOW_FOCUS_DELAY_MS = 220L;
    private static final float TWO_FINGER_MIN_PX = 60f;
    /** Id de la última ventana a la que ya llevamos el foco. */
    private int lastActiveWindowId = -1;
    /** Título capturado del último evento de ventana (nombre de pantalla). */
    private String pendingWindowLabel = null;
    /**
     * Elemento enfocado AHORA: el doble toque en cualquier parte de la
     * pantalla lo activa (así un campo abajo enfocado se activa aunque el
     * doble toque caiga arriba o a la izquierda).
     */
    private AccessibilityNodeInfo focusedNode = null;
    /** Hay un dedo del usuario en la pantalla ahora mismo. */
    private boolean touchInProgress = false;
    /** El detector del sistema ya reconoció un gesto de dos dedos. */
    private boolean frameworkTwoFingerSeen = false;
    private final Runnable windowFocusApply = this::applyWindowFocus;

    // ------- scroll con DOS DEDOS (como TalkBack) -------
    private boolean twoFingerActive = false;
    private float twoFingerStartY = 0;
    private float twoFingerLastY = 0;
    private long lastTwoFingerScrollMs = 0;

    /** Doble toque y mantener = pulsación larga (el dedo sigue quieto). */
    private final Runnable longPressCheck = new Runnable() {
        @Override
        public void run() {
            if (instance == null || longPressSent || movedFar) return;
            long now = SystemClock.uptimeMillis();
            if (now - downMs < LONG_PRESS_MS) return;
            if (!secondTapDown) return;
            // Pulsación larga sobre el elemento del doble toque: el que está
            // bajo el dedo o, si el dedo cayó en un hueco, el ENFOCADO.
            AccessibilityNodeInfo target = touchedNode != null
                    ? touchedNode : focusedNode;
            if (target == null) return;
            longPressSent = true;
            performLongPress(target, downX, downY);
        }
    };

    // ------------------------------------------------------------------ ciclo

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        overlay = new HighlightOverlay(getApplicationContext());
        feedback = new FeedbackHelper(getApplicationContext());
        earcons = Earcons.get(getApplicationContext());
        loadPrefs();
        teDialogShown = false;
        registerTeListener();
        applyServiceFlags();
        initTts(settings.engine);
        showNotification();
        // Comprobar que la exploración táctil quedó concedida PARA NOSOTROS.
        systemHandler.postDelayed(this::verifyTouchExploration, 6000);
        // Buscar actualizaciones 2 min después de conectar y luego cada 12 h.
        systemHandler.postDelayed(this::runUpdateCheck, 120000);
    }

    @Override
    public void onInterrupt() {
        if (tts != null) tts.stop();
        overlayHideSafe();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        stopAllInternal();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        stopAllInternal();
        dismissTeDialog();
        systemHandler.removeCallbacksAndMessages(null);
        try {
            AccessibilityManager am = (AccessibilityManager)
                    getSystemService(ACCESSIBILITY_SERVICE);
            if (am != null && teListener != null) {
                am.removeTouchExplorationStateChangeListener(teListener);
            }
        } catch (Exception ignored) { }
        if (tts != null) {
            try { tts.shutdown(); } catch (Exception ignored) { }
            tts = null;
        }
        if (overlay != null) overlay.release();
        if (feedback != null) feedback.release();
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(NOTIF_ID);
        instance = null;
        super.onDestroy();
    }

    /** Órdenes que llegan desde la notificación de control. */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_READ_ALL.equals(action)) {
            startReading();
        } else if (ACTION_SWEEP_NEXT.equals(action)) {
            if (mode != MODE_SWEEP) startSweep(false); else sweepNext();
        } else if (ACTION_STOP.equals(action)) {
            stopAll();
        } else if (ACTION_DISABLE.equals(action)) {
            stopAllInternal();
            dismissTeDialog();
            announceWith(Earcons.STOP, R.string.tts_disabled);
            systemHandler.postDelayed(this::disableSelf, 1500);
        }
        return START_NOT_STICKY;
    }

    // ------------------------------------------------------------------ exploración táctil independiente

    /** Escucha los cambios globales de exploración táctil mientras corrremos. */
    private void registerTeListener() {
        try {
            AccessibilityManager am = (AccessibilityManager)
                    getSystemService(ACCESSIBILITY_SERVICE);
            if (am == null) return;
            if (teListener == null) {
                teListener = enabled ->
                        systemHandler.post(() -> onTouchExplorationChanged(enabled));
            }
            am.addTouchExplorationStateChangeListener(teListener);
        } catch (Exception ignored) { }
    }

    private void onTouchExplorationChanged(boolean enabled) {
        if (instance == null) return;
        if (enabled) {
            dismissTeDialog();
            // Solo avisamos una vez y solo si aún no tenemos gestos confirmados.
            if (!gestureConfirmed && !teOnAnnounced) {
                teOnAnnounced = true;
                teRemindCount = 99;
                announceWith(Earcons.SUCCESS, getString(R.string.tts_te_on_now));
            }
        } else {
            // Se apagó (por ejemplo, al cerrar otro lector): pedirla de nuevo
            // para VozLibre, que debe sostenerla por sí mismo.
            teOnAnnounced = false;
            systemHandler.postDelayed(this::applyServiceFlags, 800);
        }
    }

    /**
     * Verifica que la exploración táctil esté activa; si nadie la activó,
     * guía con voz y vuelve a pedirla (el diálogo del sistema aparece al
     * activar el servicio o al reajustar la bandera). Unos pocos
     * recordatorios, no un acoso.
     */
    private void verifyTouchExploration() {
        if (instance == null || !settings.touchExplore || gestureConfirmed) return;
        AccessibilityManager am = (AccessibilityManager)
                getSystemService(ACCESSIBILITY_SERVICE);
        boolean on = am != null && am.isTouchExplorationEnabled();
        if (on) {
            dismissTeDialog();
            return; // activa (nuestra o compartida); esperamos el primer gesto
        }
        teRemindCount++;
        if (teRemindCount == 1) {
            announceWith(Earcons.ALERT, getString(R.string.tts_touch_needed));
            toggleTouchFlag();
        } else if (teRemindCount == 2) {
            showTeBootstrapDialog();
        }
        if (teRemindCount <= 5) {
            systemHandler.postDelayed(this::verifyTouchExploration,
                    teRemindCount == 1 ? 12000L : 25000L);
        }
    }

    /**
     * Quita y vuelve a poner la bandera de exploración táctil: esto hace que
     * el sistema vuelva a mostrar su diálogo de permiso (si no se rechazó
     * antes; en ese caso hay que desactivar y reactivar el servicio).
     */
    private void toggleTouchFlag() {
        try {
            AccessibilityServiceInfo info = getServiceInfo();
            if (info == null) return;
            info.flags &= ~AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE;
            setServiceInfo(info);
            systemHandler.postDelayed(() -> {
                if (instance == null || !settings.touchExplore) return;
                AccessibilityServiceInfo i2 = getServiceInfo();
                if (i2 != null) {
                    i2.flags |= AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE;
                    setServiceInfo(i2);
                }
            }, 300);
        } catch (Exception ignored) { }
    }

    /** La pantalla principal pide reintentar el permiso de exploración táctil. */
    public void requestTouchExplorationGrant() {
        teRemindCount = 0;
        announceWith(Earcons.ALERT, getString(R.string.touch_requested));
        toggleTouchFlag();
        systemHandler.postDelayed(this::verifyTouchExploration, 5000);
    }

    /**
     * Diálogo PROPIO de VozLibre (como hace TalkBack con sus avisos): se
     * dibuja sobre cualquier pantalla desde el propio servicio con el tipo
     * TYPE_ACCESSIBILITY_OVERLAY, con sonido propio. Así el usuario puede
     * activar la exploración táctil aunque ningún otro lector la haya
     * encendido: no dependemos de diálogos del sistema ni de otros lectores.
     */
    private void showTeBootstrapDialog() {
        if (instance == null || teDialogShown || !settings.touchExplore) return;
        teDialogShown = true;
        try {
            playEarcon(Earcons.ALERT);
            announceQueued(getString(R.string.tts_te_dialog_speak));
            AlertDialog d = new AlertDialog.Builder(this,
                    android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(R.string.te_dialog_title)
                    .setMessage(R.string.te_dialog_msg)
                    .setPositiveButton(R.string.te_btn_activate,
                            (dialog, which) -> {
                                dialog.dismiss();
                                teDialog = null;
                                teRemindCount = 0;
                                toggleTouchFlag();
                                systemHandler.postDelayed(
                                        this::verifyTouchExploration, 6000);
                            })
                    .setNeutralButton(R.string.te_btn_settings,
                            (dialog, which) -> {
                                dialog.dismiss();
                                teDialog = null;
                                try {
                                    startActivity(new Intent(
                                            Settings.ACTION_ACCESSIBILITY_SETTINGS));
                                } catch (Exception ignored) { }
                            })
                    .setNegativeButton(R.string.te_btn_exit,
                            (dialog, which) -> {
                                dialog.dismiss();
                                teDialog = null;
                                stopAllInternal();
                                announceWith(Earcons.STOP, R.string.tts_disabled);
                                systemHandler.postDelayed(this::disableSelf, 1200);
                            })
                    .create();
            d.show();
            try {
                d.getWindow().setType(
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY);
            } catch (Exception ignored) { }
            teDialog = d;
        } catch (Exception e) {
            teDialog = null;
        }
    }

    /** Cierra el diálogo propio de exploración táctil si está abierto. */
    private void dismissTeDialog() {
        if (teDialog != null) {
            try { teDialog.dismiss(); } catch (Exception ignored) { }
            teDialog = null;
        }
    }

    // ------------------------------------------------------------------ TTS

    /**
     * Inicia el motor de voz. Si el usuario no eligió ninguno, se usa el
     * motor que el SISTEMA ya tiene configurado, nunca uno impuesto.
     */
    private void initTts(String preferredEngine) {
        ttsInitAttempts++;
        String engine = (preferredEngine != null && !preferredEngine.trim().isEmpty())
                ? preferredEngine.trim()
                : Settings.Secure.getString(getContentResolver(),
                        Settings.Secure.TTS_DEFAULT_SYNTH);
        if (tts != null) {
            try { tts.stop(); tts.shutdown(); } catch (Exception ignored) { }
            tts = null;
            ttsReady = false;
        }
        try {
            if (engine != null && !engine.isEmpty()) {
                activeEnginePackage = engine;
                tts = new TextToSpeech(getApplicationContext(), ttsCallback, engine);
            } else {
                activeEnginePackage = null;
                tts = new TextToSpeech(getApplicationContext(), ttsCallback);
            }
        } catch (Exception e) {
            // Motor elegido no disponible: caemos al motor del sistema.
            activeEnginePackage = null;
            try {
                tts = new TextToSpeech(getApplicationContext(), ttsCallback);
            } catch (Exception e2) {
                tts = null;
                ttsReady = false;
            }
        }
    }

    private final TextToSpeech.OnInitListener ttsCallback =
            new TextToSpeech.OnInitListener() {
        @Override
        public void onInit(int status) {
            if (status == TextToSpeech.SUCCESS && tts != null) {
                ttsReady = true;
                // Idioma y VOZ elegidos por el usuario (o respaldo español);
                // después ritmo y tono. Voz primero, luego ritmo.
                applyVoicePreferences();
                tts.setOnUtteranceProgressListener(progressListener);
                onVoiceReady();
            } else {
                ttsReady = false;
                retryWithAnotherEngine();
            }
        }
    };

    /**
     * Aplica el idioma y la voz elegidos en los ajustes, con respaldos
     * seguros: 1) la voz concreta por nombre (si existe en el motor);
     * 2) el idioma elegido; 3) español; 4) el idioma del sistema.
     * Así la opción «voces del idioma» se cumple en cada arranque.
     */
    private void applyVoicePreferences() {
        if (tts == null || !ttsReady) return;
        try {
            boolean applied = false;
            String vname = settings.voiceName;
            String vloc = settings.voiceLocale;
            if (vname != null && !vname.trim().isEmpty()) {
                Set<Voice> voices = tts.getVoices();
                if (voices != null) {
                    for (Voice v : voices) {
                        if (v == null || v.getName() == null) continue;
                        if (vname.equals(v.getName())) {
                            applied = tts.setVoice(v)
                                    == TextToSpeech.SUCCESS;
                            break;
                        }
                    }
                }
            }
            if (!applied && vloc != null && !vloc.trim().isEmpty()) {
                String[] parts = vloc.split("_");
                Locale loc = (parts.length > 1 && !parts[1].isEmpty())
                        ? new Locale(parts[0], parts[1])
                        : new Locale(parts[0]);
                int r = tts.setLanguage(loc);
                applied = r != TextToSpeech.LANG_MISSING_DATA
                        && r != TextToSpeech.LANG_NOT_SUPPORTED;
            }
            if (!applied) {
                Locale es = new Locale("es", "ES");
                int r = tts.setLanguage(es);
                if (r == TextToSpeech.LANG_MISSING_DATA
                        || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts.setLanguage(new Locale("es"));
                    Locale got = tts.getLanguage();
                    if (got == null) tts.setLanguage(Locale.getDefault());
                }
            }
        } catch (Exception ignored) { }
        tts.setSpeechRate(settings.rate);
        tts.setPitch(settings.pitch);
    }

    /** Primera voz lista: saludo propio + guía si falta la exploración táctil. */
    private void onVoiceReady() {
        if (welcomeSpoken) return;
        welcomeSpoken = true;
        handler.postDelayed(() -> {
            announceWith(Earcons.SUCCESS, getString(R.string.tts_welcome));
            handler.postDelayed(() -> {
                if (instance == null || gestureConfirmed || !settings.touchExplore) return;
                AccessibilityManager am = (AccessibilityManager)
                        getSystemService(ACCESSIBILITY_SERVICE);
                boolean on = am != null && am.isTouchExplorationEnabled();
                if (!on) announceQueued(getString(R.string.tts_te_dialog_hint));
            }, 3000);
        }, 700);
    }

    /** Si el motor elegido no arranca, probar con otros motores instalados. */
    private void retryWithAnotherEngine() {
        if (ttsInitAttempts >= 4) {
            playEarcon(Earcons.ERROR);
            return;
        }
        List<TtsScanner.Info> all = TtsScanner.list(getApplicationContext());
        String found = null;
        for (TtsScanner.Info i : all) {
            if (i.pkg == null || i.pkg.equals(activeEnginePackage)) continue;
            found = i.pkg;
            break;
        }
        final String candidate = found;
        if (candidate != null) {
            if (!engineFallbackAnnounced) {
                engineFallbackAnnounced = true;
                announceQueued(getString(R.string.tts_engine_fallback));
            }
            systemHandler.postDelayed(() -> initTts(candidate), 250);
        } else {
            // No hay NINGÚN motor TTS instalado: no podemos hablar.
            handleNoEngines();
        }
    }

    /** Sin ningún motor TTS: aviso con sonido propio y notificación. */
    private void handleNoEngines() {
        playEarcon(Earcons.ERROR);
        speak(getString(R.string.tts_no_engines), TextToSpeech.QUEUE_FLUSH, "noeng");
        notifyNoTtsEngine();
    }

    /** Notificación para que el usuario instale un motor de voz gratuito. */
    private void notifyNoTtsEngine() {
        try {
            NotificationManager nm = (NotificationManager)
                    getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) return;
            PendingIntent pOpen = PendingIntent.getActivity(this, 21,
                    new Intent(this, MainActivity.class),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(this, CHANNEL_ID)
                    : new Notification.Builder(this);
            b.setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle(getString(R.string.notif_no_tts_title))
                    .setContentText(getString(R.string.notif_no_tts_text))
                    .setContentIntent(pOpen)
                    .setAutoCancel(true);
            nm.notify(NOTIF_ID_NO_TTS, b.build());
        } catch (Exception ignored) { }
    }

    private final UtteranceProgressListener progressListener =
            new UtteranceProgressListener() {
        @Override
        public void onStart(String utteranceId) { }

        @Override
        public void onDone(String utteranceId) {
            handler.post(() -> advanceAfterUtterance(utteranceId));
        }

        @Override
        public void onError(String utteranceId) {
            handler.post(() -> advanceAfterUtterance(utteranceId));
        }
    };

    private void advanceAfterUtterance(String utteranceId) {
        if (mode != MODE_READING) return;
        try {
            if (utteranceId != null && utteranceId.startsWith("voz-")) {
                index = Integer.parseInt(utteranceId.substring(4)) + 1;
            } else {
                index++;
            }
        } catch (NumberFormatException e) {
            index++;
        }
        speakNextInReading();
    }

    private void speak(String text, int queueMode, String utteranceId) {
        if (tts == null || !ttsReady) return;
        tts.speak(text, queueMode, null, utteranceId);
    }

    private void announce(int stringRes) {
        announce(getString(stringRes));
    }

    private void announce(String text) {
        speak(text, TextToSpeech.QUEUE_FLUSH, "ann");
    }

    /** Anuncia en cola (no corta lo que se está diciendo). */
    private void announceQueued(String text) {
        speak(text, TextToSpeech.QUEUE_ADD, "annq");
    }

    // ------------------------------------------------------------------ sonidos propios

    private void playEarcon(int which) {
        if (earcons == null) return;
        earcons.play(which, Math.max(0.05f, settings.soundVolume / 100f));
    }

    private void announceWith(int earcon, int stringRes) {
        announceWith(earcon, getString(stringRes));
    }

    private void announceWith(int earcon, String text) {
        playEarcon(earcon);
        announce(text);
    }

    private void announceQueuedWith(int earcon, String text) {
        playEarcon(earcon);
        announceQueued(text);
    }

    /** Sonido propio de navegación (siguiente/anterior). */
    private void navSound(int earcon) {
        if (!settings.soundOn) return;
        playEarcon(earcon);
    }

    /** Vibración según los ajustes del usuario. */
    private void tick() {
        if (feedback == null) return;
        if (settings.vibOn) feedback.vibrate(settings.vibStrength);
    }

    // ------------------------------------------------------------------ eventos

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        switch (event.getEventType()) {
            case AccessibilityEvent.TYPE_VIEW_HOVER_ENTER:
                // Exploración táctil del sistema: anunciar el elemento bajo
                // el dedo, salvo que nuestro explorador propio (onMotionEvent)
                // ya lo anunciara hace un instante (desduplicación).
                if (settings.touchExplore && mode == MODE_IDLE) {
                    long now = SystemClock.uptimeMillis();
                    String text = firstTextOf(event);
                    String key = text != null ? normalize(text) : "";
                    boolean ourExplorerJustDidIt = now - rawTouchSeenMs < 400;
                    boolean sameAsLast = !key.isEmpty()
                            && key.equals(lastAnnouncedKey)
                            && now - lastAnnouncedKeyMs < 700;
                    if (!ourExplorerJustDidIt || !sameAsLast) {
                        announceEventLikeRealReader(event, "hover", Earcons.FOCUS);
                    }
                }
                break;

            case AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED:
                if (mode == MODE_IDLE && settings.touchExplore
                        && SystemClock.uptimeMillis() >= suppressFocusEchoUntil) {
                    announceEventLikeRealReader(event, "afocus", Earcons.FOCUS);
                }
                break;

            case AccessibilityEvent.TYPE_VIEW_FOCUSED: {
                // SEGUIR EL FOCO: cuando la app mueve el foco (abre un
                // diálogo, salta a un campo, enfoca el primer elemento de
                // un menú), el lector se mueve con él y lo anuncia.
                AccessibilityNodeInfo src = event.getSource();
                if (src == null) break;
                if (SystemClock.uptimeMillis() < suppressFocusEchoUntil) break;
                if (mode == MODE_SWEEP) {
                    syncSweepToNode(src);
                } else if (mode == MODE_IDLE) {
                    announceNode(src, "focus", true, Earcons.FOCUS);
                }
                break;
            }

            case AccessibilityEvent.TYPE_VIEW_CLICKED:
                // Sonido de activación para cada clic (sin voz, no estorba).
                if (mode == MODE_IDLE) navSound(Earcons.CLICK);
                break;

            case AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED:
                handleWindowStateChanged(event);
                break;
            default:
                break;
        }
    }

    // ------------------------------------------------- autoenfoque de pantalla nueva

    /**
     * CAMBIO DE PANTALLA (arquitectura TalkBack): al abrirse una app, un
     * diálogo o un menú nuevo, el foco salta AL PRIMER ELEMENTO de la
     * pantalla nueva. Antes el lector se quedaba enfocado en los botones de
     * la pantalla anterior: había que barrer toda la pantalla vieja para
     * descubrir que la pantalla ya era otra. Aquí solo se captura el título
     * y se programa el reenfoque; el trabajo lo hace applyWindowFocus()
     * tras un retardo corto (deja que la app termine de abrirse y colapsa
     * la ráfaga de eventos de una sola apertura).
     */
    private void handleWindowStateChanged(AccessibilityEvent event) {
        // Título de la pantalla nueva («WhatsApp», el nombre de un diálogo…).
        // Se descarta si el evento es del TECLADO o parece un nombre de
        // paquete: no son títulos que valga la pena anunciar.
        pendingWindowLabel = null;
        String evLabel = firstTextOf(event);
        if (evLabel != null && !evLabel.trim().isEmpty()
                && !isWindowIdInputMethod(event.getWindowId())
                && !evLabel.matches("[a-z0-9_.]+")) {
            pendingWindowLabel = evLabel;
        }
        // Lectura continua: parar si cambiamos de aplicación.
        if (mode == MODE_READING && readingPackage != null) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            String rootPkg = root != null
                    ? String.valueOf(root.getPackageName())
                    : String.valueOf(event.getPackageName());
            if (!readingPackage.equals(rootPkg)) {
                stopAllInternal();
            }
        }
        handler.removeCallbacks(windowFocusApply);
        handler.postDelayed(windowFocusApply, WINDOW_FOCUS_DELAY_MS);
    }

    /** ¿La ventana con ese id es el teclado en pantalla? */
    private boolean isWindowIdInputMethod(int windowId) {
        if (windowId <= 0) return false;
        try {
            List<AccessibilityWindowInfo> wins = getWindows();
            if (wins == null) return false;
            for (AccessibilityWindowInfo w : wins) {
                if (w.getId() == windowId) {
                    return w.getType()
                            == AccessibilityWindowInfo.TYPE_INPUT_METHOD;
                }
            }
        } catch (Exception ignored) { }
        return false;
    }

    /**
     * Ventana de APLICACIÓN en uso: la enfocada (diálogos y menús emergentes
     * ganan) o la de capa más alta. Se excluyen el TECLADO, las capas de
     * accesibilidad y las ventanas del PROPIO VozLibre (su diálogo y su
     * marco verde): nunca nos robamos el foco a nosotros mismos.
     */
    private AccessibilityWindowInfo topAppWindow() {
        try {
            List<AccessibilityWindowInfo> wins = getWindows();
            if (wins == null) return null;
            AccessibilityWindowInfo focusedWin = null;
            AccessibilityWindowInfo topWin = null;
            int topLayer = Integer.MIN_VALUE;
            String me = getPackageName();
            for (AccessibilityWindowInfo w : wins) {
                try {
                    int t = w.getType();
                    if (t == AccessibilityWindowInfo.TYPE_INPUT_METHOD
                            || t == AccessibilityWindowInfo
                                    .TYPE_ACCESSIBILITY_OVERLAY) {
                        continue;
                    }
                    AccessibilityNodeInfo r = w.getRoot();
                    if (r != null
                            && me.equals(String.valueOf(r.getPackageName()))) {
                        continue;
                    }
                    if (w.isFocused() && focusedWin == null) focusedWin = w;
                    if (w.getLayer() > topLayer) {
                        topLayer = w.getLayer();
                        topWin = w;
                    }
                } catch (Exception ignored) { }
            }
            return focusedWin != null ? focusedWin : topWin;
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * AUTOENFOQUE: llevar el foco al PRIMER ELEMENTO de la pantalla que se
     * acaba de abrir y anunciarlo (así al abrir WhatsApp el foco va a un
     * botón de WhatsApp, no queda en la pantalla anterior). En barrido, se
     * recoge la ventana nueva y se salta a su primer elemento. Si el dedo
     * del usuario está en la pantalla, esperar: no pelear con la
     * exploración táctil.
     */
    private void applyWindowFocus() {
        if (instance == null) return;
        if (touchInProgress || twoFingerActive) {
            // El usuario está tocando: reintentar enseguida, sin robarle
            // el foco mientras su dedo explora.
            handler.postDelayed(windowFocusApply, 300);
            return;
        }
        AccessibilityWindowInfo win = topAppWindow();
        if (win == null) return;
        int wid = win.getId();
        AccessibilityNodeInfo root = win.getRoot();
        if (root == null) return;
        String label = pendingWindowLabel;
        pendingWindowLabel = null;
        if (wid > 0 && wid == lastActiveWindowId) {
            // MISMA ventana ya activa: solo refrescar la lista del barrido
            // (manteniendo posición si el elemento sigue ahí) y avisar el
            // título si cambió (panel o página nueva dentro de la app).
            if (mode == MODE_SWEEP) {
                String prevText = index >= 0 && index < nodes.size()
                        ? readableText(nodes.get(index)) : null;
                collectNodes(root, false);
                if (!nodes.isEmpty()) {
                    int found = -1;
                    if (prevText != null) {
                        for (int i = 0; i < nodes.size(); i++) {
                            String t = readableText(nodes.get(i));
                            if (t != null && t.equals(prevText)) {
                                found = i;
                                break;
                            }
                        }
                    }
                    index = found >= 0 ? found : -1;
                }
            }
            announceWindowTitle(label);
            return;
        }
        // TRANSICIÓN REAL: otra pantalla se abrió.
        lastActiveWindowId = wid;
        overlayHideSafe(); // el marco verde ya no corresponde a esta pantalla
        if (mode == MODE_READING) stopAllInternal();
        AccessibilityNodeInfo first = firstReadableNode(root);
        if (first == null) return;
        if (mode == MODE_SWEEP) {
            // Barrido: navegar la pantalla NUEVA desde su primer elemento.
            readingPackage = String.valueOf(root.getPackageName());
            collectNodes(root, false);
            if (nodes.isEmpty()) return;
            index = 0;
            navSound(Earcons.NEXT);
            speakSweepNode();
            return;
        }
        // Reposo: enfocar y anunciar el primer elemento de la pantalla nueva.
        focusedNode = first;
        suppressFocusEchoUntil = SystemClock.uptimeMillis() + 400;
        try {
            first.performAction(AccessibilityNodeInfo
                    .ACTION_ACCESSIBILITY_FOCUS);
        } catch (Exception ignored) { }
        String elemText = readableText(first);
        boolean labelCoversIt = label != null && elemText != null
                && normalize(label).contains(normalize(elemText));
        if (label != null && !label.trim().isEmpty() && !labelCoversIt
                && !label.equalsIgnoreCase(lastSpoken)) {
            // Primero el nombre de la pantalla («WhatsApp»), después el
            // elemento enfocado.
            lastSpoken = label;
            speak(label, TextToSpeech.QUEUE_FLUSH, "winname");
            announceNode(first, "winel", false, Earcons.WINDOW);
        } else {
            announceNode(first, "winel", true, Earcons.WINDOW);
        }
    }

    /** Anuncia el título de una ventana si es nuevo (sin repetir). */
    private void announceWindowTitle(String label) {
        if (label == null || label.trim().isEmpty()) return;
        if (label.equalsIgnoreCase(lastSpoken)) return;
        lastSpoken = label;
        playEarcon(Earcons.WINDOW);
        announce(getString(R.string.tts_window) + ": " + label);
    }

    /**
     * Anuncia un evento como lo haría un lector de pantalla completo:
     * texto + tipo de elemento + estado.
     */
    private void announceEventLikeRealReader(AccessibilityEvent event,
                                             String utteranceId, int earcon) {
        AccessibilityNodeInfo src = event.getSource();
        if (src != null) focusedNode = src;
        String text = firstTextOf(event);
        if (text == null || text.trim().isEmpty()) {
            if (src != null) text = readableText(src);
        }
        if (text == null) text = "";

        String type = typeForClass(event.getClassName());
        String state = stateForEvent(event);
        String speech = text.trim().isEmpty()
                ? type
                : text + ", " + type + (state.isEmpty() ? "" : ", " + state);

        tick();
        navSound(earcon);
        lastSpoken = text;
        lastAnnouncedKey = normalize(text);
        lastAnnouncedKeyMs = SystemClock.uptimeMillis();
        speak(speech, TextToSpeech.QUEUE_FLUSH, utteranceId);
    }

    /** Primer texto útil del registro del evento (o su descripción). */
    private String firstTextOf(AccessibilityEvent event) {
        if (event == null) return null;
        List<CharSequence> texts = event.getText();
        if (texts != null) {
            for (CharSequence t : texts) {
                if (t != null && !t.toString().trim().isEmpty()) return t.toString();
            }
        }
        CharSequence cd = event.getContentDescription();
        if (cd != null && !cd.toString().trim().isEmpty()) return cd.toString();
        return null;
    }

    /** Traduce la clase de la vista a un tipo hablado en español. */
    private String typeForClass(CharSequence className) {
        String c = className != null ? className.toString() : "";
        if (c.contains("EditText") || c.contains("AutoComplete")) return getString(R.string.type_edit);
        if (c.contains("CheckBox")) return getString(R.string.type_checkbox);
        if (c.contains("RadioButton")) return getString(R.string.type_radio);
        if (c.contains("Switch") || c.contains("Toggle")) return getString(R.string.type_switch);
        if (c.contains("SeekBar") || c.contains("Slider")) return getString(R.string.type_slider);
        if (c.contains("Button")) return getString(R.string.type_button);
        if (c.contains("ListView") || c.contains("RecyclerView") || c.contains("GridView")
                || c.contains("ScrollView") || c.contains("ViewPager")) return getString(R.string.type_list);
        if (c.contains("ImageView") || c.contains("Image") || c.contains("Canvas")) return getString(R.string.type_image);
        if (c.contains("WebView") || c.contains("Browser")) return getString(R.string.type_web);
        if (c.contains("TextView") || c.contains("Text")) return getString(R.string.type_text);
        return getString(R.string.type_element);
    }

    /** Estado hablado del elemento del evento: marcado, seleccionado… */
    private String stateForEvent(AccessibilityEvent event) {
        List<String> states = new ArrayList<>();
        try {
            if (event.isChecked()) states.add(getString(R.string.state_checked));
            else if (isCheckableClass(event.getClassName()))
                states.add(getString(R.string.state_not_checked));
            AccessibilityNodeInfo src = event.getSource();
            if (src != null && src.isSelected()) {
                states.add(getString(R.string.state_selected));
            }
            if (!event.isEnabled()) states.add(getString(R.string.state_disabled));
        } catch (Exception ignored) {
        }
        return joinStates(states);
    }

    /** Estado hablado del nodo (barrido): marcado, seleccionado, desactivado… */
    private String stateForNode(AccessibilityNodeInfo n) {
        List<String> states = new ArrayList<>();
        try {
            if (n.isCheckable()) {
                states.add(getString(n.isChecked()
                        ? R.string.state_checked : R.string.state_not_checked));
            } else if (n.isChecked()) {
                states.add(getString(R.string.state_checked));
            }
            if (n.isSelected()) states.add(getString(R.string.state_selected));
            if (!n.isEnabled()) states.add(getString(R.string.state_disabled));
        } catch (Exception ignored) {
        }
        return joinStates(states);
    }

    private String joinStates(List<String> states) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < states.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(states.get(i));
        }
        return sb.toString();
    }

    private boolean isCheckableClass(CharSequence className) {
        String c = className != null ? className.toString() : "";
        return c.contains("CheckBox") || c.contains("RadioButton")
                || c.contains("Switch");
    }

    // ------------------------------------------------- explorador táctil propio

    /**
     * Explorador táctil PROPIO de VozLibre (como el TouchExplorer de
     * TalkBack): recibe los toques crudos y hace su propio hit-test del
     * nodo bajo el dedo, así responde en TODA la pantalla (toca donde toques)
     * y no depende de que las vistas manden eventos hover ni de otro lector.
     */
    @Override
    public void onMotionEvent(MotionEvent event) {
        if (!settings.touchExplore || instance == null) return;
        final int action = event.getActionMasked();
        rawTouchSeenMs = SystemClock.uptimeMillis();
        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                // TOCAR LA PANTALLA DETIENE LA LECTURA CONTINUA (TalkBack):
                // un toque pone silencio al instante para explorar tranquilo.
                if (mode == MODE_READING) stopAllInternal();
                handler.removeCallbacks(longPressCheck);
                twoFingerActive = false;
                frameworkTwoFingerSeen = false;
                touchInProgress = true;
                downX = event.getX();
                downY = event.getY();
                downMs = rawTouchSeenMs;
                movedFar = false;
                longPressSent = false;
                secondTapDown = lastTapUpMs > 0
                        && downMs - lastTapUpMs <= DOUBLE_TAP_MS;
                touchedNode = findNodeAtPoint((int) downX, (int) downY);
                if (secondTapDown) {
                    handler.postDelayed(longPressCheck, LONG_PRESS_MS);
                } else if (touchedNode != null) {
                    // El primer toque anuncia; el segundo (doble toque) no
                    // repite: solo prepara la activación.
                    announceNode(touchedNode, "touch", true, Earcons.FOCUS);
                }
                // Si NO hay nodo bajo el dedo (barra del sistema, hueco),
                // se avisa al soltar SI fue un toque breve: así un deslizamiento
                // que empieza en un hueco no provoca anuncios falsos.
                break;
            }

            case MotionEvent.ACTION_POINTER_DOWN: {
                // Segundo dedo: empieza un gesto de DOS DEDOS (scroll).
                if (event.getPointerCount() >= 2) {
                    twoFingerActive = true;
                    twoFingerStartY = avgPointerY(event);
                    twoFingerLastY = twoFingerStartY;
                    movedFar = true;
                    lastTapUpMs = 0;
                    handler.removeCallbacks(longPressCheck);
                }
                break;
            }

            case MotionEvent.ACTION_MOVE: {
                if (twoFingerActive) {
                    // Arrastre con DOS DEDOS: solo medir la distancia vertical.
                    twoFingerLastY = avgPointerY(event);
                    break;
                }
                float dx = event.getX() - downX;
                float dy = event.getY() - downY;
                if (dx * dx + dy * dy > (float) TAP_SLOP * TAP_SLOP) {
                    movedFar = true;
                    handler.removeCallbacks(longPressCheck);
                }
                // Seguir el dedo: si entra en otro elemento, anunciarlo ya.
                if (rawTouchSeenMs - lastAnnouncedKeyMs >= 90) {
                    AccessibilityNodeInfo n = findNodeAtPoint(
                            (int) event.getX(), (int) event.getY());
                    if (n != null) {
                        String text = readableText(n);
                        String key = (text != null ? normalize(text) : "")
                                + "/" + String.valueOf(n.getClassName());
                        if (!key.equals(lastAnnouncedKey)) {
                            touchedNode = n;
                            announceNode(n, "touchmove", true, Earcons.FOCUS);
                        }
                    }
                }
                break;
            }

            case MotionEvent.ACTION_UP: {
                handler.removeCallbacks(longPressCheck);
                touchInProgress = false;
                if (twoFingerActive) {
                    // Fin del gesto de DOS DEDOS: desplazar la pantalla en la
                    // dirección arrastrada (arriba = avanzar, abajo = retroceder).
                    twoFingerActive = false;
                    lastTapUpMs = 0;
                    float dyTwo = twoFingerLastY - twoFingerStartY;
                    if (Math.abs(dyTwo) >= TWO_FINGER_MIN_PX
                            && !frameworkTwoFingerSeen) {
                        performTwoFingerScroll(dyTwo < 0);
                    }
                    break;
                }
                long upMs = SystemClock.uptimeMillis();
                float dx = event.getX() - downX;
                float dy = event.getY() - downY;
                boolean quickTap = !movedFar && !longPressSent
                        && (upMs - downMs) <= TAP_MAX_MS;
                if (quickTap) {
                    boolean doubleTap = lastTapUpMs > 0
                            && (upMs - lastTapUpMs) <= DOUBLE_TAP_MS
                            && dist2(event.getX(), event.getY(),
                                    lastTapX, lastTapY) <= (float) TAP_SLOP * TAP_SLOP;
                    if (doubleTap) {
                        // DOBLE TOQUE EN CUALQUIER PARTE = activar el elemento
                        // ENFOCADO, caiga el doble toque donde caiga.
                        lastTapUpMs = 0;
                        activateFocusedFromAnywhere(
                                event.getX(), event.getY());
                    } else {
                        // Toque simple: el nodo ya se anunció al tocar.
                        // Si NO había nodo, nunca silencio: barra de estado
                        // (hora y batería), barra de navegación o zona vacía.
                        if (touchedNode == null) {
                            announceSystemArea(event.getX(), event.getY());
                        }
                        lastTapUpMs = upMs;
                        lastTapX = event.getX();
                        lastTapY = event.getY();
                    }
                } else {
                    lastTapUpMs = 0;
                }
                break;
            }

            case MotionEvent.ACTION_CANCEL:
                handler.removeCallbacks(longPressCheck);
                twoFingerActive = false;
                touchInProgress = false;
                lastTapUpMs = 0;
                break;

            default:
                break;
        }
    }

    /** Media de las Y de todos los dedos en contacto. */
    private static float avgPointerY(MotionEvent e) {
        float sum = 0;
        int n = e.getPointerCount();
        for (int i = 0; i < n; i++) sum += e.getY(i);
        return n > 0 ? sum / n : 0;
    }

    private static float dist2(float x1, float y1, float x2, float y2) {
        float dx = x1 - x2;
        float dy = y1 - y2;
        return dx * dx + dy * dy;
    }

    /**
     * Nodo bajo un punto de la pantalla: recorre las ventanas de arriba a
     * abajo (los diálogos y menús emergentes ganan) y dentro de cada una
     * devuelve el nodo más profundo con texto o clicable que contenga el punto.
     */
    private AccessibilityNodeInfo findNodeAtPoint(int x, int y) {
        try {
            List<AccessibilityWindowInfo> wins = getWindows();
            if (wins != null && !wins.isEmpty()) {
                List<AccessibilityWindowInfo> sorted = new ArrayList<>(wins);
                Collections.sort(sorted, (a, b) ->
                        Integer.compare(b.getLayer(), a.getLayer()));
                for (AccessibilityWindowInfo w : sorted) {
                    try {
                        // TODAS las ventanas responden, incluido el TECLADO
                        // (TYPE_INPUT_METHOD): tocar sus teclas las anuncia y
                        // el doble toque las escribe. Antes se saltaba y por
                        // eso el teclado callaba.
                        AccessibilityNodeInfo root = w.getRoot();
                        if (root == null) continue;
                        AccessibilityNodeInfo hit = dfsNodeAtPoint(root, x, y);
                        if (hit != null) return hit;
                    } catch (Exception ignored) { }
                }
                return null;
            }
        } catch (Exception ignored) { }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        return root != null ? dfsNodeAtPoint(root, x, y) : null;
    }

    /** Búsqueda en profundidad del nodo más profundo bajo el punto dado. */
    private AccessibilityNodeInfo dfsNodeAtPoint(AccessibilityNodeInfo node,
                                                 int x, int y) {
        if (node == null) return null;
        try {
            Rect r = new Rect();
            node.getBoundsInScreen(r);
            if (r.isEmpty() || !r.contains(x, y) || !node.isVisibleToUser()) {
                return null;
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo hit = dfsNodeAtPoint(node.getChild(i), x, y);
                if (hit != null) return hit;
            }
            if (readableText(node) != null || node.isClickable()) return node;
        } catch (Exception ignored) { }
        return null;
    }

    /** Anuncia un nodo con su tipo y estado, marco verde, sonido y vibración. */
    private void announceNode(AccessibilityNodeInfo n, String utteranceId,
                              boolean interrupt, int earcon) {
        focusedNode = n; // el doble toque en cualquier parte activa ESTE elemento
        String text = readableText(n);
        String type = typeForClass(n.getClassName());
        if (text == null || text.trim().isEmpty()) {
            text = type;
        } else {
            text = text + ", " + type;
        }
        String state = stateForNode(n);
        if (!state.isEmpty()) text = text + ", " + state;
        // Deslizador: anunciar TAMBIÉN su valor actual («70 por ciento»).        
        if (isSliderNode(n)) {
            String val = sliderValueText(n);
            if (val != null && !val.trim().isEmpty()
                    && !normalize(text).contains(normalize(val))) {
                text = text + ", " + val;
            }
        }
        lastAnnouncedKey = normalize(text);
        lastAnnouncedKeyMs = SystemClock.uptimeMillis();
        overlayShowSafe(n);
        tick();
        navSound(earcon);
        speak(text, interrupt ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD,
                utteranceId);
    }

    /** DOBLE TOQUE: activar el elemento (clic, o el padre clicable cercano). */
    private void activateNode(AccessibilityNodeInfo node, float x, float y) {
        boolean done = false;
        AccessibilityNodeInfo n = node;
        for (int i = 0; i < 6 && n != null && !done; i++) {
            try {
                done = n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            } catch (Exception ignored) { }
            if (!done) {
                AccessibilityNodeInfo parent = n.getParent();
                n = parent;
            }
        }
        if (!done) done = dispatchTap(x, y, 60);
        if (done) {
            navSound(Earcons.CLICK);
        } else {
            playEarcon(Earcons.ERROR);
        }
    }

    /**
     * DOBLE TOQUE EN CUALQUIER PARTE (TalkBack): el clic va al elemento
     * ENFOCADO aunque el doble toque caiga en otro lado de la pantalla.
     * Si hay un campo de edición abajo enfocado y el usuario hace el doble
     * toque arriba o a la izquierda, se activa EL CAMPO. Sin foco válido
     * (se quedó en una pantalla anterior), respaldo: el elemento bajo el
     * dedo; si tampoco hay, un toque real en el punto.
     */
    private void activateFocusedFromAnywhere(float x, float y) {
        AccessibilityNodeInfo target = focusedNode;
        if (target != null) {
            Rect r = new Rect();
            Point s = screenSize();
            try {
                target.getBoundsInScreen(r);
            } catch (Exception ignored) {
                r.setEmpty();
            }
            if (r.isEmpty() || !r.intersects(0, 0, s.x, s.y)) {
                target = null; // el foco quedó en una pantalla anterior
            }
        }
        if (target == null) {
            target = touchedNode != null
                    ? touchedNode
                    : findNodeAtPoint((int) x, (int) y);
        }
        if (target != null) {
            Rect r2 = new Rect();
            try {
                target.getBoundsInScreen(r2);
            } catch (Exception ignored) {
                r2.setEmpty();
            }
            float ax = r2.isEmpty() ? x : r2.exactCenterX();
            float ay = r2.isEmpty() ? y : r2.exactCenterY();
            activateNode(target, ax, ay);
        } else {
            // Zona sin elementos: toque real en el punto (barra del sistema).
            boolean ok = dispatchTap(x, y, 40);
            navSound(ok ? Earcons.CLICK : Earcons.ERROR);
        }
    }

    /** Doble toque y mantener: pulsación larga sobre el elemento. */
    private void performLongPress(AccessibilityNodeInfo node, float x, float y) {
        navSound(Earcons.CLICK);
        boolean done = false;
        try {
            done = node.performAction(
                    AccessibilityNodeInfo.ACTION_LONG_CLICK);
        } catch (Exception ignored) { }
        if (!done) {
            // Respaldo: arrastre nulo sobre el CENTRO del elemento (no sobre
            // el punto del dedo, que puede estar en otro lado de la pantalla).
            Rect r = new Rect();
            try {
                node.getBoundsInScreen(r);
            } catch (Exception ignored) {
                r.setEmpty();
            }
            float lx = r.isEmpty() ? x : r.exactCenterX();
            float ly = r.isEmpty() ? y : r.exactCenterY();
            try {
                Path path = new Path();
                path.moveTo(lx, ly);
                GestureDescription.Builder builder = new GestureDescription.Builder();
                builder.addStroke(new GestureDescription.StrokeDescription(
                        path, 0, 600));
                dispatchGesture(builder.build(), null, null);
            } catch (Exception ignored) { }
        }
    }

    /** Toque sintético en un punto (respaldo de la activación). */
    private boolean dispatchTap(float x, float y, long durationMs) {
        try {
            Path path = new Path();
            path.moveTo(x, y);
            GestureDescription.Builder builder = new GestureDescription.Builder();
            builder.addStroke(new GestureDescription.StrokeDescription(
                    path, 0, durationMs));
            return dispatchGesture(builder.build(), null, null);
        } catch (Exception e) {
            return false;
        }
    }

    // --------------------------------------------------- zonas sin nodo (nunca silencio)

    /**
     * Toca una zona SIN elemento: barra de estado (dice la HORA y la
     * BATERÍA), barra de navegación del sistema o zona vacía. Como TalkBack:
     * la barra del sistema se lee y un hueco avisa; nunca queda mudo.
     */
    private void announceSystemArea(float x, float y) {
        tick();
        navSound(Earcons.FOCUS);
        Point s = screenSize();
        if (y >= s.y - navBarHeightPx()) {
            speak(getString(R.string.tts_navbar),
                    TextToSpeech.QUEUE_FLUSH, "sys");
        } else if (y <= statusBarHeightPx()) {
            int batt = batteryPct();
            String msg = batt >= 0
                    ? getString(R.string.tts_statusbar_time, timeNow(), batt)
                    : getString(R.string.tts_statusbar_nobatt, timeNow());
            speak(msg, TextToSpeech.QUEUE_FLUSH, "sys");
        } else {
            speak(getString(R.string.tts_empty_area),
                    TextToSpeech.QUEUE_FLUSH, "sys");
        }
    }

    /** Dimensión del sistema en px (status_bar_height, navigation_bar_height). */
    private int systemDimenPx(String name, int fallbackDp) {
        try {
            int id = getResources().getIdentifier(name, "dimen", "android");
            if (id > 0) {
                int px = getResources().getDimensionPixelSize(id);
                if (px > 0) return px;
            }
        } catch (Exception ignored) { }
        return Math.round(fallbackDp
                * getResources().getDisplayMetrics().density);
    }

    private int statusBarHeightPx() {
        return systemDimenPx("status_bar_height", 24);
    }

    private int navBarHeightPx() {
        return systemDimenPx("navigation_bar_height", 48);
    }

    /** Hora actual «HH:MM» para leer la barra de estado. */
    private String timeNow() {
        try {
            java.util.Calendar c = java.util.Calendar.getInstance();
            return String.format(Locale.getDefault(), "%02d:%02d",
                    c.get(java.util.Calendar.HOUR_OF_DAY),
                    c.get(java.util.Calendar.MINUTE));
        } catch (Exception e) {
            return "";
        }
    }

    /** Nivel de batería en porcentaje (o -1 si no se pudo leer). */
    private int batteryPct() {
        try {
            BatteryManager bm = getSystemService(BatteryManager.class);
            if (bm != null) {
                int v = bm.getIntProperty(
                        BatteryManager.BATTERY_PROPERTY_CAPACITY);
                if (v > 0) return v;
            }
        } catch (Exception ignored) { }
        return -1;
    }

    // --------------------------------------------------- deslizadores (TalkBack)

    /** ¿Es un control deslizante (SeekBar/slider o con rango definido)? */
    private boolean isSliderNode(AccessibilityNodeInfo n) {
        if (n == null) return false;
        try {
            String c = String.valueOf(n.getClassName());
            if (c.contains("SeekBar") || c.contains("AbsSeekBar")
                    || c.contains("Slider")) return true;
            if (n.getRangeInfo() != null) return true;
        } catch (Exception ignored) { }
        return false;
    }

    /** Elemento actual del barrido (el que está enfocado ahora). */
    private AccessibilityNodeInfo currentSweepNode() {
        if (mode != MODE_SWEEP) return null;
        if (index < 0 || index >= nodes.size()) return null;
        return nodes.get(index);
    }

    /**
     * Ajuste del deslizador ENFOCADO deslizando arriba/abajo, como TalkBack:
     * 1) ACTION_SET_PROGRESS con el nuevo valor (exacto); 2) si el nodo es
     * desplazable, ACTION_SCROLL_FORWARD/BACKWARD; 3) respaldo: arrastre
     * vertical sintético sobre el deslizador. Luego anuncia el valor nuevo.
     */
    private void adjustSlider(boolean increase) {
        AccessibilityNodeInfo node = currentSweepNode();
        if (node == null) return;
        boolean done = false;
        try {
            AccessibilityNodeInfo.RangeInfo ri = node.getRangeInfo();
            if (ri != null) {
                float span = ri.getMax() - ri.getMin();
                float step = span > 0 ? span / 10f : 1f;
                float v = ri.getCurrent() + (increase ? step : -step);
                v = Math.max(ri.getMin(), Math.min(ri.getMax(), v));
                Bundle args = new Bundle();
                args.putFloat(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, v);
                done = node.performAction(AccessibilityNodeInfo
                        .AccessibilityAction.ACTION_SET_PROGRESS.getId(), args);
            }
            if (!done && node.isScrollable()) {
                int act = increase
                        ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                        : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
                done = node.performAction(act);
            }
        } catch (Exception ignored) { }
        if (!done) {
            try {
                Rect r = new Rect();
                node.getBoundsInScreen(r);
                float cx = r.exactCenterX();
                float cy = r.exactCenterY();
                float dist = r.height() > r.width()
                        ? Math.max(80f, r.height() * 0.35f)
                        : Math.max(80f, r.height() * 3f);
                Path path = new Path();
                path.moveTo(cx, cy);
                path.lineTo(cx, cy + (increase ? -dist : dist));
                GestureDescription.Builder b = new GestureDescription.Builder();
                b.addStroke(new GestureDescription.StrokeDescription(path, 0, 250));
                done = dispatchGesture(b.build(), null, null);
            } catch (Exception ignored) { }
        }
        if (done) {
            navSound(Earcons.CLICK);
            handler.postDelayed(this::announceSliderValue, 250);
        } else {
            playEarcon(Earcons.ERROR);
            announce(getString(R.string.slider_adjust_failed));
        }
    }

    /** Anuncia el valor del deslizador enfocado tras el ajuste. */
    private void announceSliderValue() {
        if (mode != MODE_SWEEP) return;
        AccessibilityNodeInfo node = currentSweepNode();
        if (node == null) return;
        try {
            node.refresh();
        } catch (Exception ignored) { }
        String v = sliderValueText(node);
        if (v != null && !v.trim().isEmpty()) {
            speak(v, TextToSpeech.QUEUE_FLUSH, "slider");
        } else {
            announce(getString(R.string.slider_adjusted));
        }
    }

    /**
     * Valor hablado de un deslizador: primero su descripción de estado
     * («70 por ciento» que dan muchas apps); si no, calculado del rango;
     * si no, su texto.
     */
    private String sliderValueText(AccessibilityNodeInfo n) {
        if (n == null) return null;
        try {
            CharSequence sd = n.getStateDescription();
            if (sd != null && !sd.toString().trim().isEmpty()) {
                return sd.toString();
            }
            AccessibilityNodeInfo.RangeInfo ri = n.getRangeInfo();
            if (ri != null) {
                float span = ri.getMax() - ri.getMin();
                int pct = span > 0
                        ? Math.round((ri.getCurrent() - ri.getMin()) / span * 100f)
                        : Math.round(ri.getCurrent());
                return getString(R.string.slider_value, pct);
            }
            return readableText(n);
        } catch (Exception ignored) { }
        return null;
    }

    // ------------------------------------------------------------------ gestos

    /**
     * Gestos de exploración táctil. Al recibir el PRIMER gesto confirmamos
     * con voz y sonido propio que la navegación táctil funciona sola, y
     * ejecutamos la acción un instante después para que se oiga el aviso.
     */
    @SuppressWarnings("deprecation")
    @Override
    protected boolean onGesture(int gestureId) {
        if (!gestureConfirmed) {
            gestureConfirmed = true;
            teRemindCount = 99;
            teOnAnnounced = true;
            announceWith(Earcons.SUCCESS, getString(R.string.tts_te_confirmed));
            // SÚPER RÁPIDO: el primer gesto también se ejecuta al instante
            // (antes esperaba 900 ms y la navegación se sentía lenta).
            systemHandler.postDelayed(() -> executeGesture(gestureId), 250);
            return true;
        }
        executeGesture(gestureId);
        return true;
    }

    private void executeGesture(int gestureId) {
        switch (gestureId) {
            case GESTURE_SWIPE_UP:
                // Con un DESLIZADOR enfocado: subir su valor (TalkBack).
                if (mode == MODE_SWEEP && isSliderNode(currentSweepNode())) {
                    adjustSlider(true);
                    break;
                }
                startReading();
                break;
            case GESTURE_SWIPE_DOWN:
                // Con un DESLIZADOR enfocado: bajar su valor (TalkBack).
                if (mode == MODE_SWEEP && isSliderNode(currentSweepNode())) {
                    adjustSlider(false);
                    break;
                }
                stopAllInternal();
                announceWith(Earcons.STOP, R.string.tts_stopped);
                break;
            case GESTURE_2_FINGER_SWIPE_UP:
                // DOS DEDOS hacia arriba: la pantalla avanza (aparece el
                // contenido de abajo), como el scroll de TalkBack.
                frameworkTwoFingerSeen = true;
                performTwoFingerScroll(true);
                break;
            case GESTURE_2_FINGER_SWIPE_DOWN:
                // DOS DEDOS hacia abajo: la pantalla retrocede.
                frameworkTwoFingerSeen = true;
                performTwoFingerScroll(false);
                break;
            case GESTURE_SWIPE_RIGHT:
                // Siguiente elemento (entra en barrido si estaba en reposo).
                if (mode != MODE_SWEEP) startSweep(false); else sweepNext();
                break;
            case GESTURE_SWIPE_LEFT:
                // Elemento anterior (también entra en barrido, desde el final).
                if (mode == MODE_SWEEP) sweepPrevious(); else startSweep(true);
                break;
            default:
                break;
        }
    }

    // ------------------------------------------------------------------ ventana activa

    /** ¿Seguimos dentro de la app que se está leyendo o barriendo? */
    private boolean activeWindowMatches() {
        if (readingPackage == null) return true;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return true;
        return readingPackage.equals(String.valueOf(root.getPackageName()));
    }

    // ------------------------------------------------------------------ lectura continua

    /** Lee toda la pantalla y arrastra el scroll a su paso. */
    public void startReading() {
        stopAllInternal();
        AccessibilityNodeInfo root = activeRootForCollection();
        if (root == null) {
            announceWith(Earcons.ERROR, R.string.tts_no_content);
            return;
        }
        mode = MODE_READING;
        readingPackage = String.valueOf(root.getPackageName());
        spokenTexts.clear();
        lastSpoken = "";
        collectNodes(root, true);
        if (nodes.isEmpty()) {
            stopAllInternal();
            announceWith(Earcons.ERROR, R.string.tts_no_content);
            return;
        }
        tick();
        announceWith(Earcons.START, R.string.tts_start_reading);
        index = 0;
        handler.postDelayed(this::speakNextInReading, 300);
    }

    private void speakNextInReading() {
        if (mode != MODE_READING) return;
        // Si cambiamos de aplicación, parar aquí (no seguir «pegado»).
        if (!activeWindowMatches()) {
            stopAllInternal();
            return;
        }
        if (index >= nodes.size()) {
            if (settings.scrollDelayMs >= 0) {
                scrollToNextPage();
            } else {
                finishReading(R.string.tts_end_of_content);
            }
            return;
        }
        AccessibilityNodeInfo node = nodes.get(index);
        String text = readableText(node);
        if (text == null || text.trim().isEmpty()
                || text.equalsIgnoreCase(lastSpoken)) {
            index++;
            handler.post(this::speakNextInReading);
            return;
        }
        String norm = normalize(text);
        if (spokenTexts.containsKey(norm)) {
            index++;
            handler.post(this::speakNextInReading);
            return;
        }
        lastSpoken = text;
        spokenTexts.put(norm, Boolean.TRUE);
        overlayShowSafe(node);
        tick();
        navSound(Earcons.NEXT);
        speak(text, TextToSpeech.QUEUE_FLUSH, "voz-" + index);
    }

    /** Desplaza la pantalla para seguir leyendo: el «arrastre» del lector. */
    private void scrollToNextPage() {
        if (mode != MODE_READING) return;
        if (!activeWindowMatches()) {
            stopAllInternal();
            return;
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        boolean scrolled = false;
        if (root != null) {
            AccessibilityNodeInfo scrollable = findScrollable(root);
            if (scrollable != null) {
                scrolled = scrollable.performAction(
                        AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
            }
        }
        if (!scrolled) {
            scrolled = dispatchScrollGesture(true);
        }
        if (scrolled) {
            navSound(Earcons.SCROLL);
            handler.postDelayed(this::afterScroll,
                    Math.max(150, Math.min(settings.scrollDelayMs, 600)));
        } else {
            finishReading(R.string.tts_end_of_content);
        }
    }

    private void afterScroll() {
        if (mode != MODE_READING) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            finishReading(R.string.tts_end_of_content);
            return;
        }
        collectNodes(root, true);
        if (nodes.isEmpty()) {
            finishReading(R.string.tts_end_of_content);
            return;
        }
        index = 0;
        speakNextInReading();
    }

    private AccessibilityNodeInfo findScrollable(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo firstAny = null;
        List<AccessibilityNodeInfo> queue = new ArrayList<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            AccessibilityNodeInfo n = queue.remove(0);
            if (n == null) continue;
            if (n.isVisibleToUser() && n.isScrollable()
                    && (n.getActions()
                       & AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) != 0) {
                String cls = String.valueOf(n.getClassName());
                boolean verticalish = cls.contains("ListView")
                        || cls.contains("RecyclerView")
                        || cls.contains("ScrollView") || cls.contains("WebView")
                        || cls.contains("ViewPager");
                if (verticalish) return n;
                if (firstAny == null) firstAny = n;
            }
            for (int i = 0; i < n.getChildCount(); i++) queue.add(n.getChild(i));
        }
        return firstAny;
    }

    private boolean dispatchScrollGesture(boolean forward) {
        try {
            Point size = screenSize();
            Path path = new Path();
            if (forward) {
                path.moveTo(size.x / 2f, size.y * 0.80f);
                path.lineTo(size.x / 2f, size.y * 0.30f);
            } else {
                path.moveTo(size.x / 2f, size.y * 0.30f);
                path.lineTo(size.x / 2f, size.y * 0.80f);
            }
            GestureDescription.Builder builder = new GestureDescription.Builder();
            builder.addStroke(new GestureDescription.StrokeDescription(path, 0, 250));
            return dispatchGesture(builder.build(), null, null);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * SCROLL CON DOS DEDOS (como TalkBack): desplaza la pantalla hacia
     * arriba o abajo SIN cambiar el elemento enfocado. Deslizar hacia
     * arriba avanza (aparece el contenido de abajo); hacia abajo retrocede.
     * Va al contenedor desplazable del elemento enfocado; si no hay, al
     * principal de la ventana; si no, a un arrastre sintético.
     */
    private void performTwoFingerScroll(boolean forward) {
        if (instance == null) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastTwoFingerScrollMs < 600) {
            return; // ya desplazó el detector de gestos del sistema
        }
        lastTwoFingerScrollMs = now;
        AccessibilityNodeInfo sc = null;
        AccessibilityNodeInfo cur = focusedNode != null
                ? focusedNode : currentSweepNode();
        if (cur != null) sc = findScrollableAncestor(cur);
        if (sc == null) {
            AccessibilityNodeInfo root = activeRootForCollection();
            if (root != null) sc = findScrollable(root);
        }
        int action = forward
                ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
        boolean scrolled = false;
        if (sc != null) {
            try {
                scrolled = sc.performAction(action);
            } catch (Exception ignored) { }
        }
        if (!scrolled) scrolled = dispatchScrollGesture(forward);
        if (scrolled) {
            navSound(Earcons.SCROLL);
        } else {
            playEarcon(Earcons.ERROR);
        }
    }

    private void finishReading(int msgRes) {
        stopAllInternal();
        announceWith(Earcons.STOP, msgRes);
    }

    // ------------------------------------------------------------------ barrido bidireccional

    /** Entra en barrido hacia adelante (uso desde la notificación). */
    public void startSweep() {
        startSweep(false);
    }

    /**
     * El barrido detectó que la app cambió: recoger la ventana NUEVA y
     * anunciar su primer (o último) elemento. Así el usuario no tiene que
     * recorrer la pantalla anterior para darse cuenta de que ya está en la
     * pantalla actual.
     */
    private void restartSweepOnNewWindow(boolean backwards) {
        stopAllInternal();
        AccessibilityNodeInfo root = activeRootForCollection();
        if (root == null) {
            announceWith(Earcons.ERROR, R.string.tts_no_element);
            return;
        }
        mode = MODE_SWEEP;
        readingPackage = String.valueOf(root.getPackageName());
        collectNodes(root, false);
        if (nodes.isEmpty()) {
            mode = MODE_IDLE;
            readingPackage = null;
            announceWith(Earcons.ERROR, R.string.tts_no_element);
            return;
        }
        if (backwards) {
            index = nodes.size() - 1;
            navSound(Earcons.PREV);
        } else {
            index = 0;
            navSound(Earcons.NEXT);
        }
        speakSweepNode();
    }

    /**
     * Barrido elemento por elemento. backwards=false: deslizar a la derecha,
     * empieza por el primer elemento. backwards=true: deslizar a la
     * izquierda, empieza por el último.
     */
    public void startSweep(boolean backwards) {
        stopAllInternal();
        AccessibilityNodeInfo root = activeRootForCollection();
        if (root == null) {
            announceWith(Earcons.ERROR, R.string.tts_no_content);
            return;
        }
        mode = MODE_SWEEP;
        readingPackage = String.valueOf(root.getPackageName());
        collectNodes(root, false);
        if (nodes.isEmpty()) {
            mode = MODE_IDLE;
            readingPackage = null;
            announceWith(Earcons.ERROR, R.string.tts_no_element);
            return;
        }
        index = backwards ? nodes.size() : -1;
        if (backwards) sweepPrevious(); else sweepNext();
    }

    public void sweepNext() {
        if (mode != MODE_SWEEP) return;
        if (!activeWindowMatches()) {
            // Cambió la app mientras barríamos: saltar a la PANTALLA NUEVA
            // (su primer elemento) en vez de quedarnos con la lista vieja.
            restartSweepOnNewWindow(false);
            return;
        }
        if (index + 1 >= nodes.size()) {
            // Fin de los elementos VISIBLES: desplazar la lista para traer
            // los siguientes (antes se quedaba en los mismos cuatro textos).
            if (tryScrollSweep(true)) return;
            AccessibilityNodeInfo root = activeRootForCollection();
            if (root == null) {
                announceWith(Earcons.ERROR, R.string.tts_no_element);
                return;
            }
            collectNodes(root, false);
            if (nodes.isEmpty()) {
                announceWith(Earcons.ERROR, R.string.tts_no_element);
                return;
            }
            index = -1;
        }
        index++;
        navSound(Earcons.NEXT);
        speakSweepNode();
    }

    public void sweepPrevious() {
        if (mode != MODE_SWEEP) return;
        if (!activeWindowMatches()) {
            // Cambió la app mientras barríamos: ir a la pantalla nueva.
            restartSweepOnNewWindow(true);
            return;
        }
        if (index <= 0) {
            // Principio de lo visible: desplazar hacia atrás si se puede.
            if (tryScrollSweep(false)) return;
            AccessibilityNodeInfo root = activeRootForCollection();
            if (root != null) collectNodes(root, false);
            if (nodes.isEmpty()) {
                announceWith(Earcons.ERROR, R.string.tts_no_element);
                return;
            }
            index = nodes.size();
        }
        index--;
        navSound(Earcons.PREV);
        speakSweepNode();
    }

    /**
     * Desplaza la lista que contiene el elemento actual para traer más
     * elementos visibles (arquitectura TalkBack: al navegar más allá del
     * borde, el contenedor con foco se desplaza). Devuelve true si el
     * desplazamiento se lanzó; el barrido continúa cuando termina.
     */
    private boolean tryScrollSweep(boolean forward) {
        AccessibilityNodeInfo sc = null;
        if (index >= 0 && index < nodes.size()) {
            sc = findScrollableAncestor(nodes.get(index));
        }
        if (sc == null) {
            AccessibilityNodeInfo root = activeRootForCollection();
            if (root != null) sc = findScrollable(root);
        }
        int action = forward
                ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
        boolean scrolled = false;
        if (sc != null) {
            try {
                scrolled = sc.performAction(action);
            } catch (Exception ignored) { }
        }
        if (!scrolled) return false;
        navSound(Earcons.SCROLL);
        final boolean fwd = forward;
        handler.postDelayed(() -> {
            if (mode != MODE_SWEEP) return;
            AccessibilityNodeInfo root = activeRootForCollection();
            if (root == null) return;
            collectNodes(root, false);
            if (nodes.isEmpty()) return;
            index = fwd ? -1 : nodes.size();
            if (fwd) sweepNext(); else sweepPrevious();
        }, Math.max(150, Math.min(settings.scrollDelayMs, 600)));
        return true;
    }

    /** Contenedor desplazable que contiene al nodo (padres hacia arriba). */
    private AccessibilityNodeInfo findScrollableAncestor(
            AccessibilityNodeInfo node) {
        try {
            AccessibilityNodeInfo cur = node;
            for (int i = 0; i < 15 && cur != null; i++) {
                if (cur.isScrollable()) return cur;
                cur = cur.getParent();
            }
        } catch (Exception ignored) { }
        return null;
    }

    /**
     * Sincroniza el barrido con el foco que movió la app: si el nodo con
     * foco está en la lista, saltamos a él; si no (diálogo nuevo), recogemos
     * la ventana enfocada y lo buscamos ahí.
     */
    private void syncSweepToNode(AccessibilityNodeInfo src) {
        String text = readableText(src);
        if (text == null) return;
        String key = normalize(text);
        for (int i = 0; i < nodes.size(); i++) {
            String t = readableText(nodes.get(i));
            if (t != null && normalize(t).equals(key)) {
                index = i;
                return;
            }
        }
        AccessibilityNodeInfo root = activeRootForCollection();
        if (root != null) {
            collectNodes(root, false);
            for (int i = 0; i < nodes.size(); i++) {
                String t = readableText(nodes.get(i));
                if (t != null && normalize(t).equals(key)) {
                    index = i;
                    return;
                }
            }
        }
    }

    private void speakSweepNode() {
        if (mode != MODE_SWEEP) return;
        if (index < 0 || index >= nodes.size()) return;
        AccessibilityNodeInfo node = nodes.get(index);
        focusedNode = node;
        String text = readableText(node);
        String type = typeForClass(node.getClassName());
        if (text == null || text.trim().isEmpty()) text = type;
        else text = text + ", " + type;
        String state = stateForNode(node);
        if (!state.isEmpty()) text = text + ", " + state;
        // Deslizador: anunciar TAMBIÉN su valor actual («70 por ciento»).        
        if (isSliderNode(node)) {
            String val = sliderValueText(node);
            if (val != null && !val.trim().isEmpty()
                    && !normalize(text).contains(normalize(val))) {
                text = text + ", " + val;
            }
        }
        overlayShowSafe(node);
        // Evitar el eco: nuestro propio foco no debe re-anunciarse.
        suppressFocusEchoUntil = SystemClock.uptimeMillis() + 350;
        node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS);
        tick();
        speak(text, TextToSpeech.QUEUE_FLUSH, "sweep");
    }

    /**
     * Ventana que se está USANDO ahora: la enfocada (así los diálogos, menús
     * emergentes de la pulsación larga y sus elementos se ven y se anuncian).
     * Respaldo: la ventana activa clásica.
     */
    private AccessibilityNodeInfo activeRootForCollection() {
        try {
            List<AccessibilityWindowInfo> wins = getWindows();
            if (wins != null) {
                AccessibilityWindowInfo focusedWin = null;
                AccessibilityWindowInfo topWin = null;
                int topLayer = Integer.MIN_VALUE;
                for (AccessibilityWindowInfo w : wins) {
                    try {
                        if (w.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                            continue;
                        }
                        if (w.isFocused() && focusedWin == null) focusedWin = w;
                        if (w.getLayer() > topLayer) {
                            topLayer = w.getLayer();
                            topWin = w;
                        }
                    } catch (Exception ignored) { }
                }
                if (focusedWin != null) {
                    AccessibilityNodeInfo r = focusedWin.getRoot();
                    if (r != null) return r;
                }
                if (topWin != null) {
                    AccessibilityNodeInfo r = topWin.getRoot();
                    if (r != null) return r;
                }
            }
        } catch (Exception ignored) { }
        return getRootInActiveWindow();
    }

    /** Primer nodo con texto de un árbol (para anunciar ventanas nuevas). */
    private AccessibilityNodeInfo firstReadableNode(AccessibilityNodeInfo root) {
        if (root == null) return null;
        List<AccessibilityNodeInfo> queue = new ArrayList<>();
        queue.add(root);
        int visited = 0;
        while (!queue.isEmpty() && visited < MAX_NODES) {
            AccessibilityNodeInfo n = queue.remove(0);
            visited++;
            if (n == null) continue;
            if (n.isVisibleToUser() && readableText(n) != null) return n;
            for (int i = 0; i < n.getChildCount(); i++) queue.add(n.getChild(i));
        }
        return null;
    }

    // ------------------------------------------------------------------ recogida de nodos

    private void collectNodes(AccessibilityNodeInfo root, boolean continuousMode) {
        nodes = new ArrayList<>();
        Rect screen = new Rect(0, 0, screenSize().x, screenSize().y);
        collectDfs(root, screen);
        if (continuousMode) {
            List<AccessibilityNodeInfo> fresh = new ArrayList<>();
            for (AccessibilityNodeInfo n : nodes) {
                String t = readableText(n);
                if (t == null) continue;
                if (!spokenTexts.containsKey(normalize(t))) fresh.add(n);
            }
            nodes = fresh;
        }
    }

    private void collectDfs(AccessibilityNodeInfo node, Rect screen) {
        if (node == null || nodes.size() >= MAX_NODES) return;
        try {
            if (node.isVisibleToUser()) {
                Rect r = new Rect();
                node.getBoundsInScreen(r);
                if (!r.isEmpty() && Rect.intersects(r, screen)) {
                    String t = readableText(node);
                    if (t != null) nodes.add(node);
                }
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                collectDfs(node.getChild(i), screen);
            }
        } catch (Exception ignored) {
        }
    }

    private String readableText(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isPassword()) return getString(R.string.tts_password_field);
        CharSequence t = node.getText();
        if (t == null || t.toString().trim().isEmpty()) {
            t = node.getContentDescription();
        }
        if (t == null) return null;
        String s = t.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private String normalize(String s) {
        return s.replaceAll("\\s+", " ").trim().toLowerCase(new Locale("es"));
    }

    private Point screenSize() {
        Point p = new Point(1080, 1920);
        try {
            android.view.WindowManager wm =
                    (android.view.WindowManager) getSystemService(WINDOW_SERVICE);
            @SuppressWarnings("deprecation")
            android.view.Display d = wm.getDefaultDisplay();
            d.getRealSize(p);
        } catch (Exception ignored) {
        }
        return p;
    }

    // ------------------------------------------------------------------ control general

    public void stopAll() {
        boolean wasActive = mode != MODE_IDLE;
        stopAllInternal();
        if (wasActive) announceWith(Earcons.STOP, R.string.tts_stopped);
    }

    private void stopAllInternal() {
        mode = MODE_IDLE;
        handler.removeCallbacksAndMessages(null);
        if (tts != null) tts.stop();
        nodes = new ArrayList<>();
        index = 0;
        lastSpoken = "";
        overlayHideSafe();
    }

    // ------------------------------------------------------------------ resaltado

    private void overlayShowSafe(AccessibilityNodeInfo node) {
        if (overlay == null) return;
        try {
            Rect r = new Rect();
            node.getBoundsInScreen(r);
            overlay.show(r);
        } catch (Exception ignored) {
        }
    }

    private void overlayHideSafe() {
        if (overlay != null) overlay.hide();
    }

    // ------------------------------------------------------------------ ajustes

    public void loadPrefs() {
        Prefs.load(Prefs.get(this), settings);
    }

    /** MainActivity avisa aquí cuando el usuario aplica los cambios. */
    public void applySettings() {
        loadPrefs();
        if (tts != null && ttsReady) {
            // Reaplica idioma + voz + ritmo + tono con los ajustes nuevos.
            applyVoicePreferences();
        }
        applyServiceFlags();
        // Si cambió el motor elegido, reiniciar la voz con el nuevo motor.
        String chosen = settings.engine == null ? "" : settings.engine;
        String current = activeEnginePackage == null ? "" : activeEnginePackage;
        if (!chosen.equals(current)) {
            engineFallbackAnnounced = false;
            ttsInitAttempts = 0;
            welcomeSpoken = true; // no repetir el saludo al cambiar de voz
            initTts(chosen);
        }
    }

    public void testVoice() {
        speak(getString(R.string.tts_test), TextToSpeech.QUEUE_FLUSH, "test");
    }

    @SuppressWarnings("deprecation")
    private void applyServiceFlags() {
        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) return;
        if (settings.touchExplore) {
            info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE;
        } else {
            info.flags &= ~AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE;
        }
        setServiceInfo(info);
    }

    // ------------------------------------------------------------------ actualizaciones

    /** Comprueba actualizaciones y avisa con notificación propia. */
    private void runUpdateCheck() {
        if (instance == null) return;
        try {
            UpdateManager.checkAsync(getApplicationContext(),
                    new UpdateManager.CheckCallback() {
                @Override
                public void onResult(UpdateManager.Info info) {
                    if (info != null) {
                        UpdateManager.notifyUpdateAvailable(
                                getApplicationContext(), info);
                    }
                }

                @Override
                public void onError(String message) { }
            });
        } catch (Exception ignored) { }
        systemHandler.postDelayed(this::runUpdateCheck, UPDATE_CHECK_MS);
    }

    // ------------------------------------------------------------------ notificación

    private void showNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                    getString(R.string.notif_channel),
                    NotificationManager.IMPORTANCE_LOW);
            ch.setDescription(getString(R.string.notif_text));
            nm.createNotificationChannel(ch);
        }

        PendingIntent pOpen = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent pRead = actionPi(ACTION_READ_ALL, 11);
        PendingIntent pNext = actionPi(ACTION_SWEEP_NEXT, 12);
        PendingIntent pStop = actionPi(ACTION_STOP, 13);
        PendingIntent pDisable = actionPi(ACTION_DISABLE, 14);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        b.setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(getString(R.string.notif_text))
                .setContentIntent(pOpen)
                .setOngoing(true)
                .setAutoCancel(false)
                .addAction(android.R.drawable.ic_media_play,
                        getString(R.string.notif_read), pRead)
                .addAction(android.R.drawable.ic_media_next,
                        getString(R.string.notif_next), pNext)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel,
                        getString(R.string.notif_stop), pStop)
                .addAction(android.R.drawable.ic_lock_power_off,
                        getString(R.string.notif_disable), pDisable);
        nm.notify(NOTIF_ID, b.build());
    }

    private PendingIntent actionPi(String action, int requestCode) {
        Intent i = new Intent(this, getClass()).setAction(action);
        return PendingIntent.getService(this, requestCode, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
