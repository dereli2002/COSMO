package com.cosmo.assistant;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic Turkish commands, parsed after the wake word has been removed. */
public final class CommandParser {
    private static final Locale TURKISH = Locale.forLanguageTag("tr-TR");
    private static final String PHONE = "(\\+?[0-9][0-9 ()-]*[0-9])";
    private static final String DIGIT_WORDS = "sifir|bir|iki|uc|dort|bes|alti|yedi|sekiz|dokuz";
    private static final String[] ONES = {"sifir", "bir", "iki", "uc", "dort", "bes", "alti", "yedi", "sekiz", "dokuz"};
    private static final String[] TENS = {"", "on", "yirmi", "otuz", "kirk", "elli", "altmis", "yetmis", "seksen", "doksan"};

    public static Command parse(String spoken) {
        if (spoken == null || spoken.trim().isEmpty()) return command(Command.Type.UNKNOWN);
        Text text = new Text(spoken);
        String words = text.normalized.replaceAll("[.!?]+$", "").trim();

        if (matches(words, "iptal", "vazgec", "iptal et", "hayir")) return command(Command.Type.CANCEL);
        if (matches(words, "onayla", "onayliyorum", "evet onayla")) return command(Command.Type.CONFIRM);
        if (matches(words, "dinlemeyi durdur", "dinlemeyi kapat", "cosmoyu durdur", "cosmo'yu durdur", "asistani kapat", "kapan")) return command(Command.Type.STOP_LISTENING);
        if (matches(words, "yardim", "yardim et", "neler yapabilirsin", "komutlar", "komutlari soyle")) return command(Command.Type.HELP);
        if (matches(words, "saat", "saat kac", "su an saat kac", "saati soyle", "saat kactir")) return command(Command.Type.TIME);
        if (matches(words, "tarih", "tarih ne", "bugunun tarihi", "bugunun tarihi ne", "bugun tarih ne", "tarihi soyle", "bugun ayin kaci", "ayin kaci", "bugun gunlerden ne", "hangi gundeyiz")) return command(Command.Type.DATE);
        if (matches(words, "pil", "pil durumu", "pil kac", "sarj", "sarj kac", "sarjim ne kadar", "sarj ne kadar", "sarj durumu", "batarya durumu")) return command(Command.Type.BATTERY);
        if (words.matches("(?:el )?(?:feneri|fenerini|fener|isigi|isik) ac")) return command(Command.Type.FLASHLIGHT_ON);
        if (words.matches("(?:el )?(?:feneri|fenerini|fener|isigi|isik) (?:kapat|sondur)")) return command(Command.Type.FLASHLIGHT_OFF);
        if (matches(words, "sesi artir", "sesi yukselt", "ses ac", "sesi ac")) return command(Command.Type.VOLUME_UP);
        if (matches(words, "sesi azalt", "sesi kis", "ses kis")) return command(Command.Type.VOLUME_DOWN);
        if (matches(words, "sessize al", "sesi kapat", "sesi sustur")) return command(Command.Type.VOLUME_MUTE);
        if (words.matches("(?:wi-?fi|wi fi|kablosuz ag)(?:'?(?:yi|i))? (?:ayarlari|ayarlarini ac|ac|kapat)") || matches(words, "wifi", "wi-fi", "wi fi")) return command(Command.Type.WIFI_SETTINGS);
        if (words.matches("bluetooth(?:u|'u)? (?:ayarlari|ayarlarini ac|ac|kapat)") || words.equals("bluetooth")) return command(Command.Type.BLUETOOTH_SETTINGS);
        if (words.matches("(?:internet|mobil veri)(?:i|yi)? (?:ayarlari|ayarlarini ac|ac|kapat)") || matches(words, "internet ayarlari", "ag ayarlarini ac")) return command(Command.Type.INTERNET_SETTINGS);
        if (words.matches("(?:konum|gps)(?:u|'i)? (?:ayarlari|ayarlarini ac|ac|kapat)") || words.equals("konum")) return command(Command.Type.LOCATION_SETTINGS);
        if (matches(words, "notlari oku", "notlarimi oku", "notlari listele", "notlarim", "notlari goster")) return command(Command.Type.NOTE_LIST);
        if (matches(words, "muzigi baslat", "muzigi oynat", "medyayi oynat")) return command(Command.Type.MEDIA_PLAY);
        if (matches(words, "muzigi durdur", "muzigi duraklat", "medyayi duraklat", "medyayi durdur")) return command(Command.Type.MEDIA_PAUSE);
        if (matches(words, "oynat duraklat", "oynat veya duraklat")) return command(Command.Type.MEDIA_PLAY_PAUSE);
        if (matches(words, "sonraki sarki", "siradaki sarki", "sonraki parca", "sonraki medya")) return command(Command.Type.MEDIA_NEXT);
        if (matches(words, "geri", "geri git", "geri don")) return command(Command.Type.ACCESSIBILITY_BACK);
        if (matches(words, "ana ekran", "ana ekrana git", "ana ekrani ac")) return command(Command.Type.ACCESSIBILITY_HOME);
        if (matches(words, "son uygulamalar", "son uygulamalari ac", "uygulama degistir")) return command(Command.Type.ACCESSIBILITY_RECENTS);
        if (matches(words, "bildirimler", "bildirimleri ac", "bildirim panelini ac")) return command(Command.Type.ACCESSIBILITY_NOTIFICATIONS);
        if (matches(words, "hizli ayarlar", "hizli ayarlari ac")) return command(Command.Type.ACCESSIBILITY_QUICK_SETTINGS);
        if (matches(words, "yukari kaydir", "sayfayi yukari kaydir")) return command(Command.Type.ACCESSIBILITY_SCROLL_UP);
        if (matches(words, "asagi kaydir", "sayfayi asagi kaydir")) return command(Command.Type.ACCESSIBILITY_SCROLL_DOWN);

        // Capture original payloads so Turkish casing, punctuation and SMS/note text survive.
        Command parsed = payload(text, "^(?:not al|not kaydet|not yaz|not et)\\s*:?\\s+(.+)$", Command.Type.NOTE_SAVE);
        if (parsed != null) return parsed;
        parsed = parseSms(text);
        if (parsed != null) return parsed;
        parsed = parseDial(text);
        if (parsed != null) return parsed;
        parsed = parseAlarm(words);
        if (parsed != null) return parsed;
        parsed = parseTimer(words);
        if (parsed != null) return parsed;
        parsed = payload(text, "^(?:haritada ara|haritada bul|haritalarda ara|yol tarifi|yol tarifi al)\\s+(.+)$", Command.Type.MAP_SEARCH);
        if (parsed != null) return parsed;
        parsed = payload(text, "^(.+?)\\s+(?:haritada ara|haritada bul|icin yol tarifi)$", Command.Type.MAP_SEARCH);
        if (parsed != null) return parsed;
        parsed = payload(text, "^(?:internette ara|webde ara|google'?da ara|web aramasi yap)\\s+(.+)$", Command.Type.WEB_SEARCH);
        if (parsed != null) return parsed;
        parsed = payload(text, "^(?:internette|webde|google'?da)\\s+(.+?)\\s+ara$", Command.Type.WEB_SEARCH);
        if (parsed != null) return parsed;
        parsed = payload(text, "^(.+?)\\s+(?:internette|webde|google'?da)\\s+ara$", Command.Type.WEB_SEARCH);
        if (parsed != null) return parsed;
        parsed = payload(text, "^(?:uygulama ac|uygulamayi ac|ac)\\s+(.+)$", Command.Type.OPEN_APP);
        if (parsed != null) return parsed;
        parsed = payload(text, "^(.+?)(?:\\s+uygulamasini)?\\s+ac$", Command.Type.OPEN_APP);
        if (parsed != null) return parsed;
        return command(Command.Type.UNKNOWN);
    }

