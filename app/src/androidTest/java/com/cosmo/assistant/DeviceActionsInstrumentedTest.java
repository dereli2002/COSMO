package com.cosmo.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeTrue;

import android.Manifest;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.AlarmClock;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.json.JSONArray;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Device checks for observable Android contracts; never launches or sends an external action. */
@RunWith(AndroidJUnit4.class)
public final class DeviceActionsInstrumentedTest {
    private Context context;
    private DeviceActions actions;
    private SharedPreferences preferences;
    private boolean hadNotes;
    private String previousNotes;

    @Before public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        actions = new DeviceActions(context);
        preferences = context.getSharedPreferences("cosmo", Context.MODE_PRIVATE);
        hadNotes = preferences.contains("notes");
        previousNotes = preferences.getString("notes", null);
    }

    @After public void restoreNotes() {
        SharedPreferences.Editor editor = preferences.edit();
        if (hadNotes) editor.putString("notes", previousNotes);
        else editor.remove("notes");
        assertTrue("Test must restore the original local notes", editor.commit());
    }

    @Test public void localNotePersistsOriginalTurkishAndIsReadBack() throws Exception {
        assumeUnlocked();
        assertTrue(preferences.edit().putString("notes", "[]").commit());
        String note = "İstanbul'da Öykü ile görüş; çığ, şemsiye ve 2 elma.";

        DeviceActions.Result saved = actions.execute(new Command(Command.Type.NOTE_SAVE, note));
        assertEquals("Notunu bu telefona kaydettim.", saved.speech);
        assertNull(saved.intent);
        JSONArray persisted = new JSONArray(preferences.getString("notes", "[]"));
        assertEquals(1, persisted.length());
        assertEquals(note, persisted.getString(0));

        // A new executor still reads disk-backed preferences, rather than an in-memory note field.
        DeviceActions.Result read = new DeviceActions(context).execute(new Command(Command.Type.NOTE_LIST));
        assertTrue(read.speech.contains("1 notun var."));
        assertTrue(read.speech.contains(note));
        assertNull(read.intent);
    }

    @Test public void batteryReplyContainsAnActualPercentageInRange() {
        DeviceActions.Result result = actions.execute(new Command(Command.Type.BATTERY));
        Matcher percentage = Pattern.compile("^Pil yüzde ([0-9]{1,3})[.].*$").matcher(result.speech);
        assertTrue("Battery response should include a percentage: " + result.speech, percentage.matches());
        int value = Integer.parseInt(percentage.group(1));
        assertTrue(value >= 0 && value <= 100);
        assertNull(result.intent);
    }

    @Test public void phoneCommandPreparesDialUiWithoutPlacingCall() {
        assumeUnlocked();
        DeviceActions.Result result = actions.execute(new Command(Command.Type.DIAL, "+905551112233"));
        assertNotNull(result.intent);
        assertEquals(Intent.ACTION_DIAL, result.intent.getAction());
        assertFalse(Intent.ACTION_CALL.equals(result.intent.getAction()));
        assertEquals("tel", result.intent.getData().getScheme());
        assertEquals("+905551112233", result.intent.getData().getSchemeSpecificPart());
        assertFalse(result.stopListening);
    }

    @Test public void smsCommandOnlyComposesExactDestinationAndOriginalBody() {
        assumeUnlocked();
        String body = "Öykü, saat 19:30'da görüşürüz! & + % #";
        DeviceActions.Result result = actions.execute(new Command(Command.Type.COMPOSE_SMS,
                "+905551112233", body));
        assertNotNull(result.intent);
        assertEquals(Intent.ACTION_SENDTO, result.intent.getAction());
        assertEquals("smsto", result.intent.getData().getScheme());
        assertEquals("+905551112233", result.intent.getData().getSchemeSpecificPart());
        assertEquals(body, result.intent.getStringExtra("sms_body"));
        assertTrue(result.speech.contains("gönder düğmesine sen basmalısın"));
    }

    @Test public void alarmKeepsClockAppUiVisible() {
        assumeUnlocked();
        DeviceActions.Result result = actions.execute(new Command(Command.Type.SET_ALARM, "07:35"));
        assertNotNull(result.intent);
        assertEquals(AlarmClock.ACTION_SET_ALARM, result.intent.getAction());
        assertEquals(7, result.intent.getIntExtra(AlarmClock.EXTRA_HOUR, -1));
        assertEquals(35, result.intent.getIntExtra(AlarmClock.EXTRA_MINUTES, -1));
        assertTrue(result.intent.hasExtra(AlarmClock.EXTRA_SKIP_UI));
        assertFalse(result.intent.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, true));
    }

    @Test public void timerKeepsClockAppUiVisible() {
        assumeUnlocked();
        DeviceActions.Result result = actions.execute(new Command(Command.Type.SET_TIMER, "305"));
        assertNotNull(result.intent);
        assertEquals(AlarmClock.ACTION_SET_TIMER, result.intent.getAction());
        assertEquals(305, result.intent.getIntExtra(AlarmClock.EXTRA_LENGTH, -1));
        assertTrue(result.intent.hasExtra(AlarmClock.EXTRA_SKIP_UI));
        assertFalse(result.intent.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, true));
    }

    @Test public void timerRejectsZeroAndMoreThanOneDay() {
        assumeUnlocked();
        assertNull(actions.execute(new Command(Command.Type.SET_TIMER, "0")).intent);
        assertNull(actions.execute(new Command(Command.Type.SET_TIMER, "86401")).intent);
        assertNotNull(actions.execute(new Command(Command.Type.SET_TIMER, "86400")).intent);
    }

    @Test public void webAndMapPayloadsStayInsideSingleEncodedQueryValue() {
        assumeUnlocked();
        String query = "İstanbul & Ankara #1 + %?";
        Intent web = actions.execute(new Command(Command.Type.WEB_SEARCH, query)).intent;
        assertNotNull(web);
        assertEquals(Intent.ACTION_VIEW, web.getAction());
        assertEquals("https", web.getData().getScheme());
        assertEquals("www.google.com", web.getData().getHost());
        assertEquals(query, web.getData().getQueryParameter("q"));
        assertNull(web.getData().getFragment());
        assertEquals(1, web.getData().getQueryParameterNames().size());

        Intent map = actions.execute(new Command(Command.Type.MAP_SEARCH, query)).intent;
        assertNotNull(map);
        assertEquals(Intent.ACTION_VIEW, map.getAction());
        assertEquals("geo", map.getData().getScheme());
        // geo is an opaque URI, so hierarchical getQueryParameter is not available.
        assertEquals("geo:0,0?q=" + Uri.encode(query), map.getData().toString());
        assertNull(map.getData().getFragment());
    }

    @Test public void flashlightWithoutCameraPermissionExplainsMissingPermission() {
        assumeUnlocked();
        assumeTrue("Run this case before granting the optional camera permission",
                context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED);
        DeviceActions.Result result = actions.execute(new Command(Command.Type.FLASHLIGHT_ON));
        assertTrue(result.speech.contains("kamera izni gerekiyor"));
        assertNull(result.intent);
    }

    @Test public void lockedPhoneDoesNotDiscloseNotesOrPreparePrivateIntents() {
        KeyguardManager manager = context.getSystemService(KeyguardManager.class);
        assumeTrue("Run this case separately with the phone locked", manager != null && manager.isKeyguardLocked());
        DeviceActions.Result note = actions.execute(new Command(Command.Type.NOTE_LIST));
        assertEquals("Bu işlem için önce telefonun kilidini açmalısın.", note.speech);
        assertNull(note.intent);
        DeviceActions.Result sms = actions.execute(new Command(Command.Type.COMPOSE_SMS,
                "+905551112233", "Özel mesaj"));
        assertEquals("Bu işlem için önce telefonun kilidini açmalısın.", sms.speech);
        assertNull(sms.intent);
    }

    private void assumeUnlocked() {
        KeyguardManager manager = context.getSystemService(KeyguardManager.class);
        assumeFalse("Unlock the test device for action contract checks", manager != null && manager.isKeyguardLocked());
    }
}
