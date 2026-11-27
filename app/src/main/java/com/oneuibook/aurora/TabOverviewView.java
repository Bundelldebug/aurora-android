package com.oneuibook.aurora;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RecordingCanvas;
import android.graphics.RectF;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * Authentic iOS 26 / iPadOS 26 Safari Spatial Tab Overview:
 * - Responsive 2 (phone) / 3 (foldable) / 4 (tablet) columns with staggered entrance animations.
 * - Critically damped bottom Liquid Glass control bar (no bounce).
 */
final class TabOverviewView extends FrameLayout {
    interface Listener {
        void onSelectTab(boolean priv, int index);
        void onCloseTab(boolean priv, int index);
        void onNewTab(boolean priv);
        void onSwitchPrivate(boolean priv);
        void onCloseAllPrivate();
        void onDone();
    }

    private final AuroraGlass.Backdrop backdrop;
    private final TabManager tabs;
    private final Listener listener;
    private final ScrollView scroll;
    private final LinearLayout topRow;
    private final LinearLayout rowsHost;
    private final TextView topTitle, clearPrivBtn;
    private final BottomControlBar controlBar;

    TabOverviewView(Context c, AuroraGlass.Backdrop backdrop, TabManager tabs, Listener listener) {
        super(c);
        this.backdrop = backdrop;
        this.tabs = tabs;
        this.listener = listener;
        setVisibility(GONE);

        topRow = new LinearLayout(c);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);
        topRow.setPadding(Ui.dp(20), Math.max(Ui.statusInset, Ui.dp(24)) + Ui.dp(14), Ui.dp(20), Ui.dp(10));

