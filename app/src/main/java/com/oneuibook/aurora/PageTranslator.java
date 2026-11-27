package com.oneuibook.aurora;

import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Reliable Google Translate webpage translator for Aurora Browser:
 * - Uses Google Translate's multi-q JSON array endpoint (translate_a/t?client=gtx&sl=auto&tl=...)
 *   so every text node maps 1:1 with zero fragile delimiter splitting and zero 429 per-node storms.
 * - Streams translated batches into the live DOM immediately chunk-by-chunk (viewport nodes first).
 * - Supports automatic foreign-page detection & Auto-Translate when enabled by the user.
 */
final class PageTranslator {
    private PageTranslator() {}

    static final String[] LANG_CODES = {"ko", "en", "ja", "zh-CN", "es", "fr", "de"};
    static final String[] LANG_LABELS = {"한국어", "English", "日本語", "中文", "Español", "Français", "Deutsch"};
    private static final ExecutorService IO = Executors.newFixedThreadPool(2);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final java.util.WeakHashMap<WebView, Bridge> BRIDGES = new java.util.WeakHashMap<>();

    static String labelFor(String code) {
        for (int i = 0; i < LANG_CODES.length; i++) if (LANG_CODES[i].equals(code)) return LANG_LABELS[i];
        return LANG_LABELS[0];
    }

    /** JavaScript interface exposed as window.AuroraTranslate inside every tab's WebView. */
    static final class Bridge {
        private final WebView webView;
        private final Runnable onTranslated;
        private volatile String activeNonce = "";

        Bridge(WebView wv, Runnable onTranslated) {
            this.webView = wv;
            this.onTranslated = onTranslated;
            synchronized (BRIDGES) { BRIDGES.put(wv, this); }
        }

        String issueNonce() {
            String n = Long.toHexString(System.nanoTime()) + "_" + Integer.toHexString((int) (Math.random() * 0xFFFFFF));
            activeNonce = n;
            return n;
        }

        @JavascriptInterface public void translateBatch(final String targetLang, final String itemsJson) {
            translateBatchAuth(activeNonce, targetLang, itemsJson);
        }

        @JavascriptInterface public void translateBatchAuth(final String nonce, final String targetLang, final String itemsJson) {
            if (nonce == null || nonce.isEmpty() || !nonce.equals(activeNonce)) return;
            final String expectedNonce = nonce;
            IO.execute(() -> {
                try {
                    JSONArray arr = new JSONArray(itemsJson);
                    List<Integer> ids = new ArrayList<>();
                    List<String> texts = new ArrayList<>();
                    int curBytes = 0;
                    boolean anyApplied = false;

                    for (int i = 0; i < arr.length(); i++) {
                        if (!expectedNonce.equals(activeNonce)) return;
                        JSONObject o = arr.getJSONObject(i);
                        int id = o.getInt("i");
                        String t = o.getString("t");
                        int est = t.getBytes(StandardCharsets.UTF_8).length * 3 + 12;
                        if ((curBytes + est > 2600 || texts.size() >= 28) && !texts.isEmpty()) {
                            if (streamChunk(expectedNonce, targetLang, ids, texts, !anyApplied)) anyApplied = true;
                            ids.clear(); texts.clear(); curBytes = 0;
                        }
                        ids.add(id);
                        texts.add(t);
                        curBytes += est;
                    }
                    if (!texts.isEmpty() && expectedNonce.equals(activeNonce)) {
                        streamChunk(expectedNonce, targetLang, ids, texts, !anyApplied);
                    }
                } catch (Throwable ignored) {}
            });
        }

        private boolean streamChunk(String expectedNonce, String targetLang, List<Integer> ids, List<String> texts, boolean notifyBanner) {
            List<String> translated = translateBatchSync(texts, targetLang);
            if (translated == null || translated.size() != texts.size()) return false;
            JSONArray out = new JSONArray();
            for (int i = 0; i < ids.size(); i++) {
                String tr = translated.get(i);
                if (tr != null && !tr.isEmpty()) {
                    try { out.put(new JSONObject().put("i", ids.get(i)).put("t", tr)); } catch (Exception ignored) {}
                }
            }
            if (out.length() == 0) return false;
            final String payload = out.toString();
            MAIN.post(() -> {
                if (!expectedNonce.equals(activeNonce)) return;
                if (webView != null && webView.isAttachedToWindow()) {
                    webView.evaluateJavascript(
                            "window.__auroraApplyTranslation&&window.__auroraApplyTranslation(" + JSONObject.quote(payload) + ");",
                            null);
                    if (notifyBanner && onTranslated != null) onTranslated.run();
                }
            });
            return true;
        }
    }

