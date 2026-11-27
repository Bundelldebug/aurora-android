package com.oneuibook.aurora;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.net.Uri;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Overhauled iOS 26 / iPadOS 26 Safari Start Page (Prism-grade restraint, zero AI clutter):
 * - Supports 6 curated wallpapers + custom photo wallpaper from device gallery (persisted to filesDir/start_bg.jpg).
 * - Customizable sections: Favorites (with quick '+' add), Frequently Visited strip, compact Privacy/VPN bar,
 *   Web Apps, and Reading List.
 */
final class StartPageView extends ScrollView {
    interface Listener {
        void onOpenUrl(String url);
        void onOpenSecurityVpn();
        void onOpenExtensions();
        void onOpenSync();
        void onOpenSettings();
        void onEditStartPage();
        void onAddFavorite();
        void onRemoveBookmark(BrowserStore.Bookmark bm);
    }

    static final String[] WALLPAPER_NAMES = {
            "클리어 스카이", "오로라 미스트", "트와일라잇", "소프트 로즈", "딥 오션", "미니멀 단색"
    };
    static final int[][] LIGHT_PALETTES = {
            {0xFFE4F0FF, 0xFFF2F6FC, 0xFFF7F9FC},
            {0xFFE2F7F4, 0xFFEEF6FA, 0xFFF4F7FB},
            {0xFFECEBFF, 0xFFF3F2FA, 0xFFF6F7FB},
            {0xFFFCEBF3, 0xFFF7F3F9, 0xFFF5F7FB},
            {0xFFDCEBF8, 0xFFECF3FA, 0xFFF3F6FA},
            {0xFFF2F3F6, 0xFFF2F3F6, 0xFFF2F3F6}
    };
    static final int[][] DARK_PALETTES = {
            {0xFF0E1B36, 0xFF0C1222, 0xFF090B11},
            {0xFF0B222C, 0xFF0C151F, 0xFF090D12},
            {0xFF1B1633, 0xFF121022, 0xFF0A0B12},
            {0xFF24122B, 0xFF14111E, 0xFF0B0C12},
            {0xFF0A1929, 0xFF0A121C, 0xFF080B10},
            {0xFF101116, 0xFF0E1015, 0xFF0A0B0F}
    };

    private final LinearLayout outer;
    private final LinearLayout body;
    private final Listener listener;
    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Matrix bgMatrix = new Matrix();
    private int wallpaperIdx = 0;
    private Bitmap customBg;

