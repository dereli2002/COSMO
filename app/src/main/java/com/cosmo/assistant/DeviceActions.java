package com.cosmo.assistant;

import android.Manifest;
import android.accessibilityservice.AccessibilityService;
import android.app.SearchManager;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.provider.AlarmClock;
import android.provider.Settings;
import android.view.KeyEvent;

import org.json.JSONArray;
import org.json.JSONException;

import java.text.Normalizer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Executes public Android APIs. Any UI action is returned to the foreground Activity. */
public final class DeviceActions {
    private static final Locale TURKISH = Locale.forLanguageTag("tr-TR");
    private final Context context;
    private final SharedPreferences preferences;

    public DeviceActions(Context context) {
        this.context = context.getApplicationContext();
        this.preferences = this.context.getSharedPreferences("cosmo", Context.MODE_PRIVATE);
    }

    public static final class Result {
        public final String speech;
        public final Intent intent;
        public final boolean stopListening;

        public Result(String speech, Intent intent, boolean stopListening) {
            this.speech = speech;
            this.intent = intent;
            this.stopListening = stopListening;
        }

        public static Result say(String speech) { return new Result(speech, null, false); }
        public static Result open(String speech, Intent intent) {
            return new Result(speech, intent, false);
        }
    }

    public Result execute(Command command) {
        if (command == null) return Result.say("Komutu anlayamadım. Yardım diyebilirsin.");
        try {
            if (requiresUnlockedPhone(command.type) && isPhoneLocked()) {
                return Result.say("Bu işlem için önce telefonun kilidini açmalısın.");
            }
            switch (command.type) {
                case TIME:
                    return Result.say("Saat " + new SimpleDateFormat("HH:mm", TURKISH).format(new Date()));
                case DATE:
                    return Result.say("Bugün " + new SimpleDateFormat("d MMMM yyyy EEEE", TURKISH).format(new Date()));
                case BATTERY: return battery();
                case FLASHLIGHT_ON: return flashlight(true);
                case FLASHLIGHT_OFF: return flashlight(false);
                case VOLUME_UP: return volume(AudioManager.ADJUST_RAISE);
                case VOLUME_DOWN: return volume(AudioManager.ADJUST_LOWER);
                case VOLUME_MUTE: return volume(AudioManager.ADJUST_MUTE);
                case WIFI_SETTINGS:
                    return Result.open("Wi-Fi ayarlarını açabilirsin.", new Intent(Build.VERSION.SDK_INT >= 29
                            ? Settings.Panel.ACTION_WIFI : Settings.ACTION_WIFI_SETTINGS));
                case BLUETOOTH_SETTINGS:
                    return Result.open("Bluetooth ayarlarını açabilirsin.", new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
                case INTERNET_SETTINGS:
                    return Result.open("İnternet ayarlarını açabilirsin.", new Intent(Build.VERSION.SDK_INT >= 29
                            ? Settings.Panel.ACTION_INTERNET_CONNECTIVITY : Settings.ACTION_WIRELESS_SETTINGS));
                case LOCATION_SETTINGS:
                    return Result.open("Konum ayarlarını açabilirsin.", new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
                case OPEN_APP: return openApp(command.argument);
                case DIAL:
                    return Result.open("Numarayı kontrol edip aramayı telefon ekranından başlatabilirsin.",
                            new Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", command.argument, null)));
                case COMPOSE_SMS:
                    return Result.open("Mesaj taslağı hazır. Kontrol edip gönder düğmesine sen basmalısın.",
                            new Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", command.argument, null))
                                    .putExtra("sms_body", command.secondary));
                case MAP_SEARCH:
                    return Result.open("Harita araması hazır.", new Intent(Intent.ACTION_VIEW,
                            Uri.parse("geo:0,0?q=" + Uri.encode(command.argument))));
                case WEB_SEARCH:
                    return Result.open("İnternet araması hazır.", new Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://www.google.com/search?q=" + Uri.encode(command.argument)))
                            .putExtra(SearchManager.QUERY, command.argument));
                case SET_ALARM: return alarm(command.argument);
                case SET_TIMER: return timer(command.argument);
                case NOTE_SAVE: return saveNote(command.argument);
                case NOTE_LIST: return listNotes();
                case MEDIA_PLAY: return media(KeyEvent.KEYCODE_MEDIA_PLAY);
                case MEDIA_PAUSE: return media(KeyEvent.KEYCODE_MEDIA_PAUSE);
                case MEDIA_PLAY_PAUSE: return media(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
                case MEDIA_NEXT: return media(KeyEvent.KEYCODE_MEDIA_NEXT);
                case ACCESSIBILITY_BACK:
                    return navigate(AccessibilityService.GLOBAL_ACTION_BACK, "Geri gidiyorum.");
                case ACCESSIBILITY_HOME:
                    return navigate(AccessibilityService.GLOBAL_ACTION_HOME, "Ana ekrana gidiyorum.");
                case ACCESSIBILITY_RECENTS:
                    return navigate(AccessibilityService.GLOBAL_ACTION_RECENTS, "Son uygulamaları açıyorum.");
                case ACCESSIBILITY_NOTIFICATIONS:
                    return navigate(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS, "Bildirimleri açıyorum.");
                case ACCESSIBILITY_QUICK_SETTINGS:
                    return navigate(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS, "Hızlı ayarları açıyorum.");
                case ACCESSIBILITY_SCROLL_UP: return scroll(false);
                case ACCESSIBILITY_SCROLL_DOWN: return scroll(true);
                case STOP_LISTENING:
                    return new Result("Dinlemeyi durdurdum. Yeniden başlatmak için COSMO'yu açabilirsin.", null, true);
                case HELP:
                    return Result.say("Saat kaç, pil durumu, feneri aç, sesi artır, not al, notlarımı oku, "
                            + "YouTube aç, Wi-Fi ayarları, Ankara haritada ara veya dinlemeyi durdur diyebilirsin. "
                            + "Geri ve ana ekran komutları için isteğe bağlı erişilebilirliği açmalısın.");
                case CANCEL: return Result.say("İşlemi iptal ettim.");
                case CONFIRM: return Result.say("Onay bekleyen bir işlem yok.");
                case UNKNOWN:
                default:
                    return Result.say("Bu komutu henüz desteklemiyorum. Yardım diyerek örnekleri duyabilirsin.");
            }
        } catch (SecurityException exception) {
            return Result.say("Android bu işlem için izin vermedi. COSMO'nun uygulama izinlerini kontrol edebilirsin.");
        } catch (IllegalArgumentException exception) {
            return Result.say("Bu işlem için verilen bilgi geçersiz. Komutu yeniden söyleyebilir misin?");
        } catch (RuntimeException exception) {
            return Result.say("Bu işlem bu telefonda tamamlanamadı. Komutu yeniden deneyebilirsin.");
        }
    }

    private boolean isPhoneLocked() {
        KeyguardManager manager = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
        return manager != null && manager.isKeyguardLocked();
    }

    private static boolean requiresUnlockedPhone(Command.Type type) {
        switch (type) {
            case TIME:
            case DATE:
            case BATTERY:
            case HELP:
            case STOP_LISTENING:
            case CANCEL:
            case CONFIRM:
            case UNKNOWN:
                return false;
            default:
                return true;
        }
    }

    private Result battery() {
        Intent battery = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery == null) return Result.say("Pil bilgisine şu anda erişemiyorum.");
        int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        if (level < 0 || scale <= 0) return Result.say("Pil seviyesi bu telefonda bildirilmiyor.");
        int percent = Math.round(level * 100f / scale);
        int status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL;
        return Result.say("Pil yüzde " + percent + (charging ? ". Telefon şarja bağlı." : "."));
    }

    private Result flashlight(boolean enabled) {
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return Result.say("Fener için kamera izni gerekiyor. COSMO'yu açıp kamera iznini verebilirsin.");
        }
        CameraManager camera = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        if (camera == null) return Result.say("Bu telefonda fener bulunamadı.");
        try {
            String selected = null;
            for (String id : camera.getCameraIdList()) {
                CameraCharacteristics details = camera.getCameraCharacteristics(id);
                if (Boolean.TRUE.equals(details.get(CameraCharacteristics.FLASH_INFO_AVAILABLE))) {
                    selected = id;
                    Integer facing = details.get(CameraCharacteristics.LENS_FACING);
                    if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) break;
                }
            }
            if (selected == null) return Result.say("Bu telefonda kullanılabilir fener bulunamadı.");
            camera.setTorchMode(selected, enabled);
            return Result.say(enabled ? "Feneri açtım." : "Feneri kapattım.");
        } catch (CameraAccessException exception) {
            return Result.say("Fener şu anda kullanılamıyor. Kamera başka bir uygulamada açık olabilir.");
        }
    }

    private Result volume(int adjustment) {
        AudioManager manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (manager == null || manager.isVolumeFixed()) return Result.say("Bu cihazda ses düzeyini değiştiremiyorum.");
        // Only media volume is changed. Ringer / Do Not Disturb settings are never modified.
        manager.adjustStreamVolume(AudioManager.STREAM_MUSIC, adjustment, 0);
        if (adjustment == AudioManager.ADJUST_MUTE) {
            return Result.say("Medya sesini kıstım. COSMO'nun sesi de duyulmayabilir; sesi artır diyebilirsin.");
        }
        if (adjustment == AudioManager.ADJUST_RAISE && manager.isStreamMute(AudioManager.STREAM_MUSIC)) {
            manager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0);
        }
        return Result.say(adjustment == AudioManager.ADJUST_RAISE ? "Medya sesini artırdım." : "Medya sesini azalttım.");
    }

