package com.oneuibook.aurora;

import android.content.Context;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Cloud Account Sync engine for Aurora Browser.
 * Works directly with Google Drive, Samsung Cloud Drive, OneDrive, or local storage via Android
 * Storage Access Framework (SAF) persistent document URI + Android Auto Backup (Google Account).
 * Syncs Bookmarks, Reading List, History, Installed Web Apps, Open Tabs, and Chrome Extension IDs.
 */
final class SyncEngine {
    private SyncEngine() {}

    static JSONObject buildSnapshot(Context c, TabManager tabs) throws Exception {
        JSONObject root = new JSONObject();
        root.put("app", "Aurora");
        root.put("version", MainActivity.VERSION);
        root.put("updatedAt", System.currentTimeMillis());

        JSONArray bm = new JSONArray();
        for (BrowserStore.Bookmark b : BrowserStore.bookmarks) bm.put(b.toJson());
        root.put("bookmarks", bm);

        JSONArray rl = new JSONArray();
        for (BrowserStore.Bookmark b : BrowserStore.readingList) rl.put(b.toJson());
        root.put("readingList", rl);

        JSONArray hist = new JSONArray();
        for (int i = 0; i < Math.min(BrowserStore.history.size(), 150); i++) {
            hist.put(BrowserStore.history.get(i).toJson());
        }
        root.put("history", hist);

        JSONArray apps = new JSONArray();
        for (BrowserStore.WebAppItem w : BrowserStore.webApps) apps.put(w.toJson());
        root.put("webApps", apps);

        JSONArray openUrls = new JSONArray();
        if (tabs != null) {
            for (TabManager.Tab t : tabs.normalTabs) {
                if (!t.isStartPage()) openUrls.put(t.url);
            }
        }
        root.put("openTabs", openUrls);

        JSONArray extIds = new JSONArray();
        for (ExtensionManager.Ext e : ExtensionManager.installed) {
            extIds.put(new JSONObject().put("id", e.id).put("name", e.name).put("enabled", e.enabled));
        }
        root.put("extensions", extIds);
        return root;
    }

    static List<String> mergeSnapshot(Context c, JSONObject root) {
        List<String> missingExtIds = new ArrayList<>();
        if (root == null) return missingExtIds;

        // Merge Bookmarks
        JSONArray bm = root.optJSONArray("bookmarks");
        if (bm != null) {
            Set<String> have = new HashSet<>();
            for (BrowserStore.Bookmark b : BrowserStore.bookmarks) have.add(b.url);
            for (int i = 0; i < bm.length(); i++) {
                JSONObject o = bm.optJSONObject(i);
                if (o != null && !have.contains(o.optString("url"))) {
                    BrowserStore.bookmarks.add(BrowserStore.Bookmark.fromJson(o));
                    have.add(o.optString("url"));
                }
            }
        }

        // Merge Reading List
        JSONArray rl = root.optJSONArray("readingList");
        if (rl != null) {
            Set<String> have = new HashSet<>();
            for (BrowserStore.Bookmark b : BrowserStore.readingList) have.add(b.url);
            for (int i = 0; i < rl.length(); i++) {
                JSONObject o = rl.optJSONObject(i);
                if (o != null && !have.contains(o.optString("url"))) {
                    BrowserStore.readingList.add(BrowserStore.Bookmark.fromJson(o));
                    have.add(o.optString("url"));
                }
            }
        }

        // Merge History
        JSONArray hist = root.optJSONArray("history");
        if (hist != null) {
            Set<String> have = new HashSet<>();
            for (BrowserStore.HistoryItem h : BrowserStore.history) have.add(h.url);
            for (int i = 0; i < hist.length(); i++) {
                JSONObject o = hist.optJSONObject(i);
                if (o != null && !have.contains(o.optString("url"))) {
                    BrowserStore.history.add(BrowserStore.HistoryItem.fromJson(o));
                    have.add(o.optString("url"));
                }
            }
        }

        // Merge Web Apps
        JSONArray apps = root.optJSONArray("webApps");
        if (apps != null) {
            Set<String> have = new HashSet<>();
            for (BrowserStore.WebAppItem w : BrowserStore.webApps) have.add(w.startUrl);
            for (int i = 0; i < apps.length(); i++) {
                JSONObject o = apps.optJSONObject(i);
                if (o != null && !have.contains(o.optString("url"))) {
                    BrowserStore.webApps.add(BrowserStore.WebAppItem.fromJson(o));
                    have.add(o.optString("url"));
                }
            }
        }

        // Collect synced Chrome extensions not yet installed locally
        JSONArray exts = root.optJSONArray("extensions");
        if (exts != null) {
            Set<String> haveExt = new HashSet<>();
            for (ExtensionManager.Ext e : ExtensionManager.installed) haveExt.add(e.id);
            for (int i = 0; i < exts.length(); i++) {
                JSONObject o = exts.optJSONObject(i);
                String id = o != null ? o.optString("id") : "";
                if (!id.isEmpty() && !haveExt.contains(id)) missingExtIds.add(id);
            }
        }

        BrowserStore.lastSyncInfo = "마지막 동기화: " + new SimpleDateFormat("MM/dd HH:mm", Locale.KOREA).format(new Date());
        BrowserStore.save(c);
        return missingExtIds;
    }

    static String syncWithUri(Context c, Uri uri, TabManager tabs) throws Exception {
        // 1. Read existing cloud snapshot if non-empty and merge
        List<String> missingExts = new ArrayList<>();
        try (InputStream is = c.getContentResolver().openInputStream(uri)) {
            if (is != null) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) > 0 && bos.size() < 4 * 1024 * 1024) bos.write(buf, 0, n);
                String txt = bos.toString("UTF-8").trim();
                if (txt.startsWith("{")) {
                    missingExts = mergeSnapshot(c, new JSONObject(txt));
                }
            }
        } catch (Throwable ignored) {}

        // 2. Write merged snapshot back to the cloud document (Google Drive / Samsung Cloud / OneDrive)
        JSONObject out = buildSnapshot(c, tabs);
        try (OutputStream os = c.getContentResolver().openOutputStream(uri, "wt")) {
            if (os == null) throw new Exception("동기화 파일에 쓸 수 없습니다");
            os.write(out.toString(2).getBytes(StandardCharsets.UTF_8));
            os.flush();
        }
        BrowserStore.syncUri = uri.toString();
        BrowserStore.lastSyncInfo = "마지막 동기화: " + new SimpleDateFormat("MM/dd HH:mm", Locale.KOREA).format(new Date());
        BrowserStore.save(c);
        return missingExts.isEmpty()
                ? "클라우드 동기화 완료 (" + BrowserStore.bookmarks.size() + "개 북마크)"
                : "동기화 완료 · 새 확장 프로그램 " + missingExts.size() + "개 복원 가능";
    }
}
