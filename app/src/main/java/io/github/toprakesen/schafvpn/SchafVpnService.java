package io.github.toprakesen.schafvpn;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;

import libv2ray.CoreCallbackHandler;
import libv2ray.CoreController;
import libv2ray.Libv2ray;

/**
 * TUN arayüzünü kurar ve dosya tanımlayıcısını doğrudan Xray'e verir.
 * Uygulamanın kendisi VPN dışında tutulur; böylece Xray'in sunucuya giden
 * bağlantıları tünele geri girmez.
 */
public final class SchafVpnService extends VpnService {
    static final String ACTION_START = "io.github.toprakesen.schafvpn.START";
    static final String ACTION_STOP = "io.github.toprakesen.schafvpn.STOP";
    static final int MTU = 1500;

    private static final String TAG = "SchafVPN";
    private static final String CHANNEL = "vpn";
    private static final int NOTIFICATION_ID = 1;

    private final Object lock = new Object();
    private ParcelFileDescriptor tun;
    private CoreController core;
    private boolean userStopping;

    public static void start(Context c) {
        c.startForegroundService(new Intent(c, SchafVpnService.class).setAction(ACTION_START));
    }

    public static void stop(Context c) {
        c.startService(new Intent(c, SchafVpnService.class).setAction(ACTION_STOP));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            new Thread(() -> stopVpn(null, null), "vpn-stop").start();
            return START_NOT_STICKY;
        }
        showNotification(false);
        new Thread(this::startVpn, "vpn-start").start();
        return START_NOT_STICKY;
    }

    private void startVpn() {
        synchronized (lock) {
            if (core != null && core.getIsRunning()) return;
            userStopping = false;
            VpnState.setConnecting();
            try {
                VlessProfile profile = VlessProfile.parse(readProfile());
                int socksPort = freePort();
                String config = profile.buildXrayConfig(socksPort);

                Builder b = new Builder()
                        .setSession("SchafVPN")
                        .setMtu(MTU)
                        .addAddress("172.19.0.1", 30)
                        .addRoute("0.0.0.0", 0)
                        .addDnsServer("1.1.1.1")
                        .addDisallowedApplication(getPackageName());
                try {
                    b.addAddress("fdfe:dcba:9876::1", 126).addRoute("::", 0); // IPv6 sızıntısı olmasın
                } catch (IllegalArgumentException ignored) { }
                b.setConfigureIntent(PendingIntent.getActivity(this, 0,
                        new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE));

                tun = b.establish();
                if (tun == null) throw new IllegalStateException("VPN izni verilmedi.");

                Libv2ray.initCoreEnv(getFilesDir().getAbsolutePath(), "");
                core = Libv2ray.newCoreController(new Callback());
                core.startLoop(config, tun.getFd());

                VpnState.setConnected(socksPort);
                showNotification(true);
            } catch (Throwable t) {
                Log.e(TAG, "baslatma hatasi", t);
                String msg = t.getMessage() != null ? t.getMessage() : t.toString();
                userStopping = true; // asıl hatayı "çekirdek durdu" mesajı ezmesin
                releaseLocked();
                VpnState.setIdle("Bağlantı başlatılamadı", msg);
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
            }
        }
    }

    private void stopVpn(String errorTitle, String error) {
        synchronized (lock) {
            userStopping = true;
            releaseLocked();
        }
        VpnState.setIdle(errorTitle, error);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void releaseLocked() {
        if (core != null) {
            try { core.stopLoop(); } catch (Exception e) { Log.w(TAG, "stopLoop", e); }
            core = null;
        }
        if (tun != null) {
            try { tun.close(); } catch (Exception ignored) { }
            tun = null;
        }
    }

    /** Kullanıcı ayarlardan VPN'i kapattı veya başka bir VPN açıldı. */
    @Override
    public void onRevoke() {
        new Thread(() -> stopVpn(null, null), "vpn-revoke").start();
    }

    @Override
    public void onDestroy() {
        synchronized (lock) { releaseLocked(); }
        if (!VpnState.IDLE.equals(VpnState.state())) VpnState.setIdle(null, null);
        super.onDestroy();
    }

    // ---------------------------------------------------------------- bildirim

    private void showNotification(boolean connected) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "VPN durumu", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, SchafVpnService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);

        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_sheep)
                .setColor(0xFF3DDC97)
                .setContentTitle(connected ? "SchafVPN bağlı" : "SchafVPN bağlanıyor…")
                .setContentText(connected ? "Tüm trafik güvenli tünelden geçiyor" : "Güvenli tünel kuruluyor")
                .setContentIntent(open)
                .setOngoing(true)
                .setShowWhen(connected)
                .setWhen(System.currentTimeMillis())
                .setUsesChronometer(connected)
                .addAction(new Notification.Action.Builder(null, "Bağlantıyı kes", stop).build())
                .build();

        if (Build.VERSION.SDK_INT >= 34)
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else
            startForeground(NOTIFICATION_ID, n);
    }

    // ---------------------------------------------------------------- yardımcılar

    private String readProfile() throws Exception {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                getAssets().open("profile.txt"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.toLowerCase().startsWith("vless://")) return line;
            }
        }
        throw new IllegalStateException("Gömülü profilde vless:// linki yok.");
    }

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) { return s.getLocalPort(); }
    }

    private final class Callback implements CoreCallbackHandler {
        @Override public long startup() { return 0; }

        @Override public long shutdown() {
            // Çekirdek kendiliğinden durduysa (kullanıcı kapatmadıysa) bildir
            if (!userStopping) {
                new Thread(() -> stopVpn("Bağlantı kesildi", "Xray çekirdeği durdu."), "vpn-died").start();
            }
            return 0;
        }

        @Override public long onEmitStatus(long code, String msg) {
            Log.i(TAG, "xray: " + msg);
            return 0;
        }
    }
}
