package dev.frostguard.engine.sharedlogin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import dev.frostguard.api.domain.AccountDescriptor;

class SharedLoginPairTest {

    @Test
    void resolvesMutualPairOnDifferentEmulators() {
        AccountDescriptor a = profile(1L, "A", "1", "CharOne", 2L);
        AccountDescriptor b = profile(2L, "B", "2", "CharTwo", 1L);
        Map<Long, AccountDescriptor> byId = Map.of(1L, a, 2L, b);

        Optional<SharedLoginPair> pair = SharedLoginPair.resolve(a, byId::get);

        assertTrue(pair.isPresent());
        assertTrue(pair.get().contains(1L));
        assertTrue(pair.get().contains(2L));
    }

    @Test
    void rejectsNonMutualPeerPointer() {
        AccountDescriptor a = profile(1L, "A", "1", "CharOne", 2L);
        AccountDescriptor b = profile(2L, "B", "2", "CharTwo", null);
        Map<Long, AccountDescriptor> byId = Map.of(1L, a, 2L, b);

        assertTrue(SharedLoginPair.resolve(a, byId::get).isEmpty());
    }

    @Test
    void rejectsSameEmulator() {
        AccountDescriptor a = profile(1L, "A", "3", "CharOne", 2L);
        AccountDescriptor b = profile(2L, "B", "3", "CharTwo", 1L);
        Map<Long, AccountDescriptor> byId = Map.of(1L, a, 2L, b);

        assertTrue(SharedLoginPair.resolve(a, byId::get).isEmpty());
    }

    @Test
    void rejectsMissingCharacterIdentity() {
        AccountDescriptor a = profile(1L, "A", "1", "CharOne", 2L);
        AccountDescriptor b = profile(2L, "B", "2", null, 1L);
        Map<Long, AccountDescriptor> byId = Map.of(1L, a, 2L, b);

        assertTrue(SharedLoginPair.resolve(a, byId::get).isEmpty());
    }

    @Test
    void rejectsUnpairedProfile() {
        AccountDescriptor a = profile(1L, "A", "1", "CharOne", null);
        assertTrue(SharedLoginPair.resolve(a, id -> null).isEmpty());
        assertFalse(SharedLoginHandshakeCoordinator.shared().isHandshakeActive(1L));
    }

    private static AccountDescriptor profile(
            long id, String name, String emu, String characterName, Long peerId) {
        AccountDescriptor profile = new AccountDescriptor(
                id, name, emu, true, 50L, 0L, null, characterName, null, null, peerId);
        return profile;
    }
}
