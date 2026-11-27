package com.oneuibook.aurora;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Real Chrome Web Store CRX3 / CRX2 Extension Engine for Aurora Android.
 * - Detects Chrome Web Store detail pages and extracts 32-char extension IDs.
 * - Downloads genuine .crx packages from Google's official update2/crx endpoint.
 * - Unpacks CRX3 (magic Cr24, ver=3, protobuf header) and CRX2 (ver=2) ZIP archives.
 * - Parses MV2 & MV3 manifest.json, _locales i18n, icons, content_scripts (JS/CSS + match patterns),
 *   action/browser_action default_popup, and declarative_net_request block rules.
 * - Injects a working chrome.* / browser.* WebExtension polyfill + matching content scripts into pages.
 */
final class ExtensionManager {
    private ExtensionManager() {}

    static final ExecutorService IO = Executors.newCachedThreadPool();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Pattern CWS_ID = Pattern.compile("(?:chromewebstore\\.google\\.com|chrome\\.google\\.com/webstore)/detail/(?:[^/]+/)?([a-p]{32})", Pattern.CASE_INSENSITIVE);
    private static final Pattern RAW_ID = Pattern.compile("^[a-p]{32}$");

    static final class ContentScript {
        final List<String> matches = new ArrayList<>();
        final List<String> excludeMatches = new ArrayList<>();
        final List<String> jsFiles = new ArrayList<>();
        final List<String> cssFiles = new ArrayList<>();
        String runAt = "document_idle";
    }

    static final class Ext {
        final String id;
        final String name;
        final String version;
        final String description;
        final String popupPath;
        final String iconPath;
        final File dir;
        final List<ContentScript> scripts = new ArrayList<>();
        final List<String> dnrBlockSubstrings = new ArrayList<>();
        final Map<String, String> messages = new HashMap<>();
        boolean enabled = true;
        Bitmap cachedIcon;

        Ext(String id, String name, String version, String desc, String popupPath, String iconPath, File dir) {
            this.id = id; this.name = name; this.version = version; this.description = desc;
            this.popupPath = popupPath; this.iconPath = iconPath; this.dir = dir;
        }
    }

    interface InstallCallback {
        void onProgress(String msg);
        void onDone(boolean ok, String msg, Ext ext);
    }

    static final List<Ext> installed = new ArrayList<>();
    private static volatile Set<String> dnrBlockedCache = java.util.Collections.emptySet();
    private static boolean loaded;

