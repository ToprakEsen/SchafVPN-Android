package io.github.toprakesen.schafvpn;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * GitHub Releases üzerinden güncelleme: son sürümü denetler, APK'yı indirip SHA256 ile
 * doğrular ve Android'in paket yükleyicisine verir (kullanıcı onayıyla kurulur).
 * Güncelleme yalnızca aynı imza anahtarıyla imzalanmış APK'lar arasında mümkündür.
 */
public final class Updater {
    public static final String REPO = "ToprakEsen/SchafVPN-Android";
    private static final String ASSET = "SchafVPN.apk";

    public static final class Info {
        public final String version, url, sha256;
        public final long size;
        Info(String version, String url, String sha256, long size) {
            this.version = version; this.url = url; this.sha256 = sha256; this.size = size;
        }
    }

    public interface Progress { void onProgress(double p); }

    public static String current(Context c) {
        try { return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName; }
        catch (PackageManager.NameNotFoundException e) { return "0.0.0"; }
    }

    /**
     * Daha yeni bir sürüm varsa bilgisini, yoksa null döner; denetlenemezse hata fırlatır.
     * Önce github.com/.../releases/latest yönlendirmesi + .sha256 dosyası: GitHub API'nin
     * saatlik IP sınırına (mobil operatörlerde IP'ler paylaşıldığı için çabuk dolar) tabi değil.
     * Yalnızca o başarısız olursa API denenir.
     */
    public static Info check(Context c) throws Exception {
        Exception redirectError;
        try { return checkRedirect(c); }
        catch (Exception e) { redirectError = e; }
        try {
            Info viaApi = checkApi(c);
            return viaApi == NO_RELEASE_YET ? null : viaApi;
        } catch (Exception e) {
            throw new Exception("GitHub'a ulaşılamadı (" + redirectError.getMessage() + "; " + e.getMessage() + ")");
        }
    }

    /** "Henüz hiç sürüm yok" işareti (API 404). */
    private static final Info NO_RELEASE_YET = new Info("", "", "", 0);

