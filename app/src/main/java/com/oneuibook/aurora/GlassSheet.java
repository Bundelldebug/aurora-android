package com.oneuibook.aurora;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.RectF;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Authentic iOS 26 / iPadOS 26 Safari Liquid Glass Morphing Popover & Sheets:
 * - Prism-grade button-to-popover morph animation: the '...' glass circle grows seamlessly into the menu
 *   and shrinks back into the exact button rect on dismiss (zero abrupt jump or stock AlertDialog chrome).
 * - Responsive wide-screen (foldable/tablet) 372dp anchored popover layout.
 * - Includes Google Translate selector, Built-in Security Shield + NordVPN per-site routing,
 *   VPN/Danger interstitials, Extensions, Bookmarks/Reading/History, Cloud Sync, and Settings.
 */
final class GlassSheet extends FrameLayout {
    private final AuroraGlass.Backdrop backdrop;
    private final AuroraGlass.Surface glass = new AuroraGlass.Surface();
    private final ScrollView scroll;
    private final LinearLayout content;

    private final RectF anchor = new RectF();
    private final RectF target = new RectF();
    private final RectF cur = new RectF();
    private final Path clipPath = new Path();
    private final Ui.Spring morph = new Ui.Spring(360f, 0.94f, 0.002f);

    private float blobRadius;
    private boolean open;
    private boolean closing;
    private boolean hasAnchor;
    private int anchorIcon = Icons.MORE;
    private Runnable onRestoreSource;

