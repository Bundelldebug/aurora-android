package com.oneuibook.aurora;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Icon;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Real PWA / Web App installer:
 * Inspects the active page's <link rel="manifest"> (or apple-mobile-web-app / favicon metadata),
 * downloads the highest-resolution app icon, registers the Web App in Aurora's Start Page shelf,
 * and pins a real home-screen launcher icon via ShortcutManager.requestPinShortcut() opening WebAppActivity.
 */
final class WebAppInstaller {
    private WebAppInstaller() {}

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    static void installFromTab(Context ctx, TabManager.Tab tab, Runnable onInstalled) {
        if (tab == null || tab.isStartPage()) {
            Toast.makeText(ctx, "웹사이트를 연 상태에서 홈 화면에 추가할 수 있습니다.", Toast.LENGTH_SHORT).show();
            return;
        }
        String pageUrl = tab.url;
        String fallbackTitle = (tab.title != null && !tab.title.isEmpty()) ? tab.title : BrowserStore.domainOf(pageUrl);
        Bitmap fallbackFav = tab.favicon;

        if (tab.webView == null) {
            finishInstall(ctx, fallbackTitle, pageUrl, 0xFF0A84FF, fallbackFav, onInstalled);
            return;
        }

        String probeJs = "(function(){"
                + "var mf=document.querySelector('link[rel=\"manifest\"]');"
                + "var tc=document.querySelector('meta[name=\"theme-color\"]');"
                + "var at=document.querySelector('meta[name=\"apple-mobile-web-app-title\"]');"
                + "var ic=document.querySelector('link[rel=\"apple-touch-icon\"]')||document.querySelector('link[rel~=\"icon\"]');"
                + "return JSON.stringify({"
                + "  manifest:mf?mf.href:'',"
                + "  theme:tc?tc.content:'',"
                + "  title:at?at.content:(document.title||''),"
                + "  icon:ic?ic.href:''"
                + "});})();";

        tab.webView.evaluateJavascript(probeJs, raw -> {
            ExtensionManager.IO.execute(() -> {
                String name = fallbackTitle;
                String startUrl = pageUrl;
                int themeCol = 0xFF0A84FF;
                String iconUrl = "";
                try {
                    String unquoted = new JSONArray("[" + raw + "]").optString(0, "{}");
                    JSONObject meta = new JSONObject(unquoted);
                    if (!meta.optString("title").isEmpty()) name = meta.optString("title");
                    if (!meta.optString("icon").isEmpty()) iconUrl = meta.optString("icon");
                    themeCol = parseColorSafe(meta.optString("theme"), themeCol);

                    String mfUrl = meta.optString("manifest");
                    if (!mfUrl.isEmpty()) {
                        String mfJson = fetchString(mfUrl);
                        if (mfJson != null && !mfJson.isEmpty()) {
                            JSONObject mf = new JSONObject(mfJson);
                            String sn = mf.optString("short_name", mf.optString("name", ""));
                            if (!sn.isEmpty()) name = sn;
                            String su = mf.optString("start_url", "");
                            if (!su.isEmpty()) startUrl = new URL(new URL(mfUrl), su).toString();
                            themeCol = parseColorSafe(mf.optString("theme_color"), themeCol);
                            JSONArray icons = mf.optJSONArray("icons");
                            if (icons != null && icons.length() > 0) {
                                int bestSz = 0;
                                for (int i = 0; i < icons.length(); i++) {
                                    JSONObject io = icons.optJSONObject(i);
                                    if (io == null) continue;
                                    String src = io.optString("src");
                                    if (src.isEmpty() || src.endsWith(".svg")) continue;
                                    int sz = parseIconSize(io.optString("sizes"));
                                    if (sz >= bestSz) {
                                        bestSz = sz;
                                        iconUrl = new URL(new URL(mfUrl), src).toString();
                                    }
                                }
                            }
                        }
                    }
                } catch (Throwable ignored) {}

                Bitmap bmp = fallbackFav;
                if (!iconUrl.isEmpty()) {
                    Bitmap fetched = fetchBitmap(iconUrl);
                    if (fetched != null) bmp = fetched;
                }
                final String fName = name.length() > 24 ? name.substring(0, 24) : name;
                final String fUrl = startUrl;
                final int fCol = themeCol;
                final Bitmap fBmp = bmp;
                MAIN.post(() -> finishInstall(ctx, fName, fUrl, fCol, fBmp, onInstalled));
            });
        });
    }

