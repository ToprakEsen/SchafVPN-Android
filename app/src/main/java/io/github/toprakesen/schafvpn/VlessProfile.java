package io.github.toprakesen.schafvpn;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * vless:// paylaşım linkini çözümler ve Xray config'i üretir.
 * Windows sürümündeki VlessProfile.cs'nin karşılığı; Android'de TUN'u doğrudan Xray yönetir.
 */
public final class VlessProfile {
    public String uuid, host, name = "";
    public int port;
    public String encryption = "none", security = "none", transport = "raw", flow = "";
    public String sni = "", fingerprint = "", publicKey = "", shortId = "", spiderX = "", mldsa65Verify = "";
    public String alpn = "", path = "", hostHeader = "", serviceName = "", headerType = "", mode = "", extra = "";
    public boolean allowInsecure;

    public static VlessProfile parse(String link) {
        link = link.trim();
        if (!link.toLowerCase(Locale.ROOT).startsWith("vless://"))
            throw new IllegalArgumentException("Profil 'vless://' ile başlamalı.");
        String rest = link.substring(8);

        VlessProfile p = new VlessProfile();
        int hash = rest.indexOf('#');
        if (hash >= 0) { p.name = decode(rest.substring(hash + 1)); rest = rest.substring(0, hash); }
        String query = "";
        int q = rest.indexOf('?');
        if (q >= 0) { query = rest.substring(q + 1); rest = rest.substring(0, q); }
        if (rest.endsWith("/")) rest = rest.substring(0, rest.length() - 1);

        int at = rest.lastIndexOf('@');
        if (at <= 0) throw new IllegalArgumentException("VLESS linkinde UUID yok.");
        p.uuid = decode(rest.substring(0, at));
        String hostPort = rest.substring(at + 1);
        int colon;
        if (hostPort.startsWith("[")) {               // IPv6: [::1]:443
            int end = hostPort.indexOf(']');
            if (end < 0) throw new IllegalArgumentException("IPv6 adresi hatalı.");
            p.host = hostPort.substring(1, end);
            colon = hostPort.indexOf(':', end);
        } else {
            colon = hostPort.lastIndexOf(':');
            p.host = colon >= 0 ? hostPort.substring(0, colon) : hostPort;
        }
        if (colon < 0) throw new IllegalArgumentException("VLESS linkinde port yok.");
        try { p.port = Integer.parseInt(hostPort.substring(colon + 1)); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("Port geçersiz."); }
        if (p.host.isEmpty() || p.port <= 0) throw new IllegalArgumentException("Adres veya port eksik.");

        Map<String, String> m = parseQuery(query);
        String type = m.getOrDefault("type", "").toLowerCase(Locale.ROOT);
        switch (type) {
            case "kcp": case "mkcp": case "quic": case "h2": case "http":
                throw new IllegalArgumentException("'" + type + "' taşıma türü desteklenmiyor.");
            case "": case "tcp": case "raw": p.transport = "raw"; break;
            case "splithttp": p.transport = "xhttp"; break;
            default: p.transport = type;
        }
        p.encryption = or(m.get("encryption"), "none");
        p.security = or(m.get("security"), "none").toLowerCase(Locale.ROOT);
        p.flow = or(m.get("flow"), "");
        p.sni = or(m.get("sni"), "");
        p.fingerprint = or(m.get("fp"), "");
        p.publicKey = or(m.get("pbk"), "");
        p.shortId = or(m.get("sid"), "");
        p.spiderX = or(m.get("spx"), "");
        p.mldsa65Verify = or(m.get("pqv"), "");
        p.alpn = or(m.get("alpn"), "");
        p.allowInsecure = "1".equals(m.get("allowInsecure")) || "true".equals(m.get("allowInsecure"));
        p.path = or(m.get("path"), "");
        p.hostHeader = or(m.get("host"), "");
        p.serviceName = or(m.get("serviceName"), "");
        p.headerType = or(m.get("headerType"), "");
        p.mode = or(m.get("mode"), "");
        p.extra = or(m.get("extra"), "");
        return p;
    }

    public String displayName() { return name.isEmpty() ? host + ":" + port : name; }

    public String detail() {
        List<String> parts = new ArrayList<>();
        parts.add("VLESS");
        if (security.equals("reality")) parts.add("Reality");
        else if (security.equals("tls")) parts.add("TLS");
        switch (transport) {
            case "ws": parts.add("WebSocket"); break;
            case "grpc": parts.add("gRPC"); break;
            case "httpupgrade": parts.add("HTTPUpgrade"); break;
            case "xhttp": parts.add("XHTTP"); break;
            default: parts.add("TCP");
        }
        if (encryption.toLowerCase(Locale.ROOT).startsWith("mlkem")) parts.add("ML-KEM");
        return String.join(" · ", parts);
    }

    // ================================================================ Xray config