    /** Multi-q POST against Google Translate gtx endpoint: returns exact 1:1 translated list matching input texts. */
    static List<String> translateBatchSync(List<String> texts, String targetLang) {
        if (texts == null || texts.isEmpty()) return null;
        HttpURLConnection conn = null;
        try {
            String url = "https://translate.googleapis.com/translate_a/t?client=gtx&sl=auto&tl="
                    + URLEncoder.encode(targetLang, "UTF-8");
            StringBuilder form = new StringBuilder();
            for (int i = 0; i < texts.size(); i++) {
                if (i > 0) form.append('&');
                form.append("q=").append(URLEncoder.encode(texts.get(i), "UTF-8"));
            }
            byte[] body = form.toString().getBytes(StandardCharsets.UTF_8);
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("User-Agent", "Aurora/9.5 (Android)");
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            try (OutputStream os = conn.getOutputStream()) { os.write(body); }
            if (conn.getResponseCode() != 200) return null;
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
            return parseBatchJson(sb.toString(), texts.size());
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** Parses translate_a/t JSON response (handles both single-item and multi-item array formats). */
    static List<String> parseBatchJson(String json, int expectedSize) {
        try {
            JSONArray root = new JSONArray(json);
            List<String> out = new ArrayList<>(expectedSize);
            if (expectedSize == 1 && root.length() >= 1 && root.opt(0) instanceof String) {
                out.add(root.optString(0, ""));
                return out;
            }
            for (int i = 0; i < root.length(); i++) {
                Object item = root.opt(i);
                if (item instanceof JSONArray) {
                    out.add(((JSONArray) item).optString(0, ""));
                } else if (item instanceof String) {
                    out.add((String) item);
                }
            }
            return out.size() == expectedSize ? out : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Convenience single-text translator kept for tests and helpers. */
    static String translateTextSync(String text, String targetLang) {
        if (text == null || text.trim().isEmpty()) return "";
        List<String> one = new ArrayList<>(1);
        one.add(text);
        List<String> res = translateBatchSync(one, targetLang);
        return (res != null && !res.isEmpty()) ? res.get(0) : "";
    }

    /** Pure testable parser for Google Translate gtx JSON array response. */
    static String parseGoogleTranslateJson(String json) {
        try {
            JSONArray root = new JSONArray(json);
            JSONArray segs = root.optJSONArray(0);
            if (segs == null) return "";
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < segs.length(); i++) {
                JSONArray pair = segs.optJSONArray(i);
                if (pair != null && !pair.isNull(0)) out.append(pair.optString(0, ""));
            }
            return out.toString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** Translates visible DOM text nodes cleanly (viewport nodes first, skipping text already in target script). */
    static void translatePage(WebView wv, String targetLang) {
        if (wv == null) return;
        Bridge b;
        synchronized (BRIDGES) { b = BRIDGES.get(wv); }
        String nonce = b != null ? b.issueNonce() : "";
        String js = "(function(){"
                + "if(!window.AuroraTranslate||!document.body)return;"
                + "var tl=" + JSONObject.quote(targetLang) + ";"
                + "var skip={SCRIPT:1,STYLE:1,NOSCRIPT:1,CODE:1,PRE:1,TEXTAREA:1,INPUT:1,SVG:1,TEMPLATE:1,HEAD:1,TITLE:1};"
                + "if(!window.__auroraOrigMap){window.__auroraOrigMap=new WeakMap();}"
                + "var nodes=[];var visibleFirst=[];var rest=[];"
                + "var vh=window.innerHeight||800;"
                + "var w=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT,null);"
                + "var n;while((n=w.nextNode())&&(visibleFirst.length+rest.length)<460){"
                + "  var p=n.parentElement;if(!p||skip[p.tagName])continue;"
                + "  var orig=window.__auroraOrigMap.has(n)?window.__auroraOrigMap.get(n):n.nodeValue;"
                + "  var s=(orig||'').trim();if(s.length<2||s.length>1200||/^[\\d\\s\\p{P}\\p{S}]+$/u.test(s))continue;"
                + "  if(tl==='ko'&&/[\\uAC00-\\uD7A3]/.test(s)&&!/[A-Za-z\\u3040-\\u30FF\\u4E00-\\u9FFF]{3,}/.test(s))continue;"
                + "  var rect=p.getBoundingClientRect();"
                + "  if(rect.width===0&&rect.height===0)continue;"
                + "  if(!window.__auroraOrigMap.has(n))window.__auroraOrigMap.set(n,orig);"
                + "  var item={n:n,o:orig,s:s};"
                + "  if(rect.bottom>=0&&rect.top<=vh*1.3)visibleFirst.push(item);else rest.push(item);"
                + "}"
                + "nodes=visibleFirst.concat(rest);"
                + "window.__auroraNodes=nodes;"
                + "var batch=[];"
                + "for(var i=0;i<nodes.length;i++){batch.push({i:i,t:nodes[i].s});}"
                + "if(batch.length===0)return;"
                + "window.__auroraApplyTranslation=function(raw){"
                + "  try{var arr=JSON.parse(raw);for(var k=0;k<arr.length;k++){"
                + "    var it=arr[k];var entry=window.__auroraNodes&&window.__auroraNodes[it.i];"
                + "    if(entry&&entry.n){"
                + "      var lead=(entry.o.match(/^\\s*/)||{0:''})[0];"
                + "      var trail=(entry.o.match(/\\s*$/)||{0:''})[0];"
                + "      entry.n.nodeValue=lead+it.t+trail;"
                + "    }"
                + "  }}catch(e){}"
                + "};"
                + "window.AuroraTranslate.translateBatchAuth(" + JSONObject.quote(nonce) + ",tl,JSON.stringify(batch));"
                + "})();";
        wv.evaluateJavascript(js, null);
    }

    /** Automatically detects if the loaded webpage is in a foreign language and translates it into targetLang. */
    static void autoTranslateIfForeign(WebView wv, String targetLang) {
        if (wv == null || targetLang == null || targetLang.isEmpty()) return;
        String baseLang = targetLang.toLowerCase(Locale.ROOT).split("-")[0];
        String detectJs = "(function(){"
                + "var lang=(document.documentElement.lang||'').toLowerCase();"
                + "var txt=(document.body&&document.body.innerText?document.body.innerText:'').slice(0,1200);"
                + "var hangul=(txt.match(/[\\uAC00-\\uD7A3]/g)||[]).length;"
                + "var kana=(txt.match(/[\\u3040-\\u30FF]/g)||[]).length;"
                + "var latin=(txt.match(/[A-Za-z]/g)||[]).length;"
                + "var han=(txt.match(/[\\u4E00-\\u9FFF]/g)||[]).length;"
                + "var total=hangul+kana+latin+han;"
                + "if(total<40)return 'SKIP';"
                + "var tl=" + JSONObject.quote(baseLang) + ";"
                + "if(tl==='ko')return (hangul/total<0.18&&lang.indexOf('ko')!==0)?'TRANSLATE':'SKIP';"
                + "if(tl==='ja')return (kana/total<0.08&&lang.indexOf('ja')!==0)?'TRANSLATE':'SKIP';"
                + "if(tl==='zh')return (han/total<0.20&&lang.indexOf('zh')!==0)?'TRANSLATE':'SKIP';"
                + "if(tl==='en')return (latin/total<0.45&&lang.indexOf('en')!==0)?'TRANSLATE':'SKIP';"
                + "return (lang&&lang.indexOf(tl)!==0)?'TRANSLATE':'SKIP';"
                + "})();";
        wv.evaluateJavascript(detectJs, res -> {
            if (res != null && res.contains("TRANSLATE")) {
                translatePage(wv, targetLang);
            }
        });
    }

    static void restoreOriginal(WebView wv) {
        if (wv == null) return;
        synchronized (BRIDGES) { Bridge b = BRIDGES.get(wv); if (b != null) b.issueNonce(); }
        wv.evaluateJavascript("(function(){if(!window.__auroraNodes)return;for(var i=0;i<window.__auroraNodes.length;i++){var e=window.__auroraNodes[i];if(e&&e.n)e.n.nodeValue=e.o;}})();", null);
    }
}
