package com.oneuibook.aurora;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

/**
 * Authentic iOS 26 Safari Liquid Glass Popover & Sheets:
 * - Right-aligned monochrome SF icons (exact iOS 26 UIMenu convention)
 * - Custom iOS 26 capsule glass toggles (Icons.GlassToggle) instead of stock Android switches
 * - Crisp 0.5dp hairlines and concise native Apple-grade copy
 */
final class GlassSheet extends FrameLayout {
    private final AuroraGlass.Backdrop backdrop;
    private final AuroraGlass.Surface glass = new AuroraGlass.Surface();
    private final FrameLayout cardContainer;
    private final ScrollView scroll;
    private final LinearLayout content;
    private final RectF cardRect = new RectF();
    private boolean open;

    GlassSheet(Context c, AuroraGlass.Backdrop backdrop) {
        super(c);
        this.backdrop = backdrop;
        setWillNotDraw(false);
        setVisibility(GONE);
        setBackgroundColor(0x3D000000);
        setOnClickListener(v -> dismiss());

        cardContainer = new FrameLayout(c) {
            @Override protected void dispatchDraw(Canvas canvas) {
                cardRect.set(0, 0, getWidth(), getHeight());
                glass.tint = Ui.sheetTint();
                glass.strokeColor = Ui.glassStroke();
                glass.blurDp = 16f;
                glass.refractDp = 14f;
                glass.edgeDp = 24f;
                glass.chroma = 0.24f;
                glass.fresnelAmt = 0.58f;
                glass.shapes(cardRect, Ui.dpf(28), null, 0, null, 0);
                glass.draw(canvas, this, backdrop);
                super.dispatchDraw(canvas);
            }
        };
        cardContainer.setWillNotDraw(false);
        cardContainer.setClipToOutline(true);
        cardContainer.setOnClickListener(v -> {});

        scroll = new ScrollView(c);
        content = new LinearLayout(c);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(Ui.dp(16), Ui.dp(14), Ui.dp(16), Ui.dp(18));
        scroll.addView(content, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        cardContainer.addView(scroll, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LayoutParams lp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        lp.leftMargin = Ui.dp(14);
        lp.rightMargin = Ui.dp(14);
        lp.bottomMargin = Ui.navInset + Ui.dp(14);
        addView(cardContainer, lp);
    }

    boolean isOpen() { return open; }

    void dismiss() {
        if (!open) return;
        open = false;
        if ("robolectric".equals(android.os.Build.FINGERPRINT)) {
            setVisibility(GONE);
            content.removeAllViews();
            return;
        }
        animate().alpha(0f).setDuration(200).setInterpolator(Ui.EMPHASIZED).start();
        cardContainer.animate().translationY(Ui.dpf(44)).scaleX(0.95f).scaleY(0.95f).alpha(0f)
                .setDuration(210).setInterpolator(Ui.EMPHASIZED)
                .withEndAction(() -> {
                    setVisibility(GONE);
                    setAlpha(1f);
                    content.removeAllViews();
                }).start();
    }

    private void showBuilt() {
        open = true;
        setVisibility(VISIBLE);
        scroll.scrollTo(0, 0);
        if (cardContainer.getLayoutParams() instanceof LayoutParams) {
            ((LayoutParams) cardContainer.getLayoutParams()).bottomMargin = Math.max(Ui.navInset, Ui.dp(12)) + Ui.dp(14);
        }
        requestLayout();
        invalidate();
        if ("robolectric".equals(android.os.Build.FINGERPRINT)) {
            setAlpha(1f);
            cardContainer.setAlpha(1f);
            cardContainer.setTranslationY(0f);
            cardContainer.setScaleX(1f);
            cardContainer.setScaleY(1f);
            return;
        }
        animate().cancel();
        cardContainer.animate().cancel();
        setAlpha(0f);
        animate().alpha(1f).setDuration(240).setInterpolator(Ui.EMPHASIZED).start();
        cardContainer.setAlpha(0f);
        cardContainer.setTranslationY(Ui.dpf(52));
        cardContainer.setScaleX(0.94f);
        cardContainer.setScaleY(0.94f);
        cardContainer.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                .setDuration(320).setInterpolator(Ui.EMPHASIZED).start();
    }

    // ---- 1. iOS 26 Safari '...' Popover Menu ----
    void showMainMenu(MainActivity act) {
        content.removeAllViews();
        Context c = getContext();
        TabManager.Tab tab = act.tabs.activeTab();
        boolean onPage = tab != null && !tab.isStartPage();

        // Top 4 circular-pill quick actions (iOS 26 Safari popover top row)
        LinearLayout quick = new LinearLayout(c);
        quick.setOrientation(LinearLayout.HORIZONTAL);
        quick.addView(quickTile(c, Icons.TABS, "탭 (" + act.tabs.activeList().size() + ")", v -> { dismiss(); act.showTabOverview(true); }));
        quick.addView(quickTile(c, Icons.BOOKMARK, "북마크", v -> showLibrarySheet(act, 0)));
        quick.addView(quickTile(c, Icons.SHARE, "공유", v -> { dismiss(); act.shareCurrentPage(); }));
        quick.addView(quickTile(c, Icons.PRIVATE, act.tabs.privateMode ? "일반" : "비밀", v -> {
            dismiss();
            act.tabs.selectTab(!act.tabs.privateMode, 0);
        }));
        content.addView(quick, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(spacer(c, 10));

        LinearLayout grp = groupBox(c);
        if (onPage) {
            boolean bm = BrowserStore.isBookmarked(tab.url);
            grp.addView(iosMenuRow(c, bm ? "즐겨찾기에서 제거" : "즐겨찾기에 추가", Icons.STAR, null, v -> {
                BrowserStore.addOrToggleBookmark(c, tab.title, tab.url);
                Toast.makeText(c, bm ? "즐겨찾기에서 제거됨" : "즐겨찾기에 추가됨", Toast.LENGTH_SHORT).show();
                dismiss();
            }));
            grp.addView(div(c));
            grp.addView(iosMenuRow(c, "읽기 목록에 추가", Icons.BOOKMARK, null, v -> {
                BrowserStore.addReadingList(c, tab.title, tab.url);
                Toast.makeText(c, "읽기 목록에 추가됨", Toast.LENGTH_SHORT).show();
                dismiss();
            }));
            grp.addView(div(c));
            grp.addView(iosMenuRow(c, "홈 화면에 추가", Icons.PWA, "웹 앱", v -> {
                dismiss();
                WebAppInstaller.installFromTab(act, tab, act::refreshStartPage);
            }));
            grp.addView(div(c));
            grp.addView(iosMenuRow(c, tab.desktopMode ? "모바일 웹사이트 요청" : "데스크탑 웹사이트 요청", Icons.DESKTOP, null, v -> {
                dismiss();
                act.tabs.toggleDesktopMode(tab);
            }));
            grp.addView(div(c));
            grp.addView(iosMenuRow(c, tab.readerMode ? "리더 보기 끄기" : "리더 보기", Icons.READER, null, v -> {
                dismiss();
                act.tabs.toggleReaderMode(tab);
            }));
            grp.addView(div(c));
            grp.addView(iosMenuRow(c, "페이지에서 찾기", Icons.FIND, null, v -> { dismiss(); act.openFindBar(); }));
            grp.addView(div(c));
        }

        grp.addView(iosMenuRow(c, "확장 프로그램", Icons.PUZZLE,
                ExtensionManager.installed.isEmpty() ? null : String.valueOf(ExtensionManager.installed.size()),
                v -> showExtensionsSheet(act)));
        grp.addView(div(c));
        grp.addView(iosMenuRow(c, "동기화", Icons.CLOUD, BrowserStore.syncUri.isEmpty() ? null : "연결됨", v -> showSyncSheet(act)));
        grp.addView(div(c));
        grp.addView(iosMenuRow(c, "설정", Icons.SETTINGS, null, v -> showSettingsSheet(act)));
        content.addView(grp);
        showBuilt();
    }

    // ---- 2. Chrome Extensions Sheet ----
    void showExtensionsSheet(MainActivity act) {
        content.removeAllViews();
        Context c = getContext();
        ExtensionManager.loadAll(c);
        content.addView(sheetHeader(c, "확장 프로그램", null));

        LinearLayout grp = groupBox(c);
        if (ExtensionManager.installed.isEmpty()) {
            grp.addView(emptyRow(c, "설치된 확장 프로그램 없음"));
        } else {
            for (int i = 0; i < ExtensionManager.installed.size(); i++) {
                ExtensionManager.Ext ext = ExtensionManager.installed.get(i);
                LinearLayout row = new LinearLayout(c);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(Ui.dp(14), Ui.dp(10), Ui.dp(14), Ui.dp(10));

                Bitmap bmp = ExtensionManager.iconOf(ext);
                if (bmp != null) {
                    ImageView iv = new ImageView(c);
                    iv.setImageBitmap(bmp);
                    row.addView(iv, new LinearLayout.LayoutParams(Ui.dp(32), Ui.dp(32)));
                } else {
                    TextView badge = Ui.text(c, ext.name.isEmpty() ? "E" : ext.name.substring(0, 1).toUpperCase(), 14, Ui.bold, 0xFFFFFFFF);
                    badge.setGravity(Gravity.CENTER);
                    badge.setBackground(Ui.round(Ui.activeAccent(), 9));
                    row.addView(badge, new LinearLayout.LayoutParams(Ui.dp(32), Ui.dp(32)));
                }

                LinearLayout info = new LinearLayout(c);
                info.setOrientation(LinearLayout.VERTICAL);
                info.setPadding(Ui.dp(10), 0, Ui.dp(8), 0);
                TextView nm = Ui.text(c, ext.name, 14.5f, Ui.medium, Ui.text());
                nm.setMaxLines(1); nm.setEllipsize(TextUtils.TruncateAt.END);
                info.addView(nm);
                info.addView(Ui.text(c, "v" + ext.version, 11.5f, Ui.regular, Ui.text2()));
                row.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                if (ext.popupPath != null && !ext.popupPath.isEmpty()) {
                    TextView popBtn = Ui.text(c, "열기", 13f, Ui.semibold, Ui.activeAccent());
                    popBtn.setPadding(Ui.dp(8), Ui.dp(5), Ui.dp(10), Ui.dp(5));
                    popBtn.setOnClickListener(v -> showExtensionPopup(ext));
                    row.addView(popBtn);
                }

                row.addView(new Icons.GlassToggle(c, ext.enabled, on -> ExtensionManager.setEnabled(c, ext.id, on)));
                row.setOnLongClickListener(v -> {
                    new AlertDialog.Builder(c)
                            .setTitle(ext.name)
                            .setMessage("이 확장 프로그램을 제거할까요?")
                            .setPositiveButton("제거", (d, w) -> {
                                ExtensionManager.remove(c, ext.id);
                                showExtensionsSheet(act);
                            })
                            .setNegativeButton("취소", null).show();
                    return true;
                });
                grp.addView(row);
                if (i + 1 < ExtensionManager.installed.size()) grp.addView(div(c));
            }
        }
        content.addView(grp);
        content.addView(spacer(c, 10));

        LinearLayout actions = groupBox(c);
        actions.addView(iosMenuRow(c, "Chrome 웹 스토어에서 추가", Icons.PUZZLE, null, v -> {
            dismiss();
            act.tabs.loadUrl(act.tabs.activeTab(), "https://chromewebstore.google.com");
        }));
        actions.addView(div(c));
        actions.addView(iosMenuRow(c, "확장 ID 또는 URL로 설치", Icons.PLUS, null, v -> promptInstallExtension(act)));
        content.addView(actions);
        showBuilt();
    }

    private void promptInstallExtension(MainActivity act) {
        Context c = getContext();
        EditText et = new EditText(c);
        et.setHint("Chrome 웹 스토어 URL 또는 32자 ID");
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        new AlertDialog.Builder(c)
                .setTitle("확장 프로그램 설치")
                .setView(et)
                .setPositiveButton("설치", (d, w) -> {
                    String val = et.getText().toString().trim();
                    if (!val.isEmpty()) {
                        dismiss();
                        act.installStoreExtension(val);
                    }
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private void showExtensionPopup(ExtensionManager.Ext ext) {
        content.removeAllViews();
        Context c = getContext();
        content.addView(sheetHeader(c, ext.name, "v" + ext.version));
        WebView pop = new WebView(c);
        WebSettings s = pop.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        pop.addJavascriptInterface(new ExtensionManager.Bridge(c, null), "AuroraExt");
        File f = new File(ext.dir, ext.popupPath);
        String html = ExtensionManager.readTextSafe(f);
        String injected = "<script>" + ExtensionManager.buildWebExtPolyfill(ext) + "</script>" + html;
        pop.loadDataWithBaseURL("file://" + ext.dir.getAbsolutePath() + "/", injected, "text/html", "UTF-8", null);
        content.addView(pop, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(340)));
        showBuilt();
    }

    // ---- 3. Bookmarks / Reading List / History Sheet ----
    void showLibrarySheet(MainActivity act, int section) {
        content.removeAllViews();
        Context c = getContext();
        String[] tabs = {"즐겨찾기", "읽기 목록", "방문 기록"};
        content.addView(sheetHeader(c, tabs[section], null));

        LinearLayout seg = new LinearLayout(c);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        seg.setPadding(Ui.dp(3), Ui.dp(3), Ui.dp(3), Ui.dp(3));
        seg.setBackground(Ui.round(Ui.alpha(Ui.text(), 0.08f), 100));
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            TextView b = Ui.text(c, tabs[i], 13f, i == section ? Ui.semibold : Ui.regular, i == section ? Ui.text() : Ui.text2());
            b.setGravity(Gravity.CENTER);
            b.setPadding(0, Ui.dp(7), 0, Ui.dp(7));
            if (i == section) b.setBackground(Ui.round(Ui.card(), 100));
            b.setOnClickListener(v -> showLibrarySheet(act, idx));
            seg.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        content.addView(seg);
        content.addView(spacer(c, 10));

        LinearLayout grp = groupBox(c);
        if (section == 0) {
            for (int i = 0; i < BrowserStore.bookmarks.size(); i++) {
                BrowserStore.Bookmark b = BrowserStore.bookmarks.get(i);
                grp.addView(urlRow(c, b.title, b.url, v -> { dismiss(); act.tabs.loadUrl(act.tabs.activeTab(), b.url); }));
                if (i + 1 < BrowserStore.bookmarks.size()) grp.addView(div(c));
            }
        } else if (section == 1) {
            if (BrowserStore.readingList.isEmpty()) grp.addView(emptyRow(c, "읽기 목록이 비어 있습니다."));
            for (int i = 0; i < BrowserStore.readingList.size(); i++) {
                BrowserStore.Bookmark b = BrowserStore.readingList.get(i);
                grp.addView(urlRow(c, b.title, b.url, v -> { dismiss(); act.tabs.loadUrl(act.tabs.activeTab(), b.url); }));
                if (i + 1 < BrowserStore.readingList.size()) grp.addView(div(c));
            }
        } else {
            if (BrowserStore.history.isEmpty()) grp.addView(emptyRow(c, "방문 기록 없음"));
            int max = Math.min(40, BrowserStore.history.size());
            for (int i = 0; i < max; i++) {
                BrowserStore.HistoryItem h = BrowserStore.history.get(i);
                grp.addView(urlRow(c, h.title, h.url, v -> { dismiss(); act.tabs.loadUrl(act.tabs.activeTab(), h.url); }));
                if (i + 1 < max) grp.addView(div(c));
            }
            if (!BrowserStore.history.isEmpty()) {
                content.addView(grp);
                content.addView(spacer(c, 8));
                LinearLayout clrGrp = groupBox(c);
                TextView clr = Ui.text(c, "방문 기록 지우기", 14.5f, Ui.medium, 0xFFFF3B30);
                clr.setGravity(Gravity.CENTER);
                clr.setPadding(0, Ui.dp(12), 0, Ui.dp(12));
                clr.setOnClickListener(v -> {
                    BrowserStore.history.clear();
                    BrowserStore.save(c);
                    showLibrarySheet(act, 2);
                });
                clrGrp.addView(clr);
                content.addView(clrGrp);
                showBuilt();
                return;
            }
        }
        content.addView(grp);
        showBuilt();
    }

    // ---- 4. Cloud Account Sync Sheet ----
    void showSyncSheet(MainActivity act) {
        content.removeAllViews();
        Context c = getContext();
        content.addView(sheetHeader(c, "동기화", BrowserStore.lastSyncInfo.isEmpty() ? "Google Drive · 클라우드" : BrowserStore.lastSyncInfo));

        LinearLayout grp = groupBox(c);
        if (!BrowserStore.syncUri.isEmpty()) {
            grp.addView(iosMenuRow(c, "지금 동기화", Icons.CLOUD, null, v -> {
                dismiss();
                act.runSyncNow();
            }));
            grp.addView(div(c));
        }
        grp.addView(iosMenuRow(c, "새 동기화 파일 만들기", Icons.PLUS, "Drive", v -> {
            dismiss();
            act.createSyncDocument();
        }));
        grp.addView(div(c));
        grp.addView(iosMenuRow(c, "기존 동기화 파일 연결", Icons.DOWNLOAD, null, v -> {
            dismiss();
            act.openSyncDocument();
        }));
        content.addView(grp);
        showBuilt();
    }

    // ---- 5. Settings Sheet ----
    void showSettingsSheet(MainActivity act) {
        content.removeAllViews();
        Context c = getContext();
        content.addView(sheetHeader(c, "설정", "v" + MainActivity.VERSION));

        LinearLayout grp = groupBox(c);
        grp.addView(iosMenuRow(c, "탭 막대", Icons.TABS, Ui.layoutName(Ui.layoutMode), v -> {
            Ui.layoutMode = (Ui.layoutMode + 1) % 3;
            Ui.savePrefs(c);
            act.applyUiSettings();
            showSettingsSheet(act);
        }));
        grp.addView(div(c));
        grp.addView(iosMenuRow(c, "검색 엔진", Icons.SEARCH, Ui.searchEngineName(Ui.searchEngine), v -> {
            Ui.searchEngine = (Ui.searchEngine + 1) % 4;
            Ui.savePrefs(c);
            showSettingsSheet(act);
        }));
        grp.addView(div(c));
        grp.addView(toggleRow(c, "리퀴드 글래스", Ui.liquidGlass, on -> {
            Ui.liquidGlass = on;
            Ui.savePrefs(c);
            act.applyUiSettings();
        }));
        grp.addView(div(c));
        grp.addView(toggleRow(c, "추적기 및 광고 차단", Ui.adBlock, on -> {
            Ui.adBlock = on;
            Ui.savePrefs(c);
        }));
        grp.addView(div(c));
        grp.addView(toggleRow(c, "항상 데스크탑 웹사이트 요청", Ui.desktopDefault, on -> {
            Ui.desktopDefault = on;
            Ui.savePrefs(c);
        }));
        grp.addView(div(c));
        grp.addView(iosMenuRow(c, "업데이트 확인", Icons.DOWNLOAD, MainActivity.VERSION, v -> {
            dismiss();
            Updater.check(act, true);
        }));
        content.addView(grp);
        showBuilt();
    }

    // ---- iOS 26 Menu Components ----
    private View sheetHeader(Context c, String title, String sub) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(4), Ui.dp(2), Ui.dp(4), Ui.dp(12));
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(Ui.text(c, title, 18f, Ui.bold, Ui.text()));
        if (sub != null && !sub.isEmpty()) col.addView(Ui.text(c, sub, 12f, Ui.regular, Ui.text2()));
        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView close = Ui.text(c, "완료", 15f, Ui.semibold, Ui.activeAccent());
        close.setPadding(Ui.dp(10), Ui.dp(4), Ui.dp(4), Ui.dp(4));
        close.setOnClickListener(v -> dismiss());
        row.addView(close);
        return row;
    }

    private View quickTile(Context c, int iconId, String label, OnClickListener click) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(Ui.dp(6), Ui.dp(11), Ui.dp(6), Ui.dp(11));
        box.setBackground(Ui.round(Ui.alpha(Ui.text(), Ui.isDarkSurface() ? 0.10f : 0.06f), 18));
        View ic = new View(c) {
            @Override protected void onDraw(Canvas canvas) {
                Icons.draw(canvas, iconId, getWidth() / 2f, getHeight() / 2f, Ui.dpf(20), Ui.text());
            }
        };
        box.addView(ic, new LinearLayout.LayoutParams(Ui.dp(22), Ui.dp(22)));
        TextView tv = Ui.text(c, label, 11.5f, Ui.medium, Ui.text());
        tv.setMaxLines(1);
        tv.setPadding(0, Ui.dp(5), 0, 0);
        box.addView(tv);
        box.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(Ui.dp(3), 0, Ui.dp(3), 0);
        box.setLayoutParams(lp);
        return box;
    }

    private LinearLayout groupBox(Context c) {
        LinearLayout ll = new LinearLayout(c);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setBackground(Ui.round(Ui.alpha(Ui.text(), Ui.isDarkSurface() ? 0.08f : 0.05f), 18));
        return ll;
    }

    /** Authentic iOS 26 UIMenu row: title on left, subtle value + monochrome SF icon on RIGHT. */
    private View iosMenuRow(Context c, String label, int iconId, String detail, OnClickListener click) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(16), Ui.dp(13), Ui.dp(16), Ui.dp(13));
        row.addView(Ui.text(c, label, 15f, Ui.regular, Ui.text()),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (detail != null && !detail.isEmpty()) {
            TextView dt = Ui.text(c, detail, 13.5f, Ui.regular, Ui.text2());
            dt.setPadding(0, 0, Ui.dp(10), 0);
            row.addView(dt);
        }
        View ic = new View(c) {
            @Override protected void onDraw(Canvas canvas) {
                Icons.draw(canvas, iconId, getWidth() / 2f, getHeight() / 2f, Ui.dpf(18), Ui.text());
            }
        };
        row.addView(ic, new LinearLayout.LayoutParams(Ui.dp(20), Ui.dp(20)));
        row.setOnClickListener(click);
        return row;
    }

    private View toggleRow(Context c, String label, boolean checked, java.util.function.Consumer<Boolean> onToggle) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(16), Ui.dp(10), Ui.dp(14), Ui.dp(10));
        row.addView(Ui.text(c, label, 15f, Ui.regular, Ui.text()),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(new Icons.GlassToggle(c, checked, onToggle::accept));
        return row;
    }

    private View urlRow(Context c, String title, String url, OnClickListener click) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(Ui.dp(16), Ui.dp(11), Ui.dp(16), Ui.dp(11));
        TextView t = Ui.text(c, title == null || title.isEmpty() ? url : title, 14.5f, Ui.medium, Ui.text());
        t.setMaxLines(1); t.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(t);
        TextView u = Ui.text(c, BrowserStore.domainOf(url), 12f, Ui.regular, Ui.text2());
        u.setMaxLines(1);
        row.addView(u);
        row.setOnClickListener(click);
        return row;
    }

    private View emptyRow(Context c, String msg) {
        TextView tv = Ui.text(c, msg, 14f, Ui.regular, Ui.text2());
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(Ui.dp(16), Ui.dp(22), Ui.dp(16), Ui.dp(22));
        return tv;
    }

    private View div(Context c) {
        View v = new View(c);
        v.setBackgroundColor(Ui.sep());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(0.5f)));
        lp.leftMargin = Ui.dp(16);
        v.setLayoutParams(lp);
        return v;
    }

    private View spacer(Context c, int dpH) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(dpH)));
        return v;
    }
}
