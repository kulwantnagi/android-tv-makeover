package com.kulwant.atvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * What was last played in each app, for the small row above the dock.
 *
 * Apps report what they play to Android's media sessions. Reading those directly needs a
 * notification listener, which Android 9 refuses on low-RAM TVs like this one, so the launcher reads
 * `dumpsys media_session` through its own ADB (see LocalAdb) every
 * POLL_MS while the screen is on. That gives title and subtitle, not artwork.
 */
final class PlayHistory {

    interface Listener {
        /** Called on the main thread after something new was recorded. */
        void onHistoryChanged();
    }

    static final class Item {
        String pkg, title, sub;
        /** picture file name in dir(), or "" when none was found (yet) */
        String art = "";
        long at;
    }

    static final int PER_APP = 4;
    static final long POLL_MS = 15_000;
    private static final int MAX_ITEMS = 120;
    /** while the same thing keeps playing, its time is saved at most this often */
    private static final long RESAVE_MS = 5 * 60_000L;
    /** media sessions that aren't something you watched (the TV's HDMI/AV inputs) */
    private static final String[] IGNORED = {"com.android.tv"};

    private static volatile Listener listener;
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static List<Item> items;   // newest first
    private static long savedAt;

    private PlayHistory() {}

    static void setListener(Listener l) {
        listener = l;
    }

    /** Newest first, at most PER_APP. */
    static synchronized List<Item> forApp(Context c, String pkg) {
        load(c);
        List<Item> out = new ArrayList<>();
        for (Item it : items) {
            if (it.pkg.equals(pkg)) out.add(it);
            if (out.size() == PER_APP) break;
        }
        return out;
    }

    /** Reads what is playing now and records it. Blocks on ADB: call off the main thread. */
    static void poll(Context c) {
        // the home screen and PhoneService both poll: one at a time, so a picture is fetched once
        synchronized (POLL_LOCK) {
            String dump;
            try {
                dump = LocalAdb.shell(c, "dumpsys media_session");
            } catch (Exception e) {
                return;
            }
            boolean changed = false;
            for (String[] p : parse(dump)) changed |= record(c, p[0], p[1], p[2]);
            changed |= fillArt(c);
            Listener l = listener;
            if (changed && l != null) main.post(l::onHistoryChanged);
        }
    }

    private static final Object POLL_LOCK = new Object();

