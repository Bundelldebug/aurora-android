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

/**
 * Authentic iOS 26/27 Apple Safari Liquid Glass Floating Bar:
 * - Compact mode (default): detached '<' glass circle + floating address pill + detached '...' circle
 * - Bottom Classic mode: floating address pill over floating 5-icon glass bar
 * - Top mode: floating address pill at top + compact floating 5-icon bar at bottom
 * Full iOS 26 Safari gestures:
 * - Horizontal swipe across pill switches tabs (swipe past last tab opens a New Tab)
 * - Upward swipe on pill opens Tab Overview
 * - Long-press '<' opens history/forward; double-tap '...' opens Tab Overview
 * - Scroll-to-minimize shrinks pill into an ultra-clean translucent domain capsule
 */
final class SafariBar extends FrameLayout {
    interface Listener {
        void onBack();
        void onBackLongPress();
        void onForward();
        void onMoreMenu();
        void onPageMenu();
        void onOpenTabs();
        void onShare();
        void onBookmarks();
        void onReloadOrStop();
        void onSubmitUrl(String input);
        void onQueryChanged(String query);
        void onEditModeChanged(boolean editing);
        void onSwipeTabDelta(int delta); // -1 prev, +1 next / new tab
    }

    private final AuroraGlass.Backdrop backdrop;
    private Listener listener;

    private final AuroraGlass.Surface mainGlass = new AuroraGlass.Surface();
    private final AuroraGlass.Surface auxGlass = new AuroraGlass.Surface();

    final RectF backBtn = new RectF(), pill = new RectF(), moreBtn = new RectF();
    final RectF bottomToolbar = new RectF();
    private final RectF tmp = new RectF();

    private final Ui.Spring collapse = new Ui.Spring(320f, 0.84f, 0.002f);
    private final Ui.Spring swipeX = new Ui.Spring(440f, 0.78f, 0.5f);
    private final Ui.Spring pressBack = new Ui.Spring(620f, 0.82f, 0.002f);
    private final Ui.Spring pressPill = new Ui.Spring(620f, 0.82f, 0.002f);
    private final Ui.Spring pressMore = new Ui.Spring(620f, 0.82f, 0.002f);

    final EditText addressField;
    boolean editing;
    private int imeOffset;

    private String domainText = "";
    private String fullUrl = "";
    private int loadProgress = 100;
    private boolean loading;
    private boolean canBack, canForward;
    private boolean secureHttps;
    private int tabCount = 1;

    private final TextPaint domainPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint badgePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint progPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private static final int T_NONE = 0, T_BACK = 1, T_PILL = 2, T_MORE = 3, T_TOOLBAR = 4;
    private int touch = T_NONE;
    private float downX, downY, touchX, touchY;
    private boolean swipingPill;
    private long lastMoreTapMs;
    private final Runnable backLongPressRun = () -> {
        if (touch == T_BACK && listener != null) {
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

        mainGlass.blurDp = 8f;
        mainGlass.refractDp = 16f;
        mainGlass.edgeDp = 24f;
        mainGlass.outerDp = 6.5f;
        mainGlass.outerRefractDp = 11f;
        mainGlass.chroma = 0.34f;
        mainGlass.rim = 0.38f;
        mainGlass.fresnelAmt = 0.56f;

        auxGlass.blurDp = 8f;
        auxGlass.refractDp = 15f;
        auxGlass.edgeDp = 22f;
        auxGlass.outerDp = 6f;
        auxGlass.outerRefractDp = 10f;
        auxGlass.chroma = 0.28f;
        auxGlass.rim = 0.36f;

        Runnable inv = this::invalidate;
        collapse.onUpdate = inv;
        swipeX.onUpdate = inv;
        pressBack.onUpdate = inv;
        pressPill.onUpdate = inv;
        pressMore.onUpdate = inv;

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
        } else {
            this.fullUrl = tab.url;
            this.domainText = BrowserStore.domainOf(tab.url);
            this.loading = tab.loading;
            this.loadProgress = tab.progress;
            this.secureHttps = tab.url != null && tab.url.startsWith("https://");
        }
        invalidate();
    }

    void setProgress(int p) {
        this.loadProgress = p;
        this.loading = p > 0 && p < 100;
        invalidate();
    }

