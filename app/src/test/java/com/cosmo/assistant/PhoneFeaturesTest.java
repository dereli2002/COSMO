package com.cosmo.assistant;

import android.Manifest;
import android.accessibilityservice.AccessibilityService;
import android.app.AppOpsManager;
import android.app.Application;
import android.app.KeyguardManager;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Process;
import android.os.Looper;
import android.provider.CalendarContract;
import android.provider.ContactsContract;
import android.provider.MediaStore;
import android.provider.Settings;
import android.view.accessibility.AccessibilityNodeInfo;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Robolectric;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowAccessibilityService;
import org.robolectric.shadows.ShadowContentResolver;
import org.robolectric.shadows.ShadowSystemClock;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public final class PhoneFeaturesTest {
    private Application application;
    private PhoneFeatures features;
    private KeyguardManager keyguard;
    private TestContactsProvider contacts;

    @Before public void resetPhoneAndTransientApproval() {
        application = RuntimeEnvironment.getApplication();
        application.getSharedPreferences("cosmo", Context.MODE_PRIVATE).edit().clear().commit();
        keyguard = (KeyguardManager) application.getSystemService(Context.KEYGUARD_SERVICE);
        Shadows.shadowOf(keyguard).setKeyguardLocked(false);
        Shadows.shadowOf(keyguard).setIsDeviceLocked(false);
        features = new PhoneFeatures(application);
        features.execute(new Command(Command.Type.CANCEL));
        Shadows.shadowOf(application).denyPermissions(Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE);
        setWriteSettings(false);
        contacts = new TestContactsProvider();
        ProviderInfo info = new ProviderInfo();
        info.authority = ContactsContract.AUTHORITY;
        contacts.attachInfo(application, info);
        ShadowContentResolver.registerProviderInternal(ContactsContract.AUTHORITY, contacts);
    }

    @Test public void callNeverStartsBeforeExplicitApprovalAndApprovalIsOneShot() {
        Shadows.shadowOf(application).grantPermissions(Manifest.permission.CALL_PHONE);
        DeviceActions.Result staged = features.execute(new Command(Command.Type.CALL, "+90 (555) 111-2233"));
        assertNull(staged.intent);
        assertTrue(staged.speech.contains("+905551112233"));
        assertTrue(staged.speech.contains("Feri"));
        assertNull(Shadows.shadowOf(application).getNextStartedActivity());

        // An independent action executor instance shares the same in-memory pending command.
        DeviceActions.Result confirmed = new PhoneFeatures(application).execute(new Command(Command.Type.CONFIRM));
        assertNotNull(confirmed.intent);
        assertEquals(Intent.ACTION_CALL, confirmed.intent.getAction());
        assertEquals("tel", confirmed.intent.getScheme());
        assertEquals("+905551112233", confirmed.intent.getData().getSchemeSpecificPart());
        assertNull(Shadows.shadowOf(application).getNextStartedActivity());
        assertNull(features.execute(new Command(Command.Type.CONFIRM)).intent);
    }

    @Test public void callRequiresPermissionBothWhenStagedAndWhenApproved() {
        assertNull(features.execute(new Command(Command.Type.CALL, "05551112233")).intent);
        assertNull(features.execute(new Command(Command.Type.CONFIRM)).intent);
        Shadows.shadowOf(application).grantPermissions(Manifest.permission.CALL_PHONE);
        features.execute(new Command(Command.Type.CALL, "05551112233"));
        Shadows.shadowOf(application).denyPermissions(Manifest.permission.CALL_PHONE);
        DeviceActions.Result denied = features.execute(new Command(Command.Type.CONFIRM));
        assertNull(denied.intent);
        assertTrue(denied.speech.contains("izni kapatılmış"));
        Shadows.shadowOf(application).grantPermissions(Manifest.permission.CALL_PHONE);
        assertNull(features.execute(new Command(Command.Type.CONFIRM)).intent);
    }

    @Test public void expiredApprovalCannotDial() {
        Shadows.shadowOf(application).grantPermissions(Manifest.permission.CALL_PHONE);
        features.execute(new Command(Command.Type.CALL, "05551112233"));
        ShadowSystemClock.advanceBy(Duration.ofSeconds(30));
        DeviceActions.Result expired = features.execute(new Command(Command.Type.CONFIRM));
        assertNull(expired.intent);
        assertTrue(expired.speech.contains("süresi doldu"));
        assertNull(features.execute(new Command(Command.Type.CONFIRM)).intent);
    }

    @Test public void lockAtApprovalConsumesPendingCallWithoutDialing() {
        Shadows.shadowOf(application).grantPermissions(Manifest.permission.CALL_PHONE);
        features.execute(new Command(Command.Type.CALL, "05551112233"));
        Shadows.shadowOf(keyguard).setKeyguardLocked(true);
        DeviceActions.Result locked = features.execute(new Command(Command.Type.CONFIRM));
        assertNull(locked.intent);
        assertTrue(locked.speech.contains("kilidini açmalısın"));
        Shadows.shadowOf(keyguard).setKeyguardLocked(false);
        assertNull(features.execute(new Command(Command.Type.CONFIRM)).intent);
    }

    @Test @Config(sdk = {28, 36})
    public void screenOffThenUnlockCancelsApprovalEvenWhenNoVoiceServiceRuns() {
        Shadows.shadowOf(application).grantPermissions(Manifest.permission.CALL_PHONE);
        features.execute(new Command(Command.Type.CALL, "05551112233"));
        assertTrue(Shadows.shadowOf(application).hasReceiverForIntent(new Intent(Intent.ACTION_SCREEN_OFF)));

        Shadows.shadowOf(keyguard).setKeyguardLocked(true);
        application.sendBroadcast(new Intent(Intent.ACTION_SCREEN_OFF));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        Shadows.shadowOf(keyguard).setKeyguardLocked(false);

        DeviceActions.Result result = new DeviceActions(application).execute(new Command(Command.Type.CONFIRM));
        assertNull(result.intent);
        assertTrue(result.speech.contains("Onay bekleyen bir işlem yok"));
        assertFalse(Shadows.shadowOf(application).hasReceiverForIntent(new Intent(Intent.ACTION_SCREEN_OFF)));
        assertNull(Shadows.shadowOf(application).getNextStartedActivity());
    }

    @Test @Config(sdk = {28, 36})
    public void unlockBroadcastCancelsApprovalIfScreenOffBroadcastWasDeferred() {
        Shadows.shadowOf(application).grantPermissions(Manifest.permission.CALL_PHONE);
        features.execute(new Command(Command.Type.CALL, "05551112233"));

        application.sendBroadcast(new Intent(Intent.ACTION_USER_PRESENT));
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        DeviceActions.Result result = features.execute(new Command(Command.Type.CONFIRM));
        assertNull(result.intent);
        assertTrue(result.speech.contains("Onay bekleyen bir işlem yok"));
        assertFalse(Shadows.shadowOf(application).hasReceiverForIntent(new Intent(Intent.ACTION_USER_PRESENT)));
    }

    @Test @Config(sdk = {28, 36})
    public void approvalReceiverIsReleasedOnConsumptionCancelAndIdleExpiry() {
        Shadows.shadowOf(application).grantPermissions(Manifest.permission.CALL_PHONE);
        Intent lockEvent = new Intent(Intent.ACTION_SCREEN_OFF);
        features.execute(new Command(Command.Type.CALL, "05551112233"));
        assertTrue(Shadows.shadowOf(application).hasReceiverForIntent(lockEvent));
        assertNotNull(features.execute(new Command(Command.Type.CONFIRM)).intent);
        assertFalse(Shadows.shadowOf(application).hasReceiverForIntent(lockEvent));

        features.execute(new Command(Command.Type.CALL, "05551112233"));
        assertTrue(Shadows.shadowOf(application).hasReceiverForIntent(lockEvent));
        features.execute(new Command(Command.Type.CANCEL));
        assertFalse(Shadows.shadowOf(application).hasReceiverForIntent(lockEvent));

        features.execute(new Command(Command.Type.CALL, "05551112233"));
        assertTrue(Shadows.shadowOf(application).hasReceiverForIntent(lockEvent));
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(30_001L));
        assertFalse(Shadows.shadowOf(application).hasReceiverForIntent(lockEvent));
        assertNull(features.execute(new Command(Command.Type.CONFIRM)).intent);
    }

    @Test public void deviceLockAndNewUnrelatedCommandsCancelApproval() {
        Shadows.shadowOf(application).grantPermissions(Manifest.permission.CALL_PHONE);
        features.execute(new Command(Command.Type.CALL, "05551112233"));
        Shadows.shadowOf(keyguard).setIsDeviceLocked(true);
        assertNull(features.execute(new Command(Command.Type.CONFIRM)).intent);
        Shadows.shadowOf(keyguard).setIsDeviceLocked(false);
        features.execute(new Command(Command.Type.CALL, "05551112233"));
        assertNull(features.execute(new Command(Command.Type.TIME)));
        assertNull(features.execute(new Command(Command.Type.CONFIRM)).intent);
        features.execute(new Command(Command.Type.CALL, "05551112233"));
        features.execute(new Command(Command.Type.CANCEL));
        assertNull(features.execute(new Command(Command.Type.CONFIRM)).intent);
    }

    @Test public void callRejectsDialControlCharactersAndInvalidDestinations() {
        Shadows.shadowOf(application).grantPermissions(Manifest.permission.CALL_PHONE);
        for (String value : new String[]{"*123#", "05551112233,1234", "05551112233;1234", "tel:123", "abc123", "12", "+1234567890123456"}) {
            assertNull(features.execute(new Command(Command.Type.CALL, value)).intent);
            assertNull(features.execute(new Command(Command.Type.CONFIRM)).intent);
        }
    }

    @Test public void specialSettingsPermissionOpensOnlyThisAppsGrantPage() {
        Settings.System.putInt(application.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, 95);
        DeviceActions.Result result = features.execute(new Command(Command.Type.BRIGHTNESS_SET, "75"));
        assertNotNull(result.intent);
        assertEquals(Settings.ACTION_MANAGE_WRITE_SETTINGS, result.intent.getAction());
        assertEquals("package:" + application.getPackageName(), result.intent.getDataString());
        assertEquals(95, Settings.System.getInt(application.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, -1));
        assertEquals(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                features.execute(new Command(Command.Type.ROTATION_AUTO)).intent.getAction());
    }

    @Test public void grantedBrightnessAndRotationChangeOnlyTheirRequestedSettings() {
        setWriteSettings(true);
        assertTrue(Settings.System.canWrite(application));
        Settings.System.putInt(application.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, 95);
        assertNull(features.execute(new Command(Command.Type.BRIGHTNESS_SET, "75")).intent);
        assertEquals(191, Settings.System.getInt(application.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, -1));
        assertEquals(Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
                Settings.System.getInt(application.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE, -1));
        features.execute(new Command(Command.Type.BRIGHTNESS_SET, "200"));
        assertEquals(191, Settings.System.getInt(application.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, -1));
        features.execute(new Command(Command.Type.ROTATION_AUTO));
        assertEquals(1, Settings.System.getInt(application.getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, -1));
        features.execute(new Command(Command.Type.ROTATION_PORTRAIT));
        assertEquals(0, Settings.System.getInt(application.getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, -1));
        assertEquals(0, Settings.System.getInt(application.getContentResolver(), Settings.System.USER_ROTATION, -1));
    }

    @Test public void contactsPermissionIsOptionalAndMissingGrantNeverQueriesRehber() {
        DeviceActions.Result result = features.execute(new Command(Command.Type.DIAL_CONTACT, "Öykü Yılmaz"));
        assertNull(result.intent);
        assertTrue(result.speech.contains("rehber izni"));
        assertEquals(0, contacts.queries);
    }

    @Test public void exactUniqueContactOpensDialUiAndPreservesMessageBody() {
        Shadows.shadowOf(application).grantPermissions(Manifest.permission.READ_CONTACTS);
        contacts.add(8, "Öykü Yılmaz", "+90 555 111 22 33");
        contacts.add(9, "Öykü Yılmaz İş", "05559998877");
        DeviceActions.Result dial = features.execute(new Command(Command.Type.DIAL_CONTACT, "öykü yılmaz"));
        assertNotNull(dial.intent);
        assertEquals(Intent.ACTION_DIAL, dial.intent.getAction());
        assertEquals("tel", dial.intent.getScheme());
        assertEquals("+905551112233", dial.intent.getData().getSchemeSpecificPart());
        assertNull(Shadows.shadowOf(application).getNextStartedActivity());
        String body = "İstanbul'dayım & saat 19:30 #1";
        DeviceActions.Result sms = features.execute(new Command(Command.Type.COMPOSE_SMS_CONTACT, "Öykü Yılmaz", body));
        assertNotNull(sms.intent);
        assertEquals(Intent.ACTION_SENDTO, sms.intent.getAction());
        assertEquals("smsto", sms.intent.getScheme());
        assertEquals("+905551112233", sms.intent.getData().getSchemeSpecificPart());
        assertEquals(body, sms.intent.getStringExtra("sms_body"));
        assertTrue(sms.speech.contains("gönder düğmesine sen basmalısın"));
        assertNull(Shadows.shadowOf(application).getNextStartedActivity());
    }

    @Test public void partialOrInventedFamilyNamesNeverSelectAContact() {
        Shadows.shadowOf(application).grantPermissions(Manifest.permission.READ_CONTACTS);
        contacts.add(1, "Anne İş", "05551112233");
        contacts.add(2, "Öykü Yılmaz", "05554445566");
        assertNull(features.execute(new Command(Command.Type.DIAL_CONTACT, "Anne")).intent);
        assertNull(features.execute(new Command(Command.Type.DIAL_CONTACT, "Öykü")).intent);
    }

    @Test public void duplicateNamesOpenPickerWithoutAnyDestinationOrMessage() {
        Shadows.shadowOf(application).grantPermissions(Manifest.permission.READ_CONTACTS);
        contacts.add(1, "Ali Kaya", "05551112233");
        contacts.add(2, "Ali Kaya", "05554445566");
        DeviceActions.Result result = features.execute(new Command(Command.Type.COMPOSE_SMS_CONTACT, "Ali Kaya", "Özel mesaj"));
        assertNotNull(result.intent);
        assertEquals(Intent.ACTION_PICK, result.intent.getAction());
        assertEquals(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, result.intent.getData());
        assertNull(result.intent.getStringExtra("sms_body"));
        assertFalse(result.speech.contains("Özel mesaj"));
    }

    @Test public void multipleNumbersOpenPersonCardWithoutChoosingOne() {
        Shadows.shadowOf(application).grantPermissions(Manifest.permission.READ_CONTACTS);
        contacts.add(1, "Ali Kaya", "05551112233", "05554445566");
        DeviceActions.Result result = features.execute(new Command(Command.Type.DIAL_CONTACT, "Ali Kaya"));
        assertNotNull(result.intent);
        assertEquals(Intent.ACTION_VIEW, result.intent.getAction());
        assertEquals(Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_URI, "1"), result.intent.getData());
        assertTrue(result.speech.contains("birden fazla numarası"));
    }

    @Test public void lockedPhoneDoesNotReadContactsEvenWithPermission() {
        Shadows.shadowOf(application).grantPermissions(Manifest.permission.READ_CONTACTS);
        contacts.add(1, "Ali Kaya", "05551112233");
        Shadows.shadowOf(keyguard).setKeyguardLocked(true);
        DeviceActions.Result result = features.execute(new Command(Command.Type.DIAL_CONTACT, "Ali Kaya"));
        assertNull(result.intent);
        assertEquals(0, contacts.queries);
    }

    @Test public void camerasAndCalendarOpenManualUisWithoutCapturingOrSaving() {
        Intent photo = features.execute(new Command(Command.Type.CAMERA_PHOTO)).intent;
        Intent video = features.execute(new Command(Command.Type.CAMERA_VIDEO)).intent;
        assertEquals(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA, photo.getAction());
        assertEquals(MediaStore.INTENT_ACTION_VIDEO_CAMERA, video.getAction());
        Intent calendar = features.execute(new Command(Command.Type.CALENDAR_INSERT, "Doktor kontrolü")).intent;
        assertEquals(Intent.ACTION_INSERT, calendar.getAction());
        assertEquals(CalendarContract.Events.CONTENT_URI, calendar.getData());
        assertEquals("Doktor kontrolü", calendar.getStringExtra(CalendarContract.Events.TITLE));
        assertNull(Shadows.shadowOf(application).getNextStartedActivity());
    }

    @Test public void screenMutationsNeedSeparateOptInAndNeverLeavePendingApproval() {
        for (Command.Type type : new Command.Type[]{Command.Type.SCREEN_CLICK, Command.Type.SCREEN_TYPE}) {
            DeviceActions.Result result = features.execute(new Command(type, "Gönder"));
            assertNull(result.intent);
            assertTrue(result.speech.contains("ekran kontrolünü etkinleştirmelisin"));
            assertNull(features.execute(new Command(Command.Type.CONFIRM)).intent);
        }
        assertTrue(features.execute(new Command(Command.Type.SCREENSHOT)).speech.contains("ekran kontrolünü etkinleştirmelisin"));
    }

    @Test @Config(shadows = RootAccessibilityShadow.class)
    public void screenClickNeedsApprovalAndCanExecuteOnlyOnce() {
        ServiceController<CosmoAccessibilityService> controller = bindScreenController();
        AccessibilityNodeInfo button = screenButton("Gönder");
        Shadows.shadowOf(RootAccessibilityShadow.root).addChild(button);
        try {
            DeviceActions.Result staged = features.execute(new Command(Command.Type.SCREEN_CLICK, "Gönder"));
            assertTrue(staged.speech.contains("onayla"));
            assertTrue(Shadows.shadowOf(button).getPerformedActions().isEmpty());
            assertEquals("Düğmeye bastım.", features.execute(new Command(Command.Type.CONFIRM)).speech);
            assertEquals(1, Shadows.shadowOf(button).getPerformedActions().size());
            features.execute(new Command(Command.Type.CONFIRM));
            assertEquals(1, Shadows.shadowOf(button).getPerformedActions().size());
        } finally {
            controller.destroy();
        }
    }

    @Test @Config(shadows = RootAccessibilityShadow.class)
    public void changedRecipientCancelsClickEvenWhenButtonLabelIsUnchanged() {
        ServiceController<CosmoAccessibilityService> controller = bindScreenController();
        AccessibilityNodeInfo recipient = screenButton("Alıcı: Öykü");
        AccessibilityNodeInfo send = screenButton("Gönder");
        Shadows.shadowOf(RootAccessibilityShadow.root).addChild(recipient);
        Shadows.shadowOf(RootAccessibilityShadow.root).addChild(send);
        try {
            features.execute(new Command(Command.Type.SCREEN_CLICK, "Gönder"));
            recipient.setText("Alıcı: Başka kişi");
            DeviceActions.Result result = features.execute(new Command(Command.Type.CONFIRM));
            assertTrue(result.speech.contains("Ekran değiştiği"));
            assertTrue(Shadows.shadowOf(send).getPerformedActions().isEmpty());
            features.execute(new Command(Command.Type.CONFIRM));
            assertTrue(Shadows.shadowOf(send).getPerformedActions().isEmpty());
        } finally {
            controller.destroy();
        }
    }

    @Test @Config(shadows = RootAccessibilityShadow.class)
    public void disablingScreenConsentAfterRequestPreventsApprovedClick() {
        ServiceController<CosmoAccessibilityService> controller = bindScreenController();
        AccessibilityNodeInfo send = screenButton("Gönder");
        Shadows.shadowOf(RootAccessibilityShadow.root).addChild(send);
        try {
            features.execute(new Command(Command.Type.SCREEN_CLICK, "Gönder"));
            application.getSharedPreferences("cosmo", Context.MODE_PRIVATE).edit()
                    .putBoolean("screen_control_enabled", false).commit();
            assertTrue(features.execute(new Command(Command.Type.CONFIRM)).speech.contains("ekran kontrolünü etkinleştirmelisin"));
            application.getSharedPreferences("cosmo", Context.MODE_PRIVATE).edit()
                    .putBoolean("screen_control_enabled", true).commit();
            features.execute(new Command(Command.Type.CONFIRM));
            assertTrue(Shadows.shadowOf(send).getPerformedActions().isEmpty());
        } finally {
            controller.destroy();
        }
    }

    @Test @Config(shadows = RootAccessibilityShadow.class)
    public void screenDictationNeverWritesBeforeApprovalAndReplacesOnlyFocusedField() {
        ServiceController<CosmoAccessibilityService> controller = bindScreenController();
        AccessibilityNodeInfo input = screenButton("Eski yazı");
        input.setEditable(true);
        input.setFocused(true);
        Shadows.shadowOf(RootAccessibilityShadow.root).addChild(input);
        try {
            features.execute(new Command(Command.Type.SCREEN_TYPE, "Merhaba Öykü"));
            assertTrue(Shadows.shadowOf(input).getPerformedActions().isEmpty());
            assertEquals("Metni seçili alana yazdım.", features.execute(new Command(Command.Type.CONFIRM)).speech);
            assertEquals(1, Shadows.shadowOf(input).getPerformedActionsWithArgs().size());
            assertEquals("Merhaba Öykü", Shadows.shadowOf(input).getPerformedActionsWithArgs().get(0).second
                    .getCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE));
        } finally {
            controller.destroy();
        }
    }

    private ServiceController<CosmoAccessibilityService> bindScreenController() {
        application.getSharedPreferences("cosmo", Context.MODE_PRIVATE).edit()
                .putBoolean("accessibility_actions_enabled", true)
                .putBoolean("screen_control_enabled", true).commit();
        RootAccessibilityShadow.root = screenButton("");
        RootAccessibilityShadow.root.setPackageName("com.example.messages");
        ServiceController<CosmoAccessibilityService> controller = Robolectric.buildService(CosmoAccessibilityService.class).create();
        controller.get().onServiceConnected();
        return controller;
    }

    private static AccessibilityNodeInfo screenButton(String label) {
        AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain();
        node.setVisibleToUser(true);
        node.setEnabled(true);
        node.setClickable(true);
        node.setText(label);
        Shadows.shadowOf(node).setOnPerformActionListener((action, arguments) -> true);
        return node;
    }

    @Implements(AccessibilityService.class)
    public static class RootAccessibilityShadow extends ShadowAccessibilityService {
        static AccessibilityNodeInfo root;
        @Implementation protected AccessibilityNodeInfo getRootInActiveWindow() {
            return root == null ? null : AccessibilityNodeInfo.obtain(root);
        }
    }

    private void setWriteSettings(boolean enabled) {
        AppOpsManager appOps = (AppOpsManager) application.getSystemService(Context.APP_OPS_SERVICE);
        Shadows.shadowOf(appOps).setMode(AppOpsManager.OPSTR_WRITE_SETTINGS, Process.myUid(), application.getPackageName(),
                enabled ? AppOpsManager.MODE_ALLOWED : AppOpsManager.MODE_ERRORED);
    }

    /** Queries use a deliberately small local provider; no real phone contacts are involved. */
    public static final class TestContactsProvider extends ContentProvider {
        final List<TestPerson> people = new ArrayList<>();
        int queries;
        void add(long id, String label, String... numbers) { people.add(new TestPerson(id, label, numbers)); }
        @Override public boolean onCreate() { return true; }
        @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
            queries++;
            MatrixCursor cursor = new MatrixCursor(projection);
            if (ContactsContract.Contacts.CONTENT_URI.equals(uri)) {
                for (TestPerson person : people) cursor.addRow(new Object[]{person.id, person.label});
            } else if (ContactsContract.CommonDataKinds.Phone.CONTENT_URI.equals(uri)) {
                assertEquals(ContactsContract.CommonDataKinds.Phone.CONTACT_ID + " = ?", selection);
                for (TestPerson person : people) if (Long.toString(person.id).equals(args[0])) {
                    for (String number : person.numbers) cursor.addRow(new Object[]{number});
                }
            }
            return cursor;
        }
        @Override public String getType(Uri uri) { return null; }
        @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
        @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
    }

    private static final class TestPerson {
        final long id;
        final String label;
        final String[] numbers;
        TestPerson(long id, String label, String[] numbers) { this.id = id; this.label = label; this.numbers = numbers; }
    }
}
