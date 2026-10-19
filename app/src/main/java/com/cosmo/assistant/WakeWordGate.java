package com.cosmo.assistant;

/**
 * Authorizes exactly one Turkish utterance after the separate FERİ wake recognizer.
 * A caller must pass complete results only and start the window after the acknowledgement.
 */
public final class WakeWordGate {
    public static final long COMMAND_WINDOW_MS = 10_000L;
    // Calibrated with the wake recognizer's ferry/perry/[unk] grammar. Perry is a decoy,
    // never an accepted wake word. Physical microphone accuracy still needs device testing.
    public static final double MIN_WAKE_CONFIDENCE = 0.65;
    private long deadline = -1L;

    /**
     * "ferry" is the wake model's vocabulary word for the spoken name Feri.
     * A word confidence is meaningful only with the separate wake model's complete result. */
    public static boolean isFeriWake(String utterance, double confidence) {
        return utterance != null && "ferry".equalsIgnoreCase(utterance.trim())
                && !Double.isNaN(confidence) && !Double.isInfinite(confidence)
                && confidence >= MIN_WAKE_CONFIDENCE && confidence <= 1.0;
    }

    public void armFeri(long nowMs) {
        deadline = nowMs + COMMAND_WINDOW_MS;
    }

    /** Keeps the user's punctuation and case, and consumes authorization only for nonempty text. */
    public String acceptCommand(String utterance, long nowMs) {
        if (!isAwaitingCommand(nowMs)) {
            deadline = -1L;
            return null;
        }
        String text = utterance == null ? "" : utterance.trim();
        if (text.isEmpty()) return null;
        deadline = -1L;
        return text;
    }

    public boolean isAwaitingCommand(long nowMs) {
        return deadline >= 0L && nowMs <= deadline;
    }

    public void reset() {
        deadline = -1L;
    }
}
