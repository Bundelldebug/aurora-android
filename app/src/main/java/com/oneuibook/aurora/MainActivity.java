package com.oneuibook.aurora;

import android.app.Activity;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

/**
 * Aurora Browser (v9.5.4) — Chromium-engine mobile/foldable/tablet browser:
 * - iOS 26 / iPadOS 26 Safari Liquid Glass UI, button-to-popover morph, live tablet tab strip
 * - Built-in Security Shield + per-site VPN routing (NordVPN default)
 * - Google Translate in-place webpage translation + floating glass translation bar
 * - Chrome Web Store CRX3 extensions, PWA installation, and Cloud Sync
 */
public final class MainActivity extends Activity implements TabManager.Listener, SafariBar.Listener {
    static final String VERSION = "9.5.8";
    private static final int REQ_FILE_CHOOSER = 7001, REQ_SYNC_CREATE = 7002, REQ_SYNC_OPEN = 7003, REQ_START_BG = 7004;

    FrameLayout root;
    AuroraGlass.Backdrop backdrop;
    FrameLayout webHost;
    private View statusBarBg;
    StartPageView startPage;
    SafariBar safariBar;
    TabOverviewView tabOverview;
    GlassSheet sheet;
    TabManager tabs;

    private FrameLayout suggestionOverlay;
    private LinearLayout suggestionList;
    private LinearLayout findBar;
    private EditText findInput;
    private LinearLayout translateBanner;
    private TextView translateBannerText;
    private FrameLayout fullscreenHost;
    private WebChromeClient.CustomViewCallback fullscreenCb;
    private ValueCallback<Uri[]> fileChooserCb;
    private int scrollAccum;
    private boolean lastWasStartPage = true;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.init(this);
        BrowserStore.load(this);
        ExtensionManager.loadAll(this);

        Window w = getWindow();
        w.setDecorFitsSystemWindows(false);
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
        w.setNavigationBarContrastEnforced(false);

        root = new FrameLayout(this);
        backdrop = new AuroraGlass.Backdrop(this);
        backdrop.setBackgroundColor(Ui.bg());
        root.addView(backdrop, match());

        webHost = new FrameLayout(this);
        FrameLayout.LayoutParams wlp = match();
        wlp.topMargin = Ui.statusInset;
        backdrop.addView(webHost, wlp);

