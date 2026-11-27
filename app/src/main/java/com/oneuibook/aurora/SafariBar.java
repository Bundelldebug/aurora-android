package com.oneuibook.aurora;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.Editable;
import android.text.InputType;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * Authentic iOS 26/27 & iPadOS 26 Apple Safari Liquid Glass Floating Bar:
 * - Phone Compact mode (default): detached '<' glass circle + floating address pill + detached '...' circle
 * - Phone Bottom Classic / Top modes
 * - Foldable / Tablet Wide mode (>= 600dp): unified top floating glass toolbar + live horizontal tab strip
 * Smooth spring-driven edit expansion, tab swipe gestures, and anchor handoff for button-to-menu morphing.
 */
final class SafariBar extends FrameLayout {
    interface Listener {
        void onBack();
        void onBackLongPress();
        void onForward();
        void onMoreMenu(RectF anchor);
        void onPageMenu(RectF anchor);
        void onOpenTabs();
        void onShare();
        void onBookmarks();
        void onReloadOrStop();
        void onSubmitUrl(String input);
        void onQueryChanged(String query);
        void onEditModeChanged(boolean editing);
        void onSwipeTabDelta(int delta);
        default void onSelectTabIndex(int index) {}
        default void onCloseTabIndex(int index) {}
        default void onNewTabInline() {}
    }

    private final AuroraGlass.Backdrop backdrop;
    private Listener listener;

    private final AuroraGlass.Surface mainGlass = new AuroraGlass.Surface();
    private final AuroraGlass.Surface auxGlass = new AuroraGlass.Surface();
    private final AuroraGlass.Surface stripGlass = new AuroraGlass.Surface();

    final RectF backBtn = new RectF(), pill = new RectF(), moreBtn = new RectF();
    final RectF bottomToolbar = new RectF();
    private final RectF wideLeftPill = new RectF(), wideRightPill = new RectF(), wideStripBg = new RectF();
    private final List<RectF> tabChipRects = new ArrayList<>();
    private final RectF addTabChipRect = new RectF();
    private final List<String> tabTitles = new ArrayList<>();
    private int activeTabIdx = 0;

    private final Ui.Spring collapse = new Ui.Spring(360f, 1.0f, 0.002f);
    private final Ui.Spring editP = new Ui.Spring(440f, 1.0f, 0.002f);
    private final Ui.Spring swipeX = new Ui.Spring(460f, 1.0f, 0.5f);
    private final Ui.Spring pressBack = new Ui.Spring(640f, 1.0f, 0.002f);
    private final Ui.Spring pressPill = new Ui.Spring(640f, 1.0f, 0.002f);
    private final Ui.Spring pressMore = new Ui.Spring(640f, 1.0f, 0.002f);

    final EditText addressField;
    boolean editing;
    private boolean moreHidden;
    private int imeOffset;

    private String domainText = "";
    private String fullUrl = "";
    private int loadProgress = 100;
    private boolean loading;
    private boolean canBack, canForward;
    private boolean secureHttps;
    private boolean vpnSite;
    private boolean pageTranslated;
    private int tabCount = 1;

    private final TextPaint domainPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint badgePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint chipPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint progPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint chipFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private static final int T_NONE = 0, T_BACK = 1, T_PILL = 2, T_MORE = 3, T_TOOLBAR = 4,
            T_WIDE_LEFT = 5, T_WIDE_RIGHT = 6, T_TAB_CHIP = 7, T_ADD_TAB = 8;
    private int touch = T_NONE;
    private int touchedChip = -1;
    private boolean touchedChipClose;
    private float downX, downY, touchX, touchY;
    private boolean swipingPill;
    private long lastMoreTapMs;
    private final Runnable backLongPressRun = () -> {
        if ((touch == T_BACK || touch == T_WIDE_LEFT) && listener != null) {
            touch = T_NONE;
            pressBack.to(0f);
            listener.onBackLongPress();
        }
    };

