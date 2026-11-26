package com.oneuibook.aurora;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
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
 * Aurora Browser (v9.5.0) — Chromium-engine mobile web browser replicating iOS 26/27 Safari
 * Liquid Glass UI & gestures, Chrome Web Store CRX3 extensions, PWA installation, and Cloud Sync.
 */
public final class MainActivity extends Activity implements TabManager.Listener, SafariBar.Listener {
    static final String VERSION = "9.5.1";
    private static final int REQ_FILE_CHOOSER = 7001, REQ_SYNC_CREATE = 7002, REQ_SYNC_OPEN = 7003;

    FrameLayout root;
    AuroraGlass.Backdrop backdrop;
    FrameLayout webHost;
    StartPageView startPage;
    SafariBar safariBar;
    TabOverviewView tabOverview;
    GlassSheet sheet;
    TabManager tabs;

    private FrameLayout suggestionOverlay;
    private LinearLayout suggestionList;
    private LinearLayout findBar;
    private EditText findInput;
    private FrameLayout fullscreenHost;
    private WebChromeClient.CustomViewCallback fullscreenCb;
    private ValueCallback<Uri[]> fileChooserCb;
    private int scrollAccum;

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
        backdrop.addView(webHost, match());

        startPage = new StartPageView(this, new StartPageView.Listener() {
            @Override public void onOpenUrl(String url) { tabs.loadUrl(tabs.activeTab(), url); }
            @Override public void onOpenExtensions() { sheet.showExtensionsSheet(MainActivity.this); }
            @Override public void onOpenSync() { sheet.showSyncSheet(MainActivity.this); }
            @Override public void onOpenSettings() { sheet.showSettingsSheet(MainActivity.this); }
            @Override public void onRemoveBookmark(BrowserStore.Bookmark bm) {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle(bm.title)
                        .setMessage("즐겨찾기에서 삭제할까요?")
                        .setPositiveButton("삭제", (d, which) -> {
                            BrowserStore.bookmarks.remove(bm);
                            BrowserStore.save(MainActivity.this);
                            startPage.rebuild();
                        })
                        .setNegativeButton("취소", null).show();
            }
        });
        backdrop.addView(startPage, match());

        // Address Suggestions glass overlay (appears when editing URL)
        buildSuggestionOverlay();
        root.addView(suggestionOverlay, match());

        // Find-in-Page floating glass bar
        buildFindBar();
        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        flp.leftMargin = Ui.dp(14); flp.rightMargin = Ui.dp(14);
        root.addView(findBar, flp);

        // iOS 26 Safari Liquid Glass Floating Bar
        safariBar = new SafariBar(this, backdrop);
        safariBar.setListener(this);
        root.addView(safariBar, match());

        // Spatial Tab Overview
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

        // Floating Liquid Glass Modal Sheets
        sheet = new GlassSheet(this, backdrop);
        root.addView(sheet, match());

        // Fullscreen HTML5 Video Container
        fullscreenHost = new FrameLayout(this);
        fullscreenHost.setBackgroundColor(Color.BLACK);
        fullscreenHost.setVisibility(View.GONE);
        root.addView(fullscreenHost, match());

        root.setOnApplyWindowInsetsListener((v, ins) -> {
            Ui.statusInset = ins.getInsets(WindowInsets.Type.statusBars()).top;
            Ui.navInset = ins.getInsets(WindowInsets.Type.navigationBars()).bottom;
            int ime = ins.getInsets(WindowInsets.Type.ime()).bottom;
            safariBar.setImeOffset(Math.max(0, ime - Ui.navInset));
            ((FrameLayout.LayoutParams) findBar.getLayoutParams()).topMargin = Ui.statusInset + Ui.dp(10);
            refreshStartPage();
            return ins;
        });

        setContentView(root);
        applySystemBars();

