package com.cosmo.assistant;

import org.junit.Test;

import static org.junit.Assert.*;

public class WakeWordGateTest {
    @Test public void onlyCompleteStandaloneFeriWithConfidenceIsWake() {
        assertTrue(WakeWordGate.isFeriWake("ferry", 1.0));
        assertTrue(WakeWordGate.isFeriWake(" FERRY ", 0.65));
        assertFalse(WakeWordGate.isFeriWake("ferry", 0.649));
        assertFalse(WakeWordGate.isFeriWake("ferry", Double.NaN));
        assertFalse(WakeWordGate.isFeriWake("ferry", Double.POSITIVE_INFINITY));
        assertFalse(WakeWordGate.isFeriWake("ferry", 1.01));
        assertFalse(WakeWordGate.isFeriWake(null, 1.0));
    }

    @Test public void noFormerWakeAliasesOrCombinedPhrasesAreWake() {
        for (String text : new String[]{"asistan", "cosma", "cosmo", "kosmo", "kozmo", "kozmos", "perry",
                "hey ferry", "ferry feneri aç", "ben ferry uygulamasını açtım", "[unk] ferry"}) {
            assertFalse(text, WakeWordGate.isFeriWake(text, 1.0));
        }
    }

    @Test public void normalConversationCannotAuthorizeCommand() {
        WakeWordGate gate = new WakeWordGate();
        assertNull(gate.acceptCommand("saati söyle", 0));
        assertNull(gate.acceptCommand("feri", 1));
        assertNull(gate.acceptCommand("asistan feneri aç", 2));
    }

    @Test public void acknowledgementArmsExactlyOneUtteranceForTenSeconds() {
        WakeWordGate gate = new WakeWordGate();
        gate.armFeri(1_000);
        assertTrue(gate.isAwaitingCommand(2_000));
        assertNull(gate.acceptCommand(" ", 2_100));
        assertNull(gate.acceptCommand(null, 2_200));
        assertEquals("Not al alışveriş", gate.acceptCommand("Not al alışveriş", 11_000));
        assertFalse(gate.isAwaitingCommand(11_001));
        assertNull(gate.acceptCommand("notları oku", 11_002));
    }

    @Test public void expiredWakeAndResetCannotAuthorizeCommands() {
        WakeWordGate gate = new WakeWordGate();
        gate.armFeri(0);
        assertNull(gate.acceptCommand("feneri aç", 10_001));
        gate.armFeri(20_000);
        gate.reset();
        assertNull(gate.acceptCommand("feneri aç", 20_001));
    }

    @Test public void consumesCommandWithoutRemovingNameOrChangingPayload() {
        WakeWordGate gate = new WakeWordGate();
        gate.armFeri(0);
        assertEquals("Not al: FERİ, İstanbul, 07:30!",
                gate.acceptCommand(" Not al: FERİ, İstanbul, 07:30! ", 3));
    }
}
