package io.freemytv;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Netflix's Continue Watching, which its TV app doesn't write to Android TV's Watch Next list on TVs that aren't Google
 * TVs: read from the person's own Netflix account with the two cookies of a netflix.com session (sent from a computer or
 * phone through KeyPage; they stay in the app's private files, never in the APK, the log or Prefs). The website's own calls:
 * the profile's viewing history (newest first, without positions), then each title's details, where Netflix says how
 * far in you are and, for a show, which episode it would play next. Unofficial: Netflix may change them at any time,
 * and then the row simply has no Netflix titles.
 */
final class NetflixWatch {
    private NetflixWatch() {}

    static final String PKG = "com.netflix.ninja";
    private static final String UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36";
    /**
     * While the home screen shows, every EVERY (a refresh is a dozen small requests); back from Netflix, at once and
     * again AGAIN later (Netflix can take a moment to save where you stopped).
     */
    static final long EVERY = 10 * 60_000L;
    private static final long AGAIN = 45_000L;
    /** The history is read page by page until it's this old (that page's titles count), and this many titles are looked at. */
    private static final long DAYS = 120;
    private static final int TITLES = 12, PAGE = 20, PAGES = 8;
    static final int MAX = WatchNext.MAX;   // as many as the row holds: it's ordered by when you watched, not by app

    private static final ExecutorService IO = Ui.background(1);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile List<WatchNext.Item> items;
    private static volatile long refreshedAt;
    private static volatile boolean running;
    /** The home screen's: reload Continue Watching when Netflix's titles changed. */
    static volatile Runnable onChange;

    // MARK: - The account (the session's cookies)

    private static File dir(Context c) {
        return new File(c.getFilesDir(), "netflix");
    }

    private static JSONObject account(Context c) {
        try {
            return new JSONObject(new String(Files.readAllBytes(new File(dir(c), "account.json").toPath()), StandardCharsets.UTF_8));
        } catch (IOException | JSONException e) {
            return null;
        }
    }

    static boolean signedIn(Context c) {
        JSONObject a = account(c);
        return a != null && !a.optString("NetflixId").isEmpty();
    }

    /** "Ana" (the profile), or "" when signed out. */
    static String profile(Context c) {
        JSONObject a = account(c);
        return a == null ? "" : a.optString("profile");
    }

    /** True when Netflix ended the session (signed out everywhere, a new password): sign in again. */
    static boolean expired(Context c) {
        JSONObject a = account(c);
        return a != null && a.optBoolean("expired");
    }

    static synchronized void save(Context c, String netflixId, String secureNetflixId, String profile) {
        try {
            JSONObject a = new JSONObject().put("NetflixId", netflixId).put("SecureNetflixId", secureNetflixId).put("profile", profile);
            write(new File(dir(c), "account.json"), a.toString());
        } catch (JSONException | IOException e) {
            AppLog.w("netflix: couldn't save the account: " + e.getClass().getSimpleName());
        }
        refreshedAt = 0;
    }

