package dev.frostguard.engine.sharedlogin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import dev.frostguard.api.domain.AccountDescriptor;

class SharedLoginPairGateTest {

    @AfterEach
    void clear() {
        SharedLoginPairGate.clear();
    }

    @Test
    void pairedProfilesBlockAutomationUntilMarkedReady() {
        AccountDescriptor a = profile(1L, "A", "1", "CharOne", 2L);
        AccountDescriptor b = profile(2L, "B", "2", "CharTwo", 1L);
        Map<Long, AccountDescriptor> byId = Map.of(1L, a, 2L, b);
        SharedLoginPair pair = SharedLoginPair.resolve(a, byId::get).orElseThrow();

        assertFalse(SharedLoginPairGate.allowsAutomation(a, byId::get),
                "paired profiles start blocked until handshake succeeds");
        SharedLoginPairGate.markPending(pair);
        assertFalse(SharedLoginPairGate.allowsAutomation(a, byId::get));
        assertFalse(SharedLoginPairGate.allowsAutomation(b, byId::get));

        SharedLoginPairGate.markReady(pair);
        assertTrue(SharedLoginPairGate.allowsAutomation(a, byId::get));
        assertTrue(SharedLoginPairGate.allowsAutomation(b, byId::get));
    }

    @Test
    void unpairedProfileMayAutomate() {
        AccountDescriptor lone = profile(3L, "Lone", "3", "Solo", null);
        assertTrue(SharedLoginPairGate.allowsAutomation(lone, id -> null));
    }

    private static AccountDescriptor profile(
            long id, String name, String emu, String characterName, Long peerId) {
        return new AccountDescriptor(
                id, name, emu, true, 50L, 0L, null, characterName, null, null, peerId);
    }
}
