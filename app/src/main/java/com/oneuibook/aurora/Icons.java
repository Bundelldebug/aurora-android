package com.oneuibook.aurora;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/** Crisp SF-Symbols-inspired custom vector icons, brand site marks, and iOS 26 Liquid Glass toggle switch. */
final class Icons {
    private Icons() {}

    static final int BACK = 1, FORWARD = 2, MORE = 3, RELOAD = 4, CLOSE = 5,
            TABS = 6, SHARE = 7, BOOKMARK = 8, PLUS = 9, SEARCH = 10,
            PAGE_MENU = 11, PRIVATE = 12, PUZZLE = 13, DESKTOP = 14,
            PWA = 15, CLOUD = 16, SHIELD = 17, READER = 18, SETTINGS = 19,
            CHECK = 20, LOCK = 21, STAR = 22, HISTORY = 23, DOWNLOAD = 24,
            FIND = 25, COMPASS = 26, EDIT = 27;

    private static final Paint P = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Path PATH = new Path();
    private static final RectF RF = new RectF();

    static void draw(Canvas c, int id, float cx, float cy, float size, int color) {
        P.reset();
        P.setAntiAlias(true);
        P.setColor(color);
        P.setStyle(Paint.Style.STROKE);
        P.setStrokeCap(Paint.Cap.ROUND);
        P.setStrokeJoin(Paint.Join.ROUND);
        P.setStrokeWidth(Math.max(1.8f, size * 0.086f));
        float r = size * 0.40f;

        switch (id) {
            case BACK:
                PATH.reset();
                PATH.moveTo(cx + r * 0.28f, cy - r * 0.72f);
                PATH.lineTo(cx - r * 0.44f, cy);
                PATH.lineTo(cx + r * 0.28f, cy + r * 0.72f);
                c.drawPath(PATH, P);
                break;
            case FORWARD:
                PATH.reset();
                PATH.moveTo(cx - r * 0.28f, cy - r * 0.72f);
                PATH.lineTo(cx + r * 0.44f, cy);
                PATH.lineTo(cx - r * 0.28f, cy + r * 0.72f);
                c.drawPath(PATH, P);
                break;
            case MORE:
                P.setStyle(Paint.Style.FILL);
                float dr = size * 0.084f;
                c.drawCircle(cx - r * 0.68f, cy, dr, P);
                c.drawCircle(cx, cy, dr, P);
                c.drawCircle(cx + r * 0.68f, cy, dr, P);
                break;
            case RELOAD:
                RF.set(cx - r * 0.76f, cy - r * 0.76f, cx + r * 0.76f, cy + r * 0.76f);
                c.drawArc(RF, -50, 290, false, P);
                float ax = cx + r * 0.54f, ay = cy - r * 0.60f;
                PATH.reset();
                PATH.moveTo(ax - r * 0.34f, ay - r * 0.02f);
                PATH.lineTo(ax + r * 0.06f, ay + r * 0.08f);
                PATH.lineTo(ax - r * 0.02f, ay - r * 0.34f);
                c.drawPath(PATH, P);
                break;
            case CLOSE:
                c.drawLine(cx - r * 0.56f, cy - r * 0.56f, cx + r * 0.56f, cy + r * 0.56f, P);
                c.drawLine(cx + r * 0.56f, cy - r * 0.56f, cx - r * 0.56f, cy + r * 0.56f, P);
                break;
            case TABS:
                RF.set(cx - r * 0.76f, cy - r * 0.40f, cx + r * 0.34f, cy + r * 0.72f);
                c.drawRoundRect(RF, r * 0.24f, r * 0.24f, P);
                PATH.reset();
                PATH.moveTo(cx - r * 0.30f, cy - r * 0.76f);
                PATH.lineTo(cx + r * 0.56f, cy - r * 0.76f);
                PATH.quadTo(cx + r * 0.78f, cy - r * 0.76f, cx + r * 0.78f, cy - r * 0.52f);
                PATH.lineTo(cx + r * 0.78f, cy + r * 0.32f);
                c.drawPath(PATH, P);
                break;
            case SHARE:
                c.drawLine(cx, cy + r * 0.20f, cx, cy - r * 0.78f, P);
                PATH.reset();
                PATH.moveTo(cx - r * 0.40f, cy - r * 0.38f);
                PATH.lineTo(cx, cy - r * 0.80f);
                PATH.lineTo(cx + r * 0.40f, cy - r * 0.38f);
                c.drawPath(PATH, P);
                PATH.reset();
                PATH.moveTo(cx - r * 0.44f, cy - r * 0.14f);
                PATH.lineTo(cx - r * 0.70f, cy - r * 0.14f);
                PATH.lineTo(cx - r * 0.70f, cy + r * 0.78f);
                PATH.lineTo(cx + r * 0.70f, cy + r * 0.78f);
                PATH.lineTo(cx + r * 0.70f, cy - r * 0.14f);
                PATH.lineTo(cx + r * 0.44f, cy - r * 0.14f);
                c.drawPath(PATH, P);
                break;
            case BOOKMARK:
                PATH.reset();
                PATH.moveTo(cx, cy - r * 0.56f);
                PATH.quadTo(cx - r * 0.44f, cy - r * 0.74f, cx - r * 0.82f, cy - r * 0.52f);
                PATH.lineTo(cx - r * 0.82f, cy + r * 0.62f);
                PATH.quadTo(cx - r * 0.44f, cy + r * 0.40f, cx, cy + r * 0.64f);
                PATH.quadTo(cx + r * 0.44f, cy + r * 0.40f, cx + r * 0.82f, cy + r * 0.62f);
                PATH.lineTo(cx + r * 0.82f, cy - r * 0.52f);
                PATH.quadTo(cx + r * 0.44f, cy - r * 0.74f, cx, cy - r * 0.56f);
                PATH.moveTo(cx, cy - r * 0.56f);
                PATH.lineTo(cx, cy + r * 0.64f);
                c.drawPath(PATH, P);
                break;
            case PLUS:
                c.drawLine(cx - r * 0.66f, cy, cx + r * 0.66f, cy, P);
                c.drawLine(cx, cy - r * 0.66f, cx, cy + r * 0.66f, P);
                break;
            case SEARCH:
                c.drawCircle(cx - r * 0.16f, cy - r * 0.16f, r * 0.54f, P);
                c.drawLine(cx + r * 0.24f, cy + r * 0.24f, cx + r * 0.72f, cy + r * 0.72f, P);
                break;
            case PAGE_MENU:
                RF.set(cx - r * 0.72f, cy - r * 0.70f, cx + r * 0.72f, cy - r * 0.04f);
                c.drawRoundRect(RF, r * 0.20f, r * 0.20f, P);
                c.drawLine(cx - r * 0.68f, cy + r * 0.34f, cx + r * 0.68f, cy + r * 0.34f, P);
                c.drawLine(cx - r * 0.68f, cy + r * 0.70f, cx + r * 0.30f, cy + r * 0.70f, P);
                break;
            case PRIVATE:
                c.drawLine(cx - r * 0.82f, cy - r * 0.10f, cx + r * 0.82f, cy - r * 0.10f, P);
                PATH.reset();
                PATH.moveTo(cx - r * 0.50f, cy - r * 0.10f);
                PATH.lineTo(cx - r * 0.28f, cy - r * 0.72f);
                PATH.lineTo(cx + r * 0.28f, cy - r * 0.72f);
                PATH.lineTo(cx + r * 0.50f, cy - r * 0.10f);
                c.drawPath(PATH, P);
                c.drawCircle(cx - r * 0.34f, cy + r * 0.42f, r * 0.26f, P);
                c.drawCircle(cx + r * 0.34f, cy + r * 0.42f, r * 0.26f, P);
                c.drawLine(cx - r * 0.08f, cy + r * 0.42f, cx + r * 0.08f, cy + r * 0.42f, P);
                break;
            case PUZZLE:
                RF.set(cx - r * 0.70f, cy - r * 0.52f, cx + r * 0.52f, cy + r * 0.70f);
                c.drawRoundRect(RF, r * 0.22f, r * 0.22f, P);
                c.drawCircle(cx - r * 0.09f, cy - r * 0.70f, r * 0.20f, P);
                c.drawCircle(cx + r * 0.70f, cy + r * 0.09f, r * 0.20f, P);
                break;
            case DESKTOP:
                RF.set(cx - r * 0.82f, cy - r * 0.64f, cx + r * 0.82f, cy + r * 0.36f);
                c.drawRoundRect(RF, r * 0.20f, r * 0.20f, P);
                c.drawLine(cx, cy + r * 0.36f, cx, cy + r * 0.72f, P);
                c.drawLine(cx - r * 0.40f, cy + r * 0.72f, cx + r * 0.40f, cy + r * 0.72f, P);
                break;
            case PWA:
                RF.set(cx - r * 0.72f, cy - r * 0.72f, cx + r * 0.72f, cy + r * 0.72f);
                c.drawRoundRect(RF, r * 0.30f, r * 0.30f, P);
                c.drawLine(cx - r * 0.34f, cy, cx + r * 0.34f, cy, P);
                c.drawLine(cx, cy - r * 0.34f, cx, cy + r * 0.34f, P);
                break;
            case CLOUD:
                PATH.reset();
                PATH.moveTo(cx - r * 0.54f, cy + r * 0.48f);
                PATH.lineTo(cx + r * 0.56f, cy + r * 0.48f);
                PATH.quadTo(cx + r * 0.88f, cy + r * 0.48f, cx + r * 0.84f, cy + r * 0.12f);
                PATH.quadTo(cx + r * 0.82f, cy - r * 0.24f, cx + r * 0.44f, cy - r * 0.22f);
                PATH.quadTo(cx + r * 0.32f, cy - r * 0.70f, cx - r * 0.14f, cy - r * 0.60f);
                PATH.quadTo(cx - r * 0.52f, cy - r * 0.52f, cx - r * 0.50f, cy - r * 0.14f);
                PATH.quadTo(cx - r * 0.88f, cy - r * 0.10f, cx - r * 0.84f, cy + r * 0.22f);
                PATH.quadTo(cx - r * 0.82f, cy + r * 0.48f, cx - r * 0.54f, cy + r * 0.48f);
                c.drawPath(PATH, P);
                break;
            case SHIELD:
                PATH.reset();
                PATH.moveTo(cx, cy - r * 0.82f);
                PATH.lineTo(cx + r * 0.66f, cy - r * 0.52f);
                PATH.lineTo(cx + r * 0.60f, cy + r * 0.14f);
                PATH.quadTo(cx + r * 0.44f, cy + r * 0.60f, cx, cy + r * 0.82f);
                PATH.quadTo(cx - r * 0.44f, cy + r * 0.60f, cx - r * 0.60f, cy + r * 0.14f);
                PATH.lineTo(cx - r * 0.66f, cy - r * 0.52f);
                PATH.close();
                c.drawPath(PATH, P);
                break;
            case READER:
                c.drawLine(cx - r * 0.72f, cy - r * 0.52f, cx + r * 0.72f, cy - r * 0.52f, P);
                c.drawLine(cx - r * 0.72f, cy - r * 0.16f, cx + r * 0.72f, cy - r * 0.16f, P);
                c.drawLine(cx - r * 0.72f, cy + r * 0.18f, cx + r * 0.72f, cy + r * 0.18f, P);
                c.drawLine(cx - r * 0.72f, cy + r * 0.52f, cx + r * 0.24f, cy + r * 0.52f, P);
                break;
            case SETTINGS:
                c.drawCircle(cx, cy, r * 0.34f, P);
                for (int i = 0; i < 8; i++) {
                    double a = i * Math.PI / 4.0;
                    c.drawLine(cx + (float) Math.cos(a) * r * 0.56f, cy + (float) Math.sin(a) * r * 0.56f,
                            cx + (float) Math.cos(a) * r * 0.80f, cy + (float) Math.sin(a) * r * 0.80f, P);
                }
                break;
            case CHECK:
                PATH.reset();
                PATH.moveTo(cx - r * 0.56f, cy + r * 0.04f);
                PATH.lineTo(cx - r * 0.14f, cy + r * 0.48f);
                PATH.lineTo(cx + r * 0.64f, cy - r * 0.44f);
                c.drawPath(PATH, P);
                break;
            case LOCK:
                RF.set(cx - r * 0.54f, cy - r * 0.12f, cx + r * 0.54f, cy + r * 0.72f);
                c.drawRoundRect(RF, r * 0.18f, r * 0.18f, P);
                RF.set(cx - r * 0.32f, cy - r * 0.70f, cx + r * 0.32f, cy + r * 0.10f);
                c.drawArc(RF, 180, 180, false, P);
                break;
            case STAR:
                PATH.reset();
                for (int i = 0; i < 10; i++) {
                    double a = -Math.PI / 2.0 + i * Math.PI / 5.0;
                    float rad = (i % 2 == 0) ? r * 0.82f : r * 0.36f;
                    float px = cx + (float) Math.cos(a) * rad, py = cy + (float) Math.sin(a) * rad;
                    if (i == 0) PATH.moveTo(px, py); else PATH.lineTo(px, py);
                }
                PATH.close();
                c.drawPath(PATH, P);
                break;
            case HISTORY:
                c.drawCircle(cx, cy, r * 0.74f, P);
                c.drawLine(cx, cy, cx, cy - r * 0.40f, P);
                c.drawLine(cx, cy, cx + r * 0.32f, cy + r * 0.22f, P);
                break;
            case DOWNLOAD:
                c.drawLine(cx, cy - r * 0.70f, cx, cy + r * 0.30f, P);
                PATH.reset();
                PATH.moveTo(cx - r * 0.38f, cy - r * 0.04f);
                PATH.lineTo(cx, cy + r * 0.36f);
                PATH.lineTo(cx + r * 0.38f, cy - r * 0.04f);
                c.drawPath(PATH, P);
                c.drawLine(cx - r * 0.64f, cy + r * 0.70f, cx + r * 0.64f, cy + r * 0.70f, P);
                break;
            case FIND:
                c.drawCircle(cx - r * 0.12f, cy - r * 0.12f, r * 0.54f, P);
                c.drawLine(cx + r * 0.26f, cy + r * 0.26f, cx + r * 0.74f, cy + r * 0.74f, P);
                break;
            case COMPASS:
                c.drawCircle(cx, cy, r * 0.80f, P);
                PATH.reset();
                PATH.moveTo(cx + r * 0.38f, cy - r * 0.38f);
                PATH.lineTo(cx + r * 0.12f, cy + r * 0.12f);
                PATH.lineTo(cx - r * 0.38f, cy + r * 0.38f);
                PATH.lineTo(cx - r * 0.12f, cy - r * 0.12f);
                PATH.close();
                c.drawPath(PATH, P);
                break;
            case EDIT:
                c.drawLine(cx - r * 0.62f, cy - r * 0.34f, cx + r * 0.62f, cy - r * 0.34f, P);
                c.drawLine(cx - r * 0.62f, cy + r * 0.34f, cx + r * 0.62f, cy + r * 0.34f, P);
                P.setStyle(Paint.Style.FILL);
                c.drawCircle(cx - r * 0.22f, cy - r * 0.34f, r * 0.20f, P);
                c.drawCircle(cx + r * 0.24f, cy + r * 0.34f, r * 0.20f, P);
                break;
        }
    }

