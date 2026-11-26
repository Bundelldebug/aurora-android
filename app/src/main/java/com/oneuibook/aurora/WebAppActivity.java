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
        if (url == null || url.isEmpty()) url = "https://www.google.com";
        int themeColor = getIntent().getIntExtra("themeColor", Ui.bg());

        Window w = getWindow();
        w.setStatusBarColor(themeColor);
        w.setNavigationBarColor(themeColor);

        webView = new WebView(this);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
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
                return false;
            }
            @Override public void onPageFinished(WebView view, String u) {
                ExtensionManager.injectForPage(WebAppActivity.this, view, u, false);
            }
        });
        webView.setWebChromeClient(new WebChromeClient());

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(themeColor);
        root.addView(webView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        webView.loadUrl(url);
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
