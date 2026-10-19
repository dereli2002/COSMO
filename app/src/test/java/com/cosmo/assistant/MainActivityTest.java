package com.cosmo.assistant;

import android.Manifest;
import android.app.AlertDialog;
import android.app.Application;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Switch;

import org.json.JSONArray;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.util.ReflectionHelpers;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Exercises the real Activity and public controls without a microphone or emulator. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 36})
public class MainActivityTest {
    private ActivityController<MainActivity> controller;
    private MainActivity activity;
    private SharedPreferences preferences;

    @Before public void launchUnlockedActivity() throws Exception {
        Application application = RuntimeEnvironment.getApplication();
        preferences = application.getSharedPreferences("cosmo", Context.MODE_PRIVATE);
        preferences.edit().clear().commit();
        CosmoVoiceService.running = false;
        KeyguardManager keyguard = (KeyguardManager) application.getSystemService(Context.KEYGUARD_SERVICE);
        Shadows.shadowOf(keyguard).setKeyguardLocked(false);
        Shadows.shadowOf(keyguard).setIsDeviceLocked(false);
        // Android grants an application's own signature permission at installation. Robolectric's
        // permission shadow needs that grant explicitly; first verify the real merged declaration.
        String internalPermission = application.getPackageName() + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION";
        assertTrue(Arrays.asList(application.getPackageManager().getPackageInfo(
                application.getPackageName(), PackageManager.GET_PERMISSIONS).requestedPermissions).contains(internalPermission));
        assertEquals(PermissionInfo.PROTECTION_SIGNATURE, application.getPackageManager()
                .getPermissionInfo(internalPermission, 0).protectionLevel & PermissionInfo.PROTECTION_MASK_BASE);
        Shadows.shadowOf(application).grantPermissions(Manifest.permission.RECORD_AUDIO, internalPermission);
        controller = Robolectric.buildActivity(MainActivity.class).setup();
        activity = controller.get();
    }

    @After public void destroyActivity() {
        if (controller != null) controller.pause().stop().destroy();
        CosmoVoiceService.running = false;
    }

    @Test public void launchShowsWorkingControlsAndAnInitialResponse() {
        ViewGroup content = activity.findViewById(android.R.id.content);
        assertTrue(content.getChildCount() > 0);
        Button listen = activity.findViewById(R.id.start_listening);
        assertNotNull(listen);
        assertEquals(View.VISIBLE, listen.getVisibility());
        assertTrue(listen.isEnabled());
        assertEquals("Dinlemeyi başlat", listen.getText().toString());
        assertNotNull(activity.findViewById(R.id.model_download));
        assertNotNull(activity.findViewById(R.id.command_input));
        assertNotNull(activity.findViewById(R.id.command_send));
        assertTrue(response().contains("Hazırım"));
        assertTrue(status().contains("Dinleme kapalı"));
    }

    @Test public void startingWithoutModelExplainsRequirementAndNeverStartsMicrophoneService() {
        assertFalse(VoiceModelManager.isReady(activity));
        activity.findViewById(R.id.start_listening).performClick();
        assertTrue(response().contains(VoiceModelManager.hasBundledModel(activity)
                ? "Önce ses modellerini hazırla" : "Önce ses modellerini indir"));
        assertTrue(response().contains("Yazılı komutları"));
        assertFalse(CosmoVoiceService.running);
        assertNull(Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService());
        assertTrue(status().contains("Dinleme kapalı"));
    }

    @Test public void typedNoteSurvivesActivityRecreationAndCanBeReadBack() throws Exception {
        send("not al Yarın süt al");
        assertEquals("Notunu bu telefona kaydettim.", response());
        JSONArray saved = new JSONArray(preferences.getString("notes", "[]"));
        assertEquals(1, saved.length());
        assertEquals("Yarın süt al", saved.getString(0));

        controller.recreate();
        activity = controller.get();
        send("notlarımı oku");
        assertTrue(response().contains("1 notun var"));
        assertTrue(response().contains("Yarın süt al"));
        assertFalse(CosmoVoiceService.running);
    }

