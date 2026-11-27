package com.oneuibook.aurora;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

/**
 * Standalone chrome-less PWA / Web App container launched from Android Home Screen shortcuts.
 * Shares cookies/sessions with Aurora Browser and injects installed Chrome extensions seamlessly.
 */
public final class WebAppActivity extends Activity {
    private WebView webView;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.init(this);
        ExtensionManager.loadAll(this);

        String url = getIntent().getStringExtra("url");
        if (!isSafeWebAppUrl(url)) url = "https://www.google.com";
        int themeColor = getIntent().getIntExtra("themeColor", Ui.bg());

        Window w = getWindow();
        w.setStatusBarColor(themeColor);
        w.setNavigationBarColor(themeColor);

        webView = new WebView(this);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setSaveFormData(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        String ua = WebSettings.getDefaultUserAgent(this).replace("; wv)", ")").replace("Version/4.0 ", "");
        s.setUserAgentString(ua);
        try {
            CookieManager cm = CookieManager.getInstance();
            cm.setAcceptCookie(true);
            cm.setAcceptThirdPartyCookies(webView, true);
        } catch (Throwable ignored) {}

        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                android.net.Uri u = req != null ? req.getUrl() : null;
                if (u == null) return true;
                String sch = u.getScheme();
                return !("https".equalsIgnoreCase(sch) || "http".equalsIgnoreCase(sch));
            }
            @Override public void onPageFinished(WebView view, String u) {
                ExtensionManager.injectForPage(WebAppActivity.this, view, u, false);
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onPermissionRequest(android.webkit.PermissionRequest request) {
                if (request == null) return;
                android.net.Uri org = request.getOrigin();
                boolean https = org != null && "https".equalsIgnoreCase(org.getScheme());
                java.util.List<String> ok = new java.util.ArrayList<>();
                if (https && request.getResources() != null) {
                    for (String r : request.getResources()) {
                        if (android.webkit.PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID.equals(r)) ok.add(r);
                    }
                }
                if (ok.isEmpty()) request.deny(); else request.grant(ok.toArray(new String[0]));
            }
        });

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(themeColor);
        root.addView(webView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        webView.loadUrl(url);
    }

    /** Only permits http/https URLs to prevent exported Activity file:// or javascript: injection. */
    static boolean isSafeWebAppUrl(String url) {
        if (url == null) return false;
        String t = url.trim();
        if (t.isEmpty()) return false;
        try {
            android.net.Uri u = android.net.Uri.parse(t);
            String sch = u.getScheme();
            String host = u.getHost();
            return ("https".equalsIgnoreCase(sch) || "http".equalsIgnoreCase(sch)) && host != null && !host.isEmpty();
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        if (webView != null) webView.destroy();
        super.onDestroy();
    }
}
