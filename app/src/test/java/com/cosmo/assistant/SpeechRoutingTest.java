package com.cosmo.assistant;

import org.junit.Test;

import static org.junit.Assert.*;

public class SpeechRoutingTest {
    @Test public void wakeAudioCannotBecomeACommandAcrossAcknowledgement() {
        SpeechRouting route = new SpeechRouting();
        SpeechRouting.Snapshot wakeAudio = route.snapshot();
        assertEquals(SpeechRouting.Stage.WAKE, wakeAudio.stage);
        route.transition(SpeechRouting.Stage.PAUSED);
        SpeechRouting.Snapshot chimeAudio = route.snapshot();
        route.transition(SpeechRouting.Stage.COMMAND);
        assertFalse(route.isCurrent(wakeAudio));
        assertFalse(route.isCurrent(chimeAudio));
        assertTrue(route.isCurrent(route.snapshot()));
        assertEquals(SpeechRouting.Stage.COMMAND, route.snapshot().stage);
    }

    @Test public void staleCommandResultCannotExecuteAfterTimeoutOrTts() {
        SpeechRouting route = new SpeechRouting();
        route.transition(SpeechRouting.Stage.COMMAND);
        SpeechRouting.Snapshot pendingCommand = route.snapshot();
        route.transition(SpeechRouting.Stage.PAUSED);
        assertFalse(route.isCurrent(pendingCommand));
        SpeechRouting.Snapshot speechAudio = route.snapshot();
        route.transition(SpeechRouting.Stage.WAKE);
        assertFalse(route.isCurrent(pendingCommand));
        assertFalse(route.isCurrent(speechAudio));
    }

    @Test public void laterWakeSessionCannotAcceptEarlierWakeOrCommandResults() {
        SpeechRouting route = new SpeechRouting();
        SpeechRouting.Snapshot oldWake = route.snapshot();
        route.transition(SpeechRouting.Stage.COMMAND);
        SpeechRouting.Snapshot oldCommand = route.snapshot();
        route.transition(SpeechRouting.Stage.WAKE);
        route.transition(SpeechRouting.Stage.COMMAND);
        assertFalse(route.isCurrent(oldWake));
        assertFalse(route.isCurrent(oldCommand));
    }

    @Test public void sameStageResetInvalidatesPendingDecoderWork() {
        SpeechRouting route = new SpeechRouting();
        SpeechRouting.Snapshot oldWake = route.snapshot();
        route.transition(SpeechRouting.Stage.WAKE);
        assertFalse(route.isCurrent(oldWake));
        assertTrue(route.snapshot().generation > oldWake.generation);
    }
}
