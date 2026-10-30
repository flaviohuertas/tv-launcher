package io.freemytv;

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.net.Uri;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What you were watching in your apps, from Android TV's own lists (TvContract, needs READ_TV_LISTINGS): the system
 * Watch Next list, where Prime Video, Disney+, HBO Max, Plex and others note what you left half-way, and the home
 * screen channels some apps publish under the name "Continue Watching" instead (Stremio). Newest first, a few per app,
 * so one show doesn't fill the row.
 */
final class WatchNext {
    private WatchNext() {}

    static final String PERMISSION = "android.permission.READ_TV_LISTINGS";
    /** Declared by apps that write the TV's lists (apps can also write their own rows and Watch Next without it). */
    private static final String WRITE_EPG = "com.android.providers.tv.permission.WRITE_EPG_DATA";
    static final int PER_APP = 2, MAX = 20;
    /** Channel names that mean "continue watching" (apps name their own channel). */
    private static final Set<String> CHANNEL_NAMES = new HashSet<>(java.util.Arrays.asList(
            "continue watching", "seguir viendo", "continuar viendo", "continuar assistindo", "continue assistindo", "continuar a ver"));

    static final class Item {
        String pkg, title, episode;     // episode: "S2 E5" (in the TV's language) or the episode's title, or null
        String app;                     // the app's name, for the card's details line
        long id, duration, position, engaged;
        String intent;
        final Uri pictureUri;
        final String pictureKey, bannerKey;
        private final String picture;

        Item(String pkg, String poster, String thumbnail) {
            this.pkg = pkg;
            picture = thumbnail != null ? thumbnail : poster;
            Uri uri = picture == null ? null : Uri.parse(picture);
            pictureUri = uri != null && ("content".equalsIgnoreCase(uri.getScheme())
                    || "android.resource".equalsIgnoreCase(uri.getScheme())
                    || "http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme())) ? uri : null;
            pictureKey = pictureUri == null ? null : picture;
            bannerKey = "banner:" + pkg;
        }

        /** The picture for a 16:9 card: the thumbnail (usually 16:9) before the poster. */
        String picture() {
            return picture;
        }

        /** 0 to 1, or -1 when there is no position worth showing (not started, or no length). */
        float progress() {
            if (duration <= 0 || position < 1000 || position >= duration) return -1;
            return (float) position / duration;
        }
    }

    static final class HiddenRecord {
        final long time;
        final long engaged;
        final long pos;
        final String ep;
        final String intent;

        HiddenRecord(long time, long engaged, long pos, String ep, String intent) {
            this.time = time;
            this.engaged = engaged;
            this.pos = pos;
            this.ep = ep != null ? ep : "";
            this.intent = intent != null ? intent : "";
        }
    }

    private static final Object LOCK = new Object();
    private static Map<String, HiddenRecord> hiddenMap;

    private static Map<String, HiddenRecord> getHiddenMap() {
        synchronized (LOCK) {
            if (hiddenMap == null) {
                hiddenMap = new HashMap<>();
                String raw = Prefs.watchHiddenRecords();
                if (raw != null && !raw.trim().isEmpty() && !raw.trim().equals("{}")) {
                    try {
                        org.json.JSONObject obj = new org.json.JSONObject(raw);
                        java.util.Iterator<String> keys = obj.keys();
                        while (keys.hasNext()) {
                            String k = keys.next();
                            org.json.JSONObject o = obj.optJSONObject(k);
                            if (o != null) {
                                hiddenMap.put(k, new HiddenRecord(
                                        o.optLong("time", 0),
                                        o.optLong("engaged", 0),
                                        o.optLong("pos", 0),
                                        o.optString("ep", ""),
                                        o.optString("intent", "")
                                ));
                            }
                        }
                    } catch (Throwable e) {
                        AppLog.w("failed to parse watch_hidden_records: " + e.getMessage());
                    }
                }
            }
            return hiddenMap;
        }
    }