    /** Locale-independent comparison form for Turkish recognition and app labels. */
    public static String normalize(String value) {
        return value == null ? "" : new Text(value).normalized;
    }

    private static Command parseDial(Text text) {
        String words = text.normalized.replaceAll("[.!?]+$", "").trim();
        // Separate anchored patterns avoid treating words containing 'ara' as calls.
        Matcher match = Pattern.compile("^(?:ara|telefon et|numarayi ara|numarayi cevir)\\s+" + PHONE + "$").matcher(words);
        if (!match.matches()) {
            match = Pattern.compile("^" + PHONE + "(?:\\s+numarasini)?\\s+(?:ara|telefon et)$").matcher(words);
            if (!match.matches()) {
                String spoken = "((?:arti )?(?:" + DIGIT_WORDS + ")(?: (?:" + DIGIT_WORDS + ")){2,14})";
                match = Pattern.compile("^(?:ara|telefon et|numarayi ara|numarayi cevir) " + spoken + "$").matcher(words);
                if (!match.matches()) {
                    match = Pattern.compile("^" + spoken + "(?: numarasini)? (?:ara|telefon et)$").matcher(words);
                    if (!match.matches()) return null;
                }
                StringBuilder digits = new StringBuilder();
                for (String word : match.group(1).split(" ")) {
                    if (word.equals("arti")) digits.append('+');
                    else for (int digit = 0; digit < ONES.length; digit++) if (ONES[digit].equals(word)) digits.append(digit);
                }
                return new Command(Command.Type.DIAL, digits.toString());
            }
        }
        String number = phone(match.group(1));
        return number == null ? command(Command.Type.UNKNOWN) : new Command(Command.Type.DIAL, number);
    }

