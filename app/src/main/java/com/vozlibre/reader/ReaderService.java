package com.vozlibre.reader;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
 *  4. Exploración táctil: anuncia el elemento tocado con su TIPO («botón»,
 *     «casilla»…) y su ESTADO («marcado», «seleccionado», «desactivado»).
 *  5. Sonidos propios (earcons) incluidos en la app: inicio, parada,
 *     navegación, alertas, aciertos y errores. No usa los de otro lector.
 *  6. Motor de voz a elección: lista TODOS los motores instalados y, si el
 *     elegido falla, reintenta con otro disponible.
 *  7. Solo lee y actúa dentro de la app que está leyendo: si cambia de
 *     aplicación se detiene limpiamente (no se queda «pegado» en Chrome).
 *  8. Notificación de control con botones, incluido «Desactivar lector».
 *  9. Busca actualizaciones cada 12 h y avisa con notificación propia.
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
    private AccessibilityManager.TouchExplorationStateChangeListener teListener;

    // ------- motor de voz: reintentos -------
    private int ttsInitAttempts = 0;
    private boolean engineFallbackDone = false;

    // ------------------------------------------------------------------ ciclo

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        overlay = new HighlightOverlay(getApplicationContext());
        feedback = new FeedbackHelper(getApplicationContext());
        earcons = Earcons.get(getApplicationContext());
        loadPrefs();
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
            // Solo avisamos una vez y solo si aún no tenemos gestos confirmados.
            if (!gestureConfirmed && !teOnAnnounced) {
                teOnAnnounced = true;
                teRemindCount = 99;
                announceQueuedWith(Earcons.SUCCESS, getString(R.string.tts_te_on_now));
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
        if (on) return; // activa (nuestra o compartida); esperamos el primer gesto
        teRemindCount++;
        if (teRemindCount > 3) return;
        announceWith(Earcons.ALERT, getString(R.string.tts_touch_needed));
        toggleTouchFlag();
        systemHandler.postDelayed(this::verifyTouchExploration,
                teRemindCount == 1 ? 15000L : 25000L);
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
                Locale es = new Locale("es", "ES");
                int r = tts.setLanguage(es);
                if (r == TextToSpeech.LANG_MISSING_DATA
                        || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts.setLanguage(new Locale("es"));
                    Locale got = tts.getLanguage();
                    if (got == null) tts.setLanguage(Locale.getDefault());
                }
                tts.setSpeechRate(settings.rate);
                tts.setPitch(settings.pitch);
                tts.setOnUtteranceProgressListener(progressListener);
                onVoiceReady();
            } else {
                ttsReady = false;
                retryWithAnotherEngine();
            }
        }
    };

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

    /** Si el motor elegido no arranca, probar con otro motor instalado. */
    private void retryWithAnotherEngine() {
        if (engineFallbackDone || ttsInitAttempts >= 3) {
            playEarcon(Earcons.ERROR);
            return;
        }
        engineFallbackDone = true;
        String other = TtsScanner.firstEngineOtherThan(
                getApplicationContext(), activeEnginePackage);
        if (other != null && !other.equals(activeEnginePackage)) {
            systemHandler.postDelayed(() -> initTts(other), 250);
        } else {
            playEarcon(Earcons.ERROR);
        }
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
                // Exploración táctil: anunciar el elemento bajo el dedo.
                if (settings.touchExplore && mode == MODE_IDLE) {
                    announceEventLikeRealReader(event, "hover");
                }
                break;

            case AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED:
                if (mode == MODE_IDLE && settings.touchExplore) {
                    announceEventLikeRealReader(event, "focus");
                }
                break;

            case AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED: {
                if (mode == MODE_IDLE) {
                    // Limpiar el marco verde que quedó de una ventana anterior.
                    overlayHideSafe();
                    // Anunciar el título de la pantalla nueva (lector real).
                    String title = firstTextOf(event);
                    if (title != null && !title.trim().isEmpty()
                            && !title.equalsIgnoreCase(lastSpoken)) {
                        lastSpoken = title;
                        announce(getString(R.string.tts_window) + ": " + title);
                    }
                }
                if (mode == MODE_READING && readingPackage != null) {
                    // Salimos de la app que estábamos leyendo: detener aquí.
                    AccessibilityNodeInfo root = getRootInActiveWindow();
                    String rootPkg = root != null
                            ? String.valueOf(root.getPackageName())
                            : String.valueOf(event.getPackageName());
                    if (!readingPackage.equals(rootPkg)) {
                        stopAllInternal();
                    }
                }
                break;
            }
            default:
                break;
        }
    }

    /**
     * Anuncia un evento como lo haría un lector de pantalla completo:
     * texto + tipo de elemento + estado.
     */
    private void announceEventLikeRealReader(AccessibilityEvent event,
                                             String utteranceId) {
        String text = firstTextOf(event);
        if (text == null || text.trim().isEmpty()) {
            AccessibilityNodeInfo src = event.getSource();
            if (src != null) text = readableText(src);
        }
        if (text == null) text = "";

        String type = typeForClass(event.getClassName());
        String state = stateForEvent(event);
        String speech = text.trim().isEmpty()
                ? type
                : text + ", " + type + (state.isEmpty() ? "" : ", " + state);

        tick();
        lastSpoken = text;
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
            systemHandler.postDelayed(() -> executeGesture(gestureId), 1400);
            return true;
        }
        executeGesture(gestureId);
        return true;
    }

    private void executeGesture(int gestureId) {
        switch (gestureId) {
            case GESTURE_SWIPE_UP:
                startReading();
                break;
            case GESTURE_SWIPE_DOWN:
                stopAllInternal();
                announceWith(Earcons.STOP, R.string.tts_stopped);
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
        AccessibilityNodeInfo root = getRootInActiveWindow();
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
        handler.postDelayed(this::speakNextInReading, 1500);
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
            scrolled = dispatchScrollGesture();
        }
        if (scrolled) {
            handler.postDelayed(this::afterScroll,
                    Math.max(300, settings.scrollDelayMs));
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

    private boolean dispatchScrollGesture() {
        try {
            Point size = screenSize();
            Path path = new Path();
            path.moveTo(size.x / 2f, size.y * 0.80f);
            path.lineTo(size.x / 2f, size.y * 0.30f);
            GestureDescription.Builder builder = new GestureDescription.Builder();
            builder.addStroke(new GestureDescription.StrokeDescription(path, 0, 250));
            return dispatchGesture(builder.build(), null, null);
        } catch (Exception e) {
            return false;
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
     * Barrido elemento por elemento. backwards=false: deslizar a la derecha,
     * empieza por el primer elemento. backwards=true: deslizar a la
     * izquierda, empieza por el último.
     */
    public void startSweep(boolean backwards) {
        stopAllInternal();
        AccessibilityNodeInfo root = getRootInActiveWindow();
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
            stopAllInternal();
            return;
        }
        if (index + 1 >= nodes.size()) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
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
            stopAllInternal();
            return;
        }
        if (index > 0) index--;
        navSound(Earcons.PREV);
        speakSweepNode();
    }

    private void speakSweepNode() {
        if (mode != MODE_SWEEP) return;
        if (index < 0 || index >= nodes.size()) return;
        AccessibilityNodeInfo node = nodes.get(index);
        String text = readableText(node);
        String type = typeForClass(node.getClassName());
        if (text == null || text.trim().isEmpty()) text = type;
        else text = text + ", " + type;
        String state = stateForNode(node);
        if (!state.isEmpty()) text = text + ", " + state;
        overlayShowSafe(node);
        node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS);
        tick();
        speak(text, TextToSpeech.QUEUE_FLUSH, "sweep");
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
            tts.setSpeechRate(settings.rate);
            tts.setPitch(settings.pitch);
        }
        applyServiceFlags();
        // Si cambió el motor elegido, reiniciar la voz con el nuevo motor.
        String chosen = settings.engine == null ? "" : settings.engine;
        String current = activeEnginePackage == null ? "" : activeEnginePackage;
        if (!chosen.equals(current)) {
            engineFallbackDone = false;
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
