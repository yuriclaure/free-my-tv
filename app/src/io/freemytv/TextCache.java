package io.freemytv;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Text kept as small alpha masks on Android 8 to 11 (Ui.LEGACY), drawn tinted with the paint's color. Those TVs' CPUs lay
 * a line of text out again every time it's drawn, and screens drawn on every frame of an animation paid it on every
 * frame: on a 2018 BRAVIA a weather line took ~3 ms, and Settings' list moved at ~40 ms a frame. A mask is made once per
 * text, size, typeface and spacing (the color comes from the paint when drawn), and kept while it's used. Newer TVs draw
 * the text straight away. Only plain paints: one with a shadow layer keeps drawing its text directly.
 */
final class TextCache {
    private TextCache() {}

    private static final int MAX = 400, PAD = 2;

    private static final class Mask {
        Bitmap bitmap;
        int left, top;    // the text's bounds from its origin, minus PAD
        float advance;    // its width, for centered and right-aligned text
    }

    private static final Map<String, Mask> CACHE = new LinkedHashMap<String, Mask>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Mask> e) {
            return size() > MAX;
        }
    };
    private static final Paint TINT = new Paint(Paint.FILTER_BITMAP_FLAG);
    private static final Paint DRAW = new Paint();
    private static final Rect BOUNDS = new Rect();

    /** As c.drawText(s, x, y, p), from a mask on Android 8 to 11. */
    static void draw(Canvas c, String s, float x, float y, Paint p) {
        if (!Ui.LEGACY || s == null || s.isEmpty() || s.length() > 160) {
            if (s != null) c.drawText(s, x, y, p);
            return;
        }
        Mask m = mask(s, p);
        if (m == null) {
            c.drawText(s, x, y, p);
            return;
        }
        float ox = p.getTextAlign() == Paint.Align.CENTER ? m.advance / 2 : p.getTextAlign() == Paint.Align.RIGHT ? m.advance : 0;
        TINT.setColor(p.getColor());
        c.drawBitmap(m.bitmap, Math.round(x - ox) + m.left, Math.round(y) + m.top, TINT);
    }

    private static Mask mask(String s, Paint p) {
        String key = s + '\u0000' + p.getTextSize() + '\u0000' + System.identityHashCode(p.getTypeface()) + '\u0000'
                + p.getLetterSpacing() + '\u0000' + p.getFlags() + '\u0000' + p.getTextSkewX() + '\u0000' + p.getFontFeatureSettings();
        Mask m;
        synchronized (CACHE) {
            m = CACHE.get(key);
        }
        if (m != null) return m;
        synchronized (DRAW) {
            DRAW.set(p);
            DRAW.setTextAlign(Paint.Align.LEFT);
            DRAW.setColor(0xFF000000);
            DRAW.getTextBounds(s, 0, s.length(), BOUNDS);
            int w = BOUNDS.width() + 2 * PAD, h = BOUNDS.height() + 2 * PAD;
            if (w <= 2 * PAD || h <= 2 * PAD || w > 4096 || h > 1024) return null;
            Bitmap full = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            new Canvas(full).drawText(s, PAD - BOUNDS.left, PAD - BOUNDS.top, DRAW);
            m = new Mask();
            m.bitmap = full.extractAlpha();
            full.recycle();
            m.left = BOUNDS.left - PAD;
            m.top = BOUNDS.top - PAD;
            m.advance = DRAW.measureText(s);
        }
        synchronized (CACHE) {
            CACHE.put(key, m);
        }
        return m;
    }
}