    static File dir(Context c) {
        File d = new File(c.getFilesDir(), "history");
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    /** item -> lookups tried so far this run; a title with no picture anywhere is given up on */
    private static final java.util.Map<String, Integer> artTries = new java.util.HashMap<>();

    /** Looks up pictures for up to two entries that have none (network: off the main thread). */
    private static boolean fillArt(Context c) {
        List<Item> todo = new ArrayList<>();
        synchronized (PlayHistory.class) {
            load(c);
            for (Item it : items) {
                Integer n = artTries.get(key(it));
                if (it.art.isEmpty() && (n == null || n < 3)) todo.add(it);
                if (todo.size() == 2) break;
            }
        }
        boolean changed = false;
        for (Item it : todo) {
            String k = key(it);
            artTries.put(k, artTries.containsKey(k) ? artTries.get(k) + 1 : 1);
            String url = Artwork.find(it.pkg, it.title, it.sub);
            android.graphics.Bitmap b = url == null ? null : Artwork.download(url, 320);
            if (b == null) continue;
            String name = Integer.toHexString(k.hashCode()) + ".jpg";
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(new File(dir(c), name))) {
                b.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out);
            } catch (Exception e) {
                continue;
            } finally {
                b.recycle();
            }
            synchronized (PlayHistory.class) {
                it.art = name;
                save(c);
            }
            changed = true;
        }
        return changed;
    }

    private static String key(Item it) {
        return it.pkg + "\n" + it.title;
    }

    /** "2 h ago", "Yesterday", ... */
    static String ago(long at) {
        long m = Math.max(0, System.currentTimeMillis() - at) / 60_000L;
        if (m < 2) return "Just now";
        if (m < 60) return m + " min ago";
        if (m < 24 * 60) return (m / 60) + " h ago";
        long d = m / (24 * 60);
        return d == 1 ? "Yesterday" : d + " days ago";
    }

    // ---------------------------------------------------------------- dumpsys

    /** {package, title, subtitle} of every session that is playing. */
    private static List<String[]> parse(String dump) {
        List<String[]> out = new ArrayList<>();
        String pkg = null;
        boolean playing = false;
        for (String raw : dump.split("\n")) {
            String line = raw.trim();
            if (line.startsWith("package=")) {
                pkg = line.substring(8);
                playing = false;
            } else if (line.startsWith("state=")) {
                playing = line.startsWith("state=PlaybackState {state=3,");   // STATE_PLAYING
            } else if (line.startsWith("metadata:") && pkg != null && playing) {
                int d = line.indexOf("description=");
                if (d < 0) continue;
                // MediaDescription.toString() is "title, subtitle, description"; a title may contain ", "
                String[] parts = line.substring(d + 12).split(", ");
                int n = parts.length;
                String title = n >= 3 ? String.join(", ", java.util.Arrays.copyOf(parts, n - 2)) : parts[0];
                String sub = n >= 3 ? parts[n - 2] : n == 2 ? parts[1] : "";
                out.add(new String[]{pkg, clean(title), clean(sub)});
            }
        }
        return out;
    }

    private static String clean(String s) {
        s = s.trim();
        return s.equals("null") ? "" : s;
    }

    // ---------------------------------------------------------------- store

    /** Forgets everything (Settings tray: Clear watch history). */
    static void clear(Context c) {
        synchronized (PlayHistory.class) {
            load(c);
            for (Item it : items) dropArt(c, it);
            items.clear();
            save(c);
        }
        Listener l = listener;
        if (l != null) main.post(l::onHistoryChanged);
    }

    private static synchronized boolean record(Context c, String pkg, String title, String sub) {
        if (title.isEmpty() || pkg.equals(c.getPackageName())) return false;
        for (String p : IGNORED) if (p.equals(pkg)) return false;
        load(c);
        long now = System.currentTimeMillis();
        for (Item it : items) {
            if (!it.pkg.equals(pkg)) continue;
            // still playing the newest item of this app: just move its time on
            if (it.title.equals(title) && it.sub.equals(sub)) {
                it.at = now;
                if (now - savedAt > RESAVE_MS) save(c);
                return false;
            }
            break;
        }
        Item it = new Item();
        it.pkg = pkg;
        it.title = title;
        it.sub = sub;
        it.at = now;
        for (int i = items.size() - 1; i >= 0; i--) {
            Item old = items.get(i);
            if (!old.pkg.equals(pkg) || !old.title.equals(title)) continue;
            it.art = old.art;   // same show, next episode: keep its picture
            items.remove(i);
        }
        items.add(0, it);
        int ofApp = 0;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).pkg.equals(pkg) && ++ofApp > PER_APP) dropArt(c, items.remove(i--));
        }
        while (items.size() > MAX_ITEMS) dropArt(c, items.remove(items.size() - 1));
        save(c);
        return true;
    }

    private static void dropArt(Context c, Item it) {
        if (!it.art.isEmpty()) new File(dir(c), it.art).delete();
    }

    private static void load(Context c) {
        if (items != null) return;
        items = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs(c).getString("items", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Item it = new Item();
                it.pkg = o.getString("pkg");
                it.title = o.getString("title");
                it.sub = o.optString("sub");
                it.art = o.optString("art");
                it.at = o.getLong("at");
                items.add(it);
            }
        } catch (Exception ignored) {
        }
    }

    private static void save(Context c) {
        try {
            JSONArray a = new JSONArray();
            for (Item it : items) {
                a.put(new JSONObject().put("pkg", it.pkg).put("title", it.title).put("sub", it.sub)
                        .put("art", it.art).put("at", it.at));
            }
            prefs(c).edit().putString("items", a.toString()).apply();
            savedAt = System.currentTimeMillis();
        } catch (Exception ignored) {
        }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("history", Context.MODE_PRIVATE);
    }
}
