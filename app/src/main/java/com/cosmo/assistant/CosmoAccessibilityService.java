package com.cosmo.assistant;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Path;
import android.util.DisplayMetrics;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.lang.ref.WeakReference;

/** Acts only on explicit user commands. Accessibility events are never inspected or retained. */
public final class CosmoAccessibilityService extends AccessibilityService {
    private static WeakReference<CosmoAccessibilityService> instance = new WeakReference<>(null);

    @Override protected void onServiceConnected() {
        instance = new WeakReference<>(this);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        // Screen content and accessibility events are deliberately not collected.
    }

    @Override public void onInterrupt() { }

    @Override public void onDestroy() {
        if (instance.get() == this) instance.clear();
        super.onDestroy();
    }

    public static boolean isAvailable(Context context) {
        return context.getSharedPreferences("cosmo", Context.MODE_PRIVATE)
                .getBoolean("accessibility_actions_enabled", false) && instance.get() != null;
    }

    public static boolean isScreenControlAvailable(Context context) {
        return isAvailable(context) && context.getSharedPreferences("cosmo", Context.MODE_PRIVATE)
                .getBoolean("screen_control_enabled", false);
    }

    public static boolean navigate(Context context, int globalAction) {
        CosmoAccessibilityService service = instance.get();
        if (!isAvailable(context) || service == null || !isUnlocked(context)) return false;
        try {
            return service.performGlobalAction(globalAction);
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    /** Background app launching is permitted by Android while this user-enabled service is bound. */
    public static boolean openExternalActivity(Context context, Intent intent) {
        CosmoAccessibilityService service = instance.get();
        if (intent == null || !isAvailable(context) || service == null || !isUnlocked(context)) return false;
        try {
            service.startActivity(new Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    public static String readScreen(Context context) {
        if (!isUnlocked(context)) return "Bu işlem için önce telefonun kilidini açmalısın.";
        if (!isScreenControlAvailable(context)) return "Önce Feri içinden isteğe bağlı ekran kontrolünü etkinleştirmelisin.";
        AccessibilityNodeInfo root = activeRoot(context);
        if (root == null) return "Şu an ekran içeriğine erişemiyorum. Uygulamayı açıp yeniden dene.";
        try {
            return ScreenController.read(root);
        } catch (RuntimeException unavailable) {
            return "Bu ekrandaki yazıları okuyamadım.";
        } finally {
            root.recycle();
        }
    }

    /** Caller must obtain a fresh explicit voice confirmation before invoking this method. */
    public static boolean clickLabel(Context context, String label) {
        return clickLabel(context, label, null);
    }

    public static boolean clickLabel(Context context, String label, String expectedContextKey) {
        AccessibilityNodeInfo root = activeRoot(context);
        if (root == null) return false;
        try {
            if (expectedContextKey != null && !expectedContextKey.equals(ScreenController.contextKey(root))) return false;
            return ScreenController.click(root, label);
        } catch (RuntimeException unavailable) {
            return false;
        } finally {
            root.recycle();
        }
    }

    /** Replaces only the focused ordinary text field, after explicit voice confirmation. */
    public static boolean typeText(Context context, String text) {
        return typeText(context, text, null);
    }

    public static boolean typeText(Context context, String text, String expectedContextKey) {
        AccessibilityNodeInfo root = activeRoot(context);
        if (root == null) return false;
        try {
            if (expectedContextKey != null && !expectedContextKey.equals(ScreenController.contextKey(root))) return false;
            return ScreenController.type(root, text);
        } catch (RuntimeException unavailable) {
            return false;
        } finally {
            root.recycle();
        }
    }

    /** A private, transient fingerprint lets the caller reject approval after screen changes. */
    public static String screenContextKey(Context context) {
        AccessibilityNodeInfo root = activeRoot(context);
        if (root == null) return null;
        try {
            return ScreenController.contextKey(root);
        } catch (RuntimeException unavailable) {
            return null;
        } finally {
            root.recycle();
        }
    }

    private static AccessibilityNodeInfo activeRoot(Context context) {
        CosmoAccessibilityService service = instance.get();
        if (!isScreenControlAvailable(context) || service == null || !isUnlocked(context)) return null;
        try {
            return service.getRootInActiveWindow();
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    private static boolean isUnlocked(Context context) {
        KeyguardManager keyguard = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
        return keyguard != null && !keyguard.isKeyguardLocked() && !keyguard.isDeviceLocked();
    }

    /** A direct swipe; no node inspection, reading, or automatic clicking is used. */
    public static boolean scroll(Context context, boolean down) {
        CosmoAccessibilityService service = instance.get();
        if (!isAvailable(context) || service == null || !isUnlocked(context)) return false;
        DisplayMetrics metrics = service.getResources().getDisplayMetrics();
        float x = metrics.widthPixels * 0.5f;
        float top = metrics.heightPixels * 0.35f;
        float bottom = metrics.heightPixels * 0.72f;
        Path path = new Path();
        path.moveTo(x, down ? bottom : top);
        path.lineTo(x, down ? top : bottom);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 350))
                .build();
        try {
            return service.dispatchGesture(gesture, null, null);
        } catch (RuntimeException unavailable) {
            return false;
        }
    }
}
