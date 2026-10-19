# COSMO

Android telefonlar için Türkçe, sesli kişisel asistan. İlk sürüm; belirli telefon komutlarını uygulayan, cihaz üzerinde ses çözen bir **Android APK** uygulamasıdır. Android 8.0 (API 26) veya üzeri gerekir. Serbest sohbet için bir yapay zekâ servisine bağlanmaz.

## Dosyaları indir

- **[COSMO 0.1.0 APK dosyasını indir](https://github.com/dereli2002/COSMO/raw/refs/heads/main/downloads/COSMO-v0.1.0-debug.apk)** — yaklaşık 41 MB, Android için deneme sürümü.
- [Kaynak kodu ZIP olarak indir](https://github.com/dereli2002/COSMO/raw/refs/heads/main/downloads/COSMO-v0.1.0-kaynak.zip)
- [Kısa kurulum adımları](downloads/KURULUM.txt) · [SHA-256 sağlama toplamları](downloads/SHA256SUMS.txt)

Dosyalar GitHub'da `downloads` klasöründedir. APK dosyasının GitHub sayfasını açtıysan **Download raw file / Ham dosyayı indir** düğmesini kullan. ZIP kaynak kod içindir; telefona kurulacak dosya `.apk` uzantılıdır.

## Telefona kurulum

1. Yukarıdaki APK bağlantısından veya `downloads/COSMO-v0.1.0-debug.apk` dosyasından uygulamayı indir, telefona aktar ve aç. Android isterse yalnızca bu dosyayı açtığın uygulamaya kurulum izni ver. Bu APK geliştirme anahtarıyla imzalanmıştır.
2. COSMO'da **Türkçe modeli indir** düğmesine bas. Resmî Vosk Türkçe modeli ilk kullanımda yaklaşık 35 MB indirilir. Kurulum için en az 200 MB boş alan ve internet bağlantısı önerilir. Model APK'nın içine eklenmemiştir.
3. **Dinlemeyi başlat** düğmesine bas; mikrofon ve Android 13+ bildirim izinlerini ver.
4. **“Asistan, saat kaç?”** de. “COSMO”, “Kosmo”, “Kozmo” ve “Kozmos” yazımları da çağrı olarak kabul edilir. Türkçe model özel adı tanımazsa **Asistan** kullan. Adını tek başına söylediğinde sonraki komut için 10 saniye bekler.
5. Yanıtlar için telefonun metin okuma ayarlarında **çevrimdışı Türkçe ses** yüklü olmalıdır. Yoksa komutlar yine işlenir, yanıtlar ekranda gösterilir.

Dinlemeyi uygulamadan, sürekli mikrofon bildiriminden veya **“Asistan, dinlemeyi durdur”** komutuyla kapatabilirsin. Uygulamadan başka bir ekrana geçmek dinlemeyi kapatmaz. Telefon yeniden başladığında veya uygulama zorla durdurulduğunda COSMO'yu açıp yeniden başlatmalısın.

## Komutlar

Sesli kullanımda örneklerin başına **Asistan** ekle. Uygulamadaki yazılı komut alanında çağrı sözcüğüne gerek yoktur; modeli indirmeden de çalışır.

| İşlev | Örnek |
| --- | --- |
| Saat, tarih, pil | `saat kaç`, `bugün günlerden ne`, `pil durumu` |
| Fener | `feneri aç`, `feneri kapat` |
| Medya sesi | `sesi artır`, `sesi azalt`, `sesi kapat` |
| Medya oynatıcısı | `müziği başlat`, `müziği durdur`, `sonraki şarkı` |
| Uygulama | `YouTube aç`, `WhatsApp aç` |
| Yerel notlar | `not al yarın süt al`, `notlarımı oku` |
| Ayarlar | `Wi-Fi ayarları`, `Bluetooth ayarları`, `konum ayarları` |
| Harita ve web | `Ankara haritada ara`, `internette hava durumu ara` |
| Alarm | `07:30 alarm kur`, `saat yedi buçuk alarm kur` |
| Zamanlayıcı | `beş dakika zamanlayıcı kur`, `bir saat on beş dakika zamanlayıcı kur` |
| Arama ekranı | `05551234567 numarasını ara` |
| Mesaj taslağı | `05551234567 numarasına mesaj yaz merhaba` |
| İsteğe bağlı gezinme | `geri git`, `ana ekrana git`, `son uygulamalar`, `bildirimleri aç`, `aşağı kaydır` |
| Yardım ve durdurma | `yardım`, `dinlemeyi durdur` |

Arama ve SMS komutları numarayı hazırlar; **arama veya gönderim düğmesine kullanıcı basar**. Rehberden isim çözümleme henüz yoktur. Alarm ve zamanlayıcı sistemin saat ekranında kontrol edilir. Arka plandayken uygulama/ayar/arama gibi ekran açan işlemler için **COSMO işlem bildirimine dokunmak gerekir**. Böylece Android'in arka plandan ekran açma kuralına uyulur.

## İzinler ve Android sınırları

Android'de bir uygulamaya “telefonun tüm özelliklerine erişim” veren tek bir izin yoktur. COSMO yalnızca genel Android API'leriyle ve kullanıcının açtığı izinlerle çalışır.

| Erişim | Kullanımı |
| --- | --- |
| Mikrofon, mikrofon ön plan servisi | Kullanıcı başlattıktan sonra sürekli yerel ses çözümleme |
| Bildirim | Dinleme göstergesi, durdurma ve ekrana geçiş |
| Kamera (isteğe bağlı) | Yalnızca fener; fotoğraf/video çekilmez |
| Medya sesi | Medya sesini ve etkin oynatıcıyı yönetme |
| Uyanık tutma | Dinleme açıkken ses işlemenin sürmesi |
| İnternet | Kullanıcının başlattığı Türkçe model indirmesi |
| Alarm | Sistem saat uygulamasında alarm/zamanlayıcı hazırlama |
| Erişilebilirlik (isteğe bağlı) | Yalnızca kullanıcı komutuyla gezinme ve kaydırma |

Erişilebilirlik için önce COSMO'daki **Sesli gezinme** anahtarını açıp açıklamayı kabul et, sonra Android ayarlarında hizmeti kendin etkinleştir. Dışarıdan kurulan APK'larda Android ayrıca uygulama bilgi ekranında **Kısıtlanmış ayarlara izin ver** adımı isteyebilir. COSMO bu adımları senin yerine uygulamaz. Gezinme hizmetinin ekran içeriğini okuma yetkisi yoktur.

Wi-Fi, Bluetooth ve konum komutları ilgili sistem ayarını açar; bağlantıyı sessizce değiştirmez. Başka uygulamaların özel verilerini okuma, otomatik WhatsApp gönderimi, güvenlik ekranlarını atlama, root işlemleri ve uygulama kaldırma bu sürümde yoktur.

Kilit ekranında yalnız saat, tarih, pil, yardım ve dinlemeyi durdurma gibi sınırlı komutlar çalışır; notlar ve telefon işlemleri kilit açılmasını ister. Ses komutları konuşmacı kimliğini doğrulamaz.

## Sürekli dinleme

Ses çözümleme Vosk ile cihaz üzerinde yapılır; özel bir düşük güç çağrı sözcüğü çipi kullanılmaz. Sürekli mikrofon ve işlemci kullanımı pil tüketir. Bildirim açık kaldığı sürece kullanıcı dinlemeyi görebilir ve durdurabilir. Android'in mikrofon gizlilik anahtarı, başka bir uygulamanın mikrofonu alması veya üreticinin pil yönetimi dinlemeyi kesebilir. COSMO'yu tekrar açıp başlat; gerekirse pil ayarlarında arka plan çalışmasına izin ver. Her Android modelinde kesintisizlik garantisi yoktur.

Kısmi tanıma sonuçları komut çalıştırmaz. Tamamlanan cümle çağrı sözcüğüyle başlamalı veya önceki çağrıdan sonraki 10 saniyelik aralıkta gelmelidir. COSMO konuşurken kendi yanıtını komut sanmaması için tanıma duraklatılır.

## Geliştirme ve derleme

Android Studio'da projeyi açıp Android SDK 35 ve JDK 17+ kullanabilirsin. Gradle Wrapper 8.11.1 ve Android Gradle Plugin 8.9.2 sabitlenmiştir. Gradle arşivinin SHA-256 doğrulaması açıktır.

Bu Linux bulut ortamında:

```bash
cd /workspace/COSMO
bash scripts/bootstrap-android.sh
source /workspace/.tools/env.sh
./gradlew assembleDebug testDebugUnitTest lintDebug assembleDebugAndroidTest
```

Araçlar `/workspace/.tools` altında tutulur. Kurulum betiği resmî JDK/Gradle/SDK arşivlerini doğrular, ortamın güvenilir proxy sertifikasını standart JDK köklerine ekler ve API 35'i kurar. TLS doğrulaması kapatılmaz. İlk derleme bağımlılıkları internetten indirir. Sonraki geliştirme görevleri mevcut checkout'u kullanmalıdır; yeni worktree gerekmez.

APK: `app/build/outputs/apk/debug/app-debug.apk`. Cihaza yüklemek için:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.cosmo.assistant/.MainActivity
./gradlew connectedDebugAndroidTest
```

`assembleDebugAndroidTest` cihaz testlerini yalnız derler. `connectedDebugAndroidTest` bağlı ve kilidi açık bir Android cihaz/emülatör gerektirir. Üretim dağıtımı için ayrıca kişisel yayın anahtarı, imzalama ve mağaza hazırlığı gerekir; kaynak kodda yayın anahtarı yoktur.

## Doğrulama

Derleme ve test sonuçları [VALIDATION.md](VALIDATION.md) içinde tutulur. Mikrofon, hoparlör, gerçek Türkçe model ve ekran kapalı çalışma için fiziksel cihaz kontrol listesi de oradadır. Otomatik testler gerçek cihazda ses tanımanın doğrulandığı anlamına gelmez.

## Gizlilik ve lisanslar

Ses kaydı dosyası oluşturulmaz ve ses tanıma için bulut servisi kullanılmaz. Notlar ve son komut/yanıt bilgisi uygulamanın özel alanında tutulur; Android yedeklemesi kapalıdır. Kamera görüntüsü, rehber, SMS kutusu veya ekran metni toplanmaz. Ayrıntılar: [PRIVACY.md](PRIVACY.md).

Vosk Android **0.3.75** (Apache-2.0), JNA **5.18.1** (Apache-2.0/LGPL-2.1+ seçeneği) kullanılır. Türkçe `vosk-model-small-tr-0.3` modeli Vosk'un resmî sunucusundan ayrı indirilir (model listesinde Apache-2.0). Model ve bağımlılıkların kendi lisansları geçerlidir. Yeni Vosk/JNA sürümlerinin arm64 kitaplıkları 16 KB bellek sayfalarına göre hizalanmıştır.