        tabs.restoreSession();
        handleIncomingIntent(getIntent());
        Updater.check(this, false);
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
        backdrop.setBackgroundColor(Ui.bg());
        startPage.rebuild();
        safariBar.requestLayout();
        safariBar.invalidate();
        if (tabOverview.getVisibility() == View.VISIBLE) tabOverview.refresh();
    }

    private void applySystemBars() {
        WindowInsetsController ctrl = getWindow().getInsetsController();
        if (ctrl != null) {
            int lightFlag = Ui.isDarkSurface() ? 0 : WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS;
            ctrl.setSystemBarsAppearance(lightFlag, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
        }
    }

    void refreshStartPage() {
        TabManager.Tab t = tabs != null ? tabs.activeTab() : null;
        boolean showStart = (t == null || t.isStartPage());
        startPage.setVisibility(showStart ? View.VISIBLE : View.GONE);
        webHost.setVisibility(showStart ? View.GONE : View.VISIBLE);
        if (showStart) startPage.rebuild();
    }

    void showTabOverview(boolean show) {
        if (show) {
            tabs.captureThumb(tabs.activeTab());
            tabOverview.refresh();
            tabOverview.animate().cancel();
            tabOverview.setAlpha(1f);
            tabOverview.setVisibility(View.VISIBLE);
            safariBar.setVisibility(View.GONE);
        } else {
            tabOverview.setVisibility(View.GONE);
            safariBar.setVisibility(View.VISIBLE);
            applyUiSettings();
            refreshStartPage();
        }
    }

    // ---- TabManager.Listener ----
    @Override public void onTabsChanged() {
        applySystemBars();
        backdrop.setBackgroundColor(Ui.bg());
        TabManager.Tab t = tabs.activeTab();
        safariBar.updateState(t, tabs.canGoBack(), tabs.canGoForward(), tabs.activeList().size());
        refreshStartPage();
    }

    @Override public void onPageUpdated(TabManager.Tab tab) {
        if (tab == tabs.activeTab()) {
            safariBar.updateState(tab, tabs.canGoBack(), tabs.canGoForward(), tabs.activeList().size());
            refreshStartPage();
        }
    }

    @Override public void onProgress(TabManager.Tab tab, int progress) {
        if (tab == tabs.activeTab()) safariBar.setProgress(progress);
    }

    @Override public void onScrollDelta(int dy) {
        scrollAccum += dy;
        if (scrollAccum > Ui.dp(34)) {
            safariBar.setCollapsed(true);
            scrollAccum = 0;
        } else if (scrollAccum < -Ui.dp(24)) {
            safariBar.setCollapsed(false);
            scrollAccum = 0;
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
        String[] opts = tabs.canGoForward() ? new String[]{"앞으로 이동", "방문 기록 보기"} : new String[]{"방문 기록 보기"};
        new AlertDialog.Builder(this).setItems(opts, (d, w) -> {
            if (tabs.canGoForward() && w == 0) tabs.goForward();
            else sheet.showLibrarySheet(this, 2);
        }).show();
    }
    @Override public void onMoreMenu() { sheet.showMainMenu(this); }
    @Override public void onPageMenu() { sheet.showMainMenu(this); }
    @Override public void onOpenTabs() { showTabOverview(true); }
    @Override public void onShare() { shareCurrentPage(); }
    @Override public void onBookmarks() { sheet.showLibrarySheet(this, 0); }
    @Override public void onReloadOrStop() { tabs.reloadOrStop(); }
    @Override public void onSubmitUrl(String input) { tabs.loadInput(input); }
    @Override public void onQueryChanged(String query) { populateSuggestions(query); }
    @Override public void onEditModeChanged(boolean editing) {
        suggestionOverlay.setVisibility(editing ? View.VISIBLE : View.GONE);
        if (editing) populateSuggestions(safariBar.addressField.getText().toString());
    }
    @Override public void onSwipeTabDelta(int delta) {
        int cur = tabs.activeIndex();
        int count = tabs.activeList().size();
        int next = cur + delta;
        if (next >= 0 && next < count) {
            tabs.selectTab(tabs.privateMode, next);
        } else if (delta > 0) {
            // Exact iOS 26 Safari behavior: swipe left past last tab opens a New Tab!
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
        findBar.setVisibility(View.VISIBLE);
        findInput.requestFocus();
    }

    void closeFindBar() {
        findBar.setVisibility(View.GONE);
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

        // Quick Paste & Go from clipboard
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

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
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
        if (safariBar.editing) { safariBar.setEditing(false); return; }
        if (tabOverview.getVisibility() == View.VISIBLE) { showTabOverview(false); return; }
        if (tabs.canGoBack()) { tabs.goBack(); return; }
        super.onBackPressed();
    }

    @Override protected void onDestroy() {
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
