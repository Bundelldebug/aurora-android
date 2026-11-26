package com.oneuibook.aurora;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 34)
public class AuroraUiTest {
    private File outDir;

    @Before public void setup() {
        outDir = new File(System.getProperty("aurora.output", "."));
        outDir.mkdirs();
    }

    private void shot(View v, int w, int h, String name) throws Exception {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        v.layout(0, 0, w, h);
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        v.draw(c);
        try (FileOutputStream fos = new FileOutputStream(new File(outDir, name))) {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
        }
        bmp.recycle();
    }

    @Test public void oneUiAuroraIconRendersCleanly() throws Exception {
        Context ctx = RuntimeEnvironment.getApplication();
        Drawable icon = ctx.getDrawable(R.mipmap.ic_launcher);
        assertNotNull(icon);
        Bitmap bmp = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        icon.setBounds(0, 0, 256, 256);
        icon.draw(c);
        try (FileOutputStream fos = new FileOutputStream(new File(outDir, "icon.png"))) {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
        }
        bmp.recycle();
    }

    @Test public void crx3ExtensionEngineUnpacksAndBlocksDnr() throws Exception {
        Context ctx = RuntimeEnvironment.getApplication();
        String cwsUrl = "https://chromewebstore.google.com/detail/dark-reader/eimadpbcbfnmbkopoojfekhnkhdbieeh";
        assertEquals("eimadpbcbfnmbkopoojfekhnkhdbieeh", ExtensionManager.extractStoreId(cwsUrl));
        assertTrue(ExtensionManager.isChromeWebStore(cwsUrl));

        // Build real binary CRX3 package (Cr24 + ver=3 + 12-byte header + MV3 ZIP)
        ByteArrayOutputStream zipBaos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(zipBaos)) {
            putZip(zos, "manifest.json", "{"
                    + "\"manifest_version\":3,\"name\":\"__MSG_appName__\",\"version\":\"4.9.0\","
                    + "\"default_locale\":\"ko\","
                    + "\"action\":{\"default_popup\":\"popup.html\"},"
                    + "\"content_scripts\":[{\"matches\":[\"https://*.example.com/*\"],\"js\":[\"content.js\"],\"css\":[\"dark.css\"]}],"
                    + "\"declarative_net_request\":{\"rule_resources\":[{\"id\":\"r1\",\"enabled\":true,\"path\":\"rules.json\"}]}"
                    + "}");
            putZip(zos, "_locales/ko/messages.json", "{\"appName\":{\"message\":\"다크 리더 테스트\"}}");
            putZip(zos, "content.js", "document.documentElement.dataset.auroraExt='ok';");
            putZip(zos, "dark.css", "body{filter:invert(1);}");
            putZip(zos, "popup.html", "<html><body>Popup OK</body></html>");
            putZip(zos, "rules.json", "[{\"id\":1,\"action\":{\"type\":\"block\"},\"condition\":{\"urlFilter\":\"||ads.tracker-demo.test^\"}}]");
        }
        byte[] zipBytes = zipBaos.toByteArray();
        ByteArrayOutputStream crx = new ByteArrayOutputStream();
        crx.write("Cr24".getBytes(StandardCharsets.US_ASCII));
        crx.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(3).array());
        crx.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(12).array());
        crx.write(new byte[12]); // dummy protobuf header
        crx.write(zipBytes);

        ExtensionManager.Ext ext = ExtensionManager.installCrxBytes(ctx, "eimadpbcbfnmbkopoojfekhnkhdbieeh", crx.toByteArray());
        assertEquals("다크 리더 테스트", ext.name);
        assertEquals("4.9.0", ext.version);
        assertEquals("popup.html", ext.popupPath);
        assertEquals(1, ext.scripts.size());
        assertTrue(ExtensionManager.matchesUrl("https://sub.example.com/page", ext.scripts.get(0).matches, ext.scripts.get(0).excludeMatches));
        assertFalse(ExtensionManager.matchesUrl("https://other.org/", ext.scripts.get(0).matches, ext.scripts.get(0).excludeMatches));
        assertTrue(ExtensionManager.isBlockedByDnr("https://ads.tracker-demo.test/banner.js"));
        assertTrue(ExtensionManager.buildWebExtPolyfill(ext).contains("chrome.storage"));
    }

    @Test public void compatibleUserAgentSyncAndPwaHelpers() throws Exception {
        Context ctx = RuntimeEnvironment.getApplication();
        BrowserStore.load(ctx);
        TabManager tm = new TabManager(ctx, new android.widget.FrameLayout(ctx), new TabManager.Listener() {
            @Override public void onTabsChanged() {}
            @Override public void onPageUpdated(TabManager.Tab tab) {}
            @Override public void onProgress(TabManager.Tab tab, int progress) {}
            @Override public void onScrollDelta(int dy) {}
            @Override public void onFullscreenShow(View view, android.webkit.WebChromeClient.CustomViewCallback cb) {}
            @Override public void onFullscreenHide() {}
            @Override public void onFileChooser(android.webkit.ValueCallback<android.net.Uri[]> cb, android.webkit.WebChromeClient.FileChooserParams params) {}
            @Override public void onInstallStoreExtension(String extId) {}
        });
        assertFalse(tm.getMobileUa().contains("; wv)"));
        assertFalse(tm.getMobileUa().contains("Version/4.0 "));
        assertTrue(tm.getDesktopUa().contains("X11; Linux x86_64"));
        assertTrue(TabManager.isTrackerHost("pagead2.googlesyndication.com"));
        assertFalse(TabManager.isTrackerHost("www.google.com"));

        // Sync snapshot & merge
        JSONObject remote = new JSONObject()
                .put("bookmarks", new JSONArray().put(new JSONObject().put("title", "Synced Site").put("url", "https://synced.example.com").put("color", 0xFF0A84FF)))
                .put("extensions", new JSONArray().put(new JSONObject().put("id", "abcdefghijklmnopabcdefghijklmnop").put("name", "RemoteExt")));
        List<String> missingExts = SyncEngine.mergeSnapshot(ctx, remote);
        assertTrue(BrowserStore.isBookmarked("https://synced.example.com"));
        assertEquals(1, missingExts.size());
        assertNotNull(WebAppInstaller.renderAppIconBitmap("Aurora", 0xFF0A84FF, null));
    }

    @Test public void securityGuardVpnAndGoogleTranslateParsingWork() {
        Context ctx = RuntimeEnvironment.getApplication();
        BrowserStore.load(ctx);
        Ui.securityShield = true;
        Ui.httpsOnly = true;

        assertEquals("https://example.com/path", SecurityGuard.maybeUpgradeHttps("http://example.com/path"));
        assertEquals("http://localhost:8080/", SecurityGuard.maybeUpgradeHttps("http://localhost:8080/"));
        assertEquals("https://example.com/article?id=42",
                SecurityGuard.sanitizeUrl("https://example.com/article?id=42&utm_source=x&fbclid=abc"));
        assertEquals(SecurityGuard.RISK_DANGEROUS,
                SecurityGuard.assessUrl("https://google.com@evil.example/login").risk);
        assertEquals(SecurityGuard.RISK_DANGEROUS,
                SecurityGuard.assessUrl("https://malware.testing.google.test/").risk);
        assertEquals(SecurityGuard.RISK_SAFE,
                SecurityGuard.assessUrl("https://www.google.com/").risk);
        assertTrue(SecurityGuard.isDangerousDownload("update.apk"));
        assertFalse(SecurityGuard.isDangerousDownload("report.pdf"));

        // Per-site NordVPN routing rules
        assertEquals("NordVPN", SecurityGuard.vpnLabel("nordvpn"));
        assertEquals("com.nordvpn.android", SecurityGuard.vpnPackage("nordvpn"));
        BrowserStore.addVpnDomain(ctx, "https://www.reddit.com/r/android");
        assertTrue(SecurityGuard.requiresVpnForUrl("https://old.reddit.com/r/all"));
        assertFalse(SecurityGuard.requiresVpnForUrl("https://www.naver.com"));
        BrowserStore.removeVpnDomain(ctx, "reddit.com");

        // Google Translate JSON parser
        String sample = "[[[\"안녕하세요 \",\"Hello \",null,null,10],[\"세계\",\"world\",null,null,10]],null,\"en\"]";
        assertEquals("안녕하세요 세계", PageTranslator.parseGoogleTranslateJson(sample));
    }

    @Test @Config(qualifiers = "ko-rKR-w412dp-h915dp-xxhdpi")
    public void safariCompactBarStartPageTabsAndSheetsRender() throws Exception {
        ActivityController<MainActivity> ctrl = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity act = ctrl.get();
        assertEquals("오로라", act.getString(R.string.app_name));
        assertEquals("9.5.5", MainActivity.VERSION);
        assertEquals(0, Ui.layoutMode);

        // 1. Light Start Page + iOS 26 Safari Compact Liquid Glass Pill
        shot(act.root, 1236, 2745, "phone-start-light.png");

        // 2. Open Safari '...' Action Menu Sheet (morphed from moreBtn anchor)
        act.onMoreMenu(act.safariBar.moreBtnAnchor());
        shot(act.root, 1236, 2745, "phone-menu.png");

        // 3. Security & NordVPN Sheet + Extensions + Settings
        act.sheet.showSecurityVpnSheet(act);
        shot(act.root, 1236, 2745, "phone-security-vpn.png");
        act.sheet.showExtensionsSheet(act);
        shot(act.root, 1236, 2745, "phone-extensions.png");
        act.sheet.showSettingsSheet(act);
        shot(act.root, 1236, 2745, "phone-settings.png");
        act.sheet.dismiss();

        // 4. Spatial Tab Overview (Normal & Private Browsing)
        act.tabs.newTab(false, null, false);
        act.tabs.newTab(true, null, false);
        act.showTabOverview(true);
        shot(act.root, 1236, 2745, "phone-tabs.png");

        // Back button closes Tab Overview instead of exiting Aurora
        act.onBackPressed();
        assertEquals(View.GONE, act.tabOverview.getVisibility());
        assertFalse(act.isFinishing());
        act.showTabOverview(true);

        // 5. Switch into Private Browsing Start Page
        act.tabs.selectTab(true, 0);
        act.showTabOverview(false);
        assertTrue(Ui.privateTheme);
        shot(act.root, 1236, 2745, "phone-private.png");

        // 6. Classic Bottom 5-icon Layout (Normal Mode)
        act.tabs.selectTab(false, 0);
        Ui.layoutMode = 1;
        act.applyUiSettings();
        shot(act.root, 1236, 2745, "phone-bottom-classic.png");
    }

    @Test @Config(qualifiers = "ko-rKR-w900dp-h640dp-xhdpi")
    public void foldableAndTabletWideLayoutRendersCleanly() throws Exception {
        ActivityController<MainActivity> ctrl = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity act = ctrl.get();
        act.tabs.newTab(false, "https://github.com", false);
        act.tabs.newTab(false, "https://www.google.com", false);
        act.safariBar.updateTabList(act.tabs.activeList(), act.tabs.activeIndex());
        assertTrue(Ui.isWide());
        shot(act.root, 1800, 1280, "tablet-wide-start.png");

        act.onMoreMenu(act.safariBar.moreBtnAnchor());
        shot(act.root, 1800, 1280, "tablet-wide-popover.png");
    }

    private static void putZip(ZipOutputStream zos, String name, String content) throws Exception {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(content.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }
}
