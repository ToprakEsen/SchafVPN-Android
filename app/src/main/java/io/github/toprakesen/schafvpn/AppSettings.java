package io.github.toprakesen.schafvpn;

import android.content.Context;
import android.content.SharedPreferences;

/** Kullanıcı ayarları (SharedPreferences). */
public final class AppSettings {
    private final SharedPreferences p;

    public AppSettings(Context c) { p = c.getSharedPreferences("settings", Context.MODE_PRIVATE); }

    public boolean autoConnect() { return p.getBoolean("autoConnect", false); }
    public boolean autoUpdate() { return p.getBoolean("autoUpdate", true); }

    /** Bilinmeyen anahtar için false döner. */
    public boolean set(String key, boolean value) {
        switch (key) {
            case "autoConnect": case "autoUpdate":
                p.edit().putBoolean(key, value).apply();
                return true;
            default:
                return false;
        }
    }
}