    /** Draws crisp recognizable vector marks for Safari Start Page favorite tiles (no ugly generic letter boxes). */
    static void drawSiteBadge(Canvas c, String url, String title, int fallbackColor, float w, float h) {
        String dom = BrowserStore.domainOf(url).toLowerCase();
        float cx = w / 2f, cy = h / 2f;
        float rad = Math.min(w, h) * 0.24f;
        P.reset();
        P.setAntiAlias(true);

        // Soft Safari-style tile background
        if (dom.contains("google.com") && !dom.contains("chromewebstore")) {
            // Crisp white tile + 4-color Google G arc
            drawTileBase(c, w, h, Ui.isDarkSurface() ? 0xFF282B36 : 0xFFFFFFFF);
            P.setStyle(Paint.Style.STROKE);
            P.setStrokeWidth(w * 0.115f);
            P.setStrokeCap(Paint.Cap.BUTT);
            float gr = w * 0.24f;
            RF.set(cx - gr, cy - gr, cx + gr, cy + gr);
            P.setColor(0xFFEA4335); c.drawArc(RF, 210, 110, false, P);
            P.setColor(0xFFFBBC05); c.drawArc(RF, 140, 72, false, P);
            P.setColor(0xFF34A853); c.drawArc(RF, 42, 100, false, P);
            P.setColor(0xFF4285F4); c.drawArc(RF, -6, 52, false, P);
            P.setStrokeCap(Paint.Cap.ROUND);
            c.drawLine(cx + w * 0.01f, cy, cx + gr * 0.98f, cy, P);
        } else if (dom.contains("naver.com")) {
            drawTileBase(c, w, h, 0xFF03C75A);
            P.setStyle(Paint.Style.FILL);
            P.setColor(0xFFFFFFFF);
            PATH.reset();
            float s = w * 0.22f;
            PATH.moveTo(cx - s, cy + s);
            PATH.lineTo(cx - s, cy - s);
            PATH.lineTo(cx - s * 0.34f, cy - s);
            PATH.lineTo(cx + s * 0.34f, cy + s * 0.05f);
            PATH.lineTo(cx + s * 0.34f, cy - s);
            PATH.lineTo(cx + s, cy - s);
            PATH.lineTo(cx + s, cy + s);
            PATH.lineTo(cx + s * 0.34f, cy + s);
            PATH.lineTo(cx - s * 0.34f, cy - s * 0.05f);
            PATH.lineTo(cx - s * 0.34f, cy + s);
            PATH.close();
            c.drawPath(PATH, P);
        } else if (dom.contains("youtube.com")) {
            drawTileBase(c, w, h, Ui.isDarkSurface() ? 0xFF282B36 : 0xFFFFFFFF);
            P.setStyle(Paint.Style.FILL);
            P.setColor(0xFFFF0033);
            RF.set(cx - w * 0.30f, cy - h * 0.21f, cx + w * 0.30f, cy + h * 0.21f);
            c.drawRoundRect(RF, w * 0.12f, w * 0.12f, P);
            P.setColor(0xFFFFFFFF);
            PATH.reset();
            PATH.moveTo(cx - w * 0.07f, cy - h * 0.10f);
            PATH.lineTo(cx + w * 0.12f, cy);
            PATH.lineTo(cx - w * 0.07f, cy + h * 0.10f);
            PATH.close();
            c.drawPath(PATH, P);
        } else if (dom.contains("chromewebstore") || dom.contains("webstore")) {
            drawTileBase(c, w, h, Ui.isDarkSurface() ? 0xFF282B36 : 0xFFFFFFFF);
            // Crisp Chrome-inspired 3-segment ring + center sapphire orb
            P.setStyle(Paint.Style.STROKE);
            P.setStrokeWidth(w * 0.11f);
            float cr = w * 0.24f;
            RF.set(cx - cr, cy - cr, cx + cr, cy + cr);
            P.setColor(0xFFEA4335); c.drawArc(RF, 210, 120, false, P);
            P.setColor(0xFFFBBC05); c.drawArc(RF, -30, 120, false, P);
            P.setColor(0xFF34A853); c.drawArc(RF, 90, 120, false, P);
            P.setStyle(Paint.Style.FILL);
            P.setColor(0xFF1A73E8);
            c.drawCircle(cx, cy, w * 0.12f, P);
        } else if (dom.contains("github.com")) {
            drawTileBase(c, w, h, 0xFF1B1F24);
            P.setStyle(Paint.Style.STROKE);
            P.setColor(0xFFFFFFFF);
            P.setStrokeWidth(w * 0.07f);
            P.setStrokeCap(Paint.Cap.ROUND);
            // Minimal geometric code-branch glyph
            c.drawCircle(cx - w * 0.13f, cy - h * 0.16f, w * 0.065f, P);
            c.drawCircle(cx + w * 0.13f, cy - h * 0.12f, w * 0.065f, P);
            c.drawCircle(cx - w * 0.13f, cy + h * 0.18f, w * 0.065f, P);
            c.drawLine(cx - w * 0.13f, cy - h * 0.09f, cx - w * 0.13f, cy + h * 0.11f, P);
            PATH.reset();
            PATH.moveTo(cx + w * 0.13f, cy - h * 0.05f);
            PATH.quadTo(cx + w * 0.13f, cy + h * 0.06f, cx - w * 0.13f, cy + h * 0.08f);
            c.drawPath(PATH, P);
        } else if (dom.contains("wikipedia.org")) {
            drawTileBase(c, w, h, Ui.isDarkSurface() ? 0xFF282B36 : 0xFFFFFFFF);
            P.setStyle(Paint.Style.FILL);
            P.setColor(Ui.isDarkSurface() ? 0xFFF3F4F6 : 0xFF1F2937);
            P.setTypeface(android.graphics.Typeface.SERIF);
            P.setTextAlign(Paint.Align.CENTER);
            P.setTextSize(w * 0.46f);
            Paint.FontMetrics fm = P.getFontMetrics();
            c.drawText("W", cx, cy - (fm.ascent + fm.descent) / 2f, P);
        } else if (dom.contains("namu.wiki")) {
            drawTileBase(c, w, h, 0xFF00A495);
            // Crisp stylized leaf silhouette
            P.setStyle(Paint.Style.FILL);
            P.setColor(0xFFFFFFFF);
            PATH.reset();
            PATH.moveTo(cx, cy - h * 0.25f);
            PATH.quadTo(cx + w * 0.26f, cy - h * 0.04f, cx, cy + h * 0.25f);
            PATH.quadTo(cx - w * 0.26f, cy - h * 0.04f, cx, cy - h * 0.25f);
            PATH.close();
            c.drawPath(PATH, P);
            P.setStyle(Paint.Style.STROKE);
            P.setColor(0xFF00A495);
            P.setStrokeWidth(w * 0.045f);
            c.drawLine(cx, cy - h * 0.10f, cx, cy + h * 0.24f, P);
        } else if (dom.contains("apple.com")) {
            drawTileBase(c, w, h, Ui.isDarkSurface() ? 0xFF2C2F3A : 0xFF1D1D1F);
            draw(c, COMPASS, cx, cy, w * 0.56f, 0xFFFFFFFF);
        } else {
            drawTileBase(c, w, h, fallbackColor);
            P.setStyle(Paint.Style.FILL);
            P.setColor(0xFFFFFFFF);
            P.setTypeface(Ui.semibold);
            P.setTextAlign(Paint.Align.CENTER);
            P.setTextSize(w * 0.40f);
            String ch = (title != null && !title.trim().isEmpty()) ? title.trim().substring(0, 1).toUpperCase() : "A";
            Paint.FontMetrics fm = P.getFontMetrics();
            c.drawText(ch, cx, cy - (fm.ascent + fm.descent) / 2f, P);
        }
    }

