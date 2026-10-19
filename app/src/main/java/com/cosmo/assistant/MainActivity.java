package com.cosmo.assistant;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.Locale;

/** User-visible controls are always required before microphone / navigation access. */
public final class MainActivity extends Activity {
    private static final int BACKGROUND = Color.rgb(16, 21, 31);
    private static final int CARD = Color.rgb(26, 34, 48);
    private static final int MINT = Color.rgb(101, 230, 196);
    private static final int TEXT = Color.rgb(240, 245, 250);
    private static final int MUTED = Color.rgb(169, 183, 202);
    private SharedPreferences preferences;
    private TextView status, detail, modelStatus, reply, permissionStatus;
    private Button listenButton, modelButton;
    private Switch screenControlSwitch;
    private ProgressBar progress;
    private EditText command;
    private TextToSpeech speaker;
    private boolean ttsReady;
    private boolean bundledModel;
    private boolean downloadInProgress;
    private String modelError;
    private String lastServiceReply = "";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable refreshVisibleState = new Runnable() {
        @Override public void run() {
            refresh();
            main.postDelayed(this, 1000);
        }
    };
    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { refresh(); }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = getSharedPreferences("cosmo", MODE_PRIVATE);
        bundledModel = VoiceModelManager.hasBundledModel(this);
        if (state != null) modelError = state.getString("model_error");
        buildInterface();
        speaker = new TextToSpeech(this, result -> {
            if (result == TextToSpeech.SUCCESS && speaker != null) {
                int supported = speaker.setLanguage(Locale.forLanguageTag("tr-TR"));
                if (supported != TextToSpeech.LANG_MISSING_DATA
                        && supported != TextToSpeech.LANG_NOT_SUPPORTED && speaker.getVoices() != null) {
                    for (Voice voice : speaker.getVoices()) {
                        if (voice.getLocale().getLanguage().equals("tr") && !voice.isNetworkConnectionRequired()
                                && speaker.setVoice(voice) == TextToSpeech.SUCCESS) {
                            ttsReady = true;
                            break;
                        }
                    }
                }
            }
        });
        IntentFilter filter = new IntentFilter(CosmoVoiceService.ACTION_STATE_CHANGED);
        ContextCompat.registerReceiver(this, stateReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
        refresh();
    }

    private void buildInterface() {
        LinearLayout frame = new LinearLayout(this);
        frame.setOrientation(LinearLayout.VERTICAL);
        frame.setBackgroundColor(BACKGROUND);
        frame.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(22), dp(22), dp(22), dp(32));
        scroll.addView(page);
        frame.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
        setContentView(frame);

        TextView eyebrow = text("KİŞİSEL SESLİ DOSTUN  /  0.2.0", 11, MINT);
        eyebrow.setLetterSpacing(.15f);
        page.addView(eyebrow);
        TextView title = text("FERİ", 44, TEXT);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setLetterSpacing(.1f);
        page.addView(title);
        TextView intro = text("Bir ses kadar yakın.", 21, MUTED);
        page.addView(intro);
        space(page, 24);

        LinearLayout voice = card(page);
        status = text("●  Dinleme kapalı", 21, MINT);
        status.setId(R.id.listening_status);
        voice.addView(status);
        detail = text("Feri diyerek seslen; kısa sinyalden sonra komutunu söyle.", 15, MUTED);
        spaced(voice, detail);
        listenButton = button("Dinlemeyi başlat", true);
        listenButton.setId(R.id.start_listening);
        listenButton.setOnClickListener(v -> toggleListening());
        spaced(voice, listenButton);
        TextView listeningNote = text("Dinleme açıkken mikrofon bildirimi görünür. Ekran kapalıyken de dinlemeyi sürdürür; telefonun pil yönetimi servisi durdurabilir.", 12, MUTED);
        spaced(voice, listeningNote);

