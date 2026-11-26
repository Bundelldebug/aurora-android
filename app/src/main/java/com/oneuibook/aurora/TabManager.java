package com.oneuibook.aurora;

import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Message;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chromium WebView multi-tab manager with:
 * - Separate Normal & Private tab lists (Private tabs never save history/session/cache).
 * - Memory optimization via automatic background tab hibernation (max 3 live WebViews in RAM).
 * - Site-compatible Chromium User-Agent (cleanly omits embedded "; wv" & "Version/4.0" wrapper tokens)
 *   + genuine per-tab Desktop Mode (auto-enabled on Chrome Web Store).
 * - Built-in lightweight tracker/ad blocker + ExtensionManager DNR rules & content_scripts injection.
 */
final class TabManager {
    interface Listener {
        void onTabsChanged();
        void onPageUpdated(Tab tab);
        void onProgress(Tab tab, int progress);
        void onScrollDelta(int dy);
        void onFullscreenShow(View view, WebChromeClient.CustomViewCallback cb);
        void onFullscreenHide();
        void onFileChooser(ValueCallback<Uri[]> cb, WebChromeClient.FileChooserParams params);
        void onInstallStoreExtension(String extId);
    }

    static final class Tab {
        final long id;
        final boolean isPrivate;
        String url = "";
        String title = "시작 페이지";
        int progress;
        boolean loading;
        boolean desktopMode;
        boolean readerMode;
        Bitmap favicon;
        Bitmap thumbnail;
        Bundle savedState;
        AuroraWebView webView;
        long lastActiveMs;

        Tab(long id, boolean isPrivate) {
            this.id = id;
            this.isPrivate = isPrivate;
            this.desktopMode = Ui.desktopDefault;
            this.lastActiveMs = System.currentTimeMillis();
        }

        boolean isStartPage() {
            return url == null || url.isEmpty() || "about:blank".equals(url);
        }
    }

    /** WebView subclass exposing vertical scroll delta for iOS 26 Safari pill collapse/expand. */
    static final class AuroraWebView extends WebView {
        Listener scrollListener;
        private int lastScrollY;

        AuroraWebView(Context c) { super(c); }

        @Override protected void onScrollChanged(int l, int t, int oldl, int oldt) {
            super.onScrollChanged(l, t, oldl, oldt);
            int dy = t - lastScrollY;
            lastScrollY = t;
            if (scrollListener != null && Math.abs(dy) > 2) scrollListener.onScrollDelta(dy);
        }
    }

    private static final int MAX_LIVE_WEBVIEWS = 3;
    private static final Set<String> TRACKER_HOSTS = new HashSet<>(Arrays.asList(
            "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com",
            "pagead2.googlesyndication.com", "analytics.google.com", "google-analytics.com",
            "connect.facebook.net", "tr.snapchat.com", "ads.tiktok.com", "bat.bing.com",
            "ad.daum.net", "t1.daumcdn.net/adfit", "adcr.naver.com", "siape.veta.naver.com",
            "taboola.com", "outbrain.com", "criteo.com", "criteo.net", "scorecardresearch.com",
            "quantserve.com", "amazon-adsystem.com", "moatads.com", "rubiconproject.com", "pubmatic.com"
    ));

    private final Context ctx;
    private final FrameLayout host;
    private final Listener listener;
    final List<Tab> normalTabs = new ArrayList<>();
    final List<Tab> privateTabs = new ArrayList<>();
    boolean privateMode;
    int normalIndex;
    int privateIndex;
    private long nextId = 1;
    private String mobileUa;
    private String desktopUa;
    private String chromiumVersion = "131.0.6778.200";

    TabManager(Context ctx, FrameLayout host, Listener listener) {
        this.ctx = ctx;
        this.host = host;
        this.listener = listener;
        initUserAgents(ctx);
    }

    private void initUserAgents(Context c) {
        String raw = "";
        try { raw = WebSettings.getDefaultUserAgent(c); } catch (Throwable ignored) {}
        if (raw == null || raw.isEmpty()) {
            raw = "Mozilla/5.0 (Linux; Android 15; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.6778.200 Mobile Safari/537.36";
        }
        Matcher m = Pattern.compile("Chrome/(\\d+\\.\\d+\\.\\d+\\.\\d+)").matcher(raw);
        if (m.find()) chromiumVersion = m.group(1);

        // Cleanly omit Android WebView wrapper markers ("; wv" and "Version/4.0 ") using the device's real Chromium UA
        mobileUa = raw.replace("; wv)", ")").replace("Version/4.0 ", "");
        desktopUa = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" + chromiumVersion + " Safari/537.36";
    }