    private static void drawTileBase(Canvas c, float w, float h, int color) {
        P.setStyle(Paint.Style.FILL);
        P.setColor(color);
        RF.set(0, 0, w, h);
        c.drawRoundRect(RF, w * 0.25f, w * 0.25f, P);
        P.setStyle(Paint.Style.STROKE);
        P.setStrokeWidth(Math.max(1f, Ui.dpf(0.6f)));
        P.setColor(Ui.isDarkSurface() ? 0x24FFFFFF : 0x14000000);
        c.drawRoundRect(RF, w * 0.25f, w * 0.25f, P);
    }

    /** Authentic iOS 26 Liquid Glass Capsule Toggle Switch (replaces stock Android Switch). */
    static final class GlassToggle extends View {
        interface OnChange { void onChanged(boolean checked); }

        private boolean checked;
        private final OnChange onChange;
        private final Ui.Spring pos = new Ui.Spring(480f, 0.74f, 0.002f);
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rf = new RectF();

        GlassToggle(Context c, boolean initial, OnChange onChange) {
            super(c);
            this.checked = initial;
            this.onChange = onChange;
            pos.snap(initial ? 1f : 0f);
            pos.onUpdate = this::invalidate;
        }

        @Override protected void onMeasure(int wSpec, int hSpec) {
            setMeasuredDimension(Ui.dp(50), Ui.dp(30));
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            float r = h / 2f;
            float t = Ui.clamp(pos.value, 0f, 1f);
            int offTrack = Ui.isDarkSurface() ? 0xFF3A3D4A : 0xFFD5DAE4;
            int onTrack = Ui.privateTheme ? 0xFF8B5CF6 : 0xFF34C759;
            p.setStyle(Paint.Style.FILL);
            p.setColor(Ui.mix(offTrack, onTrack, t));
            rf.set(0, 0, w, h);
            c.drawRoundRect(rf, r, r, p);

            // Sliding white glass knob
            float pad = Ui.dpf(2.5f);
            float kw = h - pad * 2f + Math.abs(pos.velocity) * 0.35f;
            kw = Math.min(kw, w * 0.56f);
            float kh = h - pad * 2f;
            float cx = Ui.lerp(pad + kh / 2f, w - pad - kh / 2f, t);
            rf.set(cx - kw / 2f, pad, cx + kw / 2f, pad + kh);
            p.setColor(0xFFFFFFFF);
            p.setShadowLayer(Ui.dpf(4), 0, Ui.dpf(1.5f), 0x33000000);
            c.drawRoundRect(rf, kh / 2f, kh / 2f, p);
            p.clearShadowLayer();
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                checked = !checked;
                pos.to(checked ? 1f : 0f);
                if (onChange != null) onChange.onChanged(checked);
                return true;
            }
            return e.getActionMasked() == MotionEvent.ACTION_DOWN;
        }
    }
}