        LinearLayout model = card(page);
        model.addView(text("01  /  Türkçe ses modeli", 17, TEXT));
        modelStatus = text(modelSetupDescription(), 14, MUTED);
        spaced(model, modelStatus);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setVisibility(View.GONE);
        spaced(model, progress);
        modelButton = button(bundledModel ? "Ses modellerini hazırla" : "Ses modellerini indir", false);
        modelButton.setId(R.id.model_download);
        modelButton.setOnClickListener(v -> {
            if (downloadInProgress) {
                VoiceModelManager.cancelDownload();
            } else new AlertDialog.Builder(this).setTitle(bundledModel ? "Ses modellerini hazırla" : "Ses modellerini indir")
                    .setMessage(bundledModel
                            ? "Feri seslenişi ve Türkçe komut modelleri uygulamayla gelir; internet gerekmez. İki model telefonda açılıp doğrulanır. En az 170 MB boş alan gerekir. Hazırlanırken bu ekranı açık tut."
                            : "Vosk Türkçe modeli alphacephei.com adresinden indirilir (yaklaşık 35 MB). İndirme sırasında bu ekranı açık tut. Model hazır olunca sesin yalnızca cihazda işlenir.")
                    .setPositiveButton(bundledModel ? "Hazırla" : "İndir", (dialog, which) -> downloadModel())
                    .setNegativeButton("Vazgeç", null).show();
        });
        spaced(model, modelButton);

        LinearLayout test = card(page);
        test.addView(text("02  /  Bir komut dene", 17, TEXT));
        spaced(test, text("Modeli hazırlamadan da yazılı komutları deneyebilirsin.", 14, MUTED));
        command = new EditText(this);
        command.setId(R.id.command_input);
        command.setTextColor(TEXT);
        command.setHintTextColor(MUTED);
        command.setTextSize(16);
        command.setHint("Örn. saat kaç veya not al süt al");
        command.setMaxLines(4);
        command.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        spaced(test, command);
        Button send = button("Komutu çalıştır", false);
        send.setId(R.id.command_send);
        send.setOnClickListener(v -> executeTypedCommand());
        spaced(test, send);
        reply = text("Hazırım. Bir komut yazabilirsin.", 15, MINT);
        reply.setId(R.id.response_text);
        reply.setTextIsSelectable(true);
        spaced(test, reply);

