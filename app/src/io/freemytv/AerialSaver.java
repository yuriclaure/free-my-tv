package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Aerial screensaver (Home Settings > Screensaver > Aerial videos): the videos Aerial Views plays (Apple's tvOS
 * aerials, Amazon Fire TV's and the Aerial community's, listed in assets/aerial.json), streamed one after another with
 * Android's own player, muted, fading through black between them. On top, quietly: the place and Apple's caption for the
 * moment of the video in the bottom left, the time and date in the bottom right, and while a music app plays, its cover,
 * song and artist in the top right. Home Settings' Aerial videos screen picks the sources, scenes, time of day, quality,
 * and whether the captions and the music show.
 * Apple's server's certificate comes from Apple's own authority: network_security_config trusts it for that host only.
 */
final class AerialSaver extends FrameLayout implements SurfaceHolder.Callback {
    private static final class Video {
        String id, source, name, scene, time;
        String[] urls;
        int[] capAt;
        String[] capText;
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SurfaceView surface;
    private final Overlay overlay;
    /** The music corner's bouncing bars alone (BarsView): the overlay itself redraws only once a second. */
    private final BarsView barsView;
    private final NowPlaying.Watch playing;
    private MediaPlayer player;
    private List<Video> queue = new ArrayList<>();
    /** What played, newest last (← goes back through it). */
    private final List<Video> history = new ArrayList<>();
    private int next, errors;
    private Video current;
    private boolean running, surfaceReady, prepared;
    /** Bumped by stop(): a download that sees it change stops (its .part stays, to go on from next time). */
    private volatile int session;

    // The cache: the first seconds and the end of the next AHEAD videos (AerialSource), kept for next time, so the
    // screensaver opens on a video from the TV and → finds the next one there; the rest of each streams as it plays.
    // Whole videos (300 MB to 1 GB each) no longer go on the TV: on a 2018 BRAVIA they took ~800 MB of its 8 GB, the
    // next one rarely arrived before → (3.2 s to the next picture at 4K, against 0.5 s now), and the download stuttered
    // the video. Within MAX_CACHE, always leaving MIN_FREE.
    private static final long MAX_CACHE = 256L << 20, MIN_FREE = 1024L << 20, HEAD = 16L << 20;
    private static final int AHEAD = 4;
    private static final ExecutorService DOWNLOADS = Executors.newSingleThreadExecutor();
    /** Opens a video's source (its end may come from the network the first time): not on the UI thread. */
    private static final ExecutorService OPEN = Executors.newSingleThreadExecutor();

    AerialSaver(Context c) {
        super(c);
        setBackgroundColor(0xFF000000);
        surface = new SurfaceView(c);
        overlay = new Overlay(c);
        addView(surface, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        addView(overlay, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        barsView = new BarsView(c);
        addView(barsView, new LayoutParams(1, 1));
        barsView.setVisibility(INVISIBLE);
        surface.getHolder().addCallback(this);
        playing = new NowPlaying.Watch(c, overlay::musicChanged);
    }

    void start() {
        if (running) return;
        running = true;
        session++;
        if (Prefs.aerialMusic()) playing.start();
        HomeData.get(getContext()).refreshIfStale();   // the clock's weather
        overlay.tick.run();
        final Context app = getContext().getApplicationContext();
        new Thread(() -> {
            List<Video> list = load(app);
            int at = deck(list);
            handler.post(() -> {
                queue = list;
                next = at;
                if (queue.isEmpty()) AppLog.w("aerial: no videos for these settings");
                play();
            });
        }, "aerial-list").start();
    }

    /**
     * The screensaver is closing (its window fades out next): the video pauses on its frame and everything else stops,
     * but the player is let go only in stop(), once the window is gone. Releasing it here made the video's area see-
     * through for the fade, so the home screen showed under the clock and the caption, with black frames before it.
     */
    /** How long the close's fade to black takes (s). */
    private static final float CLOSE_FADE = 0.2f;

    /**
     * A key closes the screensaver: a quick fade to black over the video and its text, then `done` (finish). Without it
     * the window went at once and its video's area turned see-through, so the clock and the caption floated over the
     * home screen for a moment, between black frames.
     */
    void fadeOut(Runnable done) {
        if (overlay.closeDone != null) return;
        barsView.hide();
        overlay.closeDone = done;
        overlay.curtainTo = 1;
        overlay.invalidate();
        // In case drawing stalls, close anyway.
        postDelayed(() -> {
            Runnable d = overlay.closeDone;
            overlay.closeDone = null;
            if (d != null) d.run();
        }, 600);
    }

    void closing() {
        running = false;
        session++;   // downloads under way stop
        playing.stop();
        handler.removeCallbacksAndMessages(null);
        overlay.removeCallbacks(overlay.tick);
        barsView.hide();
        dropIncoming();
        try {
            if (player != null && prepared) player.pause();
        } catch (IllegalStateException ignored) {
        }
    }

    void stop() {
        running = false;
        session++;   // downloads under way stop
        playing.stop();
        handler.removeCallbacksAndMessages(null);
        overlay.removeCallbacks(overlay.tick);
        release();
    }

    @Override public void surfaceCreated(SurfaceHolder holder) {
        surfaceReady = true;
        play();
    }

    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    }

    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceReady = false;
        release();
    }

    private void release() {
        prepared = false;
        if (player != null) {
            releaseLater(player);
            player = null;
        }
        dropIncoming();
    }

    /** Stopping a 4K decoder can take a few hundred ms: off the UI thread, so closing the screensaver isn't held up. */
    private static final java.util.concurrent.ExecutorService RELEASE = java.util.concurrent.Executors.newSingleThreadExecutor();

    /** Each player's source, closed before the player goes: a read waiting for data would hold up setSurface. */
    private static final java.util.Map<MediaPlayer, AerialSource> SOURCES = new java.util.HashMap<>();

    private static void releaseLater(MediaPlayer mp) {
        AerialSource src;
        synchronized (SOURCES) {
            src = SOURCES.remove(mp);
        }
        if (src != null) {
            try {
                src.close();
            } catch (Exception ignored) {
            }
        }
        try {
            mp.setSurface(null);   // the surface goes with the window: let go of it now
        } catch (RuntimeException ignored) {
        }
        RELEASE.execute(() -> {
            try {
                mp.release();
            } catch (RuntimeException ignored) {
            }
        });
    }

