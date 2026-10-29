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
 * VozLibre: lector de pantalla libre (GPLv3).
 *
 *  1. Lectura continua "que arrastra la pantalla a su paso": recorre en voz
 *     alta todos los elementos visibles y, al llegar al final, desplaza el
 *     scroll de la pantalla y sigue leyendo el contenido nuevo.
 *  2. Modo barrido: salta de elemento en elemento con marco verde.
 *  3. Exploración táctil real: anuncia el elemento tocado con su TIPO
 *     («botón», «casilla», «campo de texto»…) y su ESTADO («marcado»,
 *     «seleccionado», «desactivado»).
 *  4. Usa el motor de voz que el sistema tiene configurado (o el que el
 *     usuario elija) — no impone ningún motor.
 *  5. Vibración y sonidos de navegación con fuerza y volumen ajustables.
 *  6. Notificación de control con botones, disponible en cualquier pantalla.
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

    private final Handler handler = new Handler(Looper.getMainLooper());

    private TextToSpeech tts;
    private volatile boolean ttsReady = false;
    private String activeEnginePackage = null;   // motor realmente en uso
    private String pendingEnginePackage = null;  // motor que estamos inicializando
    private int ttsInitAttempts = 0;             // evita bucles si un motor falla
    private boolean engineFallbackDone = false;  // ya caímos al motor del sistema
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

    // ------------------------------------------------------------------ ciclo

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        overlay = new HighlightOverlay(getApplicationContext());
        feedback = new FeedbackHelper(getApplicationContext());
        loadPrefs();
        ttsInitAttempts = 0;
        applyServiceFlags();
        initTts(settings.engine);
        showNotification();
        registerTouchStateListener();
        verifyTouchExploration();
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
        AccessibilityManager am =
                (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        if (am != null) {
            try {
                am.removeTouchExplorationStateChangeListener(touchStateListener);
            } catch (Exception ignored) { }
        }
        stopAllInternal();
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
            if (mode != MODE_SWEEP) startSweep(); else sweepNext();
        } else if (ACTION_STOP.equals(action)) {
            stopAll();
        } else if (ACTION_DISABLE.equals(action)) {
            // Desactiva el servicio por completo desde cualquier pantalla:
            // ya no hace falta buscar el botón «Desactivar» en la app.
            stopAllInternal();
            announce(R.string.tts_disabled);
            handler.postDelayed(this::disableSelf, 1500);
        }
        return START_NOT_STICKY;
    }

    // ------------------------------------------------------------------ TTS

    /**
     * Inicia el motor de voz. Si el usuario no eligió ninguno, se usa el
     * motor que el SISTEMA ya tiene configurado (p. ej. Reconocimiento de
     * voz de Google), nunca uno impuesto por el fabricante.
     */
    private void initTts(String preferredEngine) {
        if (ttsInitAttempts >= 3) return; // un motor roto no debe tumbar el lector
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
        activeEnginePackage = engine;
        pendingEnginePackage = engine;
        try {
            if (engine != null && !engine.isEmpty()) {
                tts = new TextToSpeech(getApplicationContext(), ttsCallback, engine);
            } else {
                pendingEnginePackage = null;
                tts = new TextToSpeech(getApplicationContext(), ttsCallback);
            }
        } catch (Exception e) {
            // Motor elegido no disponible: caemos al motor por defecto.
            activeEnginePackage = null;
            pendingEnginePackage = null;
            tts = new TextToSpeech(getApplicationContext(), ttsCallback);
        }
    }

    private final TextToSpeech.OnInitListener ttsCallback = new TextToSpeech.OnInitListener() {
        @Override
        public void onInit(int status) {
            ttsReady = (status == TextToSpeech.SUCCESS);
            // El motor elegido no arrancó: caemos UNA vez al motor del
            // sistema para no quedarnos mudo.
            if (!ttsReady && !engineFallbackDone
                    && pendingEnginePackage != null && !pendingEnginePackage.isEmpty()) {
                engineFallbackDone = true;
                handler.post(() -> initTts(""));
                return;
            }
            if (ttsReady && tts != null) {
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
            }
        }
    };

    private final UtteranceProgressListener progressListener = new UtteranceProgressListener() {
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

    /** Vibración + sonido de navegación según los ajustes del usuario. */
    private void tick() {
        if (feedback == null) return;
        if (settings.soundOn) feedback.click(settings.soundVolume);
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
                // Anunciar el título de la pantalla nueva (como un lector real).
                if (mode == MODE_IDLE) {
                    overlayHideSafe(); // borra marcos viejos al cambiar de pantalla
                    String title = firstTextOf(event);
                    if (title != null && !title.trim().isEmpty()
                            && !title.equalsIgnoreCase(lastSpoken)) {
                        lastSpoken = title;
                        announce(getString(R.string.tts_window) + ": " + title);
                    }
                }
                if (mode == MODE_READING && readingPackage != null) {
                    AccessibilityNodeInfo root = getRootInActiveWindow();
                    String rootPkg = root != null
                            ? String.valueOf(root.getPackageName())
                            : String.valueOf(event.getPackageName());
                    if (!readingPackage.equals(rootPkg)) {
                        stopAll();
                        announce(R.string.tts_stopped);
                    }
                } else if (mode == MODE_SWEEP && readingPackage != null) {
                    // El barrido tampoco puede «seguir» en otra aplicación.
                    if (!activeWindowMatches()) stopAll();
                }
                break;
            }
            default:
                break;
        }
    }

    /**
     * Anuncia un evento como lo haría un lector de pantalla completo:
     * texto + tipo de elemento + estado. Usa los datos del propio evento
     * (funciona aunque la vista no entregue su nodo) y, si hace falta,
     * recurre al nodo fuente.
     */
    private void announceEventLikeRealReader(AccessibilityEvent event, String utteranceId) {
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

    /** Estado hablado del elemento: marcado, seleccionado, desactivado… */
    private String stateForEvent(AccessibilityEvent event) {
        List<String> states = new ArrayList<>();
        try {
            if (event.isChecked()) states.add(getString(R.string.state_checked));
            else if (isCheckableClass(event.getClassName()))
                states.add(getString(R.string.state_not_checked));
            // "seleccionado" no existe en el registro; se consulta el nodo.
            AccessibilityNodeInfo src = event.getSource();
            if (src != null && src.isSelected()) {
                states.add(getString(R.string.state_selected));
            }
            if (!event.isEnabled()) states.add(getString(R.string.state_disabled));
        } catch (Exception ignored) {
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < states.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(states.get(i));
        }
        return sb.toString();
    }

    private boolean isCheckableClass(CharSequence className) {
        String c = className != null ? className.toString() : "";
        return c.contains("CheckBox") || c.contains("RadioButton") || c.contains("Switch");
    }

    /** Gestos: requieren exploración táctil activada en Android < 14. */
    @SuppressWarnings("deprecation")
    @Override
    protected boolean onGesture(int gestureId) {
        switch (gestureId) {
            case GESTURE_SWIPE_UP:
                startReading();
                return true;
            case GESTURE_SWIPE_DOWN:
                stopAll();
                announce(R.string.tts_stopped);
                return true;
            case GESTURE_SWIPE_RIGHT:
                // Elemento SIGUIENTE (como TalkBack).
                if (mode == MODE_READING) stopAllInternal();
                if (mode != MODE_SWEEP) startSweep(false); else sweepNext();
                return true;
            case GESTURE_SWIPE_LEFT:
                // Elemento ANTERIOR, aunque no estemos en modo barrido.
                if (mode == MODE_READING) stopAllInternal();
                if (mode != MODE_SWEEP) startSweep(true); else sweepPrevious();
                return true;
            default:
                return false;
        }
    }

    // ------------------------------------------------------------------ lectura continua

    /** Lee toda la pantalla y arrastra el scroll a su paso. */
    public void startReading() {
        stopAllInternal();
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            announce(R.string.tts_no_content);
            return;
        }
        mode = MODE_READING;
        readingPackage = String.valueOf(root.getPackageName());
        spokenTexts.clear();
        lastSpoken = "";
        collectNodes(root, true);
        if (nodes.isEmpty()) {
            finishReading(R.string.tts_no_content);
            return;
        }
        tick();
        announce(R.string.tts_start_reading);
        index = 0;
        handler.postDelayed(this::speakNextInReading, 1500);
    }

    private void speakNextInReading() {
        if (mode != MODE_READING) return;
        // Si cambió la ventana activa, la lectura se corta AQUÍ MISMA: no
        // puede seguir hablando una página que ya no está en pantalla.
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
        speak(text, TextToSpeech.QUEUE_FLUSH, "voz-" + index);
    }

    /** Desplaza la pantalla para seguir leyendo: el "arrastre" del lector. */
    private void scrollToNextPage() {
        if (mode != MODE_READING) return;
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
            handler.postDelayed(this::afterScroll, Math.max(300, settings.scrollDelayMs));
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
                    && (n.getActions() & AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) != 0) {
                String cls = String.valueOf(n.getClassName());
                boolean verticalish = cls.contains("ListView") || cls.contains("RecyclerView")
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
        announce(msgRes);
    }

    // ------------------------------------------------------------------ barrido

    public void startSweep() {
        startSweep(false);
    }

    /**
     * Barrido elemento por elemento. backward=true empieza por el último
     * (para deslizar a la IZQUIERDA y retroceder sin haber estado barriendo).
     */
    public void startSweep(boolean backward) {
        stopAllInternal();
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            announce(R.string.tts_no_content);
            return;
        }
        mode = MODE_SWEEP;
        readingPackage = String.valueOf(root.getPackageName());
        collectNodes(root, false);
        if (nodes.isEmpty()) {
            mode = MODE_IDLE;
            announce(R.string.tts_no_element);
            return;
        }
        index = backward ? nodes.size() : -1;
        announce(R.string.tts_sweep_started);
        handler.postDelayed(backward ? this::sweepPrevious : this::sweepNext, 1200);
    }

    public void sweepNext() {
        if (mode != MODE_SWEEP) return;
        if (!activeWindowMatches()) { stopAllInternal(); return; }
        if (index + 1 >= nodes.size()) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) { announce(R.string.tts_no_element); return; }
            collectNodes(root, false);
            if (nodes.isEmpty()) { announce(R.string.tts_no_element); return; }
            index = -1;
        }
        index++;
        speakSweepNode();
    }

    public void sweepPrevious() {
        if (mode != MODE_SWEEP) return;
        if (!activeWindowMatches()) { stopAllInternal(); return; }
        if (index > 0) index--;
        speakSweepNode();
    }

    private void speakSweepNode() {
        if (mode != MODE_SWEEP) return;
        if (index < 0 || index >= nodes.size()) return;
        try {
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
        } catch (Exception ignored) {
        }
    }

    /** ¿La ventana activa sigue siendo la que estamos leyendo/barriendo? */
    private boolean activeWindowMatches() {
        if (readingPackage == null) return true;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        String pkg = root != null ? String.valueOf(root.getPackageName()) : null;
        return readingPackage.equals(pkg);
    }

    /** Estado hablado consultando directamente el nodo. */
    private String stateForNode(AccessibilityNodeInfo node) {
        List<String> states = new ArrayList<>();
        try {
            if (node.isCheckable()) {
                states.add(node.isChecked() ? getString(R.string.state_checked)
                        : getString(R.string.state_not_checked));
            }
            if (node.isSelected()) states.add(getString(R.string.state_selected));
            if (!node.isEnabled()) states.add(getString(R.string.state_disabled));
        } catch (Exception ignored) {
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < states.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(states.get(i));
        }
        return sb.toString();
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
        if (wasActive) announce(R.string.tts_stopped);
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
        Prefs.Holder before = new Prefs.Holder();
        before.engine = activeEnginePackage != null ? activeEnginePackage : "";
        loadPrefs();
        ttsInitAttempts = 0;
        engineFallbackDone = false;
        if (tts != null && ttsReady) {
            tts.setSpeechRate(settings.rate);
            tts.setPitch(settings.pitch);
        }
        applyServiceFlags();
        // Si cambió el motor elegido, reiniciar la voz con el nuevo motor.
        String chosen = settings.engine == null ? "" : settings.engine;
        String current = activeEnginePackage == null ? "" : activeEnginePackage;
        if (!chosen.equals(current)) {
            initTts(chosen);
        }
    }

    public void testVoice() {
        speak(getString(R.string.tts_test), TextToSpeech.QUEUE_FLUSH, "test");
    }

    // ------------------------------------------------- exploración táctil

    private void registerTouchStateListener() {
        try {
            AccessibilityManager am =
                    (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
            if (am != null) am.addTouchExplorationStateChangeListener(touchStateListener);
        } catch (Exception ignored) {
        }
    }

    private final AccessibilityManager.TouchExplorationStateChangeListener touchStateListener =
            new AccessibilityManager.TouchExplorationStateChangeListener() {
                @Override
                public void onTouchExplorationStateChanged(boolean enabled) {
                    if (enabled && settings.touchExplore && mode == MODE_IDLE) {
                        announce(getString(R.string.tts_touch_on_now));
                    }
                }
            };

    /** Avisa en voz alta si la exploración táctil sigue sin estar activa. */
    private void verifyTouchExploration() {
        if (!settings.touchExplore) return;
        handler.postDelayed(() -> {
            if (instance == null) return;
            AccessibilityManager am =
                    (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
            if (am != null && !am.isTouchExplorationEnabled()) {
                announce(getString(R.string.tts_touch_needed));
            }
        }, 5000);
    }

    /**
     * MainActivity la llama cuando el usuario quiere activar la exploración
     * táctil y el aviso de Android no apareció (o lo rechazó): quita y vuelve
     * a poner la bandera para que el sistema vuelva a mostrar su aviso
     * «¿Usar VozLibre con la exploración táctil?».
     */
    public void requestTouchExplorationGrant() {
        AccessibilityManager am =
                (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        if (am != null && am.isTouchExplorationEnabled()) {
            announce(getString(R.string.tts_touch_on_now));
            return;
        }
        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) return;
        info.flags &= ~AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE;
        setServiceInfo(info);
        handler.postDelayed(() -> {
            AccessibilityServiceInfo i2 = getServiceInfo();
            if (i2 == null) return;
            i2.flags |= AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE;
            setServiceInfo(i2);
            verifyTouchExploration();
        }, 300);
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

    // ------------------------------------------------------------------ notificación

    private void showNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                    getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW);
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
                .addAction(android.R.drawable.ic_delete,
                        getString(R.string.notif_disable), pDisable);
        nm.notify(NOTIF_ID, b.build());
    }

    private PendingIntent actionPi(String action, int requestCode) {
        Intent i = new Intent(this, getClass()).setAction(action);
        return PendingIntent.getService(this, requestCode, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
