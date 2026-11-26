package com.oneuibook.aurora;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Persistent browser data store: Favorites/Bookmarks, Reading List, History,
 * Installed Web Apps (PWAs), Open Normal Tabs (Private tabs are NEVER persisted),
 * and Privacy Report tracker-block counter.
 */
final class BrowserStore {
    private BrowserStore() {}

    static final class Bookmark {
        String title, url;
        int color;
        Bookmark(String title, String url, int color) {
            this.title = title; this.url = url; this.color = color;
        }
        JSONObject toJson() throws Exception {
            return new JSONObject().put("title", title).put("url", url).put("color", color);
        }
        static Bookmark fromJson(JSONObject o) {
            return new Bookmark(o.optString("title"), o.optString("url"), o.optInt("color", 0xFF0A84FF));
        }
    }

    static final class HistoryItem {
        String title, url;
        long time;
        HistoryItem(String title, String url, long time) {
            this.title = title; this.url = url; this.time = time;
        }
        JSONObject toJson() throws Exception {
            return new JSONObject().put("title", title).put("url", url).put("time", time);
        }
        static HistoryItem fromJson(JSONObject o) {
            return new HistoryItem(o.optString("title"), o.optString("url"), o.optLong("time"));
        }
    }

    static final class WebAppItem {
        String id, name, startUrl;
        int themeColor;
        WebAppItem(String id, String name, String startUrl, int themeColor) {
            this.id = id; this.name = name; this.startUrl = startUrl; this.themeColor = themeColor;
        }
        JSONObject toJson() throws Exception {
            return new JSONObject().put("id", id).put("name", name).put("url", startUrl).put("color", themeColor);
        }
        static WebAppItem fromJson(JSONObject o) {
            return new WebAppItem(o.optString("id"), o.optString("name"), o.optString("url"), o.optInt("color", 0xFF0A84FF));
        }
    }

