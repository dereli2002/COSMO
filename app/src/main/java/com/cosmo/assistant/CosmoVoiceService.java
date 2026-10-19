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

import org.json.JSONException;
import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.RecognitionListener;

import java.io.IOException;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** User-started, visible microphone service. Recognition is entirely on the phone. */
public final class CosmoVoiceService extends Service implements RecognitionListener {
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
    private static final String WAITING = "Asistan çağrısını bekliyor.";

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
    private Recognizer recognizer;
    private LocalSpeechLoop speechService;
    private TextToSpeech tts;

    private final Runnable wakeTimeout = () -> {
        if (!destroyed && listening && !speaking && !gate.isAwaitingCommand(SystemClock.elapsedRealtime())) {
            updateStatus(WAITING);
        }
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
                "COSMO sürekli dinleme", NotificationManager.IMPORTANCE_LOW);
        listeningChannel.setDescription("Mikrofon açıkken sürekli görünür. Durdur düğmesi dinlemeyi kapatır.");
        listeningChannel.setShowBadge(false);
        notificationManager.createNotificationChannel(listeningChannel);
        NotificationChannel actions = new NotificationChannel(CHANNEL_ACTIONS,
                "COSMO işlem onayı", NotificationManager.IMPORTANCE_DEFAULT);
        actions.setDescription("Uygulama veya sistem ekranı açmak için dokunman gereken işlemler.");
        notificationManager.createNotificationChannel(actions);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            listening = false;
            if (speechService != null) speechService.setPause(true);
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
                fail("Dinleme başlamadı. COSMO'yu açıp mikrofon iznini ver.");
                return START_NOT_STICKY;
            }
            if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_MICROPHONE)) {
                fail("Bu cihazda kullanılabilir mikrofon bulunamadı.");
                return START_NOT_STICKY;
            }
            if (!VoiceModelManager.isReady(this)) {
                fail("Önce COSMO ekranından Türkçe modeli hazırla.");
                return START_NOT_STICKY;
            }
            PowerManager power = getSystemService(PowerManager.class);
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, getPackageName() + ":Listening");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire(10 * 60_000L);
            main.postDelayed(renewWakeLock, 5 * 60_000L);
            preferences.edit().putBoolean(KEY_LISTENING, false).apply();
            updateStatus("Türkçe dil modeli açılıyor…");
            initializeTts();
            loadModel();
        } catch (RuntimeException exception) {
            fail("Android dinlemeyi başlatamadı. COSMO'yu açık ekrandan başlatıp mikrofon ve bildirim izinlerini kontrol et.");
        }
        return START_NOT_STICKY;
    }

    private void loadModel() {
        new Thread(() -> {
            try {
                Model loaded = new Model(VoiceModelManager.modelDirectory(this).getAbsolutePath());
                main.post(() -> {
                    if (destroyed) { loaded.close(); return; }
                    model = loaded;
                    startRecognition();
                });
            } catch (IOException | RuntimeException | LinkageError error) {
                main.post(() -> {
                    if (!destroyed) fail("Dil modeli açılamadı. Depolama alanını ve cihaz uyumluluğunu kontrol et; modeli yeniden kurmayı deneyebilirsin.");
                });
            }
        }, "cosmo-model-loader").start();
    }

    private void startRecognition() {
        try {
            recognizer = new Recognizer(model, 16_000.0f);
            speechService = new LocalSpeechLoop(recognizer, main);
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

    @Override public void onPartialResult(String hypothesis) {
        // Deliberately never execute partials: recognizers revise them and can emit duplicates.
    }

    @Override public void onResult(String hypothesis) { handleFinalTranscript(hypothesis); }

    @Override public void onFinalResult(String hypothesis) { handleFinalTranscript(hypothesis); }

    private void handleFinalTranscript(String hypothesis) {
        if (destroyed || !listening || speaking) return;
        String transcript;
        try { transcript = new JSONObject(hypothesis).optString("text", "").trim(); }
        catch (JSONException exception) { return; }
        if (transcript.isEmpty()) return;
        long now = SystemClock.elapsedRealtime();
        String command = gate.accept(transcript, now);
        if (command == null) {
            if (gate.isAwaitingCommand(now)) {
                preferences.edit().putString(KEY_LAST_HEARD, transcript).apply();
                updateStatus("Dinliyorum. Komutunu 10 saniye içinde söyle.");
                main.removeCallbacks(wakeTimeout);
                main.postDelayed(wakeTimeout, WakeWordGate.COMMAND_WINDOW_MS + 1L);
            }
            return;
        }
        main.removeCallbacks(wakeTimeout);
        preferences.edit().putString(KEY_LAST_HEARD, transcript).apply();
        Command parsed = CommandParser.parse(command);
        if (parsed.type == Command.Type.CANCEL) notificationManager.cancel(NOTIFICATION_ACTION);
        DeviceActions.Result result = new DeviceActions(this).execute(parsed);
        String reply = result.speech;
        if (result.intent != null) {
            if (postActionNotification(result.intent)) {
                reply += " Devam etmek için COSMO işlem bildirimine dokun.";
            } else {
                reply += " İşlem bildirimi açılamadı. COSMO ekranındaki yazılı komut alanından tekrar deneyebilirsin.";
            }
        }
        if (parsed.type == Command.Type.NOTE_LIST && phoneLocked()) {
            reply = "Notlarını okumak için önce telefonun kilidini açmalısın.";
        }
        preferences.edit().putString(KEY_LAST_REPLY, reply).apply();
        broadcastState();
        speak(reply, result.stopListening);
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
                    .setContentTitle("COSMO işlemi hazır")
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
            } else updateStatus(WAITING);
            return;
        }
        speaking = true;
        stopAfterSpeech = stopWhenDone;
        if (speechService != null) {
            speechService.setPause(true);
            speechService.reset();
        }
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
        if (speechService != null) {
            speechService.reset();
            speechService.setPause(false);
        }
        updateStatus(WAITING);
    }

    @Override public void onError(Exception exception) {
        if (!destroyed) fail("Mikrofon dinlemesi kesildi. Mikrofonu kullanan uygulamaları kapatıp COSMO'dan yeniden başlat.");
    }

    @Override public void onTimeout() {
        if (!destroyed) fail("Dinleme sona erdi. COSMO ekranından yeniden başlatabilirsin.");
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
                .setContentTitle("COSMO • mikrofon açık")
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
        if (speechService != null) speechService.setPause(true);
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
        main.removeCallbacks(speechWatchdog);
        main.removeCallbacks(renewWakeLock);
        notificationManager.cancel(NOTIFICATION_ACTION);
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
        }
        Recognizer retiringRecognizer = recognizer;
        Model retiringModel = model;
        Runnable closeNativeResources = () -> {
            if (retiringRecognizer != null) retiringRecognizer.close();
            if (retiringModel != null) retiringModel.close();
        };
        if (speechService != null) {
            // Never close native objects while an AudioRecord/recognizer call is still using them.
            speechService.shutdown(closeNativeResources);
            speechService = null;
        } else {
            closeNativeResources.run();
        }
        recognizer = null;
        model = null;
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
     * Only this thread touches the recognizer; shutdown joins it before closing native resources.
     */
    private static final class LocalSpeechLoop {
        private static final int SAMPLE_RATE = 16_000;
        private static final int FRAME_SAMPLES = 3_200;
        private final Recognizer recognizer;
        private final AudioRecord recorder;
        private final Handler main;
        private final AtomicBoolean resetRequested = new AtomicBoolean();
        private volatile boolean active;
        private volatile boolean paused;
        private volatile long generation;
        private Thread audioThread;

        @SuppressLint("MissingPermission") // The service verifies RECORD_AUDIO before constructing this loop.
        @SuppressWarnings("deprecation")
        LocalSpeechLoop(Recognizer recognizer, Handler main) throws IOException {
            this.recognizer = recognizer;
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

        boolean startListening(RecognitionListener listener) {
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
                    while (active && !Thread.currentThread().isInterrupted()) {
                        long readGeneration = generation;
                        int count = recorder.read(frame, 0, frame.length, AudioRecord.READ_BLOCKING);
                        if (!active) break;
                        if (count < 0) throw new IllegalStateException("Microphone read failed: " + count);
                        if (count == 0 || paused || readGeneration != generation) continue;
                        if (resetRequested.getAndSet(false)) recognizer.reset();
                        if (recognizer.acceptWaveForm(frame, count)) {
                            String result = recognizer.getResult();
                            main.post(() -> {
                                if (active && !paused && generation == readGeneration) listener.onResult(result);
                            });
                        }
                        // Partial results cannot execute or leak a command across a TTS pause.
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

        void setPause(boolean value) {
            if (value) {
                paused = true;
                generation++;
            } else {
                resetRequested.set(true);
                generation++;
                paused = false;
            }
        }

        void reset() {
            resetRequested.set(true);
            generation++;
        }

        void shutdown(Runnable afterThreadStops) {
            active = false;
            generation++;
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
