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
            try {
                for (String[] p : parse(c, dump)) changed |= record(c, p[0], p[1], p[2]);
                changed |= fillArt(c);
            } catch (RuntimeException e) {
                return;   // apps write their own titles: odd text must never crash the launcher
            }
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
    private static final java.util.regex.Pattern HEADER =
            java.util.regex.Pattern.compile("^( +)\\S.* ([A-Za-z0-9._]+)/.* \\(userId=\\d+\\)$");
    private static final java.util.regex.Pattern STACK =
            java.util.regex.Pattern.compile("^  Sessions Stack - have (\\d{1,4}) sessions?:$");

    /**
     * Playing sessions as {package, title, subtitle}. Apps control their titles, and a title can
     * contain line breaks, so this is strict about what it believes: fields count only at the
     * exact indentation of a session block and only once per block, the package must match the
     * session's own header line and belong to its owner uid (checked with PackageManager, not the
     * text), and if the number of session blocks differs from the count dumpsys states (someone
     * injected extra "sessions"), nothing from this reading is used.
     */
    private static List<String[]> parse(Context c, String dump) {
        List<String[]> out = new ArrayList<>();
        android.content.pm.PackageManager pm = c.getPackageManager();
        int expected = -1, headers = 0;
        String owner = null, pkg = null, state = null, meta = null;
        int fieldIndent = -1, uid = -1;
        String[] lines = dump.split("\n");
        for (int i = 0; i <= lines.length; i++) {
            String raw = i < lines.length ? lines[i].replace("\r", "") : null;
            java.util.regex.Matcher h = raw == null ? null : HEADER.matcher(raw);
            boolean header = h != null && h.matches();
            boolean stackEnd = raw != null && !raw.isEmpty() && fieldIndent >= 0 && !header
                    && indent(raw) < fieldIndent;
            if (raw == null || header || stackEnd) {
                // close the block before starting another one
                if (owner != null && owner.equals(pkg) && uid >= 0 && meta != null
                        && state != null && state.startsWith("PlaybackState {state=3,")) {
                    String[] owned = pm.getPackagesForUid(uid);
                    if (owned != null && java.util.Arrays.asList(owned).contains(pkg)) {
                        String[] tm = titleOf(meta);
                        if (tm != null) out.add(new String[]{pkg, tm[0], tm[1]});
                    }
                }
                owner = pkg = state = meta = null;
                uid = -1;
                fieldIndent = -1;
                if (header) {
                    headers++;
                    owner = h.group(2);
                    fieldIndent = h.group(1).length() + 2;
                }
                continue;
            }
            // the count line comes once, before any session block, at its own indentation (an app's
            // title can't be there: titles only appear inside blocks)
            if (headers == 0 && expected < 0) {
                java.util.regex.Matcher st = STACK.matcher(raw);
                if (st.matches()) expected = Integer.parseInt(st.group(1));   // at most 4 digits
            }
            if (fieldIndent < 0 || indent(raw) != fieldIndent) continue;
            String line = raw.trim();
            if (pkg == null && line.startsWith("package=")) pkg = line.substring(8);
            else if (uid < 0 && line.startsWith("ownerPid=")) uid = intAfter(line, "ownerUid=");
            else if (state == null && line.startsWith("state=")) state = line.substring(6);
            else if (meta == null && line.startsWith("metadata:")) meta = line;
        }
        if (expected >= 0 && headers != expected) return new ArrayList<>();
        return out;
    }

    private static int indent(String s) {
        int n = 0;
        while (n < s.length() && s.charAt(n) == ' ') n++;
        return n;
    }

    private static int intAfter(String line, String key) {
        int k = line.indexOf(key);
        if (k < 0) return -1;
        int e = k + key.length();
        while (e < line.length() && Character.isDigit(line.charAt(e))) e++;
        try {
            return Integer.parseInt(line.substring(k + key.length(), e));
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    /** "metadata:size=N, description=title, subtitle, description" -> {title, subtitle}. */
    private static String[] titleOf(String meta) {
        int d = meta.indexOf("description=");
        if (d < 0) return null;
        String desc = meta.substring(d + 12);
        if (desc.equals("null")) return null;
        // MediaDescription.toString() is "title, subtitle, description"; a title may contain ", "
        String[] parts = desc.split(", ");
        int n = parts.length;
        String title = n >= 3 ? String.join(", ", java.util.Arrays.copyOf(parts, n - 2)) : parts[0];
        String sub = n >= 3 ? parts[n - 2] : n == 2 ? parts[1] : "";
        return new String[]{clean(title), clean(sub)};
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