    /** TUN girişi (VpnService fd'si) + IP sorgusu için yerel SOCKS + VLESS çıkışı. */
    public String buildXrayConfig(int socksPort) throws JSONException {
        JSONObject user = new JSONObject().put("id", uuid).put("encryption", encryption);
        if (!flow.isEmpty()) user.put("flow", flow);

        JSONObject proxy = new JSONObject()
                .put("tag", "proxy")
                .put("protocol", "vless")
                .put("settings", new JSONObject().put("vnext", new JSONArray().put(new JSONObject()
                        .put("address", host).put("port", port).put("users", new JSONArray().put(user)))))
                .put("streamSettings", buildStream());

        JSONObject tun = new JSONObject()
                .put("tag", "tun-in")
                .put("protocol", "tun")
                .put("settings", new JSONObject().put("name", "xray0").put("mtu", SchafVpnService.MTU))
                .put("sniffing", new JSONObject().put("enabled", true)
                        .put("destOverride", new JSONArray().put("http").put("tls").put("quic"))
                        .put("routeOnly", true));

        JSONObject socks = new JSONObject()
                .put("tag", "socks-in")
                .put("listen", "127.0.0.1")
                .put("port", socksPort)
                .put("protocol", "socks")
                .put("settings", new JSONObject().put("udp", false));

        JSONArray privateNets = new JSONArray()
                .put("10.0.0.0/8").put("172.16.0.0/12").put("192.168.0.0/16")
                .put("127.0.0.0/8").put("169.254.0.0/16").put("fc00::/7").put("fe80::/10");

        return new JSONObject()
                .put("log", new JSONObject().put("loglevel", "warning"))
                .put("inbounds", new JSONArray().put(tun).put(socks))
                .put("outbounds", new JSONArray().put(proxy)
                        .put(new JSONObject().put("tag", "direct").put("protocol", "freedom")))
                .put("routing", new JSONObject()
                        .put("domainStrategy", "AsIs")
                        .put("rules", new JSONArray().put(new JSONObject()
                                .put("type", "field").put("ip", privateNets).put("outboundTag", "direct"))))
                .toString(2);
    }

    private JSONObject buildStream() throws JSONException {
        JSONObject s = new JSONObject().put("network", transport).put("security", security);

        if (security.equals("reality")) {
            if (publicKey.isEmpty()) throw new IllegalArgumentException("Reality profili için 'pbk' eksik.");
            JSONObject r = new JSONObject()
                    .put("serverName", sni)
                    .put("fingerprint", fingerprint.isEmpty() ? "chrome" : fingerprint)
                    .put("publicKey", publicKey)
                    .put("shortId", shortId)
                    .put("spiderX", spiderX);
            if (!mldsa65Verify.isEmpty()) r.put("mldsa65Verify", mldsa65Verify);
            s.put("realitySettings", r);
        } else if (security.equals("tls")) {
            JSONObject t = new JSONObject().put("serverName", sni.isEmpty() ? host : sni);
            if (!fingerprint.isEmpty()) t.put("fingerprint", fingerprint);
            if (!alpn.isEmpty()) {
                JSONArray a = new JSONArray();
                for (String x : alpn.split(",")) if (!x.trim().isEmpty()) a.put(x.trim());
                t.put("alpn", a);
            }
            if (allowInsecure) t.put("allowInsecure", true);
            s.put("tlsSettings", t);
        }

        switch (transport) {
            case "raw":
                if (headerType.equals("http")) {
                    JSONObject req = new JSONObject().put("path", new JSONArray().put(path.isEmpty() ? "/" : path));
                    if (!hostHeader.isEmpty()) {
                        JSONArray hosts = new JSONArray();
                        for (String h : hostHeader.split(",")) hosts.put(h.trim());
                        req.put("headers", new JSONObject().put("Host", hosts));
                    }
                    s.put("rawSettings", new JSONObject().put("header",
                            new JSONObject().put("type", "http").put("request", req)));
                }
                break;
            case "ws":
                s.put("wsSettings", new JSONObject().put("path", slash(path)).put("host", hostHeader));
                break;
            case "httpupgrade":
                s.put("httpupgradeSettings", new JSONObject().put("path", slash(path)).put("host", hostHeader));
                break;
            case "grpc":
                s.put("grpcSettings", new JSONObject().put("serviceName", serviceName)
                        .put("authority", hostHeader).put("multiMode", mode.equals("multi")));
                break;
            case "xhttp": {
                JSONObject x = new JSONObject().put("path", slash(path)).put("host", hostHeader)
                        .put("mode", mode.isEmpty() ? "auto" : mode);
                if (!extra.isEmpty()) {
                    try { x.put("extra", new JSONObject(extra)); }
                    catch (JSONException e) { throw new IllegalArgumentException("xhttp 'extra' geçerli JSON değil."); }
                }
                s.put("xhttpSettings", x);
                break;
            }
        }
        return s;
    }

    // ================================================================ yardımcılar

    private static String slash(String p) { return p.isEmpty() ? "/" : p; }
    private static String or(String v, String d) { return v == null || v.trim().isEmpty() ? d : v.trim(); }

    private static Map<String, String> parseQuery(String q) {
        Map<String, String> m = new HashMap<>();
        for (String part : q.split("&")) {
            if (part.isEmpty()) continue;
            int eq = part.indexOf('=');
            String k = decode(eq >= 0 ? part.substring(0, eq) : part);
            String v = eq >= 0 ? decode(part.substring(eq + 1)) : "";
            m.put(k, v);
        }
        return m;
    }

    /** %XX çözer; '+' işaretini olduğu gibi bırakır (anahtarlarda geçebilir). */
    private static String decode(String s) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < b.length; i++) {
            if (b[i] == '%' && i + 2 < b.length) {
                int hi = Character.digit(b[i + 1], 16), lo = Character.digit(b[i + 2], 16);
                if (hi >= 0 && lo >= 0) { out.write((hi << 4) | lo); i += 2; continue; }
            }
            out.write(b[i]);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