        LinearLayout access = card(page);
        access.addView(text("03  /  Senin kontrolünde", 17, TEXT));
        permissionStatus = text("", 13, MUTED);
        spaced(access, permissionStatus);
        Button torchPermission = button("Fener için kamera izni", false);
        torchPermission.setOnClickListener(v -> requestPermissions(new String[]{Manifest.permission.CAMERA}, 102));
        spaced(access, torchPermission);
        Button contactsPermission = button("Rehber ve sesli arama izinleri", false);
        contactsPermission.setOnClickListener(v -> requestPermissions(
                new String[]{Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE}, 103));
        spaced(access, contactsPermission);
        Button displayPermission = button("Parlaklık ve döndürme izni", false);
        displayPermission.setOnClickListener(v -> open(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                Uri.parse("package:" + getPackageName()))));
        spaced(access, displayPermission);
        Switch navigation = new Switch(this);
        navigation.setText(R.string.navigation_opt_in);
        navigation.setTextColor(TEXT);
        navigation.setTextSize(15);
        navigation.setMinHeight(dp(52));
        navigation.setChecked(preferences.getBoolean("accessibility_actions_enabled", false));
        navigation.setOnCheckedChangeListener((view, enabled) -> {
            if (!enabled) {
                preferences.edit().putBoolean("accessibility_actions_enabled", false)
                        .putBoolean("screen_control_enabled", false).apply();
                if (screenControlSwitch != null) screenControlSwitch.setChecked(false);
                new PhoneFeatures(this).execute(new Command(Command.Type.CANCEL));
                return;
            }
            new AlertDialog.Builder(this).setTitle("Sesli gezinmeye izin ver")
                    .setMessage("FERİ; geri, ana ekran, son uygulamalar, bildirimler ve kaydırma komutlarını uygular. Bu özellik ekranda kaydırma hareketi yapabilir. Ekran okuma ve denetimi ayrı anahtarla açılır. Bu anahtar yalnız gezinmeye ve sesle uygulama açmaya izin verir; izin düğmelerine basmaz.\n\nDevam edersen Android Erişilebilirlik ayarlarında FERİ'yi kendin etkinleştirmelisin. Bu anahtarı kapatarak FERİ'nin gezinme komutlarını hemen durdurabilirsin.")
                    .setPositiveButton("Ayarları aç", (dialog, which) -> {
                        preferences.edit().putBoolean("accessibility_actions_enabled", true).apply();
                        open(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                    }).setNegativeButton("Vazgeç", (dialog, which) -> navigation.setChecked(false))
                    .setOnCancelListener(dialog -> navigation.setChecked(false)).show();
        });
        spaced(access, navigation);
        spaced(access, text("Android erişilebilirlik izni ayrıca açılmalıdır. Telefon kilitliyken gezinme komutları çalışmaz.", 12, MUTED));
        screenControlSwitch = new Switch(this);
        screenControlSwitch.setText(R.string.screen_control_opt_in);
        screenControlSwitch.setTextColor(TEXT);
        screenControlSwitch.setTextSize(15);
        screenControlSwitch.setMinHeight(dp(52));
        screenControlSwitch.setChecked(preferences.getBoolean("screen_control_enabled", false));
        screenControlSwitch.setOnCheckedChangeListener((view, enabled) -> {
            if (!enabled) {
                preferences.edit().putBoolean("screen_control_enabled", false).apply();
                new PhoneFeatures(this).execute(new Command(Command.Type.CANCEL));
                return;
            }
            if (!preferences.getBoolean("accessibility_actions_enabled", false)) {
                screenControlSwitch.setChecked(false);
                showReply("Önce Sesli gezinme anahtarını açıp Android erişilebilirlik ayarlarında FERİ'ye izin ver.");
                return;
            }
            new AlertDialog.Builder(this).setTitle("Ekran okuma ve denetimine izin ver")
                    .setMessage("Bu özellik, açık komutun üzerine etkin uygulamanın görünen yazılarını okuyabilir ve seslendirebilir. Söylediğin düğmeye basabilir veya seçili alana metin yazabilir. Tıklama ve yazma için önce ne yapılacağını söyler; yeniden Feri diyerek onayla demeni bekler.\n\nEkran yazıları kaydedilmez veya sunucuya gönderilmez. Şifre alanları ve Android izin/güvenlik ekranları denetlenmez. Bu anahtarı kapatınca özellik hemen kapanır.")
                    .setPositiveButton("İzin ver", (dialog, which) -> {
                        preferences.edit().putBoolean("screen_control_enabled", true).apply();
                        if (!CosmoAccessibilityService.isAvailable(this)) open(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                    }).setNegativeButton("Vazgeç", (dialog, which) -> screenControlSwitch.setChecked(false))
                    .setOnCancelListener(dialog -> screenControlSwitch.setChecked(false)).show();
        });
        spaced(access, screenControlSwitch);
        spaced(access, text("Ekranı oku · Ekranda Kaydet düğmesine bas · Ekrana yaz Merhaba. Ekran veya alıcı onay sırasında değişirse işlem iptal edilir.", 12, MUTED));
        Button battery = button("Pil optimizasyonu ayarları", false);
        battery.setOnClickListener(v -> open(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)));
        spaced(access, battery);
        Button settings = button("FERİ uygulama izinleri", false);
        settings.setOnClickListener(v -> open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName()))));
        spaced(access, settings);

        LinearLayout examples = card(page);
        examples.addView(text("Neler söyleyebilirsin?", 18, TEXT));
        spaced(examples, text("Önce Feri de ve sinyali bekle. Ardından:\n“saat kaç”\n“pil durumu”\n“feneri aç”\n“sesi artır”\n“not al yarın süt al”\n“notlarımı oku”\n“YouTube aç”\n“kendini aç”\n“Wi-Fi ayarları”\n“07:30 alarm kur”\n“5 dakika zamanlayıcı kur”\n“Ankara haritada ara”\n“dinlemeyi durdur”", 15, MUTED));
        spaced(examples, text("Sinyalden sonra ayrıca: Parlaklığı yüzde elli yap · Otomatik döndürmeyi aç · Ekran görüntüsü al · Ekranı kilitle · Ahmet kişisini ara · Takvime toplantı ekle · Kamerayı aç · Ekranı oku · Durumunu söyle · Merhaba", 15, MUTED));
        spaced(examples, text("Feri de, kısa sinyali bekle ve komutunu sonraki 10 saniyede söyle. Her komuttan önce yeniden Feri diye seslen.", 12, MUTED));
        spaced(examples, text("Doğrudan arama, ekrandaki düğmeye basma ve metin yazma sesli onay ister: yeniden Feri de, sinyalden sonra onayla söyle. Erişilebilirlik açıkken ekranlar sesle açılır; izin kapalıysa işlem bildirimine dokun. Android kilidi korunur.", 12, MUTED));
        space(page, 12);
        page.addView(text("Hesap gerekmez. Notlar bu cihazda saklanır.\nBu sürüm belirli komutları uygular; serbest sohbet içermez.", 12, MUTED));
    }

    private void toggleListening() {
        if (CosmoVoiceService.running) {
            stopService(new Intent(this, CosmoVoiceService.class));
            listenButton.postDelayed(this::refresh, 200);
            return;
        }
        if (!VoiceModelManager.isReady(this)) {
            showReply((bundledModel ? "Önce ses modellerini hazırla." : "Önce ses modellerini indir.")
                    + " Yazılı komutları hemen kullanabilirsin.");
            return;
        }
        ArrayList<String> needed = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.POST_NOTIFICATIONS);
        if (!needed.isEmpty()) requestPermissions(needed.toArray(new String[0]), 101);
        else startListening();
    }

    private void startListening() {
        try {
            if (speaker != null) speaker.stop();
            startForegroundService(new Intent(this, CosmoVoiceService.class).setAction(CosmoVoiceService.ACTION_START));
            listenButton.postDelayed(this::refresh, 250);
        } catch (RuntimeException exception) {
            showReply("Dinleme başlatılamadı. Mikrofon iznini ve uygulama ayarlarını kontrol et.");
        }
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        refresh();
        if (code == 101) {
            boolean granted = results.length > 0;
            for (int result : results) granted &= result == PackageManager.PERMISSION_GRANTED;
            if (granted) startListening();
            else showReply("Sürekli dinleme için mikrofon ve bildirim izinlerini açmalısın. İzinleri aşağıdaki uygulama ayarlarından değiştirebilirsin.");
        }
    }

    private void downloadModel() {
        modelError = null;
        downloadInProgress = true;
        modelButton.setText(bundledModel ? R.string.cancel_model_setup : R.string.cancel_download);
        progress.setVisibility(View.VISIBLE);
        progress.setIndeterminate(true);
        modelStatus.setText(R.string.preparing_model);
        VoiceModelManager.download(this, createModelListener());
    }

    private VoiceModelManager.Listener createModelListener() {
        return new VoiceModelManager.Listener() {
            @Override public void onProgress(int percent) {
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    progress.setIndeterminate(percent < 0);
                    if (percent >= 0) progress.setProgress(percent);
                    modelStatus.setText(percent >= 100 ? "✓ Türkçe model doğrulandı."
                            : percent < 0 || percent >= 90 ? "Türkçe model açılıyor ve doğrulanıyor…"
                            : "Türkçe model indiriliyor: %" + percent);
                });
            }
            @Override public void onComplete() {
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    downloadInProgress = false;
                    progress.setVisibility(View.GONE);
                    refresh();
                    showReply("Türkçe model hazır. Dinlemeyi başlatabilirsin.");
                });
            }
            @Override public void onError(String message) {
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    downloadInProgress = false;
                    progress.setVisibility(View.GONE);
                    modelError = message;
                    refresh();
                });
            }
        };
    }

    private void executeTypedCommand() {
        String text = command.getText().toString().trim();
        if (text.isEmpty()) { command.setError("Bir komut yaz"); return; }
        DeviceActions.Result result = new DeviceActions(this).execute(CommandParser.parse(text));
        showReply(result.speech);
        if (ttsReady && !CosmoVoiceService.running) {
            String spokenReply = result.speech.substring(0,
                    Math.min(result.speech.length(), TextToSpeech.getMaxSpeechInputLength() - 1));
            speaker.speak(spokenReply, TextToSpeech.QUEUE_FLUSH, null, "typed-command");
        }
        if (result.stopListening) {
            stopService(new Intent(this, CosmoVoiceService.class));
            listenButton.postDelayed(this::refresh, 200);
        }
        if (result.intent != null) {
            if (Intent.ACTION_DIAL.equals(result.intent.getAction()) || Intent.ACTION_SENDTO.equals(result.intent.getAction())) {
                new AlertDialog.Builder(this).setTitle("Telefon ekranını aç")
                        .setMessage(result.speech).setPositiveButton("Aç", (dialog, which) -> open(result.intent))
                        .setNegativeButton("Vazgeç", null).show();
            } else open(result.intent);
        }
    }

    private void refresh() {
        if (status == null) return;
        boolean running = CosmoVoiceService.running;
        boolean listening = running && preferences.getBoolean("listening", false);
        status.setText(listening ? "●  FERİ dinliyor" : running ? "◌  Ses sistemi hazırlanıyor" : "○  Dinleme kapalı");
        listenButton.setText(running ? "Dinlemeyi durdur" : "Dinlemeyi başlat");
        String serviceStatus = preferences.getString("voice_status", "");
        detail.setText(!serviceStatus.isEmpty() ? serviceStatus
                : "Feri diyerek seslen; kısa sinyalden sonra komutunu söyle.");
        boolean ready = VoiceModelManager.isReady(this);
        boolean wasDownloading = downloadInProgress;
        downloadInProgress = VoiceModelManager.isDownloading();
        if (downloadInProgress) {
            modelButton.setText(bundledModel ? R.string.cancel_model_setup : R.string.cancel_download);
            modelButton.setEnabled(true);
            progress.setVisibility(View.VISIBLE);
            if (!wasDownloading) {
                progress.setIndeterminate(true);
                modelStatus.setText(bundledModel ? "Türkçe model hazırlanıyor…" : getString(R.string.downloading_model));
            }
        } else {
            progress.setVisibility(View.GONE);
            modelStatus.setText(ready ? "✓ Türkçe model hazır. Ses çözümleme bu cihazda yapılır."
                    : modelError != null ? modelError
                    : modelSetupDescription());
            modelButton.setText(ready ? "Türkçe model hazır" : bundledModel ? "Ses modellerini hazırla" : "Ses modellerini indir");
            modelButton.setEnabled(!ready);
        }
        String last = preferences.getString("last_reply", "");
        if (running && !last.isEmpty() && !last.equals(lastServiceReply)) {
            lastServiceReply = last;
            reply.setText(last);
        }
        boolean mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        boolean camera = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        permissionStatus.setText(getString(R.string.permission_status,
                getString(mic ? R.string.allowed : R.string.not_allowed_yet),
                getString(camera ? R.string.allowed : R.string.optional),
                getString(CosmoAccessibilityService.isAvailable(this) ? R.string.enabled : R.string.disabled)));
    }

    private void showReply(String message) { reply.setText(message); }

    private String modelSetupDescription() {
        return bundledModel
                ? "İnternet gerekmez; Feri seslenişi ve Türkçe komut modelleri uygulamayla gelir. İlk kullanımda ikisini hazırla. En az 170 MB boş alan gerekir."
                : "İlk kullanımda yaklaşık 35 MB indirilir. Sonraki dinlemeler internetsizdir; seslerin sunucuya gönderilmez.";
    }

    private void open(Intent intent) {
        try {
            if (!CosmoAccessibilityService.openExternalActivity(this, intent)) startActivity(intent);
        }
        catch (ActivityNotFoundException | SecurityException exception) {
            Toast.makeText(this, "Bu işlemi açabilecek bir uygulama veya izin bulunamadı.", Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onResume() {
        super.onResume();
        main.removeCallbacks(refreshVisibleState);
        main.post(refreshVisibleState);
    }

    @Override protected void onPause() {
        main.removeCallbacks(refreshVisibleState);
        super.onPause();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("model_error", modelError);
        super.onSaveInstanceState(state);
    }

    @Override protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        unregisterReceiver(stateReceiver);
        if (speaker != null) { speaker.stop(); speaker.shutdown(); }
        super.onDestroy();
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextColor(color);
        view.setTextSize(size);
        view.setLineSpacing(dp(3), 1.1f);
        return view;
    }

    private Button button(String title, boolean primary) {
        Button button = new Button(this);
        button.setText(title);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setTextColor(primary ? BACKGROUND : MINT);
        button.setMinHeight(dp(52));
        button.setPadding(dp(16), dp(10), dp(16), dp(10));
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(primary ? MINT : Color.rgb(34, 49, 63));
        shape.setCornerRadius(dp(14));
        button.setBackground(shape);
        button.setGravity(Gravity.CENTER);
        return button;
    }

    private LinearLayout card(LinearLayout parent) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(18), dp(18), dp(18), dp(18));
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(CARD);
        shape.setCornerRadius(dp(22));
        layout.setBackground(shape);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(16);
        parent.addView(layout, params);
        return layout;
    }

    private void spaced(LinearLayout parent, View view) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(12);
        parent.addView(view, params);
    }

    private void space(LinearLayout parent, int height) {
        parent.addView(new View(this), new LinearLayout.LayoutParams(1, dp(height)));
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