    String getChromiumVersion() { return chromiumVersion; }
    String getMobileUa() { return mobileUa; }
    String getDesktopUa() { return desktopUa; }

    List<Tab> activeList() { return privateMode ? privateTabs : normalTabs; }
    int activeIndex() { return privateMode ? privateIndex : normalIndex; }

    Tab activeTab() {
        List<Tab> list = activeList();
        if (list.isEmpty()) return newTab(privateMode, null, true);
        int idx = Math.max(0, Math.min(list.size() - 1, activeIndex()));
        return list.get(idx);
    }

    void restoreSession() {
        List<String> urls = BrowserStore.loadSavedTabs(ctx);
        int savedIdx = BrowserStore.loadSavedTabIndex(ctx);
        if (urls.isEmpty()) {
            newTab(false, null, true);
            return;
        }
        for (String u : urls) {
            Tab t = new Tab(nextId++, false);
            t.url = u;
            t.title = (u == null || u.isEmpty()) ? "시작 페이지" : BrowserStore.domainOf(u);
            normalTabs.add(t);
        }
        normalIndex = Math.max(0, Math.min(normalTabs.size() - 1, savedIdx));
        selectTab(false, normalIndex);
    }

    void persistSession() {
        List<String> urls = new ArrayList<>();
        for (Tab t : normalTabs) urls.add(t.url);
        BrowserStore.saveNormalTabs(ctx, urls, normalIndex);
    }

    Tab newTab(boolean priv, String url, boolean select) {
        Tab t = new Tab(nextId++, priv);
        List<Tab> list = priv ? privateTabs : normalTabs;
        list.add(t);
        if (select) {
            privateMode = priv;
            if (priv) privateIndex = list.size() - 1; else normalIndex = list.size() - 1;
            Ui.privateTheme = priv;
            attachActiveTab();
            if (url != null && !url.isEmpty()) loadUrl(t, url);
        } else if (url != null && !url.isEmpty()) {
            t.url = url;
            t.title = BrowserStore.domainOf(url);
        }
        persistSession();
        listener.onTabsChanged();
        return t;
    }

    void selectTab(boolean priv, int index) {
        Tab prev = activeList().isEmpty() ? null : activeTab();
        if (prev != null && prev.webView != null) captureThumb(prev);

        privateMode = priv;
        Ui.privateTheme = priv;
        List<Tab> list = activeList();
        if (list.isEmpty()) {
            newTab(priv, null, true);
            return;
        }
        int clamped = Math.max(0, Math.min(list.size() - 1, index));
        if (priv) privateIndex = clamped; else normalIndex = clamped;
        attachActiveTab();
        persistSession();
        listener.onTabsChanged();
    }

    void closeTab(boolean priv, int index) {
        List<Tab> list = priv ? privateTabs : normalTabs;
        if (index < 0 || index >= list.size()) return;
        Tab t = list.remove(index);
        destroyWebView(t);

        if (priv && list.isEmpty()) {
            clearPrivateSessionData();
        }
        if (priv == privateMode) {
            if (list.isEmpty()) {
                newTab(priv, null, true);
                return;
            }
            int cur = priv ? privateIndex : normalIndex;
            if (cur >= list.size()) cur = list.size() - 1;
            if (priv) privateIndex = cur; else normalIndex = cur;
            attachActiveTab();
        }
        persistSession();
        listener.onTabsChanged();
    }

    void closeAllPrivateTabs() {
        for (Tab t : privateTabs) destroyWebView(t);
        privateTabs.clear();
        privateIndex = 0;
        clearPrivateSessionData();
        if (privateMode) {
            newTab(true, null, true);
        } else {
            listener.onTabsChanged();
        }
    }

    private void clearPrivateSessionData() {
        try {
            CookieManager.getInstance().flush();
        } catch (Throwable ignored) {}
    }

    void loadInput(String rawInput) {
        if (rawInput == null) return;
        String q = rawInput.trim();
        if (q.isEmpty()) return;
        String target;
        if (q.startsWith("http://") || q.startsWith("https://") || q.startsWith("about:")) {
            target = q;
        } else if (!q.contains(" ") && q.contains(".") && !q.startsWith(".") && !q.endsWith(".")) {
            target = "https://" + q;
        } else {
            target = Ui.buildSearchUrl(q);
        }
        loadUrl(activeTab(), target);
    }

