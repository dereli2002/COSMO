package com.cosmo.assistant;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.KeyguardManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.IOException;
import java.util.Locale;
import java.util.Set;

/** User-started, visible microphone service. Recognition is entirely on the phone. */
public final class CosmoVoiceService extends Service {
    public static final String ACTION_START = "com.cosmo.assistant.START_LISTENING";
    public static final String ACTION_STOP = "com.cosmo.assistant.STOP_LISTENING";
    public static final String ACTION_STATE_CHANGED = "com.cosmo.assistant.STATE_CHANGED";
    public static final String PREFS = "cosmo";
    public static final String KEY_STATUS = "voice_status";
    public static final String KEY_LAST_HEARD = "last_heard";
    public static final String KEY_LAST_REPLY = "last_reply";
    public static final String KEY_LISTENING = "listening";
    public static volatile boolean running = false;

    private static final String CHANNEL_LISTENING = "cosmo_listening";
    private static final String CHANNEL_ACTIONS = "cosmo_actions";
    private static final int NOTIFICATION_LISTENING = 1001;
    private static final int NOTIFICATION_ACTION = 1002;
    private static final String WAITING = "Feri çağrısını bekliyor.";
    private static final long ACKNOWLEDGEMENT_MS = 200L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final WakeWordGate gate = new WakeWordGate();
    private volatile boolean destroyed;
    private boolean starting;
    private boolean listening;
    private boolean speaking;
    private boolean ttsReady;
    private boolean stopAfterSpeech;
    private boolean failed;
    private long utteranceNumber;
    private String currentUtterance;
    private SharedPreferences preferences;
    private NotificationManager notificationManager;
    private PowerManager.WakeLock wakeLock;
    private Model model;
    private Model wakeModel;
    private Recognizer wakeRecognizer;
    private Recognizer commandRecognizer;
    private ToneGenerator acknowledgement;
    private LocalSpeechLoop speechService;
    private TextToSpeech tts;