    GlassSheet(Context c, AuroraGlass.Backdrop backdrop) {
        super(c);
        this.backdrop = backdrop;
        setWillNotDraw(false);
        setVisibility(GONE);

        scroll = new ScrollView(c);
        scroll.setVerticalScrollBarEnabled(false);
        content = new LinearLayout(c);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(Ui.dp(14), Ui.dp(14), Ui.dp(14), Ui.dp(16));
        scroll.addView(content, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(scroll, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        morph.onUpdate = this::onMorphUpdate;
        morph.onEnd = () -> {
            if (closing) {
                closing = false;
                open = false;
                setVisibility(GONE);
                clearSheetContent();
                if (onRestoreSource != null) {
                    Runnable r = onRestoreSource;
                    onRestoreSource = null;
                    r.run();
                }
            }
        };
    }

    private void clearSheetContent() {
        for (int i = 0; i < content.getChildCount(); i++) {
            View ch = content.getChildAt(i);
            if (ch instanceof WebView) {
                try { ((WebView) ch).destroy(); } catch (Throwable ignored) {}
            }
        }
        content.removeAllViews();
    }

    boolean isOpen() { return open && !closing; }

    void dismiss() {
        if (!open || closing) return;
        SecurityGuard.stopWatchingVpn(getContext());
        closing = true;
        if ("robolectric".equals(android.os.Build.FINGERPRINT)) {
            closing = false;
            open = false;
            setVisibility(GONE);
            clearSheetContent();
            if (onRestoreSource != null) {
                Runnable r = onRestoreSource;
                onRestoreSource = null;
                r.run();
            }
            return;
        }
        morph.stiffness = 440f;
        morph.dampingRatio = 1.0f;
        morph.to(0f);
    }

    private void showBuilt(RectF fromAnchor, int iconId, Runnable restoreSource) {
        boolean wasOpen = open && !closing;
        if (fromAnchor != null && !fromAnchor.isEmpty() && onRestoreSource != null && restoreSource != onRestoreSource) {
            Runnable prev = onRestoreSource;
            onRestoreSource = null;
            prev.run();
        }
        open = true;
        closing = false;
        if (fromAnchor != null && !fromAnchor.isEmpty()) {
            anchor.set(fromAnchor);
            hasAnchor = true;
            anchorIcon = iconId;
            onRestoreSource = restoreSource;
        } else if (!wasOpen) {
            hasAnchor = false;
            onRestoreSource = null;
        }

        setVisibility(VISIBLE);
        scroll.scrollTo(0, 0);
        requestLayout();

        if ("robolectric".equals(android.os.Build.FINGERPRINT)) {
            morph.snap(1f);
            onMorphUpdate();
            return;
        }
        if (wasOpen) {
            // Smooth in-place content crossfade when navigating to a sub-sheet
            morph.snap(1f);
            onMorphUpdate();
            scroll.setAlpha(0f);
            scroll.animate().alpha(1f).setDuration(180).setInterpolator(Ui.EMPHASIZED).start();
        } else {
            morph.stiffness = 340f;
            morph.dampingRatio = 0.94f;
            morph.snap(0f);
            post(() -> {
                if (open && !closing) morph.to(1f);
            });
        }
    }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        super.onMeasure(wSpec, hSpec);
        int W = getMeasuredWidth(), H = getMeasuredHeight();
        if (W <= 0 || H <= 0) return;
        boolean wide = (W / Ui.density) >= 600f;
        int cardW = wide
                ? Math.min(Ui.dp(376), W - Ui.dp(36))
                : Math.min(W - Ui.dp(24), Ui.dp(410));
        int maxH = Math.max(Ui.dp(180), H - Ui.statusInset - Ui.navInset - Ui.dp(wide ? 124 : 52));
        scroll.measure(
                MeasureSpec.makeMeasureSpec(cardW, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST));
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int W = getWidth(), H = getHeight();
        if (W <= 0 || H <= 0) return;
        int cw = scroll.getMeasuredWidth();
        int ch = scroll.getMeasuredHeight();
        boolean wide = (W / Ui.density) >= 600f;
        int safeTop = Math.max(Ui.statusInset, Ui.dp(16)) + Ui.dp(8);
        int safeBot = H - Math.max(Ui.navInset, Ui.dp(12)) - Ui.dp(14);

        float left, top;
        if (hasAnchor && wide) {
            boolean alignRight = anchor.centerX() >= W * 0.5f;
            left = alignRight ? Math.min(anchor.right, W - Ui.dpf(18)) - cw : Math.max(anchor.left, Ui.dpf(18));
            left = Ui.clamp(left, Ui.dpf(14), W - Ui.dpf(14) - cw);
            boolean below = anchor.centerY() < H * 0.45f;
            top = below ? (anchor.bottom + Ui.dpf(8)) : (anchor.top - ch - Ui.dpf(8));
            top = Ui.clamp(top, safeTop, Math.max(safeTop, safeBot - ch));
        } else if (hasAnchor) {
            boolean alignRight = anchor.centerX() >= W * 0.5f;
            left = alignRight ? Math.min(anchor.right, W - Ui.dpf(12)) - cw : (W - cw) / 2f;
            left = Ui.clamp(left, Ui.dpf(12), W - Ui.dpf(12) - cw);
            boolean below = anchor.centerY() < H * 0.42f;
            top = below ? (anchor.top) : (anchor.bottom - ch);
            top = Ui.clamp(top, safeTop, Math.max(safeTop, safeBot - ch));
        } else {
            left = (W - cw) / 2f;
            top = Math.max(safeTop, safeBot - ch);
        }

        target.set(left, top, left + cw, top + ch);
        scroll.layout(Math.round(target.left), Math.round(target.top), Math.round(target.right), Math.round(target.bottom));
        if (!hasAnchor) {
            anchor.set(target.centerX() - Ui.dpf(26), target.bottom - Ui.dpf(52),
                    target.centerX() + Ui.dpf(26), target.bottom);
        }
        onMorphUpdate();
    }

    /**
     * Prism-grade Liquid Drop Morph:
     * 1) Button circle gathers slightly and glides toward target popover center.
     * 2) Glass drop smoothly swells into the full rounded menu card while radius blends circle -> 26dp.
     * 3) Content fades & scales smoothly inside the clipped glass boundary; reverses identically on dismiss.
     */
    private void onMorphUpdate() {
        float v = Ui.clamp(morph.value, 0f, 1.06f);
        if (target.isEmpty()) return;

        float gather = Ui.clamp(v / 0.38f, 0f, 1f);
        gather = gather * gather * (3f - 2f * gather);
        float swell = Ui.clamp((v - 0.18f) / 0.82f, 0f, 1.05f);
        float blobSz = Math.max(anchor.height(), Ui.dpf(48)) * 1.12f;
        float bw = Ui.lerp(anchor.width(), blobSz, gather);
        float bh = Ui.lerp(anchor.height(), blobSz, gather);
        float w = Ui.lerp(bw, target.width(), swell);
        float h = Ui.lerp(bh, target.height(), swell);

        float cx = Ui.lerp(anchor.centerX(), target.centerX(), Ui.clamp(v, 0f, 1.02f));
        float cy = Ui.lerp(anchor.centerY(), target.centerY(), Ui.clamp(v, 0f, 1.02f));
        float travel = (float) Math.sin(Math.PI * Ui.clamp(v, 0f, 1f)) * 0.045f;
        w *= (1f + travel);
        h *= (1f - travel * 0.35f);

        cur.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f);
        blobRadius = Ui.lerp(Math.min(w, h) * 0.5f, Ui.dpf(26), Ui.clamp(swell, 0f, 1f));

        float ca = Ui.clamp((v - 0.42f) / 0.52f, 0f, 1f);
        scroll.setAlpha(ca);
        float sc = Ui.lerp(0.92f, 1f, ca);
        scroll.setPivotX(Ui.clamp(anchor.centerX() - target.left, 0f, target.width()));
        scroll.setPivotY(Ui.clamp(anchor.centerY() - target.top, 0f, target.height()));
        scroll.setScaleX(sc);
        scroll.setScaleY(sc);
        invalidate();
    }