        topTitle = Ui.text(c, "탭", 26, Ui.bold, Ui.text());
        topRow.addView(topTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        clearPrivBtn = Ui.text(c, "모두 닫기", 13.5f, Ui.semibold, 0xFFFF453A);
        clearPrivBtn.setPadding(Ui.dp(12), Ui.dp(6), Ui.dp(12), Ui.dp(6));
        clearPrivBtn.setBackground(Ui.round(0x20FF453A, 100));
        clearPrivBtn.setVisibility(GONE);
        clearPrivBtn.setOnClickListener(v -> listener.onCloseAllPrivate());
        topRow.addView(clearPrivBtn);

        scroll = new ScrollView(c);
        scroll.setClipToPadding(false);
        scroll.setPadding(Ui.dp(14), Ui.statusInset + Ui.dp(66), Ui.dp(14), Ui.navInset + Ui.dp(96));
        rowsHost = new LinearLayout(c);
        rowsHost.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(rowsHost, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(scroll, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        addView(topRow, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        controlBar = new BottomControlBar(c);
        addView(controlBar, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(96) + Ui.navInset, Gravity.BOTTOM));
    }

    void applyInsets() {
        int safeTop = Math.max(Ui.statusInset, Ui.dp(24));
        int safeBot = Math.max(Ui.navInset, Ui.dp(12));
        int hPad = Ui.isWide() ? Ui.dp(28) : Ui.dp(14);
        topRow.setPadding(Ui.dp(20), safeTop + Ui.dp(14), Ui.dp(20), Ui.dp(10));
        scroll.setPadding(hPad, safeTop + Ui.dp(68), hPad, safeBot + Ui.dp(98));
        if (controlBar.getLayoutParams() != null) {
            controlBar.getLayoutParams().height = Ui.dp(96) + safeBot;
            controlBar.requestLayout();
        }
    }

    void refresh() {
        applyInsets();
        setBackgroundColor(Ui.bg());
        boolean priv = tabs.privateMode;
        List<TabManager.Tab> list = tabs.activeList();
        int active = tabs.activeIndex();

        topTitle.setTextColor(Ui.text());
        topTitle.setText(priv ? "비밀 브라우징" : list.size() + "개의 탭");
        clearPrivBtn.setVisibility(priv && !list.isEmpty() ? VISIBLE : GONE);

        rowsHost.removeAllViews();
        int curW = getWidth() > 0 ? getWidth() : Ui.screenW;
        int wDp = Math.round(curW / Ui.density);
        int cols = wDp >= 840 ? 4 : (wDp >= 600 ? 3 : 2);
        int cardH = wDp >= 840 ? 218 : (wDp >= 600 ? 210 : 198);
        Context c = getContext();

        int cardIdx = 0;
        for (int rowStart = 0; rowStart < list.size(); rowStart += cols) {
            LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int col = 0; col < cols; col++) {
                int idx = rowStart + col;
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                lp.setMargins(Ui.dp(7), Ui.dp(7), Ui.dp(7), Ui.dp(14));
                if (idx < list.size()) {
                    View card = buildTabCard(c, list.get(idx), priv, idx, idx == active, cardH);
                    if (!"robolectric".equals(android.os.Build.FINGERPRINT)) {
                        card.setAlpha(0f);
                        card.setScaleX(0.94f);
                        card.setScaleY(0.94f);
                        card.animate().alpha(1f).scaleX(1f).scaleY(1f)
                                .setStartDelay(Math.min(cardIdx * 24L, 160L))
                                .setDuration(240).setInterpolator(Ui.EMPHASIZED).start();
                    }
                    cardIdx++;
                    row.addView(card, lp);
                } else {
                    row.addView(new View(c), lp);
                }
            }
            rowsHost.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        controlBar.syncMode(priv);
    }

    private View buildTabCard(Context c, TabManager.Tab t, boolean priv, int idx, boolean isCur, int cardHeightDp) {
        LinearLayout wrap = new LinearLayout(c);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setGravity(Gravity.CENTER_HORIZONTAL);

        FrameLayout card = new FrameLayout(c);
        GradientDrawable bg = Ui.roundStroke(Ui.card(), isCur ? Ui.activeAccent() : Ui.glassStroke(), isCur ? 2.4f : 0.8f, 20);
        card.setBackground(bg);
        card.setClipToOutline(true);
        card.setElevation(Ui.dpf(isCur ? 8 : 4));

        if (t.thumbnail != null && !t.thumbnail.isRecycled()) {
            ImageView iv = new ImageView(c);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImageBitmap(t.thumbnail);
            card.addView(iv, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            View miniStart = new View(c) {
                final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                final RectF rf = new RectF();
                @Override protected void onDraw(Canvas cv) {
                    int w = getWidth(), h = getHeight();
                    int c1 = priv ? 0xFF1F1D2C : (Ui.dark ? 0xFF111C35 : 0xFFE6F0FF);
                    int c2 = priv ? 0xFF13131B : (Ui.dark ? 0xFF0A0D14 : 0xFFF5F7FB);
                    p.setShader(new LinearGradient(0, 0, w * 0.4f, h, c1, c2, Shader.TileMode.CLAMP));
                    cv.drawRect(0, 0, w, h, p);
                    p.setShader(null);
                    int[] cols = {0xFF4285F4, 0xFF03C75A, 0xFFFF0033, 0xFF1A73E8, 0xFF24292F, 0xFF64748B, 0xFF00A495, 0xFF1D1D1F};
                    float padX = w * 0.12f, gap = w * 0.06f;
                    float sq = (w - padX * 2f - gap * 3f) / 4f;
                    float topY = h * 0.22f;
                    for (int i = 0; i < 8; i++) {
                        int r = i / 4, cc = i % 4;
                        float x = padX + cc * (sq + gap), y = topY + r * (sq + gap * 1.3f);
                        p.setColor(cols[i]);
                        rf.set(x, y, x + sq, y + sq);
                        cv.drawRoundRect(rf, sq * 0.28f, sq * 0.28f, p);
                    }
                    p.setColor(priv ? 0x33FFFFFF : (Ui.dark ? 0x22FFFFFF : 0xAAFFFFFF));
                    rf.set(padX, topY + sq * 2.7f, w - padX, topY + sq * 3.55f);
                    cv.drawRoundRect(rf, Ui.dpf(8), Ui.dpf(8), p);
                }
            };
            card.addView(miniStart, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        View closeBtn = new View(c) {
            final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(Canvas cv) {
                p.setColor(0x66000000);
                cv.drawCircle(getWidth() / 2f, getHeight() / 2f, getWidth() / 2f, p);
                Icons.draw(cv, Icons.CLOSE, getWidth() / 2f, getHeight() / 2f, Ui.dpf(10), 0xFFFFFFFF);
            }
        };
        LayoutParams clp = new LayoutParams(Ui.dp(24), Ui.dp(24), Gravity.TOP | Gravity.END);
        clp.topMargin = Ui.dp(8); clp.rightMargin = Ui.dp(8);
        closeBtn.setOnClickListener(v -> listener.onCloseTab(priv, idx));
        card.addView(closeBtn, clp);

        card.setOnClickListener(v -> listener.onSelectTab(priv, idx));
        Ui.press(card, 0.96f);
        wrap.addView(card, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(cardHeightDp)));

        TextView cap = Ui.text(c, t.title == null || t.title.isEmpty() ? "시작 페이지" : t.title, 12.5f,
                isCur ? Ui.semibold : Ui.regular, isCur ? Ui.text() : Ui.text2());
        cap.setMaxLines(1);
        cap.setEllipsize(TextUtils.TruncateAt.END);
        cap.setGravity(Gravity.CENTER);
        cap.setPadding(Ui.dp(6), Ui.dp(7), Ui.dp(6), 0);
        wrap.addView(cap, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return wrap;
    }

    /** Floating bottom Liquid Glass bar: [+] | Segmented (Normal / Private refracting lens) | [완료]. */
    private final class BottomControlBar extends View {
        private final AuroraGlass.Surface barGlass = new AuroraGlass.Surface();
        private final AuroraGlass.Surface lensGlass = new AuroraGlass.Surface();
        private final RenderNode activeNode = new RenderNode("tab-seg-active");
        private final RectF addRect = new RectF(), segRect = new RectF(), doneRect = new RectF(), lensRect = new RectF();
        private final Ui.Spring segPos = new Ui.Spring(440f, 1.0f, 0.002f);
        private final Ui.Spring segLift = new Ui.Spring(540f, 1.0f, 0.002f);
        private final TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int hitZone;

        BottomControlBar(Context c) {
            super(c);
            tp.setTypeface(Ui.semibold);
            tp.setTextAlign(Paint.Align.CENTER);
            tp.setTextSize(Ui.sp(13f));
            barGlass.blurDp = Ui.barBlur();
            barGlass.refractDp = 17f;
            barGlass.edgeDp = 26f;
            barGlass.outerDp = 7f;
            barGlass.outerRefractDp = 12f;
            barGlass.chroma = 0f;
            barGlass.vibrance = 1.35f;
            barGlass.rim = 0.32f;
            barGlass.edgeDark = 0.32f;
            barGlass.shadowAlpha = 0.16f;
            lensGlass.blur = false;
            lensGlass.refractDp = 22f;
            lensGlass.edgeDp = 28f;
            lensGlass.outerDp = 8f;
            lensGlass.outerRefractDp = 14f;
            lensGlass.chroma = 0.9f;
            lensGlass.rim = 0.6f;
            lensGlass.shadowAlpha = 0.16f;
            segPos.onUpdate = this::invalidate;
            segLift.onUpdate = this::invalidate;
        }

        void syncMode(boolean priv) {
            segPos.to(priv ? 1f : 0f);
            invalidate();
        }

        private void layoutBar() {
            int W = getWidth(), H = getHeight();
            float h = Ui.dpf(50);
            float pad = Ui.isWide() ? Math.max(Ui.dpf(24), (W - Ui.dpf(520)) / 2f) : Ui.dpf(16);
            float bottom = H - Ui.navInset - Ui.dpf(14);
            float top = bottom - h;
            addRect.set(pad, top, pad + h, bottom);
            float doneW = Ui.dpf(70);
            doneRect.set(W - pad - doneW, top, W - pad, bottom);
            float segW = Math.min(Ui.dpf(204), Math.max(Ui.dpf(110), doneRect.left - addRect.right - Ui.dpf(16)));
            float segL = (addRect.right + doneRect.left - segW) / 2f;
            segRect.set(segL, top, segL + segW, bottom);
        }

        @Override protected void onDraw(Canvas c) {
            if (getWidth() <= 0) return;
            layoutBar();
            barGlass.blurDp = Ui.barBlur();
            barGlass.tint = Ui.barTint();
            barGlass.strokeColor = Ui.glassStroke();
            barGlass.shapes(segRect, segRect.height() / 2f, addRect, addRect.height() / 2f, doneRect, doneRect.height() / 2f);
            barGlass.draw(c, this, backdrop);

            float halfW = segRect.width() / 2f;
            float l = segLift.value;
            float lw = Ui.lerp(halfW - Ui.dpf(6), halfW + Ui.dpf(6), l);
            float lh = Ui.lerp(segRect.height() - Ui.dpf(8), segRect.height() + Ui.dpf(4), l);
            float cx = Ui.lerp(segRect.left + halfW * 0.5f, segRect.left + halfW * 1.5f, segPos.value);
            lensRect.set(cx - lw / 2f, segRect.centerY() - lh / 2f, cx + lw / 2f, segRect.centerY() + lh / 2f);

            if (l > 0.02f && c.isHardwareAccelerated()) {
                activeNode.setPosition(0, 0, getWidth(), getHeight());
                RecordingCanvas rc = activeNode.beginRecording(getWidth(), getHeight());
                try { drawSegLabels(rc, Ui.activeAccent(), Ui.activeAccent()); } finally { activeNode.endRecording(); }
                lensGlass.extra = activeNode;
                lensGlass.shapes(lensRect, lensRect.height() / 2f, null, 0, null, 0);
                lensGlass.mag = 1f + 0.14f * l;
                lensGlass.magX = lensRect.centerX();
                lensGlass.magY = lensRect.centerY();
                lensGlass.tint = Ui.isDarkSurface() ? 0x0FFFFFFF : 0x0AFFFFFF;
                lensGlass.strokeColor = Ui.isDarkSurface() ? 0x40FFFFFF : 0x1A000000;
                drawSegLabels(c, Ui.text2(), Ui.text2());
                lensGlass.draw(c, this, backdrop);
            } else {
                fill.setColor(Ui.alpha(Ui.activeAccent(), Ui.isDarkSurface() ? 0.28f : 0.15f));
                c.drawRoundRect(lensRect, lensRect.height() / 2f, lensRect.height() / 2f, fill);
                drawSegLabels(c, tabs.privateMode ? Ui.text2() : Ui.activeAccent(),
                        tabs.privateMode ? Ui.activeAccent() : Ui.text2());
            }

            Icons.draw(c, Icons.PLUS, addRect.centerX(), addRect.centerY(), Ui.dpf(20), Ui.text());
            tp.setColor(Ui.activeAccent());
            tp.setTypeface(Ui.bold);
            Paint.FontMetrics fm = tp.getFontMetrics();
            c.drawText("완료", doneRect.centerX(), doneRect.centerY() - (fm.ascent + fm.descent) / 2f, tp);
        }

        private void drawSegLabels(Canvas c, int leftCol, int rightCol) {
            Paint.FontMetrics fm = tp.getFontMetrics();
            float ty = segRect.centerY() - (fm.ascent + fm.descent) / 2f;
            tp.setTypeface(Ui.semibold);
            tp.setColor(leftCol);
            c.drawText("탭 " + tabs.normalTabs.size(), segRect.left + segRect.width() * 0.25f, ty, tp);
            tp.setColor(rightCol);
            c.drawText("비밀 " + tabs.privateTabs.size(), segRect.left + segRect.width() * 0.75f, ty, tp);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            float x = e.getX(), y = e.getY();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    layoutBar();
                    if (addRect.contains(x, y)) { hitZone = 1; return true; }
                    if (doneRect.contains(x, y)) { hitZone = 2; return true; }
                    if (segRect.contains(x, y)) { hitZone = 3; segLift.to(1f); return true; }
                    hitZone = 0;
                    return false;
                case MotionEvent.ACTION_MOVE:
                    if (hitZone == 3) {
                        segPos.snap(Ui.clamp((x - segRect.left) / Math.max(1f, segRect.width()), 0f, 1f));
                        return true;
                    }
                    return hitZone != 0;
                case MotionEvent.ACTION_UP:
                    segLift.to(0f);
                    if (hitZone == 1) listener.onNewTab(tabs.privateMode);
                    else if (hitZone == 2) listener.onDone();
                    else if (hitZone == 3) {
                        boolean wantPriv = x >= segRect.centerX();
                        segPos.to(wantPriv ? 1f : 0f);
                        listener.onSwitchPrivate(wantPriv);
                    }
                    hitZone = 0;
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    segLift.to(0f);
                    segPos.to(tabs.privateMode ? 1f : 0f);
                    hitZone = 0;
                    return true;
            }
            return false;
        }
    }
}