    private static Command parseSms(Text text) {
        Matcher match = Pattern.compile("^" + PHONE + "\\s+numarasina\\s+(?:sms|mesaj)\\s+(?:yaz|hazirla|gonder)\\s*:?\\s+(.+)$").matcher(text.normalized);
        if (!match.matches()) {
            match = Pattern.compile("^(?:sms|mesaj)\\s+(?:yaz|hazirla|gonder)\\s+" + PHONE + "\\s+numarasina\\s*:?\\s+(.+)$").matcher(text.normalized);
            if (!match.matches()) {
                // A contiguous number has an unambiguous end even when the body starts with digits.
                // For grouped numbers require the explicit 'numarasina' delimiter above.
                match = Pattern.compile("^(?:sms|mesaj)\\s+(?:yaz|hazirla|gonder)\\s+(\\+?[0-9]{3,15})\\s*:?\\s+(.+)$").matcher(text.normalized);
                if (!match.matches()) return null;
            }
        }
        String number = phone(match.group(1));
        String body = text.originalGroup(match, 2);
        if (number == null || body.trim().isEmpty()) return command(Command.Type.UNKNOWN);
        return new Command(Command.Type.COMPOSE_SMS, number, body);
    }

    private static String phone(String input) {
        String number = input.replaceAll("[ ()-]", "");
        if (!number.matches("\\+?[0-9]{3,15}")) return null;
        int opens = 0;
        for (char c : input.toCharArray()) {
            if (c == '(') opens++;
            if (c == ')' && --opens < 0) return null;
        }
        return opens == 0 ? number : null;
    }

    private static Command parseAlarm(String words) {
        Matcher match = Pattern.compile("^(?:saat )?([0-9]{1,2})(?:[:.]([0-9]{2}))?(?:'?(?:a|e|ya|ye))?(?: icin)? alarm (?:kur|ayarla)$").matcher(words);
        if (!match.matches()) {
            match = Pattern.compile("^alarm (?:kur|ayarla)(?: saat)? ([0-9]{1,2})(?:[:.]([0-9]{2}))?$").matcher(words);
            if (!match.matches()) return parseSpokenAlarm(words);
        }
        int hour = Integer.parseInt(match.group(1));
        int minute = match.group(2) == null ? 0 : Integer.parseInt(match.group(2));
        if (hour > 23 || minute > 59) return command(Command.Type.UNKNOWN);
        return new Command(Command.Type.SET_ALARM, String.format(Locale.ROOT, "%02d:%02d", hour, minute));
    }

    private static Command parseSpokenAlarm(String words) {
        Matcher match = Pattern.compile("^(?:saat )?(.+?)(?: icin)? alarm (?:kur|ayarla)$").matcher(words);
        if (!match.matches()) {
            match = Pattern.compile("^alarm (?:kur|ayarla)(?: saat)? (.+)$").matcher(words);
            if (!match.matches()) return null;
        }
        String time = match.group(1);
        int hour = -1;
        int minute = 0;
        if (time.endsWith(" bucuk")) {
            hour = number(time.substring(0, time.length() - 6));
            minute = 30;
        } else {
            // Prefer an unambiguous whole hour: 'on yedi' means 17:00.
            hour = number(time);
            if (hour > 23) return command(Command.Type.UNKNOWN);
            if (hour < 0) {
                hour = -1;
                for (int split = time.lastIndexOf(' '); split > 0; split = time.lastIndexOf(' ', split - 1)) {
                    int candidateHour = number(time.substring(0, split));
                    int candidateMinute = number(time.substring(split + 1));
                    if (candidateHour >= 0 && candidateHour <= 23 && candidateMinute >= 0 && candidateMinute <= 59) {
                        hour = candidateHour;
                        minute = candidateMinute;
                        break;
                    }
                }
            }
        }
        if (hour < 0 || hour > 23) return command(Command.Type.UNKNOWN);
        return new Command(Command.Type.SET_ALARM, String.format(Locale.ROOT, "%02d:%02d", hour, minute));
    }