    // The next video is prepared out of sight (no surface yet) while this one keeps playing, and takes the screen only
    // once it's ready: no black wait between videos, at their end or after → or ←.
    private MediaPlayer incoming;
    private Video incomingVideo;
    private boolean incomingReady, swapWanted;
    /** When the video now coming was asked for (→, ←, the last one's end): the log says how long it took. */
    private long askedAt;

    private void dropIncoming() {
        if (incoming != null) releaseLater(incoming);
        incoming = null;
        incomingVideo = null;
        incomingReady = false;
    }

    /** Drops the video prepared ahead (← went back instead): it's next in the list again, not skipped. */
    private void unprepare() {
        if (incomingVideo != null && next > 0 && next <= queue.size() && queue.get(next - 1) == incomingVideo) next--;
        dropIncoming();
    }

    /** What a key did (onKey): handled here, or the screensaver should close, or it's the system's (the volume). */
    static final int KEY_DONE = 0, KEY_CLOSE = 1, KEY_PASS = 2;

    /**
     * The remote: ← → change the video, ↓ opens the info panel (its two buttons then take ← → and OK; ↑ ↓ or Back close
     * it), the volume keys are the system's, and any other key closes the screensaver (the caller does, on the press
     * or the release it prefers).
     */
    int onKey(android.view.KeyEvent e) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == android.view.KeyEvent.ACTION_DOWN;
        if (code == android.view.KeyEvent.KEYCODE_VOLUME_UP || code == android.view.KeyEvent.KEYCODE_VOLUME_DOWN
                || code == android.view.KeyEvent.KEYCODE_VOLUME_MUTE) return KEY_PASS;
        boolean ok = code == android.view.KeyEvent.KEYCODE_DPAD_CENTER || code == android.view.KeyEvent.KEYCODE_ENTER
                || code == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER;
        if (overlay.infoOpen) {
            if (code == android.view.KeyEvent.KEYCODE_DPAD_LEFT || code == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (down) overlay.moveInfo(code == android.view.KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                return KEY_DONE;
            }
            if (ok) {
                if (!down) {
                    if (overlay.infoFocus == 0) hideCurrent();
                    overlay.closeInfo();
                }
                return KEY_DONE;
            }
            if (code == android.view.KeyEvent.KEYCODE_DPAD_UP || code == android.view.KeyEvent.KEYCODE_DPAD_DOWN
                    || code == android.view.KeyEvent.KEYCODE_BACK) {
                if (!down) overlay.closeInfo();
                return KEY_DONE;
            }
            return KEY_CLOSE;
        }
        if (code == android.view.KeyEvent.KEYCODE_DPAD_LEFT || code == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (down) skip(code == android.view.KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
            return KEY_DONE;
        }
        if (code == android.view.KeyEvent.KEYCODE_DPAD_DOWN) {
            // On the release, as the panel closes on one: the same press must not open it and close it again.
            if (!down && current != null) overlay.openInfo();
            return KEY_DONE;
        }
        return KEY_CLOSE;
    }

    /** "Never show this video": remembered (Settings > Screensaver > Hidden videos undoes it), and on to the next one. */
    private void hideCurrent() {
        Video v = current;
        if (v == null) return;
        java.util.Set<String> hidden = Prefs.aerialHidden();
        hidden.add(v.id);
        Prefs.setAerialHidden(hidden);
        int at = queue.indexOf(v);
        if (at >= 0) {
            queue.remove(at);
            if (at < next) next--;
        }
        history.removeIf(x -> x == v);   // ← never comes back to it
        if (queue.isEmpty()) return;
        advance();   // to the one prepared ahead
    }

    /** Waiting for the next video to be ready (the overlay shows a small spinner meanwhile). */
    boolean loading() {
        return swapWanted && !incomingReady;
    }

    /**
     * → the next video, ← the one before (the start of this one when there's none); the hint at the top says so.
     * The video on screen stays until the other one is ready.
     */
    void skip(int dir) {
        overlay.showHint();
        if (!running || queue.isEmpty()) return;
        errors = 0;
        handler.removeCallbacksAndMessages(null);
        if (dir < 0) {
            if (history.size() < 2) {
                try {
                    if (player != null && prepared) player.seekTo(0);
                } catch (IllegalStateException ignored) {
                }
                return;
            }
            history.remove(history.size() - 1);   // this one
            Video before = history.remove(history.size() - 1);   // the one before (it's added back when it plays)
            unprepare();
            askedAt = SystemClock.elapsedRealtime();
            swapWanted = true;
            prepareIncoming(before);
            return;
        }
        advance();
    }

    /** The next video takes over: the one prepared ahead, or the next in the list prepared now. */
    private void advance() {
        askedAt = SystemClock.elapsedRealtime();
        swapWanted = true;
        if (incoming == null) prepareIncoming(nextInList());
        else if (incomingReady) swap();
        overlay.invalidate();
    }

    private void play() {
        if (!running || !surfaceReady || queue.isEmpty() || player != null || incoming != null) return;
        advance();
    }

    /** The next video in the shuffled order (a new shuffle once every one has shown, kept for next time). */
    private Video nextInList() {
        if (next >= queue.size()) {
            Video last = queue.isEmpty() ? null : queue.get(queue.size() - 1);
            Collections.shuffle(queue);
            if (queue.size() > 1 && queue.get(0) == last) Collections.swap(queue, 0, queue.size() - 1);   // never twice in a row
            next = 0;
            saveDeck(0);
        }
        return queue.get(next++);
    }

    /**
     * Puts `list` in the saved order, the videos the settings no longer allow left out and new ones added at the end
     * (shuffled), and returns where to go on from: every video shows once before any comes back, across sessions.
     * The next one in the order is one whose start fetchAhead put on the TV while the last session played: it opens at once.
     */
    private static int deck(List<Video> list) {
        java.util.Map<String, Video> byId = new java.util.HashMap<>();
        for (Video v : list) byId.put(v.id, v);
        List<Video> ordered = new ArrayList<>();
        int at = Prefs.aerialDeckAt(), kept = 0, before = 0;
        String[] saved = Prefs.aerialDeck().isEmpty() ? new String[0] : Prefs.aerialDeck().split(",");
        for (int i = 0; i < saved.length; i++) {
            Video v = byId.remove(saved[i]);
            if (v == null) continue;
            ordered.add(v);
            if (i < at) before++;   // already shown this round
            kept++;
        }
        List<Video> fresh = new ArrayList<>(byId.values());   // new to the order (a source or scene turned on)
        Collections.shuffle(fresh);
        ordered.addAll(fresh);
        int start = kept == 0 ? 0 : before;
        if (start >= ordered.size()) {   // all shown: a new round
            Collections.shuffle(ordered);
            start = 0;
        }
        list.clear();
        list.addAll(ordered);
        saveDeckOf(list, start);
        return start;
    }

    private void saveDeck(int at) {
        saveDeckOf(queue, at);
    }

    private static void saveDeckOf(List<Video> list, int at) {
        StringBuilder ids = new StringBuilder();
        for (Video v : list) ids.append(ids.length() == 0 ? "" : ",").append(v.id);
        Prefs.setAerialDeck(ids.toString(), at);
    }

    /** About 10 s before this video ends, the next one starts preparing (overlay's tick asks), for a seamless switch. */
    void prepareAheadIfNearEnd() {
        int pos = position(), dur = duration();
        if (incoming == null && pos >= 0 && dur > 0 && dur - pos < 10_000) prepareIncoming(nextInList());
    }

    /**
     * Prepares `v` out of sight: from the cache when the whole file is there (an older version's), else through an
     * AerialSource (its start and end from the TV, the rest streamed).
     */
    private void prepareIncoming(final Video v) {
        if (!running) return;
        dropIncoming();
        final String url = url(v);
        final File file = cacheFile(getContext(), url);
        final MediaPlayer mp = new MediaPlayer();
        incoming = mp;
        incomingVideo = v;
        try {
            mp.setVolume(0, 0);   // some videos have a soundtrack: the screensaver stays silent
            mp.setOnPreparedListener(p -> {
                if (p != incoming) return;
                incomingReady = true;
                if (swapWanted) swap();
            });
            mp.setOnErrorListener((p, what, extra) -> {
                if (p != incoming && p != player) return true;   // one let go (its source closed under it)
                AppLog.w("aerial: couldn't play “" + v.name + "” (" + what + "/" + extra + ")");
                forget(file);   // fetched again later
                if (p == incoming) {
                    boolean wanted = swapWanted;
                    dropIncoming();
                    if (wanted) failed();
                } else {   // the one on screen broke: on to the next
                    advance();
                }
                return true;
            });
            if (file.exists()) {
                //noinspection ResultOfMethodCallIgnored
                file.setLastModified(System.currentTimeMillis());   // the cache drops the longest unwatched first
                mp.setDataSource(file.getPath());
                mp.prepareAsync();
                return;
            }
        } catch (Exception e) {
            AppLog.w("aerial: " + e.getMessage());
            boolean wanted = swapWanted;
            dropIncoming();
            if (wanted) failed();
            return;
        }
        OPEN.execute(() -> {
            AerialSource src = null;
            Exception error = null;
            try {
                src = new AerialSource(url, file);
            } catch (Exception e) {
                error = e;
            }
            final AerialSource opened = src;
            final Exception failure = error;
            // View.post, not the handler: skip() clears the handler's queue, and this would be lost with it.
            post(() -> {
                if (mp != incoming) {
                    if (opened != null) opened.close();
                    return;
                }
                try {
                    if (opened == null) throw failure;
                    synchronized (SOURCES) {
                        SOURCES.put(mp, opened);
                    }
                    mp.setDataSource(opened);
                    mp.prepareAsync();
                } catch (Exception e) {
                    AppLog.w("aerial: " + e.getMessage());
                    boolean wanted = swapWanted;
                    dropIncoming();
                    if (wanted) failed();
                }
            });
        });
    }

    /** A video's files in the cache, gone (a broken download): its start and end are fetched again next time. */
    private static void forget(File file) {
        for (File f : new File[] {file, new File(file.getPath() + ".part"), new File(file.getPath() + ".tail")}) {
            if (f.exists() && !f.delete()) AppLog.w("aerial: couldn't drop a broken file");
        }
    }

    /** The prepared video takes the screen: the old one goes, a soft dip through black, and the next ones download. */
    private void swap() {
        if (!surfaceReady || incoming == null) return;
        MediaPlayer mp = incoming;
        Video v = incomingVideo;
        incoming = null;
        incomingVideo = null;
        incomingReady = false;
        swapWanted = false;
        boolean first = player == null;
        if (player != null) releaseLater(player);   // the one that was showing: off the UI thread
        player = mp;
        current = v;
        history.add(v);
        int shown = queue.indexOf(v);   // on screen now: next time goes on after it (not after one only prepared)
        if (shown >= 0) Prefs.setAerialDeck(Prefs.aerialDeck(), Math.max(Prefs.aerialDeckAt(), shown + 1));
        AppLog.w("aerial: video " + (shown + 1) + " of " + queue.size() + " (" + v.id + ")");
        while (history.size() > 50) history.remove(0);
        try {
            mp.setSurface(surface.getHolder().getSurface());
            mp.setOnCompletionListener(p -> {
                if (p == player) advance();
            });
            final long asked = askedAt;
            mp.setOnInfoListener((p, what, extra) -> {
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START)
                    AppLog.w("aerial: on screen " + (SystemClock.elapsedRealtime() - asked) + " ms after it was asked for");
                return false;
            });
            mp.start();
            prepared = true;
            errors = 0;
        } catch (RuntimeException e) {
            AppLog.w("aerial: " + e.getMessage());
            player = null;
            failed();
            return;
        }
        overlay.videoStarted(v, first);
        fetchAhead();
        // The next one, prepared now while this one plays: at → or at the end it takes the screen in ~0.4 s (the
        // decoder starting), where preparing it then took ~1 s more.
        if (incoming == null && !queue.isEmpty()) prepareIncoming(nextInList());
    }

