package com.oneuibook.aurora;

import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Clean iOS 26 / iPadOS 26 Safari Start Page (zero AI clutter):
 * - Responsive centered max-720dp container with 6-column favorites on foldable/tablet & 4-column on phone.
 * - Interactive single-row Privacy & VPN Shield bar tapping directly into Security & NordVPN settings.
 * - Subtle staggered entrance motion on non-test runs.
 */
final class StartPageView extends ScrollView {
    interface Listener {
        void onOpenUrl(String url);
        void onOpenSecurityVpn();
        void onOpenExtensions();
        void onOpenSync();
        void onOpenSettings();
        void onRemoveBookmark(BrowserStore.Bookmark bm);
    }

    private final LinearLayout outer;
    private final LinearLayout body;
    private final Listener listener;
    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int wallpaperIdx = 0;

    StartPageView(Context c, Listener listener) {
        super(c);
        this.listener = listener;
        setWillNotDraw(false);
        setFillViewport(true);
        wallpaperIdx = Ui.prefs(c).getInt("wallpaper_idx", 0);

        outer = new LinearLayout(c);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setGravity(Gravity.CENTER_HORIZONTAL);
        body = new LinearLayout(c);
        body.setOrientation(LinearLayout.VERTICAL);
        outer.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(outer, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    @Override protected void onDraw(Canvas c) {
        int W = getWidth(), H = getHeight();
        if (W > 0 && H > 0) {
            if (Ui.privateTheme) {
                bgPaint.setShader(new LinearGradient(0, 0, W * 0.3f, H,
                        new int[]{0xFF1D1B29, 0xFF15151E, 0xFF111117}, new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
                c.drawRect(0, 0, W, H, bgPaint);
            } else if (Ui.dark) {
                int[][] darkPalettes = {
                        {0xFF0E1B36, 0xFF0C1222, 0xFF090B11},
                        {0xFF24122B, 0xFF14111E, 0xFF0B0C12},
                        {0xFF0B222C, 0xFF0C151F, 0xFF090D12},
                        {0xFF121318, 0xFF0E1015, 0xFF0A0B0F}
                };
                int[] pal = darkPalettes[wallpaperIdx % darkPalettes.length];
                bgPaint.setShader(new LinearGradient(0, 0, W * 0.5f, H, pal, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
                c.drawRect(0, 0, W, H, bgPaint);
            } else {
                int[][] lightPalettes = {
                        {0xFFE5F0FF, 0xFFF2F5FA, 0xFFF5F7FB},
                        {0xFFFCEBF3, 0xFFF6F2F9, 0xFFF4F6FA},
                        {0xFFE3F7F6, 0xFFEEF6FA, 0xFFF3F6FA},
                        {0xFFF5F6F8, 0xFFF2F4F7, 0xFFF2F4F7}
                };
                int[] pal = lightPalettes[wallpaperIdx % lightPalettes.length];
                bgPaint.setShader(new LinearGradient(0, 0, W * 0.45f, H, pal, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
                c.drawRect(0, 0, W, H, bgPaint);
                if (wallpaperIdx != 3) {
                    int glow = wallpaperIdx == 1 ? 0x2DF472B6 : (wallpaperIdx == 2 ? 0x2A2DD4BF : 0x2B38BDF8);
                    bgPaint.setShader(new RadialGradient(W * 0.85f, H * 0.14f, W * 0.72f, glow, 0x00FFFFFF, Shader.TileMode.CLAMP));
                    c.drawRect(0, 0, W, H, bgPaint);
                }
            }
            bgPaint.setShader(null);
        }
        super.onDraw(c);
    }

    void rebuild() {
        body.removeAllViews();
        Context c = getContext();
        int curW = getWidth() > 0 ? getWidth() : Ui.screenW;
        boolean wide = (curW / Ui.density) >= 600f;
        int cols = wide ? 6 : 4;

        LinearLayout.LayoutParams blp = (LinearLayout.LayoutParams) body.getLayoutParams();
        blp.width = wide ? Math.min(curW - Ui.dp(48), Ui.dp(720)) : ViewGroup.LayoutParams.MATCH_PARENT;
        body.setLayoutParams(blp);

        int safeTop = Math.max(Ui.statusInset, Ui.dp(20));
        int topPad = wide ? (safeTop + Ui.dp(116)) : (safeTop + Ui.dp(Ui.layoutMode == 2 ? 78 : 24));
        int botPad = wide ? (Math.max(Ui.navInset, Ui.dp(16)) + Ui.dp(44)) : (Math.max(Ui.navInset, Ui.dp(12)) + Ui.dp(118));
        int hPad = wide ? Ui.dp(12) : Ui.dp(20);
        body.setPadding(hPad, topPad, hPad, botPad);

        // Safari Private Browsing card
        if (Ui.privateTheme) {
            LinearLayout priv = glassCard(c);
            priv.setPadding(Ui.dp(16), Ui.dp(14), Ui.dp(16), Ui.dp(14));
            LinearLayout top = new LinearLayout(c);
            top.setOrientation(LinearLayout.HORIZONTAL);
            top.setGravity(Gravity.CENTER_VERTICAL);
            View maskIc = new View(c) {
                @Override protected void onDraw(Canvas canvas) {
                    Icons.draw(canvas, Icons.PRIVATE, getWidth() / 2f, getHeight() / 2f, Ui.dpf(20), 0xFFC4B5FD);
                }
            };
            top.addView(maskIc, new LinearLayout.LayoutParams(Ui.dp(24), Ui.dp(24)));
            TextView pt = Ui.text(c, "비밀 브라우징", 16.5f, Ui.bold, 0xFFF3E8FF);
            pt.setPadding(Ui.dp(8), 0, 0, 0);
            top.addView(pt);
            priv.addView(top);
            TextView pd = Ui.text(c, "방문한 페이지, 검색 기록 및 자동 완성 정보가 저장되지 않습니다.", 12.5f, Ui.regular, Ui.text2());
            pd.setPadding(0, Ui.dp(6), 0, 0);
            priv.addView(pd);
            body.addView(priv, cardLp(14));
        }

        // 1. 즐겨찾기 (4-col on phone, 6-col on foldable/tablet)
        body.addView(sectionHeader(c, "즐겨찾기", null, null));
        int totalBm = BrowserStore.bookmarks.size();
        int iconDp = wide ? 64 : 60;
        for (int rowStart = 0; rowStart < totalBm; rowStart += cols) {
            LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int col = 0; col < cols; col++) {
                int idx = rowStart + col;
                LinearLayout item = new LinearLayout(c);
                item.setOrientation(LinearLayout.VERTICAL);
                item.setGravity(Gravity.CENTER_HORIZONTAL);
                item.setPadding(Ui.dp(4), Ui.dp(6), Ui.dp(4), Ui.dp(12));
                if (idx < totalBm) {
                    final BrowserStore.Bookmark bm = BrowserStore.bookmarks.get(idx);
                    View iconView = new View(c) {
                        @Override protected void onDraw(Canvas canvas) {
                            Icons.drawSiteBadge(canvas, bm.url, bm.title, bm.color, getWidth(), getHeight());
                        }
                    };
                    iconView.setElevation(Ui.dpf(Ui.isDarkSurface() ? 4 : 6));
                    item.addView(iconView, new LinearLayout.LayoutParams(Ui.dp(iconDp), Ui.dp(iconDp)));

                    TextView lbl = Ui.text(c, bm.title, 12f, Ui.medium, Ui.text());
                    lbl.setMaxLines(1);
                    lbl.setEllipsize(TextUtils.TruncateAt.END);
                    lbl.setGravity(Gravity.CENTER);
                    lbl.setPadding(Ui.dp(2), Ui.dp(7), Ui.dp(2), 0);
                    item.addView(lbl, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

                    item.setOnClickListener(v -> listener.onOpenUrl(bm.url));
                    item.setOnLongClickListener(v -> {
                        listener.onRemoveBookmark(bm);
                        return true;
                    });
                    Ui.press(item, 0.92f);
                }
                row.addView(item, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            }
            body.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        // 2. 개인정보 및 보안 (Compact Safari shield bar + live NordVPN status chip)
        body.addView(sectionHeader(c, "개인정보 및 보안", null, null));
        LinearLayout pr = glassCard(c);
        pr.setOrientation(LinearLayout.HORIZONTAL);
        pr.setGravity(Gravity.CENTER_VERTICAL);
        pr.setPadding(Ui.dp(16), Ui.dp(14), Ui.dp(14), Ui.dp(14));

        boolean vpnOn = SecurityGuard.isVpnActive(c);
        View shIc = new View(c) {
            @Override protected void onDraw(Canvas canvas) {
                Icons.draw(canvas, Icons.SHIELD, getWidth() / 2f, getHeight() / 2f, Ui.dpf(21), vpnOn ? 0xFF34C759 : Ui.activeAccent());
            }
        };
        pr.addView(shIc, new LinearLayout.LayoutParams(Ui.dp(24), Ui.dp(24)));
        TextView num = Ui.text(c, String.valueOf(BrowserStore.blockedTrackers), 21f, Ui.bold, Ui.text());
        num.setPadding(Ui.dp(8), 0, Ui.dp(12), 0);
        pr.addView(num);

        TextView prTxt = Ui.text(c,
                BrowserStore.blockedTrackers == 0
                        ? "추적기·피싱 보호 활성화"
                        : "추적기 차단이 활성화되어 있습니다.",
                13f, Ui.regular, Ui.text2());
        prTxt.setMaxLines(1);
        prTxt.setEllipsize(TextUtils.TruncateAt.END);
        pr.addView(prTxt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        String vpnTag = vpnOn ? SecurityGuard.vpnLabel(Ui.vpnProvider) : (BrowserStore.vpnDomains.isEmpty() ? "VPN" : "VPN " + BrowserStore.vpnDomains.size());
        TextView vpnChip = Ui.text(c, vpnTag, 11.5f, Ui.semibold, vpnOn ? 0xFF34C759 : Ui.activeAccent());
        vpnChip.setPadding(Ui.dp(10), Ui.dp(5), Ui.dp(10), Ui.dp(5));
        vpnChip.setBackground(Ui.round(Ui.alpha(vpnOn ? 0xFF34C759 : Ui.activeAccent(), 0.14f), 100));
        pr.addView(vpnChip);
        pr.setOnClickListener(v -> listener.onOpenSecurityVpn());
        Ui.press(pr, 0.98f);
        body.addView(pr, cardLp(10));

        // 3. 설치된 웹 앱
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

        // 4. 읽기 목록
        if (!BrowserStore.readingList.isEmpty()) {
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

        // 5. Safari bottom '편집' frosted pill
        LinearLayout foot = new LinearLayout(c);
        foot.setOrientation(LinearLayout.HORIZONTAL);
        foot.setGravity(Gravity.CENTER);
        foot.setPadding(0, Ui.dp(22), 0, Ui.dp(8));

        TextView editBtn = Ui.text(c, "배경 전환", 13f, Ui.semibold, Ui.text());
        editBtn.setPadding(Ui.dp(20), Ui.dp(9), Ui.dp(20), Ui.dp(9));
        editBtn.setBackground(Ui.roundStroke(Ui.alpha(Ui.card(), Ui.isDarkSurface() ? 0.65f : 0.78f), Ui.glassStroke(), 0.7f, 100));
        editBtn.setOnClickListener(v -> {
            wallpaperIdx = (wallpaperIdx + 1) % 4;
            Ui.prefs(c).edit().putInt("wallpaper_idx", wallpaperIdx).apply();
            invalidate();
        });
        Ui.press(editBtn, 0.94f);
        foot.addView(editBtn);
        body.addView(foot);

        // Clean entrance stagger on real devices
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

    private View sectionHeader(Context c, String title, String action, OnClickListener click) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(4), Ui.dp(14), Ui.dp(4), Ui.dp(8));
        row.addView(Ui.text(c, title, 18f, Ui.bold, Ui.text()),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (action != null && click != null) {
            TextView a = Ui.text(c, action, 13.5f, Ui.medium, Ui.activeAccent());
            a.setOnClickListener(click);
            row.addView(a);
        }
        return row;
    }

    private LinearLayout glassCard(Context c) {
        LinearLayout ll = new LinearLayout(c);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setBackground(Ui.roundStroke(Ui.alpha(Ui.card(), Ui.isDarkSurface() ? 0.74f : 0.78f), Ui.glassStroke(), 0.7f, 18));
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
