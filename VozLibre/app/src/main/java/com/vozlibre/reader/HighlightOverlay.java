package com.vozlibre.reader;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

/**
 * Marco verde que resalta en pantalla el elemento que VozLibre está leyendo
 * (el «rectángulo de foco» de TalkBack, pero de VozLibre).
 *
 * Se dibuja en una capa propia del servicio de accesibilidad
 * (TYPE_ACCESSIBILITY_OVERLAY) que flota sobre TODAS las apps sin robarles
 * los toques. Si el sistema no permite la capa en algún momento, el marco
 * se desactiva en silencio: el lector sigue hablando igual.
 */
public final class HighlightOverlay {

    private static final int STROKE = 6;              // grosor en píxeles
    private static final int COLOR = 0x9900C853;      // verde TalkBack-ish

    private final WindowManager wm;
    private final View view;
    private final WindowManager.LayoutParams params;
    private boolean added = false;
    private volatile boolean released = false;

    /** Vista que solo pinta el rectángulo redondeado. */
    private static final class Frame extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);

        Frame(Context ctx) {
            super(ctx);
            fill.setColor(Color.argb(0x14, 0x00, 0xC8, 0x53)); // relleno suave
            fill.setStyle(Paint.Style.FILL);
            stroke.setColor(COLOR);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(STROKE);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            RectF r = new RectF(STROKE, STROKE,
                    getWidth() - STROKE, getHeight() - STROKE);
            canvas.drawRoundRect(r, 12, 12, fill);
            canvas.drawRoundRect(r, 12, 12, stroke);
        }
    }

    public HighlightOverlay(Context ctx) {
        Context app = ctx != null ? ctx.getApplicationContext() : null;
        WindowManager w = null;
        View v = null;
        WindowManager.LayoutParams lp = null;
        if (app != null) {
            try {
                w = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
                v = new Frame(app);
                lp = new WindowManager.LayoutParams();
                lp.type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
                lp.format = android.graphics.PixelFormat.TRANSLUCENT;
                lp.gravity = Gravity.TOP | Gravity.START;
                lp.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
            } catch (Exception e) {
                w = null;
                v = null;
                lp = null;
            }
        }
        wm = w;
        view = v;
        params = lp;
    }

    /** Mueve el marco al rectángulo dado (coords de pantalla). */
    public void show(Rect r) {
        if (released || wm == null || view == null || params == null
                || r == null || r.isEmpty()) {
            return;
        }
        try {
            params.x = Math.max(0, r.left);
            params.y = Math.max(0, r.top);
            params.width = Math.max(1, r.width());
            params.height = Math.max(1, r.height());
            if (added) {
                wm.updateViewLayout(view, params);
            } else {
                wm.addView(view, params);
                added = true;
            }
        } catch (Exception e) {
            // Capa no permitida ahora: seguir sin marco, sin romper nada.
            added = false;
        }
    }

    /** Oculta el marco (la lectura continúa). */
    public void hide() {
        if (released || wm == null || view == null) return;
        try {
            if (added) {
                wm.removeView(view);
            }
        } catch (Exception ignored) { }
        added = false;
    }

    /** Libera la vista para siempre (al apagar el lector). */
    public void release() {
        released = true;
        hide();
    }

    /** API mínima del teléfono que soporta la capa del servicio. */
    public static boolean supported() {
        return Build.VERSION.SDK_INT >= 22;
    }
}