        statusBarBg = new View(this);
        statusBarBg.setBackgroundColor(Ui.bg());
        root.addView(statusBarBg, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(0, Ui.statusInset), Gravity.TOP));

        startPage = new StartPageView(this, new StartPageView.Listener() {
            @Override public void onOpenUrl(String url) { tabs.loadUrl(tabs.activeTab(), url); }
            @Override public void onOpenSecurityVpn() { sheet.showSecurityVpnSheet(MainActivity.this); }
            @Override public void onOpenExtensions() { sheet.showExtensionsSheet(MainActivity.this); }
            @Override public void onOpenSync() { sheet.showSyncSheet(MainActivity.this); }
            @Override public void onOpenSettings() { sheet.showSettingsSheet(MainActivity.this); }
            @Override public void onEditStartPage() { sheet.showStartPageEditSheet(MainActivity.this); }
            @Override public void onAddFavorite() {
                sheet.showTextInputSheet("즐겨찾기 추가", "예: youtube.com 또는 https://...", "추가", val -> {
                    String u = val.startsWith("http://") || val.startsWith("https://") ? val : ("https://" + val);
                    String dom = BrowserStore.domainOf(u);
                    BrowserStore.addOrToggleBookmark(MainActivity.this, dom.isEmpty() ? val : dom, u);
                    sheet.dismiss();
                    startPage.rebuild();
                });
            }
            @Override public void onRemoveBookmark(BrowserStore.Bookmark bm) {
                sheet.showConfirmSheet(bm.title, "즐겨찾기에서 삭제할까요?", "삭제", true, () -> {
                    BrowserStore.bookmarks.remove(bm);
                    BrowserStore.save(MainActivity.this);
                    startPage.rebuild();
                });
            }
        });
        backdrop.addView(startPage, match());

        buildSuggestionOverlay();
        root.addView(suggestionOverlay, match());

        buildFindBar();
        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        flp.leftMargin = Ui.dp(14); flp.rightMargin = Ui.dp(14);
        root.addView(findBar, flp);

        buildTranslateBanner();
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        root.addView(translateBanner, tlp);

        safariBar = new SafariBar(this, backdrop);
        safariBar.setListener(this);
        root.addView(safariBar, match());

        tabs = new TabManager(this, webHost, this);
        tabOverview = new TabOverviewView(this, backdrop, tabs, new TabOverviewView.Listener() {
            @Override public void onSelectTab(boolean priv, int index) {
                tabs.selectTab(priv, index);
                showTabOverview(false);
            }
            @Override public void onCloseTab(boolean priv, int index) {
                tabs.closeTab(priv, index);
                tabOverview.refresh();
            }
            @Override public void onNewTab(boolean priv) {
                tabs.newTab(priv, null, true);
                showTabOverview(false);
            }
            @Override public void onSwitchPrivate(boolean priv) {
                tabs.selectTab(priv, priv ? tabs.privateIndex : tabs.normalIndex);
                tabOverview.refresh();
            }
            @Override public void onCloseAllPrivate() {
                tabs.closeAllPrivateTabs();
                tabOverview.refresh();
            }
            @Override public void onDone() {
                showTabOverview(false);
            }
        });
        root.addView(tabOverview, match());

        sheet = new GlassSheet(this, backdrop);
        root.addView(sheet, match());

        fullscreenHost = new FrameLayout(this);
        fullscreenHost.setBackgroundColor(Color.BLACK);
        fullscreenHost.setVisibility(View.GONE);
        root.addView(fullscreenHost, match());

        root.setOnApplyWindowInsetsListener((v, ins) -> {
            Ui.statusInset = ins.getInsets(WindowInsets.Type.statusBars() | WindowInsets.Type.displayCutout()).top;
            Ui.navInset = ins.getInsets(WindowInsets.Type.navigationBars()).bottom;
            int ime = ins.getInsets(WindowInsets.Type.ime()).bottom;
            safariBar.setImeOffset(Math.max(0, ime - Ui.navInset));
            applyTopClearance();
            if (tabOverview != null) tabOverview.applyInsets();
            refreshStartPage();
            return ins;
        });

        setContentView(root);
        applySystemBars();

        tabs.restoreSession();
        handleIncomingIntent(getIntent());
        Updater.check(this, false);
    }

    @Override public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        Ui.density = dm.density > 0 ? dm.density : Ui.density;
        Ui.scaledDensity = dm.scaledDensity > 0 ? dm.scaledDensity : Ui.scaledDensity;
        Ui.screenW = dm.widthPixels > 0 ? dm.widthPixels : Ui.screenW;
        Ui.screenH = dm.heightPixels > 0 ? dm.heightPixels : Ui.screenH;
        Ui.dark = (newConfig.uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        applyUiSettings();
        if (root != null) root.post(this::applyUiSettings);
    }

    private void applyTopClearance() {
        int topInset = safariBar != null ? safariBar.topContentInsetPx() : Ui.statusInset;
        FrameLayout.LayoutParams wlp = (FrameLayout.LayoutParams) webHost.getLayoutParams();
        wlp.topMargin = topInset;
        // Edge-to-edge WebView behind the floating Liquid Glass bottom bar (zero cut-off strip!)
        wlp.bottomMargin = 0;
        webHost.setLayoutParams(wlp);
        if (tabs != null) {
            TabManager.Tab cur = tabs.activeTab();
            if (cur != null && cur.webView != null && !cur.isStartPage()) {
                TabManager.injectFloatingBarClearance(cur.webView, floatingBottomBarDp());
            }
        }
        if (statusBarBg != null) {
            statusBarBg.getLayoutParams().height = topInset;
            statusBarBg.requestLayout();
        }
        ((FrameLayout.LayoutParams) findBar.getLayoutParams()).topMargin = topInset + Ui.dp(8);
        ((FrameLayout.LayoutParams) translateBanner.getLayoutParams()).topMargin = topInset + Ui.dp(10);
        if (suggestionOverlay != null && suggestionOverlay.getChildCount() > 0) {
            suggestionOverlay.getChildAt(0).setPadding(Ui.dp(16), topInset + Ui.dp(12), Ui.dp(16), Ui.dp(120));
        }
    }

    @Override public int floatingBottomBarDp() {
        return safariBar != null ? safariBar.floatingBarClearanceDp() : 0;
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIncomingIntent(intent);
    }

    private void handleIncomingIntent(Intent in) {
        if (in == null) return;
        String act = in.getAction();
        if (Intent.ACTION_VIEW.equals(act) && in.getDataString() != null) {
            tabs.newTab(false, in.getDataString(), true);
        } else if (Intent.ACTION_WEB_SEARCH.equals(act)) {
            String q = in.getStringExtra("query");
            if (q != null && !q.isEmpty()) tabs.newTab(false, Ui.buildSearchUrl(q), true);
        }
    }

    void applyUiSettings() {
        applySystemBars();
        applyTopClearance();
        backdrop.setBackgroundColor(Ui.bg());
        if (statusBarBg != null) statusBarBg.setBackgroundColor(Ui.bg());
        if (findBar != null) {
            findBar.setBackground(Ui.roundStroke(Ui.sheetTint(), Ui.glassStroke(), 1f, 100));
            if (findInput != null) { findInput.setTextColor(Ui.text()); findInput.setHintTextColor(Ui.text2()); }
        }
        startPage.rebuild();
        if (tabs != null) {
            safariBar.updateTabList(tabs.activeList(), tabs.activeIndex());
            safariBar.updateState(tabs.activeTab(), tabs.canGoBack(), tabs.canGoForward(), tabs.activeList().size());
        }
        safariBar.requestLayout();
        safariBar.invalidate();
        if (tabOverview.getVisibility() == View.VISIBLE) tabOverview.refresh();
    }

    private void applySystemBars() {
        WindowInsetsController ctrl = getWindow().getInsetsController();
        if (ctrl != null) {
            int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            ctrl.setSystemBarsAppearance(Ui.isDarkSurface() ? 0 : mask, mask);
        }
    }

    void refreshStartPage() {
        TabManager.Tab t = tabs != null ? tabs.activeTab() : null;
        boolean showStart = (t == null || t.isStartPage());
        backdrop.bypassCapture = !showStart;
        if (statusBarBg != null) {
            statusBarBg.setVisibility(showStart ? View.GONE : View.VISIBLE);
            statusBarBg.setBackgroundColor(Ui.bg());
        }
        boolean switched = (showStart != lastWasStartPage);
        lastWasStartPage = showStart;

        startPage.setVisibility(showStart ? View.VISIBLE : View.GONE);
        webHost.setVisibility(showStart ? View.GONE : View.VISIBLE);
        if (showStart) {
            startPage.rebuild();
            hideTranslateBanner();
        } else if (switched && !"robolectric".equals(android.os.Build.FINGERPRINT)) {
            webHost.setAlpha(0f);
            webHost.animate().alpha(1f).setDuration(200).setInterpolator(Ui.EMPHASIZED).start();
        }
        safariBar.invalidate();
    }

    void showTabOverview(boolean show) {
        if (show) {
            tabs.captureThumb(tabs.activeTab());
            tabOverview.applyInsets();
            tabOverview.refresh();
            tabOverview.animate().cancel();
            tabOverview.setVisibility(View.VISIBLE);
            if ("robolectric".equals(android.os.Build.FINGERPRINT)) {
                tabOverview.setAlpha(1f);
                tabOverview.setScaleX(1f);
                tabOverview.setScaleY(1f);
                safariBar.setVisibility(View.GONE);
            } else {
                tabOverview.setAlpha(0f);
                tabOverview.setScaleX(1.05f);
                tabOverview.setScaleY(1.05f);
                tabOverview.animate().alpha(1f).scaleX(1f).scaleY(1f)
                        .setDuration(290).setInterpolator(Ui.EMPHASIZED).start();
                safariBar.animate().alpha(0f).translationY(Ui.dpf(22))
                        .setDuration(200).setInterpolator(Ui.EMPHASIZED)
                        .withEndAction(() -> { safariBar.setVisibility(View.GONE); safariBar.setAlpha(1f); safariBar.setTranslationY(0f); }).start();
            }
        } else {
            if ("robolectric".equals(android.os.Build.FINGERPRINT)) {
                tabOverview.setVisibility(View.GONE);
                safariBar.setVisibility(View.VISIBLE);
                applyUiSettings();
                refreshStartPage();
            } else {
                tabOverview.animate().alpha(0f).scaleX(1.04f).scaleY(1.04f)
                        .setDuration(220).setInterpolator(Ui.EMPHASIZED)
                        .withEndAction(() -> tabOverview.setVisibility(View.GONE)).start();
                safariBar.setVisibility(View.VISIBLE);
                safariBar.setAlpha(0f);
                safariBar.setTranslationY(Ui.dpf(20));
                safariBar.animate().alpha(1f).translationY(0f)
                        .setDuration(260).setInterpolator(Ui.EMPHASIZED).start();
                applyUiSettings();
                refreshStartPage();
            }
        }
    }

    // ---- Google Translate Controls ----
    private void buildTranslateBanner() {
        translateBanner = new LinearLayout(this);
        translateBanner.setOrientation(LinearLayout.HORIZONTAL);
        translateBanner.setGravity(Gravity.CENTER_VERTICAL);
        translateBanner.setPadding(Ui.dp(14), Ui.dp(8), Ui.dp(10), Ui.dp(8));
        translateBanner.setBackground(Ui.roundStroke(Ui.sheetTint(), Ui.glassStroke(), 1f, 100));
        translateBanner.setElevation(Ui.dpf(10));
        translateBanner.setVisibility(View.GONE);

        translateBannerText = Ui.text(this, "Google 번역됨", 13f, Ui.semibold, Ui.text());
        translateBanner.addView(translateBannerText);

        TextView langBtn = Ui.text(this, "언어", 12.5f, Ui.semibold, Ui.activeAccent());
        langBtn.setPadding(Ui.dp(12), Ui.dp(2), Ui.dp(8), Ui.dp(2));
        langBtn.setOnClickListener(v -> sheet.showTranslateSheet(this));
        translateBanner.addView(langBtn);

        TextView origBtn = Ui.text(this, "원문", 12.5f, Ui.semibold, Ui.text2());
        origBtn.setPadding(Ui.dp(8), Ui.dp(2), Ui.dp(8), Ui.dp(2));
        origBtn.setOnClickListener(v -> restoreCurrentPageOriginal());
        translateBanner.addView(origBtn);

        TextView neverBtn = Ui.text(this, "안 함", 12.5f, Ui.semibold, Ui.text2());
        neverBtn.setPadding(Ui.dp(6), Ui.dp(2), Ui.dp(8), Ui.dp(2));
        neverBtn.setOnClickListener(v -> {
            TabManager.Tab t = tabs.activeTab();
            if (t != null && !t.isStartPage()) {
                String dom = BrowserStore.domainOf(t.url);
                BrowserStore.addNeverTranslateHost(this, t.url);
                restoreCurrentPageOriginal();
                Toast.makeText(this, dom + " 번역 안 함으로 설정됨", Toast.LENGTH_SHORT).show();
            }
        });
        translateBanner.addView(neverBtn);

        TextView closeBtn = Ui.text(this, "×", 15f, Ui.bold, Ui.text2());
        closeBtn.setPadding(Ui.dp(6), Ui.dp(0), Ui.dp(4), Ui.dp(0));
        closeBtn.setOnClickListener(v -> hideTranslateBanner());
        translateBanner.addView(closeBtn);
    }

    void translateCurrentPage(String targetLang) {
        TabManager.Tab t = tabs.activeTab();
        if (t == null || t.webView == null || t.isStartPage()) {
            Toast.makeText(this, "번역할 웹페이지가 없습니다.", Toast.LENGTH_SHORT).show();
            return;
        }
        showTranslateBanner("Google 번역 중 · " + PageTranslator.labelFor(targetLang));
        PageTranslator.translatePage(t.webView, targetLang);
    }

    void restoreCurrentPageOriginal() {
        TabManager.Tab t = tabs.activeTab();
        if (t != null && t.webView != null) {
            PageTranslator.restoreOriginal(t.webView);
            t.translated = false;
            safariBar.updateState(t, tabs.canGoBack(), tabs.canGoForward(), tabs.activeList().size());
        }
        hideTranslateBanner();
    }

    private void showTranslateBanner(String msg) {
        translateBanner.setBackground(Ui.roundStroke(Ui.sheetTint(), Ui.glassStroke(), 1f, 100));
        translateBannerText.setTextColor(Ui.text());
        translateBannerText.setText(msg);
        translateBanner.animate().cancel();
        translateBanner.setVisibility(View.VISIBLE);
        if (!"robolectric".equals(android.os.Build.FINGERPRINT)) {
            translateBanner.setAlpha(0f);
            translateBanner.setTranslationY(-Ui.dpf(12));
            translateBanner.animate().alpha(1f).translationY(0f).setDuration(220).setInterpolator(Ui.EMPHASIZED).start();
        }
    }

    private void hideTranslateBanner() {
        if (translateBanner.getVisibility() != View.VISIBLE) return;
        if ("robolectric".equals(android.os.Build.FINGERPRINT)) {
            translateBanner.setVisibility(View.GONE);
            return;
        }
        translateBanner.animate().alpha(0f).translationY(-Ui.dpf(10)).setDuration(160)
                .withEndAction(() -> translateBanner.setVisibility(View.GONE)).start();
    }

    // ---- TabManager.Listener ----
    @Override public void onTabsChanged() {
        applySystemBars();
        applyTopClearance();
        backdrop.setBackgroundColor(Ui.bg());
        TabManager.Tab t = tabs.activeTab();
        safariBar.updateTabList(tabs.activeList(), tabs.activeIndex());
        safariBar.updateState(t, tabs.canGoBack(), tabs.canGoForward(), tabs.activeList().size());
        refreshStartPage();
    }

    @Override public void onPageUpdated(TabManager.Tab tab) {
        safariBar.updateTabList(tabs.activeList(), tabs.activeIndex());
        if (tab == tabs.activeTab()) {
            safariBar.updateState(tab, tabs.canGoBack(), tabs.canGoForward(), tabs.activeList().size());
            if (!tab.translated) hideTranslateBanner();
            refreshStartPage();
        }
    }

    @Override public void onPageTranslated(TabManager.Tab tab) {
        if (tab == tabs.activeTab()) {
            safariBar.updateState(tab, tabs.canGoBack(), tabs.canGoForward(), tabs.activeList().size());
            showTranslateBanner("Google 번역됨 · " + PageTranslator.labelFor(Ui.translateLang));
        }
    }

    @Override public void onSecurityWarning(TabManager.Tab tab, String url, String reason) {
        sheet.showDangerGateSheet(this, tab, url, reason);
    }

    @Override public void onVpnRequired(TabManager.Tab tab, String url, String domain) {
        sheet.showVpnGateSheet(this, tab, url, domain);
    }

    @Override public void onDangerousDownload(String fileName, String url, String mimeType, Runnable proceed) {
        sheet.showConfirmSheet("실행 파일 다운로드 주의",
                "'" + fileName + "'은(는) 기기 보안에 영향을 줄 수 있는 형식입니다. 신뢰할 수 있는 출처인 경우에만 다운로드하세요.",
                "계속 다운로드", true, proceed);
    }

    @Override public void onWebPermissionRequest(String origin, String permLabel, Runnable allow, Runnable deny) {
        sheet.showConfirmSheet("권한 요청",
                origin + " 사이트에서 " + permLabel + " 접근 권한을 요청했습니다. 허용하시겠습니까?",
                "허용", false, allow, deny);
    }

    @Override public void onProgress(TabManager.Tab tab, int progress) {
        if (tab == tabs.activeTab()) safariBar.setProgress(progress);
    }

    @Override public void onScrollDelta(int dy) {
        scrollAccum += dy;
        boolean before = safariBar.isCollapsed();
        if (scrollAccum > Ui.dp(34)) {
            safariBar.setCollapsed(true);
            scrollAccum = 0;
        } else if (scrollAccum < -Ui.dp(24)) {
            safariBar.setCollapsed(false);
            scrollAccum = 0;
        }
        if (before != safariBar.isCollapsed() && tabs != null) {
            TabManager.Tab cur = tabs.activeTab();
            if (cur != null && cur.webView != null && !cur.isStartPage()) {
                TabManager.injectFloatingBarClearance(cur.webView, floatingBottomBarDp());
            }
        }
    }

    @Override public void onFullscreenShow(View view, WebChromeClient.CustomViewCallback cb) {
        fullscreenCb = cb;
        fullscreenHost.removeAllViews();
        fullscreenHost.addView(view, match());
        fullscreenHost.setVisibility(View.VISIBLE);
        safariBar.setVisibility(View.GONE);
    }

    @Override public void onFullscreenHide() {
        fullscreenHost.removeAllViews();
        fullscreenHost.setVisibility(View.GONE);
        safariBar.setVisibility(View.VISIBLE);
        if (fullscreenCb != null) { fullscreenCb.onCustomViewHidden(); fullscreenCb = null; }
    }

    @Override public void onFileChooser(ValueCallback<Uri[]> cb, WebChromeClient.FileChooserParams params) {
        fileChooserCb = cb;
        try { startActivityForResult(params.createIntent(), REQ_FILE_CHOOSER); }
        catch (Throwable t) { cb.onReceiveValue(null); fileChooserCb = null; }
    }

    @Override public void onInstallStoreExtension(String extId) {
        installStoreExtension(extId);
    }

    void installStoreExtension(String idOrUrl) {
        Toast.makeText(this, "확장 프로그램 다운로드 중...", Toast.LENGTH_SHORT).show();
        ExtensionManager.installFromChromeWebStore(this, idOrUrl, tabs.getChromiumVersion(), new ExtensionManager.InstallCallback() {
            @Override public void onProgress(String msg) {}
            @Override public void onDone(boolean ok, String msg, ExtensionManager.Ext ext) {
                Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show();
                if (ok) {
                    refreshStartPage();
                    TabManager.Tab t = tabs.activeTab();
                    if (t != null && t.webView != null && !t.isStartPage() && !ExtensionManager.isChromeWebStore(t.url)) {
                        t.webView.reload();
                    }
                }
            }
        });
    }

    // ---- SafariBar.Listener ----
    @Override public void onBack() { tabs.goBack(); }
    @Override public void onForward() { tabs.goForward(); }
    @Override public void onBackLongPress() {
        if (tabs.canGoForward()) {
            sheet.showConfirmSheet("탐색", "앞으로 이동하거나 방문 기록을 볼 수 있습니다.", "앞으로 이동", false, tabs::goForward);
        } else {
            sheet.showLibrarySheet(this, 2);
        }
    }
    @Override public void onMoreMenu(RectF anchor) {
        safariBar.setMoreHidden(true);
        sheet.showMainMenu(this, anchor, Icons.MORE, () -> safariBar.setMoreHidden(false));
    }
    @Override public void onPageMenu(RectF anchor) {
        sheet.showMainMenu(this, anchor, Icons.PAGE_MENU, null);
    }
    @Override public void onOpenTabs() { showTabOverview(true); }
    @Override public void onShare() { shareCurrentPage(); }
    @Override public void onBookmarks() { sheet.showLibrarySheet(this, 0); }
    @Override public void onReloadOrStop() { tabs.reloadOrStop(); }
    @Override public void onSubmitUrl(String input) { tabs.loadInput(input); }
    @Override public void onQueryChanged(String query) { populateSuggestions(query); }
    @Override public void onSelectTabIndex(int index) { tabs.selectTab(tabs.privateMode, index); }
    @Override public void onCloseTabIndex(int index) { tabs.closeTab(tabs.privateMode, index); }
    @Override public void onNewTabInline() { tabs.newTab(tabs.privateMode, null, true); }
    @Override public void onEditModeChanged(boolean editing) {
        if (editing) {
            populateSuggestions(safariBar.addressField.getText().toString());
            suggestionOverlay.animate().cancel();
            suggestionOverlay.setVisibility(View.VISIBLE);
            suggestionOverlay.setAlpha(0f);
            suggestionOverlay.animate().alpha(1f).setDuration(200).setInterpolator(Ui.EMPHASIZED).start();
        } else {
            suggestionOverlay.animate().cancel();
            suggestionOverlay.animate().alpha(0f).setDuration(160).setInterpolator(Ui.EMPHASIZED)
                    .withEndAction(() -> suggestionOverlay.setVisibility(View.GONE)).start();
        }
    }
    @Override public void onSwipeTabDelta(int delta) {
        int cur = tabs.activeIndex();
        int count = tabs.activeList().size();
        int next = cur + delta;
        if (next >= 0 && next < count) {
            tabs.selectTab(tabs.privateMode, next);
        } else if (delta > 0) {
            tabs.newTab(tabs.privateMode, null, true);
            Toast.makeText(this, "새 탭", Toast.LENGTH_SHORT).show();
        }
    }

    void shareCurrentPage() {
        TabManager.Tab t = tabs.activeTab();
        if (t == null || t.isStartPage()) {
            Toast.makeText(this, "공유할 페이지가 없습니다.", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent s = new Intent(Intent.ACTION_SEND);
        s.setType("text/plain");
        s.putExtra(Intent.EXTRA_SUBJECT, t.title);
        s.putExtra(Intent.EXTRA_TEXT, t.url);
        startActivity(Intent.createChooser(s, "페이지 공유"));
    }

    // ---- Find in Page ----
    private void buildFindBar() {
        findBar = new LinearLayout(this);
        findBar.setOrientation(LinearLayout.HORIZONTAL);
        findBar.setGravity(Gravity.CENTER_VERTICAL);
        findBar.setPadding(Ui.dp(14), Ui.dp(6), Ui.dp(10), Ui.dp(6));
        findBar.setBackground(Ui.roundStroke(Ui.sheetTint(), Ui.glassStroke(), 1f, 100));
        findBar.setElevation(Ui.dpf(10));
        findBar.setVisibility(View.GONE);

        findInput = new EditText(this);
        findInput.setBackground(null);
        findInput.setSingleLine(true);
        findInput.setHint("페이지에서 찾기");
        findInput.setTextColor(Ui.text());
        findInput.setHintTextColor(Ui.text2());
        findInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable e) {
                TabManager.Tab t = tabs.activeTab();
                if (t != null && t.webView != null) t.webView.findAllAsync(e.toString());
            }
        });
        findBar.addView(findInput, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView prev = Ui.text(this, "▲", 14, Ui.bold, Ui.activeAccent());
        prev.setPadding(Ui.dp(10), Ui.dp(6), Ui.dp(10), Ui.dp(6));
        prev.setOnClickListener(v -> { if (tabs.activeTab().webView != null) tabs.activeTab().webView.findNext(false); });
        TextView next = Ui.text(this, "▼", 14, Ui.bold, Ui.activeAccent());
        next.setPadding(Ui.dp(10), Ui.dp(6), Ui.dp(10), Ui.dp(6));
        next.setOnClickListener(v -> { if (tabs.activeTab().webView != null) tabs.activeTab().webView.findNext(true); });
        TextView done = Ui.text(this, "완료", 13.5f, Ui.bold, Ui.text());
        done.setPadding(Ui.dp(10), Ui.dp(6), Ui.dp(6), Ui.dp(6));
        done.setOnClickListener(v -> closeFindBar());
        findBar.addView(prev); findBar.addView(next); findBar.addView(done);
    }

    void openFindBar() {
        findBar.animate().cancel();
        findBar.setVisibility(View.VISIBLE);
        findBar.setAlpha(0f);
        findBar.setTranslationY(-Ui.dpf(14));
        findBar.animate().alpha(1f).translationY(0f).setDuration(220).setInterpolator(Ui.EMPHASIZED).start();
        findInput.requestFocus();
    }

    void closeFindBar() {
        findBar.animate().cancel();
        findBar.animate().alpha(0f).translationY(-Ui.dpf(12)).setDuration(160).setInterpolator(Ui.EMPHASIZED)
                .withEndAction(() -> findBar.setVisibility(View.GONE)).start();
        findInput.setText("");
        if (tabs.activeTab().webView != null) tabs.activeTab().webView.clearMatches();
    }

    // ---- Address Suggestions Overlay ----
    private void buildSuggestionOverlay() {
        suggestionOverlay = new FrameLayout(this);
        suggestionOverlay.setBackgroundColor(Ui.alpha(Ui.bg(), 0.94f));
        suggestionOverlay.setVisibility(View.GONE);
        suggestionOverlay.setOnClickListener(v -> safariBar.setEditing(false));
        ScrollView sv = new ScrollView(this);
        sv.setPadding(Ui.dp(16), Ui.statusInset + Ui.dp(68), Ui.dp(16), Ui.dp(120));
        suggestionList = new LinearLayout(this);
        suggestionList.setOrientation(LinearLayout.VERTICAL);
        sv.addView(suggestionList, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        suggestionOverlay.addView(sv, match());
    }

    private void populateSuggestions(String query) {
        suggestionOverlay.setBackgroundColor(Ui.alpha(Ui.bg(), 0.95f));
        suggestionList.removeAllViews();
        String q = query == null ? "" : query.trim();

        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0) {
                CharSequence cs = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
                if (cs != null && cs.length() > 0 && q.isEmpty()) {
                    String clip = cs.toString().trim();
                    addSuggestionRow("붙여넣기 및 이동", clip, () -> { safariBar.setEditing(false); tabs.loadInput(clip); });
                }
            }
        } catch (Throwable ignored) {}

        if (!q.isEmpty()) {
            addSuggestionRow(Ui.searchEngineName(Ui.searchEngine) + " 검색", q, () -> {
                safariBar.setEditing(false);
                tabs.loadUrl(tabs.activeTab(), Ui.buildSearchUrl(q));
            });
        }

        String ql = q.toLowerCase(Locale.ROOT);
        int shown = 0;
        for (BrowserStore.Bookmark b : BrowserStore.bookmarks) {
            if (ql.isEmpty() || b.title.toLowerCase(Locale.ROOT).contains(ql) || b.url.toLowerCase(Locale.ROOT).contains(ql)) {
                addSuggestionRow(b.title, b.url, () -> { safariBar.setEditing(false); tabs.loadUrl(tabs.activeTab(), b.url); });
                if (++shown >= 5) break;
            }
        }
        for (BrowserStore.HistoryItem h : BrowserStore.history) {
            if (ql.isEmpty() || h.title.toLowerCase(Locale.ROOT).contains(ql) || h.url.toLowerCase(Locale.ROOT).contains(ql)) {
                addSuggestionRow(h.title, h.url, () -> { safariBar.setEditing(false); tabs.loadUrl(tabs.activeTab(), h.url); });
                if (++shown >= 10) break;
            }
        }
    }

    private void addSuggestionRow(String title, String sub, Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(Ui.dp(14), Ui.dp(11), Ui.dp(14), Ui.dp(11));
        row.setBackground(Ui.round(Ui.alpha(Ui.card(), 0.90f), 14));
        row.addView(Ui.text(this, title, 14.5f, Ui.semibold, Ui.text()));
        row.addView(Ui.text(this, sub, 12f, Ui.regular, Ui.text2()));
        row.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(6);
        suggestionList.addView(row, lp);
    }

    // ---- Cloud Account Sync (SAF) ----
    void createSyncDocument() {
        Intent in = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        in.addCategory(Intent.CATEGORY_OPENABLE);
        in.setType("application/json");
        in.putExtra(Intent.EXTRA_TITLE, "aurora-sync.json");
        startActivityForResult(in, REQ_SYNC_CREATE);
    }

    void openSyncDocument() {
        Intent in = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        in.addCategory(Intent.CATEGORY_OPENABLE);
        in.setType("application/json");
        startActivityForResult(in, REQ_SYNC_OPEN);
    }

    void runSyncNow() {
        if (BrowserStore.syncUri.isEmpty()) { createSyncDocument(); return; }
        Uri u = Uri.parse(BrowserStore.syncUri);
        ExtensionManager.IO.execute(() -> {
            try {
                String res = SyncEngine.syncWithUri(this, u, tabs);
                runOnUiThread(() -> {
                    Toast.makeText(this, res, Toast.LENGTH_LONG).show();
                    refreshStartPage();
                });
            } catch (Throwable t) {
                runOnUiThread(() -> Toast.makeText(this, "동기화 실패: " + t.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    void pickStartPageWallpaper() {
        Intent in = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        in.addCategory(Intent.CATEGORY_OPENABLE);
        in.setType("image/*");
        try { startActivityForResult(in, REQ_START_BG); }
        catch (Throwable t) { Toast.makeText(this, "사진 선택기를 열 수 없습니다.", Toast.LENGTH_SHORT).show(); }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_START_BG) {
            if (res == RESULT_OK && data != null && data.getData() != null) {
                final Uri uri = data.getData();
                ExtensionManager.IO.execute(() -> {
                    boolean ok = StartPageView.saveCustomBg(this, uri);
                    runOnUiThread(() -> {
                        if (ok) {
                            startPage.reloadBackground(this);
                            startPage.rebuild();
                            Toast.makeText(this, "시작 페이지 배경이 변경되었습니다.", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(this, "이미지를 불러오지 못했습니다.", Toast.LENGTH_SHORT).show();
                        }
                    });
                });
            }
            return;
        }
        if (req == REQ_FILE_CHOOSER) {
            if (fileChooserCb != null) {
                fileChooserCb.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data));
                fileChooserCb = null;
            }
            return;
        }
        if ((req == REQ_SYNC_CREATE || req == REQ_SYNC_OPEN) && res == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (Throwable ignored) {}
            BrowserStore.syncUri = uri.toString();
            BrowserStore.save(this);
            runSyncNow();
        }
    }

    @Override public void onBackPressed() {
        if (fullscreenHost.getVisibility() == View.VISIBLE) { onFullscreenHide(); return; }
        if (sheet.isOpen()) { sheet.dismiss(); return; }
        if (findBar.getVisibility() == View.VISIBLE) { closeFindBar(); return; }
        if (safariBar.editing || suggestionOverlay.getVisibility() == View.VISIBLE) {
            safariBar.setEditing(false);
            suggestionOverlay.setVisibility(View.GONE);
            return;
        }
        if (tabOverview.getVisibility() == View.VISIBLE) { showTabOverview(false); return; }
        if (tabs.canGoBack()) { tabs.goBack(); return; }
        TabManager.Tab cur = tabs.activeTab();
        if (cur != null && !cur.isStartPage()) {
            cur.url = "";
            cur.title = "시작 페이지";
            if (cur.webView != null) cur.webView.setVisibility(View.GONE);
            refreshStartPage();
            safariBar.updateState(cur, false, tabs.canGoForward(), tabs.activeList().size());
            return;
        }
        if (tabs.privateMode) {
            tabs.selectTab(false, 0);
            return;
        }
        super.onBackPressed();
    }

    @Override protected void onDestroy() {
        SecurityGuard.stopWatchingVpn(this);
        if (tabs != null) {
            tabs.persistSession();
            tabs.destroyAll();
        }
        super.onDestroy();
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }
}