    void loadUrl(Tab t, String url) {
        if (t == null) return;
        t.url = url;
        t.readerMode = false;
        // Auto-enable desktop mode on Chrome Web Store so extensions can be browsed & installed seamlessly
        if (ExtensionManager.isChromeWebStore(url)) t.desktopMode = true;
        else if (BrowserStore.desktopHosts.contains(BrowserStore.domainOf(url))) t.desktopMode = true;

        ensureWebView(t);
        applyTabSettings(t);
        if (t == activeTab()) attachActiveTab();
        t.webView.loadUrl(url);
        persistSession();
        listener.onPageUpdated(t);
    }

    void toggleDesktopMode(Tab t) {
        if (t == null) return;
        t.desktopMode = !t.desktopMode;
        String hostDomain = BrowserStore.domainOf(t.url);
        if (!hostDomain.isEmpty()) {
            if (t.desktopMode) BrowserStore.desktopHosts.add(hostDomain);
            else BrowserStore.desktopHosts.remove(hostDomain);
            BrowserStore.save(ctx);
        }
        if (t.webView != null) {
            applyTabSettings(t);
            t.webView.reload();
        }
        listener.onPageUpdated(t);
    }

    void toggleReaderMode(Tab t) {
        if (t == null || t.webView == null || t.isStartPage()) return;
        if (t.readerMode) {
            t.readerMode = false;
            t.webView.reload();
            return;
        }
        t.readerMode = true;
        boolean dk = Ui.isDarkSurface();
        String bg = dk ? "#12141A" : "#FAF9F5";
        String fg = dk ? "#E8ECF4" : "#1C1E22";
        String js = "(function(){"
                + "var art=document.querySelector('article')||document.querySelector('main')||document.body;"
                + "var title=document.title||'리더 모드';"
                + "var ps=Array.from(art.querySelectorAll('h1,h2,h3,p,blockquote,img')).slice(0,180);"
                + "var body=ps.map(function(el){if(el.tagName==='IMG'&&el.src)return '<img src=\"'+el.src+'\" style=\"max-width:100%;border-radius:12px;margin:14px 0;\">';"
                + "  var txt=(el.innerText||'').trim(); if(!txt) return '';"
                + "  return '<'+el.tagName.toLowerCase()+'>'+txt.replace(/</g,'&lt;')+'</'+el.tagName.toLowerCase()+'>';}).join('');"
                + "if(!body) body='<p>'+(document.body.innerText||'').slice(0,12000).replace(/\\n+/g,'</p><p>')+'</p>';"
                + "document.documentElement.innerHTML='<head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><style>"
                + "body{background:" + bg + ";color:" + fg + ";font:18px/1.75 -apple-system,sans-serif;max-width:680px;margin:0 auto;padding:28px 20px 120px;}"
                + "h1{font-size:26px;line-height:1.3;margin-bottom:18px;}h2{font-size:21px;margin-top:24px;}p{margin:0 0 16px;}"
                + "</style></head><body><h1>'+title.replace(/</g,'&lt;')+'</h1>'+body+'</body>';"
                + "})();";
        t.webView.evaluateJavascript(js, null);
        listener.onPageUpdated(t);
    }

    void goBack() {
        Tab t = activeTab();
        if (t.webView != null && t.webView.canGoBack()) {
            t.webView.goBack();
        } else if (!t.isStartPage()) {
            t.url = "";
            t.title = "시작 페이지";
            attachActiveTab();
            listener.onPageUpdated(t);
        }
    }

    void goForward() {
        Tab t = activeTab();
        if (t.webView != null && t.webView.canGoForward()) t.webView.goForward();
    }

    void reloadOrStop() {
        Tab t = activeTab();
        if (t.webView == null || t.isStartPage()) return;
        if (t.loading) t.webView.stopLoading(); else t.webView.reload();
    }

    boolean canGoBack() {
        Tab t = activeTab();
        return (t.webView != null && t.webView.canGoBack()) || !t.isStartPage();
    }

    boolean canGoForward() {
        Tab t = activeTab();
        return t.webView != null && t.webView.canGoForward();
    }