    private static void saveHiddenMapLocked(Map<String, HiddenRecord> map) {
        try {
            org.json.JSONObject obj = new org.json.JSONObject();
            for (Map.Entry<String, HiddenRecord> e : map.entrySet()) {
                HiddenRecord r = e.getValue();
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("time", r.time);
                o.put("engaged", r.engaged);
                o.put("pos", r.pos);
                o.put("ep", r.ep);
                o.put("intent", r.intent);
                obj.put(e.getKey(), o);
            }
            Prefs.setWatchHiddenRecords(obj.toString());
        } catch (Throwable e) {
            AppLog.w("failed to save watch_hidden_records: " + e.getMessage());
        }
    }

    /** How a hidden item is remembered: its app and title (apps can't be asked to drop another app's entry). */
    static String hideKey(Item it) {
        return it.pkg + "|" + it.title;
    }

    static void hide(Context c, Item it) {
        if (it == null) return;
        synchronized (LOCK) {
            Map<String, HiddenRecord> map = getHiddenMap();
            long now = System.currentTimeMillis();
            HiddenRecord r = new HiddenRecord(now, it.engaged, it.position, it.episode, it.intent);
            String k1 = hideKey(it);
            String k2 = it.pkg + "|" + it.title;
            map.put(k1, r);
            map.put(k2, r);
            if (it.intent != null) map.put(it.pkg + "|" + it.intent, r);
            saveHiddenMapLocked(map);

            Set<String> hidden = Prefs.watchHidden();
            hidden.add(k1);
            hidden.add(k2);
            if (it.intent != null) hidden.add(it.pkg + "|" + it.intent);
            Prefs.setWatchHidden(hidden);
        }
        if (c != null && it.id > 0) {
            try {
                c.getContentResolver().delete(
                        TvContract.buildWatchNextProgramUri(it.id), null, null);
            } catch (Throwable ignored) {}
        }
    }

    static void unhide(Item it) {
        if (it == null) return;
        synchronized (LOCK) {
            Map<String, HiddenRecord> map = getHiddenMap();
            String k1 = hideKey(it);
            String k2 = it.pkg + "|" + it.title;
            map.remove(k1);
            map.remove(k2);
            if (it.intent != null) map.remove(it.pkg + "|" + it.intent);
            saveHiddenMapLocked(map);

            Set<String> hidden = Prefs.watchHidden();
            hidden.remove(k1);
            hidden.remove(k2);
            if (it.intent != null) hidden.remove(it.pkg + "|" + it.intent);
            Prefs.setWatchHidden(hidden);
        }
    }

    static int hiddenCount() {
        synchronized (LOCK) {
            Map<String, HiddenRecord> map = getHiddenMap();
            Set<HiddenRecord> unique = new HashSet<>(map.values());
            int count = unique.size();
            if (count == 0) {
                Set<String> set = Prefs.watchHidden();
                return set != null ? set.size() : 0;
            }
            return count;
        }
    }

    static void clearHidden() {
        synchronized (LOCK) {
            Map<String, HiddenRecord> map = getHiddenMap();
            map.clear();
            saveHiddenMapLocked(map);
            Prefs.setWatchHidden(new HashSet<>());
        }
    }

    private static HiddenRecord findRecordLocked(Item it, Map<String, HiddenRecord> map) {
        if (it == null || map == null) return null;
        HiddenRecord r = map.get(hideKey(it));
        if (r != null) return r;
        r = map.get(it.pkg + "|" + it.title);
        if (r != null) return r;
        if (it.intent != null) {
            r = map.get(it.pkg + "|" + it.intent);
            if (r != null) return r;
        }
        return null;
    }

