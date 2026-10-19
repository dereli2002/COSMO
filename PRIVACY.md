# COSMO gizlilik bilgisi

COSMO'da hesap, reklam, analiz yazılımı veya sohbet sunucusu bulunmaz.

- **Mikrofon:** Dinlemeyi uygulamadan başlattığında, görünür bir Android mikrofon bildirimiyle kullanılır. Ses, Vosk tarafından telefonda işlenir. Ses kaydı dosyası oluşturulmaz; ses sunucuya yüklenmez. Dinlemeyi uygulamadan, bildirimden veya komutla durdurabilirsin.
- **Metin:** Komutun anlaşılması için çözümlenen metin kullanılır. Son çağrılan komut, son yanıt ve yerel notların uygulamanın özel verisinde tutulur. Bunlar internete aktarılmaz. Android yedeklemesi kapalıdır. Uygulama verisini Android ayarlarından temizlemek veya uygulamayı kaldırmak bu bilgileri ve indirilen modeli siler.
- **Konuşma:** Yanıtlar için ağ bağlantısı gerektirmediğini bildiren Türkçe Android metin okuma sesi seçilir. Böyle bir ses yoksa yanıt ekranda gösterilir.
- **Model indirmesi:** Yalnızca indirme düğmesini onayladığında, resmî `https://alphacephei.com` adresinden Türkçe model indirilir. Bu HTTPS isteğinde sunucu IP adresi ve indirme zamanı gibi standart bağlantı bilgilerini görebilir. Komut veya ses içermez. Kurulumdan sonra dinleme için internet gerekmez.
- **Fener:** Kamera izni yalnız LED feneri yönetmek içindir. Kamera görüntüsü alınmaz.
- **Gezinme:** İsteğe bağlı erişilebilirlik hizmeti yalnız geri, ana ekran, son uygulamalar, bildirimler, hızlı ayarlar ve kaydırma komutlarını uygular. Ekran içeriği erişimi kapalıdır; ekran metni okunmaz/kaydedilmez. COSMO anahtarını kapatarak bu komutları durdurabilir, Android ayarlarından hizmeti kapatabilirsin.
- **Dış uygulamalar:** Telefon, mesaj, tarayıcı, harita ve saat komutları ilgili Android ekranına aktarılır. Arama/SMS için gönderim kararını kullanıcı verir. Açılan uygulamanın kendi gizlilik kuralları geçerlidir. Web veya harita araması, arama metnini seçilen servise iletir.
- **Kilit:** Notlar ve telefon denetimi kilit açılmasını gerektirir. Saat/tarih/pil gibi temel bilgiler okunabilir. COSMO sesle kimlik doğrulama yapmaz.

COSMO rehber okuma, doğrudan arama yapma, SMS gönderme/okuma, bildirim içeriklerini okuma veya tüm dosyalara erişim izni istemez.
