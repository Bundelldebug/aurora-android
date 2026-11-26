package com.oneuibook.aurora;

import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Google Translate-powered in-place webpage translator for Aurora Browser:
 * - Collects visible DOM text nodes via JS, batches through native bridge (zero page CSP/CORS failures),
 *   translates via Google Translate endpoint (translate.googleapis.com), and restores originals cleanly on demand.
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
                    JSONArray out = new JSONArray();
                    // Group into <= 1400 UTF-8 encoded bytes (prevents HTTP 414 on CJK pages!)
                    List<Integer> ids = new ArrayList<>();
                    List<String> texts = new ArrayList<>();
                    int curBytes = 0;
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o = arr.getJSONObject(i);
                        int id = o.getInt("i");
                        String t = o.getString("t");
                        int est = t.getBytes(StandardCharsets.UTF_8).length * 3 + 16;
                        if (curBytes + est > 1400 && !texts.isEmpty()) {
                            flushChunk(targetLang, ids, texts, out);
                            ids.clear(); texts.clear(); curBytes = 0;
                        }
                        ids.add(id);
                        texts.add(t);
                        curBytes += est;
                    }
                    if (!texts.isEmpty()) flushChunk(targetLang, ids, texts, out);
                    if (out.length() == 0) return;

                    final String payload = out.toString();
                    MAIN.post(() -> {
                        if (!expectedNonce.equals(activeNonce)) return;
                        if (webView != null && webView.isAttachedToWindow()) {
                            webView.evaluateJavascript(
                                    "window.__auroraApplyTranslation&&window.__auroraApplyTranslation(" + JSONObject.quote(payload) + ");",
                                    null);
                            if (onTranslated != null) onTranslated.run();
                        }
                    });
                } catch (Throwable ignored) {}
            });
        }
    }

    private static void flushChunk(String targetLang, List<Integer> ids, List<String> texts, JSONArray out) {
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < texts.size(); i++) {
            if (i > 0) joined.append("\n@@").append(i).append("@@\n");
            joined.append(texts.get(i));
        }
        String translated = translateTextSync(joined.toString(), targetLang);
        if (translated == null || translated.isEmpty()) return;
        String[] parts = translated.split("\\s*@@\\d+@@\\s*");
        if (parts.length == texts.size()) {
            for (int i = 0; i < ids.size(); i++) {
                try { out.put(new JSONObject().put("i", ids.get(i)).put("t", parts[i].trim())); } catch (Exception ignored) {}
            }
        } else {
            // Safe per-item fallback if Google Translate merged markers
            for (int i = 0; i < ids.size(); i++) {
                String single = translateTextSync(texts.get(i), targetLang);
                if (single != null && !single.isEmpty()) {
                    try { out.put(new JSONObject().put("i", ids.get(i)).put("t", single.trim())); } catch (Exception ignored) {}
                }
            }
        }
    }

    /** Calls Google Translate public gtx endpoint via POST to avoid CJK URL-length 414 errors. */
    static String translateTextSync(String text, String targetLang) {
        if (text == null || text.trim().isEmpty()) return "";
        HttpURLConnection conn = null;
        try {
            String url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl="
                    + URLEncoder.encode(targetLang, "UTF-8") + "&dt=t";
            byte[] body = ("q=" + URLEncoder.encode(text, "UTF-8")).getBytes(StandardCharsets.UTF_8);
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            try (java.io.OutputStream os = conn.getOutputStream()) { os.write(body); }
            if (conn.getResponseCode() != 200) return "";
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
            return parseGoogleTranslateJson(sb.toString());
        } catch (Throwable ignored) {
            return "";
        } finally {
            if (conn != null) conn.disconnect();
        }
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

    static void translatePage(WebView wv, String targetLang) {
        if (wv == null) return;
        Bridge b;
        synchronized (BRIDGES) { b = BRIDGES.get(wv); }
        String nonce = b != null ? b.issueNonce() : "";
        String js = "(function(){"
                + "if(!window.AuroraTranslate)return;"
                + "var skip={SCRIPT:1,STYLE:1,NOSCRIPT:1,CODE:1,PRE:1,TEXTAREA:1,INPUT:1,SVG:1};"
                + "window.__auroraNodes=window.__auroraNodes||[];"
                + "if(window.__auroraNodes.length===0){"
                + "  var w=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT,null);"
                + "  var n;while((n=w.nextNode())&&window.__auroraNodes.length<220){"
                + "    var p=n.parentElement;if(!p||skip[p.tagName])continue;"
                + "    var s=(n.nodeValue||'').trim();if(s.length<2||/^\\d+$/.test(s))continue;"
                + "    window.__auroraNodes.push({n:n,o:n.nodeValue});"
                + "  }"
                + "}"
                + "var batch=[];"
                + "for(var i=0;i<window.__auroraNodes.length;i++){"
                + "  batch.push({i:i,t:window.__auroraNodes[i].o.trim()});"
                + "}"
                + "window.__auroraApplyTranslation=function(raw){"
                + "  try{var arr=JSON.parse(raw);for(var k=0;k<arr.length;k++){"
                + "    var it=arr[k];var entry=window.__auroraNodes[it.i];"
                + "    if(entry&&entry.n)entry.n.nodeValue=it.t;"
                + "  }}catch(e){}"
                + "};"
                + "window.AuroraTranslate.translateBatchAuth(" + JSONObject.quote(nonce) + "," + JSONObject.quote(targetLang) + ",JSON.stringify(batch));"
                + "})();";
        wv.evaluateJavascript(js, null);
    }

    static void restoreOriginal(WebView wv) {
        if (wv == null) return;
        synchronized (BRIDGES) { Bridge b = BRIDGES.get(wv); if (b != null) b.issueNonce(); }
        wv.evaluateJavascript("(function(){if(!window.__auroraNodes)return;for(var i=0;i<window.__auroraNodes.length;i++){var e=window.__auroraNodes[i];if(e&&e.n)e.n.nodeValue=e.o;}})();", null);
    }
}