    @Override protected void dispatchDraw(Canvas c) {
        float v = Ui.clamp(morph.value, 0f, 1f);
        // Subtle dim scrim behind popover
        int scrimAlpha = Math.round(0x3A * v);
        if (scrimAlpha > 0) c.drawColor(Color.argb(scrimAlpha, 0, 0, 0));

        if (!cur.isEmpty() && cur.width() > 2f && cur.height() > 2f) {
            float rad = Math.min(blobRadius, Math.min(cur.width(), cur.height()) / 2f);
            // Exact Prism GlassMenu continuous optical material blend
            glass.tint = Ui.mix(Ui.barTint(), Ui.sheetTint(), v);
            glass.strokeColor = Ui.glassStroke();
            glass.blurDp = Ui.barBlur();
            glass.refractDp = Ui.lerp(15f, 17f, v);
            glass.edgeDp = Ui.lerp(20f, 26f, v);
            glass.outerDp = Ui.lerp(5f, 7f, v);
            glass.outerRefractDp = Ui.lerp(11f, 12f, v);
            glass.rim = Ui.lerp(0.32f, 0.40f, v);
            glass.shadowAlpha = Ui.lerp(0.07f, 0.22f, v);
            glass.chroma = 0f;
            glass.vibrance = 1.35f;
            glass.alpha = hasAnchor ? 1f : Ui.clamp(v * 2.5f, 0f, 1f);
            glass.shapes(cur, rad, null, 0, null, 0);
            glass.draw(c, this, backdrop);

            // Fading '...' glyph riding inside the morphing glass bubble at start/end of animation
            float glyphA = hasAnchor ? (1f - Ui.clamp(v / 0.36f, 0f, 1f)) : 0f;
            if (glyphA > 0.02f) {
                Icons.draw(c, anchorIcon, cur.centerX(), cur.centerY(), Ui.dpf(20), Ui.alpha(Ui.text(), glyphA));
            }

            clipPath.reset();
            clipPath.addRoundRect(cur, rad, rad, Path.Direction.CW);
            int sc = c.save();
            c.clipPath(clipPath);
            super.dispatchDraw(c);
            c.restoreToCount(sc);
        }
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN && !target.contains(e.getX(), e.getY())) {
            dismiss();
            return true;
        }
        return true;
    }

    // ---- 1. iOS 26 Safari '...' Popover Menu ----
    void showMainMenu(MainActivity act) {
        showMainMenu(act, null, Icons.MORE, null);
    }

    void showMainMenu(MainActivity act, RectF fromAnchor, int iconId, Runnable restoreSource) {
        content.removeAllViews();
        Context c = getContext();
        TabManager.Tab tab = act.tabs.activeTab();
        boolean onPage = tab != null && !tab.isStartPage();
        String curDomain = onPage ? BrowserStore.domainOf(tab.url) : "";
        boolean vpnOn = SecurityGuard.isVpnActive(c);

        // Top 4 circular-pill quick actions
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
            // Google Translate built-in webpage translation
            grp.addView(iosMenuRow(c,
                    tab.translated ? "원문으로 보기" : "웹페이지 번역",
                    Icons.TRANSLATE,
                    tab.translated ? PageTranslator.labelFor(Ui.translateLang) : "Google",
                    v -> {
                        if (tab.translated) {
                            dismiss();
                            act.restoreCurrentPageOriginal();
                        } else {
                            showTranslateSheet(act);
                        }
                    }));
            grp.addView(div(c));

            // One-tap per-site NordVPN routing toggle for current domain
            if (!curDomain.isEmpty()) {
                boolean routed = BrowserStore.hasVpnDomain(curDomain);
                grp.addView(toggleRow(c, curDomain + "에 " + SecurityGuard.vpnLabel(Ui.vpnProvider) + " 적용", routed, on -> {
                    if (on) BrowserStore.addVpnDomain(c, curDomain);
                    else BrowserStore.removeVpnDomain(c, curDomain);
                    act.tabs.activeTab();
                    act.safariBar.updateState(tab, act.tabs.canGoBack(), act.tabs.canGoForward(), act.tabs.activeList().size());
                    if (on && !SecurityGuard.isVpnActive(c)) {
                        showVpnGateSheet(act, tab, tab.url, curDomain);
                    }
                }));
                grp.addView(div(c));
            }

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

        String vpnBadge = SecurityGuard.vpnLabel(Ui.vpnProvider) + (vpnOn ? " · 연결됨" : "");
        grp.addView(iosMenuRow(c, "보안 및 VPN", Icons.VPN, vpnBadge, v -> showSecurityVpnSheet(act)));
        grp.addView(div(c));
        grp.addView(iosMenuRow(c, "확장 프로그램", Icons.PUZZLE,
                ExtensionManager.installed.isEmpty() ? null : String.valueOf(ExtensionManager.installed.size()),
                v -> showExtensionsSheet(act)));
        grp.addView(div(c));
        grp.addView(iosMenuRow(c, "동기화", Icons.CLOUD, BrowserStore.syncUri.isEmpty() ? null : "연결됨", v -> showSyncSheet(act)));
        grp.addView(div(c));
        grp.addView(iosMenuRow(c, "설정", Icons.SETTINGS, null, v -> showSettingsSheet(act)));
        content.addView(grp);
        showBuilt(fromAnchor, iconId, restoreSource);
    }

    // ---- 2. Granular Google Translate Sheet (Per-site Never Translate, Per-language Always Translate, Target Picker) ----
    void showTranslateSheet(MainActivity act) {
        content.removeAllViews();
        Context c = getContext();
        TabManager.Tab tab = act.tabs.activeTab();
        boolean onPage = tab != null && !tab.isStartPage();
        String curDom = onPage ? BrowserStore.domainOf(tab.url) : "";
        String detLang = (onPage && tab.detectedLang != null) ? tab.detectedLang : "";
        String baseTarget = Ui.translateLang.toLowerCase(java.util.Locale.ROOT).split("-")[0];

        String sub = onPage
                ? (curDom + (tab.translated ? " · 번역됨" : (!detLang.isEmpty() ? " · " + PageTranslator.labelFor(detLang) : "")))
                : "Google 번역";
        content.addView(sheetHeader(c, "웹페이지 번역", sub));

        // 1) Primary 1-tap Translate / Restore Original action + Per-site "이 사이트 번역 안 함"
        if (onPage) {
            LinearLayout pageGrp = groupBox(c);
            if (tab.translated) {
                pageGrp.addView(iosMenuRow(c, "원문으로 되돌리기", Icons.RELOAD, null, v -> {
                    dismiss();
                    act.restoreCurrentPageOriginal();
                }));
            } else {
                pageGrp.addView(iosMenuRow(c, PageTranslator.labelFor(Ui.translateLang) + "(으)로 지금 번역", Icons.TRANSLATE, null, v -> {
                    dismiss();
                    act.translateCurrentPage(Ui.translateLang);
                }));
            }
            if (!curDom.isEmpty()) {
                pageGrp.addView(div(c));
                boolean neverSite = BrowserStore.isNeverTranslateHost(curDom);
                pageGrp.addView(toggleRow(c, "이 사이트 번역 안 함 (" + curDom + ")", neverSite, on -> {
                    if (on) {
                        BrowserStore.addNeverTranslateHost(c, curDom);
                        if (tab.translated) act.restoreCurrentPageOriginal();
                    } else {
                        BrowserStore.removeNeverTranslateHost(c, curDom);
                    }
                    showTranslateSheet(act);
                }));
            }
            if (!detLang.isEmpty() && !detLang.equals(baseTarget)) {
                pageGrp.addView(div(c));
                boolean alwaysLang = BrowserStore.isAlwaysTranslateLang(detLang);
                pageGrp.addView(toggleRow(c, PageTranslator.labelFor(detLang) + " 페이지 항상 번역", alwaysLang, on -> {
                    BrowserStore.setAlwaysTranslateLang(c, detLang, on);
                    if (on && !tab.translated && !BrowserStore.isNeverTranslateHost(curDom)) {
                        act.translateCurrentPage(Ui.translateLang);
                    }
                }));
            }
            content.addView(pageGrp);
            content.addView(spacer(c, 10));
        }

        // 2) Global Auto-Translate + Compact Target Language Picker
        LinearLayout autoGrp = groupBox(c);
        autoGrp.addView(toggleRow(c, "외국어 페이지 자동 번역", Ui.autoTranslate, on -> {
            Ui.autoTranslate = on;
            Ui.savePrefs(c);
            if (on && onPage && tab.webView != null && !tab.translated) {
                PageTranslator.autoTranslateIfForeign(tab.webView, tab.url, Ui.translateLang, l -> tab.detectedLang = l);
            }
        }));
        autoGrp.addView(div(c));

        LinearLayout langBox = new LinearLayout(c);
        langBox.setOrientation(LinearLayout.VERTICAL);
        langBox.setPadding(Ui.dp(16), Ui.dp(11), Ui.dp(16), Ui.dp(12));
        langBox.addView(Ui.text(c, "대상 언어", 12.5f, Ui.semibold, Ui.text2()));

        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(c);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout pills = new LinearLayout(c);
        pills.setOrientation(LinearLayout.HORIZONTAL);
        pills.setPadding(0, Ui.dp(8), 0, 0);
        for (int i = 0; i < PageTranslator.LANG_CODES.length; i++) {
            final String code = PageTranslator.LANG_CODES[i];
            final String label = PageTranslator.LANG_LABELS[i];
            boolean sel = code.equals(Ui.translateLang);
            TextView ch = Ui.text(c, label, 13f, sel ? Ui.semibold : Ui.medium, sel ? 0xFFFFFFFF : Ui.text());
            ch.setPadding(Ui.dp(13), Ui.dp(7), Ui.dp(13), Ui.dp(7));
            ch.setBackground(Ui.round(sel ? Ui.activeAccent() : Ui.alpha(Ui.text(), Ui.isDarkSurface() ? 0.12f : 0.08f), 100));
            ch.setOnClickListener(v -> {
                Ui.translateLang = code;
                Ui.savePrefs(c);
                if (onPage && !BrowserStore.isNeverTranslateHost(curDom)) {
                    dismiss();
                    act.translateCurrentPage(code);
                } else {
                    showTranslateSheet(act);
                }
            });
            Ui.press(ch, 0.94f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.leftMargin = Ui.dp(7);
            pills.addView(ch, lp);
        }
        hs.addView(pills);
        langBox.addView(hs);
        autoGrp.addView(langBox);
        content.addView(autoGrp);

        // 3) Manage "번역 안 함" excluded domains
        if (!BrowserStore.neverTranslateHosts.isEmpty()) {
            content.addView(spacer(c, 10));
            TextView exTitle = Ui.text(c, "번역 안 함 사이트 (" + BrowserStore.neverTranslateHosts.size() + ")", 12.5f, Ui.semibold, Ui.text2());
            exTitle.setPadding(Ui.dp(6), Ui.dp(2), Ui.dp(6), Ui.dp(6));
            content.addView(exTitle);

            LinearLayout exGrp = groupBox(c);
            List<String> hosts = new ArrayList<>(BrowserStore.neverTranslateHosts);
            Collections.sort(hosts);
            for (int i = 0; i < hosts.size(); i++) {
                final String h = hosts.get(i);
                exGrp.addView(iosMenuRow(c, h, Icons.CLOSE, "해제", v -> {
                    BrowserStore.removeNeverTranslateHost(c, h);
                    showTranslateSheet(act);
                }));
                if (i + 1 < hosts.size()) exGrp.addView(div(c));
            }
            content.addView(exGrp);
        }
        showBuilt(null, Icons.TRANSLATE, null);
    }

    // ---- 2b. Start Page Customization Sheet (Background Image & Sections) ----
    void showStartPageEditSheet(MainActivity act) {
        content.removeAllViews();
        Context c = getContext();
        content.addView(sheetHeader(c, "시작 페이지 사용자화", "배경 및 표시 섹션"));

        LinearLayout bgGrp = groupBox(c);
        bgGrp.addView(iosMenuRow(c, "사진에서 배경 선택", Icons.PLUS, StartPageView.hasCustomBg(c) ? "사용자 지정" : null, v -> {
            dismiss();
            act.pickStartPageWallpaper();
        }));
        if (StartPageView.hasCustomBg(c)) {
            bgGrp.addView(div(c));
            bgGrp.addView(iosMenuRow(c, "사용자 사진 배경 제거", Icons.CLOSE, null, v -> {
                StartPageView.clearCustomBg(c);
                act.startPage.reloadBackground(c);
                act.startPage.rebuild();
                showStartPageEditSheet(act);
            }));
        }
        bgGrp.addView(div(c));

        // 6 Curated gradient wallpaper swatches
        int curIdx = Ui.prefs(c).getInt("wallpaper_idx", 0);
        boolean usingPhoto = StartPageView.hasCustomBg(c);
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(c);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout swRow = new LinearLayout(c);
        swRow.setOrientation(LinearLayout.HORIZONTAL);
        swRow.setPadding(Ui.dp(14), Ui.dp(12), Ui.dp(14), Ui.dp(12));
        for (int i = 0; i < StartPageView.WALLPAPER_NAMES.length; i++) {
            final int idx = i;
            boolean sel = !usingPhoto && curIdx == i;
            LinearLayout cell = new LinearLayout(c);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);

            View swatch = new View(c);
            int[] pal = Ui.dark ? StartPageView.DARK_PALETTES[i] : StartPageView.LIGHT_PALETTES[i];
            android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.TL_BR, pal);
            gd.setCornerRadius(Ui.dpf(14));
            gd.setStroke(Ui.dp(sel ? 2.4f : 0.8f), sel ? Ui.activeAccent() : Ui.glassStroke());
            swatch.setBackground(gd);
            cell.addView(swatch, new LinearLayout.LayoutParams(Ui.dp(54), Ui.dp(72)));

            TextView lbl = Ui.text(c, StartPageView.WALLPAPER_NAMES[i], 11f, sel ? Ui.semibold : Ui.regular, sel ? Ui.activeAccent() : Ui.text2());
            lbl.setPadding(0, Ui.dp(5), 0, 0);
            cell.addView(lbl);

            cell.setOnClickListener(v -> {
                StartPageView.clearCustomBg(c);
                Ui.prefs(c).edit().putInt("wallpaper_idx", idx).apply();
                act.startPage.reloadBackground(c);
                act.startPage.rebuild();
                showStartPageEditSheet(act);
            });
            Ui.press(cell, 0.94f);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) clp.leftMargin = Ui.dp(10);
            swRow.addView(cell, clp);
        }
        hs.addView(swRow);
        bgGrp.addView(hs);
        content.addView(bgGrp);
        content.addView(spacer(c, 10));

        LinearLayout secGrp = groupBox(c);
        secGrp.addView(toggleRow(c, "즐겨찾기", Ui.prefs(c).getBoolean("sp_favorites", true), on -> {
            Ui.prefs(c).edit().putBoolean("sp_favorites", on).apply();
            act.startPage.rebuild();
        }));
        secGrp.addView(div(c));
        secGrp.addView(toggleRow(c, "자주 방문한 사이트", Ui.prefs(c).getBoolean("sp_frequent", true), on -> {
            Ui.prefs(c).edit().putBoolean("sp_frequent", on).apply();
            act.startPage.rebuild();
        }));
        secGrp.addView(div(c));
        secGrp.addView(toggleRow(c, "개인정보 보호 리포트", Ui.prefs(c).getBoolean("sp_privacy", true), on -> {
            Ui.prefs(c).edit().putBoolean("sp_privacy", on).apply();
            act.startPage.rebuild();
        }));
        secGrp.addView(div(c));
        secGrp.addView(toggleRow(c, "읽기 목록", Ui.prefs(c).getBoolean("sp_reading", true), on -> {
            Ui.prefs(c).edit().putBoolean("sp_reading", on).apply();
            act.startPage.rebuild();
        }));
        content.addView(secGrp);
        showBuilt(null, Icons.EDIT, null);
    }

    // ---- 3. Security Shield & Per-Site VPN (NordVPN default) Sheet ----
    void showSecurityVpnSheet(MainActivity act) {
        content.removeAllViews();
        Context c = getContext();
        boolean vpnOn = SecurityGuard.isVpnActive(c);
        String vpnName = SecurityGuard.vpnLabel(Ui.vpnProvider);
        content.addView(sheetHeader(c, "보안 및 VPN", vpnOn ? vpnName + " 연결됨" : vpnName));

        // Live Security & VPN status pill
        LinearLayout statusBox = groupBox(c);
        statusBox.setPadding(Ui.dp(14), Ui.dp(12), Ui.dp(14), Ui.dp(12));
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        View ic = new View(c) {
            @Override protected void onDraw(Canvas canvas) {
                Icons.draw(canvas, Icons.VPN, getWidth() / 2f, getHeight() / 2f, Ui.dpf(20), vpnOn ? 0xFF34C759 : Ui.activeAccent());
            }
        };
        row.addView(ic, new LinearLayout.LayoutParams(Ui.dp(24), Ui.dp(24)));
        LinearLayout stCol = new LinearLayout(c);
        stCol.setOrientation(LinearLayout.VERTICAL);
        stCol.setPadding(Ui.dp(10), 0, Ui.dp(8), 0);
        stCol.addView(Ui.text(c, vpnOn ? vpnName + " 보호 중" : vpnName + " 대기 중", 14.5f, Ui.semibold, Ui.text()));
        stCol.addView(Ui.text(c, "차단된 추적기 " + BrowserStore.blockedTrackers + "개 · VPN 지정 사이트 " + BrowserStore.vpnDomains.size() + "개",
                12f, Ui.regular, Ui.text2()));
        row.addView(stCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView openVpn = Ui.text(c, vpnOn ? "설정" : "연결", 13f, Ui.semibold, 0xFFFFFFFF);
        openVpn.setPadding(Ui.dp(12), Ui.dp(6), Ui.dp(12), Ui.dp(6));
        openVpn.setBackground(Ui.round(vpnOn ? 0xFF34C759 : Ui.activeAccent(), 100));
        openVpn.setOnClickListener(v -> SecurityGuard.launchVpnApp(c));
        row.addView(openVpn);
        statusBox.addView(row);
        content.addView(statusBox);
        content.addView(spacer(c, 10));

        // Security toggles & VPN provider picker
        LinearLayout secGrp = groupBox(c);
        secGrp.addView(iosMenuRow(c, "기본 VPN 앱", Icons.VPN, vpnName, v -> {
            Ui.vpnProvider = SecurityGuard.nextVpnProvider(Ui.vpnProvider);
            Ui.savePrefs(c);
            showSecurityVpnSheet(act);
        }));
        secGrp.addView(div(c));
        secGrp.addView(toggleRow(c, "피싱·악성코드 및 지문 차단", Ui.securityShield, on -> {
            Ui.securityShield = on;
            Ui.savePrefs(c);
        }));
        secGrp.addView(div(c));
        secGrp.addView(toggleRow(c, "HTTPS 우선 자동 전환", Ui.httpsOnly, on -> {
            Ui.httpsOnly = on;
            Ui.savePrefs(c);
        }));
        secGrp.addView(div(c));
        secGrp.addView(toggleRow(c, "추적기 및 광고 차단", Ui.adBlock, on -> {
            Ui.adBlock = on;
            Ui.savePrefs(c);
        }));
        content.addView(secGrp);
        content.addView(spacer(c, 10));

        // Per-site VPN routing list
        TextView secTitle = Ui.text(c, "VPN 지정 사이트 (" + vpnName + ")", 13f, Ui.semibold, Ui.text2());
        secTitle.setPadding(Ui.dp(6), Ui.dp(2), Ui.dp(6), Ui.dp(6));
        content.addView(secTitle);

        LinearLayout domGrp = groupBox(c);
        List<String> doms = new ArrayList<>(BrowserStore.vpnDomains);
        Collections.sort(doms);
        if (doms.isEmpty()) {
            domGrp.addView(emptyRow(c, "지정된 사이트가 없습니다.\n추가한 사이트 방문 시 " + vpnName + " 연결을 자동으로 확인합니다."));
        } else {
            for (int i = 0; i < doms.size(); i++) {
                final String d = doms.get(i);
                domGrp.addView(iosMenuRow(c, d, Icons.CLOSE, "해제", v -> {
                    BrowserStore.removeVpnDomain(c, d);
                    showSecurityVpnSheet(act);
                }));
                if (i + 1 < doms.size()) domGrp.addView(div(c));
            }
        }
        domGrp.addView(div(c));
        TabManager.Tab cur = act.tabs.activeTab();
        if (cur != null && !cur.isStartPage()) {
            String curDom = BrowserStore.domainOf(cur.url);
            if (!curDom.isEmpty() && !BrowserStore.hasVpnDomain(curDom)) {
                domGrp.addView(iosMenuRow(c, "현재 사이트 추가 (" + curDom + ")", Icons.PLUS, null, v -> {
                    BrowserStore.addVpnDomain(c, curDom);
                    showSecurityVpnSheet(act);
                }));
                domGrp.addView(div(c));
            }
        }
        domGrp.addView(iosMenuRow(c, "도메인 직접 추가", Icons.PLUS, null, v ->
                showTextInputSheet("VPN 적용 사이트 추가", "예: x.com, reddit.com", "추가", val -> {
                    BrowserStore.addVpnDomain(c, val);
                    showSecurityVpnSheet(act);
                })));
        content.addView(domGrp);
        showBuilt(null, Icons.VPN, null);
    }

    // ---- 4. Per-Site VPN Required Interstitial ----
    void showVpnGateSheet(MainActivity act, TabManager.Tab tab, String url, String domain) {
        content.removeAllViews();
        Context c = getContext();
        String vpnName = SecurityGuard.vpnLabel(Ui.vpnProvider);
        content.addView(sheetHeader(c, vpnName + " 연결 필요", domain));

        LinearLayout card = groupBox(c);
        card.setPadding(Ui.dp(16), Ui.dp(14), Ui.dp(16), Ui.dp(14));
        card.addView(Ui.text(c, domain + "은(는) " + vpnName + " 전용 사이트로 지정되어 있습니다.\n"
                + vpnName + "을 연결하면 자동으로 페이지가 열립니다.", 14f, Ui.regular, Ui.text()));
        content.addView(card);
        content.addView(spacer(c, 10));

        LinearLayout acts = groupBox(c);
        acts.addView(iosMenuRow(c, vpnName + " 열고 연결 대기", Icons.VPN, "자동 열기", v -> {
            SecurityGuard.waitForVpnConnection(c, () -> {
                Toast.makeText(c, vpnName + " 연결 확인됨 — 페이지 여는 중", Toast.LENGTH_SHORT).show();
                dismiss();
                act.tabs.loadUrl(tab, url);
            });
            SecurityGuard.launchVpnApp(c);
        }));
        acts.addView(div(c));
        acts.addView(iosMenuRow(c, "이번만 VPN 없이 열기", Icons.FORWARD, null, v -> {
            SecurityGuard.allowVpnBypassOnce(domain);
            dismiss();
            act.tabs.loadUrl(tab, url);
        }));
        content.addView(acts);
        showBuilt(null, Icons.VPN, null);
    }

    // ---- 5. Security Phishing/Malware Warning Gate ----
    void showDangerGateSheet(MainActivity act, TabManager.Tab tab, String url, String reason) {
        content.removeAllViews();
        Context c = getContext();
        String host = BrowserStore.domainOf(url);
        content.addView(sheetHeader(c, "보안 위험 감지됨", host));

        LinearLayout card = groupBox(c);
        card.setPadding(Ui.dp(16), Ui.dp(14), Ui.dp(16), Ui.dp(14));
        card.addView(Ui.text(c, reason, 14.5f, Ui.semibold, 0xFFFF453A));
        TextView uTxt = Ui.text(c, url, 12f, Ui.regular, Ui.text2());
        uTxt.setPadding(0, Ui.dp(6), 0, 0);
        uTxt.setMaxLines(2);
        uTxt.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        card.addView(uTxt);
        content.addView(card);
        content.addView(spacer(c, 10));

        LinearLayout acts = groupBox(c);
        acts.addView(iosMenuRow(c, "안전한 페이지로 돌아가기", Icons.SHIELD, "권장", v -> dismiss()));
        acts.addView(div(c));
        acts.addView(iosMenuRow(c, "위험을 감수하고 이동", Icons.FORWARD, null, v -> {
            SecurityGuard.allowDangerousOnce(host);
            dismiss();
            act.tabs.loadUrl(tab, url);
        }));
        content.addView(acts);
        showBuilt(null, Icons.SHIELD, null);
    }

    // ---- 6. Reusable Glass Confirmation & Text Input Sheets (Zero stock AlertDialogs) ----
    void showConfirmSheet(String title, String message, String confirmLabel, boolean destructive, Runnable onConfirm) {
        content.removeAllViews();
        Context c = getContext();
        content.addView(sheetHeader(c, title, null));

        LinearLayout card = groupBox(c);
        card.setPadding(Ui.dp(16), Ui.dp(14), Ui.dp(16), Ui.dp(14));
        card.addView(Ui.text(c, message, 14f, Ui.regular, Ui.text()));
        content.addView(card);
        content.addView(spacer(c, 10));

        LinearLayout acts = groupBox(c);
        TextView ok = Ui.text(c, confirmLabel, 15f, Ui.semibold, destructive ? 0xFFFF453A : Ui.activeAccent());
        ok.setGravity(Gravity.CENTER);
        ok.setPadding(0, Ui.dp(13), 0, Ui.dp(13));
        ok.setOnClickListener(v -> {
            dismiss();
            if (onConfirm != null) onConfirm.run();
        });
        acts.addView(ok);
        acts.addView(div(c));
        TextView cancel = Ui.text(c, "취소", 15f, Ui.regular, Ui.text2());
        cancel.setGravity(Gravity.CENTER);
        cancel.setPadding(0, Ui.dp(13), 0, Ui.dp(13));
        cancel.setOnClickListener(v -> dismiss());
        acts.addView(cancel);
        content.addView(acts);
        showBuilt(null, Icons.SHIELD, null);
    }

    void showTextInputSheet(String title, String hint, String submitLabel, java.util.function.Consumer<String> onSubmit) {
        content.removeAllViews();
        Context c = getContext();
        content.addView(sheetHeader(c, title, null));

        LinearLayout box = groupBox(c);
        box.setPadding(Ui.dp(14), Ui.dp(10), Ui.dp(14), Ui.dp(10));
        EditText et = new EditText(c);
        et.setHint(hint);
        et.setSingleLine(true);
        et.setTextColor(Ui.text());
        et.setHintTextColor(Ui.text2());
        et.setBackground(null);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        box.addView(et, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(box);
        content.addView(spacer(c, 10));

        LinearLayout acts = groupBox(c);
        TextView ok = Ui.text(c, submitLabel, 15f, Ui.semibold, Ui.activeAccent());
        ok.setGravity(Gravity.CENTER);
        ok.setPadding(0, Ui.dp(13), 0, Ui.dp(13));
        ok.setOnClickListener(v -> {
            String val = et.getText().toString().trim();
            if (!val.isEmpty() && onSubmit != null) onSubmit.accept(val);
            else dismiss();
        });
        acts.addView(ok);
        content.addView(acts);
        showBuilt(null, Icons.PLUS, null);
        et.requestFocus();
    }

    // ---- 7. Chrome Extensions Sheet ----
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
                    showConfirmSheet(ext.name, "이 확장 프로그램을 제거할까요?", "제거", true, () -> {
                        ExtensionManager.remove(c, ext.id);
                        showExtensionsSheet(act);
                    });
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
        actions.addView(iosMenuRow(c, "확장 ID 또는 URL로 설치", Icons.PLUS, null, v ->
                showTextInputSheet("확장 프로그램 설치", "Chrome 웹 스토어 URL 또는 32자 ID", "설치", val -> {
                    dismiss();
                    act.installStoreExtension(val);
                })));
        content.addView(actions);
        showBuilt(null, Icons.PUZZLE, null);
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
        showBuilt(null, Icons.PUZZLE, null);
    }

    // ---- 8. Bookmarks / Reading List / History Sheet ----
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
                showBuilt(null, Icons.BOOKMARK, null);
                return;
            }
        }
        content.addView(grp);
        showBuilt(null, Icons.BOOKMARK, null);
    }

    // ---- 9. Cloud Account Sync Sheet ----
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
        showBuilt(null, Icons.CLOUD, null);
    }

    // ---- 10. Settings Sheet ----
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
        grp.addView(iosMenuRow(c, "번역 언어 (Google)", Icons.TRANSLATE, PageTranslator.labelFor(Ui.translateLang), v -> showTranslateSheet(act)));
        grp.addView(div(c));
        grp.addView(toggleRow(c, "외국어 페이지 자동 번역", Ui.autoTranslate, on -> {
            Ui.autoTranslate = on;
            Ui.savePrefs(c);
        }));
        grp.addView(div(c));
        grp.addView(iosMenuRow(c, "시작 페이지 배경 및 메뉴", Icons.EDIT, StartPageView.hasCustomBg(c) ? "사용자 사진" : "기본", v -> showStartPageEditSheet(act)));
        grp.addView(div(c));
        grp.addView(iosMenuRow(c, "보안 및 VPN", Icons.VPN, SecurityGuard.vpnLabel(Ui.vpnProvider), v -> showSecurityVpnSheet(act)));
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
        showBuilt(null, Icons.SETTINGS, null);
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
        tv.setGravity(Gravity.CENTER);
        tv.setMaxLines(1);
        tv.setPadding(0, Ui.dp(5), 0, 0);
        box.addView(tv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        box.setOnClickListener(click);
        Ui.press(box, 0.94f);
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

    private View iosMenuRow(Context c, String label, int iconId, String detail, OnClickListener click) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(16), Ui.dp(13), Ui.dp(16), Ui.dp(13));
        TextView lbl = Ui.text(c, label, 15f, Ui.regular, Ui.text());
        lbl.setMaxLines(1);
        lbl.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(lbl, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (detail != null && !detail.isEmpty()) {
            TextView dt = Ui.text(c, detail, 13f, Ui.regular, Ui.text2());
            dt.setPadding(Ui.dp(6), 0, Ui.dp(10), 0);
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
        TextView lbl = Ui.text(c, label, 14.5f, Ui.regular, Ui.text());
        lbl.setMaxLines(1);
        lbl.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(lbl, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
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
        TextView tv = Ui.text(c, msg, 13.5f, Ui.regular, Ui.text2());
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(Ui.dp(16), Ui.dp(20), Ui.dp(16), Ui.dp(20));
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
