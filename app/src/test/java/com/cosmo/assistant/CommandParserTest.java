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

    private void assertType(Command.Type expected, String speech) {
        assertEquals(speech, expected, CommandParser.parse(speech).type);
    }
}
