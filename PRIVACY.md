# FERİ gizlilik bilgisi

FERİ 0.2.0'da hesap, reklam, analiz servisi veya sohbet sunucusu yoktur.

- **Mikrofon:** Kullanıcı başlattığında görünür bildirimle kullanılır. FERİ seslenişi ve Türkçe komut çözümleme telefonda yapılır. Ses kaydı dosyası oluşturulmaz; ses yüklenmez. Uygulama/bildirim/komutla durdurulabilir.
- **Modeller:** İki resmî Vosk modeli APK içindedir; ilk hazırlama SHA-256 doğrulayıp dosyaları telefonda açar. Modeli içermeyen özel derlemeler açık hazırlama isteğinde yalnız `https://alphacephei.com` üzerinden doğrulanmış modelleri indirebilir; ses/komut gönderilmez.
- **Konuşma:** Ağ bağlantısı gerektirmediğini bildiren Türkçe Android metin okuma sesi seçilir. Uygun ses bulunmazsa yanıt ekranda gösterilir.
- **Rehber:** İsteğe bağlı READ_CONTACTS izniyle, açık kişi komutunda tam adın yerel numarasını bulmak için okunur. Rehber kopyalanmaz, kaydedilmez veya paylaşılmaz. Belirsiz eşleşmede kendiliğinden kişi seçilmez.
- **Telefon:** CALL_PHONE izni yalnız açık doğrudan numara komutu ve ayrı sesli onay sonrasında aramayı Android'e iletir. SMS okuma veya arka planda SMS gönderme izni istenmez.
- **Ekran:** Gezinme ile ekran okuma ayrı onaylardır. Ekran okuma/denetimi ayrıca açıldığında yalnız komut üzerine etkin ekranın görünen, sıradan öğeleri incelenir. Sürekli ekran kaydı veya olay toplama yapılmaz. Okunan ekran metni son yanıt tercihlerine yazılmaz; yalnız o yanıt için bellekte tutulur. Şifre/PIN/OTP ve kart güvenlik alanları, Android izin ve güvenlik ekranları denetlenmez.
- **Tıklama/yazma:** Açık hedef ve ayrı sesli onay gerekir. Onay anında ekranın aynı olması doğrulanır. Ekran kimliği kısa süreli hash olarak yalnız bellekte tutulur. Onay 30 saniyede veya başka komut/izin/ekran değişiminde geçersizleşir.
- **Kamera:** Kamera izni fener içindir. Kamera komutları kamera uygulamasını açar; uygulama arka planda fotoğraf/video çekmez. Ekran görüntüsü komutu Android'in kendi ekran yakalama eylemini ister; güvenli ekran korumaları geçerlidir.
- **Veri:** Notlar, son açık komut/yanıt ve izin tercihleri uygulamanın özel alanında saklanır. Android bulut yedeklemesi ve cihaz aktarımı kapalıdır. Uygulama verisini temizlemek veya kaldırmak bu bilgileri/modelleri siler. Üzerine imzalı güncelleme verileri korur.
- **Kilit:** Kilitliyken saat/tarih/pil/yardım ve durdurma gibi sınırlı işlemler çalışır; rehber, ekran içeriği ve denetim kilit açılmasını ister. Ses, konuşmacı kimliğini doğrulamaz.
- **Dış uygulamalar:** Harita/web arama metni, arama/mesaj/takvim taslağı veya kamera isteği seçilen Android uygulamasına aktarılır. O uygulamanın gizlilik kuralları geçerlidir.

Erişilebilirlik ve ekran denetimini uygulamadaki anahtarlardan, Android izinlerini uygulama ayarlarından kapatabilirsin.