    /** A video that won't play is skipped; many in a row (no network) wait half a minute before trying again. */
    private void failed() {
        errors++;
        handler.postDelayed(this::advance, errors >= 5 ? 30_000 : 500);
    }

    /** Where the video is, for the caption and the fade before it ends (ms; -1 when not playing). */
    private int position() {
        try {
            return prepared && player != null ? player.getCurrentPosition() : -1;
        } catch (IllegalStateException e) {
            return -1;
        }
    }

    private int duration() {
        try {
            return prepared && player != null ? player.getDuration() : -1;
        } catch (IllegalStateException e) {
            return -1;
        }
    }

    // MARK: - The cache

    private static String url(Video v) {
        return v.urls[Math.min(Prefs.aerialQuality(), v.urls.length - 1)];
    }

    private static File cacheDir(Context c) {
        File dir = new File(c.getCacheDir(), "aerial");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }

    /** A video's file in the cache: named after its address (each quality is its own file). */
    private static File cacheFile(Context c, String url) {
        return new File(cacheDir(c), Integer.toHexString(url.hashCode()) + "-" + Integer.toHexString(url.length()) + ".mp4");
    }

    /** The start and end of the next AHEAD videos, one at a time while this one plays (making room first). */
    private void fetchAhead() {
        if (queue.isEmpty()) return;
        final Context app = getContext().getApplicationContext();
        final List<String> heads = new ArrayList<>();
        for (int i = 0; i < AHEAD && i < queue.size(); i++) heads.add(url(queue.get((next + i) % queue.size())));
        // Never made room for: this one, the one before (←), and the next ones.
        final List<File> keep = new ArrayList<>();
        List<Video> kept = new ArrayList<>();
        if (current != null) kept.add(current);
        if (history.size() > 1) kept.add(history.get(history.size() - 2));
        for (int i = 0; i < AHEAD + 1 && i < queue.size(); i++) kept.add(queue.get((next + i) % queue.size()));
        for (Video v : kept) {
            File f = cacheFile(app, url(v));
            keep.add(f);
            keep.add(new File(f.getPath() + ".part"));
            keep.add(new File(f.getPath() + ".tail"));
        }
        final int mine = session;
        DOWNLOADS.execute(() -> {
            // In the background, politely: beside a 4K video, at full speed, a download made the picture stutter.
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            for (String u : heads) {
                if (mine != session) return;
                try {
                    File out = cacheFile(app, u);
                    if (out.exists()) continue;
                    if (!makeRoom(cacheDir(app), HEAD + AerialSource.TAIL, keep, null)) {
                        AppLog.w("aerial: no room for the next videos");
                        return;
                    }
                    AerialSource.fetchHead(u, out, HEAD, mine, () -> session);
                } catch (Exception e) {
                    AppLog.w("aerial: " + e.getMessage());
                }
            }
        });
    }

