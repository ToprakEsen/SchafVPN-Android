package io.github.toprakesen.schafvpn;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * VLESS linkini Android Keystore'daki (cihazdan çıkarılamayan) AES anahtarıyla şifreleyip saklar.
 * APK güncellense de profil kalır. APK'ya gömülü bir profil varsa (kur.bat ile derlenmiş)
 * ilk açılışta buraya taşınır.
 */
public final class ProfileStore {
    private static final String KEY_ALIAS = "schafvpn_profile";
    private static final String PREFS = "profile";
    private static final String FIELD = "link_enc";

    /** Kayıtlı link; yoksa gömülü link (ve onu kaydeder); o da yoksa null. */
    public static synchronized String load(Context c) {
        String enc = prefs(c).getString(FIELD, null);
        if (enc != null) {
            try {
                String link = decrypt(enc);
                if (link.toLowerCase().startsWith("vless://")) return link;
            } catch (Exception ignored) { /* anahtar silinmiş/bozuk: gömülüye düş */ }
        }
        String embedded = readEmbedded(c);
        if (embedded != null) {
            try { save(c, embedded); } catch (Exception ignored) { }
            return embedded;
        }
        return null;
    }

    /** Linki doğrular ve şifreli kaydeder; geçersizse IllegalArgumentException. */
    public static synchronized VlessProfile save(Context c, String link) throws Exception {
        link = link.trim();
        VlessProfile p = VlessProfile.parse(link);
        p.buildXrayConfig(1); // eksik alan varsa (ör. pbk) burada yakalansın
        prefs(c).edit().putString(FIELD, encrypt(link)).apply();
        return p;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(KEY_ALIAS)) return (SecretKey) ks.getKey(KEY_ALIAS, null);
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        kg.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return kg.generateKey();
    }

    private static String encrypt(String plain) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, key());
        byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(c.getIV(), Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP);
    }

    private static String decrypt(String stored) throws Exception {
        String[] parts = stored.split(":", 2);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
        return new String(c.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
    }

    private static String readEmbedded(Context c) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getAssets().open("profile.txt"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.toLowerCase().startsWith("vless://")) return line;
            }
        } catch (Exception ignored) { /* gömülü profil yok (GitHub sürümü) */ }
        return null;
    }

    private ProfileStore() {}
}
