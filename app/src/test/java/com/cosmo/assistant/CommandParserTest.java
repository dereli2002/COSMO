package com.cosmo.assistant;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class CommandParserTest {

    @Test public void normalizesTurkishCaseDiacriticsAndWhitespace() {
        assertEquals("igusoc ig", CommandParser.normalize("  İĞÜŞÖÇ\t ıĞ  "));
        assertEquals("", CommandParser.normalize(null));
        assertEquals(Command.Type.TIME, CommandParser.parse("  SAAT\tKAÇ? ").type);
        assertEquals(Command.Type.FLASHLIGHT_ON, CommandParser.parse("IŞIĞI AÇ").type);
    }

    @Test public void handlesMissingAndUnknownCommands() {
        assertEquals(Command.Type.UNKNOWN, CommandParser.parse(null).type);
        assertEquals(Command.Type.UNKNOWN, CommandParser.parse("  ").type);
        assertEquals(Command.Type.UNKNOWN, CommandParser.parse("bütün dosyaları sil").type);
        assertEquals(Command.Type.UNKNOWN, CommandParser.parse("not al").type);
    }

    @Test public void parsesInformationalAndDeviceCommands() {
        assertType(Command.Type.DATE, "Bugün ayın kaçı?");
        assertType(Command.Type.BATTERY, "Şarjım ne kadar");
        assertType(Command.Type.FLASHLIGHT_OFF, "el fenerini kapat");
        assertType(Command.Type.VOLUME_UP, "sesi artır");
        assertType(Command.Type.VOLUME_DOWN, "sesi kıs");
        assertType(Command.Type.VOLUME_MUTE, "sessize al");
    }

    @Test public void routesRestrictedTogglesToSettings() {
        assertType(Command.Type.WIFI_SETTINGS, "Wi-Fi'yi aç");
        assertType(Command.Type.WIFI_SETTINGS, "wi fi kapat");
        assertType(Command.Type.BLUETOOTH_SETTINGS, "Bluetooth'u kapat");
        assertType(Command.Type.INTERNET_SETTINGS, "mobil veriyi aç");
        assertType(Command.Type.LOCATION_SETTINGS, "konumu aç");
        assertType(Command.Type.LOCATION_SETTINGS, "GPS'i kapat");
    }

    @Test public void preservesOriginalNoteIncludingPunctuationAndCase() {
        Command command = CommandParser.parse("NOT AL: Yarın İZMİR’e gideceğim!  Bilet al.");
        assertEquals(Command.Type.NOTE_SAVE, command.type);
        assertEquals("Yarın İZMİR’e gideceğim!  Bilet al.", command.argument);
        assertType(Command.Type.NOTE_LIST, "notlarımı oku");
    }

    @Test public void treatsWordsInsideNotesAsData() {
        Command command = CommandParser.parse("not al 112 numarasını ara ve mesaj gönder");
        assertEquals(Command.Type.NOTE_SAVE, command.type);
        assertEquals("112 numarasını ara ve mesaj gönder", command.argument);
    }

    @Test public void extractsOriginalApplicationLabel() {
        Command command = CommandParser.parse("WhatsApp uygulamasını aç");
        assertEquals(Command.Type.OPEN_APP, command.type);
        assertEquals("WhatsApp", command.argument);
        assertEquals("Google Haritalar", CommandParser.parse("uygulama aç Google Haritalar").argument);
    }

    @Test public void normalizesPhoneNumberWithoutCallingIt() {
        Command command = CommandParser.parse("+90 (555) 123-45-67 numarasını ara");
        assertEquals(Command.Type.DIAL, command.type);
        assertEquals("+905551234567", command.argument);
        assertEquals("112", CommandParser.parse("ara 112").argument);
    }

    @Test public void rejectsMalformedPhoneNumbers() {
        assertType(Command.Type.UNKNOWN, "ara ++905551234567");
        assertType(Command.Type.UNKNOWN, "ara 0555abc123");
        assertType(Command.Type.UNKNOWN, "ara 12");
        assertType(Command.Type.UNKNOWN, "ara 1234567890123456");
        assertType(Command.Type.UNKNOWN, "ara 0555 (123 45 67");
    }

    @Test public void preparesSmsAndPreservesBody() {
        Command command = CommandParser.parse("mesaj gönder +905551234567 İyi akşamlar!  2’de görüşürüz.");
        assertEquals(Command.Type.COMPOSE_SMS, command.type);
        assertEquals("+905551234567", command.argument);
        assertEquals("İyi akşamlar!  2’de görüşürüz.", command.secondary);
    }

    @Test public void supportsGroupedSmsNumbersWithExplicitDelimiter() {
        Command command = CommandParser.parse("+90 (555) 123 45 67 numarasına mesaj yaz: Merhaba Dünya!");
        assertEquals(Command.Type.COMPOSE_SMS, command.type);
        assertEquals("+905551234567", command.argument);
        assertEquals("Merhaba Dünya!", command.secondary);
    }

    @Test public void doesNotConsumeNumbersAtStartOfSmsBody() {
        Command command = CommandParser.parse("mesaj yaz 05551234567 2026 yılın kutlu olsun");
        assertEquals(Command.Type.COMPOSE_SMS, command.type);
        assertEquals("05551234567", command.argument);
        assertEquals("2026 yılın kutlu olsun", command.secondary);
        assertType(Command.Type.UNKNOWN, "mesaj yaz 12 Merhaba");
        assertType(Command.Type.UNKNOWN, "mesaj yaz 05551234567");
    }

    @Test public void distinguishesWebAndMapSearchFromDialing() {
        Command web = CommandParser.parse("Google'da arama motorları ara");
        assertEquals(Command.Type.WEB_SEARCH, web.type);
        assertEquals("arama motorları", web.argument);
        Command map = CommandParser.parse("İstanbul için yol tarifi");
        assertEquals(Command.Type.MAP_SEARCH, map.type);
        assertEquals("İstanbul", map.argument);
        assertType(Command.Type.UNKNOWN, "arama yap");
        assertType(Command.Type.UNKNOWN, "araştırma yap");
    }

    @Test public void parsesAndValidatesTwentyFourHourAlarms() {
        assertEquals("07:30", CommandParser.parse("saat 7:30 için alarm kur").argument);
        assertEquals("23:59", CommandParser.parse("alarm kur 23:59").argument);
        assertEquals("08:00", CommandParser.parse("saat 8 için alarm kur").argument);
        assertType(Command.Type.SET_ALARM, "07:30'a alarm kur");
        assertType(Command.Type.UNKNOWN, "saat 24:00 için alarm kur");
        assertType(Command.Type.UNKNOWN, "alarm kur 07:60");
    }

    @Test public void parsesTimerUnitsAndCompositeDurations() {
        assertEquals("300", CommandParser.parse("5 dakika zamanlayıcı kur").argument);
        assertEquals("3723", CommandParser.parse("1 saat 2 dakika 3 saniye sayaç başlat").argument);
        assertEquals("30", CommandParser.parse("zamanlayıcı kur 30 saniye").argument);
        assertEquals("600", CommandParser.parse("10 dakikalık zamanlayıcı kur").argument);
        assertType(Command.Type.SET_TIMER, "24 saat zamanlayıcı kur");
    }

    @Test public void rejectsInvalidOrAmbiguousTimerRequests() {
        assertType(Command.Type.UNKNOWN, "0 dakika zamanlayıcı kur");
        assertType(Command.Type.UNKNOWN, "25 saat zamanlayıcı kur");
        assertType(Command.Type.UNKNOWN, "-5 dakika zamanlayıcı kur");
        assertType(Command.Type.UNKNOWN, "999999999999999 saat zamanlayıcı kur");
        assertType(Command.Type.UNKNOWN, "5 dakika 2 saat zamanlayıcı kur");
        assertType(Command.Type.UNKNOWN, "5 dakika 2 dakika zamanlayıcı kur");
    }

    @Test public void parsesOptionalAccessibilityAndMediaCommands() {
        assertType(Command.Type.ACCESSIBILITY_BACK, "geri dön");
        assertType(Command.Type.ACCESSIBILITY_HOME, "ana ekrana git");
        assertType(Command.Type.ACCESSIBILITY_RECENTS, "son uygulamaları aç");
        assertType(Command.Type.ACCESSIBILITY_NOTIFICATIONS, "bildirimleri aç");
        assertType(Command.Type.ACCESSIBILITY_QUICK_SETTINGS, "hızlı ayarları aç");
        assertType(Command.Type.ACCESSIBILITY_SCROLL_UP, "yukarı kaydır");
        assertType(Command.Type.ACCESSIBILITY_SCROLL_DOWN, "aşağı kaydır");
        assertType(Command.Type.MEDIA_PAUSE, "müziği duraklat");
        assertType(Command.Type.MEDIA_NEXT, "sonraki şarkı");
    }

    @Test public void explicitControlCommandsRequireWholeUtterance() {
        assertType(Command.Type.STOP_LISTENING, "dinlemeyi durdur");
        assertType(Command.Type.HELP, "neler yapabilirsin");
        assertType(Command.Type.CONFIRM, "onaylıyorum");
        assertType(Command.Type.CANCEL, "vazgeç");
        assertType(Command.Type.UNKNOWN, "evet");
        assertType(Command.Type.NOTE_SAVE, "not al dinlemeyi durdur");
    }

    @Test public void parsesSpokenTurkishTimerNumbers() {
        assertEquals("300", CommandParser.parse("beş dakika zamanlayıcı kur").argument);
        assertEquals("10", CommandParser.parse("on saniye zamanlayıcı kur").argument);
        assertEquals("4500", CommandParser.parse("bir saat on beş dakika zamanlayıcı kur").argument);
        assertEquals("125", CommandParser.parse("yüz yirmi beş saniye sayaç başlat").argument);
        assertEquals("1000", CommandParser.parse("bin saniye zamanlayıcı kur").argument);
        assertEquals("86400", CommandParser.parse("yirmi dört saat zamanlayıcı kur").argument);
    }

    @Test public void rejectsMalformedSpokenTimerNumbers() {
        assertType(Command.Type.UNKNOWN, "beş beş dakika zamanlayıcı kur");
        assertType(Command.Type.UNKNOWN, "on sıfır saniye zamanlayıcı kur");
        assertType(Command.Type.UNKNOWN, "sıfır saniye zamanlayıcı kur");
        assertType(Command.Type.UNKNOWN, "yirmi beş saat zamanlayıcı kur");
        assertType(Command.Type.UNKNOWN, "bir şey dakika zamanlayıcı kur");
    }

    @Test public void parsesSpokenTurkishAlarmsAndHalfHours() {
        assertEquals("07:30", CommandParser.parse("saat yedi buçuk alarm kur").argument);
        assertEquals("07:30", CommandParser.parse("saat yedi otuz için alarm kur").argument);
        assertEquals("12:15", CommandParser.parse("alarm kur saat on iki on beş").argument);
        assertEquals("23:59", CommandParser.parse("saat yirmi üç elli dokuz alarm kur").argument);
        assertEquals("17:00", CommandParser.parse("saat on yedi alarm kur").argument);
        assertEquals("07:30", CommandParser.parse("saat 7 30 alarm kur").argument);
    }

    @Test public void rejectsSpokenOutOfRangeAlarmsWithoutReinterpretingThem() {
        assertType(Command.Type.UNKNOWN, "saat yirmi dört alarm kur");
        assertType(Command.Type.UNKNOWN, "saat yedi altmış alarm kur");
        assertType(Command.Type.UNKNOWN, "saat yirmi dört buçuk alarm kur");
        assertType(Command.Type.UNKNOWN, "saat belirsiz alarm kur");
    }

    @Test public void parsesPhoneDigitsReadIndividuallyAndRejectsAmbiguousGroups() {
        Command command = CommandParser.parse("sıfır beş beş beş bir iki üç dört beş altı yedi numarasını ara");
        assertEquals(Command.Type.DIAL, command.type);
        assertEquals("05551234567", command.argument);
        assertEquals("112", CommandParser.parse("ara bir bir iki").argument);
        assertEquals("+905551234567", CommandParser.parse("ara artı dokuz sıfır beş beş beş bir iki üç dört beş altı yedi").argument);
        assertType(Command.Type.UNKNOWN, "sıfır beş yüz elli beş numarasını ara");
        assertType(Command.Type.UNKNOWN, "ara bir iki");
        assertType(Command.Type.WEB_SEARCH, "internette sayı saymayı ara");
    }

    @Test public void leavesSpokenNumbersInUserPayloadsUntouchedAndAvoidsDeviceAppCollision() {
        assertEquals("On beş dakika sonra ara", CommandParser.parse("not al On beş dakika sonra ara").argument);
        assertEquals("yedi buçukta görüşürüz", CommandParser.parse("mesaj yaz 05551234567 yedi buçukta görüşürüz").secondary);
        assertType(Command.Type.FLASHLIGHT_ON, "feneri aç");
        assertType(Command.Type.OPEN_APP, "Fener uygulamasını aç");
        assertEquals("Fener", CommandParser.parse("Fener uygulamasını aç").argument);
    }

    @Test public void distinguishesMediaPauseFromPlayAndExplicitToggle() {
        assertType(Command.Type.MEDIA_PAUSE, "müziği durdur");
        assertType(Command.Type.MEDIA_PLAY, "müziği başlat");
        assertType(Command.Type.MEDIA_PLAY_PAUSE, "oynat duraklat");
    }

    @Test public void parsesBrightnessPercentagesAndSpokenValuesWithoutClamping() {
        assertCommand(Command.Type.BRIGHTNESS_SET, "50", "parlaklığı yüzde elli yap");
        assertCommand(Command.Type.BRIGHTNESS_SET, "0", "ekran parlaklığını yüzde sıfır ayarla");
        assertCommand(Command.Type.BRIGHTNESS_SET, "100", "parlaklığı %100 yap");
        assertType(Command.Type.BRIGHTNESS_UP, "ekran parlaklığını artır");
        assertType(Command.Type.BRIGHTNESS_DOWN, "parlaklığı kıs");
        assertType(Command.Type.UNKNOWN, "parlaklığı yüzde yüz bir yap");
        assertType(Command.Type.UNKNOWN, "parlaklığı yüzde -1 yap");
        assertType(Command.Type.UNKNOWN, "parlaklığı yüzde elli elli yap");
        assertType(Command.Type.UNKNOWN, "parlaklığı yüzde 50.5 yap");
        assertType(Command.Type.UNKNOWN, "parlaklığı yüzde 999999999999 yap");
    }

    @Test public void parsesRotationScreenLockAndScreenshotCommands() {
        assertType(Command.Type.ROTATION_AUTO, "otomatik döndürmeyi aç");
        assertType(Command.Type.ROTATION_PORTRAIT, "ekranı dikey kilitle");
        assertType(Command.Type.ROTATION_PORTRAIT, "otomatik döndürmeyi kapat");
        assertType(Command.Type.SCREEN_LOCK, "telefonu kilitle");
        assertType(Command.Type.SCREENSHOT, "ekran görüntüsü al");
        assertType(Command.Type.SCREENSHOT, "ekranın resmini çek");
    }

    @Test public void routesCameraAndCalendarToExplicitUserInterfaces() {
        assertType(Command.Type.CAMERA_PHOTO, "fotoğraf çek");
        assertType(Command.Type.CAMERA_VIDEO, "video kaydet");
        assertCommand(Command.Type.CALENDAR_INSERT, "Yarın İZMİR’de toplantı!",
                "takvime ekle: Yarın İZMİR’de toplantı!");
        assertCommand(Command.Type.CALENDAR_INSERT, "Doktor randevusu",
                "takvim etkinliği oluştur Doktor randevusu");
        assertType(Command.Type.UNKNOWN, "takvime ekle");
    }

    @Test public void parsesVisibleScreenLabelsAndPreservesTypedText() {
        assertCommand(Command.Type.SCREEN_CLICK, "Kaydet", "ekranda Kaydet düğmesine bas");
        assertCommand(Command.Type.SCREEN_CLICK, "Ayarlar", "ekrandaki Ayarlar öğesine tıkla");
        assertCommand(Command.Type.SCREEN_CLICK, "Gönder", "ekranda Gönder tıkla");
        assertCommand(Command.Type.SCREEN_TYPE, "İyi akşamlar!  Yarın 2’de görüşürüz.",
                "ekrana yaz: İyi akşamlar!  Yarın 2’de görüşürüz.");
        assertCommand(Command.Type.SCREEN_TYPE, "112 numarasını ara", "metin yaz 112 numarasını ara");
        assertType(Command.Type.SCREEN_READ, "ekranda ne var?");
        assertType(Command.Type.UNKNOWN, "ekrana yaz");
        assertType(Command.Type.UNKNOWN, "izin ver");
        assertType(Command.Type.UNKNOWN, "tüm izinleri onayla");
    }

    @Test public void resolvesContactsOnlyThroughExplicitContactGrammar() {
        assertCommand(Command.Type.DIAL_CONTACT, "Ahmet", "Ahmet adlı kişiyi ara");
        assertCommand(Command.Type.DIAL_CONTACT, "AYŞE Yılmaz", "AYŞE Yılmaz kişisini ara");
        assertCommand(Command.Type.DIAL_CONTACT, "annem", "annemi ara");
        assertCommand(Command.Type.DIAL_CONTACT, "esim", "eşimi ara");
        assertType(Command.Type.UNKNOWN, "Ahmet ara");
        assertType(Command.Type.UNKNOWN, "Ankara ara");
        assertType(Command.Type.UNKNOWN, "bir şey ara");
    }

    @Test public void preservesContactSmsPayloadAndDoesNotInterpretEmbeddedActions() {
        Command command = CommandParser.parse("İpek adlı kişiye mesaj yaz: Merhaba!  112 numarasını ara.");
        assertEquals(Command.Type.COMPOSE_SMS_CONTACT, command.type);
        assertEquals("İpek", command.argument);
        assertEquals("Merhaba!  112 numarasını ara.", command.secondary);
        assertType(Command.Type.UNKNOWN, "Ahmet adlı kişiye mesaj yaz");
        assertType(Command.Type.UNKNOWN, "Ahmet mesaj gönder");
    }

    @Test public void opensContactDetailsByExplicitLabel() {
        assertCommand(Command.Type.CONTACT_SHOW, "Özge", "rehberde Özge kişisini göster");
        assertCommand(Command.Type.CONTACT_SHOW, "Çağrı", "Çağrı adlı kişiyi göster");
        assertCommand(Command.Type.CONTACT_SHOW, "Ali Veli", "kişiyi göster Ali Veli");
    }

    @Test public void keepsQueriesAboutContactsAsSearches() {
        assertCommand(Command.Type.WEB_SEARCH, "Ahmet adlı kişiyi", "internette Ahmet adlı kişiyi ara");
        assertCommand(Command.Type.MAP_SEARCH, "Ahmet kişisini ara", "yol tarifi Ahmet kişisini ara");
        assertCommand(Command.Type.MAP_SEARCH, "Ahmet kişisini", "haritada Ahmet kişisini ara");
    }

    @Test public void directCallIntentRequiresExplicitWordingAndValidNumber() {
        assertCommand(Command.Type.CALL, "05551234567", "0555 123 45 67 numarasını telefonla ara");
        assertCommand(Command.Type.CALL, "112", "bir bir iki numarasını doğrudan ara");
        assertCommand(Command.Type.DIAL, "112", "112 numarasını ara");
        assertType(Command.Type.UNKNOWN, "12 numarasını telefonla ara");
        assertType(Command.Type.UNKNOWN, "0555abc numarasını telefonla ara");
        assertType(Command.Type.UNKNOWN, "Ahmet numarasını telefonla ara");
    }

    @Test public void routesAndroidRestrictedFeaturesToTheirSettingsPages() {
        assertType(Command.Type.APP_SETTINGS, "Cosma ayarlarını aç");
        assertType(Command.Type.DISPLAY_SETTINGS, "ekran ayarlarını aç");
        assertType(Command.Type.SOUND_SETTINGS, "ses ayarlarını aç");
        assertType(Command.Type.ACCESSIBILITY_SETTINGS, "erişilebilirlik ayarlarını aç");
        assertType(Command.Type.BATTERY_SETTINGS, "pil ayarlarını aç");
        assertType(Command.Type.AIRPLANE_SETTINGS, "uçak modunu aç");
        assertType(Command.Type.HOTSPOT_SETTINGS, "erişim noktasını kapat");
        assertType(Command.Type.NFC_SETTINGS, "NFC'yi aç");
        assertType(Command.Type.PRIVACY_SETTINGS, "gizlilik ayarlarını aç");
        assertType(Command.Type.STORAGE_SETTINGS, "depolama ayarlarını aç");
    }

    @Test public void addsPreviousTrackAndExplicitPowerRequests() {
        assertType(Command.Type.MEDIA_PREVIOUS, "önceki şarkı");
        assertType(Command.Type.POWER_OFF, "telefonu kapat");
        assertType(Command.Type.REBOOT, "telefonu yeniden başlat");
        assertType(Command.Type.STOP_LISTENING, "Cosma’yı durdur");
    }

    @Test public void localFriendlyRepliesAndStatusUseWholeCommands() {
        assertCommand(Command.Type.GREETING, "hello", "merhaba");
        assertCommand(Command.Type.GREETING, "how_are_you", "nasılsın?");
        assertCommand(Command.Type.GREETING, "thanks", "teşekkür ederim");
        assertCommand(Command.Type.GREETING, "name", "adın ne");
        assertType(Command.Type.SELF_STATUS, "dinliyor musun?");
        assertCommand(Command.Type.NOTE_SAVE, "merhaba nasılsın", "not al merhaba nasılsın");
        assertType(Command.Type.UNKNOWN, "merhaba nasılsın bugün hava nasıl");
    }

    private void assertCommand(Command.Type expected, String argument, String speech) {
        Command command = CommandParser.parse(speech);
        assertEquals(speech, expected, command.type);
        assertEquals(speech, argument, command.argument);
    }

    private void assertType(Command.Type expected, String speech) {
        assertEquals(speech, expected, CommandParser.parse(speech).type);
    }
}