    /**
     * Room for `needed` more bytes: the cache stays under MAX_CACHE and the TV keeps MIN_FREE free; the longest
     * unwatched videos go first (never the one playing or the next ones), then half-done downloads but this one.
     */
    private static boolean makeRoom(File dir, long needed, List<File> keep, File downloading) {
        File[] files = dir.listFiles();
        if (files == null) return false;
        java.util.Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        long total = 0;
        for (File f : files) total += f.length();
        for (File f : files) {
            boolean fits = total + needed <= MAX_CACHE && dir.getUsableSpace() - needed >= MIN_FREE;
            if (fits) return true;
            if (keep.contains(f) || f.equals(downloading)) continue;
            long size = f.length();
            if (f.delete()) total -= size;
        }
        return total + needed <= MAX_CACHE && dir.getUsableSpace() - needed >= MIN_FREE;
    }

    // MARK: - The list

    /** The videos for the settings: their sources and scenes, at night only night ones with Match the time, shuffled. */
    private static List<Video> load(Context c) {
        List<Video> all = new ArrayList<>(), kept = new ArrayList<>();
        try (InputStream in = c.getAssets().open("aerial.json")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            JSONArray videos = new JSONObject(out.toString("UTF-8")).getJSONArray("videos");
            for (int i = 0; i < videos.length(); i++) {
                JSONObject o = videos.getJSONObject(i);
                Video v = new Video();
                v.id = o.optString("i", o.getString("n"));
                v.source = o.getString("s");
                v.name = o.getString("n");
                v.scene = o.getString("c");
                v.time = o.optString("t", "day");
                JSONArray u = o.getJSONArray("u");
                v.urls = new String[u.length()];
                for (int j = 0; j < u.length(); j++) v.urls[j] = u.getString(j);
                JSONArray p = o.optJSONArray("p");
                int count = p == null ? 0 : p.length();
                v.capAt = new int[count];
                v.capText = new String[count];
                for (int j = 0; j < count; j++) {
                    v.capAt[j] = p.getJSONArray(j).getInt(0);
                    v.capText[j] = p.getJSONArray(j).getString(1);
                }
                all.add(v);
            }
        } catch (Exception e) {
            AppLog.w("aerial list: " + e.getMessage());
            return kept;
        }
        boolean match = "match".equals(Prefs.aerialTime());
        java.util.Set<String> hidden = Prefs.aerialHidden();   // "Never show this video"
        all.removeIf(v -> hidden.contains(v.id));
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        boolean night = hour >= 20 || hour < 6;
        for (Video v : all) {
            if (!Prefs.aerialOn(v.source) || !Prefs.aerialOn(v.scene)) continue;
            if (match && night != "night".equals(v.time)) continue;
            kept.add(v);
        }
        if (kept.isEmpty() && match) {   // nothing at this hour for these scenes: any time rather than nothing
            for (Video v : all) if (Prefs.aerialOn(v.source) && Prefs.aerialOn(v.scene)) kept.add(v);
        }
        Collections.shuffle(kept);
        return kept;
    }

    // MARK: - On top of the video

    private final class Overlay extends View {
        private float k = 1;
        private final Paint caps, caption, clock, date, title, artist, bars, curtainPaint = new Paint(), shade = new Paint();
        private final Paint coverPaint = new Paint(Paint.ANTI_ALIAS_FLAG), coverShadow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF(), barRect = new RectF();
        private final android.text.TextPaint captionFit = new android.text.TextPaint();
        /** The caption's lines (wrap), reused: words are broken at spaces, never in the middle. */
        private final String[] wrapped = new String[4];
        private String wrappedText = "";
        private float wrappedWidth;
        private int wrappedLines;

