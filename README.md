# SchafVPN Android

[SchafVPN](https://github.com/ToprakEsen/SchafVPN)'in Android sürümü: tek dokunuşlu, kendi VLESS profilini içine gömen VPN.

- Aynı arayüz (WebView), aynı Xray sürümü (v26.10.31), ML-KEM ve Reality desteği.
- Android'in `VpnService` TUN arayüzü doğrudan Xray'in yerleşik TUN girişine verilir; ek tun2socks katmanı yok.
- Tüm trafik tünelden geçer (IPv4 + IPv6), yerel ağ doğrudan gider. DNS: 1.1.1.1 (tünel üzerinden).
- Android 8.0 (API 26) ve üstü, ARM 64/32 bit telefonlar.

## Kendi APK'nı oluştur (Windows)

1. Bu klasörü indir.
2. **`kur.bat`** dosyasına çift tıkla.
3. `vless://...` linkini yapıştır ve Enter'a bas.
4. JDK ve Android SDK yoksa otomatik indirilir (yönetici izni gerekmez, `.tools\` klasörüne).
5. Bitince `dist\SchafVPN.apk` oluşur. Telefona at, kur ("Bilinmeyen kaynaklar" iznini ver).

İlk açılışta Android "VPN bağlantısı isteği" sorar; **Tamam** de. Bağlıyken bildirimde süre ve
"Bağlantıyı kes" düğmesi görünür.

> Oluşan APK **senin VLESS linkini içerir**, başkasıyla paylaşma.

## Elle derleme

```powershell
# app\src\main\assets\profile.txt içine vless:// linkini koy
.\build.ps1        # -> dist\SchafVPN.apk
```

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
