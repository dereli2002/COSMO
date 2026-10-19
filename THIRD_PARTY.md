# Üçüncü taraf bileşenler

FERİ bağımlılıklarının lisansları kendilerine aittir. Apache-2.0 metni APK içinde `assets/licenses/Apache-2.0.txt` konumundadır. JNA çift lisans bildirimi de korunur; bu dağıtımda Apache-2.0 seçeneği kullanılır.

| Bileşen | Sürüm | Kaynak / lisans |
| --- | --- | --- |
| Vosk Android | 0.3.75 | https://github.com/alphacep/vosk-api — Apache-2.0 |
| JNA | 5.18.1 | https://github.com/java-native-access/jna — Apache-2.0 veya LGPL-2.1+ |
| AndroidX / Kotlin geçişli bileşenleri | Gradle çözümlemesi | Apache-2.0 |
| Türkçe model | vosk-model-small-tr-0.3 | https://alphacephei.com/vosk/models/ — Apache-2.0 |
| FERİ seslenişi için İngilizce model | vosk-model-small-en-us-0.15 | aynı resmî model listesi — Apache-2.0 |

İki model arşivi değiştirilmeden `assets/models` altında dağıtılır. Model README dosyaları korunur. SHA-256 değerleri:

```text
8c8d07cec1bce31add14967c16891c84152cdf76d391e33c08278119b9ea96e5  vosk-model-small-tr-0.3.zip
30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498  vosk-model-small-en-us-0.15.zip
```

JUnit, Robolectric ve AndroidX test bileşenleri ana APK'ya test bağımlılığı olarak eklenmez. `app/src/test/resources/wake` içindeki sentetik sesler yalnız test içindir; APK'ya eklenmez. Üretim kaynağı ve SHA-256 listesi o klasörün README/manifest dosyalarındadır; gerçek kullanıcı sesi içermez.
