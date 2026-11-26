package com.oneuibook.aurora;

import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import java.net.URI;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Built-in Security Shield & Per-Site VPN Routing Controller (NordVPN default):
 * - URL phishing / malware / suspicious TLD / IP-credential heuristic assessment
 * - HTTPS-Only automatic upgrade (with per-host HTTP override)
 * - Anti-fingerprinting, tracking-param stripping & WebRTC leak hardening JS
 * - Dangerous download extension guard
 * - Live Android VPN tunnel detection (NetworkCapabilities.TRANSPORT_VPN) + NordVPN auto-launch & auto-resume callback
 */
final class SecurityGuard {
    private SecurityGuard() {}

    static final int RISK_SAFE = 0;
    static final int RISK_HTTP = 1;
    static final int RISK_DANGEROUS = 2;

    static final class Assessment {
        final int risk;
        final String reason;
        Assessment(int risk, String reason) { this.risk = risk; this.reason = reason; }
    }

    static final String[] VPN_KEYS = {"nordvpn", "proton", "mullvad", "wireguard", "system"};
    static final String[] VPN_LABELS = {"NordVPN", "Proton VPN", "Mullvad VPN", "WireGuard", "시스템 VPN"};
    static final String[] VPN_PACKAGES = {
            "com.nordvpn.android",
            "ch.protonvpn.android",
            "net.mullvad.mullvadvpn",
            "com.wireguard.android",
            ""
    };

    private static final Set<String> KNOWN_MALICIOUS_DOMAINS = new HashSet<>(Arrays.asList(
            "malware.testing.google.test", "testsafebrowsing.appspot.com",
            "coinhive.com", "coin-hive.com", "authedmine.com", "cryptoloot.pro",
            "jsecoin.com", "minero.cc", "webminepool.com"
    ));

    private static final Set<String> SUSPICIOUS_TLDS = new HashSet<>(Arrays.asList(
            ".zip", ".mov", ".gq", ".cf", ".tk", ".ml", ".ga", ".top", ".xyz"
    ));

    private static final Set<String> DANGEROUS_EXTS = new HashSet<>(Arrays.asList(
            ".apk", ".exe", ".bat", ".cmd", ".scr", ".msi", ".vbs", ".ps1", ".jar"
    ));

    private static final Set<String> BYPASSED_DANGEROUS_HOSTS = new HashSet<>();
    private static final Set<String> ALLOWED_HTTP_HOSTS = new HashSet<>();
    private static final Set<String> BYPASSED_VPN_HOSTS = new HashSet<>();

    private static ConnectivityManager.NetworkCallback activeVpnWatch;

    /** Lightweight WebView anti-fingerprinting & cryptominer hardening script. */
    static final String HARDENING_JS = "(function(){"
            + "if(window.__auroraShield)return;window.__auroraShield=1;"
            + "try{Object.defineProperty(navigator,'webdriver',{get:function(){return false;}});}catch(e){}"
            + "try{if(navigator.sendBeacon){var sb=navigator.sendBeacon.bind(navigator);"
            + "  navigator.sendBeacon=function(u,d){if(/analytics|pixel|telemetry|doubleclick|adservice/i.test(String(u)))return true;return sb(u,d);};}}catch(e){}"
            + "try{if(window.RTCPeerConnection){var orig=window.RTCPeerConnection;"
            + "  window.RTCPeerConnection=function(cfg){if(cfg&&cfg.iceServers){cfg=Object.assign({},cfg,{iceTransportPolicy:'relay'});}return new orig(cfg);};"
            + "  window.RTCPeerConnection.prototype=orig.prototype;}}catch(e){}"
            + "})();";

    static String vpnLabel(String key) {
        for (int i = 0; i < VPN_KEYS.length; i++) if (VPN_KEYS[i].equals(key)) return VPN_LABELS[i];
        return VPN_LABELS[0];
    }

    static String vpnPackage(String key) {
        for (int i = 0; i < VPN_KEYS.length; i++) if (VPN_KEYS[i].equals(key)) return VPN_PACKAGES[i];
        return VPN_PACKAGES[0];
    }

    static String nextVpnProvider(String cur) {
        for (int i = 0; i < VPN_KEYS.length; i++) {
            if (VPN_KEYS[i].equals(cur)) return VPN_KEYS[(i + 1) % VPN_KEYS.length];
        }
        return VPN_KEYS[0];
    }

