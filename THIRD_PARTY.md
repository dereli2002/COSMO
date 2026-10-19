# Üçüncü taraf bileşenler

Uygulamadaki bağımlılıklar kendi lisanslarına tabidir. Apache-2.0 lisans metni APK içinde `assets/licenses/Apache-2.0.txt` konumunda da bulunur.

| Bileşen | Sürüm | Lisans / kaynak |
| --- | --- | --- |
| Vosk Android | 0.3.75 | Apache-2.0; https://github.com/alphacep/vosk-api |
| Java Native Access | 5.18.1 | Apache-2.0 veya LGPL-2.1-or-later; https://github.com/java-native-access/jna |
| AndroidX Core ve geçişli AndroidX bileşenleri | Gradle ile sabitlenen sürümler | Apache-2.0; https://android.googlesource.com/platform/frameworks/support/ |
| Kotlin standard library (AndroidX geçişli bağımlılığı) | Gradle çözümlemesi | Apache-2.0; https://github.com/JetBrains/kotlin |

JNA'nın özgün çift lisans açıklaması `assets/licenses/JNA-LICENSE.txt` içindedir. COSMO dağıtımında Apache-2.0 seçeneği kullanılır. Kütüphane kaynakları değiştirilmemiştir.

`vosk-model-small-tr-0.3` APK içine eklenmemiştir. Kullanıcı isterse https://alphacephei.com/vosk/models/ sayfasındaki resmî Türkçe modeli ayrıca indirir. Model listesinde lisansı Apache-2.0 olarak belirtilir; modelin kendi lisans/README dosyaları indirilen klasörde korunur.

JUnit, Robolectric ve AndroidX test bileşenleri yalnız geliştirme/test için kullanılır; ana APK'ya test bağımlılığı olarak eklenmez.
