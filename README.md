# FERİ

Türkçe sesli telefon yardımcısı. Uygulamanın adı ve seslenişi **FERİ**. 0.2.0 sürümü Galaxy S24 Ultra / Android 16 kullanımına göre hazırlanmıştır. Bu indirme **ARM64** Android 8+ cihazlar içindir; paket adı `com.cosmo.assistant` korunur, mevcut COSMO sürümünün üzerine güncellenir.

## İndir ve güncelle

- **[FERİ 0.2.0 APK indir — yaklaşık 91 MB](https://github.com/dereli2002/COSMO/raw/refs/heads/main/downloads/FERI-v0.2.0-arm64.apk)**
- [Kaynak kod ZIP](https://github.com/dereli2002/COSMO/raw/refs/heads/main/downloads/FERI-v0.2.0-kaynak.zip)
- [Kurulum](downloads/KURULUM.txt) · [SHA-256](downloads/SHA256SUMS.txt) · [Doğrulama kaydı](VALIDATION.md)

1. APK'yı mevcut COSMO'nun **üzerine kur**. Aynı imza ve uygulama kimliği kullanılır; notların korunur. Başlatıcıdaki yeni ad FERİ olur.
2. **Ses modellerini hazırla** düğmesine bas. Türkçe komut ve Feri sesleniş modelleri APK içindedir; bu hazırlama internet kullanmaz. Önceden kurulu Türkçe model yeniden indirilmez. En az 170 MB boş alan, toplam kurulum/indirme dosyaları için yaklaşık 500 MB alan önerilir.
3. **Dinlemeyi başlat** ve mikrofon/bildirim izinlerini ver.
4. **Feri** de, kısa sinyali bekle, ardından örneğin **saat kaç** söyle. Tek komut için 10 saniye vardır. Sonraki komutta yeniden Feri diye seslen. Bu sürümde sesleniş ve komut iki ayrı konuşma adımıdır.
5. Geniş denetim için aşağıdaki isteğe bağlı izinleri aç. Sesli yanıtlar için telefonda çevrimdışı Türkçe metin okuma sesi bulunmalıdır.

Dinlemeyi uygulama, bildirim veya Feri → sinyal → **dinlemeyi durdur** komutuyla kapatabilirsin. Telefon yeniden başladıktan veya uygulama zorla durdurulduktan sonra elle başlatma gerekir. Mikrofona görünür ön plan servisiyle erişilir.

## İsteğe bağlı izinler

| Uygulamadaki kontrol | Açtığı yetenek |
| --- | --- |
| Sesli gezinme + Android Erişilebilirlik ayarında FERİ | Geri/ana ekran/kaydırma, desteklenen uygulama ve ayar ekranlarını sesle açma, ekran kilitleme |
| Ekran okuma ve denetimi | Açık komut üzerine görünen ekran yazılarını seslendirme, düğmeye basma ve seçili alana yazma; ekran görüntüsü isteği |
| Rehber ve sesli arama izinleri | Rehberdeki tekil tam eşleşmelerle arama/SMS taslağı; onaylı doğrudan numara araması |
| Parlaklık ve döndürme izni | Android'in özel sistem ayarı izniyle parlaklık ve ekran yönü |
| Fener için kamera izni | Fener; bu izinle görüntü veya video kaydı alınmaz |

Ekran okuma ayrı açıklama/onay gerektirir. Eski gezinme izni, ekran okuma iznini kendiliğinden açmaz. Erişilebilirlik hizmeti ekran içeriğini sürekli toplamaz; yalnız komut verildiğinde inceler. Şifre/PIN/OTP alanları ve Android izin/güvenlik ekranları denetlenmez. Uygulamadaki anahtarları kapatınca ilgili özellikler durur. Android, dışarıdan kurulan APK için uygulama bilgi ekranında kısıtlanmış ayar izni isteyebilir; bu adım ve sistem izinleri kullanıcı tarafından tamamlanır.

## Sesli komutlar

Her satır için önce **Feri**, sonra sinyal, sonra komut. Yazılı komut alanında sesleniş gerekmez.

| İşlem | Örnek |
| --- | --- |
| Saat, tarih, pil | `saat kaç`, `bugün günlerden ne`, `pil durumu` |
| Selamlaşma | `merhaba`, `nasılsın`, `adın ne`, `teşekkür ederim` |
| Fener | `feneri aç`, `feneri kapat` |
| Ses ve medya | `sesi artır`, `sesi azalt`, `müziği başlat`, `müziği durdur`, `sonraki şarkı`, `önceki şarkı` |
| Uygulama ve gezinme | `YouTube aç`, `kendini aç`, `geri git`, `ana ekrana git`, `aşağı kaydır`, `bildirimleri aç` |
| Ekran okuma | `ekranı oku` |
| Düğme seçme | `ekranda Kaydet düğmesine bas` |
| Metin yazma | `ekrana yaz Merhaba, nasılsın?` — seçili sıradan yazı alanını değiştirir |
| Parlaklık/yön | `parlaklığı yüzde elli yap`, `parlaklığı artır`, `otomatik döndürmeyi aç`, `ekranı dikey kilitle` |
| Ekran | `ekran görüntüsü al`, `ekranı kilitle` |
| Rehber | `Ahmet kişisini ara`, `kişiyi göster Ahmet`, `Ahmet adlı kişiye mesaj yaz merhaba` |
| Numara | `05551234567 numarasını ara` — arama ekranı; `05551234567 numarasını doğrudan ara` — sesli onaylı arama |
| Mesaj | `05551234567 numarasına mesaj yaz merhaba` — taslak |
| Kamera/takvim | `kamerayı aç`, `video kamerasını aç`, `takvime toplantı ekle` |
| Notlar | `not al yarın süt al`, `notlarımı oku` |
| Saat uygulaması | `saat yedi buçuk alarm kur`, `beş dakika zamanlayıcı kur` |
| Arama/harita | `internette hava durumu ara`, `Ankara haritada ara` |
| Ayarlar | `Wi-Fi ayarları`, `Bluetooth ayarları`, `konum ayarları`, `NFC ayarları`, `depolama ayarları` |
| Yardım/durdurma | `yardım`, `durumunu söyle`, `dinlemeyi durdur` |

### Sesli onay

Doğrudan arama, ekrana basma, metin yazma ve güç menüsü komutları hazırlanır; FERİ ne yapacağını söyler. Ardından yeniden **Feri** de, sinyali bekle ve **onayla** söyle. İptal için aynı akışta **iptal** söyle. Onay 30 saniye geçerlidir ve bir kez kullanılabilir. Başka komut, kilitlenme, izin değişikliği veya ekran/alıcı değişikliği bekleyen işlemi iptal eder. Belirsiz ya da birden fazla eşleşen düğmeye basılmaz.

### Android'in sınırları

Tek bir izinle telefonun bütün işlevlerine sınırsız erişim verilemez. Bu uygulama root veya cihaz sahibi yetkisi istemez. Wi-Fi/Bluetooth, mobil veri, uçak modu, izinler ve bazı sistem seçenekleri ilgili Android ekranına yönlendirilir. Güç komutu güç menüsünü açar; kapatma/yeniden başlatma seçimi kullanıcıya kalır. Güvenlik ve izin ekranlarında otomatik işlem yapılmaz. Kamera/takvim komutları ilgili uygulamayı/taslağı açar; kaydetme ve çekim kullanıcı tarafından veya desteklenen sıradan ekran kontrolüyle tamamlanır. Diğer uygulama özel verilerine, bankacılık korumalarına veya kilit ekranına gizli erişim yoktur.

Rehberde yalnız tekil tam ad eşleşmesi kullanılır. Belirsiz adlar veya bir kişinin birden fazla numarası seçim ekranına yönlendirilir. Eksik/yanlış tanınan isim için tam ad veya numara söyle. SMS doğrudan arka planda gönderilmez; taslak Android'in mesaj ekranında açılır. Erişilebilirlik kapalıysa arka plandan ekran açan işlemler için bildirim dokunuşu gerekir.

## Sesleniş ve gizlilik

FERİ adı için ayrı, yerel ses çözümleyici kullanılır. Söylenen `/feri/` sesi İngilizce modelin `ferry` sözcüğüyle algılanır; `perry` karşılaştırma sözcüğüdür ve uyandırmaz. Sonuç tam olarak tek `ferry` sözcüğü ve güveni en az 0.65 olmalıdır. Ardından Türkçe komut modeline geçilir. Kısmi sonuçlar işlem başlatmaz; FERİ konuşurken ses çözümleme duraklatılır.

Gerçek native sentetik ses testinde 9 seslenişin 8'i algılandı; 9 sıradan konuşma yanlış uyandırmadı. 6 benzer isim örneğinin 4'ü de uyandırdı. Bunlar gerçek kullanıcı doğruluk oranı değildir; telefonda yeni FERİ seslenişi ve pil tüketimi ayrıca denenmelidir. Yanlış uyanma tek başına arama/tıklama/yazma onayı vermez.

Konuşma ve ekran içeriği sunucuya gönderilmez. Ekran okuma sonucu diske yazılmaz. Rehber verisi yalnız açık komutun çözülmesi için yerel olarak kullanılır. Notlar ve açık komut metinleri uygulamanın özel alanındadır; yedekleme kapalıdır. Ayrıntılar [PRIVACY.md](PRIVACY.md) ve [THIRD_PARTY.md](THIRD_PARTY.md).

Bu sürüm belirli komutları ve yerel selamlaşmaları uygular; serbest bir dil modeli sohbeti içermez. Sürekli ses çözümleme pil kullanır. Ekran kapalı çalışmayı üreticinin pil ayarları ve Android'in mikrofon kontrolü etkileyebilir.

## Geliştirme

```bash
cd /workspace/COSMO
bash scripts/bootstrap-android.sh
source /workspace/.tools/env.sh
./gradlew assembleDebug testDebugUnitTest lintDebug assembleDebugAndroidTest
```

JDK 21, Gradle 8.11.1, AGP 8.9.2, Android SDK 35; Robolectric 4.17 testleri Android API 28/36 üzerinde çalışır. APK `app/build/outputs/apk/debug/app-debug.apk` dosyasıdır. Bu sürüm ARM64 paketler; diğer mimariler için Gradle ABI filtresi ayrı derlemede değiştirilmelidir. Mevcut izole checkout'u kullan; kullanıcı istemedikçe worktree oluşturma.

```bash
python3 scripts/check-turkish-model.py app/src/main/assets/models/vosk-model-small-tr-0.3.zip
python3 scripts/check-wake-phrase.py --vosk-lib /path/to/trusted/host/libvosk.so
adb install -r app/build/outputs/apk/debug/app-debug.apk
./gradlew connectedDebugAndroidTest
```

Native host kontrolü isteğe bağlı güvenilir Linux kitaplığı gerektirir; fiziksel Android testi değildir. `assembleDebugAndroidTest` cihaz testlerini derler, çalıştırmaz. Üretim dağıtımı için ayrı yayın anahtarı/mağaza hazırlığı gerekir. Güncel test ve imza kanıtları [VALIDATION.md](VALIDATION.md) içindedir.
