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
    static boolean liquidGlass = true; // true = Tri-Engine Liquid Glass, false = Frost Blur
    static float glassTransparency = 0.52f;
    static boolean desktopDefault = false;
    static int accent = 0xFF0A84FF; // iOS 26 Safari vibrant sapphire-cyan accent

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
        int nm = c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        dark = nm == Configuration.UI_MODE_NIGHT_YES;

        SharedPreferences p = prefs(c);
        layoutMode = p.getInt("layout_mode", 0);
        searchEngine = p.getInt("search_engine", 0);
        adBlock = p.getBoolean("ad_block", true);
        liquidGlass = p.getBoolean("liquid_glass", true);
        glassTransparency = p.getFloat("glass_t", 0.52f);
        desktopDefault = p.getBoolean("desktop_default", false);
        accent = p.getInt("accent", 0xFF0A84FF);
    }

    static void savePrefs(Context c) {
        prefs(c).edit()
                .putInt("layout_mode", layoutMode)
                .putInt("search_engine", searchEngine)
                .putBoolean("ad_block", adBlock)
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

    static int barTint() {
        int a = Math.round(lerp(0xB8, 0x42, clamp(glassTransparency, 0f, 1f)));
        if (privateTheme) return (Math.max(a, 0x8A) << 24) | 0x1E1F2A;
        return dark ? ((a << 24) | 0x1C1E26) : ((a << 24) | 0xFCFDFF);
    }

    static int sheetTint() {
        int a = Math.round(lerp(0xE8, 0xB8, clamp(glassTransparency, 0f, 1f)));
        if (privateTheme) return (a << 24) | 0x1C1D27;
        return dark ? ((a << 24) | 0x181B23) : ((a << 24) | 0xFAFCFF);
    }

    static int glassStroke() {
        if (privateTheme) return 0x38C4B5FD;
        return dark ? 0x30FFFFFF : 0x20000000;
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
        final float stiffness, dampingRatio, threshold;
        float value, target, velocity;
        boolean running;
        long lastNanos;
        Runnable onUpdate;

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
                return;
            }
            if (!running) {
                running = true;
                lastNanos = System.nanoTime();
                try { Choreographer.getInstance().postFrameCallback(this); } catch (Throwable ignored) { value = target; running = false; }
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
            if (Math.abs(target - value) < threshold && Math.abs(velocity) < threshold * 20f) {
                value = target;
                velocity = 0f;
                running = false;
            } else {
                Choreographer.getInstance().postFrameCallback(this);
            }
            if (onUpdate != null) onUpdate.run();
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
