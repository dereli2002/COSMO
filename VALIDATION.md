# FERİ 0.2.0 doğrulama kaydı

Tarih: 19 Ekim 2026. Hedef telefon: kullanıcının bildirdiği **Samsung Galaxy S24 Ultra / Android 16**. Bu sürümün bulut kontrolleri aşağıdadır; yeni APK henüz fiziksel telefonda çalıştırılmadı.

## Derleme ve otomatik kontroller

```bash
source /workspace/.tools/env.sh
./gradlew assembleDebug testDebugUnitTest lintDebug assembleDebugAndroidTest --console=plain
python3 scripts/check-wake-phrase.py --vosk-lib /path/to/trusted/host/libvosk.so
```

- APK derlemesi başarılı. Uygulama etiketi **FERİ**, paket `com.cosmo.assistant`, versionName `0.2.0`, versionCode `3`, minimum API 26, hedef/derleme API 35.
- **123 test geçti; 0 başarısız, 0 hata, 0 atlanan.** JUnit/Robolectric 4.17 kullanıldı. Arayüz ve yeni kilit/onay alıcısı testleri Android 16'nın API 36 görüntüsü üzerinde de çalıştırıldı; bu bir fiziksel Android cihazı testi değildir.
- Android Lint: **0 hata, 2 uyarı**. `ChromeOsAbiSupport`: hedef telefona uygun ARM64 pakette ChromeOS x86_64 desteği yok. `StaticFieldLeak`: yalnız uygulama bağlamını onay süresince tutan statik alan; Activity/Service bağlamı alınmaz ve alıcı iptal/tüketim/süre sonunda temizlenir. İki uyarı da incelendi ve gizlenmedi.
- `assembleDebugAndroidTest` başarılı: 10 mevcut cihaz testi test APK'sına derlendi. `connectedDebugAndroidTest` çalıştırılmadı; bağlı telefon/emülatör yok.
- Gerçek iki model AssetManager üzerinden okunarak checksum doğrulaması ve çevrimdışı kurulumdan geçirildi. Geçerli Türkçe modeli olan eski kuruluma yalnız yeni sesleniş modeli eklenmesi, not/sentinel korunması ve tekrar hazırlamanın modeli bozmaması doğrulandı.
- 140 ms uyanma sinyali ve kısa bekleme ardından 10 saniye Türkçe komut penceresi açılır. Ses 20 ms çerçevelerle işlenir. Uyanma/komut/TTS geçişlerinde önceki nesle ait PCM ve sonuçlar reddedilir; iki native çözümleyiciye tek ses iş parçacığı erişir.
- Doğrudan arama, ekran tıklama/yazma ve güç menüsü onayı tek kullanımlık ve 30 saniyeliktir. Ekran kapanması/kilit açılması olayında onay silinir; alıcı tüketim, iptal ve süre dolumunda kaldırılır. Kayıt başarısızsa onay hazırlanmaz. Kilit→açılma→onay regresyonları API 28 ve 36'da denendi.

| Test alanı | Geçen |
| --- | ---: |
| Türkçe komutlar, söylenen sayılar, özgün metin ve yeni telefon komutları | 37 |
| Telefon intentleri, not/veri koruma, kilit gizliliği, medya ve kendini açma | 10 |
| Arayüz, izin/onay akışı ve modeller; Android API 28 ve 36 | 18 |
| Arama/onay/iptal/süre, kilitlenip açılma, rehber belirsizliği ve sistem ayarları | 26 |
| Tekil düğme, seçili metin alanı, hassas alanlar, güvenlik ekranları ve ekran değişimi | 11 |
| Sesleniş/komut/duraklatma geçişleri, eski ses/sonuçların reddi | 4 |
| İki gerçek paketli model, eski/yeni düzen, SHA/ZIP, yükseltme ve tekrar hazırlama | 11 |
| FERİ sesleniş koşulları, güven eşiği ve tek kullanımlık 10 saniye komut penceresi | 6 |
| **Toplam** | **123** |

## Gerçek yerel ses çözümleme

APK'daki iki resmî arşivin SHA-256 değerleri:

```text
8c8d07cec1bce31add14967c16891c84152cdf76d391e33c08278119b9ea96e5  vosk-model-small-tr-0.3.zip
30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498  vosk-model-small-en-us-0.15.zip
```

Türkçe modelin eski dosya düzeni desteği 0.1.1'deki düzeltmeyle korunur. Türkçe sözlükte `feri` bulunmadığından sesleniş için İngilizce modelin F EH R IY telaffuzlu `ferry` sözcüğü kullanılır. `perry` yalnız karşılaştırma sözcüğüdür. Yalnız tam, tek sözcüklü `ferry` sonucu ve en az 0.65 güven kabul edilir. Ardından normal Türkçe komut modeline geçilir. Asistan/Cosma/Cosmo birer sesleniş olarak kabul edilmez.