    /** Strips tracking query parameters (utm_*, fbclid, gclid, etc.) when Security Shield is enabled. */
    static String sanitizeUrl(String url) {
        if (!Ui.securityShield || url == null || !url.contains("?")) return url;
        try {
            Uri u = Uri.parse(url);
            Set<String> names = u.getQueryParameterNames();
            if (names == null || names.isEmpty()) return url;
            boolean dirty = false;
            for (String n : names) {
                String nl = n.toLowerCase(Locale.ROOT);
                if (nl.startsWith("utm_") || nl.equals("fbclid") || nl.equals("gclid")
                        || nl.equals("igshid") || nl.equals("mc_cid") || nl.equals("mc_eid")) {
                    dirty = true;
                    break;
                }
            }
            if (!dirty) return url;
            Uri.Builder b = u.buildUpon().clearQuery();
            for (String n : names) {
                String nl = n.toLowerCase(Locale.ROOT);
                if (nl.startsWith("utm_") || nl.equals("fbclid") || nl.equals("gclid")
                        || nl.equals("igshid") || nl.equals("mc_cid") || nl.equals("mc_eid")) continue;
                for (String v : u.getQueryParameters(n)) b.appendQueryParameter(n, v);
            }
            return b.build().toString();
        } catch (Throwable ignored) {
            return url;
        }
    }

    /** Upgrades plain HTTP to HTTPS when HTTPS-Only mode is enabled and host is not whitelisted. */
    static String maybeUpgradeHttps(String url) {
        if (!Ui.httpsOnly || url == null || !url.startsWith("http://")) return url;
        String host = BrowserStore.domainOf(url).toLowerCase(Locale.ROOT);
        if (host.isEmpty() || host.equals("localhost") || host.startsWith("127.")
                || host.startsWith("192.168.") || host.startsWith("10.")
                || ALLOWED_HTTP_HOSTS.contains(host)) return url;
        return "https://" + url.substring(7);
    }

    static void allowHttpOnce(String host) {
        if (host != null && !host.isEmpty()) ALLOWED_HTTP_HOSTS.add(host.toLowerCase(Locale.ROOT));
    }

    static void allowDangerousOnce(String host) {
        if (host != null && !host.isEmpty()) BYPASSED_DANGEROUS_HOSTS.add(host.toLowerCase(Locale.ROOT));
    }

    static void allowVpnBypassOnce(String host) {
        if (host != null && !host.isEmpty()) BYPASSED_VPN_HOSTS.add(host.toLowerCase(Locale.ROOT));
    }