        /** Breaks `text` into lines no wider than `width` (at most 4, the last one taking the rest); returns how many. */
        private int wrap(String text, float width) {
            if (text.equals(wrappedText) && width == wrappedWidth) return wrappedLines;
            wrappedText = text;
            wrappedWidth = width;
            int n = 0;
            StringBuilder line = new StringBuilder();
            for (String word : text.split(" ")) {
                String tried = line.length() == 0 ? word : line + " " + word;
                if (line.length() > 0 && captionFit.measureText(tried) > width && n < wrapped.length - 1) {
                    wrapped[n++] = line.toString();
                    line.setLength(0);
                    line.append(word);
                } else {
                    line.setLength(0);
                    line.append(tried);
                }
            }
            if (line.length() > 0 || n == 0) wrapped[n++] = line.toString();
            wrappedLines = n;
            return n;
        }
        private LinearGradient bottomShade, topShade;
        private String clockText = "", dateText = "", place = "", artKey = "";
        private Bitmap cover;
        private MediaMetadata lastMetadata;
        private String lastArtSig = "";
        private final Shadows shadows = new Shadows();
        private BitmapShader coverShader;
        /** The black between videos: 1 = black, eased toward its target. */
        private float curtain = 1, curtainTo = 1;
        /** Set while closing: the curtain goes to black, then this closes the screensaver. */
        private Runnable closeDone;
        private long lastFrame;
        /** The hint at the top after ← or →: until when it shows (uptime), and its paints. */
        private long hintUntil;
        private float hintAlpha, musicLeft, musicBottom;
        private boolean musicShown;
        private final RectF hintRect = new RectF(), barsRect = new RectF(), infoRect = new RectF();
        private final Paint hintText, hintPill = new Paint(Paint.ANTI_ALIAS_FLAG), spinner = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final String hint = Ui.tr("← →  Previous or next video   ·   ↓  About this video   ·   Any other button closes");

        /** Once a second: the time, the caption for this moment, and the fade before the video ends. */
        final Runnable tick = new Runnable() {
            @Override public void run() {
                long now = System.currentTimeMillis();
                clockText = Ui.clock(now);
                dateText = Ui.date(now);
                prepareAheadIfNearEnd();   // the next video ready before this one ends
                invalidate();
                postDelayed(this, 1000);
            }
        };

        Overlay(Context c) {
            super(c);
            caps = paint(Ui.semibold, 22, 0xCCFFFFFF);
            caps.setLetterSpacing(0.12f);
            caption = paint(Ui.medium, 30, 0xFFFFFFFF);
            clock = paint(Ui.light, 96, 0xFFFFFFFF);
            clock.setTextAlign(Paint.Align.RIGHT);
            clock.setLetterSpacing(-0.02f);
            date = paint(Ui.medium, 26, 0xD9FFFFFF);
            date.setTextAlign(Paint.Align.RIGHT);
            title = paint(Ui.semibold, 34, 0xFFFFFFFF);
            title.setTextAlign(Paint.Align.RIGHT);
            artist = paint(Ui.medium, 24, 0xCCFFFFFF);
            artist.setTextAlign(Paint.Align.RIGHT);
            bars = new Paint(Paint.ANTI_ALIAS_FLAG);
            bars.setColor(0xFF1ED760);
            spinner.setStyle(Paint.Style.STROKE);
            spinner.setStrokeCap(Paint.Cap.ROUND);
            spinner.setColor(0xCCFFFFFF);
            hintText = Ui.text(Ui.medium, 26, 0xFFFFFFFF);
            hintText.setTextAlign(Paint.Align.CENTER);
            curtainPaint.setColor(0xFF000000);
            coverShadow.setColor(0x8C000000);   // through Shadows: a soft blur, so it can be darker than a flat block
        }

        private Paint paint(android.graphics.Typeface face, float size, int color) {
            Paint p = Ui.text(face, size, color);
            p.setShadowLayer(18, 0, 2, 0x73000000);
            return p;
        }

        @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            k = w / 1920f;
            for (Paint p : new Paint[] {caps, caption, clock, date, title, artist}) p.setShadowLayer(18 * k, 0, 2 * k, 0x73000000);
            caps.setTextSize(22 * k);
            caption.setTextSize(30 * k);
            captionFit.set(caption);
            clock.setTextSize(96 * k);
            date.setTextSize(26 * k);
            title.setTextSize(34 * k);
            artist.setTextSize(24 * k);
            pTitle.setTextSize(40 * k);
            pEyebrow.setTextSize(20 * k);
            pCaption.setTextSize(24 * k);
            pStats.setTextSize(22 * k);
            pButton.setTextSize(24 * k);
            hintText.setTextSize(26 * k);
            gClock.setTextSize(86 * k);
            gClock.setLetterSpacing(-0.01f);
            gDate.setTextSize(27 * k);
            gWeather.setTextSize(25 * k);
            for (Paint g : new Paint[] {gClock, gDate, gWeather}) g.setShadowLayer(18 * k, 0, 2 * k, 0x73000000);
            bottomShade = new LinearGradient(0, h * 0.55f, 0, h, 0x00000000, 0x8C000000, Shader.TileMode.CLAMP);
            topShade = new LinearGradient(0, 0, 0, h * 0.3f, 0x73000000, 0x00000000, Shader.TileMode.CLAMP);
            artKey = "";   // the cover again at this size
        }

        // The info panel (↓): slides down from the top over the video; two buttons, Close focused at first.
        boolean infoOpen;
        int infoFocus = 1;
        private float info;   // 0 hidden, 1 down
        private final Paint panel = new Paint(Paint.ANTI_ALIAS_FLAG), panelRim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pTitle = Ui.text(Ui.semibold, 48, 0xFFFFFFFF), pEyebrow = Ui.text(Ui.semibold, 22, 0xB3FFFFFF);
        private final Paint pCaption = Ui.text(Ui.medium, 26, 0xE6FFFFFF), pStats = Ui.numbers(Ui.medium, 22, 0x99FFFFFF);
        private final Paint pButton = Ui.text(Ui.semibold, 26, 0xFFFFFFFF), pButtonFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final String[] infoLines = new String[2];