    static File extRoot(Context c) {
        File d = new File(c.getFilesDir(), "extensions");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    static String extractStoreId(String input) {
        if (input == null) return null;
        String t = input.trim();
        if (RAW_ID.matcher(t.toLowerCase(Locale.ROOT)).matches()) return t.toLowerCase(Locale.ROOT);
        Matcher m = CWS_ID.matcher(t);
        return m.find() ? m.group(1).toLowerCase(Locale.ROOT) : null;
    }

    static boolean isChromeWebStore(String url) {
        if (url == null) return false;
        String l = url.toLowerCase(Locale.ROOT);
        return l.contains("chromewebstore.google.com") || l.contains("chrome.google.com/webstore");
    }

    static synchronized void loadAll(Context c) {
        if (loaded) return;
        loaded = true;
        installed.clear();
        dnrBlockedCache = java.util.Collections.emptySet();
        SharedPreferences sp = c.getSharedPreferences("aurora_exts", Context.MODE_PRIVATE);
        File root = extRoot(c);
        File[] dirs = root.listFiles();
        if (dirs == null) return;
        for (File d : dirs) {
            if (!d.isDirectory()) continue;
            File mf = new File(d, "manifest.json");
            if (!mf.exists()) continue;
            try {
                Ext e = parseManifest(d.getName(), d);
                if (e != null) {
                    e.enabled = sp.getBoolean("en_" + e.id, true);
                    installed.add(e);
                }
            } catch (Throwable ignored) {}
        }
        rebuildDnrIndex();
    }

    static void setEnabled(Context c, String id, boolean on) {
        for (Ext e : installed) {
            if (e.id.equals(id)) {
                e.enabled = on;
                c.getSharedPreferences("aurora_exts", Context.MODE_PRIVATE).edit().putBoolean("en_" + id, on).apply();
                break;
            }
        }
        rebuildDnrIndex();
    }

    static void remove(Context c, String id) {
        for (int i = 0; i < installed.size(); i++) {
            if (installed.get(i).id.equals(id)) {
                deleteRec(installed.get(i).dir);
                installed.remove(i);
                break;
            }
        }
        c.getSharedPreferences("aurora_exts", Context.MODE_PRIVATE).edit().remove("en_" + id).apply();
        rebuildDnrIndex();
    }

    private static synchronized void rebuildDnrIndex() {
        Set<String> next = new HashSet<>();
        for (Ext e : installed) {
            if (e.enabled) next.addAll(e.dnrBlockSubstrings);
        }
        dnrBlockedCache = java.util.Collections.unmodifiableSet(next);
    }

    static boolean isBlockedByDnr(String url) {
        Set<String> snap = dnrBlockedCache;
        if (url == null || snap.isEmpty()) return false;
        String low = url.toLowerCase(Locale.ROOT);
        for (String rule : snap) {
            if (!rule.isEmpty() && low.contains(rule)) return true;
        }
        return false;
    }

    /** Downloads a real .crx from Chrome Web Store and installs it into Aurora. */
    static void installFromChromeWebStore(Context ctx, String idOrUrl, String chromiumVersion, InstallCallback cb) {
        String id = extractStoreId(idOrUrl);
        if (id == null) {
            MAIN.post(() -> cb.onDone(false, "올바른 Chrome 웹 스토어 주소 또는 32자 확장 ID가 아닙니다.", null));
            return;
        }
        Context app = ctx.getApplicationContext();
        String ver = (chromiumVersion != null && chromiumVersion.matches("\\d+\\.\\d+\\.\\d+\\.\\d+"))
                ? chromiumVersion : "131.0.6778.200";
        String crxUrl = "https://clients2.google.com/service/update2/crx?response=redirect&prodversion="
                + ver + "&acceptformat=crx2,crx3&x=id%3D" + id + "%26uc";

        MAIN.post(() -> cb.onProgress("Chrome 웹 스토어에서 패키지 다운로드 중..."));
        IO.execute(() -> {
            HttpURLConnection conn = null;
            try {
                conn = openRedirected(crxUrl);
                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) {
                    throw new Exception("스토어 응답 오류 (HTTP " + code + ")");
                }
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                try (InputStream is = new BufferedInputStream(conn.getInputStream(), 32768)) {
                    byte[] buf = new byte[32768];
                    int n;
                    while ((n = is.read(buf)) > 0) {
                        bos.write(buf, 0, n);
                        if (bos.size() > 35 * 1024 * 1024) throw new Exception("확장 프로그램 크기가 너무 큽니다 (35MB 초과)");
                    }
                }
                byte[] crxBytes = bos.toByteArray();
                MAIN.post(() -> cb.onProgress("CRX 패키지 압축 해제 및 분석 중..."));
                Ext ext = installCrxBytes(app, id, crxBytes);
                MAIN.post(() -> cb.onDone(true, ext.name + " (" + ext.version + ") 설치 완료", ext));
            } catch (Throwable t) {
                String msg = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
                MAIN.post(() -> cb.onDone(false, "설치 실패: " + msg, null));
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    /** Parses CRX3 / CRX2 / ZIP bytes, unpacks into filesDir/extensions/<id>, and registers the extension. */
    static Ext installCrxBytes(Context c, String id, byte[] crxBytes) throws Exception {
        byte[] zipBytes = stripCrxHeader(crxBytes);
        File targetDir = new File(extRoot(c), id);
        if (targetDir.exists()) deleteRec(targetDir);
        targetDir.mkdirs();

        String canonRoot = targetDir.getCanonicalPath() + File.separator;
        int extracted = 0;
        long totalBytes = 0L;
        final long MAX_UNPACK_BYTES = 60L * 1024 * 1024;
        final int MAX_ENTRIES = 1500;
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry ze;
            byte[] buf = new byte[16384];
            while ((ze = zis.getNextEntry()) != null) {
                if (extracted >= MAX_ENTRIES) throw new Exception("확장 프로그램 파일 수가 허용 한도를 초과했습니다");
                String name = ze.getName().replace('\\', '/');
                while (name.startsWith("/")) name = name.substring(1);
                if (name.isEmpty() || name.contains("..")) continue;
                File out = new File(targetDir, name);
                if (!out.getCanonicalPath().startsWith(canonRoot)) continue;
                if (ze.isDirectory()) {
                    out.mkdirs();
                } else {
                    if (out.getParentFile() != null) out.getParentFile().mkdirs();
                    try (FileOutputStream fos = new FileOutputStream(out)) {
                        int n;
                        while ((n = zis.read(buf)) > 0) {
                            totalBytes += n;
                            if (totalBytes > MAX_UNPACK_BYTES) throw new Exception("확장 프로그램 압축 해제 크기가 한도를 초과했습니다");
                            fos.write(buf, 0, n);
                        }
                    }
                    extracted++;
                }
            }
        }
        if (extracted == 0 || !new File(targetDir, "manifest.json").exists()) {
            deleteRec(targetDir);
            throw new Exception("유효한 manifest.json을 찾을 수 없습니다");
        }
        Ext ext = parseManifest(id, targetDir);
        if (ext == null) throw new Exception("manifest.json 파싱 실패");
        synchronized (ExtensionManager.class) {
            for (int i = 0; i < installed.size(); i++) {
                if (installed.get(i).id.equals(id)) { installed.remove(i); break; }
            }
            installed.add(0, ext);
            c.getSharedPreferences("aurora_exts", Context.MODE_PRIVATE).edit().putBoolean("en_" + id, true).apply();
            rebuildDnrIndex();
        }
        return ext;
    }

    /** Strips CRX3 or CRX2 binary header and returns the embedded ZIP bytes. */
    static byte[] stripCrxHeader(byte[] raw) throws Exception {
        if (raw == null || raw.length < 16) throw new Exception("빈 패키지입니다");
        // Raw ZIP?
        if (raw[0] == 'P' && raw[1] == 'K' && raw[2] == 3 && raw[3] == 4) return raw;
        // CRX magic "Cr24"
        if (raw[0] == 'C' && raw[1] == 'r' && raw[2] == '2' && raw[3] == '4') {
            int ver = ByteBuffer.wrap(raw, 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
            int zipOff;
            if (ver == 3) {
                int hdrSize = ByteBuffer.wrap(raw, 8, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
                zipOff = 12 + hdrSize;
            } else if (ver == 2) {
                int pubLen = ByteBuffer.wrap(raw, 8, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
                int sigLen = ByteBuffer.wrap(raw, 12, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
                zipOff = 16 + pubLen + sigLen;
            } else {
                throw new Exception("지원하지 않는 CRX 버전: " + ver);
            }
            if (zipOff < 0 || zipOff >= raw.length - 4) throw new Exception("손상된 CRX 헤더");
            byte[] out = new byte[raw.length - zipOff];
            System.arraycopy(raw, zipOff, out, 0, out.length);
            return out;
        }
        // Fallback: scan for PK\x03\x04
        for (int i = 0; i < Math.min(raw.length - 4, 8192); i++) {
            if (raw[i] == 'P' && raw[i + 1] == 'K' && raw[i + 2] == 3 && raw[i + 3] == 4) {
                byte[] out = new byte[raw.length - i];
                System.arraycopy(raw, i, out, 0, out.length);
                return out;
            }
        }
        throw new Exception("CRX/ZIP 서명을 찾을 수 없습니다");
    }

    private static Ext parseManifest(String id, File dir) throws Exception {
        String raw = readText(new File(dir, "manifest.json"));
        if (raw.startsWith("\uFEFF")) raw = raw.substring(1);
        JSONObject m = new JSONObject(raw);
        Map<String, String> msgs = loadLocales(dir, m.optString("default_locale", "en"));

        String name = resolveMsg(m.optString("name", id), msgs);
        String ver = m.optString("version", "1.0");
        String desc = resolveMsg(m.optString("description", ""), msgs);

        // Icon (prefer 128 -> 64 -> 48 -> any)
        String iconPath = null;
        JSONObject icons = m.optJSONObject("icons");
        if (icons != null) {
            for (String sz : new String[]{"128", "96", "64", "48", "32", "16"}) {
                if (icons.has(sz)) { iconPath = icons.optString(sz); break; }
            }
        }

        // Popup HTML (MV3 action or MV2 browser_action / page_action)
        String popupPath = null;
        for (String k : new String[]{"action", "browser_action", "page_action"}) {
            JSONObject act = m.optJSONObject(k);
            if (act != null && act.has("default_popup")) {
                popupPath = act.optString("default_popup");
                break;
            }
        }

        Ext ext = new Ext(id, name, ver, desc, popupPath, iconPath, dir);
        ext.messages.putAll(msgs);

        // Content scripts
        JSONArray csArr = m.optJSONArray("content_scripts");
        if (csArr != null) {
            for (int i = 0; i < csArr.length(); i++) {
                JSONObject o = csArr.optJSONObject(i);
                if (o == null) continue;
                ContentScript cs = new ContentScript();
                cs.runAt = o.optString("run_at", "document_idle");
                copyStrings(o.optJSONArray("matches"), cs.matches);
                copyStrings(o.optJSONArray("exclude_matches"), cs.excludeMatches);
                copyStrings(o.optJSONArray("js"), cs.jsFiles);
                copyStrings(o.optJSONArray("css"), cs.cssFiles);
                ext.scripts.add(cs);
            }
        }

        // Declarative Net Request rules (MV3 ad/tracker blockers)
        JSONObject dnr = m.optJSONObject("declarative_net_request");
        if (dnr != null) {
            JSONArray rr = dnr.optJSONArray("rule_resources");
            if (rr != null) {
                for (int i = 0; i < rr.length(); i++) {
                    JSONObject r = rr.optJSONObject(i);
                    if (r != null && r.optBoolean("enabled", true)) {
                        loadDnrRules(new File(dir, r.optString("path", "")), ext.dnrBlockSubstrings);
                    }
                }
            }
        }
        return ext;
    }

    private static void loadDnrRules(File ruleFile, List<String> out) {
        if (!ruleFile.exists() || ruleFile.length() > 2 * 1024 * 1024) return;
        try {
            JSONArray arr = new JSONArray(readText(ruleFile));
            for (int i = 0; i < Math.min(arr.length(), 800); i++) {
                JSONObject rule = arr.optJSONObject(i);
                if (rule == null) continue;
                JSONObject act = rule.optJSONObject("action");
                JSONObject cond = rule.optJSONObject("condition");
                if (act != null && "block".equals(act.optString("type")) && cond != null) {
                    String f = cond.optString("urlFilter", "");
                    if (f.startsWith("||")) f = f.substring(2);
                    if (f.endsWith("^")) f = f.substring(0, f.length() - 1);
                    f = f.replace("*", "").trim().toLowerCase(Locale.ROOT);
                    if (f.length() >= 4 && !f.contains("|")) out.add(f);
                }
            }
        } catch (Throwable ignored) {}
    }

    private static final java.util.WeakHashMap<WebView, Bridge> BRIDGES = new java.util.WeakHashMap<>();

    /** Injects chrome.* polyfill, matching extension content scripts, and Chrome Web Store install helper. */
    static void injectForPage(Context c, WebView wv, String url, boolean atStart) {
        if (url == null || !url.startsWith("http")) return;
        loadAll(c);
        Bridge bridge;
        synchronized (BRIDGES) { bridge = BRIDGES.get(wv); }

        // 1. Chrome Web Store: inject sleek Aurora Install pill with per-WebView cryptographic nonce!
        if (!atStart && isChromeWebStore(url) && bridge != null) {
            String installNonce = bridge.issueInstallNonce();
            String js = "(function(){"
                    + "if(window.__auroraCwsHook) return; window.__auroraCwsHook=true;"
                    + "var __cwsNonce=" + JSONObject.quote(installNonce) + ";"
                    + "function syncBtn(){"
                    + "  var m=location.href.match(/(?:chromewebstore\\.google\\.com|chrome\\.google\\.com\\/webstore)\\/detail\\/(?:[^\\/]+\\/)?([a-p]{32})/i);"
                    + "  var old=document.getElementById('aurora-cws-bar');"
                    + "  if(!m){ if(old) old.remove(); return; }"
                    + "  var id=m[1].toLowerCase();"
                    + "  if(!old){"
                    + "    old=document.createElement('div'); old.id='aurora-cws-bar';"
                    + "    old.style.cssText='position:fixed;top:12px;left:50%;transform:translateX(-50%);z-index:2147483647;"
                    + "background:rgba(16,24,48,0.88);backdrop-filter:blur(22px);-webkit-backdrop-filter:blur(22px);"
                    + "color:#fff;padding:10px 16px;border-radius:999px;display:flex;align-items:center;gap:10px;"
                    + "box-shadow:0 10px 30px rgba(0,0,0,0.32),inset 0 1px 0 rgba(255,255,255,0.28);font:600 13.5px sans-serif;';"
                    + "    var lbl=document.createElement('span'); lbl.textContent='오로라 확장 프로그램';"
                    + "    var btn=document.createElement('button'); btn.id='aurora-cws-go';"
                    + "    btn.style.cssText='background:#0A84FF;color:#fff;border:0;border-radius:999px;padding:7px 14px;font:700 13px sans-serif;cursor:pointer;';"
                    + "    btn.textContent='오로라에 추가';"
                    + "    btn.onclick=function(){ if(window.AuroraExt) window.AuroraExt.installStoreExtension(__cwsNonce,btn.dataset.extId); };"
                    + "    old.appendChild(lbl); old.appendChild(btn); document.body.appendChild(old);"
                    + "  }"
                    + "  var b=document.getElementById('aurora-cws-go'); if(b) b.dataset.extId=id;"
                    + "}"
                    + "syncBtn(); setInterval(syncBtn, 1200);"
                    + "})();";
            wv.evaluateJavascript(js, null);
        }

        // 2. Run matching content scripts from enabled extensions
        for (Ext ext : installed) {
            if (!ext.enabled) continue;
            for (ContentScript cs : ext.scripts) {
                boolean wantNow = atStart ? "document_start".equals(cs.runAt) : !"document_start".equals(cs.runAt);
                if (!wantNow) continue;
                if (!matchesUrl(url, cs.matches, cs.excludeMatches)) continue;

                // Inject CSS files
                for (String cssRel : cs.cssFiles) {
                    String css = readTextSafe(new File(ext.dir, cssRel));
                    if (!css.isEmpty()) {
                        String b64 = Base64.encodeToString(css.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
                        wv.evaluateJavascript("(function(){var s=document.createElement('style');s.textContent=decodeURIComponent(escape(atob('" + b64 + "')));(document.head||document.documentElement).appendChild(s);})();", null);
                    }
                }
                // Inject chrome.* polyfill + JS files (isolated token per extension)
                if (!cs.jsFiles.isEmpty()) {
                    String tok = bridge != null ? bridge.issueStorageToken(ext.id) : "";
                    String polyfill = buildWebExtPolyfill(ext, tok);
                    StringBuilder bundle = new StringBuilder("(function(){\ntry{\n").append(polyfill).append("\n");
                    for (String jsRel : cs.jsFiles) {
                        String code = readTextSafe(new File(ext.dir, jsRel));
                        if (!code.isEmpty()) bundle.append("\n;(function(){\n").append(code).append("\n})();\n");
                    }
                    bundle.append("\n}catch(e){console.warn('[AuroraExt]',e);}\n})();");
                    wv.evaluateJavascript(bundle.toString(), null);
                }
            }
        }
    }

    static String buildWebExtPolyfill(Ext ext) {
        return buildWebExtPolyfill(ext, "");
    }

    static String buildWebExtPolyfill(Ext ext, String storageToken) {
        String mfJson = readTextSafe(new File(ext.dir, "manifest.json"));
        if (mfJson.isEmpty()) mfJson = "{}";
        String mfB64 = Base64.encodeToString(mfJson.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        JSONObject msgObj = new JSONObject(ext.messages);
        String msgB64 = Base64.encodeToString(msgObj.toString().getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);

        return "var __extId=" + JSONObject.quote(ext.id) + ";"
                + "var __extTok=" + JSONObject.quote(storageToken == null ? "" : storageToken) + ";"
                + "var __mf=JSON.parse(decodeURIComponent(escape(atob('" + mfB64 + "'))));"
                + "var __msgs=JSON.parse(decodeURIComponent(escape(atob('" + msgB64 + "'))));"
                + "function __mkStore(area){return {"
                + "  get:function(keys,cb){var res={};try{var raw=window.AuroraExt?window.AuroraExt.storageGet(__extTok,__extId,area):'{}';var all=JSON.parse(raw||'{}');"
                + "    if(!keys){res=all;}else if(typeof keys==='string'){if(keys in all)res[keys]=all[keys];}"
                + "    else if(Array.isArray(keys)){keys.forEach(function(k){if(k in all)res[k]=all[k];});}"
                + "    else{Object.keys(keys).forEach(function(k){res[k]=(k in all)?all[k]:keys[k];});}"
                + "  }catch(e){} if(cb)cb(res); return Promise.resolve(res);},"
                + "  set:function(obj,cb){try{if(window.AuroraExt)window.AuroraExt.storageSet(__extTok,__extId,area,JSON.stringify(obj||{}));}catch(e){} if(cb)cb(); return Promise.resolve();},"
                + "  remove:function(keys,cb){try{if(window.AuroraExt)window.AuroraExt.storageRemove(__extTok,__extId,area,JSON.stringify(Array.isArray(keys)?keys:[keys]));}catch(e){} if(cb)cb(); return Promise.resolve();},"
                + "  clear:function(cb){try{if(window.AuroraExt)window.AuroraExt.storageClear(__extTok,__extId,area);}catch(e){} if(cb)cb(); return Promise.resolve();}"
                + "};}"
                + "var chrome=window.chrome||{};"
                + "chrome.runtime=chrome.runtime||{"
                + "  id:__extId, getManifest:function(){return __mf;},"
                + "  getURL:function(p){return 'https://aurora-ext.local/'+__extId+'/'+String(p||'').replace(/^\\//,'');},"
                + "  sendMessage:function(m,cb){if(cb)cb({});return Promise.resolve({});},"
                + "  onMessage:{addListener:function(){},removeListener:function(){}}"
                + "};"
                + "chrome.storage=chrome.storage||{local:__mkStore('local'),sync:__mkStore('sync'),onChanged:{addListener:function(){}}};"
                + "chrome.i18n=chrome.i18n||{getMessage:function(k){return __msgs[k]||k;},getUILanguage:function(){return navigator.language||'ko';}};"
                + "chrome.tabs=chrome.tabs||{query:function(q,cb){var r=[{id:1,active:true,url:location.href,title:document.title}];if(cb)cb(r);return Promise.resolve(r);}};"
                + "window.chrome=chrome; window.browser=chrome;";
    }

    private static String randomNonce() {
        byte[] b = new byte[16];
        new java.security.SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder(32);
        for (byte x : b) sb.append(String.format(Locale.US, "%02x", x & 0xFF));
        return sb.toString();
    }

    /** Hardened native JS interface attached to WebViews as `window.AuroraExt` with cryptographic nonces & strict ID/area validation. */
    static final class Bridge {
        private final Context ctx;
        private final Callback cb;
        private volatile String installNonce = "";
        private final Map<String, String> extTokens = new java.util.concurrent.ConcurrentHashMap<>();

        interface Callback {
            void onInstallStoreExtension(String extId);
        }

        Bridge(Context c, Callback cb) {
            this(c, null, cb);
        }

        Bridge(Context c, WebView wv, Callback cb) {
            this.ctx = c.getApplicationContext();
            this.cb = cb;
            if (wv != null) synchronized (BRIDGES) { BRIDGES.put(wv, this); }
        }

        String issueInstallNonce() {
            String n = randomNonce();
            installNonce = n;
            return n;
        }

        String issueStorageToken(String extId) {
            if (extId == null || !RAW_ID.matcher(extId).matches()) return "";
            String tok = randomNonce();
            extTokens.put(extId, tok);
            return tok;
        }

        private boolean validStorageCall(String token, String extId, String area) {
            if (token == null || token.isEmpty() || extId == null || area == null) return false;
            if (!RAW_ID.matcher(extId).matches()) return false;
            if (!"local".equals(area) && !"sync".equals(area)) return false;
            String expected = extTokens.get(extId);
            return expected != null && java.security.MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
        }

        private SharedPreferences storePrefs(String extId, String area) {
            return ctx.getSharedPreferences("aurora_ext_kv_" + extId + "_" + area, Context.MODE_PRIVATE);
        }

        @JavascriptInterface public void installStoreExtension(String nonce, String extId) {
            if (cb == null || nonce == null || nonce.isEmpty() || extId == null) return;
            String expected = installNonce;
            if (expected == null || expected.isEmpty()) return;
            if (!java.security.MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), nonce.getBytes(StandardCharsets.UTF_8))) return;
            String cleanId = extractStoreId(extId);
            if (cleanId == null) return;
            MAIN.post(() -> cb.onInstallStoreExtension(cleanId));
        }

        @JavascriptInterface public String storageGet(String token, String extId, String area) {
            if (!validStorageCall(token, extId, area)) return "{}";
            return storePrefs(extId, area).getString("json", "{}");
        }

        @JavascriptInterface public void storageSet(String token, String extId, String area, String patchJson) {
            if (!validStorageCall(token, extId, area) || patchJson == null || patchJson.length() > 512 * 1024) return;
            try {
                SharedPreferences sp = storePrefs(extId, area);
                JSONObject cur = new JSONObject(sp.getString("json", "{}"));
                JSONObject patch = new JSONObject(patchJson);
                java.util.Iterator<String> it = patch.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    cur.put(k, patch.get(k));
                }
                sp.edit().putString("json", cur.toString()).apply();
            } catch (Exception ignored) {}
        }

        @JavascriptInterface public void storageRemove(String token, String extId, String area, String keysJson) {
            if (!validStorageCall(token, extId, area) || keysJson == null) return;
            try {
                SharedPreferences sp = storePrefs(extId, area);
                JSONObject cur = new JSONObject(sp.getString("json", "{}"));
                JSONArray ks = new JSONArray(keysJson);
                for (int i = 0; i < ks.length(); i++) cur.remove(ks.optString(i));
                sp.edit().putString("json", cur.toString()).apply();
            } catch (Exception ignored) {}
        }

        @JavascriptInterface public void storageClear(String token, String extId, String area) {
            if (!validStorageCall(token, extId, area)) return;
            storePrefs(extId, area).edit().putString("json", "{}").apply();
        }
    }

    static Bitmap iconOf(Ext ext) {
        if (ext.cachedIcon != null) return ext.cachedIcon;
        if (ext.iconPath != null && !ext.iconPath.isEmpty()) {
            File f = new File(ext.dir, ext.iconPath);
            if (f.exists()) ext.cachedIcon = BitmapFactory.decodeFile(f.getAbsolutePath());
        }
        return ext.cachedIcon;
    }

    static boolean matchesUrl(String url, List<String> matches, List<String> excludes) {
        boolean matched = false;
        for (String p : matches) {
            if (matchPattern(url, p)) { matched = true; break; }
        }
        if (!matched) return false;
        for (String ex : excludes) {
            if (matchPattern(url, ex)) return false;
        }
        return true;
    }

    static boolean matchPattern(String url, String pattern) {
        if (pattern == null || pattern.isEmpty()) return false;
        if ("<all_urls>".equals(pattern)) return url.startsWith("http://") || url.startsWith("https://");
        StringBuilder rx = new StringBuilder("^");
        for (int i = 0; i < pattern.length(); i++) {
            char ch = pattern.charAt(i);
            if (ch == '*') rx.append(".*");
            else if ("\\.^$+?|()[]{}".indexOf(ch) >= 0) rx.append('\\').append(ch);
            else rx.append(ch);
        }
        rx.append('$');
        try { return Pattern.compile(rx.toString(), Pattern.CASE_INSENSITIVE).matcher(url).matches(); }
        catch (Exception e) { return false; }
    }

    private static Map<String, String> loadLocales(File dir, String defLocale) {
        Map<String, String> map = new HashMap<>();
        String[] tries = {defLocale, "en", "en_US", "ko"};
        for (String loc : tries) {
            if (loc == null || loc.isEmpty()) continue;
            File f = new File(dir, "_locales/" + loc + "/messages.json");
            if (!f.exists()) continue;
            try {
                JSONObject o = new JSONObject(readText(f));
                java.util.Iterator<String> it = o.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    JSONObject v = o.optJSONObject(k);
                    if (v != null && v.has("message")) map.put(k, v.optString("message"));
                }
            } catch (Throwable ignored) {}
        }
        return map;
    }

    private static String resolveMsg(String s, Map<String, String> msgs) {
        if (s != null && s.startsWith("__MSG_") && s.endsWith("__")) {
            String key = s.substring(6, s.length() - 2);
            String v = msgs.get(key);
            if (v != null && !v.isEmpty()) return v;
        }
        return s != null ? s : "";
    }

    private static void copyStrings(JSONArray a, List<String> dst) {
        if (a == null) return;
        for (int i = 0; i < a.length(); i++) {
            String s = a.optString(i, "");
            if (!s.isEmpty()) dst.add(s);
        }
    }

    static boolean isTrustedCwsHost(String url) {
        if (url == null || !url.startsWith("https://")) return false;
        try {
            String host = new URL(url).getHost().toLowerCase(Locale.ROOT);
            return host.equals("clients2.google.com") || host.endsWith(".googleusercontent.com")
                    || host.endsWith(".gvt1.com") || host.endsWith(".google.com");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static HttpURLConnection openRedirected(String raw) throws Exception {
        String cur = raw;
        for (int i = 0; i < 8; i++) {
            if (!isTrustedCwsHost(cur)) throw new Exception("신뢰할 수 없는 확장 프로그램 다운로드 호스트입니다");
            HttpURLConnection c = (HttpURLConnection) new URL(cur).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setRequestProperty("Accept", "*/*");
            int code = c.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null || loc.isEmpty()) throw new Exception("빈 리다이렉트");
                cur = new URL(new URL(cur), loc).toString();
                continue;
            }
            return c;
        }
        throw new Exception("리다이렉트 초과");
    }

    private static String readText(File f) throws Exception {
        try (InputStream is = new FileInputStream(f)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toString("UTF-8");
        }
    }

    static String readTextSafe(File f) {
        if (!f.exists() || !f.isFile() || f.length() > 2 * 1024 * 1024) return "";
        try { return readText(f); } catch (Throwable t) { return ""; }
    }

    private static void deleteRec(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRec(k);
        }
        f.delete();
    }
}