    SafariBar(Context c, AuroraGlass.Backdrop backdrop) {
        super(c);
        this.backdrop = backdrop;
        setWillNotDraw(false);
        setClipChildren(false);

        domainPaint.setTypeface(Ui.semibold);
        domainPaint.setTextAlign(Paint.Align.CENTER);
        badgePaint.setTypeface(Ui.bold);
        badgePaint.setTextAlign(Paint.Align.CENTER);
        badgePaint.setTextSize(Ui.sp(10f));
        chipPaint.setTypeface(Ui.medium);
        chipPaint.setTextSize(Ui.sp(12f));

        // Exact Prism Dock barGlass optical constants
        mainGlass.blurDp = Ui.barBlur();
        mainGlass.refractDp = 17f;
        mainGlass.edgeDp = 26f;
        mainGlass.outerDp = 7f;
        mainGlass.outerRefractDp = 12f;
        mainGlass.chroma = 0f;
        mainGlass.vibrance = 1.35f;
        mainGlass.rim = 0.32f;
        mainGlass.edgeDark = 0.32f;
        mainGlass.shadowAlpha = 0.16f;

        auxGlass.blurDp = Ui.barBlur();
        auxGlass.refractDp = 17f;
        auxGlass.edgeDp = 26f;
        auxGlass.outerDp = 7f;
        auxGlass.outerRefractDp = 12f;
        auxGlass.chroma = 0f;
        auxGlass.vibrance = 1.35f;
        auxGlass.rim = 0.32f;
        auxGlass.edgeDark = 0.32f;
        auxGlass.shadowAlpha = 0.16f;

        stripGlass.blurDp = Ui.barBlur();
        stripGlass.refractDp = 15f;
        stripGlass.edgeDp = 20f;
        stripGlass.outerDp = 5f;
        stripGlass.outerRefractDp = 11f;
        stripGlass.chroma = 0f;
        stripGlass.vibrance = 1.35f;
        stripGlass.rim = 0.32f;

        Runnable inv = () -> { placeField(); invalidate(); };
        collapse.onUpdate = this::invalidate;
        editP.onUpdate = inv;
        swipeX.onUpdate = this::invalidate;
        pressBack.onUpdate = this::invalidate;
        pressPill.onUpdate = this::invalidate;
        pressMore.onUpdate = this::invalidate;

        addressField = new EditText(c);
        addressField.setBackground(null);
        addressField.setSingleLine(true);
        addressField.setIncludeFontPadding(false);
        addressField.setHint(c.getString(R.string.search_hint));
        addressField.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f);
        addressField.setTypeface(Ui.regular);
        addressField.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        addressField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        addressField.setImeOptions(EditorInfo.IME_ACTION_GO);
        addressField.setPadding(0, 0, 0, 0);
        addressField.setVisibility(GONE);
        addressField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable e) {
                if (editing && listener != null) listener.onQueryChanged(e.toString());
                invalidate();
            }
        });
        addressField.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_SEARCH
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                String q = addressField.getText().toString().trim();
                setEditing(false);
                if (!q.isEmpty() && listener != null) listener.onSubmitUrl(q);
                return true;
            }
            return false;
        });
        addView(addressField, new LayoutParams(1, 1));
    }

    void setListener(Listener l) { listener = l; }

    boolean isWideLayout() {
        int w = getWidth() > 0 ? getWidth() : Ui.screenW;
        return (w / Ui.density) >= 600f;
    }

    /** Extra top clearance needed by content/WebView when Safari bar sits at top (wide/foldable/tablet or Top mode). */
    int topContentInsetPx() {
        int base = Ui.statusInset;
        if (isWideLayout()) return base + Ui.dp(100);
        if (Ui.layoutMode == 2) return base + Ui.dp(62);
        return base;
    }

    private boolean collapsedTarget;

    boolean isCollapsed() { return collapsedTarget; }

    /** Dynamic bottom clearance (in CSS dp/px) injected into live webpages so fixed-bottom site UI glides cleanly above the floating glass bar. */
    int floatingBarClearanceDp() {
        if (isWideLayout()) return 0;
        int navDp = Math.round(Ui.navInset / Math.max(1f, Ui.density));
        if (collapsedTarget && Ui.layoutMode != 2) return navDp + 40;
        if (Ui.layoutMode == 1) return navDp + 112; // Classic Bottom (pill + 5-icon bar)
        if (Ui.layoutMode == 2) return navDp + 66;  // Top address bar + bottom 5-icon bar
        return navDp + 72;                          // Compact floating glass pill
    }

    /** Edge-to-edge WebView behind the floating bottom bar (0 bottom cut-off margin!). */
    int bottomContentInsetPx() {
        return 0;
    }

    void setMoreHidden(boolean hidden) {
        if (moreHidden == hidden) return;
        moreHidden = hidden;
        invalidate();
    }

    RectF moreBtnAnchor() {
        layoutRects();
        RectF src;
        if (!moreBtn.isEmpty()) {
            src = new RectF(moreBtn);
        } else if (!wideRightPill.isEmpty()) {
            float s = wideRightPill.height();
            src = new RectF(wideRightPill.right - s, wideRightPill.top, wideRightPill.right, wideRightPill.bottom);
        } else if (!bottomToolbar.isEmpty()) {
            float s = bottomToolbar.height();
            src = new RectF(bottomToolbar.right - s, bottomToolbar.top, bottomToolbar.right, bottomToolbar.bottom);
        } else {
            float sz = Ui.dpf(48);
            src = new RectF(getWidth() - Ui.dpf(14) - sz, getHeight() - Ui.navInset - Ui.dpf(12) - sz,
                    getWidth() - Ui.dpf(14), getHeight() - Ui.navInset - Ui.dpf(12));
        }
        return src;
    }

    RectF pageBtnAnchor() {
        layoutRects();
        float s = Math.max(Ui.dpf(40), pill.height());
        return new RectF(pill.left, pill.top, pill.left + s, pill.bottom);
    }

    void updateTabList(List<TabManager.Tab> list, int activeIdx) {
        tabTitles.clear();
        if (list != null) {
            for (TabManager.Tab t : list) {
                String lbl = (t.title != null && !t.title.isEmpty()) ? t.title : "시작 페이지";
                tabTitles.add(lbl);
            }
        }
        this.activeTabIdx = Math.max(0, Math.min(Math.max(0, tabTitles.size() - 1), activeIdx));
        invalidate();
    }

    void updateState(TabManager.Tab tab, boolean canB, boolean canF, int count) {
        this.canBack = canB;
        this.canForward = canF;
        this.tabCount = Math.max(1, count);
        if (tab == null || tab.isStartPage()) {
            this.fullUrl = "";
            this.domainText = "";
            this.loading = false;
            this.loadProgress = 100;
            this.secureHttps = false;
            this.vpnSite = false;
            this.pageTranslated = false;
        } else {
            this.fullUrl = tab.url;
            this.domainText = BrowserStore.domainOf(tab.url);
            this.loading = tab.loading;
            this.loadProgress = tab.progress;
            this.secureHttps = tab.url != null && tab.url.startsWith("https://");
            this.vpnSite = SecurityGuard.requiresVpnForUrl(tab.url);
            this.pageTranslated = tab.translated;
        }
        invalidate();
    }

    void setProgress(int p) {
        this.loadProgress = p;
        this.loading = p > 0 && p < 100;
        invalidate();
    }

    void setCollapsed(boolean min) {
        if (editing || Ui.layoutMode == 2 || isWideLayout()) min = false;
        collapsedTarget = min;
        collapse.to(min ? 1f : 0f);
    }

    void setImeOffset(int px) {
        imeOffset = px;
        placeField();
        invalidate();
    }

    void setEditing(boolean on) {
        if (editing == on) return;
        editing = on;
        if (on) {
            collapse.to(0f);
            editP.to(1f);
            addressField.setVisibility(VISIBLE);
            addressField.setTextColor(Ui.text());
            addressField.setHintTextColor(Ui.text2());
            addressField.setText(fullUrl);
            addressField.selectAll();
            addressField.requestFocus();
            showIme();
        } else {
            editP.to(0f);
            addressField.clearFocus();
            addressField.setVisibility(GONE);
            hideIme();
        }
        placeField();
        if (listener != null) listener.onEditModeChanged(on);
        invalidate();
    }

    private void showIme() {
        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(addressField, InputMethodManager.SHOW_IMPLICIT);
    }

    private void hideIme() {
        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(addressField.getWindowToken(), 0);
    }

    private void layoutRects() {
        int W = getWidth(), H = getHeight();
        if (W <= 0 || H <= 0) return;

        float pad = Ui.dpf(14);
        float gap = Ui.dpf(9);
        float bottomY = H - Ui.navInset - Ui.dpf(12) - imeOffset;
        float k = collapse.value;
        float ep = Ui.clamp(editP.value, 0f, 1f);

        backBtn.setEmpty();
        moreBtn.setEmpty();
        bottomToolbar.setEmpty();
        wideLeftPill.setEmpty();
        wideRightPill.setEmpty();
        wideStripBg.setEmpty();
        tabChipRects.clear();
        addTabChipRect.setEmpty();

        // ---- Foldable / Tablet Wide iPadOS 26 Safari Layout (>= 600dp) ----
        if (isWideLayout()) {
            float topY = Math.max(Ui.statusInset, Ui.dp(10)) + Ui.dpf(8);
            float barH = Ui.dpf(44);
            float sidePad = Ui.dpf(18);

            if (editing) {
                float pW = Math.min(W - sidePad * 2f, Ui.dpf(640));
                pill.set((W - pW) / 2f, topY, (W + pW) / 2f, topY + barH);
                return;
            }

            float leftW = Ui.dpf(92);   // [< | >]
            float rightW = Ui.dpf(136); // [Share | Tabs | ...]
            wideLeftPill.set(sidePad, topY, sidePad + leftW, topY + barH);
            wideRightPill.set(W - sidePad - rightW, topY, W - sidePad, topY + barH);

            float availW = wideRightPill.left - wideLeftPill.right - Ui.dpf(24);
            float pW = Math.max(Ui.dpf(120), Math.min(availW, Ui.dpf(540)));
            float centerX = (wideLeftPill.right + wideRightPill.left) / 2f;
            float pL = centerX - pW / 2f + swipeX.value * 0.5f;
            pL = Ui.clamp(pL, wideLeftPill.right + Ui.dpf(10), wideRightPill.left - Ui.dpf(10) - pW);
            pill.set(pL, topY, pL + pW, topY + barH);

            // Live horizontal tab strip below the toolbar
            float stripTop = topY + barH + Ui.dpf(8);
            float stripH = Ui.dpf(34);
            wideStripBg.set(sidePad, stripTop, W - sidePad, stripTop + stripH);

            float plusSz = stripH;
            addTabChipRect.set(wideStripBg.right - plusSz, stripTop, wideStripBg.right, stripTop + stripH);
            float chipsAvail = addTabChipRect.left - wideStripBg.left - Ui.dpf(6);
            int shown = Math.min(Math.max(1, tabTitles.size()), 7);
            float chipGap = Ui.dpf(6);
            float chipW = Math.min(Ui.dpf(190), (chipsAvail - chipGap * Math.max(0, shown - 1)) / shown);
            float cx = wideStripBg.left;
            for (int i = 0; i < shown; i++) {
                tabChipRects.add(new RectF(cx, stripTop, cx + chipW, stripTop + stripH));
                cx += chipW + chipGap;
            }
            return;
        }

        // ---- Phone Layouts ----
        if (Ui.layoutMode == 0) {
            // iOS 26 Safari Compact Layout: [<]  [  domain.com  ↻ ]  [...]
            float btnSz = Ui.dpf(50);
            float fullH = Ui.dpf(50);
            float minH = Ui.dpf(30);
            float h = Ui.lerp(fullH, minH, k);
            float bY = Ui.lerp(bottomY, H - Ui.navInset - Ui.dpf(6), k);
            float top = bY - h;

            float expLeft = Ui.lerp(pad + btnSz + gap, pad, ep);
            float expRight = Ui.lerp(W - pad - btnSz - gap, W - pad, ep);
            float minW = Math.min(W * 0.44f, Ui.dpf(178));
            float pL = Ui.lerp(expLeft, (W - minW) / 2f, k) + swipeX.value;
            float pR = Ui.lerp(expRight, (W + minW) / 2f, k) + swipeX.value;
            pill.set(pL, top, pR, bY);

            float vis = (1f - k) * (1f - ep);
            if (vis > 0.15f) {
                float growL = Ui.dpf(3.5f) * pressBack.value;
                float growR = Ui.dpf(3.5f) * pressMore.value;
                float bs = btnSz * Ui.lerp(0.65f, 1f, vis);
                float cy = top + h / 2f;
                backBtn.set(pad - growL, cy - bs / 2f - growL, pad + bs + growL, cy + bs / 2f + growL);
                moreBtn.set(W - pad - bs - growR, cy - bs / 2f - growR, W - pad + growR, cy + bs / 2f + growR);
            }
        } else if (Ui.layoutMode == 1) {
            // Classic Bottom Layout
            if (editing) {
                float h = Ui.dpf(50);
                pill.set(pad, bottomY - h, W - pad, bottomY);
                return;
            }
            float barH = Ui.dpf(46);
            float pillH = Ui.lerp(Ui.dpf(46), Ui.dpf(30), k);
            float tTop = Ui.lerp(bottomY - barH, H + Ui.dpf(10), k);
            bottomToolbar.set(pad, tTop, W - pad, tTop + barH);
            float pBot = Ui.lerp(tTop - Ui.dpf(8), bottomY, k);
            float minW = Math.min(W * 0.46f, Ui.dpf(184));
            float pL = Ui.lerp(pad, (W - minW) / 2f, k) + swipeX.value;
            float pR = Ui.lerp(W - pad, (W + minW) / 2f, k) + swipeX.value;
            pill.set(pL, pBot - pillH, pR, pBot);
        } else {
            // Top Layout
            float pillH = Ui.dpf(46);
            float topY = Ui.statusInset + Ui.dpf(8);
            pill.set(pad + swipeX.value, topY, W - pad + swipeX.value, topY + pillH);
            if (!editing) {
                float barH = Ui.dpf(48);
                bottomToolbar.set(pad, bottomY - barH, W - pad, bottomY);
            }
        }
    }

    private void placeField() {
        if (addressField.getVisibility() != VISIBLE) return;
        layoutRects();
        int l = Math.round(pill.left + Ui.dpf(40));
        int t = Math.round(pill.top);
        int r = Math.round(pill.right - Ui.dpf(42));
        int b = Math.round(pill.bottom);
        int w = Math.max(1, r - l), h = Math.max(1, b - t);
        addressField.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
        addressField.layout(l, t, l + w, t + h);
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        layoutRects();
        placeField();
    }

    @Override protected void dispatchDraw(Canvas c) {
        layoutRects();
        int fg = Ui.text(), fg2 = Ui.text2();
        float k = collapse.value;
        float ep = Ui.clamp(editP.value, 0f, 1f);

        // 1. Wide iPadOS 26 Safari Bar + Live Tab Strip
        if (isWideLayout()) {
            mainGlass.tint = Ui.barTint();
            mainGlass.blurDp = Ui.barBlur();
            mainGlass.strokeColor = Ui.glassStroke();
            mainGlass.pressX = touchX; mainGlass.pressY = touchY;
            mainGlass.pressR = Ui.dpf(54);
            mainGlass.pressAmt = Math.max(pressPill.value, Math.max(pressBack.value, pressMore.value));
            mainGlass.smoothK = 0f;
            if (!wideLeftPill.isEmpty() && !wideRightPill.isEmpty()) {
                mainGlass.shapes(pill, pill.height() / 2f,
                        wideLeftPill, wideLeftPill.height() / 2f,
                        wideRightPill, wideRightPill.height() / 2f);
            } else {
                mainGlass.shapes(pill, pill.height() / 2f, null, 0, null, 0);
            }
            mainGlass.alpha = 1f;
            mainGlass.draw(c, this, backdrop);

            if (!wideLeftPill.isEmpty()) {
                float hw = wideLeftPill.width() / 2f;
                Icons.draw(c, Icons.BACK, wideLeftPill.left + hw * 0.5f, wideLeftPill.centerY(), Ui.dpf(18),
                        Ui.alpha(canBack ? fg : fg2, canBack ? 1f : 0.42f));
                Icons.draw(c, Icons.FORWARD, wideLeftPill.left + hw * 1.5f, wideLeftPill.centerY(), Ui.dpf(18),
                        Ui.alpha(canForward ? fg : fg2, canForward ? 1f : 0.42f));
            }
            if (!wideRightPill.isEmpty()) {
                float sw = wideRightPill.width() / 3f;
                Icons.draw(c, Icons.SHARE, wideRightPill.left + sw * 0.5f, wideRightPill.centerY(), Ui.dpf(18), fg);
                Icons.draw(c, Icons.TABS, wideRightPill.left + sw * 1.5f, wideRightPill.centerY(), Ui.dpf(18), fg);
                if (!moreHidden) {
                    Icons.draw(c, Icons.MORE, wideRightPill.left + sw * 2.5f, wideRightPill.centerY(), Ui.dpf(18), fg);
                }
            }
            drawPillContent(c, fg, fg2, 0f);
            drawWideTabStrip(c, fg, fg2);
            super.dispatchDraw(c);
            return;
        }

        // 2. Phone Primary floating glass shapes
        mainGlass.tint = Ui.barTint();
        mainGlass.blurDp = Ui.barBlur();
        mainGlass.strokeColor = Ui.glassStroke();
        mainGlass.pressX = touchX; mainGlass.pressY = touchY;
        mainGlass.pressR = Ui.dpf(54);
        mainGlass.pressAmt = Math.max(pressPill.value, Math.max(pressBack.value, pressMore.value));
        mainGlass.smoothK = Ui.dpf(10) * pressPill.value;

        boolean drawMoreShape = !moreBtn.isEmpty() && !moreHidden;
        if (!backBtn.isEmpty() && drawMoreShape) {
            mainGlass.shapes(pill, pill.height() / 2f, backBtn, backBtn.height() / 2f, moreBtn, moreBtn.height() / 2f);
        } else if (!backBtn.isEmpty()) {
            mainGlass.shapes(pill, pill.height() / 2f, backBtn, backBtn.height() / 2f, null, 0);
        } else {
            mainGlass.shapes(pill, pill.height() / 2f, null, 0, null, 0);
        }
        mainGlass.alpha = 1f;
        mainGlass.draw(c, this, backdrop);

        // 3. Secondary floating glass toolbar (Bottom Classic / Top modes)
        if (!bottomToolbar.isEmpty() && bottomToolbar.top < getHeight()) {
            auxGlass.tint = Ui.barTint();
            auxGlass.blurDp = Ui.barBlur();
            auxGlass.strokeColor = Ui.glassStroke();
            auxGlass.shapes(bottomToolbar, bottomToolbar.height() / 2f, null, 0, null, 0);
            auxGlass.alpha = 1f - k;
            auxGlass.draw(c, this, backdrop);
            drawFiveIconToolbar(c, fg, fg2, 1f - k);
        }

        // 4. Compact mode side icons (< and ...)
        float sideAlpha = (1f - k) * (1f - ep);
        if (!backBtn.isEmpty() && sideAlpha > 0.12f) {
            Icons.draw(c, Icons.BACK, backBtn.centerX(), backBtn.centerY(), Ui.dpf(20),
                    Ui.alpha(canBack ? fg : fg2, (canBack ? 1f : 0.45f) * sideAlpha));
        }
        if (drawMoreShape && sideAlpha > 0.12f) {
            Icons.draw(c, Icons.MORE, moreBtn.centerX(), moreBtn.centerY(), Ui.dpf(20), Ui.alpha(fg, sideAlpha));
        }

        // 5. Address Pill interior
        drawPillContent(c, fg, fg2, k);
        super.dispatchDraw(c);
    }

    private void drawWideTabStrip(Canvas c, int fg, int fg2) {
        if (tabChipRects.isEmpty()) return;
        Paint.FontMetrics fm = chipPaint.getFontMetrics();
        for (int i = 0; i < tabChipRects.size(); i++) {
            RectF r = tabChipRects.get(i);
            boolean active = (i == activeTabIdx);
            chipFillPaint.setStyle(Paint.Style.FILL);
            chipFillPaint.setColor(active
                    ? (Ui.isDarkSurface() ? 0xCC282D3B : 0xEBFFFFFF)
                    : (Ui.isDarkSurface() ? 0x661C1F2A : 0x80FFFFFF));
            c.drawRoundRect(r, r.height() / 2f, r.height() / 2f, chipFillPaint);

            chipFillPaint.setStyle(Paint.Style.STROKE);
            chipFillPaint.setStrokeWidth(Math.max(1f, Ui.dpf(active ? 1.1f : 0.6f)));
            chipFillPaint.setColor(active ? Ui.alpha(Ui.activeAccent(), 0.55f) : Ui.glassStroke());
            c.drawRoundRect(r, r.height() / 2f, r.height() / 2f, chipFillPaint);

            String title = i < tabTitles.size() ? tabTitles.get(i) : "탭";
            chipPaint.setTypeface(active ? Ui.semibold : Ui.regular);
            chipPaint.setColor(active ? fg : fg2);
            chipPaint.setTextAlign(Paint.Align.LEFT);
            boolean showClose = tabTitles.size() > 1 || active;
            float maxW = Math.max(Ui.dpf(24), r.width() - Ui.dpf(showClose ? 34 : 18));
            CharSequence ell = TextUtils.ellipsize(title, chipPaint, maxW, TextUtils.TruncateAt.END);
            float ty = r.centerY() - (fm.ascent + fm.descent) / 2f;
            c.drawText(ell, 0, ell.length(), r.left + Ui.dpf(12), ty, chipPaint);

            if (showClose && tabTitles.size() > 1) {
                Icons.draw(c, Icons.CLOSE, r.right - Ui.dpf(14), r.centerY(), Ui.dpf(9.5f), fg2);
            }
        }
        if (!addTabChipRect.isEmpty()) {
            chipFillPaint.setStyle(Paint.Style.FILL);
            chipFillPaint.setColor(Ui.isDarkSurface() ? 0x881E212C : 0xB8FFFFFF);
            c.drawRoundRect(addTabChipRect, addTabChipRect.height() / 2f, addTabChipRect.height() / 2f, chipFillPaint);
            chipFillPaint.setStyle(Paint.Style.STROKE);
            chipFillPaint.setStrokeWidth(Math.max(1f, Ui.dpf(0.6f)));
            chipFillPaint.setColor(Ui.glassStroke());
            c.drawRoundRect(addTabChipRect, addTabChipRect.height() / 2f, addTabChipRect.height() / 2f, chipFillPaint);
            Icons.draw(c, Icons.PLUS, addTabChipRect.centerX(), addTabChipRect.centerY(), Ui.dpf(14), fg);
        }
    }

    private void drawPillContent(Canvas c, int fg, int fg2, float k) {
        if (editing) {
            Icons.draw(c, Ui.privateTheme ? Icons.PRIVATE : Icons.SEARCH,
                    pill.left + Ui.dpf(22), pill.centerY(), Ui.dpf(18), Ui.activeAccent());
            Icons.draw(c, Icons.CLOSE, pill.right - Ui.dpf(22), pill.centerY(), Ui.dpf(16), fg2);
            return;
        }

        if (loading && loadProgress > 0 && loadProgress < 100) {
            float frac = Ui.clamp(loadProgress / 100f, 0.08f, 1f);
            float inset = Ui.dpf(18);
            float pw = Math.max(Ui.dpf(12), (pill.width() - inset * 2f) * frac);
            progPaint.setColor(Ui.activeAccent());
            c.drawRoundRect(pill.left + inset, pill.bottom - Ui.dpf(2.6f), pill.left + inset + pw, pill.bottom - Ui.dpf(0.4f), Ui.dpf(2), Ui.dpf(2), progPaint);
        }

        float fullA = 1f - k;
        if (fullA > 0.15f) {
            int leftIcon = Ui.privateTheme ? Icons.PRIVATE
                    : (pageTranslated ? Icons.TRANSLATE : (vpnSite ? Icons.VPN : (domainText.isEmpty() ? Icons.SEARCH : Icons.PAGE_MENU)));
            int leftCol = (Ui.privateTheme || pageTranslated) ? Ui.activeAccent() : (vpnSite ? 0xFF34C759 : fg2);
            Icons.draw(c, leftIcon, pill.left + Ui.dpf(22), pill.centerY(), Ui.dpf(17), Ui.alpha(leftCol, fullA));
            if (!domainText.isEmpty()) {
                Icons.draw(c, loading ? Icons.CLOSE : Icons.RELOAD, pill.right - Ui.dpf(22), pill.centerY(), Ui.dpf(16.5f), Ui.alpha(fg2, fullA));
            }
        }

        String display = domainText.isEmpty() ? getContext().getString(R.string.search_hint) : domainText;
        domainPaint.setTextSize(Ui.sp(Ui.lerp(14.5f, 11.5f, k)));
        domainPaint.setTypeface(domainText.isEmpty() ? Ui.regular : Ui.semibold);
        domainPaint.setColor(domainText.isEmpty() ? fg2 : fg);
        float maxW = Math.max(Ui.dpf(40), pill.width() - Ui.dpf(fullA > 0.2f ? 84 : 24));
        CharSequence ell = TextUtils.ellipsize(display, domainPaint, maxW, TextUtils.TruncateAt.END);
        Paint.FontMetrics fm = domainPaint.getFontMetrics();
        float ty = pill.centerY() - (fm.ascent + fm.descent) / 2f;
        c.drawText(ell, 0, ell.length(), pill.centerX(), ty, domainPaint);

        if (secureHttps && !domainText.isEmpty() && k < 0.5f) {
            float tw = domainPaint.measureText(ell, 0, ell.length());
            float lx = pill.centerX() - tw / 2f - Ui.dpf(10);
            if (lx > pill.left + Ui.dpf(38)) {
                Icons.draw(c, Icons.LOCK, lx, pill.centerY(), Ui.dpf(11), Ui.alpha(fg2, 0.85f * fullA));
            }
        }
    }

    private void drawFiveIconToolbar(Canvas c, int fg, int fg2, float alpha) {
        if (alpha <= 0.05f) return;
        float sw = bottomToolbar.width() / 5f;
        float cy = bottomToolbar.centerY();
        int[] icons = {Icons.BACK, Icons.FORWARD, Icons.SHARE, Icons.BOOKMARK, Icons.TABS};
        for (int i = 0; i < 5; i++) {
            float cx = bottomToolbar.left + sw * (i + 0.5f);
            boolean dim = (i == 0 && !canBack) || (i == 1 && !canForward);
            int col = Ui.alpha(dim ? fg2 : fg, (dim ? 0.42f : 1f) * alpha);
            Icons.draw(c, icons[i], cx, cy, Ui.dpf(20), col);
            if (i == 4 && tabCount > 1) {
                badgePaint.setColor(Ui.alpha(Ui.activeAccent(), alpha));
                c.drawText(String.valueOf(Math.min(99, tabCount)), cx - Ui.dpf(1.5f), cy + Ui.dpf(5), badgePaint);
            }
        }
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = touchX = x; downY = touchY = y;
                swipingPill = false;
                touchedChip = -1;
                touchedChipClose = false;
                if (editing) {
                    if (pill.contains(x, y) && x > pill.right - Ui.dpf(44)) {
                        if (addressField.getText().length() > 0) addressField.setText("");
                        else setEditing(false);
                        return true;
                    }
                    if (!pill.contains(x, y)) {
                        // Let suggestionOverlay handle taps on suggestion rows or blank backdrop dismissal
                        return false;
                    }
                    return true;
                }
                if (isWideLayout()) {
                    if (!wideLeftPill.isEmpty() && hit(wideLeftPill, x, y)) {
                        touch = T_WIDE_LEFT;
                        pressBack.to(1f);
                        if (x < wideLeftPill.centerX()) postDelayed(backLongPressRun, 420);
                        return true;
                    }
                    if (!wideRightPill.isEmpty() && hit(wideRightPill, x, y)) {
                        touch = T_WIDE_RIGHT;
                        pressMore.to(1f);
                        return true;
                    }
                    if (!addTabChipRect.isEmpty() && hit(addTabChipRect, x, y)) {
                        touch = T_ADD_TAB;
                        return true;
                    }
                    for (int i = 0; i < tabChipRects.size(); i++) {
                        RectF r = tabChipRects.get(i);
                        if (r.contains(x, y)) {
                            touch = T_TAB_CHIP;
                            touchedChip = i;
                            touchedChipClose = tabTitles.size() > 1 && x > r.right - Ui.dpf(26);
                            return true;
                        }
                    }
                    if (hit(pill, x, y)) {
                        touch = T_PILL;
                        pressPill.to(1f);
                        return true;
                    }
                    touch = T_NONE;
                    return false;
                }
                if (!backBtn.isEmpty() && hit(backBtn, x, y)) {
                    touch = T_BACK;
                    pressBack.to(1f);
                    postDelayed(backLongPressRun, 420);
                    return true;
                }
                if (!moreBtn.isEmpty() && hit(moreBtn, x, y)) {
                    touch = T_MORE;
                    pressMore.to(1f);
                    return true;
                }
                if (!bottomToolbar.isEmpty() && hit(bottomToolbar, x, y)) {
                    touch = T_TOOLBAR;
                    return true;
                }
                if (hit(pill, x, y)) {
                    touch = T_PILL;
                    pressPill.to(1f);
                    return true;
                }
                touch = T_NONE;
                return false;

            case MotionEvent.ACTION_MOVE:
                touchX = x; touchY = y;
                if (touch == T_PILL && collapse.value < 0.5f && !isWideLayout()) {
                    float dx = x - downX, dy = y - downY;
                    if (Math.abs(dx) > Ui.dpf(12) && Math.abs(dx) > Math.abs(dy)) {
                        swipingPill = true;
                        swipeX.snap(Ui.clamp(dx * 0.55f, -Ui.dpf(86), Ui.dpf(86)));
                    } else if (dy < -Ui.dpf(42) && Math.abs(dy) > Math.abs(dx) * 1.2f) {
                        touch = T_NONE;
                        pressPill.to(0f);
                        swipeX.to(0f);
                        if (listener != null) listener.onOpenTabs();
                    }
                    return true;
                }
                if ((touch == T_BACK || touch == T_WIDE_LEFT) && Math.hypot(x - downX, y - downY) > Ui.dpf(14)) {
                    removeCallbacks(backLongPressRun);
                }
                return touch != T_NONE;

            case MotionEvent.ACTION_UP: {
                removeCallbacks(backLongPressRun);
                int t = touch;
                touch = T_NONE;
                pressBack.to(0f); pressPill.to(0f); pressMore.to(0f);

                if (t == T_WIDE_LEFT && listener != null) {
                    if (x < wideLeftPill.centerX()) listener.onBack();
                    else listener.onForward();
                } else if (t == T_WIDE_RIGHT && listener != null) {
                    int seg = Math.max(0, Math.min(2, (int) ((x - wideRightPill.left) / (wideRightPill.width() / 3f))));
                    if (seg == 0) listener.onShare();
                    else if (seg == 1) listener.onOpenTabs();
                    else listener.onMoreMenu(moreBtnAnchor());
                } else if (t == T_ADD_TAB && listener != null) {
                    listener.onNewTabInline();
                } else if (t == T_TAB_CHIP && listener != null && touchedChip >= 0) {
                    if (touchedChipClose) listener.onCloseTabIndex(touchedChip);
                    else listener.onSelectTabIndex(touchedChip);
                } else if (t == T_BACK && listener != null) {
                    listener.onBack();
                } else if (t == T_MORE && listener != null) {
                    long now = System.currentTimeMillis();
                    if (now - lastMoreTapMs < 320) {
                        lastMoreTapMs = 0;
                        listener.onOpenTabs();
                    } else {
                        lastMoreTapMs = now;
                        listener.onMoreMenu(moreBtnAnchor());
                    }
                } else if (t == T_TOOLBAR && listener != null && !bottomToolbar.isEmpty()) {
                    int idx = Math.max(0, Math.min(4, (int) ((x - bottomToolbar.left) / (bottomToolbar.width() / 5f))));
                    if (idx == 0) listener.onBack();
                    else if (idx == 1) listener.onForward();
                    else if (idx == 2) listener.onShare();
                    else if (idx == 3) listener.onBookmarks();
                    else listener.onOpenTabs();
                } else if (t == T_PILL) {
                    float dx = x - downX;
                    swipeX.to(0f);
                    if (swipingPill && Math.abs(dx) > Ui.dpf(48)) {
                        if (listener != null) listener.onSwipeTabDelta(dx < 0 ? 1 : -1);
                    } else if (collapse.value > 0.4f) {
                        setCollapsed(false);
                    } else if (!domainText.isEmpty() && x < pill.left + Ui.dpf(40)) {
                        if (listener != null) listener.onPageMenu(pageBtnAnchor());
                    } else if (!domainText.isEmpty() && x > pill.right - Ui.dpf(40)) {
                        if (listener != null) listener.onReloadOrStop();
                    } else {
                        setEditing(true);
                    }
                }
                swipingPill = false;
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(backLongPressRun);
                touch = T_NONE;
                swipingPill = false;
                pressBack.to(0f); pressPill.to(0f); pressMore.to(0f);
                swipeX.to(0f);
                return true;
        }
        return false;
    }

    private boolean hit(RectF r, float x, float y) {
        float pad = Ui.dpf(6);
        return x >= r.left - pad && x <= r.right + pad && y >= r.top - pad && y <= r.bottom + pad;
    }
}