    /**
     * The two cookies a phone sent (each pasted alone, or the whole cookie line in either field): checked with Netflix,
     * then kept with the profile's name. Null when saved, else why not. Off the UI thread.
     */
    static String check(Context c, String netflixId, String secureNetflixId) {
        String id = cookie(netflixId + ";" + secureNetflixId, "NetflixId", netflixId), secure = cookie(netflixId + ";" + secureNetflixId, "SecureNetflixId", secureNetflixId);
        if (id.isEmpty() || secure.isEmpty() || id.contains(" ") || secure.contains(" ")) return Ui.tr("Those don't look like Netflix's cookies.");
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL("https://www.netflix.com/browse").openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(20_000);
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Cookie", "NetflixId=" + id + "; SecureNetflixId=" + secure);
            int code = conn.getResponseCode();
            if (code != 200) {
                conn.disconnect();
                return code >= 300 && code < 400 ? Ui.tr("Netflix says that session is signed out. Sign in at netflix.com again and copy them again.")
                        : Ui.trf("Netflix answered {code}. Try again.", "code", code);
            }
            String html;
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] b = new byte[16384];
                int n;
                try {
                    while ((n = in.read(b)) > 0) buf.write(b, 0, n);
                } catch (IOException e) {
                    if (buf.size() == 0) throw e;
                }
                html = buf.toString(StandardCharsets.UTF_8.name());
            } finally {
                conn.disconnect();
            }
            if (!html.contains("\"userGuid\"")) return Ui.tr("Netflix didn't open a profile with them. Choose your profile at netflix.com, then copy them again.");
            Matcher m = Pattern.compile("\"profileName\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(html);
            String profile = m.find() ? new JSONObject("{\"p\":\"" + m.group(1) + "\"}").optString("p") : "";
            save(c, id, secure, profile);
            return null;
        } catch (IOException | JSONException e) {
            return Ui.trf("Couldn't reach Netflix: {error}", "error", e.getClass().getSimpleName());
        }
    }

    /** `name`'s value in a cookie line, or `alone` itself when it's just the value (without a "name=" before it). */
    private static String cookie(String line, String name, String alone) {
        for (String part : line.split(";")) {
            int eq = part.indexOf('=');
            if (eq > 0 && part.substring(0, eq).trim().equals(name)) return part.substring(eq + 1).trim();
        }
        return alone.trim();
    }

    static synchronized void signOut(Context c) {
        //noinspection ResultOfMethodCallIgnored
        new File(dir(c), "account.json").delete();
        //noinspection ResultOfMethodCallIgnored
        new File(dir(c), "continue.json").delete();
        items = new ArrayList<>();
        refreshedAt = 0;
    }

    private static synchronized void update(Context c, String netflixId, String secureNetflixId, boolean expired) {
        JSONObject a = account(c);
        if (a == null) return;
        try {
            if (netflixId != null) a.put("NetflixId", netflixId);
            if (secureNetflixId != null) a.put("SecureNetflixId", secureNetflixId);
            a.put("expired", expired);
            write(new File(dir(c), "account.json"), a.toString());
        } catch (JSONException | IOException ignored) {
        }
    }

    private static void write(File f, String text) throws IOException {
        //noinspection ResultOfMethodCallIgnored
        f.getParentFile().mkdirs();
        File tmp = new File(f.getPath() + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(f)) throw new IOException("rename");
    }

    // MARK: - The titles

    /**
     * Netflix's titles for Continue Watching, newest first (the last ones read; never waits on the network), and a
     * refresh in the background when they're old: `onChange` says when new ones came.
     */
    static List<WatchNext.Item> items(Context c) {
        if (!signedIn(c)) return new ArrayList<>();
        if (items == null) items = readCache(c);
        long age = System.currentTimeMillis() - refreshedAt;
        // A little before EVERY, so the home screen's 10-minute tick always finds it due.
        if ((age > EVERY - 30_000 || age < 0) && !running && !expired(c)) refresh(c.getApplicationContext());
        List<WatchNext.Item> out = new ArrayList<>();
        for (WatchNext.Item it : items) out.add(copy(it));   // WatchNext fills in each item's app name
        return out;
    }

    /** The app opened from Continue Watching, the shelf or All Apps: back from Netflix, its titles are read again. */
    static volatile String lastApp;
    private static String sessionSeen;

    /**
     * The home screen came back (main thread): after Netflix, its titles are read now and again a little later. Netflix
     * opened any way (its remote button too) shows in its media session, whose position moved since the last look.
     */
    static void homeResumed(Context c) {
        if (!signedIn(c) || expired(c)) return;
        boolean back = PKG.equals(lastApp) | sessionMoved(c);
        lastApp = null;
        if (!back) return;
        Context app = c.getApplicationContext();
        MAIN.removeCallbacks(again);
        againContext = app;
        if (!running) refresh(app);
        MAIN.postDelayed(again, AGAIN);
    }

    private static Context againContext;
    private static final Runnable again = () -> {
        if (againContext != null && !running) refresh(againContext);
    };

    /** Netflix's media session played or moved since the last look (only with the Now Playing widget's access). */
    private static boolean sessionMoved(Context c) {
        String key = null;
        try {
            for (android.media.session.MediaController m : c.getSystemService(android.media.session.MediaSessionManager.class)
                    .getActiveSessions(NowPlaying.component(c))) {
                if (!PKG.equals(m.getPackageName())) continue;
                android.media.session.PlaybackState st = m.getPlaybackState();
                key = st == null ? "none" : st.getState() + ":" + st.getPosition() / 30_000 + ":" + st.getLastPositionUpdateTime();
            }
        } catch (RuntimeException e) {   // no access (the widget is off): only Netflix opened from the home screen counts
            return false;
        }
        boolean moved = key != null && sessionSeen != null && !key.equals(sessionSeen);
        if (key != null) sessionSeen = key;
        return moved;
    }

    private static WatchNext.Item copy(WatchNext.Item it) {
        WatchNext.Item n = new WatchNext.Item(PKG, null, null);
        n.title = it.title;
        n.episode = it.episode;
        n.duration = it.duration;
        n.position = it.position;
        n.engaged = it.engaged;
        n.intent = it.intent;
        return n;
    }

    private static void refresh(Context c) {
        running = true;
        refreshedAt = System.currentTimeMillis();
        IO.execute(() -> {
            try {
                List<WatchNext.Item> next = fetch(c);
                boolean changed = !same(next, items);
                items = next;
                writeCache(c, next);
                AppLog.w("netflix: " + next.size() + " to continue");
                if (changed && onChange != null) MAIN.post(onChange);
            } catch (Expired e) {
                AppLog.w("netflix: the session ended: sign in again in Home Settings");
                update(c, null, null, true);
            } catch (Exception e) {
                // Never the cookies or titles: what failed and how.
                AppLog.w("netflix: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            } finally {
                running = false;
            }
        });
    }

    private static boolean same(List<WatchNext.Item> a, List<WatchNext.Item> b) {
        if (b == null || a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            WatchNext.Item x = a.get(i), y = b.get(i);
            if (!x.title.equals(y.title) || !String.valueOf(x.episode).equals(String.valueOf(y.episode))
                    || x.position / 60_000 != y.position / 60_000) return false;
        }
        return true;
    }

    private static final class Expired extends IOException {
        Expired() { super("signed out"); }
    }

    private static List<WatchNext.Item> fetch(Context c) throws IOException, JSONException {
        String html = get(c, "https://www.netflix.com/browse", null);
        Matcher m = Pattern.compile("\"userGuid\"\\s*:\\s*\"([A-Z0-9]+)\"").matcher(html);
        if (!m.find()) throw new IOException("no profile on the page");
        String guid = m.group(1);

        // The history: pages of 20 until TITLES different titles, or DAYS back.
        List<JSONObject> viewed = new ArrayList<>();
        Set<Long> titles = new HashSet<>();
        long since = System.currentTimeMillis() - DAYS * 86_400_000L;
        for (int page = 0; page < PAGES && titles.size() < TITLES; page++) {
            JSONArray got = history(c, guid, page);
            if (got.length() == 0) break;
            for (int i = 0; i < got.length(); i++) {
                JSONObject it = got.getJSONObject(i);
                viewed.add(it);
                titles.add(it.optLong("series", it.optLong("movieID")));
            }
            if (got.getJSONObject(got.length() - 1).optLong("date") < since) break;
        }

        List<WatchNext.Item> out = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (JSONObject it : viewed) {
            long id = it.has("series") ? it.optLong("series") : it.optLong("movieID");
            if (id == 0 || !seen.add(id)) continue;   // older ones too, from the pages read: Netflix's own row keeps them
            if (seen.size() > TITLES || out.size() >= MAX) break;
            JSONObject video = new JSONObject(get(c, "https://www.netflix.com/nq/website/memberapi/release/metadata?movieid=" + id
                    + "&_=" + System.currentTimeMillis(), null)).optJSONObject("video");
            if (video == null) continue;
            WatchNext.Item item = card(video, it.optLong("date"));
            if (item != null) out.add(item);
        }
        return out;
    }

    /** The title's card, or null when it's finished (or never really started). */
    private static WatchNext.Item card(JSONObject video, long viewedAt) {
        String title = video.optString("title");
        if (title.isEmpty()) return null;
        JSONObject watch = video, season = null;
        if ("show".equals(video.optString("type"))) {
            long current = video.optLong("currentEpisode");
            watch = null;
            JSONArray seasons = video.optJSONArray("seasons");
            for (int s = 0; seasons != null && s < seasons.length() && watch == null; s++) {
                JSONArray eps = seasons.optJSONObject(s).optJSONArray("episodes");
                for (int e = 0; eps != null && e < eps.length(); e++) {
                    JSONObject ep = eps.optJSONObject(e);
                    if (ep.optLong("id") == current || ep.optLong("episodeId") == current) {
                        watch = ep;
                        season = seasons.optJSONObject(s);
                        break;
                    }
                }
            }
            if (watch == null) return null;
        }
        JSONObject bookmark = watch.optJSONObject("bookmark");
        long offset = bookmark == null ? 0 : bookmark.optLong("offset");
        long end = watch.optLong("creditsOffset") > 0 ? watch.optLong("creditsOffset") : watch.optLong("runtime");
        if (end > 0 && offset >= end - 30) return null;   // watched to the credits: finished (a show: no episode left)
        if (season == null && offset <= 60) return null;   // a movie never really started (a trailer, a peek)
        WatchNext.Item item = new WatchNext.Item(PKG, null, null);
        item.title = title;
        if (season != null) {
            item.episode = Ui.trf("S{season} E{episode}", "season", season.optInt("seq"), "episode", watch.optInt("seq"));
        }
        item.duration = watch.optLong("runtime") * 1000;
        item.position = offset > 60 ? offset * 1000 : 0;   // a next episode not started yet: no bar, no time left
        long watchedAt = bookmark == null ? 0 : bookmark.optLong("watchedDate");
        item.engaged = Math.max(viewedAt, watchedAt);
        item.intent = "intent://www.netflix.com/watch/" + watch.optLong("id") + "#Intent;scheme=https;S.source=30;end";
        return item;
    }

    /** One page of the profile's viewing history (the account page's own call). */
    private static JSONArray history(Context c, String guid, int page) throws IOException, JSONException {
        String url = "https://www.netflix.com/api/aui/pathEvaluator/web/%5E2.0.0?method=call&falcor_server=0.1.0&callPath="
                + URLEncoder.encode("[\"aui\",\"viewingActivity\"," + page + "," + PAGE + "]", "UTF-8");
        String body = "param=" + URLEncoder.encode(new JSONObject().put("guid", guid).toString(), "UTF-8");
        JSONObject graph = new JSONObject(get(c, url, body)).optJSONObject("jsonGraph");
        JSONObject value = graph == null ? null : graph.optJSONObject("aui");
        value = value == null ? null : value.optJSONObject("viewingActivity");
        value = value == null ? null : value.optJSONObject("value");
        JSONArray viewed = value == null ? null : value.optJSONArray("viewedItems");
        return viewed == null ? new JSONArray() : viewed;
    }

    /** GET (or POST `form`) with the session's cookies; Netflix's renewed cookies are kept. Never follows a redirect. */
    private static String get(Context c, String url, String form) throws IOException {
        JSONObject a = account(c);
        if (a == null) throw new Expired();
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setInstanceFollowRedirects(false);
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(20_000);
        conn.setRequestProperty("User-Agent", UA);
        conn.setRequestProperty("Accept-Language", Ui.locale().toLanguageTag() + ",en;q=0.8");
        conn.setRequestProperty("Cookie", "NetflixId=" + a.optString("NetflixId") + "; SecureNetflixId=" + a.optString("SecureNetflixId"));
        try {
            if (form != null) {
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                conn.setRequestProperty("Accept", "application/json");
                conn.setRequestProperty("Origin", "https://www.netflix.com");
                conn.setRequestProperty("Referer", "https://www.netflix.com/settings/viewed");
                conn.setRequestProperty("x-netflix.request.routing",
                        "{\"path\":\"/nq/aui/endpoint/%5E1.0.0-web/pathEvaluator\",\"control_tag\":\"auinqweb\"}");
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(form.getBytes(StandardCharsets.UTF_8));
                }
            }
            int code = conn.getResponseCode();
            renewed(c, conn.getHeaderFields());
            if (code >= 300 && code < 400) {
                // Signed out, the home page sends you to the sign-in page (or the signed-out front page).
                if (url.endsWith("/browse") || String.valueOf(conn.getHeaderField("Location")).contains("login")) throw new Expired();
                throw new IOException("redirected (" + code + ")");
            }
            if (code == 401) throw new Expired();
            if (code != 200) throw new IOException("HTTP " + code + " for " + url.replaceAll("\\?.*", ""));
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] b = new byte[16384];
                int n;
                try {
                    while ((n = in.read(b)) > 0) buf.write(b, 0, n);
                } catch (IOException e) {
                    if (buf.size() == 0) throw e;   // Netflix's big pages sometimes end early: what came is enough
                }
                return buf.toString(StandardCharsets.UTF_8.name());
            }
        } finally {
            conn.disconnect();
        }
    }

    /** Netflix renews its session cookies now and then: the new ones replace the saved ones. */
    private static void renewed(Context c, Map<String, List<String>> headers) {
        String id = null, secure = null;
        for (Map.Entry<String, List<String>> h : headers.entrySet()) {
            if (h.getKey() == null || !h.getKey().equalsIgnoreCase("Set-Cookie")) continue;
            for (String v : h.getValue()) {
                String pair = v.split(";", 2)[0];
                int eq = pair.indexOf('=');
                if (eq <= 0) continue;
                String name = pair.substring(0, eq).trim(), value = pair.substring(eq + 1).trim();
                if (value.isEmpty() || v.toLowerCase().contains("max-age=0") || v.contains("1970")) continue;
                if (name.equals("NetflixId")) id = value;
                else if (name.equals("SecureNetflixId")) secure = value;
            }
        }
        if (id != null || secure != null) update(c, id, secure, false);
    }

    // MARK: - The last titles read (shown at once after a restart)

    private static List<WatchNext.Item> readCache(Context c) {
        List<WatchNext.Item> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(new String(Files.readAllBytes(new File(dir(c), "continue.json").toPath()), StandardCharsets.UTF_8));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                WatchNext.Item it = new WatchNext.Item(PKG, null, null);
                it.title = o.getString("title");
                it.episode = o.optString("episode").isEmpty() ? null : o.optString("episode");
                it.duration = o.optLong("duration");
                it.position = o.optLong("position");
                it.engaged = o.optLong("engaged");
                it.intent = o.optString("intent");
                out.add(it);
            }
        } catch (IOException | JSONException ignored) {   // none yet
        }
        return out;
    }

    private static void writeCache(Context c, List<WatchNext.Item> list) {
        try {
            JSONArray a = new JSONArray();
            for (WatchNext.Item it : list) {
                a.put(new JSONObject().put("title", it.title).put("episode", it.episode == null ? "" : it.episode)
                        .put("duration", it.duration).put("position", it.position).put("engaged", it.engaged).put("intent", it.intent));
            }
            write(new File(dir(c), "continue.json"), a.toString());
        } catch (IOException | JSONException ignored) {
        }
    }
}
