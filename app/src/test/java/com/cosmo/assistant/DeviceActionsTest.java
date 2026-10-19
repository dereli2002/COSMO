package com.cosmo.assistant;

import android.Manifest;
import android.app.Application;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.net.Uri;
import android.provider.AlarmClock;
import android.view.KeyEvent;

import org.json.JSONArray;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAudioManager;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Runs real action code against an isolated Android JVM sandbox, without a device. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public final class DeviceActionsTest {
    private Application application;
    private DeviceActions actions;
    private SharedPreferences preferences;
    private KeyguardManager keyguard;

    @Before public void prepareIsolatedUnlockedPhone() {
        application = RuntimeEnvironment.getApplication();
        preferences = application.getSharedPreferences("cosmo", Context.MODE_PRIVATE);
        assertTrue(preferences.edit().clear().commit());
        keyguard = (KeyguardManager) application.getSystemService(Context.KEYGUARD_SERVICE);
        Shadows.shadowOf(keyguard).setKeyguardLocked(false);
        Shadows.shadowOf(keyguard).setIsDeviceLocked(false);
        actions = new DeviceActions(application);
    }

    @Test public void dialPreparesExactNumberWithoutLaunchingOrCalling() {
        DeviceActions.Result result = actions.execute(new Command(Command.Type.DIAL, "+905551112233"));

        assertNotNull(result.intent);
        assertEquals(Intent.ACTION_DIAL, result.intent.getAction());
        assertEquals("tel", result.intent.getData().getScheme());
        assertEquals("+905551112233", result.intent.getData().getSchemeSpecificPart());
        assertFalse(Intent.ACTION_CALL.equals(result.intent.getAction()));
        assertNull(Shadows.shadowOf(application).getNextStartedActivity());
    }

    @Test public void selfOpeningWorksWithoutEnumeratingOtherApps() {
        DeviceActions.Result result = actions.execute(CommandParser.parse("kendini aç"));
        assertNotNull(result.intent);
        assertEquals(application.getPackageName(), result.intent.getComponent().getPackageName());
        assertEquals(MainActivity.class.getName(), result.intent.getComponent().getClassName());
        assertNull(Shadows.shadowOf(application).getNextStartedActivity());
    }

    @Test public void smsKeepsDestinationAndTurkishBodyInComposeUiOnly() {
        String message = "Öykü, İstanbul'da saat 19:30! & + % #";
        DeviceActions.Result result = actions.execute(new Command(Command.Type.COMPOSE_SMS,
                "+905551112233", message));

        assertNotNull(result.intent);
        assertEquals(Intent.ACTION_SENDTO, result.intent.getAction());
        assertEquals("smsto", result.intent.getData().getScheme());
        assertEquals("+905551112233", result.intent.getData().getSchemeSpecificPart());
        assertEquals(message, result.intent.getStringExtra("sms_body"));
        assertTrue(result.speech.contains("gönder düğmesine sen basmalısın"));
        assertNull(Shadows.shadowOf(application).getNextStartedActivity());
    }

    @Test public void hundredthNoteIsStoredAndHundredAndFirstPreservesAllExistingNotes() throws Exception {
        JSONArray initial = new JSONArray();
        for (int index = 0; index < 99; index++) initial.put("Önceki not " + index);
        assertTrue(preferences.edit().putString("notes", initial.toString()).commit());

        DeviceActions.Result hundredth = actions.execute(new Command(Command.Type.NOTE_SAVE, "Yüzüncü not: çığ ve şemsiye"));
        assertEquals("Notunu bu telefona kaydettim.", hundredth.speech);
        JSONArray full = new JSONArray(preferences.getString("notes", "[]"));
        assertEquals(100, full.length());
        assertEquals("Yüzüncü not: çığ ve şemsiye", full.getString(0));
        for (int index = 0; index < 99; index++) assertEquals("Önceki not " + index, full.getString(index + 1));
        String completeSavedNotes = preferences.getString("notes", null);

        DeviceActions.Result rejected = actions.execute(new Command(Command.Type.NOTE_SAVE, "Kaydedilmemeli"));
        assertTrue(rejected.speech.contains("100 not"));
        assertTrue(rejected.speech.contains("Yeni not kaydedilmedi"));
        assertEquals(completeSavedNotes, preferences.getString("notes", null));
        assertNull(rejected.intent);
    }

    @Test public void lockedPhoneNeverSpeaksSavedPrivateNotes() {
        String privateNote = "Özel kapı kodum 9472";
        assertTrue(preferences.edit().putString("notes", new JSONArray().put(privateNote).toString()).commit());
        assertTrue(actions.execute(new Command(Command.Type.NOTE_LIST)).speech.contains(privateNote));

        Shadows.shadowOf(keyguard).setKeyguardLocked(true);
        DeviceActions.Result result = actions.execute(new Command(Command.Type.NOTE_LIST));

        assertEquals("Bu işlem için önce telefonun kilidini açmalısın.", result.speech);
        assertFalse(result.speech.contains(privateNote));
        assertNull(result.intent);
        assertTrue(preferences.getString("notes", "").contains(privateNote));
    }

    @Test public void lockAlsoBlocksNewPrivateDataAndPhoneIntentPreparation() {
        Shadows.shadowOf(keyguard).setKeyguardLocked(true);
        DeviceActions.Result note = actions.execute(new Command(Command.Type.NOTE_SAVE, "Kaydedilmemeli"));
        DeviceActions.Result phone = actions.execute(new Command(Command.Type.DIAL, "+905551112233"));
        DeviceActions.Result sms = actions.execute(new Command(Command.Type.COMPOSE_SMS, "+905551112233", "Özel mesaj"));

        assertTrue(note.speech.contains("kilidini açmalısın"));
        assertFalse(preferences.contains("notes"));
        assertTrue(phone.speech.contains("kilidini açmalısın"));
        assertTrue(sms.speech.contains("kilidini açmalısın"));
        assertNull(phone.intent);
        assertNull(sms.intent);
        assertNull(Shadows.shadowOf(application).getNextStartedActivity());
        assertTrue(actions.execute(new Command(Command.Type.TIME)).speech.matches("Saat (?:[01]\\d|2[0-3]):[0-5]\\d"));
        assertTrue(actions.execute(new Command(Command.Type.STOP_LISTENING)).stopListening);
    }

    @Test public void playAndPauseDispatchDistinctMediaKeysAndLockBlocksBoth() {
        AudioManager manager = (AudioManager) application.getSystemService(Context.AUDIO_SERVICE);
        ShadowAudioManager audio = Shadows.shadowOf(manager);
        audio.clearDispatchedMediaKeyEvents();

        actions.execute(new Command(Command.Type.MEDIA_PLAY));
        actions.execute(new Command(Command.Type.MEDIA_PAUSE));
        List<KeyEvent> events = audio.getDispatchedMediaKeyEvents();
        assertEquals(4, events.size());
        assertEquals(KeyEvent.ACTION_DOWN, events.get(0).getAction());
        assertEquals(KeyEvent.KEYCODE_MEDIA_PLAY, events.get(0).getKeyCode());
        assertEquals(KeyEvent.ACTION_UP, events.get(1).getAction());
        assertEquals(KeyEvent.KEYCODE_MEDIA_PLAY, events.get(1).getKeyCode());
        assertEquals(KeyEvent.ACTION_DOWN, events.get(2).getAction());
        assertEquals(KeyEvent.KEYCODE_MEDIA_PAUSE, events.get(2).getKeyCode());
        assertEquals(KeyEvent.ACTION_UP, events.get(3).getAction());
        assertEquals(KeyEvent.KEYCODE_MEDIA_PAUSE, events.get(3).getKeyCode());

        Shadows.shadowOf(keyguard).setKeyguardLocked(true);
        assertTrue(actions.execute(new Command(Command.Type.MEDIA_PLAY)).speech.contains("kilidini açmalısın"));
        assertTrue(actions.execute(new Command(Command.Type.MEDIA_PAUSE)).speech.contains("kilidini açmalısın"));
        assertEquals(4, audio.getDispatchedMediaKeyEvents().size());
    }

    @Test public void alarmAndTimerRequireVisibleClockUiAndKeepExactValues() {
        Intent alarm = actions.execute(new Command(Command.Type.SET_ALARM, "07:35")).intent;
        assertNotNull(alarm);
        assertEquals(AlarmClock.ACTION_SET_ALARM, alarm.getAction());
        assertEquals(7, alarm.getIntExtra(AlarmClock.EXTRA_HOUR, -1));
        assertEquals(35, alarm.getIntExtra(AlarmClock.EXTRA_MINUTES, -1));
        assertFalse(alarm.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, true));
        Intent timer = actions.execute(new Command(Command.Type.SET_TIMER, "305")).intent;
        assertNotNull(timer);
        assertEquals(AlarmClock.ACTION_SET_TIMER, timer.getAction());
        assertEquals(305, timer.getIntExtra(AlarmClock.EXTRA_LENGTH, -1));
        assertFalse(timer.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, true));
        assertNull(actions.execute(new Command(Command.Type.SET_TIMER, "86401")).intent);
        assertNull(Shadows.shadowOf(application).getNextStartedActivity());
    }

    @Test public void searchEncodesTurkishPayloadAsOneQueryValueWithoutNewFragment() {
        String query = "İstanbul & Ankara #1 + %?";
        Intent web = actions.execute(new Command(Command.Type.WEB_SEARCH, query)).intent;
        assertNotNull(web);
        assertEquals(query, web.getData().getQueryParameter("q"));
        assertEquals(1, web.getData().getQueryParameterNames().size());
        assertNull(web.getData().getFragment());
        Intent map = actions.execute(new Command(Command.Type.MAP_SEARCH, query)).intent;
        assertNotNull(map);
        assertEquals("geo:0,0?q=" + Uri.encode(query), map.getData().toString());
        assertNull(map.getData().getFragment());
    }

    @Test public void missingOptionalCameraPermissionExplainsTorchRequirement() {
        Shadows.shadowOf(application).denyPermissions(Manifest.permission.CAMERA);
        DeviceActions.Result result = actions.execute(new Command(Command.Type.FLASHLIGHT_ON));
        assertTrue(result.speech.contains("kamera izni gerekiyor"));
        assertNull(result.intent);
    }
}
