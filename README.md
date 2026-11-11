# SchafVPN Android

[SchafVPN](https://github.com/ToprakEsen/SchafVPN)'in Android sürümü: kendi VLESS profilinle çalışan, kendini güncelleyen tek dokunuşlu VPN.

> 💻 **Windows sürümü:** [SchafVPN](https://github.com/ToprakEsen/SchafVPN)
> ([exe indir](https://github.com/ToprakEsen/SchafVPN/releases/latest))

- Aynı arayüz (WebView), aynı Xray sürümü (v26.10.31), ML-KEM ve Reality desteği.
- Android'in `VpnService` TUN arayüzü doğrudan Xray'in yerleşik TUN girişine verilir; ek tun2socks katmanı yok.
- Tüm trafik tünelden geçer (IPv4 + IPv6), yerel ağ doğrudan gider. DNS: 1.1.1.1 (tünel üzerinden).
- Android 8.0 (API 26) ve üstü, ARM 64/32 bit telefonlar.

## Kurulum

1. [**Releases**](https://github.com/ToprakEsen/SchafVPN-Android/releases/latest) sayfasından `SchafVPN.apk` dosyasını telefona indir ve kur
   ("Bilinmeyen kaynaklardan yükleme" iznini ver).
2. İlk açılışta v2rayN / v2rayNG'den kopyaladığın `vless://...` linkini yapıştırıp **Kaydet**'e dokun.
3. Güç düğmesine dokun; Android "VPN bağlantısı isteği" sorar, **Tamam** de.

- Link yalnızca telefonda, Android Keystore anahtarıyla şifrelenerek saklanır. APK'da profil yoktur.
- **Ayarlar** (sağ üstteki dişli): açılışta otomatik bağlan, Android'in "Her zaman açık VPN" ayarı,
  profil değiştirme, otomatik güncelleme.
- Yeni sürüm yayınlandığında uygulama arka planda indirir ve **Güncelle** düğmesi gösterir;
  Android kurulum için bir kez onay ister.

## Yeni sürüm yayınlama (geliştirici)

```powershell
.\release.ps1 -Version 1.2.0 -Notes "Neler değişti"
```

Script profilsiz APK derler, içinde kişisel profil olmadığını ve kalıcı anahtarla imzalandığını
doğrular, sürüm numarasını commit'ler ve GitHub Release oluşturur.

> **İmza anahtarı:** Güncellemeler yalnızca aynı anahtarla imzalı APK'lar arasında kurulabilir.
> Anahtar depo dışında, `%USERPROFILE%\.schafvpn\` klasöründedir (`android-release.jks` +
> `keystore.properties`). **Bu klasörü yedekle**; kaybolursa kullanıcılar bir sonraki sürümü
> ancak uygulamayı kaldırıp yeniden kurarak alabilir.

## Kendin derle (isteğe bağlı)

1. **`kur.bat`** dosyasına çift tıkla, linkini yapıştır. JDK ve Android SDK yoksa otomatik indirilir.
2. `dist\SchafVPN.apk` oluşur. Bu APK linkini içerir (ilk açılışta şifreli depoya taşınır); paylaşma.

Arayüz Windows sürümünden üretilir: `.\scripts\sync-ui.ps1` (ortak `ui/index.html` → Android kopyası).

## Yapı

| Yol | İçerik |
|---|---|
| `app/src/main/java/.../VlessProfile.java` | Link çözümleme + Xray config (TUN girişi + VLESS çıkışı) |
| `app/src/main/java/.../SchafVpnService.java` | `VpnService`: TUN kurulumu, Xray başlat/durdur, bildirim |
| `app/src/main/java/.../MainActivity.java` | WebView arayüz, JS köprüsü, hız ve IP/konum |
| `app/src/main/assets/index.html` | Windows sürümüyle aynı arayüz (Android köprüsüyle) |
| `scripts/get-libv2ray.ps1` | [AndroidLibXrayLite](https://github.com/2dust/AndroidLibXrayLite) indirir, SHA256 doğrular |

APK, makinenin Android debug anahtarıyla imzalanır (kişisel kurulum için). Aynı bilgisayarda
yeniden derlenen sürümler eskisinin üzerine güncellenebilir.
