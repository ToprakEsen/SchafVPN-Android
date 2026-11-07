package io.github.toprakesen.schafvpn;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Insets;
import android.net.TrafficStats;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.view.View;
import android.view.WindowInsets;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Windows sürümündeki arayüzün aynısını (ui/index.html) WebView içinde gösterir. */
public final class MainActivity extends Activity implements VpnState.Listener {
    private static final int BG = 0xFF090B10;
    private static final int REQ_VPN = 1, REQ_NOTIFY = 2;
    /** Arayüzde sunucu adı yerine gösterilen sabit isim. */
    private static final String SERVER_LABEL = "KoyunVPN";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final AtomicInteger ipGen = new AtomicInteger();

    private WebView web;
    private boolean pageReady;
    private String lastState = "";
    private String profileName = "", profileDetail = "VLESS";
    private String profileError;
    private long lastRx = -1, lastTx = -1;

    private final Runnable trafficTick = new Runnable() {
        @Override public void run() {
            if (!VpnState.CONNECTED.equals(VpnState.state())) return;
            // Uygulama VPN dışında tutulduğu için kendi trafiği = tüneldeki trafik
            long rx = TrafficStats.getUidRxBytes(Process.myUid());
            long tx = TrafficStats.getUidTxBytes(Process.myUid());
            if (lastRx >= 0 && rx >= lastRx && tx >= lastTx) {
                try {
                    send(new JSONObject().put("type", "traffic").put("down", rx - lastRx).put("up", tx - lastTx));
                } catch (Exception ignored) { }
            }
            lastRx = rx; lastTx = tx;
            main.postDelayed(this, 1000);
        }
    };

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);

        try {
            VlessProfile p = VlessProfile.parse(readProfile());
            profileName = SERVER_LABEL;
            profileDetail = p.detail();
        } catch (Exception e) {
            profileError = e.getMessage();
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        web = new WebView(this);
        web.setBackgroundColor(BG);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setVerticalScrollBarEnabled(false);
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);

        // Kenardan kenara çizim: durum ve gezinme çubuğu kadar iç boşluk
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top, bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                Insets i = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                top = i.top; bottom = i.bottom;
            } else {
                top = insets.getSystemWindowInsetTop(); bottom = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(0, top, 0, bottom);
            return insets;
        });

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setSupportZoom(false);
        s.setTextZoom(100);
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new Bridge(), "SchafAndroid");
        web.loadUrl("file:///android_asset/index.html");

        VpnState.addListener(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        onVpnStateChanged();
    }

    @Override
    protected void onDestroy() {
        VpnState.removeListener(this);
        main.removeCallbacks(trafficTick);
        io.shutdownNow();
        web.destroy();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- JS köprüsü

    private final class Bridge {
        @JavascriptInterface
        public void post(String json) {
            main.post(() -> {
                try { handle(new JSONObject(json).optString("cmd")); } catch (Exception ignored) { }
            });
        }
    }

    private void handle(String cmd) {
        switch (cmd) {
            case "ready":
                pageReady = true;
                lastState = "";
                onVpnStateChanged();
                refreshIp(0);
                break;
            case "toggle":
                toggle();
                break;
        }
    }

    private void send(JSONObject message) {
        if (!pageReady) return;
        String js = "window.__schafReceive(" + message + ")";
        main.post(() -> web.evaluateJavascript(js, null));
    }

    private void pushState(String errorTitle, String error) {
        try {
            JSONObject m = new JSONObject()
                    .put("type", "state")
                    .put("state", VpnState.state())
                    .put("name", profileError != null ? "Profil okunamadı" : profileName)
                    .put("detail", profileError != null ? profileError : profileDetail)
                    .put("profileError", profileError != null)
                    .put("connectedAt", VpnState.connectedAt());
            if (error != null) m.put("error", error).put("errorTitle", errorTitle);
            send(m);
        } catch (Exception ignored) { }
    }

    @Override
    public void onVpnStateChanged() {
        String st = VpnState.state();
        String[] err = VpnState.takeError();
        boolean changed = !st.equals(lastState);
        lastState = st;
        pushState(err != null ? err[0] : null, err != null ? err[1] : null);
        if (!changed) return;

        main.removeCallbacks(trafficTick);
        if (VpnState.CONNECTED.equals(st)) {
            lastRx = lastTx = -1;
            main.post(trafficTick);
            refreshIp(800);
        } else if (VpnState.IDLE.equals(st)) {
            refreshIp(800);
        }
    }

    // ---------------------------------------------------------------- bağlantı

    private void toggle() {
        String st = VpnState.state();
        if (VpnState.CONNECTING.equals(st) || profileError != null) return;
        if (VpnState.CONNECTED.equals(st)) { SchafVpnService.stop(this); return; }

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{ Manifest.permission.POST_NOTIFICATIONS }, REQ_NOTIFY);
            return; // izin sonucunda devam edilir
        }
        requestVpnAndStart();
    }

    private void requestVpnAndStart() {
        Intent consent = VpnService.prepare(this);
        if (consent != null) startActivityForResult(consent, REQ_VPN);
        else SchafVpnService.start(this);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        // Bildirim izni verilmese de VPN çalışır; yalnızca bildirim görünmez
        if (requestCode == REQ_NOTIFY) requestVpnAndStart();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_VPN) return;
        if (resultCode == RESULT_OK) {
            SchafVpnService.start(this);
        } else {
            lastState = "";
            pushState("VPN izni verilmedi", "Bağlanmak için VPN bağlantı isteğini onaylaman gerekiyor.");
        }
    }

    // ---------------------------------------------------------------- IP / konum

    private void refreshIp(long delayMs) {
        int gen = ipGen.incrementAndGet();
        try { send(new JSONObject().put("type", "ip").put("loading", true)); } catch (Exception ignored) { }
        io.execute(() -> {
            try {
                if (delayMs > 0) Thread.sleep(delayMs);
                if (gen != ipGen.get()) return;
                // Uygulama VPN dışında: bağlıyken Xray'in yerel SOCKS'u üzerinden sor
                int port = VpnState.socksPort();
                Proxy proxy = VpnState.CONNECTED.equals(VpnState.state()) && port > 0
                        ? new Proxy(Proxy.Type.SOCKS, new InetSocketAddress("127.0.0.1", port))
                        : Proxy.NO_PROXY;
                HttpURLConnection c = (HttpURLConnection) new URL("https://ipinfo.io/json").openConnection(proxy);
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                c.setRequestProperty("User-Agent", "SchafVPN/1.0");
                c.setRequestProperty("Connection", "close");
                StringBuilder sb = new StringBuilder();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line);
                } finally { c.disconnect(); }
                if (gen != ipGen.get()) return;
                JSONObject j = new JSONObject(sb.toString());
                send(new JSONObject().put("type", "ip")
                        .put("ip", j.optString("ip")).put("city", j.optString("city")).put("country", j.optString("country")));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                if (gen == ipGen.get()) {
                    try { send(new JSONObject().put("type", "ip").put("error", true)); } catch (Exception ignored) { }
                }
            }
        });
    }

    private String readProfile() throws Exception {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(getAssets().open("profile.txt"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.toLowerCase().startsWith("vless://")) return line;
            }
        }
        throw new IllegalStateException("Gömülü profilde vless:// linki yok.");
    }
}
