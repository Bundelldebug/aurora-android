package com.oneuibook.aurora;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Choreographer;
import android.view.View;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;
import android.widget.TextView;

/**
 * Adaptive theme, typography, motion curves, spring physics, and preferences for Aurora Browser.
 * Supports Light, Dark, and Safari-style Private Browsing graphite-indigo palette.
 */
final class Ui {
    private Ui() {}

    static float density = 3f;
    static float scaledDensity = 3f;
    static boolean dark;
    static boolean privateTheme;
    static int statusInset, navInset;

    // User preferences
    static int layoutMode = 0;      // 0 = Compact (iOS 26 default), 1 = Bottom Classic, 2 = Top
    static int searchEngine = 0;    // 0 = Google, 1 = Naver, 2 = DuckDuckGo, 3 = Bing
    static boolean adBlock = true;
    static boolean securityShield = true;
    static boolean httpsOnly = true;
    static String vpnProvider = "nordvpn";
    static String translateLang = "ko";
    static boolean autoTranslate = false;
    static boolean liquidGlass = true; // true = Prism-unified Liquid Glass, false = Frost Blur
    static float glassTransparency = 0.5f; // matches Prism Ui.glassT default (0.5f)
    static boolean desktopDefault = false;
    static int accent = 0xFF0A84FF; // iOS 26 Safari vibrant sapphire-cyan accent
    static int screenW = 1080, screenH = 2400;

    static Typeface regular = Typeface.create("sans-serif", Typeface.NORMAL);
    static Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    static Typeface semibold = Typeface.create("sans-serif-medium", Typeface.BOLD);
    static Typeface bold = Typeface.create("sans-serif", Typeface.BOLD);