    private static Info checkApi(Context c) throws Exception {
        HttpURLConnection h = open("https://api.github.com/repos/" + REPO + "/releases/latest", c);
        h.setRequestProperty("Accept", "application/vnd.github+json");
        try {
            int code = h.getResponseCode();
            if (code == 404) return NO_RELEASE_YET;
            if (code != 200) throw new Exception("API HTTP " + code);
            JSONObject root = new JSONObject(readAll(h.getInputStream()));
            String tag = root.optString("tag_name").replaceFirst("^[vV]", "");
            if (compare(tag, current(c)) <= 0) return null;

            String url = null, sha = null, shaUrl = null;
            long size = 0;
            JSONArray assets = root.getJSONArray("assets");
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.getJSONObject(i);
                String name = a.optString("name");
                if (ASSET.equals(name)) {
                    url = a.optString("browser_download_url");
                    size = a.optLong("size");
                    String d = a.optString("digest", "");
                    if (d.startsWith("sha256:")) sha = d.substring(7);
                } else if ((ASSET + ".sha256").equals(name)) {
                    shaUrl = a.optString("browser_download_url");
                }
            }
            if (url == null) return null;
            if (sha == null && shaUrl != null) {
                HttpURLConnection s = open(shaUrl, c);
                try { sha = readAll(s.getInputStream()).trim().split("\\s+")[0]; } finally { s.disconnect(); }
            }
            if (sha == null || sha.length() != 64) return null; // doğrulanamayan güncelleme kurulmaz
            return new Info(tag, url, sha.toLowerCase(Locale.ROOT), size);
        } finally { h.disconnect(); }
    }

    /** API'siz yol: github.com/.../releases/latest → .../releases/tag/vX.Y.Z yönlendirmesi. */
    private static Info checkRedirect(Context c) throws Exception {
        HttpURLConnection h = open("https://github.com/" + REPO + "/releases/latest", c);
        h.setInstanceFollowRedirects(false);
        String location;
        try {
            int code = h.getResponseCode();
            location = h.getHeaderField("Location");
            if (code / 100 != 3 || location == null) throw new Exception("releases/latest HTTP " + code);
        } finally { h.disconnect(); }
        int i = location.lastIndexOf("/tag/");
        if (i < 0) return null; // hiç sürüm yok: /releases sayfasına yönlenir
        String rawTag = location.substring(i + 5);
        String tag = rawTag.replaceFirst("^[vV]", "");
        if (compare(tag, current(c)) <= 0) return null;

        String base = "https://github.com/" + REPO + "/releases/download/" + rawTag + "/" + ASSET;
        HttpURLConnection s = open(base + ".sha256", c);
        String sha;
        try {
            if (s.getResponseCode() != 200) throw new Exception(".sha256 HTTP " + s.getResponseCode());
            sha = readAll(s.getInputStream()).trim().split("\\s+")[0];
        } finally { s.disconnect(); }
        if (sha.length() != 64) throw new Exception("SHA256 dosyası geçersiz.");
        return new Info(tag, base, sha.toLowerCase(Locale.ROOT), 0);
    }

    /** APK'yı indirir ve SHA256 doğrular; doğrulanmış dosyayı döner. */
    public static File download(Context c, Info u, Progress progress) throws Exception {
        File dir = new File(c.getCacheDir(), "update");
        if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("Önbellek klasörü oluşturulamadı.");
        File target = new File(dir, "SchafVPN-" + u.version + ".apk");
        if (target.exists() && u.sha256.equals(sha256(target))) return target;
        File[] old = dir.listFiles();
        if (old != null) for (File f : old) //noinspection ResultOfMethodCallIgnored
            f.delete();

        File part = new File(dir, target.getName() + ".part");
        HttpURLConnection h = open(u.url, c);
        h.setReadTimeout(60000);
        try (InputStream in = h.getInputStream(); OutputStream out = new FileOutputStream(part)) {
            long total = h.getContentLengthLong() > 0 ? h.getContentLengthLong() : u.size, read = 0;
            byte[] buf = new byte[1 << 16];
            int n; double last = -1;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                read += n;
                if (total > 0 && progress != null) {
                    double p = (double) read / total;
                    if (p - last >= 0.01) { last = p; progress.onProgress(p); }
                }
            }
        } finally { h.disconnect(); }

        if (!u.sha256.equals(sha256(part))) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw new SecurityException("İndirilen güncellemenin SHA256 değeri uyuşmuyor.");
        }
        if (!part.renameTo(target)) throw new IllegalStateException("Güncelleme dosyası kaydedilemedi.");
        return target;
    }

    /** APK'yı paket yükleyiciye verir; Android kullanıcıdan onay ister (InstallReceiver). */
    public static void install(Context c, File apk) throws Exception {
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(c.getPackageName());
        if (Build.VERSION.SDK_INT >= 31)
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        int id = pi.createSession(params);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (InputStream in = new FileInputStream(apk); OutputStream out = s.openWrite("base.apk", 0, apk.length())) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                s.fsync(out);
            }
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            PendingIntent done = PendingIntent.getBroadcast(c, id, new Intent(c, InstallReceiver.class), flags);
            s.commit(done.getIntentSender());
        }
    }

    // ---------------------------------------------------------------- yardımcılar

    /** "1.2.10" > "1.2.9" gibi sayısal karşılaştırma. */
    static int compare(String a, String b) {
        String[] x = a.split("[.+-]"), y = b.split("[.+-]");
        for (int i = 0; i < 3; i++) {
            int xi = i < x.length ? parse(x[i]) : 0, yi = i < y.length ? parse(y[i]) : 0;
            if (xi != yi) return Integer.compare(xi, yi);
        }
        return 0;
    }

    private static int parse(String s) { try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; } }

    private static HttpURLConnection open(String url, Context c) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(15000);
        h.setReadTimeout(15000);
        h.setInstanceFollowRedirects(true);
        h.setRequestProperty("User-Agent", "SchafVPN-Android-Updater/" + current(c));
        return h;
    }

    private static String readAll(InputStream in) throws Exception {
        try (InputStream i = in) {
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = i.read(buf)) > 0) b.write(buf, 0, n);
            return b.toString("UTF-8");
        }
    }

    private static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format(Locale.ROOT, "%02x", b));
        return sb.toString();
    }

    private Updater() {}
}
