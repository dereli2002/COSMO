package com.cosmo.assistant;

import android.os.Bundle;
import android.text.InputType;
import android.view.accessibility.AccessibilityNodeInfo;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Bounded, command-only inspection of the currently visible accessibility tree. */
final class ScreenController {
    static final int MAX_NODES = 500;
    static final int MAX_READ_CHARS = 4000;
    private static final int MAX_TEXT_CHARS = 2000;
    private static final Locale TURKISH = Locale.forLanguageTag("tr-TR");

    private ScreenController() { }

    static boolean isAllowedRoot(AccessibilityNodeInfo root) {
        if (root == null || root.getPackageName() == null) return false;
        String name = root.getPackageName().toString().toLowerCase(Locale.ROOT);
        // Android permissions, installation, unlocking and security choices remain manual.
        return !name.equals("android") && !name.contains(".settings")
                && !name.contains(".systemui") && !name.contains(".keyguard")
                && !name.contains("permissioncontroller")
                && !name.contains("packageinstaller")
                && !name.contains("credentialmanager")
                && !name.contains("securitycenter")
                && !name.contains("securitypermission");
    }

    static String read(AccessibilityNodeInfo root) {
        if (!isAllowedRoot(root)) return "Bu ekrandaki güvenlik ve izin işlemlerini telefondan yapmalısın.";
        StringBuilder spoken = new StringBuilder();
        Set<String> seen = new LinkedHashSet<>();
        for (AccessibilityNodeInfo node : nodes(root)) {
            if (!node.isVisibleToUser() || sensitive(node)) continue;
            // Do not speak the contents of editable fields (including an unmasked secret).
            String hint = value(node.getHintText()).trim();
            String label = node.isEditable() ? "Yazı alanı" + (hint.isEmpty() ? "" : ": " + hint) : label(node);
            if (label.isEmpty() || !seen.add(normalize(label))) continue;
            if (spoken.length() > 0) {
                if (spoken.length() + 2 >= MAX_READ_CHARS) break;
                spoken.append(". ");
            }
            int remaining = MAX_READ_CHARS - spoken.length();
            if (remaining <= 0) break;
            spoken.append(label, 0, Math.min(label.length(), remaining));
            if (spoken.length() >= MAX_READ_CHARS) break;
        }
        return spoken.length() == 0 ? "Bu ekranda okuyabildiğim bir yazı yok." : spoken.toString();
    }

    static boolean click(AccessibilityNodeInfo root, String requestedLabel) {
        if (!isAllowedRoot(root)) return false;
        String requested = normalize(requestedLabel);
        if (requested.isEmpty() || requested.length() > 300) return false;
        List<AccessibilityNodeInfo> candidates = new ArrayList<>();
        for (AccessibilityNodeInfo node : nodes(root)) {
            if (!node.isVisibleToUser() || sensitive(node)) continue;
            if (!requested.equals(normalize(value(node.getText())))
                    && !requested.equals(normalize(value(node.getContentDescription())))
                    && !requested.equals(normalize(value(node.getHintText())))) continue;
            AccessibilityNodeInfo target = clickableAncestor(node);
            if (target != null && !candidates.contains(target)) candidates.add(target);
        }
        // A spoken label must identify exactly one currently visible control.
        return candidates.size() == 1
                && candidates.get(0).performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }

    static boolean type(AccessibilityNodeInfo root, String text) {
        if (!isAllowedRoot(root) || text == null || text.trim().isEmpty() || text.length() > MAX_TEXT_CHARS) return false;
        AccessibilityNodeInfo focused = focusedEditable(root);
        if (focused == null || sensitive(focused)) return false;
        Bundle arguments = new Bundle();
        arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments);
    }

    /** Stored only in memory during voice confirmation; never contains readable screen text. */
    static String contextKey(AccessibilityNodeInfo root) {
        if (!isAllowedRoot(root)) return null;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (AccessibilityNodeInfo node : nodes(root)) {
                if (!node.isVisibleToUser() || sensitive(node)) continue;
                String identity = value(node.getViewIdResourceName()) + '\u0000'
                        + value(node.getClassName()) + '\u0000'
                        + label(node) + '\u0000'
                        + node.isClickable() + node.isEnabled() + node.isFocused() + node.isEditable();
                digest.update(identity.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\n');
            }
            StringBuilder hex = new StringBuilder();
            for (byte item : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", item & 0xff));
            return root.getPackageName() + "|" + root.getWindowId() + "|" + hex;
        } catch (NoSuchAlgorithmException unavailable) {
            return null;
        }
    }

    private static AccessibilityNodeInfo focusedEditable(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo focused = null;
        for (AccessibilityNodeInfo node : nodes(root)) {
            if (!node.isVisibleToUser() || !node.isEnabled() || !node.isEditable() || !node.isFocused()) continue;
            if (focused != null) return null;
            focused = node;
        }
        return focused;
    }

    private static AccessibilityNodeInfo clickableAncestor(AccessibilityNodeInfo node) {
        for (int depth = 0; node != null && depth < 10; depth++, node = node.getParent()) {
            if (!node.isVisibleToUser() || !node.isEnabled() || sensitive(node)) return null;
            if (node.isClickable()) return node;
        }
        return null;
    }

    private static boolean sensitive(AccessibilityNodeInfo node) {
        if (node.isPassword()) return true;
        int input = node.getInputType();
        int type = input & InputType.TYPE_MASK_CLASS;
        int variation = input & InputType.TYPE_MASK_VARIATION;
        if (type == InputType.TYPE_CLASS_TEXT
                && (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD
                || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) return true;
        if (type == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD) return true;
        AccessibilityNodeInfo labeledBy = node.getLabeledBy();
        String associatedLabel = labeledBy == null ? "" : value(labeledBy.getText()) + " "
                + value(labeledBy.getContentDescription());
        String identity = normalize(value(node.getViewIdResourceName()) + " "
                + value(node.getContentDescription()) + " " + value(node.getHintText()) + " " + associatedLabel);
        return identity.matches(".*(?:password|passcode|sifre|parola|verification|one.?time|security.?code|guvenlik.?kodu|dogrulama|tek.?kullanimlik|card.?number|kart.?numarasi|cvv|cvc).*" )
                || identity.matches(".*(?:^|[^a-z0-9])(?:pin|otp)(?:[^a-z0-9]|$).*" );
    }

    private static List<AccessibilityNodeInfo> nodes(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> result = new ArrayList<>();
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty() && result.size() < MAX_NODES) {
            AccessibilityNodeInfo node = queue.removeFirst();
            result.add(node);
            // Bound even malformed/custom trees; do not inspect off-screen private fields.
            if (sensitive(node)) continue;
            int available = MAX_NODES - result.size() - queue.size();
            int children = Math.min(node.getChildCount(), Math.max(0, available));
            for (int index = 0; index < children; index++) {
                AccessibilityNodeInfo child = node.getChild(index);
                if (child != null) queue.addLast(child);
            }
        }
        return result;
    }

    private static String label(AccessibilityNodeInfo node) {
        String text = value(node.getText()).trim();
        if (!text.isEmpty()) return text;
        String description = value(node.getContentDescription()).trim();
        return description.isEmpty() ? value(node.getHintText()).trim() : description;
    }

    private static String value(CharSequence text) {
        if (text == null) return "";
        return text.subSequence(0, Math.min(text.length(), MAX_READ_CHARS)).toString();
    }

    static String normalize(String text) {
        if (text == null) return "";
        return Normalizer.normalize(text.toLowerCase(TURKISH), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").replace('ı', 'i').replaceAll("\\s+", " ").trim();
    }
}