    private final Runnable wakeTimeout = () -> {
        if (!destroyed && listening && !speaking) returnToWake();
    };
    private final Runnable beginCommandAfterChime = () -> {
        if (destroyed || !listening || speaking || speechService == null) return;
        gate.armFeri(SystemClock.elapsedRealtime());
        speechService.route(SpeechRouting.Stage.COMMAND);
        updateStatus("Dinliyorum. Komutunu 10 saniye içinde söyle.");
        main.removeCallbacks(wakeTimeout);
        main.postDelayed(wakeTimeout, WakeWordGate.COMMAND_WINDOW_MS + 1L);
    };
    private final Runnable speechWatchdog = () -> {
        String timedOutUtterance = currentUtterance;
        if (tts != null) tts.stop();
        main.postDelayed(() -> finishSpeech(timedOutUtterance), 350);
    };
    private final Runnable renewWakeLock = new Runnable() {
        @Override public void run() {
            if (!destroyed && !failed && wakeLock != null) {
                wakeLock.acquire(10 * 60_000L);
                main.postDelayed(this, 5 * 60_000L);
            }
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        running = true;
        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        notificationManager = getSystemService(NotificationManager.class);
        NotificationChannel listeningChannel = new NotificationChannel(CHANNEL_LISTENING,
                "Feri sürekli dinleme", NotificationManager.IMPORTANCE_LOW);
        listeningChannel.setDescription("Mikrofon açıkken sürekli görünür. Durdur düğmesi dinlemeyi kapatır.");
        listeningChannel.setShowBadge(false);
        notificationManager.createNotificationChannel(listeningChannel);
        NotificationChannel actions = new NotificationChannel(CHANNEL_ACTIONS,
                "Feri işlem onayı", NotificationManager.IMPORTANCE_DEFAULT);
        actions.setDescription("Uygulama veya sistem ekranı açmak için dokunman gereken işlemler.");
        notificationManager.createNotificationChannel(actions);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            listening = false;
            if (speechService != null) speechService.route(SpeechRouting.Stage.PAUSED);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (starting || listening) return START_NOT_STICKY;
        starting = true;
        try {
            Notification notification = listeningNotification("Çevrimdışı ses sistemi hazırlanıyor…");
            if (Build.VERSION.SDK_INT >= 30) {
                startForeground(NOTIFICATION_LISTENING, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else {
                startForeground(NOTIFICATION_LISTENING, notification);
            }
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                fail("Dinleme başlamadı. Feri'yi açıp mikrofon iznini ver.");
                return START_NOT_STICKY;
            }
            if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_MICROPHONE)) {
                fail("Bu cihazda kullanılabilir mikrofon bulunamadı.");
                return START_NOT_STICKY;
            }
            if (!VoiceModelManager.isReady(this)) {
                fail("Önce Feri ekranından ses modellerini hazırla.");
                return START_NOT_STICKY;
            }
            PowerManager power = getSystemService(PowerManager.class);
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, getPackageName() + ":Listening");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire(10 * 60_000L);
            main.postDelayed(renewWakeLock, 5 * 60_000L);
            preferences.edit().putBoolean(KEY_LISTENING, false).apply();
            updateStatus("Çevrimdışı ses modelleri açılıyor…");
            initializeTts();
            loadModel();
        } catch (RuntimeException exception) {
            fail("Android dinlemeyi başlatamadı. Feri'yi açık ekrandan başlatıp mikrofon ve bildirim izinlerini kontrol et.");
        }
        return START_NOT_STICKY;
    }

    private void loadModel() {
        new Thread(() -> {
            Model loadedWakeModel = null;
            Model loadedCommandModel = null;
            Recognizer loadedWake = null;
            Recognizer loadedCommand = null;
            try {
                // The English model contains "ferry", the phonetic equivalent of the name Feri.
                // The Turkish model handles commands. Native setup stays off the main thread;
                // once started, only the single audio loop uses either recognizer.
                loadedWakeModel = new Model(VoiceModelManager.wakeModelDirectory(this).getAbsolutePath());
                loadedCommandModel = new Model(VoiceModelManager.modelDirectory(this).getAbsolutePath());
                loadedWake = new Recognizer(loadedWakeModel, 16_000.0f, "[\"ferry\",\"perry\",\"[unk]\"]");
                loadedWake.setWords(true);
                loadedCommand = new Recognizer(loadedCommandModel, 16_000.0f);
                Model readyWakeModel = loadedWakeModel;
                Model readyCommandModel = loadedCommandModel;
                Recognizer readyWake = loadedWake;
                Recognizer readyCommand = loadedCommand;
                main.post(() -> {
                    if (destroyed) {
                        closeNativeResources(readyWake, readyCommand, readyWakeModel, readyCommandModel);
                        return;
                    }
                    wakeModel = readyWakeModel;
                    model = readyCommandModel;
                    wakeRecognizer = readyWake;
                    commandRecognizer = readyCommand;
                    startRecognition();
                });
            } catch (IOException | RuntimeException | LinkageError error) {
                closeNativeResources(loadedWake, loadedCommand, loadedWakeModel, loadedCommandModel);
                main.post(() -> {
                    if (!destroyed) fail("Dil modeli açılamadı. Depolama alanını ve cihaz uyumluluğunu kontrol et; modeli yeniden kurmayı deneyebilirsin.");
                });
            }
        }, "cosmo-model-loader").start();
    }

    private static void closeNativeResources(Recognizer wake, Recognizer command, Model wakeModel, Model commandModel) {
        if (wake != null) wake.close();
        if (command != null) command.close();
        if (wakeModel != null) wakeModel.close();
        if (commandModel != null) commandModel.close();
    }

    private void startRecognition() {
        try {
            speechService = new LocalSpeechLoop(wakeRecognizer, commandRecognizer, main);
            if (!speechService.startListening(this)) throw new IOException("Recorder did not start");
            starting = false;
            listening = true;
            preferences.edit().putBoolean(KEY_LISTENING, true).apply();
            updateStatus(WAITING);
        } catch (IOException | RuntimeException | LinkageError error) {
            fail("Mikrofon açılamadı. Başka bir uygulama kullanıyor olabilir; izni kontrol edip yeniden başlat.");
        }
    }

    private void initializeTts() {
        tts = new TextToSpeech(this, status -> main.post(() -> {
            if (destroyed || tts == null) return;
            try {
                if (status == TextToSpeech.SUCCESS) {
                    Locale turkish = Locale.forLanguageTag("tr-TR");
                    int language = tts.setLanguage(turkish);
                    Set<Voice> voices = tts.getVoices();
                    if (language != TextToSpeech.LANG_MISSING_DATA && language != TextToSpeech.LANG_NOT_SUPPORTED
                            && voices != null) {
                        for (Voice voice : voices) {
                            if (voice.getLocale().getLanguage().equals("tr") && !voice.isNetworkConnectionRequired()
                                    && tts.setVoice(voice) == TextToSpeech.SUCCESS) {
                                ttsReady = true;
                                break;
                            }
                        }
                    }
                    tts.setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
                    tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                        @Override public void onStart(String utteranceId) { }
                        @Override public void onDone(String utteranceId) {
                            main.postDelayed(() -> finishSpeech(utteranceId), 350);
                        }
                        @Override public void onError(String utteranceId) {
                            main.postDelayed(() -> finishSpeech(utteranceId), 350);
                        }
                        @Override public void onError(String utteranceId, int errorCode) {
                            onError(utteranceId);
                        }
                    });
                }
            } catch (RuntimeException exception) { ttsReady = false; }
            if (!ttsReady) {
                preferences.edit().putString(KEY_LAST_REPLY,
                        "Telefonda çevrimdışı Türkçe ses bulunamadı. Yanıtları ekrandan okuyabilirsin; sistemin metin okuma ayarlarından Türkçe ses yükleyebilirsin.").apply();
                broadcastState();
            }
        }));
    }

    private void handleFinalTranscript(SpeechRouting.Stage stage, String hypothesis) {
        if (destroyed || !listening || speaking) return;
        JSONObject result;
        String transcript;
        try {
            result = new JSONObject(hypothesis);
            transcript = result.optString("text", "").trim();
        } catch (JSONException exception) { return; }
        if (transcript.isEmpty()) return;
        if (stage == SpeechRouting.Stage.WAKE) {
            JSONArray words = result.optJSONArray("result");
            JSONObject word = words != null && words.length() == 1 ? words.optJSONObject(0) : null;
            if (word == null || !"ferry".equals(word.optString("word"))
                    || !WakeWordGate.isFeriWake(transcript, word.optDouble("conf", Double.NaN))) return;
            // Stop routing audio before the acknowledgement so neither recognizer hears the tone.
            gate.reset();
            speechService.route(SpeechRouting.Stage.PAUSED);
            preferences.edit().putString(KEY_LAST_HEARD, "Feri").apply();
            updateStatus("Feri burada. Sinyalden sonra komutunu söyle.");
            try {
                if (acknowledgement == null) acknowledgement = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 55);
                acknowledgement.startTone(ToneGenerator.TONE_PROP_ACK, 140);
            } catch (RuntimeException ignored) {
                // A muted/unavailable tone does not prevent the visible command window.
            }
            main.removeCallbacks(beginCommandAfterChime);
            main.postDelayed(beginCommandAfterChime, ACKNOWLEDGEMENT_MS);
            return;
        }
        if (stage != SpeechRouting.Stage.COMMAND) return;
        long now = SystemClock.elapsedRealtime();
        String command = gate.acceptCommand(transcript, now);
        if (command == null) {
            if (!gate.isAwaitingCommand(now)) returnToWake();
            return;
        }
        main.removeCallbacks(wakeTimeout);
        speechService.route(SpeechRouting.Stage.PAUSED);
        preferences.edit().putString(KEY_LAST_HEARD, transcript).apply();
        Command parsed = CommandParser.parse(command);
        if (parsed.type == Command.Type.CANCEL) notificationManager.cancel(NOTIFICATION_ACTION);
        DeviceActions.Result resultAction = new DeviceActions(this).execute(parsed);
        String reply = resultAction.speech;
        if (resultAction.intent != null && !CosmoAccessibilityService.openExternalActivity(this, resultAction.intent)) {
            if (postActionNotification(resultAction.intent)) {
                reply += " Devam etmek için Feri işlem bildirimine dokun.";
            } else {
                reply += " İşlem bildirimi açılamadı. Feri ekranındaki yazılı komut alanından tekrar deneyebilirsin.";
            }
        }
        if ((parsed.type == Command.Type.NOTE_LIST || parsed.type == Command.Type.SCREEN_READ) && phoneLocked()) {
            reply = "Bu bilgiyi okumak için önce telefonun kilidini açmalısın.";
        }
        // Screen content is ephemeral: only the generic result enters saved UI state.
        preferences.edit().putString(KEY_LAST_REPLY, parsed.type == Command.Type.SCREEN_READ
                ? "Ekran okuma komutu işlendi." : reply).apply();
        broadcastState();
        speak(reply, resultAction.stopListening);
    }

    private void returnToWake() {
        gate.reset();
        main.removeCallbacks(wakeTimeout);
        main.removeCallbacks(beginCommandAfterChime);
        if (speechService != null) speechService.route(SpeechRouting.Stage.WAKE);
        updateStatus(WAITING);
    }

    private boolean phoneLocked() {
        KeyguardManager keyguard = getSystemService(KeyguardManager.class);
        return keyguard == null || keyguard.isDeviceLocked() || keyguard.isKeyguardLocked();
    }

    private boolean postActionNotification(Intent target) {
        // A direct Activity PendingIntent is activated by the user's tap. No background launch or lock bypass.
        try {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) return false;
            target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent action = PendingIntent.getActivity(this, 2, target,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification notification = new Notification.Builder(this, CHANNEL_ACTIONS)
                    .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                    .setContentTitle("Feri işlemi hazır")
                    .setContentText("Devam etmek için dokun")
                    .setStyle(new Notification.BigTextStyle().bigText("İstenen uygulama veya sistem ekranını açmak için dokun."))
                    .setContentIntent(action).setAutoCancel(true)
                    .setVisibility(Notification.VISIBILITY_PRIVATE).build();
            notificationManager.notify(NOTIFICATION_ACTION, notification);
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private void speak(String reply, boolean stopWhenDone) {
        if (!ttsReady || tts == null) {
            if (stopWhenDone) {
                listening = false;
                stopSelf();
            } else returnToWake();
            return;
        }
        speaking = true;
        stopAfterSpeech = stopWhenDone;
        gate.reset();
        main.removeCallbacks(wakeTimeout);
        main.removeCallbacks(beginCommandAfterChime);
        if (speechService != null) speechService.route(SpeechRouting.Stage.PAUSED);
        currentUtterance = "cosmo-" + (++utteranceNumber);
        updateStatus("Yanıt veriyor…");
        String text = reply.length() > TextToSpeech.getMaxSpeechInputLength()
                ? reply.substring(0, TextToSpeech.getMaxSpeechInputLength() - 1) : reply;
        int result;
        try { result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, currentUtterance); }
        catch (RuntimeException exception) { ttsReady = false; result = TextToSpeech.ERROR; }
        main.removeCallbacks(speechWatchdog);
        if (result == TextToSpeech.ERROR) {
            finishSpeech(currentUtterance);
        } else {
            // A broken TTS engine must not leave the listener paused indefinitely.
            main.postDelayed(speechWatchdog, Math.min(120_000L, 5_000L + text.length() * 90L));
        }
    }

    private void finishSpeech(String utteranceId) {
        if (destroyed || !speaking || utteranceId == null || !utteranceId.equals(currentUtterance)) return;
        main.removeCallbacks(speechWatchdog);
        speaking = false;
        currentUtterance = null;
        if (stopAfterSpeech) {
            stopSelf();
            return;
        }
        returnToWake();
    }

    private void onError(Exception exception) {
        if (!destroyed) fail("Mikrofon dinlemesi kesildi. Mikrofonu kullanan uygulamaları kapatıp Feri'den yeniden başlat.");
    }

    private Notification listeningNotification(String status) {
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openApp = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, CosmoVoiceService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_LISTENING)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Feri • mikrofon açık")
                .setContentText(status).setContentIntent(openApp)
                .setOngoing(true).setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(new Notification.Action.Builder(null, "Dinlemeyi durdur", stop).build())
                .build();
    }

    private void updateStatus(String status) {
        if (destroyed) return;
        preferences.edit().putString(KEY_STATUS, status).apply();
        notificationManager.notify(NOTIFICATION_LISTENING, listeningNotification(status));
        broadcastState();
    }

    private void broadcastState() {
        sendBroadcast(new Intent(ACTION_STATE_CHANGED).setPackage(getPackageName()));
    }

    private void fail(String explanation) {
        if (destroyed) return;
        failed = true;
        listening = false;
        if (speechService != null) speechService.route(SpeechRouting.Stage.PAUSED);
        preferences.edit().putString(KEY_STATUS, explanation).putBoolean(KEY_LISTENING, false).apply();
        broadcastState();
        stopSelf();
    }

    @Override public void onDestroy() {
        destroyed = true;
        running = false;
        listening = false;
        gate.reset();
        main.removeCallbacks(wakeTimeout);
        main.removeCallbacks(beginCommandAfterChime);
        main.removeCallbacks(speechWatchdog);
        main.removeCallbacks(renewWakeLock);
        notificationManager.cancel(NOTIFICATION_ACTION);
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
        }
        if (acknowledgement != null) {
            acknowledgement.release();
            acknowledgement = null;
        }
        Recognizer retiringWake = wakeRecognizer;
        Recognizer retiringCommand = commandRecognizer;
        Model retiringModel = model;
        Model retiringWakeModel = wakeModel;
        Runnable closeNativeResources = () -> closeNativeResources(retiringWake, retiringCommand, retiringWakeModel, retiringModel);
        if (speechService != null) {
            // Never close native objects while an AudioRecord/recognizer call is still using them.
            speechService.shutdown(closeNativeResources);
            speechService = null;
        } else {
            closeNativeResources.run();
        }
        wakeRecognizer = null;
        commandRecognizer = null;
        model = null;
        wakeModel = null;
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        SharedPreferences.Editor edit = preferences.edit().putBoolean(KEY_LISTENING, false);
        if (!failed) edit.putString(KEY_STATUS, "Dinleme durduruldu.");
        edit.apply();
        stopForeground(STOP_FOREGROUND_REMOVE);
        broadcastState();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    /**
     * A bounded PCM loop around Vosk. Unlike the library's convenience recorder, microphone
     * failures are delivered to the service and cannot escape as uncaught audio-thread exceptions.
     * Only this thread decodes or resets the recognizers; shutdown joins it before closing native resources.
     */
    private static final class LocalSpeechLoop {
        private static final int SAMPLE_RATE = 16_000;
        private static final int FRAME_SAMPLES = 320;
        private final Recognizer wakeRecognizer;
        private final Recognizer commandRecognizer;
        private final AudioRecord recorder;
        private final Handler main;
        private volatile boolean active;
        private final SpeechRouting routing = new SpeechRouting();
        private Thread audioThread;

        @SuppressLint("MissingPermission") // The service verifies RECORD_AUDIO before constructing this loop.
        @SuppressWarnings("deprecation")
        LocalSpeechLoop(Recognizer wakeRecognizer, Recognizer commandRecognizer, Handler main) throws IOException {
            this.wakeRecognizer = wakeRecognizer;
            this.commandRecognizer = commandRecognizer;
            this.main = main;
            int minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minimum <= 0) throw new IOException("16 kHz microphone capture is unavailable");
            recorder = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(minimum, FRAME_SAMPLES * 4));
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                recorder.release();
                throw new IOException("Microphone recorder could not initialize");
            }
        }

        boolean startListening(CosmoVoiceService listener) {
            if (audioThread != null) return false;
            active = true;
            audioThread = new Thread(() -> {
                try {
                    if (!active) return;
                    recorder.startRecording();
                    if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                        throw new IllegalStateException("Microphone recording did not start");
                    }
                    short[] frame = new short[FRAME_SAMPLES];
                    SpeechRouting.Snapshot applied = null;
                    while (active && !Thread.currentThread().isInterrupted()) {
                        SpeechRouting.Snapshot readRoute = routing.snapshot();
                        int count = recorder.read(frame, 0, frame.length, AudioRecord.READ_BLOCKING);
                        if (!active) break;
                        if (count < 0) throw new IllegalStateException("Microphone read failed: " + count);
                        if (count == 0 || !routing.isCurrent(readRoute)) continue;
                        if (applied != readRoute) {
                            // No main-thread reset can race an in-flight native decoder call.
                            wakeRecognizer.reset();
                            commandRecognizer.reset();
                            applied = readRoute;
                        }
                        if (readRoute.stage == SpeechRouting.Stage.PAUSED) continue;
                        Recognizer recognizer = readRoute.stage == SpeechRouting.Stage.WAKE
                                ? wakeRecognizer : commandRecognizer;
                        if (recognizer.acceptWaveForm(frame, count)) {
                            String result = recognizer.getResult();
                            main.post(() -> {
                                if (active && routing.isCurrent(readRoute)) {
                                    listener.handleFinalTranscript(readRoute.stage, result);
                                }
                            });
                        }
                        // Partial results never authorize a wake or execute a command.
                    }
                } catch (RuntimeException | LinkageError error) {
                    if (active) {
                        main.post(() -> {
                            if (active) listener.onError(new IOException("Microphone recognition stopped", error));
                        });
                    }
                } finally {
                    stopRecorder();
                }
            }, "cosmo-audio");
            audioThread.start();
            return true;
        }

        void route(SpeechRouting.Stage stage) {
            routing.transition(stage);
        }

        void shutdown(Runnable afterThreadStops) {
            active = false;
            route(SpeechRouting.Stage.PAUSED);
            Thread thread = audioThread;
            if (thread != null) thread.interrupt();
            // stop() wakes a blocking read; the join happens away from Android's main thread.
            stopRecorder();
            new Thread(() -> {
                boolean interrupted = false;
                if (thread != null) {
                    while (thread.isAlive()) {
                        try { thread.join(); }
                        catch (InterruptedException ignored) { interrupted = true; }
                    }
                }
                recorder.release();
                afterThreadStops.run();
                if (interrupted) Thread.currentThread().interrupt();
            }, "cosmo-audio-cleanup").start();
        }

        private void stopRecorder() {
            try {
                if (recorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) recorder.stop();
            } catch (RuntimeException ignored) {
                // Recorder was already stopped/revoked; release still runs after the thread exits.
            }
        }
    }
}
