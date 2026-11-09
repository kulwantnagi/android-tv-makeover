package com.kulwant.atvhome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * A soft band of light that glides once across the focused tile when focus lands on it, like the
 * gloss on Apple TV icons. Sits above the tiles; draws nothing while idle.
 */
final class Sheen extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix shift = new Matrix();
    private final Path clip = new Path();
    private final RectF rect = new RectF();
    private ValueAnimator anim;
    private float t = -1;

    Sheen(Context c) {
        super(c);
        // a diagonal band: transparent, light, transparent; moved across with a matrix
        paint.setShader(new LinearGradient(0, 0, 100, 60,
                new int[]{0x00FFFFFF, 0x38FFFFFF, 0x00FFFFFF}, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
    }

    /** Sweeps once across {@code r} (the tile, in this view's coordinates), after {@code delayMs}. */
    void sweep(RectF r, float radius, long delayMs) {
        if (anim != null) anim.cancel();
        rect.set(r);
        clip.reset();
        clip.addRoundRect(r, radius, radius, Path.Direction.CW);
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setStartDelay(delayMs);
        anim.setDuration(750);
        anim.setInterpolator(new DecelerateInterpolator(1.4f));
        anim.addUpdateListener(a -> {
            t = (float) a.getAnimatedValue();
            invalidate();
        });
        anim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator a) {
                t = -1;
                invalidate();
            }
        });
        anim.start();
    }

    void stop() {
        if (anim != null) anim.cancel();
        t = -1;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        if (t < 0 || rect.isEmpty()) return;
        float band = rect.width() * 0.55f;
        // the gradient spans 100 x 60 units: scale it to the band, then slide it from left to right
        shift.setScale(band / 100f, rect.height() / 60f);
        shift.postTranslate(rect.left - band + (rect.width() + band) * t, rect.top);
        paint.getShader().setLocalMatrix(shift);
        c.save();
        c.clipPath(clip);
        c.drawRect(rect, paint);
        c.restore();
    }
}