    static final List<Bookmark> bookmarks = new ArrayList<>();
    static final List<Bookmark> readingList = new ArrayList<>();
    static final List<HistoryItem> history = new ArrayList<>();
    static final List<WebAppItem> webApps = new ArrayList<>();
    static final Set<String> desktopHosts = new HashSet<>();
    static int blockedTrackers;
    static String lastSyncInfo = "";
    static String syncUri = "";
    private static boolean loaded;

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("aurora_store", Context.MODE_PRIVATE);
    }

    static void load(Context c) {
        if (loaded) return;
        loaded = true;
        SharedPreferences p = sp(c);
        blockedTrackers = p.getInt("blocked_trackers", 0);
        lastSyncInfo = p.getString("last_sync_info", "");
        syncUri = p.getString("sync_uri", "");

        bookmarks.clear();
        String bmRaw = p.getString("bookmarks", null);
        if (bmRaw == null) {
            seedDefaultFavorites();
        } else {
            try {
                JSONArray a = new JSONArray(bmRaw);
                for (int i = 0; i < a.length(); i++) bookmarks.add(Bookmark.fromJson(a.getJSONObject(i)));
            } catch (Exception ignored) { seedDefaultFavorites(); }
        }

        readingList.clear();
        try {
            JSONArray a = new JSONArray(p.getString("reading_list", "[]"));
            for (int i = 0; i < a.length(); i++) readingList.add(Bookmark.fromJson(a.getJSONObject(i)));
        } catch (Exception ignored) {}

        history.clear();
        try {
            JSONArray a = new JSONArray(p.getString("history", "[]"));
            for (int i = 0; i < a.length(); i++) history.add(HistoryItem.fromJson(a.getJSONObject(i)));
        } catch (Exception ignored) {}

        webApps.clear();
        try {
            JSONArray a = new JSONArray(p.getString("webapps", "[]"));
            for (int i = 0; i < a.length(); i++) webApps.add(WebAppItem.fromJson(a.getJSONObject(i)));
        } catch (Exception ignored) {}

        desktopHosts.clear();
        Set<String> dh = p.getStringSet("desktop_hosts", null);
        if (dh != null) desktopHosts.addAll(dh);
    }

    private static void seedDefaultFavorites() {
        bookmarks.add(new Bookmark("Google", "https://www.google.com", 0xFF4285F4));
        bookmarks.add(new Bookmark("네이버", "https://m.naver.com", 0xFF03C75A));
        bookmarks.add(new Bookmark("YouTube", "https://m.youtube.com", 0xFFFF0033));
        bookmarks.add(new Bookmark("웹 스토어", "https://chromewebstore.google.com", 0xFF1A73E8));
        bookmarks.add(new Bookmark("GitHub", "https://github.com", 0xFF24292F));
        bookmarks.add(new Bookmark("위키백과", "https://ko.wikipedia.org", 0xFF5C6B73));
        bookmarks.add(new Bookmark("나무위키", "https://namu.wiki", 0xFF00A495));
        bookmarks.add(new Bookmark("Apple", "https://www.apple.com", 0xFF1D1D1F));
    }

    static void save(Context c) {
        try {
            JSONArray b = new JSONArray();
            for (Bookmark x : bookmarks) b.put(x.toJson());
            JSONArray r = new JSONArray();
            for (Bookmark x : readingList) r.put(x.toJson());
            JSONArray h = new JSONArray();
            for (int i = 0; i < Math.min(history.size(), 250); i++) h.put(history.get(i).toJson());
            JSONArray w = new JSONArray();
            for (WebAppItem x : webApps) w.put(x.toJson());

            sp(c).edit()
                    .putString("bookmarks", b.toString())
                    .putString("reading_list", r.toString())
                    .putString("history", h.toString())
                    .putString("webapps", w.toString())
                    .putStringSet("desktop_hosts", new HashSet<>(desktopHosts))
                    .putInt("blocked_trackers", blockedTrackers)
                    .putString("last_sync_info", lastSyncInfo)
                    .putString("sync_uri", syncUri)
                    .apply();
        } catch (Exception ignored) {}
    }

    static void recordHistory(Context c, String title, String url) {
        if (url == null || url.isEmpty() || !url.startsWith("http")) return;
        String cleanTitle = (title == null || title.trim().isEmpty()) ? url : title.trim();
        for (int i = 0; i < history.size(); i++) {
            if (url.equals(history.get(i).url)) { history.remove(i); break; }
        }
        history.add(0, new HistoryItem(cleanTitle, url, System.currentTimeMillis()));
        while (history.size() > 250) history.remove(history.size() - 1);
        save(c);
    }

    static boolean isBookmarked(String url) {
        if (url == null) return false;
        for (Bookmark b : bookmarks) if (url.equals(b.url)) return true;
        return false;
    }

    static void addOrToggleBookmark(Context c, String title, String url) {
        if (url == null || url.isEmpty()) return;
        for (int i = 0; i < bookmarks.size(); i++) {
            if (url.equals(bookmarks.get(i).url)) {
                bookmarks.remove(i);
                save(c);
                return;
            }
        }
        int[] palette = {0xFF0A84FF, 0xFF30D158, 0xFFFF9F0A, 0xFFBF5AF2, 0xFFFF375F, 0xFF64D2FF};
        int col = palette[Math.abs(url.hashCode()) % palette.length];
        bookmarks.add(0, new Bookmark(title == null || title.isEmpty() ? domainOf(url) : title, url, col));
        save(c);
    }

    static void addReadingList(Context c, String title, String url) {
        if (url == null || url.isEmpty()) return;
        for (Bookmark b : readingList) if (url.equals(b.url)) return;
        readingList.add(0, new Bookmark(title == null || title.isEmpty() ? domainOf(url) : title, url, 0xFF0A84FF));
        save(c);
    }

    static void addWebApp(Context c, WebAppItem app) {
        for (int i = 0; i < webApps.size(); i++) {
            if (app.startUrl.equals(webApps.get(i).startUrl)) { webApps.remove(i); break; }
        }
        webApps.add(0, app);
        save(c);
    }

    static void incrementBlocked(Context c) {
        blockedTrackers++;
        if ((blockedTrackers & 7) == 0) sp(c).edit().putInt("blocked_trackers", blockedTrackers).apply();
    }

    static void saveNormalTabs(Context c, List<String> urls, int activeIdx) {
        JSONArray a = new JSONArray();
        for (String u : urls) a.put(u == null ? "" : u);
        sp(c).edit().putString("saved_tabs", a.toString()).putInt("saved_tab_idx", activeIdx).apply();
    }

    static List<String> loadSavedTabs(Context c) {
        List<String> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(sp(c).getString("saved_tabs", "[]"));
            for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
        } catch (Exception ignored) {}
        return out;
    }

    static int loadSavedTabIndex(Context c) {
        return sp(c).getInt("saved_tab_idx", 0);
    }

    static String domainOf(String url) {
        if (url == null || url.isEmpty()) return "";
        try {
            android.net.Uri u = android.net.Uri.parse(url);
            String host = u.getHost();
            if (host != null && !host.isEmpty()) {
                return host.startsWith("www.") ? host.substring(4) : host;
            }
        } catch (Exception ignored) {}
        return url;
    }
}