    private static boolean isRewatched(Item it, HiddenRecord r) {
        if (it == null || r == null) return false;
        // Engaged timestamp advanced by at least 1 second
        if (it.engaged > r.engaged + 1000) return true;
        // Engagement occurred at or after the moment it was hidden
        if (r.time > 0 && it.engaged >= r.time && it.engaged > r.engaged) return true;
        // Playback position shifted by at least 3 seconds (or restarted)
        if (Math.abs(it.position - r.pos) >= 3000) return true;
        // Different episode of the same show
        if (it.episode != null && !it.episode.isEmpty() && !r.ep.isEmpty() && !it.episode.equals(r.ep)) return true;
        return false;
    }

    static boolean isHidden(Item it) {
        if (it == null) return false;
        synchronized (LOCK) {
            Map<String, HiddenRecord> map = getHiddenMap();
            HiddenRecord r = findRecordLocked(it, map);
            if (r != null) {
                if (isRewatched(it, r)) {
                    unhide(it);
                    return false;
                }
                return true;
            }
            Set<String> set = Prefs.watchHidden();
            if (set != null && !set.isEmpty()) {
                if (set.contains(hideKey(it)) || set.contains(it.pkg + "|" + it.title)
                        || (it.intent != null && set.contains(it.pkg + "|" + it.intent))) {
                    return true;
                }
            }
            return false;
        }
    }

    static boolean isHidden(Item it, Set<String> hidden) {
        if (it == null) return false;
        if (isHidden(it)) return true;
        if (hidden != null && !hidden.isEmpty()) {
            if (hidden.contains(it.pkg + "|" + it.title)) return true;
            if (it.intent != null && hidden.contains(it.pkg + "|" + it.intent)) return true;
            if (hidden.contains(hideKey(it))) return true;
        }
        return false;
    }

    static boolean allowed(Context c) {
        return c.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED;
    }

    /** Everything to show, newest first (off the main thread: these are content provider queries). */
    static List<Item> load(Context c) {
        if (!allowed(c)) return Collections.emptyList();
        ContentResolver cr = c.getContentResolver();
        try {
            android.content.Intent ping = new android.content.Intent("android.media.tv.action.INITIALIZE_PROGRAMS");
            ping.setPackage("com.nuvio.tv");
            c.sendBroadcast(ping);
        } catch (Throwable ignored) {}
        List<Item> all = new ArrayList<>(read(cr, TvContract.WatchNextPrograms.CONTENT_URI));
        for (long channel : continueChannels(cr, c.getPackageName())) {
            all.addAll(read(cr, TvContract.buildPreviewProgramsUriForChannel(channel)));
        }
        boolean hasNuvio = false;
        for (Item it : all) {
            if ("com.nuvio.tv".equals(it.pkg)) { hasNuvio = true; break; }
        }
        if (!hasNuvio) {
            try {
                all.addAll(read(cr, Uri.parse("content://android.media.tv/preview_program")));
            } catch (Throwable ignored) {}
        }
        Set<String> off = Prefs.watchOff();
        for (java.util.Iterator<Item> i = all.iterator(); i.hasNext(); ) {
            Item it = i.next();
            if (off.contains(it.pkg) || isHidden(it)) i.remove();
        }
        Collections.sort(all, (a, b) -> Long.compare(b.engaged, a.engaged));
        int max = Prefs.watchItems();
        int perAppLimit = Math.max(PER_APP, (max + 2) / 3);
        List<Item> out = new ArrayList<>();
        Map<String, Integer> perApp = new HashMap<>();
        Set<String> seen = new HashSet<>();
        for (Item it : all) {
            String key = it.pkg + "|" + (it.intent != null ? it.intent : it.title);
            int n = perApp.containsKey(it.pkg) ? perApp.get(it.pkg) : 0;
            if (n >= perAppLimit || !seen.add(key)) continue;
            perApp.put(it.pkg, n + 1);
            it.app = appName(c, it.pkg);
            out.add(it);
            if (out.size() >= max) break;
        }
        if (out.size() < max) {
            for (Item it : all) {
                String key = it.pkg + "|" + (it.intent != null ? it.intent : it.title);
                if (!seen.add(key)) continue;
                it.app = appName(c, it.pkg);
                out.add(it);
                if (out.size() >= max) break;
            }
        }
        return out;
    }