Resmî PyPI'den SHA-256 doğrulanmış Vosk **0.3.45 Linux** native kitaplığıyla, uygulamadaki aynı model/gramer/eşik ve 20 ms çerçeveler kullanılarak 24 sabit, SHA-256 doğrulanmış PCM WAV örneği işlendi:

| Sentetik ses grubu | Algılanan / toplam |
| --- | ---: |
| Feri seslenişi | 8 / 9 |
| Sıradan konuşmada yanlış uyanma | 0 / 9 |
| Benzer isimlerde uyanma | 4 / 6 |

Bu örnekler farklı eSpeak Türkçe sesleri ve hızlarıyla üretilmiştir; gerçek konuşmacı doğruluğu değildir. Benzer isimlerde yanlış uyanma sürer. Uyanma yalnız ayrı komut penceresini açar; doğrudan arama ve ekran işlemi onay gerektirir. Uygulamadaki Android Vosk 0.3.75, gerçek mikrofon, yankı/gürültü ve uzun süreli pil davranışı host testiyle doğrulanmış sayılmaz. Tekrarlama ve kaynak bilgisi `scripts/check-wake-phrase.py` ile `app/src/test/resources/wake/README.md` içindedir. Test WAV dosyaları APK'ya dahil değildir.

## Paket, imza ve güncelleme

- `downloads/FERI-v0.2.0-arm64.apk`: **90,788,782 bayt** (ARM64). İki ses modeli APK içindedir; hazırlama için ağ gerekmez.
- `apksigner verify --verbose --print-certs`: geçerli APK Signature Scheme v2 imzası. Önceki yayımlanmış COSMO 0.1.1 APK'sıyla imzalayan sertifikası birebir aynı. Paket kimliği de korunur; mevcut uygulamanın üzerine güncelleme için uygundur.
- Sertifika SHA-256: `db16c6b866ceecc504a4e5434993066d459bfb328bdb6f5470e4dda7695188d6`.
- `zipalign -c -P 16 4`: başarılı. ARM64 `libvosk.so` ve `libjnidispatch.so` ELF LOAD hizalamalarının hepsi 0x4000 (16 KB).
- Bu deneme dağıtımı mevcut debug imzasını korur; üretim yayın anahtarı/mağaza dağıtımı değildir. Kaynak ZIP imzalama anahtarı veya yerel önbellek içermez.

APK SHA-256:

```text
c782ef56851f88c05555f96c118c7c5243b5a8ba4e87bd2095fc84bd11500882
```

İki indirme dosyasının SHA-256 değerleri `downloads/SHA256SUMS.txt` içindedir.

## Telefonda doğrulanacaklar

Kullanıcı önceki **COSMO 0.1.1** için ilk işlevleri, sesli komutları, ekran kapalı dinlemeyi ve durdurmayı başarılı olarak bildirdi. Bu sonuçlar yeni FERİ seslenişi, ikinci model ve yeni ekran/telefon işlevlerinin fiziksel cihaz testi yerine geçmez.

1. Yeni APK'yı mevcut uygulamanın üzerine kur; adın FERİ olduğunu ve notların korunduğunu kontrol et.
2. İnternet kapalıyken **Ses modellerini hazırla** işlemini tamamla; dinlemeyi başlat.
3. **Feri → kısa sinyal → saat kaç** akışını farklı mesafelerde dene. Her komuttan önce tekrar Feri de. Sinyalden sonra komut için 10 saniye vardır.
4. Kullanmak istediğin gezinme, ayrı ekran denetimi, rehber/arama ve sistem ayarı izinlerini kendin aç. Ekranı oku, geri git, parlaklığı yüzde elli yap ve kamera komutlarını dene.
5. Sıradan bir uygulamada ekrana basma/yazma isteğini önce iptal et; sonra yeniden isteyip **Feri → sinyal → onayla** de. Ekranı değiştirme ve kilitleyip açma sonrasında eski onayın işlem başlatmadığını dene.
6. Arama/SMS hedef ve taslaklarını kontrol et; doğrudan aramayı yalnız kendi seçtiğin bir numarayla ayrıca onayla. Wi-Fi/Bluetooth gibi korunan ayarlar ilgili sistem ekranından tamamlanır.
7. Ekran kapalı uzun dinleme, yanlış uyanma, TTS yankısı, mikrofon izni/kesinti ve Samsung pil yönetimini yeniden dene. Bildirimden ve sesle durdurmayı doğrula.
8. İstenirse bağlı ve kilidi açık test cihazında `./gradlew connectedDebugAndroidTest` çalıştır; kilitli durum testi ve donanım izinlerine bağlı koşullar ayrıca raporlanır.
