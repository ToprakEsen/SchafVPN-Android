package io.github.toprakesen.schafvpn;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.net.VpnService;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * Bildirim panelindeki (Hızlı Ayarlar) SchafVPN kutucuğu: dokununca bağlanır / keser.
 * İlk bağlantıda VPN izni veya profil gerekiyorsa uygulamayı açar.
 */
public final class SchafTileService extends TileService implements VpnState.Listener {

    /** Durum değişince açık paneldeki kutucuğu yenilet. */
    static void refresh(Context c) {
        try { TileService.requestListeningState(c, new ComponentName(c, SchafTileService.class)); }
        catch (Exception ignored) { }
    }

    @Override
    public void onStartListening() {
        VpnState.addListener(this);
        render();
    }

    @Override
    public void onStopListening() {
        VpnState.removeListener(this);
    }

    @Override
    public void onVpnStateChanged() { render(); }

    @Override
    public void onClick() {
        String st = VpnState.state();
        if (VpnState.CONNECTING.equals(st)) return;
        if (VpnState.CONNECTED.equals(st)) { SchafVpnService.stop(this); return; }

        // Profil yoksa veya VPN izni henüz verilmediyse kullanıcının uygulamada onaylaması gerekir
        if (ProfileStore.load(this) == null || VpnService.prepare(this) != null) {
            openApp(true);
            return;
        }
        try {
            SchafVpnService.start(this);
        } catch (Exception e) {
            // Bazı sürümler arka plandan ön plan servisi başlatmaya izin vermez: uygulama üzerinden bağlan
            openApp(true);
        }
    }

    private void openApp(boolean connect) {
        Intent i = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (connect) i.setAction(MainActivity.ACTION_CONNECT);
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, i,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        } else {
            //noinspection deprecation
            startActivityAndCollapse(i);
        }
    }

    private void render() {
        Tile t = getQsTile();
        if (t == null) return;
        String st = VpnState.state();
        t.setIcon(Icon.createWithResource(this, R.drawable.ic_stat_sheep));
        t.setLabel("SchafVPN");
        t.setState(VpnState.IDLE.equals(st) ? Tile.STATE_INACTIVE : Tile.STATE_ACTIVE);
        if (Build.VERSION.SDK_INT >= 29) {
            t.setSubtitle(VpnState.CONNECTED.equals(st) ? "Bağlı"
                    : VpnState.CONNECTING.equals(st) ? "Bağlanıyor…" : "Bağlı değil");
        }
        t.setContentDescription("SchafVPN " + (VpnState.CONNECTED.equals(st) ? "bağlı" : "bağlı değil"));
        t.updateTile();
    }
}
