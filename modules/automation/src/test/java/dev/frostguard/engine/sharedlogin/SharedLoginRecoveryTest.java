package dev.frostguard.engine.sharedlogin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import dev.frostguard.api.domain.AccountDescriptor;

class SharedLoginRecoveryTest {

    @AfterEach
    void clearCoordinator() {
        SharedLoginHandshakeCoordinator.shared().clear();
    }

    @Test
    void beginOpensActiveHandshakeForMutualPair() {
        AccountDescriptor a = profile(1L, "A", "1", "CharOne", 2L);
        AccountDescriptor b = profile(2L, "B", "2", "CharTwo", 1L);
        Map<Long, AccountDescriptor> byId = Map.of(1L, a, 2L, b);

        SharedLoginPair pair = SharedLoginRecovery.resolvePair(a, byId::get).orElseThrow();
        SharedLoginRecovery.begin(pair);

        assertTrue(SharedLoginHandshakeCoordinator.shared().isHandshakeActive(1L));
        assertTrue(SharedLoginHandshakeCoordinator.shared().isHandshakeActive(2L));
    }

    @Test
    void beginReturnsFalseWhenUnpaired() {
        AccountDescriptor lone = profile(3L, "Lone", "3", "Solo", null);
        assertFalse(SharedLoginRecovery.begin(lone));
        assertFalse(SharedLoginHandshakeCoordinator.shared().isHandshakeActive(3L));
    }

    @Test
    void beginReopensSessionAfterPreviousHandshakeFinished() {
        AccountDescriptor a = profile(1L, "A", "1", "CharOne", 2L);
        AccountDescriptor b = profile(2L, "B", "2", "CharTwo", 1L);
        SharedLoginPair pair = SharedLoginRecovery.resolvePair(a, Map.of(1L, a, 2L, b)::get).orElseThrow();

        SharedLoginHandshakeSession first = SharedLoginRecovery.begin(pair);
        SharedLoginHandshakeCoordinator.shared().finish(first);
        assertFalse(SharedLoginHandshakeCoordinator.shared().isHandshakeActive(1L));

        SharedLoginHandshakeSession second = SharedLoginRecovery.begin(pair);
        assertTrue(SharedLoginHandshakeCoordinator.shared().isHandshakeActive(1L));
        assertTrue(second != first);
    }

    private static AccountDescriptor profile(
            long id, String name, String emu, String characterName, Long peerId) {
        return new AccountDescriptor(
                id, name, emu, true, 50L, 0L, null, characterName, null, null, peerId);
    }
}
