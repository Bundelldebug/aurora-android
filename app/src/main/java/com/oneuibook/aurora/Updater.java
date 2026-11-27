package com.oneuibook.aurora;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** GitHub Releases in-app update checker for Aurora Android (Bundelldebug/aurora-android). */
final class Updater {
    private Updater() {}
    private static final String REPO = "Bundelldebug/aurora-android";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    static void check(Activity a, boolean userTriggered) {
        ExtensionManager.IO.execute(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL("https://api.github.com/repos/" + REPO + "/releases/latest").openConnection();
                c.setConnectTimeout(10000);
                c.setReadTimeout(10000);
                c.setRequestProperty("Accept", "application/vnd.github+json");
                c.setRequestProperty("User-Agent", "Aurora/" + MainActivity.VERSION);
                if (c.getResponseCode() != 200) {
                    if (userTriggered) MAIN.post(() -> Toast.makeText(a, "최신 버전을 사용 중입니다 (" + MainActivity.VERSION + ")", Toast.LENGTH_SHORT).show());
                    return;
                }
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                try (InputStream is = c.getInputStream()) {
                    byte[] b = new byte[4096]; int n;
                    while ((n = is.read(b)) > 0) bos.write(b, 0, n);
                }
                JSONObject o = new JSONObject(bos.toString("UTF-8"));
                String tag = o.optString("tag_name", "").replaceFirst("^v", "");
                String notes = o.optString("body", "").replaceAll("(?m)^#+\\s*", "").replace("**", "");
                String url = o.optString("html_url", "https://github.com/" + REPO + "/releases");
                JSONArray assets = o.optJSONArray("assets");
                if (assets != null) {
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject as = assets.optJSONObject(i);
                        if (as != null && as.optString("name").endsWith(".apk")) {
                            url = as.optString("browser_download_url", url);
                            break;
                        }
                    }
                }
                final String dlUrl = isTrustedReleaseUrl(url) ? url : ("https://github.com/" + REPO + "/releases");
                if (isNewer(tag, MainActivity.VERSION)) {
                    MAIN.post(() -> new AlertDialog.Builder(a)
                            .setTitle("오로라 " + tag + " 업데이트")
                            .setMessage(notes.isEmpty() ? "새 버전이 출시되었습니다." : notes)
                            .setPositiveButton("다운로드", (d, w) -> {
                                try { a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(dlUrl))); } catch (Throwable ignored) {}
                            })
                            .setNegativeButton("나중에", null)
                            .show());
                } else if (userTriggered) {
                    MAIN.post(() -> Toast.makeText(a, "최신 버전입니다 (" + MainActivity.VERSION + ")", Toast.LENGTH_SHORT).show());
                }
            } catch (Throwable t) {
                if (userTriggered) MAIN.post(() -> Toast.makeText(a, "업데이트 확인 실패", Toast.LENGTH_SHORT).show());
            }
        });
    }

    /** Validates that release download URLs strictly belong to this project's GitHub Releases over HTTPS. */
    static boolean isTrustedReleaseUrl(String url) {
        if (url == null || !url.startsWith("https://")) return false;
        try {
            Uri u = Uri.parse(url);
            String host = u.getHost();
            if (host == null) return false;
            String h = host.toLowerCase(java.util.Locale.ROOT);
            if ("github.com".equals(h)) {
                String p = u.getPath();
                return p != null && p.toLowerCase(java.util.Locale.ROOT).startsWith("/" + REPO.toLowerCase(java.util.Locale.ROOT) + "/");
            }
            return "objects.githubusercontent.com".equals(h) || "release-assets.githubusercontent.com".equals(h);
        } catch (Throwable ignored) {
            return false;
        }
    }

    static boolean isNewer(String remote, String local) {
        String[] r = remote.split("\\."), l = local.split("\\.");
        int n = Math.max(r.length, l.length);
        for (int i = 0; i < n; i++) {
            int rv = i < r.length ? parseIntSafe(r[i]) : 0;
            int lv = i < l.length ? parseIntSafe(l[i]) : 0;
            if (rv != lv) return rv > lv;
        }
        return false;
    }

    private static int parseIntSafe(String s) {
        try { return Integer.parseInt(s.replaceAll("\\D+", "")); } catch (Exception e) { return 0; }
    }
}
