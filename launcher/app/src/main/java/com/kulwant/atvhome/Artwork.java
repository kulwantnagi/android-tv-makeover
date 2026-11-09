package com.kulwant.atvhome;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A picture for a "last played" title, since apps on this TV don't hand theirs over. No API keys:
 * YouTube videos get the first search result's thumbnail; shows a TVMaze poster; films (and shows
 * TVMaze doesn't know) the poster on their Wikipedia page. Names must match the title, so a wrong
 * guess shows no picture rather than someone else's. Every method returns null on any failure.
 */
final class Artwork {

    private static final String BROWSER =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36";
    // Wikipedia asks API clients to name themselves
    private static final String AGENT = "atvhome/1.0 (Android TV home screen; personal use)";
    private static final Pattern VIDEO_ID = Pattern.compile("\"videoId\":\"([A-Za-z0-9_-]{11})\"");

    private Artwork() {}

    /** Image URL for this history entry, or null. */
    static String find(String pkg, String title, String sub) {
        if (pkg.contains("youtube")) return youtube(title);
        boolean show = !sub.isEmpty();
        String url = show ? tvmaze(title) : null;
        if (url == null) url = wikipedia(title, show ? "TV series" : "film");
        if (url == null && !show) url = tvmaze(title);   // no subtitle doesn't always mean a film
        return url;
    }

    /** Downloads and scales to at most maxW wide. */
    static Bitmap download(String url, int maxW) {
        byte[] data = bytes(url, 4 << 20);
        if (data == null) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        int sample = 1;
        while (o.outWidth / (sample * 2) >= maxW) sample *= 2;
        o.inJustDecodeBounds = false;
        o.inSampleSize = sample;
        Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length, o);
        if (b == null || b.getWidth() <= maxW) return b;
        Bitmap s = Bitmap.createScaledBitmap(b, maxW, b.getHeight() * maxW / b.getWidth(), true);
        b.recycle();
        return s;
    }

    // ---------------------------------------------------------------- sources

    private static String youtube(String title) {
        String id = firstMatch("https://www.youtube.com/results?search_query=" + enc(title), VIDEO_ID);
        return id == null ? null : "https://i.ytimg.com/vi/" + id + "/mqdefault.jpg";
    }

    private static String tvmaze(String title) {
        try {
            JSONObject o = new JSONObject(text("https://api.tvmaze.com/singlesearch/shows?q=" + enc(title)));
            if (!same(o.getString("name"), title)) return null;
            JSONObject img = o.optJSONObject("image");
            return img == null ? null : img.optString("medium", null);
        } catch (Exception e) {
            return null;
        }
    }

    private static String wikipedia(String title, String kind) {
        try {
            JSONArray hits = new JSONObject(text("https://en.wikipedia.org/w/api.php?action=query&format=json"
                    + "&list=search&srlimit=3&srsearch=" + enc(title + " " + kind)))
                    .getJSONObject("query").getJSONArray("search");
            for (int i = 0; i < hits.length(); i++) {
                String page = hits.getJSONObject(i).getString("title");
                // "Jawan (film)" matches "Jawan"; "Animal Kingdom (film)" doesn't match "Animal"
                int paren = page.indexOf(" (");
                if (!same(paren > 0 ? page.substring(0, paren) : page, title)) continue;
                JSONObject s = new JSONObject(text("https://en.wikipedia.org/api/rest_v1/page/summary/"
                        + enc(page.replace(' ', '_'))));
                JSONObject t = s.optJSONObject("thumbnail");
                return t == null ? null : t.optString("source", null);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static boolean same(String a, String b) {
        return norm(a).equals(norm(b));
    }

    private static String norm(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return s;
        }
    }

    // ---------------------------------------------------------------- http

    private static HttpURLConnection open(String url, String agent) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(15_000);
        c.setRequestProperty("User-Agent", agent);
        c.setRequestProperty("Accept-Language", "en-US,en");
        return c;
    }

    private static String text(String url) throws Exception {
        byte[] b = bytes(url, 1 << 20);
        if (b == null) throw new java.io.IOException("no response");
        return new String(b, StandardCharsets.UTF_8);
    }

    private static byte[] bytes(String url, int max) {
        HttpURLConnection c = null;
        try {
            c = open(url, AGENT);
            if (c.getResponseCode() != 200) return null;
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > max) return null;
                }
                return out.toByteArray();
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Scans a big page in chunks (YouTube's is ~1 MB) and stops at the first match. */
    private static String firstMatch(String url, Pattern p) {
        HttpURLConnection c = null;
        try {
            c = open(url, BROWSER);
            if (c.getResponseCode() != 200) return null;
            try (Reader r = new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8)) {
                char[] buf = new char[64 * 1024];
                StringBuilder win = new StringBuilder();
                int n;
                while ((n = r.read(buf)) > 0) {
                    win.append(buf, 0, n);
                    Matcher m = p.matcher(win);
                    if (m.find()) return m.group(1);
                    if (win.length() > 512) win.delete(0, win.length() - 512);
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.disconnect();
        }
        return null;
    }
}
