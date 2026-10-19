# COSMO 0.1.0 doğrulama kaydı

Tarih: 19 Ekim 2026. Bu kayıt bulut geliştirme ortamında üretilen **deneme APK'sı** içindir; gerçek telefon sertifikasyonu değildir.

## Tamamlanan kontroller

```bash
bash scripts/bootstrap-android.sh
source /workspace/.tools/env.sh
./gradlew assembleDebug testDebugUnitTest lintDebug assembleDebugAndroidTest
```

- Kurulum betiği ilk kurulumdan sonra ikinci kez başarıyla çalıştırıldı. JDK, Gradle ve SDK arşivlerinin resmî sağlama toplamları doğrulandı. TLS doğrulaması açık tutuldu.
- `assembleDebug`: başarılı; `com.cosmo.assistant`, sürüm `0.1.0`, minimum API 26, hedef API 35.
- `testDebugUnitTest`: **54 test, 0 başarısız, 0 hata, 0 atlanan**.
- `lintDebug`: başarılı; **0 hata, 0 uyarı**.
- `assembleDebugAndroidTest`: başarılı; 10 cihaz testini içeren ayrı test APK'sı üretildi. Bu testler bir cihazda çalıştırılmadı.
- `apksigner verify --verbose`: başarılı; Android geliştirme anahtarıyla APK Signature Scheme v2 imzası.
- `zipalign -c -P 16 4`: başarılı; APK'nın 16 KB sayfa hizalaması doğrulandı. ARM64 `libvosk.so` ve `libjnidispatch.so` ELF LOAD hizalamaları da 16384 olarak denetlendi.
- Her iki ses kitaplığının birlikte bulunduğu `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64` mimarileri paketlendi.

| Otomatik test | Geçen |
| --- | ---: |
| Türkçe komutlar, söylenen sayılar, SMS/not metni koruma, hatalı girdiler | 25 |
| Telefon intentleri, not sınırı/veri koruma, kilit gizliliği, medya tuşları | 9 |
| Gerçek Activity iş akışı: açılış, not saklama/okuma, saat ve model yokken mikrofon koruması; API 28 ve API 35 üzerinde Robolectric | 8 |
| Sentetik model ZIP'i: yol sınırı, yinelenen dosya, eksik model ve iki grafik biçimi | 6 |
| Çağrı sözcüğü, süre sınırı, tek komut, özgün metin ve saat işaretlerini koruma | 6 |
| **Toplam** | **54** |

Sentetik model dosyaları gerçek ses modeli değildir. Bu testler indirme yolunun dosya işlemlerini sınar; modelin Türkçe tanıma başarısını ölçmez.

Teslim APK'sı: `downloads/COSMO-v0.1.0-debug.apk` (42.766.179 bayt). Yerel çıktı kopyası `dist` klasöründe tutulur.

SHA-256:

```text
571b02007cc4b22d7d38cd5ee72ce3a54264bf3d498a228c3c144b00884f4944
```

## Çalıştırılmayan kontroller ve sınırlar

Bu bulut makinesinde `/dev/kvm` ve bağlı Android telefon bulunmadığından Android emülatörü/fiziksel cihaz testi yapılmadı. Robolectric testleri Java/Android iş akışını doğrular; gerçek mikrofon, pil, ekran kapalı çalışma veya OEM uyumluluğunu doğrulamaz.

Resmî `alphacephei.com` Türkçe model adresi bu bulut oturumunda HTTP 403 döndürdü. Alan adı ortam yapılandırma taslağına eklendi ancak çalışan ağ erişimi değişmedi. **Gerçek model indirilip tanıma için açılmadı.** APK modeli telefonda kullanıcının onayından sonra indirir; bu indirme ve COSMO özel adının tanınması henüz fiziksel cihazda doğrulanmadı. “Asistan” alternatif çağrısı uygulamada desteklenir; tanıma doğruluğu cihazda denenmelidir.

İndirilen model ilk açılışta native Vosk tarafından yüklenirken sorun çıkarsa uygulama durumu ve Android günlükleriyle ayrıca incelenmelidir. APK'nın derlenmesi bu adımın çalıştığını kanıtlamaz.

## Telefonda kabul kontrol listesi

1. APK'yı Android 8+ telefona kur; yazılı `saat kaç`, `not al Yarın süt al` ve `notlarımı oku` komutlarını dene.
2. Türkçe modeli indir; ilerleme, iptal ve tekrar deneme yollarını kontrol et. İnterneti kapatıp dinlemeyi başlat. Android'de çevrimdışı Türkçe metin okuma sesini yükle.
3. Mikrofon ve bildirim izinlerini ver. `Asistan, saat kaç` ve `COSMO, saat kaç` de; aynı cümlede ve ayrı cümlelerde çağrı/komut akışını dene. Çağrı sözcüğü olmadan konuşmanın komut çalıştırmadığını kontrol et.
4. Fener, ses, not, uygulama açma, alarm ve zamanlayıcıyı dene. Arka plandaki ekran işlemlerinin bildirime dokununca açıldığını doğrula. Numara/SMS taslağında otomatik arama veya gönderim yapılmadığını kontrol et.
5. Telefonu kilitle. Saat/pil yanıtı alınabildiğini, not okuma ve gezinmenin engellendiğini doğrula.
6. Ekranı kapatıp en az 15 dakika sonra tekrar seslen. Üretici pil yönetimi kesiyorsa uygulamanın pil ayarını değiştirip yeniden dene; pil tüketimini gözlemle.
7. TTS yanıtı sırasında COSMO'nun kendi sesini yeni komut olarak işlemediğini kontrol et. Mikrofon izni geri alma, sistem mikrofon anahtarı, telefon araması ve başka kayıt uygulamasıyla kesinti davranışını dene.
8. Bildirimdeki durdur düğmesini ve `Asistan, dinlemeyi durdur` komutunu dene. Mikrofon göstergesinin kapandığını doğrula. Yeniden başlatma/uygulamayı zorla durdurma sonrası açık kullanıcı başlatması gerektiğini kontrol et.
9. İsteğe bağlı gezinme açıklamasını kabul edip Android hizmetini elle etkinleştir; geri/ana ekran/kaydırma komutlarını dene. COSMO anahtarını kapatınca gezinmenin durduğunu kontrol et.
10. Bağlı ve kilidi açık cihazda `./gradlew connectedDebugAndroidTest` çalıştır; bu 10 testin sonucu bu kayıttan ayrı raporlanmalıdır.

## Yeniden kullanılabilir bulut ayarları

`install_script` ve `start_skill` ortam taslağına kaydedildi; `alphacephei.com` özel ağ alanı eklendi. Taslak kaydı komut çalıştırmaz veya ortamı yayımlamaz. Mevcut oturumda derleme/test doğrulandı; yeni bir görevde snapshot geri yükleme ayrıca doğrulanmadı. Ortamı kalıcı kullanıma almak için kullanıcı ortam ayarlarında değişiklikleri gözden geçirip kaydetmeli ve ortamı yayımlamalıdır.