    private static Command parseTimer(String words) {
        Matcher match = Pattern.compile("^(.+?) (?:zamanlayici|sayac) (?:kur|baslat|ayarla)$").matcher(words);
        if (!match.matches()) {
            match = Pattern.compile("^(?:zamanlayici|sayac) (?:kur|baslat|ayarla) (.+)$").matcher(words);
            if (!match.matches()) return null;
        }
        String duration = match.group(1);
        Matcher units = Pattern.compile("(.+?) (saat|dakika|saniye)(?:lik)?(?= |$)").matcher(duration);
        long seconds = 0;
        int cursor = 0;
        int lastUnit = 4;
        while (units.find()) {
            if (!duration.substring(cursor, units.start()).trim().isEmpty()) return command(Command.Type.UNKNOWN);
            int rank = units.group(2).equals("saat") ? 3 : units.group(2).equals("dakika") ? 2 : 1;
            if (rank >= lastUnit) return command(Command.Type.UNKNOWN);
            lastUnit = rank;
            long amount = number(units.group(1).trim());
            if (amount < 0) return command(Command.Type.UNKNOWN);
            seconds += amount * (rank == 3 ? 3600 : rank == 2 ? 60 : 1);
            cursor = units.end();
        }
        if (cursor == 0 || !duration.substring(cursor).trim().isEmpty() || seconds < 1 || seconds > 86400) return command(Command.Type.UNKNOWN);
        return new Command(Command.Type.SET_TIMER, Long.toString(seconds));
    }

    /** Strict Turkish cardinal grammar; number words are never rewritten in user payloads. */
    private static int number(String value) {
        if (value.matches("[0-9]{1,7}")) return Integer.parseInt(value);
        String[] thousands = value.split("(?:^| )bin(?: |$)", -1);
        if (thousands.length == 1) return belowThousand(value);
        if (thousands.length != 2) return -1;
        int high = thousands[0].isEmpty() ? 1 : belowThousand(thousands[0]);
        int low = thousands[1].isEmpty() ? 0 : belowThousand(thousands[1]);
        return high > 0 && low >= 0 ? high * 1000 + low : -1;
    }

    private static int belowThousand(String value) {
        String[] hundreds = value.split("(?:^| )yuz(?: |$)", -1);
        if (hundreds.length == 1) return belowHundred(value);
        if (hundreds.length != 2) return -1;
        int high = hundreds[0].isEmpty() ? 1 : digit(hundreds[0]);
        int low = hundreds[1].isEmpty() ? 0 : belowHundred(hundreds[1]);
        return high > 0 && low >= 0 ? high * 100 + low : -1;
    }

    private static int belowHundred(String value) {
        String[] tokens = value.split(" ");
        if (tokens.length == 1) {
            int one = digit(value);
            if (one >= 0) return one;
            for (int ten = 1; ten < TENS.length; ten++) if (TENS[ten].equals(value)) return ten * 10;
        } else if (tokens.length == 2) {
            int one = digit(tokens[1]);
            if (one <= 0) return -1;
            for (int ten = 1; ten < TENS.length; ten++) if (TENS[ten].equals(tokens[0])) return ten * 10 + one;
        }
        return -1;
    }

    private static int digit(String value) {
        for (int digit = 0; digit < ONES.length; digit++) if (ONES[digit].equals(value)) return digit;
        return -1;
    }

    private static Command payload(Text text, String expression, Command.Type type) {
        Matcher match = Pattern.compile(expression).matcher(text.normalized);
        if (!match.matches()) return null;
        String argument = text.originalGroup(match, 1).trim();
        return argument.isEmpty() ? command(Command.Type.UNKNOWN) : new Command(type, argument);
    }

    private static boolean matches(String input, String... choices) {
        for (String choice : choices) if (input.equals(choice)) return true;
        return false;
    }

    private static Command command(Command.Type type) {
        return new Command(type);
    }

    /** A normalized character keeps its original UTF-16 range for lossless payloads. */
    private static final class Text {
        final String original;
        final String normalized;
        final List<Integer> starts = new ArrayList<>();
        final List<Integer> ends = new ArrayList<>();

        Text(String original) {
            this.original = original;
            StringBuilder result = new StringBuilder();
            boolean spacing = false;
            for (int offset = 0; offset < original.length();) {
                int codePoint = original.codePointAt(offset);
                int end = offset + Character.charCount(codePoint);
                if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
                    if (result.length() > 0) spacing = true;
                } else {
                    if (spacing) {
                        result.append(' ');
                        starts.add(offset);
                        ends.add(offset);
                        spacing = false;
                    }
                    String part = Normalizer.normalize(new String(Character.toChars(codePoint)).toLowerCase(TURKISH), Normalizer.Form.NFD)
                            .replaceAll("\\p{M}+", "").replace('ı', 'i').replace('’', '\'');
                    for (int i = 0; i < part.length(); i++) {
                        result.append(part.charAt(i));
                        starts.add(offset);
                        ends.add(end);
                    }
                }
                offset = end;
            }
            normalized = result.toString();
        }

        String originalGroup(Matcher matcher, int group) {
            return original.substring(starts.get(matcher.start(group)), ends.get(matcher.end(group) - 1));
        }
    }
}