    /**
     * The apps that can feed Continue Watching (Settings, off the main thread): those allowed to write Android TV's
     * lists, plus any publishing right now. As {package, name}, sorted by name, without TV Launcher.
     */
    static List<String[]> sources(Context c) {
        Set<String> pkgs = new HashSet<>();
        PackageManager pm = c.getPackageManager();
        try {
            for (android.content.pm.PackageInfo p : pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)) {
                if (p.requestedPermissions == null) continue;
                for (String perm : p.requestedPermissions) {
                    if (WRITE_EPG.equals(perm)) { pkgs.add(p.packageName); break; }
                }
            }
        } catch (RuntimeException e) {
            AppLog.w("continue watching apps: " + e.getMessage());
        }
        if (allowed(c)) {
            ContentResolver cr = c.getContentResolver();
            packages(cr, TvContract.WatchNextPrograms.CONTENT_URI, TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME, null, pkgs);
            // Home screen rows (not the tuner's channels): apps write these, and Watch Next, without any permission.
            packages(cr, TvContract.Channels.CONTENT_URI, TvContract.Channels.COLUMN_PACKAGE_NAME, TvContract.Channels.COLUMN_TYPE, pkgs);
        }
        pkgs.remove(c.getPackageName());
        // Only apps you can open from the TV: leaves out system pieces (Shell) and home screens (other launchers),
        // which ask for the permission but have nothing to resume.
        Set<String> homes = new HashSet<>();
        for (android.content.pm.ResolveInfo ri : pm.queryIntentActivities(
                new android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME), 0)) {
            homes.add(ri.activityInfo.packageName);
        }
        List<String[]> out = new ArrayList<>();
        for (String p : pkgs) {
            if (homes.contains(p) || HomeApps.launchIntent(pm, p) == null) continue;
            String name = appName(c, p);
            out.add(new String[] {p, name.isEmpty() ? p : name});
        }
        Collections.sort(out, (a, b) -> a[1].toLowerCase(Locale.ROOT).compareTo(b[1].toLowerCase(Locale.ROOT)));
        return out;
    }

    /** The packages in a TvContract table (only preview channels when typeColumn is given). */
    private static void packages(ContentResolver cr, Uri uri, String column, String typeColumn, Set<String> out) {
        String[] projection = typeColumn == null ? new String[] {column} : new String[] {column, typeColumn};
        try (Cursor cur = cr.query(uri, projection, null, null, null)) {
            if (cur == null) return;
            while (cur.moveToNext()) {
                if (typeColumn != null && !TvContract.Channels.TYPE_PREVIEW.equals(cur.getString(1))) continue;
                String p = cur.isNull(0) ? null : cur.getString(0);
                if (p != null) out.add(p);
            }
        } catch (RuntimeException e) {
            AppLog.w("continue watching apps " + uri + ": " + e.getMessage());
        }
    }

    private static String appName(Context c, String pkg) {
        try {
            PackageManager pm = c.getPackageManager();
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    private static List<Long> continueChannels(ContentResolver cr, String self) {
        List<Long> ids = new ArrayList<>();
        try (Cursor cur = cr.query(TvContract.Channels.CONTENT_URI,
                new String[] {TvContract.Channels._ID, TvContract.Channels.COLUMN_DISPLAY_NAME,
                        TvContract.Channels.COLUMN_PACKAGE_NAME}, null, null, null)) {
            if (cur == null) return ids;
            int id = cur.getColumnIndex(TvContract.Channels._ID);
            int name = cur.getColumnIndex(TvContract.Channels.COLUMN_DISPLAY_NAME);
            int pkg = cur.getColumnIndex(TvContract.Channels.COLUMN_PACKAGE_NAME);
            while (cur.moveToNext()) {
                String n = name < 0 ? null : cur.getString(name), p = pkg < 0 ? null : cur.getString(pkg);
                if (!self.equals(p) && p != null) {
                    boolean isNuvio = "com.nuvio.tv".equals(p);
                    String lower = n != null ? n.trim().toLowerCase(Locale.ROOT) : "";
                    boolean isCw = isNuvio || CHANNEL_NAMES.contains(lower) || lower.contains("continuar") || lower.contains("continue");
                    if (isCw) ids.add(cur.getLong(id));
                }
            }
        } catch (RuntimeException e) {
            AppLog.w("continue watching channels: " + e.getMessage());
        }
        return ids;
    }

    private static List<Item> read(ContentResolver cr, Uri uri) {
        List<Item> out = new ArrayList<>();
        List<String> projection = new ArrayList<>(java.util.Arrays.asList(
                android.provider.BaseColumns._ID,
                TvContract.WatchNextPrograms.COLUMN_BROWSABLE,
                TvContract.WatchNextPrograms.COLUMN_TITLE,
                TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME,
                TvContract.WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER,
                TvContract.WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER,
                TvContract.WatchNextPrograms.COLUMN_EPISODE_TITLE,
                TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS,
                TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,
                TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI,
                TvContract.WatchNextPrograms.COLUMN_THUMBNAIL_URI,
                TvContract.WatchNextPrograms.COLUMN_INTENT_URI,
                TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS));
        if (TvContract.WatchNextPrograms.CONTENT_URI.equals(uri)) {
            projection.add(TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE);
        }
        try (Cursor cur = cr.query(uri, projection.toArray(new String[0]), null, null, null)) {
            if (cur == null) return out;
            while (cur.moveToNext()) {
                if ("0".equals(text(cur, TvContract.WatchNextPrograms.COLUMN_BROWSABLE))) continue;
                // Saved for later, not something you're in the middle of.
                if (number(cur, TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE)
                        == TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_WATCHLIST) continue;
                String title = text(cur, TvContract.WatchNextPrograms.COLUMN_TITLE);
                String pkg = text(cur, TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME);
                if (title == null || pkg == null) continue;
                Item it = new Item(pkg, text(cur, TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI),
                        text(cur, TvContract.WatchNextPrograms.COLUMN_THUMBNAIL_URI));
                it.title = title;
                String season = text(cur, TvContract.WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER);
                String episode = text(cur, TvContract.WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER);
                // Season and episode as each language writes them: "S2 E5", "T2 E5" (temporada), "St. 2 F. 5".
                it.episode = season != null && episode != null
                        ? Ui.trf("S{season} E{episode}", "season", season, "episode", episode)
                        : text(cur, TvContract.WatchNextPrograms.COLUMN_EPISODE_TITLE);
                if (it.episode != null && (it.episode.isEmpty() || it.title.contains(it.episode))) it.episode = null;
                it.duration = number(cur, TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS);
                it.position = number(cur, TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS);
                it.id = number(cur, android.provider.BaseColumns._ID);
                it.engaged = number(cur, TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS);
                if (it.engaged <= 0) it.engaged = it.position;
                it.intent = text(cur, TvContract.WatchNextPrograms.COLUMN_INTENT_URI);
                out.add(it);
            }
        } catch (RuntimeException e) {   // a provider that refuses (no permission on some firmware) means nothing to show
            AppLog.w("continue watching " + uri + ": " + e.getMessage());
        }
        return out;
    }

    private static String text(Cursor c, String column) {
        int i = c.getColumnIndex(column);
        return i < 0 || c.isNull(i) ? null : c.getString(i);
    }

    private static long number(Cursor c, String column) {
        int i = c.getColumnIndex(column);
        return i < 0 || c.isNull(i) ? 0 : c.getLong(i);
    }
}