    @Test public void typedTimeCommandReturnsAValidHourAndMinuteWithoutModel() {
        assertFalse(VoiceModelManager.isReady(activity));
        send("saat kaç");
        assertTrue("Unexpected clock response: " + response(),
                response().matches("Saat (?:[01]\\d|2[0-3]):[0-5]\\d"));
    }

    @Test public void bundledModelPreparationDoesNotAskForAnInternetDownload() {
        assertTrue(VoiceModelManager.hasBundledModel(activity));
        Button prepare = activity.findViewById(R.id.model_download);
        assertEquals("Ses modellerini hazırla", prepare.getText().toString());
        assertTrue(modelStatus().contains("İnternet gerekmez"));
        prepare.performClick();
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(dialog);
        assertEquals("Hazırla", dialog.getButton(DialogInterface.BUTTON_POSITIVE).getText().toString());
        assertTrue(Shadows.shadowOf(dialog).getMessage().toString().contains("internet gerekmez"));
        dialog.dismiss();
    }

    @Test public void extractionProgressIsDescribedAsPreparationBeforeModelIsVerified() {
        VoiceModelManager.Listener listener = modelListener();
        listener.onProgress(42);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals("Türkçe model indiriliyor: %42", modelStatus());

        listener.onProgress(91);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals("Türkçe model açılıyor ve doğrulanıyor…", modelStatus());
        assertFalse(modelStatus().contains("%100"));

        listener.onProgress(100);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals("✓ Türkçe model doğrulandı.", modelStatus());
    }

    @Test public void modelFailureKeepsItsSpecificAdviceAcrossRefreshAndRecreation() {
        String failure = "Dil modeli kurulamadı: Yeterli boş alan yok. Yer açıp yeniden dene.";
        modelListener().onError(failure);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(failure, modelStatus());
        assertFalse(modelStatus().contains("İnternet bağlantını"));
        assertTrue(activity.findViewById(R.id.model_download).isEnabled());

        controller.pause().resume();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(failure, modelStatus());

        controller.recreate();
        activity = controller.get();
        assertEquals(failure, modelStatus());
    }

    @Test public void screenControlCannotEnableWithoutNavigationConsent() {
        Switch toggle = ReflectionHelpers.getField(activity, "screenControlSwitch");
        assertFalse(preferences.getBoolean("screen_control_enabled", false));
        toggle.setChecked(true);
        assertFalse(toggle.isChecked());
        assertFalse(preferences.getBoolean("screen_control_enabled", false));
        assertTrue(response().contains("Önce Sesli gezinme"));
    }

    @Test public void screenControlRequiresItsOwnExplicitDisclosureApproval() {
        preferences.edit().putBoolean("accessibility_actions_enabled", true).commit();
        Switch toggle = ReflectionHelpers.getField(activity, "screenControlSwitch");
        toggle.setChecked(true);
        assertFalse("A switch tap alone must not grant screen reading", preferences.getBoolean("screen_control_enabled", false));
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(dialog);
        assertTrue(Shadows.shadowOf(dialog).getMessage().toString().contains("görünen yazılarını"));
        dialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertFalse(toggle.isChecked());
        assertFalse(preferences.getBoolean("screen_control_enabled", false));
        toggle.setChecked(true);
        ShadowAlertDialog.getLatestAlertDialog().getButton(DialogInterface.BUTTON_POSITIVE).performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue(preferences.getBoolean("screen_control_enabled", false));
        toggle.setChecked(false);
        assertFalse(preferences.getBoolean("screen_control_enabled", false));
    }

    private VoiceModelManager.Listener modelListener() {
        return ReflectionHelpers.callInstanceMethod(activity, "createModelListener");
    }

    private String modelStatus() {
        return ((TextView) ReflectionHelpers.getField(activity, "modelStatus")).getText().toString();
    }

    private void send(String command) {
        EditText input = activity.findViewById(R.id.command_input);
        input.setText(command);
        activity.findViewById(R.id.command_send).performClick();
    }

    private String response() {
        return ((TextView) activity.findViewById(R.id.response_text)).getText().toString();
    }

    private String status() {
        return ((TextView) activity.findViewById(R.id.listening_status)).getText().toString();
    }
}
