package com.cosmo.assistant;

import org.junit.Test;

import static org.junit.Assert.*;

public class WakeWordGateTest {
    @Test public void normalConversationDoesNotTriggerCommands() {
        WakeWordGate gate = new WakeWordGate();
        assertNull(gate.accept("saati söyle", 0));
        assertNull(gate.accept("mikrokozmos saati söyle", 1));
        assertNull(gate.accept("ben cosmo uygulamasını açtım", 2));
    }

    @Test public void acceptsWakeAndCommandInOneFinalUtterance() {
        WakeWordGate gate = new WakeWordGate();
        assertEquals("Saati söyle.", gate.accept("HEY, COSMO! Saati söyle.", 100));
        assertNull(gate.accept("saati söyle", 101));
        assertEquals("feneri aç", gate.accept("kosmo feneri aç", 102));
        assertEquals("geri", gate.accept("kozmo geri", 103));
        assertEquals("ses aç", gate.accept("kozmos ses aç", 104));
        assertEquals("ana ekran", gate.accept("asistan ana ekran", 105));
    }

    @Test public void separateWakeArmsOneUtteranceForTenSeconds() {
        WakeWordGate gate = new WakeWordGate();
        assertNull(gate.accept("cosmo", 1_000));
        assertTrue(gate.isAwaitingCommand(2_000));
        assertNull(gate.accept(" ", 2_100));
        assertEquals("Not al alışveriş", gate.accept("Not al alışveriş", 11_000));
        assertFalse(gate.isAwaitingCommand(11_001));
        assertNull(gate.accept("notları oku", 11_002));
    }

    @Test public void expiredWakeAndResetCannotAuthorizeCommands() {
        WakeWordGate gate = new WakeWordGate();
        gate.accept("asistan", 0);
        assertNull(gate.accept("feneri aç", 10_001));
        gate.accept("hey kosmo", 20_000);
        gate.reset();
        assertNull(gate.accept("feneri aç", 20_001));
        assertNull(gate.accept(null, 20_002));
    }

    @Test public void wakeCanBeRepeatedWithoutBecomingACommand() {
        WakeWordGate gate = new WakeWordGate();
        gate.accept("cosmo", 0);
        assertNull(gate.accept("cosmo", 5_000));
        assertEquals("tarih", gate.accept("tarih", 14_000));
    }

    @Test public void wakePreservesClockPunctuationAndOriginalBody() {
        WakeWordGate gate = new WakeWordGate();
        assertEquals("07:30 alarm kur", gate.accept("COSMO, 07:30 alarm kur", 0));
        assertEquals("not al İstanbul, saat 8!", gate.accept("asistan, not al İstanbul, saat 8!", 1));
        gate.accept("asistan", 2);
        assertEquals("Not al: İstanbul, 07:30!", gate.accept(" Not al: İstanbul, 07:30! ", 3));
    }
}