    void attachActiveTab() {
        Tab t = activeTab();
        t.lastActiveMs = System.currentTimeMillis();
        host.removeAllViews();
        if (!t.isStartPage()) {
            boolean restored = (t.webView == null);
            ensureWebView(t);
            if (restored) {
                if (t.savedState != null) t.webView.restoreState(t.savedState);
                else t.webView.loadUrl(t.url);
            }
            t.webView.onResume();
            host.addView(t.webView, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        }
        hibernateBackgroundTabs(t);
        listener.onPageUpdated(t);
    }

    private void hibernateBackgroundTabs(Tab keep) {
        List<Tab> live = new ArrayList<>();
        for (Tab t : normalTabs) if (t.webView != null && t != keep) live.add(t);
        for (Tab t : privateTabs) if (t.webView != null && t != keep) live.add(t);
        live.sort((a, b) -> Long.compare(a.lastActiveMs, b.lastActiveMs));
        for (Tab t : live) t.webView.onPause();
        while (live.size() >= MAX_LIVE_WEBVIEWS) {
            Tab oldest = live.remove(0);
            captureThumb(oldest);
            oldest.savedState = new Bundle();
            try { oldest.webView.saveState(oldest.savedState); } catch (Throwable ignored) {}
            destroyWebView(oldest);
        }
    }

    void captureThumb(Tab t) {
        if (t == null || t.webView == null || t.webView.getWidth() <= 0 || t.webView.getHeight() <= 0) return;
        try {
            int tw = 240, th = Math.max(240, Math.round(240f * t.webView.getHeight() / Math.max(1, t.webView.getWidth())));
            th = Math.min(th, 420);
            Bitmap bmp = Bitmap.createBitmap(tw, th, Bitmap.Config.RGB_565);
            Canvas c = new Canvas(bmp);
            float sc = tw / (float) t.webView.getWidth();
            c.scale(sc, sc);
            t.webView.draw(c);
            t.thumbnail = bmp;
        } catch (Throwable ignored) {}
    }

    private void ensureWebView(Tab t) {
        if (t.webView != null) return;
        AuroraWebView wv = new AuroraWebView(ctx);
        wv.scrollListener = listener;
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(!t.isPrivate);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setSupportMultipleWindows(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        if (t.isPrivate) {
            s.setCacheMode(WebSettings.LOAD_NO_CACHE);
            s.setSaveFormData(false);
            s.setGeolocationEnabled(false);
        } else {
            s.setCacheMode(WebSettings.LOAD_DEFAULT);
        }

        try {
            CookieManager cm = CookieManager.getInstance();
            cm.setAcceptCookie(true);
            cm.setAcceptThirdPartyCookies(wv, !t.isPrivate);
        } catch (Throwable ignored) {}

        wv.addJavascriptInterface(new ExtensionManager.Bridge(ctx, listener::onInstallStoreExtension), "AuroraExt");

        wv.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                Uri uri = req.getUrl();
                if (uri == null) return null;
                String urlStr = uri.toString();

                // Serve unpacked extension web_accessible_resources from https://aurora-ext.local/<id>/<path>
                if ("aurora-ext.local".equals(uri.getHost())) {
                    return serveExtensionResource(uri);
                }

                // Lightweight tracker/ad blocker + Extension DNR rules (skip main frame)
                if (!req.isForMainFrame() && (Ui.adBlock || !ExtensionManager.installed.isEmpty())) {
                    String h = uri.getHost();
                    boolean blocked = (Ui.adBlock && isTrackerHost(h)) || ExtensionManager.isBlockedByDnr(urlStr);
                    if (blocked) {
                        BrowserStore.incrementBlocked(ctx);
                        return new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
                    }
                }
                return null;
            }

            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (u == null) return false;
                String scheme = u.getScheme();
                if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme) || "about".equalsIgnoreCase(scheme)) {
                    String sUrl = u.toString();
                    if (ExtensionManager.isChromeWebStore(sUrl) && !t.desktopMode) {
                        t.desktopMode = true;
                        applyTabSettings(t);
                    }
                    return false;
                }
                try {
                    Intent intent = "intent".equalsIgnoreCase(scheme)
                            ? Intent.parseUri(u.toString(), Intent.URI_INTENT_SCHEME)
                            : new Intent(Intent.ACTION_VIEW, u);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(intent);
                } catch (Throwable ignored) {}
                return true;
            }

            @Override public void onPageStarted(WebView view, String url, Bitmap fav) {
                t.url = url;
                t.loading = true;
                t.progress = 12;
                if (fav != null) t.favicon = fav;
                ExtensionManager.injectForPage(ctx, view, url, true);
                listener.onPageUpdated(t);
            }

            @Override public void onPageFinished(WebView view, String url) {
                t.url = url;
                t.loading = false;
                t.progress = 100;
                String title = view.getTitle();
                if (title != null && !title.isEmpty()) t.title = title;
                if (!t.isPrivate) BrowserStore.recordHistory(ctx, t.title, url);
                ExtensionManager.injectForPage(ctx, view, url, false);
                persistSession();
                listener.onPageUpdated(t);
            }
        });

        wv.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int p) {
                t.progress = p;
                listener.onProgress(t, p);
            }

            @Override public void onReceivedTitle(WebView view, String title) {
                if (title != null && !title.isEmpty()) {
                    t.title = title;
                    listener.onPageUpdated(t);
                }
            }

            @Override public void onReceivedIcon(WebView view, Bitmap icon) {
                if (icon != null) {
                    t.favicon = icon;
                    listener.onPageUpdated(t);
                }
            }

            @Override public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
                Tab child = newTab(t.isPrivate, null, true);
                ensureWebView(child);
                WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(child.webView);
                resultMsg.sendToTarget();
                return true;
            }

            @Override public void onShowCustomView(View view, CustomViewCallback callback) {
                listener.onFullscreenShow(view, callback);
            }

            @Override public void onHideCustomView() {
                listener.onFullscreenHide();
            }

            @Override public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                listener.onFileChooser(filePathCallback, fileChooserParams);
                return true;
            }

            @Override public void onPermissionRequest(PermissionRequest request) {
                try { request.grant(request.getResources()); } catch (Throwable ignored) {}
            }
        });

        wv.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            try {
                String fn = URLUtil.guessFileName(url, contentDisposition, mimeType);
                DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
                req.setTitle(fn);
                req.setMimeType(mimeType);
                req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fn);
                DownloadManager dm = (DownloadManager) ctx.getSystemService(Context.DOWNLOAD_SERVICE);
                if (dm != null) {
                    dm.enqueue(req);
                    Toast.makeText(ctx, "다운로드 시작: " + fn, Toast.LENGTH_SHORT).show();
                }
            } catch (Throwable e) {
                Toast.makeText(ctx, "다운로드 실패: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });

        t.webView = wv;
        applyTabSettings(t);
    }

    private void applyTabSettings(Tab t) {
        if (t.webView == null) return;
        WebSettings s = t.webView.getSettings();
        s.setUserAgentString(t.desktopMode ? desktopUa : mobileUa);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
    }

    private WebResourceResponse serveExtensionResource(Uri uri) {
        try {
            List<String> segs = uri.getPathSegments();
            if (segs.size() < 2) return null;
            String extId = segs.get(0);
            StringBuilder rel = new StringBuilder();
            for (int i = 1; i < segs.size(); i++) {
                if (i > 1) rel.append('/');
                rel.append(segs.get(i));
            }
            File base = new File(ExtensionManager.extRoot(ctx), extId);
            File f = new File(base, rel.toString());
            if (!f.exists() || !f.getCanonicalPath().startsWith(base.getCanonicalPath())) return null;
            String n = f.getName().toLowerCase(Locale.ROOT);
            String mime = n.endsWith(".js") ? "application/javascript"
                    : n.endsWith(".css") ? "text/css"
                    : n.endsWith(".html") ? "text/html"
                    : n.endsWith(".png") ? "image/png"
                    : n.endsWith(".svg") ? "image/svg+xml"
                    : n.endsWith(".json") ? "application/json" : "application/octet-stream";
            return new WebResourceResponse(mime, "UTF-8", new FileInputStream(f));
        } catch (Throwable t) {
            return null;
        }
    }

    static boolean isTrackerHost(String host) {
        if (host == null || host.isEmpty()) return false;
        String h = host.toLowerCase(Locale.ROOT);
        if (TRACKER_HOSTS.contains(h)) return true;
        int dot = h.indexOf('.');
        while (dot > 0 && dot < h.length() - 1) {
            h = h.substring(dot + 1);
            if (TRACKER_HOSTS.contains(h)) return true;
            dot = h.indexOf('.');
        }
        return false;
    }

    private void destroyWebView(Tab t) {
        if (t.webView == null) return;
        try {
            if (t.webView.getParent() instanceof FrameLayout) ((FrameLayout) t.webView.getParent()).removeView(t.webView);
            if (t.isPrivate) {
                t.webView.clearCache(true);
                t.webView.clearHistory();
            }
            t.webView.stopLoading();
            t.webView.destroy();
        } catch (Throwable ignored) {}
        t.webView = null;
    }

    void destroyAll() {
        for (Tab t : normalTabs) destroyWebView(t);
        for (Tab t : privateTabs) destroyWebView(t);
    }
}