    static Assessment assessUrl(String url) {
        if (!Ui.securityShield || url == null || url.isEmpty() || url.startsWith("about:")) {
            return new Assessment(RISK_SAFE, "");
        }
        try {
            Uri uri = Uri.parse(url);
            String host = uri.getHost();
            if (host == null) return new Assessment(RISK_SAFE, "");
            String hl = host.toLowerCase(Locale.ROOT);
            if (BYPASSED_DANGEROUS_HOSTS.contains(hl)) return new Assessment(RISK_SAFE, "");

            // 1) Known malware / cryptominer domains
            for (String bad : KNOWN_MALICIOUS_DOMAINS) {
                if (hl.equals(bad) || hl.endsWith("." + bad)) {
                    return new Assessment(RISK_DANGEROUS, "알려진 악성코드 또는 암호화폐 채굴 도메인입니다.");
                }
            }
            // 2) Embedded userinfo phishing trick (e.g. https://google.com@evil.example)
            if (uri.getUserInfo() != null && !uri.getUserInfo().isEmpty()) {
                return new Assessment(RISK_DANGEROUS, "주소에 위장된 로그인(@) 피싱 패턴이 포함되어 있습니다.");
            }
            // 3) Punycode homograph + login/bank keyword
            String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);
            if (hl.contains("xn--") && (path.contains("login") || path.contains("verify") || path.contains("bank") || path.contains("account"))) {
                return new Assessment(RISK_DANGEROUS, "유사 도메인(Punycode)을 이용한 피싱 의심 사이트입니다.");
            }
            // 4) Suspicious TLD combined with credential-harvesting keywords
            for (String tld : SUSPICIOUS_TLDS) {
                if (hl.endsWith(tld) && (hl.contains("login") || hl.contains("secure") || hl.contains("verify")
                        || hl.contains("paypal") || hl.contains("apple-id") || hl.contains("naver-") || hl.contains("kakao-"))) {
                    return new Assessment(RISK_DANGEROUS, "계정 탈취가 의심되는 피싱 도메인 패턴이 감지되었습니다.");
                }
            }
            // 5) Raw public IP serving login page over HTTP
            if ("http".equalsIgnoreCase(uri.getScheme()) && hl.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")
                    && !hl.startsWith("127.") && !hl.startsWith("192.168.") && !hl.startsWith("10.")) {
                if (path.contains("login") || path.contains("signin")) {
                    return new Assessment(RISK_DANGEROUS, "암호화되지 않은 IP 주소에서 로그인을 요청하고 있습니다.");
                }
                return new Assessment(RISK_HTTP, "암호화되지 않은 HTTP 연결입니다.");
            }
        } catch (Throwable ignored) {}
        return new Assessment(RISK_SAFE, "");
    }

    static boolean isDangerousDownload(String fileName) {
        if (!Ui.securityShield || fileName == null) return false;
        String lower = fileName.toLowerCase(Locale.ROOT);
        for (String ext : DANGEROUS_EXTS) if (lower.endsWith(ext)) return true;
        return false;
    }

    // ---- Per-Site VPN Routing (NordVPN default) ----

    static boolean requiresVpnForUrl(String url) {
        if (url == null || !url.startsWith("http")) return false;
        String dom = BrowserStore.domainOf(url).toLowerCase(Locale.ROOT);
        if (dom.isEmpty() || BYPASSED_VPN_HOSTS.contains(dom)) return false;
        for (String rule : BrowserStore.vpnDomains) {
            String r = rule.toLowerCase(Locale.ROOT);
            if (dom.equals(r) || dom.endsWith("." + r)) return true;
        }
        return false;
    }

    static boolean isVpnActive(Context c) {
        if (c == null) return false;
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            Network active = cm.getActiveNetwork();
            if (active != null) {
                NetworkCapabilities nc = cm.getNetworkCapabilities(active);
                if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return true;
            }
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /** Launches NordVPN (or chosen VPN app / Android VPN settings) so the user can connect in 1 tap. */
    static boolean launchVpnApp(Context c) {
        String pkg = vpnPackage(Ui.vpnProvider);
        try {
            if (!pkg.isEmpty()) {
                Intent launch = c.getPackageManager().getLaunchIntentForPackage(pkg);
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    c.startActivity(launch);
                    return true;
                }
            }
        } catch (Throwable ignored) {}
        try {
            Intent vpnSettings = new Intent("android.settings.VPN_SETTINGS");
            vpnSettings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(vpnSettings);
            return true;
        } catch (Throwable ignored) {}
        if (!pkg.isEmpty()) {
            try {
                Intent store = new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=" + pkg));
                store.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(store);
                return true;
            } catch (Throwable ignored) {}
        }
        return false;
    }

    /** Watches Android's ConnectivityManager and invokes {@code onConnected} immediately once a VPN tunnel comes up. */
    static synchronized void waitForVpnConnection(Context c, Runnable onConnected) {
        Context app = c.getApplicationContext();
        stopWatchingVpn(app);
        try {
            ConnectivityManager cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return;
            Handler main = new Handler(Looper.getMainLooper());
            java.util.concurrent.atomic.AtomicBoolean fired = new java.util.concurrent.atomic.AtomicBoolean(false);
            NetworkRequest req = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build();
            activeVpnWatch = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) {
                    if (fired.compareAndSet(false, true)) {
                        stopWatchingVpn(app);
                        main.post(onConnected);
                    }
                }
            };
            cm.registerNetworkCallback(req, activeVpnWatch);
        } catch (Throwable ignored) {}
    }

    static synchronized void stopWatchingVpn(Context c) {
        if (activeVpnWatch == null || c == null) return;
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) cm.unregisterNetworkCallback(activeVpnWatch);
        } catch (Throwable ignored) {}
        activeVpnWatch = null;
    }
}
