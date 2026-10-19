package com.cosmo.assistant;

import android.Manifest;
import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.content.ContentUris;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.CalendarContract;
import android.provider.ContactsContract;
import android.provider.MediaStore;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.Surface;

import com.cosmo.assistant.DeviceActions.Result;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Public Android features. Voice approvals are one-shot, short lived, and never persisted. */
public final class PhoneFeatures {
    private static final long APPROVAL_MILLIS = 30_000L;
    private static final Handler approvalHandler = new Handler(Looper.getMainLooper());
    private static final Runnable expireApproval = PhoneFeatures::clearPending;
    private static PendingAction pending;
    private static Context approvalContext;
    private static BroadcastReceiver approvalInvalidator;
    private final Context context;

    public PhoneFeatures(Context context) {
        this.context = context.getApplicationContext();
    }

    /** Called before the outer lock gate so even a rejected unrelated command cancels approval. */
    static synchronized void clearPendingFor(Command command) {
        if (command == null || (command.type != Command.Type.CONFIRM && command.type != Command.Type.CANCEL)) {
            clearPending();
        }
    }

    /** Returns null for commands handled by DeviceActions. */
    public Result execute(Command command) {
        clearPendingFor(command);
        if (command == null) return null;
        try {
            switch (command.type) {
                case CONFIRM: return confirm();
                case CANCEL:
                    clearPending();
                    return Result.say("İşlemi iptal ettim.");
                case GREETING: return greeting(command.argument);
                case SELF_STATUS: return status();
                default: break;
            }
            if (!isExtraCommand(command.type)) return null;
            if (!unlocked()) return Result.say("Bu işlem için önce telefonun kilidini açmalısın.");
            switch (command.type) {
                case BRIGHTNESS_UP: return brightness(null, 26);
                case BRIGHTNESS_DOWN: return brightness(null, -26);
                case BRIGHTNESS_SET: return brightness(command.argument, 0);
                case ROTATION_AUTO: return rotation(true);
                case ROTATION_PORTRAIT: return rotation(false);
                case APP_SETTINGS:
                    return Result.open("Feri uygulama ayarlarını açıyorum.",
                            new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri()));
                case DISPLAY_SETTINGS: return settings("Ekran", Settings.ACTION_DISPLAY_SETTINGS);
                case SOUND_SETTINGS: return settings("Ses", Settings.ACTION_SOUND_SETTINGS);
                case ACCESSIBILITY_SETTINGS: return settings("Erişilebilirlik", Settings.ACTION_ACCESSIBILITY_SETTINGS);
                case BATTERY_SETTINGS: return settings("Pil", Settings.ACTION_BATTERY_SAVER_SETTINGS);
                case AIRPLANE_SETTINGS: return settings("Uçak modu", Settings.ACTION_AIRPLANE_MODE_SETTINGS);
                case HOTSPOT_SETTINGS:
                    return Result.open("Erişim noktasını bağlantı ayarlarından açabilirsin.",
                            new Intent(Settings.ACTION_WIRELESS_SETTINGS));
                case NFC_SETTINGS: return settings("NFC", Settings.ACTION_NFC_SETTINGS);
                case PRIVACY_SETTINGS: return settings("Gizlilik", Settings.ACTION_PRIVACY_SETTINGS);
                case STORAGE_SETTINGS: return settings("Depolama", Settings.ACTION_INTERNAL_STORAGE_SETTINGS);
                case CAMERA_PHOTO:
                    return Result.open("Fotoğraf kamerasını açıyorum. Çekimi kamera ekranından yapabilirsin.",
                            new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA));
                case CAMERA_VIDEO:
                    return Result.open("Video kamerasını açıyorum. Kaydı kamera ekranından başlatabilirsin.",
                            new Intent(MediaStore.INTENT_ACTION_VIDEO_CAMERA));
                case CALENDAR_INSERT:
                    if (command.argument.trim().isEmpty()) return Result.say("Etkinliğin adını söylemelisin.");
                    return Result.open("Takvim taslağı hazır. Tarihi ve saati kontrol edip kaydedebilirsin.",
                            new Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
                                    .putExtra(CalendarContract.Events.TITLE, command.argument));
                case SCREENSHOT:
                    if (Build.VERSION.SDK_INT < 28) return Result.say("Sesli ekran görüntüsü Android 9 ve üzerini gerektiriyor.");
                    if (!CosmoAccessibilityService.isScreenControlAvailable(context)) return screenPermission();
                    return Result.say(CosmoAccessibilityService.navigate(context,
                            AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
                            ? "Ekran görüntüsü isteğini Android'e ilettim."
                            : "Ekran görüntüsü alınamadı. Bazı uygulamalar görüntü alınmasına izin vermez.");
                case SCREEN_LOCK:
                    if (Build.VERSION.SDK_INT < 28) return Result.say("Sesli ekran kilitleme Android 9 ve üzerini gerektiriyor.");
                    return globalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN, "Ekranı kilitledim.");
                case SCREEN_READ: return Result.say(CosmoAccessibilityService.readScreen(context));
                case SCREEN_CLICK: return prepareScreen(command.type, command.argument);
                case SCREEN_TYPE: return prepareScreen(command.type, command.argument);
                case CALL: return prepareCall(command.argument);
                case CONTACT_SHOW:
                case DIAL_CONTACT:
                case COMPOSE_SMS_CONTACT: return contact(command);
                case MEDIA_PREVIOUS: return previousTrack();
                case POWER_OFF:
                case REBOOT:
                    if (!CosmoAccessibilityService.isAvailable(context)) return navigationPermission();
                    if (!stage(new PendingAction(command.type, "", null))) return approvalUnavailable();
                    return Result.say("Android telefonu doğrudan kapatmama veya yeniden başlatmama izin vermiyor. "
                            + "Güç menüsünü açmam için Feri de, ardından onayla. Menüden seçimi sen yapmalısın. İptal diyebilirsin.");
                default: return null;
            }
        } catch (SecurityException denied) {
            return Result.say("Android bu işlem için izin vermedi. Feri içinden ilgili izni kontrol edebilirsin.");
        } catch (RuntimeException unavailable) {
            return Result.say("Bu özellik bu telefonda şu anda kullanılamıyor. İlgili uygulamayı veya ayarı elle açabilirsin.");
        }
    }

    private static boolean isExtraCommand(Command.Type type) {
        switch (type) {
            case BRIGHTNESS_UP: case BRIGHTNESS_DOWN: case BRIGHTNESS_SET:
            case ROTATION_AUTO: case ROTATION_PORTRAIT:
            case APP_SETTINGS: case DISPLAY_SETTINGS: case SOUND_SETTINGS:
            case ACCESSIBILITY_SETTINGS: case BATTERY_SETTINGS: case AIRPLANE_SETTINGS:
            case HOTSPOT_SETTINGS: case NFC_SETTINGS: case PRIVACY_SETTINGS: case STORAGE_SETTINGS:
            case CAMERA_PHOTO: case CAMERA_VIDEO: case CALENDAR_INSERT:
            case SCREENSHOT: case SCREEN_LOCK: case SCREEN_READ: case SCREEN_CLICK: case SCREEN_TYPE:
            case CALL: case CONTACT_SHOW: case DIAL_CONTACT: case COMPOSE_SMS_CONTACT:
            case MEDIA_PREVIOUS: case POWER_OFF: case REBOOT: return true;
            default: return false;
        }
    }

    private Result brightness(String requested, int delta) {
        int percent = -1;
        if (requested != null) {
            try { percent = Integer.parseInt(requested); }
            catch (NumberFormatException invalid) { return Result.say("Parlaklık için sıfır ile yüz arasında bir yüzde söylemelisin."); }
            if (percent < 0 || percent > 100) return Result.say("Parlaklık için sıfır ile yüz arasında bir yüzde söylemelisin.");
        }
        if (!Settings.System.canWrite(context)) return writeSettingsPermission();
        int current = Settings.System.getInt(context.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, 128);
        int value = requested == null ? Math.max(0, Math.min(255, current + delta))
                : Math.round(percent * 255f / 100f);
        boolean modeSet = Settings.System.putInt(context.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
        boolean valueSet = modeSet && Settings.System.putInt(context.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, value);
        return Result.say(valueSet ? "Ekran parlaklığını yüzde " + Math.round(value * 100f / 255f) + " yaptım."
                : "Android ekran parlaklığını değiştirmeme izin vermedi.");
    }

    private Result rotation(boolean automatic) {
        if (!Settings.System.canWrite(context)) return writeSettingsPermission();
        boolean portraitSet = automatic || Settings.System.putInt(context.getContentResolver(),
                Settings.System.USER_ROTATION, Surface.ROTATION_0);
        boolean saved = portraitSet && Settings.System.putInt(context.getContentResolver(),
                Settings.System.ACCELEROMETER_ROTATION, automatic ? 1 : 0);
        return Result.say(saved ? (automatic ? "Otomatik ekran döndürmeyi açtım." : "Ekranı dikey konuma kilitledim.")
                : "Android ekran döndürme ayarını değiştirmeme izin vermedi.");
    }

    private Result writeSettingsPermission() {
        return Result.open("Parlaklık ve ekran döndürme için sistem ayarlarını değiştirme izni gerekiyor. "
                        + "Açılan ekranda Feri için izin verip komutu tekrar söyleyebilirsin.",
                new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, packageUri()));
    }

    private Result settings(String label, String action) {
        return Result.open(label + " ayarlarını açıyorum. Değişikliği ayarlar ekranından yapabilirsin.", new Intent(action));
    }

    private Result previousTrack() {
        AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audio == null) return Result.say("Medya denetimi bu telefonda kullanılamıyor.");
        audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS));
        audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PREVIOUS));
        return Result.say("Önceki parça komutunu etkin medya oynatıcısına ilettim.");
    }

    private Result prepareScreen(Command.Type type, String target) {
        if (!CosmoAccessibilityService.isScreenControlAvailable(context)) return screenPermission();
        if (target.trim().isEmpty() || target.length() > (type == Command.Type.SCREEN_CLICK ? 300 : 2000)) {
            return Result.say(type == Command.Type.SCREEN_CLICK ? "Ekrandaki düğmenin tam adını söylemelisin."
                    : "Yazılacak metin boş olmamalı ve iki bin karakteri geçmemeli.");
        }
        String key = CosmoAccessibilityService.screenContextKey(context);
        if (key == null) return Result.say("Bu ekrandaki denetimlere erişemiyorum. Telefonun kilidini açıp normal bir uygulamada yeniden dene.");
        if (!stage(new PendingAction(type, target, key))) return approvalUnavailable();
        String description = type == Command.Type.SCREEN_CLICK
                ? "Ekrandaki şu düğmeye basmamı istiyorsun: "
                : "Seçili yazı alanını şu metinle değiştirmemi istiyorsun: ";
        return Result.say(description + excerpt(target) + ". Onaylamak için Feri de, ardından onayla. "
                + "Otuz saniye içinde onay verebilir veya iptal diyebilirsin.");
    }

    private Result prepareCall(String rawNumber) {
        String number = phoneNumber(rawNumber);
        if (number == null) return Result.say("Arama için geçerli bir telefon numarası söylemelisin.");
        if (!has(Manifest.permission.CALL_PHONE)) {
            return Result.say("Doğrudan arama için önce Feri içinden telefon izni vermelisin. "
                    + "Numarayı ara komutuyla izin vermeden arama ekranını da açabilirsin.");
        }
        if (!stage(new PendingAction(Command.Type.CALL, number, null))) return approvalUnavailable();
        return Result.say(number + " numarasını doğrudan aramam için Feri de, ardından onayla. "
                + "Otuz saniye içinde onay verebilir veya iptal diyebilirsin.");
    }

    private Result confirm() {
        PendingAction action = takePending();
        if (action == null) return Result.say("Onay bekleyen bir işlem yok.");
        // Consume before every check: denied, expired, failed and successful actions cannot replay.
        if (!unlocked()) return Result.say("Onayı iptal ettim. Bu işlem için önce telefonun kilidini açmalısın.");
        if (SystemClock.elapsedRealtime() >= action.expiresAt) return Result.say("Onay süresi doldu. Komutu yeniden söylemelisin.");
        switch (action.type) {
            case CALL:
                if (!has(Manifest.permission.CALL_PHONE)) return Result.say("Telefon izni kapatılmış. Arama başlatılmadı.");
                return Result.open(action.target + " numarasına arama isteğini gönderiyorum.",
                        new Intent(Intent.ACTION_CALL, Uri.fromParts("tel", action.target, null)));
            case SCREEN_CLICK:
            case SCREEN_TYPE:
                if (!CosmoAccessibilityService.isScreenControlAvailable(context)) return screenPermission();
                if (!action.screenKey.equals(CosmoAccessibilityService.screenContextKey(context))) {
                    return Result.say("Ekran değiştiği için onayı iptal ettim. Komutu yeniden söylemelisin.");
                }
                boolean done = action.type == Command.Type.SCREEN_CLICK
                        ? CosmoAccessibilityService.clickLabel(context, action.target, action.screenKey)
                        : CosmoAccessibilityService.typeText(context, action.target, action.screenKey);
                return Result.say(done ? (action.type == Command.Type.SCREEN_CLICK ? "Düğmeye bastım." : "Metni seçili alana yazdım.")
                        : "İşlemi yapamadım. Düğme tek ve tam eşleşmeli; yazı alanı seçili olmalı. Güvenlik ve parola alanlarını telefondan kullanmalısın.");
            case POWER_OFF:
            case REBOOT:
                return globalAction(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG,
                        "Güç menüsünü açtım. Kapatma veya yeniden başlatma seçimini telefondan yapmalısın.");
            default: return Result.say("Onay bekleyen bir işlem yok.");
        }
    }

    private Result contact(Command command) {
        if (!has(Manifest.permission.READ_CONTACTS)) {
            return Result.say("Kişileri adlarıyla bulmak için önce Feri içinden rehber izni vermelisin. "
                    + "İzin vermeden telefon numarasıyla komut verebilirsin.");
        }
        String requested = CommandParser.normalize(command.argument).trim();
        if (requested.isEmpty()) return Result.say("Kişinin rehberde kayıtlı tam adını söylemelisin.");
        Map<Long, Person> matches = new LinkedHashMap<>();
        try (Cursor rows = context.getContentResolver().query(ContactsContract.Contacts.CONTENT_URI,
                new String[]{ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY},
                null, null, null)) {
            if (rows != null) {
                int count = 0;
                while (rows.moveToNext() && count++ < 20_000) {
                    String label = rows.getString(1);
                    if (requested.equals(CommandParser.normalize(label).trim())) {
                        long id = rows.getLong(0);
                        matches.put(id, new Person(id, label));
                        if (matches.size() > 1) break;
                    }
                }
                // A truncated lookup cannot establish uniqueness.
                if (count >= 20_000 && !rows.isAfterLast()) return chooseContact("Rehber çok büyük; kişiyi rehberden seçmelisin.");
            }
        }
        if (matches.isEmpty()) return Result.say("Rehberde bu tam adla kayıtlı kişi bulamadım. Kayıtlı tam adını söylemelisin.");
        if (matches.size() != 1) return chooseContact("Bu adla birden fazla kişi var. Yanlış kişiyi seçmemek için rehberden seçmelisin.");
        Person person = matches.values().iterator().next();
        Uri personUri = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, person.id);
        if (command.type == Command.Type.CONTACT_SHOW) {
            return Result.open(person.label + " kişisinin kartını açıyorum.", new Intent(Intent.ACTION_VIEW, personUri));
        }
        Set<String> numbers = new LinkedHashSet<>();
        try (Cursor rows = context.getContentResolver().query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                new String[]{ContactsContract.CommonDataKinds.Phone.NUMBER},
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID + " = ?", new String[]{Long.toString(person.id)}, null)) {
            if (rows != null) {
                while (rows.moveToNext()) {
                    String number = phoneNumber(rows.getString(0));
                    if (number != null) numbers.add(number);
                    if (numbers.size() > 1) break;
                }
            }
        }
        if (numbers.isEmpty()) return Result.open("Bu kişinin kullanılabilir telefon numarası bulunamadı. Kişi kartını açıyorum.",
                new Intent(Intent.ACTION_VIEW, personUri));
        if (numbers.size() != 1) return Result.open("Bu kişinin birden fazla numarası var. Kişi kartından doğru numarayı seçmelisin.",
                new Intent(Intent.ACTION_VIEW, personUri));
        String number = numbers.iterator().next();
        if (command.type == Command.Type.DIAL_CONTACT) {
            return Result.open(person.label + ", " + number + ". Numarayı kontrol edip aramayı telefon ekranından başlatabilirsin.",
                    new Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)));
        }
        if (command.secondary.trim().isEmpty()) return Result.say("Mesajda ne yazmamı istediğini söylemelisin.");
        return Result.open(person.label + " için mesaj taslağı hazır. Kontrol edip gönder düğmesine sen basmalısın.",
                new Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null))
                        .putExtra("sms_body", command.secondary));
    }

    private Result chooseContact(String speech) {
        return Result.open(speech + " Ardından tam ad veya numarayla komutu yeniden söyleyebilirsin.",
                new Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI));
    }

    private Result greeting(String kind) {
        if ("name".equals(kind)) return Result.say("Ben Feri. Bana Feri diye seslenebilirsin. Komutlarında sana yardımcı olmak için buradayım.");
        if ("thanks".equals(kind)) return Result.say("Rica ederim. İhtiyacın olduğunda buradayım.");
        if ("how_are_you".equals(kind)) return Result.say("Hazırım, buradayım. Sen nasılsın? Birlikte ne yapalım?");
        return Result.say("Merhaba, buradayım. Birlikte ne yapalım?");
    }

    private Result status() {
        return Result.say("Feri burada. Mikrofon izni " + (has(Manifest.permission.RECORD_AUDIO) ? "açık" : "kapalı")
                + ". Sesli ekran kontrolü " + (CosmoAccessibilityService.isScreenControlAvailable(context) ? "açık" : "kapalı")
                + ". Rehber izni " + (has(Manifest.permission.READ_CONTACTS) ? "açık" : "kapalı")
                + ". Doğrudan arama izni " + (has(Manifest.permission.CALL_PHONE) ? "açık" : "kapalı") + ".");
    }

    private Result globalAction(int action, String success) {
        if (!CosmoAccessibilityService.isAvailable(context)) return navigationPermission();
        return Result.say(CosmoAccessibilityService.navigate(context, action) ? success
                : "Android bu işlemi şu anda tamamlayamadı. Telefonda elle deneyebilirsin.");
    }

    private Result navigationPermission() {
        return Result.say("Önce Feri içinden isteğe bağlı erişilebilirlik denetimini etkinleştirmelisin.");
    }

    private Result screenPermission() {
        return Result.say("Önce Feri içinden isteğe bağlı erişilebilirliği ve ayrıca ekran kontrolünü etkinleştirmelisin.");
    }

    private boolean has(String permission) {
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean unlocked() {
        KeyguardManager keyguard = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
        return keyguard != null && !keyguard.isKeyguardLocked() && !keyguard.isDeviceLocked();
    }

    private Uri packageUri() { return Uri.parse("package:" + context.getPackageName()); }

    private static String excerpt(String text) {
        return text.length() <= 180 ? text : text.substring(0, 180) + ", devamı var";
    }

    private static String phoneNumber(String value) {
        if (value == null || !value.matches("[+0-9 ()-]+")) return null;
        String number = value.replaceAll("[ ()-]", "");
        return number.matches("\\+?[0-9]{3,15}") ? number : null;
    }

    private Result approvalUnavailable() {
        return Result.say("İşlemi güvenli biçimde onaya hazırlayamadım. Komutu yeniden söylemelisin.");
    }

    /**
     * Watch only while an approval exists. Application context also covers typed commands when
     * no voice service runs, and never retains an Activity or Service after its destruction.
     */
    private boolean stage(PendingAction action) {
        synchronized (PhoneFeatures.class) {
            clearPending();
            BroadcastReceiver invalidator = new BroadcastReceiver() {
                @Override public void onReceive(Context receivedContext, Intent intent) {
                    String event = intent == null ? null : intent.getAction();
                    if (Intent.ACTION_SCREEN_OFF.equals(event) || Intent.ACTION_USER_PRESENT.equals(event)
                            || Intent.ACTION_USER_UNLOCKED.equals(event)) clearPending();
                }
            };
            IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
            // Invalidate on unlock too if an OEM deferred the preceding screen-off broadcast.
            filter.addAction(Intent.ACTION_USER_PRESENT);
            filter.addAction(Intent.ACTION_USER_UNLOCKED);
            try {
                if (Build.VERSION.SDK_INT >= 33) {
                    context.registerReceiver(invalidator, filter, Context.RECEIVER_NOT_EXPORTED);
                } else {
                    // These are protected system broadcasts; apps cannot spoof them on API26..32.
                    context.registerReceiver(invalidator, filter);
                }
            } catch (RuntimeException unavailable) {
                return false;
            }
            approvalContext = context;
            approvalInvalidator = invalidator;
            pending = action;
            // confirm() still enforces the exact monotonic deadline; this also releases the
            // receiver when nobody confirms or cancels the expired request.
            approvalHandler.postDelayed(expireApproval, APPROVAL_MILLIS + 1L);
            return true;
        }
    }

    private static synchronized void clearPending() {
        pending = null;
        approvalHandler.removeCallbacks(expireApproval);
        if (approvalContext != null && approvalInvalidator != null) {
            try { approvalContext.unregisterReceiver(approvalInvalidator); }
            catch (IllegalArgumentException alreadyRemoved) { /* Idempotent cleanup. */ }
        }
        approvalContext = null;
        approvalInvalidator = null;
    }

    private static synchronized PendingAction takePending() {
        PendingAction action = pending;
        clearPending();
        return action;
    }

    private static final class PendingAction {
        final Command.Type type;
        final String target;
        final String screenKey;
        final long expiresAt;
        PendingAction(Command.Type type, String target, String screenKey) {
            this.type = type;
            this.target = target;
            this.screenKey = screenKey;
            this.expiresAt = SystemClock.elapsedRealtime() + APPROVAL_MILLIS;
        }
    }

    private static final class Person {
        final long id;
        final String label;
        Person(long id, String label) { this.id = id; this.label = label; }
    }
}
