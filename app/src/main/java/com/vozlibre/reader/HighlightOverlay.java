package com.vozlibre.reader;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

/**
 * Marco verde flotante que marca visualmente el elemento que el lector
 * está hablando en cada momento. Se dibuja en una ventana de tipo
 * ACCESSIBILITY_OVERLAY: un lector de pantalla tiene permiso para ello
 * sin necesidad de conceder "dibujar sobre otras apps".
 */
class HighlightOverlay {

    private final WindowManager wm;
    private final Context appContext;
    private final int stroke;
    private View view;
    private WindowManager.LayoutParams params;
    private boolean added = false;

    HighlightOverlay(Context appContext) {
        this.appContext = appContext;
        this.wm = (WindowManager) appContext.getSystemService(Context.WINDOW_SERVICE);
        float density = appContext.getResources().getDisplayMetrics().density;
        this.stroke = (int) (3.5f * density);
    }

    /** Mueve el marco hasta los límites en pantalla del nodo indicado. */
    void show(Rect nodeBounds) {
        if (nodeBounds == null || nodeBounds.isEmpty()) {
            hide();
            return;
        }
        if (!ensureAdded()) return;
        try {
            params.x = nodeBounds.left - stroke;
            params.y = nodeBounds.top - stroke;
            params.width = nodeBounds.width() + 2 * stroke;
            params.height = nodeBounds.height() + 2 * stroke;
            wm.updateViewLayout(view, params);
            view.setVisibility(View.VISIBLE);
        } catch (Exception ignored) {
            // La ventana puede haber sido retirada por el sistema; no es crítico.
        }
    }

    void hide() {
        if (!added) return;
        try {
            wm.removeView(view);
        } catch (Exception ignored) {
        }
        added = false;
    }

    void release() {
        hide();
        view = null;
    }

    private boolean ensureAdded() {
        if (added) return true;
        if (view == null) {
            view = new View(appContext) {
                private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

                @Override
                protected void onDraw(Canvas canvas) {
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(stroke);
                    paint.setColor(0xFF22C55E); // verde destacado
                    float half = stroke / 2f;
                    canvas.drawRect(half, half,
                            getWidth() - half, getHeight() - half, paint);
                }
            };
        }
        try {
            params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.TOP | Gravity.START;
            params.x = 0;
            params.y = 0;
            wm.addView(view, params);
            added = true;
        } catch (Exception e) {
            added = false;
        }
        return added;
    }
}
