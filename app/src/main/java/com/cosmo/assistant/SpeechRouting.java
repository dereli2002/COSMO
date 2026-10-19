package com.cosmo.assistant;

/** Shares routing decisions without calling either native recognizer from the UI thread. */
final class SpeechRouting {
    enum Stage { WAKE, COMMAND, PAUSED }

    static final class Snapshot {
        final Stage stage;
        final long generation;
        private Snapshot(Stage stage, long generation) {
            this.stage = stage;
            this.generation = generation;
        }
    }

    private volatile Snapshot current = new Snapshot(Stage.WAKE, 0L);

    Snapshot snapshot() { return current; }

    boolean isCurrent(Snapshot candidate) { return candidate == current; }

    /** Even the same stage starts a clean decoder generation and invalidates queued audio. */
    synchronized void transition(Stage stage) {
        if (stage == null) throw new IllegalArgumentException("A routing stage is required");
        current = new Snapshot(stage, current.generation + 1L);
    }
}