    private Result media(int keyCode) {
        AudioManager manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (manager == null) return Result.say("Medya denetimi bu telefonda kullanılamıyor.");
        manager.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
        manager.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keyCode));
        return Result.say("Komutu etkin medya oynatıcısına ilettim.");
    }

    private Result alarm(String value) {
        if (!value.matches("\\d{1,2}:\\d{2}")) return Result.say("Alarm için örneğin 07:30 alarm kur diyebilirsin.");
        String[] parts = value.split(":");
        int hour = Integer.parseInt(parts[0]);
        int minute = Integer.parseInt(parts[1]);
        if (hour < 0 || hour > 23 || minute < 0 || minute > 59) return Result.say("Geçerli bir alarm saati söylemelisin.");
        return Result.open("Alarmı saat uygulamasında kontrol edebilirsin.", new Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, hour)
                .putExtra(AlarmClock.EXTRA_MINUTES, minute)
                .putExtra(AlarmClock.EXTRA_MESSAGE, "COSMO")
                .putExtra(AlarmClock.EXTRA_SKIP_UI, false));
    }

    private Result timer(String value) {
        int seconds;
        try { seconds = Integer.parseInt(value); }
        catch (NumberFormatException exception) { return Result.say("Zamanlayıcı için geçerli bir süre söylemelisin."); }
        if (seconds <= 0 || seconds > 86400) return Result.say("Zamanlayıcı süresi bir saniye ile 24 saat arasında olmalı.");
        return Result.open("Zamanlayıcıyı saat uygulamasında kontrol edebilirsin.", new Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                .putExtra(AlarmClock.EXTRA_MESSAGE, "COSMO")
                .putExtra(AlarmClock.EXTRA_SKIP_UI, false));
    }

    private Result saveNote(String note) {
        note = note.trim();
        if (note.isEmpty()) return Result.say("Not almak için kaydetmek istediğin cümleyi söylemelisin.");
        if (note.length() > 2000) return Result.say("Bu not çok uzun. Daha kısa parçalar halinde kaydedebilirsin.");
        JSONArray existing = readNotes();
        if (existing.length() >= 100) {
            return Result.say("Not sınırı doldu: en fazla 100 not saklayabilirim. Yeni not kaydedilmedi; mevcut notların korundu.");
        }
        JSONArray updated = new JSONArray();
        // Newest first. Reaching the limit never silently deletes an older note.
        updated.put(note);
        for (int index = 0; index < existing.length(); index++) {
            updated.put(existing.optString(index));
        }
        boolean saved = preferences.edit().putString("notes", updated.toString()).commit();
        return Result.say(saved ? "Notunu bu telefona kaydettim." : "Not kaydedilemedi. Depolama alanını kontrol edebilirsin.");
    }

    private Result listNotes() {
        JSONArray notes = readNotes();
        if (notes.length() == 0) return Result.say("Henüz kayıtlı notun yok.");
        StringBuilder spoken = new StringBuilder();
        spoken.append(notes.length()).append(" notun var. ");
        if (notes.length() > 5) spoken.append("Son beş notunu okuyorum. ");
        for (int index = 0; index < notes.length() && index < 5; index++) {
            spoken.append(index + 1).append(". ").append(notes.optString(index)).append(". ");
        }
        return Result.say(spoken.toString());
    }

    private JSONArray readNotes() {
        try { return new JSONArray(preferences.getString("notes", "[]")); }
        catch (JSONException exception) { return new JSONArray(); }
    }

    private Result openApp(String requested) {
        String query = normalize(requested);
        if (query.isEmpty()) return Result.say("Açmamı istediğin uygulamanın adını söylemelisin.");
        if (query.equals("ayarlar")) return Result.open("Telefon ayarlarını açabilirsin.", new Intent(Settings.ACTION_SETTINGS));
        PackageManager manager = context.getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = manager.queryIntentActivities(launcher, 0);
        List<ResolveInfo> exact = new ArrayList<>();
        List<ResolveInfo> partial = new ArrayList<>();
        for (ResolveInfo info : apps) {
            if (info.activityInfo == null || !info.activityInfo.exported) continue;
            String label = normalize(info.loadLabel(manager).toString());
            String packageName = info.activityInfo.packageName;
            if (label.equals(query) || normalize(packageName).equals(query) || isAlias(query, packageName)) {
                addUnique(exact, info);
            } else if (label.contains(query)) {
                addUnique(partial, info);
            }
        }
        List<ResolveInfo> matches = exact.isEmpty() ? partial : exact;
        if (matches.isEmpty()) return Result.say("Bu isimde açılabilir bir uygulama bulamadım. Telefonda görünen tam adını söyleyebilirsin.");
        if (matches.size() > 1) {
            StringBuilder names = new StringBuilder();
            for (int index = 0; index < Math.min(matches.size(), 3); index++) {
                if (index > 0) names.append(", ");
                names.append(matches.get(index).loadLabel(manager));
            }
            return Result.say("Birden fazla uygulama buldum: " + names + ". Tam adını söyleyebilirsin.");
        }
        ResolveInfo target = matches.get(0);
        Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setClassName(target.activityInfo.packageName, target.activityInfo.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        return Result.open(target.loadLabel(manager) + " uygulamasını açabilirsin.", intent);
    }

    private static void addUnique(List<ResolveInfo> destination, ResolveInfo item) {
        for (ResolveInfo present : destination) {
            if (present.activityInfo.packageName.equals(item.activityInfo.packageName)) return;
        }
        destination.add(item);
    }

    private static boolean isAlias(String name, String packageName) {
        switch (name) {
            case "youtube": return packageName.equals("com.google.android.youtube");
            case "whatsapp": case "vatsap": return packageName.equals("com.whatsapp");
            case "instagram": return packageName.equals("com.instagram.android");
            case "telegram": return packageName.equals("org.telegram.messenger");
            case "chrome": case "tarayici": return packageName.equals("com.android.chrome");
            case "haritalar": case "google haritalar": return packageName.equals("com.google.android.apps.maps");
            default: return false;
        }
    }

    private static String normalize(String value) {
        String lowered = value.toLowerCase(TURKISH).replace('ı', 'i');
        return Normalizer.normalize(lowered, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").trim().replaceAll("\\s+", " ");
    }

    private Result navigate(int action, String speech) {
        if (!CosmoAccessibilityService.isAvailable(context)) return accessibilityUnavailable();
        return Result.say(CosmoAccessibilityService.navigate(context, action) ? speech
                : "Bu ekranda gezinme komutu uygulanamadı.");
    }

    private Result scroll(boolean down) {
        if (!CosmoAccessibilityService.isAvailable(context)) return accessibilityUnavailable();
        return Result.say(CosmoAccessibilityService.scroll(context, down)
                ? (down ? "Aşağı kaydırıyorum." : "Yukarı kaydırıyorum.")
                : "Bu ekranda kaydırma komutu uygulanamadı.");
    }

    private Result accessibilityUnavailable() {
        return Result.say("Bu komut için COSMO'da gezinme iznini ve Android erişilebilirlik hizmetini kendin açmalısın.");
    }
}
