package dev.frostguard.engine.sharedlogin;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

import dev.frostguard.api.domain.AccountDescriptor;

/**
 * Blocks automation for a shared-login pair until both sides have completed a
 * successful handshake on their owned characters. Until then only Initialize may run.
 */
public final class SharedLoginPairGate {

    private static final ConcurrentMap<String, Boolean> READY_BY_PAIR = new ConcurrentHashMap<>();

    private SharedLoginPairGate() {
    }

    /** Test-only reset. */
    public static void clear() {
        READY_BY_PAIR.clear();
    }

    public static void markPending(SharedLoginPair pair) {
        if (pair != null) {
            READY_BY_PAIR.put(pair.sessionKey(), Boolean.FALSE);
        }
    }

    public static void markReady(SharedLoginPair pair) {
        if (pair != null) {
            READY_BY_PAIR.put(pair.sessionKey(), Boolean.TRUE);
        }
    }

    public static boolean isReady(SharedLoginPair pair) {
        if (pair == null) {
            return true;
        }
        return Boolean.TRUE.equals(READY_BY_PAIR.get(pair.sessionKey()));
    }

    /**
     * Unpaired profiles may automate. Paired profiles may automate only after a
     * successful shared-login handshake for that pair.
     */
    public static boolean allowsAutomation(AccountDescriptor profile) {
        Optional<SharedLoginPair> pair = SharedLoginRecovery.resolvePair(profile);
        return pair.map(SharedLoginPairGate::isReady).orElse(true);
    }

    public static boolean allowsAutomation(
            AccountDescriptor profile,
            Function<Long, AccountDescriptor> lookup) {
        return SharedLoginPair.resolve(profile, lookup)
                .map(SharedLoginPairGate::isReady)
                .orElse(true);
    }

    public static Optional<SharedLoginPair> pendingPair(AccountDescriptor profile) {
        Optional<SharedLoginPair> pair = SharedLoginRecovery.resolvePair(profile);
        if (pair.isEmpty() || isReady(pair.get())) {
            return Optional.empty();
        }
        return pair;
    }
}
