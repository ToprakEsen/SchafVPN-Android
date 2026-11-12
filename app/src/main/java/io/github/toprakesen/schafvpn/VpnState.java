package io.github.toprakesen.schafvpn;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.CopyOnWriteArrayList;

/** Servis ile ekran arasında paylaşılan bağlantı durumu (aynı süreç). */
public final class VpnState {
    public static final String IDLE = "idle", CONNECTING = "connecting", CONNECTED = "connected";

    public interface Listener { void onVpnStateChanged(); }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** Hızlı Ayarlar kutucuğunu yenilemek için uygulama bağlamı (servis/ekran açılınca atanır). */
    static volatile android.content.Context appContext;
    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    private static volatile String state = IDLE;
    private static volatile long connectedAt;
    private static volatile int socksPort;
    private static volatile String pendingError, pendingErrorTitle;

    public static String state() { return state; }
    public static long connectedAt() { return connectedAt; }
    public static int socksPort() { return socksPort; }

    /** Bağlanırken bulunulan adım ("Tünel kuruluyor" vb.); bağlanmıyorken null. */
    private static volatile String stage;
    public static String stage() { return stage; }
    static void setStage(String s) { stage = s; notifyListeners(); }

    static void setConnecting() { stage = null; state = CONNECTING; notifyListeners(); }

    static void setConnected(int port) {
        socksPort = port;
        connectedAt = System.currentTimeMillis();
        stage = null;
        state = CONNECTED;
        notifyListeners();
    }

    static void setIdle(String errorTitle, String error) {
        state = IDLE;
        stage = null;
        socksPort = 0;
        pendingErrorTitle = errorTitle;
        pendingError = error;
        notifyListeners();
    }

    /** Hata mesajı ekranda bir kez gösterilsin. */
    public static String[] takeError() {
        if (pendingError == null) return null;
        String[] e = { pendingErrorTitle, pendingError };
        pendingError = null;
        pendingErrorTitle = null;
        return e;
    }

    public static void addListener(Listener l) { LISTENERS.addIfAbsent(l); }
    public static void removeListener(Listener l) { LISTENERS.remove(l); }

    private static void notifyListeners() {
        MAIN.post(() -> {
            for (Listener l : LISTENERS) l.onVpnStateChanged();
            if (appContext != null) SchafTileService.refresh(appContext);
        });
    }

    private VpnState() {}
}
