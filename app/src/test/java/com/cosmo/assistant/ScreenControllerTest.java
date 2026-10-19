package com.cosmo.assistant;

import android.app.Application;
import android.app.KeyguardManager;
import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.text.InputType;
import android.view.accessibility.AccessibilityNodeInfo;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ServiceController;

import static org.junit.Assert.*;

/** Exercises visible targets and Android accessibility actions, including ambiguity/security boundaries. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public final class ScreenControllerTest {
    private AccessibilityNodeInfo root;

    @Before public void prepareOrdinaryApplicationScreen() {
        root = node("");
        root.setPackageName("com.example.messages");
    }

    @Test public void clickingTurkishLabelSelectsOneExactVisibleButton() {
        AccessibilityNodeInfo target = button("GÖNDER");
        AccessibilityNodeInfo unrelated = button("Gönderiyi paylaş");
        add(root, target);
        add(root, unrelated);
        assertTrue(ScreenController.click(root, "gönder"));
        assertEquals(1, Shadows.shadowOf(target).getPerformedActions().size());
        assertEquals(Integer.valueOf(AccessibilityNodeInfo.ACTION_CLICK),
                Shadows.shadowOf(target).getPerformedActions().get(0));
        assertTrue(Shadows.shadowOf(unrelated).getPerformedActions().isEmpty());
    }

    @Test public void duplicateVisibleLabelsNeverGuessTheTarget() {
        AccessibilityNodeInfo first = button("Sil");
        AccessibilityNodeInfo second = button("Sil");
        add(root, first);
        add(root, second);
        assertFalse(ScreenController.click(root, "sil"));
        assertTrue(Shadows.shadowOf(first).getPerformedActions().isEmpty());
        assertTrue(Shadows.shadowOf(second).getPerformedActions().isEmpty());
    }

    @Test public void blankEditableFieldCanBeSelectedByItsVisibleHint() {
        AccessibilityNodeInfo field = editable("", false);
        field.setHintText("Mesaj yaz");
        field.setClickable(true);
        add(root, field);
        assertTrue(ScreenController.read(root).contains("Yazı alanı: Mesaj yaz"));
        assertTrue(ScreenController.click(root, "mesaj yaz"));
        assertEquals(Integer.valueOf(AccessibilityNodeInfo.ACTION_CLICK),
                Shadows.shadowOf(field).getPerformedActions().get(0));
    }

    @Test public void hiddenAndDisabledControlsAreNotClicked() {
        AccessibilityNodeInfo hidden = button("Gönder");
        hidden.setVisibleToUser(false);
        AccessibilityNodeInfo disabled = button("Sil");
        disabled.setEnabled(false);
        add(root, hidden);
        add(root, disabled);
        assertFalse(ScreenController.click(root, "gönder"));
        assertFalse(ScreenController.click(root, "sil"));
        assertTrue(Shadows.shadowOf(hidden).getPerformedActions().isEmpty());
        assertTrue(Shadows.shadowOf(disabled).getPerformedActions().isEmpty());
    }

    @Test public void dictationReplacesOnlyFocusedOrdinaryEditableField() {
        AccessibilityNodeInfo name = editable("Eski metin", true);
        AccessibilityNodeInfo other = editable("Başka alan", false);
        add(root, name);
        add(root, other);
        String message = "İstanbul'da saat 19:30, Öykü!";
        assertTrue(ScreenController.type(root, message));
        assertEquals(1, Shadows.shadowOf(name).getPerformedActionsWithArgs().size());
        assertEquals(Integer.valueOf(AccessibilityNodeInfo.ACTION_SET_TEXT),
                Shadows.shadowOf(name).getPerformedActionsWithArgs().get(0).first);
        assertEquals(message, Shadows.shadowOf(name).getPerformedActionsWithArgs().get(0).second
                .getCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE));
        assertTrue(Shadows.shadowOf(other).getPerformedActions().isEmpty());
    }

    @Test public void passwordPinAndVerificationInputsAreNeverReadOrWritten() {
        AccessibilityNodeInfo password = editable("çok gizli", true);
        password.setPassword(true);
        add(root, password);
        assertFalse(ScreenController.type(root, "değiştir"));
        assertFalse(ScreenController.read(root).contains("çok gizli"));
        password.setFocused(false);
        AccessibilityNodeInfo pin = editable("9472", true);
        pin.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        add(root, pin);
        assertFalse(ScreenController.type(root, "0000"));
        assertFalse(ScreenController.read(root).contains("9472"));
        pin.setFocused(false);
        AccessibilityNodeInfo otp = editable("348192", true);
        otp.setHintText("SMS doğrulama kodu");
        add(root, otp);
        assertFalse(ScreenController.type(root, "123456"));
        assertFalse(ScreenController.read(root).contains("348192"));
        assertTrue(Shadows.shadowOf(password).getPerformedActions().isEmpty());
        assertTrue(Shadows.shadowOf(pin).getPerformedActions().isEmpty());
        assertTrue(Shadows.shadowOf(otp).getPerformedActions().isEmpty());
    }

    @Test public void ordinaryEditableContentAlsoStaysOutOfSpokenScreenReading() {
        add(root, node("Mesajlar"));
        add(root, editable("Henüz göndermediğim özel metin", true));
        String spoken = ScreenController.read(root);
        assertTrue(spoken.contains("Mesajlar"));
        assertTrue(spoken.contains("Yazı alanı"));
        assertFalse(spoken.contains("özel metin"));
    }

    @Test public void permissionAndSystemSecurityWindowsRejectAllContentActions() {
        AccessibilityNodeInfo allow = button("İzin ver");
        AccessibilityNodeInfo field = editable("", true);
        add(root, allow);
        add(root, field);
        for (String packageName : new String[]{"com.android.permissioncontroller",
                "com.google.android.permissioncontroller", "com.android.settings",
                "com.android.systemui", "com.google.android.packageinstaller"}) {
            root.setPackageName(packageName);
            assertFalse(packageName, ScreenController.click(root, "İzin ver"));
            assertFalse(packageName, ScreenController.type(root, "evet"));
            assertNull(packageName, ScreenController.contextKey(root));
            assertTrue(ScreenController.read(root).contains("telefondan yapmalısın"));
        }
        assertTrue(Shadows.shadowOf(allow).getPerformedActions().isEmpty());
        assertTrue(Shadows.shadowOf(field).getPerformedActions().isEmpty());
    }

    @Test public void changedContentOrInputInvalidatesTransientConfirmationFingerprint() {
        AccessibilityNodeInfo recipient = node("Alıcı: Öykü");
        AccessibilityNodeInfo input = editable("Merhaba", true);
        add(root, recipient);
        add(root, input);
        String first = ScreenController.contextKey(root);
        assertNotNull(first);
        assertFalse(first.contains("Öykü"));
        assertFalse(first.contains("Merhaba"));
        assertEquals(first, ScreenController.contextKey(root));
        recipient.setText("Alıcı: Başka kişi");
        String second = ScreenController.contextKey(root);
        assertNotEquals(first, second);
        input.setText("Başka bir metin");
        assertNotEquals(second, ScreenController.contextKey(root));
    }

    @Test public void screenReadingAndTraversalAreBounded() {
        add(root, node("A".repeat(3999)));
        add(root, node("Son metin"));
        assertTrue(ScreenController.read(root).length() <= ScreenController.MAX_READ_CHARS);
        AccessibilityNodeInfo deepRoot = node("");
        deepRoot.setPackageName("com.example.app");
        for (int index = 0; index < ScreenController.MAX_NODES + 100; index++) {
            add(deepRoot, node("Başlık " + index));
        }
        assertFalse(ScreenController.read(deepRoot).contains("Başlık 599"));
    }

    @Test public void lockedDeviceCannotNavigateOrReadEvenWithSavedConsent() {
        Application application = RuntimeEnvironment.getApplication();
        application.getSharedPreferences("cosmo", Context.MODE_PRIVATE).edit()
                .putBoolean("accessibility_actions_enabled", true)
                .putBoolean("screen_control_enabled", true).commit();
        KeyguardManager keyguard = (KeyguardManager) application.getSystemService(Context.KEYGUARD_SERVICE);
        Shadows.shadowOf(keyguard).setKeyguardLocked(false);
        Shadows.shadowOf(keyguard).setIsDeviceLocked(false);
        ServiceController<CosmoAccessibilityService> controller = Robolectric.buildService(CosmoAccessibilityService.class).create();
        CosmoAccessibilityService service = controller.get();
        service.onServiceConnected();
        try {
            assertTrue(CosmoAccessibilityService.isAvailable(application));
            assertTrue(CosmoAccessibilityService.navigate(application, AccessibilityService.GLOBAL_ACTION_HOME));
            assertEquals(1, Shadows.shadowOf(service).getGlobalActionsPerformed().size());
            Shadows.shadowOf(keyguard).setKeyguardLocked(true);
            assertFalse(CosmoAccessibilityService.navigate(application, AccessibilityService.GLOBAL_ACTION_BACK));
            assertEquals(1, Shadows.shadowOf(service).getGlobalActionsPerformed().size());
            assertTrue(CosmoAccessibilityService.readScreen(application).contains("kilidini açmalısın"));
            assertFalse(CosmoAccessibilityService.clickLabel(application, "Gönder"));
            assertFalse(CosmoAccessibilityService.typeText(application, "Merhaba"));
            assertNull(CosmoAccessibilityService.screenContextKey(application));
        } finally {
            controller.destroy();
        }
    }

    private static AccessibilityNodeInfo node(String text) {
        AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain();
        node.setVisibleToUser(true);
        node.setEnabled(true);
        node.setText(text);
        return node;
    }

    private static AccessibilityNodeInfo button(String text) {
        AccessibilityNodeInfo node = node(text);
        node.setClickable(true);
        Shadows.shadowOf(node).setOnPerformActionListener((action, arguments) -> true);
        return node;
    }

    private static AccessibilityNodeInfo editable(String text, boolean focused) {
        AccessibilityNodeInfo node = node(text);
        node.setEditable(true);
        node.setFocused(focused);
        node.setInputType(InputType.TYPE_CLASS_TEXT);
        Shadows.shadowOf(node).setOnPerformActionListener((action, arguments) -> true);
        return node;
    }

    private static void add(AccessibilityNodeInfo parent, AccessibilityNodeInfo child) {
        Shadows.shadowOf(parent).addChild(child);
    }
}