    private static void finishInstall(Context ctx, String name, String startUrl, int themeColor, Bitmap iconBmp, Runnable onInstalled) {
        String id = "pwa_" + Integer.toHexString(startUrl.hashCode());
        BrowserStore.WebAppItem item = new BrowserStore.WebAppItem(id, name, startUrl, themeColor);
        BrowserStore.addWebApp(ctx, item);

        Bitmap square = renderAppIconBitmap(name, themeColor, iconBmp);
        ShortcutManager sm = ctx.getSystemService(ShortcutManager.class);
        if (sm != null && sm.isRequestPinShortcutSupported()) {
            Intent launch = new Intent(ctx, WebAppActivity.class);
            launch.setAction("com.oneuibook.aurora.OPEN_WEBAPP");
            launch.putExtra("url", startUrl);
            launch.putExtra("name", name);
            launch.putExtra("themeColor", themeColor);

            ShortcutInfo info = new ShortcutInfo.Builder(ctx, id)
                    .setShortLabel(name)
                    .setLongLabel(name)
                    .setIcon(Icon.createWithAdaptiveBitmap(square))
                    .setIntent(launch)
                    .build();
            sm.requestPinShortcut(info, null);
            Toast.makeText(ctx, "'" + name + "' 웹 앱 설치 요청 완료", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(ctx, "'" + name + "' 웹 앱이 시작 페이지에 저장되었습니다.", Toast.LENGTH_SHORT).show();
        }
        if (onInstalled != null) onInstalled.run();
    }

    static Bitmap renderAppIconBitmap(String name, int bgCol, Bitmap fav) {
        int sz = 192;
        Bitmap out = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        p.setColor(bgCol);
        c.drawRect(0, 0, sz, sz, p);
        if (fav != null && fav.getWidth() >= 32) {
            float pad = sz * 0.22f;
            c.drawBitmap(fav, null, new RectF(pad, pad, sz - pad, sz - pad), p);
        } else {
            p.setColor(Color.WHITE);
            p.setTextAlign(Paint.Align.CENTER);
            p.setTextSize(sz * 0.42f);
            p.setTypeface(Ui.bold);
            String ch = (name != null && !name.isEmpty()) ? name.substring(0, 1).toUpperCase() : "A";
            Paint.FontMetrics fm = p.getFontMetrics();
            c.drawText(ch, sz / 2f, sz / 2f - (fm.ascent + fm.descent) / 2f, p);
        }
        return out;
    }

    private static int parseColorSafe(String s, int def) {
        if (s == null || s.trim().isEmpty()) return def;
        try { return Color.parseColor(s.trim()); } catch (Exception e) { return def; }
    }

    private static int parseIconSize(String s) {
        if (s == null) return 0;
        int x = s.toLowerCase().indexOf('x');
        try { return x > 0 ? Integer.parseInt(s.substring(0, x).trim()) : 0; } catch (Exception e) { return 0; }
    }

    private static String fetchString(String url) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(6000); c.setReadTimeout(8000);
            if (c.getResponseCode() != 200) return null;
            try (InputStream is = c.getInputStream()) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] b = new byte[4096]; int n;
                while ((n = is.read(b)) > 0 && bos.size() < 256 * 1024) bos.write(b, 0, n);
                return bos.toString("UTF-8");
            }
        } catch (Throwable t) { return null; }
    }

    private static Bitmap fetchBitmap(String url) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(6000); c.setReadTimeout(8000);
            if (c.getResponseCode() != 200) return null;
            try (InputStream is = c.getInputStream()) {
                return BitmapFactory.decodeStream(is);
            }
        } catch (Throwable t) { return null; }
    }
}