        void openInfo() {
            infoOpen = true;
            infoFocus = 1;
            hintUntil = 0;
            lastFrame = 0;
            invalidate();
        }

        void closeInfo() {
            infoOpen = false;
            lastFrame = 0;
            invalidate();
        }

        void moveInfo(int dir) {
            infoFocus = Math.max(0, Math.min(1, infoFocus + dir));
            invalidate();
        }

        private String sourceName(Video v) {
            return "apple".equals(v.source) ? "Apple" : "amazon".equals(v.source) ? "Amazon" : Ui.tr("Community");
        }

        private String sceneName(Video v) {
            switch (v.scene) {
                case "city": return Ui.tr("City");
                case "space": return Ui.tr("Space");
                case "sea": return Ui.tr("Underwater");
                case "abstract": return Ui.tr("Abstract");
                default: return Ui.tr("Landscape");
            }
        }

        private String timeName(Video v) {
            switch (v.time) {
                case "night": return Ui.tr("Night");
                case "sunset": return Ui.tr("Sunset");
                case "sunrise": return Ui.tr("Sunrise");
                default: return Ui.tr("Day");
            }
        }

        private String clockOf(int ms) {
            int s = Math.max(0, ms / 1000);
            return s / 60 + ":" + (s % 60 < 10 ? "0" : "") + s % 60;
        }

        /**
         * The panel, a slim bar: on the left the source, scene, time and where it is on one line, the place, and its
         * caption in full (a second line only when it needs one); on the right the two buttons.
         */
        private void drawInfo(Canvas c, float w, float ease) {
            Video v = current;
            if (v == null) return;
            String[] labels = {Ui.tr("Never show this video"), Ui.tr("Close")};
            float bh = 60 * k, gap = 14 * k, buttonsW = gap;
            for (String l : labels) buttonsW += pButton.measureText(l) + 60 * k;
            float pw = 1400 * k, left = (w - pw) / 2, pad = 40 * k, textW = pw - 2 * pad - buttonsW - 40 * k;
            // The caption in full, broken at spaces: one line, or two when it's long.
            String cap = captionNow();
            int lines = 0;
            StringBuilder line = new StringBuilder();
            for (String word : cap.split(" ")) {
                String tried = line.length() == 0 ? word : line + " " + word;
                if (line.length() > 0 && pCaption.measureText(tried) > textW && lines < 1) {
                    infoLines[lines++] = line.toString();
                    line.setLength(0);
                    line.append(word);
                } else {
                    line.setLength(0);
                    line.append(tried);
                }
            }
            if (line.length() > 0) infoLines[lines++] = line.toString();
            float ph = (154 + 32 * Math.max(1, lines)) * k, r = 30 * k;
            float top = 36 * k - (1 - ease) * (ph + 60 * k);
            rect.set(left, top, left + pw, top + ph);
            infoRect.set(rect);
            panel.setColor(Ui.alpha(0xFF141418, 0.82f));
            c.drawRoundRect(rect, r, r, panel);
            panelRim.setStyle(Paint.Style.STROKE);
            panelRim.setStrokeWidth(1.5f * k);
            panelRim.setColor(0x29FFFFFF);
            c.drawRoundRect(rect, r, r, panelRim);
            int pos = position(), dur = duration();
            boolean onTv = cacheFile(getContext(), url(v)).exists();
            pEyebrow.setLetterSpacing(0.12f);
            String eyebrow = (sourceName(v) + "   ·   " + sceneName(v) + "   ·   " + timeName(v)
                    + (pos >= 0 && dur > 0 ? "   ·   " + clockOf(pos) + " / " + clockOf(dur) : "")
                    + "   ·   " + (onTv ? Ui.tr("On the TV") : Ui.tr("Streaming"))).toUpperCase(Ui.locale());
            c.drawText(eyebrow, left + pad, top + 52 * k, pEyebrow);
            c.drawText(v.name, left + pad, top + 104 * k, pTitle);
            for (int i = 0; i < lines; i++) c.drawText(infoLines[i], left + pad, top + (144 + i * 32) * k, pCaption);
            // The buttons on the right, centred on the bar's height: the focused one white with dark text.
            float x = left + pw - pad, by = top + (ph - bh) / 2;
            for (int i = labels.length - 1; i >= 0; i--) {
                float bw = pButton.measureText(labels[i]) + 60 * k;
                rect.set(x - bw, by, x, by + bh);
                boolean on = i == infoFocus;
                pButtonFill.setColor(on ? 0xFFFFFFFF : 0x24FFFFFF);
                c.drawRoundRect(rect, bh / 2, bh / 2, pButtonFill);
                pButton.setColor(on ? 0xFF1D1D1F : 0xFFFFFFFF);
                pButton.setTextAlign(Paint.Align.CENTER);
                c.drawText(labels[i], rect.centerX(), rect.centerY() + Ui.halfCap(pButton), pButton);
                x -= bw + gap;
            }
        }

        void showHint() {
            hintUntil = SystemClock.uptimeMillis() + 4000;
            lastFrame = 0;
            invalidate();
        }

        /** A video took the screen: the first fades in from black, the next ones with a short soft dip. */
        void videoStarted(Video v, boolean first) {
            place = v.name.toUpperCase(Ui.locale());
            if (!first) curtain = Math.max(curtain, 0.55f);
            curtainTo = 0;
            lastFrame = 0;
            invalidate();
        }

        void musicChanged() {
            invalidate();
        }

        /** The music app's session while it plays (NowPlaying.Watch.music), when the corner is on. */
        private MediaController music() {
            return Prefs.aerialMusic() ? playing.music : null;
        }

