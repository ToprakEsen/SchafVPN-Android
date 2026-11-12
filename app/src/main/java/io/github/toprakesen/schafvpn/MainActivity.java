package io.github.toprakesen.schafvpn;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Insets;
import android.net.TrafficStats;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.provider.Settings;
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
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Windows sürümündeki arayüzün aynısını (assets/index.html) WebView içinde gösterir. */
public final class MainActivity extends Activity implements VpnState.Listener {
    // assets/index.html içindeki --bg ile aynı renkler
    private static final int BG_DARK = 0xFF090B10, BG_LIGHT = 0xFFF2F4F9;
    private static final int REQ_VPN = 1, REQ_NOTIFY = 2;
    /** Arayüzde sunucu adı yerine gösterilen sabit isim. */
    private static final String SERVER_LABEL = "KoyunVPN";
    private static final long UPDATE_INTERVAL_MS = 6L * 60 * 60 * 1000;
    /** Hızlı Ayarlar kutucuğu izin/profil gerektirdiğinde uygulamayı bu eylemle açar. */
    static final String ACTION_CONNECT = "io.github.toprakesen.schafvpn.CONNECT";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService upd = Executors.newSingleThreadExecutor();
    private final AtomicInteger ipGen = new AtomicInteger();

    private WebView web;
    private FrameLayout root;
    /** Şu an uygulanan tema ("dark" / "light"); köprüden sayfa yüklenirken de okunur. */
    private volatile String themeNow = "dark";
    private AppSettings settings;
    private boolean pageReady, autoConnectDone, updateBusy;
    private String lastState = "";
    private VlessProfile profile;
    private String profileError;
    private long lastRx = -1, lastTx = -1;
    private Updater.Info update;
    private File updateFile;

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

    private final Runnable periodicUpdate = new Runnable() {
        @Override public void run() {
            if (settings.autoUpdate()) checkForUpdate(false);
            main.postDelayed(this, UPDATE_INTERVAL_MS);
        }
    };

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        VpnState.appContext = getApplicationContext();
        settings = new AppSettings(this);
        loadProfile();

        root = new FrameLayout(this);
        web = new WebView(this);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setVerticalScrollBarEnabled(false);
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        applyTheme(false);

        // Kenardan kenara çizim: durum ve gezinme çubuğu (ve klavye) kadar iç boşluk
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top, bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                Insets i = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
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
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    /** Kutucuktan gelindiyse bağlan (izin ekranı gerekiyorsa burada açılır). */
    private void handleIntent(Intent intent) {
        if (intent == null || !ACTION_CONNECT.equals(intent.getAction())) return;
        intent.setAction(null); // ekran döndürülünce tekrar tetiklenmesin
        main.post(() -> { if (VpnState.IDLE.equals(VpnState.state())) toggle(); });
    }

    // ---------------------------------------------------------------- tema

    private boolean resolveLight() {
        String pref = settings.theme();
        if ("light".equals(pref)) return true;
        if ("dark".equals(pref)) return false;
        int night = getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return night != android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    /** Arka planı ve sistem çubuklarının simge rengini temaya göre ayarlar. */
    private void applyTheme(boolean notifyPage) {
        boolean light = resolveLight();
        themeNow = light ? "light" : "dark";
        int bg = light ? BG_LIGHT : BG_DARK;
        root.setBackgroundColor(bg);
        web.setBackgroundColor(bg);
        if (Build.VERSION.SDK_INT >= 30) {
            android.view.WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                int mask = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                c.setSystemBarsAppearance(light ? mask : 0, mask);
            }
        } else {
            View decor = getWindow().getDecorView();
            int mask = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            int flags = decor.getSystemUiVisibility();
            decor.setSystemUiVisibility(light ? flags | mask : flags & ~mask);
        }
        if (notifyPage) sendTheme();
    }

    private void sendTheme() {
        try { send(new JSONObject().put("type", "theme").put("theme", themeNow)); } catch (Exception ignored) { }
    }

