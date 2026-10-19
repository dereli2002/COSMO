package com.cosmo.assistant;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Consumes complete utterances only. Audio and partial recognition never trigger an action. */
public final class WakeWordGate {
    public static final long COMMAND_WINDOW_MS = 10_000L;
    private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{N}]+");
    private long deadline = -1L;

    /**
     * Returns one command, or null when a wake phrase/command is still needed.
     * The caller supplies a monotonic clock, e.g. Android's elapsedRealtime().
     */
    public String accept(String utterance, long nowMs) {
        String text = utterance == null ? "" : utterance.trim();
        if (text.isEmpty()) return null;
        if (deadline >= 0L && nowMs > deadline) deadline = -1L;

        Matcher token = TOKEN.matcher(text);
        if (!token.find()) return null;
        String first = token.group().toLowerCase(Locale.forLanguageTag("tr-TR"));
        if (first.equals("hey") || first.equals("merhaba")) {
            if (!token.find()) return consumeArmedText(text);
            first = token.group().toLowerCase(Locale.forLanguageTag("tr-TR"));
        }
        boolean hasWake = isWakeWord(first);
        if (hasWake) {
            // Strip only wake separators. Preserve clock punctuation, case, notes and SMS bodies.
            String command = text.substring(token.end()).replaceFirst("^[\\s,.:;!?…\\-–—]+", "").trim();
            if (command.isEmpty()) {
                deadline = nowMs + COMMAND_WINDOW_MS;
                return null;
            }
            deadline = -1L;
            return command;
        }

        return consumeArmedText(text);
    }

    private String consumeArmedText(String text) {
        if (deadline >= 0L) {
            deadline = -1L;
            return text;
        }
        return null;
    }

    public boolean isAwaitingCommand(long nowMs) {
        return deadline >= 0L && nowMs <= deadline;
    }

    public void reset() {
        deadline = -1L;
    }

    private static boolean isWakeWord(String word) {
        // Turkish recognizers may spell COSMO differently; "asistan" is a documented fallback.
        return word.equals("cosmo") || word.equals("kosmo") || word.equals("kozmo")
                || word.equals("kozmos") || word.equals("asistan");
    }

}
