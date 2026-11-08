package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;

import java.util.ArrayList;
import java.util.List;

/**
 * The input switcher: SwitcherActivity's strip on the screen's left edge, listing Live TV and the TV's inputs (as
 * HomeApps.allInputs, the drawer and the quick panel list them). Each is the app switcher's kind of icon, a white symbol
 * on a colour for what's plugged in; an input with nothing plugged in shows faded until focused. OK switches to it.
 * Exported for button mappers: action io.freemytv.action.SWITCH_INPUTS, or io.freemytv/.InputSwitcherActivity.
 */
public class InputSwitcherActivity extends SwitcherActivity {
    @Override protected boolean leftSide() { return true; }

    @Override protected String titleText() { return Ui.tr("Switch inputs"); }

    @Override protected List<App> load(Context c) {
        List<App> out = new ArrayList<>();
        for (HomeApps.Item it : HomeApps.allInputs(c)) {
            App a = new App("input:" + it.key + ":" + it.symbol, it.label, colorFor(it));
            a.item = it;
            a.dim = it.kind == HomeApps.INPUT && !it.connected;
            out.add(a);
        }
        return out;
    }

    /** Live TV orange, a games console indigo, a streamer blue, a sound system pink, any other input teal. */
    private static int colorFor(HomeApps.Item it) {
        if (it.kind == HomeApps.LIVE_TV) return 0xFFFF9F0A;
        String s = it.symbol == null ? "" : it.symbol;
        switch (s) {
            case "gamepad": return 0xFF5E5CE6;
            case "cast": return 0xFF0A84FF;
            case "speaker": return 0xFFFF375F;
            default: return 0xFF30B0C7;
        }
    }

    @Override protected Bitmap makeIcon(Context c, App a, int size) {
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        float r = size / 4f;
        RectF box = new RectF(0, 0, size, size);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, 0, 0, size, mix(a.color, 0xFFFFFFFF, 0.12f), a.color, Shader.TileMode.CLAMP));
        cv.drawRoundRect(box, r, r, p);
        String symbol = a.item != null && a.item.symbol != null ? a.item.symbol : "hdmi";
        Symbols.draw(c, cv, symbol, size / 2f, size / 2f, size * 0.5f, 0xFFFFFFFF);
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1, size / 60f));
        p.setColor(0x1FFFFFFF);
        box.inset(p.getStrokeWidth() / 2, p.getStrokeWidth() / 2);
        cv.drawRoundRect(box, r, r, p);
        return out;
    }

    @Override protected boolean openEntry(App a) {
        return a.item != null && HomeApps.open(this, a.item, null);
    }
}