        private String captionNow() {
            Video v = current;
            int pos = position();
            if (v == null || v.capText.length == 0) return "";
            int sec = Math.max(0, pos / 1000), at = 0;
            for (int i = 0; i < v.capAt.length; i++) if (v.capAt[i] <= sec) at = i;
            return v.capText[at];
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            long now = SystemClock.uptimeMillis();
            float dt = lastFrame == 0 ? 0.016f : Math.min(0.1f, (now - lastFrame) / 1000f);
            lastFrame = now;
            boolean moving = false;
            if (curtain != curtainTo) {   // about 0.8 s from black to the video and back (closing: CLOSE_FADE)
                float step = dt / (closeDone != null ? CLOSE_FADE : 0.8f);
                curtain = curtainTo > curtain ? Math.min(curtainTo, curtain + step) : Math.max(curtainTo, curtain - step);
                moving = true;
            } else if (closeDone != null && curtain >= 1) {   // black: now the screensaver closes
                Runnable done = closeDone;
                closeDone = null;
                // The video's layer goes too (under black, nothing changes): it outlived the window by a moment and
                // flashed back under the home screen. A frame without it, then the close.
                surface.setVisibility(GONE);
                postDelayed(done, 50);
            }
            MediaController m = music();
            shade.setShader(bottomShade);
            c.drawRect(0, h * 0.55f, w, h, shade);
            if (m != null) {
                shade.setShader(topShade);
                c.drawRect(0, 0, w, h * 0.3f, shade);
            }
            if (Prefs.aerialCaptions() && current != null) {
                // The whole caption, never cut: wrapped onto as many lines as it needs (up to where the clock starts),
                // stacked up from the bottom, the place above it.
                float room = (clockLeft > 0 ? clockLeft - 56 * k : w - 700 * k) - 96 * k;   // stops short of the clock
                int lines = wrap(captionNow(), room);
                float line = 40 * k, last = h - 85 * k;
                for (int i = 0; i < lines; i++) c.drawText(wrapped[i], 96 * k, last - (lines - 1 - i) * line, caption);
                c.drawText(place, 96 * k, last - (lines - 1) * line - 42 * k, caps);
            }
            drawClock(c, w, h);
            musicShown = false;
            if (m != null) moving |= drawMusic(c, m, w);
            else barsView.hide();
            if (curtain > 0.001f) {
                curtainPaint.setAlpha(Math.round(255 * curtain));
                c.drawRect(0, 0, w, h, curtainPaint);
            }
            if (loading()) {   // the next video is on its way: a small spinning ring in the middle
                float r = 26 * k, cx = w / 2, cy = h / 2, turn = (now % 1000) / 1000f * 360;
                spinner.setStrokeWidth(5 * k);
                rect.set(cx - r, cy - r, cx + r, cy + r);
                c.drawArc(rect, turn, 270, false, spinner);
                moving = true;
            }
            float infoTo = infoOpen ? 1 : 0;
            if (info != infoTo) {   // about a quarter of a second down or up
                float step = dt / 0.25f;
                info = infoTo > info ? Math.min(infoTo, info + step) : Math.max(infoTo, info - step);
                moving = true;
            }
            infoRect.setEmpty();
            if (info > 0.001f) {
                float e = info * info * (3 - 2 * info);
                drawInfo(c, w, e);
            }
            long hintLeft = hintUntil - now;
            if (hintLeft > 0) {   // a dark pill at the top centre, fading in and out over its last 0.4 s
                float a = Math.min(1, Math.min(hintLeft, 4000 - hintLeft) / 400f);
                // At the top: centred, or at the left when the music corner leaves no room there; under the corner only
                // when neither fits (a very long song).
                float tw = hintText.measureText(hint), pw = tw + 72 * k, ph = 64 * k, top = 48 * k, left = w / 2 - pw / 2;
                if (musicShown && left + pw > musicLeft - 32 * k) {
                    left = 96 * k;
                    if (left + pw > musicLeft - 32 * k) {
                        left = w / 2 - pw / 2;
                        top = musicBottom + 32 * k;
                    }
                }
                rect.set(left, top, left + pw, top + ph);
                hintRect.set(rect);
                hintAlpha = a;
                hintPill.setColor(Ui.alpha(0xFF000000, 0.55f * a));
                c.drawRoundRect(rect, ph / 2, ph / 2, hintPill);
                hintText.setAlpha(Math.round(255 * a));
                c.drawText(hint, rect.centerX(), top + ph / 2 + Ui.halfCap(hintText), hintText);
                moving = true;
            } else {
                hintAlpha = 0;
            }
            // The bouncing bars are a view of their own above this one: they're dimmed by what this one draws over them
            // (the black curtain as it opens and closes, the info panel, the hint) as much as the rest of the corner is,
            // so they sit behind those like the song's text instead of showing through on top.
            float barsA = 1 - curtain;
            if (!infoRect.isEmpty() && RectF.intersects(infoRect, barsRect)) barsA *= 1 - 0.82f;
            if (hintAlpha > 0 && RectF.intersects(hintRect, barsRect)) barsA *= 1 - 0.55f * hintAlpha;
            if (barsView.getAlpha() != barsA) barsView.setAlpha(barsA);
            if (moving) postInvalidateOnAnimation();
            else lastFrame = 0;
        }

        // The clock as on the home screen (Continue Watching and Picks): the time large, a hairline, then the date over
        // the weather (its symbol, now, and the day's high and low), here set into the bottom right corner.
        private final Paint gClock = Ui.numbers(Ui.light, 86, 0xFFFFFFFF), gDate = Ui.text(Ui.semibold, 27, 0xFFFFFFFF);
        private final Paint gWeather = Ui.numbers(Ui.medium, 25, 0xD9FFFFFF), gLine = new Paint();
        private String weatherLine = "";
        /** Where the clock block starts (drawClock, last frame): the caption wraps before it. */
        private float clockLeft;
        private long weatherAt = -1;

