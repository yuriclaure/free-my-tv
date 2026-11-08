package io.freemytv;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What's popular now, by Rotten Tomatoes: its "Popular at home" movies and "Popular TV" shows (what people look up on
 * its site this week), filtered as its site filters them to titles Fresh with critics and liked by audiences, in its
 * order, with each one's audience score (the Popcornmeter). Read from the site's own browse page, one request per kind:
 * Rotten Tomatoes has no public API, so a change to that page can stop it, and the picks then fall back to TMDB's.
 */
final class RtPopular {
    private RtPopular() {}

    static final class Title {
        String title;
        int year;        // a movie's year when the page gives one, else 0
        int audience;    // the audience score (%), or -1
    }

    private static final String BASE = "https://www.rottentomatoes.com/browse/";
    // The site answers its own pages to a browser; page=2 is its first two pages (56 titles).
    private static final String AGENT = "Mozilla/5.0 (Linux; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36";
    private static final Pattern TILE = Pattern.compile("<a\\s+class=\"js-tile-link\".*?href=\"/(m|tv)/[^\"]+\".*?</a>", Pattern.DOTALL);
    private static final Pattern NAME = Pattern.compile("discovery-media-list-item-title\">\\s*([^<]+?)\\s*<");
    private static final Pattern AUDIENCE = Pattern.compile("slot=\"audienceScore\"[^>]*>\\s*(\\d+)%");
    private static final Pattern DATE = Pattern.compile("start-date\">\\s*([^<]+?)\\s*<");
    private static final Pattern YEAR = Pattern.compile("(19|20)\\d\\d");

    /** "movie" or "tv": the list, most popular first. */
    static List<Title> fetch(String kind) throws IOException {
        String path = kind.equals("movie") ? "movies_at_home" : "tv_series_browse";
        String page = read(BASE + path + "/audience:upright~critics:fresh~sort:popular?page=2");
        List<Title> out = new ArrayList<>();
        Matcher tile = TILE.matcher(page);
        while (tile.find()) {
            String block = tile.group();
            Matcher name = NAME.matcher(block);
            if (!name.find()) continue;
            Title t = new Title();
            t.title = android.text.Html.fromHtml(name.group(1)).toString().trim();
            Matcher a = AUDIENCE.matcher(block);
            t.audience = a.find() ? Integer.parseInt(a.group(1)) : -1;
            Matcher d = DATE.matcher(block);
            if (d.find()) {
                Matcher y = YEAR.matcher(d.group(1));
                if (y.find()) t.year = Integer.parseInt(y.group());
            }
            if (!t.title.isEmpty()) out.add(t);
        }
        if (out.isEmpty()) throw new IOException("no titles on the page");
        return out;
    }

    private static final Pattern ROW = Pattern.compile("<search-page-media-row(.*?)</search-page-media-row>", Pattern.DOTALL);
    private static final Pattern ROW_LINK = Pattern.compile("href=\"(https://www\\.rottentomatoes\\.com/(m|tv)/[^\"]+)\"");
    private static final Pattern ROW_NAME = Pattern.compile("alt=\"([^\"]*)\"");
    private static final Pattern ROW_YEAR = Pattern.compile("(?:releaseyear|startyear)=\"(\\d{4})\"");
    private static final Pattern PAGE_AUDIENCE = Pattern.compile("\"audienceScore\":\\{[^}]*\"scorePercent\":\"(\\d+)%\"");

    /**
     * One title's audience score (%), from Rotten Tomatoes' search and the title's page (the search's own results:
     * same name and kind, a movie within a year of `year`); -1 when it has none, or none matches. Two requests.
     */
    static int audience(String title, int year, boolean show) throws IOException {
        String page = read("https://www.rottentomatoes.com/search?search=" + java.net.URLEncoder.encode(title, "UTF-8"));
        String want = key(title), link = null;
        Matcher row = ROW.matcher(page);
        while (row.find() && link == null) {
            String r = row.group(1);
            Matcher l = ROW_LINK.matcher(r), n = ROW_NAME.matcher(r), y = ROW_YEAR.matcher(r);
            if (!l.find() || !n.find() || !l.group(2).equals(show ? "tv" : "m")) continue;
            if (!key(android.text.Html.fromHtml(n.group(1)).toString()).equals(want)) continue;
            if (!show && year > 0 && y.find() && Math.abs(Integer.parseInt(y.group(1)) - year) > 1) continue;
            link = l.group(1);
        }
        if (link == null) return -1;
        Matcher a = PAGE_AUDIENCE.matcher(read(link));
        return a.find() ? Integer.parseInt(a.group(1)) : -1;
    }

    private static String key(String title) {
        return title.toLowerCase(java.util.Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    static String read(String address) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        c.setConnectTimeout(15_000);
        c.setReadTimeout(20_000);
        c.setInstanceFollowRedirects(false);
        c.setRequestProperty("User-Agent", AGENT);
        c.setRequestProperty("Accept-Language", "en");
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 19);
                byte[] buf = new byte[32 * 1024];
                int n;
                while ((n = in.read(buf)) > 0 && out.size() < (4 << 20)) out.write(buf, 0, n);
                return out.toString("UTF-8");
            }
        } finally {
            c.disconnect();
        }
    }
}
