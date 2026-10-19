package com.cosmo.assistant;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.graphics.Path;
import android.util.DisplayMetrics;
import android.view.accessibility.AccessibilityEvent;

import java.lang.ref.WeakReference;

/** Performs only navigation actions explicitly requested by the user. Never reads screen content. */
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

    public static boolean navigate(Context context, int globalAction) {
        CosmoAccessibilityService service = instance.get();
        return isAvailable(context) && service != null && service.performGlobalAction(globalAction);
    }

    /** A direct swipe; no node inspection, reading, or automatic clicking is used. */
    public static boolean scroll(Context context, boolean down) {
        CosmoAccessibilityService service = instance.get();
        if (!isAvailable(context) || service == null) return false;
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
        return service.dispatchGesture(gesture, null, null);
    }
}