    void setCollapsed(boolean min) {
        if (editing || Ui.layoutMode == 2) min = false;
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
            addressField.setVisibility(VISIBLE);
            addressField.setTextColor(Ui.text());
            addressField.setHintTextColor(Ui.text2());
            addressField.setText(fullUrl);
            addressField.selectAll();
            addressField.requestFocus();
            showIme();
        } else {
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

        backBtn.setEmpty();
        moreBtn.setEmpty();
        bottomToolbar.setEmpty();

        if (editing) {
            float h = Ui.dpf(50);
            float top = Ui.layoutMode == 2 ? (Ui.statusInset + Ui.dpf(10)) : (bottomY - h);
            pill.set(pad, top, W - pad, top + h);
            return;
        }

        if (Ui.layoutMode == 0) {
            // iOS 26 Safari Compact Layout: [<]  [  apple.com  ↻ ]  [...]
            float btnSz = Ui.dpf(50);
            float fullH = Ui.dpf(50);
            float minH = Ui.dpf(30);
            float h = Ui.lerp(fullH, minH, k);
            float bY = Ui.lerp(bottomY, H - Ui.navInset - Ui.dpf(6), k);
            float top = bY - h;

            float expLeft = pad + btnSz + gap;
            float expRight = W - pad - btnSz - gap;
            float minW = Math.min(W * 0.44f, Ui.dpf(178));
            float pL = Ui.lerp(expLeft, (W - minW) / 2f, k) + swipeX.value;
            float pR = Ui.lerp(expRight, (W + minW) / 2f, k) + swipeX.value;
            pill.set(pL, top, pR, bY);

            if (k < 0.85f) {
                float growL = Ui.dpf(3.5f) * pressBack.value;
                float growR = Ui.dpf(3.5f) * pressMore.value;
                float bScale = 1f - k * 0.4f;
                float bs = btnSz * bScale;
                float cy = top + h / 2f;
                backBtn.set(pad - growL, cy - bs / 2f - growL, pad + bs + growL, cy + bs / 2f + growL);
                moreBtn.set(W - pad - bs - growR, cy - bs / 2f - growR, W - pad + growR, cy + bs / 2f + growR);
            }
        } else if (Ui.layoutMode == 1) {
            // Classic Bottom Layout: floating pill above 5-icon glass bar
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
            // Top Layout: floating address pill at top + floating 5-icon toolbar at bottom
            float pillH = Ui.dpf(46);
            float topY = Ui.statusInset + Ui.dpf(8);
            pill.set(pad + swipeX.value, topY, W - pad + swipeX.value, topY + pillH);
            float barH = Ui.dpf(48);
            bottomToolbar.set(pad, bottomY - barH, W - pad, bottomY);
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

        // 1. Primary floating glass shapes (pill + detached compact circles smooth-unioned on touch)
        mainGlass.tint = Ui.barTint();
        mainGlass.strokeColor = Ui.glassStroke();
        mainGlass.pressX = touchX; mainGlass.pressY = touchY;
        mainGlass.pressR = Ui.dpf(54);
        mainGlass.pressAmt = Math.max(pressPill.value, Math.max(pressBack.value, pressMore.value));
        mainGlass.smoothK = Ui.dpf(10) * pressPill.value;

        if (!backBtn.isEmpty() && !moreBtn.isEmpty()) {
            mainGlass.shapes(pill, pill.height() / 2f, backBtn, backBtn.height() / 2f, moreBtn, moreBtn.height() / 2f);
        } else {
            mainGlass.shapes(pill, pill.height() / 2f, null, 0, null, 0);
        }
        mainGlass.alpha = 1f;
        mainGlass.draw(c, this, backdrop);

        // 2. Secondary floating glass toolbar (Bottom Classic / Top modes)
        if (!bottomToolbar.isEmpty() && bottomToolbar.top < getHeight()) {
            auxGlass.tint = Ui.barTint();
            auxGlass.strokeColor = Ui.glassStroke();
            auxGlass.shapes(bottomToolbar, bottomToolbar.height() / 2f, null, 0, null, 0);
            auxGlass.alpha = 1f - k;
            auxGlass.draw(c, this, backdrop);
            drawFiveIconToolbar(c, fg, fg2, 1f - k);
        }

        // 3. Compact mode side icons (< and ...)
        if (!backBtn.isEmpty() && k < 0.85f) {
            float a = 1f - k;
            Icons.draw(c, Icons.BACK, backBtn.centerX(), backBtn.centerY(), Ui.dpf(20), Ui.alpha(canBack ? fg : fg2, (canBack ? 1f : 0.45f) * a));
        }
        if (!moreBtn.isEmpty() && k < 0.85f) {
            float a = 1f - k;
            Icons.draw(c, Icons.MORE, moreBtn.centerX(), moreBtn.centerY(), Ui.dpf(20), Ui.alpha(fg, a));
        }

        // 4. Address Pill interior
        drawPillContent(c, fg, fg2, k);

        super.dispatchDraw(c);
    }

    private void drawPillContent(Canvas c, int fg, int fg2, float k) {
        if (editing) {
            Icons.draw(c, Ui.privateTheme ? Icons.PRIVATE : Icons.SEARCH,
                    pill.left + Ui.dpf(22), pill.centerY(), Ui.dpf(18), Ui.activeAccent());
            Icons.draw(c, Icons.CLOSE, pill.right - Ui.dpf(22), pill.centerY(), Ui.dpf(16), fg2);
            return;
        }

        // Subtle Safari loading progress bar along the bottom edge of the pill
        if (loading && loadProgress > 0 && loadProgress < 100) {
            float frac = Ui.clamp(loadProgress / 100f, 0.08f, 1f);
            float inset = Ui.dpf(18);
            float pw = Math.max(Ui.dpf(12), (pill.width() - inset * 2f) * frac);
            progPaint.setColor(Ui.activeAccent());
            c.drawRoundRect(pill.left + inset, pill.bottom - Ui.dpf(2.6f), pill.left + inset + pw, pill.bottom - Ui.dpf(0.4f), Ui.dpf(2), Ui.dpf(2), progPaint);
        }

        float fullA = 1f - k;
        if (fullA > 0.15f) {
            // Left: Page Menu / Private Shield icon
            int leftIcon = Ui.privateTheme ? Icons.PRIVATE : (domainText.isEmpty() ? Icons.SEARCH : Icons.PAGE_MENU);
            Icons.draw(c, leftIcon, pill.left + Ui.dpf(22), pill.centerY(), Ui.dpf(17),
                    Ui.alpha(Ui.privateTheme ? Ui.activeAccent() : fg2, fullA));
            // Right: Reload / Stop icon (when on a website)
            if (!domainText.isEmpty()) {
                Icons.draw(c, loading ? Icons.CLOSE : Icons.RELOAD, pill.right - Ui.dpf(22), pill.centerY(), Ui.dpf(16.5f), Ui.alpha(fg2, fullA));
            }
        }

        // Centered Domain or Hint text
        String display = domainText.isEmpty() ? getContext().getString(R.string.search_hint) : domainText;
        domainPaint.setTextSize(Ui.sp(Ui.lerp(14.5f, 11.5f, k)));
        domainPaint.setTypeface(domainText.isEmpty() ? Ui.regular : Ui.semibold);
        domainPaint.setColor(domainText.isEmpty() ? fg2 : fg);
        float maxW = Math.max(Ui.dpf(40), pill.width() - Ui.dpf(fullA > 0.2f ? 84 : 24));
        CharSequence ell = TextUtils.ellipsize(display, domainPaint, maxW, TextUtils.TruncateAt.END);
        Paint.FontMetrics fm = domainPaint.getFontMetrics();
        float ty = pill.centerY() - (fm.ascent + fm.descent) / 2f;
        c.drawText(ell, 0, ell.length(), pill.centerX(), ty, domainPaint);

        // Lock dot indicator right before HTTPS domain
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
                if (editing) {
                    if (pill.contains(x, y) && x > pill.right - Ui.dpf(44)) {
                        if (addressField.getText().length() > 0) addressField.setText("");
                        else setEditing(false);
                        return true;
                    }
                    if (!pill.contains(x, y)) {
                        setEditing(false);
                        return false;
                    }
                    return true;
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
                if (touch == T_PILL && collapse.value < 0.5f) {
                    float dx = x - downX, dy = y - downY;
                    if (Math.abs(dx) > Ui.dpf(12) && Math.abs(dx) > Math.abs(dy)) {
                        swipingPill = true;
                        swipeX.snap(Ui.clamp(dx * 0.55f, -Ui.dpf(86), Ui.dpf(86)));
                    } else if (dy < -Ui.dpf(42) && Math.abs(dy) > Math.abs(dx) * 1.2f) {
                        // Swipe up on Safari pill -> Spatial Tab Overview!
                        touch = T_NONE;
                        pressPill.to(0f);
                        swipeX.to(0f);
                        if (listener != null) listener.onOpenTabs();
                    }
                    return true;
                }
                if (touch == T_BACK && Math.hypot(x - downX, y - downY) > Ui.dpf(14)) {
                    removeCallbacks(backLongPressRun);
                }
                return touch != T_NONE;

            case MotionEvent.ACTION_UP: {
                removeCallbacks(backLongPressRun);
                int t = touch;
                touch = T_NONE;
                pressBack.to(0f); pressPill.to(0f); pressMore.to(0f);

                if (t == T_BACK && listener != null) {
                    listener.onBack();
                } else if (t == T_MORE && listener != null) {
                    long now = System.currentTimeMillis();
                    if (now - lastMoreTapMs < 320) {
                        lastMoreTapMs = 0;
                        listener.onOpenTabs();
                    } else {
                        lastMoreTapMs = now;
                        listener.onMoreMenu();
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
                        if (listener != null) listener.onPageMenu();
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