        private void drawClock(Canvas c, float w, float h) {
            HomeData data = HomeData.get(getContext());
            HomeData.Weather wx = data.weather;
            boolean weather = Prefs.weather() && !Double.isNaN(wx.temp);
            if (weather && data.weatherAt != weatherAt) {
                weatherAt = data.weatherAt;
                weatherLine = HomeData.degrees(wx.temp) + "  " + HomeData.condition(wx.code, wx.isDay) + "  ·  " + HomeData.highLow(wx, "  ");
            }
            // Stacked in the bottom right corner, all right-aligned: the time, then the date, then the weather under it.
            float right = w - 96 * k, weatherBase = h - 84 * k, dateBase = weather ? weatherBase - 40 * k : weatherBase;
            float timeBase = dateBase - 50 * k;
            gClock.setTextAlign(Paint.Align.RIGHT);
            gDate.setTextAlign(Paint.Align.RIGHT);
            gWeather.setTextAlign(Paint.Align.RIGHT);
            c.drawText(clockText, right, timeBase, gClock);
            c.drawText(dateText, right, dateBase, gDate);
            float widest = Math.max(gClock.measureText(clockText), gDate.measureText(dateText));
            if (weather) {
                float size = 28 * k, lineW = gWeather.measureText(weatherLine);
                String symbol = Symbols.weather(wx.code, wx.isDay);
                float sw = Symbols.width(getContext(), symbol, size);
                c.drawText(weatherLine, right, weatherBase, gWeather);
                Symbols.draw(getContext(), c, symbol, right - lineW - 12 * k - sw / 2, weatherBase - Ui.halfCap(gWeather), size, gWeather.getColor());
                widest = Math.max(widest, lineW + 12 * k + sw);
            }
            clockLeft = right - widest;
        }

        /** The top right: the cover at the edge, the song (after the level bars) and the artist beside it. */
        private boolean drawMusic(Canvas c, MediaController m, float w) {
            MediaMetadata md = m.getMetadata();
            if (md == null) return false;
            String song = md.getString(MediaMetadata.METADATA_KEY_TITLE);
            String who = md.getString(MediaMetadata.METADATA_KEY_ARTIST);
            // The picture's own look too (only when the metadata changed): a new cover can come after the new title.
            if (md != lastMetadata) {
                lastMetadata = md;
                lastArtSig = NowPlaying.artSignature(md);
            }
            String key = m.getPackageName() + "|" + song + "|" + who + "|" + lastArtSig;
            if (!key.equals(artKey)) {
                artKey = key;
                Bitmap art = NowPlaying.art(md);
                int size = Math.max(1, Math.round(132 * k));
                cover = art == null ? null : Bitmap.createScaledBitmap(art, size, size, true);
                coverShader = cover == null ? null : new BitmapShader(cover, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            }
            float size = 132 * k, right = w - 96 * k, top = 72 * k, r = 18 * k;
            float textRight = right;
            if (cover != null) {
                // A soft shadow under the cover (blurred, cached by Shadows), not a hard offset block.
                rect.set(right - size, top + 10 * k, right, top + size + 10 * k);
                shadows.draw(c, rect, r, 30 * k, coverShadow);
                rect.set(right - size, top, right, top + size);
                coverPaint.setShader(coverShader);
                c.save();
                c.translate(rect.left, rect.top);
                rect.offsetTo(0, 0);
                c.drawRoundRect(rect, r, r, coverPaint);
                c.restore();
                textRight = right - size - 28 * k;
            }
            String songText = song == null ? "" : song;
            String whoText = (who == null ? "" : who) + appSuffix(m);
            float base = top + 58 * k;
            c.drawText(songText, textRight, base, title);
            float cap = Ui.halfCap(title) * 2, barsW = NowPlaying.barsWidth(cap);
            PlaybackState st = m.getPlaybackState();
            boolean live = st != null && st.getState() == PlaybackState.STATE_PLAYING;
            float bx = textRight - title.measureText(songText) - 16 * k - barsW;
            // Bouncing, they're drawn by their own small view: redrawing this whole overlay (its text shadows over a 4K
            // video) 60 times a second took ~1.5 cores of a 2018 BRAVIA, and the home screen was sluggish after it.
            musicShown = true;
            musicLeft = Math.min(bx, textRight - artist.measureText(whoText));
            musicBottom = top + size;
            barsRect.set(bx, base - cap, bx + barsW, base);
            if (live) barsView.show(bx, base, cap, bars);
            else {
                barsView.hide();
                NowPlaying.drawBars(c, bx, base, cap, bars, false, barRect);
            }
            c.drawText(whoText, textRight, top + 98 * k, artist);
            return false;
        }

        private String appLabel = "", appPkg = "";

        private String appSuffix(MediaController m) {
            if (!m.getPackageName().equals(appPkg)) {
                appPkg = m.getPackageName();
                try {
                    android.content.pm.PackageManager pm = getContext().getPackageManager();
                    appLabel = pm.getApplicationLabel(pm.getApplicationInfo(appPkg, 0)).toString();
                } catch (android.content.pm.PackageManager.NameNotFoundException e) {
                    appLabel = "";
                }
            }
            return appLabel.isEmpty() ? "" : "  ·  " + appLabel;
        }
    }

    /** The music corner's three bars, bouncing at 30 fps in a view of their own size (the overlay draws them still). */
    private static final class BarsView extends android.view.View {
        private float cap;
        private Paint paint;
        private final RectF r = new RectF();
        private final Runnable frame = this::invalidate;

        BarsView(Context c) {
            super(c);
        }

        /** Where the overlay would draw them: x and baseline on screen, their height (cap), their paint. */
        void show(float x, float base, float cap, Paint paint) {
            this.paint = paint;
            int pad = 4, w = Math.round(NowPlaying.barsWidth(cap)) + 2 * pad, h = Math.round(cap) + 2 * pad;
            if (this.cap != cap || getWidth() != w || getHeight() != h) {
                this.cap = cap;
                android.view.ViewGroup.LayoutParams lp = getLayoutParams();
                lp.width = w;
                lp.height = h;
                setLayoutParams(lp);
            }
            float left = Math.round(x) - pad, top = Math.round(base - cap) - pad;
            if (getX() != left) setX(left);
            if (getY() != top) setY(top);
            if (getVisibility() != VISIBLE) setVisibility(VISIBLE);
        }

        void hide() {
            if (getVisibility() != INVISIBLE) setVisibility(INVISIBLE);
            removeCallbacks(frame);
        }

        @Override protected void onDraw(Canvas c) {
            if (paint == null || cap <= 0) return;
            NowPlaying.drawBars(c, 4, 4 + cap, cap, paint, true, r);
            removeCallbacks(frame);
            postDelayed(frame, 33);
        }

        @Override protected void onDetachedFromWindow() {
            removeCallbacks(frame);
            super.onDetachedFromWindow();
        }
    }
}
