package io.freemytv;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What any app is playing (title, artist or channel, the app), from the media session each app publishes for the remote's
 * play/pause and the Assistant: one way for music, videos, radio and live streams alike. Reading the sessions needs this
 * notification listener allowed (HomeMode does it while Home Settings' Now Playing widget is on); it never reads a
 * notification and asks the system to unbind it at once, so no process stays up for it.
 */
public class NowPlaying extends NotificationListenerService {

    @Override public void onListenerConnected() {
        requestUnbind();
    }

    private static final float[] STILL = {0.55f, 1, 0.75f}, PERIOD_MS = {170, 230, 200};

    /** The bars' width for text of this cap height (drawBars). */
    static float barsWidth(float cap) {
        return cap * 1.12f;
    }

    /** Three level bars before the title, standing on its baseline, `cap` tall: still, or bouncing like a level meter. */
    static void drawBars(Canvas c, float x, float base, float cap, Paint p, boolean moving, RectF r) {
        long t = SystemClock.uptimeMillis();
        for (int i = 0; i < 3; i++) {
            float level = moving ? 0.3f + 0.7f * Math.abs((float) Math.sin(t / PERIOD_MS[i] + i * 1.3f)) : STILL[i];
            float bx = x + i * cap * 0.42f;
            r.set(bx, base - cap * level, bx + cap * 0.28f, base);
            c.drawRoundRect(r, cap * 0.14f, cap * 0.14f, p);
        }
    }

    /** The track's picture (the cover, else the app's art or icon), or null. */
    static android.graphics.Bitmap art(MediaMetadata md) {
        if (md == null) return null;
        android.graphics.Bitmap a = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (a == null) a = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (a == null) a = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        return a;
    }

    /**
     * What the picture looks like, cheaply (its size and 16 pixels): apps like Spotify send a new track's title first and
     * its cover a moment later, in a second update with the same title, so the title alone missed the new cover. Each
     * update brings a new Bitmap object, so this looks at the pixels, not at which object it is.
     */
    static String artSignature(MediaMetadata md) {
        android.graphics.Bitmap a = art(md);
        if (a == null || a.isRecycled()) return "none";
        int w = a.getWidth(), h = a.getHeight(), sum = 17;
        try {
            for (int i = 1; i <= 4; i++) for (int j = 1; j <= 4; j++) sum = sum * 31 + a.getPixel(w * i / 5, h * j / 5);
        } catch (RuntimeException e) {
            return w + "x" + h;
        }
        return w + "x" + h + ":" + Integer.toHexString(sum);
    }

    static ComponentName component(Context c) {
        return new ComponentName(c, NowPlaying.class);
    }

    /** Whether Android lets us read the sessions: our listener is allowed. */
    static boolean allowed(Context c) {
        try {
            c.getSystemService(MediaSessionManager.class).getActiveSessions(component(c));
            return true;
        } catch (SecurityException e) {
            return false;
        }
    }

    private static boolean asked;

    /** A screen's watch: start() when it shows, stop() when it's hidden. text is null while nothing plays. */
    static final class Watch implements MediaSessionManager.OnActiveSessionsChangedListener {
        private final Context context;
        private final Runnable changed;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final List<MediaController> controllers = new ArrayList<>();
        private final Map<String, String> labels = new HashMap<>();   // some players report every second
        private final MediaController.Callback callback = new MediaController.Callback() {
            @Override public void onPlaybackStateChanged(PlaybackState state) { update(); }
            @Override public void onMetadataChanged(MediaMetadata metadata) { update(); }
            @Override public void onSessionDestroyed() { update(); }
        };
        private boolean on;
        volatile String text;
        /** The title alone (the app's package when it gives none): the line's other parts can change every second. */
        volatile String title;
        /**
         * The music app's session for the home screen's Now Playing tab: one playing (or buffering, between songs) that
         * names an artist, which a film or a show doesn't; null when there's none, so the tab goes as soon as the music
         * is paused or stopped. Its state and metadata are read from it.
         */
        volatile MediaController music;
        private String musicKey = "";

        Watch(Context c, Runnable changed) {
            context = c;
            this.changed = changed;
        }

        void start() {
            if (on || !Prefs.nowPlaying()) return;   // off in Home Settings: nothing watched at all
            MediaSessionManager m = context.getSystemService(MediaSessionManager.class);
            try {
                m.addOnActiveSessionsChangedListener(this, component(context), handler);
                on = true;
                onActiveSessionsChanged(m.getActiveSessions(component(context)));
            } catch (SecurityException e) {
                // On, but not allowed (it was on when Original or the watchdog took it back): asked once, as Free My TV
                // mode's switch itself never allows it. A TV that refused it shows the row unavailable instead.
                if (!asked && Capabilities.adb(context) && !Capabilities.refused(context, Capabilities.LISTENER)) {
                    asked = true;
                    HomeMode.nowPlayingAccess(context);
                }
            }
        }

        void stop() {
            if (!on) return;
            on = false;
            context.getSystemService(MediaSessionManager.class).removeOnActiveSessionsChangedListener(this);
            onActiveSessionsChanged(null);
        }

        @Override public void onActiveSessionsChanged(List<MediaController> sessions) {
            for (MediaController mc : controllers) mc.unregisterCallback(callback);
            controllers.clear();
            if (sessions != null && on) {
                for (MediaController mc : sessions) {
                    mc.registerCallback(callback, handler);
                    controllers.add(mc);
                }
            }
            update();
        }

        private void update() {
            boolean musicChanged = updateMusic();
            String now = null, nowTitle = null;
            for (MediaController mc : controllers) {   // most recent first
                PlaybackState s = mc.getPlaybackState();
                int state = s == null ? PlaybackState.STATE_NONE : s.getState();
                // A stream that buffers is still playing: the line doesn't blink back to the prices.
                if (state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING
                        || state == PlaybackState.STATE_CONNECTING) {
                    now = describe(mc);
                    MediaMetadata m = mc.getMetadata();
                    CharSequence t = m == null ? null : m.getDescription().getTitle();
                    nowTitle = now == null ? null : TextUtils.isEmpty(t) ? mc.getPackageName() : t.toString().trim();
                    break;
                }
            }
            if (Objects.equals(now, text) && !musicChanged) return;
            title = nowTitle;
            text = now;
            changed.run();
        }

        /** Picks the music session; true when it, its track, its state or its position (a seek) changed. */
        private boolean updateMusic() {
            MediaController found = null;
            for (MediaController mc : controllers) {
                PlaybackState s = mc.getPlaybackState();
                int state = s == null ? PlaybackState.STATE_NONE : s.getState();
                if (state != PlaybackState.STATE_PLAYING && state != PlaybackState.STATE_BUFFERING
                        && state != PlaybackState.STATE_CONNECTING) continue;
                MediaMetadata m = mc.getMetadata();
                if (m == null || TextUtils.isEmpty(m.getString(MediaMetadata.METADATA_KEY_ARTIST))) continue;
                found = mc;
                break;
            }
            String key = "";
            if (found != null) {
                MediaMetadata m = found.getMetadata();
                PlaybackState s = found.getPlaybackState();
                key = found.getPackageName() + "|" + m.getString(MediaMetadata.METADATA_KEY_TITLE) + "|"
                        + m.getString(MediaMetadata.METADATA_KEY_ARTIST) + "|" + m.getLong(MediaMetadata.METADATA_KEY_DURATION)
                        + "|" + (s == null ? 0 : s.getState()) + "|" + (s == null ? 0 : s.getLastPositionUpdateTime())
                        + "|" + artSignature(m);   // the cover that comes after the title
            }
            music = found;
            if (key.equals(musicKey)) return false;
            musicKey = key;
            return true;
        }

        /** "Title · Artist · App": the parts the app gives, then its name; null when there's none of them. */
        private String describe(MediaController mc) {
            List<String> parts = new ArrayList<>();
            MediaMetadata m = mc.getMetadata();
            MediaDescription d = m == null ? null : m.getDescription();
            if (d != null && !TextUtils.isEmpty(d.getTitle())) parts.add(d.getTitle().toString().trim());
            if (d != null && !TextUtils.isEmpty(d.getSubtitle())) parts.add(d.getSubtitle().toString().trim());
            String app = labels.get(mc.getPackageName());
            if (app == null) {
                try {
                    PackageManager pm = context.getPackageManager();
                    app = pm.getApplicationLabel(pm.getApplicationInfo(mc.getPackageName(), 0)).toString();
                } catch (PackageManager.NameNotFoundException e) {
                    app = "";   // an app without a launcher icon is hidden from us (Android 11+): no name to show
                }
                labels.put(mc.getPackageName(), app);
            }
            if (!app.isEmpty() && !parts.contains(app)) parts.add(app);
            return parts.isEmpty() ? null : TextUtils.join(" · ", parts);
        }
    }
}
