package com.kulwant.atvhome;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.SparseArray;
import android.util.TypedValue;
import android.widget.TextView;

/** Small helpers: dp, the Outfit variable font at a given weight, and shared drawables. */
final class Ui {
    private static final SparseArray<Typeface> FONTS = new SparseArray<>();

    private Ui() {}

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static Typeface font(Context c, int weight) {
        Typeface t = FONTS.get(weight);
        if (t == null) {
            try {
                t = new Typeface.Builder(c.getAssets(), "fonts/Outfit.ttf")
                        .setFontVariationSettings("'wght' " + weight)
                        .build();
            } catch (Exception e) {
                t = Typeface.create("sans-serif-light", Typeface.NORMAL);
            }
            FONTS.put(weight, t);
        }
        return t;
    }

    static TextView text(Context c, int weight, float sp, int color) {
        TextView t = new TextView(c);
        t.setTypeface(font(c, weight));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setSingleLine(true);
        return t;
    }

    /**
     * Frosted-glass blur for a small bitmap (Android 9 has no RenderEffect): repeated box blurs,
     * which approach a Gaussian. Meant for screen snapshots taken at about 1/8 size.
     */
    static android.graphics.Bitmap blur(android.graphics.Bitmap src, int r, int passes) {
        int w = src.getWidth(), h = src.getHeight();
        int[] a = new int[w * h], b = new int[w * h];
        src.getPixels(a, 0, w, 0, 0, w, h);
        for (int p = 0; p < passes; p++) {
            box(a, b, w, h, r, 1, w);   // rows
            box(b, a, h, w, r, w, 1);   // columns
        }
        android.graphics.Bitmap out = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
        out.setPixels(a, 0, w, 0, 0, w, h);
        return out;
    }

    /** One box pass over `lines` lines of `len` pixels; step = distance between neighbours in a line. */
    private static void box(int[] in, int[] out, int len, int lines, int r, int step, int lineStep) {
        int div = 2 * r + 1;
        for (int l = 0; l < lines; l++) {
            int base = l * lineStep, sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int px = in[base + Math.max(0, Math.min(i, len - 1)) * step];
                sr += px >> 16 & 255;
                sg += px >> 8 & 255;
                sb += px & 255;
            }
            for (int x = 0; x < len; x++) {
                out[base + x * step] = 0xFF000000 | (sr / div) << 16 | (sg / div) << 8 | sb / div;
                int add = in[base + Math.min(x + r + 1, len - 1) * step], rem = in[base + Math.max(x - r, 0) * step];
                sr += (add >> 16 & 255) - (rem >> 16 & 255);
                sg += (add >> 8 & 255) - (rem >> 8 & 255);
                sb += (add & 255) - (rem & 255);
            }
        }
    }

    static GradientDrawable rounded(int fill, float radiusPx, int strokePx, int strokeColor) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(radiusPx);
        if (strokePx > 0) d.setStroke(strokePx, strokeColor);
        return d;
    }
}