    static final Interpolator EMPHASIZED = new PathInterpolator(0.22f, 1.0f, 0.36f, 1.0f);
    static final Interpolator DECEL = new PathInterpolator(0.0f, 0.0f, 0.2f, 1.0f);

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("aurora_prefs", Context.MODE_PRIVATE);
    }

    static void init(Context c) {
        DisplayMetrics dm = c.getResources().getDisplayMetrics();
        density = dm.density > 0 ? dm.density : 3f;
        scaledDensity = dm.scaledDensity > 0 ? dm.scaledDensity : density;
        screenW = dm.widthPixels > 0 ? dm.widthPixels : 1080;
        screenH = dm.heightPixels > 0 ? dm.heightPixels : 2400;
        int nm = c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        dark = nm == Configuration.UI_MODE_NIGHT_YES;

        SharedPreferences p = prefs(c);
        layoutMode = p.getInt("layout_mode", 0);
        searchEngine = p.getInt("search_engine", 0);
        adBlock = p.getBoolean("ad_block", true);
        securityShield = p.getBoolean("security_shield", true);
        httpsOnly = p.getBoolean("https_only", true);
        vpnProvider = p.getString("vpn_provider", "nordvpn");
        translateLang = p.getString("translate_lang", "ko");
        autoTranslate = p.getBoolean("auto_translate", false);
        liquidGlass = p.getBoolean("liquid_glass", true);
        glassTransparency = p.getFloat("glass_t", 0.5f);
        desktopDefault = p.getBoolean("desktop_default", false);
        accent = p.getInt("accent", 0xFF0A84FF);
    }

    static boolean isWide() { return (screenW / density) >= 600f; }
    static boolean isTablet() { return (screenW / density) >= 840f; }

    static void savePrefs(Context c) {
        prefs(c).edit()
                .putInt("layout_mode", layoutMode)
                .putInt("search_engine", searchEngine)
                .putBoolean("ad_block", adBlock)
                .putBoolean("security_shield", securityShield)
                .putBoolean("https_only", httpsOnly)
                .putString("vpn_provider", vpnProvider)
                .putString("translate_lang", translateLang)
                .putBoolean("auto_translate", autoTranslate)
                .putBoolean("liquid_glass", liquidGlass)
                .putFloat("glass_t", glassTransparency)
                .putBoolean("desktop_default", desktopDefault)
                .putInt("accent", accent)
                .apply();
    }

    static int dp(float v) { return Math.round(v * density); }
    static float dpf(float v) { return v * density; }
    static float sp(float v) { return v * scaledDensity; }
    static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }
    static float lerp(float a, float b, float t) { return a + (b - a) * t; }

    /** Prism-style tactile press recoil animation. */
    static void press(View v, float scale) {
        v.setOnTouchListener((view, e) -> {
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    view.animate().scaleX(scale).scaleY(scale).setDuration(110).setInterpolator(DECEL).start();
                    break;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    view.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(EMPHASIZED).start();
                    break;
            }
            return false;
        });
    }

    static boolean isDarkSurface() { return dark || privateTheme; }

    static int bg() {
        if (privateTheme) return 0xFF14151C;
        return dark ? 0xFF0A0C12 : 0xFFF2F4F8;
    }

    static int card() {
        if (privateTheme) return 0xFF222430;
        return dark ? 0xFF1A1D26 : 0xFFFFFFFF;
    }

    static int text() {
        return isDarkSurface() ? 0xFFF5F7FB : 0xFF111318;
    }

    static int text2() {
        return isDarkSurface() ? 0xFF9EA6B8 : 0xFF6B7280;
    }

    static int sep() {
        return isDarkSurface() ? 0x24FFFFFF : 0x18000000;
    }

    static int activeAccent() {
        return privateTheme ? 0xFFA78BFA : accent;
    }

    static int glassBlend(int opaque, int medium, int high) {
        float t = clamp(glassTransparency, 0f, 1f);
        return t >= 0.5f ? mix(medium, high, (t - 0.5f) * 2f) : mix(opaque, medium, t * 2f);
    }

    /** Exact Prism barTint() optical blend curve. */
    static int barTint() {
        if (privateTheme) return glassBlend(0xFF1E1F2A, 0xA61E1F2A, 0x521E1F2A);
        return dark ? glassBlend(0xFF1C1C1E, 0xA61C1C1E, 0x521C1C1E)
                : glassBlend(0xFFFAFAFC, 0xA8FAFAFC, 0x40FFFFFF);
    }

    /** Exact Prism barBlur() optical blur curve (6dp at 0.5 -> 2dp at 1.0). */
    static float barBlur() {
        float t = clamp(glassTransparency, 0f, 1f);
        return t >= 0.5f ? lerp(6f, 2f, (t - 0.5f) * 2f) : 6f;
    }

    /** Exact Prism menuTint() optical blend curve. */
    static int sheetTint() {
        if (privateTheme) return glassBlend(0xF51C1D27, 0xB81C1D27, 0x8C1C1D27);
        return dark ? glassBlend(0xF51C1C1E, 0xB81C1C1E, 0x8C1C1C1E)
                : glassBlend(0xF5FAFAFC, 0xBDFAFAFC, 0x8CFAFAFC);
    }

    /** Exact Prism glassStroke() values. */
    static int glassStroke() {
        if (privateTheme) return 0x38C4B5FD;
        return dark ? 0x33FFFFFF : 0x24000000;
    }

    static int alpha(int color, float factor) {
        int a = Math.round(((color >>> 24) & 0xFF) * clamp(factor, 0f, 1f));
        return (a << 24) | (color & 0x00FFFFFF);
    }

    static int mix(int c1, int c2, float t) {
        t = clamp(t, 0f, 1f);
        int a = Math.round(lerp((c1 >>> 24) & 255, (c2 >>> 24) & 255, t));
        int r = Math.round(lerp((c1 >> 16) & 255, (c2 >> 16) & 255, t));
        int g = Math.round(lerp((c1 >> 8) & 255, (c2 >> 8) & 255, t));
        int b = Math.round(lerp(c1 & 255, c2 & 255, t));
        return Color.argb(a, r, g, b);
    }

    static GradientDrawable round(int fillColor, float radiusDp) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(fillColor);
        gd.setCornerRadius(dpf(radiusDp));
        return gd;
    }

    static GradientDrawable roundStroke(int fillColor, int strokeColor, float strokeDp, float radiusDp) {
        GradientDrawable gd = round(fillColor, radiusDp);
        gd.setStroke(Math.max(1, dp(strokeDp)), strokeColor);
        return gd;
    }

    static TextView text(Context c, String s, float spSize, Typeface tf, int color) {
        TextView tv = new TextView(c);
        tv.setText(s);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, spSize);
        tv.setTypeface(tf);
        tv.setTextColor(color);
        return tv;
    }

    static ValueAnimator animate(float from, float to, long ms, Interpolator interp, ValueAnimator.AnimatorUpdateListener l) {
        ValueAnimator va = ValueAnimator.ofFloat(from, to);
        va.setDuration(ms);
        va.setInterpolator(interp);
        va.addUpdateListener(l);
        va.start();
        return va;
    }

    /** Damped harmonic spring (Choreographer-driven) for iOS 26 Safari fluid pill & lens motion. */
    static final class Spring implements Choreographer.FrameCallback {
        float stiffness, dampingRatio;
        final float threshold;
        float value, target, velocity;
        boolean running;
        long lastNanos;
        Runnable onUpdate;
        Runnable onEnd;

        Spring(float stiffness, float dampingRatio, float threshold) {
            this.stiffness = stiffness;
            this.dampingRatio = dampingRatio;
            this.threshold = threshold;
        }

        void snap(float v) {
            stop();
            value = target = v;
            velocity = 0f;
            if (onUpdate != null) onUpdate.run();
        }

        void to(float t) {
            target = t;
            if (Math.abs(target - value) < threshold && Math.abs(velocity) < threshold) {
                value = target;
                velocity = 0f;
                if (onUpdate != null) onUpdate.run();
                if (onEnd != null) onEnd.run();
                return;
            }
            if (!running) {
                running = true;
                lastNanos = System.nanoTime();
                try {
                    Choreographer.getInstance().postFrameCallback(this);
                } catch (Throwable ignored) {
                    value = target;
                    running = false;
                    if (onUpdate != null) onUpdate.run();
                    if (onEnd != null) onEnd.run();
                }
            }
        }

        void stop() {
            if (running) {
                running = false;
                try { Choreographer.getInstance().removeFrameCallback(this); } catch (Throwable ignored) {}
            }
        }

        @Override public void doFrame(long frameTimeNanos) {
            if (!running) return;
            float dt = Math.min(0.032f, Math.max(0.001f, (frameTimeNanos - lastNanos) / 1e9f));
            lastNanos = frameTimeNanos;
            float c = 2f * dampingRatio * (float) Math.sqrt(stiffness);
            float force = -stiffness * (value - target) - c * velocity;
            velocity += force * dt;
            value += velocity * dt;
            boolean done = false;
            if (Math.abs(target - value) < threshold && Math.abs(velocity) < threshold * 20f) {
                value = target;
                velocity = 0f;
                running = false;
                done = true;
            } else {
                Choreographer.getInstance().postFrameCallback(this);
            }
            if (onUpdate != null) onUpdate.run();
            if (done && onEnd != null) onEnd.run();
        }
    }

    static String searchEngineName(int idx) {
        switch (idx) {
            case 1: return "Naver";
            case 2: return "DuckDuckGo";
            case 3: return "Bing";
            default: return "Google";
        }
    }

    static String buildSearchUrl(String query) {
        String enc = android.net.Uri.encode(query);
        switch (searchEngine) {
            case 1: return "https://m.search.naver.com/search.naver?query=" + enc;
            case 2: return "https://duckduckgo.com/?q=" + enc;
            case 3: return "https://www.bing.com/search?q=" + enc;
            default: return "https://www.google.com/search?q=" + enc;
        }
    }

    static String layoutName(int idx) {
        switch (idx) {
            case 1: return "하단 막대 (클래식)";
            case 2: return "상단 주소 표시줄";
            default: return "컴팩트 (iOS 26)";
        }
    }
}