    /** Sistem koyu/açık moda geçtiğinde ("Sistem" seçiliyse) arayüz de değişsin. */
    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if ("system".equals(settings.theme())) applyTheme(true);
    }

    private void loadProfile() {
        profile = null;
        profileError = null;
        String link = ProfileStore.load(this);
        if (link == null) return; // profil yok: arayüz kurulum ekranını açar
        try { profile = VlessProfile.parse(link); }
        catch (Exception e) { profileError = e.getMessage(); }
    }

    @Override
    protected void onResume() {
        super.onResume();
        onVpnStateChanged();
    }

    @Override
    protected void onDestroy() {
        VpnState.removeListener(this);
        main.removeCallbacksAndMessages(null);
        io.shutdownNow();
        upd.shutdownNow();
        web.destroy();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- JS köprüsü

    private final class Bridge {
        @JavascriptInterface
        public void post(String json) {
            main.post(() -> {
                try { handle(new JSONObject(json)); } catch (Exception ignored) { }
            });
        }

        /** Sayfa çizilmeden önce eşzamanlı okunur (açılışta tema yanıp sönmesin). */
        @JavascriptInterface
        public String initialTheme() { return themeNow; }
    }

    private void handle(JSONObject m) throws Exception {
        switch (m.optString("cmd")) {
            case "ready":
                pageReady = true;
                lastState = "";
                sendTheme();
                onVpnStateChanged();
                pushSettings();
                send(new JSONObject().put("type", "version").put("version", Updater.current(this)));
                pushUpdate();
                refreshIp(0);
                main.removeCallbacks(periodicUpdate);
                main.postDelayed(periodicUpdate, 4000);
                if (settings.autoConnect() && !autoConnectDone && profile != null && VpnState.IDLE.equals(VpnState.state())) {
                    autoConnectDone = true;
                    toggle();
                }
                break;
            case "toggle": toggle(); break;
            case "saveProfile": saveProfile(m.optString("link")); break;
            case "paste": {
                ClipboardManager cm = getSystemService(ClipboardManager.class);
                ClipData clip = cm != null ? cm.getPrimaryClip() : null;
                String text = clip != null && clip.getItemCount() > 0 && clip.getItemAt(0).getText() != null
                        ? clip.getItemAt(0).getText().toString().trim() : "";
                send(new JSONObject().put("type", "paste").put("text", text));
                break;
            }
            case "setSetting":
                settings.set(m.optString("key"), m.optBoolean("value"));
                if ("autoUpdate".equals(m.optString("key")) && m.optBoolean("value")) checkForUpdate(false);
                pushSettings();
                break;
            case "setTheme":
                settings.setTheme(m.optString("value"));
                applyTheme(true);
                pushSettings();
                break;
            case "checkUpdate": checkForUpdate(true); break;
            case "applyUpdate": applyUpdate(); break;
            case "addTile": addTile(); break;
            case "openVpnSettings":
                try { startActivity(new Intent(Settings.ACTION_VPN_SETTINGS)); }
                catch (Exception e) { notice("VPN ayarları açılamadı."); }
                break;
        }
    }

    private void send(JSONObject message) {
        if (!pageReady) return;
        String js = "window.__schafReceive(" + message + ")";
        main.post(() -> web.evaluateJavascript(js, null));
    }

    private void notice(String text) {
        try { send(new JSONObject().put("type", "notice").put("text", text)); } catch (Exception ignored) { }
    }

    private void pushState(String errorTitle, String error) {
        try {
            JSONObject m = new JSONObject()
                    .put("type", "state")
                    .put("state", VpnState.state())
                    .put("hasProfile", profile != null || profileError != null)
                    .put("name", profileError != null ? "Profil okunamadı" : SERVER_LABEL)
                    .put("detail", profileError != null ? profileError : profile != null ? profile.detail() : "VLESS")
                    .put("profileError", profileError != null)
                    .put("connectedAt", VpnState.connectedAt());
            if (error != null) m.put("error", error).put("errorTitle", errorTitle);
            send(m);
        } catch (Exception ignored) { }
    }

    private void pushSettings() {
        try {
            send(new JSONObject().put("type", "settings")
                    .put("autoConnect", settings.autoConnect())
                    .put("autoUpdate", settings.autoUpdate())
                    .put("theme", settings.theme()));
        } catch (Exception ignored) { }
    }

    private void saveProfile(String link) {
        io.execute(() -> {
            JSONObject r = new JSONObject();
            try {
                if (!VpnState.IDLE.equals(VpnState.state()))
                    throw new IllegalStateException("Profili değiştirmek için önce bağlantıyı kes.");
                VlessProfile p = ProfileStore.save(this, link);
                main.post(() -> { profile = p; profileError = null; pushState(null, null); });
                r.put("type", "profileResult").put("ok", true);
            } catch (Exception e) {
                try { r.put("type", "profileResult").put("ok", false).put("message", e.getMessage()); } catch (Exception ignored) { }
            }
            send(r);
        });
    }

    @Override
    public void onVpnStateChanged() {
        String st = VpnState.state();
        String[] err = VpnState.takeError();
        boolean changed = !st.equals(lastState);
        lastState = st;
        pushState(err != null ? err[0] : null, err != null ? err[1] : null);
        // Bağlanırken servisin bulunduğu gerçek adım ("Tünel kuruluyor" vb.)
        String stage = VpnState.stage();
        if (VpnState.CONNECTING.equals(st) && stage != null) {
            try { send(new JSONObject().put("type", "stage").put("text", stage)); } catch (Exception ignored) { }
        }
        if (!changed) return;

        main.removeCallbacks(trafficTick);
        if (VpnState.CONNECTED.equals(st)) {
            lastRx = lastTx = -1;
            main.post(trafficTick);
            refreshIp(800);
        } else if (VpnState.IDLE.equals(st)) {
            refreshIp(800);
        }
        pushUpdate();
    }

    /** Hızlı Ayarlar kutucuğunu panele ekle (Android 13+ sistem penceresiyle). */
    private void addTile() {
        if (Build.VERSION.SDK_INT >= 33) {
            android.app.StatusBarManager sbm = getSystemService(android.app.StatusBarManager.class);
            sbm.requestAddTileService(
                    new android.content.ComponentName(this, SchafTileService.class),
                    "SchafVPN",
                    android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_stat_sheep),
                    getMainExecutor(),
                    result -> {
                        if (result == android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED)
                            notice("Kutucuk eklendi. Bildirim panelini aşağı kaydırınca göreceksin.");
                        else if (result == android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED)
                            notice("Kutucuk zaten panelde.");
                    });
        } else {
            notice("Paneli iki kez aşağı kaydır → kalem simgesi → SchafVPN'i sürükle.");
        }
    }

    // ---------------------------------------------------------------- bağlantı

    private void toggle() {
        String st = VpnState.state();
        if (VpnState.CONNECTING.equals(st)) return;
        if (VpnState.CONNECTED.equals(st)) { SchafVpnService.stop(this); return; }
        if (profile == null) { pushState(null, null); return; } // kurulum ekranı açılsın

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

    // ---------------------------------------------------------------- güncelleme

    private void checkForUpdate(boolean manual) {
        if (updateBusy) return;
        updateBusy = true;
        if (manual) sendUpdate("checking", null, 0);
        upd.execute(() -> {
            try {
                Updater.Info info = Updater.check(this);
                if (info == null) {
                    if (manual) {
                        JSONObject m = new JSONObject().put("type", "update").put("status", "none").put("version", Updater.current(this));
                        send(m);
                    }
                    return;
                }
                if (updateFile != null && update != null && update.version.equals(info.version)) { pushUpdate(); return; }
                update = info;
                sendUpdate("downloading", info.version, 0);
                File f = Updater.download(this, info, p -> sendUpdate("downloading", info.version, p));
                updateFile = f;
                pushUpdate();
            } catch (Exception e) {
                if (manual) {
                    try { send(new JSONObject().put("type", "update").put("status", "error").put("message", e.getMessage())); }
                    catch (Exception ignored) { }
                }
            } finally {
                main.post(() -> updateBusy = false);
            }
        });
    }

    private void sendUpdate(String status, String version, double progress) {
        try {
            JSONObject m = new JSONObject().put("type", "update").put("status", status).put("progress", progress);
            if (version != null) m.put("version", version);
            send(m);
        } catch (Exception ignored) { }
    }

    private void pushUpdate() {
        if (update == null || updateFile == null) return;
        try {
            send(new JSONObject().put("type", "update").put("status", "ready").put("version", update.version)
                    .put("connected", !VpnState.IDLE.equals(VpnState.state())));
        } catch (Exception ignored) { }
    }

    private void applyUpdate() {
        if (updateFile == null || !updateFile.exists()) return;
        // Android: bilinmeyen kaynaklardan kurma izni (ilk seferde ayarlardan verilir)
        if (!getPackageManager().canRequestPackageInstalls()) {
            notice("Güncellemeyi kurmak için SchafVPN'e izin ver, sonra tekrar Güncelle'ye dokun.");
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
            pushUpdate();
            return;
        }
        File f = updateFile;
        upd.execute(() -> {
            try { Updater.install(this, f); }
            catch (Exception e) {
                try { send(new JSONObject().put("type", "update").put("status", "error").put("message", e.getMessage())); }
                catch (Exception ignored) { }
                pushUpdate();
            }
        });
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
}
