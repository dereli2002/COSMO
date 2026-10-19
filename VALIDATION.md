# COSMO 0.1.1 doğrulama kaydı

Tarih: 19 Ekim 2026. Bu kayıt gerçek modelle doğrulanan **deneme APK'sı** içindir; fiziksel telefon mikrofon testi değildir.

## Düzeltilen hata

0.1.0 sürümü yalnız `am/final.mdl`, `conf/model.conf` ve `graph/` gibi yeni dosya yollarını denetliyordu. Resmî `vosk-model-small-tr-0.3` arşivi ise `final.mdl`, `mfcc.conf`, `HCLr.fst`, `Gr.fst` ve `disambig_tid.int` dosyalarını kökte tutar. Vosk bu eski düzeni desteklediği halde COSMO modeli eksik sayıyordu. Arayüz ayrıca her kurulum hatasına internet bağlantısı tavsiyesi ekliyordu.

0.1.1 iki model düzenini de tanır; dinamik grafiğin disambig dosyasını ve mevcut i-vector klasörünün tüm gerekli dosyalarını denetler. Gerçek Türkçe arşiv APK'ya eklendi. Hazırlama sırasında SHA-256 doğrulanır, ZIP güvenli geçici klasöre açılır ve model atomik olarak kurulur. %100 yalnız model gerçekten hazır olduktan sonra bildirilir. Depolama/doğrulama hataları kendi açıklamasıyla gösterilir; bağlantıyla ilgili olmayan hataya internet uyarısı eklenmez.

## Tamamlanan kontroller

```bash
source /workspace/.tools/env.sh
./gradlew assembleDebug testDebugUnitTest lintDebug assembleDebugAndroidTest
python3 scripts/check-turkish-model.py app/src/main/assets/models/vosk-model-small-tr-0.3.zip
```

- `assembleDebug`: başarılı; `com.cosmo.assistant`, sürüm `0.1.1`, versionCode `2`, minimum API 26, hedef API 35.
- `testDebugUnitTest`: **64 test, 0 başarısız, 0 hata, 0 atlanan**.
- `lintDebug`: **0 hata, 0 uyarı**.
- `assembleDebugAndroidTest`: 10 cihaz testini içeren test APK'sı derlendi; bu testler bir cihazda çalıştırılmadı.
- Gerçek APK modeli Robolectric altında Android AssetManager'dan okunup SHA-256 ile doğrulandı, açıldı ve kalıcı model klasörüne kuruldu. Bu akış ağ kullanmadı. İkinci hazırlamanın mevcut geçerli modeli koruduğu ve %100 anında modelin hazır olduğu doğrulandı.
- `apksigner verify`: geçerli v2 imzası. 0.1.0 ve 0.1.1 imzalayan sertifikalarının SHA-256 değeri aynı; uygulamanın üzerine güncelleme yapılabilir. Kişisel yayın anahtarı üretilmedi.
- `zipalign -c -P 16 4`: başarılı. ARM64 Vosk/JNA kitaplıkları 16 KB ELF LOAD hizalamasına sahiptir.
- Paketlenen mimariler: `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`.

| Otomatik test | Geçen |
| --- | ---: |
| Türkçe komutlar, söylenen sayılar, SMS/not metni koruma | 25 |
| Telefon intentleri, not sınırı/veri koruma, kilit gizliliği, medya tuşları | 9 |
| Activity iş akışı, çevrimdışı model hazırlama arayüzü, doğru hata/progress gösterimi ve ekran yeniden oluşturma; API 28 ve API 35 | 14 |
| Eski/yeni model düzenleri, eksik i-vector, güvenli ZIP, checksum reddi, gerçek paketli model kurulumu ve tekrar hazırlama | 10 |
| Çağrı sözcüğü, süre sınırı, tek komut, özgün metin ve saat işaretleri | 6 |
| **Toplam** | **64** |

## Gerçek ses modelinin yerel kontrolü

Model resmî HTTPS adresinden indirildi; tüm ZIP CRC'leri doğrulandı. Arşiv SHA-256:

```text
8c8d07cec1bce31add14967c16891c84152cdf76d391e33c08278119b9ea96e5
```

Resmî PyPI'deki SHA-256 doğrulanmış **Vosk 0.3.45 Linux** kitaplığıyla aynı arşiv ayrıca açıldı. `vosk_model_new` ve 16 kHz `vosk_recognizer_new` başarılı oldu; bir saniye üretilmiş PCM16 sessizlik sorunsuz işlendi. Model/i-vector/grafik dosyaları gerçek native yükleyici tarafından okundu. Kullanılan resmî wheel SHA-256'sı ve tekrarlama komutu `scripts/check-turkish-model.py` içinde belirtilmiştir.

Bu host testi Android APK'daki Vosk 0.3.75 veya telefon mikrofonu testi değildir. Türkçe modelin native yükleyici tarafından okunabildiğini gösterir.

Gerçek model sözlüğünde `asistan` var (ID 66895); `COSMO`, `cosmo`, `kosmo`, `kozmo`, `kozmos` yok. Bu nedenle arayüzde sesleniş **Asistan** olarak belirtilir. Uygulama adı COSMO'dur.

## Teslim dosyası

`downloads/COSMO-v0.1.1-debug.apk`: **79.680.650 bayt**. Model arşivi APK içindedir; ilk hazırlama internet kullanmaz.

APK SHA-256:

```text
fa83d1ebf29bd791152baba9745fd8b364a9c7b96cf6e2ac046c142b58ce9f4e
```

## Telefonda kalan kontroller

Bu bulut makinesinde bağlı Android telefon ve `/dev/kvm` yoktur. Gerçek cihaz mikrofonu, TTS hoparlörü ve ekran kapalı çalışma henüz doğrulanmadı.

1. 0.1.1 APK'sını mevcut COSMO'nun üzerine kur; notların korunduğunu kontrol et.
2. İnterneti kapatıp **Türkçe modeli hazırla** düğmesine bas. **Türkçe model hazır** yazısını bekle. Kurulum sırasında en az 80 MB boş alan gerekir.
3. **Dinlemeyi başlat** düğmesine bas; mikrofon ve bildirim izinlerini ver. **Asistan, saat kaç?** de. Sesli yanıtlar için Android'de çevrimdışı Türkçe TTS sesi yüklü olmalıdır.
4. Tek cümle ve ayrı çağrı/komut yollarını dene; çağrı olmadan konuşmanın işlem başlatmadığını doğrula.
5. Ekranı kapatıp 15 dakika sonra tekrar seslen; üreticinin pil yönetimi ve mikrofon gizlilik anahtarını kontrol et.
6. Kilitliyken not/özel işlem engelini, açıkken fener/ses/not/uygulama komutlarını dene. Arama/SMS'de işlemi kullanıcının tamamladığını doğrula.
7. Bildirimden ve sesli komutla durdurma, mikrofon izninin geri alınması ve başka uygulamayla mikrofon kesintisini dene.
8. Kilidi açık bağlı cihazda `./gradlew connectedDebugAndroidTest` çalıştır. Bu 10 testin sonucu ayrıca raporlanmalıdır.
