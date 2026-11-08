package io.freemytv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The app switcher: a frosted strip down the right edge, over whatever is playing, with every app as a small icon
 * (the shelf's apps first, then the rest A to Z, as in All Apps). The focused icon opens into a row in the app's colour
 * with its name; OK opens it, Back (or the same button again) closes. Exported, so a remote-button mapper can start it:
 * action io.freemytv.action.SWITCH_APPS, or the component io.freemytv/.SwitcherActivity.
 *
 * Like the quick panel, the glass is painted (TVs turn window blur off). Each app's icon is drawn in one style: a
 * white symbol on its colour (res/drawable/sw_*.xml and the shelf's logo_*.xml); apps without one show their own icon.
 * InputSwitcherActivity is the same strip on the left edge, for the TV's inputs: it overrides the hooks below.
 */
public class SwitcherActivity extends Activity {
    // MARK: - What a strip lists and where (InputSwitcherActivity overrides these)

    /** The strip on the screen's left edge (opening to the right) instead of its right. */
    protected boolean leftSide() { return false; }

    protected String titleText() { return Ui.tr("Switch apps"); }

    /** The list, off the main thread. */
    protected List<App> load(Context c) { return apps(c); }

    /**
     * Whether the last list and its icons are also kept on disk, for an opening after Android has ended :home: the app
     * list read cold took ~2.4 s on a 2018 BRAVIA (each app's name loads its resources), and the strip waited for it.
     * Not for the inputs, whose entries carry the TV's input objects.
     */
    protected boolean saved() { return true; }

    /** An entry's icon at `size` px, off the main thread. */
    protected Bitmap makeIcon(Context c, App a, int size) { return drawIcon(c, a, size); }

    /** OK on an entry: true when it opened (the strip then closes). */
    protected boolean openEntry(App a) {
        HomeApps.Item it = new HomeApps.Item();
        it.kind = HomeApps.APP;
        it.pkg = a.pkg;
        it.key = a.pkg;
        it.label = a.label;
        return HomeApps.open(this, it, null);
    }

    /** The strip in a 1920 × 1080 space, and the room beside it that the focused row opens into. */
    private static final float W = 168, OVERHANG = 600;

    private SwitcherView view;
    private Click click;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.init(this);
        click = new Click(this);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        float k = dm.widthPixels / 1920f;
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.gravity = Gravity.TOP | (leftSide() ? Gravity.START : Gravity.END);
        lp.width = Math.round((W + OVERHANG) * k);
        lp.height = WindowManager.LayoutParams.MATCH_PARENT;
        getWindow().setAttributes(lp);
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        overridePendingTransition(0, 0);   // the strip slides in by itself
        view = new SwitcherView(this, k);
        setContentView(view);
    }

    /** The button that opened it, again: closes. */
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        view.close();
    }

    /** Leaving for another app or the home screen closes it. */
    @Override protected void onStop() {
        super.onStop();
        if (!isFinishing()) finish();
    }

    @Override public void finish() {
        super.finish();
        overridePendingTransition(0, 0);
    }

    @Override protected void onDestroy() {
        click.release();
        super.onDestroy();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (view.onKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    // MARK: - The apps and their icons

    /**
     * An entry in the list: its key (an app's package, or "input:" and the input), name, colour, and icon (drawn in the
     * background, null until then). An input also carries its item, and whether anything is plugged into it.
     */
    static final class App {
        final String pkg, label;
        final int color;
        Bitmap icon;
        HomeApps.Item item;
        boolean dim;

        App(String pkg, String label, int color) {
            this.pkg = pkg;
            this.label = label;
            this.color = color;
        }
    }

    /** package → {colour, drawable, how the symbol sits: "mark", "glyph", "wide" or "boxed"}. */
    private static final Map<String, Object[]> ICONS = new HashMap<>();
    private static void icon(String pkg, int color, String drawable, String fit) {
        ICONS.put(pkg, new Object[] {color, drawable, fit});
    }
    static {
        icon("com.netflix.ninja", 0xFFE50914, "sw_netflix", "mark");
        icon("com.google.android.youtube.tv", 0xFFFF0033, "sw_youtube", "mark");
        icon("com.amazon.amazonvideo.livingroom", 0xFF0779FF, "sw_prime", "boxed");
        icon("com.disney.disneyplus", 0xFF1838C8, "logo_disney", "wide");
        icon("com.apple.atve.androidtv.appletv", 0xFF1C1C1E, "sw_apple", "mark");
        icon("com.apple.atve.sony.appletv", 0xFF1C1C1E, "sw_apple", "mark");
        icon("com.spotify.tv.android", 0xFF1DB954, "sw_spotify", "mark");
        icon("com.wbd.stream", 0xFF5C30E8, "sw_hbo", "wide");
        icon("com.bskyb.nowtv.beta", 0xFF14A69B, "logo_now", "wide");
        icon("hosh.io.atv_launchers.iPlayer", 0xFFFF4B95, "sw_iplayer", "mark");
        icon("hosh.io.atv_launchers.my5", 0xFF4B3D7A, "logo_my5", "wide");
        icon("hosh.io.atv_launchers.itvHub", 0xFF19A9C0, "logo_itv", "wide");
        icon("hosh.io.atv_launchers.all4", 0xFF2A2A2E, "sw_channel4", "mark");
        icon("com.discovery.dplay", 0xFF2175D9, "sw_public", "glyph");
        icon("tv.twitch.android.app", 0xFF9146FF, "sw_twitch", "mark");
        icon("com.stremio.one", 0xFF7B5BB5, "sw_stremio", "mark");
        icon("com.globo.globotv", 0xFFFF5A1F, "sw_play_circle", "glyph");
        icon("com.android.vending", 0xFF0B8043, "sw_googleplay", "mark");
        icon("com.neilturner.aerialviews", 0xFF2F6DB5, "sw_landscape", "glyph");
        icon("com.recisio.kfandroid", 0xFF7B2FF7, "sw_mic", "glyph");
        icon("org.localsend.localsend_app", 0xFF00897B, "sw_share", "glyph");
        icon("net.mullvad.mullvadvpn", 0xFF294D73, "sw_mullvad", "mark");
        icon("com.esaba.downloader", 0xFFE8710A, "sw_download", "glyph");
        icon("za.kilowatch.ultimatefilemanager", 0xFF455A64, "sw_folder", "glyph");
    }

    /** The shelf's apps in its order, then every other app A to Z (All Apps' list), without the live TV apps and us. */
    static List<App> apps(Context c) {
        List<App> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Set<String> skip = new HashSet<>(java.util.Arrays.asList(HomeApps.LIVE_TV_APPS));
        skip.add(c.getPackageName());
        Map<String, String> labels = new HashMap<>();
        for (String[] a : HomeApps.launchable(c)) labels.put(a[0], a[1]);
        for (String pkg : HomeApps.shelfPackages(c)) {
            String label = labels.get(pkg);
            if (label == null || skip.contains(pkg) || HomeApps.isHidden(pkg) || !seen.add(pkg)) continue;
            out.add(app(pkg, label));
        }
        List<App> rest = new ArrayList<>();
        for (Map.Entry<String, String> e : labels.entrySet()) {
            String pkg = e.getKey();
            if (skip.contains(pkg) || HomeApps.isHidden(pkg) || seen.contains(pkg)) continue;
            rest.add(app(pkg, e.getValue()));
        }
        Collections.sort(rest, (a, b) -> a.label.toLowerCase(Locale.ROOT).compareTo(b.label.toLowerCase(Locale.ROOT)));
        out.addAll(rest);
        return out;
    }

    /** The app's colour in the switcher (its icon's background), or 0 for an app without a symbol of its own. */
    static int appColor(String pkg) {
        Object[] spec = pkg == null ? null : ICONS.get(pkg);
        return spec == null ? 0 : (int) spec[0];
    }

    /** The app's switcher icon at `size` px (for the home screen's cards: drawn off the UI thread). */
    static Bitmap appIcon(Context c, String pkg, int size) {
        return drawIcon(c, app(pkg, pkg), size);
    }

    private static App app(String pkg, String label) {
        Object[] spec = ICONS.get(pkg);
        return new App(pkg, label, spec != null ? (int) spec[0] : 0xFF3A3A3C);
    }

    /**
     * The app's icon at `size` px: a rounded square in its colour with its white symbol, sized by how the symbol sits
     * (a compact mark, a glyph, a wide wordmark); without one, the app's own icon in the same rounded square.
     */
    static Bitmap drawIcon(Context c, App a, int size) {
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        float r = size / 4f;
        RectF box = new RectF(0, 0, size, size);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Object[] spec = ICONS.get(a.pkg);
        int id = spec == null ? 0 : c.getResources().getIdentifier((String) spec[1], "drawable", c.getPackageName());
        if (id != 0) {
            p.setShader(new LinearGradient(0, 0, 0, size, mix(a.color, 0xFFFFFFFF, 0.12f), a.color, Shader.TileMode.CLAMP));
            cv.drawRoundRect(box, r, r, p);
            Drawable d = c.getDrawable(id);
            String fit = (String) spec[2];
            float maxW = size * ("wide".equals(fit) ? 0.74f : "boxed".equals(fit) ? 0.66f : "glyph".equals(fit) ? 0.62f : 0.58f);
            float maxH = size * ("wide".equals(fit) ? 0.5f : "boxed".equals(fit) ? 0.66f : "glyph".equals(fit) ? 0.62f : 0.58f);
            float iw = d.getIntrinsicWidth(), ih = d.getIntrinsicHeight();
            float s = Math.min(maxW / iw, maxH / ih);
            int w = Math.round(iw * s), h = Math.round(ih * s);
            int left = (size - w) / 2, top = (size - h) / 2;
            d.setBounds(left, top, left + w, top + h);
            d.draw(cv);
        } else {
            // The app's own icon on a white tile: many have a transparent background, which reads as faint on the glass.
            try {
                PackageManager pm = c.getPackageManager();
                Drawable d = pm.getApplicationIcon(a.pkg);
                Bitmap raw = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
                Canvas rc = new Canvas(raw);
                rc.drawColor(0xFFFFFFFF);
                int inset = Math.round(size * 0.12f);
                d.setBounds(inset, inset, size - inset, size - inset);
                d.draw(rc);
                p.setShader(new BitmapShader(raw, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
                cv.drawRoundRect(box, r, r, p);
            } catch (PackageManager.NameNotFoundException | RuntimeException e) {
                p.setColor(a.color);
                cv.drawRoundRect(box, r, r, p);
            }
        }
        // A fine edge every icon has: on the light glass, a white tile needs it to keep its shape.
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1, size / 60f));
        p.setColor(id != 0 ? 0x1FFFFFFF : 0x1A000000);
        box.inset(p.getStrokeWidth() / 2, p.getStrokeWidth() / 2);
        cv.drawRoundRect(box, r, r, p);
        return out;
    }

    /** Each strip's last list and its icons: kept while the process (:home) runs, so the next opening shows at once. */
    private static final Map<Class<?>, List<App>> LAST = new HashMap<>();
    private static final Map<String, Bitmap> ICON_CACHE = new HashMap<>();

    private Bitmap icon(Context c, App a, int size) {
        String key = a.pkg + "@" + size;
        synchronized (ICON_CACHE) {
            Bitmap b = ICON_CACHE.get(key);
            if (b != null) return b;
        }
        Bitmap b;
        try {
            b = makeIcon(c, a, size);
        } catch (RuntimeException | OutOfMemoryError e) {
            AppLog.w("switcher icon " + a.pkg + ": " + e.getMessage());
            return null;
        }
        synchronized (ICON_CACHE) {
            ICON_CACHE.put(key, b);
        }
        return b;
    }

    // MARK: - The copy on disk

    private static java.io.File savedDir(Context c) {
        return new java.io.File(c.getCacheDir(), "switcher");
    }

    /**
     * The list saved last time (null when there's none, or for another size), with the icons of the first `icons`
     * entries (savedIcons reads the others): the first screenful shows without waiting for the rest.
     */
    private static List<App> readSaved(Context c, int size, int icons) {
        java.io.File dir = savedDir(c), list = new java.io.File(dir, "apps-" + size + ".txt");
        if (!list.exists()) return null;
        List<App> out = new ArrayList<>();
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(new java.io.FileInputStream(list), "UTF-8"))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] f = line.split("\t", 3);
                if (f.length < 3) continue;
                App a = new App(f[0], f[2], (int) Long.parseLong(f[1]));
                if (out.size() < icons) savedIcon(dir, a, size);
                out.add(a);
            }
        } catch (java.io.IOException | RuntimeException e) {
            AppLog.w("switcher saved list: " + e.getMessage());
            return null;
        }
        return out.isEmpty() ? null : out;
    }

    /** The icons readSaved left out. */
    private static void savedIcons(Context c, List<App> list, int size) {
        java.io.File dir = savedDir(c);
        for (App a : list) if (a.icon == null) savedIcon(dir, a, size);
    }

    private static void savedIcon(java.io.File dir, App a, int size) {
        Bitmap b = android.graphics.BitmapFactory.decodeFile(new java.io.File(dir, iconName(a.pkg, size)).getPath());
        if (b == null) return;
        synchronized (ICON_CACHE) {
            ICON_CACHE.put(a.pkg + "@" + size, b);
        }
        a.icon = b;
    }

    /** Saves the list and any icon not saved yet (written to a temporary file, then renamed). */
    private static void writeSaved(Context c, List<App> list, int size) {
        java.io.File dir = savedDir(c);
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        try {
            StringBuilder b = new StringBuilder();
            for (App a : list) {
                b.append(a.pkg).append('\t').append(a.color).append('\t').append(a.label.replace('\n', ' ')).append('\n');
                java.io.File icon = new java.io.File(dir, iconName(a.pkg, size));
                if (a.icon != null && !icon.exists()) {
                    java.io.File tmp = new java.io.File(dir, icon.getName() + ".tmp");
                    try (java.io.FileOutputStream o = new java.io.FileOutputStream(tmp)) {
                        a.icon.compress(Bitmap.CompressFormat.PNG, 100, o);
                    }
                    //noinspection ResultOfMethodCallIgnored
                    tmp.renameTo(icon);
                }
            }
            java.io.File tmp = new java.io.File(dir, "apps-" + size + ".tmp");
            try (java.io.FileOutputStream o = new java.io.FileOutputStream(tmp)) {
                o.write(b.toString().getBytes("UTF-8"));
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.renameTo(new java.io.File(dir, "apps-" + size + ".txt"));
        } catch (java.io.IOException | RuntimeException e) {
            AppLog.w("switcher save: " + e.getMessage());
        }
    }

    private static String iconName(String pkg, int size) {
        return pkg.replaceAll("[^A-Za-z0-9._-]", "_") + "@" + size + ".png";
    }

    private static boolean samePackages(List<App> a, List<App> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            App x = a.get(i), y = b.get(i);
            if (!x.pkg.equals(y.pkg) || !x.label.equals(y.label) || x.dim != y.dim) return false;
        }
        return true;
    }

    static int mix(int a, int b, float f) {
        return Color.argb(
                Math.round(Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * f),
                Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * f),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * f),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * f));
    }

    // MARK: - The strip

    private final class SwitcherView extends View {
        private static final float ICON = 66, STEP = 92, TOP = 56, TITLE = 22, TITLE_LINE = 26, TITLE_GAP = 26,
                PAD = 10, PILL_LEFT = 30, NAME_GAP = 20, NAME = 30, PILL_R = 26, BOTTOM = 30;

        private final float k;
        private final boolean left = leftSide();
        private final Handler main = new Handler(Looper.getMainLooper());
        private List<App> apps = new ArrayList<>();
        private Spring[] open = new Spring[0];
        private float[] nameW = new float[0];
        private int focus;
        private boolean closing;
        private final Spring show = new Spring(0.5f, 0.82f, 0);
        private final Spring scroll = new Spring(0.36f, 0.86f, 0);
        private long lastFrame;

        private final String[] title;
        private final Paint titlePaint, namePaint;
        // One paint per job: a shader is drawn at its paint's alpha, so a shared paint left translucent by the last
        // shadow made the glass see-through, by a different amount on every frame (the flicker).
        private final Paint glassPaint = new Paint(), edgePaint = new Paint(), linePaint = new Paint();
        private final Paint pillPaint = new Paint(Paint.ANTI_ALIAS_FLAG), shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint placeholderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Shadows shadows = new Shadows();
        private final RectF rect = new RectF();
        private LinearGradient glass, edgeShadow;

        private final Runnable frame = this::invalidate;

        SwitcherView(Context c, float k) {
            super(c);
            this.k = k;
            // "Switch apps" in two short lines, so the strip stays narrow: split at the first space.
            String t = titleText();
            int space = t.indexOf(' ');
            title = space > 0 ? new String[] {t.substring(0, space), t.substring(space + 1)} : new String[] {t};
            titlePaint = Ui.text(Ui.semibold, TITLE * k, 0xFF1D1D1F);
            titlePaint.setTextAlign(Paint.Align.CENTER);
            namePaint = Ui.text(Ui.semibold, NAME * k, 0xFFFFFFFF);
            namePaint.setTextAlign(left ? Paint.Align.LEFT : Paint.Align.RIGHT);
            setFocusable(true);
            shadowPaint.setMaskFilter(new android.graphics.BlurMaskFilter(24 * k, android.graphics.BlurMaskFilter.Blur.NORMAL));
            placeholderPaint.setColor(0x14000000);
            linePaint.setColor(0x99FFFFFF);
            final Context app = c.getApplicationContext();
            final int size = Math.round(ICON * k);
            // Opened before (the list and icons are kept while :home runs): at once, and the list is read again behind it.
            final List<App> cached;
            synchronized (LAST) { cached = LAST.get(SwitcherActivity.this.getClass()); }
            if (cached != null && cached.size() > 0 && cached.get(0).icon != null && cached.get(0).icon.getWidth() == size) {
                setApps(cached);
                show.target = 1;
            }
            final boolean disk = cached == null && saved();
            new Thread(() -> {
                // Opened cold (:home started again): last time's list from the disk first, at once; then read afresh.
                if (disk) {
                    final List<App> before = readSaved(app, size, 12);
                    if (before != null) {
                        main.post(() -> {
                            if (apps.isEmpty()) replace(before);
                        });
                        savedIcons(app, before, size);
                        main.post(this::invalidate);
                    }
                }
                final List<App> list = load(app);
                // The first screenful of icons before the strip slides in, so it never arrives empty; the rest after.
                int first = Math.min(list.size(), 14);
                for (int i = 0; i < list.size(); i++) {
                    App a = list.get(i);
                    a.icon = icon(app, a, size);
                    if (i == first - 1) main.post(() -> replace(list));
                }
                if (list.isEmpty()) main.post(() -> replace(list));
                main.post(this::invalidate);
                synchronized (LAST) { LAST.put(SwitcherActivity.this.getClass(), list); }
                if (saved()) writeSaved(app, list, size);
            }, "switcher").start();
        }

        /** The list read now: shown (keeping the focused app) unless it's the one already showing. */
        private void replace(List<App> list) {
            show.target = 1;
            if (!samePackages(list, apps)) {
                String focused = focus < apps.size() ? apps.get(focus).pkg : null;
                setApps(list);
                for (int i = 0; focused != null && i < list.size(); i++) {
                    if (list.get(i).pkg.equals(focused)) {
                        open[focus].snap(0);
                        open[focus].target = 0;
                        focus = i;
                        open[i].snap(1);
                        break;
                    }
                }
            } else {
                for (int i = 0; i < list.size(); i++) if (apps.get(i).icon == null) apps.get(i).icon = list.get(i).icon;
            }
            invalidate();
        }

        private void setApps(List<App> list) {
            apps = list;
            open = new Spring[list.size()];
            nameW = new float[list.size()];
            for (int i = 0; i < open.length; i++) {
                open[i] = new Spring(0.34f, 0.82f, 0);
                nameW[i] = namePaint.measureText(list.get(i).label);
            }
            focus = 0;
            if (open.length > 0) open[0].snap(1);
            invalidate();
        }

        @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            // ~98% opaque light glass: with no blur behind it, anything less lets the picture show through as a ghost.
            glass = new LinearGradient(0, 0, 0, h, 0xFAF7F7F9, 0xFAECECF1, Shader.TileMode.CLAMP);
            float edge = left ? W * k : w - W * k, out = left ? edge + 40 * k : edge - 40 * k;
            edgeShadow = new LinearGradient(out, 0, edge, 0, 0x00000000, 0x40000000, Shader.TileMode.CLAMP);
        }

        void close() {
            if (closing) return;
            closing = true;
            show.target = 0;
            invalidate();
        }

        private float listTop() {
            return (TOP + TITLE_LINE * title.length + TITLE_GAP) * k;
        }

        private int visible() {
            return Math.max(1, (int) ((getHeight() - listTop() - BOTTOM * k) / (STEP * k)));
        }

        /** The first row on screen: the list keeps the focused app in the middle when it can. */
        private int first() {
            int v = visible();
            return Math.max(0, Math.min(focus - v / 2, apps.size() - v));
        }

        @Override protected void onDraw(Canvas c) {
            long now = SystemClock.uptimeMillis();
            float dt = lastFrame == 0 ? 0.016f : Math.min(0.05f, (now - lastFrame) / 1000f);
            lastFrame = now;
            scroll.target = first() * STEP * k;
            boolean moving = show.step(dt) | scroll.step(dt);
            for (Spring s : open) moving |= s.step(dt);
            if (closing && show.value < 0.03f) {
                finish();
                return;
            }
            float w = getWidth(), h = getHeight(), stripLeft = left ? 0 : w - W * k, stripRight = stripLeft + W * k;
            float s = Math.max(0, show.value);
            c.save();
            c.translate((left ? -1 : 1) * (1 - s) * (W + 40) * k, 0);
            // The glass, with a soft shadow on the picture along its inner edge and a fine light line on it.
            edgePaint.setShader(edgeShadow);
            if (left) c.drawRect(stripRight, 0, stripRight + 40 * k, h, edgePaint);
            else c.drawRect(stripLeft - 40 * k, 0, stripLeft, h, edgePaint);
            glassPaint.setShader(glass);
            c.drawRect(stripLeft, 0, stripRight, h, glassPaint);
            float line = left ? stripRight - Math.max(1, k) : stripLeft;
            c.drawRect(line, 0, line + Math.max(1, k), h, linePaint);
            float cx = (stripLeft + stripRight) / 2;
            for (int i = 0; i < title.length; i++) {
                c.drawText(title[i], cx, (TOP + TITLE_LINE * (i + 1)) * k - 6 * k, titlePaint);
            }
            drawRows(c, cx);
            c.restore();
            if (moving) postOnAnimation(frame);
            else lastFrame = 0;
        }

        private void drawRows(Canvas c, float cx) {
            float top = listTop(), bottom = getHeight() - BOTTOM * k, step = STEP * k, icon = ICON * k;
            c.save();
            c.clipRect(0, top - 14 * k, getWidth(), bottom + 14 * k);   // the open row's shadow may reach a little past
            int from = Math.max(0, (int) (scroll.value / step) - 1), to = Math.min(apps.size(), from + visible() + 3);
            int focusedDrawn = -1;
            for (int i = from; i < to; i++) {
                if (i == focus) { focusedDrawn = i; continue; }   // the open row last, over its neighbours
                drawRow(c, i, cx, top + i * step - scroll.value + step / 2, icon);
            }
            if (focusedDrawn >= 0) drawRow(c, focusedDrawn, cx, top + focusedDrawn * step - scroll.value + step / 2, icon);
            c.restore();
        }

        private void drawRow(Canvas c, int i, float cx, float cy, float icon) {
            App a = apps.get(i);
            float o = Ui.clamp(open[i].value);
            if (o > 0.005f) {
                // The row opens away from the screen's edge: leftwards on the right strip, rightwards on the left one.
                float pad = PAD * k, full = (PILL_LEFT - PAD + NAME_GAP) * k + nameW[i];
                float left = this.left ? cx - icon / 2 - pad : cx - icon / 2 - pad - full * o;
                float right = this.left ? cx + icon / 2 + pad + full * o : cx + icon / 2 + pad, r = PILL_R * k;
                float t = cy - icon / 2 - pad, b = cy + icon / 2 + pad;
                // A soft blurred shadow under the open row (cached nine-slices on older TVs), then the row in the
                // app's colour.
                rect.set(left, t + 10 * k, right, b + 10 * k);
                shadowPaint.setColor(Ui.alpha(0xFF000000, 0.3f * o));
                shadows.draw(c, rect, r, 24 * k, shadowPaint);
                rect.set(left, t, right, b);
                pillPaint.setColor(Ui.alpha(a.color, Math.min(1, o * 2.5f)));
                c.drawRoundRect(rect, r, r, pillPaint);
                c.save();
                if (this.left) c.clipRect(cx + icon / 2, t, right - pad, b);
                else c.clipRect(left + pad, t, cx - icon / 2, b);
                namePaint.setAlpha(Math.round(255 * Math.min(1, o * 1.6f)));
                float nameX = this.left ? cx + icon / 2 + NAME_GAP * k : cx - icon / 2 - NAME_GAP * k;
                c.drawText(a.label, nameX, cy + Ui.halfCap(namePaint), namePaint);
                c.restore();
            }
            float grow = 1 + 0.06f * o;
            float half = icon * grow / 2;
            rect.set(cx - half, cy - half, cx + half, cy + half);
            if (a.icon != null) {
                bitmapPaint.setAlpha(a.dim && o < 0.5f ? 115 : 255);   // nothing plugged in: faded, until focused
                c.drawBitmap(a.icon, null, rect, bitmapPaint);
            } else {
                c.drawRoundRect(rect, half / 2, half / 2, placeholderPaint);
            }
        }

        boolean onKey(KeyEvent e) {
            if (closing) return true;
            int code = e.getKeyCode();
            boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
            switch (code) {
                case KeyEvent.KEYCODE_BACK:
                    if (!down) close();
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    // Toward the screen's edge closes the strip; the other way does nothing.
                    if (!down && (code == KeyEvent.KEYCODE_DPAD_LEFT) == left) close();
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (down) move(code == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    if (!down && e.getRepeatCount() == 0) select();
                    return true;
                default:
                    return false;
            }
        }

        private void move(int d) {
            if (apps.isEmpty()) return;
            int next = Math.max(0, Math.min(apps.size() - 1, focus + d));
            if (next == focus) return;
            open[focus].target = 0;
            focus = next;
            open[focus].target = 1;
            click.move();
            invalidate();
        }

        private void select() {
            if (apps.isEmpty()) return;
            click.open();
            if (openEntry(apps.get(focus))) finish();
        }
    }
}