    StartPageView(Context c, Listener listener) {
        super(c);
        this.listener = listener;
        setWillNotDraw(false);
        setFillViewport(true);
        setVerticalScrollBarEnabled(false);
        reloadBackground(c);

        outer = new LinearLayout(c);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setGravity(Gravity.CENTER_HORIZONTAL);
        body = new LinearLayout(c);
        body.setOrientation(LinearLayout.VERTICAL);
        outer.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(outer, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    static File customBgFile(Context c) {
        return new File(c.getFilesDir(), "start_bg.jpg");
    }

    static boolean hasCustomBg(Context c) {
        return Ui.prefs(c).getBoolean("use_custom_bg", false) && customBgFile(c).exists();
    }

    static boolean saveCustomBg(Context c, Uri uri) {
        if (c == null || uri == null) return false;
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            try (InputStream is = c.getContentResolver().openInputStream(uri)) {
                if (is == null) return false;
                BitmapFactory.decodeStream(is, null, opts);
            }
            int maxDim = Math.max(opts.outWidth, opts.outHeight);
            int sample = 1;
            while (maxDim / sample > 1600) sample *= 2;
            BitmapFactory.Options dec = new BitmapFactory.Options();
            dec.inSampleSize = sample;
            Bitmap bmp;
            try (InputStream is = c.getContentResolver().openInputStream(uri)) {
                if (is == null) return false;
                bmp = BitmapFactory.decodeStream(is, null, dec);
            }
            if (bmp == null) return false;
            try (FileOutputStream fos = new FileOutputStream(customBgFile(c))) {
                bmp.compress(Bitmap.CompressFormat.JPEG, 90, fos);
            }
            bmp.recycle();
            Ui.prefs(c).edit().putBoolean("use_custom_bg", true).apply();
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    static void clearCustomBg(Context c) {
        Ui.prefs(c).edit().putBoolean("use_custom_bg", false).apply();
        try { customBgFile(c).delete(); } catch (Throwable ignored) {}
    }

    void reloadBackground(Context c) {
        wallpaperIdx = Ui.prefs(c).getInt("wallpaper_idx", 0);
        if (customBg != null && !customBg.isRecycled()) {
            customBg.recycle();
        }
        customBg = null;
        if (hasCustomBg(c)) {
            try {
                customBg = BitmapFactory.decodeFile(customBgFile(c).getAbsolutePath());
            } catch (Throwable ignored) {}
        }
        invalidate();
    }

    private boolean showingPhotoBg() {
        return !Ui.privateTheme && customBg != null && !customBg.isRecycled();
    }

    private int startFg() {
        return showingPhotoBg() ? 0xFFFFFFFF : Ui.text();
    }

    private int startFg2() {
        return showingPhotoBg() ? 0xCCFFFFFF : Ui.text2();
    }

    @Override protected void onDraw(Canvas c) {
        int W = getWidth(), H = getHeight();
        if (W > 0 && H > 0) {
            int sy = getScrollY();
            c.save();
            c.translate(0, sy);
            if (Ui.privateTheme) {
                bgPaint.setShader(new LinearGradient(0, 0, W * 0.3f, H,
                        new int[]{0xFF1D1B29, 0xFF15151E, 0xFF111117}, new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
                c.drawRect(0, 0, W, H, bgPaint);
                bgPaint.setShader(null);
            } else if (showingPhotoBg()) {
                float scale = Math.max(W / (float) customBg.getWidth(), H / (float) customBg.getHeight());
                float dx = (W - customBg.getWidth() * scale) * 0.5f;
                float dy = (H - customBg.getHeight() * scale) * 0.5f;
                bgMatrix.reset();
                bgMatrix.setScale(scale, scale);
                bgMatrix.postTranslate(dx, dy);
                bgPaint.setShader(null);
                c.drawBitmap(customBg, bgMatrix, bgPaint);
                // Refined readability scrim over user photo wallpaper
                bgPaint.setShader(new LinearGradient(0, 0, 0, H,
                        new int[]{Ui.dark ? 0x8A000000 : 0x52000000, Ui.dark ? 0x6E000000 : 0x42000000, Ui.dark ? 0x99000000 : 0x66000000},
                        new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
                c.drawRect(0, 0, W, H, bgPaint);
                bgPaint.setShader(null);
            } else if (Ui.dark) {
                int[] pal = DARK_PALETTES[Math.abs(wallpaperIdx) % DARK_PALETTES.length];
                bgPaint.setShader(new LinearGradient(0, 0, W * 0.45f, H, pal, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
                c.drawRect(0, 0, W, H, bgPaint);
                bgPaint.setShader(null);
            } else {
                int[] pal = LIGHT_PALETTES[Math.abs(wallpaperIdx) % LIGHT_PALETTES.length];
                bgPaint.setShader(new LinearGradient(0, 0, W * 0.45f, H, pal, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
                c.drawRect(0, 0, W, H, bgPaint);
                bgPaint.setShader(null);
            }
            c.restore();
        }
        super.onDraw(c);
    }

    void rebuild() {
        body.removeAllViews();
        Context c = getContext();
        int curW = getWidth() > 0 ? getWidth() : Ui.screenW;
        boolean wide = (curW / Ui.density) >= 600f;
        int cols = wide ? 6 : 4;
        boolean showFav = Ui.prefs(c).getBoolean("sp_favorites", true);
        boolean showFreq = Ui.prefs(c).getBoolean("sp_frequent", true);
        boolean showPriv = Ui.prefs(c).getBoolean("sp_privacy", true);
        boolean showRead = Ui.prefs(c).getBoolean("sp_reading", true);

        LinearLayout.LayoutParams blp = (LinearLayout.LayoutParams) body.getLayoutParams();
        blp.width = wide ? Math.min(curW - Ui.dp(48), Ui.dp(740)) : ViewGroup.LayoutParams.MATCH_PARENT;
        body.setLayoutParams(blp);

        int safeTop = Math.max(Ui.statusInset, Ui.dp(20));
        int topPad = wide ? (safeTop + Ui.dp(116)) : (safeTop + Ui.dp(Ui.layoutMode == 2 ? 78 : 26));
        int botPad = wide ? (Math.max(Ui.navInset, Ui.dp(16)) + Ui.dp(48)) : (Math.max(Ui.navInset, Ui.dp(12)) + Ui.dp(116));
        int hPad = wide ? Ui.dp(12) : Ui.dp(20);
        body.setPadding(hPad, topPad, hPad, botPad);

        // Safari Private Browsing card
        if (Ui.privateTheme) {
            LinearLayout priv = glassCard(c);
            priv.setPadding(Ui.dp(18), Ui.dp(16), Ui.dp(18), Ui.dp(16));
            LinearLayout top = new LinearLayout(c);
            top.setOrientation(LinearLayout.HORIZONTAL);
            top.setGravity(Gravity.CENTER_VERTICAL);
            View maskIc = new View(c) {
                @Override protected void onDraw(Canvas canvas) {
                    Icons.draw(canvas, Icons.PRIVATE, getWidth() / 2f, getHeight() / 2f, Ui.dpf(20), 0xFFC4B5FD);
                }
            };
            top.addView(maskIc, new LinearLayout.LayoutParams(Ui.dp(24), Ui.dp(24)));
            TextView pt = Ui.text(c, "비밀 브라우징", 17f, Ui.bold, 0xFFF3E8FF);
            pt.setPadding(Ui.dp(8), 0, 0, 0);
            top.addView(pt);
            priv.addView(top);
            TextView pd = Ui.text(c, "방문한 페이지, 검색 기록 및 자동 완성 정보가 저장되지 않습니다.", 13f, Ui.regular, Ui.text2());
            pd.setPadding(0, Ui.dp(6), 0, 0);
            priv.addView(pd);
            body.addView(priv, cardLp(16));
        }

        // 1. 즐겨찾기 (iOS 26 / iPadOS 26 Safari grid + quick '+' button)
        if (showFav) {
            body.addView(sectionHeader(c, "즐겨찾기", "추가", v -> listener.onAddFavorite()));
            int totalBm = BrowserStore.bookmarks.size();
            int cellCount = totalBm + 1; // trailing '+' quick-add tile
            int iconDp = wide ? 64 : 60;
            for (int rowStart = 0; rowStart < cellCount; rowStart += cols) {
                LinearLayout row = new LinearLayout(c);
                row.setOrientation(LinearLayout.HORIZONTAL);
                for (int col = 0; col < cols; col++) {
                    int idx = rowStart + col;
                    LinearLayout item = new LinearLayout(c);
                    item.setOrientation(LinearLayout.VERTICAL);
                    item.setGravity(Gravity.CENTER_HORIZONTAL);
                    item.setPadding(Ui.dp(4), Ui.dp(6), Ui.dp(4), Ui.dp(14));
                    if (idx < totalBm) {
                        final BrowserStore.Bookmark bm = BrowserStore.bookmarks.get(idx);
                        View iconView = new View(c) {
                            @Override protected void onDraw(Canvas canvas) {
                                Icons.drawSiteBadge(canvas, bm.url, bm.title, bm.color, getWidth(), getHeight());
                            }
                        };
                        iconView.setElevation(Ui.dpf(Ui.isDarkSurface() ? 3 : 5));
                        item.addView(iconView, new LinearLayout.LayoutParams(Ui.dp(iconDp), Ui.dp(iconDp)));

                        TextView lbl = Ui.text(c, bm.title, 12f, Ui.medium, startFg());
                        lbl.setMaxLines(1);
                        lbl.setEllipsize(TextUtils.TruncateAt.END);
                        lbl.setGravity(Gravity.CENTER);
                        lbl.setPadding(Ui.dp(2), Ui.dp(8), Ui.dp(2), 0);
                        item.addView(lbl, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

                        item.setOnClickListener(v -> listener.onOpenUrl(bm.url));
                        item.setOnLongClickListener(v -> {
                            listener.onRemoveBookmark(bm);
                            return true;
                        });
                        Ui.press(item, 0.92f);
                    } else if (idx == totalBm) {
                        FrameLayout plusTile = new FrameLayout(c);
                        plusTile.setBackground(Ui.roundStroke(
                                showingPhotoBg() ? 0x38FFFFFF : Ui.alpha(Ui.card(), Ui.isDarkSurface() ? 0.72f : 0.80f),
                                showingPhotoBg() ? 0x55FFFFFF : Ui.glassStroke(), 0.7f, 16));
                        View plusIc = new View(c) {
                            @Override protected void onDraw(Canvas canvas) {
                                Icons.draw(canvas, Icons.PLUS, getWidth() / 2f, getHeight() / 2f, Ui.dpf(22), startFg2());
                            }
                        };
                        plusTile.addView(plusIc, new FrameLayout.LayoutParams(Ui.dp(24), Ui.dp(24), Gravity.CENTER));
                        item.addView(plusTile, new LinearLayout.LayoutParams(Ui.dp(iconDp), Ui.dp(iconDp)));

                        TextView lbl = Ui.text(c, "추가", 12f, Ui.medium, startFg2());
                        lbl.setGravity(Gravity.CENTER);
                        lbl.setPadding(Ui.dp(2), Ui.dp(8), Ui.dp(2), 0);
                        item.addView(lbl, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                        item.setOnClickListener(v -> listener.onAddFavorite());
                        Ui.press(item, 0.92f);
                    }
                    row.addView(item, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                }
                body.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
        }

        // 2. 자주 방문한 사이트 (deduped horizontal glass cards from real history)
        if (showFreq && !BrowserStore.history.isEmpty()) {
            List<BrowserStore.HistoryItem> freq = recentUniqueHistory(6);
            if (!freq.isEmpty()) {
                body.addView(sectionHeader(c, "자주 방문한 사이트", null, null));
                HorizontalScrollView hs = new HorizontalScrollView(c);
                hs.setHorizontalScrollBarEnabled(false);
                hs.setOverScrollMode(OVER_SCROLL_NEVER);
                LinearLayout strip = new LinearLayout(c);
                strip.setOrientation(LinearLayout.HORIZONTAL);
                for (int i = 0; i < freq.size(); i++) {
                    final BrowserStore.HistoryItem hi = freq.get(i);
                    LinearLayout tile = glassCard(c);
                    tile.setOrientation(LinearLayout.HORIZONTAL);
                    tile.setGravity(Gravity.CENTER_VERTICAL);
                    tile.setPadding(Ui.dp(12), Ui.dp(11), Ui.dp(14), Ui.dp(11));

                    View ic = new View(c) {
                        @Override protected void onDraw(Canvas canvas) {
                            Icons.drawSiteBadge(canvas, hi.url, hi.title, Ui.activeAccent(), getWidth(), getHeight());
                        }
                    };
                    tile.addView(ic, new LinearLayout.LayoutParams(Ui.dp(34), Ui.dp(34)));

                    LinearLayout col = new LinearLayout(c);
                    col.setOrientation(LinearLayout.VERTICAL);
                    col.setPadding(Ui.dp(10), 0, 0, 0);
                    TextView tt = Ui.text(c, hi.title == null || hi.title.isEmpty() ? BrowserStore.domainOf(hi.url) : hi.title,
                            13.5f, Ui.semibold, Ui.text());
                    tt.setMaxLines(1); tt.setEllipsize(TextUtils.TruncateAt.END);
                    col.addView(tt);
                    TextView dm = Ui.text(c, BrowserStore.domainOf(hi.url), 11.5f, Ui.regular, Ui.text2());
                    dm.setMaxLines(1); dm.setEllipsize(TextUtils.TruncateAt.END);
                    col.addView(dm);
                    tile.addView(col, new LinearLayout.LayoutParams(Ui.dp(120), ViewGroup.LayoutParams.WRAP_CONTENT));
                    tile.setOnClickListener(v -> listener.onOpenUrl(hi.url));
                    Ui.press(tile, 0.95f);

                    LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    if (i > 0) tlp.leftMargin = Ui.dp(10);
                    strip.addView(tile, tlp);
                }
                hs.addView(strip);
                body.addView(hs, cardLp(10));
            }
        }

        // 3. 개인정보 보호 리포트 (Restrained Apple Safari single-row glass card — zero noisy badges)
        if (showPriv) {
            body.addView(sectionHeader(c, "개인정보 보호 리포트", null, null));
            LinearLayout pr = glassCard(c);
            pr.setOrientation(LinearLayout.HORIZONTAL);
            pr.setGravity(Gravity.CENTER_VERTICAL);
            pr.setPadding(Ui.dp(16), Ui.dp(14), Ui.dp(16), Ui.dp(14));

            boolean vpnOn = SecurityGuard.isVpnActive(c);
            View shIc = new View(c) {
                @Override protected void onDraw(Canvas canvas) {
                    Icons.draw(canvas, Icons.SHIELD, getWidth() / 2f, getHeight() / 2f, Ui.dpf(19), Ui.text2());
                }
            };
            pr.addView(shIc, new LinearLayout.LayoutParams(Ui.dp(22), Ui.dp(22)));
            TextView num = Ui.text(c, String.valueOf(BrowserStore.blockedTrackers), 19f, Ui.bold, Ui.text());
            num.setPadding(Ui.dp(10), 0, Ui.dp(12), 0);
            pr.addView(num);

            String reportText = BrowserStore.blockedTrackers == 0
                    ? "추적기 차단이 켜져 있습니다."
                    : "추적기가 프로파일링하지 못하도록 차단했습니다.";
            if (vpnOn) reportText = SecurityGuard.vpnLabel(Ui.vpnProvider) + " 연결됨 · " + reportText;
            TextView prTxt = Ui.text(c, reportText, 13f, Ui.regular, Ui.text2());
            prTxt.setMaxLines(2);
            prTxt.setEllipsize(TextUtils.TruncateAt.END);
            pr.addView(prTxt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            pr.setOnClickListener(v -> listener.onOpenSecurityVpn());
            Ui.press(pr, 0.98f);
            body.addView(pr, cardLp(10));
        }

        // 4. 설치된 웹 앱
        if (!BrowserStore.webApps.isEmpty()) {
            body.addView(sectionHeader(c, "웹 앱", null, null));
            LinearLayout appCard = glassCard(c);
            for (int i = 0; i < BrowserStore.webApps.size(); i++) {
                final BrowserStore.WebAppItem app = BrowserStore.webApps.get(i);
                LinearLayout row = new LinearLayout(c);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(Ui.dp(14), Ui.dp(11), Ui.dp(14), Ui.dp(11));

                View ic = new View(c) {
                    @Override protected void onDraw(Canvas canvas) {
                        Icons.drawSiteBadge(canvas, app.startUrl, app.name, app.themeColor, getWidth(), getHeight());
                    }
                };
                row.addView(ic, new LinearLayout.LayoutParams(Ui.dp(34), Ui.dp(34)));

                LinearLayout info = new LinearLayout(c);
                info.setOrientation(LinearLayout.VERTICAL);
                info.setPadding(Ui.dp(12), 0, 0, 0);
                info.addView(Ui.text(c, app.name, 14.5f, Ui.semibold, Ui.text()));
                info.addView(Ui.text(c, BrowserStore.domainOf(app.startUrl), 12f, Ui.regular, Ui.text2()));
                row.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                row.setOnClickListener(v -> {
                    Intent in = new Intent(c, WebAppActivity.class);
                    in.putExtra("url", app.startUrl);
                    in.putExtra("name", app.name);
                    in.putExtra("themeColor", app.themeColor);
                    c.startActivity(in);
                });
                appCard.addView(row);
                if (i + 1 < BrowserStore.webApps.size()) appCard.addView(divider(c));
            }
            body.addView(appCard, cardLp(10));
        }

        // 5. 읽기 목록
        if (showRead && !BrowserStore.readingList.isEmpty()) {
            body.addView(sectionHeader(c, "읽기 목록", null, null));
            LinearLayout rlCard = glassCard(c);
            int limit = Math.min(4, BrowserStore.readingList.size());
            for (int i = 0; i < limit; i++) {
                BrowserStore.Bookmark b = BrowserStore.readingList.get(i);
                LinearLayout row = new LinearLayout(c);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(Ui.dp(16), Ui.dp(11), Ui.dp(16), Ui.dp(11));
                TextView t1 = Ui.text(c, b.title, 14f, Ui.semibold, Ui.text());
                t1.setMaxLines(1); t1.setEllipsize(TextUtils.TruncateAt.END);
                row.addView(t1);
                row.addView(Ui.text(c, BrowserStore.domainOf(b.url), 12f, Ui.regular, Ui.text2()));
                row.setOnClickListener(v -> listener.onOpenUrl(b.url));
                rlCard.addView(row);
                if (i + 1 < limit) rlCard.addView(divider(c));
            }
            body.addView(rlCard, cardLp(10));
        }

        // 6. Safari-authentic bottom '편집' frosted pill (opens Start Page & Wallpaper Customizer)
        LinearLayout foot = new LinearLayout(c);
        foot.setOrientation(LinearLayout.HORIZONTAL);
        foot.setGravity(Gravity.CENTER);
        foot.setPadding(0, Ui.dp(24), 0, Ui.dp(8));

        LinearLayout editPill = new LinearLayout(c);
        editPill.setOrientation(LinearLayout.HORIZONTAL);
        editPill.setGravity(Gravity.CENTER);
        editPill.setPadding(Ui.dp(18), Ui.dp(10), Ui.dp(20), Ui.dp(10));
        editPill.setBackground(Ui.roundStroke(
                showingPhotoBg() ? 0xB81C1C1E : Ui.alpha(Ui.card(), Ui.isDarkSurface() ? 0.72f : 0.84f),
                showingPhotoBg() ? 0x44FFFFFF : Ui.glassStroke(), 0.7f, 100));
        View edIc = new View(c) {
            @Override protected void onDraw(Canvas canvas) {
                Icons.draw(canvas, Icons.EDIT, getWidth() / 2f, getHeight() / 2f, Ui.dpf(15), showingPhotoBg() ? 0xFFFFFFFF : Ui.text());
            }
        };
        editPill.addView(edIc, new LinearLayout.LayoutParams(Ui.dp(17), Ui.dp(17)));
        TextView editTxt = Ui.text(c, "편집", 13.5f, Ui.semibold, showingPhotoBg() ? 0xFFFFFFFF : Ui.text());
        editTxt.setPadding(Ui.dp(7), 0, 0, 0);
        editPill.addView(editTxt);
        editPill.setOnClickListener(v -> listener.onEditStartPage());
        Ui.press(editPill, 0.94f);
        foot.addView(editPill);
        body.addView(foot);

        if (!"robolectric".equals(android.os.Build.FINGERPRINT)) {
            int n = body.getChildCount();
            for (int i = 0; i < n; i++) {
                View ch = body.getChildAt(i);
                ch.setAlpha(0f);
                ch.setTranslationY(Ui.dpf(10));
                ch.animate().alpha(1f).translationY(0f).setStartDelay(i * 22L)
                        .setDuration(260).setInterpolator(Ui.EMPHASIZED).start();
            }
        }
        invalidate();
    }

    private List<BrowserStore.HistoryItem> recentUniqueHistory(int limit) {
        Map<String, BrowserStore.HistoryItem> map = new LinkedHashMap<>();
        for (BrowserStore.HistoryItem h : BrowserStore.history) {
            String dom = BrowserStore.domainOf(h.url);
            if (!dom.isEmpty() && !map.containsKey(dom)) {
                map.put(dom, h);
                if (map.size() >= limit) break;
            }
        }
        return new ArrayList<>(map.values());
    }

    private View sectionHeader(Context c, String title, String action, OnClickListener click) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(4), Ui.dp(16), Ui.dp(4), Ui.dp(10));
        row.addView(Ui.text(c, title, 18.5f, Ui.bold, startFg()),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (action != null && click != null) {
            TextView a = Ui.text(c, action, 13.5f, Ui.semibold, showingPhotoBg() ? 0xFFFFFFFF : Ui.activeAccent());
            a.setOnClickListener(click);
            row.addView(a);
        }
        return row;
    }

    private LinearLayout glassCard(Context c) {
        LinearLayout ll = new LinearLayout(c);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setBackground(Ui.roundStroke(
                Ui.alpha(Ui.card(), showingPhotoBg() ? 0.86f : (Ui.isDarkSurface() ? 0.78f : 0.84f)),
                Ui.glassStroke(), 0.7f, 20));
        ll.setClipToOutline(true);
        return ll;
    }

    private LinearLayout.LayoutParams cardLp(int botDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(botDp);
        return lp;
    }

    private View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(Ui.sep());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(0.5f)));
        lp.leftMargin = Ui.dp(16);
        v.setLayoutParams(lp);
        return v;
    }
}
